package com.ailux.core.response

/**
 * Full (non-streaming) response returned by [LLMProvider.generate].
 *
 * Unlike [LLMEvent], which models a single streaming event,
 * [LLMResponse] represents the complete output of a single request.
 *
 * @property text  The full generated text.
 * @property usage Token usage information, if the provider returned it.
 * @property model The model that actually handled the request (may differ from
 *                 the requested model if the backend performed routing).
 */
data class LLMResponse(
    val text: String,
    val usage: UsageInfo? = null,
    val model: String? = null,
)
