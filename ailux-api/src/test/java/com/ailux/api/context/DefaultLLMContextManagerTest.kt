package com.ailux.api.context

import com.ailux.core.config.ContextConfig
import com.ailux.core.context.TrimAggressiveness
import com.ailux.core.message.Message
import com.ailux.core.tool.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DefaultLLMContextManagerTest {

    private lateinit var manager: DefaultLLMContextManager
    private lateinit var counter: EstimatedTokenCounter

    @Before
    fun setup() {
        counter = EstimatedTokenCounter(safetyMargin = 0f)
        manager = DefaultLLMContextManager(
            tokenCounter = counter,
            trimStrategy = SlidingWindowStrategy(),
            protector = FcMessageProtector()
        )
    }

    // ── Stage 1: Budget check (no trim needed) ──

    @Test
    fun `within budget returns all messages unchanged`() {
        val messages = listOf(
            Message.System("system"),
            Message.User("hello"),
            Message.Assistant(content = "hi there")
        )

        val totalTokens = counter.count(messages)
        val config = ContextConfig(budget = totalTokens + 100)

        val result = manager.process(messages, config)

        assertEquals(messages, result.messages)
        assertTrue(result.removed.isEmpty())
        assertEquals(0, result.estimatedTokensSaved)
    }

    @Test
    fun `exactly at budget returns all messages`() {
        val messages = listOf(
            Message.System("system"),
            Message.User("hello")
        )

        val totalTokens = counter.count(messages)
        val config = ContextConfig(budget = totalTokens)  // Exactly at budget

        val result = manager.process(messages, config)

        assertEquals(messages, result.messages)
        assertTrue(result.removed.isEmpty())
    }

    // ── Stage 2+3: Over budget triggers trimming ──

    @Test
    fun `over budget removes oldest messages`() {
        val messages = listOf(
            Message.System("system"),
            Message.User("old message 1"),
            Message.User("old message 2"),
            Message.User("old message 3"),
            Message.User("recent message")
        )

        val totalTokens = counter.count(messages)
        // Budget = 60% of total → must trim some messages
        val config = ContextConfig(budget = (totalTokens * 0.6).toInt())

        val result = manager.process(messages, config)

        // System is always kept
        assertTrue(result.messages.contains(Message.System("system")))
        // Most recent should be kept
        assertTrue(result.messages.contains(Message.User("recent message")))
        // Something was removed
        assertTrue(result.removed.isNotEmpty())
        assertTrue(result.estimatedTokensSaved > 0)
    }

    @Test
    fun `removed messages are correct`() {
        val messages = listOf(
            Message.System("s"),
            Message.User("a"),
            Message.User("b"),
            Message.User("c")
        )

        val systemTokens = counter.count(messages[0])
        val lastTokens = counter.count(messages[3])
        // Only enough for system + last message
        val config = ContextConfig(budget = systemTokens + lastTokens + 1)

        val result = manager.process(messages, config)

        // "a" and "b" should be in removed
        assertTrue(result.removed.contains(Message.User("a")))
        assertTrue(result.removed.contains(Message.User("b")))
        // Remaining should be system + "c"
        assertEquals(Message.System("s"), result.messages[0])
        assertTrue(result.messages.contains(Message.User("c")))
    }

    // ── FC protection integration ──

    @Test
    fun `undigested FC group is protected even when over budget`() {
        val messages = listOf(
            Message.System("system"),                                         // 0
            Message.User("old msg"),                                          // 1
            Message.User("another old"),                                      // 2
            Message.Assistant(                                                // 3
                toolCalls = listOf(ToolCall(id = "c1", name = "fn", arguments = "{}"))
            ),
            Message.Tool(toolCallId = "c1", content = "tool result")         // 4
            // No digest → group is protected
        )

        val totalTokens = counter.count(messages)
        // Tight budget: must trim, but FC group should survive
        val config = ContextConfig(
            budget = (totalTokens * 0.7).toInt(),
            aggressiveness = TrimAggressiveness.CONSERVATIVE
        )

        val result = manager.process(messages, config)

        // FC group (indices 3, 4) should be in the result
        assertTrue(
            "Assistant with toolCalls should be preserved",
            result.messages.any { it is Message.Assistant && it.toolCalls != null }
        )
        assertTrue(
            "Tool response should be preserved",
            result.messages.any { it is Message.Tool }
        )
    }

    @Test
    fun `digested FC group can be trimmed in CONSERVATIVE mode`() {
        val messages = listOf(
            Message.System("s"),                                              // 0
            Message.User("q1"),                                               // 1
            Message.Assistant(                                                // 2
                toolCalls = listOf(ToolCall(id = "c1", name = "fn", arguments = "{}"))
            ),
            Message.Tool(toolCallId = "c1", content = "result"),             // 3
            Message.Assistant(content = "Summary of tool result"),            // 4 ← digest
            Message.User("q2"),                                               // 5
            Message.User("q3"),                                               // 6
            Message.User("q4"),                                               // 7
            Message.Assistant(content = "latest reply")                       // 8
        )

        val systemTokens = counter.count(messages[0])
        val lastMsgsTokens = counter.count(listOf(messages[7], messages[8]))
        // Only enough for system + last 2 messages
        val config = ContextConfig(
            budget = systemTokens + lastMsgsTokens + 5,
            aggressiveness = TrimAggressiveness.CONSERVATIVE
        )

        val result = manager.process(messages, config)

        // The digested FC group (2,3) can be trimmed since budget is tight
        assertTrue(result.removed.isNotEmpty())
        // System and most recent should remain
        assertEquals(Message.System("s"), result.messages[0])
    }

    @Test
    fun `AGGRESSIVE purges digested group while CONSERVATIVE keeps it when budget allows`() {
        val messages = listOf(
            Message.System("s"),                                              // 0
            Message.User("old"),                                              // 1  oldest, short
            Message.Assistant(                                                // 2  ┐ digested group
                toolCalls = listOf(
                    ToolCall(id = "c1", name = "get_weather", arguments = """{"city":"Beijing"}""")
                )
            ),
            Message.Tool(                                                     // 3  ┘
                toolCallId = "c1",
                content = """{"temp":"22C","humidity":"40%","wind":"NE 3"}"""
            ),
            Message.Assistant(content = "Beijing is 22C."),                   // 4 ← digests {2,3}
            Message.User("what about tomorrow")                               // 5  recent
        )

        // Budget fits everything EXCEPT the oldest plain message "old"(1).
        val budget = counter.count(
            listOf(messages[0], messages[2], messages[3], messages[4], messages[5])
        )
        // Over total budget so Stage-1 early return does NOT fire.
        assertTrue(counter.count(messages) > budget)

        val conservative = manager.process(
            messages, ContextConfig(budget = budget, aggressiveness = TrimAggressiveness.CONSERVATIVE)
        )
        val aggressive = manager.process(
            messages, ContextConfig(budget = budget, aggressiveness = TrimAggressiveness.AGGRESSIVE)
        )

        // CONSERVATIVE: keeps the digested group (budget allows), drops the oldest plain msg.
        assertTrue(
            "CONSERVATIVE keeps the digested Tool message",
            conservative.messages.any { it is Message.Tool }
        )
        assertFalse(
            "CONSERVATIVE drops the oldest plain user message",
            conservative.messages.contains(Message.User("old"))
        )

        // AGGRESSIVE: proactively purges the whole digested group, freeing room for the older msg.
        assertFalse(
            "AGGRESSIVE purges the digested Tool message",
            aggressive.messages.any { it is Message.Tool }
        )
        assertFalse(
            "AGGRESSIVE purges the digested Assistant(toolCalls)",
            aggressive.messages.any { it is Message.Assistant && it.toolCalls != null }
        )
        assertTrue(
            "AGGRESSIVE keeps the older plain user message instead",
            aggressive.messages.contains(Message.User("old"))
        )

        // The two modes now produce genuinely different results.
        assertTrue(
            "CONSERVATIVE and AGGRESSIVE must differ",
            conservative.messages != aggressive.messages
        )
    }

    // ── Warning message ──

    @Test
    fun `warning is set when messages are removed`() {
        val messages = listOf(
            Message.System("system"),
            Message.User("msg1"),
            Message.User("msg2")
        )

        val config = ContextConfig(budget = counter.count(messages[0]) + 1)

        val result = manager.process(messages, config)

        assertTrue(result.removed.isNotEmpty())
        assertTrue(result.warning != null)
        assertTrue(result.warning!!.contains("Cropped"))
    }

    // ── Factory method ──

    @Test
    fun `default factory creates working instance`() {
        val defaultManager = DefaultLLMContextManager.default()

        val messages = listOf(
            Message.System("system"),
            Message.User("hello")
        )

        // Should not crash
        val result = defaultManager.process(messages, ContextConfig(budget = 100_000))
        assertEquals(messages, result.messages)
    }

    @Test
    fun `default factory respects custom safety margin`() {
        val manager1 = DefaultLLMContextManager.default(safetyMargin = 0.1f)
        val manager2 = DefaultLLMContextManager.default(safetyMargin = 0.5f)

        val messages = listOf(
            Message.System("system prompt with enough text to matter"),
            Message.User("a user message with some content"),
            Message.Assistant(content = "a reply")
        )

        // With a higher safety margin, the estimated tokens are higher,
        // so a borderline budget will trigger trimming for manager2 but not manager1
        val tokens1 = (manager1.tokenCounter as EstimatedTokenCounter).count(messages)
        val tokens2 = (manager2.tokenCounter as EstimatedTokenCounter).count(messages)

        assertTrue(
            "Higher safety margin should produce higher token count",
            tokens2 > tokens1
        )
    }
}
