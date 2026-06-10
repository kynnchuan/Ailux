package com.ailux.core.error

/**
 * Unified Ailux error codes.
 *
 * v0.1 only contains error codes related to the backend-proxy flow.
 * On-device inference error codes (MODEL_LOAD_FAILED, MODEL_FILE_INVALID,
 * INSUFFICIENT_MEMORY, STORAGE_NOT_ENOUGH) will be added in v0.3+.
 *
 * @property retriable Whether this error is safe to retry automatically.
 */
enum class ErrorCode(val retriable: Boolean = false) {

    /** Network is unreachable or DNS resolution failed. */
    NETWORK_UNAVAILABLE(retriable = true),

    /** The request timed out before a response was received. */
    REQUEST_TIMEOUT(retriable = true),

    /** Authentication failed (HTTP 401 / 403). */
    AUTH_FAILED,

    /** The server returned a rate-limit response (HTTP 429). */
    RATE_LIMITED(retriable = true),

    /** The requested model does not exist on the backend. */
    MODEL_NOT_FOUND,

    /** The request was cancelled by the caller. */
    REQUEST_CANCELLED,

    /** A new request was rejected because the concurrency policy does not allow it. */
    CONCURRENT_REQUEST_REJECTED,

    /** The provider does not support the given [AttachmentSource] or mimeType combination
     *  (e.g. [BackendProxyProvider] received a [LocalUri] that was not converted to Base64). */
    UNSUPPORTED_MODALITY,

    /** An uncategorized unknown error. */
    UNKNOWN;
}
