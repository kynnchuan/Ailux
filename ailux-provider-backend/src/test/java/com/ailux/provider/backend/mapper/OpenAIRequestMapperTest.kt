package com.ailux.provider.backend.mapper

import com.ailux.core.error.LLMException
import com.ailux.core.message.Message
import com.ailux.core.request.Attachment
import com.ailux.core.request.AttachmentSource
import com.ailux.core.request.LLMRequest
import com.ailux.core.tool.ToolCall
import com.ailux.core.tool.ToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class OpenAIRequestMapperTest {

    private lateinit var mapper: OpenAIRequestMapper
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        mapper = OpenAIRequestMapper()
    }

    // --- Basic message mapping ---

    @Test
    fun `basic user message is plain string content`() {
        val request = LLMRequest(
            messages = listOf(Message.User("Hello")),
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        val messages = result["messages"]!!.jsonArray
        assertEquals(1, messages.size)

        val msg = messages[0].jsonObject
        assertEquals("user", msg["role"]?.jsonPrimitive?.content)
        assertEquals("Hello", msg["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `system message mapped with role system`() {
        val request = LLMRequest(
            messages = listOf(
                Message.System("Be helpful"),
                Message.User("Hi"),
            ),
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        val messages = result["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("Be helpful", messages[0].jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `assistant with tool_calls serialized correctly`() {
        val request = LLMRequest(
            messages = listOf(
                Message.User("Weather?"),
                Message.Assistant(
                    content = "Let me check.",
                    toolCalls = listOf(
                        ToolCall("call_1", "get_weather", "{\"city\":\"Beijing\"}")
                    ),
                ),
            ),
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        val assistantMsg = result["messages"]!!.jsonArray[1].jsonObject
        assertEquals("assistant", assistantMsg["role"]?.jsonPrimitive?.content)
        assertEquals("Let me check.", assistantMsg["content"]?.jsonPrimitive?.content)

        val toolCalls = assistantMsg["tool_calls"]!!.jsonArray
        assertEquals(1, toolCalls.size)
        val call = toolCalls[0].jsonObject
        assertEquals("call_1", call["id"]?.jsonPrimitive?.content)
        assertEquals("function", call["type"]?.jsonPrimitive?.content)
        assertEquals("get_weather", call["function"]!!.jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("{\"city\":\"Beijing\"}", call["function"]!!.jsonObject["arguments"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tool message mapped with role tool`() {
        val request = LLMRequest(
            messages = listOf(
                Message.User("Weather?"),
                Message.Assistant(toolCalls = listOf(ToolCall("c1", "f", "{}"))),
                Message.Tool(toolCallId = "c1", content = "22C"),
            ),
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        val toolMsg = result["messages"]!!.jsonArray[2].jsonObject
        assertEquals("tool", toolMsg["role"]?.jsonPrimitive?.content)
        assertEquals("c1", toolMsg["tool_call_id"]?.jsonPrimitive?.content)
        assertEquals("22C", toolMsg["content"]?.jsonPrimitive?.content)
    }

    // --- Tools ---

    @Test
    fun `tools serialized in OpenAI format`() {
        val tool = ToolDefinition(
            name = "search",
            description = "Search the web",
            arguments = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") { put("type", "string") }
                }
            },
        )

        val request = LLMRequest(
            messages = listOf(Message.User("test")),
            tools = listOf(tool),
            toolChoice = "auto",
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        val tools = result["tools"]!!.jsonArray
        assertEquals(1, tools.size)

        val t = tools[0].jsonObject
        assertEquals("function", t["type"]?.jsonPrimitive?.content)
        val fn = t["function"]!!.jsonObject
        assertEquals("search", fn["name"]?.jsonPrimitive?.content)
        assertEquals("Search the web", fn["description"]?.jsonPrimitive?.content)
        assertNotNull(fn["parameters"])

        assertEquals("auto", result["tool_choice"]?.jsonPrimitive?.content)
    }

    @Test
    fun `no tools means no tools field in output`() {
        val request = LLMRequest(
            messages = listOf(Message.User("test")),
            tools = emptyList(),
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        assertNull(result["tools"])
        assertNull(result["tool_choice"])
    }

    // --- v0.2.4: stop sequences ---

    @Test
    fun `stop sequences mapped to stop array`() {
        val request = LLMRequest(
            messages = listOf(Message.User("test")),
            stop = listOf("\n\n", "###"),
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        val stop = result["stop"]!!.jsonArray
        assertEquals(2, stop.size)
        assertEquals("\n\n", stop[0].jsonPrimitive.content)
        assertEquals("###", stop[1].jsonPrimitive.content)
    }

    @Test
    fun `empty stop list means no stop field`() {
        val request = LLMRequest(
            messages = listOf(Message.User("test")),
            stop = emptyList(),
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        assertNull(result["stop"])
    }

    // --- v0.2.4: multimodal attachments ---

    @Test
    fun `url attachment converts last user message to content-parts array`() {
        val request = LLMRequest(
            messages = listOf(
                Message.User("What is this?"),
            ),
            attachments = listOf(
                Attachment(AttachmentSource.Url("https://example.com/img.png"), "image/png"),
            ),
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        val content = result["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(2, content.size)

        // Text part
        assertEquals("text", content[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("What is this?", content[0].jsonObject["text"]?.jsonPrimitive?.content)

        // Image part
        val imagePart = content[1].jsonObject
        assertEquals("image_url", imagePart["type"]?.jsonPrimitive?.content)
        assertEquals(
            "https://example.com/img.png",
            imagePart["image_url"]!!.jsonObject["url"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `base64 attachment uses data URI format`() {
        val request = LLMRequest(
            messages = listOf(Message.User("Describe")),
            attachments = listOf(
                Attachment(AttachmentSource.Base64("iVBORw0KGgo="), "image/png"),
            ),
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        val content = result["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        val url = content[1].jsonObject["image_url"]!!.jsonObject["url"]?.jsonPrimitive?.content
        assertEquals("data:image/png;base64,iVBORw0KGgo=", url)
    }

    @Test
    fun `attachments only affect the last user message`() {
        val request = LLMRequest(
            messages = listOf(
                Message.User("First"),
                Message.User("Second with image"),
            ),
            attachments = listOf(
                Attachment(AttachmentSource.Url("https://x.com/a.jpg"), "image/jpeg"),
            ),
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        val messages = result["messages"]!!.jsonArray

        // First message: plain string content
        assertEquals("First", messages[0].jsonObject["content"]?.jsonPrimitive?.content)

        // Second message: content-parts array
        val content = messages[1].jsonObject["content"]!!.jsonArray
        assertEquals(2, content.size)
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

    @Test
    fun `no attachments keeps plain string content`() {
        val request = LLMRequest(
            messages = listOf(Message.User("plain text")),
            attachments = emptyList(),
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        val msg = result["messages"]!!.jsonArray[0].jsonObject
        // Should be a plain string, not an array
        assertEquals("plain text", msg["content"]?.jsonPrimitive?.content)
    }

    // --- v0.2.4: overrides ---

    @Test
    fun `overrides merged at top level`() {
        val request = LLMRequest(
            messages = listOf(Message.User("test")),
            overrides = buildJsonObject {
                put("seed", 42)
                putJsonObject("response_format") { put("type", "json_object") }
            },
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        assertEquals("42", result["seed"]?.jsonPrimitive?.content)
        assertEquals("json_object", result["response_format"]!!.jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `overrides can override strong-typed fields`() {
        val request = LLMRequest(
            messages = listOf(Message.User("test")),
            temperature = 0.7f,
            overrides = buildJsonObject {
                put("temperature", 1)
            },
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        // overrides win: integer 1 instead of float 0.7
        assertEquals("1", result["temperature"]?.jsonPrimitive?.content)
    }

    // --- Standard fields ---

    @Test
    fun `model defaults to default when empty`() {
        val request = LLMRequest(
            messages = listOf(Message.User("test")),
            model = "",
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        assertEquals("default", result["model"]?.jsonPrimitive?.content)
    }

    @Test
    fun `maxTokens omitted when null`() {
        val request = LLMRequest(
            messages = listOf(Message.User("test")),
            maxTokens = null,
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        assertNull(result["max_tokens"])
    }

    @Test
    fun `maxTokens included when specified`() {
        val request = LLMRequest(
            messages = listOf(Message.User("test")),
            maxTokens = 1024,
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        assertEquals("1024", result["max_tokens"]?.jsonPrimitive?.content)
    }

    // --- v0.3.0: topK (R3) ---

    @Test
    fun `topK omitted when null (default)`() {
        val request = LLMRequest(messages = listOf(Message.User("hi")))

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        assertNull("top_k should not be present by default", result["top_k"])
    }

    @Test
    fun `topK included as top-level field when specified`() {
        val request = LLMRequest(
            messages = listOf(Message.User("hi")),
            topK = 40,
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        assertEquals("40", result["top_k"]?.jsonPrimitive?.content)
    }

    @Test
    fun `topK can be overridden by overrides escape hatch`() {
        // overrides win over strong-typed topK — same precedence as other fields.
        val request = LLMRequest(
            messages = listOf(Message.User("hi")),
            topK = 40,
            overrides = buildJsonObject { put("top_k", 100) },
        )

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        assertEquals("100", result["top_k"]?.jsonPrimitive?.content)
    }

    @Test
    fun `stream field is set correctly`() {
        val request = LLMRequest(messages = listOf(Message.User("hi")))

        val streamResult = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        assertEquals("true", streamResult["stream"]?.jsonPrimitive?.content)

        val nonStreamResult = json.parseToJsonElement(mapper.map(request, stream = false)).jsonObject
        assertEquals("false", nonStreamResult["stream"]?.jsonPrimitive?.content)
    }

    // --- v0.2.6: stream_options.include_usage ---

    @Test
    fun `stream_options include_usage injected when stream=true and includeUsageInStream=true`() {
        val request = LLMRequest(messages = listOf(Message.User("hi")))

        val result = json.parseToJsonElement(mapper.map(request, stream = true)).jsonObject
        val streamOptions = result["stream_options"]?.jsonObject
        assertNotNull("stream_options should be present", streamOptions)
        assertEquals("true", streamOptions!!["include_usage"]?.jsonPrimitive?.content)
    }

    @Test
    fun `stream_options not present when stream=false`() {
        val request = LLMRequest(messages = listOf(Message.User("hi")))

        val result = json.parseToJsonElement(mapper.map(request, stream = false)).jsonObject
        assertNull("stream_options should not be present for non-streaming", result["stream_options"])
    }

    @Test
    fun `stream_options not present when includeUsageInStream=false`() {
        val disabledMapper = OpenAIRequestMapper(includeUsageInStream = false)
        val request = LLMRequest(messages = listOf(Message.User("hi")))

        val result = json.parseToJsonElement(disabledMapper.map(request, stream = true)).jsonObject
        assertNull("stream_options should not be present when disabled", result["stream_options"])
    }
}
