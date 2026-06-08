package com.ailux.core.context

import com.ailux.core.message.Message

/**
 * Result of a context-trimming operation.
 *
 * Returned by [LLMContextManager.process] to provide the caller
 * with full visibility into what was trimmed and how much budget was reclaimed.
 *
 * @property messages            the trimmed message list (ready to send to the provider).
 * @property removed             messages that were removed during trimming.
 * @property estimatedTokensSaved estimated number of tokens saved by the trim.
 * @property warning             optional human-readable warning/info string (e.g., trim summary).
 */
data class ContextResult(
    val messages: List<Message>,
    val removed: List<Message>,
    val estimatedTokensSaved: Int,
    val warning: String? = null
)
