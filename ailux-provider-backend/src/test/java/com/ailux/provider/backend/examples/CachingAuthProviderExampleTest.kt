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
 * **v0.2.6 H-7 extension-point example — `AuthProvider.onUnauthorized` with
 * single-flight refresh.**
 *
 * The companion example [OAuthClientCredentialsAuthProviderExampleTest] shows
 * how to **avoid** 401s (proactive refresh ahead of expiry, driven by
 * `expiresAtMillis`). This example tackles the orthogonal problem: what to do
 * when a 401 actually escapes — typically because the server invalidated the
 * session early (revocation, key-rotation, forced re-auth), or because the
 * client's clock drifted past the skew window.
 *
 * The SDK calls [AuthProvider.onUnauthorized] once per task on the first 401.
 * If it returns `true`, the SDK replays the request through the same
 * [com.ailux.provider.backend.config.RetryPolicy] pipeline. Two implementation
 * pitfalls this example demonstrates:
 *
 *  - **Concurrent 401s share one refresh.** With N requests in flight against a
 *    just-revoked credential, N `onUnauthorized()` calls fire simultaneously.
 *    Without single-flight you stampede the IdP and may invalidate each others'
 *    freshly issued tokens. A simple [Mutex] + "did somebody else already
 *    refresh while I was waiting?" check is enough.
 *  - **Refresh has to invalidate the cache, not just append.** If you only
 *    overwrite when the IdP call succeeds, a refresh-then-still-401 (rare but
 *    possible — race with a revocation propagating) leaves stale state. Clear
 *    first, then write fresh.
 *
 * The actual IdP call is stubbed via [refresher] for unit-testability.
 *
 * @see AuthProvider.onUnauthorized
 */
class CachingAuthProviderExampleTest {

    /**
     * Reference implementation — the focus of this file.
     *
     * In production, [refresher] would be an OkHttp call to your IdP's
     * `/token` endpoint with `grant_type=refresh_token` (or whatever your
     * flow uses).
     */
    private class CachingAuthProvider(
        private val refresher: suspend () -> String,
    ) : AuthProvider {

        private val mutex = Mutex()
        @Volatile private var cachedToken: String? = null

        /** Token version increments on every successful refresh. Used by
         *  the double-check inside the mutex. */
        @Volatile private var version: Int = 0

        override suspend fun getAuthToken(): String {
            return "Bearer ${cachedToken ?: "uninitialized"}"
        }

        override suspend fun onUnauthorized(): Boolean {
            // Snapshot the version we observed before contending for the lock.
            // If somebody else refreshes while we wait, our snapshot will be
            // out of date and we skip the IdP call entirely.
            val observed = version
            return mutex.withLock {
                if (version != observed) {
                    // Another waiter already refreshed; reuse their work.
                    return@withLock true
                }
                runCatching {
                    val fresh = refresher()
                    cachedToken = fresh
                    version++
                }.isSuccess
            }
        }

        fun seedToken(token: String) {
            cachedToken = token
        }
    }

    @Test
    fun `successful refresh returns true and rotates the cached token`() = runBlocking {
        val refreshCount = AtomicInteger(0)
        val provider = CachingAuthProvider {
            "tok-${refreshCount.incrementAndGet()}"
        }
        provider.seedToken("expired-tok")

        val refreshed = provider.onUnauthorized()

        assertEquals(true, refreshed)
        assertEquals(1, refreshCount.get())
        assertEquals("Bearer tok-1", provider.getAuthToken())
    }

    @Test
    fun `failed refresh returns false so SDK falls back to AUTH_FAILED`() = runBlocking {
        val provider = CachingAuthProvider { error("IdP unreachable") }
        val refreshed = provider.onUnauthorized()
        assertEquals(false, refreshed)
    }

    @Test
    fun `16 concurrent 401s trigger exactly one refresh (single-flight)`() = runBlocking {
        val refreshCount = AtomicInteger(0)
        val provider = CachingAuthProvider {
            // Simulate slow IdP — 30ms — to ensure callers genuinely race.
            delay(30)
            "tok-${refreshCount.incrementAndGet()}"
        }
        provider.seedToken("expired-tok")

        val results: List<Boolean> = coroutineScope {
            (1..16).map { async { provider.onUnauthorized() } }.awaitAll()
        }

        assertEquals(
            "exactly one IdP round-trip must fire across 16 concurrent 401s",
            1, refreshCount.get(),
        )
        assertTrue("every caller must observe success", results.all { it })
        assertEquals("Bearer tok-1", provider.getAuthToken())
    }

    @Test
    fun `second wave of 401s after a successful refresh triggers a second refresh`() = runBlocking {
        // Models the realistic scenario where a long-lived process refreshes,
        // operates for a while, then loses the credential again (e.g. another
        // revocation event). Each wave should be a fresh single-flight cycle.
        val refreshCount = AtomicInteger(0)
        val provider = CachingAuthProvider { "tok-${refreshCount.incrementAndGet()}" }
        provider.seedToken("expired-tok-A")

        assertEquals(true, provider.onUnauthorized())
        assertEquals(1, refreshCount.get())
        assertEquals("Bearer tok-1", provider.getAuthToken())

        // Time passes. Credential gets revoked server-side again.
        assertEquals(true, provider.onUnauthorized())
        assertEquals(2, refreshCount.get())
        assertEquals("Bearer tok-2", provider.getAuthToken())
    }

    @Test
    fun `exception inside refresher is swallowed — returns false, no SDK crash`() = runBlocking {
        // The SDK treats both `false` and a thrown exception as "refresh
        // declined" → terminal AUTH_FAILED. Returning `false` is preferred
        // (no exception construction on the hot path), but this test makes
        // sure throwing implementations don't break a caller's expectations.
        val provider = CachingAuthProvider { throw RuntimeException("boom") }
        val refreshed = runCatching { provider.onUnauthorized() }.getOrDefault(true)
        assertNotEquals(
            "implementation must not let the throw escape uncaught",
            true, refreshed,
        )
    }
}
