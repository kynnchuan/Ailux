package com.ailux.provider.backend.mapper

import com.ailux.core.model.ErrorCode
import com.ailux.core.model.LLMError
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Default error mapper based on standard HTTP status codes and common
 * exception types.
 *
 * Mapping rules:
 *
 * | Condition | ErrorCode |
 * |---|---|
 * | [CancellationException] | [ErrorCode.REQUEST_CANCELLED] |
 * | [UnknownHostException], [ConnectException] | [ErrorCode.NETWORK_UNAVAILABLE] |
 * | [SocketTimeoutException], [InterruptedIOException] | [ErrorCode.REQUEST_TIMEOUT] |
 * | HTTP 401 / 403 | [ErrorCode.AUTH_FAILED] |
 * | HTTP 429 | [ErrorCode.RATE_LIMITED] |
 * | HTTP 404 | [ErrorCode.MODEL_NOT_FOUND] |
 * | Otherwise | [ErrorCode.UNKNOWN] |
 *
 * @see ErrorMapper
 */
class DefaultErrorMapper : ErrorMapper {

    override fun map(throwable: Throwable?, httpCode: Int?, responseBody: String?): LLMError {
        // 1. Check by exception type first
        if (throwable != null) {
            val mapped = mapThrowable(throwable)
            if (mapped != null) return mapped
        }

        // 2. Then check by HTTP status code
        if (httpCode != null) {
            val mapped = mapHttpCode(httpCode, responseBody, throwable)
            if (mapped != null) return mapped
        }

        // 3. Fallback
        return LLMError(
            code = ErrorCode.UNKNOWN,
            message = throwable?.message
                ?: responseBody?.take(200)
                ?: "Unknown error (HTTP $httpCode)",
            cause = throwable,
        )
    }

    private fun mapThrowable(throwable: Throwable): LLMError? = when (throwable) {
        is CancellationException -> LLMError(
            code = ErrorCode.REQUEST_CANCELLED,
            message = "Request cancelled",
            cause = throwable,
        )

        is UnknownHostException, is ConnectException -> LLMError(
            code = ErrorCode.NETWORK_UNAVAILABLE,
            message = "Network unreachable: ${throwable.message}",
            cause = throwable,
        )

        is SocketTimeoutException -> LLMError(
            code = ErrorCode.REQUEST_TIMEOUT,
            message = "Request timed out: ${throwable.message}",
            cause = throwable,
        )

        is InterruptedIOException -> LLMError(
            code = ErrorCode.REQUEST_TIMEOUT,
            message = "I/O interrupted (possibly a timeout): ${throwable.message}",
            cause = throwable,
        )

        else -> null
    }

    private fun mapHttpCode(
        httpCode: Int,
        responseBody: String?,
        throwable: Throwable?,
    ): LLMError? = when (httpCode) {
        401, 403 -> LLMError(
            code = ErrorCode.AUTH_FAILED,
            message = "Authentication failed (HTTP $httpCode)",
            cause = throwable,
        )

        429 -> LLMError(
            code = ErrorCode.RATE_LIMITED,
            message = "Rate limited (HTTP 429)",
            cause = throwable,
        )

        404 -> LLMError(
            code = ErrorCode.MODEL_NOT_FOUND,
            message = "Resource not found (HTTP 404): ${responseBody?.take(200) ?: ""}",
            cause = throwable,
        )

        else -> null
    }
}
