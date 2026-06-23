package com.ailux.provider.backend.mapper

import com.ailux.core.error.ErrorCode
import com.ailux.core.error.LLMError
import com.ailux.core.error.LLMException
import com.ailux.core.message.Message
import com.ailux.core.request.Attachment
import com.ailux.core.request.AttachmentSource
import com.ailux.core.request.LLMRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Request mapper for the Anthropic Messages API format.
 *
 * Produces the JSON structure expected by `POST /v1/messages` on
 * `api.anthropic.com` and compatible proxies (AWS Bedrock Claude, etc.).
 *
 * ## Key differences from OpenAI format
 *
 * | Aspect | OpenAI | Anthropic |
 * |--------|--------|-----------|
 * | System message | Inside `messages` with role "system" | Top-level `system` field |
 * | Content | Plain string | Array of content blocks |
 * | Tool definitions | `tools[].function` | `tools[].input_schema` |
 * | Tool calls (in assistant) | `tool_calls[].function` | Content block `type: "tool_use"` |
 * | Tool results | `role: "tool"` | `role: "user"` with `type: "tool_result"` block |
 * | Streaming | `"stream": true` | `"stream": true` |
 * | Tool choice | `"tool_choice": "auto"` | `"tool_choice": {"type": "auto"}` |
 * | Stop sequences | `"stop": [...]` | `"stop_sequences": [...]` |
 *
 * ## Multimodal attachments (v0.2.4)
 *
 * Anthropic uses `{"type":"image","source":{...}}` content blocks:
 * - [AttachmentSource.Url] → `{"type":"image","source":{"type":"url","url":"<url>"}}`
 * - [AttachmentSource.Base64] → `{"type":"image","source":{"type":"base64","media_type":"<mimeType>","data":"<data>"}}`
 * - [AttachmentSource.LocalUri] → throws [LLMException] with [ErrorCode.UNSUPPORTED_MODALITY].
 *
 * ## Output JSON shape
 *
 * ```json
 * {
 *   "model": "claude-3-5-sonnet-20241022",
 *   "system": "You are a helpful assistant.",
 *   "messages": [
 *     {"role": "user", "content": [{"type": "text", "text": "Hello"}]},
 *     {"role": "user", "content": [
 *       {"type": "text", "text": "What is in this image?"},
 *       {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "..."}}
 *     ]},
 *     {"role": "assistant", "content": [
 *       {"type": "text", "text": "I'll check the weather."},
 *       {"type": "tool_use", "id": "toolu_01", "name": "get_weather", "input": {"city": "Beijing"}}
 *     ]},
 *     {"role": "user", "content": [
 *       {"type": "tool_result", "tool_use_id": "toolu_01", "content": "{\"temp\":\"22°C\"}"}
 *     ]}
 *   ],
 *   "tools": [
 *     {"name": "get_weather", "description": "...", "input_schema": {...}}
 *   ],
 *   "tool_choice": {"type": "auto"},
 *   "max_tokens": 4096,
 *   "temperature": 0.7,
 *   "top_p": 1.0,
 *   "top_k": 40,
 *   "stop_sequences": ["\n\n"],
 *   "stream": true,
 *   "<overrides keys>": "..."
 * }
 * ```
 *
 * ## Sampling fields
 *
 * - `top_k`: omitted when `LLMRequest.topK` is null. Native Anthropic field;
 *   mapped 1:1 to top-level `top_k`.
 *
 * ## Usage
 *
 * ```kotlin
 * val config = BackendProxyConfig(
 *     baseUrl = "https://api.anthropic.com",
 *     streamEndpoint = "/v1/messages",
 *     requestMapper = AnthropicRequestMapper(),
 *     streamResponseParser = AnthropicStreamResponseParser(),
 *     headers = mapOf(
 *         "anthropic-version" to "2023-06-01",
 *         "x-api-key" to apiKey,
 *     ),
 * )
 * ```
 *
 * @see OpenAIRequestMapper
 * @see AnthropicStreamResponseParser
 * @see RequestMapper
 */
class AnthropicRequestMapper : RequestMapper {

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
        val nonSystemMessages = request.messages.filter { it !is Message.System }
        val lastUserIndex = nonSystemMessages.indexOfLast { it is Message.User }
        val hasAttachments = request.attachments.isNotEmpty() && lastUserIndex >= 0

        val json = buildJsonObject {
            put("model", request.model.ifEmpty { "claude-3-5-sonnet-20241022" })

            // Anthropic: system prompt is a top-level field, not inside messages
            val systemContent = request.messages
                .filterIsInstance<Message.System>()
                .joinToString("\n\n") { it.content }
            if (systemContent.isNotEmpty()) {
                put("system", systemContent)
            }

            // Build messages (excluding System, which is top-level)
            putJsonArray("messages") {
                // Anthropic requires alternating user/assistant turns.
                // Tool results must be wrapped as user messages with tool_result blocks.
                var i = 0
                while (i < nonSystemMessages.size) {
                    val msg = nonSystemMessages[i]
                    when (msg) {
                        is Message.User -> {
                            addJsonObject {
                                put("role", "user")
                                putJsonArray("content") {
                                    // Text content block
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", msg.content)
                                    }
                                    // Multimodal: append attachment blocks to the last user message
                                    if (hasAttachments && i == lastUserIndex) {
                                        request.attachments.forEach { att ->
                                            addAttachmentBlock(att)
                                        }
                                    }
                                }
                            }
                        }
                        is Message.Assistant -> {
                            addJsonObject {
                                put("role", "assistant")
                                putJsonArray("content") {
                                    // Text content (if any)
                                    msg.content?.let { text ->
                                        if (text.isNotEmpty()) {
                                            addJsonObject {
                                                put("type", "text")
                                                put("text", text)
                                            }
                                        }
                                    }
                                    // Tool use blocks (if any)
                                    msg.toolCalls?.forEach { call ->
                                        addJsonObject {
                                            put("type", "tool_use")
                                            put("id", call.id)
                                            put("name", call.name)
                                            // Parse arguments JSON string into a JsonObject for "input"
                                            val inputObj = call.arguments?.let { args ->
                                                try {
                                                    Json.parseToJsonElement(args)
                                                } catch (_: Exception) {
                                                    buildJsonObject {} // empty object on parse failure
                                                }
                                            } ?: buildJsonObject {}
                                            put("input", inputObj)
                                        }
                                    }
                                }
                            }
                        }
                        is Message.Tool -> {
                            // Anthropic: tool results go inside a "user" message
                            // with content blocks of type "tool_result".
                            // Collect consecutive Tool messages into one user message.
                            val toolResults = mutableListOf(msg)
                            while (i + 1 < nonSystemMessages.size &&
                                nonSystemMessages[i + 1] is Message.Tool
                            ) {
                                i++
                                toolResults.add(nonSystemMessages[i] as Message.Tool)
                            }
                            addJsonObject {
                                put("role", "user")
                                putJsonArray("content") {
                                    toolResults.forEach { toolMsg ->
                                        addJsonObject {
                                            put("type", "tool_result")
                                            put("tool_use_id", toolMsg.toolCallId)
                                            put("content", toolMsg.content)
                                        }
                                    }
                                }
                            }
                        }
                        is Message.System -> { /* already handled as top-level */ }
                    }
                    i++
                }
            }

            // Tools (Anthropic format)
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    request.tools.forEach { tool ->
                        addJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", tool.arguments)
                        }
                    }
                }

                // tool_choice (Anthropic uses object form)
                request.toolChoice?.let { choice ->
                    putJsonObject("tool_choice") {
                        when (choice) {
                            "auto" -> put("type", "auto")
                            "none" -> put("type", "none")
                            "required", "any" -> put("type", "any")
                            else -> {
                                // Specific tool name
                                put("type", "tool")
                                put("name", choice)
                            }
                        }
                    }
                }
            }

            // Anthropic requires max_tokens (mandatory field)
            put("max_tokens", request.maxTokens ?: 4096)
            put("temperature", request.temperature)
            put("top_p", request.topP)
            // Native Anthropic field; mapped 1:1.
            request.topK?.let { put("top_k", it) }

            // Stop sequences (v0.2.4): Anthropic uses "stop_sequences" instead of "stop"
            if (request.stop.isNotEmpty()) {
                putJsonArray("stop_sequences") {
                    request.stop.forEach { add(JsonPrimitive(it)) }
                }
            }

            put("stream", stream)

            // Tier-2 escape hatch: overrides merged last (can override anything above)
            applyOverrides(request.overrides)
        }

        return json.toString()
    }

    /**
     * Appends an Anthropic image content block for the given [attachment].
     *
     * - [AttachmentSource.Url] → `{"type":"image","source":{"type":"url","url":"..."}}`
     * - [AttachmentSource.Base64] → `{"type":"image","source":{"type":"base64","media_type":"...","data":"..."}}`
     */
    private fun JsonArrayBuilder.addAttachmentBlock(attachment: Attachment) {
        addJsonObject {
            put("type", "image")
            putJsonObject("source") {
                when (val src = attachment.source) {
                    is AttachmentSource.Url -> {
                        put("type", "url")
                        put("url", src.url)
                    }
                    is AttachmentSource.Base64 -> {
                        put("type", "base64")
                        put("media_type", attachment.mimeType)
                        put("data", src.data)
                    }
                    is AttachmentSource.LocalUri -> {
                        error("LocalUri should have been rejected earlier")
                    }
                }
            }
        }
    }

    private fun JsonArrayBuilder.addJsonObject(
        block: JsonObjectBuilder.() -> Unit,
    ) {
        add(buildJsonObject(block))
    }
}
