package com.ailux.provider.backend

import com.ailux.core.ProviderConfig
import com.ailux.provider.backend.mapper.ErrorMapper
import com.ailux.provider.backend.mapper.RequestMapper
import com.ailux.provider.backend.parser.StreamResponseParser

/**
 * Configuration for [BackendProxyProvider].
 *
 * Constructed as a data class and immutable once handed to a Provider.
 * All extension hooks are optional — passing `null` falls back to a built-in
 * default implementation.
 *
 * ```kotlin
 * val config = BackendProxyConfig(
 *     baseUrl = "https://api.company.com",
 *     streamEndpoint = "/ai/chat/stream",
 *     authProvider = AuthProvider { "Bearer ${tokenManager.getAccessToken()}" },
 * )
 * ```
 *
 * @property baseUrl              Base URL of the backend proxy (required, no trailing slash).
 * @property streamEndpoint       Path of the streaming generation endpoint. Defaults to `/v1/llm/chat/stream`.
 * @property generateEndpoint     Path of the non-streaming generation endpoint. Defaults to `/v1/llm/chat`.
 * @property authProvider         Auth provider. `null` means no Authorization header is sent.
 * @property requestMapper        Request body mapper. Falls back to [DefaultRequestMapper] when `null`.
 * @property streamResponseParser SSE event parser. Falls back to [OpenAIStreamResponseParser] (compatible with DeepSeek, OpenAI, Tongyi Qianwen, etc.) when `null`.
 * @property errorMapper          Error mapper. Falls back to [DefaultErrorMapper] when `null`.
 * @property connectTimeoutMillis OkHttp connect timeout in milliseconds. Defaults to 10 seconds.
 * @property readTimeoutMillis    OkHttp read timeout in milliseconds. For long-lived SSE connections, prefer ≥ 30 seconds.
 * @property callTimeoutMillis    OkHttp overall call timeout in milliseconds. 0 means unlimited (recommended for SSE).
 * @property retryCount           Number of automatic retries on retriable errors. Defaults to 0 (no retry).
 * @property headers              Extra custom HTTP headers (sent on every request).
 */
data class BackendProxyConfig(
    val baseUrl: String,
    val streamEndpoint: String = "",
    val generateEndpoint: String = "",
    val authProvider: AuthProvider? = null,
    val requestMapper: RequestMapper? = null,
    val streamResponseParser: StreamResponseParser? = null,
    val errorMapper: ErrorMapper? = null,
    val connectTimeoutMillis: Long = 10_000L,
    val readTimeoutMillis: Long = 30_000L,
    val callTimeoutMillis: Long = 0L,
    val retryCount: Int = 0,
    val headers: Map<String, String> = emptyMap(),
) : ProviderConfig {

    init {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        require(!baseUrl.endsWith("/")) { "baseUrl must not end with '/', got: $baseUrl" }
        require(connectTimeoutMillis >= 0) { "connectTimeoutMillis must not be negative" }
        require(readTimeoutMillis >= 0) { "readTimeoutMillis must not be negative" }
        require(callTimeoutMillis >= 0) { "callTimeoutMillis must not be negative" }
        require(retryCount >= 0) { "retryCount must not be negative" }
    }
}
