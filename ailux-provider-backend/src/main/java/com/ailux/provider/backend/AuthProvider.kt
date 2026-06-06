package com.ailux.provider.backend

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
}
