package com.ailux.provider.backend.parser.nonstream

import com.ailux.core.response.LLMResponse

/**
 * Strategy for parsing the full HTTP response body of a non-streaming
 * generation call into a unified [LLMResponse].
 *
 * Each provider (OpenAI-compatible, Anthropic Messages, etc.) ships its own
 * implementation; pick one via
 * [com.ailux.provider.backend.config.BackendProxyConfig.nonStreamResponseParser]
 * to keep [com.ailux.provider.backend.BackendProxyProvider] protocol-agnostic.
 *
 * Implementation contract:
 * - Receive the **raw response body string** of a business-success HTTP
 *   response (HTTP 2xx). HTTP-level errors (4xx/5xx) are handled by the
 *   caller before reaching this parser.
 * - Return a fully populated [LLMResponse]; never return `null`.
 * - Implementations are expected to be **stateless** and **thread-safe**: a
 *   single instance may be shared across concurrent requests.
 * - On malformed payloads, implementations may either throw an
 *   [com.ailux.core.error.LLMException] or return a best-effort
 *   [LLMResponse] (e.g. raw body as text) — choose per provider semantics
 *   and document it on the concrete class.
 */
fun interface NonStreamResponseParser {

    /**
     * Parse the given [body] (raw HTTP response payload, typically JSON) into
     * an [LLMResponse]. See the interface KDoc for contract details.
     */
    fun parse(body: String): LLMResponse
}
