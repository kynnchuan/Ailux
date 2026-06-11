package com.ailux.core.privacy

/**
 * Declarative privacy policy applied to SDK logging.
 *
 * The defaults are **secure by default** — no prompt, response, overrides,
 * HTTP headers, or HTTP request body reaches any
 * [com.ailux.core.logging.AiluxLogger] sink unless the host app explicitly
 * flips the corresponding flag.
 *
 * This config is consumed internally by `RedactingLogSink`, which sits between
 * the SDK's call sites and the user-supplied [com.ailux.core.logging.AiluxLogger].
 *
 * ## Field semantics
 *
 *  - [logPrompt] — when `true`, the full request body (system / user / tool
 *    messages, attachment metadata) may appear in logs. **Off by default.**
 *  - [logResponse] — when `true`, streamed token / reasoning text and final
 *    completion bodies may appear in logs. **Off by default.**
 *  - [logOverrides] — when `true`, the structured `LLMRequest.overrides` JSON
 *    object may appear in logs. **Off by default**, since `overrides` is the
 *    canonical channel for vendor-specific keys that occasionally include
 *    sensitive secrets.
 *  - [logHeaders] — when `true`, outbound HTTP headers (including
 *    `Authorization`) may appear in logs. **Off by default.** Note that
 *    `Authorization` values are still independently scrubbed inside the
 *    `RedactingLogSink.logHeaders` helper.
 *  - [logRequestBody] — when `true`, the full serialised HTTP request body
 *    may appear in logs. **Off by default.**
 *  - [redactionMask] — the literal substituted in place of redacted content.
 *    Pure decoration: it is never parsed.
 *  - [maxLoggedBodyLength] — defensive cap on the number of characters of
 *    request/response body that the SDK is willing to emit, even when the
 *    matching opt-in flag is `true`. Prevents accidental megabyte-sized log
 *    lines. Set to `Int.MAX_VALUE` to disable.
 *
 * ## Example
 *
 * ```kotlin
 * // Production: lock everything down (this is the default).
 * val privacy = PrivacyConfig.SECURE_DEFAULT
 *
 * // Development: opt into prompt + response + overrides logging.
 * val privacy = PrivacyConfig.DEBUG_VERBOSE
 *
 * // Custom: opt into prompt only, keep response off, cap body at 4 KiB.
 * val privacy = PrivacyConfig(
 *     logPrompt = true,
 *     maxLoggedBodyLength = 4096,
 * )
 * ```
 *
 * @since 0.2.5
 */
public data class PrivacyConfig(
    val logPrompt: Boolean = false,
    val logResponse: Boolean = false,
    val logOverrides: Boolean = false,
    val logHeaders: Boolean = false,
    val logRequestBody: Boolean = false,
    val redactionMask: String = "***",
    val maxLoggedBodyLength: Int = 2048,
) {

    init {
        require(maxLoggedBodyLength >= 0) {
            "maxLoggedBodyLength must be non-negative; got $maxLoggedBodyLength"
        }
    }

    public companion object {
        /**
         * Secure-by-default policy. Equivalent to the no-arg constructor.
         *
         * No prompt, response, overrides, headers, or request body content is
         * emitted to any logger sink. Safe for production builds.
         */
        public val SECURE_DEFAULT: PrivacyConfig = PrivacyConfig()

        /**
         * Verbose development policy. Opts into prompt, response, and overrides
         * logging — but keeps [logHeaders] and [logRequestBody] off, since
         * Authorization headers and raw bodies are easy to leak into shipped
         * crash reports even in debug builds.
         *
         * Recommended only for short debugging sessions inside `BuildConfig.DEBUG`.
         */
        public val DEBUG_VERBOSE: PrivacyConfig = PrivacyConfig(
            logPrompt = true,
            logResponse = true,
            logOverrides = true,
        )
    }
}
