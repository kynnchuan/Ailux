package com.ailux.core.request

import com.ailux.core.message.Message
import com.ailux.core.tool.ToolDefinition
import kotlinx.serialization.Serializable

/**
 * LLM request payload.
 *
 * v0.2.0: [prompt] has been removed; use [messages] for all conversation turns.
 * The minimal call is `LLMRequest(messages = listOf(Message.User("...")))`.
 *
 * Fields that are not yet first-class (custom backend headers, vendor-specific
 * parameters, ...) can be passed via [extras] as String key/value pairs.
 *
 * Java callers: [@JvmOverloads] generates overloads that respect default values.
 *
 * @property messages     The conversation messages. The last message is typically
 *                       the current user input.
 * @property tools        Tool definitions available to the model.
 * @property toolChoice   Forces the model to call a specific tool, or "auto".
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
    val messages: List<Message>,
    val tools: List<ToolDefinition>,
    val toolChoice: String? = null,
    val role: String = "user",
    val model: String = "",
    val temperature: Float = 0.7f,
    val topP: Float = 1.0f,
    val maxTokens: Int? = null,
    val extras: Map<String, String> = emptyMap(),
)
