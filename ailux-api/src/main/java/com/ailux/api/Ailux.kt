package com.ailux.api

import com.ailux.core.model.LLMRequest
import com.ailux.core.model.LLMResponse
import com.ailux.core.model.LLMEvent
import com.ailux.core.model.LLMTaskState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Static singleton entry point of the Ailux SDK.
 *
 * Internally delegates to a global default [AiluxClient] instance and offers
 * a zero-configuration, call-from-anywhere style. It fits the common case of
 * "one global configuration shared across the whole app".
 *
 * [init] must be called before any other API:
 *
 * ```kotlin
 * // Application.onCreate()
 * Ailux.init(
 *     AiluxConfig.Builder()
 *         .setProvider(myProvider)
 *         .setTimeoutMillis(30_000)
 *         .build()
 * )
 *
 * // Anywhere later
 * Ailux.streamGenerate(LLMRequest(prompt = "Hello")).collect { ... }
 * ```
 *
 * For multi-instance scenarios, use [AiluxClient] directly.
 */
object Ailux {

    @Volatile
    private var defaultClient: AiluxClient? = null

    /**
     * Initialize the SDK.
     *
     * Typically called once in `Application.onCreate()`.
     * Calling it again replaces the previous default Client (the old one will be released).
     *
     * @param config SDK configuration.
     */
    fun init(config: AiluxConfig) {
        defaultClient?.release()
        defaultClient = AiluxClient(config)
    }

    /** Current task state. Equivalent to [AiluxClient.state] on the default Client. */
    val state: StateFlow<LLMTaskState>
        get() = requireClient().state

    /**
     * Streaming generation. Equivalent to [AiluxClient.streamGenerate] on the default Client.
     *
     * @see AiluxClient.streamGenerate
     */
    fun streamGenerate(request: LLMRequest): Flow<LLMEvent> =
        requireClient().streamGenerate(request)

    /**
     * Non-streaming generation. Equivalent to [AiluxClient.generate] on the default Client.
     *
     * @see AiluxClient.generate
     */
    suspend fun generate(request: LLMRequest): LLMResponse =
        requireClient().generate(request)

    /**
     * Cancel the in-flight request. Equivalent to [AiluxClient.cancel] on the default Client.
     *
     * @see AiluxClient.cancel
     */
    fun cancel() = requireClient().cancel()

    /**
     * Release resources held by the default Client.
     *
     * After this call, [init] must be invoked again before further use.
     */
    fun release() {
        defaultClient?.release()
        defaultClient = null
    }

    /**
     * Returns the default Client, or throws an explicit exception if not initialized.
     */
    private fun requireClient(): AiluxClient =
        defaultClient
            ?: throw IllegalStateException(
                "Ailux is not initialized. Call Ailux.init(config) first."
            )
}
