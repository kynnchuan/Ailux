package com.ailux.provider.backend.mapper

import com.ailux.core.error.ErrorCode
import com.ailux.core.error.LLMError
import com.ailux.core.error.LLMException
import com.ailux.core.message.Message
import com.ailux.core.request.Attachment
import com.ailux.core.request.AttachmentSource
import com.ailux.core.request.LLMRequest
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Default request mapper for the Ailux recommended protocol.
 *
 * This mapper produces the **OpenAI Chat Completions** JSON format, which is
 * the de facto standard adopted by 90%+ of LLM providers (OpenAI, DeepSeek,
 * Tongyi Qianwen, Moonshot, GLM, Minimax, Groq, Together AI, etc.).
 *
 * **Important**: This mapper is NOT compatible with the Anthropic Messages API,
 * which uses a different request structure (e.g. `content` blocks instead of
 * `role`/`content` pairs, `tool_use` instead of `tool_calls`). If you need to
 * call Anthropic directly, use [AnthropicRequestMapper] via
 * [BackendProxyConfig.requestMapper].
 *
 * ## Output JSON shape
 *
 * ```json
 * {
 *   "model": "default",
 *   "messages": [
 *     {"role": "user", "content": "Hello"},
 *     {"role": "user", "content": [
 *       {"type": "text", "text": "What is in this image?"},
 *       {"type": "image_url", "image_url": {"url": "https://..."}}
 *     ]},
 *     {"role": "assistant", "tool_calls": [{"id":"...","type":"function","function":{"name":"...","arguments":"..."}}]},
 *     {"role": "tool", "tool_call_id": "call_01", "content": "..."}
 *   ],
 *   "tools": [
 *     {"type": "function", "function": {"name": "...", "description": "...", "parameters": {...}}}
 *   ],
 *   "tool_choice": "auto",
 *   "stream": true,
 *   "temperature": 0.7,
 *   "top_p": 1.0,
 *   "max_tokens": 2048,
 *   "stop": ["\n\n"],
 *   "<overrides keys>": "..."
 * }
 * ```
 *
 * ## Field mapping rules
 *
 * - `model`: uses `LLMRequest.model`; falls back to `"default"` when empty.
 * - `messages`: fully supports multi-turn with system/user/assistant/tool roles.
 *   When `attachments` is non-empty, the last `Message.User`'s content is serialized
 *   as OpenAI multimodal `content` array (text + image_url parts).
 * - `tools`: omitted when `LLMRequest.tools` is empty (no function calling).
 * - `tool_choice`: omitted when `LLMRequest.toolChoice` is null.
 * - `max_tokens`: omitted when `LLMRequest.maxTokens` is null.
 * - `stop`: omitted when `LLMRequest.stop` is empty.
 * - `overrides`: merged at top level as the **last step** via [applyOverrides];
 *   same-name keys override any previously serialized field.
 *
 * ## Multimodal attachments (v0.2.4)
 *
 * - [AttachmentSource.Url] → `{"type":"image_url","image_url":{"url":"<url>"}}`
 * - [AttachmentSource.Base64] → `{"type":"image_url","image_url":{"url":"data:<mimeType>;base64,<data>"}}`
 * - [AttachmentSource.LocalUri] → throws [LLMException] with [ErrorCode.UNSUPPORTED_MODALITY].
 *   Use `Attachment.fromLocalUri()` in `ailux-android` to convert before sending.
 *
 * @see RequestMapper
 * @see applyOverrides
 */
class OpenAIRequestMapper(
    val includeUsageInStream: Boolean = true
) : RequestMapper {

    override fun map(request: LLMRequest, stream: Boolean): String {
        // Pre-validate: LocalUri is not supported over the network
        request.attachments.forEach { att ->
            if (att.source is AttachmentSource.LocalUri) {
                throw LLMException(
                    LLMError(
                        code = ErrorCode.UNSUPPORTED_MODALITY,
                        message = "LocalUri attachments cannot be sent via BackendProxy. " +
                            "Use Attachment.fromLocalUri() to convert to Base64 first."
                    )
                )
            }
        }

        // Determine which User message should carry attachments (the last one)
        val lastUserIndex = request.messages.indexOfLast { it is Message.User }
        val hasAttachments = request.attachments.isNotEmpty() && lastUserIndex >= 0

        val json = buildJsonObject {
            put("model", request.model.ifEmpty { "default" })

            putJsonArray("messages") {
                request.messages.forEachIndexed { index, message ->
                    addJsonObject {
                        when (message) {
                            is Message.System -> {
                                put("role", "system")
                                put("content", message.content)
                            }
                            is Message.User -> {
                                put("role", "user")
                                if (hasAttachments && index == lastUserIndex) {
                                    // Multimodal content-parts array
                                    putJsonArray("content") {
                                        // Text part
                                        addJsonObject {
                                            put("type", "text")
                                            put("text", message.content)
                                        }
                                        // Attachment parts
                                        request.attachments.forEach { att ->
                                            addJsonObject {
                                                put("type", "image_url")
                                                putJsonObject("image_url") {
                                                    put("url", resolveAttachmentUrl(att))
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    put("content", message.content)
                                }
                            }
                            is Message.Assistant -> {
                                put("role", "assistant")
                                message.content?.let { put("content", it) }
                                // Serialize tool_calls if present
                                message.toolCalls?.let { toolCalls ->
                                    putJsonArray("tool_calls") {
                                        toolCalls.forEach { call ->
                                            addJsonObject {
                                                put("id", call.id)
                                                put("type", "function")
                                                putJsonObject("function") {
                                                    put("name", call.name)
                                                    put("arguments", call.arguments ?: "")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            is Message.Tool -> {
                                put("role", "tool")
                                put("tool_call_id", message.toolCallId)
                                put("content", message.content)
                            }
                        }
                    }
                }
            }

            // Serialize tools if present (OpenAI format)
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    request.tools.forEach { tool ->
                        addJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", tool.arguments)
                            }
                        }
                    }
                }

                // tool_choice
                request.toolChoice?.let { put("tool_choice", it) }
            }

            put("stream", stream)
            put("temperature", request.temperature)
            put("top_p", request.topP)

            if (stream && includeUsageInStream) {
                putJsonObject("stream_options") {
                    put("include_usage", true)
                }
            }

            request.maxTokens?.let { put("max_tokens", it) }

            // Stop sequences (v0.2.4)
            if (request.stop.isNotEmpty()) {
                putJsonArray("stop") {
                    request.stop.forEach { add(JsonPrimitive(it)) }
                }
            }

            // Tier-2 escape hatch: overrides merged last (can override anything above)
            applyOverrides(request.overrides)
        }

        return json.toString()
    }

    /**
     * Resolves an [Attachment] into an OpenAI-compatible URL string.
     *
     * - [AttachmentSource.Url] → direct URL
     * - [AttachmentSource.Base64] → `data:<mimeType>;base64,<data>`
     */
    private fun resolveAttachmentUrl(attachment: Attachment): String = when (val src = attachment.source) {
        is AttachmentSource.Url -> src.url
        is AttachmentSource.Base64 -> "data:${attachment.mimeType};base64,${src.data}"
        is AttachmentSource.LocalUri -> error("LocalUri should have been rejected earlier")
    }

    private fun JsonArrayBuilder.addJsonObject(
        block: JsonObjectBuilder.() -> Unit,
    ) {
        add(buildJsonObject(block))
    }
}
