package com.ailux.api.context

import com.ailux.core.config.ModelConfig

/**
 * Built-in registry of popular LLM models and their context window sizes (in tokens).
 *
 * Used to automatically resolve the token budget when the caller specifies a model
 * name but does not explicitly set [ModelConfig.contextWindowSize].
 *
 * Supports both exact name matching and prefix matching (e.g., "gpt-4o-2024-05-13"
 * matches the "gpt-4o" entry via longest-prefix rule).
 *
 * To add new models, simply extend the [models] map below.
 */
object ModelRegistry {

    private val models = mapOf(
        // ═══════════════════════════════════════════════
        // OpenAI — Generation Models
        // ═══════════════════════════════════════════════
        "gpt-5" to 400_000,
        "gpt-5-mini" to 400_000,
        "gpt-5-nano" to 400_000,
        "gpt-4.1" to 1_047_576,
        "gpt-4.1-mini" to 1_047_576,
        "gpt-4.1-nano" to 1_047_576,
        "gpt-4o" to 128_000,
        "gpt-4o-mini" to 128_000,
        "gpt-4-turbo" to 128_000,
        "gpt-4" to 8_192,
        "gpt-3.5-turbo" to 16_385,

        // OpenAI — Reasoning Models (o-series)
        "o3" to 200_000,
        "o3-mini" to 200_000,
        "o4-mini" to 200_000,
        "o1" to 200_000,
        "o1-pro" to 200_000,
        "o1-mini" to 128_000,

        // ═══════════════════════════════════════════════
        // Anthropic Claude
        // ═══════════════════════════════════════════════
        "claude-opus-4" to 200_000,
        "claude-sonnet-4" to 200_000,
        "claude-4.5-opus" to 200_000,
        "claude-4.5-sonnet" to 200_000,
        "claude-3.7-sonnet" to 200_000,
        "claude-3.5-sonnet" to 200_000,
        "claude-3.5-haiku" to 200_000,
        "claude-3-opus" to 200_000,
        "claude-3-haiku" to 200_000,

        // ═══════════════════════════════════════════════
        // Google Gemini
        // ═══════════════════════════════════════════════
        "gemini-2.5-pro" to 1_048_576,
        "gemini-2.5-flash" to 1_048_576,
        "gemini-2.0-flash" to 1_048_576,
        "gemini-1.5-pro" to 2_097_152,
        "gemini-1.5-flash" to 1_048_576,

        // ═══════════════════════════════════════════════
        // DeepSeek
        // ═══════════════════════════════════════════════
        "deepseek-v4-pro" to 1_000_000,
        "deepseek-v4-flash" to 1_000_000,

        // ═══════════════════════════════════════════════
        // Alibaba Qwen
        // ═══════════════════════════════════════════════
        "qwen3-max" to 256_000,
        "qwen3.5" to 256_000,
        "qwen-plus" to 1_000_000,
        "qwen-flash" to 1_000_000,
        "qwen-turbo" to 1_000_000,

        // ═══════════════════════════════════════════════
        // Zhipu GLM
        // ═══════════════════════════════════════════════
        "glm-4.5" to 128_000,
        "glm-4" to 128_000,
        "glm-4-long" to 1_000_000,

        // ═══════════════════════════════════════════════
        // Moonshot (Kimi)
        // ═══════════════════════════════════════════════
        "kimi-k2" to 256_000,
        "kimi-k2.5" to 256_000,
        "moonshot-v1-128k" to 128_000,
        "moonshot-v1-32k" to 32_000,
        "moonshot-v1-8k" to 8_000,

        // ═══════════════════════════════════════════════
        // ByteDance Doubao
        // ═══════════════════════════════════════════════
        "doubao-pro" to 256_000,
        "doubao-lite" to 128_000,
        "doubao-1.5-pro" to 256_000,

        // ═══════════════════════════════════════════════
        // Mistral
        // ═══════════════════════════════════════════════
        "mistral-large" to 128_000,
        "mistral-nemo" to 128_000
    )

    /**
     * Look up the context window size for a given model name.
     *
     * Resolution order:
     * 1. Exact match against the registry keys.
     * 2. Longest-prefix match (e.g., "gpt-4o-2024-05-13" matches "gpt-4o").
     *
     * @param modelName the model identifier to look up.
     * @return context window size in tokens, or `null` if no match found.
     */
    fun getContextWindow(modelName: String): Int? {
        // Exact match first.
        models[modelName]?.let { return it }
        // Longest prefix match.
        return models.entries
            .filter { modelName.startsWith(it.key) }
            .maxByOrNull { it.key.length }
            ?.value
    }
}

/**
 * Resolve the effective context window size for a given model configuration.
 *
 * Priority:
 * 1. [ModelConfig.contextWindowSize] (explicit user override) — highest priority.
 * 2. [ModelRegistry] lookup by model name.
 * 3. Fallback default: 128,000 tokens.
 *
 * @param modelConfig the model configuration (may be null).
 * @return the resolved context window size in tokens.
 */
fun resolveContextWindow(modelConfig: ModelConfig?): Int {
    // 1. Explicit override takes highest priority.
    modelConfig?.contextWindowSize?.let { return it }
    // 2. Look up from the built-in model registry.
    modelConfig?.name?.let { name ->
        ModelRegistry.getContextWindow(name)?.let { return it }
    }
    // 3. Fallback default.
    return 128_000
}
