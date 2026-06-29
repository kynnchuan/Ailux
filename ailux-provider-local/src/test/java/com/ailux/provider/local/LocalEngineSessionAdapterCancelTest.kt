package com.ailux.provider.local

import com.ailux.core.concurrency.MessageConcurrencyPolicy
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression tests for issue #3 of `docs/SESSION-ENGINE-AUDIT-zh.md`:
 * `MessageConcurrencyPolicy.CANCEL_PREVIOUS` must do a *real* cancel — abort
 * the in-flight worker AND tell the native engine to stop, instead of merely
 * setting an "advisory" flag while the engine continues generating.
 *
 * What we assert here:
 * 1. When a second turn arrives under CANCEL_PREVIOUS, the first turn's
 *    worker observes a `CancellationException` mid-stream (i.e. it does NOT
 *    run to its natural EOS).
 * 2. [EngineSession.cancel] is invoked exactly when CANCEL_PREVIOUS fires (or
 *    when the consumer cancels its collector) — proving the native abort path
 *    is wired up.
 * 3. The cancelled turn's half-streamed assistant reply is **not** folded into
 *    history; only fully-streamed turns can leave an assistant message behind.
 *
 * We use real threads / `runBlocking` rather than `runTest` because the
 * cancellation paths we exercise involve real time sleeps (so the engine has
 * a window to stream a partial token before being preempted).
 */
class LocalEngineSessionAdapterCancelTest {

    @Test
    fun `CANCEL_PREVIOUS cancels worker and invokes engineSession cancel`() {
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = AtomicBoolean(false)

        val engine = ScriptedEngine { call ->
            if (call == 1) {
                flow {
                    firstStarted.complete(Unit)
                    try {
                        repeat(20) {
                            emit(EngineEvent.Token("t$it"))
                            delay(50)
                        }
                        emit(EngineEvent.Stop(EngineStopReason.EOS))
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        firstCancelled.set(true)
                        throw ce
                    }
                }
            } else {
                flow {
                    emit(EngineEvent.Token("ok2"))
                    emit(EngineEvent.Stop(EngineStopReason.EOS))
                }
            }
        }

        val nativeSession = TrackingEngineSession()
        val adapter = LocalEngineSessionAdapter(
            engineSession = nativeSession,
            engine = engine,
            config = SessionConfig(
                messageConcurrencyPolicy = MessageConcurrencyPolicy.CANCEL_PREVIOUS,
            ),
        )

        runBlocking {
            coroutineScope {
                val first = launch {
                    runCatching {
                        adapter.streamGenerate(
                            LLMRequest(messages = listOf(Message.User("a")))
                        ).toList()
                    }
                }
                firstStarted.await()
                // Let the first turn emit at least one token.
                delay(60)

                // Second turn must preempt the first.
                val ev2 = adapter.streamGenerate(
                    LLMRequest(messages = listOf(Message.User("b")))
                ).toList()
                assertTrue(
                    "second turn must reach LLMEvent.Done",
                    ev2.any { it is LLMEvent.Done },
                )
                first.join()

                assertTrue(
                    "first turn's engine flow must observe CancellationException",
                    firstCancelled.get(),
                )
                assertTrue(
                    "engineSession.cancel() must be invoked at least once " +
                        "(by CANCEL_PREVIOUS pre-emption AND/or by worker's CE catch)",
                    nativeSession.cancelCount.get() >= 1,
                )
            }
        }
    }

    @Test
    fun `CANCEL_PREVIOUS drops half-streamed assistant reply from history`() {
        val firstStarted = CompletableDeferred<Unit>()
        val engine = ScriptedEngine { call ->
            if (call == 1) {
                flow {
                    firstStarted.complete(Unit)
                    emit(EngineEvent.Token("PARTIAL_"))
                    delay(60_000)
                    emit(EngineEvent.Stop(EngineStopReason.EOS))
                }
            } else {
                flow {
                    emit(EngineEvent.Token("FINAL"))
                    emit(EngineEvent.Stop(EngineStopReason.EOS))
                }
            }
        }
        val adapter = LocalEngineSessionAdapter(
            engineSession = TrackingEngineSession(),
            engine = engine,
            config = SessionConfig(
                messageConcurrencyPolicy = MessageConcurrencyPolicy.CANCEL_PREVIOUS,
            ),
        )

        runBlocking {
            coroutineScope {
                val first = launch {
                    runCatching {
                        adapter.streamGenerate(
                            LLMRequest(messages = listOf(Message.User("a")))
                        ).toList()
                    }
                }
                firstStarted.await()
                delay(20)

                adapter.streamGenerate(
                    LLMRequest(messages = listOf(Message.User("b")))
                ).toList()
                first.join()

                val snap = adapter.snapshot()
                val assistantContents = snap.messages
                    .filterIsInstance<Message.Assistant>()
                    .mapNotNull { it.content }
                assertEquals(
                    "only the completed turn's assistant reply should be folded",
                    listOf("FINAL"),
                    assistantContents,
                )
                assertFalse(
                    "PARTIAL_ must not appear anywhere in folded history",
                    assistantContents.any { it.contains("PARTIAL_") },
                )
            }
        }
    }

    /**
     * Engine that returns a per-call scripted [Flow] of [EngineEvent] using
     * [scenario]. The 1-based call index lets the test distinguish the first
     * (cancelled) and second (clean) turns.
     */
    private class ScriptedEngine(
        private val scenario: (call: Int) -> Flow<EngineEvent>,
    ) : InferenceEngine {
        val callCount = AtomicInteger(0)
        private val caps = EngineCapabilities(
            supportAbis = setOf("arm64-v8a"),
            estimatedRamMb = 256,
            gpuBackend = GpuBackend.NONE,
            supportsTools = true,
            supportsInterruptibleCancellation = true,
            supportedModelExtensions = setOf("test"),
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
        ): Flow<EngineEvent> = scenario(callCount.incrementAndGet())
    }

    /** Counts cancel/close calls so tests can assert the native abort wired up. */
    private class TrackingEngineSession : EngineSession {
        override val sessionId: String = "test-session"
        override val approximateMemoryBytes: Long = -1L
        override val hasCachedPrefix: Boolean = false
        val cancelCount = AtomicInteger(0)
        val closeCount = AtomicInteger(0)
        override fun cancel() {
            cancelCount.incrementAndGet()
        }
        override fun close() {
            closeCount.incrementAndGet()
        }
    }
}
