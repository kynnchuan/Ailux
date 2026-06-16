package com.ailux.provider.backend.config

import okhttp3.OkHttpClient

/**
 * Transport-layer configuration for [BackendProxyProvider].
 *
 * Controls the OkHttpClient that the provider uses for all HTTP requests.
 * Separate from [BackendProxyConfig] (which holds endpoint/auth/routing) to avoid
 * forcing every user to see enterprise-grade transport knobs in auto-complete.
 *
 * Defaults produce a reasonable client (10 s connect / 30 s read / no overall call
 * timeout) suitable for most production scenarios. Customize only when your
 * deployment needs certificate pinning, corporate proxy, mTLS, APM interceptors,
 * or per-service connection pools.
 *
 * ```kotlin
 * // Default (works for most cases)
 * val provider = BackendProxyProvider(config, HttpClientConfig())
 *
 * // Enterprise: certificate pinning via pre-configured client
 * val provider = BackendProxyProvider(
 *     config,
 *     httpConfig = HttpClientConfig(baseHttpClient = pinnedClient),
 * )
 * ```
 *
 * @property connectTimeoutMillis TCP handshake timeout in milliseconds.
 * @property readTimeoutMillis    Socket read timeout in milliseconds.
 * @property callTimeoutMillis    Overall call timeout in milliseconds; 0 = unlimited.
 * @property baseHttpClient       Pre-configured OkHttpClient to reuse (connection pool,
 *                                DNS, interceptors, certificate pinning). The provider
 *                                calls `newBuilder()` on it and applies its own timeout
 *                                overrides. Those overrides can be re-adjusted by
 *                                [customizer].
 * @property customizer           Last-step callback on the `OkHttpClient.Builder`
 *                                right before `build()`. Use for APM interceptors,
 *                                proxy selection, or overriding SDK-applied timeouts.
 *                                Formerly `okhttpClientCustomizer` in the unsplit config.
 */
data class HttpClientConfig(
    val connectTimeoutMillis: Long = 10_000L,
    val readTimeoutMillis: Long = 30_000L,
    val callTimeoutMillis: Long = 0L,
    val baseHttpClient: OkHttpClient? = null,
    val customizer: (OkHttpClient.Builder.() -> Unit)? = null,
) {
    init {
        require(connectTimeoutMillis >= 0) { "connectTimeoutMillis must not be negative" }
        require(readTimeoutMillis >= 0) { "readTimeoutMillis must not be negative" }
        require(callTimeoutMillis >= 0) { "callTimeoutMillis must not be negative" }
    }
}
