package com.ailux.chatdemo

import com.ailux.api.AiluxClient
import com.ailux.api.AiluxConfig
import com.ailux.core.config.ModelConfig
import com.ailux.core.context.TrimAggressiveness
import com.ailux.provider.backend.auth.AuthProvider
import com.ailux.provider.backend.config.BackendProxyConfig
import com.ailux.provider.backend.BackendProxyProvider
import com.ailux.provider.mock.MockProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Available provider modes for the demo app.
 */
enum class ProviderMode(val label: String) {
    MOCK("MockProvider · Offline demo mode"),
    BACKEND_PROXY("BackendProxy · Backend proxy mode"),
}

/**
 * Manages AiluxClient lifecycle with runtime-switchable provider modes.
 *
 * Call [initialize] once from [MainActivity.onCreate] or [Application.onCreate].
 * The provider mode can be changed at runtime via [switchProvider].
 * Observers of [providerMode] should react by recreating their ViewModel.
 */
object ChatClientManager {

    lateinit var ailuxClient: AiluxClient
        private set

    /** Observable provider mode state. UI recomposes on change. */
    private val _providerMode = MutableStateFlow(ProviderMode.MOCK)
    val providerMode: StateFlow<ProviderMode> = _providerMode.asStateFlow()

    /**
     * Monotonically increasing generation counter. Incremented on every
     * [switchProvider] call to guarantee a unique ViewModel key — prevents
     * Compose from reusing a stale ViewModel that holds a released client.
     */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    /** Always configured — both modes work out of the box. */
    val isConfigured: Boolean
        get() = true

    /** The Provider mode label shown at the top of the UI. */
    val providerModeLabel: String
        get() = _providerMode.value.label

    // ──────────────────────────────────────────────────────
    // Backend Proxy configuration
    // ──────────────────────────────────────────────────────

    /**
     * Backend proxy base URL. In BackendProxy mode, this is the URL of the
     * backend sample service. On an Android emulator, 10.0.2.2 maps to the
     * host machine's localhost.
     */
    private val backendBaseUrl: String
        get() = BuildConfig.AILUX_BASE_URL.ifBlank { "http://10.0.2.2:8080/api" }

    /**
     * Auth token for the backend proxy. This is NOT the LLM API key —
     * it's the user token for authenticating with the backend service.
     * In BackendProxy mode, the backend manages LLM API keys.
     */
    private val backendAuthToken: String
        get() = BuildConfig.AILUX_API_KEY.ifBlank { "token-pro-001" }

    // ──────────────────────────────────────────────────────

    /** Model configuration for context window budget calculation. */
    private val modelConfig = ModelConfig(
        name = "deepseek-chat",
        reserveForReply = 4096
    )

    /**
     * Initialize the client manager. Should be called once at app startup.
     * Typically called from [MainActivity.onCreate] or [Application.onCreate].
     */
    fun initialize() {
        if (::ailuxClient.isInitialized) return
        ailuxClient = buildClient(_providerMode.value)
    }

    /**
     * Switch the active provider mode at runtime.
     *
     * This releases the current client and builds a new one.
     * The UI layer should observe [providerMode] and recreate its ViewModel
     * when the value changes.
     */
    /**
     * Switch the active provider mode at runtime.
     *
     * This releases the current client, builds a new one, and increments
     * [generation] so that Compose creates a fresh ViewModel (rather than
     * reusing a cached one that holds the released client reference).
     */
    fun switchProvider(mode: ProviderMode) {
        if (mode == _providerMode.value) return
        ailuxClient.release()
        _providerMode.value = mode
        ailuxClient = buildClient(mode)
        _generation.value++
    }

    private fun buildClient(mode: ProviderMode): AiluxClient {
        val config = when (mode) {
            ProviderMode.MOCK -> {
                val provider = MockProvider(
                    tokenDelayMillis = 1L,
                    reasoningDelayMillis = 1L,
                )
                AiluxConfig.Builder()
                    .setProvider(provider)
                    .setModelConfig(modelConfig)
                    .build()
            }

            ProviderMode.BACKEND_PROXY -> {
                val providerConfig = BackendProxyConfig(
                    baseUrl = backendBaseUrl,
                    streamEndpoint = "/chat/completions",
                    authProvider = AuthProvider { "Bearer $backendAuthToken" },
                )
                val provider = BackendProxyProvider(config = providerConfig)
                AiluxConfig.Builder()
                    .setProvider(provider)
                    .setProviderConfig(providerConfig)
                    .setModelConfig(modelConfig)
                    .setTrimAggressiveness(TrimAggressiveness.CONSERVATIVE)
                    .build()
            }
        }
        return AiluxClient(config)
    }
}
