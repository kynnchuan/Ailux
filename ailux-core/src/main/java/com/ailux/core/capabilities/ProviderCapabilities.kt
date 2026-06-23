package com.ailux.core.capabilities

/**
 * Runtime-discoverable capabilities of an [com.ailux.core.LLMProvider]
 * implementation.
 *
 * Capabilities reflect **physical / provider-side facts**, not user intent.
 * The Client layer combines these facts with the user-supplied
 * `SessionConcurrencyPolicy` / `MessageConcurrencyPolicy` to decide actual
 * runtime behaviour (with soft-degradation + warning when policy exceeds
 * capability — never throwing on policy/capability mismatch).
 *
 * @property supportsTool                        whether the provider can perform
 *   native tool calling (function calling).
 * @property supportsStream                      whether `streamGenerate` emits
 *   tokens incrementally instead of one final blob.
 * @property supportsVision                      whether the provider accepts
 *   image attachments.
 * @property maxContextToken                     model's context window in tokens,
 *   or `null` when unknown / not enforced by the provider.
 * @property supportsInterruptibleCancellation   whether cancelling the collecting
 *   coroutine truly stops native work mid-token. See spec §6.1.5.
 *
 * @property maxConcurrentSessions hard upper bound on the number of [com.ailux.core.session.Session]s
 *   that may execute concurrently against this provider. **Since v0.3.0.**
 *
 *   - `1` — provider serialises all session work internally.
 *   - `n > 1` — up to `n` sessions may run in parallel.
 *   - [Int.MAX_VALUE] — no provider-side limit (typical for cloud transports
 *     and proxy backends).
 *
 *   This value caps the user-facing `SessionConcurrencyPolicy.PARALLEL`
 *   intent: when the requested policy exceeds capability, the Client layer
 *   downgrades to ENQUEUE behaviour and emits a one-time warning.
 *
 *   Providers MUST NOT lie — reporting a higher value than the provider can
 *   safely sustain risks GPU race conditions, KV-cache corruption, or OOM.
 *   When in doubt, report `1`.
 */
data class ProviderCapabilities(
    val supportsTool: Boolean,
    val supportsStream: Boolean,
    val supportsVision: Boolean,
    val maxContextToken: Int?,
    val supportsInterruptibleCancellation: Boolean,
    val maxConcurrentSessions: Int = 1,
)
