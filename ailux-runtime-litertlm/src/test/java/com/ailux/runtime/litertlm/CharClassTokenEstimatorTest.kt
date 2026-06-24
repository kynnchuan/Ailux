package com.ailux.runtime.litertlm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CharClassTokenEstimator.estimate] — the language-aware
 * char-class heuristic that replaced the old `(len + 3) / 4` rule used by
 * [LiteRTLMEngine.sizeInTokens].
 *
 * The historical Chinese-under-count failure mode is captured explicitly:
 * 1000 Chinese chars used to estimate ~250 tokens (3x low). The new
 * estimator returns ~667, well within the actual ~700-900 tokenizer range.
 *
 * Lives in this file rather than `LiteRTLMEngineEstimateTokensTest` so we
 * don't have to load [LiteRTLMEngine] (its native AAR is compiled at JDK 21
 * / class-file 65, beyond the JDK 17 the test runner uses; loading the
 * class would throw UnsupportedClassVersionError).
 */
class CharClassTokenEstimatorTest {

    @Test
    fun emptyString_returnsZero() {
        assertEquals(0, CharClassTokenEstimator.estimate(""))
    }

    @Test
    fun pureEnglish_uses4CharsPerToken() {
        // 1000 ASCII chars → ceil(1000 / 4) = 250.
        val text = "a".repeat(1000)
        assertEquals(250, CharClassTokenEstimator.estimate(text))
    }

    @Test
    fun pureChinese_usesRoughly1_5CharsPerToken() {
        // 1000 CJK chars → ceil(2 * 1000 / 3) = 667.
        val text = "中".repeat(1000)
        val est = CharClassTokenEstimator.estimate(text)
        assertEquals(667, est)
        // Sanity vs. the old broken impl which would have returned 250.
        assertTrue(
            "regression: Chinese must NOT be estimated at ~4 chars/token (got $est)",
            est >= 500,
        )
    }

    @Test
    fun mixedChineseAndEnglish_addsTwoBucketsIndependently() {
        // 500 CJK + 500 ASCII → ceil(2*500/3) + ceil(500/4) = 334 + 125 = 459.
        val text = "中".repeat(500) + "a".repeat(500)
        assertEquals(459, CharClassTokenEstimator.estimate(text))
    }

    @Test
    fun chineseFullWidthPunctuation_countedAsCjk() {
        // "中，国。日" — 3 CJK ideographs + 2 full-width punctuation marks.
        // All five chars fall in the CJK bucket → ceil(2*5/3) = 4.
        val text = "中，国。日"
        assertEquals(4, CharClassTokenEstimator.estimate(text))
    }

    @Test
    fun hiraganaKatakanaHangul_countedAsCjk() {
        // 6 chars across hiragana / katakana / hangul → ceil(2*6/3) = 4.
        val text = "あカ가ナ漢한"
        assertEquals(4, CharClassTokenEstimator.estimate(text))
    }

    @Test
    fun singleAsciiChar_roundsUpToOneToken() {
        // ceil(1/4) = 1 — not 0.
        assertEquals(1, CharClassTokenEstimator.estimate("a"))
    }

    @Test
    fun singleChineseChar_roundsUpToOneToken() {
        // ceil(2*1/3) = 1 — not 0.
        assertEquals(1, CharClassTokenEstimator.estimate("中"))
    }
}
