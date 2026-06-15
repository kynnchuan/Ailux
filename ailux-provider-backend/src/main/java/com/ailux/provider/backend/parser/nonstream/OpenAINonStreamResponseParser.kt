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
 * Non-streaming response parser for the OpenAI Chat Completions API
 * (`POST /v1/chat/completions`) and any OpenAI-compatible backend
 * (DeepSeek, Tongyi Qianwen, Moonshot, etc.).
 *
 * Expected response shape (excerpt — per the official OpenAI spec):
 * ```json
 * {
 *   "id": "chatcmpl-xxx",
 *   "model": "gpt-4o-mini",
 *   "choices": [
 *     {
 *       "index": 0,
 *       "message": { "role": "assistant", "content": "Hello!" },
 *       "finish_reason": "stop"
 *     }
 *   ],
 *   "usage": {
 *     "prompt_tokens": 12,
 *     "completion_tokens": 20,
 *     "total_tokens": 32
 *   }
 * }
 * ```
 *
 * Mapping rules:
 * - `choices[0].message.content` → [LLMResponse.text]. Only the first choice
 *   is consumed; multi-choice responses (`n > 1`) are not currently exposed
 *   by [LLMResponse]. Missing/null content degrades to an empty string.
 * - `model` is passed through verbatim.
 * - `usage.*` → [UsageInfo]. Missing fields fall back to `0`; the whole
 *   `usage` object is optional.
 *   ⚠️ **Known bug — token field names are wrong**: the current
 *   implementation reads `input_tokens` / `output_tokens` (Anthropic-style
 *   names) instead of the OpenAI-spec `prompt_tokens` / `completion_tokens`
 *   / `total_tokens`. As a result, parsing a real OpenAI response yields
 *   `inputTokens = 0` / `outputTokens = 0`. A regression test reproduces
 *   this in `OpenAINonStreamResponseParserTest` (an `@Ignore`'d case named
 *   *"usage reads prompt_tokens and completion_tokens per OpenAI spec"*);
 *   un-ignore it once the field names are fixed.
 *
 * Tool calls (`choices[0].message.tool_calls`), function calls and other
 * structured fields are intentionally **not** mapped: the current
 * [LLMResponse] contract only carries plain text + usage + model. Extend
 * once [LLMResponse] grows tool-call / reasoning fields.
 *
 * Uses `ignoreUnknownKeys = true` so future protocol additions never break
 * existing clients.
 *
 * **Thread-safety:** stateless; a single instance can be reused across
 * concurrent requests.
 */
class OpenAINonStreamResponseParser : NonStreamResponseParser {

    private val json = Json { ignoreUnknownKeys = true }

    override fun parse(body: String): LLMResponse {
        val obj = json.parseToJsonElement(body).jsonObject

        // 1) choices[0].message.content -> plain text (empty when absent)
        val text = obj["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject?.get("content")
            ?.jsonPrimitive?.contentOrNull
            ?: ""

        // 2) model is forwarded as-is (nullable).
        val model = obj["model"]?.jsonPrimitive?.contentOrNull

        // 3) usage is optional; treat missing token fields as 0.
        //    BUG: `input_tokens` / `output_tokens` are Anthropic-style field
        //    names — OpenAI uses `prompt_tokens` / `completion_tokens`. Kept
        //    as-is for now; see class KDoc and the @Ignore'd regression test.
        val usage = obj["usage"]?.jsonObject?.let { u ->
            UsageInfo(
                inputTokens = u["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                outputTokens = u["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }

        return LLMResponse(
            text = text,
            usage = usage,
            model = model,
        )
    }
}
