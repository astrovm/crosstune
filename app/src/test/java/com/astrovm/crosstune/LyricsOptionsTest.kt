package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsOptionsTest {
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
