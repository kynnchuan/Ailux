package com.ailux.api.stream

import com.ailux.core.event.LLMEvent
import com.ailux.core.task.LLMTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

/**
 * Extension functions on [LLMTask] for simplified event consumption.
 *
 * Three levels of abstraction (choose the one that fits your use case):
 *
 * ## Level 1: tokenFlow() — "I just want the text"
 * ```kotlin
 * val reply = task.tokenFlow().fold("") { acc, t -> acc + t }
 * ```
 *
 * ## Level 2: handle {} — "I want callbacks, no boilerplate"
 * ```kotlin
 * task.handle {
 *     onToken { text -> appendContent(text) }
 *     onReasoning { text -> appendThinking(text) }
 *     onDone { reason -> markFinished(reason) }
 * }
 * ```
 *
 * ## Level 3: events.collect {} — "I need full control"
 * ```kotlin
 * task.events.collect { event ->
 *     when (event) { ... }
 * }
 * ```
 */

// ─────────────────────────────────────────────────────────────
// Level 1: tokenFlow()
// ─────────────────────────────────────────────────────────────

/**
 * Returns a [Flow] that emits only the text token strings from this task's event stream.
 *
 * All other events (reasoning, usage, errors, tool calls, etc.) are silently ignored.
 * This is the simplest way to consume the model's reply text.
 *
 * Example — collect the full reply:
 * ```kotlin
 * val fullReply = task.tokenFlow().fold("") { acc, chunk -> acc + chunk }
 * ```
 *
 * Example — stream to UI:
 * ```kotlin
 * task.tokenFlow().collect { chunk ->
 *     _text.update { it + chunk }
 * }
 * ```
 *
 * Note: this flow does NOT trigger the FC loop. If you need Function Calling support,
 * use [handle] or collect [LLMTask.events] directly.
 */
fun LLMTask.tokenFlow(): Flow<String> =
    events.mapNotNull { event ->
        (event as? LLMEvent.Token)?.text
    }

/**
 * Returns a [Flow] that emits only the reasoning / chain-of-thought text chunks.
 *
 * Useful when you want to display the model's thinking process separately from
 * the final answer.
 */
fun LLMTask.reasoningFlow(): Flow<String> =
    events.mapNotNull { event ->
        (event as? LLMEvent.Reasoning)?.text
    }

// ─────────────────────────────────────────────────────────────
// Level 2: handle {} DSL
// ─────────────────────────────────────────────────────────────

/**
 * Collect this task's events using a callback-style DSL.
 *
 * The [block] configures which events you want to handle — all others are
 * silently ignored. This is a **suspend** function that completes when the
 * event stream ends (i.e., after [LLMEvent.Done] is dispatched).
 *
 * Example:
 * ```kotlin
 * viewModelScope.launch {
 *     task.handle {
 *         onToken { text -> _content.update { it + text } }
 *         onReasoning { text -> _reasoning.update { it + text } }
 *         onToolCallReceived { calls -> pendingCalls = calls }
 *         onDone { reason -> finishReason = reason }
 *         onError { e -> _error.value = e.message }
 *         onUsage { info -> _usage.value = info }
 *         onContextTrimmed { removed, saved ->
 *             Log.d("Ailux", "Trimmed $removed msgs, saved ~$saved tokens")
 *         }
 *     }
 *     // Stream has ended — handle FC loop or finalize UI here
 * }
 * ```
 *
 * **Important**: This function does NOT automatically run the Function Calling loop.
 * If `onDone` reports [FinishReason.TOOL_CALL], you must execute the tools and
 * call `streamGenerate()` again yourself.
 *
 * @param block DSL configuration lambda. See [EventHandlerScope] for available callbacks.
 */
suspend fun LLMTask.handle(block: EventHandlerScope.() -> Unit) {
    val scope = EventHandlerScope().apply(block)
    events.collect { event -> scope.dispatch(event) }
}
