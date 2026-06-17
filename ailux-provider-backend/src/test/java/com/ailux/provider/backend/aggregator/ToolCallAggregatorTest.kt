package com.ailux.provider.backend.aggregator

import com.ailux.core.event.LLMEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ToolCallAggregatorTest {

    private lateinit var aggregator: ToolCallAggregator

    @Before
    fun setUp() {
        aggregator = ToolCallAggregator()
    }

    @Test
    fun `isNotEmpty returns false initially`() {
        assertFalse(aggregator.isNotEmpty())
    }

    @Test
    fun `single tool call with multiple argument deltas`() {
        aggregator.feed(LLMEvent.ToolCallDelta(index = 0, id = "call_01", name = "get_weather", argumentsDelta = ""))
        aggregator.feed(LLMEvent.ToolCallDelta(index = 0, id = null, name = null, argumentsDelta = "{\"city\":"))
        aggregator.feed(LLMEvent.ToolCallDelta(index = 0, id = null, name = null, argumentsDelta = "\"Beijing\"}"))

        assertTrue(aggregator.isNotEmpty())

        val result = aggregator.build()
        assertEquals(1, result.size)
        assertEquals("call_01", result[0].id)
        assertEquals("get_weather", result[0].name)
        assertEquals("{\"city\":\"Beijing\"}", result[0].arguments)
    }

    @Test
    fun `multiple parallel tool calls`() {
        // First tool call
        aggregator.feed(LLMEvent.ToolCallDelta(index = 0, id = "call_01", name = "get_weather", argumentsDelta = ""))
        aggregator.feed(LLMEvent.ToolCallDelta(index = 0, id = null, name = null, argumentsDelta = "{\"city\":\"BJ\"}"))

        // Second tool call
        aggregator.feed(LLMEvent.ToolCallDelta(index = 1, id = "call_02", name = "get_time", argumentsDelta = ""))
        aggregator.feed(LLMEvent.ToolCallDelta(index = 1, id = null, name = null, argumentsDelta = "{\"tz\":\"UTC+8\"}"))

        val result = aggregator.build()
        assertEquals(2, result.size)

        // Sorted by index
        assertEquals("call_01", result[0].id)
        assertEquals("get_weather", result[0].name)
        assertEquals("{\"city\":\"BJ\"}", result[0].arguments)

        assertEquals("call_02", result[1].id)
        assertEquals("get_time", result[1].name)
        assertEquals("{\"tz\":\"UTC+8\"}", result[1].arguments)
    }

    @Test
    fun `empty arguments produces null`() {
        aggregator.feed(LLMEvent.ToolCallDelta(index = 0, id = "call_01", name = "no_args_tool", argumentsDelta = ""))

        val result = aggregator.build()
        assertEquals(1, result.size)
        assertEquals("call_01", result[0].id)
        assertEquals("no_args_tool", result[0].name)
        // Empty arguments string → null
        assertEquals(null, result[0].arguments)
    }

    @Test
    fun `reset clears all state`() {
        aggregator.feed(LLMEvent.ToolCallDelta(index = 0, id = "call_01", name = "test", argumentsDelta = "{}"))
        assertTrue(aggregator.isNotEmpty())

        aggregator.reset()
        assertFalse(aggregator.isNotEmpty())

        val result = aggregator.build()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `id and name are taken from first non-null occurrence`() {
        // First delta has id and name
        aggregator.feed(LLMEvent.ToolCallDelta(index = 0, id = "call_99", name = "my_func", argumentsDelta = "{"))
        // Subsequent deltas have null id/name (only arguments)
        aggregator.feed(LLMEvent.ToolCallDelta(index = 0, id = null, name = null, argumentsDelta = "}"))

        val result = aggregator.build()
        assertEquals("call_99", result[0].id)
        assertEquals("my_func", result[0].name)
        assertEquals("{}", result[0].arguments)
    }
}
