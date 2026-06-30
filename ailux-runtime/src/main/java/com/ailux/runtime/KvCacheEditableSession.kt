package com.ailux.runtime

/**
 * Tier-1 KV-cache editing seam (ADR-0010).
 *
 * Stateful sessions whose engine reports
 * [EngineCapabilities.supportsKvCacheEdit] = `true` (currently only llama.cpp)
 * MAY additionally implement this interface, exposing **fine-grained, in-place**
 * eviction of a logical token range from the middle of the native KV-cache —
 * without rebuilding the whole cache.
 *
 * ## Responsibility split
 *
 * This is the *execution* half of ADR-0010's "Ailux decides what to keep, the
 * engine executes how to delete". The Provider layer
 * ([com.ailux.provider.local.LocalEngineSessionAdapter]) makes the *semantic*
 * decision (which messages survive a trim) and translates the dropped messages
 * into a contiguous logical token range; the engine then physically removes that
 * range and shifts the surviving suffix down to close the gap, so subsequent
 * prefill positions stay contiguous.
 *
 * A session that does **not** implement this interface (every Tier-2 engine, and
 * llama.cpp before this primitive is wired) forces the adapter onto the
 * close + replay rebuild path instead.
 */
interface KvCacheEditableSession {

    /**
     * Remove [tokenCount] logical tokens starting at logical position
     * [startToken] from this session's KV-cache, then shift every surviving
     * token after the removed range left by [tokenCount] so positions remain
     * contiguous (llama.cpp: `llama_kv_cache_seq_rm` followed by
     * `llama_kv_cache_seq_add` with a negative delta).
     *
     * Positions are **logical token positions** in the order the tokens were
     * ingested (0 = the very first token of the system prompt). The caller
     * guarantees `startToken >= 0`, `tokenCount > 0`, and that the range lies
     * within the currently ingested span; implementations MAY clamp defensively.
     *
     * After a successful edit, [EngineSession.ingestedTokens] MUST reflect the
     * new, smaller span (old value minus [tokenCount]).
     *
     * @return `true` if the edit was applied, `false` if the engine could not
     *   perform it (the caller then falls back to close + replay).
     */
    fun evictTokenRange(startToken: Int, tokenCount: Int): Boolean
}
