package com.ailux.api.context

import com.ailux.core.context.ITokenCounter
import com.ailux.core.message.Message

/**
 * Heuristic-based token counter for mixed Chinese/English text.
 *
 * Uses character-level rules to approximate token counts without requiring a
 * model-specific tokenizer. The estimate is intentionally conservative (over-estimates)
 * to avoid exceeding the context window, which would cause an API error.
 *
 * Approximation rules:
 * - CJK characters (U+4E00–U+9FFF): ~1.5 tokens each
 * - CJK punctuation (U+3000–U+303F): ~1.5 tokens each
 * - JSON structural chars ({, }, [, ], :, ", ,): ~1 token each
 * - ASCII letters/digits/other: ~0.25 tokens each (~4 chars per token)
 * - Per-message overhead: +4 tokens (for im_start, role, newline, im_end)
 *
 * @property safetyMargin additional percentage to over-estimate by (default 15%).
 *                        e.g., 0.15 means the raw estimate is multiplied by 1.15.
 */
class EstimatedTokenCounter(
    val safetyMargin: Float = 0.15f
) : ITokenCounter {

    override fun count(messages: List<Message>): Int {
        return messages.sumOf { count(it) }
    }

    override fun count(message: Message): Int {
        val text = when (message) {
            is Message.System -> message.content
            is Message.User -> message.content
            is Message.Tool -> message.content
            is Message.Assistant -> {
                (message.content ?: "") + (
                    message.toolCalls?.joinToString {
                        it.name + it.arguments
                    } ?: ""
                )
            }
        }
        val rawEstimate = estimateTokens(text)
        return (rawEstimate * (1 + safetyMargin)).toInt()
    }

    /**
     * Estimate tokens for a raw text string.
     *
     * Uses a scaled-integer approach: each character contributes a weighted score
     * (scaled by 10x), then the total is divided by 10 to get the token count.
     * A fixed overhead of 4 is added per message for the chat-ML framing tokens.
     */
    private fun estimateTokens(text: String): Int {
        var tokens = 0
        for (char in text) {
            tokens += when {
                char.code in 0x4E00..0x9FFF -> 15  // CJK ideograph: ~1.5 tokens
                char.code in 0x3000..0x303F -> 15  // CJK punctuation: ~1.5 tokens
                char in "{}[]\":," -> 10            // JSON structural: ~1 token
                else -> 3                           // ASCII/other: ~0.3 tokens
            }
        }
        return tokens / 10 + 4  // +4 for per-message protocol overhead
    }
}
