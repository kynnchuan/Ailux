package com.ailux.api

import com.ailux.core.LLMProvider
import com.ailux.core.diagnostics.Outcome
import com.ailux.core.error.ErrorCode
import com.ailux.core.error.LLMError
import com.ailux.core.event.LLMEvent
import com.ailux.core.message.Message
import com.ailux.core.request.LLMRequest
import com.ailux.core.response.LLMResponse
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
 * Integration coverage of the diagnostics pipeline:
 *  1. Successful task → `lastDiagnostic` reports `Outcome.Success`.
 *  2. Provider Error event → `lastDiagnostic` reports `Outcome.Failure` with code.
 *  3. Concurrent reject → diagnostic still recorded.
 *  4. `createDiagnosticReport` returns recent task summaries (newest first).
 */
class AiluxClientDiagnosticsTest {

    private class FakeProvider(private val script: List<LLMEvent>) : LLMProvider {
        override fun streamGenerate(request: LLMRequest): Flow<LLMEvent> = flow {
            for (event in script) emit(event)
        }

        override suspend fun generate(request: LLMRequest): LLMResponse =
            LLMResponse(text = "noop")
    }

    private fun newClient(provider: LLMProvider, contextManager: Any? = null): AiluxClient {
        return AiluxClient(
            AiluxConfig.Builder()
                .setProvider(provider)
                .setContextManager(null) // disable context manager for predictable tests
                .build()
        )
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

        val task = client.streamGenerate(simpleRequest("ok-1"))
        task.events.toList()

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

        val task = client.streamGenerate(simpleRequest("fail-1"))
        task.events.toList()

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
        val task = client.streamGenerate(simpleRequest("inflight"))
        // Do NOT collect events — lastReport must still be null.
        assertNull(task.lastDiagnostic())
        client.release()
    }

    @Test
    fun `createDiagnosticReport surfaces recent finished tasks newest first`() = runBlocking {
        val client = newClient(
            FakeProvider(listOf(LLMEvent.Connected, LLMEvent.Token("x"), LLMEvent.Done()))
        )

        client.streamGenerate(simpleRequest("a")).events.toList()
        client.streamGenerate(simpleRequest("b")).events.toList()
        client.streamGenerate(simpleRequest("c")).events.toList()

        val session = client.createDiagnosticReport(includeRecentTasks = 5)
        assertNull("session report has no taskId", session.taskId)
        assertEquals(Outcome.Pending, session.outcome)
        assertEquals(3, session.recentTasks.size)
        assertEquals("c", session.recentTasks[0].taskId)
        assertEquals("b", session.recentTasks[1].taskId)
        assertEquals("a", session.recentTasks[2].taskId)

        client.release()
    }

    @Test
    fun `createDiagnosticReport respects includeRecentTasks cap`() = runBlocking {
        val client = newClient(
            FakeProvider(listOf(LLMEvent.Connected, LLMEvent.Done()))
        )

        repeat(4) { idx ->
            client.streamGenerate(simpleRequest("t$idx")).events.toList()
        }

        val session = client.createDiagnosticReport(includeRecentTasks = 2)
        assertEquals(2, session.recentTasks.size)
        assertEquals("t3", session.recentTasks[0].taskId)
        assertEquals("t2", session.recentTasks[1].taskId)

        client.release()
    }

    @Test
    fun `shareable text round-trips through createDiagnosticReport without crash`() = runBlocking {
        val client = newClient(
            FakeProvider(listOf(LLMEvent.Connected, LLMEvent.Token("x"), LLMEvent.Done()))
        )
        client.streamGenerate(simpleRequest("text-x")).events.toList()
        val text = client.createDiagnosticReport().toShareableText()
        assertTrue(text.contains("=== Ailux Diagnostic ==="))
        assertTrue(text.contains("Recent tasks: 1"))
        assertTrue(text.contains("text-x"))
        client.release()
    }
}
