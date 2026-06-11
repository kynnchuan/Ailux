package com.ailux.core.diagnostics

import com.ailux.core.error.LLMError
import com.ailux.core.privacy.PrivacyConfig
import java.util.concurrent.atomic.AtomicReference

/**
 * Mutable, thread-safe accumulator that observes one task's lifetime and
 * crystallises a [DiagnosticReport] at terminal state.
 *
 * Designed for one writer (the task coroutine) plus one reader (the host app
 * calling [snapshot] or [toReport]). All mutating calls are atomic.
 *
 * Lifecycle helpers, in chronological order of typical use:
 *
 *  1. [onStart] — invoked once when the task transitions out of Idle.
 *  2. [onFirstContent] — invoked when the first Token / Reasoning / ToolCall
 *     event arrives. Subsequent calls are no-ops.
 *  3. [onRetry] — invoked once per automatic retry attempt.
 *  4. Exactly one of [onSuccess], [onFailure], [onCancel] — terminal call.
 *
 * After a terminal call, the recorder snapshots itself into an immutable
 * [DiagnosticReport] that can be re-read any number of times via
 * [lastReport]. Subsequent lifecycle calls are ignored.
 *
 * @since 0.2.5
 */
public class DiagnosticsRecorder(
    private val taskId: String,
    private val sdkVersion: String,
    private val providerName: String,
    private val modelName: String?,
    private val privacy: PrivacyConfig,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val state: AtomicReference<RecorderState> = AtomicReference(RecorderState.INITIAL)
    private val terminalReport: AtomicReference<DiagnosticReport?> = AtomicReference(null)

    /**
     * Marks the task as started. The first call wins; later calls are
     * ignored so a flapping caller cannot reset the clock.
     */
    public fun onStart() {
        update { current ->
            if (current.startedAt > 0L) current else current.copy(startedAt = clock())
        }
    }

    /**
     * Records the arrival of the first content event. Subsequent calls do
     * nothing — TTFT is a single, well-defined instant.
     */
    public fun onFirstContent() {
        update { current ->
            if (current.firstTokenAt != null) current else current.copy(firstTokenAt = clock())
        }
    }

    /**
     * Appends a retry record. Safe to call from inside `retryWhen`.
     */
    public fun onRetry(reason: String, delayMs: Long) {
        update { current ->
            val nextIndex = current.retries.size
            current.copy(retries = current.retries + RetryAttempt(nextIndex, reason, delayMs))
        }
    }

    /** Terminal: success. Snapshots an immutable [DiagnosticReport]. */
    public fun onSuccess() {
        finish(Outcome.Success)
    }

    /** Terminal: failure with structured [LLMError]. */
    public fun onFailure(error: LLMError) {
        finish(Outcome.ofError(error))
    }

    /** Terminal: failure with arbitrary [Throwable]. */
    public fun onFailure(throwable: Throwable) {
        finish(
            Outcome.Failure(
                errorClass = throwable::class.simpleName ?: "Throwable",
                errorCode = null,
                errorMessage = throwable.message ?: throwable.toString(),
            )
        )
    }

    /** Terminal: cancellation. */
    public fun onCancel() {
        finish(Outcome.Cancelled)
    }

    /**
     * Returns the immutable report once terminal state has been reached, or
     * `null` while the task is still in flight.
     */
    public fun lastReport(): DiagnosticReport? = terminalReport.get()

    /**
     * Returns a best-effort snapshot of the current state. Safe to call before
     * terminal state — fields that have not yet been observed return their
     * defaults. Outcome reads as [Outcome.Pending] while in flight, or the
     * frozen terminal value once finished.
     */
    public fun snapshot(): DiagnosticReport {
        terminalReport.get()?.let { return it }
        val current = state.get()
        return buildReport(current, Outcome.Pending)
    }

    private fun finish(outcome: Outcome) {
        if (terminalReport.get() != null) return
        val frozen = state.get().copy(finishedAt = clock())
        // Best-effort: store the frozen state so future snapshots match.
        state.compareAndSet(state.get(), frozen)
        val report = buildReport(frozen, outcome)
        terminalReport.compareAndSet(null, report)
    }

    private fun buildReport(state: RecorderState, outcome: Outcome): DiagnosticReport {
        return DiagnosticReport(
            sdkVersion = sdkVersion,
            timestamp = clock(),
            taskId = taskId,
            provider = providerName,
            model = modelName,
            timing = TimingMetrics(
                startedAt = state.startedAt,
                firstTokenAt = state.firstTokenAt,
                finishedAt = state.finishedAt,
            ),
            outcome = outcome,
            retries = state.retries.toList(),
            privacy = PrivacyConfigSnapshot.of(privacy),
        )
    }

    /**
     * Atomically applies [mutator] to the current state, retrying on CAS
     * failure. Terminal states are immutable — once [terminalReport] is set,
     * later mutations are dropped.
     */
    private inline fun update(mutator: (RecorderState) -> RecorderState) {
        if (terminalReport.get() != null) return
        while (true) {
            val current = state.get()
            val next = mutator(current)
            if (next === current) return
            if (state.compareAndSet(current, next)) return
        }
    }

    private data class RecorderState(
        val startedAt: Long = 0L,
        val firstTokenAt: Long? = null,
        val finishedAt: Long? = null,
        val retries: List<RetryAttempt> = emptyList(),
    ) {
        companion object {
            val INITIAL: RecorderState = RecorderState()
        }
    }
}
