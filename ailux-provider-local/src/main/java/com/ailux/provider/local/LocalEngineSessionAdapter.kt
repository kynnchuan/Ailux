package com.ailux.provider.local

import com.ailux.core.concurrency.MessageConcurrencyPolicy
import com.ailux.core.config.ContextConfig
import com.ailux.core.context.LLMContextManager
import com.ailux.core.context.TrimAggressiveness
import com.ailux.core.error.ErrorCode
import com.ailux.core.error.LLMError
import com.ailux.core.event.FinishReason
import com.ailux.core.event.LLMEvent
import com.ailux.core.message.Message
import com.ailux.core.request.LLMRequest
import com.ailux.core.response.UsageInfo
import com.ailux.core.session.Session
import com.ailux.core.session.SessionConfig
import com.ailux.core.session.SessionSnapshot
import com.ailux.runtime.EngineEvent
import com.ailux.runtime.EngineSession
import com.ailux.runtime.EngineStopReason
import com.ailux.runtime.InferenceEngine
import com.ailux.runtime.KvCacheEditableSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock as withReentrantLock

/**
 * Adapter that wraps a stateful native [EngineSession] (e.g. a LiteRT-LM
 * `Conversation`) as a public [Session].
 *
 * Compared with [com.ailux.core.session.StatelessProviderSession], which keeps
 * the whole history in memory and resends it every turn, this adapter
 * delegates to the engine's native KV-cache — only the new turn's tokens go
 * through prefill, giving near-O(new tokens) latency.
 *
 * ## Context-window governance owner (ADR-0010)
 *
 * This adapter is the **single owner** of native-path context-window governance.
 * The upper `SessionPipeline.resolveMessages` trim is a no-op here: it only ever
 * sees the incremental turn, not the history accumulating inside the native
 * KV-cache. Governance therefore lives at this layer, where we can both observe
 * the accumulated [EngineSession.ingestedTokens] and rebuild / edit the native
 * session.
 *
 * The split (ADR-0010 §3): **Ailux decides what to keep, the engine executes how
 * to delete.** This adapter routes by [EngineCapabilities.supportsKvCacheEdit]:
 *
 * - **Tier 1** (`supportsKvCacheEdit = true`, e.g. llama.cpp): a precise in-place
 *   KV edit is possible. *(Wired in the engine binding; this adapter still owns
 *   the tip-over detection and the trimmed logical history.)*
 * - **Tier 2** (`supportsKvCacheEdit = false`, e.g. LiteRT-LM): on tip-over we
 *   [EngineSession.close] the current native session and [rebuildSession] from
 *   the **semantically trimmed** logical history (system prompt + protected /
 *   most-recent turns), paying a one-time recompute but keeping the trim correct.
 *
 * Both tiers are driven by an optional [contextManager] + [windowBudgetTokens];
 * when neither is supplied (e.g. older call sites / unit doubles) the adapter
 * keeps its legacy behaviour of forwarding every turn untouched.
 *
 * ## Snapshot semantics
 *
 * The native KV-cache is **not** captured. [snapshot] returns the logical
 * `messages + config` view we have accumulated on the Kotlin side; restoring
 * such a snapshot creates a fresh native session and replays the history on
 * the first [streamGenerate], rebuilding the cache lazily.
 *
 * @param engineSession     the underlying native session (caller-owned —
 *                          we [EngineSession.close] it on [close]).
 * @param engine            the engine used to run [streamGenerate]; needed
 *                          to access [InferenceEngine.streamGenerate]
 *                          `(request, session)` and [InferenceEngine.sizeInTokens].
 * @param config            original session config (kept for snapshot).
 * @param createdAtEpochMs  when the source session was opened.
 * @param initialHistory    history we already logged (system + initial
 *                          messages); used by [snapshot].
 * @param contextManager    optional semantic trimmer (ADR-0010). When non-null
 *                          and the native window approaches [windowBudgetTokens],
 *                          the adapter trims the logical history through this
 *                          before deciding the KV action. `null` disables native
 *                          governance (legacy pass-through behaviour).
 * @param windowBudgetTokens token budget for the native window (typically
 *                          `contextLength - reserveForReply`). `<= 0` disables
 *                          governance regardless of [contextManager].
 * @param trimAggressiveness aggressiveness handed to [contextManager].
 */
internal class LocalEngineSessionAdapter(
    engineSession: EngineSession,
    private val engine: InferenceEngine,
    private val config: SessionConfig,
    private val createdAtEpochMs: Long = System.currentTimeMillis(),
    initialHistory: List<Message> = emptyList(),
    private val contextManager: LLMContextManager? = null,
    private val windowBudgetTokens: Int = 0,
    private val trimAggressiveness: TrimAggressiveness = TrimAggressiveness.CONSERVATIVE,
) : Session {

    /**
     * The live native session. Mutable because ADR-0010 Tier-2 governance
     * rebuilds it (close + replay) on tip-over; guarded by [turnLock] (only
     * mutated between turns, never during an in-flight generation).
     */
    @Volatile
    private var engineSession: EngineSession = engineSession

    override val sessionId: String = engineSession.sessionId.ifBlank { UUID.randomUUID().toString() }

    /**
     * Echoes [SessionConfig.modelId]. For native engines this is the
     * provider-derived `local:<stem>` id of the loaded model file (set by
     * [LocalRuntimeProvider.openSession] / `restoreSession`) and is fixed
     * for the lifetime of this adapter — re-routing per request is
     * physically impossible, see [SessionConfig.modelId] KDoc.
     */
    override val modelId: String? = config.modelId

    /**
     * Working history. Reads and writes guarded by [historyLock]; turns
     * serialised by [turnLock]. See `StatelessProviderSession` for the rationale
     * (do NOT mix `kotlinx Mutex` with `synchronized(history)` — they don't
     * block each other and race `snapshot()` with in-flight streamGenerate).
     */
    private val history: MutableList<Message> = ArrayList<Message>().apply {
        // System instruction is engine-side, but mirror into history so
        // snapshot/restore is symmetric across providers.
        config.systemInstruction?.let { add(Message.System(it)) }
        addAll(initialHistory.ifEmpty { config.initialMessages })
    }

    /** Guards every read/write of [history]; held only across O(1) ops + snapshot copy. */
    private val historyLock = ReentrantLock()

    /** Per-session in-flight turn serializer (coroutine-friendly). */
    private val turnLock = Mutex()

    private val closed = AtomicBoolean(false)

    /**
     * Reference to the worker [Job] of the currently in-flight turn (or `null`
     * when idle). [MessageConcurrencyPolicy.CANCEL_PREVIOUS] uses this to truly
     * abort the previous turn before starting the next: we [Job.cancelAndJoin]
     * it and call [com.ailux.runtime.EngineSession.cancel] to terminate the
     * native pass — replacing the previous best-effort `aborting = true` flag
     * which only stopped Kotlin-side accumulation while letting the engine
     * generate to natural EOS.
     */
    @Volatile
    private var inflightWorker: Job? = null

    override fun streamGenerate(request: LLMRequest): Flow<LLMEvent> = channelFlow {
        check(!closed.get()) { "Session $sessionId is closed." }

        when (config.messageConcurrencyPolicy) {
            MessageConcurrencyPolicy.REJECT -> if (turnLock.isLocked) {
                send(
                    LLMEvent.Error(
                        LLMError(
                            code = ErrorCode.CONCURRENT_REQUEST_REJECTED,
                            message = "Session $sessionId already has an in-flight message (policy=REJECT)",
                        )
                    )
                )
                send(LLMEvent.Done(FinishReason.ERROR))
                return@channelFlow
            }
            MessageConcurrencyPolicy.CANCEL_PREVIOUS -> {
                // Real cancel: ask the native engine to abort, then await the
                // previous worker's full unwind. Either alone is insufficient —
                // without engineSession.cancel() the native pass keeps
                // generating tokens after the Kotlin coroutine has been
                // cancelled; without join() we may race the next turn before
                // the engine's `sendMessageAsync` flow finishes its teardown.
                runCatching { engineSession.cancel() }
                inflightWorker?.cancelAndJoin()
            }
            MessageConcurrencyPolicy.ENQUEUE -> Unit
        }

        turnLock.withLock {
            val turnMessages = request.messages
            historyLock.withReentrantLock { history.addAll(turnMessages) }

            // ADR-0010: govern the native context window BEFORE forwarding the
            // turn. This is the ONLY place native-path trimming can work — the
            // upper SessionPipeline trim only sees the increment and is a no-op.
            // May close+rebuild engineSession (Tier 2) or edit the KV in place
            // (Tier 1). Safe between turns: we hold turnLock and no generation
            // is in flight. The current turn is preserved (it is the most recent
            // and always survives the trim) and is still forwarded below.
            runCatching { maybeGovernWindow(turnMessages) }
                .onFailure { /* governance is best-effort; never block a turn */ }

            // Honour engine.capabilities().supportsBatchedIngest:
            //
            // - true  → forward `request` as-is; the engine has a true
            //           prefill-only API (e.g. llama.cpp) and can ingest each
            //           turn message with its native role boundary preserved.
            //
            // - false → engines like LiteRT-LM 0.13.x have no prefill-only
            //           entry point; a multi-message turn would trigger n-1
            //           wasted generations and pollute the native KV cache.
            //           Collapse all but the last message into the final one
            //           with role-tagged segments and forward a single-message
            //           request. See [collapseTurnForNonBatchedEngine] for the
            //           merge format. This degraded path is removable once
            //           upstream exposes a real batched-ingest API.
            val effectiveRequest =
                if (engine.capabilities().supportsBatchedIngest || turnMessages.size <= 1) {
                    request
                } else {
                    request.copy(messages = collapseTurnForNonBatchedEngine(turnMessages))
                }

            val emittedTokens = StringBuilder()
            var outputTokenCount = 0
            var nativeUsage: EngineEvent.Usage? = null
            var stopReason: EngineStopReason? = null
            var cancelledByPeer = false
            var earlyExit = false

            // Drive the engine flow inside a child coroutine so the next
            // CANCEL_PREVIOUS caller has a [Job] to cancel. We use channelFlow
            // (not flow { coroutineScope { launch {...} } }) because the latter
            // violates Flow's "no cross-coroutine emission" invariant; channelFlow
            // permits `send()` from any child coroutine of its scope.
            val worker = launch {
                try {
                    engine.streamGenerate(effectiveRequest, engineSession).collect { ev ->
                        when (ev) {
                            is EngineEvent.Token -> {
                                emittedTokens.append(ev.text)
                                outputTokenCount += 1
                                send(LLMEvent.Token(ev.text))
                            }
                            is EngineEvent.Usage -> nativeUsage = ev
                            is EngineEvent.Stop -> stopReason = ev.reason
                            is EngineEvent.ToolCallReceived -> {
                                send(LLMEvent.ToolCallReceived(ev.toolCalls))
                                stopReason = EngineStopReason.TOOL_CALL
                            }
                        }
                    }
                } catch (ce: CancellationException) {
                    // Either the consumer cancelled its collector (channelFlow
                    // tears down the worker via structured concurrency) OR a
                    // peer turn ran us through CANCEL_PREVIOUS. Both branches:
                    // tell native to stop, drop the half-baked assistant reply,
                    // do NOT emit Done — the consumer's CancellationException
                    // surfaces naturally on the collector side.
                    cancelledByPeer = true
                    runCatching { engineSession.cancel() }
                    throw ce
                } catch (oom: OutOfMemoryError) {
                    earlyExit = true
                    send(LLMEvent.Error(LLMError(ErrorCode.INSUFFICIENT_MEMORY, "OOM during generation", oom)))
                    send(LLMEvent.Done(FinishReason.ERROR))
                } catch (t: Throwable) {
                    earlyExit = true
                    val msg = t.message.orEmpty().lowercase()
                    val code = when {
                        "oom" in msg || "out of memory" in msg || "memory" in msg -> ErrorCode.INSUFFICIENT_MEMORY
                        else -> ErrorCode.MODEL_LOAD_FAILED
                    }
                    send(LLMEvent.Error(LLMError(code, t.message ?: code.name, t)))
                    send(LLMEvent.Done(FinishReason.ERROR))
                }
            }
            inflightWorker = worker
            try {
                worker.join()
            } finally {
                // Clear only if it's still ours (a CANCEL_PREVIOUS racer may
                // already have installed its own job here, though strict
                // turnLock ordering normally precludes that).
                if (inflightWorker === worker) inflightWorker = null
            }

            if (cancelledByPeer || earlyExit) return@withLock

            // Fold assistant reply into history for the next snapshot.
            if (emittedTokens.isNotEmpty()) {
                historyLock.withReentrantLock {
                    history.add(Message.Assistant(content = emittedTokens.toString()))
                }
            }

            // Usage: prefer engine-native, else size-in-tokens.
            val usage = nativeUsage?.let {
                UsageInfo(inputTokens = it.promptTokens, outputTokens = it.genTokens, estimated = false)
            } ?: runCatching {
                UsageInfo(
                    inputTokens = engine.sizeInTokens(turnMessages.joinToString("\n") { messageText(it) }),
                    outputTokens = engine.sizeInTokens(emittedTokens.toString()),
                    estimated = false,
                )
            }.getOrNull()
            if (usage != null) send(LLMEvent.Usage(usage))

            // FinishReason translation (mirrors LocalRuntimeProvider).
            val finish = when (stopReason) {
                EngineStopReason.EOS,
                EngineStopReason.STOP_WORD -> FinishReason.COMPLETE
                EngineStopReason.LENGTH -> FinishReason.LENGTH
                EngineStopReason.TOOL_CALL -> FinishReason.TOOL_CALL
                EngineStopReason.UNKNOWN, null -> {
                    val maxTokens = request.maxTokens
                    if (maxTokens != null && outputTokenCount >= maxTokens) FinishReason.LENGTH
                    else FinishReason.COMPLETE
                }
            }
            send(LLMEvent.Done(finish))
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // ADR-0010 native context-window governance
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Inspect the accumulated native window and, if it is at/over budget, apply
     * the engine-appropriate trim. Called between turns under [turnLock] with
     * the new turn already folded into [history].
     *
     * Decision (Ailux) vs execution (engine):
     *  1. compute the logical token total — prefer the engine's
     *     [EngineSession.ingestedTokens]; fall back to our own estimate;
     *  2. if within budget → return (the common case);
     *  3. otherwise ask [contextManager] which messages survive;
     *  4. Tier 1 ([KvCacheEditableSession]) → translate dropped messages to a
     *     contiguous token range and evict in place;
     *     Tier 2 (everything else) → close + replay the trimmed history.
     *
     * No-op when governance is disabled ([contextManager] null or
     * [windowBudgetTokens] <= 0).
     */
    private fun maybeGovernWindow(currentTurn: List<Message>) {
        val manager = contextManager ?: return
        if (windowBudgetTokens <= 0) return

        val logical = historyLock.withReentrantLock { history.toList() }
        if (logical.isEmpty()) return

        val ingested = engineSession.ingestedTokens
        val totalTokens =
            if (ingested >= 0L) ingested.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            else runCatching { estimateTokens(logical) }.getOrDefault(0)
        if (totalTokens <= windowBudgetTokens) return

        val result = manager.process(
            messages = logical,
            config = ContextConfig(budget = windowBudgetTokens, aggressiveness = trimAggressiveness),
        )
        val trimmed = result.messages
        if (result.removed.isEmpty() || trimmed.size == logical.size) return

        // Identify the contiguous dropped span. The sliding-window trimmer keeps
        // a leading protected prefix (system + early protected) and a trailing
        // recent window, dropping a single middle block — exactly the shape a
        // KV seq_rm can evict. If the removed indices are NOT contiguous we play
        // safe and rebuild.
        val droppedRange = contiguousDroppedRange(logical, trimmed)

        val edited = droppedRange?.let { range ->
            (engineSession as? KvCacheEditableSession)?.let { editable ->
                val startToken = estimateTokens(logical.subList(0, range.first))
                val dropTokens = estimateTokens(logical.subList(range.first, range.last + 1))
                if (dropTokens <= 0) false
                else runCatching { editable.evictTokenRange(startToken, dropTokens) }
                    .getOrDefault(false)
            } ?: false
        } ?: false

        if (edited) {
            historyLock.withReentrantLock {
                history.clear()
                history.addAll(trimmed)
            }
            return
        }

        // Tier 2 fallback: close the old native session and replay the trimmed
        // history MINUS the current turn (the current turn is still forwarded as
        // the increment by the caller, so seeding it here would double-ingest).
        rebuildSession(trimmed, currentTurn)
    }

    /**
     * Rebuild the native [engineSession] from [trimmed] history, excluding the
     * trailing [currentTurn] (still forwarded as the live increment). Splits the
     * system instruction back out so the engine re-seeds it via its dedicated
     * channel.
     */
    private fun rebuildSession(trimmed: List<Message>, currentTurn: List<Message>) {
        val seed = if (currentTurn.isNotEmpty() && trimmed.takeLast(currentTurn.size) == currentTurn) {
            trimmed.dropLast(currentTurn.size)
        } else {
            trimmed
        }
        val systemInstruction =
            (seed.firstOrNull() as? Message.System)?.content ?: config.systemInstruction
        val seedHistory = seed.filterNot { it is Message.System }

        val old = engineSession
        val rebuilt = engine.createSession(
            systemInstruction = systemInstruction,
            initialMessages = seedHistory,
        )
        engineSession = rebuilt
        runCatching { old.close() }

        historyLock.withReentrantLock {
            history.clear()
            history.addAll(trimmed)
        }
    }

    /**
     * If [trimmed] equals [original] with a single contiguous middle block
     * removed, return the index range (in [original]) of that block; else null.
     * A null result means the trim is not a clean middle-cut and we must rebuild
     * rather than risk an incorrect in-place KV edit.
     */
    private fun contiguousDroppedRange(
        original: List<Message>,
        trimmed: List<Message>,
    ): IntRange? {
        if (trimmed.size >= original.size) return null
        // Longest common prefix.
        var prefix = 0
        while (prefix < trimmed.size && trimmed[prefix] === original[prefix]) prefix++
        // Longest common suffix (not overlapping the prefix).
        var suffix = 0
        while (
            suffix < trimmed.size - prefix &&
            trimmed[trimmed.size - 1 - suffix] === original[original.size - 1 - suffix]
        ) suffix++
        // Everything matched as prefix+suffix on the trimmed side → the gap in
        // original is [prefix, original.size - suffix).
        if (prefix + suffix != trimmed.size) return null
        val dropStart = prefix
        val dropEndExclusive = original.size - suffix
        if (dropEndExclusive <= dropStart) return null
        return dropStart until dropEndExclusive
    }

    private fun estimateTokens(messages: List<Message>): Int =
        messages.sumOf { runCatching { engine.sizeInTokens(messageText(it)) }.getOrDefault(0) }

    override fun snapshot(): SessionSnapshot {
        check(!closed.get()) { "Session $sessionId is closed." }
        // Same lock as the brief mutations inside streamGenerate; turnLock may
        // still be held by an in-flight stream — we don't block turn progress.
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
        if (closed.compareAndSet(false, true)) {
            // Best-effort: ask the native engine to abort any in-flight pass,
            // then cancel the worker so the consumer flow observes
            // cancellation promptly. We don't join (close is non-suspend);
            // the consumer's collector surfaces the cancellation on next emit.
            runCatching { engineSession.cancel() }
            inflightWorker?.cancel()
            runCatching { engineSession.close() }
            historyLock.withReentrantLock { history.clear() }
        }
    }

    private fun messageText(msg: Message): String = when (msg) {
        is Message.System -> msg.content
        is Message.User -> msg.content
        is Message.Assistant -> msg.content ?: ""
        is Message.Tool -> msg.content
    }

    /**
     * Degraded path for engines that report
     * `EngineCapabilities.supportsBatchedIngest = false` (e.g. LiteRT-LM 0.13.x).
     *
     * Such engines have no "prefill-only" / "batched-ingest" entry point — every
     * `sendMessage*` call triggers a full sampling pass. If we forwarded a turn
     * containing N messages as-is, the engine would generate N times, polluting
     * the native KV cache with N-1 discarded middle replies and wasting compute.
     *
     * We collapse the turn into a single message whose content tags each
     * original segment with its source role, so the model can still distinguish
     * the user prompt from individual tool results in a single forward pass.
     * The final segment dictates the carrier role (so a turn ending in a Tool
     * reply produces a Tool-carried merged message; a turn ending in a User
     * message stays User-carried — matching the role boundary the model would
     * have observed had we sent them separately).
     *
     * Role markers are intentionally human-readable so failed-call diagnostics
     * (full prompt logs in PrivacyConfig.DEBUG_VERBOSE) remain inspectable. The
     * markers are **not** model-trained tokens — they are framing hints for the
     * synthesis turn. If a future engine needs a different marker syntax, this
     * is the single place to adapt.
     *
     * Removable once LiteRT-LM (or any other engine in this category) exposes
     * a real batched-ingest / prefill-only API and flips `supportsBatchedIngest`
     * to `true`.
     */
    internal fun collapseTurnForNonBatchedEngine(turn: List<Message>): List<Message> {
        if (turn.size <= 1) return turn
        val builder = StringBuilder()
        for ((idx, msg) in turn.withIndex()) {
            if (idx > 0) builder.append('\n')
            when (msg) {
                is Message.User -> builder.append("[user]\n").append(msg.content)
                is Message.Tool -> {
                    builder.append("[tool:").append(msg.toolCallId).append("]\n")
                    builder.append(msg.content)
                }
                is Message.Assistant -> {
                    // Rare in incoming turns, but kept symmetric for completeness.
                    builder.append("[assistant]\n").append(msg.content ?: "")
                }
                is Message.System -> {
                    // System should be handled at session-open time; if it leaks
                    // into a turn we still preserve it so semantics aren't lost.
                    builder.append("[system]\n").append(msg.content)
                }
            }
        }
        val merged = when (val last = turn.last()) {
            is Message.Tool -> Message.Tool(toolCallId = last.toolCallId, content = builder.toString())
            is Message.User -> Message.User(content = builder.toString())
            is Message.Assistant -> Message.Assistant(content = builder.toString())
            is Message.System -> Message.System(content = builder.toString())
        }
        return listOf(merged)
    }
}
