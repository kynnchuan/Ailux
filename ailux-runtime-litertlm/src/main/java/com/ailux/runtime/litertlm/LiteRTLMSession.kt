package com.ailux.runtime.litertlm

import com.ailux.runtime.EngineSession
import com.google.ai.edge.litertlm.Conversation
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import com.google.ai.edge.litertlm.Message as LiteRtMessage

/**
 * Engine-side [EngineSession] backed by a LiteRT-LM
 * [com.google.ai.edge.litertlm.Conversation].
 *
 * Owns the underlying native conversation handle and exposes:
 *
 * - `sendMessageAsync(...)` — used by [LiteRTLMEngine.streamGenerate]`(request, session)`
 *   to drive the streaming `Flow<EngineEvent>`.
 * - `sendMessageSync(...)`  — used to flush non-terminal turns when the caller
 *   bundles multiple messages (e.g. user + tool result) into a single request;
 *   only the final turn streams back tokens.
 * - [close]                 — releases the native conversation and is idempotent.
 *
 * ## Not exposed
 *
 * KV-cache footprint is opaque in LiteRT-LM 0.13.x — there is no public
 * "size of cache" API on `Conversation`. We therefore report
 * [approximateMemoryBytes] = `-1L` and [hasCachedPrefix] = `false`. Both
 * fields are advisory (diagnostics / UX) and a coarse value is harmless.
 *
 * ## Thread-safety
 *
 * Upstream documentation does not guarantee that one `Conversation` is safe
 * to drive from multiple threads concurrently. Callers (notably the provider-
 * layer `LocalEngineSessionAdapter`) MUST serialise `sendMessage*` calls per
 * session. We do not add another internal lock here to avoid double-serialisation.
 */
internal class LiteRTLMSession(
    private val conversation: Conversation,
) : EngineSession {

    override val sessionId: String = UUID.randomUUID().toString()

    override val approximateMemoryBytes: Long = -1L

    override val hasCachedPrefix: Boolean = false

    @Volatile
    private var closed: Boolean = false

    /** Stream a single LiteRT-LM message; emits incremental [LiteRtMessage] tokens. */
    fun sendMessageAsync(message: LiteRtMessage): Flow<LiteRtMessage> {
        check(!closed) { "Session $sessionId is closed." }
        return conversation.sendMessageAsync(message)
    }

    /**
     * Synchronously dispatch a non-terminal turn. Used when a request bundles
     * multiple messages (e.g. user + tool response); only the LAST one streams
     * back tokens.
     */
    fun sendMessageSync(message: LiteRtMessage): LiteRtMessage {
        check(!closed) { "Session $sessionId is closed." }
        return conversation.sendMessage(message)
    }

    /**
     * Maps to [Conversation.cancelProcess]; the in-flight `sendMessageAsync`
     * flow terminates promptly (no further tokens, no thrown exception on the
     * collector side beyond a normal flow completion).
     *
     * Best-effort: if the session is already closed, swallowed; if the native
     * side has already finished generating, the call is a no-op. Never throws.
     */
    override fun cancel() {
        if (closed) return
        runCatching { conversation.cancelProcess() }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { conversation.close() }
    }
}
