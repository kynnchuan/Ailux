package com.ailux.core.diagnostics

import com.ailux.core.privacy.PrivacyConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [DiagnosticReport.toShareableText] and
 * [DiagnosticReport.toJson].
 *
 * The text renderer is treated as a stable API: integrations grep for fixed
 * line prefixes (`SDK:`, `Outcome:`). Format drift would silently break those
 * consumers, so we lock the layout here.
 */
class DiagnosticReportTextTest {

    private val privacySnapshot = PrivacyConfigSnapshot.of(PrivacyConfig.SECURE_DEFAULT)

    private fun fixedTimestamp(): Long {
        // 2026-06-11T11:32:13Z (matches the user's clock at writing time).
        return 1_780_000_000_000L
    }

    @Test
    fun `task report shareable text contains required prefixes`() {
        val report = DiagnosticReport(
            sdkVersion = "0.2.5",
            timestamp = fixedTimestamp(),
            taskId = "chat-7f3a",
            provider = "BackendProxyProvider",
            model = "gpt-4o-mini",
            timing = TimingMetrics(startedAt = 100L, firstTokenAt = 412L, finishedAt = 1580L),
            outcome = Outcome.Success,
            retries = emptyList(),
            privacy = privacySnapshot,
        )

        val text = report.toShareableText()

        assertTrue("missing SDK header", text.contains("SDK: 0.2.5"))
        assertTrue("missing Time header", text.contains("Time: "))
        assertTrue("missing Task header", text.contains("Task: chat-7f3a"))
        assertTrue("provider missing", text.contains("provider=BackendProxyProvider"))
        assertTrue("model missing", text.contains("model=gpt-4o-mini"))
        assertTrue("Outcome line missing", text.contains("Outcome: Success"))
        assertTrue("TTFT calculation wrong", text.contains("TTFT=312ms"))
        assertTrue("total calculation wrong", text.contains("total=1480ms"))
        assertTrue("Retries header missing", text.contains("Retries: 0"))
        assertTrue("Privacy line missing", text.contains("Privacy: prompt=off"))
        assertTrue(text.startsWith("=== Ailux Diagnostic ==="))
        assertTrue(text.endsWith("========================"))
    }

    @Test
    fun `failure outcome serialises with error class code and message`() {
        val report = DiagnosticReport(
            sdkVersion = "0.2.5",
            timestamp = fixedTimestamp(),
            taskId = "chat-fail",
            provider = "BackendProxyProvider",
            model = null,
            timing = TimingMetrics(startedAt = 0L),
            outcome = Outcome.Failure(
                errorClass = "HttpException",
                errorCode = "RATE_LIMITED",
                errorMessage = "rate limited",
            ),
            retries = listOf(
                RetryAttempt(0, "http_429", 1000L),
                RetryAttempt(1, "http_429", 2000L),
            ),
            privacy = privacySnapshot,
        )

        val text = report.toShareableText()

        assertTrue(text.contains("Outcome: Failure(HttpException, code=RATE_LIMITED, \"rate limited\")"))
        assertTrue(text.contains("Retries: 2"))
        assertTrue(text.contains("[0] http_429, delay=1000ms"))
        assertTrue(text.contains("[1] http_429, delay=2000ms"))
    }

    @Test
    fun `session report uses Scope header and lists recent tasks`() {
        val task = DiagnosticReport(
            sdkVersion = "0.2.5",
            timestamp = fixedTimestamp(),
            taskId = "task-A",
            provider = "BackendProxyProvider",
            model = null,
            timing = TimingMetrics(startedAt = 1L, finishedAt = 501L),
            outcome = Outcome.Success,
            retries = emptyList(),
            privacy = privacySnapshot,
        )
        val session = DiagnosticReport(
            sdkVersion = "0.2.5",
            timestamp = fixedTimestamp(),
            taskId = null,
            provider = "BackendProxyProvider",
            model = "gpt-4o",
            timing = TimingMetrics.EMPTY,
            outcome = Outcome.Pending,
            retries = emptyList(),
            privacy = privacySnapshot,
            recentTasks = listOf(task),
        )

        val text = session.toShareableText()
        assertTrue(text.contains("Scope: session (provider=BackendProxyProvider, model=gpt-4o)"))
        assertFalse("session report should not have Task: line", text.contains("Task: "))
        assertTrue(text.contains("Recent tasks: 1"))
        assertTrue(text.contains("[0] task-A ok total=500ms"))
    }

    @Test
    fun `notes block is rendered when present`() {
        val report = DiagnosticReport(
            sdkVersion = "0.2.5",
            timestamp = fixedTimestamp(),
            taskId = "noted",
            provider = "FakeProvider",
            model = null,
            timing = TimingMetrics(startedAt = 1L, finishedAt = 11L),
            outcome = Outcome.Success,
            retries = emptyList(),
            privacy = privacySnapshot,
            notes = listOf("test note A", "test note B"),
        )

        val text = report.toShareableText()
        assertTrue(text.contains("Notes:"))
        assertTrue(text.contains("- test note A"))
        assertTrue(text.contains("- test note B"))
    }

    @Test
    fun `toJson is non-empty and contains key fields`() {
        val report = DiagnosticReport(
            sdkVersion = "0.2.5",
            timestamp = 1L,
            taskId = "j",
            provider = "P",
            model = "M",
            timing = TimingMetrics(startedAt = 1L, firstTokenAt = 5L, finishedAt = 10L),
            outcome = Outcome.Failure("E", "C", "msg with \"quote\""),
            retries = listOf(RetryAttempt(0, "r", 50L)),
            privacy = privacySnapshot,
        )

        val json = report.toJson()
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
        assertTrue(json.contains("\"sdkVersion\":\"0.2.5\""))
        assertTrue("escaped quote should survive", json.contains("\\\"quote\\\""))
        assertTrue(json.contains("\"kind\":\"failure\""))
        assertTrue(json.contains("\"errorCode\":\"C\""))
        assertTrue(json.contains("\"reason\":\"r\""))
    }

    @Test
    fun `iso8601 timestamp formatter is fixed UTC and parseable`() {
        val report = DiagnosticReport(
            sdkVersion = "0.2.5",
            timestamp = 0L, // epoch
            taskId = "x",
            provider = "P",
            model = null,
            timing = TimingMetrics.EMPTY,
            outcome = Outcome.Success,
            retries = emptyList(),
            privacy = privacySnapshot,
        )

        val text = report.toShareableText()
        assertTrue("epoch should render as 1970-01-01T00:00:00Z, got: $text",
            text.contains("Time: 1970-01-01T00:00:00Z"))
    }

    @Test
    fun `outcome ofError populates structured failure`() {
        val err = com.ailux.core.error.LLMError(
            code = com.ailux.core.error.ErrorCode.AUTH_FAILED,
            message = "no token",
            cause = IllegalArgumentException("bad token"),
        )
        val outcome = Outcome.ofError(err)
        assertEquals("AUTH_FAILED", outcome.errorCode)
        assertEquals("no token", outcome.errorMessage)
        assertEquals("IllegalArgumentException", outcome.errorClass)
    }
}
