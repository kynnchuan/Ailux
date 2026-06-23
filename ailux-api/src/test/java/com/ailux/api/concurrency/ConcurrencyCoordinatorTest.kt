package com.ailux.api.concurrency

import com.ailux.core.concurrency.SessionConcurrencyPolicy
import com.ailux.core.logging.AiluxLogger
import com.ailux.core.logging.LogLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavior tests for [ConcurrencyCoordinator] — the cross-session coordinator
 * that enforces [SessionConcurrencyPolicy] capped by
 * `ProviderCapabilities.maxConcurrentSessions`.
 *
 * Coverage matrix:
 * - PARALLEL with cap = MAX_VALUE: tasks run in parallel, no warning
 * - PARALLEL with cap = 1: second task is soft-degraded (queued), exactly
 *   ONE WARN log emitted
 * - PARALLEL: WARN is emitted at most once across N over-cap tasks
 * - REJECT: second task returns false; no log
 * - ENQUEUE: tasks serialise on the same FIFO mutex
 * - CANCEL_PREVIOUS: opening a new task cancels the in-flight one
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConcurrencyCoordinatorTest {

    private class CapturingLogger : AiluxLogger {
        data class Entry(val level: LogLevel, val tag: String, val message: String)
        val entries = mutableListOf<Entry>()
        override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
            entries += Entry(level, tag, message)
        }

        fun warnsAboutCap(): Int = entries.count {
            it.level == LogLevel.WARN && it.message.contains("maxConcurrentSessions")
        }
    }

    @Test
    fun `PARALLEL with high cap admits both tasks without warning`() = runTest {
        val logger = CapturingLogger()
        val coord = ConcurrencyCoordinator(
            policy = SessionConcurrencyPolicy.PARALLEL,
            maxConcurrentSessions = Int.MAX_VALUE,
            logger = logger,
        )

        coroutineScope {
            val gate = CompletableDeferred<Unit>()

            val a = launch {
                coord.onTaskStart("a", coroutineContext[Job]!!) { /* never queued */ fail("not queued") }
                gate.await()
                coord.onTaskEnd("a")
            }
            val b = launch {
                coord.onTaskStart("b", coroutineContext[Job]!!) { fail("not queued") }
                gate.await()
                coord.onTaskEnd("b")
            }

            advanceUntilIdle()
            assertEquals("no cap-degradation warning expected", 0, logger.warnsAboutCap())
            gate.complete(Unit)
            a.join(); b.join()
        }
    }

    @Test
    fun `PARALLEL over cap soft-degrades second task and warns once`() = runTest {
        val logger = CapturingLogger()
        val coord = ConcurrencyCoordinator(
            policy = SessionConcurrencyPolicy.PARALLEL,
            maxConcurrentSessions = 1,
            logger = logger,
        )

        coroutineScope {
            val firstGate = CompletableDeferred<Unit>()
            val firstStarted = CompletableDeferred<Unit>()

            val first = launch {
                val ok = coord.onTaskStart("first", coroutineContext[Job]!!) { /* not queued */ }
                assertTrue(ok)
                firstStarted.complete(Unit)
                firstGate.await()
                coord.onTaskEnd("first")
            }

            firstStarted.await()
            advanceUntilIdle()

            // Second task: cap is reached → must be queued, must complete only
            // after the first ends.
            val secondQueued = CompletableDeferred<Int>()
            val second = launch {
                val ok = coord.onTaskStart("second", coroutineContext[Job]!!) { pos ->
                    secondQueued.complete(pos)
                }
                assertTrue("PARALLEL must never reject — only soft-degrade", ok)
                coord.onTaskEnd("second")
            }

            // Wait for the queue position callback (means we hit the cap branch).
            advanceUntilIdle()
            yield()
            // We MUST have invoked onQueued for the second task.
            // But it'll only resolve after we let the scheduler run; pump until ready.
            // (Inside runTest, advanceUntilIdle is enough.)
            assertTrue(
                "onQueued must fire when PARALLEL hits cap",
                secondQueued.isCompleted,
            )

            // Release first → second proceeds.
            firstGate.complete(Unit)
            first.join()
            second.join()

            // EXACTLY one WARN.
            assertEquals(
                "warning must be emitted once and only once",
                1,
                logger.warnsAboutCap(),
            )
        }
    }

    @Test
    fun `PARALLEL over cap warns ONLY once across many degraded tasks`() = runTest {
        val logger = CapturingLogger()
        val coord = ConcurrencyCoordinator(
            policy = SessionConcurrencyPolicy.PARALLEL,
            maxConcurrentSessions = 1,
            logger = logger,
        )

        coroutineScope {
            val gate = CompletableDeferred<Unit>()
            val first = launch {
                coord.onTaskStart("0", coroutineContext[Job]!!) { /* unused */ }
                gate.await()
                coord.onTaskEnd("0")
            }

            // Queue 3 degraded tasks.
            val degraded = (1..3).map { i ->
                launch {
                    coord.onTaskStart("$i", coroutineContext[Job]!!) { /* queue position */ }
                    coord.onTaskEnd("$i")
                }
            }

            advanceUntilIdle()
            // All four are now alive; release first to drain the queue.
            gate.complete(Unit)
            first.join()
            degraded.forEach { it.join() }

            assertEquals(
                "WARN must be emitted at most once even across 3 degraded tasks",
                1,
                logger.warnsAboutCap(),
            )
        }
    }

    @Test
    fun `REJECT denies the second task and emits no warning`() = runTest {
        val logger = CapturingLogger()
        val coord = ConcurrencyCoordinator(
            policy = SessionConcurrencyPolicy.REJECT,
            maxConcurrentSessions = Int.MAX_VALUE,
            logger = logger,
        )

        coroutineScope {
            val gate = CompletableDeferred<Unit>()
            val first = launch {
                val ok = coord.onTaskStart("a", coroutineContext[Job]!!) { fail("not queued") }
                assertTrue(ok)
                gate.await()
                coord.onTaskEnd("a")
            }
            advanceUntilIdle()

            val secondAdmitted = async {
                coord.onTaskStart("b", coroutineContext[Job]!!) { /* unused */ }
            }
            advanceUntilIdle()
            assertFalse("REJECT must deny the second task", secondAdmitted.await())

            gate.complete(Unit)
            first.join()

            assertEquals(
                "REJECT must not emit cap-degradation warning",
                0,
                logger.warnsAboutCap(),
            )
        }
    }

    @Test
    fun `ENQUEUE serialises tasks via the fair mutex`() = runTest {
        val coord = ConcurrencyCoordinator(
            policy = SessionConcurrencyPolicy.ENQUEUE,
            maxConcurrentSessions = Int.MAX_VALUE,
            logger = null,
        )

        val started = mutableListOf<String>()
        coroutineScope {
            val gateA = CompletableDeferred<Unit>()
            val a = launch {
                coord.onTaskStart("a", coroutineContext[Job]!!) { /* may receive position 1 */ }
                started += "a"
                gateA.await()
                coord.onTaskEnd("a")
            }
            advanceUntilIdle()
            // a must have started (took the mutex)
            assertEquals(listOf("a"), started.toList())

            val b = launch {
                coord.onTaskStart("b", coroutineContext[Job]!!) { /* queue position */ }
                started += "b"
                coord.onTaskEnd("b")
            }
            advanceUntilIdle()
            // b must NOT have started yet — it's blocked on the mutex.
            assertEquals(
                "b must wait for a to complete under ENQUEUE",
                listOf("a"),
                started.toList(),
            )

            gateA.complete(Unit)
            a.join(); b.join()
            assertEquals(listOf("a", "b"), started.toList())
        }
    }

    @Test
    fun `CANCEL_PREVIOUS cancels in-flight task on new task start`() = runTest {
        val coord = ConcurrencyCoordinator(
            policy = SessionConcurrencyPolicy.CANCEL_PREVIOUS,
            maxConcurrentSessions = Int.MAX_VALUE,
            logger = null,
        )
        coroutineScope {
            val firstStarted = CompletableDeferred<Unit>()
            val firstFinished = CompletableDeferred<Unit>()

            val first = launch {
                try {
                    coord.onTaskStart("first", coroutineContext[Job]!!) { fail("not queued") }
                    firstStarted.complete(Unit)
                    // Simulate a long task that should be cancelled.
                    delay(60_000)
                } finally {
                    firstFinished.complete(Unit)
                    coord.onTaskEnd("first")
                }
            }
            firstStarted.await()
            advanceUntilIdle()

            // New task → cancels first.
            launch {
                coord.onTaskStart("second", coroutineContext[Job]!!) { fail("not queued") }
                coord.onTaskEnd("second")
            }
            advanceUntilIdle()

            // First should now be cancelled.
            assertTrue("first task should have completed (cancelled)", firstFinished.isCompleted)
            // Drain.
            first.join()
        }
    }

    private fun fail(msg: String): Nothing = throw AssertionError(msg)
}
