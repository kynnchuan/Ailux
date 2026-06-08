package com.ailux.api.context

import com.ailux.core.context.IMessageProtector
import com.ailux.core.context.ITokenCounter
import com.ailux.core.context.ITrimStrategy
import com.ailux.core.context.LLMContextManager
import com.ailux.core.config.ContextConfig
import com.ailux.core.context.ContextResult
import com.ailux.core.message.Message

/**
 * Default implementation of [LLMContextManager].
 *
 * Executes a three-stage pipeline:
 * 1. **Token budget check** — if total tokens are within budget, return immediately.
 * 2. **Message protection** — identify indices that must not be trimmed.
 * 3. **Sliding window trim** — remove oldest unprotected messages until budget is met.
 *
 * All three components ([tokenCounter], [trimStrategy], [protector]) are pluggable.
 *
 * @property tokenCounter estimates or precisely counts tokens per message.
 * @property trimStrategy decides which messages to discard when over budget.
 * @property protector    identifies messages that must not be trimmed.
 */
class DefaultLLMContextManager(
    val tokenCounter: ITokenCounter = EstimatedTokenCounter(),
    val trimStrategy: ITrimStrategy = SlidingWindowStrategy(),
    val protector: IMessageProtector = FcMessageProtector()
) : LLMContextManager {

    override fun process(messages: List<Message>, config: ContextConfig): ContextResult {
        // Stage 1: token budget check — if within budget, return immediately.
        val totalTokens = tokenCounter.count(messages)
        if (totalTokens <= config.budget) {
            return ContextResult(
                messages = messages,
                removed = emptyList(),
                estimatedTokensSaved = 0
            )
        }

        // Stage 2: identify protected message indices (system msgs, active FC groups).
        val protectedIndices = protector.protect(messages, config.aggressiveness)

        // Stage 3: execute the trim strategy (sliding window from most recent).
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

    companion object {

        /**
         * Factory method for creating a [DefaultLLMContextManager] with sensible defaults.
         *
         * @param safetyMargin safety margin applied by [EstimatedTokenCounter] (default 15%).
         * @return a fully configured [DefaultLLMContextManager] ready to use.
         */
        fun default(
            safetyMargin: Float = 0.15f
        ): DefaultLLMContextManager = DefaultLLMContextManager(
            tokenCounter = EstimatedTokenCounter(safetyMargin = safetyMargin),
            trimStrategy = SlidingWindowStrategy(),
            protector = FcMessageProtector()
        )
    }
}
