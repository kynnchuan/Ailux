package com.ailux.chatdemo.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Chat message data model.
 *
 * Serializable so the UI history can be persisted to disk alongside the
 * SDK's [com.ailux.core.session.SessionSnapshot] — see [com.ailux.chatdemo.ChatPersistence].
 * UI history is render cache only; the SDK snapshot is the source of truth.
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
@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val reasoningContent: String = "",
    val isStreaming: Boolean = false,
    val isReasoning: Boolean = false,
    val usageLabel: String? = null,
    /** URI string of an attached image (user messages only). */
    val imageUri: String? = null,
)
