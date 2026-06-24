package com.ailux.core.request

import com.ailux.core.message.Message
import com.ailux.core.tool.ToolDefinition
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * LLM request payload.
 *
 * v0.2.0: [prompt] has been removed; use [messages] for all conversation turns.
 * The minimal call is `LLMRequest(messages = listOf(Message.User("...")))`.
 *
 * v0.2.1: Added [contextPolicy] for per-request context management policy.
 *
 * v0.2.4: Established the **three-tier extensibility model**:
 * - **Tier 1 — Strong-typed fields** (`stop`, `attachments`): cross-protocol consistent, type-safe.
 * - **Tier 2 — `overrides` escape hatch** (`JsonObject`): vendor-specific or long-tail params,
 *   merged at the top level of the request body by each [RequestMapper].
 * - **Tier 3 — Custom [RequestMapper]**: full control for wholly different protocols.
 *
 * `extras: Map<String,String>` has been replaced by [overrides] (breaking change).
 *
 * Java callers: [@JvmOverloads] generates overloads that respect default values.
 *
 * @property requestId       Unique identifier for this request, used for concurrency
 *                          tracking, cancellation, idempotency header injection, and
 *                          logging. Auto-generated if omitted.
 * @property messages        The conversation messages. The last message is typically
 *                          the current user input.
 * @property tools           Tool definitions available to the model.
 * @property toolChoice      Forces the model to call a specific tool, or "auto".
 * @property role           Message role, defaults to `"user"`. Each provider may
 *                          interpret it according to its own protocol.
 * @property model          Model identifier — semantics depend on the provider.
 *                          An empty string means "use the provider default model".
 *
 *                          - **Cloud / proxy providers**: forwarded to the
 *                            backend, which decides whether to route the
 *                            request to a different deployment. The returned
 *                            [com.ailux.core.response.LLMResponse.model] reflects
 *                            what the backend actually used.
 *                          - **On-device / native engines** (e.g. LiteRT-LM):
 *                            cannot be re-routed per request — one engine
 *                            instance owns exactly one loaded model file.
 *                            If non-empty and it does not match the loaded
 *                            model's id (e.g. `local:gemma-2b-it-int4`), the
 *                            provider surfaces [com.ailux.core.error.ErrorCode.MODEL_NOT_FOUND]
 *                            instead of silently ignoring the field.
 *                            See [com.ailux.core.session.SessionConfig.modelId].
 * @property temperature    Sampling temperature; higher means more randomness.
 * @property topP           Nucleus sampling threshold (top-p).
 * @property maxTokens      Maximum number of generated tokens for this single
 *                          request. `null` means use the provider's default.
 *
 *                          - **Cloud / proxy providers**: enforced on the
 *                            backend (mapped to `max_tokens` for OpenAI,
 *                            `max_tokens` for Anthropic). Generation truly
 *                            stops at the cap.
 *                          - **On-device / native engines**:
 *                            - llama.cpp, etc.: enforced on the producer
 *                              when the engine has a per-request entry.
 *                            - LiteRT-LM 0.13.x: **no per-request entry
 *                              point**; enforcement is consumer-side only
 *                              (the provider tags `FinishReason.LENGTH` once
 *                              the count reached the cap, but the engine
 *                              keeps generating to natural EOS until the
 *                              consumer detaches). For an actual native-side
 *                              hard limit, set
 *                              [com.ailux.core.config.LocalRuntimeConfig.maxOutputTokens]
 *                              at provider construction (engine-level
 *                              runaway guard).
 * @property contextPolicy  Per-request policy for context management components
 *                          (strategy, protector, tokenCounter, aggressiveness).
 *                          `null` means use the global configuration from AiluxConfig.
 * @property stop           Stop sequences. When the model generates any of these strings,
 *                          it stops immediately. Mapped to `stop` (OpenAI) or
 *                          `stop_sequences` (Anthropic) by the RequestMapper.
 * @property attachments    Multimodal attachments (images, documents, audio, etc.) to send
 *                          with this request. See [Attachment] for transport details.
 * @property overrides      Structured escape hatch for vendor-specific parameters.
 *                          A [JsonObject] whose keys are merged at the **top level** of
 *                          the serialized request body by the RequestMapper. Can express
 *                          any JSON type (objects, arrays, numbers, booleans). Same-name
 *                          keys **override** strong-typed fields — use with caution.
 *                          Replaces the former `extras: Map<String,String>`.
 */
@Serializable
data class LLMRequest @JvmOverloads constructor(
    val requestId: String = UUID.randomUUID().toString(),
    val messages: List<Message>,
    val tools: List<ToolDefinition> = emptyList(),
    val toolChoice: String? = null,
    val role: String = "user",
    val model: String = "",
    val temperature: Float = 0.7f,
    val topP: Float = 1.0f,
    val topK: Int? = null,
    val maxTokens: Int? = null,
    @kotlinx.serialization.Transient
    val contextPolicy: ContextPolicy? = null,
    val stop: List<String> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val overrides: JsonObject = JsonObject(emptyMap())
)
