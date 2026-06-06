package com.ailux.provider.mock

import com.ailux.core.model.LLMEvent
import com.ailux.core.model.LLMRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockProviderTest {

    @Test
    fun generate_returnsMatchingRuleReply() = runBlocking {
        val provider = MockProvider()

        val response = provider.generate(LLMRequest(prompt = "what's the weather like today"))

        assertTrue(response.text.contains("weather"))
        assertTrue(response.text.contains("Temperature"))
    }

    @Test
    fun generate_returnsFallbackReplyWhenNoKeywordMatches() = runBlocking {
        val provider = MockProvider()

        val response = provider.generate(LLMRequest(prompt = "hello"))

        assertEquals("Hi there! Happy to help. What can I do for you?", response.text)
    }

    @Test
    fun streamGenerate_emitsTokenUsageAndDoneInOrder() = runBlocking {
        val provider = MockProvider(
            rules = listOf(
                MockRule(keyword = "mock", reply = "abc")
            )
        )

        val events = provider.streamGenerate(LLMRequest(prompt = "mock request")).toList()

        assertEquals(5, events.size)
        assertEquals("a", (events[0] as LLMEvent.Token).text)
        assertEquals("b", (events[1] as LLMEvent.Token).text)
        assertEquals("c", (events[2] as LLMEvent.Token).text)

        assertTrue(events[3] is LLMEvent.Usage)
        val usage = (events[3] as LLMEvent.Usage).info
        assertEquals("mock request".length, usage.inputTokens)
        assertEquals("abc".length, usage.outputTokens)
        assertTrue(usage.estimated)

        assertEquals(LLMEvent.Done, events[4])
    }

    @Test
    fun streamGenerate_emitsReasoningBeforeTokensWhenRuleHasReasoning() = runBlocking {
        val provider = MockProvider(
            rules = listOf(
                MockRule(keyword = "mock", reply = "ok", reasoning = "why")
            )
        )

        val events = provider.streamGenerate(LLMRequest(prompt = "mock request")).toList()

        assertEquals(7, events.size)
        assertEquals("w", (events[0] as LLMEvent.Reasoning).text)
        assertEquals("h", (events[1] as LLMEvent.Reasoning).text)
        assertEquals("y", (events[2] as LLMEvent.Reasoning).text)
        assertEquals("o", (events[3] as LLMEvent.Token).text)
        assertEquals("k", (events[4] as LLMEvent.Token).text)
        assertTrue(events[5] is LLMEvent.Usage)
        assertEquals(LLMEvent.Done, events[6])
    }

    @Test
    fun streamGenerate_usesFallbackRuleWhenNoKeywordMatches() = runBlocking {
        val provider = MockProvider(
            rules = listOf(
                MockRule(keyword = "weather", reply = "weather reply"),
                MockRule(keyword = "", reply = "fallback reply")
            )
        )

        val tokens = provider.streamGenerate(LLMRequest(prompt = "unknown"))
            .toList()
            .filterIsInstance<LLMEvent.Token>()
            .joinToString(separator = "") { it.text }

        assertEquals("fallback reply", tokens)
    }
}
