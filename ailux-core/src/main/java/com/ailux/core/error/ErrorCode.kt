package com.ailux.core.error

/**
 * Unified Ailux error codes.
 *
 * v0.1 only contains error codes related to the backend-proxy flow.
 * v0.3.0 added four on-device inference error codes: [MODEL_LOAD_FAILED],
 * [MODEL_FILE_INVALID], [INSUFFICIENT_MEMORY], [UNSUPPORTED_ABI].
 * `STORAGE_NOT_ENOUGH` was deliberately not added — the in-SDK model
 * downloader was rejected (ADR-0005), so the SDK never writes storage
 * during model load (it only reads an existing local file).
 *
 * @property retriable Whether this error is safe to retry automatically.
 */
enum class ErrorCode(val retriable: Boolean = false) {

    /** Network is unreachable or DNS resolution failed. */
    NETWORK_UNAVAILABLE(retriable = true),

    /** The request timed out before a response was received. */
    REQUEST_TIMEOUT(retriable = true),

    /**
     * Authentication failed (HTTP 401 / 403) — **terminal**.
     *
     * Refresh is not possible (either the [com.ailux.provider.backend.auth.AuthProvider]
     * does not implement `onUnauthorized()`, or it has already attempted a refresh
     * and the replay still failed). UIs typically respond by kicking the user
     * back to the login page.
     *
     * Distinct from [AUTH_EXPIRED] (recoverable) so callers can branch on the
     * recovery decision.
     */
    AUTH_FAILED,

    /**
     * Authentication credential is expired **but recoverable**.
     *
     * Surfaces when the SDK detected a 401 and a custom `AuthProvider`
     * implementation declared (via `onUnauthorized()` returning `true`) that
     * the credential has been refreshed and the request can be safely replayed.
     *
     * Marked [retriable] so the standard retry pipeline handles the replay —
     * no parallel retry plumbing is required. If all retries are exhausted
     * this code surfaces to the collector instead of [AUTH_FAILED], so the UI
     * can choose between silent re-login and a hard logout.
     *
     * @since 0.2.6
     */
    AUTH_EXPIRED(retriable = true),

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

    /** A server-side error (HTTP 5xx). Typically transient and safe to retry. */
    SERVER_ERROR(retriable = true),

    /**
     * On-device engine failed to initialize or the model file format is not
     * recognized by the engine. Format validation is internal to each
     * `InferenceEngine.load()` (the central format-sniffer was rejected — see
     * spec §1.5 / Q5).
     *
     * @since 0.3.0
     */
    MODEL_LOAD_FAILED,

    /**
     * Opt-in SHA-256 integrity check failed.
     *
     * Only raised when [com.ailux.core.config.LocalRuntimeConfig.verifySha256]
     * is non-null and the streamed digest does not match. The SDK does not
     * look up "the correct hash" — the expected value is provided by the
     * business caller (typically copied from a HuggingFace / ModelScope
     * model card).
     *
     * @since 0.3.0
     */
    MODEL_FILE_INVALID,

    /**
     * Device RAM is insufficient to load / run the requested model.
     *
     * Raised either:
     *  - Before load, by the engine's self-estimated requirement vs
     *    `ActivityManager.MemoryInfo.totalMem` / `isLowRamDevice()`; or
     *  - Mid-stream, on `OutOfMemoryError` — already-emitted tokens are
     *    preserved (the SDK provides the mechanism; whether to clear the
     *    partial answer is a business policy).
     *
     * @since 0.3.0
     */
    INSUFFICIENT_MEMORY,

    /**
     * Device ABI has no overlap with the engine's supported ABIs.
     *
     * Raised by the pre-load `DeviceProbe` so we fail fast before any
     * native `.so` load is attempted. v0.3.0 engines ship `arm64-v8a` only
     * (Gradle `abiFilters arm64-v8a` is the build-time complement).
     *
     * @since 0.3.0
     */
    UNSUPPORTED_ABI,

    /** An uncategorized unknown error. */
    UNKNOWN;
}
