package com.ailux.chatdemo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ailux.android.AiluxViewModel
import com.ailux.android.logging.AndroidAiluxLogger
import com.ailux.api.AiluxClient
import com.ailux.api.stream.handle
import com.ailux.core.logging.AiluxLogger
import com.ailux.core.message.Message
import com.ailux.core.request.Attachment
import com.ailux.core.request.AttachmentSource
import com.ailux.core.request.LLMRequest
import com.ailux.core.event.FinishReason
import com.ailux.core.response.UsageInfo
import com.ailux.core.task.LLMTask
import com.ailux.core.tool.ToolCall
import com.ailux.core.tool.ToolDefinition
import com.ailux.chatdemo.debug.DebugConfig
import com.ailux.chatdemo.model.ChatMessage
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
 *
 * Demonstrates the Level 2 `handle {}` DSL for event consumption — much cleaner
 * than the raw `events.collect { when(event) { ... } }` approach.
 */
class ChatViewModel(private val ailuxClient: AiluxClient) : AiluxViewModel(ailuxClient) {

    /**
     * Demo logger — routes diagnostics through the Ailux logging SPI instead of
     * touching `android.util.Log` directly. Showing the SDK-recommended path
     * doubles as documentation: any host app can swap [AndroidAiluxLogger] for
     * [com.ailux.core.logging.NoopAiluxLogger] (silent) or a Timber/Sentry
     * bridge with a one-line change.
     */
    private val logger: AiluxLogger = AndroidAiluxLogger()

    /**
     * Most-recent task created by [send]. Held so the Debug Panel can read
     * [LLMTask.lastDiagnostic] from outside the streaming coroutine.
     *
     * Only the latest task is retained — older tasks fall out of scope and are
     * eligible for GC, but their snapshots can still be retrieved via
     * [AiluxClient.createDiagnosticReport] which queries the SDK's internal
     * ring buffer.
     */
    private var latestTask: LLMTask? = null

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())

    /** Chat message list, observed by the UI layer via collectAsState. */
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /**
     * Debug configuration — read on every send() to apply runtime overrides.
     * Modified by the Debug Panel UI without requiring a client rebuild.
     */
    private val _debugConfig = MutableStateFlow(DebugConfig())
    val debugConfig: StateFlow<DebugConfig> = _debugConfig.asStateFlow()

    /** Update debug config from the Debug Panel. */
    fun updateDebugConfig(config: DebugConfig) {
        _debugConfig.value = config
    }

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

    /**
     * Send a user message and trigger generation (streaming or non-streaming
     * depending on [DebugConfig.useStreaming]).
     *
     * @param prompt The text the user typed.
     */
    fun send(prompt: String) {
        if (prompt.isBlank()) return

        // Append user message to both UI and conversation history
        val userMessage = ChatMessage(role = "user", content = prompt)
        _messages.update { it + userMessage }
        conversationHistory.add(Message.User(prompt))

        // Append a placeholder assistant message for updates
        val assistantMessage = ChatMessage(
            role = "assistant",
            content = "",
            isStreaming = true,
        )
        _messages.update { it + assistantMessage }
        val assistantId = assistantMessage.id

        viewModelScope.launch {
            val debug = _debugConfig.value

            if (debug.useStreaming) {
                sendStreaming(assistantId, debug)
            } else {
                sendNonStreaming(assistantId, debug)
            }

            // Record the assistant's final reply in conversation history
            val finalContent = _messages.value
                .find { it.id == assistantId }
                ?.content
                ?.replace("\n\n🔧 Calling tools...", "")
                ?.replace(Regex("\n\n💡 Context trimmed:.*"), "")
                ?.trim()

            if (!finalContent.isNullOrBlank()) {
                conversationHistory.add(Message.Assistant(content = finalContent))
            }

            logger.d("Ailux", "Conversation history size: ${conversationHistory.size} messages")
        }
    }

    // ──────────────────────────────────────────
    // Streaming path (existing, refactored out)
    // ──────────────────────────────────────────

    private suspend fun sendStreaming(assistantId: String, debug: DebugConfig) {
        var finishReason: FinishReason
        do {
            finishReason = FinishReason.COMPLETE
            var pendingToolCalls: List<ToolCall>? = null

            val request = buildRequest(debug)

            val task = streamGenerate(request)
            latestTask = task
            task.handle {

                onToken { text ->
                    updateMessage(assistantId) {
                        it.copy(content = it.content + text, isReasoning = false)
                    }
                }

                onReasoning { text ->
                    updateMessage(assistantId) {
                        it.copy(reasoningContent = it.reasoningContent + text, isReasoning = true)
                    }
                }

                onError { error ->
                    updateMessage(assistantId) {
                        it.copy(
                            content = it.content + "\n\n⚠️ ${error.message}",
                            isStreaming = false,
                            isReasoning = false,
                        )
                    }
                }

                onUsage { info ->
                    updateMessage(assistantId) {
                        it.copy(usageLabel = info.toDisplayLabel())
                    }
                }

                onToolCallReceived { calls ->
                    pendingToolCalls = calls
                }

                onDone { reason ->
                    finishReason = reason
                    updateMessage(assistantId) {
                        it.copy(isStreaming = false, isReasoning = false)
                    }
                }

                onContextTrimmed { removedCount, estimatedTokensSaved ->
                    if (removedCount > 0) {
                        logger.d("Ailux", "Context trimmed: removed $removedCount messages, saved ~$estimatedTokensSaved tokens")
                        updateMessage(assistantId) {
                            it.copy(content = it.content + "\n\n💡 Context trimmed: $removedCount older messages removed to stay within token budget.")
                        }
                    } else {
                        logger.w("Ailux", "Warning: estimated tokens exceed context window, but context manager is disabled.")
                    }
                }

                onStallDetected { phase, idleMillis ->
                    logger.w("Ailux", "Stall detected: phase=$phase, idle=${idleMillis}ms")
                }
            }

            // FC loop: if the model requested tool calls, execute them and continue
            if (finishReason == FinishReason.TOOL_CALL && pendingToolCalls != null) {
                conversationHistory.add(Message.Assistant(toolCalls = pendingToolCalls))

                for (call in pendingToolCalls!!) {
                    val result = executeToolCall(call)
                    conversationHistory.add(Message.Tool(toolCallId = call.id, content = result))
                }

                updateMessage(assistantId) {
                    it.copy(content = it.content + "\n\n🔧 Calling tools...", isStreaming = true)
                }
            }

        } while (finishReason == FinishReason.TOOL_CALL)
    }

    // ──────────────────────────────────────────
    // Non-streaming path (v0.2.6)
    // ──────────────────────────────────────────

    /**
     * Non-streaming generation: calls [generate] which returns the full response
     * at once. Simpler than streaming — no event handling, no stall detection.
     * Useful for batch/background scenarios or when latency-to-first-token
     * is not critical.
     */
    private suspend fun sendNonStreaming(assistantId: String, debug: DebugConfig) {
        val request = buildRequest(debug)

        try {
            val response = generate(request)

            // Show the full response at once
            updateMessage(assistantId) {
                it.copy(
                    content = response.text,
                    isStreaming = false,
                    usageLabel = response.usage?.toDisplayLabel() ?: "",
                )
            }
        } catch (e: Exception) {
            updateMessage(assistantId) {
                it.copy(
                    content = "⚠️ ${e.message ?: "Unknown error"}",
                    isStreaming = false,
                )
            }
        }
    }

    // ──────────────────────────────────────────
    // Shared request builder
    // ──────────────────────────────────────────

    private fun buildRequest(debug: DebugConfig): LLMRequest {
        val attachments = if (debug.attachTestImage) {
            listOf(
                Attachment(
                    source = AttachmentSource.Url("https://upload.wikimedia.org/wikipedia/commons/thumb/4/47/PNG_transparency_demonstration_1.png/300px-PNG_transparency_demonstration_1.png"),
                    mimeType = "image/png",
                )
            )
        } else emptyList()

        return LLMRequest(
            messages = conversationHistory.toList(),
            tools = demoTools,
            model = debug.model,
            stop = debug.stopSequences,
            attachments = attachments,
            overrides = debug.buildOverrides(),
        )
    }

    // ── Helper: update a specific message by ID ──

    private inline fun updateMessage(id: String, transform: (ChatMessage) -> ChatMessage) {
        _messages.update { messages ->
            messages.map { msg -> if (msg.id == id) transform(msg) else msg }
        }
    }

    // ── Tool execution ──

    /**
     * Executes a tool call and returns the result as a JSON string.
     *
     * In a real app, this would dispatch to actual implementations (API calls,
     * database queries, device sensors, etc.). This demo uses mock data.
     */
    private fun executeToolCall(call: ToolCall): String {
        return when (call.name) {
            "get_weather" -> {
                val args = try {
                    call.arguments?.let { jsonParser.parseToJsonElement(it).jsonObject }
                } catch (_: Exception) { null }

                val city = args?.get("city")?.jsonPrimitive?.content ?: "Unknown"
                val unit = args?.get("unit")?.jsonPrimitive?.content ?: "celsius"
                val temp = if (unit == "fahrenheit") "72°F" else "22°C"

                buildJsonObject {
                    put("city", city)
                    put("temperature", temp)
                    put("condition", "sunny")
                    put("humidity", "45%")
                }.toString()
            }
            else -> {
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

    // ── Diagnostics (B2-2 Demo Debug Panel hooks) ──

    /**
     * Shareable text for the **most recent task** (the request currently being
     * streamed or the one that just finished). Returns `null` until [send] has
     * been called at least once.
     *
     * Calls [LLMTask.lastDiagnostic] which is guaranteed redacted per the
     * active [com.ailux.core.privacy.PrivacyConfig] — never contains prompt
     * text, completion text, headers, request bodies or overrides JSON.
     */
    fun lastTaskDiagnosticText(): String? =
        latestTask?.lastDiagnostic()?.toShareableText()

    /**
     * Shareable text for a **session-level diagnostic snapshot** that bundles
     * the SDK version, active privacy snapshot, and a configurable number of
     * the most-recent finished tasks (newest first).
     *
     * @param includeRecentTasks how many recent tasks to embed (1..16; default 5).
     */
    fun sessionDiagnosticText(includeRecentTasks: Int = 5): String =
        ailuxClient.createDiagnosticReport(includeRecentTasks).toShareableText()

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
