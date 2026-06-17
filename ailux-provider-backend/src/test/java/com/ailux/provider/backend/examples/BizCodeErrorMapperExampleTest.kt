package com.ailux.provider.backend.examples

import com.ailux.core.error.ErrorCode
import com.ailux.provider.backend.mapper.ErrorMapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * **v0.2.5 G-2 extension-point example — `ErrorMapper`.**
 *
 * Demonstrates how to plug a custom [ErrorMapper] into [BackendProxyConfig]
 * when the backend uses a **business error code scheme** rather than relying
 * on HTTP status codes.
 *
 * Many enterprise gateways return `HTTP 200` even for failures and put the
 * real status inside the JSON body:
 *
 * ```json
 * { "code": "GATEWAY_RATE_LIMITED", "message": "QPS exceeded for tenant X" }
 * ```
 *
 * The default [com.ailux.provider.backend.mapper.DefaultErrorMapper] cannot
 * see this — it would treat the response as success because the HTTP code is
 * 200. A custom [ErrorMapper] is the correct extension point.
 *
 * The example also handles two non-HTTP failures (network down, timeout) so
 * that the mapper exhibits the "throwable | httpCode | body — at least one is
 * non-null" contract from the [ErrorMapper] KDoc.
 *
 * @see ErrorMapper
 */
class BizCodeErrorMapperExampleTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val bizMapper = ErrorMapper { throwable, httpCode, responseBody ->
        // ── Step 1: surface non-HTTP failures verbatim ─────────────────────
        when (throwable) {
            is SocketTimeoutException -> return@ErrorMapper com.ailux.core.error.LLMError(
                code = ErrorCode.REQUEST_TIMEOUT,
                message = throwable.message ?: "request timed out",
                cause = throwable,
            )
            is IOException -> return@ErrorMapper com.ailux.core.error.LLMError(
                code = ErrorCode.NETWORK_UNAVAILABLE,
                message = throwable.message ?: "network unavailable",
                cause = throwable,
            )
        }

        // ── Step 2: try to parse the gateway envelope ──────────────────────
        val parsed = responseBody?.takeIf { it.isNotBlank() }?.let { body ->
            runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        }

        val bizCode = parsed?.get("code")?.jsonPrimitive?.content
        val bizMessage = parsed?.get("message")?.jsonPrimitive?.content
            ?: "HTTP $httpCode"

        // ── Step 3: translate the business code to ErrorCode ───────────────
        val mapped = when (bizCode) {
            "GATEWAY_RATE_LIMITED", "TOO_MANY_REQUESTS" -> ErrorCode.RATE_LIMITED
            "AUTH_TOKEN_EXPIRED", "AUTH_INVALID" -> ErrorCode.AUTH_FAILED
            "MODEL_OFFLINE", "MODEL_NOT_FOUND" -> ErrorCode.MODEL_NOT_FOUND
            "TIMEOUT" -> ErrorCode.REQUEST_TIMEOUT
            null -> when (httpCode) { // fall back to HTTP if no biz code
                401, 403 -> ErrorCode.AUTH_FAILED
                404 -> ErrorCode.MODEL_NOT_FOUND
                429 -> ErrorCode.RATE_LIMITED
                in 500..599 -> ErrorCode.UNKNOWN
                else -> ErrorCode.UNKNOWN
            }
            else -> ErrorCode.UNKNOWN
        }

        com.ailux.core.error.LLMError(code = mapped, message = bizMessage, cause = throwable)
    }

    @Test
    fun `gateway 200 with biz code GATEWAY_RATE_LIMITED maps to RATE_LIMITED`() {
        val err = bizMapper.map(
            throwable = null,
            httpCode = 200,
            responseBody = """{"code":"GATEWAY_RATE_LIMITED","message":"QPS exceeded for tenant X"}""",
        )
        assertEquals(ErrorCode.RATE_LIMITED, err.code)
        assertEquals("QPS exceeded for tenant X", err.message)
        assertEquals(true, err.isRetriable) // RATE_LIMITED is retriable
    }

    @Test
    fun `auth biz code maps to AUTH_FAILED with non-retriable flag`() {
        val err = bizMapper.map(
            throwable = null,
            httpCode = 200,
            responseBody = """{"code":"AUTH_TOKEN_EXPIRED","message":"please re-login"}""",
        )
        assertEquals(ErrorCode.AUTH_FAILED, err.code)
        assertEquals(false, err.isRetriable)
    }

    @Test
    fun `unknown biz code falls back to HTTP-based mapping`() {
        val err = bizMapper.map(
            throwable = null,
            httpCode = 429,
            responseBody = """{"some":"unrelated"}""",
        )
        // No biz `code` field → fallback path → HTTP 429 → RATE_LIMITED
        assertEquals(ErrorCode.RATE_LIMITED, err.code)
    }

    @Test
    fun `network IOException maps to NETWORK_UNAVAILABLE and propagates cause`() {
        val cause = IOException("DNS failed")
        val err = bizMapper.map(throwable = cause, httpCode = null, responseBody = null)
        assertEquals(ErrorCode.NETWORK_UNAVAILABLE, err.code)
        assertNotNull(err.cause)
        assertEquals(cause, err.cause)
    }

    @Test
    fun `socket timeout maps to REQUEST_TIMEOUT (retriable)`() {
        val err = bizMapper.map(
            throwable = SocketTimeoutException("read timed out"),
            httpCode = null,
            responseBody = null,
        )
        assertEquals(ErrorCode.REQUEST_TIMEOUT, err.code)
        assertEquals(true, err.isRetriable)
    }

    @Test
    fun `malformed JSON body is tolerated and falls back to HTTP code`() {
        val err = bizMapper.map(
            throwable = null,
            httpCode = 500,
            responseBody = "<html>not json</html>",
        )
        // Parser throws → caught → bizCode=null → fall through HTTP branch → 500 → UNKNOWN
        assertEquals(ErrorCode.UNKNOWN, err.code)
    }
}
