package com.ailux.core.diagnostics

import com.ailux.core.error.LLMError
import com.ailux.core.privacy.PrivacyConfig

/**
 * Redacted snapshot of an Ailux task or session, suitable for direct copy into
 * a bug report.
 *
 * Two flavours exist:
 *  - **Task-level** ([taskId] non-null) — produced by `LLMTask.lastDiagnostic()`.
 *    Captures one task's lifetime (timing, outcome, retry trail).
 *  - **Session-level** ([taskId] is `null`) — produced by
 *    `Ailux.createDiagnosticReport()`. Wraps a small ring buffer of recent
 *    task reports plus an SDK-wide snapshot.
 *
 * The report itself never contains prompt or response content — only timing,
 * outcome class, error code, retry counters, and an indication of which
 * privacy flags were active. Pasting this output into a public Issue is safe
 * by construction.
 *
 * @property sdkVersion         Ailux SDK semantic version that generated this report.
 * @property timestamp          Wall-clock instant (epoch millis) at which this report was built.
 * @property taskId             Task identifier, or `null` for session-level reports.
 * @property provider           Provider class simple name, e.g. `"BackendProxyProvider"`.
 * @property model              Resolved model name if available, else `null`.
 * @property timing             Timing breakdown for the task; zeroed for pure session reports.
 * @property outcome            Terminal status of the task. Always [Outcome.Pending] for session reports.
 * @property retries            Sequential record of automatic retry attempts.
 * @property privacy            Snapshot of the [PrivacyConfig] that was active.
 * @property recentTasks        For session-level reports: most-recent-first list of task reports.
 *                              Empty for task-level reports.
 * @property notes              Optional annotations supplied at build time.
 *
 * @since 0.2.5
 */
public data class DiagnosticReport(
    val sdkVersion: String,
    val timestamp: Long,
    val taskId: String?,
    val provider: String,
    val model: String?,
    val timing: TimingMetrics,
    val outcome: Outcome,
    val retries: List<RetryAttempt>,
    val privacy: PrivacyConfigSnapshot,
    val recentTasks: List<DiagnosticReport> = emptyList(),
    val notes: List<String> = emptyList(),
) {

    /**
     * Renders a human-readable, pure-ASCII representation. The output is
     * stable across patch releases: tooling can grep for `SDK:` / `Outcome:`
     * lines and trust the prefix.
     *
     * Example:
     * ```
     * === Ailux Diagnostic ===
     * SDK: 0.2.5
     * Time: 2026-06-11T19:30:00Z
     * Task: chat-7f3a (provider=BackendProxyProvider, model=gpt-4o-mini)
     * Outcome: Failure(HttpException, code=RATE_LIMITED, "rate limited")
     * Timing: TTFT=312ms, total=1480ms
     * Retries: 2
     *   [0] http_429, delay=1000ms
     *   [1] http_429, delay=2000ms
     * Privacy: prompt=off, response=off, overrides=off, headers=off, body=off
     * ========================
     * ```
     */
    public fun toShareableText(): String = buildString {
        appendLine("=== Ailux Diagnostic ===")
        appendLine("SDK: $sdkVersion")
        appendLine("Time: ${formatIso8601(timestamp)}")
        if (taskId != null) {
            append("Task: $taskId (provider=$provider")
            if (model != null) append(", model=$model")
            appendLine(")")
        } else {
            appendLine("Scope: session (provider=$provider${model?.let { ", model=$it" }.orEmpty()})")
        }
        appendLine("Outcome: ${outcome.toShareableText()}")
        if (timing.startedAt > 0L) {
            val ttft = timing.ttftMs?.let { "${it}ms" } ?: "n/a"
            val total = timing.durationMs?.let { "${it}ms" } ?: "n/a"
            appendLine("Timing: TTFT=$ttft, total=$total")
        }
        appendLine("Retries: ${retries.size}")
        retries.forEachIndexed { idx, attempt ->
            appendLine("  [$idx] ${attempt.reason}, delay=${attempt.delayMs}ms")
        }
        appendLine(
            "Privacy: prompt=${privacy.logPrompt.flag()}, response=${privacy.logResponse.flag()}, " +
                "overrides=${privacy.logOverrides.flag()}, headers=${privacy.logHeaders.flag()}, " +
                "body=${privacy.logRequestBody.flag()}"
        )
        if (notes.isNotEmpty()) {
            appendLine("Notes:")
            notes.forEach { appendLine("  - $it") }
        }
        if (recentTasks.isNotEmpty()) {
            appendLine("Recent tasks: ${recentTasks.size}")
            recentTasks.forEachIndexed { idx, task ->
                val tt = task.timing.durationMs?.let { "${it}ms" } ?: "n/a"
                appendLine("  [$idx] ${task.taskId ?: "?"} ${task.outcome.shortLabel()} total=$tt")
            }
        }
        append("========================")
    }

    /**
     * Renders a stable, machine-parseable JSON representation. Hand-written
     * (no dependency on serialization codegen) so this method is callable from
     * any Ailux module without extra wiring.
     */
    public fun toJson(): String = buildString {
        append('{')
        appendKv("sdkVersion", sdkVersion); append(',')
        appendKv("timestamp", timestamp); append(',')
        appendKv("taskId", taskId); append(',')
        appendKv("provider", provider); append(',')
        appendKv("model", model); append(',')
        append("\"timing\":").append(timing.toJson()); append(',')
        append("\"outcome\":").append(outcome.toJson()); append(',')
        append("\"retries\":[")
        retries.forEachIndexed { idx, attempt ->
            if (idx > 0) append(',')
            append(attempt.toJson())
        }
        append("],")
        append("\"privacy\":").append(privacy.toJson()); append(',')
        append("\"recentTasks\":[")
        recentTasks.forEachIndexed { idx, report ->
            if (idx > 0) append(',')
            append(report.toJson())
        }
        append("],")
        append("\"notes\":[")
        notes.forEachIndexed { idx, note ->
            if (idx > 0) append(',')
            appendJsonString(note)
        }
        append(']')
        append('}')
    }
}

/**
 * Wall-clock timing breakdown for one task.
 *
 * @property startedAt      Epoch millis when the task entered Connecting state.
 * @property firstTokenAt   Epoch millis at which the first content event was observed.
 * @property finishedAt     Epoch millis when the task reached terminal state.
 * @property ttftMs         Convenience: `firstTokenAt - startedAt`, or `null` if no token arrived.
 * @property durationMs     Convenience: `finishedAt - startedAt`, or `null` if not finished.
 */
public data class TimingMetrics(
    val startedAt: Long = 0L,
    val firstTokenAt: Long? = null,
    val finishedAt: Long? = null,
) {
    val ttftMs: Long? get() = firstTokenAt?.takeIf { startedAt > 0L }?.let { it - startedAt }
    val durationMs: Long? get() = finishedAt?.takeIf { startedAt > 0L }?.let { it - startedAt }

    internal fun toJson(): String = buildString {
        append('{')
        append("\"startedAt\":").append(startedAt); append(',')
        append("\"firstTokenAt\":").append(firstTokenAt?.toString() ?: "null"); append(',')
        append("\"finishedAt\":").append(finishedAt?.toString() ?: "null"); append(',')
        append("\"ttftMs\":").append(ttftMs?.toString() ?: "null"); append(',')
        append("\"durationMs\":").append(durationMs?.toString() ?: "null")
        append('}')
    }

    public companion object {
        public val EMPTY: TimingMetrics = TimingMetrics()
    }
}

/**
 * Terminal status of a task.
 *
 * Crucially, neither [Failure.errorMessage] nor [Failure.errorClass] embeds
 * prompt or response content — they are short identifiers safe to share.
 */
public sealed class Outcome {

    public abstract fun toShareableText(): String
    public abstract fun shortLabel(): String
    internal abstract fun toJson(): String

    /** Task reached `Completed`. */
    public data object Success : Outcome() {
        override fun toShareableText(): String = "Success"
        override fun shortLabel(): String = "ok"
        override fun toJson(): String = "{\"kind\":\"success\"}"
    }

    /** Task entered `Failed`. */
    public data class Failure(
        val errorClass: String,
        val errorCode: String?,
        val errorMessage: String,
    ) : Outcome() {
        override fun toShareableText(): String =
            "Failure($errorClass, code=${errorCode ?: "n/a"}, \"$errorMessage\")"

        override fun shortLabel(): String = "fail/${errorCode ?: errorClass}"

        override fun toJson(): String = buildString {
            append('{')
            appendKv("kind", "failure"); append(',')
            appendKv("errorClass", errorClass); append(',')
            appendKv("errorCode", errorCode); append(',')
            appendKv("errorMessage", errorMessage)
            append('}')
        }
    }

    /** Task was cancelled before completion. */
    public data object Cancelled : Outcome() {
        override fun toShareableText(): String = "Cancelled"
        override fun shortLabel(): String = "cancelled"
        override fun toJson(): String = "{\"kind\":\"cancelled\"}"
    }

    /** Task has not yet reached a terminal state. Used for session-level reports. */
    public data object Pending : Outcome() {
        override fun toShareableText(): String = "Pending"
        override fun shortLabel(): String = "pending"
        override fun toJson(): String = "{\"kind\":\"pending\"}"
    }

    public companion object {
        /** Convenience constructor that maps an [LLMError] to a [Failure] outcome. */
        public fun ofError(error: LLMError): Failure = Failure(
            errorClass = error.cause?.let { it::class.simpleName ?: "Throwable" } ?: "LLMError",
            errorCode = error.code.name,
            errorMessage = error.message,
        )
    }
}

/**
 * Single retry attempt record.
 *
 * @property index    Zero-based attempt index.
 * @property reason   Short identifier of why the retry was triggered (e.g. `"http_429"`).
 * @property delayMs  Delay applied before this attempt was issued.
 */
public data class RetryAttempt(
    val index: Int,
    val reason: String,
    val delayMs: Long,
) {
    internal fun toJson(): String = buildString {
        append('{')
        append("\"index\":").append(index); append(',')
        appendKv("reason", reason); append(',')
        append("\"delayMs\":").append(delayMs)
        append('}')
    }
}

/**
 * Immutable snapshot of the [PrivacyConfig] flags active when the report was
 * captured. Lets the receiver tell at a glance which channels were redacted.
 */
public data class PrivacyConfigSnapshot(
    val logPrompt: Boolean,
    val logResponse: Boolean,
    val logOverrides: Boolean,
    val logHeaders: Boolean,
    val logRequestBody: Boolean,
) {
    internal fun toJson(): String = buildString {
        append('{')
        append("\"logPrompt\":").append(logPrompt); append(',')
        append("\"logResponse\":").append(logResponse); append(',')
        append("\"logOverrides\":").append(logOverrides); append(',')
        append("\"logHeaders\":").append(logHeaders); append(',')
        append("\"logRequestBody\":").append(logRequestBody)
        append('}')
    }

    public companion object {
        public fun of(privacy: PrivacyConfig): PrivacyConfigSnapshot = PrivacyConfigSnapshot(
            logPrompt = privacy.logPrompt,
            logResponse = privacy.logResponse,
            logOverrides = privacy.logOverrides,
            logHeaders = privacy.logHeaders,
            logRequestBody = privacy.logRequestBody,
        )
    }
}

// ──────────────────── helpers ────────────────────

private fun Boolean.flag(): String = if (this) "on" else "off"

private fun formatIso8601(epochMillis: Long): String {
    // No java.time on minSdk 23 baseline (< API 26) for all callers, but the
    // SDK already targets minSdk 23 and uses kotlinx-coroutines internally; we
    // intentionally fall back to a manual formatter so this stays platform
    // neutral and unit-testable on the JVM.
    return java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ROOT).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }.format(java.util.Date(epochMillis))
}

internal fun StringBuilder.appendKv(key: String, value: String?) {
    append('"').append(key).append("\":")
    appendJsonString(value)
}

internal fun StringBuilder.appendKv(key: String, value: Long) {
    append('"').append(key).append("\":").append(value)
}

internal fun StringBuilder.appendJsonString(value: String?) {
    if (value == null) {
        append("null")
        return
    }
    append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
    append('"')
}
