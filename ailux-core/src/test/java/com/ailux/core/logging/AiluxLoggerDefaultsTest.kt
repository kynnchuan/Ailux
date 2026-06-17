package com.ailux.core.logging

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies that the `default` convenience methods on [AiluxLogger] dispatch to
 * [AiluxLogger.log] with the correct [LogLevel].
 *
 * Why this matters: implementers override only [AiluxLogger.log], but callers
 * use [AiluxLogger.v] / [d] / [i] / [w] / [e]. If any default delegates to the
 * wrong level, **every call site in the SDK silently drifts** with no compile
 * error. Lock the contract down here.
 *
 * @since 0.2.5
 */
class AiluxLoggerDefaultsTest {

    private class Capturing : AiluxLogger {
        var lastLevel: LogLevel? = null
        var lastTag: String? = null
        var lastMessage: String? = null
        var lastThrowable: Throwable? = null
        override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
            lastLevel = level
            lastTag = tag
            lastMessage = message
            lastThrowable = throwable
        }
    }

    @Test
    fun `v dispatches VERBOSE`() {
        val capture = Capturing()
        capture.v("Ailux", "msg")
        assertEquals(LogLevel.VERBOSE, capture.lastLevel)
        assertEquals("Ailux", capture.lastTag)
        assertEquals("msg", capture.lastMessage)
    }

    @Test
    fun `d dispatches DEBUG`() {
        val capture = Capturing()
        capture.d("Ailux", "msg")
        assertEquals(LogLevel.DEBUG, capture.lastLevel)
    }

    @Test
    fun `i dispatches INFO`() {
        val capture = Capturing()
        capture.i("Ailux", "msg")
        assertEquals(LogLevel.INFO, capture.lastLevel)
    }

    @Test
    fun `w dispatches WARN`() {
        val capture = Capturing()
        capture.w("Ailux", "msg")
        assertEquals(LogLevel.WARN, capture.lastLevel)
    }

    @Test
    fun `e dispatches ERROR with throwable`() {
        val capture = Capturing()
        val cause = IllegalStateException("boom")
        capture.e("Ailux", "msg", cause)
        assertEquals(LogLevel.ERROR, capture.lastLevel)
        assertEquals(cause, capture.lastThrowable)
    }
}
