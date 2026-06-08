package com.ailux.core.config

import com.ailux.core.context.TrimAggressiveness

/**
 * Configuration for a single context-trimming pass.
 *
 * Constructed internally by [com.ailux.api.AiluxClient] from [ModelConfig] and
 * the global [TrimAggressiveness] setting, then passed to [com.ailux.core.context.LLMContextManager.process].
 *
 * @property budget         the maximum token count allowed for the trimmed message list
 *                          (computed as contextWindow - reserveForReply).
 * @property aggressiveness controls how digested function-calling groups are treated
 *                          during trimming. Defaults to [TrimAggressiveness.CONSERVATIVE].
 */
data class ContextConfig(
    val budget: Int,
    val aggressiveness: TrimAggressiveness = TrimAggressiveness.CONSERVATIVE
)
