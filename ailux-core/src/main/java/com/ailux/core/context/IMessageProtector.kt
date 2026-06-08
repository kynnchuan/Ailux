package com.ailux.core.context

import com.ailux.core.message.Message

/**
 * Interface for identifying messages that must be protected from trimming.
 *
 * Implementations analyze the message list and return indices of messages that
 * the trim strategy must never remove (e.g., system prompts, active tool-call groups).
 *
 * Default implementation: [com.ailux.api.context.FcMessageProtector] (protects
 * system messages and undigested function-calling groups).
 */
interface IMessageProtector {

    /**
     * Identify protected message indices.
     *
     * @param messages       the full conversation message list.
     * @param aggressiveness controls how aggressively digested tool groups are treated.
     * @return a set of message indices that must not be trimmed.
     */
    fun protect(
        messages: List<Message>,
        aggressiveness: TrimAggressiveness
    ): Set<Int>
}
