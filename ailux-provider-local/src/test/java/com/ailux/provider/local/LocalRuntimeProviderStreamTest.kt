package com.ailux.provider.local

import com.ailux.core.config.LocalRuntimeConfig
import com.ailux.core.config.ModelSource
import com.ailux.core.error.ErrorCode
import com.ailux.core.event.FinishReason
import com.ailux.core.event.LLMEvent
import com.ailux.core.message.Message
import com.ailux.core.request.LLMRequest
import com.ailux.runtime.EngineCapabilities
import com.ailux.runtime.EngineEvent
import com.ailux.runtime.EngineStopReason
import com.ailux.runtime.GpuBackend
import com.ailux.runtime.InferenceEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Streaming end-to-end coverage of [LocalRuntimeProvider] using a `FakeEngine`.
 *
 * We bypass [com.ailux.provider.local.device.DeviceProbe] (no Android `Context`
 * available in this JVM-only sourceSet) by pre-loading the engine via reflection
 * on the `loaded` flag. That lets us focus on Steps 3–5 (normalization, exact
 * Usage, finishReason translation) and Step 6 (mid-stream error mapping).
 *
 * Spec §6.1 (steps 3 / 4 / 5 / 6).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalRuntimeProviderStreamTest {

    @Test
    fun normalize_tokensThenNativeUsageThenStop_emitsTokenUsageDoneInOrder() = runTest {
        val engine = FakeEngine(
            script = listOf(
                EngineEvent.Token("Hello"),
                EngineEvent.Token(", world!"),
                EngineEvent.Usage(promptTokens = 5, genTokens = 3),
                EngineEvent.Stop(EngineStopReason.EOS),
            ),
        )
        val provider = newPreloadedProvider(engine)

        val events = provider.streamGenerate(simpleRequest()).toList()

        // Order: Token, Token, Usage, Done(COMPLETE)
        assertEquals(4, events.size)
        assertEquals(LLMEvent.Token("Hello"), events[0])
        assertEquals(LLMEvent.Token(", world!"), events[1])
        val usage = (events[2] as LLMEvent.Usage).info
        assertEquals(5, usage.inputTokens)
        assertEquals(3, usage.outputTokens)
        // Spec §6.1.4: on-device usage is exact, never estimated.
        assertEquals(false, usage.estimated)
        assertEquals(FinishReason.COMPLETE, (events[3] as LLMEvent.Done).finishReason)
    }

    @Test
    fun normalize_noNativeUsage_fallsBackToSizeInTokens() = runTest {
        val engine = FakeEngine(
            script = listOf(
                EngineEvent.Token("ab"),
                EngineEvent.Token("cde"),
                EngineEvent.Stop(EngineStopReason.EOS),
            ),
            // sizeInTokens uses string length in our fake — convenient for asserts.
        )
        val provider = newPreloadedProvider(engine)

        val events = provider.streamGenerate(simpleRequest("hi")).toList()

        val usage = events.filterIsInstance<LLMEvent.Usage>().single().info
        // Prompt = "hi" → length 2; concat tokens "abcde" → length 5.
        assertEquals(2, usage.inputTokens)
        assertEquals(5, usage.outputTokens)
        assertEquals(false, usage.estimated)
    }

    @Test
    fun normalize_lengthStop_emitsDoneLength() = runTest {
        val engine = FakeEngine(
            script = listOf(
                EngineEvent.Token("x"),
                EngineEvent.Stop(EngineStopReason.LENGTH),
            ),
        )
        val provider = newPreloadedProvider(engine)

        val done = provider.streamGenerate(simpleRequest()).toList()
            .filterIsInstance<LLMEvent.Done>().single()
        assertEquals(FinishReason.LENGTH, done.finishReason)
    }

    @Test
    fun normalize_unknownStopAtCap_workaroundLength() = runTest {
        val engine = FakeEngine(
            script = listOf(
                EngineEvent.Token("a"),
                EngineEvent.Token("b"),
                EngineEvent.Stop(EngineStopReason.UNKNOWN),
            ),
        )
        val provider = newPreloadedProvider(engine)

        val done = provider.streamGenerate(simpleRequest(maxTokens = 2)).toList()
            .filterIsInstance<LLMEvent.Done>().single()
        // Output reached the cap → workaround maps UNKNOWN to LENGTH.
        assertEquals(FinishReason.LENGTH, done.finishReason)
    }

    @Test
    fun midStream_throw_keepsAlreadyEmittedTokens_andReportsError() = runTest {
        val engine = FakeEngine(
            script = listOf(
                EngineEvent.Token("partial"),
                // After this, the engine throws — simulating a native failure mid-stream.
            ),
            failAfter = RuntimeException("native crashed"),
        )
        val provider = newPreloadedProvider(engine)

        val events = provider.streamGenerate(simpleRequest()).toList()
        // Spec §6.1.6: partial tokens preserved → Error → Done(ERROR).
        assertEquals(LLMEvent.Token("partial"), events[0])
        val err = events.filterIsInstance<LLMEvent.Error>().single().error
        assertEquals(ErrorCode.MODEL_LOAD_FAILED, err.code)
        val done = events.filterIsInstance<LLMEvent.Done>().single()
        assertEquals(FinishReason.ERROR, done.finishReason)
    }

    @Test
    fun midStream_oom_mapsToInsufficientMemory() = runTest {
        val engine = FakeEngine(
            script = listOf(EngineEvent.Token("p")),
            failAfter = OutOfMemoryError("synthetic"),
        )
        val provider = newPreloadedProvider(engine)

        val events = provider.streamGenerate(simpleRequest()).toList()
        val err = events.filterIsInstance<LLMEvent.Error>().single().error
        assertEquals(ErrorCode.INSUFFICIENT_MEMORY, err.code)
        assertEquals(FinishReason.ERROR, (events.last() as LLMEvent.Done).finishReason)
    }

    @Test
    fun nonStreaming_generate_aggregatesTokensAndUsage() = runTest {
        val engine = FakeEngine(
            script = listOf(
                EngineEvent.Token("A"),
                EngineEvent.Token("B"),
                EngineEvent.Token("C"),
                EngineEvent.Usage(promptTokens = 1, genTokens = 3),
                EngineEvent.Stop(EngineStopReason.EOS),
            ),
        )
        val provider = newPreloadedProvider(engine)

        val response = provider.generate(simpleRequest())
        assertEquals("ABC", response.text)
        assertNotNull(response.usage)
        assertEquals(3, response.usage!!.outputTokens)
        assertEquals(false, response.usage!!.estimated)
    }

    // ── Issue 4: engine-level runaway guard plumbed via LocalRuntimeConfig.maxOutputTokens ──

    @Test
    fun coldLoad_forwardsMaxOutputTokensToEngine() = runTest {
        val engine = FakeEngine(
            script = listOf(
                EngineEvent.Token("ok"),
                EngineEvent.Stop(EngineStopReason.EOS),
            ),
        )
        val provider = LocalRuntimeProvider(
            appContext = null, // skips DeviceProbe entirely
            config = LocalRuntimeConfig(
                modelSource = ModelSource.LocalPath("/tmp/fake.bin"),
                maxOutputTokens = 1024,
            ),
            engine = engine,
        )
        // Trigger cold-load via streamGenerate; we don't care about events here.
        provider.streamGenerate(simpleRequest()).toList()
        assertEquals(1024, engine.lastLoadedConfig?.maxOutputTokens)
    }

    @Test
    fun coldLoad_maxOutputTokensDefaultsToNull() = runTest {
        val engine = FakeEngine(
            script = listOf(
                EngineEvent.Token("ok"),
                EngineEvent.Stop(EngineStopReason.EOS),
            ),
        )
        val provider = LocalRuntimeProvider(
            appContext = null,
            config = LocalRuntimeConfig(modelSource = ModelSource.LocalPath("/tmp/fake.bin")),
            engine = engine,
        )
        provider.streamGenerate(simpleRequest()).toList()
        // Caller opted out — engine-level cap stays null and the engine is responsible
        // for whatever its own default behaviour is.
        assertNull(engine.lastLoadedConfig?.maxOutputTokens)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Builds a provider with the `loaded` flag pre-flipped to true via reflection,
     * skipping the cold-load path that needs Android `Context`.
     */
    private fun newPreloadedProvider(engine: FakeEngine): LocalRuntimeProvider {
        val provider = LocalRuntimeProvider(
            appContext = null,
            config = LocalRuntimeConfig(modelSource = ModelSource.LocalPath("/tmp/fake.bin")),
            engine = engine,
        )
        // Skip cold-load — DeviceProbe needs ActivityManager which only Android instrumented tests have.
        val field = LocalRuntimeProvider::class.java.getDeclaredField("loaded").apply { isAccessible = true }
        field.setBoolean(provider, true)
        return provider
    }

    private fun simpleRequest(prompt: String = "x", maxTokens: Int? = null) = LLMRequest(
        messages = listOf(Message.User(prompt)),
        maxTokens = maxTokens,
    )

    /** Programmable engine: scripted events, optional terminal failure. */
    private class FakeEngine(
        private val script: List<EngineEvent>,
        private val failAfter: Throwable? = null,
    ) : InferenceEngine {

        private val caps = EngineCapabilities(
            supportAbis = setOf("arm64-v8a"),
            estimatedRamMb = 1024,
            gpuBackend = GpuBackend.GPU_DELEGATE,
            supportsTools = true,
            supportsInterruptibleCancellation = false,
            supportedModelExtensions = setOf("litertlm", "task"),
        )

        @Volatile
        var lastLoadedConfig: LocalRuntimeConfig? = null
            private set

        override suspend fun load(config: LocalRuntimeConfig) {
            lastLoadedConfig = config
        }

        override fun streamGenerate(request: LLMRequest): Flow<EngineEvent> = flow {
            script.forEach { emit(it) }
            failAfter?.let { throw it }
        }

        override fun release() = Unit
        override fun capabilities(): EngineCapabilities = caps
        override fun sizeInTokens(text: String): Int = text.length
    }
}
