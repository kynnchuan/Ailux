package com.ailux.api

import com.ailux.core.LLMProvider
import com.ailux.core.diagnostics.Outcome
import com.ailux.core.error.ErrorCode
import com.ailux.core.error.LLMError
import com.ailux.core.event.LLMEvent
import com.ailux.core.message.Message
import com.ailux.core.request.LLMRequest
import com.ailux.core.response.LLMResponse
import com.ailux.core.session.Session
import com.ailux.core.session.SessionConfig
import com.ailux.core.session.StatelessProviderSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration coverage of the diagnostics pipeline (post-v0.3.0b — Session-only):
 *  1. Successful task → `lastDiagnostic` reports `Outcome.Success`.
 *  2. Provider Error event → `lastDiagnostic` reports `Outcome.Failure` with code.
 *  3. Concurrent reject → diagnostic still recorded.
 *  4. `createDiagnosticReport` returns recent task summaries (newest first).
 *
 * After ADR-0009 the entry point is `client.openSession().streamGenerateAsTask(req)`
 * rather than the old `client.streamGenerate(req)`. The diagnostics ring buffer
 * is fed by the same [com.ailux.api.session.SessionPipeline] that powers the
 * pipelined Session, so the behaviours covered here are unchanged.
 */
class AiluxClientDiagnosticsTest {

    private class FakeProvider(private val script: List<LLMEvent>) : LLMProvider {
        // Bare-minimum capabilities for the diagnostics test path; default
        // maxConcurrentSessions = Int.MAX_VALUE so the coordinator never gates
        // the test tasks.
        override val capabilities: com.ailux.core.capabilities.ProviderCapabilities =
            com.ailux.core.capabilities.ProviderCapabilities(
                supportsTool = false,
                supportsStream = true,
                supportsVision = false,
                maxContextToken = null,
                supportsInterruptibleCancellation = true,
                maxConcurrentSessions = Int.MAX_VALUE,
            )

        override fun streamGenerate(request: LLMRequest): Flow<LLMEvent> = flow {
            for (event in script) emit(event)
        }

        override suspend fun generate(request: LLMRequest): LLMResponse =
            LLMResponse(text = "noop")

        // Session-aware path (since v0.3.0): expose the same script via a
        // StatelessProviderSession so the AiluxClient pipeline can drive it
        // through the same code path used by real cloud / proxy providers.
        override fun openSession(config: SessionConfig): Session =
            StatelessProviderSession(
                config = config,
                streamGenerateRaw = ::streamGenerate,
            )
    }

    private fun newClient(provider: LLMProvider): AiluxClient {
        return AiluxClient(
            AiluxConfig.Builder()
                .setProvider(provider)
                .setContextManager(null) // disable context manager for predictable tests
                .build()
        )
    }

    /**
     * Helper: fire a single pipelined task on a fresh anonymous session and
     * drain its events. Returns the [com.ailux.core.task.LLMTask] so callers
     * can read [com.ailux.core.task.LLMTask.lastDiagnostic] afterwards.
     */
    private suspend fun fireAndDrain(
        client: AiluxClient,
        requestId: String,
    ): com.ailux.core.task.LLMTask {
        val session = client.openSession()
        val task = session.streamGenerateAsTask(simpleRequest(requestId))
        try {
            task.events.toList()
        } finally {
            session.close()
        }
        return task
    }

    private fun simpleRequest(id: String) = LLMRequest(
        requestId = id,
        messages = listOf(Message.User("hello")),
    )

    @Test
    fun `successful stream yields Success outcome with TTFT and duration populated`() = runBlocking {
        val client = newClient(
            FakeProvider(
                listOf(
                    LLMEvent.Connected,
                    LLMEvent.Token("hi"),
                    LLMEvent.Token(" there"),
                    LLMEvent.Done(),
                )
            )
        )

        val task = fireAndDrain(client, "ok-1")

        val report = task.lastDiagnostic()
        assertNotNull("lastDiagnostic must be non-null after terminal state", report)
        report!!
        assertEquals(Outcome.Success, report.outcome)
        assertEquals("ok-1", report.taskId)
        assertNotNull(report.timing.ttftMs)
        assertNotNull(report.timing.durationMs)
        assertTrue(
            "TTFT should be <= total duration",
            (report.timing.ttftMs ?: Long.MAX_VALUE) <= (report.timing.durationMs ?: 0L)
        )
        assertEquals("FakeProvider", report.provider)

        client.release()
    }

    @Test
    fun `provider error event maps to Failure outcome`() = runBlocking {
        val err = LLMError(code = ErrorCode.RATE_LIMITED, message = "throttled")
        val client = newClient(
            FakeProvider(
                listOf(
                    LLMEvent.Connected,
                    LLMEvent.Error(err),
                    LLMEvent.Done(),
                )
            )
        )

        val task = fireAndDrain(client, "fail-1")

        val outcome = task.lastDiagnostic()!!.outcome
        assertTrue(outcome is Outcome.Failure)
        outcome as Outcome.Failure
        assertEquals("RATE_LIMITED", outcome.errorCode)
        assertEquals("throttled", outcome.errorMessage)

        client.release()
    }

    @Test
    fun `lastDiagnostic is null while task is in flight`() {
        val client = newClient(FakeProvider(listOf(LLMEvent.Connected, LLMEvent.Done())))
        val session = client.openSession()
        val task = session.streamGenerateAsTask(simpleRequest("inflight"))
        // Do NOT collect events — lastReport must still be null.
        assertNull(task.lastDiagnostic())
        session.close()
        client.release()
    }

    @Test
    fun `createDiagnosticReport surfaces recent finished tasks newest first`() = runBlocking {
        val client = newClient(
            FakeProvider(listOf(LLMEvent.Connected, LLMEvent.Token("x"), LLMEvent.Done()))
        )

        fireAndDrain(client, "a")
        fireAndDrain(client, "b")
        fireAndDrain(client, "c")

        val sessionReport = client.createDiagnosticReport(includeRecentTasks = 5)
        assertNull("session report has no taskId", sessionReport.taskId)
        assertEquals(Outcome.Pending, sessionReport.outcome)
        assertEquals(3, sessionReport.recentTasks.size)
        assertEquals("c", sessionReport.recentTasks[0].taskId)
        assertEquals("b", sessionReport.recentTasks[1].taskId)
        assertEquals("a", sessionReport.recentTasks[2].taskId)

        client.release()
    }

    @Test
    fun `createDiagnosticReport respects includeRecentTasks cap`() = runBlocking {
        val client = newClient(
            FakeProvider(listOf(LLMEvent.Connected, LLMEvent.Done()))
        )

        repeat(4) { idx -> fireAndDrain(client, "t$idx") }

        val sessionReport = client.createDiagnosticReport(includeRecentTasks = 2)
        assertEquals(2, sessionReport.recentTasks.size)
        assertEquals("t3", sessionReport.recentTasks[0].taskId)
        assertEquals("t2", sessionReport.recentTasks[1].taskId)

        client.release()
    }

    @Test
    fun `shareable text round-trips through createDiagnosticReport without crash`() = runBlocking {
        val client = newClient(
            FakeProvider(listOf(LLMEvent.Connected, LLMEvent.Token("x"), LLMEvent.Done()))
        )
        fireAndDrain(client, "text-x")
        val text = client.createDiagnosticReport().toShareableText()
        assertTrue(text.contains("=== Ailux Diagnostic ==="))
        assertTrue(text.contains("Recent tasks: 1"))
        assertTrue(text.contains("text-x"))
        client.release()
    }
}
