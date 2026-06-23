package com.ailux.core.concurrency

/**
 * Policy that governs how **multiple messages within the same Session** are scheduled.
 *
 * Scope: this policy is enforced **inside a single session** (one in-flight
 * `streamGenerate` vs. a new one targeting the same session). For ordering across
 * different sessions, use [SessionConcurrencyPolicy].
 *
 * ## Why no PARALLEL?
 *
 * Unlike [SessionConcurrencyPolicy], this enum **deliberately omits** PARALLEL even
 * for cloud providers. Reasoning:
 *
 * 1. **Local engine constraint**: a stateful session owns a single native KV cache
 *    (e.g. LiteRT-LM `Conversation`). Concurrent token generation on the same cache
 *    is undefined behavior — it would corrupt the conversation state.
 *
 * 2. **Application clarity**: even for cloud providers where two concurrent HTTP
 *    requests are technically harmless, allowing parallel messages within one session
 *    forces the application to disambiguate **which response belongs to which message**.
 *    The simpler mental model — "one session, one in-flight message at a time" —
 *    matches how humans actually talk in a conversation.
 *
 * 3. **End-to-end symmetry**: keeping the same constraint on local and cloud sessions
 *    means application code never has to special-case provider type for in-session
 *    ordering.
 *
 * If the application genuinely needs parallel inference, the right pattern is
 * **multiple sessions**, gated by [SessionConcurrencyPolicy].
 *
 * ## Default — [ENQUEUE]
 *
 * The default preserves message ordering, which is what almost every chat-style UI
 * actually wants.
 */
enum class MessageConcurrencyPolicy {

    /**
     * Cancel the in-flight message in this session before starting the new one.
     *
     * Useful for "user hit send while previous reply was still streaming" UX —
     * the old reply is dropped, the new turn takes over.
     */
    CANCEL_PREVIOUS,

    /**
     * Queue the new message and execute it after the current one finishes. **Default.**
     */
    ENQUEUE,

    /**
     * Reject the new message immediately if one is already running in this session.
     * Throws `LLMException` (REJECTED) from `Session.streamGenerate`.
     */
    REJECT
}
