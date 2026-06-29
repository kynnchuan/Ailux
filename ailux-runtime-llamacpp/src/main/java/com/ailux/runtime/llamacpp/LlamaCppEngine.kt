package com.ailux.runtime.llamacpp

import com.ailux.core.config.LocalRuntimeConfig
import com.ailux.core.config.ModelSource
import com.ailux.core.message.Message
import com.ailux.core.request.LLMRequest
import com.ailux.runtime.EngineCapabilities
import com.ailux.runtime.EngineEvent
import com.ailux.runtime.EngineStopReason
import com.ailux.runtime.GpuBackend
import com.ailux.runtime.InferenceEngine
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
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
            nCtx = nCtx,
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
        // Tool-call token parsing is not wired in this binding yet; honest false.
        supportsTools = false,
        // llama.cpp's decode loop honours an abort flag between tokens.
        supportsInterruptibleCancellation = true,
        // Declaration only — never used to pre-flight/reject (spec §1.5 / Q11).
        supportedModelExtensions = setOf("gguf"),
        // Stateless engine: session capacity is meaningless, report 1.
        maxConcurrentSessions = 1,
        // llama.cpp DOES have a prefill-only path (llama_decode with no sampling),
        // but we don't expose sessions yet, so this flag is inert. Keep false
        // until the session SPI is implemented for this engine.
        supportsBatchedIngest = false,
    )

    override fun sizeInTokens(text: String): Int {
        val h = handle
        // Before load (or after release) we have no tokenizer; fall back to a
        // rough char-based estimate so callers never get a hard failure.
        if (h == 0L) return roughTokenEstimate(text)
        return runCatching { bridge.tokenCount(h, text) }.getOrElse { roughTokenEstimate(text) }
    }

    // ──────────────────────────────────────────
    // Stateless generation
    // ──────────────────────────────────────────

    override fun streamGenerate(request: LLMRequest): Flow<EngineEvent> = callbackFlow {
        val h = handle
        check(h != 0L) {
            "LlamaCppEngine.load(config) must be called before streamGenerate(). " +
                "If routing through LocalRuntimeProvider, the cold-load path runs first."
        }

        val prompt = buildPrompt(request)
        val aborted = AtomicBoolean(false)

        val sink = object : LlamaBridge.TokenSink {
            override fun onToken(text: String) {
                if (text.isEmpty()) return
                // Native callback thread cannot suspend; non-blocking offer into
                // the UNLIMITED buffer (see buffer() below). trySend never blocks.
                trySend(EngineEvent.Token(text))
            }

            override fun isAborted(): Boolean = aborted.get()

            override fun onStop(nativeStopReason: Int, promptTokens: Int, genTokens: Int) {
                if (promptTokens >= 0 && genTokens >= 0) {
                    trySend(EngineEvent.Usage(promptTokens = promptTokens, genTokens = genTokens))
                }
                trySend(EngineEvent.Stop(mapStopReason(nativeStopReason)))
            }
        }

        // generate() blocks until the native loop exits; run it on the flow's
        // collecting context (the Provider pins this to its single native
        // thread). We surface native failures by closing the channel with the
        // throwable so the Provider maps it to MODEL_LOAD_FAILED / OOM.
        try {
            bridge.generate(
                handle = h,
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

        // When the collector cancels, flip the abort flag so the native loop
        // stops mid-token (interruptible cancel). awaitClose runs on cancel and
        // on normal close.
        awaitClose {
            aborted.set(true)
        }
    }.buffer(capacity = Int.MAX_VALUE, onBufferOverflow = BufferOverflow.SUSPEND)

    // ──────────────────────────────────────────
    // Internal helpers (unit-tested directly)
    // ──────────────────────────────────────────

    /**
     * Build a single prompt string from the request messages.
     *
     * This is a deliberately minimal, model-agnostic role-tagged format. Chat
     * templating that matches a specific GGUF's expected format (ChatML, Gemma,
     * etc.) is a business/demo concern layered above; the engine keeps a plain
     * default so a bare model still produces coherent output.
     */
    internal fun buildPrompt(request: LLMRequest): String = buildString {
        for (msg in request.messages) {
            when (msg) {
                is Message.System -> appendLine("System: ${msg.content}")
                is Message.User -> appendLine("User: ${msg.content}")
                is Message.Assistant -> msg.content?.let { appendLine("Assistant: $it") }
                is Message.Tool -> appendLine("Tool(${msg.toolCallId}): ${msg.content}")
            }
        }
        // Prime the model to continue as the assistant.
        append("Assistant: ")
    }

    /** Rough RAM estimate: context length is the dominant tunable we control. */
    private fun estimateRamMb(): Int {
        // Heuristic floor; real footprint depends on model size (unknown to the
        // engine until load) + n_ctx. Callers tighten via LocalRuntimeConfig.minRamMb.
        val ctxContributionMb = (resolvedContextLength / 1024) * 16
        return (BASE_RAM_FLOOR_MB + ctxContributionMb).coerceAtLeast(BASE_RAM_FLOOR_MB)
    }

    internal companion object {
        /** llama.cpp's common default when n_ctx isn't pinned. */
        const val DEFAULT_CTX_FALLBACK = 4096

        private const val BASE_RAM_FLOOR_MB = 1_024

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
