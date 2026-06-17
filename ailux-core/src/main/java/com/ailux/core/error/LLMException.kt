package com.ailux.core.error

/**
 * SDK-level exception carrying a structured [LLMError].
 *
 * Thrown by providers and the API layer to signal LLM-related failures.
 * Callers can inspect [error] for the machine-readable [ErrorCode] and
 * human-readable message.
 *
 * @property error the structured error details.
 */
class LLMException(
    val error: LLMError,
) : Exception(error.message, error.cause)
