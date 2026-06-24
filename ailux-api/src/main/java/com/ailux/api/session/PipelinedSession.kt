package com.ailux.api.session

import com.ailux.api.concurrency.ConcurrencyCoordinator
import com.ailux.core.error.ErrorCode
import com.ailux.core.error.LLMError
import com.ailux.core.error.LLMException
import com.ailux.core.event.LLMEvent
import com.ailux.core.request.LLMRequest
import com.ailux.core.response.LLMResponse
import com.ailux.core.session.Session
import com.ailux.core.session.SessionSnapshot
import com.ailux.core.task.LLMTask
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A [Session] decorator that wraps the bare provider session with the
 * full **AiluxClient pipeline** — context trimming, stall detection,
 * the canonical [com.ailux.core.state.LLMTaskState] machine and
 * [com.ailux.core.diagnostics.DiagnosticsRecorder] bookkeeping.
 *
 * Returned from `AiluxClient.openSession(...)`. Direct callers of
 * `LLMProvider.openSession(...)` skip this layer and only get the bare
 * default [Session.streamGenerateAsTask] / [Session.generate] from
 * `BasicLLMTaskFactory` and `SessionDefaults`.
 *
 * ## Concurrency
 *
 * Each pipelined call acquires a single ticket from the client's
 * [ConcurrencyCoordinator] before delegating to the bare session and
 * releases it in `finally`. This preserves the cross-session policy
 * (`PARALLEL` / `CANCEL_PREVIOUS` / `ENQUEUE` / `REJECT`) that lived on
 * the legacy `AiluxClient.streamGenerate` entry. In-session ordering
 * (`MessageConcurrencyPolicy`) remains the bare session's responsibility.
 *
 * ## Lifecycle
 *
 * Forwards [Session.snapshot] and [Session.close] verbatim; once the
 * underlying bare session is closed every method behaves as the bare
 * session would (typically [IllegalStateException]).
 *
 * @since 0.3.0b
 */
internal class PipelinedSession(
    private val delegate: Session,
    private val pipeline: SessionPipeline,
    private val coordinator: ConcurrencyCoordinator,
) : Session {

    override val sessionId: String
        get() = delegate.sessionId

    /**
     * Streaming, raw flow form. Adds context trimming + stall detection on
     * top of the bare session's stream — but **no** state machine, since
     * callers using this entry point have opted out of [LLMTask].
     */
    override fun streamGenerate(request: LLMRequest): Flow<LLMEvent> = flow {
        coroutineScope {
            val job = coroutineContext[Job]!!
            val allowed = coordinator.onTaskStart(
                taskId = request.requestId,
                job = job,
                onQueued = { /* raw stream: no UI state to update */ },
            )
            if (!allowed) {
                emit(
                    LLMEvent.Error(
                        LLMError(
                            code = ErrorCode.CONCURRENT_REQUEST_REJECTED,
                            message = "Request rejected: a task is already active (policy=REJECT)",
                        ),
                    ),
                )
                emit(LLMEvent.Done())
                return@coroutineScope
            }
            try {
                pipeline.enrichRaw(request, delegate::streamGenerate).collect { emit(it) }
            } finally {
                coordinator.onTaskEnd(request.requestId)
            }
        }
    }

    /**
     * Streaming, [LLMTask]-handle form. Full pipeline (state machine +
     * diagnostics + stall + context).
     */
    override fun streamGenerateAsTask(request: LLMRequest): LLMTask {
        // The pipeline owns the cold flow, so we attach coordinator
        // ticketing through a wrapper stream that grabs/releases the slot
        // on every collection.
        val ticketedStream: (LLMRequest) -> Flow<LLMEvent> = { req ->
            flow {
                coroutineScope {
                    val job = coroutineContext[Job]!!
                    val allowed = coordinator.onTaskStart(
                        taskId = req.requestId,
                        job = job,
                        onQueued = { /* state updates handled by pipeline runAsTask */ },
                    )
                    if (!allowed) {
                        emit(
                            LLMEvent.Error(
                                LLMError(
                                    code = ErrorCode.CONCURRENT_REQUEST_REJECTED,
                                    message = "Request rejected: a task is already active (policy=REJECT)",
                                ),
                            ),
                        )
                        emit(LLMEvent.Done())
                        return@coroutineScope
                    }
                    try {
                        delegate.streamGenerate(req).collect { emit(it) }
                    } finally {
                        coordinator.onTaskEnd(req.requestId)
                    }
                }
            }
        }
        return pipeline.runAsTask(request, ticketedStream)
    }

    /**
     * Non-streaming form. Same pipeline, but blocks until the stream is
     * fully drained; surfaces the first [LLMError] (if any) as an
     * [LLMException].
     */
    override suspend fun generate(request: LLMRequest): LLMResponse = coroutineScope {
        val job = coroutineContext[Job]!!
        val allowed = coordinator.onTaskStart(
            taskId = request.requestId,
            job = job,
            onQueued = { /* non-streaming: no UI state */ },
        )
        if (!allowed) {
            throw LLMException(
                LLMError(
                    code = ErrorCode.CONCURRENT_REQUEST_REJECTED,
                    message = "Request rejected: a task is already active (policy=REJECT)",
                ),
            )
        }
        try {
            pipeline.runToResponse(request, delegate::streamGenerate)
        } finally {
            coordinator.onTaskEnd(request.requestId)
        }
    }

    override fun snapshot(): SessionSnapshot = delegate.snapshot()

    override fun close() = delegate.close()
}
