package com.ailux.runtime.litertlm

import android.content.Context
import com.ailux.core.config.LocalRuntimeConfig
import com.ailux.core.config.ModelSource
import com.ailux.core.message.Message
import com.ailux.core.request.LLMRequest
import com.ailux.runtime.EngineCapabilities
import com.ailux.runtime.EngineEvent
import com.ailux.runtime.EngineSession
import com.ailux.runtime.EngineStopReason
import com.ailux.runtime.GpuBackend
import com.ailux.runtime.InferenceEngine
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.LiteRtLmJniException
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.google.ai.edge.litertlm.Message as LiteRtMessage

/**
 * [InferenceEngine] implementation backed by Google AI Edge **LiteRT-LM**
 * (`com.google.ai.edge.litertlm:litertlm-android` 0.13.x).
 *
 * ## What this binding does
 *
 * - Owns one [Engine] instance per `LiteRTLMEngine`; the engine is loaded
 *   lazily on the first call to [load] and may be replaced if [release] is
 *   called.
 * - Exposes the stateful **Session SPI** ([supportsSessions] = `true`):
 *   each [EngineSession] wraps a `com.google.ai.edge.litertlm.Conversation`,
 *   giving native KV-cache reuse across turns.
 * - Maps Ailux [LLMRequest] / [Message] into LiteRT-LM `Message.user` /
 *   `Message.model` / `Message.tool`, and surfaces token deltas back as
 *   [EngineEvent.Token].
 *
 * ## What it does NOT do
 *
 * - **Stateless [streamGenerate]** is intentionally unimplemented (throws):
 *   LiteRT-LM is a fundamentally stateful engine; the Provider-layer fallback
 *   for "no-session" callers is provided by `StatelessProviderSession`
 *   wrapping a short-lived Session. Forcing a stateless code path through
 *   the engine would just allocate-then-discard a `Conversation` every turn,
 *   which is what `StatelessProviderSession` already does in a more
 *   transparent way.
 *
 * - **`supportsInterruptibleCancellation` = `true`** (since 0.13): we map
 *   [LiteRTLMSession.cancel] → `Conversation.cancelProcess()`, which causes
 *   the in-flight `sendMessageAsync` flow to terminate promptly. Consumers
 *   that cancel their collecting coroutine no longer leave the native pass
 *   running — the wrapping [LocalEngineSessionAdapter] forwards cancellation
 *   to the session.
 *
 * ## Threading
 *
 * `Engine.initialize()` can take up to ~10 s and **must** run off the UI
 * thread. The `LocalRuntimeProvider` already wraps every call in a
 * single-thread dispatcher; we rely on that and do **not** add another layer
 * here. `Conversation` itself is not documented as thread-safe by upstream
 * — we treat each `Conversation` as single-threaded and serialise turns
 * within a session via [LiteRTLMSession.lock].
 *
 * ## Capacity
 *
 * LiteRT-LM 0.13.x permits multiple `Conversation` objects per `Engine`
 * (they share the loaded weights) but the public docs do **not** guarantee
 * concurrent inference safety. We default [EngineCapabilities.maxConcurrentSessions]
 * to **`1`** (serialize generation across sessions on the same engine) and
 * leave the value as configurable via [maxConcurrentSessionsOverride] for
 * deployments that have profiled their device.
 *
 * @param appContext                    Android context, kept for future NPU
 *                                      backend support (needs `nativeLibraryDir`).
 * @param backend                       which LiteRT-LM backend to use; defaults to GPU.
 * @param maxConcurrentSessionsOverride conservative override for the cap reported
 *                                      via [capabilities]. Default is `1`.
 * @param enableSpeculativeDecoding     opt-in MTP / speculative decoding flag for
 *                                      GPU; must be set BEFORE [Engine.initialize]
 *                                      (LiteRT-LM constraint).
 */
class LiteRTLMEngine @JvmOverloads constructor(
    private val appContext: Context,
    private val backend: LiteRtBackend = LiteRtBackend.GPU,
    private val maxConcurrentSessionsOverride: Int = 1,
    private val enableSpeculativeDecoding: Boolean = false,
) : InferenceEngine {

    // ──────────────────────────────────────────
    // Loadable native handles
    // ──────────────────────────────────────────

    /** Guards engine load / release. Generation paths are serialised at the session level. */
    private val engineLock = Mutex()

    @Volatile
    private var engine: Engine? = null

    /** Cached config; the only thing here that ever varies between calls. */
    @Volatile
    private var loadedConfig: LocalRuntimeConfig? = null

    // ──────────────────────────────────────────
    // InferenceEngine — load / release / capabilities
    // ──────────────────────────────────────────

    @OptIn(ExperimentalApi::class)
    override suspend fun load(config: LocalRuntimeConfig) {
        engineLock.withLock {
            if (engine != null && loadedConfig == config) return

            // If a previous engine was loaded with different config, tear it down first.
            engine?.let { runCatching { it.close() } }
            engine = null

            val source = config.modelSource
            require(source is ModelSource.LocalPath) {
                "LiteRTLMEngine only supports ModelSource.LocalPath; got $source"
            }

            // MTP must be flipped BEFORE Engine.initialize() — upstream contract.
            if (enableSpeculativeDecoding) {
                com.google.ai.edge.litertlm.ExperimentalFlags.enableSpeculativeDecoding = true
            }

            val engineConfig = EngineConfig(
                modelPath = source.absolutePath,
                backend = when (backend) {
                    LiteRtBackend.CPU -> Backend.CPU()
                    LiteRtBackend.GPU -> Backend.GPU()
                    LiteRtBackend.NPU -> Backend.NPU(
                        nativeLibraryDir = appContext.applicationInfo.nativeLibraryDir,
                    )
                },
                // Engine-level runaway guard — see [LocalRuntimeConfig.maxOutputTokens]
                // KDoc for the distinction vs per-request [LLMRequest.maxTokens].
                // Null is passed through verbatim ("no engine-level cap"); the value
                // becomes immutable once Engine.initialize() runs (LiteRT-LM contract:
                // changing it requires release() + reload).
                maxNumTokens = config.maxOutputTokens,
            )

            val newEngine = Engine(engineConfig)
            try {
                newEngine.initialize()
            } catch (jni: LiteRtLmJniException) {
                runCatching { newEngine.close() }
                throw jni
            }
            engine = newEngine
            loadedConfig = config
        }
    }

    override fun release() {
        // Best-effort: tear down both the native engine and discard cached config.
        val toClose = engine
        engine = null
        loadedConfig = null
        toClose?.let { runCatching { it.close() } }
    }

    override fun capabilities(): EngineCapabilities = EngineCapabilities(
        // ABI declared by upstream AAR: arm64-v8a only on current LiteRT-LM builds.
        supportAbis = setOf("arm64-v8a"),
        // Conservative default; real value depends on the model size and chosen backend.
        // Callers can override via LocalRuntimeConfig.minRamMb.
        estimatedRamMb = 2_048,
        gpuBackend = when (backend) {
            LiteRtBackend.CPU -> GpuBackend.NONE
            LiteRtBackend.GPU -> GpuBackend.GPU_DELEGATE
            LiteRtBackend.NPU -> GpuBackend.NONE
        },
        supportsTools = true, // LiteRT-LM supports both @Tool and OpenAPI tools.
        // 0.13.x exposes Conversation.cancelProcess(); see LiteRTLMSession.cancel().
        supportsInterruptibleCancellation = true,
        supportsModelExtensions = setOf("litertlm"),
        maxConcurrentSessions = maxConcurrentSessionsOverride.coerceAtLeast(1),
        // LiteRT-LM 0.13.x has no prefill-only / batched-ingest API: both
        // `sendMessage` and `sendMessageAsync` always trigger a full sampling
        // pass. We therefore report `false`, and rely on the Provider-layer
        // adapter (LocalEngineSessionAdapter) to merge non-final turn
        // messages into the final one so this engine only ever sees a single
        // message per turn. Flip to `true` when upstream exposes a true
        // prefill-only entry point.
        supportsBatchedIngest = false,
    )

    /**
     * Char-class aware fallback (CJK vs. other). See [CharClassTokenEstimator]
     * for rationale and the unit-test entry point — kept in its own top-level
     * file so tests don't have to load this class (which transitively pulls
     * the JDK 21-compiled LiteRT-LM AAR and trips a UnsupportedClassVersion
     * on JDK 17 test runners).
     */
    override fun sizeInTokens(text: String): Int = CharClassTokenEstimator.estimate(text)

    // ──────────────────────────────────────────
    // Stateless path — intentionally unsupported
    // ──────────────────────────────────────────

    override fun streamGenerate(request: LLMRequest): Flow<EngineEvent> =
        throw UnsupportedOperationException(
            "LiteRTLMEngine is a session-first engine; use openSession(...).streamGenerate(req). " +
                "The stateless fallback is provided by Provider-layer wrappers (StatelessProviderSession)."
        )

    // ──────────────────────────────────────────
    // Session SPI
    // ──────────────────────────────────────────

    override val supportsSessions: Boolean get() = true

    override fun createSession(
        systemInstruction: String?,
        initialMessages: List<Message>,
    ): EngineSession {
        val activeEngine = engine
            ?: throw IllegalStateException(
                "LiteRTLMEngine.load(config) must be called before createSession(). " +
                    "If you are routing through LocalRuntimeProvider, this should not happen — " +
                    "the provider's cold-load path should run first."
            )

        val conversationConfig = ConversationConfig(
            systemInstruction = systemInstruction?.let { Contents.of(it) },
            initialMessages = initialMessages.mapNotNull { it.toLiteRtOrNull() },
            // Per-session sampler defaults; per-request overrides are applied via
            // ConversationConfig.copy at sendMessage time inside LiteRTLMSession.
            //
            // SamplerConfig has no zero-arg constructor in LiteRT-LM 0.13.x:
            // topK / topP / temperature are required. We pick conservative defaults
            // here that line up with the upstream sample (topK=40, topP=0.95, temp=0.8);
            // callers wanting different defaults should set them via the per-request
            // path on `LLMRequest` (which the Provider layer will eventually translate).
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8),
        )

        val conversation = activeEngine.createConversation(conversationConfig)
        return LiteRTLMSession(conversation = conversation)
    }

    override fun streamGenerate(request: LLMRequest, session: EngineSession): Flow<EngineEvent> = flow {
        require(session is LiteRTLMSession) {
            "LiteRTLMEngine can only stream against a LiteRTLMSession; got ${session::class.simpleName}"
        }
        // Single-message contract — see [capabilities].supportsBatchedIngest = false.
        //
        // LiteRT-LM 0.13.x has no prefill-only API: every `sendMessage*` call
        // triggers a full generation. Calling sync-then-async on a multi-message
        // turn would therefore (a) waste n-1 inference passes, (b) pollute the
        // native KV cache with discarded middle replies, and (c) silently drop
        // their token output.
        //
        // To avoid that, we contract with the Provider-layer
        // [com.ailux.provider.local.LocalEngineSessionAdapter] that, for engines
        // reporting `supportsBatchedIngest = false`, the adapter MUST merge any
        // non-final turn messages into the final message before calling
        // `engine.streamGenerate(request, session)`. This engine therefore
        // expects exactly ONE convertible LiteRT-LM message per request.
        val incoming = request.messages
            .ifEmpty { error("LLMRequest.messages must contain at least the new user turn") }
        val convertible = incoming.mapNotNull { it.toLiteRtOrNull() }
        require(convertible.isNotEmpty()) { "LLMRequest produced no LiteRT-LM message" }
        require(convertible.size == 1) {
            "LiteRTLMEngine.streamGenerate expects a single LiteRT-LM message per turn " +
                "(supportsBatchedIngest=false). Got ${convertible.size}; the Provider-layer " +
                "adapter is responsible for merging multi-message turns before calling here."
        }
        val finalFlow: Flow<LiteRtMessage> = session.sendMessageAsync(convertible.single())
        try {
            finalFlow.collect { msg ->
                // LiteRT-LM 0.13.x has no `Message.text` accessor; the textual
                // payload lives inside `message.contents.contents` as zero or more
                // `Content.Text` chunks. We concatenate them in order — multimodal
                // content (Image/Audio/ToolResponse) is dropped from the streaming
                // text path and surfaces via separate channels (tool calls flow
                // through `LLMEvent.ToolCallReceived` upstream of this engine).
                val text = msg.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString(separator = "") { it.text }
                if (text.isNotEmpty()) {
                    emit(EngineEvent.Token(text))
                }
            }
            // LiteRT-LM 0.13.x does not surface a stop-reason or token-usage event on
            // the streaming flow; we emit a Stop(EOS) on natural completion and let
            // LocalRuntimeProvider's fallback compute the Usage via sizeInTokens.
            emit(EngineEvent.Stop(EngineStopReason.EOS))
        } catch (jni: LiteRtLmJniException) {
            // Translate to UNKNOWN; LocalRuntimeProvider wraps it as MODEL_LOAD_FAILED
            // unless the message contains memory keywords (handled there).
            emit(EngineEvent.Stop(EngineStopReason.UNKNOWN))
            throw jni
        }
    }

    // ──────────────────────────────────────────
    // Message conversion
    // ──────────────────────────────────────────

    /**
     * Convert an Ailux [Message] into a LiteRT-LM [LiteRtMessage].
     *
     * Returns `null` for messages that have no natural LiteRT-LM equivalent
     * for the current call (e.g. [Message.System] is forwarded via
     * `ConversationConfig.systemInstruction` at session-open time, not as a
     * regular message turn — so we drop it here).
     */
    private fun Message.toLiteRtOrNull(): LiteRtMessage? = when (this) {
        is Message.System -> null
        is Message.User -> LiteRtMessage.user(content)
        is Message.Assistant -> LiteRtMessage.model(content ?: "")
        is Message.Tool -> LiteRtMessage.tool(Contents.of(content))
    }

    /** Which LiteRT-LM backend to bind to. */
    enum class LiteRtBackend { CPU, GPU, NPU }
}
