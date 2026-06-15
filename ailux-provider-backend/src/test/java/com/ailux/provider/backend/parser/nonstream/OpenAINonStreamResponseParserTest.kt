package com.ailux.provider.backend.parser.nonstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [OpenAINonStreamResponseParser].
 *
 * 协议参考：OpenAI Chat Completions API（`POST /v1/chat/completions`，最新版）。
 * 典型 non-streaming 响应形如：
 * ```json
 * {
 *   "id": "chatcmpl-...",
 *   "object": "chat.completion",
 *   "created": 1715000000,
 *   "model": "gpt-4o-2024-11-20",
 *   "choices": [
 *     {
 *       "index": 0,
 *       "message": {"role": "assistant", "content": "Hello!"},
 *       "finish_reason": "stop"
 *     }
 *   ],
 *   "usage": {
 *     "prompt_tokens": 9,
 *     "completion_tokens": 12,
 *     "total_tokens": 21
 *   }
 * }
 * ```
 */
class OpenAINonStreamResponseParserTest {

    private lateinit var parser: OpenAINonStreamResponseParser

    @Before
    fun setUp() {
        parser = OpenAINonStreamResponseParser()
    }

    // ── Happy path ──

    @Test
    fun `single choice - text model and usage parsed`() {
        val body = """
            {
              "id": "chatcmpl-abc",
              "object": "chat.completion",
              "created": 1715000000,
              "model": "gpt-4o-2024-11-20",
              "choices": [
                {
                  "index": 0,
                  "message": {"role": "assistant", "content": "Hello, world!"},
                  "finish_reason": "stop"
                }
              ],
              "usage": {"prompt_tokens": 9, "completion_tokens": 12, "total_tokens": 21}
            }
        """.trimIndent()

        val resp = parser.parse(body)

        assertEquals("Hello, world!", resp.text)
        assertEquals("gpt-4o-2024-11-20", resp.model)
        assertNotNull(resp.usage)
        assertEquals(9, resp.usage!!.inputTokens)
        assertEquals(12, resp.usage!!.outputTokens)
        assertEquals(false, resp.usage!!.estimated)
    }

    @Test
    fun `multiple choices - only the first is taken`() {
        val body = """
            {
              "id": "chatcmpl-1",
              "model": "gpt-4o-mini",
              "choices": [
                {"index": 0, "message": {"role": "assistant", "content": "primary"}, "finish_reason": "stop"},
                {"index": 1, "message": {"role": "assistant", "content": "secondary"}, "finish_reason": "stop"}
              ],
              "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
            }
        """.trimIndent()

        val resp = parser.parse(body)
        assertEquals("primary", resp.text)
    }

    // ── Edge cases ──

    @Test
    fun `empty choices yields empty text`() {
        val body = """
            {
              "id": "chatcmpl-2",
              "model": "gpt-4o-mini",
              "choices": [],
              "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}
            }
        """.trimIndent()

        val resp = parser.parse(body)
        assertEquals("", resp.text)
        assertEquals(0, resp.usage!!.inputTokens)
        assertEquals(0, resp.usage!!.outputTokens)
    }

    @Test
    fun `null content in message yields empty text`() {
        // 出现工具调用时，OpenAI 会把 content 设为 null
        val body = """
            {
              "id": "chatcmpl-3",
              "model": "gpt-4o",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": null,
                    "tool_calls": [
                      {"id": "call_1", "type": "function",
                       "function": {"name": "get_weather", "arguments": "{}"}}
                    ]
                  },
                  "finish_reason": "tool_calls"
                }
              ],
              "usage": {"prompt_tokens": 5, "completion_tokens": 8, "total_tokens": 13}
            }
        """.trimIndent()

        val resp = parser.parse(body)
        // content == null -> contentOrNull == null -> 兜底为 ""
        assertEquals("", resp.text)
        assertEquals("gpt-4o", resp.model)
    }

    @Test
    fun `missing usage yields null usage`() {
        val body = """
            {
              "id": "chatcmpl-4",
              "model": "gpt-4o",
              "choices": [
                {"index": 0, "message": {"role": "assistant", "content": "ok"}, "finish_reason": "stop"}
              ]
            }
        """.trimIndent()

        val resp = parser.parse(body)
        assertEquals("ok", resp.text)
        assertNull(resp.usage)
    }

    @Test
    fun `missing model yields null model`() {
        val body = """
            {
              "id": "chatcmpl-5",
              "choices": [
                {"index": 0, "message": {"role": "assistant", "content": "ok"}, "finish_reason": "stop"}
              ],
              "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
            }
        """.trimIndent()

        val resp = parser.parse(body)
        assertEquals("ok", resp.text)
        assertNull(resp.model)
    }

    @Test
    fun `partial usage - only prompt_tokens present`() {
        val body = """
            {
              "model": "gpt-4o",
              "choices": [
                {"index": 0, "message": {"role": "assistant", "content": "x"}, "finish_reason": "stop"}
              ],
              "usage": {"prompt_tokens": 7}
            }
        """.trimIndent()

        val resp = parser.parse(body)
        assertEquals(7, resp.usage!!.inputTokens)
        assertEquals(0, resp.usage!!.outputTokens) // 缺失字段兜底 0
    }

    @Test
    fun `multiline content is preserved verbatim`() {
        val body = """
            {
              "model": "gpt-4o",
              "choices": [
                {"index": 0,
                 "message": {"role": "assistant", "content": "line1\nline2\n\nline4"},
                 "finish_reason": "stop"}
              ],
              "usage": {"prompt_tokens": 1, "completion_tokens": 4, "total_tokens": 5}
            }
        """.trimIndent()

        val resp = parser.parse(body)
        assertEquals("line1\nline2\n\nline4", resp.text)
    }

    @Test(expected = Exception::class)
    fun `malformed json throws`() {
        parser.parse("not a json")
    }

    // ── OpenAI spec compliance (was @Ignore'd before usage field fix) ──

    @Test
    fun `usage reads prompt_tokens and completion_tokens per OpenAI spec`() {
        val body = """
            {
              "id": "chatcmpl-real",
              "object": "chat.completion",
              "created": 1715000000,
              "model": "gpt-4o-2024-11-20",
              "choices": [
                {"index": 0,
                 "message": {"role": "assistant", "content": "Hello!"},
                 "finish_reason": "stop"}
              ],
              "usage": {
                "prompt_tokens": 9,
                "completion_tokens": 12,
                "total_tokens": 21
              }
            }
        """.trimIndent()

        val resp = parser.parse(body)
        assertEquals(9, resp.usage!!.inputTokens)
        assertEquals(12, resp.usage!!.outputTokens)
    }

    @Test
    fun `unknown fields in response are ignored gracefully`() {
        val body = """
            {
              "id": "chatcmpl-6",
              "object": "chat.completion",
              "model": "gpt-4o",
              "system_fingerprint": "fp_abc123",
              "choices": [
                {
                  "index": 0,
                  "message": {"role": "assistant", "content": "hi"},
                  "logprobs": null,
                  "finish_reason": "stop"
                }
              ],
              "usage": {
                "prompt_tokens": 5,
                "completion_tokens": 2,
                "total_tokens": 7,
                "prompt_tokens_details": {"cached_tokens": 0},
                "completion_tokens_details": {"reasoning_tokens": 0}
              }
            }
        """.trimIndent()

        val resp = parser.parse(body)
        assertEquals("hi", resp.text)
        assertEquals("gpt-4o", resp.model)
        assertEquals(5, resp.usage!!.inputTokens)
        assertEquals(2, resp.usage!!.outputTokens)
    }
}
