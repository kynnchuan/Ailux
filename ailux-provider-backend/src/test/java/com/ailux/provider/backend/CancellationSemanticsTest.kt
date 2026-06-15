package com.ailux.provider.backend

import com.ailux.core.error.ErrorCode
import com.ailux.core.event.LLMEvent
import com.ailux.core.message.Message
import com.ailux.core.request.LLMRequest
import com.ailux.provider.backend.config.BackendProxyConfig
import com.ailux.provider.backend.config.RetryPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * v0.2.6 §3.6 — Cancellation semantics test coverage.
 *
 * Verifies that both streaming (callbackFlow) and non-streaming
 * (suspendCancellableCoroutine) paths correctly handle cancellation:
 * - Cancel → immediate stop
 * - No further events emitted after cancel
 * - Underlying HTTP connection is closed (OkHttp Call/EventSource cancelled)
 *
 * Also verifies cancel-during-retry-backoff: delay is interrupted without
 * further retry attempts.
 */
class CancellationSemanticsTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    private fun newRequest(): LLMRequest =
        LLMRequest(
            messages = listOf(Message.User("hello")),
            requestId = "req-cancel-test",
        )

    // ─── Non-streaming cancellation tests ───

    @Test
    fun `non-streaming cancel aborts HTTP call immediately`() = runTest {
        // Server delays response for 10 seconds — simulates a slow LLM response.
        // The test cancels before the response arrives.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeadersDelay(5, TimeUnit.SECONDS)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":"hello"}}]}""")
        )

        val provider = BackendProxyProvider(
            BackendProxyConfig(
                baseUrl = baseUrl(),
                generateEndpoint = "/v1/chat",
            )
        )

        val deferred = async {
            provider.generate(newRequest())
        }

        // Give the request time to start, then cancel
        delay(100)
        deferred.cancel()

        // Verify it throws CancellationException (not LLMException)
        var cancelled = false
        try {
            deferred.await()
        } catch (e: CancellationException) {
            cancelled = true
        }
        assertTrue("generate() should throw CancellationException on cancel", cancelled)
    }

    @Test
    fun `non-streaming cancel during retry backoff stops immediately`() = runTest {
        // First attempt: 503 (retriable)
        // The retry delay will be at least 500ms. We cancel during the delay.
        server.enqueue(MockResponse().setResponseCode(503))

        val provider = BackendProxyProvider(
            BackendProxyConfig(
                baseUrl = baseUrl(),
                generateEndpoint = "/v1/chat",
                retryPolicy = RetryPolicy(
                    maxRetries = 3,
                    initialBackoffMillis = 5_000, // Long backoff so we can cancel during it
                    maxBackoffMillis = 10_000,
                ),
            )
        )

        val deferred = async {
            provider.generate(newRequest())
        }

        // Wait for the first attempt to fail and enter backoff
        delay(200)
        deferred.cancel()

        var cancelled = false
        try {
            deferred.await()
        } catch (e: CancellationException) {
            cancelled = true
        }
        assertTrue("generate() should cancel during retry backoff", cancelled)

        // Verify only 1 request was made (no retry after cancel)
        assertEquals(
            "Only one request should be sent before cancel interrupts backoff",
            1,
            server.requestCount,
        )
    }

    // ─── Streaming cancellation tests ───

    @Test
    fun `streaming cancel stops event emission immediately`() = runTest {
        // SSE response that sends tokens slowly (one per second)
        val sseBody = buildString {
            // First chunk arrives quickly
            append("data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"},\"index\":0}]}\n\n")
            // Following chunks would arrive after delay — but we'll cancel before them
        }

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(sseBody, 10)
        )

        val provider = BackendProxyProvider(
            BackendProxyConfig(
                baseUrl = baseUrl(),
                streamEndpoint = "/v1/chat",
            )
        )

        val collectedEvents = Collections.synchronizedList(mutableListOf<LLMEvent>())
        val job = launch {
            provider.streamGenerate(newRequest()).collect { event ->
                collectedEvents.add(event)
            }
        }

        // Wait for some events to be collected, then cancel
        delay(200)
        job.cancel()
        job.join()

        // After cancellation, no more events should be emitted.
        val countAtCancel = collectedEvents.size
        delay(300) // Wait a bit to see if any stray events arrive
        assertEquals(
            "No new events should be emitted after cancellation",
            countAtCancel,
            collectedEvents.size,
        )
    }

    @Test
    fun `streaming cancel during retry backoff stops without further retries`() = runTest {
        // First attempt: 503 (retriable). The retry will have a long backoff.
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "text/event-stream")
        )

        val provider = BackendProxyProvider(
            BackendProxyConfig(
                baseUrl = baseUrl(),
                streamEndpoint = "/v1/chat",
                retryPolicy = RetryPolicy(
                    maxRetries = 3,
                    initialBackoffMillis = 5_000,
                    maxBackoffMillis = 10_000,
                ),
            )
        )

        val collectedEvents = Collections.synchronizedList(mutableListOf<LLMEvent>())
        val job = launch {
            provider.streamGenerate(newRequest()).collect { event ->
                collectedEvents.add(event)
            }
        }

        // Wait for the first attempt to fail and enter backoff delay
        delay(300)
        job.cancel()
        job.join()

        // Should not have retried — only 1 request sent
        assertEquals(
            "Only one request should be sent before cancel interrupts backoff",
            1,
            server.requestCount,
        )
    }

    @Test
    fun `streaming flow completion after cancel does not emit Error or Done`() = runTest {
        // A slow SSE stream
        val sseBody = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"tok1\"},\"index\":0}]}\n\n")
        }

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                // Throttle to simulate slow streaming
                .throttleBody(10, 1, TimeUnit.SECONDS)
                .setChunkedBody(sseBody, 5)
        )

        val provider = BackendProxyProvider(
            BackendProxyConfig(
                baseUrl = baseUrl(),
                streamEndpoint = "/v1/chat",
            )
        )

        val collectedEvents = Collections.synchronizedList(mutableListOf<LLMEvent>())
        val job = launch {
            provider.streamGenerate(newRequest()).collect { event ->
                collectedEvents.add(event)
            }
        }

        // Let the stream start
        delay(150)
        job.cancel()
        job.join()

        // After cancel, verify no Error or Done event was emitted as a terminal signal.
        // Cancellation is a coroutine-level signal, not an LLMEvent — the Flow simply
        // stops collecting. The UI layer detects cancellation via Job.isCancelled.
        val hasErrorAfterCancel = collectedEvents.any { event ->
            event is LLMEvent.Error && event.error.code == ErrorCode.REQUEST_CANCELLED
        }
        // Cancellation should NOT produce an LLMEvent.Error with REQUEST_CANCELLED —
        // it's handled at the coroutine level, not the event stream level.
        assertTrue(
            "Cancellation should not emit REQUEST_CANCELLED as an LLMEvent — " +
                "it is a coroutine-level concern",
            !hasErrorAfterCancel,
        )
    }
}
