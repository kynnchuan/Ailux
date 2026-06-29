package com.ailux.runtime.llamacpp

import com.ailux.core.config.LocalRuntimeConfig
import com.ailux.core.config.ModelSource
import com.ailux.core.message.Message
import com.ailux.core.request.LLMRequest
import com.ailux.runtime.EngineEvent
import com.ailux.runtime.EngineStopReason
import com.ailux.runtime.GpuBackend
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
        // llama.cpp's defining advantage over LiteRT-LM: real mid-token cancel.
        assertTrue(caps.supportsInterruptibleCancellation)
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
    fun buildPrompt_rolesTaggedAndPrimesAssistant() {
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
        assertTrue(prompt.contains("System: be brief"))
        assertTrue(prompt.contains("User: hi"))
        assertTrue(prompt.contains("Assistant: hello"))
        assertTrue(prompt.contains("User: bye"))
        assertTrue(prompt.trimEnd().endsWith("Assistant:"))
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
    ) : LlamaBridge {

        var lastNCtx: Int = -1
        var lastNGpuLayers: Int = -1
        var lastNThreads: Int = -1
        var releaseCount: Int = 0
        private var nextHandle: Long = 0L

        override fun loadModel(
            modelPath: String,
            nCtx: Int,
            nGpuLayers: Int,
            nThreads: Int,
            useVulkan: Boolean,
        ): Long {
            lastNCtx = nCtx
            lastNGpuLayers = nGpuLayers
            lastNThreads = nThreads
            return ++nextHandle
        }

        override fun isVulkanActive(handle: Long): Boolean = vulkanActive

        override fun tokenCount(handle: Long, text: String): Int = tokenCountFn(text)

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
