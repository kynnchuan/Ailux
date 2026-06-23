package com.ailux.core.concurrency

/**
 * Policy that governs how **multiple sessions** are scheduled against a single [LLMProvider].
 *
 * Scope: this policy is enforced **across sessions** (one session vs. another), not inside
 * a single session. For ordering of multiple messages **within the same session**, use
 * [MessageConcurrencyPolicy] instead.
 *
 * ## Default — [PARALLEL]
 *
 * The default is [PARALLEL] because:
 * - `BackendProxyProvider` (cloud) is intrinsically concurrent — every request is
 *   independent HTTP, the backend handles its own sessioning.
 * - `LocalRuntimeProvider` may also support concurrent sessions when the underlying
 *   engine (e.g. LiteRT-LM with `ThreadedExecutionManager` + copy-on-write KV cache)
 *   allows it.
 *
 * ## Hard guard rail
 *
 * Even when the user opts into [PARALLEL], the actual fan-out is **capped** by the
 * provider's `ProviderCapabilities.maxConcurrentSessions`:
 * - `maxConcurrentSessions = 1`            → forced serial regardless of policy
 * - `maxConcurrentSessions = n` (n > 1)    → allow up to n parallel sessions
 * - `maxConcurrentSessions = Int.MAX_VALUE` → unbounded (cloud / proxy provider)
 *
 * When the cap is hit, the coordinator falls back to the policy's secondary semantics
 * (typically ENQUEUE) and emits a `warn` log so the application can tune its load.
 *
 * "Policy expresses **intent**, Capability expresses **fact**."
 */
enum class SessionConcurrencyPolicy {

    /**
     * Allow multiple sessions to run simultaneously (subject to
     * `ProviderCapabilities.maxConcurrentSessions`). **Default.**
     */
    PARALLEL,

    /**
     * Cancel the in-flight session(s) when a new one opens.
     *
     * Rare for multi-session use; mainly useful for "single active conversation"
     * applications that want to ensure only one session is consuming the engine
     * at any time.
     */
    CANCEL_PREVIOUS,

    /**
     * Queue new sessions and execute them serially. Sessions still **open**
     * immediately (lightweight handle), but their first inference call blocks
     * until the previous session yields the engine.
     */
    ENQUEUE,

    /**
     * Reject session opens that would exceed the cap. Throws
     * `LLMException` (REJECTED) from `openSession` / `restoreSession`.
     *
     * Useful for hard back-pressure when the application cannot tolerate
     * unbounded queue growth.
     */
    REJECT
}
