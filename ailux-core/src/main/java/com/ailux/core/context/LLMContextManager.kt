package com.ailux.core.context

import com.ailux.core.config.ContextConfig
import com.ailux.core.message.Message

/**
 * Core interface for managing LLM conversation context (message trimming).
 *
 * Implementations of this interface decide how to trim the message list when the
 * total estimated token count exceeds the configured budget. The SDK invokes
 * [process] automatically before sending messages to the provider.
 *
 * Out-of-the-box implementation: [com.ailux.api.context.DefaultLLMContextManager].
 *
 * Set to `null` in [com.ailux.api.AiluxConfig] to disable automatic trimming.
 */
interface LLMContextManager {

    /**
     * Process the message list according to the given [config].
     *
     * If the total token count is within budget, the original list is returned unchanged.
     * Otherwise, messages are trimmed following the configured strategy and protection rules.
     *
     * @param messages the full conversation message list.
     * @param config   trimming configuration (budget, aggressiveness).
     * @return a [ContextResult] containing the trimmed messages and metadata.
     */
    fun process(messages: List<Message>, config: ContextConfig): ContextResult
}
