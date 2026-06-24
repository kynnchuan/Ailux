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
import com.ailux.core.session.Session
import com.ailux.core.session.SessionConfig
import com.ailux.core.session.SessionSnapshot
import com.ailux.core.session.StatelessProviderSession
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
     *
     * ### Cancellation — honest semantics (spec §6.1.5 / L0-8)
     *
     * Cancelling the collecting coroutine **always stops emission to the
     * downstream `Flow<LLMEvent>` immediately** — the caller will not see any
     * more `LLMEvent.Token`. What happens to the underlying native generation
     * depends on the engine and is surfaced via
     * [ProviderCapabilities.supportsInterruptibleCancellation]:
     *
     * - **`supportsInterruptibleCancellation = true`** (e.g. `LlamaCppEngine`
     *   via abort callback): the engine is told to stop as soon as cancel is
     *   observed. Native CPU/GPU work winds down promptly; memory pressure
     *   is relieved within milliseconds.
     *
     * - **`supportsInterruptibleCancellation = false`** (e.g. `LiteRTLMEngine`,
     *   which has no mid-generation abort hook): the upstream `Flow` is
     *   detached but **the engine keeps generating to its natural stop**.
     *   The remaining tokens are silently dropped; the only way to *truly*
     *   stop the work is to call [release], which tears the engine down.
     *
     * Either way, no events are emitted after cancellation — the contract to
     * the consumer is identical. The difference is purely about resource
     * pressure and tail latency. Business code that must guarantee the model
     * really stops (e.g. before reloading a different model) should call
     * [release] explicitly rather than relying on cancellation alone.
     */
    override fun streamGenerate(request: LLMRequest): Flow<LLMEvent> = flow {
        // Honesty guard — see [SessionConfig.modelId] / [LLMRequest.model] KDocs.
        // A native engine has exactly one loaded model file; per-request model
        // routing is physically impossible. If the caller passed a non-empty
        // `request.model` that doesn't match the loaded model's derived id,
        // fail loudly instead of silently ignoring the field — otherwise the
        // caller would assume the model switched when it did not.
        val expected = derivedModelId()
        val requested = request.model
        if (requested.isNotEmpty() && expected != null && requested != expected) {
            emit(
                LLMEvent.Error(
                    LLMError(
                        ErrorCode.MODEL_NOT_FOUND,
                        "LocalRuntimeProvider is bound to model '$expected' but request.model='$requested'. " +
                            "Native engines load one model per instance; open a different provider/session to switch.",
                    )
                )
            )
            emit(LLMEvent.Done(FinishReason.ERROR))
            return@flow
        }

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
     * Release native resources. Safe to call repeatedly (idempotent).
     *
     * After release, the next [streamGenerate] will trigger a fresh cold-load
     * (preflight → SHA-256 → `engine.load()`).
     *
     * **Use this when cancellation alone is not enough.** Per [streamGenerate]'s
     * cancellation contract, an engine that does not support interruptible
     * cancellation (e.g. `LiteRTLMEngine`) keeps the native work running after
     * the consumer cancels its `Flow`. Calling [release] is the only way to
     * truly stop the underlying engine — typical scenarios:
     *
     * - Switching to a different model (the previous model's KV cache must be torn down).
     * - Application background / activity destroyed (free GPU memory promptly).
     * - User explicitly opted into a "stop and free resources" UX, beyond just cancel.
     *
     * Any failure inside `engine.release()` is swallowed (`runCatching`) — release
     * is best-effort and must never throw, since it's commonly called from
     * `onCleared` / `onDestroy` paths.
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
            // Bubble engine's session capacity 1:1. Engines without session
            // support report 1; stateful engines (e.g. LiteRTLMEngine) report
            // the real fan-out their backend can safely sustain.
            maxConcurrentSessions = engineCaps.maxConcurrentSessions,
        )

    // ──────────────────────────────────────────
    // Session API (since v0.3.0)
    //
    // Two paths depending on the underlying engine:
    //
    // 1. Stateful engine (engine.supportsSessions = true): wrap the native
    //    EngineSession via [LocalEngineSessionAdapter]. KV-cache is reused
    //    across turns.
    //
    // 2. Stateless engine (or capability not declared): fall back to the
    //    generic client-side history accumulator. Every turn replays the full
    //    message list through engine.streamGenerate(req) — same behaviour as
    //    BackendProxy / Mock.
    //
    // openSession itself does NOT trigger model load — that still happens
    // lazily on the first Session.streamGenerate, so cold-load failures
    // surface as LLMEvent.Error inside the flow, not as exceptions from
    // openSession.
    // ──────────────────────────────────────────

    override fun openSession(config: SessionConfig): Session {
        // Provider always wins on modelId — application code shouldn't try to
        // set it on a native runtime. See [SessionConfig.modelId] KDoc.
        val effectiveConfig = config.copy(modelId = derivedModelId())
        return if (engine.supportsSessions) {
            // Native KV-cache path. We don't ensureLoaded here — engine.createSession
            // contract MAY require load(); engines should document either way.
            // Our load happens lazily inside the adapter's streamGenerate the first
            // time it's invoked (via engine.streamGenerate(req, session) which itself
            // calls back into the engine; we do not block openSession on it).
            val engineSession = engine.createSession(
                systemInstruction = effectiveConfig.systemInstruction,
                initialMessages = effectiveConfig.initialMessages,
            )
            LocalEngineSessionAdapter(
                engineSession = engineSession,
                engine = engine,
                config = effectiveConfig,
            )
        } else {
            // Stateless fallback (LlamaCppEngine, dumb single-shot executors, …).
            StatelessProviderSession(
                config = effectiveConfig,
                streamGenerateRaw = { req -> streamGenerate(req) },
            )
        }
    }

    override fun restoreSession(snapshot: SessionSnapshot): Session {
        // `snapshot.messages` is the canonical history and already includes the
        // original `Message.System` entry (when present). We MUST NOT also pass
        // `snapshot.systemInstruction` into the [SessionConfig] / engine
        // `createSession` paths, or the underlying session-init code would
        // prepend a duplicate system message to the working history.
        //
        // Two strategies are valid here:
        //  1. Strip the System from `snapshot.messages` and pass it via the
        //     dedicated `systemInstruction` parameter (preferred when the
        //     engine handles system separately, e.g. LiteRT-LM `ConversationConfig`).
        //  2. Leave `snapshot.messages` intact and skip `systemInstruction`.
        //
        // We use (2) here for both branches: a minimal, history-as-truth approach
        // that round-trips through `Message.System` and avoids strip/re-add churn.
        // For native engines that genuinely need system separated, the engine
        // adapter is responsible for filtering the System out at send time.
        val derived = derivedModelId()
        return if (engine.supportsSessions) {
            val engineSession = engine.createSession(
                systemInstruction = null,
                initialMessages = snapshot.messages,
            )
            LocalEngineSessionAdapter(
                engineSession = engineSession,
                engine = engine,
                config = SessionConfig(
                    samplerOverrides = snapshot.samplerOverrides,
                    providerHint = snapshot.providerHint,
                    modelId = derived,
                ),
                createdAtEpochMs = snapshot.createdAtEpochMs,
                initialHistory = snapshot.messages,
            )
        } else {
            StatelessProviderSession(
                config = SessionConfig(
                    samplerOverrides = snapshot.samplerOverrides,
                    providerHint = snapshot.providerHint,
                    modelId = derived,
                ),
                createdAtEpochMs = snapshot.createdAtEpochMs,
                initialHistory = snapshot.messages,
                streamGenerateRaw = { req -> streamGenerate(req) },
            )
        }
    }

    /**
     * Derive a stable, human-readable model id for the currently configured
     * local model. Currently emits `local:<stem>` where `<stem>` is the file
     * name without extension (e.g. `local:gemma-2b-it-int4`). Returns `null`
     * for unsupported [ModelSource] subtypes — surfaced as a `null` modelId
     * downstream rather than as an exception, since "no id" is still a valid
     * legacy session state.
     */
    internal fun derivedModelId(): String? {
        val source = config.modelSource
        return when (source) {
            is com.ailux.core.config.ModelSource.LocalPath -> {
                val stem = File(source.absolutePath).nameWithoutExtension
                if (stem.isEmpty()) null else "local:$stem"
            }
        }
    }

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
