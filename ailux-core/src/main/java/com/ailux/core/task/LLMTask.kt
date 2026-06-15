package com.ailux.core.task

import com.ailux.core.diagnostics.DiagnosticReport
import com.ailux.core.event.LLMEvent
import com.ailux.core.state.LLMTaskState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Per-request handle for an LLM streaming task.
 *
 * Each call to `AiluxClient.streamGenerate()` returns a fresh [LLMTask] instance,
 * analogous to OkHttp's `Call`. The handle exposes:
 * - [events]: a cold Flow that starts the request on first collection.
 * - [state]: a hot StateFlow reflecting the task's lifecycle (Idle → Connecting → Streaming → Completed/Failed).
 * - [cancel]: cancel this specific task without affecting other concurrent tasks.
 * - [lastDiagnostic]: redacted snapshot of the task's lifetime, available
 *   after the task reaches a terminal state.
 *
 * The handle is lightweight and does not hold resources until [events] is collected.
 */
interface LLMTask {

    /** Unique task identifier, sourced from `LLMRequest.requestId`. */
    val id: String

    /**
     * Cold event flow; the underlying network request starts only when
     * this flow is collected. Emits [LLMEvent] instances and ends with [LLMEvent.Done].
     */
    val events: Flow<LLMEvent>

    /**
     * Hot state flow reflecting the task's lifecycle. UI layers can collect
     * this directly (e.g. `collectAsState()` in Compose).
     */
    val state: StateFlow<LLMTaskState>

    /**
     * Cancel this task. Triggers [CancellationException] in the underlying coroutine,
     * closes the provider connection, and transitions [state] to [LLMTaskState.Idle].
     *
     * ## Billing Boundary
     *
     * Cancellation immediately closes the client-side connection (SSE disconnect or
     * HTTP call abort) and stops emitting further events. However, **whether the
     * upstream LLM provider actually stops inference and billing depends entirely on
     * the backend implementation**. Most providers bill for tokens already generated
     * up to the point of disconnection.
     *
     * Ailux recommends that your backend adopt a "client-disconnect = abort upstream
     * request" policy (see the backend-sample for a reference implementation). This
     * minimizes wasted billing, but cannot be guaranteed at the SDK layer.
     *
     * ## Observability
     *
     * A cancelled task records [com.ailux.core.diagnostics.Outcome.Cancelled] in its
     * [DiagnosticReport], including timing metrics up to the cancellation point.
     *
     * Safe to call multiple times (idempotent). Does not affect other tasks
     * on the same client.
     */
    fun cancel()

    /**
     * Returns the diagnostic report for this task once it has reached a
     * terminal state ([LLMTaskState.Completed] / [LLMTaskState.Failed] / cancelled).
     *
     * The report is **redacted by construction** — it never embeds prompt or
     * response bodies, only timing, outcome, and retry metadata. Safe to
     * paste into a public bug report.
     *
     * Returns `null` while the task is still in flight, and remains stable
     * once set: callers can safely read it multiple times.
     *
     * @since 0.2.5
     */
    fun lastDiagnostic(): DiagnosticReport? = null
}
