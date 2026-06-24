package com.ailux.core.response

/**
 * Full (non-streaming) response returned by [LLMProvider.generate].
 *
 * Unlike [LLMEvent], which models a single streaming event,
 * [LLMResponse] represents the complete output of a single request.
 *
 * @property text  The full generated text.
 * @property usage Token usage information, if the provider returned it.
 * @property model The model that actually handled the request:
 *                 - **Cloud / proxy providers**: the value the backend
 *                   echoed back (may differ from [com.ailux.core.request.LLMRequest.model]
 *                   if routing took place).
 *                 - **On-device / native engines**: the provider-derived
 *                   stable id of the loaded model (e.g. `local:gemma-2b-it-int4`),
 *                   guaranteed to be either equal to a non-empty
 *                   `request.model` or to make the request fail before
 *                   generation.
 *                 - May be `null` for legacy providers that don't expose
 *                   a model identifier.
 */
data class LLMResponse(
    val text: String,
    val usage: UsageInfo? = null,
    val model: String? = null,
)
