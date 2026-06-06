package com.ailux.provider.backend.mapper

import com.ailux.core.model.LLMError

/**
 * Error mapper: converts HTTP response errors and exceptions into the SDK's
 * unified [LLMError].
 *
 * Different enterprise backends use different error code schemes. Implement
 * this interface to map your business error codes onto the SDK's
 * [com.ailux.core.model.ErrorCode].
 *
 * Passing `null` to [BackendProxyConfig.errorMapper] makes the SDK fall back
 * to [DefaultErrorMapper] (based on standard HTTP status codes).
 *
 * ```kotlin
 * val mapper = ErrorMapper { throwable, httpCode, responseBody ->
 *     // Parse the company-specific error body
 *     val bizError = parseCompanyError(responseBody)
 *     LLMError(
 *         code = mapBizCode(bizError.code),
 *         message = bizError.message,
 *         cause = throwable,
 *     )
 * }
 * ```
 *
 * @see DefaultErrorMapper
 */
fun interface ErrorMapper {

    /**
     * Maps an exception or HTTP error response into an [LLMError].
     *
     * At least one of [throwable] and [httpCode] is non-null.
     *
     * @param throwable    The original exception (network error, timeout, etc.). May be `null` for pure HTTP errors.
     * @param httpCode     HTTP response status code. May be `null` if the connection failed before a response.
     * @param responseBody HTTP error response body text. May be `null` or empty.
     * @return The unified SDK error after mapping.
     */
    fun map(throwable: Throwable?, httpCode: Int?, responseBody: String?): LLMError
}
