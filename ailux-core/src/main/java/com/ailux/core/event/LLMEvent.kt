package com.ailux.core.event

import com.ailux.core.error.LLMError
import com.ailux.core.response.UsageInfo
import com.ailux.core.tool.ToolCall

/**
 * Streaming events emitted by [LLMProvider.streamGenerate].
 *
 * Consumers collect a `Flow<LLMEvent>` and react to each subtype:
 *
 * ```kotlin
 * provider.streamGenerate(request).collect { event ->
 *     when (event) {
 *         is LLMEvent.ContextTrimmed   -> logTrim(event.removedCount)
 *         is LLMEvent.Token            -> appendContent(event.text)
 *         is LLMEvent.Reasoning        -> appendThinking(event.text)
 *         is LLMEvent.Usage            -> showUsage(event.info)
 *         is LLMEvent.Error            -> showError(event.error)
 *         is LLMEvent.ToolCallDelta    -> { /* optional: show typing indicator */ }
 *         is LLMEvent.ToolCallReceived -> executeTools(event.toolCalls)
 *         is LLMEvent.Done             -> hideLoading()
 *     }
 * }
 * ```
 *
 * ## Event lifecycle (Function Calling)
 *
 * When the model invokes tools, the event sequence is:
 * 1. Zero or more [ToolCallDelta] — incremental fragments (internal to parser, not always emitted)
 * 2. One [ToolCallReceived] — aggregated complete tool calls
 * 3. One [Done] with `finishReason = TOOL_CALL`
 *
 * The caller should then execute the tools, append results as [Message.Tool],
 * and send a follow-up request to continue the conversation.
 */
sealed class LLMEvent {

    /** A chunk of generated text (one or more tokens). Maps to the `delta.content` field. */
    data class Token(val text: String) : LLMEvent()

    /**
     * A chunk of reasoning / chain-of-thought text. Maps to `delta.reasoning_content`
     * (DeepSeek) or a `thinking` block (Anthropic).
     *
     * The UI layer can collapse it or render it with a different style than the final reply.
     */
    data class Reasoning(val text: String) : LLMEvent()

    /** Token usage information, typically emitted once at the end of the stream. */
    data class Usage(val info: UsageInfo) : LLMEvent()

    /** An error event. The stream may or may not continue after this. */
    data class Error(val error: LLMError) : LLMEvent()

    /**
     * An incremental fragment of a tool call being streamed.
     *
     * Built-in parsers aggregate these internally and emit a single [ToolCallReceived]
     * at the end. This event type exists in the sealed hierarchy so that custom parsers
     * can optionally emit deltas for real-time UI feedback (e.g. showing the function
     * arguments as they stream in).
     *
     * @property index          Zero-based index of the tool call in a parallel batch.
     * @property id             Tool call ID (present only in the first delta for each index).
     * @property name           Function name (present only in the first delta for each index).
     * @property argumentsDelta A fragment of the JSON arguments string.
     */
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argumentsDelta: String,
    ) : LLMEvent()

    /**
     * One or more complete tool calls aggregated from the stream.
     *
     * Emitted once, immediately before [Done] with `finishReason = TOOL_CALL`.
     * The caller should execute each [ToolCall] and include results in the next request.
     */
    data class ToolCallReceived(
        val toolCalls: List<ToolCall>,
    ) : LLMEvent()

    /**
     * Marks the end of the stream. Always the last event emitted.
     *
     * @property finishReason Why the model stopped generating. Check this to determine
     *                        if a tool-call loop continuation is needed.
     */
    data class Done(
        val finishReason: FinishReason = FinishReason.COMPLETE,
    ) : LLMEvent()

    /**
     * Notification that the context manager has trimmed the message list before
     * sending it to the provider, or a pre-check warning that the estimated token
     * count exceeds the model's context window.
     *
     * Emitted **before** any [Token] events in the stream.
     *
     * Two usage modes:
     * - **Actual trim** ([removedCount] > 0): messages were removed to fit the budget.
     *   The UI can inform the user that older messages are no longer visible to the model.
     * - **Pre-check warning** ([removedCount] == 0, [estimatedTokensSaved] == 0):
     *   the context manager is disabled (`null`), but estimated tokens exceed the
     *   model's context window. The request is still sent as-is — this is advisory only.
     *
     * @property removedCount         number of messages removed from the conversation.
     * @property estimatedTokensSaved approximate token savings achieved by the trim.
     */
    data class ContextTrimmed(
        val removedCount: Int,
        val estimatedTokensSaved: Int
    ) : LLMEvent()

}
