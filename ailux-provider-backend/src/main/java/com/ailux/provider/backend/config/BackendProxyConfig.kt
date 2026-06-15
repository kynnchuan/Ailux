package com.ailux.provider.backend.config

import com.ailux.core.config.ProviderConfig
import com.ailux.provider.backend.auth.AuthProvider
import com.ailux.provider.backend.mapper.ErrorMapper
import com.ailux.provider.backend.mapper.RequestMapper
import com.ailux.provider.backend.parser.stream.StreamResponseParser
import com.ailux.provider.backend.parser.nonstream.NonStreamResponseParser
import okhttp3.OkHttpClient

/**
 * Configuration for [BackendProxyProvider].
 *
 * Constructed as a data class and immutable once handed to a Provider.
 * All extension hooks are optional — passing `null` falls back to a built-in
 * default implementation.
 *
 * ```kotlin
 * // OpenAI-compatible backend (default)
 * val config = BackendProxyConfig(
 *     baseUrl = "https://api.company.com",
 *     streamEndpoint = "/ai/chat/stream",
 *     authProvider = AuthProvider { "Bearer ${tokenManager.getAccessToken()}" },
 * )
 *
 * // Anthropic direct API
 * val anthropicConfig = BackendProxyConfig(
 *     baseUrl = "https://api.anthropic.com",
 *     streamEndpoint = "/v1/messages",
 *     requestMapper = AnthropicRequestMapper(),
 *     streamResponseParser = AnthropicStreamResponseParser(),
 *     headers = mapOf("anthropic-version" to "2023-06-01", "x-api-key" to apiKey),
 * )
 * ```
 *
 * ## Per-request timeout guidance
 *
 * Some use cases (e.g. long-form generation, chain-of-thought reasoning) need longer
 * timeouts than the configured defaults. Rather than introducing a per-request override
 * field on [LLMRequest] (which would bloat the contract layer), the recommended approach is:
 *
 * 1. **Keep `callTimeoutMillis = 0`** (unlimited) and rely on the stall detection mechanism
 *    (v0.2.3 [StallDetected] events) to surface unresponsive connections — this is the
 *    **preferred** default that works for both short and long generation.
 * 2. If you **must** differentiate timeouts by task type, instantiate separate
 *    [BackendProxyProvider] instances with different timeout configs.
 * 3. For fine-grained per-call control, use [okhttpClientCustomizer] or share [baseHttpClient]
 *    across providers while adjusting only timeouts per instance.
 *
 * @property baseUrl              Base URL of the backend proxy (required, no trailing slash).
 * @property streamEndpoint       Path of the streaming generation endpoint. Defaults to `/v1/llm/chat/stream`.
 * @property generateEndpoint     Path of the non-streaming generation endpoint. Defaults to `/v1/llm/chat`.
 * @property authProvider         Auth provider. `null` means no Authorization header is sent.
 * @property requestMapper        Request body mapper. Falls back to [OpenAIRequestMapper] (OpenAI format) when `null`.
 *                                Use [AnthropicRequestMapper] for Anthropic API.
 * @property streamResponseParser SSE event parser. Falls back to [OpenAIStreamResponseParser] (compatible with DeepSeek, OpenAI, Tongyi Qianwen, etc.) when `null`.
 * @property nonStreamResponseParser
 *                                Parser for non-streaming responses. Falls back to [OpenAINonStreamResponseParser]
 *                                (OpenAI Chat Completions schema) when `null`. Use [AnthropicNonStreamResponseParser]
 *                                for the Anthropic Messages API.
 * @property errorMapper          Error mapper. Falls back to [DefaultErrorMapper] when `null`.
 * @property connectTimeoutMillis OkHttp connect timeout in milliseconds. Defaults to 10 seconds.
 * @property readTimeoutMillis    OkHttp read timeout in milliseconds. For long-lived SSE connections, prefer ≥ 30 seconds.
 * @property callTimeoutMillis    OkHttp overall call timeout in milliseconds. 0 means unlimited (recommended for SSE).
 *                                See "Per-request timeout guidance" below.
 * @property retryPolicy          Retry policy for retriable errors (HTTP 5xx, transient I/O failures, etc.).
 *                                `null` (the default) means **no retry**, equivalent to [RetryPolicy.NONE].
 *                                NOTE: the policy is currently a configuration placeholder; the actual retry
 *                                pipeline in [BackendProxyProvider] is being wired up — behavior may change.
 * @property headers              Extra custom HTTP headers (sent on every request).
 * @property idempotencyHeaderName Name of the HTTP header used to carry the idempotency key
 *                                (defaults to `"Idempotency-Key"`, the IETF draft standard).
 *                                Set to `null` to disable idempotency header injection entirely.
 *                                The value is always [LLMRequest.requestId], which stays stable
 *                                across retries to ensure at-most-once semantics on the server.
 */
data class BackendProxyConfig(
    val baseUrl: String,
    val streamEndpoint: String = "",
    val generateEndpoint: String = "",
    val authProvider: AuthProvider? = null,
    val requestMapper: RequestMapper? = null,
    val streamResponseParser: StreamResponseParser? = null,
    val nonStreamResponseParser: NonStreamResponseParser? = null,
    val errorMapper: ErrorMapper? = null,
    val includeUsageInStream: Boolean = true,
    val baseHttpClient: OkHttpClient? = null,
    val okhttpClientCustomizer: (OkHttpClient.Builder.() -> Unit)? = null,
    val connectTimeoutMillis: Long = 10_000L,
    val readTimeoutMillis: Long = 30_000L,
    val callTimeoutMillis: Long = 0L,
    val retryPolicy: RetryPolicy? = null,
    val headers: Map<String, String> = emptyMap(),
    val idempotencyHeaderName: String? = "Idempotency-Key"
) : ProviderConfig {

    init {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        require(!baseUrl.endsWith("/")) { "baseUrl must not end with '/', got: $baseUrl" }
        require(connectTimeoutMillis >= 0) { "connectTimeoutMillis must not be negative" }
        require(readTimeoutMillis >= 0) { "readTimeoutMillis must not be negative" }
        require(callTimeoutMillis >= 0) { "callTimeoutMillis must not be negative" }
    }
}
