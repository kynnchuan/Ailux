package com.ailux.provider.backend

import com.ailux.core.error.ErrorCode
import com.ailux.core.error.LLMException
import com.ailux.core.event.LLMEvent
import com.ailux.core.message.Message
import com.ailux.core.request.LLMRequest
import com.ailux.provider.backend.auth.AuthProvider
import com.ailux.provider.backend.auth.RequestSigner
import com.ailux.provider.backend.config.BackendProxyConfig
import com.ailux.provider.backend.config.RetryPolicy
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * v0.2.6 H-7 — end-to-end verification of the 401 → `onUnauthorized()` → replay
 * pipeline at the [BackendProxyProvider] layer. Uses [MockWebServer] to drive
 * real HTTP traffic so the OkHttp call path, the SSE event source, the
 * suspending `retryWhen`, and the `RequestSigner` insertion point all
 * exercise their production code paths.
 *
 * Test matrix (spec v0.2.6 §3.7):
 *
 *  | Scenario                                       | Expected terminal code |
 *  |------------------------------------------------|------------------------|
 *  | 401 + onUnauthorized()=true + replay ok        | success (200)          |
 *  | 401 + onUnauthorized()=true + second 401       | AUTH_EXPIRED           |
 *  | 401 + onUnauthorized()=false                   | AUTH_FAILED            |
 *  | 401 + onUnauthorized() throws                  | AUTH_FAILED            |
 *  | 401 + no AuthProvider                          | AUTH_FAILED            |
 *  | Replay budget independent of RetryPolicy.NONE  | replay still happens   |
 *  | RequestSigner output overrides Authorization   | signer wins            |
 *  | RequestSigner returns empty map                | no-op (request sent)   |
 */
class AuthReplayTest {

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

    // ──────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────

    private fun successResponse(): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """{"choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
            )

    private fun unauthorizedResponse(): MockResponse =
        MockResponse().setResponseCode(401)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"error":{"message":"token expired"}}""")

    private fun newRequest(requestId: String = "req-h7-1"): LLMRequest =
        LLMRequest(
            messages = listOf(Message.User("hi")),
            requestId = requestId,
        )

    private fun providerWith(
        authProvider: AuthProvider? = null,
        retryPolicy: RetryPolicy? = null,
        requestSigner: RequestSigner? = null,
    ) = BackendProxyProvider(
        BackendProxyConfig(
            baseUrl = server.url("/").toString().trimEnd('/'),
            generateEndpoint = "/v1/chat",
            streamEndpoint = "/v1/chat/stream",
            authProvider = authProvider,
            retryPolicy = retryPolicy,
            requestSigner = requestSigner,
        ),
    )

    // ──────────────────────────────────────────
    // Non-streaming path
    // ──────────────────────────────────────────

    @Test
    fun `401 plus successful refresh plus 200 yields success and the replayed request carries the new token`() = runTest {
        server.enqueue(unauthorizedResponse())
        server.enqueue(successResponse())

        val tokenCounter = AtomicInteger(1) // start at tok-1
        val refreshCount = AtomicInteger(0)
        val provider = providerWith(
            authProvider = object : AuthProvider {
                override suspend fun getAuthToken(): String =
                    "Bearer tok-${tokenCounter.get()}"
                override suspend fun onUnauthorized(): Boolean {
                    refreshCount.incrementAndGet()
                    tokenCounter.incrementAndGet() // rotate to tok-2
                    return true
                }
            },
        )

        val response = provider.generate(newRequest())
        assertNotNull("happy-path replay should produce a response", response)
        assertEquals("exactly one refresh expected", 1, refreshCount.get())

        val first = server.takeRequest(2, TimeUnit.SECONDS)!!
        val second = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals(
            "first request must carry the pre-refresh token",
            "Bearer tok-1",
            first.getHeader("Authorization"),
        )
        assertEquals(
            "replayed request must carry the freshly-rotated token",
            "Bearer tok-2",
            second.getHeader("Authorization"),
        )
    }

    @Test
    fun `401 then successful refresh then second 401 surfaces AUTH_EXPIRED (not AUTH_FAILED)`() = runTest {
        server.enqueue(unauthorizedResponse())
        server.enqueue(unauthorizedResponse()) // replay also 401 → terminal

        val provider = providerWith(
            authProvider = object : AuthProvider {
                override suspend fun getAuthToken() = "Bearer x"
                override suspend fun onUnauthorized() = true
            },
        )

        val thrown = runCatching { provider.generate(newRequest()) }.exceptionOrNull()
        val ex = thrown as? LLMException ?: fail("expected LLMException, got $thrown")
        assertEquals(
            "refresh-attempted-but-still-failed must surface as AUTH_EXPIRED " +
                "so UIs distinguish silent re-login from hard logout",
            ErrorCode.AUTH_EXPIRED,
            (ex as LLMException).error.code,
        )
    }

    @Test
    fun `401 with onUnauthorized returning false yields terminal AUTH_FAILED and no replay`() = runTest {
        server.enqueue(unauthorizedResponse())
        // No second enqueue — if replay fires, MockWebServer will hang and fail the test.

        val provider = providerWith(
            authProvider = object : AuthProvider {
                override suspend fun getAuthToken() = "Bearer x"
                override suspend fun onUnauthorized() = false
            },
        )

        val thrown = runCatching { provider.generate(newRequest()) }.exceptionOrNull()
        val ex = thrown as? LLMException ?: fail("expected LLMException, got $thrown")
        assertEquals(ErrorCode.AUTH_FAILED, (ex as LLMException).error.code)
        assertEquals("no replay should have fired", 1, server.requestCount)
    }

    @Test
    fun `401 with onUnauthorized throwing is treated as refresh-declined`() = runTest {
        server.enqueue(unauthorizedResponse())

        val provider = providerWith(
            authProvider = object : AuthProvider {
                override suspend fun getAuthToken() = "Bearer x"
                override suspend fun onUnauthorized(): Boolean = error("IdP down")
            },
        )

        val thrown = runCatching { provider.generate(newRequest()) }.exceptionOrNull()
        val ex = thrown as? LLMException ?: fail("expected LLMException, got $thrown")
        assertEquals(ErrorCode.AUTH_FAILED, (ex as LLMException).error.code)
        assertEquals("no replay after onUnauthorized() threw", 1, server.requestCount)
    }

    @Test
    fun `401 without AuthProvider is terminal AUTH_FAILED (no replay machinery activated)`() = runTest {
        server.enqueue(unauthorizedResponse())

        val provider = providerWith(authProvider = null)

        val thrown = runCatching { provider.generate(newRequest()) }.exceptionOrNull()
        val ex = thrown as? LLMException ?: fail("expected LLMException, got $thrown")
        assertEquals(ErrorCode.AUTH_FAILED, (ex as LLMException).error.code)
    }

    @Test
    fun `auth replay works even with RetryPolicy NONE (budget is independent)`() = runTest {
        // RetryPolicy.NONE governs transient-error retries, NOT auth replay —
        // these are two orthogonal budgets per spec v0.2.6 §3.7.
        server.enqueue(unauthorizedResponse())
        server.enqueue(successResponse())

        val refreshCount = AtomicInteger(0)
        val provider = providerWith(
            authProvider = object : AuthProvider {
                override suspend fun getAuthToken() = "Bearer x"
                override suspend fun onUnauthorized(): Boolean {
                    refreshCount.incrementAndGet(); return true
                }
            },
            retryPolicy = RetryPolicy.NONE, // explicitly no transient retries
        )

        provider.generate(newRequest())
        assertEquals(1, refreshCount.get())
        assertEquals("one initial + one replay = two calls", 2, server.requestCount)
    }

    @Test
    fun `replay budget is one — two consecutive 401s do not trigger two refreshes`() = runTest {
        server.enqueue(unauthorizedResponse())
        server.enqueue(unauthorizedResponse())

        val refreshCount = AtomicInteger(0)
        val provider = providerWith(
            authProvider = object : AuthProvider {
                override suspend fun getAuthToken() = "Bearer x"
                override suspend fun onUnauthorized(): Boolean {
                    refreshCount.incrementAndGet(); return true
                }
            },
        )

        runCatching { provider.generate(newRequest()) }
        assertEquals("onUnauthorized() must be called at most once per task", 1, refreshCount.get())
    }

    // ──────────────────────────────────────────
    // RequestSigner integration
    // ──────────────────────────────────────────

    @Test
    fun `RequestSigner runs last and overrides the Authorization header`() = runTest {
        server.enqueue(successResponse())

        val provider = providerWith(
            authProvider = AuthProvider { "Bearer original" },
            requestSigner = RequestSigner { req ->
                // Sanity-check the snapshot we received.
                assertEquals("POST", req.method)
                assertEquals("Bearer original", req.headers["Authorization"])
                assertTrue(
                    "signer must see the JSON body",
                    req.body.contains("\"messages\""),
                )
                mapOf("Authorization" to "Signature signed-by-test")
            },
        )

        provider.generate(newRequest())

        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals(
            "signer's Authorization must win over AuthProvider's",
            "Signature signed-by-test",
            recorded.getHeader("Authorization"),
        )
    }

    @Test
    fun `RequestSigner returning empty map is a no-op`() = runTest {
        server.enqueue(successResponse())

        val provider = providerWith(
            authProvider = AuthProvider { "Bearer kept" },
            requestSigner = RequestSigner { emptyMap() },
        )

        provider.generate(newRequest())

        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals(
            "AuthProvider's Authorization is preserved when signer returns empty",
            "Bearer kept",
            recorded.getHeader("Authorization"),
        )
    }

    // ──────────────────────────────────────────
    // Streaming path
    // ──────────────────────────────────────────

    @Test
    fun `streaming 401 plus refresh plus stream succeeds and emits no spurious error`() = runTest {
        // First attempt: 401 → close with BackendUnauthorizedException → retryWhen
        // calls onUnauthorized() and replays.
        // Second attempt: successful SSE stream.
        server.enqueue(unauthorizedResponse())
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n" +
                        "data: [DONE]\n\n"
                )
        )

        val refreshCount = AtomicInteger(0)
        val provider = providerWith(
            authProvider = object : AuthProvider {
                override suspend fun getAuthToken() = "Bearer x"
                override suspend fun onUnauthorized(): Boolean {
                    refreshCount.incrementAndGet(); return true
                }
            },
        )

        val events = provider.streamGenerate(newRequest()).toList()
        assertEquals("exactly one refresh expected on stream replay", 1, refreshCount.get())
        // The collector must NOT see an LLMEvent.Error from the 401 — that's
        // the whole point of marking the 401 close as a marker exception
        // resolved transparently in retryWhen.
        assertTrue(
            "no spurious Error event must leak from the 401 path: $events",
            events.none { it is LLMEvent.Error },
        )
        assertTrue("stream must terminate with Done", events.last() is LLMEvent.Done)
    }

    @Test
    fun `streaming 401 plus refresh-declined surfaces terminal AUTH_FAILED on the stream`() = runTest {
        server.enqueue(unauthorizedResponse())

        val provider = providerWith(
            authProvider = object : AuthProvider {
                override suspend fun getAuthToken() = "Bearer x"
                override suspend fun onUnauthorized() = false
            },
        )

        val events = provider.streamGenerate(newRequest()).toList()
        val errors = events.filterIsInstance<LLMEvent.Error>()
        assertEquals(1, errors.size)
        assertEquals(ErrorCode.AUTH_FAILED, errors.single().error.code)
        assertTrue("stream must terminate with Done", events.last() is LLMEvent.Done)
    }

    @Test
    fun `streaming 401 plus refresh ok plus second 401 surfaces AUTH_EXPIRED on the stream`() = runTest {
        server.enqueue(unauthorizedResponse())
        server.enqueue(unauthorizedResponse())

        val provider = providerWith(
            authProvider = object : AuthProvider {
                override suspend fun getAuthToken() = "Bearer x"
                override suspend fun onUnauthorized() = true
            },
        )

        val events = provider.streamGenerate(newRequest()).toList()
        val errors = events.filterIsInstance<LLMEvent.Error>()
        assertEquals(1, errors.size)
        assertEquals(
            "stream replay path must mirror the non-stream path's AUTH_EXPIRED semantics",
            ErrorCode.AUTH_EXPIRED,
            errors.single().error.code,
        )
    }
}
