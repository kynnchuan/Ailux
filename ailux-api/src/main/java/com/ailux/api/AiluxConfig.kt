package com.ailux.api

import com.ailux.api.context.DefaultLLMContextManager
import com.ailux.core.context.LLMContextManager
import com.ailux.core.LLMProvider
import com.ailux.core.concurrency.ConcurrencyPolicy
import com.ailux.core.config.ModelConfig
import com.ailux.core.config.ProviderConfig
import com.ailux.core.context.TrimAggressiveness
import com.ailux.core.logging.AiluxLogger
import com.ailux.core.logging.NoopAiluxLogger
import com.ailux.core.privacy.PrivacyConfig
import com.ailux.core.stream.StreamConfig

/**
 * Global / per-instance configuration for the Ailux SDK.
 *
 * Constructed via [Builder]; immutable once created.
 *
 * ```kotlin
 * val config = AiluxConfig.Builder()
 *     .setProvider(myProvider)
 *     .setProviderConfig(BackendProxyConfig(baseUrl = "..."))
 *     .setModelConfig(ModelConfig(name = "gpt-4o"))
 *     .setTimeoutMillis(30_000)
 *     .setRetryCount(2)
 *     .setLogger(AndroidAiluxLogger())          // optional — defaults to Noop
 *     .setPrivacyConfig(PrivacyConfig.DEBUG_VERBOSE) // optional — defaults to SECURE_DEFAULT
 *     .build()
 * ```
 *
 * @property provider          the active [LLMProvider] instance.
 * @property providerConfig    provider-specific configuration (e.g. `BackendProxyConfig`),
 *                             assembled by the app layer; type safety is enforced by each
 *                             provider via downcasting.
 * @property modelConfig       model metadata used to auto-resolve the token budget.
 * @property contextManager    the context manager for automatic message trimming.
 *                             Set to `null` to disable automatic trimming.
 * @property trimAggressiveness controls how aggressively digested FC groups are trimmed.
 * @property timeoutMillis     per-request timeout in milliseconds. `0` means no limit.
 * @property retryCount        automatic retry count after a failure. Only applied to errors
 *                             where [com.ailux.core.error.ErrorCode.retriable] is `true`.
 * @property logger            sink that receives **already-redacted** SDK log messages.
 *                             Defaults to [NoopAiluxLogger] — the SDK is silent until the
 *                             host app wires a real logger (e.g. `AndroidAiluxLogger`).
 * @property privacy           privacy policy applied to logging before the message reaches
 *                             [logger]. Defaults to [PrivacyConfig.SECURE_DEFAULT].
 * @property extras            reserved key-value bag for the business layer to pass custom parameters.
 */
class AiluxConfig private constructor(
    val provider: LLMProvider,
    val providerConfig: ProviderConfig?,
    val modelConfig: ModelConfig? = null,
    val contextManager: LLMContextManager? = DefaultLLMContextManager.default(),
    val trimAggressiveness: TrimAggressiveness = TrimAggressiveness.CONSERVATIVE,
    val timeoutMillis: Long,
    val retryCount: Int,
    val concurrencyPolicy: ConcurrencyPolicy = ConcurrencyPolicy.PARALLEL,
    val streamConfig: StreamConfig = StreamConfig(),
    val logger: AiluxLogger = NoopAiluxLogger,
    val privacy: PrivacyConfig = PrivacyConfig.SECURE_DEFAULT,
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
        private var modelConfig: ModelConfig? = null
        private var contextManager: LLMContextManager? = DefaultLLMContextManager.default()
        private var trimAggressiveness: TrimAggressiveness = TrimAggressiveness.CONSERVATIVE
        private var timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
        private var retryCount: Int = DEFAULT_RETRY_COUNT
        private var concurrencyPolicy: ConcurrencyPolicy = ConcurrencyPolicy.PARALLEL
        private var streamConfig: StreamConfig = StreamConfig()
        private var logger: AiluxLogger = NoopAiluxLogger
        private var privacy: PrivacyConfig = PrivacyConfig.SECURE_DEFAULT
        private var extras: MutableMap<String, Any> = mutableMapOf()

        /** Set the active [LLMProvider] instance. **Required.** */
        fun setProvider(provider: LLMProvider) = apply {
            this.provider = provider
        }

        /** Set provider-specific configuration. */
        fun setProviderConfig(config: ProviderConfig) = apply {
            this.providerConfig = config
        }

        /**
         * Set the model configuration (name, context window, reply reserve).
         * Used by [LLMContextManager] to compute the token budget automatically.
         */
        fun setModelConfig(config: ModelConfig) = apply {
            this.modelConfig = config
        }

        /**
         * Set the context manager. Pass `null` to disable automatic context trimming.
         * Defaults to [DefaultLLMContextManager.default].
         */
        fun setContextManager(manager: LLMContextManager?) = apply {
            this.contextManager = manager
        }

        /**
         * Set the trim aggressiveness level for context management.
         * Defaults to [TrimAggressiveness.CONSERVATIVE].
         */
        fun setTrimAggressiveness(aggressiveness: TrimAggressiveness) = apply {
            this.trimAggressiveness = aggressiveness
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

        /**
         * Set the concurrency policy for parallel/serial request handling.
         * Defaults to [ConcurrencyPolicy.PARALLEL].
         *
         * @see ConcurrencyPolicy
         */
        fun setConcurrencyPolicy(policy: ConcurrencyPolicy) = apply {
            this.concurrencyPolicy = policy
        }

        /**
         * Set the stream health configuration (stall detection timeouts).
         * Defaults to a [StreamConfig] with all timeouts at 0 (disabled).
         *
         * @see StreamConfig
         */
        fun setStreamConfig(config: StreamConfig) = apply {
            this.streamConfig = config
        }

        /**
         * Set the SDK logger sink. Defaults to [NoopAiluxLogger] — silent.
         *
         * The SDK does not call this sink directly. Every message is filtered
         * through `RedactingLogSink` first, which applies the active
         * [PrivacyConfig] before forwarding.
         */
        fun setLogger(logger: AiluxLogger) = apply {
            this.logger = logger
        }

        /**
         * Set the privacy policy applied to all SDK logging.
         * Defaults to [PrivacyConfig.SECURE_DEFAULT].
         */
        fun setPrivacyConfig(privacy: PrivacyConfig) = apply {
            this.privacy = privacy
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
                modelConfig = modelConfig,
                contextManager = contextManager,
                trimAggressiveness = trimAggressiveness,
                timeoutMillis = timeoutMillis,
                retryCount = retryCount,
                concurrencyPolicy = concurrencyPolicy,
                streamConfig = streamConfig,
                logger = logger,
                privacy = privacy,
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
