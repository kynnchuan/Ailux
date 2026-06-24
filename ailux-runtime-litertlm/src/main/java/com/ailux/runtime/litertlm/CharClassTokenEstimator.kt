package com.ailux.runtime.litertlm

/**
 * Language-aware token-count heuristic used as a fallback when no native
 * tokenizer is available (LiteRT-LM 0.13.x does not expose
 * `tokenize(text)`; `Conversation.getTokenCount()` returns the running
 * conversation total, not per-string length).
 *
 * Rationale: the historical implementation used `(len + 3) / 4`, a
 * ~4 chars/token rule that is roughly correct for English/Latin text but
 * underestimates CJK text by 2~3x (Chinese is closer to ~1.5 chars/token in
 * modern BPE / SentencePiece tokenizers). For a Chinese-first product this
 * caused upstream context-management to under-trim ("thinks there's still
 * budget left, but actually over"), and Usage fallback reported wildly low
 * input/output tokens.
 *
 * Estimator: split by CJK-ish vs. other Unicode ranges.
 *  - CJK chars     : ~1.5 chars/token  → ceil(2 * c / 3)
 *  - non-CJK chars : ~4 chars/token    → ceil(o / 4)
 *
 * Still pure heuristic, no tokenizer dependency. Replace with a native
 * tokenizer entry point as soon as LiteRT-LM exposes one.
 *
 * Lives in its own top-level file so unit tests can exercise it without
 * loading [LiteRTLMEngine] (and its native LiteRT-LM dependency, whose AAR
 * is compiled at JDK 21 / class-file 65 — beyond the JDK 17 the test runner
 * uses).
 */
internal object CharClassTokenEstimator {

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (c in text) {
            if (isCjkLike(c)) cjk++ else other++
        }
        // Ceil-divide each class individually to avoid bias near 0.
        val cjkTokens = (cjk * 2 + 2) / 3       // ceil(cjk * 2 / 3)
        val otherTokens = (other + 3) / 4       // ceil(other / 4)
        return cjkTokens + otherTokens
    }

    /**
     * Whether [c] belongs to a Unicode block where the typical
     * chars-per-token ratio is much lower than Latin text. Conservative
     * bucket — when in doubt we'd rather over-count than under-count (the
     * result feeds context-budget trimming; under-counting is the dangerous
     * direction).
     */
    private fun isCjkLike(c: Char): Boolean {
        val code = c.code
        return (code in 0x4E00..0x9FFF) ||      // CJK Unified Ideographs
               (code in 0x3400..0x4DBF) ||      // CJK Ext-A
               (code in 0x3000..0x303F) ||      // CJK Symbols & Punctuation (含中文全角标点)
               (code in 0x3040..0x309F) ||      // Hiragana
               (code in 0x30A0..0x30FF) ||      // Katakana
               (code in 0xAC00..0xD7AF) ||      // Hangul Syllables
               (code in 0xFF00..0xFFEF)         // Halfwidth & Fullwidth Forms
    }
}
