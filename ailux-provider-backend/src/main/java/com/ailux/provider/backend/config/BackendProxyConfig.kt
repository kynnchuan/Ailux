package com.ailux.provider.backend.config

import com.ailux.core.config.ProviderConfig
import com.ailux.provider.backend.auth.AuthProvider
import com.ailux.provider.backend.auth.RequestSigner

/**
 * Core business configuration for [BackendProxyProvider].
 *
 * Holds endpoint routing, authentication, retry policy, and custom headers —
 * the pieces every project configures. For transport-layer customization
 * (timeouts, certificate pinning, interceptors), see [HttpClientConfig].
 * For protocol adaptation (custom mappers / parsers for non-OpenAI backends),
 * see [ProtocolConfig].
 *
 * ```kotlin
 * // OpenAI-compatible backend (default) — simplest form
 * val provider = BackendProxyProvider(
 *     config = BackendProxyConfig(
 *         baseUrl = "https://api.company.com",
 *         authProvider = AuthProvider { "Bearer ${tokenManager.getAccessToken()}" },
 *     ),
 * )
 *
 * // Anthropic direct API
 * val provider = BackendProxyProvider(
 *     config = BackendProxyConfig(
 *         baseUrl = "https://api.anthropic.com",
 *         headers = mapOf("anthropic-version" to "2023-06-01"),
 *     ),
 *     protocolConfig = ProtocolConfig(
 *         requestMapper = AnthropicRequestMapper(),
 *         streamResponseParser = AnthropicStreamResponseParser(),
 *         nonStreamResponseParser = AnthropicNonStreamResponseParser(),
 *     ),
 * )
 * ```
 *
 * @property baseUrl              Base URL of the backend proxy (required, no trailing slash).
 * @property streamEndpoint       Path of the streaming generation endpoint.
 * @property generateEndpoint     Path of the non-streaming generation endpoint.
 * @property authProvider         Auth provider. `null` means no Authorization header is sent.
 * @property retryPolicy          Retry policy for retriable errors (HTTP 5xx, transient I/O
 *                                failures, etc.). `null` = no retry.
 * @property headers              Extra custom HTTP headers (sent on every request).
 * @property idempotencyHeaderName HTTP header name for the idempotency key.
 *                                The value comes from [LLMRequest.requestId], stable across
 *                                retries for at-most-once semantics. `null` disables injection.
 * @property requestSigner        Optional per-request signer for backends that require a
 *                                request-level integrity signature (HMAC over body, replay
 *                                timestamps, etc.). Runs **after** auth / custom headers /
 *                                idempotency-key, so signers may deliberately overwrite any of
 *                                them. `null` disables signing entirely. See [RequestSigner].
 *                                **(@since 0.2.6)**
 */
data class BackendProxyConfig(
    val baseUrl: String,
    val streamEndpoint: String = "",
    val generateEndpoint: String = "",
    val authProvider: AuthProvider? = null,
    val retryPolicy: RetryPolicy? = null,
    val headers: Map<String, String> = emptyMap(),
    val idempotencyHeaderName: String? = "Idempotency-Key",
    val requestSigner: RequestSigner? = null,
) : ProviderConfig {

    init {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        require(!baseUrl.endsWith("/")) { "baseUrl must not end with '/', got: $baseUrl" }
    }
}
