package com.ailux.runtime.llamacpp

import com.ailux.core.config.LocalRuntimeConfig
import com.ailux.core.config.ModelSource
import com.ailux.core.message.Message
import com.ailux.core.request.LLMRequest
import com.ailux.core.tool.ToolDefinition
import com.ailux.runtime.EngineEvent
import com.ailux.runtime.EngineSession
import com.ailux.runtime.EngineStopReason
import com.ailux.runtime.GpuBackend
import com.ailux.runtime.KvCacheEditableSession
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage of [LlamaCppEngine]'s policy / mapping logic.
 *
 * All JNI is behind the [LlamaBridge] seam, so a [FakeBridge] lets us test the
 * engine on a normal JUnit runner without loading any `.so`. We cover the
 * DoD §10.2 rows that don't require a real device:
 *  - EngineEvent normalization order (Token* → Usage → Stop)
 *  - native stop-reason mapping (eos/limit/word/abort)
 *  - contextLength → n_ctx translation
 *  - constructor params (n_gpu_layers / n_threads) not leaking into config
 *  - format declaration (gguf), declaration-only (not enforced)
 *  - Vulkan capability reporting
 *  - interruptible-cancel capability = true
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LlamaCppEngineTest {

    // ── capabilities ──────────────────────────────────────────────────────────

    @Test
    fun capabilities_declareGgufArmInterruptible() {
        val engine = LlamaCppEngine(nGpuLayers = 0, nThreads = 4, useVulkan = false, bridge = FakeBridge())
        val caps = engine.capabilities()

        assertEquals(setOf("gguf"), caps.supportedModelExtensions)
        assertEquals(setOf("arm64-v8a"), caps.supportAbis)
        assertTrue(caps.supportsTools)
        assertEquals(2, caps.maxConcurrentSessions)
        // llama.cpp's defining advantage over LiteRT-LM: real mid-token cancel.
        assertTrue(caps.supportsInterruptibleCancellation)
        assertTrue(engine.supportsSessions)
        // No CPU/GPU loaded yet → NONE.
        assertEquals(GpuBackend.NONE, caps.gpuBackend)
    }

    @Test
    fun capabilities_vulkanReportedWhenRequestedAndActive() = runTest {
        val bridge = FakeBridge(vulkanActive = true)
        val engine = LlamaCppEngine(nGpuLayers = 99, nThreads = 0, useVulkan = true, bridge = bridge)
        engine.load(configWith(contextLength = 2048))

        assertEquals(GpuBackend.VULKAN, engine.capabilities().gpuBackend)
    }

    @Test
    fun capabilities_vulkanFallsBackToNoneWhenNotActive() = runTest {
        // Requested Vulkan but native says it isn't active → honest NONE.
        val bridge = FakeBridge(vulkanActive = false)
        val engine = LlamaCppEngine(nGpuLayers = 99, nThreads = 0, useVulkan = true, bridge = bridge)
        engine.load(configWith(contextLength = 2048))

        assertEquals(GpuBackend.NONE, engine.capabilities().gpuBackend)
    }

    // ── load / contextLength → n_ctx ────────────────────────────────────────────

    @Test
    fun load_translatesContextLengthToNCtx() = runTest {
        val bridge = FakeBridge()
        val engine = LlamaCppEngine(nGpuLayers = 0, nThreads = 0, useVulkan = false, bridge = bridge)

        engine.load(configWith(contextLength = 8192))

        assertEquals(8192, bridge.lastNCtx)
    }

    @Test
    fun load_nullContextLengthPassesZeroSentinel() = runTest {
        val bridge = FakeBridge()
        val engine = LlamaCppEngine(nGpuLayers = 0, nThreads = 0, useVulkan = false, bridge = bridge)

        engine.load(configWith(contextLength = null))

        // 0 = "let llama.cpp pick the model default".
        assertEquals(0, bridge.lastNCtx)
    }

    @Test
    fun load_forwardsConstructorPrivateParamsNotFromConfig() = runTest {
        // n_gpu_layers / n_threads come from the constructor (ADR-0001), never
        // from LocalRuntimeConfig (which has no such fields).
        val bridge = FakeBridge()
        val engine = LlamaCppEngine(nGpuLayers = 24, nThreads = 6, useVulkan = false, bridge = bridge)

        engine.load(configWith(contextLength = 4096))

        assertEquals(24, bridge.lastNGpuLayers)
        assertEquals(6, bridge.lastNThreads)
    }

    // NOTE: A "rejects non-LocalPath ModelSource" test is intentionally omitted.
    // `ModelSource` is a sealed interface in :ailux-core with `LocalPath` as its
    // only variant, and a sealed type cannot be extended from another module, so
    // there is no second variant to feed the engine. The engine still keeps the
    // defensive `require(source is ModelSource.LocalPath)` guard for when a new
    // variant (e.g. Asset/Remote) is added — at which point this test can return.

    // ── streamGenerate normalization ────────────────────────────────────────────

    @Test
    fun streamGenerate_emitsTokensUsageThenStopEos() = runTest {
        val bridge = FakeBridge(
            script = GenScript(
                tokens = listOf("Hello", ", ", "world"),
                stopReason = LlamaBridge.NATIVE_STOP_EOS,
                promptTokens = 7,
                genTokens = 3,
            ),
        )
        val engine = engine(bridge = bridge)
        engine.load(configWith(contextLength = 4096))

        val events = engine.streamGenerate(userRequest("hi")).toList()

        assertEquals(EngineEvent.Token("Hello"), events[0])
        assertEquals(EngineEvent.Token(", "), events[1])
        assertEquals(EngineEvent.Token("world"), events[2])
        assertEquals(EngineEvent.Usage(promptTokens = 7, genTokens = 3), events[3])
        assertEquals(EngineEvent.Stop(EngineStopReason.EOS), events[4])
    }

    @Test
    fun streamGenerate_mapsLimitStopReason() = runTest {
        val bridge = FakeBridge(
            script = GenScript(tokens = listOf("a"), stopReason = LlamaBridge.NATIVE_STOP_LIMIT),
        )
        val engine = engine(bridge = bridge)
        engine.load(configWith(contextLength = 4096))

        val events = engine.streamGenerate(userRequest("hi")).toList()

        assertEquals(EngineEvent.Stop(EngineStopReason.LENGTH), events.last())
    }

    @Test
    fun streamGenerate_mapsStopWordReason() = runTest {
        val bridge = FakeBridge(
            script = GenScript(tokens = listOf("a"), stopReason = LlamaBridge.NATIVE_STOP_WORD),
        )
        val engine = engine(bridge = bridge)
        engine.load(configWith(contextLength = 4096))

        val events = engine.streamGenerate(userRequest("hi")).toList()

        assertEquals(EngineEvent.Stop(EngineStopReason.STOP_WORD), events.last())
    }

    @Test
    fun streamGenerate_omitsUsageWhenNativeCountersUnavailable() = runTest {
        val bridge = FakeBridge(
            script = GenScript(
                tokens = listOf("x"),
                stopReason = LlamaBridge.NATIVE_STOP_EOS,
                promptTokens = -1,  // unavailable
                genTokens = -1,
            ),
        )
        val engine = engine(bridge = bridge)
        engine.load(configWith(contextLength = 4096))

        val events = engine.streamGenerate(userRequest("hi")).toList()

        // No Usage event — Provider falls back to sizeInTokens.
        assertFalse(events.any { it is EngineEvent.Usage })
        assertEquals(EngineEvent.Stop(EngineStopReason.EOS), events.last())
    }

    @Test
    fun streamGenerate_beforeLoadThrows() = runTest {
        val engine = engine(bridge = FakeBridge())
        var threw = false
        try {
            engine.streamGenerate(userRequest("hi")).toList()
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    // ── stop-reason mapping (companion, no flow) ─────────────────────────────────

    @Test
    fun mapStopReason_coversAllCodes() {
        assertEquals(EngineStopReason.EOS, LlamaCppEngine.mapStopReason(LlamaBridge.NATIVE_STOP_EOS))
        assertEquals(EngineStopReason.LENGTH, LlamaCppEngine.mapStopReason(LlamaBridge.NATIVE_STOP_LIMIT))
        assertEquals(EngineStopReason.STOP_WORD, LlamaCppEngine.mapStopReason(LlamaBridge.NATIVE_STOP_WORD))
        assertEquals(EngineStopReason.UNKNOWN, LlamaCppEngine.mapStopReason(LlamaBridge.NATIVE_STOP_ABORT))
        assertEquals(EngineStopReason.UNKNOWN, LlamaCppEngine.mapStopReason(999))
    }

    // ── sizeInTokens ─────────────────────────────────────────────────────────────

    @Test
    fun sizeInTokens_usesBridgeAfterLoad() = runTest {
        val bridge = FakeBridge(tokenCountFn = { it.length })
        val engine = engine(bridge = bridge)
        engine.load(configWith(contextLength = 4096))

        assertEquals(5, engine.sizeInTokens("hello"))
    }

    @Test
    fun sizeInTokens_fallsBackBeforeLoad() {
        // No handle yet → rough char-based estimate, never a hard failure.
        val engine = engine(bridge = FakeBridge())
        assertEquals(0, engine.sizeInTokens(""))
        assertTrue(engine.sizeInTokens("hello world") > 0)
    }

    // ── prompt building ──────────────────────────────────────────────────────────

    @Test
    fun buildPrompt_defaultsToChatMlAndPrimesAssistant() {
        val engine = engine(bridge = FakeBridge())
        val prompt = engine.buildPrompt(
            LLMRequest(
                messages = listOf(
                    Message.System("be brief"),
                    Message.User("hi"),
                    Message.Assistant("hello"),
                    Message.User("bye"),
                ),
            ),
        )
        assertTrue(prompt.contains("<|im_start|>system\nbe brief<|im_end|>"))
        assertTrue(prompt.contains("<|im_start|>user\nhi<|im_end|>"))
        assertTrue(prompt.contains("<|im_start|>assistant\nhello<|im_end|>"))
        assertTrue(prompt.contains("<|im_start|>user\nbye<|im_end|>"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun buildPrompt_usesLlama3TemplateWhenModelMatches() {
        val engine = engine(bridge = FakeBridge())
        val prompt = engine.buildPrompt(
            LLMRequest(
                model = "Meta-Llama-3-8B-Instruct",
                messages = listOf(
                    Message.System("be brief"),
                    Message.User("hi"),
                ),
            ),
        )
        assertTrue(prompt.startsWith("<|begin_of_text|><|start_header_id|>system<|end_header_id|>"))
        assertTrue(prompt.contains("<|start_header_id|>user<|end_header_id|>\n\nhi<|eot_id|>"))
        assertTrue(prompt.endsWith("<|start_header_id|>assistant<|end_header_id|>\n\n"))
    }

    @Test
    fun streamGenerate_parsesToolCallJsonWhenToolsPresent() = runTest {
        val bridge = FakeBridge(
            script = GenScript(
                tokens = listOf("{\"name\":\"get_weather\",\"arguments\":{\"city\":\"Beijing\"}}"),
                promptTokens = 9,
                genTokens = 6,
            ),
        )
        val engine = engine(bridge = bridge)
        engine.load(configWith(contextLength = 4096))

        val events = engine.streamGenerate(
            LLMRequest(
                messages = listOf(Message.User("weather?")),
                tools = listOf(weatherTool()),
            )
        ).toList()

        val toolEvent = events.filterIsInstance<EngineEvent.ToolCallReceived>().single()
        assertEquals("get_weather", toolEvent.toolCalls.single().name)
        assertTrue(toolEvent.toolCalls.single().arguments!!.contains("Beijing"))
        assertEquals(EngineEvent.Usage(promptTokens = 9, genTokens = 6), events[0])
        assertEquals(EngineEvent.Stop(EngineStopReason.TOOL_CALL), events.last())
        assertFalse(events.any { it is EngineEvent.Token })
    }

    @Test
    fun createSession_usesIndependentContextAndReleasesItOnClose() = runTest {
        val bridge = FakeBridge()
        val engine = engine(bridge = bridge)
        engine.load(configWith(contextLength = 4096))

        val session: EngineSession = engine.createSession(systemInstruction = "sys", initialMessages = listOf(Message.User("seed")))
        assertTrue(session.hasCachedPrefix)
        assertEquals(1, bridge.createContextCount)

        engine.streamGenerate(LLMRequest(messages = listOf(Message.User("next"))), session).toList()
        assertEquals(101L, bridge.lastGenerateHandle)

        session.close()

        assertEquals(1, bridge.releaseContextCount)
    }

    // ── ADR-0010 capabilities + KV edit ──────────────────────────────────────────

    @Test
    fun capabilities_reportTier1KvCacheGovernance() {
        val caps = engine(bridge = FakeBridge()).capabilities()
        // Tier 1: fine-grained KV editing is available …
        assertTrue(caps.supportsKvCacheEdit)
        // … and the engine does NOT silently self-shift (Ailux owns the window).
        assertFalse(caps.supportsContextShift)
    }

    @Test
    fun session_tracksIngestedTokensFromSeedAndAppends() = runTest {
        // tokenCount = char length, so token totals are deterministic.
        val bridge = FakeBridge(tokenCountFn = { it.length })
        val engine = engine(bridge = bridge)
        engine.load(configWith(contextLength = 4096))

        val session = engine.createSession(
            systemInstruction = "sys",          // 3
            initialMessages = listOf(Message.User("hello")), // 5
        )
        // Seed counted up front: 3 + 5 = 8.
        assertEquals(8L, session.ingestedTokens)

        // One turn: user "next" (4) + assistant reply "world" (5) folded in.
        engine.streamGenerate(
            LLMRequest(messages = listOf(Message.User("next"))),
            session,
        ).toList().also { /* assistant text = concatenation of script tokens */ }

        // FakeBridge default script emits no tokens; assistant content "" → +0,
        // plus the appended user "next" (4). 8 + 4 = 12.
        assertEquals(12L, session.ingestedTokens)
    }

    @Test
    fun session_evictTokenRange_callsSeqRmThenSeqAddAndShrinksIngested() = runTest {
        val bridge = FakeBridge(tokenCountFn = { it.length }, seqRmResult = true)
        val engine = engine(bridge = bridge)
        engine.load(configWith(contextLength = 4096))

        val session = engine.createSession(
            systemInstruction = "system-prompt",      // 13
            initialMessages = listOf(Message.User("0123456789")), // 10
        )
        assertEquals(23L, session.ingestedTokens)

        val editable = session as KvCacheEditableSession
        // Drop 10 tokens starting at logical position 13 (the user message block).
        val ok = editable.evictTokenRange(startToken = 13, tokenCount = 10)

        assertTrue(ok)
        // seq_rm over [13, 23), then seq_add shifting the suffix left by 10.
        assertEquals(13 to 23, bridge.lastSeqRm)
        assertEquals(Triple(23, Int.MAX_VALUE, -10), bridge.lastSeqAdd)
        // ingested shrank by exactly the evicted span.
        assertEquals(13L, session.ingestedTokens)
    }

    @Test
    fun session_evictTokenRange_returnsFalseWhenSeqRmFails() = runTest {
        val bridge = FakeBridge(tokenCountFn = { it.length }, seqRmResult = false)
        val engine = engine(bridge = bridge)
        engine.load(configWith(contextLength = 4096))

        val session = engine.createSession(initialMessages = listOf(Message.User("0123456789")))
        val before = session.ingestedTokens

        val ok = (session as KvCacheEditableSession).evictTokenRange(startToken = 0, tokenCount = 5)

        assertFalse(ok)
        // No seq_add attempted, ingested unchanged.
        assertEquals(null, bridge.lastSeqAdd)
        assertEquals(before, session.ingestedTokens)
    }

    @Test
    fun session_evictTokenRange_rejectsInvalidArgs() = runTest {
        val bridge = FakeBridge(tokenCountFn = { it.length }, seqRmResult = true)
        val engine = engine(bridge = bridge)
        engine.load(configWith(contextLength = 4096))
        val session = engine.createSession(initialMessages = listOf(Message.User("abc"))) as KvCacheEditableSession

        assertFalse(session.evictTokenRange(startToken = 0, tokenCount = 0))
        assertFalse(session.evictTokenRange(startToken = -1, tokenCount = 5))
    }

    // ── release idempotency ──────────────────────────────────────────────────────

    @Test
    fun release_isIdempotent() = runTest {
        val bridge = FakeBridge()
        val engine = engine(bridge = bridge)
        engine.load(configWith(contextLength = 4096))

        engine.release()
        engine.release() // second call must not blow up

        assertEquals(1, bridge.releaseCount) // only the live handle was released
    }

    // ── helpers / fakes ──────────────────────────────────────────────────────────

    /**
     * Build an engine wired to a fake bridge. The production `@JvmOverloads`
     * constructor deliberately hides the `bridge` seam, so tests go through the
     * internal 4-arg constructor; this factory just supplies the CPU defaults.
     */
    private fun engine(
        bridge: LlamaBridge = FakeBridge(),
        nGpuLayers: Int = 0,
        nThreads: Int = 0,
        useVulkan: Boolean = false,
    ): LlamaCppEngine = LlamaCppEngine(
        nGpuLayers = nGpuLayers,
        nThreads = nThreads,
        useVulkan = useVulkan,
        bridge = bridge,
    )

    private fun configWith(contextLength: Int?) = LocalRuntimeConfig(
        modelSource = ModelSource.LocalPath("/tmp/model.gguf"),
        contextLength = contextLength,
    )

    private fun userRequest(text: String) = LLMRequest(messages = listOf(Message.User(text)))

    private fun weatherTool() = ToolDefinition(
        name = "get_weather",
        description = "Get weather by city.",
        arguments = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("city", buildJsonObject { put("type", "string") })
            })
        },
    )

    /** Script describing one generation pass for the fake bridge. */
    private data class GenScript(
        val tokens: List<String> = emptyList(),
        val stopReason: Int = LlamaBridge.NATIVE_STOP_EOS,
        val promptTokens: Int = 1,
        val genTokens: Int = 1,
    )

    /** Pure-JVM [LlamaBridge] — records inputs and replays a scripted pass. */
    private class FakeBridge(
        private val vulkanActive: Boolean = false,
        private val script: GenScript = GenScript(),
        private val tokenCountFn: (String) -> Int = { it.length },
        private val seqRmResult: Boolean = true,
    ) : LlamaBridge {

        var lastNCtx: Int = -1
        var lastNGpuLayers: Int = -1
        var lastNThreads: Int = -1
        var releaseCount: Int = 0
        var createContextCount: Int = 0
        var releaseContextCount: Int = 0
        var lastGenerateHandle: Long = 0L
        var lastSeqRm: Pair<Int, Int>? = null
        var lastSeqAdd: Triple<Int, Int, Int>? = null
        private var nextHandle: Long = 0L

        override fun loadModel(
            modelPath: String,
            nCtxLen: Int,
            nGpuLayers: Int,
            nThreads: Int,
            useVulkan: Boolean,
        ): Long {
            lastNCtx = nCtxLen
            lastNGpuLayers = nGpuLayers
            lastNThreads = nThreads
            return ++nextHandle
        }

        override fun isVulkanActive(handle: Long): Boolean = vulkanActive

        override fun tokenCount(handle: Long, text: String): Int = tokenCountFn(text)

        override fun createContext(handle: Long): Long {
            createContextCount++
            return handle + 100L
        }

        override fun releaseContext(contextHandle: Long) {
            releaseContextCount++
        }

        override fun seqRm(contextHandle: Long, p0: Int, p1: Int): Boolean {
            lastSeqRm = p0 to p1
            return seqRmResult
        }

        override fun seqAdd(contextHandle: Long, p0: Int, p1: Int, delta: Int) {
            lastSeqAdd = Triple(p0, p1, delta)
        }

        override fun generate(
            handle: Long,
            prompt: String,
            temperature: Float,
            topP: Float,
            topK: Int,
            maxTokens: Int,
            stopWords: Array<String>,
            sink: LlamaBridge.TokenSink,
        ) {
            lastGenerateHandle = handle
            for (t in script.tokens) {
                if (sink.isAborted()) {
                    sink.onStop(LlamaBridge.NATIVE_STOP_ABORT, script.promptTokens, script.genTokens)
                    return
                }
                sink.onToken(t)
            }
            sink.onStop(script.stopReason, script.promptTokens, script.genTokens)
        }

        override fun release(handle: Long) {
            releaseCount++
        }
    }
}
