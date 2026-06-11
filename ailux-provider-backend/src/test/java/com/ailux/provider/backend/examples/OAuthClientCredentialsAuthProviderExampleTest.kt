package com.ailux.provider.backend.examples

import com.ailux.provider.backend.auth.AuthProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * **v0.2.5 G-2 extension-point example — `AuthProvider`.**
 *
 * Demonstrates an OAuth 2.0 *client_credentials* flow with **concurrent-safe
 * auto-refresh**:
 *
 *  - On first call → fetch a token, cache it with its expiry.
 *  - On subsequent calls → return the cached token until it nears expiry,
 *    then refresh once.
 *  - Under bursts of parallel requests that all see an expired token, only
 *    **one** refresh actually fires; the others wait and reuse it.
 *
 * Why this is the right place for an example: simple `Bearer` tokens fit the
 * built-in `staticToken()` helper (TODO: add). Anything that needs a refresh
 * loop, mutex, or token endpoint round-trip belongs here — and the SDK gives
 * you `suspend fun getAuthToken(): String` precisely so you can do async I/O
 * without blocking.
 *
 * The actual HTTP call to the token endpoint is stubbed out as
 * [stubFetchToken] for unit-testability; in production you'd issue an OkHttp
 * call to `https://your.idp/oauth/token` and parse the JSON response.
 *
 * @see AuthProvider
 */
class OAuthClientCredentialsAuthProviderExampleTest {

    /** Encapsulated example implementation — the focus of this file. */
    private class OAuthAuthProvider(
        private val refreshSkewMillis: Long = 30_000L,
        private val clock: () -> Long = System::currentTimeMillis,
        private val fetcher: suspend () -> CachedToken,
    ) : AuthProvider {

        data class CachedToken(val accessToken: String, val expiresAtMillis: Long)

        private val mutex = Mutex()
        @Volatile private var cached: CachedToken? = null

        override suspend fun getAuthToken(): String {
            // Fast path — no lock if cache is fresh.
            cached?.let { token ->
                if (clock() < token.expiresAtMillis - refreshSkewMillis) {
                    return "Bearer ${token.accessToken}"
                }
            }
            // Slow path — single-flight refresh.
            return mutex.withLock {
                // Double-check inside the lock: a previous waiter may have
                // already refreshed.
                val current = cached
                if (current != null && clock() < current.expiresAtMillis - refreshSkewMillis) {
                    return@withLock "Bearer ${current.accessToken}"
                }
                val fresh = fetcher()
                cached = fresh
                "Bearer ${fresh.accessToken}"
            }
        }
    }

    @Test
    fun `first call triggers fetch, second call reuses cache`() = runBlocking {
        val fetchCount = AtomicInteger(0)
        var now = 0L

        val provider = OAuthAuthProvider(
            refreshSkewMillis = 1_000L,
            clock = { now },
            fetcher = {
                fetchCount.incrementAndGet()
                OAuthAuthProvider.CachedToken(
                    accessToken = "tok-${fetchCount.get()}",
                    expiresAtMillis = now + 60_000L,
                )
            },
        )

        assertEquals("Bearer tok-1", provider.getAuthToken())
        now += 5_000L
        assertEquals("Bearer tok-1", provider.getAuthToken())
        assertEquals("only the first call hits the network", 1, fetchCount.get())
    }

    @Test
    fun `expiry past skew window triggers refresh`() = runBlocking {
        val fetchCount = AtomicInteger(0)
        var now = 0L

        val provider = OAuthAuthProvider(
            refreshSkewMillis = 5_000L,
            clock = { now },
            fetcher = {
                fetchCount.incrementAndGet()
                OAuthAuthProvider.CachedToken(
                    accessToken = "tok-${fetchCount.get()}",
                    expiresAtMillis = now + 30_000L,
                )
            },
        )

        val first = provider.getAuthToken()
        now += 28_000L // within 5s of expiry → refresh
        val second = provider.getAuthToken()

        assertEquals("Bearer tok-1", first)
        assertEquals("Bearer tok-2", second)
        assertEquals(2, fetchCount.get())
    }

    @Test
    fun `concurrent expired access fires exactly one refresh (single-flight)`() = runBlocking {
        val fetchCount = AtomicInteger(0)
        var now = 0L

        val provider = OAuthAuthProvider(
            refreshSkewMillis = 1_000L,
            clock = { now },
            fetcher = {
                // Simulate slow IdP — 50ms
                delay(50)
                fetchCount.incrementAndGet()
                OAuthAuthProvider.CachedToken(
                    accessToken = "tok-${fetchCount.get()}",
                    expiresAtMillis = now + 60_000L,
                )
            },
        )

        // 16 concurrent callers all racing on a cold cache.
        val results: List<String> = coroutineScope {
            (1..16).map {
                async { provider.getAuthToken() }
            }.awaitAll()
        }

        assertEquals("single-flight: only one fetch must fire", 1, fetchCount.get())
        assertTrue("every caller must observe the same token", results.toSet().size == 1)
        assertEquals("Bearer tok-1", results.first())
    }

    @Test
    fun `fetcher exception propagates so SDK can map to AUTH_FAILED`() = runBlocking {
        val provider = OAuthAuthProvider(fetcher = { error("IdP unreachable") })
        val thrown = runCatching { provider.getAuthToken() }.exceptionOrNull()
        assertNotEquals(null, thrown)
        assertEquals("IdP unreachable", thrown!!.message)
    }
}
