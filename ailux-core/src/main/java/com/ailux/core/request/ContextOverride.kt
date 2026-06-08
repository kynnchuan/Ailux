package com.ailux.core.request

import com.ailux.core.context.IMessageProtector
import com.ailux.core.context.ITokenCounter
import com.ailux.core.context.ITrimStrategy
import com.ailux.core.context.TrimAggressiveness

/**
 * Per-request overrides for context management behavior.
 *
 * When attached to an [LLMRequest], any non-null field overrides the corresponding
 * component from the global [com.ailux.api.AiluxConfig] for that single request only.
 * Fields left as `null` inherit from the global configuration.
 *
 * This enables scenarios like:
 * - Using a more aggressive trim strategy during FC multi-turn loops.
 * - Temporarily switching to a precise token counter for a critical request.
 * - Overriding aggressiveness without changing the global setting.
 *
 * ```kotlin
 * val request = LLMRequest(
 *     messages = messages,
 *     contextOverride = ContextOverride(
 *         aggressiveness = TrimAggressiveness.AGGRESSIVE
 *     )
 * )
 * ```
 *
 * @property strategy       overrides the trim strategy.
 * @property protector      overrides the message protector.
 * @property tokenCounter   overrides the token counter.
 * @property aggressiveness overrides the trim aggressiveness level for this request.
 */
data class ContextOverride(
    val strategy: ITrimStrategy? = null,
    val protector: IMessageProtector? = null,
    val tokenCounter: ITokenCounter? = null,
    val aggressiveness: TrimAggressiveness? = null
)
