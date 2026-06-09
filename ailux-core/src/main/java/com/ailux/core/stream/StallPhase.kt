package com.ailux.core.stream

/**
 * Identifies which timeout phase triggered a stall event during streaming.
 */
enum class StallPhase {
    /** No token has been received yet after the connection was established. */
    WAITING_FIRST_TOKEN,

    /** A gap between consecutive tokens exceeded the configured threshold. */
    INTER_TOKEN
}