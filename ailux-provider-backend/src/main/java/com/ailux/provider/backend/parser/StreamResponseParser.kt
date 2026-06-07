package com.ailux.provider.backend.parser

import com.ailux.core.event.LLMEvent

/**
 * SSE event parser: converts a single SSE event into the SDK's [LLMEvent] list.
 *
 * The backend pushes streaming data via Server-Sent Events. Each event has an
 * `event` type and a `data` payload. Implement this interface to fit your
 * backend's event format.
 *
 * ## Stateful parsers
 *
 * Parsers **may be stateful** (e.g. for accumulating Function Calling tool call
 * deltas across multiple SSE chunks). When a parser is stateful, a new instance
 * should be created for each streaming request.
 *
 * The return type is `List<LLMEvent>` (not a single event) to allow:
 * - Returning multiple events from one SSE chunk (e.g. ToolCallReceived + Done)
 * - Returning an empty list to indicate "consumed internally, nothing to emit yet"
 *
 * ## Built-in parsers
 *
 * Passing `null` to [BackendProxyConfig.streamResponseParser] makes the SDK
 * fall back to [OpenAIStreamResponseParser] (compatible with OpenAI,
 * DeepSeek, Tongyi Qianwen, and other `chat.completion.chunk`-style formats).
 *
 * The SDK ships two built-in parsers:
 * - [OpenAIStreamResponseParser]: OpenAI-compatible format (default, stateful for FC).
 * - [AnthropicStreamResponseParser]: Anthropic Claude native format (stateful for FC).
 *
 * ## Custom parser examples
 *
 * ```kotlin
 * // Simple stateless parser (no Function Calling)
 * val parser = StreamResponseParser { eventType, data ->
 *     when (eventType) {
 *         "delta"    -> listOf(LLMEvent.Token(parseCompanyDelta(data)))
 *         "finish"   -> listOf(LLMEvent.Done())
 *         "metrics"  -> listOf(LLMEvent.Usage(parseCompanyUsage(data)))
 *         else       -> emptyList()  // ignore unknown events
 *     }
 * }
 *
 * // Parser for a protocol that returns complete tool calls (non-streaming)
 * val fcParser = StreamResponseParser { eventType, data ->
 *     when (eventType) {
 *         "tool_call" -> {
 *             val toolCalls = parseFullToolCalls(data)
 *             listOf(
 *                 LLMEvent.ToolCallReceived(toolCalls),
 *                 LLMEvent.Done(FinishReason.TOOL_CALL)
 *             )
 *         }
 *         // ...
 *     }
 * }
 * ```
 *
 * @see OpenAIStreamResponseParser
 * @see AnthropicStreamResponseParser
 */
fun interface StreamResponseParser {

    /**
     * Parses a single SSE event.
     *
     * @param eventType The value of the SSE `event:` field (e.g. `"token"`, `"usage"`, `"error"`, `"done"`).
     *                  When the SSE stream has no explicit `event:` field, OkHttp passes `"message"`.
     * @param data      The value of the SSE `data:` field (usually a JSON string).
     * @return A list of [LLMEvent]s to emit. Return an empty list to skip/ignore the event.
     */
    fun parse(eventType: String, data: String): List<LLMEvent>
}
