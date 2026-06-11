package com.ailux.provider.backend

import com.ailux.core.error.LLMException
import com.ailux.core.request.LLMRequest
import com.ailux.core.request.Message
import com.ailux.provider.backend.config.BackendProxyConfig
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Verifies the v0.2.4 `Idempotency-Key` HTTP header injection at the
 * [BackendProxyProvider] layer (i.e. above the mapper layer that the existing
 * mapper unit tests cover). Uses MockWebServer to capture the actual outbound
 * request — checking the wire bytes is the only way to be sure the header
 * survives the OkHttp call pipeline.
 */
class IdempotencyHeaderTest {

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

    private fun successResponse(): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"text":"ok"}""")

    private fun newRequest(requestId: String = "req-fixed-1"): LLMRequest =
        LLMRequest(
            messages = listOf(Message.User("hi")),
            requestId = requestId,
        )

    @Test
    fun `default config injects Idempotency-Key header with requestId`() = runTest {
        server.enqueue(successResponse())

        val provider = BackendProxyProvider(
            BackendProxyConfig(
                baseUrl = server.url("/").toString().trimEnd('/'),
                generateEndpoint = "/v1/chat",
            )
        )

        provider.generate(newRequest("req-abc-123"))

        val recorded = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("server should have received a request", recorded)
        assertEquals("req-abc-123", recorded!!.getHeader("Idempotency-Key"))
    }

    @Test
    fun `custom header name is honored`() = runTest {
        server.enqueue(successResponse())

        val provider = BackendProxyProvider(
            BackendProxyConfig(
                baseUrl = server.url("/").toString().trimEnd('/'),
                generateEndpoint = "/v1/chat",
                idempotencyHeaderName = "X-Idempotency",
            )
        )

        provider.generate(newRequest("req-custom-1"))

        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("req-custom-1", recorded.getHeader("X-Idempotency"))
        assertNull(
            "default header must not be sent when a custom name is configured",
            recorded.getHeader("Idempotency-Key"),
        )
    }

    @Test
    fun `null header name disables injection entirely`() = runTest {
        server.enqueue(successResponse())

        val provider = BackendProxyProvider(
            BackendProxyConfig(
                baseUrl = server.url("/").toString().trimEnd('/'),
                generateEndpoint = "/v1/chat",
                idempotencyHeaderName = null,
            )
        )

        provider.generate(newRequest())

        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertNull(recorded.getHeader("Idempotency-Key"))
    }

    @Test
    fun `automatic retries reuse the same Idempotency-Key`() = runTest {
        // First attempt: 503 (retriable). Second attempt: 200.
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(successResponse())

        val provider = BackendProxyProvider(
            BackendProxyConfig(
                baseUrl = server.url("/").toString().trimEnd('/'),
                generateEndpoint = "/v1/chat",
                retryCount = 1,
            )
        )

        // generate() does not retry on its own (only streamGenerate does), so we
        // simulate a retry by calling twice with the same request — this models
        // what an upstream retry decorator would do, and proves the header is
        // *stable across calls* with the same LLMRequest, which is what the
        // server-side dedup contract requires.
        val request = newRequest("req-retry-stable-1")
        try {
            provider.generate(request)
        } catch (_: LLMException) {
            // first call surfaces the 503 — that is fine for this test
        }
        provider.generate(request)

        val first = server.takeRequest(2, TimeUnit.SECONDS)!!
        val second = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("req-retry-stable-1", first.getHeader("Idempotency-Key"))
        assertEquals(
            "retried call must carry the same Idempotency-Key",
            first.getHeader("Idempotency-Key"),
            second.getHeader("Idempotency-Key"),
        )
    }
}
