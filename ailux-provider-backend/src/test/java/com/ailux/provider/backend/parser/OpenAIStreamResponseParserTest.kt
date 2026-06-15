package com.ailux.provider.backend.parser

import com.ailux.core.event.FinishReason
import com.ailux.core.event.LLMEvent
import com.ailux.provider.backend.parser.stream.OpenAIStreamResponseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAIStreamResponseParserTest {

    private lateinit var parser: OpenAIStreamResponseParser

    @Before
    fun setUp() {
        parser = OpenAIStreamResponseParser()
    }

    // ── Normal text streaming ──

    @Test
    fun `parses content delta as Token event`() {
        val data = """{"choices":[{"delta":{"content":"Hello"},"index":0}]}"""
        val events = parser.parse("message", data)

        assertEquals(1, events.size)
        assertTrue(events[0] is LLMEvent.Token)
        assertEquals("Hello", (events[0] as LLMEvent.Token).text)
    }

    @Test
    fun `parses reasoning_content as Reasoning event`() {
        val data = """{"choices":[{"delta":{"reasoning_content":"Let me think..."},"index":0}]}"""
        val events = parser.parse("message", data)

        assertEquals(1, events.size)
        assertTrue(events[0] is LLMEvent.Reasoning)
        assertEquals("Let me think...", (events[0] as LLMEvent.Reasoning).text)
    }

    @Test
    fun `DONE without tool calls emits Done with COMPLETE`() {
        // First, send a content delta
        parser.parse("message", """{"choices":[{"delta":{"content":"Hi"},"index":0}]}""")
        // Then finish_reason
        parser.parse("message", """{"choices":[{"delta":{},"finish_reason":"stop","index":0}]}""")
        // Then [DONE]
        val events = parser.parse("message", "[DONE]")

        assertEquals(1, events.size)
        assertTrue(events[0] is LLMEvent.Done)
        assertEquals(FinishReason.COMPLETE, (events[0] as LLMEvent.Done).finishReason)
    }

    // ── Function Calling ──

    @Test
    fun `tool_calls deltas are aggregated and emitted on DONE`() {
        // First delta: id + function name
        parser.parse("message", """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_abc","type":"function","function":{"name":"get_weather","arguments":""}}]},"index":0}]}""")

        // Second delta: arguments fragment
        parser.parse("message", """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"city\":"}}]},"index":0}]}""")

        // Third delta: more arguments
        parser.parse("message", """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"Beijing\"}"}}]},"index":0}]}""")

        // finish_reason
        parser.parse("message", """{"choices":[{"delta":{},"finish_reason":"tool_calls","index":0}]}""")

        // [DONE] → should emit ToolCallReceived + Done(TOOL_CALL)
        val events = parser.parse("message", "[DONE]")

        assertEquals(2, events.size)

        // First event: ToolCallReceived
        assertTrue(events[0] is LLMEvent.ToolCallReceived)
        val received = events[0] as LLMEvent.ToolCallReceived
        assertEquals(1, received.toolCalls.size)
        assertEquals("call_abc", received.toolCalls[0].id)
        assertEquals("get_weather", received.toolCalls[0].name)
        assertEquals("{\"city\":\"Beijing\"}", received.toolCalls[0].arguments)

        // Second event: Done with TOOL_CALL
        assertTrue(events[1] is LLMEvent.Done)
        assertEquals(FinishReason.TOOL_CALL, (events[1] as LLMEvent.Done).finishReason)
    }

    @Test
    fun `multiple parallel tool calls are aggregated correctly`() {
        // Tool 1 - first chunk
        parser.parse("message", """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_01","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"BJ\"}"}}]},"index":0}]}""")

        // Tool 2 - first chunk
        parser.parse("message", """{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_02","type":"function","function":{"name":"get_time","arguments":"{\"tz\":\"UTC+8\"}"}}]},"index":0}]}""")

        // finish_reason
        parser.parse("message", """{"choices":[{"delta":{},"finish_reason":"tool_calls","index":0}]}""")

        // [DONE]
        val events = parser.parse("message", "[DONE]")

        assertEquals(2, events.size)
        val received = events[0] as LLMEvent.ToolCallReceived
        assertEquals(2, received.toolCalls.size)
        assertEquals("call_01", received.toolCalls[0].id)
        assertEquals("call_02", received.toolCalls[1].id)
    }

    @Test
    fun `content_filter finish_reason maps to CONTENT_FILTER`() {
        parser.parse("message", """{"choices":[{"delta":{},"finish_reason":"content_filter","index":0}]}""")
        val events = parser.parse("message", "[DONE]")

        assertEquals(1, events.size)
        assertTrue(events[0] is LLMEvent.Done)
        assertEquals(FinishReason.CONTENT_FILTER, (events[0] as LLMEvent.Done).finishReason)
    }

    @Test
    fun `length finish_reason maps to LENGTH`() {
        parser.parse("message", """{"choices":[{"delta":{"content":"Hi"},"index":0}]}""")
        parser.parse("message", """{"choices":[{"delta":{},"finish_reason":"length","index":0}]}""")
        val events = parser.parse("message", "[DONE]")

        assertEquals(1, events.size)
        assertEquals(FinishReason.LENGTH, (events[0] as LLMEvent.Done).finishReason)
    }

    // ── Usage ──

    @Test
    fun `usage chunk is parsed correctly`() {
        val data = """{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}"""
        val events = parser.parse("message", data)

        assertEquals(1, events.size)
        assertTrue(events[0] is LLMEvent.Usage)
        val usage = (events[0] as LLMEvent.Usage).info
        assertEquals(10, usage.inputTokens)
        assertEquals(20, usage.outputTokens)
    }

    // ── Error handling ──

    @Test
    fun `error field in response emits Error event`() {
        val data = """{"error":{"message":"Rate limit exceeded","code":"rate_limit_exceeded"}}"""
        val events = parser.parse("message", data)

        assertEquals(1, events.size)
        assertTrue(events[0] is LLMEvent.Error)
        val error = (events[0] as LLMEvent.Error).error
        assertEquals("Rate limit exceeded", error.message)
    }

    @Test
    fun `empty data returns empty list`() {
        val events = parser.parse("message", "")
        assertTrue(events.isEmpty())
    }

    @Test
    fun `malformed JSON returns empty list`() {
        val events = parser.parse("message", "not json at all{{{")
        assertTrue(events.isEmpty())
    }

    @Test
    fun `role-only delta returns empty list`() {
        val data = """{"choices":[{"delta":{"role":"assistant"},"index":0}]}"""
        val events = parser.parse("message", data)
        assertTrue(events.isEmpty())
    }
}
