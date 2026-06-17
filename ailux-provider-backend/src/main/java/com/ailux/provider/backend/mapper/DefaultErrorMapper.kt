package com.ailux.provider.backend.mapper

import com.ailux.core.error.ErrorCode
import com.ailux.core.error.LLMError
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
 * | HTTP 5xx (500, 502, 503, 504, etc.) | [ErrorCode.SERVER_ERROR] (retriable) |
 * | Otherwise | [ErrorCode.UNKNOWN] |
 *
 * ## AUTH_FAILED vs AUTH_EXPIRED (since 0.2.6)
 *
 * This mapper always emits [ErrorCode.AUTH_FAILED] for 401/403. The downgrade to
 * the recoverable [ErrorCode.AUTH_EXPIRED] is a **provider-mediated** concern:
 * [com.ailux.provider.backend.BackendProxyProvider] consults the configured
 * [com.ailux.provider.backend.auth.AuthProvider.onUnauthorized] in a suspending
 * context that this mapper does not have, and only after a refresh path has been
 * acknowledged does the terminal event get rewritten from `AUTH_FAILED` to
 * `AUTH_EXPIRED`. Custom mappers should preserve the same invariant: emit
 * `AUTH_FAILED` from the HTTP layer; let the provider decide whether the
 * situation was recoverable.
 *
 * @see ErrorMapper
 * @see com.ailux.provider.backend.auth.AuthProvider.onUnauthorized
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

        in 500..599 -> LLMError(
            code = ErrorCode.SERVER_ERROR,
            message = "Server error (HTTP $httpCode): ${responseBody?.take(200) ?: ""}",
            cause = throwable,
        )

        else -> null
    }
}
