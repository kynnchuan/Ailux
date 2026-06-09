package com.ailux.api.context

import com.ailux.core.context.IMessageProtector
import com.ailux.core.context.TrimAggressiveness
import com.ailux.core.message.Message

/**
 * Function-calling aware message protector.
 *
 * Protects messages that must not be removed to maintain conversation integrity:
 * - **System messages**: always protected (critical for model behavior).
 * - **Undigested tool groups**: an Assistant message with toolCalls + its corresponding
 *   Tool response messages, where no subsequent assistant summary exists yet.
 *   Removing these would break the conversation flow for the model.
 *
 * "Digested" tool groups (those followed by a summarizing assistant reply) are left
 * unprotected and participate in normal budget-based trimming.
 */
class FcMessageProtector : IMessageProtector {

    override fun protect(
        messages: List<Message>,
        aggressiveness: TrimAggressiveness
    ): Set<Int> {
        val protectedIndices = mutableSetOf<Int>()

        // Always protect system messages.
        messages.forEachIndexed { index, msg ->
            if (msg is Message.System) protectedIndices.add(index)
        }

        // Identify function-calling groups and apply protection rules.
        //
        // Only **undigested** groups are force-protected here (removing them would break
        // the conversation flow). **Digested** groups are never force-protected — the
        // CONSERVATIVE vs AGGRESSIVE difference is NOT expressed via protection:
        // - CONSERVATIVE: digested groups compete for leftover budget in the sliding window.
        // - AGGRESSIVE:   the context manager proactively purges digested groups (via
        //                 [digestedGroupIndices]) before the window runs.
        // Either way, this method leaves digested groups unprotected. The [aggressiveness]
        // parameter is retained for interface compatibility and custom protectors.
        val toolGroups = identifyToolGroups(messages)
        for (group in toolGroups) {
            if (!isDigested(messages, group)) {
                protectedIndices.addAll(group.indices)
            }
        }
        return protectedIndices
    }

    /**
     * Returns the indices of all messages belonging to **digested** tool groups.
     *
     * A digested group (1 Assistant-with-toolCalls + its Tool responses, followed by a
     * summarizing assistant reply) is safe to discard as a whole. Used by
     * [TrimAggressiveness.AGGRESSIVE]'s proactive purge. The group is always returned
     * whole, never partially, to preserve the Assistant/Tool pairing invariant.
     */
    override fun digestedGroupIndices(messages: List<Message>): Set<Int> {
        val result = mutableSetOf<Int>()
        for (group in identifyToolGroups(messages)) {
            if (isDigested(messages, group)) result.addAll(group.indices)
        }
        return result
    }

    /**
     * Identify tool-call groups in the message list.
     *
     * A group consists of one Assistant message (with non-empty toolCalls) followed
     * by one or more Tool messages whose toolCallId matches the assistant's calls.
     * Parallel function calls (1 Assistant + N Tools) are treated as a single group.
     */
    private fun identifyToolGroups(messages: List<Message>): List<ToolGroup> {
        val groups = mutableListOf<ToolGroup>()

        var i = 0
        while (i < messages.size) {
            val msg = messages[i]
            if (msg is Message.Assistant && !msg.toolCalls.isNullOrEmpty()) {
                val indices = mutableListOf(i)

                // Collect subsequent Tool messages that belong to this call group.
                var j = i + 1
                val expectedIds = msg.toolCalls!!.map { it.id }.toSet()
                while (j < messages.size && messages[j] is Message.Tool) {
                    val toolMsg = messages[j] as Message.Tool
                    if (toolMsg.toolCallId in expectedIds) {
                        indices.add(j)
                    }
                    j++
                }
                groups.add(ToolGroup(indices = indices, endIndex = j - 1))
                i = j
            } else {
                i++
            }
        }
        return groups
    }

    /**
     * Determine whether a tool group has been "digested" by the model.
     *
     * A group is considered digested if there exists a subsequent Assistant message
     * with non-null content and no toolCalls — indicating the model has already
     * summarized/used the tool results in a reply to the user.
     */
    private fun isDigested(messages: List<Message>, group: ToolGroup): Boolean {
        for (i in (group.endIndex + 1) until messages.size) {
            val msg = messages[i]
            if (msg is Message.Assistant && msg.content != null && msg.toolCalls.isNullOrEmpty()) {
                return true
            }
        }
        return false
    }

    /**
     * Represents a function-calling group.
     *
     * @property indices  all message indices in this group (1 Assistant + N Tool messages).
     * @property endIndex the index of the last Tool message in this group.
     */
    data class ToolGroup(
        val indices: List<Int>,
        val endIndex: Int
    )
}
