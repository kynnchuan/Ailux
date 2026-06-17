package com.ailux.provider.backend.mapper

import com.ailux.core.request.LLMRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder

/**
 * Request body mapper: converts the SDK's [LLMRequest] into the JSON string
 * the backend expects.
 *
 * Different enterprise backends differ in field naming, structure, and
 * required fields. Implement this interface to fit your backend protocol.
 *
 * Passing `null` to [BackendProxyConfig.requestMapper] makes the SDK fall
 * back to [OpenAIRequestMapper] (which follows the Ailux recommended
 * protocol).
 *
 * ```kotlin
 * // Custom mapping example
 * val mapper = RequestMapper { request ->
 *     buildJsonObject {
 *         put("query", request.prompt)
 *         put("model_name", request.model.ifEmpty { "gpt-4" })
 *         put("max_length", request.maxTokens ?: 2048)
 *     }.toString()
 * }
 * ```
 *
 * @see OpenAIRequestMapper
 */
fun interface RequestMapper {

    /**
     * Maps an [LLMRequest] to a JSON string.
     *
     * The returned string is sent as the body of the HTTP POST request.
     * The implementation must return valid JSON.
     *
     * @param request The SDK-side inference request.
     * @param stream  Whether this is a streaming request. Implementations may use this to decide whether to include `"stream": true` in the JSON.
     * @return The request body as a JSON-formatted string.
     */
    fun map(request: LLMRequest, stream: Boolean): String
}

/**
 * Merges [LLMRequest.overrides] into the request body being built.
 *
 * This extension is the shared convention for the "Tier 2 escape hatch": all built-in
 * mappers call it as the **last step** of JSON construction so that override keys are
 * merged at the top level of the request body. Same-name keys intentionally overwrite
 * any previously written field (including strong-typed ones like `temperature`), giving
 * callers an ultimate escape path.
 *
 * @receiver The [JsonObjectBuilder] representing the in-progress request body.
 * @param override The [JsonObject] from [LLMRequest.overrides].
 */
fun JsonObjectBuilder.applyOverrides(override: JsonObject) {
    override.forEach { (key, value) -> put(key, value) }
}
