package com.ailux.provider.backend.aggregator

import com.ailux.core.event.LLMEvent
import com.ailux.core.tool.ToolCall

/**
 * Accumulates [LLMEvent.ToolCallDelta] fragments into complete [ToolCall] objects.
 *
 * This is a **utility class** provided by the SDK for parser implementations.
 * Built-in parsers ([OpenAIStreamResponseParser], [AnthropicStreamResponseParser])
 * use it internally, but custom [StreamResponseParser] implementations can also
 * use it if their protocol streams tool call arguments incrementally.
 *
 * ## Usage
 *
 * ```kotlin
 * val aggregator = ToolCallAggregator()
 *
 * // Feed deltas as they arrive
 * aggregator.feed(toolCallDelta)
 *
 * // When the stream signals completion with tool_calls finish reason:
 * if (aggregator.isNotEmpty()) {
 *     val toolCalls = aggregator.build()
 *     // emit LLMEvent.ToolCallReceived(toolCalls)
 * }
 * ```
 *
 * ## Thread safety
 *
 * This class is **not** thread-safe. It is designed to be used within a single
 * coroutine/stream context.
 */
class ToolCallAggregator {

    private val buffer = mutableMapOf<Int, ToolCallBuilder>()

    /**
     * Feeds a single tool call delta into the aggregator.
     *
     * @param delta The incremental tool call data. `id` and `name` are recorded
     *             on first occurrence; `argumentsDelta` is appended each time.
     */
    fun feed(delta: LLMEvent.ToolCallDelta) {
        val builder = buffer.getOrPut(delta.index) { ToolCallBuilder() }

        delta.id?.let { builder.id = it }
        delta.name?.let { builder.name = it }
        builder.arguments.append(delta.argumentsDelta)
    }

    /**
     * Returns `true` if at least one tool call delta has been fed.
     */
    fun isNotEmpty(): Boolean = buffer.isNotEmpty()

    /**
     * Builds the aggregated [ToolCall] list, sorted by index.
     *
     * Call this only after all deltas have been fed (typically on stream completion).
     */
    fun build(): List<ToolCall> = buffer.entries
        .sortedBy { it.key }
        .map { (_, b) ->
            ToolCall(
                id = b.id,
                name = b.name,
                arguments = b.arguments.toString().ifEmpty { null },
            )
        }

    /**
     * Clears all buffered data. Use if you need to reuse the same instance
     * across multiple tool call rounds (not typical).
     */
    fun reset() = buffer.clear()
}

private class ToolCallBuilder {
    var id: String = ""
    var name: String = ""
    val arguments = StringBuilder()
}