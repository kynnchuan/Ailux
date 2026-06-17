package com.ailux.core.stream

/**
 * Configuration for stream health monitoring (stall detection).
 *
 * Both timeouts default to 10 seconds. Setting a timeout to 0 disables that
 * particular check. Use [DISABLED] to turn off stall detection entirely.
 *
 * @property firstTokenTimeoutMillis Maximum wait time (ms) for the first token
 *           after the connection is established. 0 = disabled.
 * @property stallTimeoutMillis      Maximum idle time (ms) between consecutive
 *           tokens before the stream is considered stalled. 0 = disabled.
 */
data class StreamConfig(
    val firstTokenTimeoutMillis: Long = 10_000L,

    val stallTimeoutMillis: Long = 10_000L
) {

    init {
        require(firstTokenTimeoutMillis >= 0L) {
            "firstTokenTimeoutMillis must not be negative"
        }
        require(stallTimeoutMillis >= 0L) {
            "stallTimeoutMillis must not be negative"
        }
    }

    companion object {
        /** Sentinel instance that disables all stall detection. */
        val DISABLED = StreamConfig(firstTokenTimeoutMillis = 0L, stallTimeoutMillis = 0L)
    }

}