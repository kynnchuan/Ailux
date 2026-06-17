package com.ailux.provider.backend.parser.nonstream

import com.ailux.core.response.LLMResponse
import com.ailux.core.response.UsageInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Non-streaming response parser for the Anthropic Messages API
 * (`POST /v1/messages`).
 *
 * Aligned with the latest Anthropic Messages API schema, mirroring the
 * `Message` / `Usage` / `ContentBlock` models from `anthropic-sdk-python`.
 *
 * Expected response shape (excerpt):
 * ```json
 * {
 *   "id": "msg_01ABC...",
 *   "type": "message",
 *   "role": "assistant",
 *   "model": "claude-sonnet-4-5-20250929",
 *   "content": [
 *     { "type": "thinking", "thinking": "...", "signature": "..." },
 *     { "type": "text", "text": "Hello, world!" }
 *   ],
 *   "stop_reason": "end_turn",
 *   "stop_sequence": null,
 *   "usage": {
 *     "input_tokens": 25,
 *     "output_tokens": 13,
 *     "cache_creation_input_tokens": 0,
 *     "cache_read_input_tokens": 0,
 *     "service_tier": "standard"
 *   }
 * }
 * ```
 *
 * Mapping rules:
 * - `content[]` is iterated in order; only blocks with `type == "text"`
 *   are concatenated into [LLMResponse.text]. All other block types
 *   (`tool_use`, `thinking`, `redacted_thinking`, `server_tool_use`,
 *   `web_search_tool_result`, `web_fetch_tool_result`,
 *   `code_execution_tool_result`, `bash_code_execution_tool_result`,
 *   `text_editor_code_execution_tool_result`, `tool_search_tool_result`,
 *   `container_upload`) are intentionally **ignored** — the current
 *   [LLMResponse] contract does not yet carry tool-call / reasoning
 *   payloads. Extend once [LLMResponse] grows the corresponding fields.
 * - `usage.input_tokens` / `usage.output_tokens` → [UsageInfo]. Both are
 *   mandatory in the Anthropic schema; missing values fall back to `0`
 *   defensively. `cache_creation_input_tokens`, `cache_read_input_tokens`
 *   and `service_tier` are not mapped yet (waiting on [UsageInfo] to grow
 *   those fields).
 * - `model` is passed through verbatim.
 *
 * Error responses (HTTP 4xx/5xx) are expected to be handled by the caller
 * based on status codes; this parser only deals with business-success
 * payloads.
 *
 * Uses `ignoreUnknownKeys = true` so future block types or usage fields
 * (e.g. nested `cache_creation` objects) never break existing clients.
 *
 * **Thread-safety:** stateless; a single instance can be reused across
 * concurrent requests.
 */
class AnthropicNonStreamResponseParser : NonStreamResponseParser {

    private val json = Json { ignoreUnknownKeys = true }

    override fun parse(body: String): LLMResponse {
        val root = json.parseToJsonElement(body).jsonObject

        // 1) content[] -> concatenate text from blocks with type == "text" only.
        val text = root["content"]
            ?.jsonArray
            .orEmpty()
            .mapNotNull { element ->
                val block = element.jsonObject
                val type = block["type"]?.jsonPrimitive?.contentOrNull
                if (type == "text") {
                    block["text"]?.jsonPrimitive?.contentOrNull
                } else {
                    null
                }
            }
            .joinToString(separator = "")

        // 2) usage: input_tokens / output_tokens are mandatory in the Anthropic schema;
        //    fall back to 0 defensively if a buggy/partial payload omits them.
        val usage = root["usage"]?.jsonObject?.let { u ->
            UsageInfo(
                inputTokens = u["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                outputTokens = u["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                estimated = false,
            )
        }

        // 3) model is forwarded as-is.
        val model = root["model"]?.jsonPrimitive?.contentOrNull

        return LLMResponse(
            text = text,
            usage = usage,
            model = model,
        )
    }
}
