package com.ailux.demo

import android.app.Application
import com.ailux.api.AiluxClient
import com.ailux.api.AiluxConfig
import com.ailux.provider.backend.auth.AuthProvider
import com.ailux.provider.backend.config.BackendProxyConfig
import com.ailux.provider.backend.BackendProxyProvider
import com.ailux.provider.mock.MockProvider

/**
 * Demo Application: initializes the Ailux SDK.
 *
 * Reads `baseUrl` and `apiKey` from BuildConfig and constructs a
 * [BackendProxyProvider] together with an [AiluxClient]. The client is exposed
 * to the ViewModel layer through the [ailuxClient] property.
 */
class AiluxDemoApp : Application() {

    lateinit var ailuxClient: AiluxClient
        private set

    /** MockProvider acts as a fallback when baseUrl is blank, so the demo is always usable. */
    val isConfigured: Boolean
        get() = true

    /** The Provider mode the demo is currently using; shown as a hint at the top of the UI. */
    val providerModeLabel: String
        get() = if (BuildConfig.AILUX_BASE_URL.isBlank()) {
            "MockProvider · Offline demo mode"
        } else {
            "BackendProxyProvider · Backend proxy mode"
        }

    override fun onCreate() {
        super.onCreate()

        val baseUrl = BuildConfig.AILUX_BASE_URL
        val apiKey = BuildConfig.AILUX_API_KEY

        val config = if (baseUrl.isBlank()) {
            // Fall back to MockProvider when configuration is missing,
            // so the demo runs without an API key or backend service.
            val provider = MockProvider(
                tokenDelayMillis = 1L,
                reasoningDelayMillis = 1L,
            )

            AiluxConfig.Builder()
                .setProvider(provider)
                .build()
        } else {
            val providerConfig = BackendProxyConfig(
                baseUrl = baseUrl,
                streamEndpoint = "/chat/completions",
                authProvider = if (apiKey.isNotBlank()) {
                    AuthProvider { "Bearer $apiKey" }
                } else {
                    null
                },
            )
            val provider = BackendProxyProvider(config = providerConfig)

            AiluxConfig.Builder()
                .setProvider(provider)
                .setProviderConfig(providerConfig)
                .build()
        }

        ailuxClient = AiluxClient(config)
    }
}
