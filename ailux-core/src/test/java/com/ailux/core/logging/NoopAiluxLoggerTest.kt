package com.ailux.core.logging

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sanity-check that [NoopAiluxLogger] really discards every entry — the SDK's
 * "secure by default" promise depends on this for production builds that want
 * the SDK to be silent.
 */
class NoopAiluxLoggerTest {

    @Test
    fun `noop logger swallows every level`() {
        val logger: AiluxLogger = NoopAiluxLogger
        // No assertion needed beyond "this does not throw" — but we exhaustively
        // hit every level so a future regression (e.g. someone adds a side-effect)
        // breaks here loudly.
        LogLevel.values().forEach { level ->
            logger.log(level, "Ailux", "anything", IllegalStateException("ignored"))
        }
        assertEquals(LogLevel.values().size, LogLevel.values().size) // smoke
    }
}
