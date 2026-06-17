package com.ailux.core.context

import com.ailux.core.message.Message

/**
 * Interface for estimating or precisely counting the number of tokens in messages.
 *
 * The SDK uses this to decide whether context trimming is needed and how many
 * messages can fit within the token budget.
 *
 * Default implementation: [com.ailux.api.context.EstimatedTokenCounter] (heuristic-based).
 * For precise counting, callers can supply their own implementation backed by a tokenizer
 * library (e.g., tiktoken).
 */
interface ITokenCounter {

    /**
     * Count the total tokens across all [messages].
     *
     * @param messages the list of messages to count.
     * @return estimated or precise total token count.
     */
    fun count(messages: List<Message>): Int

    /**
     * Count the tokens of a single [message].
     *
     * @param message the message to count.
     * @return estimated or precise token count for this message.
     */
    fun count(message: Message): Int
}
