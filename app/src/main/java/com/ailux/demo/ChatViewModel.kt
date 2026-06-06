package com.ailux.demo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ailux.android.AiluxViewModel
import com.ailux.api.AiluxClient
import com.ailux.core.model.LLMEvent
import com.ailux.core.model.LLMRequest
import com.ailux.core.model.UsageInfo
import com.ailux.demo.model.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
            val request = LLMRequest(prompt = prompt, model = "deepseek-v4-flash")
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

                    LLMEvent.Done -> {
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
        }
    }

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
