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
 *
 * @property modelId
 *   Stable identifier of the model this session is bound to, set by the
 *   provider at `openSession` time.
 *
 *   - For native on-device engines (e.g. LiteRT-LM), one engine == one
 *     loaded model file, so this is derived from the model source (e.g.
 *     `"local:gemma-2b-it-int4"`) and **does not change** for the lifetime
 *     of the session. Per-request [LLMRequest.model] **cannot** re-route to
 *     a different model — switching models requires re-opening the session
 *     and a cold reload.
 *   - For cloud / proxy providers, this is whichever model the provider
 *     considers authoritative for the session (typically the configured
 *     default; can still be overridden per-request via [LLMRequest.model]
 *     when the backend supports it).
 *
 *   When non-null this value is surfaced as [com.ailux.core.response.LLMResponse.model]
 *   for the bare-Session [com.ailux.core.session.Session.generate] path. The
 *   AiluxClient pipeline path takes the model name from
 *   `AiluxConfig.modelConfig` instead.
 *
 *   **Provider-set**: application code should not pass a value here when
 *   calling `provider.openSession(SessionConfig(...))`; any value it does pass
 *   will be overwritten by the provider.
 */
@Serializable
data class SessionConfig(
    val systemInstruction: String? = null,
    val initialMessages: List<Message> = emptyList(),
    val samplerOverrides: JsonObject = JsonObject(emptyMap()),
    val messageConcurrencyPolicy: MessageConcurrencyPolicy = MessageConcurrencyPolicy.ENQUEUE,
    val providerHint: JsonObject = JsonObject(emptyMap()),
    val modelId: String? = null,
)
