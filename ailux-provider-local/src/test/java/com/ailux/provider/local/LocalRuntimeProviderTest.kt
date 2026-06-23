package com.ailux.provider.local

import com.ailux.core.config.LocalRuntimeConfig
import com.ailux.core.config.ModelSource
import com.ailux.core.error.ErrorCode
import com.ailux.core.event.FinishReason
import com.ailux.core.event.LLMEvent
import com.ailux.core.message.Message
import com.ailux.core.request.LLMRequest
import com.ailux.core.response.UsageInfo
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage of [LocalRuntimeProvider]'s normalization rules — the parts
 * that don't need Android `Context`. Cold-load / DeviceProbe paths are exercised
 * separately under `androidTest`.
 *
 * Spec §6.1 (steps 3 — event normalization, 4 — exact Usage, 5 — finishReason
 * translation) + spec §5.1 (R1/R2) red lines.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalRuntimeProviderTest {

    // ── Step 5: finishReason translation ────────────────────────────────────

    @Test
    fun translateFinishReason_eos_mapsToComplete() {
        val provider = providerWithoutContext()
        assertEquals(FinishReason.COMPLETE, provider.translateFinishReason(EngineStopReason.EOS, 5, 100))
    }

    @Test
    fun translateFinishReason_stopWord_mapsToComplete() {
        // Per FinishReason table: OpenAI "stop" / Anthropic "stop_sequence" both → COMPLETE.
        val provider = providerWithoutContext()
        assertEquals(FinishReason.COMPLETE, provider.translateFinishReason(EngineStopReason.STOP_WORD, 5, 100))
    }

    @Test
    fun translateFinishReason_length_mapsToLength() {
        val provider = providerWithoutContext()
        assertEquals(FinishReason.LENGTH, provider.translateFinishReason(EngineStopReason.LENGTH, 100, 100))
    }

    @Test
    fun translateFinishReason_unknownAtCap_workaroundLength() {
        val provider = providerWithoutContext()
        assertEquals(FinishReason.LENGTH, provider.translateFinishReason(EngineStopReason.UNKNOWN, 100, 100))
    }

    @Test
    fun translateFinishReason_unknownBelowCap_workaroundComplete() {
        val provider = providerWithoutContext()
        assertEquals(FinishReason.COMPLETE, provider.translateFinishReason(EngineStopReason.UNKNOWN, 50, 100))
    }

    @Test
    fun translateFinishReason_unknownNoCap_workaroundComplete() {
        // maxTokens=null → can never overflow, so workaround should land on COMPLETE.
        val provider = providerWithoutContext()
        assertEquals(FinishReason.COMPLETE, provider.translateFinishReason(EngineStopReason.UNKNOWN, 999, null))
    }

    @Test
    fun translateFinishReason_nullStop_workaroundComplete() {
        // Engine never emitted a Stop event → still must produce a Done.
        val provider = providerWithoutContext()
        assertEquals(FinishReason.COMPLETE, provider.translateFinishReason(null, 1, 100))
    }

    // ── Step 6: error mapping for native exceptions ─────────────────────────

    @Test
    fun mapLocalError_oomMessage_mapsInsufficientMemory() {
        val provider = providerWithoutContext()
        val err = provider.mapLocalError(RuntimeException("native OOM detected"))
        assertEquals(ErrorCode.INSUFFICIENT_MEMORY, err.code)
    }

    @Test
    fun mapLocalError_abiMessage_mapsUnsupportedAbi() {
        val provider = providerWithoutContext()
        val err = provider.mapLocalError(RuntimeException("incompatible ABI armeabi-v7a"))
        assertEquals(ErrorCode.UNSUPPORTED_ABI, err.code)
    }

    @Test
    fun mapLocalError_unknownThrowable_mapsModelLoadFailed() {
        val provider = providerWithoutContext()
        val err = provider.mapLocalError(IllegalStateException("session not initialized"))
        assertEquals(ErrorCode.MODEL_LOAD_FAILED, err.code)
    }

    @Test
    fun mapLocalError_localRuntimeException_preservesCode() {
        val provider = providerWithoutContext()
        val err = provider.mapLocalError(
            LocalRuntimeException(ErrorCode.MODEL_FILE_INVALID, "hash mismatch")
        )
        assertEquals(ErrorCode.MODEL_FILE_INVALID, err.code)
    }

    // ── Step 7: capabilities bubble-up ──────────────────────────────────────

    @Test
    fun buildProviderCapabilities_bubblesEngineFlags() {
        val provider = providerWithoutContext()
        val pc = provider.buildProviderCapabilities(
            EngineCapabilities(
                supportAbis = setOf("arm64-v8a"),
                estimatedRamMb = 2048,
                gpuBackend = GpuBackend.VULKAN,
                supportsTools = true,
                supportsInterruptibleCancellation = true,
                supportsModelExtensions = setOf("gguf"),
            )
        )
        assertEquals(true, pc.supportsTool)
        assertEquals(true, pc.supportsStream)
        assertEquals(false, pc.supportsVision)
        assertEquals(true, pc.supportsInterruptibleCancellation)
    }

    // ── Smoke for buildProviderCapabilities visibility of the `false` path ──

    @Test
    fun buildProviderCapabilities_uninterruptibleEngineSurfacesFalse() {
        val provider = providerWithoutContext()
        val pc = provider.buildProviderCapabilities(litertlmLikeCaps())
        assertEquals(false, pc.supportsInterruptibleCancellation)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Builds a provider that never touches Android `Context`. We only exercise the
     * pure translation / mapping helpers; cold-load + `DeviceProbe` paths run in
     * `androidTest` where `ActivityManager` is available.
     */
    private fun providerWithoutContext(): LocalRuntimeProvider = LocalRuntimeProvider(
        appContext = null,
        config = LocalRuntimeConfig(modelSource = ModelSource.LocalPath("/tmp/fake.bin")),
        engine = FakeEngine(litertlmLikeCaps()),
    )

    private fun litertlmLikeCaps() = EngineCapabilities(
        supportAbis = setOf("arm64-v8a"),
        estimatedRamMb = 1024,
        gpuBackend = GpuBackend.GPU_DELEGATE,
        supportsTools = true,
        supportsInterruptibleCancellation = false, // LiteRT-LM cannot cancel mid-generation.
        supportsModelExtensions = setOf("litertlm", "task"),
    )

    /** FakeEngine that we can program with a script — used in normalization tests below if needed. */
    private class FakeEngine(
        private val caps: EngineCapabilities,
        private val script: List<EngineEvent> = emptyList(),
    ) : InferenceEngine {
        override suspend fun load(config: LocalRuntimeConfig) = Unit
        override fun streamGenerate(request: LLMRequest): Flow<EngineEvent> = flow {
            script.forEach { emit(it) }
        }
        override fun release() = Unit
        override fun capabilities(): EngineCapabilities = caps
        override fun sizeInTokens(text: String): Int = text.length
    }
}
