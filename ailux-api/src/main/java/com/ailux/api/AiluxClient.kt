package com.ailux.api

import com.ailux.api.concurrency.ConcurrencyCoordinator
import com.ailux.api.session.PipelinedSession
import com.ailux.api.session.SessionPipeline
import com.ailux.core.AiluxSdk
import com.ailux.core.diagnostics.DiagnosticReport
import com.ailux.core.diagnostics.Outcome
import com.ailux.core.diagnostics.PrivacyConfigSnapshot
import com.ailux.core.diagnostics.TimingMetrics
import com.ailux.core.event.LLMEvent
import com.ailux.core.logging.internal.RedactingLogSink
import com.ailux.core.session.Session
import com.ailux.core.session.SessionConfig
import com.ailux.core.session.SessionSnapshot
import com.ailux.core.state.LLMTaskState
import com.ailux.core.task.LLMTask
import kotlinx.coroutines.CancellationException

/**
 * Concurrent LLM client — **session-only** entry point as of v0.3.0b
 * (see [ADR-0009](../../../../../../../../ailux-docs/decisions/adr/0009-session-only-single-pipeline.md)).
 *
 * A single [AiluxClient] instance holds one configured
 * [com.ailux.core.LLMProvider] and exposes a Session-first API:
 *
 * - [openSession]: open a fresh stateful conversation handle. The returned
 *   [Session] is wrapped in [PipelinedSession], which transparently applies
 *   context trimming, stall detection, diagnostics and the canonical
 *   [LLMTaskState] machine to every [Session.streamGenerate],
 *   [Session.streamGenerateAsTask] and [Session.generate] call.
 * - [restoreSession]: rebuild a session from a previously captured
 *   [SessionSnapshot]; the pipeline wrap is applied identically.
 *
 * The pre-v0.3.0b `AiluxClient.streamGenerate(req)` / `generate(req)` entry
 * points (which implicitly opened an anonymous one-shot session per call)
 * have been **removed**. Replacement patterns:
 *
 * ```kotlin
 * // Streaming:
 * client.openSession().use { session ->
 *     session.streamGenerateAsTask(request).events.collect { … }
 * }
 *
 * // Non-streaming:
 * client.openSession().use { session ->
 *     val response = session.generate(request)
 * }
 * ```
 *
 * The Android adapter `AiluxClientDelegate` still offers single-shot helpers
 * that internally open + close an anonymous session, for hosts that want the
 * old call shape with the new pipeline.
 *
 * ## Concurrency
 *
 * [AiluxConfig.sessionConcurrencyPolicy] governs how multiple sessions (or
 * pipelined per-request tickets) are scheduled against the provider:
 * - PARALLEL (default): all sessions / tasks run simultaneously, subject to
 *   the provider's `ProviderCapabilities.maxConcurrentSessions` cap.
 * - CANCEL_PREVIOUS: new task auto-cancels in-flight tasks.
 * - ENQUEUE: tasks execute one at a time in FIFO order.
 * - REJECT: new task is rejected if one is already active.
 *
 * Ordering of multiple messages **within a single session** is a separate
 * concern, controlled by
 * [com.ailux.core.session.SessionConfig.messageConcurrencyPolicy] when the
 * session is opened.
 *
 * ## Lifecycle
 *
 * - After construction, [openSession] can be called immediately.
 * - Use [cancelAll] to cancel all in-flight tasks (across every open session).
 * - Call [release] when the client is no longer needed; sessions opened after
 *   release throw `IllegalStateException`.
 */
class AiluxClient(
    val config: AiluxConfig,
) {

    // ──────────────────── Internal State ────────────────────

    /**
     * Concurrency coordinator: enforces the user-configured
     * [com.ailux.core.concurrency.SessionConcurrencyPolicy] across all
     * sessions / pipelined calls, capped by the provider's reported
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

    /**
     * Shared pipeline instance that powers every [PipelinedSession]. Holds
     * a back-reference into [recentDiagnostics] for archiving terminal reports.
     */
    private val sessionPipeline: SessionPipeline = SessionPipeline(
        config = config,
        logSink = logSink,
        onDiagnosticArchived = { archiveDiagnostic(it) },
    )

    /** Whether the client has been released (terminal state). */
    @Volatile
    private var released = false

    // ──────────────────── Session API (since v0.3.0, single entry point as of v0.3.0b) ────────────────────

    /**
     * Open a fresh stateful [Session] on this client.
     *
     * The returned session holds conversation state across turns — native
     * KV-cache for local engines that support it, or a client-side history
     * accumulator for cloud / proxy providers. Application code interacts
     * with the same API in either case.
     *
     * The returned object is a [PipelinedSession] wrapping the raw provider
     * session — every call to [Session.streamGenerate],
     * [Session.streamGenerateAsTask] or [Session.generate] therefore goes
     * through the AiluxClient pipeline (context trimming + stall detection
     * + diagnostics + state machine).
     *
     * Sessions are independent: closing one does not affect others. The
     * client-level [AiluxConfig.sessionConcurrencyPolicy] still governs how
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
        val bare = config.provider.openSession(sessionConfig)
        return PipelinedSession(bare, sessionPipeline, coordinator)
    }

    /**
     * Restore a [Session] from a previously captured [SessionSnapshot].
     *
     * Logical state (history + config) is restored exactly. Native KV-cache
     * is NOT in the snapshot and will be lazily rebuilt on the first call
     * to [Session.streamGenerate]. The returned session is wrapped in
     * [PipelinedSession], same as [openSession].
     *
     * @throws IllegalStateException if the client has been [release]d.
     * @throws UnsupportedOperationException if the provider does not yet
     *         support session restore.
     */
    fun restoreSession(snapshot: SessionSnapshot): Session {
        checkNotReleased()
        val bare = config.provider.restoreSession(snapshot)
        return PipelinedSession(bare, sessionPipeline, coordinator)
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
     *
     * Cancels all active tasks via the coordinator and marks the client as
     * unusable. After release, [openSession] / [restoreSession] throw
     * [IllegalStateException]. Already-open sessions remain usable until
     * the caller closes them explicitly — they no longer benefit from
     * coordinator-level cancellation, but the bare session's `close()` still
     * works.
     */
    fun release() {
        released = true
        cancelAll()
    }

    // ──────────────────── Utilities ────────────────────

    /** Verify that the client has not been released. */
    private fun checkNotReleased() {
        check(!released) { "AiluxClient has been release()d and can no longer be used." }
    }

    // ──────────────────── Diagnostics ────────────────────

    /**
     * Stores a finished task's diagnostic in the per-client ring buffer.
     * Invoked from [SessionPipeline] every time a pipelined task reaches
     * a terminal state.
     */
    private fun archiveDiagnostic(report: DiagnosticReport) {
        synchronized(recentDiagnostics) {
            recentDiagnostics.addFirst(report)
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
        /** Maximum number of finished task diagnostics retained per client. */
        private const val DIAGNOSTIC_HISTORY_SIZE = 16
    }
}
