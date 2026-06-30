package com.ailux.runtime.llamacpp

import com.ailux.core.config.LocalRuntimeConfig
import com.ailux.core.config.ModelSource
import com.ailux.core.message.Message
import com.ailux.core.request.LLMRequest
import com.ailux.core.tool.ToolCall
import com.ailux.core.tool.ToolDefinition
import com.ailux.runtime.EngineCapabilities
import com.ailux.runtime.EngineEvent
import com.ailux.runtime.EngineStopReason
import com.ailux.runtime.GpuBackend
import com.ailux.runtime.InferenceEngine
import com.ailux.runtime.EngineSession
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [InferenceEngine] implementation backed by **llama.cpp** (GGUF models),
 * compiled by this module into `libailux_llama.so` (arm64-v8a).
 *
 * ## Role in v0.3.1 — the "second engine" that calibrates the abstraction
 *
 * v0.3.0 shaped `InferenceEngine` against a single engine (LiteRT-LM).
 * llama.cpp has a deliberately *different* shape, and this binding spends that
 * difference as real signal rather than papering over it (spec §1.2 / §二):
 *
 * - **Native stop reasons.** llama.cpp tells us *why* it stopped
 *   (`stopped_eos` / `stopped_limit` / `stopped_word`); we forward those as
 *   [EngineEvent.Stop] with the matching [EngineStopReason] — no token-counting
 *   workaround (contrast LiteRT-LM which always emits `Stop(EOS|UNKNOWN)`).
 *
 * - **Interruptible cancel.** The decode loop polls an abort flag between
 *   tokens; cancelling the collector stops native work *mid-token*. We report
 *   `supportsInterruptibleCancellation = true` and never lie to look "the same"
 *   as LiteRT-LM (spec §二 #3 honesty-over-isomorphism).
 *
 * - **Native usage.** `n_p_eval` / `n_eval` come back as [EngineEvent.Usage]
 *   with `estimated = false` once the Provider sees it.
 *
 * - **Vulkan.** When built with `GGML_VULKAN=ON`, available, and requested,
 *   `gpuBackend = VULKAN`; otherwise `NONE` (CPU).
 *
 * ## Execution model
 *
 * Stateless only (`supportsSessions = false`). KV-cache *session* reuse across
 * turns is a later increment; for now every [streamGenerate] replays the full
 * [LLMRequest.messages] (the Provider's `StatelessProviderSession` already wraps
 * this for multi-turn callers). The native side still benefits from llama.cpp's
 * own prefix caching where applicable.
 *
 * ## Parameter ownership (spec §4.3)
 *
 * - **Engine-private** load-time params (`n_gpu_layers`, `n_threads`, Vulkan
 *   toggle) are constructor arguments — ADR-0001 "config belongs to config".
 * - **Engine-agnostic** `contextLength` rides on [LocalRuntimeConfig] and is
 *   translated here to llama.cpp's `n_ctx`.
 * - **Per-request** sampling (`temperature`/`topP`/`topK`/`maxTokens`/`stop`) is
 *   read from each [LLMRequest].
 *
 * @param nGpuLayers number of layers to offload to GPU (Vulkan). `0` = pure CPU.
 * @param nThreads   CPU eval threads. `<= 0` = let llama.cpp choose.
 * @param useVulkan  request the Vulkan backend (honoured only if the `.so` was
 *                   built with `GGML_VULKAN=ON` and a loader is present).
 * @param bridge     JNI seam; defaults to the production [JniLlamaBridge].
 *                   Injected in tests with a pure-JVM fake.
 */
class LlamaCppEngine internal constructor(
    private val nGpuLayers: Int,
    private val nThreads: Int,
    private val useVulkan: Boolean,
    private val bridge: LlamaBridge,
) : InferenceEngine {

    /** Public constructor: production path wired to the real native bridge. */
    @JvmOverloads
    constructor(
        nGpuLayers: Int = 0,
        nThreads: Int = 0,
        useVulkan: Boolean = false,
    ) : this(nGpuLayers, nThreads, useVulkan, JniLlamaBridge)

    @Volatile
    private var handle: Long = 0L

    @Volatile
    private var loadedConfig: LocalRuntimeConfig? = null

    /** Resolved context length actually requested at load (for RAM/cap reporting). */
    @Volatile
    private var resolvedContextLength: Int = DEFAULT_CTX_FALLBACK

    @Volatile
    private var vulkanActive: Boolean = false

    private val sessions = ConcurrentHashMap<String, LlamaCppSession>()

    // ──────────────────────────────────────────
    // InferenceEngine — load / release / capabilities
    // ──────────────────────────────────────────

    override suspend fun load(config: LocalRuntimeConfig) {
        if (handle != 0L && loadedConfig == config) return

        // Tear down any previous handle loaded with a different config.
        if (handle != 0L) {
            val old = handle
            handle = 0L
            loadedConfig = null
            runCatching { bridge.release(old) }
        }

        val source = config.modelSource
        require(source is ModelSource.LocalPath) {
            "LlamaCppEngine only supports ModelSource.LocalPath; got $source"
        }

        // contextLength is engine-agnostic (spec §4.2); translate to n_ctx.
        // null → 0 sentinel lets llama.cpp pick the model's training default.
        val nCtx = config.contextLength ?: 0

        // Production path: make sure libailux_llama.so is linked before the
        // first JNI call. The fake bridge in tests is a no-op here.
        (bridge as? JniLlamaBridge)?.ensureNativeLoaded()

        val newHandle = bridge.loadModel(
            modelPath = source.absolutePath,
            nCtxLen = nCtx,
            nGpuLayers = nGpuLayers,
            nThreads = nThreads,
            useVulkan = useVulkan,
        )
        require(newHandle != 0L) { "llama.cpp returned a null handle for ${source.absolutePath}" }

        handle = newHandle
        loadedConfig = config
        resolvedContextLength = if (nCtx > 0) nCtx else DEFAULT_CTX_FALLBACK
        vulkanActive = useVulkan && runCatching { bridge.isVulkanActive(newHandle) }.getOrDefault(false)
    }

    override fun release() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
        val toClose = handle
        handle = 0L
        loadedConfig = null
        vulkanActive = false
        if (toClose != 0L) runCatching { bridge.release(toClose) }
    }

    override fun capabilities(): EngineCapabilities = EngineCapabilities(
        // Only arm64-v8a is shipped prebuilt (spec §7.1).
        supportAbis = setOf("arm64-v8a"),
        estimatedRamMb = estimateRamMb(),
        gpuBackend = if (vulkanActive) GpuBackend.VULKAN else GpuBackend.NONE,
        supportsTools = true,
        // llama.cpp's decode loop honours an abort flag between tokens.
        supportsInterruptibleCancellation = true,
        // Declaration only — never used to pre-flight/reject (spec §1.5 / Q11).
        supportedModelExtensions = setOf("gguf"),
        // Each Session owns an independent llama_context while sharing the
        // read-only llama_model. Keep this conservative until real-device RAM /
        // thermal profiling proves a higher fan-out is safe.
        maxConcurrentSessions = DEFAULT_MAX_CONCURRENT_SESSIONS,
        supportsBatchedIngest = true,
    )

    override fun sizeInTokens(text: String): Int {
        val h = handle
        // Before load (or after release) we have no tokenizer; fall back to a
        // rough char-based estimate so callers never get a hard failure.
        if (h == 0L) return roughTokenEstimate(text)
        return runCatching { bridge.tokenCount(h, text) }.getOrElse { roughTokenEstimate(text) }
    }

    override val supportsSessions: Boolean get() = true

    override fun createSession(
        systemInstruction: String?,
        initialMessages: List<Message>,
    ): EngineSession {
        val h = handle
        check(h != 0L) {
            "LlamaCppEngine.load(config) must be called before createSession()."
        }
        val contextHandle = bridge.createContext(h)
        check(contextHandle != 0L) { "llama.cpp failed to create a session context." }
        return LlamaCppSession(
            contextHandle = contextHandle,
            bridge = bridge,
            systemInstruction = systemInstruction,
            initialMessages = initialMessages,
            onClose = { sessionId -> sessions.remove(sessionId) },
        ).also { session ->
            sessions[session.sessionId] = session
        }
    }

    override fun streamGenerate(request: LLMRequest, session: EngineSession): Flow<EngineEvent> {
        require(session is LlamaCppSession) {
            "LlamaCppEngine can only stream against a LlamaCppSession; got ${session::class.simpleName}"
        }
        check(!session.closed.get()) { "Session ${session.sessionId} is closed." }
        val fullRequest = request.copy(messages = session.snapshotMessages() + request.messages)
        return streamWithHandle(
            handle = session.contextHandle,
            request = fullRequest,
            onAssistantComplete = { assistantText, toolCalls ->
                session.append(request.messages)
                session.append(listOf(Message.Assistant(
                    content = assistantText.takeIf { toolCalls == null },
                    toolCalls = toolCalls,
                )))
            },
            abortFlag = session.abortFlag,
        )
    }

    // ──────────────────────────────────────────
    // Stateless generation
    // ──────────────────────────────────────────

    override fun streamGenerate(request: LLMRequest): Flow<EngineEvent> {
        val h = handle
        check(h != 0L) {
            "LlamaCppEngine.load(config) must be called before streamGenerate(). " +
                "If routing through LocalRuntimeProvider, the cold-load path runs first."
        }
        return streamWithHandle(handle = h, request = request)
    }

    private fun streamWithHandle(
        handle: Long,
        request: LLMRequest,
        onAssistantComplete: (assistantText: String, toolCalls: List<ToolCall>?) -> Unit = { _, _ -> },
        abortFlag: AtomicBoolean = AtomicBoolean(false),
    ): Flow<EngineEvent> = callbackFlow {
        abortFlag.set(false)
        val prompt = buildPrompt(request)
        val parseTools = request.tools.isNotEmpty()
        val buffered = StringBuilder()

        val sink = object : LlamaBridge.TokenSink {
            override fun onToken(text: String) {
                if (text.isEmpty()) return
                if (parseTools) {
                    buffered.append(text)
                } else {
                    trySend(EngineEvent.Token(text))
                }
            }

            override fun isAborted(): Boolean = abortFlag.get()

            override fun onStop(nativeStopReason: Int, promptTokens: Int, genTokens: Int) {
                val generated = buffered.toString()
                val toolCalls = if (parseTools) parseToolCalls(generated) else null
                if (toolCalls.isNullOrEmpty()) {
                    if (parseTools && generated.isNotEmpty()) trySend(EngineEvent.Token(generated))
                    onAssistantComplete(generated, null)
                    if (promptTokens >= 0 && genTokens >= 0) {
                        trySend(EngineEvent.Usage(promptTokens = promptTokens, genTokens = genTokens))
                    }
                    trySend(EngineEvent.Stop(mapStopReason(nativeStopReason)))
                } else {
                    onAssistantComplete(generated, toolCalls)
                    if (promptTokens >= 0 && genTokens >= 0) {
                        trySend(EngineEvent.Usage(promptTokens = promptTokens, genTokens = genTokens))
                    }
                    trySend(EngineEvent.ToolCallReceived(toolCalls))
                    trySend(EngineEvent.Stop(EngineStopReason.TOOL_CALL))
                }
            }
        }

        try {
            bridge.generate(
                handle = handle,
                prompt = prompt,
                temperature = request.temperature,
                topP = request.topP,
                topK = request.topK ?: 0,
                maxTokens = request.maxTokens ?: 0,
                stopWords = request.stop.toTypedArray(),
                sink = sink,
            )
            close()
        } catch (t: Throwable) {
            close(t)
        }

        awaitClose {
            abortFlag.set(true)
        }
    }.buffer(capacity = Int.MAX_VALUE, onBufferOverflow = BufferOverflow.SUSPEND)

    // ──────────────────────────────────────────
    // Internal helpers (unit-tested directly)
    // ──────────────────────────────────────────

    /**
     * Build a model-family-aware prompt.
     *
     * Prefer the format requested by [LLMRequest.model] when present. Otherwise
     * fall back to ChatML because it is accepted by Qwen/Qwen2/Qwen3 and many
     * modern instruct GGUFs. This keeps the Kotlin path deterministic even when
     * the native binding cannot read a GGUF-embedded chat_template yet.
     */
    internal fun buildPrompt(request: LLMRequest): String {
        val family = ChatFamily.fromModelId(request.model)
        return when (family) {
            ChatFamily.LLAMA3 -> buildLlama3Prompt(request)
            ChatFamily.GEMMA -> buildGemmaPrompt(request)
            ChatFamily.MISTRAL -> buildMistralPrompt(request)
            ChatFamily.CHATML -> buildChatMlPrompt(request)
            ChatFamily.PLAIN -> buildPlainPrompt(request)
        }
    }

    private fun buildChatMlPrompt(request: LLMRequest): String = buildString {
        val system = mergedSystem(request)
        if (system.isNotBlank()) append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
        for (msg in request.messages) {
            when (msg) {
                is Message.System -> Unit
                is Message.User -> append("<|im_start|>user\n").append(msg.content).append("<|im_end|>\n")
                is Message.Assistant -> {
                    append("<|im_start|>assistant\n")
                    msg.toolCalls?.let { append(encodeToolCallsJson(it)) }
                        ?: append(msg.content.orEmpty())
                    append("<|im_end|>\n")
                }
                is Message.Tool -> append("<|im_start|>tool\n").append(msg.content).append("<|im_end|>\n")
            }
        }
        append("<|im_start|>assistant\n")
    }

    private fun buildLlama3Prompt(request: LLMRequest): String = buildString {
        val system = mergedSystem(request)
        if (system.isNotBlank()) {
            append("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n")
            append(system).append("<|eot_id|>")
        } else {
            append("<|begin_of_text|>")
        }
        for (msg in request.messages) {
            when (msg) {
                is Message.System -> Unit
                is Message.User -> append("<|start_header_id|>user<|end_header_id|>\n\n").append(msg.content).append("<|eot_id|>")
                is Message.Assistant -> {
                    append("<|start_header_id|>assistant<|end_header_id|>\n\n")
                    msg.toolCalls?.let { append(encodeToolCallsJson(it)) }
                        ?: append(msg.content.orEmpty())
                    append("<|eot_id|>")
                }
                is Message.Tool -> append("<|start_header_id|>tool<|end_header_id|>\n\n").append(msg.content).append("<|eot_id|>")
            }
        }
        append("<|start_header_id|>assistant<|end_header_id|>\n\n")
    }

    private fun buildGemmaPrompt(request: LLMRequest): String = buildString {
        val system = mergedSystem(request)
        for (msg in request.messages) {
            when (msg) {
                is Message.System -> Unit
                is Message.User -> {
                    append("<start_of_turn>user\n")
                    if (system.isNotBlank() && isFirstUserMessage(request.messages, msg)) {
                        append(system).append("\n\n")
                    }
                    append(msg.content).append("<end_of_turn>\n")
                }
                is Message.Assistant -> {
                    append("<start_of_turn>model\n")
                    msg.toolCalls?.let { append(encodeToolCallsJson(it)) }
                        ?: append(msg.content.orEmpty())
                    append("<end_of_turn>\n")
                }
                is Message.Tool -> append("<start_of_turn>user\nTool result: ").append(msg.content).append("<end_of_turn>\n")
            }
        }
        append("<start_of_turn>model\n")
    }

    private fun buildMistralPrompt(request: LLMRequest): String = buildString {
        val system = mergedSystem(request)
        var pendingSystem = system
        for (msg in request.messages) {
            when (msg) {
                is Message.System -> Unit
                is Message.User -> {
                    append("[INST] ")
                    if (pendingSystem.isNotBlank()) {
                        append(pendingSystem).append("\n\n")
                        pendingSystem = ""
                    }
                    append(msg.content).append(" [/INST]")
                }
                is Message.Assistant -> {
                    append(' ')
                    msg.toolCalls?.let { append(encodeToolCallsJson(it)) }
                        ?: append(msg.content.orEmpty())
                    append("</s>")
                }
                is Message.Tool -> append("[INST] Tool result: ").append(msg.content).append(" [/INST]")
            }
        }
    }

    private fun buildPlainPrompt(request: LLMRequest): String = buildString {
        val system = mergedSystem(request)
        if (system.isNotBlank()) appendLine("System: $system")
        for (msg in request.messages) {
            when (msg) {
                is Message.System -> Unit
                is Message.User -> appendLine("User: ${msg.content}")
                is Message.Assistant -> msg.content?.let { appendLine("Assistant: $it") }
                is Message.Tool -> appendLine("Tool(${msg.toolCallId}): ${msg.content}")
            }
        }
        append("Assistant: ")
    }

    private fun mergedSystem(request: LLMRequest): String {
        val systemMessages = request.messages.filterIsInstance<Message.System>().map { it.content }
        val toolInstruction = if (request.tools.isEmpty()) null else toolInstruction(request.tools)
        return (systemMessages + listOfNotNull(toolInstruction)).joinToString("\n\n")
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonElement.asJsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

    private fun isFirstUserMessage(messages: List<Message>, candidate: Message.User): Boolean =
        messages.firstOrNull { it is Message.User } === candidate

    private fun encodeToolCallsJson(toolCalls: List<ToolCall>): String = JsonArray(toolCalls.map { call ->
        buildJsonObject {
            put("id", call.id)
            put("type", "function")
            put("function", buildJsonObject {
                put("name", call.name)
                call.arguments?.let { args ->
                    val parsedArgs = runCatching { jsonParser.parseToJsonElement(args) }.getOrNull()
                    put("arguments", parsedArgs ?: JsonPrimitive(args))
                }
            })
        }
    }).toString()

    private fun toolInstruction(tools: List<ToolDefinition>): String {
        val toolsJson = JsonArray(tools.map { tool ->
            buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.arguments)
                })
            }
        }).toString()
        return """
You have access to tools. When you need to call a tool, respond with ONLY JSON in one of these forms:
{"name":"tool_name","arguments":{...}}
[{"name":"tool_name","arguments":{...}}]
Available tools: $toolsJson
""".trimIndent()
    }

    private fun parseToolCalls(text: String): List<ToolCall>? {
        val trimmed = extractJsonCandidate(text) ?: return null
        val parsed = runCatching { jsonParser.parseToJsonElement(trimmed) }.getOrNull() ?: return null
        val elements = when (parsed) {
            is JsonArray -> parsed.toList()
            is JsonObject -> listOf(parsed)
            else -> return null
        }
        val calls = elements.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val function = obj["function"]?.asJsonObjectOrNull()
            val name = obj["name"]?.asJsonPrimitiveOrNull()?.contentOrNull
                ?: function?.get("name")?.asJsonPrimitiveOrNull()?.contentOrNull
                ?: obj["tool_name"]?.asJsonPrimitiveOrNull()?.contentOrNull
                ?: obj["function_name"]?.asJsonPrimitiveOrNull()?.contentOrNull
                ?: return@mapNotNull null
            val argsElement = obj["arguments"]
                ?: function?.get("arguments")
                ?: obj["parameters"]
                ?: obj["args"]
                ?: JsonObject(emptyMap())
            ToolCall(
                id = obj["id"]?.asJsonPrimitiveOrNull()?.contentOrNull ?: "call_${UUID.randomUUID()}",
                name = name,
                arguments = argsElement.toJsonString(),
            )
        }
        return calls.takeIf { it.isNotEmpty() }
    }

    private fun extractJsonCandidate(text: String): String? {
        val cleaned = text.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        if (cleaned.startsWith("{") || cleaned.startsWith("[")) return cleaned
        val startObj = cleaned.indexOf('{')
        val startArr = cleaned.indexOf('[')
        val start = listOf(startObj, startArr).filter { it >= 0 }.minOrNull() ?: return null
        val end = maxOf(cleaned.lastIndexOf('}'), cleaned.lastIndexOf(']'))
        return if (end > start) cleaned.substring(start, end + 1) else null
    }

    private fun JsonElement.toJsonString(): String = when (this) {
        is JsonPrimitive -> contentOrNull ?: toString()
        else -> toString()
    }

    /** Rough RAM estimate: context length is the dominant tunable we control. */
    private fun estimateRamMb(): Int {
        // Heuristic floor; real footprint depends on model size (unknown to the
        // engine until load) + n_ctx. Callers tighten via LocalRuntimeConfig.minRamMb.
        val ctxContributionMb = (resolvedContextLength / 1024) * 16
        return (BASE_RAM_FLOOR_MB + ctxContributionMb).coerceAtLeast(BASE_RAM_FLOOR_MB)
    }

    private class LlamaCppSession(
        val contextHandle: Long,
        private val bridge: LlamaBridge,
        systemInstruction: String?,
        initialMessages: List<Message>,
        private val onClose: (String) -> Unit,
    ) : EngineSession {
        override val sessionId: String = UUID.randomUUID().toString()
        override val approximateMemoryBytes: Long = -1L
        override val hasCachedPrefix: Boolean get() = history.isNotEmpty()
        val abortFlag: AtomicBoolean = AtomicBoolean(false)
        val closed: AtomicBoolean = AtomicBoolean(false)
        private val history: MutableList<Message> = ArrayList<Message>().apply {
            systemInstruction?.let { add(Message.System(it)) }
            addAll(initialMessages)
        }

        @Synchronized
        fun snapshotMessages(): List<Message> = history.toList()

        @Synchronized
        fun append(messages: List<Message>) {
            history.addAll(messages)
        }

        override fun cancel() {
            abortFlag.set(true)
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                cancel()
                try {
                    bridge.releaseContext(contextHandle)
                } finally {
                    onClose(sessionId)
                }
            }
        }
    }

    private enum class ChatFamily {
        CHATML,
        LLAMA3,
        GEMMA,
        MISTRAL,
        PLAIN;

        companion object {
            fun fromModelId(modelId: String): ChatFamily {
                val id = modelId.lowercase()
                return when {
                    id.contains("llama-3") || id.contains("llama3") -> LLAMA3
                    id.contains("gemma") -> GEMMA
                    id.contains("mistral") || id.contains("mixtral") -> MISTRAL
                    id.contains("plain") -> PLAIN
                    else -> CHATML
                }
            }
        }
    }

    internal companion object {
        private val jsonParser = Json { ignoreUnknownKeys = true }

        /** llama.cpp's common default when n_ctx isn't pinned. */
        const val DEFAULT_CTX_FALLBACK = 4096

        private const val BASE_RAM_FLOOR_MB = 1_024

        private const val DEFAULT_MAX_CONCURRENT_SESSIONS = 2

        /** Map the native stop code to the SPI's [EngineStopReason]. */
        internal fun mapStopReason(nativeStopReason: Int): EngineStopReason = when (nativeStopReason) {
            LlamaBridge.NATIVE_STOP_EOS -> EngineStopReason.EOS
            LlamaBridge.NATIVE_STOP_LIMIT -> EngineStopReason.LENGTH
            LlamaBridge.NATIVE_STOP_WORD -> EngineStopReason.STOP_WORD
            // Abort = consumer cancelled; the collector is already being torn
            // down so the reason is informational. UNKNOWN lets the Provider's
            // fallback decide (it won't be turned into a Done in practice
            // because the flow is cancelled).
            LlamaBridge.NATIVE_STOP_ABORT -> EngineStopReason.UNKNOWN
            else -> EngineStopReason.UNKNOWN
        }

        /** Tokenizer-free fallback (~4 chars/token), only used before load. */
        internal fun roughTokenEstimate(text: String): Int =
            if (text.isEmpty()) 0 else ((text.length + 3) / 4).coerceAtLeast(1)
    }
}
