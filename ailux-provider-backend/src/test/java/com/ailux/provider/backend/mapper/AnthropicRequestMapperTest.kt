package com.ailux.provider.backend.mapper

import com.ailux.core.error.ErrorCode
import com.ailux.core.error.LLMException
import com.ailux.core.message.Message
import com.ailux.core.request.Attachment
import com.ailux.core.request.AttachmentSource
import com.ailux.core.request.LLMRequest
import com.ailux.core.tool.ToolCall
import com.ailux.core.tool.ToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnthropicRequestMapperTest {

    private lateinit var mapper: AnthropicRequestMapper
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        mapper = AnthropicRequestMapper()
    }

    @Test
    fun `system message is extracted to top-level field`() {
        val request = LLMRequest(
            messages = listOf(
                Message.System("You are helpful."),
                Message.User("Hello"),
            ),
            tools = emptyList(),
            model = "claude-3-5-sonnet-20241022",
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject

        // System is top-level
        assertEquals("You are helpful.", result["system"]?.jsonPrimitive?.content)

        // Messages should not include the system message
        val messages = result["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun `user message uses content blocks format`() {
        val request = LLMRequest(
            messages = listOf(Message.User("Hello world")),
            tools = emptyList(),
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        val messages = result["messages"]!!.jsonArray
        val userMsg = messages[0].jsonObject

        assertEquals("user", userMsg["role"]?.jsonPrimitive?.content)
        val content = userMsg["content"]!!.jsonArray
        assertEquals(1, content.size)
        assertEquals("text", content[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("Hello world", content[0].jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tools are mapped with input_schema field`() {
        val tool = ToolDefinition(
            name = "get_weather",
            description = "Get weather for a city.",
            arguments = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("city") { put("type", "string") }
                }
            },
        )

        val request = LLMRequest(
            messages = listOf(Message.User("Weather?")),
            tools = listOf(tool),
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        val tools = result["tools"]!!.jsonArray
        assertEquals(1, tools.size)

        val toolObj = tools[0].jsonObject
        assertEquals("get_weather", toolObj["name"]?.jsonPrimitive?.content)
        assertEquals("Get weather for a city.", toolObj["description"]?.jsonPrimitive?.content)
        assertNotNull(toolObj["input_schema"])
        assertEquals("object", toolObj["input_schema"]!!.jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `assistant tool_calls are mapped as tool_use content blocks`() {
        val request = LLMRequest(
            messages = listOf(
                Message.User("Weather in Beijing?"),
                Message.Assistant(
                    content = "Let me check.",
                    toolCalls = listOf(
                        ToolCall(id = "toolu_01", name = "get_weather", arguments = "{\"city\":\"Beijing\"}")
                    ),
                ),
            ),
            tools = emptyList(),
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        val messages = result["messages"]!!.jsonArray
        val assistantMsg = messages[1].jsonObject

        assertEquals("assistant", assistantMsg["role"]?.jsonPrimitive?.content)
        val content = assistantMsg["content"]!!.jsonArray

        // First block: text
        assertEquals("text", content[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("Let me check.", content[0].jsonObject["text"]?.jsonPrimitive?.content)

        // Second block: tool_use
        val toolUse = content[1].jsonObject
        assertEquals("tool_use", toolUse["type"]?.jsonPrimitive?.content)
        assertEquals("toolu_01", toolUse["id"]?.jsonPrimitive?.content)
        assertEquals("get_weather", toolUse["name"]?.jsonPrimitive?.content)
        assertEquals("Beijing", toolUse["input"]!!.jsonObject["city"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tool results are mapped as user message with tool_result blocks`() {
        val request = LLMRequest(
            messages = listOf(
                Message.User("Weather?"),
                Message.Assistant(toolCalls = listOf(ToolCall("toolu_01", "get_weather", "{}"))),
                Message.Tool(toolCallId = "toolu_01", content = "{\"temp\":\"22C\"}"),
            ),
            tools = emptyList(),
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        val messages = result["messages"]!!.jsonArray

        // Third message should be user with tool_result
        val toolResultMsg = messages[2].jsonObject
        assertEquals("user", toolResultMsg["role"]?.jsonPrimitive?.content)

        val content = toolResultMsg["content"]!!.jsonArray
        assertEquals(1, content.size)
        val block = content[0].jsonObject
        assertEquals("tool_result", block["type"]?.jsonPrimitive?.content)
        assertEquals("toolu_01", block["tool_use_id"]?.jsonPrimitive?.content)
        assertEquals("{\"temp\":\"22C\"}", block["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `consecutive tool results are merged into one user message`() {
        val request = LLMRequest(
            messages = listOf(
                Message.User("Do things"),
                Message.Assistant(toolCalls = listOf(
                    ToolCall("t1", "func1", "{}"),
                    ToolCall("t2", "func2", "{}"),
                )),
                Message.Tool(toolCallId = "t1", content = "result1"),
                Message.Tool(toolCallId = "t2", content = "result2"),
            ),
            tools = emptyList(),
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        val messages = result["messages"]!!.jsonArray

        // Should be 3 messages: user, assistant, user(tool_results)
        assertEquals(3, messages.size)

        val toolResultMsg = messages[2].jsonObject
        assertEquals("user", toolResultMsg["role"]?.jsonPrimitive?.content)
        val content = toolResultMsg["content"]!!.jsonArray
        assertEquals(2, content.size)
        assertEquals("t1", content[0].jsonObject["tool_use_id"]?.jsonPrimitive?.content)
        assertEquals("t2", content[1].jsonObject["tool_use_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tool_choice auto maps to object form`() {
        val request = LLMRequest(
            messages = listOf(Message.User("test")),
            tools = listOf(
                ToolDefinition("f", "d", buildJsonObject { put("type", "object") })
            ),
            toolChoice = "auto",
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        val toolChoice = result["tool_choice"]!!.jsonObject
        assertEquals("auto", toolChoice["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tool_choice required maps to any type`() {
        val request = LLMRequest(
            messages = listOf(Message.User("test")),
            tools = listOf(
                ToolDefinition("f", "d", buildJsonObject { put("type", "object") })
            ),
            toolChoice = "required",
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        val toolChoice = result["tool_choice"]!!.jsonObject
        assertEquals("any", toolChoice["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tool_choice specific tool name maps to tool type with name`() {
        val request = LLMRequest(
            messages = listOf(Message.User("test")),
            tools = listOf(
                ToolDefinition("get_weather", "d", buildJsonObject { put("type", "object") })
            ),
            toolChoice = "get_weather",
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        val toolChoice = result["tool_choice"]!!.jsonObject
        assertEquals("tool", toolChoice["type"]?.jsonPrimitive?.content)
        assertEquals("get_weather", toolChoice["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `max_tokens defaults to 4096 when not specified`() {
        val request = LLMRequest(
            messages = listOf(Message.User("test")),
            tools = emptyList(),
            maxTokens = null,
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        assertEquals(4096, result["max_tokens"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `stream field is set correctly`() {
        val request = LLMRequest(messages = listOf(Message.User("hi")), tools = emptyList())

        val streamResult = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        assertEquals("true", streamResult["stream"]?.jsonPrimitive?.content)

        val nonStreamResult = json.parseToJsonElement(mapper.map(request, stream = false)).jsonObject
        assertEquals("false", nonStreamResult["stream"]?.jsonPrimitive?.content)
    }

    @Test
    fun `no system message means no system field in output`() {
        val request = LLMRequest(
            messages = listOf(Message.User("hello")),
            tools = emptyList(),
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        assertNull(result["system"])
    }

    // --- v0.2.4: stop sequences ---

    @Test
    fun `stop sequences mapped to stop_sequences field`() {
        val request = LLMRequest(
            messages = listOf(Message.User("test")),
            stop = listOf("\n\n", "END"),
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        val stopSeqs = result["stop_sequences"]!!.jsonArray
        assertEquals(2, stopSeqs.size)
        assertEquals("\n\n", stopSeqs[0].jsonPrimitive.content)
        assertEquals("END", stopSeqs[1].jsonPrimitive.content)
    }

    @Test
    fun `empty stop list means no stop_sequences field`() {
        val request = LLMRequest(
            messages = listOf(Message.User("test")),
            stop = emptyList(),
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        assertNull(result["stop_sequences"])
    }

    // --- v0.2.4: multimodal attachments ---

    @Test
    fun `base64 attachment appended as image block on last user message`() {
        val request = LLMRequest(
            messages = listOf(
                Message.User("First message"),
                Message.User("What is in this image?"),
            ),
            attachments = listOf(
                Attachment(AttachmentSource.Base64("abc123"), "image/png"),
            ),
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        val messages = result["messages"]!!.jsonArray

        // First user message: no attachment
        val first = messages[0].jsonObject["content"]!!.jsonArray
        assertEquals(1, first.size)
        assertEquals("text", first[0].jsonObject["type"]?.jsonPrimitive?.content)

        // Last user message: text + image block
        val last = messages[1].jsonObject["content"]!!.jsonArray
        assertEquals(2, last.size)
        assertEquals("text", last[0].jsonObject["type"]?.jsonPrimitive?.content)

        val imageBlock = last[1].jsonObject
        assertEquals("image", imageBlock["type"]?.jsonPrimitive?.content)
        val source = imageBlock["source"]!!.jsonObject
        assertEquals("base64", source["type"]?.jsonPrimitive?.content)
        assertEquals("image/png", source["media_type"]?.jsonPrimitive?.content)
        assertEquals("abc123", source["data"]?.jsonPrimitive?.content)
    }

    @Test
    fun `url attachment appended as image block with url source`() {
        val request = LLMRequest(
            messages = listOf(Message.User("Describe this")),
            attachments = listOf(
                Attachment(AttachmentSource.Url("https://example.com/cat.png"), "image/png"),
            ),
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        val content = result["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(2, content.size)

        val imageBlock = content[1].jsonObject
        assertEquals("image", imageBlock["type"]?.jsonPrimitive?.content)
        val source = imageBlock["source"]!!.jsonObject
        assertEquals("url", source["type"]?.jsonPrimitive?.content)
        assertEquals("https://example.com/cat.png", source["url"]?.jsonPrimitive?.content)
    }

    @Test(expected = LLMException::class)
    fun `localUri attachment throws UNSUPPORTED_MODALITY`() {
        val request = LLMRequest(
            messages = listOf(Message.User("test")),
            attachments = listOf(
                Attachment(AttachmentSource.LocalUri("content://photo/1"), "image/jpeg"),
            ),
        )

        mapper.map(request, stream = true)
    }

    // --- v0.2.4: overrides ---

    @Test
    fun `overrides are merged at top level`() {
        val request = LLMRequest(
            messages = listOf(Message.User("test")),
            overrides = buildJsonObject {
                put("top_k", 40)
                put("custom_field", "value")
            },
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        assertEquals("40", result["top_k"]?.jsonPrimitive?.content)
        assertEquals("value", result["custom_field"]?.jsonPrimitive?.content)
    }

    @Test
    fun `overrides can override strong-typed fields`() {
        val request = LLMRequest(
            messages = listOf(Message.User("test")),
            temperature = 0.7f,
            overrides = buildJsonObject {
                put("temperature", 0.9)
            },
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        // overrides should win (0.9 instead of 0.7)
        assertEquals("0.9", result["temperature"]?.jsonPrimitive?.content)
    }

    // --- v0.3.0: topK (R3) ---

    @Test
    fun `topK omitted when null (default)`() {
        val request = LLMRequest(messages = listOf(Message.User("hi")))

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        assertNull("top_k should not be present by default", result["top_k"])
    }

    @Test
    fun `topK included as top-level field when specified (native Anthropic field)`() {
        val request = LLMRequest(
            messages = listOf(Message.User("hi")),
            topK = 40,
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        assertEquals("40", result["top_k"]?.jsonPrimitive?.content)
    }

    @Test
    fun `topK can be overridden by overrides escape hatch`() {
        val request = LLMRequest(
            messages = listOf(Message.User("hi")),
            topK = 40,
            overrides = buildJsonObject { put("top_k", 100) },
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        assertEquals("100", result["top_k"]?.jsonPrimitive?.content)
    }
}
