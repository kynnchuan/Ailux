package com.ailux.core.model

/**
 * Observable state of an LLM task (request lifecycle).
 *
 * The API layer exposes it as a `StateFlow<LLMTaskState>`, so the UI can
 * reactively render loading / streaming / error states.
 *
 * v0.1 state transitions:
 * ```
 * Idle -> Connecting -> Streaming -> Completed
 *              |              |
 *              v              v
 *           Failed        Cancelling -> Idle
 *                              |
 *                              v
 *                           Failed
 * ```
 *
 * v0.3+ will introduce a `Fallbacking` state between Streaming and a backup provider.
 */
sealed class LLMTaskState {

    /** No active request. Initial state and the resting state after cancellation. */
    data object Idle : LLMTaskState()

    /** Connecting to the provider. */
    data object Connecting : LLMTaskState()

    /** Receiving tokens.
     *  @property tokenCount Cumulative number of tokens received so far. */
    data class Streaming(val tokenCount: Int = 0) : LLMTaskState()

    /** A cancellation has been requested; waiting for the provider to acknowledge. */
    data object Cancelling : LLMTaskState()

    /** The task failed.
     *  @property error Structured error information. */
    data class Failed(val error: LLMError) : LLMTaskState()

    /** The task completed successfully.
     *  @property usage Final usage information, if available. */
    data class Completed(val usage: UsageInfo? = null) : LLMTaskState()
}
