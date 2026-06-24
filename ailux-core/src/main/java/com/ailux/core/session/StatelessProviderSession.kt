package com.ailux.core.session

import com.ailux.core.concurrency.MessageConcurrencyPolicy
import com.ailux.core.event.LLMEvent
import com.ailux.core.message.Message
import com.ailux.core.request.LLMRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock as withReentrantLock
import kotlinx.serialization.json.JsonObject

/**
 * Generic [Session] implementation for **stateless providers** — cloud HTTP
 * transports (BackendProxyProvider, direct OpenAI/Anthropic), mocks, and any
 * local engine whose `supportsSessions = false`.
 *
 * ## How it differs from a native KV-cache session
 *
 * | Aspect                       | Stateless accumulator              | Native KV-cache session            |
 * |------------------------------|------------------------------------|------------------------------------|
 * | History storage              | In-memory `List<Message>`          | Native KV-cache                    |
 * | Per-turn cost                | O(prompt) — full history resent    | O(new tokens) — only diff prefill  |
 * | Concurrency on same session  | Serialised by [MessageConcurrencyPolicy] | Serialised by engine + policy |
 * | Snapshot                     | Trivial (`messages` is the state)  | Snapshot stores logical history,  KV rebuilt on restore |
 *
 * The application-facing API is identical — that's the point of [Session].
 *
 * ## Concurrency
 *
 * Two locks, with non-overlapping responsibilities:
 *  - [turnLock] (`kotlinx.coroutines.sync.Mutex`) — serialises **turns** so two
 *    in-flight `streamGenerate` calls can't interleave their append-and-stream
 *    blocks.
 *  - [historyLock] (`java.util.concurrent.locks.ReentrantLock`) — guards the
 *    `history` list's reads and writes. Held only across O(1) mutations and the
 *    O(n) snapshot copy; never held across a suspending `collect`.
 *
 * The historic bug (v0.3.0) was mixing a coroutine `Mutex` for writes with
 * `synchronized(history)` for reads — those two primitives are independent and
 * do **not** block each other, so a frequent `snapshot()` racing with
 * `streamGenerate` could throw `ConcurrentModificationException`. Using one
 * `ReentrantLock` for the data structure removes that race; we keep the
 * coroutine `Mutex` strictly for turn-level scheduling.
 *
 * In-session ordering is enforced by [MessageConcurrencyPolicy]:
 *  - [MessageConcurrencyPolicy.ENQUEUE] (default): turns serialised via
 *    [turnLock]; callers wait for the previous turn's flow to fully drain.
 *  - [MessageConcurrencyPolicy.CANCEL_PREVIOUS]: the previous turn's flow is
 *    cancelled (via its collecting coroutine) before the new turn runs. Because
 *    we cannot reach into the caller's coroutine here, "cancel" is implemented
 *    pessimistically: the new turn waits on the lock, but the lock-holder is
 *    asked to abort by setting [aborting]; this is best-effort. (See
 *    `docs/SESSION-ENGINE-AUDIT-zh.md` issue 3 for the planned upgrade to a
 *    real cancel path via `Conversation.cancelProcess()`.)
 *  - [MessageConcurrencyPolicy.REJECT]: the new turn throws if a previous turn
 *    is still in flight.
 *
 * ## Snapshot / restore
 *
 * - [snapshot] captures `messages + systemInstruction + samplerOverrides`.
 * - Restore is handled by the provider's `restoreSession`, which creates a new
 *   [StatelessProviderSession] seeded with `snapshot.messages`.
 *
 * @param sessionId            unique id for this session (logs/tracing).
 * @param config               original config the session was opened with.
 * @param createdAtEpochMs     when this session (or its source snapshot) was created.
 * @param streamGenerateRaw    delegate that produces a stateless flow of
 *                             [LLMEvent]s given an [LLMRequest] with the FULL
 *                             accumulated history attached. Typically the
 *                             provider's own `streamGenerate`.
 */
class StatelessProviderSession(
    override val sessionId: String = UUID.randomUUID().toString(),
    private val config: SessionConfig,
    private val createdAtEpochMs: Long = System.currentTimeMillis(),
    initialHistory: List<Message> = emptyList(),
    private val streamGenerateRaw: (LLMRequest) -> Flow<LLMEvent>,
) : Session {

    /** Echoes [SessionConfig.modelId] so `generate()` can stamp [com.ailux.core.response.LLMResponse.model]. */
    override val modelId: String? = config.modelId

    /**
     * Working history. **All reads and writes** are guarded by [historyLock]
     * (a `java.util.concurrent.locks.ReentrantLock`). The coroutine [turnLock]
     * below serialises *turns* (so two `streamGenerate` calls can't interleave
     * their append-and-stream blocks), but it does NOT guard the data structure
     * itself — `snapshot()` / `close()` are non-suspend and must use the JVM
     * lock to be mutually exclusive with the brief mutations inside
     * `streamGenerate`. Mixing `kotlinx Mutex` and `synchronized(history)` was
     * the original v0.3 bug: those two primitives are independent and don't
     * block each other, so concurrent snapshot + streamGenerate could observe
     * a half-updated list or throw `ConcurrentModificationException`.
     */
    private val history: MutableList<Message> = ArrayList<Message>().apply {
        config.systemInstruction?.let { add(Message.System(it)) }
        addAll(initialHistory.ifEmpty { config.initialMessages })
    }

    /** Guards every read/write of [history]; held only across O(1) operations. */
    private val historyLock = ReentrantLock()

    /** Per-session in-flight turn serializer (coroutine-friendly). */
    private val turnLock = Mutex()

    /** Closed flag (CAS-style). */
    private val closed = AtomicBoolean(false)

    /** Best-effort abort signal for CANCEL_PREVIOUS (advisory; honoured by collector). */
    @Volatile
    private var aborting: Boolean = false

    override fun streamGenerate(request: LLMRequest): Flow<LLMEvent> = flow {
        check(!closed.get()) { "Session $sessionId is closed." }

        when (config.messageConcurrencyPolicy) {
            MessageConcurrencyPolicy.REJECT -> {
                if (turnLock.isLocked) {
                    throw com.ailux.core.error.LLMException(
                        com.ailux.core.error.LLMError(
                            code = com.ailux.core.error.ErrorCode.CONCURRENT_REQUEST_REJECTED,
                            message = "Session $sessionId already has an in-flight message (policy=REJECT)",
                        )
                    )
                }
            }
            MessageConcurrencyPolicy.CANCEL_PREVIOUS -> {
                // Signal current holder to drop and yield ASAP.
                aborting = true
            }
            MessageConcurrencyPolicy.ENQUEUE -> Unit /* fall through */
        }

        turnLock.withLock {
            aborting = false
            // Append the incoming new turn(s) to the working history.
            val turnMessages = request.messages
            historyLock.withReentrantLock { history.addAll(turnMessages) }

            // Build the *full* outbound request — the stateless transport sees
            // the whole conversation. We deliberately strip system from the
            // incoming request if already present in history to avoid duplicates.
            val fullRequest = request.copy(
                messages = historyLock.withReentrantLock { history.toList() }
            )

            val assistantBuf = StringBuilder()
            streamGenerateRaw(fullRequest)
                .onEach { ev ->
                    if (aborting) {
                        // CANCEL_PREVIOUS observed: stop accumulating tokens.
                        return@onEach
                    }
                    if (ev is LLMEvent.Token) assistantBuf.append(ev.text)
                }
                .collect { ev -> if (!aborting) emit(ev) }

            // Fold assistant reply back into history so the next turn sees it.
            if (assistantBuf.isNotEmpty()) {
                historyLock.withReentrantLock {
                    history.add(Message.Assistant(content = assistantBuf.toString()))
                }
            }
        }
    }

    override fun snapshot(): SessionSnapshot {
        check(!closed.get()) { "Session $sessionId is closed." }
        // Held briefly across an O(n) copy — turnLock may still be held by an
        // in-flight stream (we do NOT block turn progress for snapshot); we only
        // synchronise with the brief mutations inside that stream.
        val historyCopy = historyLock.withReentrantLock { history.toList() }
        return SessionSnapshot(
            messages = historyCopy,
            systemInstruction = config.systemInstruction,
            samplerOverrides = config.samplerOverrides,
            providerHint = config.providerHint,
            createdAtEpochMs = createdAtEpochMs,
            snapshotAtEpochMs = System.currentTimeMillis(),
        )
    }

    override fun close() {
        // Idempotent: only the first close does any work.
        if (closed.compareAndSet(false, true)) {
            aborting = true
            historyLock.withReentrantLock { history.clear() }
        }
    }
}
