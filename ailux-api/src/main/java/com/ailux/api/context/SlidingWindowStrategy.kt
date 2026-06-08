package com.ailux.api.context

import com.ailux.core.context.ITokenCounter
import com.ailux.core.context.ITrimStrategy
import com.ailux.core.message.Message

/**
 * Sliding-window trim strategy: keeps the most recent messages that fit the budget.
 *
 * Algorithm:
 * 1. System messages are always retained (they occupy a fixed budget).
 * 2. Protected messages' token cost is pre-deducted from the remaining budget.
 * 3. Starting from the most recent non-system, non-protected message and working
 *    backward, messages are included until the available budget is exhausted.
 * 4. The final result preserves the original message order.
 *
 * This ensures that the model always sees the most recent conversation context,
 * while system prompts and protected messages (e.g., active tool-call groups) are
 * never discarded.
 */
class SlidingWindowStrategy : ITrimStrategy {

    override fun trim(
        messages: List<Message>,
        budget: Int,
        protectedIndices: Set<Int>,
        tokenCounter: ITokenCounter
    ): List<Message> {
        // Step 1: Always retain system messages at the beginning.
        val systemMessages = messages.takeWhile { it is Message.System }
        val systemTokens = tokenCounter.count(systemMessages)
        val remainingBudget = budget - systemTokens

        // Step 2: Pre-deduct the cost of protected (non-system) messages.
        val candidates = messages.drop(systemMessages.size)
        val protectedMessages = protectedIndices
            .filter { it >= systemMessages.size }
            .map { messages[it] }
        val protectedTokens = tokenCounter.count(protectedMessages)
        val availableBudget = remainingBudget - protectedTokens

        // Step 3: Fill from the most recent unprotected messages backward.
        val kept = mutableListOf<Message>()
        var usedTokens = 0

        for (i in candidates.indices.reversed()) {
            val globalIndex = i + systemMessages.size
            if (globalIndex in protectedIndices) continue

            val msgTokens = tokenCounter.count(candidates[i])
            if (usedTokens + msgTokens <= availableBudget) {
                kept.add(0, candidates[i])
                usedTokens += msgTokens
            }
        }

        // Step 4: Reassemble in original order (system + protected + kept).
        val finalResult = mutableListOf<Message>()
        finalResult.addAll(systemMessages)

        var keptIdx = 0
        for (i in candidates.indices) {
            val globalIndex = i + systemMessages.size
            if (globalIndex in protectedIndices) {
                finalResult.add(candidates[i])
            } else if (keptIdx < kept.size && kept[keptIdx] == candidates[i]) {
                finalResult.add(candidates[i])
                keptIdx++
            }
        }
        return finalResult
    }
}
