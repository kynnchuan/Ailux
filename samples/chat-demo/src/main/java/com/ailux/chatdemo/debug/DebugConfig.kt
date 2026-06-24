package com.ailux.chatdemo.debug

import com.ailux.chatdemo.ProviderMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Runtime-mutable debug configuration for the chat demo.
 *
 * This is the single source of truth read by [ChatViewModel.send] on each request.
 * The Debug Panel UI modifies this via a StateFlow; no client rebuild is needed for
 * request-level params (model, provider, context_mode, overrides, stop, attachments).
 *
 * Client-level params (providerMode, baseUrl, stallDetection, concurrencyPolicy)
 * require a client rebuild — the Panel shows a "Rebuild required" badge for these.
 *
 * Design per v0.2.2 §14.4.5: "avoid changing code and recompiling for each scenario".
 */
data class DebugConfig(
    // ─── Request-level (instant, no rebuild) ───

    /** LLM model identifier sent in LLMRequest.model. */
    val model: String = "deepseek-v4-flash",

    /** Backend provider routing key (sent via overrides.provider). */
    val provider: String = "deepseek",

    /** Context management mode: "server" or "client". */
    val contextMode: String = "client",

    /** Preset account token (free/pro/admin). */
    val presetAccount: PresetAccount = PresetAccount.PRO,

    /** Current session ID. Change to start a fresh conversation. */
    val sessionId: String = java.util.UUID.randomUUID().toString(),

    /** Custom stop sequences (empty = none). */
    val stopSequences: List<String> = emptyList(),

    /** Custom overrides JSON string (parsed to JsonObject on each request). */
    val customOverridesJson: String = "",

    /** Whether to attach a test image (for multimodal demo). */
    val attachTestImage: Boolean = false,

    /** Use streaming (SSE) or non-streaming (single JSON response) mode.
     *  When false, calls Session.generate() instead of Session.streamGenerateAsTask(). */
    val useStreaming: Boolean = true,

    // ─── Client-level (require rebuild) ───

    /** Provider mode (Mock/Backend). Requires client rebuild. */
    val providerMode: ProviderMode = ProviderMode.MOCK,

    /** Enable stall detection. Requires client rebuild. */
    val stallDetectionEnabled: Boolean = true,

    /** Stall detection idle threshold in ms. Requires client rebuild. */
    val stallIdleThresholdMs: Long = 15_000L,

    /** Concurrency policy name. Requires client rebuild. */
    val concurrencyPolicy: String = "CANCEL_PREVIOUS",
) {
    /**
     * Builds the overrides JsonObject to inject into LLMRequest.
     * Merges provider routing, context_mode, session_id, and any custom JSON.
     */
    fun buildOverrides(): JsonObject = buildJsonObject {
        put("provider", provider)
        put("context_mode", contextMode)
        put("session_id", sessionId)

        // Merge user-provided custom overrides
        if (customOverridesJson.isNotBlank()) {
            try {
                val custom = kotlinx.serialization.json.Json.parseToJsonElement(customOverridesJson)
                if (custom is JsonObject) {
                    custom.forEach { (key, value) -> put(key, value) }
                }
            } catch (_: Exception) {
                // Silently ignore malformed JSON — the Panel shows a parse error badge
            }
        }
    }
}

/**
 * Preset demo accounts with different quota levels.
 * In a real backend, these map to different user tokens with different rate limits.
 * Display labels are resolved at runtime via [com.ailux.chatdemo.Strings] for i18n support.
 */
enum class PresetAccount(val label: String, val token: String) {
    FREE("Free tier (5 req/min)", "token-free-001"),
    PRO("Pro tier (100 req/min)", "token-pro-001"),
    ADMIN("Admin (unlimited)", "token-admin-001"),
}

