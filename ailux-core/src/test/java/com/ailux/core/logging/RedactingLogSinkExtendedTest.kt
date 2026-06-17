package com.ailux.core.logging

import com.ailux.core.logging.internal.RedactingLogSink
import com.ailux.core.privacy.PrivacyConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Extended contract tests for [RedactingLogSink] covering the v0.2.5 B2-1
 * additions: headers / request-body / response-body helpers, body truncation,
 * `SECURE_DEFAULT` / `DEBUG_VERBOSE` factory presets, and the always-on
 * scrubbing of credential headers.
 */
class RedactingLogSinkExtendedTest {

    private class CapturingLogger : AiluxLogger {
        val messages = mutableListOf<String>()
        val throwables = mutableListOf<Throwable?>()
        override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
            messages += message
            throwables += throwable
        }
    }

    // ──────────────────── Headers ────────────────────

    @Test
    fun `headers are redacted by default`() {
        val capture = CapturingLogger()
        val sink = RedactingLogSink(capture, PrivacyConfig.SECURE_DEFAULT)

        sink.logHeaders(
            LogLevel.DEBUG,
            "Ailux",
            mapOf("Content-Type" to "application/json", "X-Custom" to "v"),
        )

        assertEquals(1, capture.messages.size)
        assertTrue(capture.messages[0].contains("redacted"))
        assertFalse(capture.messages[0].contains("application/json"))
    }

    @Test
    fun `headers are emitted when logHeaders is on but credential headers stay scrubbed`() {
        val capture = CapturingLogger()
        val sink = RedactingLogSink(
            capture,
            PrivacyConfig(logHeaders = true, redactionMask = "***"),
        )

        sink.logHeaders(
            LogLevel.DEBUG,
            "Ailux",
            linkedMapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer sk-very-secret",
                "X-Api-Key" to "abc123",
                "Cookie" to "session=zzz",
                "Set-Cookie" to "session=zzz",
                "Proxy-Authorization" to "Basic xxx",
            ),
        )

        val rendered = capture.messages.single()
        assertTrue("Content-Type should be plain", rendered.contains("Content-Type: application/json"))
        assertFalse("Authorization value must not appear", rendered.contains("sk-very-secret"))
        assertFalse("X-Api-Key value must not appear", rendered.contains("abc123"))
        assertFalse("Cookie value must not appear", rendered.contains("session=zzz"))
        assertFalse("Proxy-Authorization value must not appear", rendered.contains("Basic xxx"))
        // Each scrubbed header should still report its name + the mask.
        assertTrue(rendered.contains("Authorization: ***"))
        assertTrue(rendered.contains("X-Api-Key: ***"))
        assertTrue(rendered.contains("Cookie: ***"))
        assertTrue(rendered.contains("Set-Cookie: ***"))
        assertTrue(rendered.contains("Proxy-Authorization: ***"))
    }

    @Test
    fun `header name match is case insensitive`() {
        val capture = CapturingLogger()
        val sink = RedactingLogSink(capture, PrivacyConfig(logHeaders = true))

        sink.logHeaders(
            LogLevel.DEBUG,
            "Ailux",
            mapOf("authorization" to "Bearer sk-secret", "X-API-KEY" to "abc"),
        )

        val rendered = capture.messages.single()
        assertFalse(rendered.contains("sk-secret"))
        assertFalse(rendered.contains("abc"))
    }

    // ──────────────────── Request body ────────────────────

    @Test
    fun `request body is redacted by default`() {
        val capture = CapturingLogger()
        val sink = RedactingLogSink(capture, PrivacyConfig.SECURE_DEFAULT)

        sink.logRequestBody(LogLevel.DEBUG, "Ailux", """{"messages":[{"content":"hi"}]}""")

        assertTrue(capture.messages.single().contains("redacted"))
    }

    @Test
    fun `request body is emitted when logRequestBody is on`() {
        val capture = CapturingLogger()
        val sink = RedactingLogSink(capture, PrivacyConfig(logRequestBody = true))
        val body = """{"messages":[{"content":"hi"}]}"""

        sink.logRequestBody(LogLevel.DEBUG, "Ailux", body)

        assertEquals(body, capture.messages.single())
    }

    @Test
    fun `request body is truncated to maxLoggedBodyLength`() {
        val capture = CapturingLogger()
        val sink = RedactingLogSink(
            capture,
            PrivacyConfig(logRequestBody = true, maxLoggedBodyLength = 10),
        )
        val body = "0123456789ABCDEF" // 16 chars

        sink.logRequestBody(LogLevel.DEBUG, "Ailux", body)

        val rendered = capture.messages.single()
        assertTrue("expected truncation marker, got: $rendered", rendered.startsWith("0123456789"))
        assertTrue(rendered.contains("truncated"))
        assertTrue(rendered.contains("6 chars dropped"))
    }

    // ──────────────────── Response body ────────────────────

    @Test
    fun `response body honours logResponse flag`() {
        val capture = CapturingLogger()
        val sinkOff = RedactingLogSink(capture, PrivacyConfig.SECURE_DEFAULT)
        val sinkOn = RedactingLogSink(capture, PrivacyConfig(logResponse = true))

        sinkOff.logResponseBody(LogLevel.DEBUG, "Ailux", """{"text":"answer"}""")
        sinkOn.logResponseBody(LogLevel.DEBUG, "Ailux", """{"text":"answer"}""")

        assertTrue(capture.messages[0].contains("redacted"))
        assertEquals("""{"text":"answer"}""", capture.messages[1])
    }

    // ──────────────────── Factory presets ────────────────────

    @Test
    fun `SECURE_DEFAULT preset blocks every sensitive channel`() {
        val capture = CapturingLogger()
        val sink = RedactingLogSink(capture, PrivacyConfig.SECURE_DEFAULT)

        sink.logPrompt(LogLevel.DEBUG, "Ailux", "p")
        sink.logResponse(LogLevel.DEBUG, "Ailux", "r")
        sink.logOverrides(LogLevel.DEBUG, "Ailux", "o")
        sink.logHeaders(LogLevel.DEBUG, "Ailux", mapOf("Content-Type" to "v"))
        sink.logRequestBody(LogLevel.DEBUG, "Ailux", "body")
        sink.logResponseBody(LogLevel.DEBUG, "Ailux", "body")

        capture.messages.forEach { assertTrue("expected redacted, got: $it", it.contains("redacted")) }
    }

    @Test
    fun `DEBUG_VERBOSE preset opens prompt response and overrides only`() {
        val capture = CapturingLogger()
        val sink = RedactingLogSink(capture, PrivacyConfig.DEBUG_VERBOSE)

        sink.logPrompt(LogLevel.DEBUG, "Ailux", "P")
        sink.logResponse(LogLevel.DEBUG, "Ailux", "R")
        sink.logOverrides(LogLevel.DEBUG, "Ailux", "O")
        sink.logHeaders(LogLevel.DEBUG, "Ailux", mapOf("X" to "y"))
        sink.logRequestBody(LogLevel.DEBUG, "Ailux", "body")

        assertEquals("P", capture.messages[0])
        assertEquals("R", capture.messages[1])
        assertEquals("O", capture.messages[2])
        assertTrue("DEBUG_VERBOSE keeps headers off", capture.messages[3].contains("redacted"))
        assertTrue("DEBUG_VERBOSE keeps request body off", capture.messages[4].contains("redacted"))
    }

    @Test
    fun `privacyConfig getter returns the bound policy`() {
        val sink = RedactingLogSink(CapturingLogger(), PrivacyConfig.DEBUG_VERBOSE)
        assertNotNull(sink.privacyConfig)
        assertEquals(PrivacyConfig.DEBUG_VERBOSE, sink.privacyConfig)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative maxLoggedBodyLength is rejected`() {
        PrivacyConfig(maxLoggedBodyLength = -1)
    }
}
