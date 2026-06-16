package com.ailux.provider.backend.config

import com.ailux.provider.backend.mapper.ErrorMapper
import com.ailux.provider.backend.mapper.RequestMapper
import com.ailux.provider.backend.parser.nonstream.NonStreamResponseParser
import com.ailux.provider.backend.parser.stream.StreamResponseParser

/**
 * Protocol-adaptation configuration for [BackendProxyProvider].
 *
 * Controls how the provider maps SDK [LLMRequest]s to HTTP request bodies and
 * how it parses HTTP responses back into SDK types. Separate from
 * [BackendProxyConfig] (endpoint/auth) and [HttpClientConfig] (transport).
 *
 * Defaults produce the OpenAI Chat Completions protocol family, which works
 * out of the box with OpenAI, DeepSeek, Moonshot, Tongyi Qianwen, and other
 * OpenAI-compatible backends. Customize only when connecting to Anthropic,
 * a private on-premise format, or any protocol that diverges from OpenAI.
 *
 * ```kotlin
 * // Anthropic Messages API
 * val provider = BackendProxyProvider(
 *     config = BackendProxyConfig(...),
 *     protocolConfig = ProtocolConfig(
 *         requestMapper = AnthropicRequestMapper(),
 *         streamResponseParser = AnthropicStreamResponseParser(),
 *         nonStreamResponseParser = AnthropicNonStreamResponseParser(),
 *     ),
 * )
 * ```
 *
 * @property requestMapper         Request body mapper. `null` → [OpenAIRequestMapper].
 * @property streamResponseParser  SSE event parser. `null` → [OpenAIStreamResponseParser].
 * @property nonStreamResponseParser
 *                                 Non-streaming response parser. `null` → [OpenAINonStreamResponseParser].
 * @property errorMapper           Error mapper. `null` → [DefaultErrorMapper].
 * @property includeUsageInStream  Whether the default [OpenAIRequestMapper] should include
 *                                 `stream_options.include_usage=true` in streaming requests.
 *                                 Ignored when a custom [requestMapper] is supplied.
 */
data class ProtocolConfig(
    val requestMapper: RequestMapper? = null,
    val streamResponseParser: StreamResponseParser? = null,
    val nonStreamResponseParser: NonStreamResponseParser? = null,
    val errorMapper: ErrorMapper? = null,
    val includeUsageInStream: Boolean = true,
)
