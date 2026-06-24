package com.ailux.provider.local

import com.ailux.core.config.LocalRuntimeConfig
import com.ailux.core.event.LLMEvent
import com.ailux.core.message.Message
import com.ailux.core.request.LLMRequest
import com.ailux.core.session.SessionConfig
import com.ailux.runtime.EngineCapabilities
import com.ailux.runtime.EngineEvent
import com.ailux.runtime.EngineSession
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies the contract introduced for [EngineCapabilities.supportsBatchedIngest]:
 *
 * - `false` engines (LiteRT-LM 0.13.x-class): a turn containing
 *   `[User, Tool, Tool]` MUST trigger the engine's generation path exactly
 *   **once**, and the request the engine sees MUST contain a single,
 *   merged message — i.e. the native KV-cache cannot be polluted by N-1
 *   wasted middle generations.
 *
 * - `true` engines (llama.cpp-class with a real prefill-only API): the turn
 *   is forwarded as-is — all 3 messages reach the engine with their
 *   individual role boundaries preserved, and the engine is still invoked
 *   exactly once (we do NOT pre-split the request from the adapter).
 *
 * Both branches exercise [LocalEngineSessionAdapter.streamGenerate] end-to-end,
 * not the helper in isolation, so the wiring (capability lookup + request
 * rewriting + engine dispatch) is what gets covered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalEngineSessionAdapterBatchedIngestTest {

    @Test
    fun nonBatchedEngine_collapsesTurnAndCallsOnce() = runTest {
        val engine = RecordingEngine(supportsBatchedIngest = false)
        val adapter = LocalEngineSessionAdapter(
            engineSession = NoopEngineSession(),
            engine = engine,
            config = SessionConfig(),
        )

        val turn = listOf(
            Message.User("Summarise the two weather reports."),
            Message.Tool(toolCallId = "call-1", content = "{\"city\":\"Beijing\",\"t\":\"22C\"}"),
            Message.Tool(toolCallId = "call-2", content = "{\"city\":\"Shanghai\",\"t\":\"25C\"}"),
        )

        val events = adapter.streamGenerate(LLMRequest(messages = turn)).toList()

        // Engine generation must run exactly once — no n-1 phantom passes.
        assertEquals(1, engine.invocationCount.get())

        // The request that hit the engine must carry a single, merged message.
        val seen = engine.lastRequest!!
        assertEquals(1, seen.messages.size)

        val merged = seen.messages.single()
        // The carrier role follows the LAST turn message (here: Tool) so the
        // model still sees the correct role boundary for synthesis.
        assertTrue("expected merged carrier to be Message.Tool", merged is Message.Tool)
        val mergedText = (merged as Message.Tool).content

        // Source segments must remain inspectable for diagnostics.
        assertTrue("user segment marker missing", mergedText.contains("[user]"))
        assertTrue("first tool marker missing", mergedText.contains("[tool:call-1]"))
        assertTrue("second tool marker missing", mergedText.contains("[tool:call-2]"))
        assertTrue("user content missing", mergedText.contains("Summarise the two weather reports."))
        assertTrue("call-1 content missing", mergedText.contains("Beijing"))
        assertTrue("call-2 content missing", mergedText.contains("Shanghai"))

        // Sanity: the stream still emits Token + Done so callers behave the same.
        val tokens = events.filterIsInstance<LLMEvent.Token>()
        val done = events.filterIsInstance<LLMEvent.Done>().lastOrNull()
        assertTrue("expected at least one token", tokens.isNotEmpty())
        assertNotNull("expected terminal Done event", done)
    }

    @Test
    fun batchedIngestEngine_forwardsTurnAsIsAndCallsOnce() = runTest {
        val engine = RecordingEngine(supportsBatchedIngest = true)
        val adapter = LocalEngineSessionAdapter(
            engineSession = NoopEngineSession(),
            engine = engine,
            config = SessionConfig(),
        )

        val turn = listOf(
            Message.User("Pick the warmer city."),
            Message.Tool(toolCallId = "call-1", content = "{\"city\":\"Beijing\",\"t\":\"22C\"}"),
            Message.Tool(toolCallId = "call-2", content = "{\"city\":\"Shanghai\",\"t\":\"25C\"}"),
        )

        val events = adapter.streamGenerate(LLMRequest(messages = turn)).toList()

        // Still exactly one engine invocation — we do not pre-split.
        assertEquals(1, engine.invocationCount.get())

        // But all three messages must reach the engine with their roles intact —
        // the engine is responsible for ingesting them via its prefill-only API
        // and only sampling on the last one.
        val seen = engine.lastRequest!!
        assertEquals(3, seen.messages.size)
        assertEquals(Message.User("Pick the warmer city."), seen.messages[0])
        assertTrue(seen.messages[1] is Message.Tool && (seen.messages[1] as Message.Tool).toolCallId == "call-1")
        assertTrue(seen.messages[2] is Message.Tool && (seen.messages[2] as Message.Tool).toolCallId == "call-2")

        // And again — clean Token + Done envelope.
        assertTrue(events.filterIsInstance<LLMEvent.Token>().isNotEmpty())
        assertNotNull(events.filterIsInstance<LLMEvent.Done>().lastOrNull())
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test doubles
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Records how many times `streamGenerate(request, session)` is called and
     * captures the last request seen, so tests can assert the engine sees
     * exactly one logical generation per turn.
     */
    private class RecordingEngine(
        private val supportsBatchedIngest: Boolean,
    ) : InferenceEngine {

        val invocationCount = AtomicInteger(0)
        @Volatile
        var lastRequest: LLMRequest? = null

        private val caps = EngineCapabilities(
            supportAbis = setOf("arm64-v8a"),
            estimatedRamMb = 256,
            gpuBackend = GpuBackend.NONE,
            supportsTools = true,
            supportsInterruptibleCancellation = false,
            supportsModelExtensions = setOf("test"),
            maxConcurrentSessions = 1,
            supportsBatchedIngest = supportsBatchedIngest,
        )

        override suspend fun load(config: LocalRuntimeConfig) = Unit
        override fun release() = Unit
        override fun capabilities(): EngineCapabilities = caps
        override fun sizeInTokens(text: String): Int = text.length
        override val supportsSessions: Boolean = true

        override fun streamGenerate(request: LLMRequest): Flow<EngineEvent> =
            throw UnsupportedOperationException("session-only test engine")

        override fun streamGenerate(
            request: LLMRequest,
            session: EngineSession,
        ): Flow<EngineEvent> = flow {
            invocationCount.incrementAndGet()
            lastRequest = request
            emit(EngineEvent.Token("ok"))
            emit(EngineEvent.Stop(EngineStopReason.EOS))
        }
    }

    private class NoopEngineSession : EngineSession {
        override val sessionId: String = "test-session"
        override val approximateMemoryBytes: Long = -1L
        override val hasCachedPrefix: Boolean = false
        override fun close() = Unit
    }
}
