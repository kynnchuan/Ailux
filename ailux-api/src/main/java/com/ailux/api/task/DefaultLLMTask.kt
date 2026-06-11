package com.ailux.api.task

import com.ailux.core.diagnostics.DiagnosticReport
import com.ailux.core.diagnostics.DiagnosticsRecorder
import com.ailux.core.event.LLMEvent
import com.ailux.core.state.LLMTaskState
import com.ailux.core.task.LLMTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Default implementation of [LLMTask].
 *
 * This is an internal class — callers interact only with the [LLMTask] interface.
 * Each instance represents a single LLM request and is produced by
 * [AiluxClient.streamGenerate].
 *
 * @property id      unique task ID (sourced from LLMRequest.requestId).
 * @property events  cold event flow; collection starts the underlying request.
 * @property state   observable state for this task (UI can collect directly).
 * @property onCancel callback that cancels the underlying coroutine Job.
 * @property recorder diagnostics accumulator backing [lastDiagnostic]. Receives
 *                    lifecycle calls from the streaming pipeline; surfaces an
 *                    immutable [DiagnosticReport] once terminal state is reached.
 */
internal class DefaultLLMTask(
    override val id: String,
    override val events: Flow<LLMEvent>,
    override val state: StateFlow<LLMTaskState>,
    private val onCancel: () -> Unit,
    private val recorder: DiagnosticsRecorder,
) : LLMTask {

    /**
     * Cancel this task.
     *
     * Triggers CancellationException in the task's coroutine, which causes:
     * - The provider's SSE connection to be closed.
     * - The coordinator to release this task's slot (via finally block).
     * - The state to transition to Idle.
     *
     * Safe to call multiple times (idempotent). Does not affect other tasks
     * on the same [AiluxClient].
     */
    override fun cancel() {
        onCancel()
    }

    override fun lastDiagnostic(): DiagnosticReport? = recorder.lastReport()
}
