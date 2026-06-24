package com.ailux.core.session

import com.ailux.core.error.ErrorCode
import com.ailux.core.error.LLMError
import com.ailux.core.event.LLMEvent
import com.ailux.core.request.LLMRequest
import com.ailux.core.response.UsageInfo
import com.ailux.core.state.ConnectingPhase
import com.ailux.core.state.LLMTaskState
import com.ailux.core.task.LLMTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * Factory for the **bare** [LLMTask] used by the default
 * [Session.streamGenerateAsTask] implementation.
 *
 * A "bare" task wraps a raw [Session.streamGenerate] flow with **only** the
 * minimum state machine (Idle → Connecting → Streaming → Completed / Failed /
 * Idle-on-cancel). It deliberately does **not** apply:
 * - Stall detection (added by the AiluxClient pipeline since v0.2.3).
 * - Context-management trimming (since v0.2.1).
 * - DiagnosticsRecorder bookkeeping (since v0.2.5).
 *
 * Callers who obtain a [Session] directly from `LLMProvider.openSession(...)`
 * (no AiluxClient) get this bare task. Callers who obtain a Session via
 * `AiluxClient.openSession(...)` get a richer task produced by the API
 * layer's `PipelinedSession` decorator.
 *
 * @since 0.3.0b
 */
internal object BasicLLMTaskFactory {

    /**
     * Wrap a [Session.streamGenerate]-style raw event flow into an [LLMTask]
     * with the minimum state machine.
     *
     * The returned task's [LLMTask.events] flow is cold: the underlying
     * [stream] is only invoked when collection starts, and collection happens
     * inside a `coroutineScope` so that [LLMTask.cancel] can deterministically
     * tear it down without affecting unrelated work.
     *
     * @param request the original request — its [LLMRequest.requestId] is
     *                exposed as [LLMTask.id].
     * @param stream a function that, given the request, produces a raw
     *               provider event flow. Typically `Session::streamGenerate`.
     */
    fun fromSessionStream(
        request: LLMRequest,
        stream: (LLMRequest) -> Flow<LLMEvent>,
    ): LLMTask {
        val taskState = MutableStateFlow<LLMTaskState>(LLMTaskState.Idle)
        var taskJob: Job? = null

        val events: Flow<LLMEvent> = flow {
            coroutineScope {
                taskJob = coroutineContext[Job]

                try {
                    taskState.value = LLMTaskState.Connecting(
                        phase = ConnectingPhase.ESTABLISHING,
                    )

                    var tokenCount = 0
                    var latestUsage: UsageInfo? = null

                    stream(request).collect { event ->
                        tokenCount = reduceState(taskState, event, tokenCount)
                        if (event is LLMEvent.Usage) {
                            latestUsage = event.info
                        }
                        emit(event)
                    }

                    // Stream completed normally. If a terminal Error was already
                    // surfaced (taskState == Failed), keep it; otherwise mark Completed.
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
                }
            }
        }

        return BasicLLMTask(
            id = request.requestId,
            events = events,
            state = taskState.asStateFlow(),
            onCancel = { taskJob?.cancel() },
        )
    }

    /**
     * Minimal state reduction that mirrors the AiluxClient pipeline but
     * omits stall/diagnostic concerns. Returns the (possibly updated) token
     * count after applying the event.
     */
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
            else -> {
                // Connected handled above; Usage / ToolCallReceived / ContextTrimmed /
                // StallDetected (not produced by bare path) / Done: no state change.
                currentTokenCount
            }
        }
    }
}

/**
 * Minimal [LLMTask] backing [BasicLLMTaskFactory]. Holds no diagnostics —
 * [lastDiagnostic] always returns `null` via the interface default.
 */
private class BasicLLMTask(
    override val id: String,
    override val events: Flow<LLMEvent>,
    override val state: StateFlow<LLMTaskState>,
    private val onCancel: () -> Unit,
) : LLMTask {

    override fun cancel() {
        onCancel()
    }
}
