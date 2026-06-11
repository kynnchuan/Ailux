package com.ailux.provider.backend.examples

import com.ailux.core.message.Message
import com.ailux.core.request.LLMRequest
import com.ailux.provider.backend.mapper.RequestMapper
import com.ailux.provider.backend.mapper.applyOverrides
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **v0.2.5 G-2 extension-point example — `RequestMapper`.**
 *
 * Demonstrates how to plug a custom [RequestMapper] into [BackendProxyConfig]
 * when the backend protocol is **structurally different** from OpenAI /
 * Anthropic. The fictional "AcmeChat" backend used here differs in three ways
 * that cannot be papered over with `LLMRequest.overrides`:
 *
 *  1. Top-level field renames — `messages` → `chat_history`, `role` → `speaker`,
 *     `content` → `utterance`, `temperature` → `creativity`, `max_tokens` →
 *     `cap`.
 *  2. A required-but-renamed envelope: every request must carry
 *     `protocol_version: "acme.v1"`.
 *  3. System messages are hoisted out of the array into a top-level
 *     `directives` string (à la Anthropic), instead of being kept inline.
 *
 * Because these are **structural** changes, `overrides` is the wrong tool.
 * Writing a 40-line mapper is the right one.
 *
 * The test asserts the produced JSON exactly so the file doubles as living
 * documentation: change the mapper and the test tells you what shifted.
 *
 * @see RequestMapper
 * @see applyOverrides
 */
class AcmeRequestMapperExampleTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The example mapper. Kept inline (not promoted to `src/main`) on purpose —
     * this is *documentation*, not a production preset.
     */
    private val acmeMapper = RequestMapper { request, stream ->
        buildJsonObject {
            put("protocol_version", "acme.v1")
            put("model_name", request.model.ifEmpty { "acme-chat-1" })
            put("creativity", request.temperature)
            request.maxTokens?.let { put("cap", it) }
            put("streaming", stream)

            // Hoist system messages into a top-level directives string.
            val systems = request.messages.filterIsInstance<Message.System>()
            if (systems.isNotEmpty()) {
                put("directives", systems.joinToString("\n") { it.content })
            }

            // Remaining messages → chat_history with Acme-shaped entries.
            val nonSystem = request.messages.filter { it !is Message.System }
            put("chat_history", buildJsonArray {
                nonSystem.forEach { msg ->
                    add(buildJsonObject {
                        when (msg) {
                            is Message.User -> {
                                put("speaker", "human")
                                put("utterance", msg.content)
                            }
                            is Message.Assistant -> {
                                put("speaker", "assistant")
                                put("utterance", msg.content ?: "")
                            }
                            is Message.Tool -> {
                                put("speaker", "tool")
                                put("utterance", msg.content)
                                put("tool_call_id", msg.toolCallId)
                            }
                            is Message.System -> Unit // already hoisted
                        }
                    })
                }
            })

            // Escape hatch: overrides always win, so users can still bolt on
            // Acme-specific knobs (e.g. `traceId`) without a code change.
            applyOverrides(request.overrides)
        }.toString()
    }

    @Test
    fun `maps system + user into directives + chat_history with Acme field names`() {
        val request = LLMRequest(
            model = "acme-chat-1",
            messages = listOf(
                Message.System("You are concise."),
                Message.User("Hi there"),
            ),
            temperature = 0.4,
            maxTokens = 256,
        )

        val body = json.parseToJsonElement(acmeMapper.map(request, stream = true)).jsonObject

        assertEquals("acme.v1", body["protocol_version"]!!.jsonPrimitive.content)
        assertEquals("acme-chat-1", body["model_name"]!!.jsonPrimitive.content)
        assertEquals(0.4, body["creativity"]!!.jsonPrimitive.content.toDouble(), 0.0001)
        assertEquals(256, body["cap"]!!.jsonPrimitive.content.toInt())
        assertEquals(true, body["streaming"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("You are concise.", body["directives"]!!.jsonPrimitive.content)

        val history = body["chat_history"]!!.jsonArray
        assertEquals(1, history.size)
        val first = history[0].jsonObject
        assertEquals("human", first["speaker"]!!.jsonPrimitive.content)
        assertEquals("Hi there", first["utterance"]!!.jsonPrimitive.content)
    }

    @Test
    fun `overrides win against strong-typed Acme fields (escape-hatch contract)`() {
        val request = LLMRequest(
            messages = listOf(Message.User("ping")),
            temperature = 0.4,
            overrides = buildJsonObject {
                // Override a strong-typed field
                put("creativity", 0.99)
                // Add a brand-new Acme-only field
                putJsonObject("trace") {
                    put("id", "trace-abc")
                }
            },
        )

        val body = json.parseToJsonElement(acmeMapper.map(request, stream = false)).jsonObject

        // Overrides override (top-level merge, last write wins).
        assertEquals(0.99, body["creativity"]!!.jsonPrimitive.content.toDouble(), 0.0001)
        assertEquals("trace-abc", body["trace"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        // Stable identity preserved
        assertEquals("acme.v1", body["protocol_version"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no system message means no directives field at all`() {
        val request = LLMRequest(
            messages = listOf(Message.User("ping")),
        )

        val body = json.parseToJsonElement(acmeMapper.map(request, stream = false)).jsonObject
        assertTrue("directives must be absent when no system message", !body.containsKey("directives"))
    }
}
