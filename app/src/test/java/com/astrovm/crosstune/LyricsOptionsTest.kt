package com.astrovm.crosstune

import java.text.NumberFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsOptionsTest {
    @Test
    fun theReadingSummaryDescribesOnlyWhatsVisibleAndKeepsCombinedOptions() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val japanese = Learning(script = Script.JAPANESE, readings = true, readingScale = 0.5f)
            // The number is a share of the largest size, so the end of the slider is 100%.
            assertEquals("あ · 62%", japanese.readingSummary("Off"))
            assertEquals("あ · 100%", japanese.copy(readingScale = MAX_READING_SCALE).readingSummary("Off"))
            assertEquals("あ · 62% · Aa", japanese.copy(romanized = true).readingSummary("Off"))
            assertEquals("Off", japanese.copy(readings = false).readingSummary("Off"))
            assertEquals("Aa", japanese.copy(readings = false, romanized = true).readingSummary("Off"))
            assertEquals("Off", japanese.copy(wordsAs = WordsAs.KANA).readingSummary("Off"))
            assertEquals("Aa", japanese.copy(wordsAs = WordsAs.LATIN).readingSummary("Off"))
            assertEquals("拼 · 62%", japanese.copy(script = Script.CHINESE).readingSummary("Off"))
            assertEquals("拼 · 100%", japanese.copy(script = Script.CHINESE, readingScale = MAX_READING_SCALE).readingSummary("Off"))
            assertEquals("Off", japanese.copy(script = Script.KOREAN).readingSummary("Off"))
            assertEquals("Off", japanese.copy(script = null).readingSummary("Off"))
            val percent = NumberFormat.getPercentInstance()
            assertEquals("100%", percent.format(sizeShare(MAX_WORDS_SCALE, MAX_WORDS_SCALE)))
            // 1% of the largest words is a speck. The floor is half the starting size, about 17%.
            assertEquals(0.5f, MIN_WORDS_SCALE, 0f)
            assertEquals("17%", percent.format(sizeShare(MIN_WORDS_SCALE, MAX_WORDS_SCALE)))
            // Readings run from 1% of their largest size to it. Darkness runs from none to full.
            assertEquals("あ · 1%", japanese.copy(readingScale = MIN_READING_SCALE).readingSummary("Off"))
            assertEquals("0%", percent.format(sizeShare(0f, MAX_VISUALS_SHADE)))
            assertEquals("100%", percent.format(sizeShare(MAX_VISUALS_SHADE, MAX_VISUALS_SHADE)))
            assertEquals("50%", percent.format(sizeShare(DEFAULT_VISUALS_SHADE, MAX_VISUALS_SHADE)))
            assertEquals(1f, sizeShare(MAX_READING_SCALE, MAX_READING_SCALE), 0f)
            // The old largest words, 1.6 times how they start, are under the new 100%.
            assertTrue(sizeShare(1.6f, MAX_WORDS_SCALE) < 1f)
            assertEquals(0f, sizeShare(1f, 0f), 0f)
            assertEquals(0f, sizeShare(-1f, MAX_WORDS_SCALE), 0f)
        } finally {
            Locale.setDefault(before)
        }
    }
}
