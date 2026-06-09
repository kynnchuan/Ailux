package com.ailux.core.state

import com.ailux.core.error.LLMError
import com.ailux.core.response.UsageInfo

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

    /** Waiting in the concurrency queue.
     *  @property position Zero-based position in the queue (0 = next to execute). */
    data class Queued(val position: Int = 0) : LLMTaskState()

    /** Connecting to the provider. */
    data object Connecting : LLMTaskState()

    /** Receiving tokens.
     *  @property tokenCount Cumulative number of tokens received so far.
     *  @property stalled    Whether the stream is considered stalled (no new tokens
     *                       within the configured timeout).
     *  @property idleMillis Milliseconds elapsed since the last token was received. */
    data class Streaming(
        val tokenCount: Int = 0,
        val stalled: Boolean = false,
        val idleMillis: Long = 0L
    ) : LLMTaskState()

    /** A cancellation has been requested; waiting for the provider to acknowledge. */
    data object Cancelling : LLMTaskState()

    /** The task failed.
     *  @property error Structured error information. */
    data class Failed(val error: LLMError) : LLMTaskState()

    /** The task completed successfully.
     *  @property usage Final usage information, if available. */
    data class Completed(val usage: UsageInfo? = null) : LLMTaskState()
}


/**
 * Sub-phases within the [LLMTaskState.Connecting] state, providing finer-grained
 * progress information to the UI layer.
 */
enum class ConnectingPhase {
    /** TCP / TLS handshake in progress. */
    ESTABLISHING,

    /** Connection established; waiting for the first token from the model. */
    WAITING_FIRST_TOKEN
}