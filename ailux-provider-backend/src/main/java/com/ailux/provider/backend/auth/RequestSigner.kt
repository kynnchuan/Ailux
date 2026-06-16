package com.ailux.provider.backend.auth

/**
 * Per-request signature hook for backends that require request-level integrity
 * signing (HMAC over body, replay-protection timestamps, distributed tracing
 * IDs, etc.).
 *
 * ## Why a separate interface from [AuthProvider]?
 *
 * [AuthProvider] owns the **identity credential** (`Authorization` header):
 * who you are. `RequestSigner` owns the **request-level integrity signature**:
 * proof that *this exact request* was issued by you and has not been tampered
 * with. They are orthogonal concerns and frequently co-exist on the same
 * enterprise gateway (Bearer token *plus* HMAC over body, AWS SigV4-style).
 *
 * - `AuthProvider.getAuthToken()` only sees "give me a credential" — it has
 *   no visibility into the outbound method/URL/body, which makes
 *   request-bound signing impossible from there.
 * - `RequestSigner.sign()` receives a [SignableRequest] snapshot of the
 *   request as it is about to leave [com.ailux.provider.backend.BackendProxyProvider]
 *   — including the serialised JSON body — so HMAC over body / signed-headers
 *   schemes work without leaking the body into the auth path.
 *
 * ## Header injection order
 *
 * Within [com.ailux.provider.backend.BackendProxyProvider.buildBaseRequest]
 * the order is, last-write-wins:
 * 1. Static request headers (`Content-Type`, `Accept`).
 * 2. `Authorization` from [AuthProvider.getAuthToken] (if configured).
 * 3. User-supplied [com.ailux.provider.backend.config.BackendProxyConfig.headers].
 * 4. `Idempotency-Key` from [com.ailux.core.request.LLMRequest.requestId]
 *    (if [com.ailux.provider.backend.config.BackendProxyConfig.idempotencyHeaderName]
 *    is non-null).
 * 5. **Signature headers from this `RequestSigner`** — runs last, so signers
 *    can deliberately override anything above (e.g. swap the `Authorization`
 *    value for a signed bearer, or add an `X-Signature` alongside).
 *
 * ## Failure semantics
 *
 * Any exception thrown from [sign] propagates to the request build path and
 * surfaces as a normal provider failure (mapped via the configured
 * [com.ailux.provider.backend.mapper.ErrorMapper]). Implementations should
 * therefore validate their preconditions cheaply and throw early.
 *
 * ## What this hook is *not* for
 *
 * - **mTLS / certificate pinning**: those are transport-layer concerns —
 *   configure them on [com.ailux.provider.backend.config.HttpClientConfig.baseHttpClient]
 *   or [com.ailux.provider.backend.config.HttpClientConfig.customizer].
 * - **Credential rotation / refresh**: that is [AuthProvider.onUnauthorized]'s
 *   job, not the signer's.
 *
 * @since 0.2.6
 * @see SignableRequest
 * @see AuthProvider
 */
fun interface RequestSigner {
    /**
     * Computes the signature headers for the outbound request.
     *
     * Implementations receive an immutable snapshot of the request and return
     * the headers that should be appended/overlaid onto it. Returning an
     * empty map is allowed (no-op).
     *
     * @param req snapshot of the request about to be sent.
     * @return headers to merge into the outbound request; later entries with
     *         the same key win over headers already on the request.
     * @throws Exception when the signature cannot be computed.
     */
    suspend fun sign(req: SignableRequest): Map<String, String>
}

/**
 * Immutable snapshot of an outbound HTTP request as seen by [RequestSigner].
 *
 * Signers typically need to canonicalise some subset of these fields (method,
 * URL path, body, a designated subset of headers) and feed the canonical form
 * to an HMAC / signing primitive.
 *
 * @property method   HTTP method (always `"POST"` for the current Provider).
 * @property url      Fully-qualified URL including scheme, host, and path.
 * @property body     Serialised request body (already produced by the
 *                    configured [com.ailux.provider.backend.mapper.RequestMapper]).
 *                    Empty string when there is no body.
 * @property headers  Headers as they currently stand at the signer's
 *                    injection point — i.e. after `Authorization`,
 *                    custom headers and `Idempotency-Key` have been applied,
 *                    but before the signer's own output is merged in.
 *                    Header keys are compared case-insensitively per
 *                    HTTP/1.1 (RFC 7230 §3.2), but this map preserves the
 *                    builder's casing.
 * @since 0.2.6
 */
data class SignableRequest(
    val method: String,
    val url: String,
    val body: String,
    val headers: Map<String, String>,
)
