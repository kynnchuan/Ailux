package com.ailux.provider.backend.config

import com.ailux.provider.backend.auth.AuthProvider

/**
 * Builds a [BackendProxyConfig] for **BYOK direct-to-cloud LLM** access.
 *
 * This is the **only** official entry point for Ailux's "direct mode". It is
 * intentionally not a separate Provider class, nor a separate Gradle module —
 * this thin "config factory" exists for one reason only: **to act as the
 * carrier for the [DirectCloudUsage] opt-in annotation**, so that
 * "putting an API key into the client process" is explicitly flagged at the
 * source level.
 *
 * ## Differences from the production Backend Proxy path
 *
 * | Aspect | Backend Proxy (production default) | Direct mode (this function) |
 * |---|---|---|
 * | API key location | Company AI Gateway (server side) | Inside the client process (!) |
 * | Auth header | Short-lived token / company auth | `Authorization: Bearer <long-lived API key>` |
 * | Use cases | Production apps, enterprise SDKs | Debug, demos, personal tools, BYOK |
 * | How to construct | [BackendProxyConfig] directly | Requires `@OptIn(DirectCloudUsage::class)` |
 *
 * ## Usage
 *
 * ```kotlin
 * @OptIn(DirectCloudUsage::class)
 * val (config, httpConfig) = directCloudConfig(
 *     baseUrl = "https://api.deepseek.com",
 *     apiKey = BuildConfig.DEEPSEEK_API_KEY, // inject in debug builds only
 *     streamEndpoint = "/chat/completions",
 * )
 * val provider = BackendProxyProvider(config, httpConfig = httpConfig)
 * ```
 *
 * @param baseUrl           Root URL of the third-party LLM API (no trailing slash),
 *                          e.g. `https://api.deepseek.com`, `https://api.openai.com`.
 * @param apiKey            Long-lived API key of the third-party LLM. **Never** ship this in production builds.
 * @param streamEndpoint    Path of the streaming generation endpoint. Defaults to `/chat/completions`.
 * @param generateEndpoint  Path of the non-streaming generation endpoint. Defaults to `/chat/completions`.
 * @param extraHeaders      Extra custom headers (e.g. `OpenAI-Organization`).
 * @param httpConfig        Transport-layer configuration (timeouts, client injection).
 *                          Defaults to standard timeouts; customize for long-generation needs.
 *
 * @see DirectCloudUsage
 * @see BackendProxyConfig
 * @see HttpClientConfig
 */
@DirectCloudUsage
fun directCloudConfig(
    baseUrl: String,
    apiKey: String,
    streamEndpoint: String = "/chat/completions",
    generateEndpoint: String = "/chat/completions",
    extraHeaders: Map<String, String> = emptyMap(),
    httpConfig: HttpClientConfig = HttpClientConfig(),
): Pair<BackendProxyConfig, HttpClientConfig> {
    require(apiKey.isNotBlank()) { "apiKey must not be blank (BYOK direct mode requires a long-lived API key)" }
    val config = BackendProxyConfig(
        baseUrl = baseUrl,
        streamEndpoint = streamEndpoint,
        generateEndpoint = generateEndpoint,
        authProvider = AuthProvider { "Bearer $apiKey" },
        headers = extraHeaders,
    )
    return config to httpConfig
}
