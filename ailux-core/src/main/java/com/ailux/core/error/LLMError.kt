package com.ailux.core.error

/**
 * Structured error produced by the SDK or a provider.
 *
 * Used by both [LLMEvent.Error] (stream events) and
 * [LLMTaskState.Failed] (task-level state).
 *
 * @property code    Machine-readable [ErrorCode].
 * @property message Human-readable description (may come from the backend).
 * @property cause   Original exception, if any. Excluded from serialization.
 */
data class LLMError(
    val code: ErrorCode,
    val message: String,
    val cause: Throwable? = null,
) {
    /** Convenience flag: whether this error is safe to retry automatically. */
    val isRetriable: Boolean get() = code.retriable
}
