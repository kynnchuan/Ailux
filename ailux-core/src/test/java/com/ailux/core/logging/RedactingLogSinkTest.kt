package com.ailux.core.logging

import com.ailux.core.logging.internal.RedactingLogSink
import com.ailux.core.privacy.PrivacyConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [RedactingLogSink].
 *
 * What we guarantee here:
 *  1. Default [PrivacyConfig] redacts prompt / response / overrides bodies.
 *  2. Per-channel toggles let prompts / responses / overrides through
 *     **independently** — flipping `logResponse` does not also leak prompts.
 *  3. Safe (non-sensitive) messages are always forwarded untouched.
 *  4. Throwables are propagated verbatim — redaction concerns only the
 *     message string.
 *  5. The substituted text contains [PrivacyConfig.redactionMask] verbatim, so
 *     callers can assert "redacted output uses my custom mask".
 */
class RedactingLogSinkTest {

    private class CapturingLogger : AiluxLogger {
        data class Entry(
            val level: LogLevel,
            val tag: String,
            val message: String,
            val throwable: Throwable?,
        )

        val entries = mutableListOf<Entry>()

        override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
            entries += Entry(level, tag, message, throwable)
        }
    }

    @Test
    fun `default config redacts prompt response and overrides`() {
        val capture = CapturingLogger()
        val sink = RedactingLogSink(capture, PrivacyConfig())

        sink.logPrompt(LogLevel.DEBUG, "Ailux", "system: you are helpful\nuser: secret query")
        sink.logResponse(LogLevel.DEBUG, "Ailux", "the answer is 42")
        sink.logOverrides(LogLevel.DEBUG, "Ailux", """{"api_key":"sk-very-secret"}""")

        assertEquals(3, capture.entries.size)
        assertTrue("prompt should be redacted", capture.entries[0].message.contains("redacted"))
        assertTrue("prompt should not contain raw text", !capture.entries[0].message.contains("secret query"))
        assertTrue("response should be redacted", capture.entries[1].message.contains("redacted"))
        assertTrue("response should not contain raw text", !capture.entries[1].message.contains("42"))
        assertTrue("overrides should be redacted", capture.entries[2].message.contains("redacted"))
        assertTrue("overrides should not contain raw key", !capture.entries[2].message.contains("sk-very-secret"))
    }

    @Test
    fun `logSafe is always forwarded untouched`() {
        val capture = CapturingLogger()
        val sink = RedactingLogSink(capture, PrivacyConfig())

        sink.logSafe(LogLevel.INFO, "Ailux", "request started: requestId=abc")

        assertEquals(1, capture.entries.size)
        assertEquals("request started: requestId=abc", capture.entries[0].message)
        assertEquals(LogLevel.INFO, capture.entries[0].level)
    }

    @Test
    fun `enabling logPrompt does not leak response or overrides`() {
        val capture = CapturingLogger()
        val sink = RedactingLogSink(
            capture,
            PrivacyConfig(logPrompt = true),
        )

        sink.logPrompt(LogLevel.DEBUG, "Ailux", "system: you are helpful")
        sink.logResponse(LogLevel.DEBUG, "Ailux", "secret answer")
        sink.logOverrides(LogLevel.DEBUG, "Ailux", """{"api_key":"sk-leak"}""")

        assertEquals("system: you are helpful", capture.entries[0].message)
        assertTrue("response must still be redacted", capture.entries[1].message.contains("redacted"))
        assertTrue("response must not leak", !capture.entries[1].message.contains("secret answer"))
        assertTrue("overrides must still be redacted", capture.entries[2].message.contains("redacted"))
        assertTrue("overrides must not leak", !capture.entries[2].message.contains("sk-leak"))
    }

    @Test
    fun `enabling logResponse does not leak prompt or overrides`() {
        val capture = CapturingLogger()
        val sink = RedactingLogSink(
            capture,
            PrivacyConfig(logResponse = true),
        )

        sink.logPrompt(LogLevel.DEBUG, "Ailux", "secret prompt")
        sink.logResponse(LogLevel.DEBUG, "Ailux", "the answer is 42")
        sink.logOverrides(LogLevel.DEBUG, "Ailux", """{"api_key":"sk-leak"}""")

        assertTrue("prompt must still be redacted", capture.entries[0].message.contains("redacted"))
        assertTrue("prompt must not leak", !capture.entries[0].message.contains("secret prompt"))
        assertEquals("the answer is 42", capture.entries[1].message)
        assertTrue("overrides must still be redacted", capture.entries[2].message.contains("redacted"))
    }

    @Test
    fun `enabling logOverrides does not leak prompt or response`() {
        val capture = CapturingLogger()
        val sink = RedactingLogSink(
            capture,
            PrivacyConfig(logOverrides = true),
        )

        sink.logPrompt(LogLevel.DEBUG, "Ailux", "secret prompt")
        sink.logResponse(LogLevel.DEBUG, "Ailux", "secret response")
        sink.logOverrides(LogLevel.DEBUG, "Ailux", """{"vendor_only":"value"}""")

        assertTrue(capture.entries[0].message.contains("redacted"))
        assertTrue(capture.entries[1].message.contains("redacted"))
        assertEquals("""{"vendor_only":"value"}""", capture.entries[2].message)
    }

    @Test
    fun `all flags on lets every body through`() {
        val capture = CapturingLogger()
        val sink = RedactingLogSink(
            capture,
            PrivacyConfig(
                logPrompt = true,
                logResponse = true,
                logOverrides = true,
            ),
        )

        sink.logPrompt(LogLevel.DEBUG, "Ailux", "P")
        sink.logResponse(LogLevel.DEBUG, "Ailux", "R")
        sink.logOverrides(LogLevel.DEBUG, "Ailux", "O")

        assertEquals(listOf("P", "R", "O"), capture.entries.map { it.message })
    }

    @Test
    fun `throwable is forwarded even when message is redacted`() {
        val capture = CapturingLogger()
        val sink = RedactingLogSink(capture, PrivacyConfig())
        val cause = IllegalStateException("boom")

        sink.logResponse(LogLevel.ERROR, "Ailux", "stream failed mid-flight", cause)

        assertEquals(1, capture.entries.size)
        assertTrue(capture.entries[0].message.contains("redacted"))
        assertEquals(cause, capture.entries[0].throwable)
    }

    @Test
    fun `custom redaction mask appears in redacted output`() {
        val capture = CapturingLogger()
        val sink = RedactingLogSink(
            capture,
            PrivacyConfig(redactionMask = "<HIDDEN>"),
        )

        sink.logPrompt(LogLevel.DEBUG, "Ailux", "anything")

        assertTrue(
            "expected custom mask to appear, got: ${capture.entries[0].message}",
            capture.entries[0].message.contains("<HIDDEN>"),
        )
    }
}
