package com.ailux.core.task

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
     * Safe to call multiple times (idempotent). Does not affect other tasks
     * on the same client.
     */
    fun cancel()
}