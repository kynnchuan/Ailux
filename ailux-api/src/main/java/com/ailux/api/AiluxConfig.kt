package com.ailux.api

import com.ailux.core.LLMProvider
import com.ailux.core.ProviderConfig

/**
 * Global / per-instance configuration for the Ailux SDK.
 *
 * Constructed via [Builder]; immutable once created.
 *
 * ```kotlin
 * val config = AiluxConfig.Builder()
 *     .setProvider(myProvider)
 *     .setProviderConfig(BackendProxyConfig(baseUrl = "..."))
 *     .setTimeoutMillis(30_000)
 *     .setRetryCount(2)
 *     .build()
 * ```
 *
 * @property provider       the active [LLMProvider] instance.
 * @property providerConfig provider-specific configuration (e.g. `BackendProxyConfig`),
 *                          assembled by the app layer; type safety is enforced by each
 *                          provider via downcasting.
 * @property timeoutMillis  per-request timeout in milliseconds. `0` means no limit.
 * @property retryCount     automatic retry count after a failure. Only applied to errors
 *                          where [com.ailux.core.error.ErrorCode.retriable] is `true`.
 * @property extras         reserved key-value bag for the business layer to pass custom parameters.
 */
class AiluxConfig private constructor(
    val provider: LLMProvider,
    val providerConfig: ProviderConfig?,
    val timeoutMillis: Long,
    val retryCount: Int,
    val extras: Map<String, Any>,
) {

    /**
     * Builder for [AiluxConfig].
     *
     * [setProvider] must be called before [build]; otherwise [IllegalStateException] is thrown.
     */
    class Builder {
        private var provider: LLMProvider? = null
        private var providerConfig: ProviderConfig? = null
        private var timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
        private var retryCount: Int = DEFAULT_RETRY_COUNT
        private var extras: MutableMap<String, Any> = mutableMapOf()

        /** Set the active [LLMProvider] instance. **Required.** */
        fun setProvider(provider: LLMProvider) = apply {
            this.provider = provider
        }

        /** Set provider-specific configuration. */
        fun setProviderConfig(config: ProviderConfig) = apply {
            this.providerConfig = config
        }

        /** Set the request timeout in milliseconds. Defaults to [DEFAULT_TIMEOUT_MILLIS]. */
        fun setTimeoutMillis(millis: Long) = apply {
            require(millis >= 0) { "timeoutMillis must be non-negative; got $millis" }
            this.timeoutMillis = millis
        }

        /** Set the automatic retry count. Defaults to [DEFAULT_RETRY_COUNT]. */
        fun setRetryCount(count: Int) = apply {
            require(count >= 0) { "retryCount must be non-negative; got $count" }
            this.retryCount = count
        }

        /** Add a single extras entry. */
        fun putExtra(key: String, value: Any) = apply {
            this.extras[key] = value
        }

        /** Add a batch of extras entries. */
        fun putExtras(extras: Map<String, Any>) = apply {
            this.extras.putAll(extras)
        }

        /**
         * Build the immutable [AiluxConfig] instance.
         *
         * @throws IllegalStateException if [setProvider] has not been called.
         */
        fun build(): AiluxConfig {
            val resolvedProvider = provider
                ?: throw IllegalStateException("setProvider() must be called to provide an LLMProvider instance")

            return AiluxConfig(
                provider = resolvedProvider,
                providerConfig = providerConfig,
                timeoutMillis = timeoutMillis,
                retryCount = retryCount,
                extras = extras.toMap(),
            )
        }
    }

    companion object {
        /** Default request timeout: 30 seconds. */
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L

        /** Default retry count: 0 (no retry). */
        const val DEFAULT_RETRY_COUNT = 0
    }
}
