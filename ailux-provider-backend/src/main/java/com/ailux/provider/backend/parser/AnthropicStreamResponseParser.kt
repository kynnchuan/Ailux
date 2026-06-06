package com.ailux.provider.backend.parser

import com.ailux.core.model.ErrorCode
import com.ailux.core.model.LLMError
import com.ailux.core.model.LLMEvent
import com.ailux.core.model.UsageInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * SSE event parser for Anthropic Messages API format.
 *
 * Adapts to Claude's native API (`api.anthropic.com`) and services compatible
 * with this protocol (e.g. AWS Bedrock Claude, certain proxies' Anthropic mode).
 *
 * ## SSE Format
 *
 * The Anthropic API uses an explicit `event:` field to distinguish event types:
 * ```
 * event: message_start
 * data: {"type":"message_start","message":{"id":"...","model":"claude-3-5-sonnet-20241022",...}}
 *
 * event: content_block_delta
 * data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}
 *
 * event: message_delta
 * data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":20}}
 *
 * event: message_stop
 * data: {"type":"message_stop"}
 * ```
 *
 * ## Event Mapping
 *
 * | Anthropic event | Mapped to |
 * |---|---|
 * | `content_block_delta` (text_delta) | [LLMEvent.Token] |
 * | `content_block_delta` (thinking_delta) | [LLMEvent.Reasoning] (chain-of-thought) |
 * | `message_delta` | [LLMEvent.Usage] (when usage present) |
 * | `message_stop` | [LLMEvent.Done] |
 * | `error` | [LLMEvent.Error] |
 * | others | ignored (null) |
 *
 * ## Usage
 *
 * ```kotlin
 * val config = BackendProxyConfig(
 *     baseUrl = "https://api.anthropic.com",
 *     streamEndpoint = "/v1/messages",
 *     streamResponseParser = AnthropicStreamResponseParser(),
 *     headers = mapOf(
 *         "anthropic-version" to "2023-06-01",
 *         "x-api-key" to apiKey,
 *     ),
 * )
 * ```
 *
 * @see OpenAIStreamResponseParser
 * @see StreamResponseParser
 */
class AnthropicStreamResponseParser : StreamResponseParser {

    private val json = Json { ignoreUnknownKeys = true }

    override fun parse(eventType: String, data: String): LLMEvent? {
        val trimmed = data.trim()
        if (trimmed.isEmpty()) return null

        return try {
            when (eventType) {
                "content_block_delta" -> parseContentBlockDelta(trimmed)
                "message_delta" -> parseMessageDelta(trimmed)
                "message_stop" -> LLMEvent.Done
                "error" -> parseError(trimmed)
                // message_start, content_block_start, content_block_stop, ping, etc. are ignored
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses a content_block_delta event.
     *
     * ```json
     * {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}
     * ```
     *
     * Also supports thinking delta:
     * ```json
     * {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"..."}}
     * ```
     */
    private fun parseContentBlockDelta(data: String): LLMEvent? {
        val root = json.parseToJsonElement(data).jsonObject
        val delta = root["delta"]?.jsonObject ?: return null
        val deltaType = delta["type"]?.jsonPrimitive?.contentOrNull

        return when (deltaType) {
            "text_delta" -> {
                val text = delta["text"]?.jsonPrimitive?.contentOrNull
                if (!text.isNullOrEmpty()) LLMEvent.Token(text) else null
            }
            "thinking_delta" -> {
                val thinking = delta["thinking"]?.jsonPrimitive?.contentOrNull
                if (!thinking.isNullOrEmpty()) LLMEvent.Reasoning(thinking) else null
            }
            else -> null
        }
    }

    /**
     * Parses a message_delta event (which carries usage info).
     *
     * ```json
     * {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":20}}
     * ```
     */
    private fun parseMessageDelta(data: String): LLMEvent? {
        val root = json.parseToJsonElement(data).jsonObject
        val usage = root["usage"]?.jsonObject ?: return null

        val outputTokens = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        // Anthropic's message_delta typically only contains output_tokens.
        // input_tokens lives in message_start; simplified here.
        return LLMEvent.Usage(
            UsageInfo(inputTokens = 0, outputTokens = outputTokens)
        )
    }

    /**
     * Parses an error event.
     *
     * ```json
     * {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}
     * ```
     */
    private fun parseError(data: String): LLMEvent {
        val root = json.parseToJsonElement(data).jsonObject
        val errorObj = root["error"]?.jsonObject

        val errorType = errorObj?.get("type")?.jsonPrimitive?.contentOrNull ?: "unknown"
        val message = errorObj?.get("message")?.jsonPrimitive?.contentOrNull ?: "Unknown error"

        val errorCode = when (errorType) {
            "authentication_error" -> ErrorCode.AUTH_FAILED
            "rate_limit_error" -> ErrorCode.RATE_LIMITED
            "not_found_error" -> ErrorCode.MODEL_NOT_FOUND
            "overloaded_error" -> ErrorCode.RATE_LIMITED
            else -> ErrorCode.UNKNOWN
        }

        return LLMEvent.Error(LLMError(code = errorCode, message = message))
    }
}
