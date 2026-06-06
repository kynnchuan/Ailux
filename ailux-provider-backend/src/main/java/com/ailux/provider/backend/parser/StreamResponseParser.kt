package com.ailux.provider.backend.parser

import com.ailux.core.model.LLMEvent

/**
 * SSE event parser: converts a single SSE event into the SDK's [LLMEvent].
 *
 * The backend pushes streaming data via Server-Sent Events. Each event has an
 * `event` type and a `data` payload. Implement this interface to fit your
 * backend's event format.
 *
 * Passing `null` to [BackendProxyConfig.streamResponseParser] makes the SDK
 * fall back to [OpenAIStreamResponseParser] (compatible with OpenAI,
 * DeepSeek, Tongyi Qianwen, and other `chat.completion.chunk`-style formats).
 *
 * The SDK ships two built-in parsers:
 * - [OpenAIStreamResponseParser]: OpenAI-compatible format (default).
 * - [AnthropicStreamResponseParser]: Anthropic Claude native format.
 *
 * ```kotlin
 * // Custom parser example
 * val parser = StreamResponseParser { eventType, data ->
 *     when (eventType) {
 *         "delta"    -> LLMEvent.Token(parseCompanyDelta(data))
 *         "finish"   -> LLMEvent.Done
 *         "metrics"  -> LLMEvent.Usage(parseCompanyUsage(data))
 *         else       -> null  // ignore unknown events
 *     }
 * }
 * ```
 *
 * @see OpenAIStreamResponseParser
 * @see AnthropicStreamResponseParser
 */
fun interface StreamResponseParser {

    /**
     * Parses a single SSE event.
     *
     * @param eventType The value of the SSE `event:` field (e.g. `"token"`, `"usage"`, `"error"`, `"done"`).
     *                  When the SSE stream has no explicit `event:` field, OkHttp passes `"message"`.
     * @param data      The value of the SSE `data:` field (usually a JSON string).
     * @return The parsed [LLMEvent], or `null` to ignore the event (do not emit).
     */
    fun parse(eventType: String, data: String): LLMEvent?
}
