package com.ailux.core

import com.ailux.core.capabilities.ProviderCapabilities
import com.ailux.core.event.LLMEvent
import com.ailux.core.request.LLMRequest
import com.ailux.core.response.LLMResponse
import com.ailux.core.session.Session
import com.ailux.core.session.SessionConfig
import com.ailux.core.session.SessionSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * Abstract interface for an LLM provider.
 *
 * Each integration approach (backend proxy, direct cloud connection, on-device
 * inference) implements its own [LLMProvider], wired into the [com.ailux.api]
 * layer either via SPI or by manual registration.
 *
 * ## Two execution paths
 *
 * 1. **Stateless** (legacy): [streamGenerate] / [generate] take a full
 *    [LLMRequest] every turn. The provider replays the entire message history
 *    on every call. Always supported.
 *
 * 2. **Stateful sessions** (since v0.3.0): [openSession] returns a
 *    [Session] handle that carries conversation state across turns. The
 *    Session reuses native KV-cache (local) or a client-side accumulator
 *    (cloud) so subsequent turns are cheaper. This is the **recommended**
 *    path for multi-turn conversations.
 *
 * Capability discovery — including the maximum number of concurrent sessions
 * — is reported via [capabilities].
 */
interface LLMProvider {

    /** Provider capabilities (physical facts; see [ProviderCapabilities]). */
    val capabilities: ProviderCapabilities

    /**
     * Stateless streaming generation. Emits [LLMEvent]s in order:
     * `Token* → Usage? → Done | Error`.
     *
     * The provider treats every call as independent and MUST replay the full
     * [LLMRequest.messages] each time. For multi-turn conversations, prefer
     * [openSession] which reuses KV-cache / client-side history.
     */
    fun streamGenerate(request: LLMRequest): Flow<LLMEvent>

    /**
     * Stateless non-streaming generation: returns the full response in one shot.
     *
     * @throws Exception Implementations are expected to wrap network/auth/timeout
     * errors in [com.ailux.core.error.LLMError] before throwing.
     */
    suspend fun generate(request: LLMRequest): LLMResponse

    // ───────────────────────────────────────────────────────────────────────
    // Stateful Session API (since v0.3.0)
    //
    // Default implementations throw UnsupportedOperationException so that
    // older providers keep compiling. Concrete providers (BackendProxy,
    // Mock, LocalRuntime, …) override both methods.
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Open a fresh stateful [Session] against this provider.
     *
     * For local providers backed by a stateful engine (e.g. LiteRT-LM), this
     * allocates a native conversation handle (KV-cache, sampler state, …).
     * For cloud / proxy providers, this returns an in-memory accumulator that
     * appends each turn's history to the next outbound request.
     *
     * Either way, the Session exposes the **same** API; application code
     * never has to branch on provider type for conversation management.
     *
     * @throws UnsupportedOperationException if this provider has not yet been
     *         migrated to the session-first API. Default for legacy providers.
     */
    fun openSession(config: SessionConfig = SessionConfig()): Session =
        throw UnsupportedOperationException(
            "${this::class.simpleName} does not support stateful sessions yet; " +
                "use streamGenerate(request) instead."
        )

    /**
     * Restore a [Session] from a previously captured [SessionSnapshot].
     *
     * Logical state — message history, system instruction, sampler defaults —
     * is restored exactly. Native KV-cache is NOT restored and will be lazily
     * rebuilt on the first call to [Session.streamGenerate] by replaying the
     * history.
     *
     * @throws UnsupportedOperationException if this provider does not yet
     *         support session snapshots.
     */
    fun restoreSession(snapshot: SessionSnapshot): Session =
        throw UnsupportedOperationException(
            "${this::class.simpleName} does not support session restore yet."
        )
}
