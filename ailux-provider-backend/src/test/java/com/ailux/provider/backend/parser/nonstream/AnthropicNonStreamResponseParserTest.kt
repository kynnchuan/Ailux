package com.ailux.provider.backend.parser.nonstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AnthropicNonStreamResponseParserTest {

    private lateinit var parser: AnthropicNonStreamResponseParser

    @Before
    fun setUp() {
        parser = AnthropicNonStreamResponseParser()
    }

    @Test
    fun `single text block - text usage and model parsed`() {
        val body = """
            {
              "id": "msg_01ABC",
              "type": "message",
              "role": "assistant",
              "model": "claude-sonnet-4-5-20250929",
              "content": [
                {"type": "text", "text": "Hello, world!"}
              ],
              "stop_reason": "end_turn",
              "stop_sequence": null,
              "usage": {
                "input_tokens": 25,
                "output_tokens": 13,
                "cache_creation_input_tokens": 0,
                "cache_read_input_tokens": 0,
                "service_tier": "standard"
              }
            }
        """.trimIndent()

        val resp = parser.parse(body)

        assertEquals("Hello, world!", resp.text)
        assertEquals("claude-sonnet-4-5-20250929", resp.model)
        assertNotNull(resp.usage)
        assertEquals(25, resp.usage!!.inputTokens)
        assertEquals(13, resp.usage!!.outputTokens)
        assertEquals(false, resp.usage!!.estimated)
    }

    @Test
    fun `multiple text blocks are concatenated in order`() {
        val body = """
            {
              "id": "msg_01",
              "type": "message",
              "role": "assistant",
              "model": "claude-opus-4-1",
              "content": [
                {"type": "text", "text": "Hello, "},
                {"type": "text", "text": "world!"}
              ],
              "stop_reason": "end_turn",
              "usage": {"input_tokens": 1, "output_tokens": 2}
            }
        """.trimIndent()

        val resp = parser.parse(body)
        assertEquals("Hello, world!", resp.text)
    }

    @Test
    fun `thinking and tool_use blocks are ignored, only text kept`() {
        val body = """
            {
              "id": "msg_01",
              "type": "message",
              "role": "assistant",
              "model": "claude-sonnet-4-5",
              "content": [
                {"type": "thinking", "thinking": "Let me think...", "signature": "sig"},
                {"type": "text", "text": "The answer is "},
                {"type": "tool_use", "id": "tool_1", "name": "calculator", "input": {"a": 1}},
                {"type": "text", "text": "42."}
              ],
              "stop_reason": "tool_use",
              "usage": {"input_tokens": 10, "output_tokens": 5}
            }
        """.trimIndent()

        val resp = parser.parse(body)
        assertEquals("The answer is 42.", resp.text)
    }

    @Test
    fun `redacted_thinking and server tool blocks are ignored`() {
        val body = """
            {
              "id": "msg_01",
              "type": "message",
              "role": "assistant",
              "model": "claude-sonnet-4-5",
              "content": [
                {"type": "redacted_thinking", "data": "encrypted-blob"},
                {"type": "server_tool_use", "id": "srv_1", "name": "web_search", "input": {"query": "x"}},
                {"type": "web_search_tool_result", "tool_use_id": "srv_1", "content": []},
                {"type": "text", "text": "Done."}
              ],
              "stop_reason": "end_turn",
              "usage": {"input_tokens": 8, "output_tokens": 3}
            }
        """.trimIndent()

        val resp = parser.parse(body)
        assertEquals("Done.", resp.text)
    }

    @Test
    fun `missing usage yields null usage`() {
        val body = """
            {
              "id": "msg_01",
              "type": "message",
              "role": "assistant",
              "model": "claude-sonnet-4-5",
              "content": [{"type": "text", "text": "hi"}],
              "stop_reason": "end_turn"
            }
        """.trimIndent()

        val resp = parser.parse(body)
        assertEquals("hi", resp.text)
        assertNull(resp.usage)
    }

    @Test
    fun `empty content yields empty text`() {
        val body = """
            {
              "id": "msg_01",
              "type": "message",
              "role": "assistant",
              "model": "claude-sonnet-4-5",
              "content": [],
              "stop_reason": "end_turn",
              "usage": {"input_tokens": 4, "output_tokens": 0}
            }
        """.trimIndent()

        val resp = parser.parse(body)
        assertEquals("", resp.text)
        assertEquals(4, resp.usage!!.inputTokens)
        assertEquals(0, resp.usage!!.outputTokens)
    }

    @Test
    fun `unknown extra fields are ignored gracefully`() {
        val body = """
            {
              "id": "msg_01",
              "type": "message",
              "role": "assistant",
              "model": "claude-sonnet-4-5",
              "content": [{"type": "text", "text": "ok", "citations": null}],
              "stop_reason": "end_turn",
              "stop_details": null,
              "container": null,
              "usage": {
                "input_tokens": 1,
                "output_tokens": 1,
                "cache_creation": {"ephemeral_5m_input_tokens": 0, "ephemeral_1h_input_tokens": 0},
                "server_tool_use": {"web_search_requests": 0}
              },
              "future_field": "should_not_throw"
            }
        """.trimIndent()

        val resp = parser.parse(body)
        assertEquals("ok", resp.text)
        assertEquals(1, resp.usage!!.inputTokens)
        assertEquals(1, resp.usage!!.outputTokens)
    }
}
