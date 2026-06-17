package com.ailux.api.context

import com.ailux.core.config.ModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelRegistryTest {

    // ── Exact match ──

    @Test
    fun `exact match for gpt-4o`() {
        assertEquals(128_000, ModelRegistry.getContextWindow("gpt-4o"))
    }

    @Test
    fun `exact match for claude-opus-4`() {
        assertEquals(200_000, ModelRegistry.getContextWindow("claude-opus-4"))
    }

    @Test
    fun `exact match for deepseek-v4-pro`() {
        assertEquals(1_000_000, ModelRegistry.getContextWindow("deepseek-v4-pro"))
    }

    @Test
    fun `exact match for gemini-2_5-pro`() {
        assertEquals(1_048_576, ModelRegistry.getContextWindow("gemini-2.5-pro"))
    }

    @Test
    fun `exact match for gpt-4_1`() {
        assertEquals(1_047_576, ModelRegistry.getContextWindow("gpt-4.1"))
    }

    @Test
    fun `exact match for o1-mini`() {
        assertEquals(128_000, ModelRegistry.getContextWindow("o1-mini"))
    }

    @Test
    fun `exact match for gpt-5`() {
        assertEquals(400_000, ModelRegistry.getContextWindow("gpt-5"))
    }

    // ── Prefix match ──

    @Test
    fun `prefix match for gpt-4o with date suffix`() {
        assertEquals(128_000, ModelRegistry.getContextWindow("gpt-4o-2024-05-13"))
    }

    @Test
    fun `prefix match for gpt-4_1 with date suffix`() {
        assertEquals(1_047_576, ModelRegistry.getContextWindow("gpt-4.1-2025-04-14"))
    }

    @Test
    fun `prefix match for claude-3_5-sonnet with version suffix`() {
        assertEquals(200_000, ModelRegistry.getContextWindow("claude-3.5-sonnet-20241022"))
    }

    @Test
    fun `longest prefix wins - gpt-4o-mini vs gpt-4o`() {
        // "gpt-4o-mini-2024-07-18" should match "gpt-4o-mini" (longer prefix) not "gpt-4o"
        assertEquals(128_000, ModelRegistry.getContextWindow("gpt-4o-mini-2024-07-18"))
    }

    @Test
    fun `longest prefix wins - gpt-4_1-mini vs gpt-4_1`() {
        // Both "gpt-4.1" and "gpt-4.1-mini" are keys, "gpt-4.1-mini-xxx" should match the longer
        assertEquals(1_047_576, ModelRegistry.getContextWindow("gpt-4.1-mini-2025-04-14"))
    }

    // ── No match ──

    @Test
    fun `unknown model returns null`() {
        assertNull(ModelRegistry.getContextWindow("llama-3-70b"))
    }

    @Test
    fun `empty string returns null`() {
        assertNull(ModelRegistry.getContextWindow(""))
    }

    @Test
    fun `partial name that is not a prefix of any key returns null`() {
        assertNull(ModelRegistry.getContextWindow("xyz-model"))
    }

    // ── resolveContextWindow priority ──

    @Test
    fun `explicit contextWindowSize takes highest priority`() {
        val config = ModelConfig(
            name = "gpt-4o",  // Registry says 128K
            contextWindowSize = 64_000  // But user explicitly says 64K
        )
        assertEquals(64_000, resolveContextWindow(config))
    }

    @Test
    fun `model registry lookup when no explicit size`() {
        val config = ModelConfig(name = "gpt-4o")
        assertEquals(128_000, resolveContextWindow(config))
    }

    @Test
    fun `fallback to 128K when model not found and no explicit size`() {
        val config = ModelConfig(name = "unknown-model-xyz")
        assertEquals(128_000, resolveContextWindow(config))
    }

    @Test
    fun `null modelConfig falls back to 128K`() {
        assertEquals(128_000, resolveContextWindow(null))
    }

    @Test
    fun `reserveForReply does not affect resolveContextWindow`() {
        // resolveContextWindow only resolves the window, not the budget
        val config = ModelConfig(name = "gpt-4o", reserveForReply = 8192)
        assertEquals(128_000, resolveContextWindow(config))
    }
}
