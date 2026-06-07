package com.ailux.demo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ailux.android.AiluxViewModel
import com.ailux.api.AiluxClient
import com.ailux.core.event.LLMEvent
import com.ailux.core.message.Message
import com.ailux.core.request.LLMRequest
import com.ailux.core.event.FinishReason
import com.ailux.core.response.UsageInfo
import com.ailux.core.tool.ToolCall
import com.ailux.core.tool.ToolDefinition
import com.ailux.demo.model.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.launch

/**
 * Chat ViewModel: manages the message list and the streaming generation logic.
 *
 * Inherits [AiluxViewModel] to get automatic lifecycle management
 * (the underlying client is released in onCleared).
 */
class ChatViewModel(client: AiluxClient) : AiluxViewModel(client) {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())

    /** Chat message list, observed by the UI layer via collectAsState. */
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /**
     * Send a user message and trigger streaming generation.
     *
     * @param prompt The text the user typed.
     */
    /** Demo tool definitions — a simple weather query example. */
    private val demoTools: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "get_weather",
            description = "Get the current weather for a given city.",
            arguments = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("city") {
                        put("type", "string")
                        put("description", "The city name, e.g. 'Beijing'")
                    }
                    putJsonObject("unit") {
                        put("type", "string")
                        put("description", "Temperature unit: 'celsius' or 'fahrenheit'")
                    }
                }
                put("required", buildJsonArray { add(JsonPrimitive("city")) })
            },
        )
    )

    fun send(prompt: String) {
        if (prompt.isBlank()) return

        // Append the user message
        val userMessage = ChatMessage(role = "user", content = prompt)
        _messages.update { it + userMessage }

        // Append a placeholder assistant message for streaming updates
        val assistantMessage = ChatMessage(
            role = "assistant",
            content = "",
            isStreaming = true,
        )
        _messages.update { it + assistantMessage }

        // Kick off streaming generation
        viewModelScope.launch {
            val msgs = mutableListOf<Message>(
                Message.User(prompt)
            )

            var finishReason: FinishReason
            do {
                finishReason = FinishReason.COMPLETE
                var pendingToolCalls: List<ToolCall>? = null

                val request = LLMRequest(
                    messages = msgs,
                    tools = demoTools,
                    model = "deepseek-v4-flash",
                )
                val assistantId = assistantMessage.id

                streamGenerate(request).collect { event ->
                    when (event) {
                        is LLMEvent.Token -> {
                            // Append content to the last assistant message
                            _messages.update { messages ->
                                messages.map { msg ->
                                    if (msg.id == assistantId) {
                                        msg.copy(
                                            content = msg.content + event.text,
                                            isReasoning = false,
                                        )
                                    } else {
                                        msg
                                    }
                                }
                            }
                        }

                        is LLMEvent.Reasoning -> {
                            // Append reasoning text
                            _messages.update { messages ->
                                messages.map { msg ->
                                    if (msg.id == assistantId) {
                                        msg.copy(
                                            reasoningContent = msg.reasoningContent + event.text,
                                            isReasoning = true,
                                        )
                                    } else {
                                        msg
                                    }
                                }
                            }
                        }

                        is LLMEvent.Error -> {
                            // Mark the message as errored
                            _messages.update { messages ->
                                messages.map { msg ->
                                    if (msg.id == assistantId) {
                                        msg.copy(
                                            content = msg.content + "\n\n⚠️ ${event.error.message}",
                                            isStreaming = false,
                                            isReasoning = false,
                                        )
                                    } else {
                                        msg
                                    }
                                }
                            }
                        }
                        is LLMEvent.Usage -> {
                            _messages.update { messages ->
                                messages.map { msg ->
                                    if (msg.id == assistantId) {
                                        msg.copy(usageLabel = event.info.toDisplayLabel())
                                    } else {
                                        msg
                                    }
                                }
                            }
                        }

                        is LLMEvent.ToolCallDelta -> {
                            // Function calling: tool call delta — no UI update needed
                            // (Parser aggregates internally)
                        }
                        is LLMEvent.ToolCallReceived -> {
                            pendingToolCalls = event.toolCalls
                        }

                        is LLMEvent.Done -> {
                            finishReason = event.finishReason
                            // Mark the streaming as finished
                            _messages.update { messages ->
                                messages.map { msg ->
                                    if (msg.id == assistantId) {
                                        msg.copy(isStreaming = false, isReasoning = false)
                                    } else {
                                        msg
                                    }
                                }
                            }
                        }
                    }
                }

                // FC loop: if the model requested tool calls, execute them and continue
                if (finishReason == FinishReason.TOOL_CALL && pendingToolCalls != null) {
                    // Append assistant message with tool_calls to conversation history
                    msgs.add(Message.Assistant(toolCalls = pendingToolCalls))

                    // Execute each tool call and append results
                    for (call in pendingToolCalls!!) {
                        val result = executeToolCall(call)
                        msgs.add(Message.Tool(toolCallId = call.id, content = result))
                    }

                    // Update UI to show tool execution status
                    _messages.update { messages ->
                        messages.map { msg ->
                            if (msg.id == assistantMessage.id) {
                                msg.copy(
                                    content = msg.content + "\n\n🔧 Calling tools...",
                                    isStreaming = true,
                                )
                            } else {
                                msg
                            }
                        }
                    }
                }

            } while (finishReason == FinishReason.TOOL_CALL)
        }
    }

    /**
     * Executes a tool call and returns the result as a JSON string.
     *
     * In a real app, this would dispatch to actual implementations (API calls,
     * database queries, device sensors, etc.). This demo uses mock data.
     *
     * @param call The tool call to execute, containing name and arguments JSON.
     * @return The tool execution result as a string (typically JSON).
     */
    private fun executeToolCall(call: ToolCall): String {
        return when (call.name) {
            "get_weather" -> {
                // Parse arguments from the model's function call
                val args = try {
                    call.arguments?.let { jsonParser.parseToJsonElement(it).jsonObject }
                } catch (_: Exception) { null }

                val city = args?.get("city")?.jsonPrimitive?.content ?: "Unknown"
                val unit = args?.get("unit")?.jsonPrimitive?.content ?: "celsius"

                // Mock weather data (in real app: call a weather API)
                val temp = if (unit == "fahrenheit") "72°F" else "22°C"
                buildJsonObject {
                    put("city", city)
                    put("temperature", temp)
                    put("condition", "sunny")
                    put("humidity", "45%")
                }.toString()
            }
            else -> {
                // Unknown tool — return an error message so the model can adapt
                buildJsonObject {
                    put("error", "Unknown tool: ${call.name}")
                }.toString()
            }
        }
    }

    private val jsonParser = Json { ignoreUnknownKeys = true }

    private fun UsageInfo.toDisplayLabel(): String {
        val source = if (estimated) "local estimate" else "server reported"
        return "Tokens · in $inputTokens / out $outputTokens · $source"
    }

    /**
     * ViewModel factory: injects the AiluxClient.
     */
    class Factory(private val client: AiluxClient) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(client) as T
        }
    }
}
