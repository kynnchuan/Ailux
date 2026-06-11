package com.ailux.core.logging.internal

import com.ailux.core.logging.AiluxLogger
import com.ailux.core.logging.LogLevel
import com.ailux.core.privacy.PrivacyConfig

/**
 * Internal sink that mediates **every** SDK log call before it reaches the
 * user-supplied [AiluxLogger].
 *
 * SDK call sites are forbidden from invoking [AiluxLogger.log] directly when
 * the message contains sensitive material. They must go through one of the
 * redaction-aware helpers exposed here:
 *
 *  - [logSafe] — for messages that contain no sensitive material (task
 *    lifecycle markers, HTTP status codes, retry counters, etc.);
 *  - [logPrompt] — for messages whose content is part of the user prompt;
 *  - [logResponse] — for streamed tokens / reasoning / completion bodies;
 *  - [logOverrides] — for structured overrides JSON dumps;
 *  - [logHeaders] — for outbound HTTP headers (Authorization is scrubbed
 *    even when [PrivacyConfig.logHeaders] is `true`);
 *  - [logRequestBody] — for the full HTTP request body;
 *  - [logResponseBody] — for the full HTTP response body.
 *
 * Each sensitive helper consults [PrivacyConfig] and either forwards the
 * original message to the underlying [AiluxLogger] or substitutes
 * [PrivacyConfig.redactionMask].
 *
 * ## Why this exists
 *
 * The user-facing [AiluxLogger] is an open extension point — host apps freely
 * bridge it to Timber, Sentry, structured logs, etc. We cannot trust every
 * sink to remember the SDK's privacy contract, so the SDK applies it once,
 * here, before the message ever leaves Ailux's boundary.
 *
 * Authorisation headers and API keys are stripped here regardless of the
 * active [PrivacyConfig], for defence in depth.
 *
 * @since 0.2.5
 */
public class RedactingLogSink(
    private val delegate: AiluxLogger,
    private val privacy: PrivacyConfig,
) {

    /** The privacy policy currently bound to this sink. */
    public val privacyConfig: PrivacyConfig get() = privacy

    /**
     * Records a non-sensitive message. Forwarded as-is to [delegate].
     *
     * Suitable for: task lifecycle markers, HTTP status codes, retry counters,
     * stall detection signals, and similar metadata that does not embed
     * prompt / response / header / body content.
     */
    public fun logSafe(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        delegate.log(level, tag, message, throwable)
    }

    /**
     * Records a message that contains prompt content (system / user / tool
     * messages, attachment text payloads). Suppressed unless
     * [PrivacyConfig.logPrompt] is `true`.
     */
    public fun logPrompt(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (privacy.logPrompt) {
            delegate.log(level, tag, message, throwable)
        } else {
            delegate.log(level, tag, "[prompt redacted: ${privacy.redactionMask}]", throwable)
        }
    }

    /**
     * Records a message that contains response content (token / reasoning /
     * final completion text). Suppressed unless [PrivacyConfig.logResponse]
     * is `true`.
     */
    public fun logResponse(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (privacy.logResponse) {
            delegate.log(level, tag, message, throwable)
        } else {
            delegate.log(level, tag, "[response redacted: ${privacy.redactionMask}]", throwable)
        }
    }

    /**
     * Records a message that contains structured `overrides` JSON.
     * Suppressed unless [PrivacyConfig.logOverrides] is `true`.
     */
    public fun logOverrides(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (privacy.logOverrides) {
            delegate.log(level, tag, message, throwable)
        } else {
            delegate.log(level, tag, "[overrides redacted: ${privacy.redactionMask}]", throwable)
        }
    }

    /**
     * Records outbound HTTP headers.
     *
     * Behaviour:
     *  - When [PrivacyConfig.logHeaders] is `false` → emits a redacted summary
     *    `[headers redacted: ***]`.
     *  - When [PrivacyConfig.logHeaders] is `true` → forwards the headers map
     *    serialised as `key: value` lines, but with any header whose name
     *    matches [SENSITIVE_HEADER_PATTERN] (case-insensitive `Authorization`,
     *    `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`) replaced
     *    by [PrivacyConfig.redactionMask].
     */
    public fun logHeaders(
        level: LogLevel,
        tag: String,
        headers: Map<String, String>,
        throwable: Throwable? = null,
    ) {
        if (!privacy.logHeaders) {
            delegate.log(level, tag, "[headers redacted: ${privacy.redactionMask}]", throwable)
            return
        }
        val rendered = buildString {
            headers.entries.forEachIndexed { idx, (key, value) ->
                if (idx > 0) append('\n')
                append(key).append(": ")
                if (isSensitiveHeader(key)) {
                    append(privacy.redactionMask)
                } else {
                    append(value)
                }
            }
        }
        delegate.log(level, tag, rendered, throwable)
    }

    /**
     * Records the serialised HTTP request body. Suppressed unless
     * [PrivacyConfig.logRequestBody] is `true`. Even when allowed, the body
     * is truncated to [PrivacyConfig.maxLoggedBodyLength] characters with a
     * trailing marker indicating how many characters were dropped.
     */
    public fun logRequestBody(
        level: LogLevel,
        tag: String,
        body: String,
        throwable: Throwable? = null,
    ) {
        if (!privacy.logRequestBody) {
            delegate.log(level, tag, "[request body redacted: ${privacy.redactionMask}]", throwable)
            return
        }
        delegate.log(level, tag, truncate(body), throwable)
    }

    /**
     * Records the serialised HTTP response body. Treated as response content
     * — gated by [PrivacyConfig.logResponse] and capped by
     * [PrivacyConfig.maxLoggedBodyLength].
     */
    public fun logResponseBody(
        level: LogLevel,
        tag: String,
        body: String,
        throwable: Throwable? = null,
    ) {
        if (!privacy.logResponse) {
            delegate.log(level, tag, "[response body redacted: ${privacy.redactionMask}]", throwable)
            return
        }
        delegate.log(level, tag, truncate(body), throwable)
    }

    private fun truncate(payload: String): String {
        val cap = privacy.maxLoggedBodyLength
        return if (payload.length <= cap) {
            payload
        } else {
            val dropped = payload.length - cap
            payload.substring(0, cap) + "... [truncated: $dropped chars dropped]"
        }
    }

    private fun isSensitiveHeader(name: String): Boolean =
        SENSITIVE_HEADER_PATTERN.containsMatchIn(name)

    public companion object {
        /**
         * Header names that are always redacted — even when
         * [PrivacyConfig.logHeaders] is `true` — because their values are
         * credentials.
         */
        public val SENSITIVE_HEADER_PATTERN: Regex = Regex(
            pattern = "^(authorization|proxy-authorization|cookie|set-cookie|x-api-key)$",
            option = RegexOption.IGNORE_CASE,
        )
    }
}
