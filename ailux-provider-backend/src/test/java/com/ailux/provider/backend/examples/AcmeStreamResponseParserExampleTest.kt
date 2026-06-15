package com.ailux.provider.backend.examples

import com.ailux.core.event.FinishReason
import com.ailux.core.event.LLMEvent
import com.ailux.core.response.UsageInfo
import com.ailux.provider.backend.parser.stream.StreamResponseParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **v0.2.5 G-2 extension-point example — `StreamResponseParser`.**
 *
 * Demonstrates how to plug a custom [StreamResponseParser] into
 * [BackendProxyConfig] when the backend's SSE shape diverges enough from
 * OpenAI / Anthropic that the built-in parsers cannot decode it.
 *
 * The fictional "AcmeChat" backend used here streams events like:
 *
 * ```text
 * event: delta
 * data: {"chunk":"Hello"}
 *
 * event: delta
 * data: {"chunk":" world"}
 *
 * event: metrics
 * data: {"in":12,"out":2}
 *
 * event: finish
 * data: {"reason":"natural"}
 * ```
 *
 * Three structural differences from OpenAI:
 *
 *  1. `event:` names are vendor-specific (`delta` / `metrics` / `finish`),
 *     so we cannot reuse `OpenAIStreamResponseParser`.
 *  2. The terminal frame is a separate `finish` event with a vendor-named
 *     `reason` field — must be translated to [FinishReason].
 *  3. Usage arrives as its own `metrics` event, not piggy-backed on the last
 *     content chunk.
 *
 * This parser is **stateless** (no FC), which is the easy case. For an FC
 * example see `OpenAIStreamResponseParser` in production code.
 *
 * @see StreamResponseParser
 */
class AcmeStreamResponseParserExampleTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val acmeParser = StreamResponseParser { eventType, data ->
        when (eventType) {
            "delta" -> {
                val chunk = json.parseToJsonElement(data).jsonObject["chunk"]?.jsonPrimitive?.content
                if (chunk.isNullOrEmpty()) emptyList() else listOf(LLMEvent.Token(chunk))
            }

            "metrics" -> {
                val obj = json.parseToJsonElement(data).jsonObject
                val input = obj["in"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val output = obj["out"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                listOf(LLMEvent.Usage(UsageInfo(inputTokens = input, outputTokens = output)))
            }

            "finish" -> {
                val reason = json.parseToJsonElement(data).jsonObject["reason"]?.jsonPrimitive?.content
                listOf(LLMEvent.Done(mapAcmeFinishReason(reason)))
            }

            // Important: ignore unknown events rather than throw — a backend
            // version bump must be backward-compatible for old clients.
            else -> emptyList()
        }
    }

    private fun mapAcmeFinishReason(raw: String?): FinishReason = when (raw) {
        "natural", "end_of_turn" -> FinishReason.COMPLETE
        "max_length" -> FinishReason.LENGTH
        "filtered" -> FinishReason.CONTENT_FILTER
        "tool_invoke" -> FinishReason.TOOL_CALL
        else -> FinishReason.COMPLETE // tolerant default
    }

    @Test
    fun `delta events become Token events`() {
        val events = acmeParser.parse("delta", """{"chunk":"Hello"}""")
        assertEquals(1, events.size)
        assertEquals(LLMEvent.Token("Hello"), events[0])
    }

    @Test
    fun `empty chunk is silently skipped instead of emitting empty Token`() {
        val events = acmeParser.parse("delta", """{"chunk":""}""")
        assertTrue("empty chunk must not emit any event, got: $events", events.isEmpty())
    }

    @Test
    fun `metrics event emits Usage with parsed counts`() {
        val events = acmeParser.parse("metrics", """{"in":12,"out":34}""")
        assertEquals(1, events.size)
        val usage = (events[0] as LLMEvent.Usage).info
        assertEquals(12, usage.inputTokens)
        assertEquals(34, usage.outputTokens)
        assertEquals(false, usage.estimated) // server-reported, not estimated
    }

    @Test
    fun `finish event maps natural to COMPLETE`() {
        val events = acmeParser.parse("finish", """{"reason":"natural"}""")
        assertEquals(1, events.size)
        assertEquals(FinishReason.COMPLETE, (events[0] as LLMEvent.Done).finishReason)
    }

    @Test
    fun `finish event maps max_length to LENGTH`() {
        val events = acmeParser.parse("finish", """{"reason":"max_length"}""")
        assertEquals(FinishReason.LENGTH, (events[0] as LLMEvent.Done).finishReason)
    }

    @Test
    fun `unknown reason falls back to COMPLETE rather than throwing`() {
        val events = acmeParser.parse("finish", """{"reason":"who_knows"}""")
        assertEquals(FinishReason.COMPLETE, (events[0] as LLMEvent.Done).finishReason)
    }

    @Test
    fun `unknown event type is ignored not thrown (forward compatibility)`() {
        val events = acmeParser.parse("future_event_v3", """{"any":"thing"}""")
        assertTrue("must not throw, must not emit", events.isEmpty())
    }
}
