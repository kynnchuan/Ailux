package com.ailux.provider.local

import android.content.Context
import com.ailux.core.LLMProvider
import com.ailux.core.capabilities.ProviderCapabilities
import com.ailux.core.config.LocalRuntimeConfig
import com.ailux.core.error.ErrorCode
import com.ailux.core.error.LLMError
import com.ailux.core.event.FinishReason
import com.ailux.core.event.LLMEvent
import com.ailux.core.request.LLMRequest
import com.ailux.core.response.LLMResponse
import com.ailux.core.response.UsageInfo
import com.ailux.provider.local.device.DeviceProbe
import com.ailux.provider.local.util.Sha256Verifier
import com.ailux.runtime.EngineCapabilities
import com.ailux.runtime.EngineEvent
import com.ailux.runtime.EngineStopReason
import com.ailux.runtime.InferenceEngine
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * On-device [LLMProvider] — companion of `BackendProxyProvider`. Owns the model
 * lifecycle, normalizes raw [EngineEvent] into [LLMEvent], translates engine
 * stop reasons into [FinishReason], maps native exceptions into
 * [ErrorCode]s, and surfaces engine capabilities as [ProviderCapabilities].
 *
 * Spec §6.1 — implements the 7 steps:
 *
 * 1. **Dedicated single-thread executor** — satisfies the native handle's
 *    thread-affinity requirement *and* serializes generations naturally (no
 *    extra `Mutex` for execution; one is used only to guard cold-load races).
 *
 * 2. **First [streamGenerate] triggers cold [InferenceEngine.load]** — preflight
 *    (ABI / RAM) → SHA-256 (if configured) → `engine.load()`. Failures throw
 *    typed [com.ailux.core.error.LLMException] **before** any event is emitted
 *    and the provider remains "not loaded" (re-loadable on the next request).
 *
 * 3. **Event normalization** (R1/R2): consumes `engine.streamGenerate(...)`'s
 *    `Flow<EngineEvent>`. `Token` → [LLMEvent.Token]; `Stop` / `Usage` are
 *    deferred to steps 4 / 5 so the final order is `Token* → Usage → Done`.
 *
 * 4. **Exact Usage**: prefers the engine's native [EngineEvent.Usage] (e.g.
 *    llama.cpp's `n_p_eval`/`n_eval`); otherwise counts via
 *    [InferenceEngine.sizeInTokens] over the prompt and the concatenated
 *    output. Either way **`estimated = false`** — the model's own tokenizer
 *    is authoritative.
 *
 * 5. **finishReason translation** (R2): the engine emits `Stop(EngineStopReason)`;
 *    the Provider translates: `EOS`/`STOP_WORD` → `COMPLETE`, `LENGTH` → `LENGTH`,
 *    `UNKNOWN` → workaround (output tokens ≥ `maxTokens` ⇒ `LENGTH` else `COMPLETE`).
 *
 * 6. **Error mapping**: native exceptions → [LLMEvent.Error] + [LLMEvent.Done]
 *    via [mapLocalError]. Mid-stream OOM is caught the same way; already-emitted
 *    tokens are preserved — what to do with the partial answer is a business
 *    policy (spec §3 mechanism-vs-policy).
 *
 * 7. **capabilities** = engine self-description bubbled up to
 *    [ProviderCapabilities] (see [buildProviderCapabilities]).
 *
 * @param appContext  Used by [DeviceProbe] for `ActivityManager.MemoryInfo`.
 *                    May be `null` **only** in pure-JVM unit tests where the
 *                    cold-load path is bypassed (e.g. by reflection-flipping
 *                    `loaded=true`); production code must always pass a real
 *                    application context.
 * @param config      Model-lifetime configuration (model path, hash, RAM override).
 * @param engine      Engine implementation (`LiteRTLMEngine`, `LlamaCppEngine`, …).
 */
class LocalRuntimeProvider(
    private val appContext: Context?,
    private val config: LocalRuntimeConfig,
    private val engine: InferenceEngine,
) : LLMProvider {

    // Step 1 — long-lived dedicated single-thread dispatcher; chosen over
    // `limitedParallelism(1)` because native handles often require thread
    // affinity (the same physical thread for load / generate / release).
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ailux-local-runtime").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    // Guards the cold-load critical section (load is async + idempotent here).
    private val loadMutex = Mutex()

    @Volatile
    private var loaded: Boolean = false

    /** Step 7 — bubble engine capabilities up to the provider layer. */
    override val capabilities: ProviderCapabilities by lazy {
        buildProviderCapabilities(engine.capabilities())
    }

    /** Step 2 — cold-load: preflight → SHA-256 → engine.load. Failures keep `loaded=false`. */
    private suspend fun ensureLoaded() {
        if (loaded) return
        loadMutex.withLock {
            if (loaded) return

            // (a) Device-capability preflight (default-on). Skipped when
            // `appContext == null` — a JVM-test escape hatch; production code
            // always has a non-null application context.
            if (appContext != null) {
                when (val probe = DeviceProbe.check(appContext, engine.capabilities(), config.minRamMb)) {
                    is DeviceProbe.Result.Ok -> Unit
                    is DeviceProbe.Result.UnsupportedAbi ->
                        throw LocalRuntimeException(ErrorCode.UNSUPPORTED_ABI, probe.message)
                    is DeviceProbe.Result.InsufficientMemory ->
                        throw LocalRuntimeException(ErrorCode.INSUFFICIENT_MEMORY, probe.message)
                }
            }

            // (b) SHA-256 verification (opt-in — null hash skips).
            val source = config.modelSource
            if (source is com.ailux.core.config.ModelSource.LocalPath) {
                val file = File(source.absolutePath)
                Sha256Verifier.verify(file, config.verifySha256)?.let { detail ->
                    throw LocalRuntimeException(ErrorCode.MODEL_FILE_INVALID, detail)
                }
            }

            // (c) Engine load — format validation is internal to the engine.
            try {
                engine.load(config)
                loaded = true
            } catch (oom: OutOfMemoryError) {
                throw LocalRuntimeException(ErrorCode.INSUFFICIENT_MEMORY, "OOM during model load", oom)
            } catch (t: Throwable) {
                throw LocalRuntimeException(ErrorCode.MODEL_LOAD_FAILED, t.message ?: "engine.load failed", t)
            }
        }
    }

    /**
     * Steps 2–6 wrapped in a normalizing flow. The whole pipeline runs on
     * [dispatcher] (single thread → natural serialization across concurrent
     * tasks; cold-load is shared via [loadMutex]).
     */
    override fun streamGenerate(request: LLMRequest): Flow<LLMEvent> = flow {
        // Step 2 — cold-load on demand.
        try {
            ensureLoaded()
        } catch (e: LocalRuntimeException) {
            emit(LLMEvent.Error(LLMError(e.code, e.message ?: e.code.name, e.cause)))
            emit(LLMEvent.Done(FinishReason.ERROR))
            return@flow
        }

        // Step 4 — bookkeeping for tokens / usage.
        var nativeUsage: EngineEvent.Usage? = null
        var stopReason: EngineStopReason? = null
        val emittedTokens = StringBuilder()
        var outputTokenCount = 0

        try {
            engine.streamGenerate(request).collect { ev ->
                when (ev) {
                    is EngineEvent.Token -> {
                        emittedTokens.append(ev.text)
                        outputTokenCount += 1
                        emit(LLMEvent.Token(ev.text))
                    }
                    is EngineEvent.Usage -> {
                        nativeUsage = ev
                    }
                    is EngineEvent.Stop -> {
                        stopReason = ev.reason
                    }
                }
            }
        } catch (oom: OutOfMemoryError) {
            // Step 6 — mid-stream OOM; keep partial tokens, surface error then Done.
            emit(LLMEvent.Error(LLMError(ErrorCode.INSUFFICIENT_MEMORY, "OOM during generation", oom)))
            emit(LLMEvent.Done(FinishReason.ERROR))
            return@flow
        } catch (t: Throwable) {
            val mapped = mapLocalError(t)
            emit(LLMEvent.Error(mapped))
            emit(LLMEvent.Done(FinishReason.ERROR))
            return@flow
        }

        // Step 4 — emit exact Usage (engine-native preferred; tokenizer fallback).
        val usageInfo = nativeUsage?.let {
            UsageInfo(inputTokens = it.promptTokens, outputTokens = it.genTokens, estimated = false)
        } ?: runCatching {
            UsageInfo(
                inputTokens = engine.sizeInTokens(promptOf(request)),
                outputTokens = engine.sizeInTokens(emittedTokens.toString()),
                estimated = false,
            )
        }.getOrNull()
        if (usageInfo != null) emit(LLMEvent.Usage(usageInfo))

        // Step 5 — finishReason translation (engine emits Stop; Provider translates).
        emit(LLMEvent.Done(translateFinishReason(stopReason, outputTokenCount, request.maxTokens)))
    }.flowOn(dispatcher)

    /**
     * Non-streaming generation: collects the streaming flow internally so cancel
     * semantics and error mapping stay consistent.
     */
    override suspend fun generate(request: LLMRequest): LLMResponse = withContext(dispatcher) {
        val events = streamGenerate(request).toList()
        val text = events.filterIsInstance<LLMEvent.Token>().joinToString("") { it.text }
        val usage = events.filterIsInstance<LLMEvent.Usage>().lastOrNull()?.info
        val error = events.filterIsInstance<LLMEvent.Error>().firstOrNull()
        if (error != null) {
            throw com.ailux.core.error.LLMException(error.error)
        }
        LLMResponse(text = text, usage = usage)
    }

    /**
     * Release native resources. Safe to call repeatedly. After release, the next
     * [streamGenerate] will trigger a fresh cold-load.
     */
    fun release() {
        runCatching { engine.release() }
        loaded = false
    }

    // ---- internals ---------------------------------------------------------

    /** Step 5 — engine-emit-Stop, Provider-translate (spec §6.1.5 + FinishReason table). */
    internal fun translateFinishReason(
        reason: EngineStopReason?,
        outputTokens: Int,
        maxTokens: Int?,
    ): FinishReason = when (reason) {
        EngineStopReason.EOS -> FinishReason.COMPLETE
        // Per FinishReason table: stop sequence == COMPLETE (OpenAI "stop" / Anthropic "stop_sequence").
        EngineStopReason.STOP_WORD -> FinishReason.COMPLETE
        EngineStopReason.LENGTH -> FinishReason.LENGTH
        // UNKNOWN / null → workaround: if we hit the cap, it's LENGTH; else COMPLETE.
        EngineStopReason.UNKNOWN, null -> {
            if (maxTokens != null && outputTokens >= maxTokens) FinishReason.LENGTH
            else FinishReason.COMPLETE
        }
    }

    /** Step 6 — error mapping for native exceptions other than OOM. */
    internal fun mapLocalError(t: Throwable): LLMError {
        if (t is LocalRuntimeException) {
            return LLMError(t.code, t.message ?: t.code.name, t.cause)
        }
        val msg = t.message.orEmpty().lowercase()
        val code = when {
            "oom" in msg || "out of memory" in msg || "memory" in msg -> ErrorCode.INSUFFICIENT_MEMORY
            "abi" in msg || "arch" in msg                              -> ErrorCode.UNSUPPORTED_ABI
            else                                                       -> ErrorCode.MODEL_LOAD_FAILED
        }
        return LLMError(code, t.message ?: code.name, t)
    }

    /** Step 7 — engine capabilities bubble-up. */
    internal fun buildProviderCapabilities(engineCaps: EngineCapabilities): ProviderCapabilities =
        ProviderCapabilities(
            supportsTool = engineCaps.supportsTools,
            supportsStream = true,
            supportsVision = false, // v0.3.0: on-device VLM is not in scope.
            maxContextToken = null, // Until LocalRuntimeConfig.contextLength is wired (R3 follow-up).
            supportsInterruptibleCancellation = engineCaps.supportsInterruptibleCancellation,
        )

    /** Best-effort prompt extraction for token-count fallback. */
    private fun promptOf(request: LLMRequest): String =
        request.messages.joinToString("\n") { msg -> messageText(msg) }

    private fun messageText(msg: com.ailux.core.message.Message): String = when (msg) {
        is com.ailux.core.message.Message.System    -> msg.content
        is com.ailux.core.message.Message.User      -> msg.content
        is com.ailux.core.message.Message.Assistant -> msg.content ?: ""
        is com.ailux.core.message.Message.Tool      -> msg.content
    }
}

/**
 * Typed exception used inside cold-load to carry an [ErrorCode] back out of the
 * `Mutex.withLock` boundary without losing the mapping.
 */
internal class LocalRuntimeException(
    val code: ErrorCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
