package com.astrovm.crosstune

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** The line being sung stays what the eye goes to over the brightest the visuals get, with the others still there to read. */
class WordsContrastTest {
    private val brightest = Color(VISUALS_CEILING, VISUALS_CEILING, VISUALS_CEILING)

    private fun contrast(alpha: Float): Float {
        val ink = Color.White.copy(alpha = alpha).compositeOver(brightest).luminance()
        return (ink + 0.05f) / (brightest.luminance() + 0.05f)
    }

    private val sung = contrast(lineAlpha(0, waiting = false, coming = false))
    private val next = contrast(lineAlpha(1, waiting = false, coming = true))
    private val toCome = contrast(lineAlpha(1, waiting = false, coming = false))
    private val past = contrast(lineAlpha(-1, waiting = false, coming = false))

    @Test
    fun eachLineStandsApartFromTheOneBrighterThanIt() {
        assertTrue("sung $sung next $next", sung >= next * 1.25f)
        assertTrue("next $next to come $toCome", next >= toCome * 1.5f)
        assertTrue("to come $toCome past $past", toCome >= past * 1.5f)
    }

    @Test
    fun theLineBeingSungReadsWell() = assertTrue("$sung", sung >= 7f)

    @Test
    fun evenTheDimmestLineCanBeRead() = assertTrue("$past", past >= 3f)
}
