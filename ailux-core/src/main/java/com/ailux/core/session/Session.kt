package com.ailux.core.session

import com.ailux.core.event.LLMEvent
import com.ailux.core.request.LLMRequest
import com.ailux.core.response.LLMResponse
import com.ailux.core.task.LLMTask
import kotlinx.coroutines.flow.Flow

/**
 * A stateful conversation handle bound to a specific [LLMProvider].
 *
 * Session is the **first-class abstraction** of Ailux's conversational layer.
 * It unifies the semantics of:
 * - **Local engines** with native KV cache reuse (e.g. LiteRT-LM `Conversation`):
 *   only the new turn's tokens are processed; prefix is cached natively.
 * - **Cloud / proxy providers** without native session support:
 *   the Session acts as a client-side history accumulator, sending the full
 *   message list each turn. The application sees the **same** API.
 *
 * This end-to-end symmetry means application code never has to branch on
 * "is this a local or cloud provider?" for conversation management.
 *
 * ## Lifecycle
 *
 * 1. **Open**: `provider.openSession(config)` returns a fresh Session.
 *    For local engines this allocates KV cache; for cloud providers this is
 *    a lightweight in-memory object.
 * 2. **Use**: call [streamGenerate] one or more times. Each call sends the
 *    **incremental** new turn (typically a single `Message.User`), not the
 *    whole history.
 * 3. **Close**: call [close] (preferably via Kotlin's `use { }` block) to
 *    release native resources. Once closed, all methods throw
 *    `IllegalStateException`.
 *
 * Sessions are **independent**: closing one does not affect any other session
 * on the same provider. Multiple sessions may run in parallel subject to
 * `SessionConcurrencyPolicy` and `ProviderCapabilities.maxConcurrentSessions`.
 *
 * ## Concurrency
 *
 * Within a single session, the [MessageConcurrencyPolicy] (set via
 * [SessionConfig.messageConcurrencyPolicy]) decides what happens when
 * [streamGenerate] is called while a previous turn is still streaming.
 * The default is `ENQUEUE` — turns are serialized and the second call
 * waits for the first to complete.
 *
 * ## Persistence
 *
 * The application can call [snapshot] to obtain a serializable
 * [SessionSnapshot] that captures the **logical** conversation state
 * (messages + config). The native KV cache is **not** persisted — restoring
 * from a snapshot will re-build the cache by replaying the message history
 * on the first `streamGenerate` call.
 *
 * @see SessionConfig
 * @see SessionSnapshot
 */
interface Session : AutoCloseable {

    /**
     * Stable identifier for this session. Useful for logs, diagnostics, and
     * cross-process tracing. **Not** intended as a routing key — application
     * code should hold the [Session] object directly.
     */
    val sessionId: String

    /**
     * Send the next turn into this session and stream events back.
     *
     * **Increment semantics**: [request].messages should contain only the
     * **new** turn (typically the latest `Message.User`), not the entire
     * history. The provider — local or cloud — is responsible for combining
     * it with the session state:
     * - Local engine: appends to the native KV cache, processes only new tokens.
     * - Cloud provider: appends to the in-memory history accumulator and
     *   sends the resulting full list to the backend.
     *
     * The first message returned in any [LLMEvent.Token] / [LLMEvent.Done]
     * is conceptually the assistant reply for this turn; the Session
     * automatically records it as a `Message.Assistant` so the next call
     * sees it as part of the history.
     *
     * Behavior when called while a previous turn is still in-flight is
     * governed by [SessionConfig.messageConcurrencyPolicy].
     *
     * **Raw stream** — bypasses the [LLMTask] state machine, stall detection,
     * context management and diagnostics pipeline. For UI use cases prefer
     * [streamGenerateAsTask].
     *
     * @throws IllegalStateException if [close] has already been called.
     */
    fun streamGenerate(request: LLMRequest): Flow<LLMEvent>

    /**
     * Send the next turn into this session and return an [LLMTask] handle.
     *
     * Equivalent to [streamGenerate] but wraps the raw event flow with the
     * task state machine (Idle → Connecting → Streaming → Completed/Failed),
     * exposed via [LLMTask.state] for direct `collectAsState` in Compose
     * and Flow consumers.
     *
     * **Pipeline note** — bare [Session] implementations only attach the
     * minimum state machine. When obtained via [com.ailux.api.AiluxClient.openSession],
     * the returned task additionally goes through stall detection (v0.2.3),
     * context management (v0.2.1) and diagnostics (v0.2.5). Direct callers
     * of `provider.openSession(...)` get the bare task.
     *
     * Behavior when called while a previous turn is still in-flight is
     * governed by [SessionConfig.messageConcurrencyPolicy] — same as
     * [streamGenerate].
     *
     * @throws IllegalStateException if [close] has already been called.
     * @since 0.3.0b
     */
    fun streamGenerateAsTask(request: LLMRequest): LLMTask =
        BasicLLMTaskFactory.fromSessionStream(request, ::streamGenerate)

    /**
     * Send the next turn into this session and suspend until the full reply
     * is ready. Non-streaming equivalent of [streamGenerate].
     *
     * Internally collects [streamGenerate] and aggregates:
     * - [LLMEvent.Token] text → [LLMResponse.text]
     * - The last [LLMEvent.Usage] (if any) → [LLMResponse.usage]
     *
     * If the stream emits an [LLMEvent.Error] before [LLMEvent.Done], this
     * method throws [com.ailux.core.error.LLMException] carrying that error.
     *
     * The assistant turn is folded into the session history just like
     * [streamGenerate] — calling [generate] and then [streamGenerate] (or
     * vice versa) on the same Session produces a coherent conversation.
     *
     * Behavior when called while a previous turn is still in-flight is
     * governed by [SessionConfig.messageConcurrencyPolicy].
     *
     * @throws IllegalStateException if [close] has already been called.
     * @throws com.ailux.core.error.LLMException if the stream surfaces an error.
     * @throws kotlinx.coroutines.CancellationException if cancelled.
     * @since 0.3.0b
     */
    suspend fun generate(request: LLMRequest): LLMResponse =
        SessionDefaults.collectToResponse(request, ::streamGenerate)

    /**
     * Capture the current logical state of this session as a serializable snapshot.
     *
     * The returned [SessionSnapshot] contains the message history, system
     * instruction, sampler overrides and any provider hints — everything needed
     * to reconstruct the session **logically**. The native KV cache is **not**
     * captured; on restore, the cache is lazily rebuilt by replaying the history.
     *
     * Safe to call concurrently with [streamGenerate] — the snapshot reflects the
     * state up to the most recently committed turn.
     *
     * @throws IllegalStateException if [close] has already been called.
     */
    fun snapshot(): SessionSnapshot

    /**
     * Release all resources held by this session. **Idempotent** — calling
     * [close] more than once is safe and does nothing on subsequent calls.
     *
     * For local engines this releases the native KV cache. For cloud providers
     * this drops the in-memory history.
     *
     * After [close] returns, every other method on this interface throws
     * [IllegalStateException].
     */
    override fun close()
}
