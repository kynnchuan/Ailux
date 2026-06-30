package com.ailux.provider.local

import com.ailux.core.config.ContextConfig
import com.ailux.core.context.ContextResult
import com.ailux.core.context.ITokenCounter
import com.ailux.core.context.LLMContextManager
import com.ailux.core.message.Message
import com.ailux.runtime.InferenceEngine

/**
 * Self-contained, core-only [LLMContextManager] used by
 * [LocalEngineSessionAdapter] to govern the **native KV-cache window**
 * (ADR-0010).
 *
 * Why a dedicated implementation instead of `ailux-api`'s
 * `DefaultLLMContextManager`: `:ailux-provider-local` deliberately depends only
 * on `:ailux-core` + `:ailux-runtime` (never `:ailux-api`). This trimmer
 * therefore re-expresses the *minimum* semantics ADR-0010 requires using only
 * core types, and is shaped specifically for the KV path:
 *
 * - **Keep the system prefix.** Leading [Message.System] messages are never
 *   dropped (losing the persona is the MediaPipe failure mode ADR-0010 calls
 *   out).
 * - **Protect the most recent turns.** The trailing window (sized to fit the
 *   remaining budget) always survives, so the model keeps short-term coherence.
 * - **Drop a single contiguous middle block.** Whatever sits between the kept
 *   prefix and the kept suffix is removed as one run — this is exactly the shape
 *   a llama.cpp `seq_rm` can evict in place (Tier 1) and the shape
 *   [LocalEngineSessionAdapter.contiguousDroppedRange] recognises.
 *
 * It is intentionally simpler than the api-layer trimmer (no function-calling
 * group digestion / aggressiveness branching beyond "keep recent"): the api
 * trimmer still owns the cloud / stateless path; this one owns the native path.
 * Applications wanting richer semantics can inject their own
 * [LLMContextManager] into [LocalRuntimeProvider].
 */
internal class NativeWindowContextManager(
    private val tokenCounter: ITokenCounter = CharCountTokenCounter,
) : LLMContextManager {

    override fun process(messages: List<Message>, config: ContextConfig): ContextResult {
        val total = tokenCounter.count(messages)
        if (total <= config.budget) {
            return ContextResult(messages = messages, removed = emptyList(), estimatedTokensSaved = 0)
        }

        // Leading system prefix is always retained.
        val systemPrefix = messages.takeWhile { it is Message.System }
        val systemTokens = tokenCounter.count(systemPrefix)
        val tail = messages.drop(systemPrefix.size)

        val recencyBudget = (config.budget - systemTokens).coerceAtLeast(0)

        // Fill the trailing window from the most recent message backward.
        val keptTailReversed = ArrayList<Message>()
        var used = 0
        for (i in tail.indices.reversed()) {
            val cost = tokenCounter.count(tail[i])
            if (used + cost <= recencyBudget || keptTailReversed.isEmpty()) {
                // Always keep at least the single most recent message even if it
                // alone exceeds the budget — dropping the live turn would be worse
                // than a slightly-over window the engine can still handle.
                keptTailReversed.add(tail[i])
                used += cost
            } else {
                break
            }
        }
        val keptTail = keptTailReversed.asReversed()

        val result = systemPrefix + keptTail
        if (result.size == messages.size) {
            return ContextResult(messages = messages, removed = emptyList(), estimatedTokensSaved = 0)
        }

        val removed = messages.subList(systemPrefix.size, messages.size - keptTail.size).toList()
        val saved = tokenCounter.count(removed)
        return ContextResult(
            messages = result,
            removed = removed,
            estimatedTokensSaved = saved,
            warning = "Native window trim: dropped ${removed.size} middle messages (~$saved tokens)",
        )
    }
}

/**
 * Tokenizer-free fallback counter (~4 chars/token). Used only when no
 * engine-backed counter is wired; [LocalEngineSessionAdapter] prefers the
 * engine's own `sizeInTokens` for the tip-over decision, so this is a coarse
 * default for the trim arithmetic.
 */
internal object CharCountTokenCounter : ITokenCounter {
    override fun count(messages: List<Message>): Int = messages.sumOf { count(it) }
    override fun count(message: Message): Int {
        val text = textOf(message)
        return if (text.isEmpty()) 0 else ((text.length + 3) / 4).coerceAtLeast(1)
    }
}

/**
 * [ITokenCounter] backed by the engine's own tokenizer ([InferenceEngine.sizeInTokens]).
 *
 * **Critical for ADR-0010 correctness:** [LocalEngineSessionAdapter] decides
 * *whether* to trim using the engine's token scale (via
 * `EngineSession.ingestedTokens` / `engine.sizeInTokens`), so the trimmer MUST
 * measure the budget on the **same scale**. If the trimmer used a different
 * counter (e.g. the coarse `~4 chars/token` [CharCountTokenCounter]), the tip-over
 * check could say "over budget" while the trimmer computes "under budget" and
 * drops nothing — the trim degenerates into a no-op. Wiring this counter keeps
 * the decision and the execution consistent.
 */
internal class EngineTokenCounter(
    private val engine: InferenceEngine,
) : ITokenCounter {
    override fun count(messages: List<Message>): Int = messages.sumOf { count(it) }
    override fun count(message: Message): Int {
        val text = textOf(message)
        return if (text.isEmpty()) 0
        else runCatching { engine.sizeInTokens(text) }.getOrElse { ((text.length + 3) / 4).coerceAtLeast(1) }
    }
}

private fun textOf(message: Message): String = when (message) {
    is Message.System -> message.content
    is Message.User -> message.content
    is Message.Assistant -> message.content ?: ""
    is Message.Tool -> message.content
}
