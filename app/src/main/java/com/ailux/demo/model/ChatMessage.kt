package com.ailux.demo.model

import java.util.UUID

/**
 * Chat message data model.
 *
 * @property id Unique identifier, used as the LazyColumn key.
 * @property role Message role: "user" or "assistant".
 * @property content Main reply text of the message.
 * @property reasoningContent Reasoning / chain-of-thought text
 *           (DeepSeek reasoning_content / Anthropic thinking).
 * @property isStreaming Whether the message is currently being streamed
 *           (only meaningful for assistant messages).
 * @property isReasoning Whether reasoning text is currently being emitted
 *           (used to differentiate UI states).
 * @property usageLabel Token usage summary for this reply, displayed once a
 *           Usage event arrives at the end of the stream.
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val reasoningContent: String = "",
    val isStreaming: Boolean = false,
    val isReasoning: Boolean = false,
    val usageLabel: String? = null,
)
