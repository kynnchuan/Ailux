package com.ailux.core.config

/**
 * Provider-lifetime configuration for [com.ailux.provider.local.LocalRuntimeProvider].
 *
 * @property modelSource Where to load the model from.
 * @property verifySha256 Optional SHA-256 of the model file; if non-null, the
 *   provider verifies the file digest before [com.ailux.runtime.InferenceEngine.load]
 *   and throws [com.ailux.core.error.ErrorCode.MODEL_FILE_INVALID] on mismatch.
 * @property minRamMb Optional minimum-RAM override for [DeviceProbe]. When
 *   `null`, the engine's self-estimated `EngineCapabilities.estimatedRamMb` is
 *   used. Set this when you have measured a more accurate floor for a
 *   particular model/device combination.
 * @property maxOutputTokens Optional **engine-level** generation hard cap.
 *
 *   This is **NOT** the same field as per-request [com.ailux.core.request.LLMRequest.maxTokens]:
 *   - `LLMRequest.maxTokens` is the per-call generation budget (the "this answer
 *     should be at most N tokens" knob), enforced **on the producer** by
 *     engines that have a per-request entry (cloud, llama.cpp) and only
 *     **consumer-side** by engines that don't (LiteRT-LM 0.13.x). It is set by
 *     the application on each request.
 *   - `LocalRuntimeConfig.maxOutputTokens` is the **per-engine-instance** hard
 *     limit pushed into the engine at load time. For LiteRT-LM it maps to
 *     `EngineConfig.maxNumTokens` and acts as a runaway guard: if a model
 *     enters a degenerate loop and the per-request limit was not set (or the
 *     engine can't enforce it), this still bounds the native pass.
 *
 *   Change semantics: takes effect at the next `engine.load(config)`. With
 *   LiteRT-LM, changing the value requires `release()` + reload (the upstream
 *   `EngineConfig` is immutable once `Engine.initialize()` has run).
 *
 *   Defaults to `null` — caller has opted out of the engine-level cap and is
 *   responsible for per-request bounds or for accepting unbounded generation.
 */
data class LocalRuntimeConfig(
    val modelSource: ModelSource,
    val verifySha256: String? = null,
    val minRamMb: Int? = null,
    val maxOutputTokens: Int? = null,
): ProviderConfig


sealed interface ModelSource {

    data class LocalPath(val absolutePath: String): ModelSource

}