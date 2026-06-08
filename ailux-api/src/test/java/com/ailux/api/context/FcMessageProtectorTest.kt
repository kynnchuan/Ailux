package com.ailux.api.context

import com.ailux.core.context.TrimAggressiveness
import com.ailux.core.message.Message
import com.ailux.core.tool.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FcMessageProtectorTest {

    private lateinit var protector: FcMessageProtector

    @Before
    fun setup() {
        protector = FcMessageProtector()
    }

    // ── System message protection ──

    @Test
    fun `system messages are always protected`() {
        val messages = listOf(
            Message.System("system prompt"),       // 0
            Message.User("hello"),                 // 1
            Message.Assistant(content = "world")   // 2
        )

        val result = protector.protect(messages, TrimAggressiveness.CONSERVATIVE)

        assertTrue("Index 0 (system) should be protected", 0 in result)
        assertFalse("Index 1 (user) should NOT be protected", 1 in result)
        assertFalse("Index 2 (assistant) should NOT be protected", 2 in result)
    }

    @Test
    fun `multiple system messages are all protected`() {
        val messages = listOf(
            Message.System("system 1"),   // 0
            Message.System("system 2"),   // 1
            Message.User("hello")         // 2
        )

        val result = protector.protect(messages, TrimAggressiveness.CONSERVATIVE)

        assertTrue(0 in result)
        assertTrue(1 in result)
        assertFalse(2 in result)
    }

    // ── Undigested tool group protection ──

    @Test
    fun `undigested tool group is protected in CONSERVATIVE mode`() {
        val messages = listOf(
            Message.System("system"),                                         // 0
            Message.User("What's the weather?"),                              // 1
            Message.Assistant(                                                // 2
                toolCalls = listOf(
                    ToolCall(id = "call_1", name = "get_weather", arguments = """{"city":"Beijing"}""")
                )
            ),
            Message.Tool(toolCallId = "call_1", content = """{"temp":"22C"}""")  // 3
            // No subsequent assistant reply → NOT digested
        )

        val result = protector.protect(messages, TrimAggressiveness.CONSERVATIVE)

        assertTrue("System (0) protected", 0 in result)
        assertTrue("Assistant with toolCalls (2) protected", 2 in result)
        assertTrue("Tool response (3) protected", 3 in result)
    }

    @Test
    fun `undigested tool group is protected in AGGRESSIVE mode too`() {
        val messages = listOf(
            Message.System("system"),                                         // 0
            Message.User("query"),                                            // 1
            Message.Assistant(                                                // 2
                toolCalls = listOf(
                    ToolCall(id = "call_1", name = "search", arguments = """{"q":"test"}""")
                )
            ),
            Message.Tool(toolCallId = "call_1", content = "result")          // 3
            // No digest
        )

        val result = protector.protect(messages, TrimAggressiveness.AGGRESSIVE)

        assertTrue("Undigested FC group must be protected even in AGGRESSIVE", 2 in result)
        assertTrue("Undigested FC tool must be protected even in AGGRESSIVE", 3 in result)
    }

    // ── Digested tool group handling ──

    @Test
    fun `digested tool group is NOT force-protected in CONSERVATIVE mode`() {
        val messages = listOf(
            Message.System("system"),                                         // 0
            Message.User("What's the weather?"),                              // 1
            Message.Assistant(                                                // 2
                toolCalls = listOf(
                    ToolCall(id = "call_1", name = "get_weather", arguments = """{"city":"Beijing"}""")
                )
            ),
            Message.Tool(toolCallId = "call_1", content = """{"temp":"22C"}"""),  // 3
            Message.Assistant(content = "The weather in Beijing is 22°C.")    // 4 ← digests the tool group
        )

        val result = protector.protect(messages, TrimAggressiveness.CONSERVATIVE)

        assertTrue("System (0) always protected", 0 in result)
        // Digested FC group is NOT force-protected in CONSERVATIVE — it participates
        // in normal budget-based trimming (kept if budget allows, dropped if tight).
        assertFalse("Digested FC assistant (2) NOT force-protected in CONSERVATIVE", 2 in result)
        assertFalse("Digested FC tool (3) NOT force-protected in CONSERVATIVE", 3 in result)
    }

    @Test
    fun `digested tool group is NOT protected in AGGRESSIVE mode`() {
        val messages = listOf(
            Message.System("system"),                                         // 0
            Message.User("query"),                                            // 1
            Message.Assistant(                                                // 2
                toolCalls = listOf(
                    ToolCall(id = "call_1", name = "search", arguments = """{"q":"test"}""")
                )
            ),
            Message.Tool(toolCallId = "call_1", content = "data"),           // 3
            Message.Assistant(content = "Here's what I found.")              // 4 ← digest
        )

        val result = protector.protect(messages, TrimAggressiveness.AGGRESSIVE)

        assertFalse("Digested FC assistant (2) NOT protected", 2 in result)
        assertFalse("Digested FC tool (3) NOT protected", 3 in result)
    }

    // ── Parallel tool calls (1 Assistant + N Tools) ──

    @Test
    fun `parallel tool calls are treated as one group`() {
        val messages = listOf(
            Message.System("system"),                                         // 0
            Message.User("Get weather and news"),                             // 1
            Message.Assistant(                                                // 2
                toolCalls = listOf(
                    ToolCall(id = "call_1", name = "get_weather", arguments = """{"city":"Beijing"}"""),
                    ToolCall(id = "call_2", name = "get_news", arguments = """{"topic":"tech"}""")
                )
            ),
            Message.Tool(toolCallId = "call_1", content = """{"temp":"22C"}"""),  // 3
            Message.Tool(toolCallId = "call_2", content = """{"headline":"AI"}""") // 4
            // Not digested
        )

        val result = protector.protect(messages, TrimAggressiveness.CONSERVATIVE)

        // Entire group (2, 3, 4) should be protected as undigested
        assertTrue("Assistant (2) protected", 2 in result)
        assertTrue("Tool 1 (3) protected", 3 in result)
        assertTrue("Tool 2 (4) protected", 4 in result)
    }

    @Test
    fun `parallel tool calls digested - NOT force-protected in CONSERVATIVE`() {
        val messages = listOf(
            Message.System("system"),                                         // 0
            Message.User("Get weather and news"),                             // 1
            Message.Assistant(                                                // 2
                toolCalls = listOf(
                    ToolCall(id = "call_1", name = "get_weather", arguments = """{"city":"BJ"}"""),
                    ToolCall(id = "call_2", name = "get_news", arguments = """{"t":"ai"}""")
                )
            ),
            Message.Tool(toolCallId = "call_1", content = "22C"),            // 3
            Message.Tool(toolCallId = "call_2", content = "AI news"),        // 4
            Message.Assistant(content = "Weather is 22C, and AI news...")     // 5 ← digest
        )

        val result = protector.protect(messages, TrimAggressiveness.CONSERVATIVE)

        // Digested + CONSERVATIVE → NOT force-protected (budget decides)
        assertFalse(2 in result)
        assertFalse(3 in result)
        assertFalse(4 in result)
    }

    @Test
    fun `parallel tool calls digested - NOT protected in AGGRESSIVE`() {
        val messages = listOf(
            Message.System("system"),                                         // 0
            Message.User("Get weather and news"),                             // 1
            Message.Assistant(                                                // 2
                toolCalls = listOf(
                    ToolCall(id = "call_1", name = "get_weather", arguments = """{"city":"BJ"}"""),
                    ToolCall(id = "call_2", name = "get_news", arguments = """{"t":"ai"}""")
                )
            ),
            Message.Tool(toolCallId = "call_1", content = "22C"),            // 3
            Message.Tool(toolCallId = "call_2", content = "AI news"),        // 4
            Message.Assistant(content = "Weather is 22C, and AI news...")     // 5 ← digest
        )

        val result = protector.protect(messages, TrimAggressiveness.AGGRESSIVE)

        // Digested + AGGRESSIVE → not protected
        assertFalse(2 in result)
        assertFalse(3 in result)
        assertFalse(4 in result)
    }

    // ── Mixed scenario: multiple FC rounds ──

    @Test
    fun `mixed digested and undigested groups`() {
        val messages = listOf(
            Message.System("system"),                                         // 0
            Message.User("step 1"),                                           // 1
            // First FC group — digested
            Message.Assistant(                                                // 2
                toolCalls = listOf(ToolCall(id = "c1", name = "fn1", arguments = "{}"))
            ),
            Message.Tool(toolCallId = "c1", content = "r1"),                 // 3
            Message.Assistant(content = "Done step 1"),                       // 4 ← digests group 1

            Message.User("step 2"),                                           // 5
            // Second FC group — NOT digested
            Message.Assistant(                                                // 6
                toolCalls = listOf(ToolCall(id = "c2", name = "fn2", arguments = "{}"))
            ),
            Message.Tool(toolCallId = "c2", content = "r2")                  // 7
        )

        val result = protector.protect(messages, TrimAggressiveness.CONSERVATIVE)

        // System always protected
        assertTrue(0 in result)
        // Group 1 (indices 2,3) digested + CONSERVATIVE → NOT force-protected
        assertFalse("Digested group 1 assistant (2) not force-protected", 2 in result)
        assertFalse("Digested group 1 tool (3) not force-protected", 3 in result)
        // Group 2 (indices 6,7) NOT digested → protected
        assertTrue("Undigested group 2 assistant (6) protected", 6 in result)
        assertTrue("Undigested group 2 tool (7) protected", 7 in result)
    }

    @Test
    fun `mixed digested and undigested groups - AGGRESSIVE mode`() {
        val messages = listOf(
            Message.System("system"),                                         // 0
            Message.User("step 1"),                                           // 1
            // First FC group — digested
            Message.Assistant(                                                // 2
                toolCalls = listOf(ToolCall(id = "c1", name = "fn1", arguments = "{}"))
            ),
            Message.Tool(toolCallId = "c1", content = "r1"),                 // 3
            Message.Assistant(content = "Done step 1"),                       // 4 ← digests group 1

            Message.User("step 2"),                                           // 5
            // Second FC group — NOT digested
            Message.Assistant(                                                // 6
                toolCalls = listOf(ToolCall(id = "c2", name = "fn2", arguments = "{}"))
            ),
            Message.Tool(toolCallId = "c2", content = "r2")                  // 7
        )

        val result = protector.protect(messages, TrimAggressiveness.AGGRESSIVE)

        // System always protected
        assertTrue(0 in result)
        // Group 1 (indices 2,3) digested + AGGRESSIVE → NOT protected
        assertFalse("Digested group 1 assistant (2) not protected in AGGRESSIVE", 2 in result)
        assertFalse("Digested group 1 tool (3) not protected in AGGRESSIVE", 3 in result)
        // Group 2 (indices 6,7) NOT digested → still protected
        assertTrue("Undigested group 2 assistant (6) protected", 6 in result)
        assertTrue("Undigested group 2 tool (7) protected", 7 in result)
    }

    // ── Edge cases ──

    @Test
    fun `no tool calls returns only system indices`() {
        val messages = listOf(
            Message.System("system"),
            Message.User("hello"),
            Message.Assistant(content = "world")
        )

        val result = protector.protect(messages, TrimAggressiveness.CONSERVATIVE)

        assertEquals(setOf(0), result)
    }

    @Test
    fun `empty message list returns empty set`() {
        val result = protector.protect(emptyList(), TrimAggressiveness.CONSERVATIVE)
        assertTrue(result.isEmpty())
    }
}
