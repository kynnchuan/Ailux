package com.ailux.provider.backend.auth

/**
 * Authentication provider interface.
 *
 * Enterprise backends typically require every request to carry a business token
 * (e.g. JWT / session). This interface decouples token retrieval from the
 * Provider so that callers can plug in their own login / refresh logic.
 *
 * Implementation tips:
 * - This is invoked on every request; keep it cheap (cache, return synchronously
 *   when possible).
 * - It runs on a coroutine; suspending I/O (refresh token over the network) is
 *   allowed but should be guarded by a timeout.
 * - When token retrieval fails, throw an exception. The Provider will surface
 *   it as an [com.ailux.core.model.LLMError] with
 *   [com.ailux.core.model.ErrorCode.AUTH_FAILED].
 */
fun interface AuthProvider {
    /**
     * Returns the full value of the `Authorization` header to be carried in the
     * next request.
     *
     * Implementations should return the complete header value (including any
     * scheme prefix), e.g. `"Bearer <token>"`. The Provider sets this string as
     * the value of the standard `Authorization` request header.
     *
     * @return the full `Authorization` header value
     * @throws Exception when the token cannot be obtained (e.g. login expired,
     *   network failure)
     */
    suspend fun getAuthToken(): String

    /**
     * Called by [com.ailux.provider.backend.BackendProxyProvider] when the
     * server returns **HTTP 401 Unauthorized** (or the equivalent mapped by a
     * custom [com.ailux.provider.backend.mapper.ErrorMapper]).
     *
     * This is the SDK's "credential refresh + replay" hook. Implementations
     * should:
     * 1. Force-refresh the credential (clear any cache, re-login, exchange a
     *    new access token, etc.).
     * 2. Return `true` when the refresh succeeded — the Provider will replay
     *    the current request **once**, threaded through the same
     *    [com.ailux.provider.backend.config.RetryPolicy] pipeline (no
     *    parallel retry plumbing). The replayed request will carry the
     *    freshly-issued Authorization header.
     * 3. Return `false` when the refresh failed or is not possible — the
     *    Provider will surface [com.ailux.core.error.ErrorCode.AUTH_FAILED]
     *    (terminal) without further retries.
     *
     * The default implementation returns `false`, preserving the v0.2.5
     * behaviour: 401 → terminal `AUTH_FAILED`. Existing single-method
     * implementations (lambdas, SAM conversions) keep compiling unchanged.
     *
     * ## Concurrency
     *
     * Multiple in-flight requests may discover a stale credential at the
     * same time and race to call `onUnauthorized()`. SDK consumers that own
     * a non-trivial refresh routine (network round-trip, OAuth dance) should
     * single-flight the refresh — see the `CachingAuthProvider` example in
     * the extensibility guide. The SDK itself does NOT serialise calls to
     * `onUnauthorized()` because the "right" deduplication strategy is a
     * business decision (per-account, per-tenant, per-process, etc.).
     *
     * ## Failure handling
     *
     * Implementations should treat any exception thrown from this method as
     * a failed refresh — the Provider catches it and falls back to
     * [com.ailux.core.error.ErrorCode.AUTH_FAILED]. Throwing therefore has
     * the same outward effect as returning `false`, but `false` is preferred
     * because it avoids paying the exception-construction cost on the hot
     * authentication path.
     *
     * @return `true` if the credential was refreshed and the request should
     *         be replayed, `false` to treat the 401 as terminal.
     * @since 0.2.6
     */
    suspend fun onUnauthorized(): Boolean = false
}
