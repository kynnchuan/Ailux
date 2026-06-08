package com.ailux.api.context

import com.ailux.core.message.Message
import com.ailux.core.tool.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EstimatedTokenCounterTest {

    private lateinit var counter: EstimatedTokenCounter

    @Before
    fun setup() {
        counter = EstimatedTokenCounter(safetyMargin = 0.15f)
    }

    // ── Basic counting ──

    @Test
    fun `empty message returns minimal overhead`() {
        val msg = Message.User("")
        val count = counter.count(msg)
        // Empty text → 0/10 + 4 = 4, then × 1.15 = 4
        assertTrue(count >= 4)
    }

    @Test
    fun `english text estimation`() {
        // "hello world" = 11 chars × 3 = 33, /10 = 3, +4 = 7, ×1.15 = 8
        val msg = Message.User("hello world")
        val count = counter.count(msg)
        assertTrue("English text should be > 0, got $count", count > 0)
        assertTrue("English text should be reasonable, got $count", count < 50)
    }

    @Test
    fun `chinese text costs more tokens than english`() {
        // 10 Chinese chars vs 10 English chars
        val chinese = Message.User("你好世界测试一下中文字")  // 10 CJK chars
        val english = Message.User("helloworld")         // 10 ASCII chars

        val chineseCount = counter.count(chinese)
        val englishCount = counter.count(english)

        assertTrue(
            "Chinese ($chineseCount) should cost more than English ($englishCount)",
            chineseCount > englishCount
        )
    }

    @Test
    fun `json structural characters cost more than plain text`() {
        val json = Message.User("""{"key":"value","arr":[1,2,3]}""")
        val plain = Message.User("key value arr 1 2 3 plain text")

        val jsonCount = counter.count(json)
        val plainCount = counter.count(plain)

        assertTrue(
            "JSON ($jsonCount) should cost more than plain text ($plainCount)",
            jsonCount > plainCount
        )
    }

    // ── Safety margin ──

    @Test
    fun `safety margin increases token count`() {
        val noMargin = EstimatedTokenCounter(safetyMargin = 0f)
        val highMargin = EstimatedTokenCounter(safetyMargin = 0.3f)

        val msg = Message.User("This is a test message with enough content to measure")

        val noMarginCount = noMargin.count(msg)
        val highMarginCount = highMargin.count(msg)

        assertTrue(
            "Higher margin ($highMarginCount) should exceed no margin ($noMarginCount)",
            highMarginCount > noMarginCount
        )
    }

    @Test
    fun `zero safety margin returns raw estimate`() {
        val noMargin = EstimatedTokenCounter(safetyMargin = 0f)
        val msg = Message.User("test")
        // "test" = 4 chars × 3 = 12, /10 = 1, +4 = 5, ×1.0 = 5
        val count = noMargin.count(msg)
        assertEquals(5, count)
    }

    // ── Message type handling ──

    @Test
    fun `system message content is counted`() {
        val msg = Message.System("You are a helpful assistant")
        val count = counter.count(msg)
        assertTrue(count > 4)
    }

    @Test
    fun `tool message content is counted`() {
        val msg = Message.Tool(toolCallId = "call_123", content = """{"result": "success"}""")
        val count = counter.count(msg)
        assertTrue(count > 4)
    }

    @Test
    fun `assistant message with toolCalls includes function name and arguments`() {
        val withCalls = Message.Assistant(
            content = "Let me check",
            toolCalls = listOf(
                ToolCall(id = "call_1", name = "get_weather", arguments = """{"city":"Beijing"}""")
            )
        )
        val withoutCalls = Message.Assistant(content = "Let me check")

        val withCallsCount = counter.count(withCalls)
        val withoutCallsCount = counter.count(withoutCalls)

        assertTrue(
            "Message with toolCalls ($withCallsCount) should cost more than without ($withoutCallsCount)",
            withCallsCount > withoutCallsCount
        )
    }

    @Test
    fun `assistant message with null content is handled`() {
        val msg = Message.Assistant(content = null, toolCalls = null)
        val count = counter.count(msg)
        // Should not crash, returns at least the overhead
        assertTrue(count >= 4)
    }

    // ── Batch counting ──

    @Test
    fun `count list equals sum of individual counts`() {
        val messages = listOf(
            Message.System("system prompt"),
            Message.User("hello"),
            Message.Assistant(content = "world")
        )

        val batchCount = counter.count(messages)
        val sumCount = messages.sumOf { counter.count(it) }

        assertEquals(sumCount, batchCount)
    }

    @Test
    fun `empty list returns zero`() {
        assertEquals(0, counter.count(emptyList()))
    }
}
