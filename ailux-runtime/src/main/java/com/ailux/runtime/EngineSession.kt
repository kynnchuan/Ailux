package com.ailux.runtime

/**
 * Optional stateful session handle exposed by engines that maintain persistent
 * conversation state (typically a native KV-cache, sampler state, prompt cache,
 * etc.) across multiple [InferenceEngine.streamGenerate] calls.
 *
 * ## When does an engine implement this?
 *
 * Implementing [EngineSession] is **opt-in**. Engines fall into two categories:
 *
 * - **Stateless engines** (e.g. a one-shot forward-pass executor, a cloud SSE
 *   transport): leave [InferenceEngine.supportsSessions] as `false`; consumers
 *   call [InferenceEngine.streamGenerate] without a session and the full
 *   message history is replayed every time.
 * - **Stateful engines** (e.g. `LiteRTLMEngine` backed by
 *   `com.google.ai.edge.litertlm.Conversation`): override
 *   [InferenceEngine.supportsSessions] to `true` and implement
 *   [InferenceEngine.createSession] / [InferenceEngine.streamGenerate]
 *   `(request, session)`. The Provider layer can then reuse the underlying
 *   KV-cache across turns, achieving near-O(new tokens) latency.
 *
 * ## Lifecycle
 *
 * - Created by [InferenceEngine.createSession]; each call returns an
 *   independent session.
 * - Active until explicitly [close]d. Engines MUST NOT auto-release sessions —
 *   resource management is the caller's responsibility.
 * - Closing one session never affects sibling sessions on the same engine.
 * - After [close], passing the session to [InferenceEngine.streamGenerate]
 *   MUST throw `IllegalStateException`.
 *
 * ## Concurrency
 *
 * Whether multiple sessions on the same engine may execute concurrently is
 * controlled by [EngineCapabilities.maxConcurrentSessions]:
 *  - `1`           → the engine serialises all session work internally.
 *  - `n > 1`       → up to `n` sessions may run in parallel.
 *  - `Int.MAX_VALUE` → no engine-side limit (typical for cloud transports).
 *
 * The Provider/Client layer enforces this limit and downgrades user-facing
 * `SessionConcurrencyPolicy` accordingly (warn once, never throw).
 *
 * ## Identity
 *
 * [sessionId] is intended for **logging, tracing, and engine-internal
 * bookkeeping** only. Application code should hold the [EngineSession] object
 * itself (or its higher-level wrapper exposed by the Provider layer); the id
 * string is not a stable cross-process handle.
 */
interface EngineSession : AutoCloseable {

    /**
     * Stable identifier for this session within the engine instance. Intended
     * for logging and tracing; not a cross-process / persisted identifier.
     */
    val sessionId: String

    /**
     * Approximate native memory footprint of this session's KV-cache and
     * related state, in bytes. Returns `-1L` when the engine cannot provide
     * an estimate.
     *
     * Provider/Client layers may use this for memory budgeting decisions
     * (e.g. evicting cold sessions when the device is under pressure).
     */
    val approximateMemoryBytes: Long

    /**
     * `true` if subsequent generations on this session will be able to reuse
     * cached prompt prefix (KV-cache hit), `false` if the next generation
     * will incur a full prefill cost.
     *
     * This is best-effort metadata for diagnostics / UX (e.g. showing the
     * user "first response may be slower" indicator); engines that cannot
     * track this state should return `false`.
     */
    val hasCachedPrefix: Boolean

    /**
     * Best-effort request to abort the in-flight generation on this session.
     *
     * Engines whose
     * [EngineCapabilities.supportsInterruptibleCancellation] is `true` MUST
     * use this hook to signal the native side to stop generating ASAP — for
     * example LiteRT-LM ≥ 0.13 maps it to `Conversation.cancelProcess()`,
     * which causes the streaming `Flow` to terminate (next observed event is
     * the natural close of the flow).
     *
     * Engines without a real abort hook MAY leave the default no-op
     * implementation; callers detect that via the capability flag and fall
     * back to coroutine-level cancellation, accepting that the native pass
     * keeps running until natural EOS or until the session is `close()`d.
     *
     * Concurrency: this method is safe to call from any thread (including
     * the consumer's coroutine that is currently collecting the streaming
     * `Flow`). Implementations MUST NOT block on the in-flight generation;
     * the actual termination is asynchronous.
     *
     * Idempotent: cancelling an already-finished or already-cancelled
     * session MUST be a no-op (must not throw).
     *
     * @since 0.3.0c
     */
    fun cancel() { /* default: best-effort, no native abort available */ }

    /**
     * Release the native resources backing this session. Idempotent — calling
     * [close] more than once MUST be a no-op (it MUST NOT throw, even on the
     * second call).
     *
     * After close, any further use of this session via
     * [InferenceEngine.streamGenerate] MUST throw `IllegalStateException`.
     */
    override fun close()
}
