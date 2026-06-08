package com.ailux.api.context

import com.ailux.core.message.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SlidingWindowStrategyTest {

    private lateinit var strategy: SlidingWindowStrategy
    private lateinit var counter: EstimatedTokenCounter

    @Before
    fun setup() {
        strategy = SlidingWindowStrategy()
        counter = EstimatedTokenCounter(safetyMargin = 0f)
    }

    // ── System message preservation ──

    @Test
    fun `system messages are always preserved`() {
        val messages = listOf(
            Message.System("You are helpful"),
            Message.User("msg1"),
            Message.User("msg2"),
            Message.User("msg3"),
            Message.User("msg4")
        )

        // Use a very tight budget — only enough for system + 1 message
        val systemTokens = counter.count(messages[0])
        val lastMsgTokens = counter.count(messages[4])
        val tightBudget = systemTokens + lastMsgTokens + 1

        val result = strategy.trim(messages, tightBudget, emptySet(), counter)

        // System message must be first
        assertTrue(result.isNotEmpty())
        assertEquals(Message.System("You are helpful"), result[0])
    }

    @Test
    fun `multiple system messages at beginning are all preserved`() {
        val messages = listOf(
            Message.System("System 1"),
            Message.System("System 2"),
            Message.User("user msg 1"),
            Message.User("user msg 2")
        )

        val systemTokens = counter.count(listOf(messages[0], messages[1]))
        val lastMsgTokens = counter.count(messages[3])
        val budget = systemTokens + lastMsgTokens + 1

        val result = strategy.trim(messages, budget, emptySet(), counter)

        assertTrue(result.size >= 2)
        assertEquals(Message.System("System 1"), result[0])
        assertEquals(Message.System("System 2"), result[1])
    }

    // ── Recency priority ──

    @Test
    fun `keeps most recent messages when budget is tight`() {
        val messages = listOf(
            Message.System("system"),
            Message.User("old message 1"),
            Message.User("old message 2"),
            Message.User("recent message")
        )

        val systemTokens = counter.count(messages[0])
        val recentTokens = counter.count(messages[3])
        // Budget only enough for system + last message
        val budget = systemTokens + recentTokens + 1

        val result = strategy.trim(messages, budget, emptySet(), counter)

        assertTrue(
            "Result should contain the most recent message",
            result.contains(Message.User("recent message"))
        )
        assertTrue(
            "Result should NOT contain old messages",
            !result.contains(Message.User("old message 1"))
        )
    }

    @Test
    fun `preserves original order in output`() {
        val messages = listOf(
            Message.System("system"),
            Message.User("msg1"),
            Message.Assistant(content = "reply1"),
            Message.User("msg2"),
            Message.Assistant(content = "reply2")
        )

        // Budget enough for system + last 2 messages
        val systemTokens = counter.count(messages[0])
        val last2Tokens = counter.count(listOf(messages[3], messages[4]))
        val budget = systemTokens + last2Tokens + 1

        val result = strategy.trim(messages, budget, emptySet(), counter)

        // Verify order: system comes first, then the kept messages in order
        assertEquals(Message.System("system"), result[0])
        if (result.size >= 3) {
            val nonSystemResult = result.drop(1)
            for (i in 0 until nonSystemResult.size - 1) {
                val idx1 = messages.indexOf(nonSystemResult[i])
                val idx2 = messages.indexOf(nonSystemResult[i + 1])
                assertTrue("Messages should maintain original order", idx1 < idx2)
            }
        }
    }

    // ── Protected indices ──

    @Test
    fun `protected messages are always included`() {
        val messages = listOf(
            Message.System("system"),
            Message.User("old msg"),       // index 1 — will be protected
            Message.User("middle msg"),    // index 2
            Message.User("recent msg")    // index 3
        )

        val systemTokens = counter.count(messages[0])
        val protectedTokens = counter.count(messages[1])
        val recentTokens = counter.count(messages[3])
        // Budget enough for system + protected + 1 recent only
        val budget = systemTokens + protectedTokens + recentTokens + 1

        val protectedIndices = setOf(1)  // Protect the old message
        val result = strategy.trim(messages, budget, protectedIndices, counter)

        assertTrue(
            "Protected message should be in result",
            result.contains(Message.User("old msg"))
        )
        assertTrue(
            "Most recent message should be in result",
            result.contains(Message.User("recent msg"))
        )
    }

    @Test
    fun `protected messages budget is pre-deducted`() {
        val messages = listOf(
            Message.System("sys"),
            Message.User("protected1"),  // index 1 — protected
            Message.User("protected2"),  // index 2 — protected
            Message.User("candidate1"),  // index 3
            Message.User("candidate2"),  // index 4
            Message.User("candidate3")   // index 5
        )

        val totalTokens = counter.count(messages)
        // Budget = total - just enough to force dropping 1 candidate
        val budget = totalTokens - counter.count(messages[3]) - 1

        val protectedIndices = setOf(1, 2)
        val result = strategy.trim(messages, budget, protectedIndices, counter)

        // Protected messages must be present
        assertTrue(result.contains(Message.User("protected1")))
        assertTrue(result.contains(Message.User("protected2")))
        // Most recent candidates should be preferred
        assertTrue(result.contains(Message.User("candidate3")))
    }

    // ── Edge cases ──

    @Test
    fun `all messages fit within budget returns all`() {
        val messages = listOf(
            Message.System("system"),
            Message.User("hello"),
            Message.Assistant(content = "hi")
        )

        val totalTokens = counter.count(messages)
        val result = strategy.trim(messages, totalTokens + 100, emptySet(), counter)

        assertEquals(messages.size, result.size)
    }

    @Test
    fun `empty message list returns empty`() {
        val result = strategy.trim(emptyList(), 1000, emptySet(), counter)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `only system messages in list`() {
        val messages = listOf(
            Message.System("system 1"),
            Message.System("system 2")
        )

        val result = strategy.trim(messages, 1000, emptySet(), counter)
        assertEquals(2, result.size)
    }
}
