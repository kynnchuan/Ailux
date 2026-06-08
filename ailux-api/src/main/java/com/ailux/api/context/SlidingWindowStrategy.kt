package com.ailux.api.context

import com.ailux.core.ITokenCounter
import com.ailux.core.ITrimStrategy
import com.ailux.core.message.Message

class SlidingWindowStrategy: ITrimStrategy {

    override fun trim(
        messages: List<Message>,
        budget: Int,
        protectedIndices: Set<Int>,
        tokenCounter: ITokenCounter
    ): List<Message> {

        val systemMessages = messages.takeWhile { it is Message.System }
        val systemTokens = tokenCounter.count(systemMessages)
        val remainingBudget = budget - systemTokens

        val candidates = messages.drop(systemMessages.size)
        val result = mutableListOf<Message>()
        var usedTokens = 0

        val protectedMessages = protectedIndices
            .filter { it >= systemMessages.size }
            .map { messages[it] }
        val protectedTokens = tokenCounter.count(protectedMessages)

        val availableBudget = remainingBudget - protectedTokens

        for (i in candidates.indices.reversed()) {
            val globalIndex = i + systemMessages.size
            if (globalIndex in protectedIndices) continue

            val msgTokens = tokenCounter.count(candidates[i])
            if (usedTokens + msgTokens <= availableBudget) {
                result.add(0, candidates[i])
                usedTokens += msgTokens
            }
        }

        val finalResult = mutableListOf<Message>()
        finalResult.addAll(systemMessages)
        var resultIdx = 0
        for (i in candidates.indices) {
            val globalIndex = i + systemMessages.size
            if (globalIndex in protectedIndices) {
                finalResult.add(candidates[i])
            } else if (resultIdx < result.size && result[resultIdx] == candidates[i]) {
                finalResult.add(candidates[i])
                resultIdx++
            }
        }
        return finalResult
    }
}