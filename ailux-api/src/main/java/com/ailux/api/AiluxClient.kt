package com.ailux.api

import com.ailux.api.concurrency.ConcurrencyCoordinator
import com.ailux.api.context.DefaultLLMContextManager
import com.ailux.api.context.EstimatedTokenCounter
import com.ailux.api.context.resolveContextWindow
import com.ailux.core.stream.StallState
import com.ailux.core.stream.stallDetection
import com.ailux.api.task.DefaultLLMTask
import com.ailux.core.config.ContextConfig
import com.ailux.core.config.ModelConfig
import com.ailux.core.context.LLMContextManager
import com.ailux.core.error.ErrorCode
import com.ailux.core.error.LLMError
import com.ailux.core.error.LLMException
import com.ailux.core.event.LLMEvent
import com.ailux.core.message.Message
import com.ailux.core.request.ContextPolicy
import com.ailux.core.request.LLMRequest
import com.ailux.core.response.LLMResponse
import com.ailux.core.response.UsageInfo
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
 * Concurrency behavior is governed by [AiluxConfig.concurrencyPolicy]:
 * - PARALLEL (default): all tasks run simultaneously.
 * - CANCEL_PREVIOUS: new task auto-cancels in-flight tasks.
 * - ENQUEUE: tasks execute one at a time in FIFO order.
 * - REJECT: new task is rejected if one is already active.
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

    /** Concurrency coordinator: enforces the configured policy across all tasks. */
    private val coordinator = ConcurrencyCoordinator(config.concurrencyPolicy)

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

        val events: Flow<LLMEvent> = flow {
            coroutineScope {
                val job = coroutineContext[Job]!!
                taskJob = job

                // ─── Step 1: Concurrency coordination ───
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
                    emit(LLMEvent.Error(err))
                    emit(LLMEvent.Done())
                    return@coroutineScope
                }

                try {
                    taskState.value = LLMTaskState.Connecting(
                        phase = ConnectingPhase.ESTABLISHING
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
                    }
                } catch (e: CancellationException) {
                    taskState.value = LLMTaskState.Idle
                    throw e
                } catch (e: Exception) {
                    val error = LLMError(
                        code = ErrorCode.UNKNOWN,
                        message = e.message ?: "Unknown error",
                        cause = e,
                    )
                    taskState.value = LLMTaskState.Failed(error)
                    emit(LLMEvent.Error(error))
                    emit(LLMEvent.Done())
                } finally {
                    // ─── Step 4: Always release coordinator resources ───
                    coordinator.onTaskEnd(request.requestId)
                }
            }
        }

        return DefaultLLMTask(
            id = request.requestId,
            events = events,
            state = taskState.asStateFlow(),
            onCancel = { taskJob?.cancel() }
        )
    }

    /**
     * Non-streaming generation: suspends until the full response is ready.
     *
     * Also subject to [ConcurrencyPolicy][com.ailux.core.concurrency.ConcurrencyPolicy]
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

    /**
     * Cancel all in-flight tasks on this client.
     * Each task transitions to Idle via CancellationException handling.
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
}
