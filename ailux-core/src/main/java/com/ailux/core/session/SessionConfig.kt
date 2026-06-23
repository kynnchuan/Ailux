package com.ailux.core.session

import com.ailux.core.concurrency.MessageConcurrencyPolicy
import com.ailux.core.message.Message
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Configuration used when opening a new [Session] via `LLMProvider.openSession`.
 *
 * All fields are optional. The minimal call is `provider.openSession(SessionConfig())`,
 * which yields an empty session with the default model and sampling parameters.
 *
 * @property systemInstruction
 *   Optional system prompt that anchors the conversation persona / behavior.
 *   For local engines this is fed into the KV cache **once** at session open
 *   so subsequent turns don't pay the prefill cost.
 *
 * @property initialMessages
 *   Optional pre-loaded conversation history. Useful for forking a session from
 *   an existing transcript, or for providing few-shot examples.
 *   The list is treated as the prefix that already happened — neither the
 *   `User` nor `Assistant` entries will be re-generated.
 *
 * @property samplerOverrides
 *   Session-scoped defaults for sampling parameters (e.g. `temperature`, `topP`,
 *   `topK`, `maxTokens`). Per-call values in [LLMRequest] still take precedence;
 *   this is just where the application sets the session's "personality".
 *   A [JsonObject] whose keys map directly to [LLMRequest] fields.
 *
 * @property messageConcurrencyPolicy
 *   How concurrent calls to [Session.streamGenerate] **on this session** are
 *   handled. Default is [MessageConcurrencyPolicy.ENQUEUE] — message ordering
 *   is preserved, which is what conversational UIs almost always want.
 *
 * @property providerHint
 *   Free-form provider-specific hint. For LiteRT-LM this could be e.g.
 *   `{"executionManager": "threaded"}`; for cloud providers it could carry a
 *   tenant id. Most applications can ignore this.
 */
@Serializable
data class SessionConfig(
    val systemInstruction: String? = null,
    val initialMessages: List<Message> = emptyList(),
    val samplerOverrides: JsonObject = JsonObject(emptyMap()),
    val messageConcurrencyPolicy: MessageConcurrencyPolicy = MessageConcurrencyPolicy.ENQUEUE,
    val providerHint: JsonObject = JsonObject(emptyMap())
)
