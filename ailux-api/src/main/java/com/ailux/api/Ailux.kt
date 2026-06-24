package com.ailux.api

import com.ailux.core.diagnostics.DiagnosticReport
import com.ailux.core.session.Session
import com.ailux.core.session.SessionConfig
import com.ailux.core.session.SessionSnapshot

/**
 * Static singleton entry point of the Ailux SDK.
 *
 * Internally delegates to a global default [AiluxClient] instance and offers
 * a zero-configuration, call-from-anywhere style. It fits the common case of
 * "one global configuration shared across the whole app".
 *
 * [init] must be called before any other API:
 *
 * ```kotlin
 * // Application.onCreate()
 * Ailux.init(
 *     AiluxConfig.Builder()
 *         .setProvider(myProvider)
 *         .setTimeoutMillis(30_000)
 *         .build()
 * )
 *
 * // Anywhere later — Session-first API (since v0.3.0b)
 * Ailux.openSession().use { session ->
 *     session.streamGenerateAsTask(request).events.collect { … }
 * }
 * ```
 *
 * The pre-v0.3.0b shortcuts `Ailux.streamGenerate(req)` and `Ailux.generate(req)`
 * have been **removed** — see ADR-0009. Use [openSession] (or the Android
 * adapter's single-shot helpers) instead.
 *
 * For multi-instance scenarios, use [AiluxClient] directly.
 */
object Ailux {

    @Volatile
    private var defaultClient: AiluxClient? = null

    /**
     * Initialize the SDK.
     *
     * Typically called once in `Application.onCreate()`.
     * Calling it again replaces the previous default Client (the old one will be released).
     *
     * @param config SDK configuration.
     */
    fun init(config: AiluxConfig) {
        defaultClient?.release()
        defaultClient = AiluxClient(config)
    }

    /**
     * Open a fresh stateful [Session] on the default client.
     *
     * Equivalent to [AiluxClient.openSession]. The caller MUST eventually
     * call [Session.close] (typically via `use { }`).
     *
     * @see AiluxClient.openSession
     * @since 0.3.0b
     */
    fun openSession(sessionConfig: SessionConfig = SessionConfig()): Session =
        requireClient().openSession(sessionConfig)

    /**
     * Restore a [Session] from a previously captured snapshot.
     *
     * Equivalent to [AiluxClient.restoreSession].
     *
     * @see AiluxClient.restoreSession
     * @since 0.3.0b
     */
    fun restoreSession(snapshot: SessionSnapshot): Session =
        requireClient().restoreSession(snapshot)

    /**
     * Cancel every in-flight pipelined task on the default Client.
     *
     * @see AiluxClient.cancelAll
     */
    fun cancelAll() = requireClient().cancelAll()

    /**
     * Build a session-level [DiagnosticReport] from the default client.
     *
     * The report aggregates the most recent finished tasks (capped by the
     * client's ring buffer) plus an SDK-wide snapshot. It contains no prompt
     * or response content — safe to paste directly into a public bug report.
     *
     * @param includeRecentTasks how many recent task reports to embed; defaults to 5.
     * @return a redacted session-level diagnostic report.
     * @throws IllegalStateException if the SDK has not been [init]ialised.
     *
     * @see AiluxClient.createDiagnosticReport
     * @since 0.2.5
     */
    fun createDiagnosticReport(includeRecentTasks: Int = 5): DiagnosticReport =
        requireClient().createDiagnosticReport(includeRecentTasks)

    /**
     * Release resources held by the default Client.
     *
     * After this call, [init] must be invoked again before further use.
     */
    fun release() {
        defaultClient?.release()
        defaultClient = null
    }

    /**
     * Returns the default Client, or throws an explicit exception if not initialized.
     */
    private fun requireClient(): AiluxClient =
        defaultClient
            ?: throw IllegalStateException(
                "Ailux is not initialized. Call Ailux.init(config) first."
            )
}
