package com.ailux.core.tool

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Definition of a tool (function) that the model can call.
 *
 * Each tool definition tells the model what the tool does and what parameters
 * it accepts, using JSON Schema to describe the parameters.
 *
 * ## Example
 *
 * ```kotlin
 * val weatherTool = ToolDefinition(
 *     name = "get_weather",
 *     description = "Get the current weather for a given city.",
 *     arguments = buildJsonObject {
 *         put("type", "object")
 *         putJsonObject("properties") {
 *             putJsonObject("city") {
 *                 put("type", "string")
 *                 put("description", "City name, e.g. 'Beijing'")
 *             }
 *         }
 *         put("required", buildJsonArray { add(JsonPrimitive("city")) })
 *     }
 * )
 * ```
 *
 * @property name        Unique function name (must match `[a-zA-Z0-9_-]+`).
 * @property description Human-readable description of what the tool does.
 *                       The model uses this to decide when to call the tool.
 * @property arguments   JSON Schema object describing the function's parameters.
 *                       Uses the OpenAI-compatible format (type, properties, required).
 */
@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val arguments: JsonObject,
)