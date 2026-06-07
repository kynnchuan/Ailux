package com.ailux.provider.backend.parser

import com.ailux.core.error.ErrorCode
import com.ailux.core.error.LLMError
import com.ailux.core.event.FinishReason
import com.ailux.core.event.LLMEvent
import com.ailux.core.response.UsageInfo
import com.ailux.core.tool.ToolCall
import com.ailux.provider.backend.aggregator.ToolCallAggregator
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
 * event: content_block_start
 * data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_01","name":"get_weather","input":{}}}
 *
 * event: content_block_delta
 * data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"loc"}}
 *
 * event: message_delta
 * data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":20}}
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
 * | `content_block_delta` (input_json_delta) | internal aggregation (no emit) |
 * | `content_block_start` (tool_use) | records tool id/name for aggregation |
 * | `message_delta` (stop_reason=tool_use) | records finish reason |
 * | `message_delta` | [LLMEvent.Usage] (when usage present) |
 * | `message_stop` | [LLMEvent.ToolCallReceived] + [LLMEvent.Done] (if FC) or just [LLMEvent.Done] |
 * | `error` | [LLMEvent.Error] |
 * | others | ignored |
 *
 * ## Stateful design (Function Calling)
 *
 * This parser is **stateful**: it accumulates tool call data across `content_block_start`
 * and `content_block_delta` (input_json_delta) events using a [ToolCallAggregator].
 * The aggregated [LLMEvent.ToolCallReceived] is only emitted on `message_stop`.
 *
 * **Important**: A new instance should be created for each streaming request.
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
 * @see ToolCallAggregator
 */
class AnthropicStreamResponseParser : StreamResponseParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** Internal aggregator: accumulates tool call data from content_block events. */
    private val toolCallAggregator = ToolCallAggregator()

    /** Tracks tool call metadata (id, name) keyed by content block index. */
    private val toolCallMeta = mutableMapOf<Int, Pair<String, String>>() // index -> (id, name)

    /** Records the stop_reason from message_delta to determine Done semantics. */
    private var pendingFinishReason: FinishReason? = null

    override fun parse(eventType: String, data: String): List<LLMEvent> {
        val trimmed = data.trim()
        if (trimmed.isEmpty()) return emptyList()

        return try {
            when (eventType) {
                "content_block_start" -> parseContentBlockStart(trimmed)
                "content_block_delta" -> parseContentBlockDelta(trimmed)
                "message_delta" -> parseMessageDelta(trimmed)
                "message_stop" -> buildDoneEvents()
                "error" -> listOf(parseError(trimmed))
                // message_start, content_block_stop, ping, etc. are ignored
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Parses a content_block_start event.
     *
     * For tool_use blocks, records the tool id and name for later aggregation:
     * ```json
     * {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_01A","name":"get_weather","input":{}}}
     * ```
     */
    private fun parseContentBlockStart(data: String): List<LLMEvent> {
        val root = json.parseToJsonElement(data).jsonObject
        val index = root["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: return emptyList()
        val contentBlock = root["content_block"]?.jsonObject ?: return emptyList()
        val blockType = contentBlock["type"]?.jsonPrimitive?.contentOrNull

        if (blockType == "tool_use") {
            val id = contentBlock["id"]?.jsonPrimitive?.contentOrNull ?: ""
            val name = contentBlock["name"]?.jsonPrimitive?.contentOrNull ?: ""
            toolCallMeta[index] = id to name

            // Feed the initial metadata as a delta with empty arguments
            toolCallAggregator.feed(
                LLMEvent.ToolCallDelta(
                    index = index,
                    id = id,
                    name = name,
                    argumentsDelta = "",
                )
            )
        }

        return emptyList()
    }

    /**
     * Parses a content_block_delta event.
     *
     * ```json
     * {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}
     * {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"loc"}}
     * ```
     */
    private fun parseContentBlockDelta(data: String): List<LLMEvent> {
        val root = json.parseToJsonElement(data).jsonObject
        val index = root["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val delta = root["delta"]?.jsonObject ?: return emptyList()
        val deltaType = delta["type"]?.jsonPrimitive?.contentOrNull

        return when (deltaType) {
            "text_delta" -> {
                val text = delta["text"]?.jsonPrimitive?.contentOrNull
                if (!text.isNullOrEmpty()) listOf(LLMEvent.Token(text)) else emptyList()
            }
            "thinking_delta" -> {
                val thinking = delta["thinking"]?.jsonPrimitive?.contentOrNull
                if (!thinking.isNullOrEmpty()) listOf(LLMEvent.Reasoning(thinking)) else emptyList()
            }
            "input_json_delta" -> {
                // Tool call arguments fragment — feed to aggregator
                val partialJson = delta["partial_json"]?.jsonPrimitive?.contentOrNull ?: ""
                toolCallAggregator.feed(
                    LLMEvent.ToolCallDelta(
                        index = index,
                        id = null,
                        name = null,
                        argumentsDelta = partialJson,
                    )
                )
                emptyList()
            }
            else -> emptyList()
        }
    }

    /**
     * Parses a message_delta event (carries stop_reason and usage info).
     *
     * ```json
     * {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":20}}
     * ```
     */
    private fun parseMessageDelta(data: String): List<LLMEvent> {
        val root = json.parseToJsonElement(data).jsonObject

        // Record stop_reason
        val deltaObj = root["delta"]?.jsonObject
        val stopReason = deltaObj?.get("stop_reason")?.jsonPrimitive?.contentOrNull
        if (stopReason != null) {
            pendingFinishReason = mapStopReason(stopReason)
        }

        // Extract usage
        val usage = root["usage"]?.jsonObject
        val events = mutableListOf<LLMEvent>()
        if (usage != null) {
            val outputTokens = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
            events.add(LLMEvent.Usage(UsageInfo(inputTokens = 0, outputTokens = outputTokens)))
        }

        return events
    }

    /**
     * Builds the final events when `message_stop` is received.
     *
     * If the stop_reason was TOOL_CALL, emits aggregated ToolCallReceived before Done.
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
     * Maps an Anthropic stop_reason to a [FinishReason].
     *
     * Official values (as of 2025):
     * - "end_turn": natural stop
     * - "stop_sequence": matched a custom stop sequence
     * - "tool_use": model requested tool invocation
     * - "max_tokens": hit max_tokens or model limit
     * - "pause_turn": long-running turn paused (extended thinking)
     * - "refusal": content refused due to policy
     */
    private fun mapStopReason(reason: String): FinishReason = when (reason) {
        "end_turn", "stop_sequence" -> FinishReason.COMPLETE
        "tool_use" -> FinishReason.TOOL_CALL
        "max_tokens" -> FinishReason.LENGTH
        "refusal" -> FinishReason.CONTENT_FILTER
        "pause_turn" -> FinishReason.COMPLETE // treat as normal completion for now
        else -> FinishReason.COMPLETE
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
