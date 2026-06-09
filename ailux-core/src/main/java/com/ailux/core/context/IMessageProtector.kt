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

    /**
     * Identify indices belonging to **digested** tool groups — groups that have already
     * been summarized by a later assistant reply and are therefore safe to discard.
     *
     * This is the input to [TrimAggressiveness.AGGRESSIVE]'s proactive purge: the context
     * manager removes these (as whole groups) **before** the sliding window runs, freeing
     * their budget for more recent conversational turns. Under
     * [TrimAggressiveness.CONSERVATIVE] these indices are left alone (they compete for
     * leftover budget in the window instead).
     *
     * Default implementation returns an empty set (no proactive purge).
     *
     * @param messages the full conversation message list.
     * @return indices of messages that belong to digested tool groups.
     */
    fun digestedGroupIndices(messages: List<Message>): Set<Int> = emptySet()
}
