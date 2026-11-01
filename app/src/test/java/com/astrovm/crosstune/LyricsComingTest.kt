package com.astrovm.crosstune

import android.os.SystemClock
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** The line coming next brightening a moment before it's sung, when set to, in the words of a song. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class LyricsComingTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val lines = listOf(LyricLine(0, "First"), LyricLine(5_000, "Second"), LyricLine(9_000, "Third"))

    private fun show(positionMs: Long, aheadMs: Long, lines: List<LyricLine> = this.lines) {
        val state = UiState(
            lyricsFor = MusicMetadata("River Road", "The Walkers"),
            lyrics = lines.joinToString("\n") { it.text },
            lyricLines = lines,
            following = Following(PlaybackClock(positionMs, SystemClock.elapsedRealtime(), playing = false), "Spotify", canSeek = true),
            canFollowApps = true,
            linesEarlyMs = 0,
            nextLineAheadMs = aheadMs
        )
        composeRule.setContent { CrosstuneTheme { LyricsScreen(state, ScreenActions()) } }
        composeRule.waitForIdle()
    }

    /**
     * How strongly the line [index] is drawn against what's behind it: its most different pixel, across
     * the line or, as a share of where its words are drawn, from [from] to [to].
     */
    private fun ink(index: Int, from: Float = 0f, to: Float = 1f): Int {
        val node = composeRule.onAllNodesWithTag(LYRIC_LINE_TAG).fetchSemanticsNodes()[index]
        val image = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val bounds = node.boundsInRoot
        val rows = bounds.top.toInt() until bounds.bottom.toInt()
        // Against what's behind it on the same row, as the ground shades from top to bottom.
        fun difference(x: Int, y: Int) = shifts.sumOf { shift -> abs(((image.getPixel(x, y) shr shift) and 0xFF) - ((image.getPixel(0, y) shr shift) and 0xFF)) }
        val drawn = (bounds.left.toInt() until bounds.right.toInt()).filter { x -> rows.any { y -> difference(x, y) > inked } }
        val start = drawn.first()
        val width = drawn.last() - start
        return rows.maxOf { y -> ((start + width * from).toInt()..(start + width * to).toInt()).maxOf { x -> difference(x, y) } }
    }

    private val shifts = listOf(0, 8, 16)

    /** How different from what's behind it a pixel has to be to be a letter's, not the ground's shading. */
    private val inked = 60


    @Test
    fun setToTheLineComingNextIsBrighterThanTheOnesAfterItAMomentBeforeItIsSung() {
        show(4_500, aheadMs = 1_000)
        assertTrue(ink(0) > ink(1))
        assertTrue("${ink(1)} ${ink(2)}", ink(1) > ink(2) + 20)
    }

    @Test
    fun notSetToItIsDimLikeTheRestUntilItIsSung() {
        show(4_500, aheadMs = 0)
        assertEquals(ink(2).toFloat(), ink(1).toFloat(), 15f)
    }

    @Test
    fun setToItWaitsTillTheNextLineIsNearBeforeBrightening() {
        show(3_500, aheadMs = 1_000)
        assertEquals(ink(2).toFloat(), ink(1).toFloat(), 15f)
    }

    @Test
    fun setFurtherAheadItBrightensSooner() {
        show(3_500, aheadMs = 2_000)
        assertTrue("${ink(1)} ${ink(2)}", ink(1) > ink(2) + 20)
    }

    @Test
    fun timedWordByWordOnlyItsFirstWordBrightensAhead() {
        // The words light one by one as they're sung, so only the first is the eye's to find ahead.
        val words = listOf(TimedWord(5_000, "Wide "), TimedWord(5_400, "open "), TimedWord(5_800, "roads"))
        val timed = listOf(LyricLine(0, "First"), LyricLine(5_000, "Wide open roads", words), LyricLine(9_000, "Third"))
        show(4_500, aheadMs = 1_000, lines = timed)
        // Its first word, where it starts, as bright as a line brightening ahead; its last, where it ends, as dim as those after it.
        assertTrue("${ink(1, to = 0.25f)} ${ink(2)}", ink(1, to = 0.25f) > ink(2) + 20)
        assertEquals(ink(2).toFloat(), ink(1, from = 0.75f).toFloat(), 15f)
    }
}
