package com.ailux.core.context

import com.ailux.core.message.Message

/**
 * Strategy interface for trimming a message list to fit within a token budget.
 *
 * Implementations decide which messages to keep and which to discard, respecting
 * the [protectedIndices] that must not be removed.
 *
 * Default implementation: [com.ailux.api.context.SlidingWindowStrategy] (keeps the
 * most recent messages that fit the budget).
 */
interface ITrimStrategy {

    /**
     * Trim the [messages] list so the total token count fits within [budget].
     *
     * @param messages         the full conversation message list.
     * @param budget           target token budget (messages must fit within this).
     * @param protectedIndices indices of messages that must NOT be removed.
     * @param tokenCounter     counter used to compute token costs.
     * @return a trimmed message list preserving original order.
     */
    fun trim(
        messages: List<Message>,
        budget: Int,
        protectedIndices: Set<Int>,
        tokenCounter: ITokenCounter
    ): List<Message>
}
