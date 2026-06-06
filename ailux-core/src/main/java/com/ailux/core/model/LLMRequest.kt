package com.ailux.core.model

import kotlinx.serialization.Serializable

/**
 * LLM request payload.
 *
 * Designed as a simple data class with sensible defaults; the minimal call only
 * needs `LLMRequest(prompt = "...")`.
 *
 * Fields that are not yet first-class (custom backend headers, vendor-specific
 * parameters, ...) can be passed via [extras] as String key/value pairs. As the
 * API evolves (v0.2+: messages, systemPrompt, ...), high-frequency fields will
 * be promoted from extras to typed properties.
 *
 * Java callers: [@JvmOverloads] generates overloads that respect default values.
 *
 * @property prompt      The user prompt text.
 * @property role        Message role, defaults to `"user"`. Each provider may
 *                       interpret it according to its own protocol.
 * @property model       Model identifier (semantics depend on the provider).
 *                       An empty string means "use the provider default model".
 * @property temperature Sampling temperature; higher means more randomness.
 * @property topP        Nucleus sampling threshold (top-p).
 * @property maxTokens   Maximum number of generated tokens. `null` means use
 *                       the provider's default.
 * @property extras      Arbitrary key/value pairs forwarded to the provider.
 */
@Serializable
data class LLMRequest @JvmOverloads constructor(
    val prompt: String,
    val role: String = "user",
    val model: String = "",
    val temperature: Float = 0.7f,
    val topP: Float = 1.0f,
    val maxTokens: Int? = null,
    val extras: Map<String, String> = emptyMap(),
)
