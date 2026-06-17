package com.ailux.provider.backend.parser.stream

import com.ailux.core.error.ErrorCode
import com.ailux.core.error.LLMError
import com.ailux.core.event.FinishReason
import com.ailux.core.event.LLMEvent
import com.ailux.core.response.UsageInfo
import com.ailux.provider.backend.aggregator.ToolCallAggregator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
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
 * - **`tool_calls`**: Function calling tool call deltas, aggregated internally and emitted as
 *   a single [LLMEvent.ToolCallReceived] when the stream completes with `finish_reason: "tool_calls"`.
 * - **`usage`**: Token usage stats at the end of the stream (OpenAI `stream_options` / DeepSeek).
 *
 * ## Stateful design (Function Calling)
 *
 * This parser is **stateful**: it internally accumulates tool call deltas using a
 * [ToolCallAggregator] and only emits the aggregated [LLMEvent.ToolCallReceived] when
 * `[DONE]` is received with a recorded `finish_reason` of `"tool_calls"`.
 *
 * This design gives SDK users who implement custom [StreamResponseParser] full control
 * over when and how to emit tool call events — they are not forced into a specific
 * aggregation pattern by the Provider layer.
 *
 * **Important**: Because this parser is stateful, a new instance should be created
 * for each streaming request. Do NOT reuse across multiple requests.
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
 * @see ToolCallAggregator
 */
class OpenAIStreamResponseParser : StreamResponseParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** Internal aggregator: accumulates tool call deltas across multiple SSE events. */
    private val toolCallAggregator = ToolCallAggregator()

    /** Tracks the last seen finish_reason to determine Done event semantics. */
    private var pendingFinishReason: FinishReason? = null

    override fun parse(eventType: String, data: String): List<LLMEvent> {
        val trimmed = data.trim()

        // End-of-stream marker for the OpenAI format
        if (trimmed == "[DONE]") {
            return buildDoneEvents()
        }

        // Ignore empty data
        if (trimmed.isEmpty()) return emptyList()

        return try {
            val root = json.parseToJsonElement(trimmed).jsonObject

            // Check for an `error` field (some vendors return errors mid-stream)
            root["error"]?.let { errorElement ->
                val errorObj = errorElement.jsonObject
                val message = errorObj["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                val code = errorObj["code"]?.jsonPrimitive?.contentOrNull
                return listOf(
                    LLMEvent.Error(
                        LLMError(
                            code = mapErrorCode(code),
                            message = message,
                        )
                    )
                )
            }

            val choices = root["choices"]?.jsonArray
            if (choices.isNullOrEmpty()) {
                // No choices but possibly a `usage` field (some implementations send a final usage-only chunk)
                val usage = root["usage"]?.let { parseUsage(it.jsonObject) }
                return if (usage != null) listOf(usage) else emptyList()
            }

            val choice = choices[0].jsonObject
            val delta = choice["delta"]?.jsonObject

            // Check `finish_reason` — record it but don't emit Done yet (wait for [DONE])
            val finishReasonStr = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
            if (finishReasonStr != null) {
                pendingFinishReason = mapFinishReason(finishReasonStr)
                // Also try to extract usage from this chunk
                val usage = root["usage"]?.let { parseUsage(it.jsonObject) }
                return if (usage != null) listOf(usage) else emptyList()
            }

            if (delta == null) return emptyList()

            // Check for tool_calls delta
            val toolCallsArray = delta["tool_calls"]?.jsonArray
            if (!toolCallsArray.isNullOrEmpty()) {
                return parseToolCallDeltas(toolCallsArray)
            }

            // Extract `content` (standard field)
            val content = delta["content"]?.jsonPrimitive?.contentOrNull

            // Extract `reasoning_content` (DeepSeek chain-of-thought)
            val reasoningContent = delta["reasoning_content"]?.jsonPrimitive?.contentOrNull

            // Differentiate: content → Token, reasoning_content → Reasoning
            when {
                !content.isNullOrEmpty() -> listOf(LLMEvent.Token(content))
                !reasoningContent.isNullOrEmpty() -> listOf(LLMEvent.Reasoning(reasoningContent))
                else -> emptyList() // role-only delta or empty delta, ignore
            }
        } catch (_: Exception) {
            // JSON parse failure: do not break the stream, just drop this event
            emptyList()
        }
    }

    /**
     * Builds the final events when `[DONE]` is received.
     *
     * If the finish_reason was TOOL_CALL, aggregates all buffered tool call deltas
     * and emits [LLMEvent.ToolCallReceived] followed by [LLMEvent.Done].
     */
    private fun buildDoneEvents(): List<LLMEvent> {
        val reason = pendingFinishReason ?: FinishReason.COMPLETE
        val events = mutableListOf<LLMEvent>()

        if (reason == FinishReason.TOOL_CALL && toolCallAggregator.isNotEmpty()) {
            events.add(LLMEvent.ToolCallReceived(toolCallAggregator.build()))
        }

        events.add(LLMEvent.Done(reason))
        return events
    }

    /**
     * Parses the `tool_calls` delta array and feeds each delta into the aggregator.
     *
     * OpenAI format:
     * ```json
     * "tool_calls": [{
     *   "index": 0,
     *   "id": "call_abc123",
     *   "type": "function",
     *   "function": { "name": "get_weather", "arguments": "{\"lo" }
     * }]
     * ```
     *
     * The first chunk for each tool call contains `id` and `function.name`;
     * subsequent chunks only contain `function.arguments` fragments.
     */
    private fun parseToolCallDeltas(
        toolCallsArray: kotlinx.serialization.json.JsonArray,
    ): List<LLMEvent> {
        for (element in toolCallsArray) {
            val obj = element.jsonObject
            val index = obj["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val id = obj["id"]?.jsonPrimitive?.contentOrNull
            val function = obj["function"]?.jsonObject
            val name = function?.get("name")?.jsonPrimitive?.contentOrNull
            val arguments = function?.get("arguments")?.jsonPrimitive?.contentOrNull ?: ""

            val delta = LLMEvent.ToolCallDelta(
                index = index,
                id = id,
                name = name,
                argumentsDelta = arguments,
            )
            toolCallAggregator.feed(delta)
        }
        return emptyList()
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
     * Maps an OpenAI finish_reason string to a [FinishReason].
     *
     * Official values (as of 2025):
     * - "stop": natural stop or matched stop sequence
     * - "tool_calls": model requested tool invocation
     * - "function_call": deprecated, replaced by "tool_calls"
     * - "length": hit max_tokens limit
     * - "content_filter": content omitted due to safety filter
     */
    private fun mapFinishReason(reason: String): FinishReason = when (reason) {
        "stop" -> FinishReason.COMPLETE
        "tool_calls" -> FinishReason.TOOL_CALL
        "function_call" -> FinishReason.TOOL_CALL // deprecated, treat same as tool_calls
        "length" -> FinishReason.LENGTH
        "content_filter" -> FinishReason.CONTENT_FILTER
        else -> FinishReason.COMPLETE
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
