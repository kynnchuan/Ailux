package com.ailux.android

import com.ailux.api.AiluxClient
import com.ailux.core.diagnostics.DiagnosticReport
import com.ailux.core.event.LLMEvent
import com.ailux.core.request.LLMRequest
import com.ailux.core.session.Session
import com.ailux.core.state.LLMTaskState
import com.ailux.core.task.LLMTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/**
 * Helper that adapts a single-shot [LLMRequest] to the Session-only API
 * (v0.3.0b) without forcing the caller to manage [Session] lifetime by hand.
 *
 * Each invocation opens a fresh anonymous [Session] on the supplied
 * [AiluxClient] and forwards to [Session.streamGenerateAsTask]. The returned
 * [LLMTask] looks identical to the legacy `client.streamGenerate(req)` task,
 * but the underlying session is **eagerly opened** (so state/diagnostics are
 * available before collection) and **deterministically closed** the first
 * time the event flow leaves its `collect { … }` block — whether by normal
 * completion, an exception, or coroutine cancellation.
 *
 * This shim lives in `ailux-android` because it is exclusively used by the
 * UI adapters ([AiluxClientDelegate] / [AiluxViewModel]) to preserve their
 * pre-v0.3.0b ergonomic surface. Core / API never use it.
 *
 * @since 0.3.0b
 */
internal object AnonymousSessionTask {

    fun wrap(client: AiluxClient, request: LLMRequest): LLMTask {
        val session: Session = client.openSession()
        val inner: LLMTask = session.streamGenerateAsTask(request)
        return ClosingTask(inner, session)
    }
}

/**
 * Wraps another [LLMTask] and closes a backing [Session] when the event
 * flow terminates for any reason.
 */
private class ClosingTask(
    private val inner: LLMTask,
    private val session: Session,
) : LLMTask {

    override val id: String get() = inner.id

    override val state: StateFlow<LLMTaskState> get() = inner.state

    override val events: Flow<LLMEvent> = flow {
        try {
            inner.events.collect { emit(it) }
        } finally {
            // close() is idempotent on every Session implementation we ship.
            session.close()
        }
    }

    override fun cancel() {
        inner.cancel()
        // session.close() will still run via the events {} finally when the
        // collector unwinds. If the caller never collected, close eagerly so
        // we never leak the session.
        if (state.value is LLMTaskState.Idle) {
            session.close()
        }
    }

    override fun lastDiagnostic(): DiagnosticReport? = inner.lastDiagnostic()
}
