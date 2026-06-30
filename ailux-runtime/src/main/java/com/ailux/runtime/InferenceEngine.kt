package com.ailux.runtime

import com.ailux.core.config.LocalRuntimeConfig
import com.ailux.core.message.Message
import com.ailux.core.request.LLMRequest
import com.ailux.core.tool.ToolCall
import kotlinx.coroutines.flow.Flow

/**
 * Engine SPI for on-device (or otherwise embedded) inference backends.
 *
 * Two execution models are supported:
 *
 * 1. **Stateless** (default): callers invoke [streamGenerate] with a full
 *    [LLMRequest] every turn; the engine has no memory between calls.
 *    Implementations MUST always support this path.
 *
 * 2. **Stateful sessions** (opt-in, since v0.3.0): engines that maintain
 *    persistent conversation state (e.g. native KV-cache) override
 *    [supportsSessions] / [createSession] / [streamGenerate] (request, session).
 *    Provider layer detects the capability via [supportsSessions] and routes
 *    multi-turn conversations through the session-based path to reuse
 *    KV-cache and achieve near-O(new tokens) latency.
 *
 * Capability discovery — including the maximum number of concurrent sessions
 * — is reported via [capabilities].
 */
interface InferenceEngine {

    suspend fun load(config: LocalRuntimeConfig)

    /**
     * Stateless generation. The engine treats every call as independent — it
     * MUST replay the full [LLMRequest.messages] each time.
     *
     * For stateful multi-turn conversations on engines that support sessions,
     * prefer [streamGenerate] (request, session).
     */
    fun streamGenerate(request: LLMRequest): Flow<EngineEvent>

    fun release()

    fun capabilities(): EngineCapabilities

    fun sizeInTokens(text: String): Int

    // ───────────────────────────────────────────────────────────────────────
    // Stateful Session SPI (opt-in, since v0.3.0)
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Whether this engine maintains stateful sessions across [streamGenerate]
     * calls. Defaults to `false` — engines MUST override and return `true`
     * when they implement [createSession] and [streamGenerate]
     * (request, session).
     *
     * The Provider layer reads this property to decide whether to route
     * multi-turn work through the session-based path or replay full message
     * history on every call.
     */
    val supportsSessions: Boolean get() = false

    /**
     * Create a new stateful session, optionally seeded with a system
     * instruction and pre-existing conversation history.
     *
     * The returned [EngineSession] is owned by the caller; the engine MUST
     * NOT auto-release it. Closing one session never affects sibling
     * sessions on the same engine.
     *
     * @param systemInstruction optional system prompt establishing the
     *                          assistant's persona for this session.
     * @param initialMessages   optional prior conversation history to prefill
     *                          the session with. Engines MAY perform a
     *                          one-shot prefill so that the first call to
     *                          [streamGenerate] (request, session) can
     *                          benefit from KV-cache reuse.
     *
     * @throws UnsupportedOperationException if [supportsSessions] is `false`.
     */
    fun createSession(
        systemInstruction: String? = null,
        initialMessages: List<Message> = emptyList(),
    ): EngineSession =
        throw UnsupportedOperationException(
            "${this::class.simpleName} does not support stateful sessions; " +
                "use streamGenerate(request) instead."
        )

    /**
     * Stateful generation against an existing [session].
     *
     * **Increment semantics**: implementations MUST treat [request.messages]
     * as **new turns to append** to the session, NOT as a replacement of the
     * full conversation history. Typically this is one trailing user message;
     * multi-message inputs (e.g. a user message plus a tool response) are
     * also valid and are appended in order.
     *
     * The session's KV-cache is reused when the prompt prefix matches; only
     * the new tokens go through prefill. After successful generation, the
     * assistant response is folded into the session's internal history,
     * ready for the next turn.
     *
     * **Context-window governance (ADR-0010).** Because history accumulates
     * inside the native cache, the *window* is governed by the Provider layer,
     * not by this method: the caller
     * ([com.ailux.provider.local.LocalEngineSessionAdapter]) is responsible for
     * deciding *what* to keep (semantic trim) and the engine for *how* to delete
     * (KV execution). Concretely the caller MUST keep the ingested prefix
     * **stable and monotonic** — it never re-sends already-ingested turns as new
     * increments. When the window approaches `n_ctx` (detected via
     * [EngineSession.ingestedTokens]) the caller either drives a precise KV edit
     * (engines reporting [EngineCapabilities.supportsKvCacheEdit]) or closes and
     * replays a trimmed history (all other stateful engines). Engines MUST NOT
     * silently drop history on their own when Ailux owns the window — see
     * [EngineCapabilities.supportsContextShift].
     *
     * Cancellation semantics follow [capabilities]
     * `.supportsInterruptibleCancellation`: see the doc on
     * `LocalRuntimeProvider.streamGenerate` for the honest contract.
     *
     * @throws UnsupportedOperationException if [supportsSessions] is `false`.
     * @throws IllegalStateException if [session] has been [closed][EngineSession.close]
     *                               or was created by a different engine instance.
     */
    fun streamGenerate(request: LLMRequest, session: EngineSession): Flow<EngineEvent> =
        throw UnsupportedOperationException(
            "${this::class.simpleName} does not support stateful sessions; " +
                "use streamGenerate(request) instead."
        )
}

sealed interface EngineEvent {

    data class Token(val text: String): EngineEvent

    data class Stop(val reason: EngineStopReason): EngineEvent

    data class Usage(val promptTokens: Int, val genTokens: Int): EngineEvent

    /** Complete tool calls parsed by an engine-native/tool-template aware runtime. */
    data class ToolCallReceived(val toolCalls: List<ToolCall>): EngineEvent

}

enum class EngineStopReason {

    EOS,

    LENGTH,

    STOP_WORD,

    TOOL_CALL,

    UNKNOWN

}
