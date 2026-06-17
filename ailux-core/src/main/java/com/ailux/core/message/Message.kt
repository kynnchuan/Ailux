package com.ailux.core.message

import com.ailux.core.tool.ToolCall
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
sealed class Message {

    data class System(val content: String) : Message()

    data class User(val content: String) : Message()

    data class Assistant(
        val content: String? = null,
        val toolCalls: List<ToolCall>? = null,
        val reasoningItems: List<JsonElement>? = null
    ) : Message()

    data class Tool(
        val toolCallId: String,
        val content: String,
        ) : Message()
    
}