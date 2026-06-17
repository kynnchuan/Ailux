package com.ailux.core.diagnostics

import com.ailux.core.error.ErrorCode
import com.ailux.core.error.LLMError
import com.ailux.core.privacy.PrivacyConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Contract tests for [DiagnosticsRecorder].
 *
 * Covers:
 *  1. TTFT / total duration calculation against a deterministic clock.
 *  2. Each terminal call (success / failure / cancel) yields the expected
 *     [Outcome].
 *  3. Retry trail is preserved in insertion order.
 *  4. After terminal state, further lifecycle calls are no-ops.
 *  5. snapshot() before terminal returns Outcome.Pending.
 *  6. lastReport() is stable across repeated reads.
 */
class DiagnosticsRecorderTest {

    private class FakeClock(start: Long = 1_000_000L) {
        private val now = AtomicLong(start)
        fun advance(deltaMs: Long): Long {
            now.addAndGet(deltaMs)
            return now.get()
        }
        fun read(): Long = now.get()
    }

    private fun newRecorder(
        clock: FakeClock,
        privacy: PrivacyConfig = PrivacyConfig.SECURE_DEFAULT,
    ): DiagnosticsRecorder = DiagnosticsRecorder(
        taskId = "test-1",
        sdkVersion = "0.2.5",
        providerName = "FakeProvider",
        modelName = "fake-model",
        privacy = privacy,
        clock = { clock.read() },
    )

    @Test
    fun `success outcome captures TTFT and total duration`() {
        val clock = FakeClock(start = 100L)
        val rec = newRecorder(clock)

        rec.onStart()
        clock.advance(150L)
        rec.onFirstContent()
        clock.advance(800L)
        rec.onSuccess()

        val report = rec.lastReport()!!
        assertEquals(Outcome.Success, report.outcome)
        assertEquals(150L, report.timing.ttftMs)
        assertEquals(950L, report.timing.durationMs)
        assertEquals(0, report.retries.size)
        assertEquals("test-1", report.taskId)
        assertEquals("0.2.5", report.sdkVersion)
        assertEquals("FakeProvider", report.provider)
        assertEquals("fake-model", report.model)
    }

    @Test
    fun `failure outcome maps LLMError into Outcome_Failure`() {
        val clock = FakeClock()
        val rec = newRecorder(clock)
        rec.onStart()
        clock.advance(200L)

        rec.onFailure(
            LLMError(
                code = ErrorCode.RATE_LIMITED,
                message = "rate limited",
            )
        )

        val outcome = rec.lastReport()!!.outcome as Outcome.Failure
        assertEquals("RATE_LIMITED", outcome.errorCode)
        assertEquals("rate limited", outcome.errorMessage)
        assertEquals("LLMError", outcome.errorClass)
    }

    @Test
    fun `failure outcome from arbitrary throwable preserves class name`() {
        val clock = FakeClock()
        val rec = newRecorder(clock)
        rec.onStart()
        rec.onFailure(IllegalStateException("kaboom"))

        val outcome = rec.lastReport()!!.outcome as Outcome.Failure
        assertEquals("IllegalStateException", outcome.errorClass)
        assertEquals("kaboom", outcome.errorMessage)
        assertNull(outcome.errorCode)
    }

    @Test
    fun `cancel outcome is recorded`() {
        val clock = FakeClock()
        val rec = newRecorder(clock)
        rec.onStart()
        rec.onCancel()

        assertEquals(Outcome.Cancelled, rec.lastReport()!!.outcome)
    }

    @Test
    fun `retries are appended in order`() {
        val clock = FakeClock()
        val rec = newRecorder(clock)
        rec.onStart()
        rec.onRetry("http_429", 1000L)
        rec.onRetry("http_503", 2000L)
        rec.onSuccess()

        val retries = rec.lastReport()!!.retries
        assertEquals(2, retries.size)
        assertEquals(0, retries[0].index)
        assertEquals("http_429", retries[0].reason)
        assertEquals(1000L, retries[0].delayMs)
        assertEquals(1, retries[1].index)
    }

    @Test
    fun `terminal state freezes report against further calls`() {
        val clock = FakeClock()
        val rec = newRecorder(clock)
        rec.onStart()
        clock.advance(100L)
        rec.onSuccess()
        val firstSnapshot = rec.lastReport()!!

        // Try to mutate after terminal state — must all be no-ops.
        clock.advance(500L)
        rec.onFirstContent()
        rec.onRetry("late", 999L)
        rec.onCancel()
        rec.onFailure(IllegalStateException("late"))

        val secondSnapshot = rec.lastReport()!!
        assertSame(firstSnapshot, secondSnapshot)
        assertEquals(Outcome.Success, secondSnapshot.outcome)
        assertEquals(0, secondSnapshot.retries.size)
    }

    @Test
    fun `snapshot before terminal returns Pending outcome`() {
        val clock = FakeClock()
        val rec = newRecorder(clock)
        rec.onStart()
        clock.advance(50L)

        val snap = rec.snapshot()
        assertEquals(Outcome.Pending, snap.outcome)
        assertNull(snap.timing.firstTokenAt)
        assertNotNull("startedAt must be populated", snap.timing.startedAt)
        assertTrue(snap.timing.startedAt > 0L)
    }

    @Test
    fun `onFirstContent records only the first call`() {
        val clock = FakeClock(start = 1000L)
        val rec = newRecorder(clock)
        rec.onStart()
        clock.advance(100L)
        rec.onFirstContent()
        val firstAt = rec.snapshot().timing.firstTokenAt
        clock.advance(500L)
        rec.onFirstContent()
        val stillFirstAt = rec.snapshot().timing.firstTokenAt
        assertEquals(firstAt, stillFirstAt)
    }

    @Test
    fun `lastReport is null while task is in flight`() {
        val clock = FakeClock()
        val rec = newRecorder(clock)
        rec.onStart()
        assertNull(rec.lastReport())
    }

    @Test
    fun `privacy snapshot matches config at construction time`() {
        val clock = FakeClock()
        val rec = newRecorder(clock, PrivacyConfig.DEBUG_VERBOSE)
        rec.onStart()
        rec.onSuccess()

        val snap = rec.lastReport()!!.privacy
        assertEquals(true, snap.logPrompt)
        assertEquals(true, snap.logResponse)
        assertEquals(true, snap.logOverrides)
        assertEquals(false, snap.logHeaders)
        assertEquals(false, snap.logRequestBody)
    }
}
