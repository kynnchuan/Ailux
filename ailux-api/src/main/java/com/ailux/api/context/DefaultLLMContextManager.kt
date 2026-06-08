package com.ailux.api.context

import com.ailux.core.IMessageProtector
import com.ailux.core.ITokenCounter
import com.ailux.core.ITrimStrategy
import com.ailux.core.LLMContextManager
import com.ailux.core.config.ContextConfig
import com.ailux.core.context.ContextResult
import com.ailux.core.context.TrimAggressiveness
import com.ailux.core.message.Message

class DefaultLLMContextManager(
    val tokenCounter: ITokenCounter = EstimatedTokenCounter(),
    val trimStrategy: ITrimStrategy,
    val protector: IMessageProtector
): LLMContextManager {

    override fun process(messages: List<Message>, config: ContextConfig): ContextResult {

        val totalTokens = tokenCounter.count(messages)

        if (totalTokens <= config.budget) {
            return ContextResult(messages = messages, removed = emptyList(), estimatedTokensSaved = 0)
        }

        val protectedIndices = protector.protect(messages, config.aggressiveness)

        val trimmed = trimStrategy.trim(messages, config.budget, protectedIndices, tokenCounter)

        val removed = messages - trimmed.toSet()
        val savedTokens = totalTokens - tokenCounter.count(trimmed)

        return ContextResult(
            messages = trimmed,
            removed = removed,
            estimatedTokensSaved = savedTokens,
            warning = "Cropped ${removed.size} messages, estimated savings of $savedTokens tokens"
        )
    }

    companion object{

        fun default(
            safetyMargin: Float = 0.15f,
            aggressiveness: TrimAggressiveness = TrimAggressiveness.CONSERVATIVE
        ): DefaultLLMContextManager = DefaultLLMContextManager(
            tokenCounter =
        )

    }

}