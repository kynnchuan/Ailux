package com.ailux.core.session

import com.ailux.core.concurrency.MessageConcurrencyPolicy
import com.ailux.core.error.ErrorCode
import com.ailux.core.error.LLMException
import com.ailux.core.event.LLMEvent
import com.ailux.core.message.Message
import com.ailux.core.request.LLMRequest
import com.ailux.core.response.UsageInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavior tests for [StatelessProviderSession] — the client-side history
 * accumulator that backs every provider without a native KV-cache session.
 *
 * Coverage:
 * - history accumulation across two turns
 * - snapshot returns a faithful copy of the working history
 * - close() is idempotent (CAS-based, repeat calls must not blow up)
 * - close() then any other method throws IllegalStateException
 * - MessageConcurrencyPolicy.REJECT rejects the second concurrent turn
 *   with ErrorCode.CONCURRENT_REQUEST_REJECTED
 * - MessageConcurrencyPolicy.ENQUEUE serialises turns under a Mutex
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatelessProviderSessionTest {

    /** A delegate that immediately replies with one Token + Usage + Done. */
    private val instantReply: (LLMRequest) -> Flow<LLMEvent> = { req ->
        flow {
            emit(LLMEvent.Token("ok"))
            emit(
                LLMEvent.Usage(
                    UsageInfo(
                        inputTokens = req.messages.sumOf { (it as? Message.User)?.content?.length ?: 0 },
                        outputTokens = 2,
                        estimated = true,
                    )
                )
            )
            emit(LLMEvent.Done())
        }
    }

    @Test
    fun `history accumulates across two turns and folds assistant reply`() = runTest {
        val seenHistorySizes = mutableListOf<Int>()
        val delegate: (LLMRequest) -> Flow<LLMEvent> = { req ->
            // Snapshot the history that the delegate sees on this call.
            seenHistorySizes += req.messages.size
            instantReply(req)
        }

        val session = StatelessProviderSession(
            config = SessionConfig(systemInstruction = "be brief"),
            streamGenerateRaw = delegate,
        )

        // Turn 1: send "hi"
        val ev1 = session.streamGenerate(
            LLMRequest(messages = listOf(Message.User("hi")))
        ).toList()
        assertTrue("turn 1 emits at least Token+Done", ev1.size >= 2)

        // Turn 2: send "again"
        val ev2 = session.streamGenerate(
            LLMRequest(messages = listOf(Message.User("again")))
        ).toList()
        assertTrue("turn 2 emits at least Token+Done", ev2.size >= 2)

        // Delegate saw: turn1 → [System, User] = 2; turn2 → [System, User, Assistant, User] = 4
        // (StatelessProviderSession also folds Token "ok" into a Message.Assistant)
        assertEquals(
            "delegate should see growing history (2 → 4)",
            listOf(2, 4),
            seenHistorySizes,
        )
    }

    @Test
    fun `snapshot reflects working history`() = runTest {
        val session = StatelessProviderSession(
            config = SessionConfig(systemInstruction = "sys"),
            streamGenerateRaw = instantReply,
        )
        session.streamGenerate(
            LLMRequest(messages = listOf(Message.User("hi")))
        ).toList()

        val snap = session.snapshot()
        assertEquals("sys", snap.systemInstruction)
        // Expect: [System("sys"), User("hi"), Assistant("ok")]
        assertEquals(3, snap.messages.size)
        assertTrue(snap.messages[0] is Message.System)
        assertTrue(snap.messages[1] is Message.User)
        assertTrue(snap.messages[2] is Message.Assistant)
        assertEquals("ok", (snap.messages[2] as Message.Assistant).content)
    }

    @Test
    fun `close is idempotent`() {
        val session = StatelessProviderSession(
            config = SessionConfig(),
            streamGenerateRaw = instantReply,
        )
        session.close()
        session.close() // must not throw
        session.close()
    }

    @Test
    fun `streamGenerate after close throws IllegalStateException`() = runTest {
        val session = StatelessProviderSession(
            config = SessionConfig(),
            streamGenerateRaw = instantReply,
        )
        session.close()
        // The check fires inside the cold flow, so we trigger collection.
        assertThrows(IllegalStateException::class.java) {
            // toList() invokes collect; the IllegalStateException is wrapped by
            // runTest in a JobCancellationException so we use kotlinx.coroutines.runBlocking.
            kotlinx.coroutines.runBlocking {
                session.streamGenerate(
                    LLMRequest(messages = listOf(Message.User("x")))
                ).toList()
            }
        }
    }

    @Test
    fun `snapshot after close throws IllegalStateException`() {
        val session = StatelessProviderSession(
            config = SessionConfig(),
            streamGenerateRaw = instantReply,
        )
        session.close()
        assertThrows(IllegalStateException::class.java) { session.snapshot() }
    }

    @Test
    fun `REJECT policy rejects second concurrent turn`() = runTest {
        // Use a delegate that never finishes until we tell it to — gives the
        // first turn an in-flight window long enough to test rejection.
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slowDelegate: (LLMRequest) -> Flow<LLMEvent> = {
            flow {
                emit(LLMEvent.Token("..."))
                gate.await()
                emit(LLMEvent.Done())
            }
        }
        val session = StatelessProviderSession(
            config = SessionConfig(
                messageConcurrencyPolicy = MessageConcurrencyPolicy.REJECT,
            ),
            streamGenerateRaw = slowDelegate,
        )

        coroutineScope {
            // Start the in-flight turn (first collector).
            val first = launch {
                session.streamGenerate(
                    LLMRequest(messages = listOf(Message.User("a")))
                ).toList()
            }
            // Give the first launch enough time to take the lock.
            advanceUntilIdle()
            yield()
            // Burn a real-time millisecond to be sure the Mutex is taken.
            delay(1)

            // Second turn must be rejected.
            val ex = assertThrows(LLMException::class.java) {
                kotlinx.coroutines.runBlocking {
                    session.streamGenerate(
                        LLMRequest(messages = listOf(Message.User("b")))
                    ).toList()
                }
            }
            assertEquals(
                ErrorCode.CONCURRENT_REQUEST_REJECTED,
                ex.error.code,
            )

            // Release the first turn so coroutineScope can finish.
            gate.complete(Unit)
            first.join()
        }
    }

    @Test
    fun `ENQUEUE policy serialises two concurrent turns`() = runTest {
        // Track when each turn's delegate starts — they must NOT overlap.
        val delegateStarts = mutableListOf<Int>()
        var counter = 0
        val gate1 = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slowDelegate: (LLMRequest) -> Flow<LLMEvent> = { _ ->
            flow {
                val n = ++counter
                delegateStarts += n
                emit(LLMEvent.Token("t$n"))
                if (n == 1) gate1.await()
                emit(LLMEvent.Done())
            }
        }

        val session = StatelessProviderSession(
            config = SessionConfig(
                messageConcurrencyPolicy = MessageConcurrencyPolicy.ENQUEUE,
            ),
            streamGenerateRaw = slowDelegate,
        )

        coroutineScope {
            val first = async {
                session.streamGenerate(
                    LLMRequest(messages = listOf(Message.User("a")))
                ).toList()
            }
            // Wait for first to start.
            advanceUntilIdle()
            yield()
            assertEquals(
                "first delegate started",
                listOf(1),
                delegateStarts.toList(),
            )

            // Second turn launches but must block on the Mutex.
            val second = async {
                session.streamGenerate(
                    LLMRequest(messages = listOf(Message.User("b")))
                ).toList()
            }
            advanceUntilIdle()
            yield()
            assertEquals(
                "second delegate must NOT start while first holds the lock",
                listOf(1),
                delegateStarts.toList(),
            )

            // Release the first; the second should proceed.
            gate1.complete(Unit)
            first.await()
            second.await()
            assertEquals(
                "second delegate ran after first",
                listOf(1, 2),
                delegateStarts.toList(),
            )
        }
    }

    /**
     * Regression: snapshot() and streamGenerate() used to be guarded by two
     * different lock primitives (`synchronized(history)` vs `kotlinx Mutex`),
     * which don't block each other → ConcurrentModificationException on the
     * iterator inside `history.toList()`.
     *
     * After the fix (a single ReentrantLock for history reads/writes), high-
     * frequency snapshot must coexist with high-frequency streamGenerate
     * without ever throwing CME, and every snapshot must satisfy the
     * "history is a coherent prefix" invariant — i.e. messages alternate
     * user/assistant after the (optional) system row.
     *
     * Uses real Dispatchers (Default + IO) rather than runTest because the
     * race we're testing is genuine multi-thread interleaving, not virtual
     * time.
     */
    @Test
    fun snapshotConcurrentWithStreamGenerate_neverThrows() {
        val session = StatelessProviderSession(
            config = SessionConfig(),
            streamGenerateRaw = { _ ->
                flow {
                    repeat(8) {
                        emit(LLMEvent.Token("tok$it"))
                        // Hand the dispatcher back so snapshot threads can interleave.
                        kotlinx.coroutines.yield()
                    }
                    emit(LLMEvent.Done())
                }
            },
        )

        val producer = Thread {
            kotlinx.coroutines.runBlocking {
                repeat(40) { i ->
                    session.streamGenerate(
                        LLMRequest(messages = listOf(Message.User("u$i")))
                    ).toList()
                }
            }
        }

        val snapshotErrors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val snapshotThreads = (0 until 4).map { tid ->
            Thread {
                repeat(2_000) {
                    try {
                        val snap = session.snapshot()
                        // Coherent-prefix invariant: skip optional system,
                        // then messages must alternate user / assistant.
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
}
