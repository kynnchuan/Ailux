package com.ailux.provider.local

import com.ailux.core.concurrency.MessageConcurrencyPolicy
import com.ailux.core.error.ErrorCode
import com.ailux.core.error.LLMError
import com.ailux.core.error.LLMException
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Adapter that wraps a stateful native [EngineSession] (e.g. a LiteRT-LM
 * `Conversation`) as a public [Session].
 *
 * Compared with [com.ailux.core.session.StatelessProviderSession], which keeps
 * the whole history in memory and resends it every turn, this adapter
 * delegates to the engine's native KV-cache — only the new turn's tokens go
 * through prefill, giving near-O(new tokens) latency.
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
 */
internal class LocalEngineSessionAdapter(
    private val engineSession: EngineSession,
    private val engine: InferenceEngine,
    private val config: SessionConfig,
    private val createdAtEpochMs: Long = System.currentTimeMillis(),
    initialHistory: List<Message> = emptyList(),
) : Session {

    override val sessionId: String = engineSession.sessionId.ifBlank { UUID.randomUUID().toString() }

    private val history: MutableList<Message> = ArrayList<Message>().apply {
        // System instruction is engine-side, but mirror into history so
        // snapshot/restore is symmetric across providers.
        config.systemInstruction?.let { add(Message.System(it)) }
        addAll(initialHistory.ifEmpty { config.initialMessages })
    }

    private val lock = Mutex()
    private val closed = AtomicBoolean(false)

    @Volatile
    private var aborting: Boolean = false

    override fun streamGenerate(request: LLMRequest): Flow<LLMEvent> = flow {
        check(!closed.get()) { "Session $sessionId is closed." }

        when (config.messageConcurrencyPolicy) {
            MessageConcurrencyPolicy.REJECT -> if (lock.isLocked) {
                throw LLMException(
                    LLMError(
                        code = ErrorCode.CONCURRENT_REQUEST_REJECTED,
                        message = "Session $sessionId already has an in-flight message (policy=REJECT)",
                    )
                )
            }
            MessageConcurrencyPolicy.CANCEL_PREVIOUS -> aborting = true
            MessageConcurrencyPolicy.ENQUEUE -> Unit
        }

        lock.withLock {
            aborting = false
            val turnMessages = request.messages
            history.addAll(turnMessages)

            val emittedTokens = StringBuilder()
            var outputTokenCount = 0
            var nativeUsage: EngineEvent.Usage? = null
            var stopReason: EngineStopReason? = null

            try {
                engine.streamGenerate(request, engineSession).collect { ev ->
                    if (aborting) return@collect
                    when (ev) {
                        is EngineEvent.Token -> {
                            emittedTokens.append(ev.text)
                            outputTokenCount += 1
                            emit(LLMEvent.Token(ev.text))
                        }
                        is EngineEvent.Usage -> nativeUsage = ev
                        is EngineEvent.Stop -> stopReason = ev.reason
                    }
                }
            } catch (oom: OutOfMemoryError) {
                emit(LLMEvent.Error(LLMError(ErrorCode.INSUFFICIENT_MEMORY, "OOM during generation", oom)))
                emit(LLMEvent.Done(FinishReason.ERROR))
                return@withLock
            } catch (t: Throwable) {
                val msg = t.message.orEmpty().lowercase()
                val code = when {
                    "oom" in msg || "out of memory" in msg || "memory" in msg -> ErrorCode.INSUFFICIENT_MEMORY
                    else -> ErrorCode.MODEL_LOAD_FAILED
                }
                emit(LLMEvent.Error(LLMError(code, t.message ?: code.name, t)))
                emit(LLMEvent.Done(FinishReason.ERROR))
                return@withLock
            }

            // Fold assistant reply into history for the next snapshot.
            if (emittedTokens.isNotEmpty()) {
                history.add(Message.Assistant(content = emittedTokens.toString()))
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
            if (usage != null) emit(LLMEvent.Usage(usage))

            // FinishReason translation (mirrors LocalRuntimeProvider).
            val finish = when (stopReason) {
                EngineStopReason.EOS,
                EngineStopReason.STOP_WORD -> FinishReason.COMPLETE
                EngineStopReason.LENGTH -> FinishReason.LENGTH
                EngineStopReason.UNKNOWN, null -> {
                    val maxTokens = request.maxTokens
                    if (maxTokens != null && outputTokenCount >= maxTokens) FinishReason.LENGTH
                    else FinishReason.COMPLETE
                }
            }
            emit(LLMEvent.Done(finish))
        }
    }

    override fun snapshot(): SessionSnapshot {
        check(!closed.get()) { "Session $sessionId is closed." }
        val historyCopy = synchronized(history) { history.toList() }
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
            aborting = true
            runCatching { engineSession.close() }
            synchronized(history) { history.clear() }
        }
    }

    private fun messageText(msg: Message): String = when (msg) {
        is Message.System -> msg.content
        is Message.User -> msg.content
        is Message.Assistant -> msg.content ?: ""
        is Message.Tool -> msg.content
    }
}
