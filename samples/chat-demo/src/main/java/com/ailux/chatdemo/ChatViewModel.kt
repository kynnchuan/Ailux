package com.ailux.chatdemo

import android.util.Log
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
import com.ailux.chatdemo.model.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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
 *
 * v0.2.1: Maintains a persistent [conversationHistory] across all turns so that
 * the context manager can observe token growth and trigger trimming when the
 * conversation exceeds the model's context window.
 */
class ChatViewModel(client: AiluxClient) : AiluxViewModel(client) {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())

    /** Chat message list, observed by the UI layer via collectAsState. */
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /**
     * Full conversation history sent to the LLM on each request.
     *
     * This list grows with each user/assistant/tool turn. The SDK's
     * [LLMContextManager] automatically trims it when it exceeds the
     * token budget — the demo doesn't need to manage this manually.
     */
    private val conversationHistory = mutableListOf<Message>(
        Message.System("You are a helpful AI assistant. Answer concisely.")
    )

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

        // Append user message to both UI and conversation history
        val userMessage = ChatMessage(role = "user", content = prompt)
        _messages.update { it + userMessage }
        conversationHistory.add(Message.User(prompt))

        // Append a placeholder assistant message for streaming updates
        val assistantMessage = ChatMessage(
            role = "assistant",
            content = "",
            isStreaming = true,
        )
        _messages.update { it + assistantMessage }

        // Kick off streaming generation
        viewModelScope.launch {
            var finishReason: FinishReason
            do {
                finishReason = FinishReason.COMPLETE
                var pendingToolCalls: List<ToolCall>? = null

                // Pass the FULL conversation history — the SDK's context manager
                // will automatically trim it if it exceeds the token budget.
                val request = LLMRequest(
                    messages = conversationHistory.toList(),
                    tools = demoTools,
                    model = "deepseek-v4-flash",
                )
                val assistantId = assistantMessage.id

                streamGenerate(request).events.collect { event ->
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

                        is LLMEvent.ContextTrimmed -> {
                            if (event.removedCount > 0) {
                                // Context was trimmed: some messages were removed to fit the token budget.
                                Log.d("Ailux", "Context trimmed: removed ${event.removedCount} messages, saved ~${event.estimatedTokensSaved} tokens")
                                _messages.update { messages ->
                                    messages.map { msg ->
                                        if (msg.id == assistantId) {
                                            msg.copy(
                                                content = msg.content + "\n\n💡 Context trimmed: ${event.removedCount} older messages removed to stay within token budget."
                                            )
                                        } else {
                                            msg
                                        }
                                    }
                                }
                            } else {
                                // Pre-check warning: context manager is disabled but estimated tokens exceed the model window.
                                Log.w("Ailux", "Warning: estimated tokens exceed context window, but context manager is disabled.")
                            }
                        }

                        is LLMEvent.Connected -> {
                            // SSE connection established; waiting for first token.
                        }

                        is LLMEvent.StallDetected -> {
                            // Stream stall detected — optionally show UI indicator.
                            Log.w("Ailux", "Stall detected: phase=${event.phase}, idle=${event.idleMillis}ms")
                        }
                    }
                }

                // FC loop: if the model requested tool calls, execute them and continue
                if (finishReason == FinishReason.TOOL_CALL && pendingToolCalls != null) {
                    // Append assistant message with tool_calls to conversation history
                    conversationHistory.add(Message.Assistant(toolCalls = pendingToolCalls))

                    // Execute each tool call and append results
                    for (call in pendingToolCalls!!) {
                        val result = executeToolCall(call)
                        conversationHistory.add(Message.Tool(toolCallId = call.id, content = result))
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

            // After the full generation completes (including FC loops), record the
            // assistant's final reply in conversation history for subsequent turns.
            val finalContent = _messages.value
                .find { it.id == assistantMessage.id }
                ?.content
                ?.replace("\n\n🔧 Calling tools...", "")
                ?.replace(Regex("\n\n💡 Context trimmed:.*"), "")
                ?.trim()

            if (!finalContent.isNullOrBlank()) {
                conversationHistory.add(Message.Assistant(content = finalContent))
            }

            Log.d("Ailux", "Conversation history size: ${conversationHistory.size} messages")
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
