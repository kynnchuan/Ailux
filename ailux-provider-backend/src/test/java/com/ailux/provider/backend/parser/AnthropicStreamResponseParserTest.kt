package com.ailux.provider.backend.parser

import com.ailux.core.event.FinishReason
import com.ailux.core.event.LLMEvent
import com.ailux.provider.backend.parser.stream.AnthropicStreamResponseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnthropicStreamResponseParserTest {

    private lateinit var parser: AnthropicStreamResponseParser

    @Before
    fun setUp() {
        parser = AnthropicStreamResponseParser()
    }

    // ── Normal text streaming ──

    @Test
    fun `text_delta emits Token event`() {
        val data = """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}"""
        val events = parser.parse("content_block_delta", data)

        assertEquals(1, events.size)
        assertTrue(events[0] is LLMEvent.Token)
        assertEquals("Hello", (events[0] as LLMEvent.Token).text)
    }

    @Test
    fun `thinking_delta emits Reasoning event`() {
        val data = """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Let me analyze..."}}"""
        val events = parser.parse("content_block_delta", data)

        assertEquals(1, events.size)
        assertTrue(events[0] is LLMEvent.Reasoning)
        assertEquals("Let me analyze...", (events[0] as LLMEvent.Reasoning).text)
    }

    @Test
    fun `message_stop with end_turn emits Done COMPLETE`() {
        // message_delta with stop_reason
        parser.parse("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":15}}""")

        // message_stop
        val events = parser.parse("message_stop", """{"type":"message_stop"}""")

        assertEquals(1, events.size)
        assertTrue(events[0] is LLMEvent.Done)
        assertEquals(FinishReason.COMPLETE, (events[0] as LLMEvent.Done).finishReason)
    }

    // ── Function Calling ──

    @Test
    fun `tool_use content blocks are aggregated and emitted on message_stop`() {
        // content_block_start with tool_use
        parser.parse("content_block_start", """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_01","name":"get_weather","input":{}}}""")

        // input_json_delta fragments
        parser.parse("content_block_delta", """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"city\":"}}""")
        parser.parse("content_block_delta", """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"Beijing\"}"}}""")

        // message_delta with stop_reason: tool_use
        parser.parse("message_delta", """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":25}}""")

        // message_stop → should emit ToolCallReceived + Done(TOOL_CALL)
        val events = parser.parse("message_stop", """{"type":"message_stop"}""")

        assertEquals(2, events.size)

        // First: ToolCallReceived
        assertTrue(events[0] is LLMEvent.ToolCallReceived)
        val received = events[0] as LLMEvent.ToolCallReceived
        assertEquals(1, received.toolCalls.size)
        assertEquals("toolu_01", received.toolCalls[0].id)
        assertEquals("get_weather", received.toolCalls[0].name)
        assertEquals("{\"city\":\"Beijing\"}", received.toolCalls[0].arguments)

        // Second: Done(TOOL_CALL)
        assertTrue(events[1] is LLMEvent.Done)
        assertEquals(FinishReason.TOOL_CALL, (events[1] as LLMEvent.Done).finishReason)
    }

    @Test
    fun `multiple tool_use blocks are aggregated`() {
        // Tool 1
        parser.parse("content_block_start", """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_01","name":"get_weather","input":{}}}""")
        parser.parse("content_block_delta", """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"city\":\"BJ\"}"}}""")

        // Tool 2
        parser.parse("content_block_start", """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_02","name":"get_time","input":{}}}""")
        parser.parse("content_block_delta", """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"tz\":\"UTC+8\"}"}}""")

        // stop_reason: tool_use
        parser.parse("message_delta", """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":30}}""")

        val events = parser.parse("message_stop", """{"type":"message_stop"}""")

        assertEquals(2, events.size)
        val received = events[0] as LLMEvent.ToolCallReceived
        assertEquals(2, received.toolCalls.size)
        assertEquals("toolu_01", received.toolCalls[0].id)
        assertEquals("get_weather", received.toolCalls[0].name)
        assertEquals("toolu_02", received.toolCalls[1].id)
        assertEquals("get_time", received.toolCalls[1].name)
    }

    @Test
    fun `refusal stop_reason maps to CONTENT_FILTER`() {
        parser.parse("message_delta", """{"type":"message_delta","delta":{"stop_reason":"refusal"},"usage":{"output_tokens":0}}""")
        val events = parser.parse("message_stop", """{"type":"message_stop"}""")

        assertEquals(1, events.size)
        assertEquals(FinishReason.CONTENT_FILTER, (events[0] as LLMEvent.Done).finishReason)
    }

    @Test
    fun `max_tokens stop_reason maps to LENGTH`() {
        parser.parse("message_delta", """{"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":4096}}""")
        val events = parser.parse("message_stop", """{"type":"message_stop"}""")

        assertEquals(1, events.size)
        assertEquals(FinishReason.LENGTH, (events[0] as LLMEvent.Done).finishReason)
    }

    // ── Usage ──

    @Test
    fun `message_delta with usage emits Usage event`() {
        val events = parser.parse("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":42}}""")

        assertEquals(1, events.size)
        assertTrue(events[0] is LLMEvent.Usage)
        assertEquals(42, (events[0] as LLMEvent.Usage).info.outputTokens)
    }

    // ── Error handling ──

    @Test
    fun `error event emits Error`() {
        val data = """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""
        val events = parser.parse("error", data)

        assertEquals(1, events.size)
        assertTrue(events[0] is LLMEvent.Error)
        assertEquals("Overloaded", (events[0] as LLMEvent.Error).error.message)
    }

    @Test
    fun `unknown event type returns empty list`() {
        val events = parser.parse("ping", """{"type":"ping"}""")
        assertTrue(events.isEmpty())
    }

    @Test
    fun `message_start is ignored`() {
        val events = parser.parse("message_start", """{"type":"message_start","message":{"id":"msg_01","model":"claude-3-5-sonnet"}}""")
        assertTrue(events.isEmpty())
    }

    @Test
    fun `empty data returns empty list`() {
        val events = parser.parse("content_block_delta", "")
        assertTrue(events.isEmpty())
    }
}
