package com.ailux.api.stream

import com.ailux.core.error.LLMError
import com.ailux.core.event.FinishReason
import com.ailux.core.event.LLMEvent
import com.ailux.core.response.UsageInfo
import com.ailux.core.stream.StallPhase
import com.ailux.core.tool.ToolCall

/**
 * DSL scope for handling [LLMEvent]s in a callback-style manner.
 *
 * Usage:
 * ```kotlin
 * task.handle {
 *     onToken { text -> appendContent(text) }
 *     onReasoning { text -> appendThinking(text) }
 *     onToolCallReceived { calls -> executeCalls(calls) }
 *     onDone { reason -> markFinished(reason) }
 *     onError { error -> showError(error) }
 * }
 * ```
 *
 * All callbacks have a no-op default — you only need to register the ones you care about.
 * The scope object is configured **before** collection starts, so registration order
 * does not matter.
 */
class EventHandlerScope {

    // ── Callback slots (internal, set by the DSL methods) ──

    internal var tokenHandler: ((String) -> Unit)? = null
    internal var reasoningHandler: ((String) -> Unit)? = null
    internal var usageHandler: ((UsageInfo) -> Unit)? = null
    internal var errorHandler: ((LLMError) -> Unit)? = null
    internal var toolCallDeltaHandler: ((index: Int, id: String?, name: String?, argumentsDelta: String) -> Unit)? = null
    internal var toolCallReceivedHandler: ((List<ToolCall>) -> Unit)? = null
    internal var doneHandler: ((FinishReason) -> Unit)? = null
    internal var contextTrimmedHandler: ((removedCount: Int, estimatedTokensSaved: Int) -> Unit)? = null
    internal var connectedHandler: (() -> Unit)? = null
    internal var stallDetectedHandler: ((phase: StallPhase, idleMillis: Long) -> Unit)? = null

    // ── Public DSL methods ──

    /**
     * Called for each text token chunk received from the model.
     *
     * @param action receives the token text string.
     */
    fun onToken(action: (text: String) -> Unit) {
        tokenHandler = action
    }

    /**
     * Called for each reasoning / chain-of-thought text chunk.
     *
     * @param action receives the reasoning text string.
     */
    fun onReasoning(action: (text: String) -> Unit) {
        reasoningHandler = action
    }

    /**
     * Called when token usage information is reported (typically once at stream end).
     *
     * @param action receives the [UsageInfo] object.
     */
    fun onUsage(action: (info: UsageInfo) -> Unit) {
        usageHandler = action
    }

    /**
     * Called when an error event is emitted during streaming.
     *
     * @param action receives the [LLMError] object.
     */
    fun onError(action: (error: LLMError) -> Unit) {
        errorHandler = action
    }

    /**
     * Called for each incremental tool call delta (optional, for real-time UI feedback).
     *
     * Most consumers should use [onToolCallReceived] instead — this is for advanced
     * use cases where you want to show arguments streaming in real time.
     *
     * @param action receives index, id, name, and argumentsDelta.
     */
    fun onToolCallDelta(action: (index: Int, id: String?, name: String?, argumentsDelta: String) -> Unit) {
        toolCallDeltaHandler = action
    }

    /**
     * Called when complete tool calls are received (aggregated from deltas).
     *
     * This is the primary hook for Function Calling. The list contains one or more
     * [ToolCall] objects that the model wants to invoke.
     *
     * @param action receives the list of complete [ToolCall]s.
     */
    fun onToolCallReceived(action: (toolCalls: List<ToolCall>) -> Unit) {
        toolCallReceivedHandler = action
    }

    /**
     * Called when the stream ends.
     *
     * @param action receives the [FinishReason] indicating why generation stopped.
     *               Check for [FinishReason.TOOL_CALL] if you need to continue
     *               the FC loop.
     */
    fun onDone(action: (reason: FinishReason) -> Unit) {
        doneHandler = action
    }

    /**
     * Called when the context manager trims the conversation history.
     *
     * @param action receives the number of messages removed and estimated tokens saved.
     */
    fun onContextTrimmed(action: (removedCount: Int, estimatedTokensSaved: Int) -> Unit) {
        contextTrimmedHandler = action
    }

    /**
     * Called when the SSE connection is established (before any tokens arrive).
     */
    fun onConnected(action: () -> Unit) {
        connectedHandler = action
    }

    /**
     * Called when a stream stall is detected.
     *
     * @param action receives the stall phase and elapsed idle time in milliseconds.
     */
    fun onStallDetected(action: (phase: StallPhase, idleMillis: Long) -> Unit) {
        stallDetectedHandler = action
    }

    // ── Internal dispatch ──

    internal fun dispatch(event: LLMEvent) {
        when (event) {
            is LLMEvent.Token -> tokenHandler?.invoke(event.text)
            is LLMEvent.Reasoning -> reasoningHandler?.invoke(event.text)
            is LLMEvent.Usage -> usageHandler?.invoke(event.info)
            is LLMEvent.Error -> errorHandler?.invoke(event.error)
            is LLMEvent.ToolCallDelta -> toolCallDeltaHandler?.invoke(
                event.index, event.id, event.name, event.argumentsDelta
            )
            is LLMEvent.ToolCallReceived -> toolCallReceivedHandler?.invoke(event.toolCalls)
            is LLMEvent.Done -> doneHandler?.invoke(event.finishReason)
            is LLMEvent.ContextTrimmed -> contextTrimmedHandler?.invoke(
                event.removedCount, event.estimatedTokensSaved
            )
            is LLMEvent.Connected -> connectedHandler?.invoke()
            is LLMEvent.StallDetected -> stallDetectedHandler?.invoke(event.phase, event.idleMillis)
        }
    }
}
