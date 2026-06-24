package com.ailux.api.session

import com.ailux.api.AiluxConfig
import com.ailux.api.concurrency.ConcurrencyCoordinator
import com.ailux.api.context.DefaultLLMContextManager
import com.ailux.api.context.EstimatedTokenCounter
import com.ailux.api.context.resolveContextWindow
import com.ailux.api.task.DefaultLLMTask
import com.ailux.core.AiluxSdk
import com.ailux.core.config.ContextConfig
import com.ailux.core.config.ModelConfig
import com.ailux.core.context.LLMContextManager
import com.ailux.core.diagnostics.DiagnosticReport
import com.ailux.core.diagnostics.DiagnosticsRecorder
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
import com.ailux.core.state.ConnectingPhase
import com.ailux.core.state.LLMTaskState
import com.ailux.core.stream.StallState
import com.ailux.core.stream.stallDetection
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
 * The Session-side equivalent of the legacy `AiluxClient.streamGenerate(...)`
 * pipeline.
 *
 * Originally `AiluxClient.streamGenerate(LLMRequest)` wrapped the raw provider
 * stream with three concerns:
 *
 * 1. **Context management** — resolve effective [LLMContextManager], trim
 *    messages if over budget, emit [LLMEvent.ContextTrimmed].
 * 2. **Stall detection** — overlay [LLMEvent.StallDetected] events and update
 *    a [LLMTaskState] StateFlow with stall flags.
 * 3. **Diagnostics** — drive a [DiagnosticsRecorder] from start to terminal
 *    state and archive its report into the per-client ring buffer.
 *
 * As of v0.3.0b ([ADR-0009](../../../../../../../../../ailux-docs/decisions/adr/0009-session-only-single-pipeline.md))
 * the `streamGenerate` / `generate` entry points have been removed from
 * [com.ailux.api.AiluxClient]; the same three concerns now live on top of
 * the Session abstraction. This class hosts that pipeline, so that
 * [PipelinedSession] (returned from `AiluxClient.openSession(...)`) can
 * apply it uniformly to both the streaming and the non-streaming Session
 * methods.
 *
 * Unlike a bare [com.ailux.core.session.Session], a pipelined Session
 * deliberately depends on the **API layer**: it needs the
 * [com.ailux.core.context.LLMContextManager], the
 * [com.ailux.api.concurrency.ConcurrencyCoordinator] and the
 * [DiagnosticsRecorder] ring buffer that [com.ailux.api.AiluxClient]
 * already owns. We thread those in via this helper instead of pushing them
 * down into `core`.
 *
 * **Concurrency.** The pipeline does **not** invoke
 * [ConcurrencyCoordinator.onTaskStart]; that is the Session's `streamGenerate`
 * inner concern (Sessions do their own message-level ordering via
 * [com.ailux.core.concurrency.MessageConcurrencyPolicy]). Cross-session
 * scheduling on the AiluxClient side remains a per-`openSession` ticket
 * acquired in [com.ailux.api.AiluxClient.openSession] (handled by
 * [PipelinedSession], not here).
 *
 * @since 0.3.0b
 */
internal class SessionPipeline(
    private val config: AiluxConfig,
    private val logSink: RedactingLogSink,
    private val onDiagnosticArchived: (DiagnosticReport) -> Unit,
) {

    // ──────────────────── Public entry points ────────────────────

    /**
     * Enrich a bare Session [rawStream] with context trimming + stall detection,
     * **without** building an [LLMTask]. Used by
     * [com.ailux.core.session.Session.streamGenerate] when the caller wants the
     * raw [Flow] but still benefits from the API-layer pipeline.
     *
     * Diagnostics are not recorded on this path because there is no terminal
     * state observation point — callers who want diagnostics should use
     * [runAsTask] (or the [com.ailux.core.session.Session.streamGenerateAsTask]
     * default, which delegates here through [PipelinedSession]).
     */
    fun enrichRaw(
        request: LLMRequest,
        rawStream: (LLMRequest) -> Flow<LLMEvent>,
    ): Flow<LLMEvent> = flow {
        val effectiveMessages = resolveMessages(request.messages, request.contextPolicy)

        rawStream(request.copy(messages = effectiveMessages))
            .stallDetection(config.streamConfig) { /* no state to update on raw path */ }
            .collect { emit(it) }
    }

    /**
     * Wrap a bare Session stream in a fully-equipped [LLMTask] — context
     * trimming, stall detection, the canonical state machine and a
     * [DiagnosticsRecorder] feeding the per-client ring buffer. Mirrors
     * exactly the behavior of the legacy `AiluxClient.streamGenerate(req)`.
     */
    fun runAsTask(
        request: LLMRequest,
        rawStream: (LLMRequest) -> Flow<LLMEvent>,
    ): LLMTask {
        val taskState = MutableStateFlow<LLMTaskState>(LLMTaskState.Idle)
        var taskJob: Job? = null

        val recorder = DiagnosticsRecorder(
            taskId = request.requestId,
            sdkVersion = AiluxSdk.VERSION,
            providerName = config.provider::class.simpleName ?: "LLMProvider",
            modelName = config.modelConfig?.name,
            privacy = config.privacy,
        )

        val events: Flow<LLMEvent> = flow {
            coroutineScope {
                taskJob = coroutineContext[Job]
                recorder.onStart()

                try {
                    taskState.value = LLMTaskState.Connecting(
                        phase = ConnectingPhase.ESTABLISHING,
                    )
                    logSink.logSafe(
                        LogLevel.DEBUG,
                        TAG,
                        "session task ${request.requestId} starting (provider=${config.provider::class.simpleName})",
                    )

                    val effectiveMessages = resolveMessages(request.messages, request.contextPolicy)

                    var tokenCount = 0
                    var latestUsage: UsageInfo? = null

                    rawStream(request.copy(messages = effectiveMessages))
                        .stallDetection(config.streamConfig) { stall ->
                            applyStall(taskState, stall)
                        }
                        .collect { event ->
                            if (event is LLMEvent.Token ||
                                event is LLMEvent.Reasoning ||
                                event is LLMEvent.ToolCallDelta ||
                                event is LLMEvent.ToolCallReceived
                            ) {
                                recorder.onFirstContent()
                            }
                            tokenCount = reduceState(taskState, event, tokenCount)
                            if (event is LLMEvent.Usage) {
                                latestUsage = event.info
                            }
                            emit(event)
                        }

                    if (taskState.value !is LLMTaskState.Failed) {
                        taskState.value = LLMTaskState.Completed(latestUsage)
                        logSink.logSafe(
                            LogLevel.DEBUG,
                            TAG,
                            "session task ${request.requestId} completed (tokens=$tokenCount)",
                        )
                        recorder.onSuccess()
                    } else {
                        val err = (taskState.value as LLMTaskState.Failed).error
                        recorder.onFailure(err)
                    }
                } catch (e: CancellationException) {
                    taskState.value = LLMTaskState.Idle
                    logSink.logSafe(
                        LogLevel.DEBUG,
                        TAG,
                        "session task ${request.requestId} cancelled",
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
                        "session task ${request.requestId} failed: ${error.code} ${error.message}",
                        e,
                    )
                    recorder.onFailure(error)
                    emit(LLMEvent.Error(error))
                    emit(LLMEvent.Done())
                } finally {
                    archive(recorder)
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
     * Suspend until the session stream is fully drained, returning the
     * aggregated [LLMResponse]. Same context+stall pipeline as [runAsTask],
     * but without an externally observable [LLMTask] — diagnostics are still
     * recorded and archived.
     *
     * @throws LLMException if the stream surfaces an [LLMEvent.Error].
     */
    suspend fun runToResponse(
        request: LLMRequest,
        rawStream: (LLMRequest) -> Flow<LLMEvent>,
    ): LLMResponse {
        val recorder = DiagnosticsRecorder(
            taskId = request.requestId,
            sdkVersion = AiluxSdk.VERSION,
            providerName = config.provider::class.simpleName ?: "LLMProvider",
            modelName = config.modelConfig?.name,
            privacy = config.privacy,
        )
        recorder.onStart()

        val text = StringBuilder()
        var usage: UsageInfo? = null
        var firstError: LLMError? = null

        try {
            // We need a FlowCollector to use resolveMessages (which emits ContextTrimmed events)
            // but in non-streaming mode we still want those events to be observable. Wrap
            // the same flow plumbing here.
            val piped: Flow<LLMEvent> = flow {
                val effectiveMessages = resolveMessages(request.messages, request.contextPolicy)
                rawStream(request.copy(messages = effectiveMessages))
                    .stallDetection(config.streamConfig) { /* no state */ }
                    .collect { emit(it) }
            }

            piped.collect { ev ->
                when (ev) {
                    is LLMEvent.Token -> {
                        recorder.onFirstContent()
                        text.append(ev.text)
                    }
                    is LLMEvent.Reasoning, is LLMEvent.ToolCallDelta, is LLMEvent.ToolCallReceived -> {
                        recorder.onFirstContent()
                    }
                    is LLMEvent.Usage -> usage = ev.info
                    is LLMEvent.Error -> if (firstError == null) firstError = ev.error
                    else -> Unit
                }
            }
        } catch (e: CancellationException) {
            recorder.onCancel()
            archive(recorder)
            throw e
        } catch (e: Exception) {
            val error = LLMError(
                code = ErrorCode.UNKNOWN,
                message = e.message ?: "Unknown error",
                cause = e,
            )
            recorder.onFailure(error)
            archive(recorder)
            throw LLMException(error)
        }

        val capturedError = firstError
        if (capturedError != null) {
            recorder.onFailure(capturedError)
            archive(recorder)
            throw LLMException(capturedError)
        }

        recorder.onSuccess()
        archive(recorder)

        return LLMResponse(
            text = text.toString(),
            usage = usage,
            model = config.modelConfig?.name,
        )
    }

    // ──────────────────── Context management (lifted from AiluxClient) ────────────────────

    /**
     * Resolve the effective messages after context management processing.
     *
     * Pipeline:
     * 1. Resolve the effective [LLMContextManager] by merging global config with
     *    per-request [ContextPolicy] (if present).
     * 2. If a context manager is active and messages exceed the token budget,
     *    trim messages and emit [LLMEvent.ContextTrimmed] into the surrounding flow.
     * 3. If no context manager is active, perform a passive pre-check: emit
     *    a synthetic ContextTrimmed warning when estimated tokens exceed the
     *    model's context window.
     *
     * Mirrors `AiluxClient.resolveMessages` byte-for-byte.
     */
    private suspend fun FlowCollector<LLMEvent>.resolveMessages(
        messages: List<Message>,
        override: ContextPolicy?,
    ): List<Message> {
        val effectiveContextManager = resolveContextManager(override)

        if (effectiveContextManager != null && messages.isNotEmpty()) {
            val budget = resolveBudget(config.modelConfig)
            val effectiveAggressiveness = override?.aggressiveness ?: config.trimAggressiveness
            val contextConfig = ContextConfig(
                budget = budget,
                aggressiveness = effectiveAggressiveness,
            )

            val result = effectiveContextManager.process(messages, contextConfig)

            if (result.removed.isNotEmpty()) {
                emit(
                    LLMEvent.ContextTrimmed(
                        result.removed.size,
                        result.estimatedTokensSaved,
                    ),
                )
            }
            return result.messages
        }

        if (effectiveContextManager == null && messages.isNotEmpty()) {
            val estimated = EstimatedTokenCounter().count(messages)
            val window = resolveContextWindow(config.modelConfig)
            if (estimated > window) {
                emit(
                    LLMEvent.ContextTrimmed(
                        removedCount = 0,
                        estimatedTokensSaved = 0,
                    ),
                )
            }
        }

        return messages
    }

    private fun resolveContextManager(override: ContextPolicy?): LLMContextManager? {
        val base = config.contextManager ?: return null
        if (override == null) return base
        return if (base is DefaultLLMContextManager) {
            DefaultLLMContextManager(
                tokenCounter = override.tokenCounter ?: base.tokenCounter,
                trimStrategy = override.strategy ?: base.trimStrategy,
                protector = override.protector ?: base.protector,
            )
        } else {
            base
        }
    }

    private fun resolveBudget(modelConfig: ModelConfig?): Int {
        val contextWindow = resolveContextWindow(modelConfig)
        val reserveForReply = modelConfig?.reserveForReply ?: 4096
        return contextWindow - reserveForReply
    }

    // ──────────────────── State reduction (lifted from AiluxClient) ────────────────────

    private fun reduceState(
        taskState: MutableStateFlow<LLMTaskState>,
        event: LLMEvent,
        currentTokenCount: Int,
    ): Int {
        return when (event) {
            is LLMEvent.Connected -> {
                taskState.value = LLMTaskState.Connecting(
                    phase = ConnectingPhase.WAITING_FIRST_TOKEN,
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
            is LLMEvent.StallDetected -> currentTokenCount
            else -> currentTokenCount
        }
    }

    private fun applyStall(
        taskState: MutableStateFlow<LLMTaskState>,
        stall: StallState,
    ) {
        val current = taskState.value
        taskState.value = when {
            stall.stalled && current is LLMTaskState.Connecting ->
                current.copy(stalled = true, elapsedMillis = stall.idleMillis)
            stall.stalled && current is LLMTaskState.Streaming ->
                current.copy(stalled = true, idleMillis = stall.idleMillis)
            !stall.stalled && current is LLMTaskState.Connecting ->
                current.copy(stalled = false, elapsedMillis = 0)
            !stall.stalled && current is LLMTaskState.Streaming ->
                current.copy(stalled = false, idleMillis = 0)
            else -> current
        }
    }

    // ──────────────────── Diagnostics ────────────────────

    private fun archive(recorder: DiagnosticsRecorder) {
        val finished = recorder.lastReport() ?: return
        onDiagnosticArchived(finished)
    }

    private companion object {
        private const val TAG = "Ailux/SessionPipeline"
    }
}
