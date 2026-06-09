package com.ailux.core.concurrency

/**
 * Policy that governs how concurrent LLM requests are handled when a new
 * request arrives while one (or more) is already in progress.
 */
enum class ConcurrencyPolicy {

    /** Allow multiple requests to run simultaneously. */
    PARALLEL,

    /** Cancel the in-flight request before starting the new one. */
    CANCEL_PREVIOUS,

    /** Queue the new request and execute it after the current one finishes. */
    ENQUEUE,

    /** Reject the new request immediately if one is already running. */
    REJECT

}