package com.ailux.provider.local

import com.ailux.core.config.LocalRuntimeConfig
import com.ailux.core.message.Message
import com.ailux.core.request.LLMRequest
import com.ailux.core.session.SessionConfig
import com.ailux.runtime.EngineCapabilities
import com.ailux.runtime.EngineEvent
import com.ailux.runtime.EngineSession
import com.ailux.runtime.EngineStopReason
import com.ailux.runtime.GpuBackend
import com.ailux.runtime.InferenceEngine
import com.ailux.runtime.KvCacheEditableSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADR-0010 acceptance: a long native-KV conversation that tips over the window
 * budget must be trimmed by the Provider adapter — keeping the system prompt and
 * the most-recent turns while dropping the middle — instead of degenerating into
 * a no-op (the bug ADR-0010 fixes).
 *
 * Two engine tiers are exercised through the real
 * [LocalEngineSessionAdapter.streamGenerate] path:
 *
 *  - **Tier 2** (`supportsKvCacheEdit = false`): on tip-over the adapter must
 *    `close()` the native session and rebuild it (close + replay) from the
 *    trimmed logical history — system prompt preserved, middle dropped.
 *  - **Tier 1** (`supportsKvCacheEdit = true` + [KvCacheEditableSession]): the
 *    adapter must instead drive an in-place `evictTokenRange` and NOT rebuild.
 *
 * Token accounting uses `sizeInTokens = text.length` so budgets are exact.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalEngineSessionAdapterWindowGovernanceTest {

    // Each message is 10 chars => 10 tokens. Budget 45 => after several turns the
    // logical history exceeds budget and a trim must fire.
    private fun msg10(role: String, n: Int): Message = when (role) {
        "sys" -> Message.System("S".repeat(10))
        "u" -> Message.User("U$n".padEnd(10, 'u'))
        "a" -> Message.Assistant("A$n".padEnd(10, 'a'))
        else -> error("role")
    }

    @Test
    fun tier2_tipOver_closesAndRebuildsWithTrimmedHistory() = runTest {
        val engine = FakeSessionEngine(supportsKvCacheEdit = false)
        val seed = listOf(
            msg10("sys", 0),  // system — must survive
            msg10("u", 1), msg10("a", 1),
            msg10("u", 2), msg10("a", 2),
            msg10("u", 3), msg10("a", 3),
        ) // 7 * 10 = 70 tokens already seeded, well over budget
        val firstSession = engine.openLike(seed)

        val adapter = LocalEngineSessionAdapter(
            engineSession = firstSession,
            engine = engine,
            config = SessionConfig(systemInstruction = "SSSSSSSSSS", initialMessages = seed.drop(1)),
            contextManager = NativeWindowContextManager(EngineTokenCounter(engine)),
            windowBudgetTokens = 45,
        )

        // New turn pushes the window further over budget → trim must fire.
        val events = adapter.streamGenerate(
            LLMRequest(messages = listOf(msg10("u", 4))),
        ).toList()

        // Tier 2: the engine rebuilt a session (createSession called again).
        assertTrue("expected a rebuild on tip-over", engine.createSessionCount.get() >= 2)
        // The old native session was closed.
        assertTrue("old session must be closed after rebuild", firstSession.closed)

        // The rebuilt session was seeded with a trimmed history that still leads
        // with the system prompt (never dropped) and is within budget-ish.
        val rebuiltSeed = engine.lastCreateSessionMessages
        assertTrue("system instruction must be preserved", engine.lastSystemInstruction == "SSSSSSSSSS")
        // Middle turns were dropped → fewer messages than the original tail.
        assertTrue("middle should be trimmed", rebuiltSeed.size < seed.drop(1).size + 1)

        // Stream still produces a clean envelope.
        assertTrue(events.any { it is com.ailux.core.event.LLMEvent.Token })
        assertNull(engine.lastEvictCall) // no KV edit on Tier 2
    }

    @Test
    fun tier1_tipOver_editsKvInPlaceWithoutRebuild() = runTest {
        val engine = FakeSessionEngine(supportsKvCacheEdit = true)
        val seed = listOf(
            msg10("sys", 0),
            msg10("u", 1), msg10("a", 1),
            msg10("u", 2), msg10("a", 2),
            msg10("u", 3), msg10("a", 3),
        )
        val firstSession = engine.openLike(seed)

        val adapter = LocalEngineSessionAdapter(
            engineSession = firstSession,
            engine = engine,
            config = SessionConfig(systemInstruction = "SSSSSSSSSS", initialMessages = seed.drop(1)),
            contextManager = NativeWindowContextManager(EngineTokenCounter(engine)),
            windowBudgetTokens = 45,
        )

        adapter.streamGenerate(LLMRequest(messages = listOf(msg10("u", 4)))).toList()

        // Tier 1: an in-place KV edit happened, NO rebuild.
        assertEquals("must NOT rebuild on Tier 1", 1, engine.createSessionCount.get())
        assertFalse("old session must stay open on Tier 1", firstSession.closed)
        val evict = engine.lastEvictCall
        assertTrue("expected an in-place evictTokenRange call", evict != null)
        // The evicted block starts after the system prefix (10 tokens) and drops
        // a positive number of middle tokens.
        assertEquals(10, evict!!.first)
        assertTrue("must drop a positive token span", evict.second > 0)
    }

    @Test
    fun withinBudget_noTrimNoRebuild() = runTest {
        val engine = FakeSessionEngine(supportsKvCacheEdit = false)
        val seed = listOf(msg10("sys", 0), msg10("u", 1))
        val firstSession = engine.openLike(seed)

        val adapter = LocalEngineSessionAdapter(
            engineSession = firstSession,
            engine = engine,
            config = SessionConfig(),
            contextManager = NativeWindowContextManager(),
            windowBudgetTokens = 10_000, // huge → never trips
        )

        adapter.streamGenerate(LLMRequest(messages = listOf(msg10("u", 2)))).toList()

        assertEquals("no rebuild when within budget", 1, engine.createSessionCount.get())
        assertFalse(firstSession.closed)
        assertNull(engine.lastEvictCall)
    }

    @Test
    fun governanceDisabled_whenNoContextManager_passesThrough() = runTest {
        val engine = FakeSessionEngine(supportsKvCacheEdit = false)
        val seed = (0..10).map { msg10("u", it) }
        val firstSession = engine.openLike(seed)

        val adapter = LocalEngineSessionAdapter(
            engineSession = firstSession,
            engine = engine,
            config = SessionConfig(),
            // contextManager = null (default) → governance off
            windowBudgetTokens = 5,
        )

        adapter.streamGenerate(LLMRequest(messages = listOf(msg10("u", 99)))).toList()

        assertEquals("disabled governance must not rebuild", 1, engine.createSessionCount.get())
        assertNull(engine.lastEvictCall)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test doubles
    // ────────────────────────────────────────────────────────────────────────

    private class FakeSessionEngine(
        private val supportsKvCacheEdit: Boolean,
    ) : InferenceEngine {

        val createSessionCount = AtomicInteger(0)
        @Volatile var lastCreateSessionMessages: List<Message> = emptyList()
        @Volatile var lastSystemInstruction: String? = null
        @Volatile var lastEvictCall: Pair<Int, Int>? = null

        private val caps = EngineCapabilities(
            supportAbis = setOf("arm64-v8a"),
            estimatedRamMb = 128,
            gpuBackend = GpuBackend.NONE,
            supportsTools = false,
            supportsInterruptibleCancellation = false,
            supportedModelExtensions = setOf("test"),
            maxConcurrentSessions = 1,
            supportsBatchedIngest = true,
            supportsKvCacheEdit = supportsKvCacheEdit,
            supportsContextShift = false,
        )

        override suspend fun load(config: LocalRuntimeConfig) = Unit
        override fun release() = Unit
        override fun capabilities(): EngineCapabilities = caps
        override fun sizeInTokens(text: String): Int = text.length
        override val supportsSessions: Boolean = true

        /** Helper mirroring the provider's createSession for an initial seed. */
        fun openLike(seed: List<Message>): FakeSession {
            val sys = (seed.firstOrNull() as? Message.System)?.content
            return createSession(sys, seed.filterNot { it is Message.System }) as FakeSession
        }

        override fun createSession(
            systemInstruction: String?,
            initialMessages: List<Message>,
        ): EngineSession {
            createSessionCount.incrementAndGet()
            lastSystemInstruction = systemInstruction
            lastCreateSessionMessages = initialMessages
            val seeded = buildList {
                systemInstruction?.let { add(Message.System(it)) }
                addAll(initialMessages)
            }
            return FakeSession(
                supportsKvCacheEdit = supportsKvCacheEdit,
                seededTokens = seeded.sumOf { sizeInTokens(textOf(it)) }.toLong(),
                onEvict = { start, count -> lastEvictCall = start to count },
            )
        }

        override fun streamGenerate(request: LLMRequest): Flow<EngineEvent> =
            throw UnsupportedOperationException("session-only")

        override fun streamGenerate(
            request: LLMRequest,
            session: EngineSession,
        ): Flow<EngineEvent> = flow {
            emit(EngineEvent.Token("ok"))
            emit(EngineEvent.Stop(EngineStopReason.EOS))
        }

        private fun textOf(m: Message): String = when (m) {
            is Message.System -> m.content
            is Message.User -> m.content
            is Message.Assistant -> m.content ?: ""
            is Message.Tool -> m.content
        }
    }

    private class FakeSession(
        private val supportsKvCacheEdit: Boolean,
        seededTokens: Long,
        private val onEvict: (Int, Int) -> Unit,
    ) : EngineSession, KvCacheEditableSession {
        override val sessionId: String = "fake-${System.nanoTime()}"
        override val approximateMemoryBytes: Long = -1L
        override val hasCachedPrefix: Boolean = true
        @Volatile var closed: Boolean = false
        private var ingested: Long = seededTokens
        override val ingestedTokens: Long get() = ingested

        override fun evictTokenRange(startToken: Int, tokenCount: Int): Boolean {
            if (!supportsKvCacheEdit) return false
            if (tokenCount <= 0 || startToken < 0) return false
            onEvict(startToken, tokenCount)
            ingested = (ingested - tokenCount).coerceAtLeast(0L)
            return true
        }

        override fun close() { closed = true }
    }
}
