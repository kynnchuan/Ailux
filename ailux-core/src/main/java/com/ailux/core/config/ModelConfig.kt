package com.ailux.core.config

/**
 * Configuration describing the target LLM model's capabilities.
 *
 * Used by the context manager to automatically compute the token budget without
 * requiring callers to hard-code magic numbers.
 *
 * @property name             model identifier (e.g., "gpt-4o", "claude-opus-4").
 *                            Used to look up the context window from [com.ailux.api.context.ModelRegistry].
 * @property contextWindowSize explicit context window override (in tokens). When set,
 *                            takes precedence over the built-in model registry.
 *                            `null` means auto-resolve from the registry.
 * @property reserveForReply  number of tokens reserved for the model's reply output.
 *                            The effective input budget = contextWindowSize - reserveForReply.
 *                            Defaults to 4096.
 */
data class ModelConfig(
    val name: String,
    val contextWindowSize: Int? = null,
    val reserveForReply: Int = 4096
)
