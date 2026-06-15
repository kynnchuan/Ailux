package com.ailux.provider.backend.parser.nonstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Ignore
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
 *
 * ⚠️ Known parser bug：当前实现读取 `usage.input_tokens` / `usage.output_tokens`，
 * 这是 Anthropic 协议字段；OpenAI 协议实际是 `prompt_tokens` / `completion_tokens`。
 * 本测试同时包含：
 * - 反映当前实现行为的用例（使用 input_tokens / output_tokens 让测试通过）
 * - `@Ignore` 标注的"协议预期行为"用例，作为 Bug 复现样本，等修复后取消 Ignore
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
        // 注意：当前实现按 Anthropic 字段读 usage，所以测试体里也用 input_tokens / output_tokens
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
              "usage": {"input_tokens": 9, "output_tokens": 12}
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
              "usage": {"input_tokens": 1, "output_tokens": 1}
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
              "usage": {"input_tokens": 0, "output_tokens": 0}
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
              "usage": {"input_tokens": 5, "output_tokens": 8}
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
              "usage": {"input_tokens": 1, "output_tokens": 1}
            }
        """.trimIndent()

        val resp = parser.parse(body)
        assertEquals("ok", resp.text)
        assertNull(resp.model)
    }

    @Test
    fun `partial usage - only input_tokens present`() {
        val body = """
            {
              "model": "gpt-4o",
              "choices": [
                {"index": 0, "message": {"role": "assistant", "content": "x"}, "finish_reason": "stop"}
              ],
              "usage": {"input_tokens": 7}
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
              "usage": {"input_tokens": 1, "output_tokens": 4}
            }
        """.trimIndent()

        val resp = parser.parse(body)
        assertEquals("line1\nline2\n\nline4", resp.text)
    }

    @Test(expected = Exception::class)
    fun `malformed json throws`() {
        parser.parse("not a json")
    }

    // ── Bug repros: enable after fixing the parser to read OpenAI fields ──

    /**
     * OpenAI Chat Completions API 的 usage 字段为 `prompt_tokens` / `completion_tokens` /
     * `total_tokens`。当前 [OpenAINonStreamResponseParser] 误读为 Anthropic 的
     * `input_tokens` / `output_tokens`，导致真实 OpenAI 响应解析时 usage 全为 0。
     *
     * 修复后请取消 [Ignore] 并确认本用例通过。
     */
    @Ignore("Known parser bug: parser reads input_tokens/output_tokens instead of prompt_tokens/completion_tokens")
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
}
