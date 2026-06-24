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
import com.ailux.core.session.Session
import com.ailux.core.session.SessionConfig
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
 * ## v0.3.0b: Session-first conversation
 *
 * Since v0.3.0b (ADR-0009) the SDK no longer exposes a stateless
 * `client.streamGenerate(req)`. This demo opens a single
 * [com.ailux.core.session.Session] on first send and reuses it for every
 * subsequent turn — local engines (LiteRT-LM) benefit from native KV-cache
 * reuse, while cloud / proxy providers transparently fall back to a
 * client-side history accumulator. The application code is the same in
 * either case.
 *
 * Demonstrates the Level 2 `handle {}` DSL for event consumption — much
 * cleaner than the raw `events.collect { when(event) { ... } }` approach.
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
     * Long-lived Session that holds the conversation state across turns.
     *
     * Opened lazily on the first [send]. Each turn pushes only the
     * **new** messages (user prompt, then any tool results) into the
     * session — Session is responsible for maintaining the running history,
     * either as a native KV-cache (local engines) or as a client-side
     * accumulator (cloud / proxy providers).
     *
     * The SDK's [com.ailux.core.context.LLMContextManager] automatically
     * trims the running history when it exceeds the token budget — the
     * demo doesn't need to manage this manually.
     */
    private var session: Session? = null

    /** System instruction sent on every newly-opened session. */
    private val systemInstruction = "You are a helpful AI assistant. Answer concisely."

    /**
     * Open the long-lived [Session] on demand.
     *
     * The session is opened with the configured system instruction and
     * `MessageConcurrencyPolicy.ENQUEUE` (the default), so concurrent send
     * calls are serialised within this conversation.
     */
    private fun ensureSession(): Session {
        return session ?: ailuxClient.openSession(
            SessionConfig(systemInstruction = systemInstruction),
        ).also { session = it }
    }

    /** Reset the conversation: close the current session and clear UI history. */
    fun newConversation() {
        session?.close()
        session = null
        _messages.value = emptyList()
        latestTask = null
    }

    override fun onCleared() {
        session?.close()
        super.onCleared()
    }

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

        // Append user message to the UI; the Session keeps its own
        // authoritative copy and is responsible for replay / KV-cache reuse.
        val userMessage = ChatMessage(role = "user", content = prompt)
        _messages.update { it + userMessage }

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
            val activeSession = ensureSession()

            if (debug.useStreaming) {
                sendStreaming(activeSession, assistantId, debug, prompt)
            } else {
                sendNonStreaming(activeSession, assistantId, debug, prompt)
            }

            logger.d("Ailux", "Session ${activeSession.sessionId} turn complete")
        }
    }

    // ──────────────────────────────────────────
    // Streaming path (existing, refactored out)
    // ──────────────────────────────────────────

    private suspend fun sendStreaming(
        session: Session,
        assistantId: String,
        debug: DebugConfig,
        userPrompt: String,
    ) {
        // First turn carries the user prompt; subsequent FC iterations carry
        // only the tool replies (the assistant turn with toolCalls is appended
        // by the Session automatically from its own stream observation).
        var turnMessages: List<Message> = listOf(Message.User(userPrompt))

        var finishReason: FinishReason
        do {
            finishReason = FinishReason.COMPLETE
            var pendingToolCalls: List<ToolCall>? = null

            val request = buildRequest(debug, turnMessages)

            val task = session.streamGenerateAsTask(request)
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

            // FC loop: if the model requested tool calls, execute them and continue.
            // The next turn carries only the new tool replies as messages; the
            // Session already remembers the assistant turn that requested the calls.
            if (finishReason == FinishReason.TOOL_CALL && pendingToolCalls != null) {
                val toolReplies = pendingToolCalls!!.map { call ->
                    Message.Tool(toolCallId = call.id, content = executeToolCall(call))
                }
                turnMessages = toolReplies

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
    private suspend fun sendNonStreaming(
        session: Session,
        assistantId: String,
        debug: DebugConfig,
        userPrompt: String,
    ) {
        val request = buildRequest(debug, listOf(Message.User(userPrompt)))

        try {
            val response = session.generate(request)

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

    /**
     * Build the per-turn [LLMRequest].
     *
     * Session uses **increment semantics**: `messages` should carry only the
     * new turn (typically a single [Message.User] for fresh prompts, or a
     * batch of [Message.Tool] replies during a function-calling loop). The
     * Session remembers everything else.
     */
    private fun buildRequest(debug: DebugConfig, turnMessages: List<Message>): LLMRequest {
        val attachments = if (debug.attachTestImage) {
            listOf(
                Attachment(
                    source = AttachmentSource.Url("https://upload.wikimedia.org/wikipedia/commons/thumb/4/47/PNG_transparency_demonstration_1.png/300px-PNG_transparency_demonstration_1.png"),
                    mimeType = "image/png",
                )
            )
        } else emptyList()

        return LLMRequest(
            messages = turnMessages,
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
