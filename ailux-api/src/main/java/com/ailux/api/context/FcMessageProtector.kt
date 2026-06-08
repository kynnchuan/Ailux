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
        val toolGroups = identifyToolGroups(messages)

        for (group in toolGroups) {
            val isDigested = isDigested(messages, group)

            when {
                // Not yet digested: the model has not summarized the tool results,
                // so removing them would break the conversation flow. Force protect.
                !isDigested -> protectedIndices.addAll(group.indices)

                // Digested + CONSERVATIVE: do NOT force-protect. The tool group has been
                // summarized by a subsequent assistant reply. It participates in normal
                // budget-based sliding-window trimming — kept if budget allows, discarded
                // if budget is tight. This gives the sliding window flexibility to retain
                // useful context when there's room, without forcing it at the cost of
                // recent messages.
                isDigested && aggressiveness == TrimAggressiveness.CONSERVATIVE -> {
                    // Not force-protected — participates in budget-based trimming.
                }

                // Digested + AGGRESSIVE: the tool group has been summarized, so it's
                // safe to discard. Not protected — maximizes room for recent messages.
                isDigested && aggressiveness == TrimAggressiveness.AGGRESSIVE -> {
                    // Not protected.
                }
            }
        }
        return protectedIndices
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
