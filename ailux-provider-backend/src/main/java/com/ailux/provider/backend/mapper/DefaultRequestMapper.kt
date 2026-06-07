package com.ailux.provider.backend.mapper

import com.ailux.core.request.LLMRequest
import com.ailux.core.message.Message
import kotlinx.serialization.json.Json
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
 *   "metadata": {"extra_key": "extra_value"}
 * }
 * ```
 *
 * ## Field mapping rules
 *
 * - `model`: uses `LLMRequest.model`; falls back to `"default"` when empty.
 * - `messages`: fully supports multi-turn with system/user/assistant/tool roles.
 * - `tools`: omitted when `LLMRequest.tools` is empty (no function calling).
 * - `tool_choice`: omitted when `LLMRequest.toolChoice` is null.
 * - `max_tokens`: omitted when `LLMRequest.maxTokens` is null.
 * - `metadata`: present only when `LLMRequest.extras` is non-empty.
 *
 * @see RequestMapper
 */
class DefaultRequestMapper : RequestMapper {

    private val jsonParser = Json { ignoreUnknownKeys = true }

    override fun map(request: LLMRequest, stream: Boolean): String {
        val json = buildJsonObject {
            put("model", request.model.ifEmpty { "default" })

            putJsonArray("messages") {
                request.messages.forEach { message ->
                    addJsonObject {
                        when (message) {
                            is Message.System -> {
                                put("role", "system")
                                put("content", message.content)
                            }
                            is Message.User -> {
                                put("role", "user")
                                put("content", message.content)
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

            request.maxTokens?.let { put("max_tokens", it) }

            if (request.extras.isNotEmpty()) {
                putJsonObject("metadata") {
                    request.extras.forEach { (key, value) -> put(key, value) }
                }
            }
        }

        return json.toString()
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.addJsonObject(
        block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ) {
        add(buildJsonObject(block))
    }
}
