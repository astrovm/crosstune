package com.astrovm.crosstune

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsOptionsTest {
    @Test
    fun theReadingSummaryDescribesOnlyWhatsVisibleAndKeepsCombinedOptions() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val japanese = Learning(script = Script.JAPANESE, readings = true, readingScale = 0.5f)
            // Percentages describe the usable range, not the physical text scale.
            assertEquals("あ · 15%", japanese.readingSummary("Off"))
            assertEquals("あ · 100%", japanese.copy(readingScale = MAX_READING_SCALE).readingSummary("Off"))
            assertEquals("あ · 15% · Aa", japanese.copy(romanized = true).readingSummary("Off"))
            assertEquals("Off", japanese.copy(readings = false).readingSummary("Off"))
            assertEquals("Aa", japanese.copy(readings = false, romanized = true).readingSummary("Off"))
            assertEquals("Off", japanese.copy(wordsAs = WordsAs.KANA).readingSummary("Off"))
            assertEquals("Aa", japanese.copy(wordsAs = WordsAs.LATIN).readingSummary("Off"))
            assertEquals("拼 · 15%", japanese.copy(script = Script.CHINESE).readingSummary("Off"))
            assertEquals("拼 · 100%", japanese.copy(script = Script.CHINESE, readingScale = MAX_READING_SCALE).readingSummary("Off"))
            assertEquals("Off", japanese.copy(script = Script.KOREAN).readingSummary("Off"))
            assertEquals("Off", japanese.copy(script = null).readingSummary("Off"))
            assertEquals("あ · 1%", japanese.copy(readingScale = MIN_READING_SCALE).readingSummary("Off"))

        } finally {
            Locale.setDefault(before)
        }
    }
    @Test
    fun everyPercentSliderStartsAtOneAndKeepsItsReadableMinimum() {
        assertEquals(0.6f, MIN_WORDS_SCALE, 0f)
        assertEquals(0.45f, MIN_READING_SCALE, 0f)
        val ranges = listOf(
            MIN_WORDS_SCALE..MAX_WORDS_SCALE,
            MIN_READING_SCALE..MAX_READING_SCALE,
            FloatingOptions.MIN_SCALE..FloatingOptions.MAX_SCALE,
            0f..FloatingOptions.MAX_BACKGROUND,
            0f..MAX_VISUALS_SHADE
        )
        ranges.forEach { range ->
            assertEquals(0.01f, sizeShare(range.start, range), 0.00001f)
            assertEquals(1f, sizeShare(range.endInclusive, range), 0.00001f)
            assertEquals(0.505f, sizeShare((range.start + range.endInclusive) / 2f, range), 0.00001f)
            assertEquals(0.01f, sizeShare(range.start - 1f, range), 0.00001f)
            assertEquals(1f, sizeShare(range.endInclusive + 1f, range), 0.00001f)
        }
        assertEquals(0.01f, sizeShare(1f, 0f..0f), 0f)
        assertEquals(0.01f, sizeShare(1f, 1f..0f), 0f)
    }

}
