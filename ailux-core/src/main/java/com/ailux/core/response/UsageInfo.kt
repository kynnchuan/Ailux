package com.ailux.core.response

import kotlinx.serialization.Serializable

/**
 * Token usage information for an LLM request.
 *
 * When usage is reported by the backend, [estimated] is `false`.
 * When usage is computed locally on the client (e.g. via a client-side
 * tokenizer), [estimated] is `true`.
 *
 * @property inputTokens  Number of tokens in the prompt / input.
 * @property outputTokens Number of tokens in the generated output.
 * @property estimated    Whether the usage is locally estimated rather than
 *                        an authoritative server-side value.
 */
@Serializable
data class UsageInfo(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val estimated: Boolean = false,
)
