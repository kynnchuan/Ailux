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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression for the v0.3 bug where [LocalEngineSessionAdapter.snapshot] used
 * `synchronized(history)` while [LocalEngineSessionAdapter.streamGenerate] used
 * a coroutine `Mutex` to guard the same list — two independent primitives that
 * don't block each other, racing the snapshot copy with the in-flight append.
 *
 * After the fix (a single `ReentrantLock` for history reads/writes), high-
 * frequency snapshot must coexist with high-frequency streamGenerate without
 * ever throwing `ConcurrentModificationException`, and every snapshot must
 * satisfy the "coherent prefix" invariant: optional System row → then strictly
 * alternating User / Assistant.
 *
 * Uses real threads rather than `runTest` because the race we're testing is
 * genuine multi-thread interleaving, not virtual time.
 */
class LocalEngineSessionAdapterSnapshotRaceTest {

    @Test
    fun snapshotConcurrentWithStreamGenerate_neverThrows() {
        val engine = StreamingEngine()
        val adapter = LocalEngineSessionAdapter(
            engineSession = NoopEngineSession(),
            engine = engine,
            config = SessionConfig(),
        )

        val producer = Thread {
            runBlocking {
                repeat(40) { i ->
                    adapter.streamGenerate(
                        LLMRequest(messages = listOf(Message.User("u$i")))
                    ).toList()
                }
            }
        }

        val snapshotErrors = ConcurrentLinkedQueue<Throwable>()
        val snapshotThreads = (0 until 4).map { tid ->
            Thread {
                repeat(2_000) {
                    try {
                        val snap = adapter.snapshot()
                        // Coherent-prefix invariant: skip optional System, then
                        // messages must strictly alternate User / Assistant.
                        var idx = 0
                        if (snap.messages.firstOrNull() is Message.System) idx = 1
                        var expectUser = true
                        while (idx < snap.messages.size) {
                            val m = snap.messages[idx]
                            val ok = (expectUser && m is Message.User) ||
                                    (!expectUser && m is Message.Assistant)
                            if (!ok) {
                                throw AssertionError(
                                    "snapshot at idx=$idx not coherent: $m " +
                                        "(thread $tid, snap size=${snap.messages.size})"
                                )
                            }
                            expectUser = !expectUser
                            idx++
                        }
                    } catch (t: Throwable) {
                        snapshotErrors.add(t)
                    }
                }
            }
        }

        producer.start()
        snapshotThreads.forEach { it.start() }
        producer.join()
        snapshotThreads.forEach { it.join() }

        if (snapshotErrors.isNotEmpty()) {
            throw AssertionError(
                "snapshot raced with streamGenerate (${snapshotErrors.size} errors): " +
                    "first=${snapshotErrors.first()}"
            )
        }
    }

    /**
     * Emits a stream of tokens with `yield()` between each so snapshot threads
     * can actually interleave inside the streamGenerate block.
     */
    private class StreamingEngine : InferenceEngine {
        val invocations = AtomicInteger(0)

        private val caps = EngineCapabilities(
            supportAbis = setOf("arm64-v8a"),
            estimatedRamMb = 256,
            gpuBackend = GpuBackend.NONE,
            supportsTools = true,
            supportsInterruptibleCancellation = false,
            supportsModelExtensions = setOf("test"),
            maxConcurrentSessions = 1,
            supportsBatchedIngest = true,
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
            invocations.incrementAndGet()
            repeat(8) {
                emit(EngineEvent.Token("t$it"))
                yield()
            }
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
