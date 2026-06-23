package com.ailux.api

import com.ailux.api.concurrency.ConcurrencyCoordinator
import com.ailux.api.context.DefaultLLMContextManager
import com.ailux.api.context.EstimatedTokenCounter
import com.ailux.api.context.resolveContextWindow
import com.ailux.core.stream.StallState
import com.ailux.core.stream.stallDetection
import com.ailux.api.task.DefaultLLMTask
import com.ailux.core.AiluxSdk
import com.ailux.core.config.ContextConfig
import com.ailux.core.config.ModelConfig
import com.ailux.core.context.LLMContextManager
import com.ailux.core.diagnostics.DiagnosticReport
import com.ailux.core.diagnostics.DiagnosticsRecorder
import com.ailux.core.diagnostics.Outcome
import com.ailux.core.diagnostics.PrivacyConfigSnapshot
import com.ailux.core.diagnostics.TimingMetrics
import com.ailux.core.error.ErrorCode
import com.ailux.core.error.LLMError
import com.ailux.core.error.LLMException
import com.ailux.core.event.LLMEvent
import com.ailux.core.logging.LogLevel
import com.ailux.core.logging.internal.RedactingLogSink
import com.ailux.core.message.Message
import com.ailux.core.request.ContextPolicy
import com.ailux.core.request.LLMRequest
import com.ailux.core.response.LLMResponse
import com.ailux.core.response.UsageInfo
import com.ailux.core.session.Session
import com.ailux.core.session.SessionConfig
import com.ailux.core.session.SessionSnapshot
import com.ailux.core.state.ConnectingPhase
import com.ailux.core.state.LLMTaskState
import com.ailux.core.task.LLMTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * Concurrent LLM client.
 *
 * A single [AiluxClient] instance holds one configured [com.ailux.core.LLMProvider]
 * and supports N concurrent tasks. Each call to [streamGenerate] returns an
 * independent [LLMTask] handle with its own state, events, and cancel capability.
 *
 * Concurrency behavior is governed by [AiluxConfig.sessionConcurrencyPolicy]:
 * - PARALLEL (default): all sessions / tasks run simultaneously, subject to
 *   the provider's `ProviderCapabilities.maxConcurrentSessions` cap.
 * - CANCEL_PREVIOUS: new task auto-cancels in-flight tasks.
 * - ENQUEUE: tasks execute one at a time in FIFO order.
 * - REJECT: new task is rejected if one is already active.
 *
 * Ordering of multiple messages **within a single session** is a separate
 * concern, controlled by
 * [com.ailux.core.session.SessionConfig.messageConcurrencyPolicy] when
 * the session is opened.
 *
 * Lifecycle:
 * - After construction, [streamGenerate] / [generate] can be called immediately.
 * - Use [cancelAll] to cancel all in-flight tasks.
 * - Call [release] when the client is no longer needed.
 *
 * ```kotlin
 * val client = AiluxClient(config)
 *
 * // Streaming generation — returns per-request handle
 * val task = client.streamGenerate(request)
 * task.state.collect { ... }   // observe state
 * task.events.collect { ... }  // collect events (starts the request)
 * task.cancel()                // cancel this task only
 *
 * // Non-streaming generation
 * val response = client.generate(request)
 * ```
 */
class AiluxClient(
    val config: AiluxConfig,
) {

    // ──────────────────── Internal State ────────────────────

    /**
     * Concurrency coordinator: enforces the user-configured
     * [com.ailux.core.concurrency.SessionConcurrencyPolicy] across all
     * sessions / stateless calls, capped by the provider's reported
     * [com.ailux.core.capabilities.ProviderCapabilities.maxConcurrentSessions].
     *
     * When PARALLEL exceeds capability, new tasks soft-degrade to ENQUEUE
     * with a one-time WARN log — never throws on policy/capability mismatch.
     */
    private val coordinator = ConcurrencyCoordinator(
        policy = config.sessionConcurrencyPolicy,
        maxConcurrentSessions = config.provider.capabilities.maxConcurrentSessions,
        logger = config.logger,
    )

    /**
     * Privacy-aware log sink. Every SDK-internal log call goes through this
     * mediator, which applies the active [com.ailux.core.privacy.PrivacyConfig]
     * before forwarding to the user-supplied [com.ailux.core.logging.AiluxLogger].
     */
    internal val logSink: RedactingLogSink = RedactingLogSink(
        delegate = config.logger,
        privacy = config.privacy,
    )

    /**
     * Ring buffer of the most recent finished task diagnostics, capped at
     * [DIAGNOSTIC_HISTORY_SIZE]. Used by [createDiagnosticReport] to assemble
     * session-level reports.
     */
    private val recentDiagnostics: ArrayDeque<DiagnosticReport> = ArrayDeque(DIAGNOSTIC_HISTORY_SIZE)

    /** Whether the client has been released (terminal state). */
    @Volatile
    private var released = false

    // ──────────────────── Public API ────────────────────

    /**
     * Streaming generation: returns a per-request [LLMTask] handle.
     *
     * The returned task's [LLMTask.events] flow is cold — the actual network
     * request is dispatched only when collection starts. This preserves structured
     * concurrency: the task lives within the collector's coroutine scope.
     *
     * Pipeline (per-task):
     * 1. Concurrency coordination (policy check via [ConcurrencyCoordinator]).
     * 2. Context management (resolve effective manager, trim messages if over budget).
     * 3. Provider stream → stall detection → state reduction → emit events.
     *
     * @param request the LLM request payload.
     * @return an [LLMTask] handle for observing and controlling this request.
     * @throws IllegalStateException if the client has been [release]d.
     */
    fun streamGenerate(request: LLMRequest): LLMTask {
        checkNotReleased()

        val taskState = MutableStateFlow<LLMTaskState>(LLMTaskState.Idle)
        var taskJob: Job? = null

        // Per-task diagnostics accumulator. Receives lifecycle calls from
        // every branch below, including the early concurrency-rejection path.
        val recorder = DiagnosticsRecorder(
            taskId = request.requestId,
            sdkVersion = AiluxSdk.VERSION,
            providerName = config.provider::class.simpleName ?: "LLMProvider",
            modelName = config.modelConfig?.name,
            privacy = config.privacy,
        )

        val events: Flow<LLMEvent> = flow {
            coroutineScope {
                val job = coroutineContext[Job]!!
                taskJob = job

                // ─── Step 1: Concurrency coordination ───
                recorder.onStart()
                val allowed = coordinator.onTaskStart(
                    taskId = request.requestId,
                    job = job,
                    onQueued = { position ->
                        taskState.value = LLMTaskState.Queued(position)
                    }
                )

                if (!allowed) {
                    val err = LLMError(
                        code = ErrorCode.CONCURRENT_REQUEST_REJECTED,
                        message = "Request rejected: a task is already active (policy=REJECT)"
                    )
                    taskState.value = LLMTaskState.Failed(err)
                    logSink.logSafe(
                        LogLevel.WARN,
                        TAG,
                        "task ${request.requestId} rejected by concurrency policy",
                    )
                    recorder.onFailure(err)
                    archiveDiagnostic(recorder)
                    emit(LLMEvent.Error(err))
                    emit(LLMEvent.Done())
                    return@coroutineScope
                }

                try {
                    taskState.value = LLMTaskState.Connecting(
                        phase = ConnectingPhase.ESTABLISHING
                    )
                    logSink.logSafe(
                        LogLevel.DEBUG,
                        TAG,
                        "task ${request.requestId} starting (provider=${config.provider::class.simpleName})",
                    )

                    // ─── Step 2: Context management ───
                    val messages = request.messages
                    val effectiveMessages = resolveMessages(messages, request.contextPolicy)

                    // ─── Step 3: Provider stream → stall detection → state reduction ───
                    var tokenCount = 0
                    var latestUsage: UsageInfo? = null

                    config.provider.streamGenerate(request.copy(messages = effectiveMessages))
                        .stallDetection(config.streamConfig) { stall ->
                            applyStall(taskState, stall)
                        }
                        .collect { event ->
                            // First content-bearing event marks TTFT.
                            if (event is LLMEvent.Token ||
                                event is LLMEvent.Reasoning ||
                                event is LLMEvent.ToolCallDelta ||
                                event is LLMEvent.ToolCallReceived
                            ) {
                                recorder.onFirstContent()
                            }
                            reduceState(taskState, event, tokenCount).also { newCount ->
                                tokenCount = newCount
                            }
                            if (event is LLMEvent.Usage) {
                                latestUsage = event.info
                            }
                            emit(event)
                        }

                    // Stream ended normally (after Done event from provider).
                    if (taskState.value !is LLMTaskState.Failed) {
                        taskState.value = LLMTaskState.Completed(latestUsage)
                        logSink.logSafe(
                            LogLevel.DEBUG,
                            TAG,
                            "task ${request.requestId} completed (tokens=$tokenCount)",
                        )
                        recorder.onSuccess()
                    } else {
                        // State already set to Failed via reduceState; record
                        // the matching outcome from the LLMEvent.Error.
                        val err = (taskState.value as LLMTaskState.Failed).error
                        recorder.onFailure(err)
                    }
                } catch (e: CancellationException) {
                    taskState.value = LLMTaskState.Idle
                    logSink.logSafe(
                        LogLevel.DEBUG,
                        TAG,
                        "task ${request.requestId} cancelled",
                    )
                    recorder.onCancel()
                    throw e
                } catch (e: Exception) {
                    val error = LLMError(
                        code = ErrorCode.UNKNOWN,
                        message = e.message ?: "Unknown error",
                        cause = e,
                    )
                    taskState.value = LLMTaskState.Failed(error)
                    logSink.logSafe(
                        LogLevel.WARN,
                        TAG,
                        "task ${request.requestId} failed: ${error.code} ${error.message}",
                        e,
                    )
                    recorder.onFailure(error)
                    emit(LLMEvent.Error(error))
                    emit(LLMEvent.Done())
                } finally {
                    // ─── Step 4: Always release coordinator resources ───
                    coordinator.onTaskEnd(request.requestId)
                    archiveDiagnostic(recorder)
                }
            }
        }

        return DefaultLLMTask(
            id = request.requestId,
            events = events,
            state = taskState.asStateFlow(),
            onCancel = { taskJob?.cancel() },
            recorder = recorder,
        )
    }

    /**
     * Non-streaming generation: suspends until the full response is ready.
     *
     * Also subject to [SessionConcurrencyPolicy][com.ailux.core.concurrency.SessionConcurrencyPolicy]
     * coordination. Cancellation is handled via the calling coroutine's scope.
     *
     * @param request the LLM request payload.
     * @return the complete response.
     * @throws LLMException if the request is rejected by concurrency policy or fails.
     * @throws CancellationException if cancelled via coroutine or [cancelAll].
     * @throws IllegalStateException if the client has been [release]d.
     */
    suspend fun generate(request: LLMRequest): LLMResponse {
        checkNotReleased()

        return coroutineScope {
            val job = coroutineContext[Job]!!

            val allowed = coordinator.onTaskStart(
                taskId = request.requestId,
                job = job,
                onQueued = { /* Non-streaming: no UI state to update */ }
            )

            if (!allowed) {
                throw LLMException(
                    LLMError(
                        code = ErrorCode.CONCURRENT_REQUEST_REJECTED,
                        message = "Request rejected: a task is already active (policy=REJECT)"
                    )
                )
            }

            try {
                config.provider.generate(request)
            } finally {
                coordinator.onTaskEnd(request.requestId)
            }
        }
    }

    // ──────────────────── Session API (since v0.3.0) ────────────────────

    /**
     * Open a fresh stateful [Session] on this client.
     *
     * The returned session holds conversation state across turns — native
     * KV-cache for local engines that support it, or a client-side history
     * accumulator for cloud / proxy providers. Application code interacts
     * with the same API in either case.
     *
     * Sessions are independent: closing one does not affect others. The
     * client-level [config.sessionConcurrencyPolicy] still governs how
     * multiple sessions are scheduled against the provider.
     *
     * @param sessionConfig per-session config (system instruction, initial
     *                      history, message ordering policy, sampler defaults).
     * @return an open [Session]; the caller MUST eventually call
     *         [Session.close] (preferably via Kotlin's `use { }` block).
     * @throws IllegalStateException if the client has been [release]d.
     * @throws UnsupportedOperationException if the provider has not yet been
     *         migrated to the session-first API.
     */
    fun openSession(sessionConfig: SessionConfig = SessionConfig()): Session {
        checkNotReleased()
        return config.provider.openSession(sessionConfig)
    }

    /**
     * Restore a [Session] from a previously captured [SessionSnapshot].
     *
     * Logical state (history + config) is restored exactly. Native KV-cache
     * is NOT in the snapshot and will be lazily rebuilt on the first call
     * to [Session.streamGenerate].
     *
     * @throws IllegalStateException if the client has been [release]d.
     * @throws UnsupportedOperationException if the provider does not yet
     *         support session restore.
     */
    fun restoreSession(snapshot: SessionSnapshot): Session {
        checkNotReleased()
        return config.provider.restoreSession(snapshot)
    }

    /**
     * Cancel all in-flight tasks on this client.
     *
     * Each active task receives a [CancellationException], which triggers:
     * - Immediate closure of the provider connection (SSE disconnect / HTTP call abort).
     * - No further [LLMEvent] emissions after the cancel point.
     * - State transition to [LLMTaskState.Idle].
     * - [com.ailux.core.diagnostics.Outcome.Cancelled] recorded in the task's diagnostic report.
     *
     * ## Billing Boundary
     *
     * Cancellation severs the client↔backend connection but does **not** guarantee
     * that the upstream LLM provider stops inference or billing. Tokens already
     * generated before the disconnect are typically still billed. The recommended
     * backend pattern is "client-disconnect = abort upstream request" (demonstrated
     * in the Ailux backend-sample), which minimizes — but cannot eliminate — post-cancel
     * billing.
     *
     * @see LLMTask.cancel for per-task cancellation.
     */
    fun cancelAll() {
        coordinator.cancelAll()
    }

    /**
     * Release resources held by this client.
     * Cancels all active tasks and marks the client as unusable.
     * After release, [streamGenerate] / [generate] throw IllegalStateException.
     */
    fun release() {
        released = true
        cancelAll()
    }

    // ──────────────────── Context Management ────────────────────

    /**
     * Resolve the effective messages after context management processing.
     *
     * Pipeline:
     * 1. Resolve the effective [LLMContextManager] by merging global config with
     *    per-request [ContextPolicy] (if present).
     * 2. If a context manager is active and messages exceed the token budget,
     *    trim messages and emit [LLMEvent.ContextTrimmed].
     * 3. If no context manager is active, perform a passive pre-check: warn if
     *    estimated tokens exceed the model's context window.
     *
     * @param messages the original message list from the request.
     * @param override optional per-request context overrides.
     * @return the (potentially trimmed) message list to send to the provider.
     */
    private suspend fun FlowCollector<LLMEvent>.resolveMessages(
        messages: List<Message>,
        override: ContextPolicy?
    ): List<Message> {
        val effectiveContextManager = resolveContextManager(override)

        // Path A: Context manager is active — trim if over budget.
        if (effectiveContextManager != null && messages.isNotEmpty()) {
            val budget = resolveBudget(config.modelConfig)
            val effectiveAggressiveness = override?.aggressiveness
                ?: config.trimAggressiveness
            val contextConfig = ContextConfig(
                budget = budget,
                aggressiveness = effectiveAggressiveness
            )

            val result = effectiveContextManager.process(messages, contextConfig)

            if (result.removed.isNotEmpty()) {
                emit(
                    LLMEvent.ContextTrimmed(
                        result.removed.size,
                        result.estimatedTokensSaved
                    )
                )
            }
            return result.messages
        }

        // Path B: No context manager — passive pre-check warning.
        if (effectiveContextManager == null && messages.isNotEmpty()) {
            val estimated = EstimatedTokenCounter().count(messages)
            val window = resolveContextWindow(config.modelConfig)
            if (estimated > window) {
                emit(
                    LLMEvent.ContextTrimmed(
                        removedCount = 0,
                        estimatedTokensSaved = 0
                    )
                )
            }
        }

        return messages
    }

    /**
     * Resolve the effective [LLMContextManager] by merging the global config
     * with per-request [ContextPolicy].
     *
     * Resolution rules:
     * - No override → use global [AiluxConfig.contextManager] as-is.
     * - Override present + global is [DefaultLLMContextManager] → create a new instance
     *   with overridden components (tokenCounter / trimStrategy / protector).
     * - Override present + global is custom (non-default) → cannot apply field-level
     *   overrides; fall back to the global manager unchanged.
     * - Global is null → context management disabled (returns null).
     */
    private fun resolveContextManager(override: ContextPolicy?): LLMContextManager? {
        val base = config.contextManager ?: return null

        if (override == null) return base

        return if (base is DefaultLLMContextManager) {
            DefaultLLMContextManager(
                tokenCounter = override.tokenCounter ?: base.tokenCounter,
                trimStrategy = override.strategy ?: base.trimStrategy,
                protector = override.protector ?: base.protector
            )
        } else {
            // Non-default context manager: per-request overrides not applicable.
            base
        }
    }

    /**
     * Compute the token budget available for input messages.
     *
     * Formula: contextWindow - reserveForReply.
     * - contextWindow is resolved via [resolveContextWindow]
     *   (ModelConfig > ModelRegistry > 128K fallback).
     * - reserveForReply defaults to 4096 if not specified in [ModelConfig].
     *
     * @param modelConfig optional model configuration carrying explicit overrides.
     * @return the token budget that the context manager should trim to.
     */
    private fun resolveBudget(modelConfig: ModelConfig?): Int {
        val contextWindow = resolveContextWindow(modelConfig)
        val reserveForReply = modelConfig?.reserveForReply ?: 4096
        return contextWindow - reserveForReply
    }

    // ──────────────────── State Reduction ────────────────────

    /**
     * Maps an incoming [LLMEvent] to the appropriate [LLMTaskState] transition.
     *
     * State machine:
     * - Connected       → Connecting(WAITING_FIRST_TOKEN)
     * - First progress  → Streaming(tokenCount=1)
     * - Subsequent progress → Streaming(tokenCount++)
     * - Error           → Failed
     * - StallDetected   → (handled by [applyStall], no transition here)
     * - Done            → (no-op here; Completed is set after collect returns)
     *
     * @return updated token count.
     */
    private fun reduceState(
        taskState: MutableStateFlow<LLMTaskState>,
        event: LLMEvent,
        currentTokenCount: Int
    ): Int {
        return when (event) {
            is LLMEvent.Connected -> {
                taskState.value = LLMTaskState.Connecting(
                    phase = ConnectingPhase.WAITING_FIRST_TOKEN
                )
                currentTokenCount
            }
            is LLMEvent.Token, is LLMEvent.Reasoning, is LLMEvent.ToolCallDelta -> {
                val newCount = currentTokenCount + 1
                taskState.value = LLMTaskState.Streaming(tokenCount = newCount)
                newCount
            }
            is LLMEvent.Error -> {
                taskState.value = LLMTaskState.Failed(event.error)
                currentTokenCount
            }
            is LLMEvent.StallDetected -> {
                // Stall state is handled by applyStall callback; no transition here.
                currentTokenCount
            }
            else -> {
                // Usage / ToolCallReceived / Done / ContextTrimmed: no state change.
                currentTokenCount
            }
        }
    }

    /**
     * Applies stall detection state to the task's StateFlow.
     *
     * When stalled=true: overlays `stalled` and `idleMillis` onto the current state.
     * When stalled=false (recovery): clears the stall flags while preserving other fields.
     */
    private fun applyStall(
        taskState: MutableStateFlow<LLMTaskState>,
        stall: StallState
    ) {
        val current = taskState.value
        taskState.value = when {
            stall.stalled && current is LLMTaskState.Connecting -> {
                current.copy(stalled = true, elapsedMillis = stall.idleMillis)
            }
            stall.stalled && current is LLMTaskState.Streaming -> {
                current.copy(stalled = true, idleMillis = stall.idleMillis)
            }
            !stall.stalled && current is LLMTaskState.Connecting -> {
                current.copy(stalled = false, elapsedMillis = 0)
            }
            !stall.stalled && current is LLMTaskState.Streaming -> {
                current.copy(stalled = false, idleMillis = 0)
            }
            else -> current  // Shouldn't happen; defensive no-op.
        }
    }

    // ──────────────────── Utilities ────────────────────

    /** Verify that the client has not been released. */
    private fun checkNotReleased() {
        check(!released) { "AiluxClient has been release()d and can no longer be used." }
    }

    // ──────────────────── Diagnostics ────────────────────

    /**
     * Stores a finished task's diagnostic in the per-client ring buffer.
     * No-op while the task is still in flight.
     */
    private fun archiveDiagnostic(recorder: DiagnosticsRecorder) {
        val finished = recorder.lastReport() ?: return
        synchronized(recentDiagnostics) {
            recentDiagnostics.addFirst(finished)
            while (recentDiagnostics.size > DIAGNOSTIC_HISTORY_SIZE) {
                recentDiagnostics.removeLast()
            }
        }
    }

    /**
     * Builds a session-level [DiagnosticReport] for this client, capturing
     * the SDK version, active privacy snapshot, and the most-recent finished
     * task reports (newest first).
     *
     * The report contains no prompt or response content and is safe to attach
     * to a public bug report.
     *
     * @param includeRecentTasks how many recent task reports to embed (capped
     *                           at the ring buffer size [DIAGNOSTIC_HISTORY_SIZE]).
     * @return a session-level diagnostic report. [DiagnosticReport.taskId] is
     *         `null`; [DiagnosticReport.outcome] is [Outcome.Pending].
     */
    public fun createDiagnosticReport(includeRecentTasks: Int = 5): DiagnosticReport {
        require(includeRecentTasks >= 0) {
            "includeRecentTasks must be non-negative; got $includeRecentTasks"
        }
        val snapshot = synchronized(recentDiagnostics) {
            recentDiagnostics.take(includeRecentTasks.coerceAtMost(DIAGNOSTIC_HISTORY_SIZE))
        }
        return DiagnosticReport(
            sdkVersion = AiluxSdk.VERSION,
            timestamp = System.currentTimeMillis(),
            taskId = null,
            provider = config.provider::class.simpleName ?: "LLMProvider",
            model = config.modelConfig?.name,
            timing = TimingMetrics.EMPTY,
            outcome = Outcome.Pending,
            retries = emptyList(),
            privacy = PrivacyConfigSnapshot.of(config.privacy),
            recentTasks = snapshot,
        )
    }

    private companion object {
        private const val TAG = "Ailux/Client"

        /** Maximum number of finished task diagnostics retained per client. */
        private const val DIAGNOSTIC_HISTORY_SIZE = 16
    }
}
