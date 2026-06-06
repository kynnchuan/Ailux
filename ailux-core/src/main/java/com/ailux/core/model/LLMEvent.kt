package com.ailux.core.model

/**
 * Streaming events emitted by [LLMProvider.streamGenerate].
 *
 * Consumers collect a `Flow<LLMEvent>` and react to each subtype:
 *
 * ```kotlin
 * provider.streamGenerate(request).collect { event ->
 *     when (event) {
 *         is LLMEvent.Token     -> appendContent(event.text)
 *         is LLMEvent.Reasoning -> appendThinking(event.text)
 *         is LLMEvent.Usage     -> showUsage(event.info)
 *         is LLMEvent.Error     -> showError(event.error)
 *         LLMEvent.Done         -> hideLoading()
 *     }
 * }
 * ```
 *
 * v0.1 defines five event types. v0.3+ will add `FallbackTriggered` for
 * cloud-to-on-device fallback scenarios.
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

    /** Marks the stream as completed normally. Always the last event. */
    data object Done : LLMEvent()
}
