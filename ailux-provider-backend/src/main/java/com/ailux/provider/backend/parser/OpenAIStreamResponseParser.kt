package com.ailux.provider.backend.parser

import com.ailux.core.model.ErrorCode
import com.ailux.core.model.LLMError
import com.ailux.core.model.LLMEvent
import com.ailux.core.model.UsageInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * OpenAI-compatible SSE event parser (default).
 *
 * Works with any LLM API that follows the OpenAI Chat Completions SSE
 * protocol, including but not limited to:
 * - OpenAI (GPT-4o, GPT-4, etc.)
 * - DeepSeek (deepseek-v4-flash, deepseek-v4-pro)
 * - Tongyi Qianwen / DashScope (qwen-turbo, qwen-plus, etc.)
 * - Moonshot / Kimi
 * - GLM / Zhipu
 * - Minimax
 * - Groq, Together AI, OpenRouter, etc.
 *
 * ## SSE format
 *
 * OpenAI-compatible APIs **do not** use the `event:` field (in OkHttp the
 * type is null → `"message"`); the data format is:
 * ```
 * data: {"id":"...","object":"chat.completion.chunk","choices":[{"delta":{"content":"Hello"}}]}
 *
 * data: [DONE]
 * ```
 *
 * ## Special field support
 *
 * - **`reasoning_content`**: DeepSeek chain-of-thought output, returned as a Reasoning event.
 * - **`usage`**: Token usage stats at the end of the stream (OpenAI `stream_options` / DeepSeek).
 *
 * ## Design notes
 *
 * Why OpenAI format is the default parser:
 * 1. OpenAI Chat Completions is the de facto standard for LLM APIs — 90%+ of vendors are compatible.
 * 2. Users can integrate the vast majority of LLM services with zero configuration.
 * 3. Anthropic format is shipped as a second optional parser to cover the rest.
 *
 * @see AnthropicStreamResponseParser
 * @see StreamResponseParser
 */
class OpenAIStreamResponseParser : StreamResponseParser {

    private val json = Json { ignoreUnknownKeys = true }

    override fun parse(eventType: String, data: String): LLMEvent? {
        val trimmed = data.trim()

        // End-of-stream marker for the OpenAI format
        if (trimmed == "[DONE]") {
            return LLMEvent.Done
        }

        // Ignore empty data
        if (trimmed.isEmpty()) return null

        return try {
            val root = json.parseToJsonElement(trimmed).jsonObject

            // Check for an `error` field (some vendors return errors mid-stream)
            root["error"]?.let { errorElement ->
                val errorObj = errorElement.jsonObject
                val message = errorObj["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                val code = errorObj["code"]?.jsonPrimitive?.contentOrNull
                return LLMEvent.Error(
                    LLMError(
                        code = mapErrorCode(code),
                        message = message,
                    )
                )
            }

            val choices = root["choices"]?.jsonArray ?: return null
            if (choices.isEmpty()) {
                // No choices but possibly a `usage` field (some implementations send a final usage-only chunk)
                return root["usage"]?.let { parseUsage(it.jsonObject) }
            }

            val choice = choices[0].jsonObject
            val delta = choice["delta"]?.jsonObject

            // Check `finish_reason`
            val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
            if (finishReason != null) {
                // When finish_reason is present, try to extract usage
                return root["usage"]?.let { parseUsage(it.jsonObject) }
            }

            if (delta == null) return null

            // Extract `content` (standard field)
            val content = delta["content"]?.jsonPrimitive?.contentOrNull

            // Extract `reasoning_content` (DeepSeek chain-of-thought)
            val reasoningContent = delta["reasoning_content"]?.jsonPrimitive?.contentOrNull

            // Differentiate: content → Token, reasoning_content → Reasoning
            when {
                !content.isNullOrEmpty() -> LLMEvent.Token(content)
                !reasoningContent.isNullOrEmpty() -> LLMEvent.Reasoning(reasoningContent)
                else -> null // role-only delta or empty delta, ignore
            }
        } catch (_: Exception) {
            // JSON parse failure: do not break the stream, just drop this event
            null
        }
    }

    /**
     * Parses a `usage` object into [LLMEvent.Usage].
     *
     * OpenAI format: `{"prompt_tokens": 12, "completion_tokens": 20, "total_tokens": 32}`
     */
    private fun parseUsage(usageObj: kotlinx.serialization.json.JsonObject): LLMEvent.Usage {
        val inputTokens = usageObj["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val outputTokens = usageObj["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        return LLMEvent.Usage(UsageInfo(inputTokens = inputTokens, outputTokens = outputTokens))
    }

    /**
     * Maps an error code string to an [ErrorCode].
     */
    private fun mapErrorCode(code: String?): ErrorCode = when (code?.lowercase()) {
        "invalid_api_key", "authentication_error" -> ErrorCode.AUTH_FAILED
        "rate_limit_exceeded", "rate_limited" -> ErrorCode.RATE_LIMITED
        "model_not_found" -> ErrorCode.MODEL_NOT_FOUND
        "timeout" -> ErrorCode.REQUEST_TIMEOUT
        else -> ErrorCode.UNKNOWN
    }
}
