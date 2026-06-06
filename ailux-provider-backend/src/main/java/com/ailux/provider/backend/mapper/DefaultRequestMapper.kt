package com.ailux.provider.backend.mapper

import com.ailux.core.model.LLMRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Default request mapper for the Ailux recommended protocol.
 *
 * Maps [LLMRequest] into the following JSON shape:
 *
 * ```json
 * {
 *   "model": "default",
 *   "messages": [
 *     {"role": "user", "content": "Hello"}
 *   ],
 *   "stream": true,
 *   "temperature": 0.7,
 *   "top_p": 1.0,
 *   "max_tokens": 2048,
 *   "metadata": {
 *     "extra_key": "extra_value"
 *   }
 * }
 * ```
 *
 * - `model` field: when `LLMRequest.model` is empty, it is mapped to `"default"`.
 * - `messages` field: v0.1 only supports a single-turn prompt, sent as `{"role": request.role, "content": prompt}`.
 * - `max_tokens`: omitted when `LLMRequest.maxTokens` is `null`.
 * - `metadata`: when `LLMRequest.extras` is non-empty, it is mapped to a `metadata` object.
 *
 * @see RequestMapper
 */
class DefaultRequestMapper : RequestMapper {

    override fun map(request: LLMRequest, stream: Boolean): String {
        val json = buildJsonObject {
            put("model", request.model.ifEmpty { "default" })

            putJsonArray("messages") {
                addJsonObject {
                    put("role", request.role)
                    put("content", request.prompt)
                }
            }

            put("stream", stream)
            put("temperature", request.temperature)
            put("top_p", request.topP)

            request.maxTokens?.let { put("max_tokens", it) }

            if (request.extras.isNotEmpty()) {
                putJsonObject("metadata") {
                    request.extras.forEach { (key, value) -> put(key, value) }
                }
            }
        }

        return json.toString()
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.addJsonObject(
        block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ) {
        add(buildJsonObject(block))
    }
}
