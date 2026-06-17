package com.ailux.core.tool

import kotlinx.serialization.Serializable

/**
 * A tool call requested by the model during generation.
 *
 * When the model decides to invoke a tool/function, the SDK emits a
 * [LLMEvent.ToolCallReceived] containing one or more [ToolCall] instances.
 * The caller should execute the function and return the result via
 * [Message.Tool] in the next request.
 *
 * @property id        Unique identifier for this tool call (e.g. `"call_abc123"`).
 *                     Used to correlate the result in [Message.Tool.toolCallId].
 * @property name      Name of the function to invoke (matches [ToolDefinition.name]).
 * @property arguments JSON string of the function arguments. May be `null` for
 *                     tools that take no parameters. Parse with `Json.parseToJsonElement()`.
 */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String?,
)