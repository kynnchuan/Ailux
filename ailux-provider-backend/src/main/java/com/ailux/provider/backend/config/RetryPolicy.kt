package com.ailux.provider.backend.config

data class RetryPolicy(
    val maxRetries: Int = 0,
    val initialBackoffMillis: Long = 500,
    val maxBackoffMillis: Long = 8_000,
    val backoffMultiplier: Double = 2.0,
    val jitterFactor: Double = 0.2,
    val respectRetryAfter: Boolean = true
) {
    companion object {
        val NONE = RetryPolicy(maxRetries = 0)
    }
}
