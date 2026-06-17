package com.ailux.provider.backend.mapper

import com.ailux.core.error.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Unit tests for [DefaultErrorMapper].
 *
 * Verifies the mapping from exception types and HTTP status codes to
 * unified [ErrorCode] values — especially the retriability contract
 * required by the retry pipeline (v0.2.6 §3.2).
 */
class DefaultErrorMapperTest {

    private lateinit var mapper: DefaultErrorMapper

    @Before
    fun setUp() {
        mapper = DefaultErrorMapper()
    }

    // ── Exception type mapping ──

    @Test
    fun `CancellationException maps to REQUEST_CANCELLED (not retriable)`() {
        val error = mapper.map(
            throwable = CancellationException("cancelled"),
            httpCode = null,
            responseBody = null,
        )
        assertEquals(ErrorCode.REQUEST_CANCELLED, error.code)
        assertFalse(error.isRetriable)
    }

    @Test
    fun `UnknownHostException maps to NETWORK_UNAVAILABLE (retriable)`() {
        val error = mapper.map(
            throwable = UnknownHostException("api.example.com"),
            httpCode = null,
            responseBody = null,
        )
        assertEquals(ErrorCode.NETWORK_UNAVAILABLE, error.code)
        assertTrue(error.isRetriable)
    }

    @Test
    fun `ConnectException maps to NETWORK_UNAVAILABLE (retriable)`() {
        val error = mapper.map(
            throwable = ConnectException("Connection refused"),
            httpCode = null,
            responseBody = null,
        )
        assertEquals(ErrorCode.NETWORK_UNAVAILABLE, error.code)
        assertTrue(error.isRetriable)
    }

    @Test
    fun `SocketTimeoutException maps to REQUEST_TIMEOUT (retriable)`() {
        val error = mapper.map(
            throwable = SocketTimeoutException("Read timed out"),
            httpCode = null,
            responseBody = null,
        )
        assertEquals(ErrorCode.REQUEST_TIMEOUT, error.code)
        assertTrue(error.isRetriable)
    }

    @Test
    fun `InterruptedIOException maps to REQUEST_TIMEOUT (retriable)`() {
        val error = mapper.map(
            throwable = InterruptedIOException("timeout"),
            httpCode = null,
            responseBody = null,
        )
        assertEquals(ErrorCode.REQUEST_TIMEOUT, error.code)
        assertTrue(error.isRetriable)
    }

    @Test
    fun `unknown exception falls through to HTTP code or UNKNOWN`() {
        val error = mapper.map(
            throwable = RuntimeException("something weird"),
            httpCode = null,
            responseBody = null,
        )
        assertEquals(ErrorCode.UNKNOWN, error.code)
        assertFalse(error.isRetriable)
    }

    // ── HTTP status code mapping ──

    @Test
    fun `HTTP 401 maps to AUTH_FAILED (not retriable)`() {
        val error = mapper.map(throwable = null, httpCode = 401, responseBody = null)
        assertEquals(ErrorCode.AUTH_FAILED, error.code)
        assertFalse(error.isRetriable)
    }

    @Test
    fun `HTTP 403 maps to AUTH_FAILED (not retriable)`() {
        val error = mapper.map(throwable = null, httpCode = 403, responseBody = null)
        assertEquals(ErrorCode.AUTH_FAILED, error.code)
        assertFalse(error.isRetriable)
    }

    @Test
    fun `HTTP 429 maps to RATE_LIMITED (retriable)`() {
        val error = mapper.map(throwable = null, httpCode = 429, responseBody = null)
        assertEquals(ErrorCode.RATE_LIMITED, error.code)
        assertTrue(error.isRetriable)
    }

    @Test
    fun `HTTP 404 maps to MODEL_NOT_FOUND (not retriable)`() {
        val error = mapper.map(throwable = null, httpCode = 404, responseBody = "Not Found")
        assertEquals(ErrorCode.MODEL_NOT_FOUND, error.code)
        assertFalse(error.isRetriable)
    }

    @Test
    fun `HTTP 500 maps to SERVER_ERROR (retriable)`() {
        val error = mapper.map(
            throwable = null,
            httpCode = 500,
            responseBody = "Internal Server Error",
        )
        assertEquals(ErrorCode.SERVER_ERROR, error.code)
        assertTrue(error.isRetriable)
    }

    @Test
    fun `HTTP 502 maps to SERVER_ERROR (retriable)`() {
        val error = mapper.map(throwable = null, httpCode = 502, responseBody = "Bad Gateway")
        assertEquals(ErrorCode.SERVER_ERROR, error.code)
        assertTrue(error.isRetriable)
    }

    @Test
    fun `HTTP 503 maps to SERVER_ERROR (retriable)`() {
        val error = mapper.map(
            throwable = null,
            httpCode = 503,
            responseBody = "Service Unavailable",
        )
        assertEquals(ErrorCode.SERVER_ERROR, error.code)
        assertTrue(error.isRetriable)
    }

    @Test
    fun `HTTP 504 maps to SERVER_ERROR (retriable)`() {
        val error = mapper.map(throwable = null, httpCode = 504, responseBody = "Gateway Timeout")
        assertEquals(ErrorCode.SERVER_ERROR, error.code)
        assertTrue(error.isRetriable)
    }

    @Test
    fun `HTTP 418 (unrecognized) maps to UNKNOWN (not retriable)`() {
        val error = mapper.map(throwable = null, httpCode = 418, responseBody = "I'm a teapot")
        assertEquals(ErrorCode.UNKNOWN, error.code)
        assertFalse(error.isRetriable)
    }

    // ── Priority: exception takes precedence over HTTP code ──

    @Test
    fun `exception mapping takes priority over HTTP code`() {
        // If both are present, the throwable-based mapping wins
        val error = mapper.map(
            throwable = SocketTimeoutException("timed out"),
            httpCode = 500,
            responseBody = null,
        )
        // SocketTimeoutException → REQUEST_TIMEOUT, not SERVER_ERROR
        assertEquals(ErrorCode.REQUEST_TIMEOUT, error.code)
    }

    // ── Response body is included in error message ──

    @Test
    fun `response body is included in SERVER_ERROR message`() {
        val error = mapper.map(
            throwable = null,
            httpCode = 503,
            responseBody = """{"error":"overloaded"}""",
        )
        assertTrue(error.message.contains("overloaded"))
    }

    @Test
    fun `long response body is truncated to 200 chars`() {
        val longBody = "x".repeat(500)
        val error = mapper.map(throwable = null, httpCode = 503, responseBody = longBody)
        // The message should contain at most 200 chars of body
        assertTrue(error.message.length < 250) // some prefix + 200 chars max
    }
}
