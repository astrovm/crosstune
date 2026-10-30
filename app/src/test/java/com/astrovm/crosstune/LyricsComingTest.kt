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

    private fun show(positionMs: Long, nextLineFirst: Boolean) {
        val state = UiState(
            lyricsFor = MusicMetadata("River Road", "The Walkers"),
            lyrics = lines.joinToString("\n") { it.text },
            lyricLines = lines,
            following = Following(PlaybackClock(positionMs, SystemClock.elapsedRealtime(), playing = false), "Spotify", canSeek = true),
            canFollowApps = true,
            linesEarlyMs = 0,
            nextLineFirst = nextLineFirst
        )
        composeRule.setContent { CrosstuneTheme { LyricsScreen(state, ScreenActions()) } }
        composeRule.waitForIdle()
    }

    /** How strongly the line [index] is drawn against what's behind it: its most different pixel. */
    private fun ink(index: Int): Int {
        val node = composeRule.onAllNodesWithTag(LYRIC_LINE_TAG).fetchSemanticsNodes()[index]
        val image = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val bounds = node.boundsInRoot
        val background = image.getPixel(0, bounds.top.toInt())
        var strongest = 0
        for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
            for (x in bounds.left.toInt() until bounds.right.toInt()) {
                val pixel = image.getPixel(x, y)
                strongest = maxOf(strongest, shifts.sumOf { shift -> abs(((pixel shr shift) and 0xFF) - ((background shr shift) and 0xFF)) })
            }
        }
        return strongest
    }

    private val shifts = listOf(0, 8, 16)


    @Test
    fun setToTheLineComingNextIsBrighterThanTheOnesAfterItAMomentBeforeItIsSung() {
        show(4_500, nextLineFirst = true)
        assertTrue(ink(0) > ink(1))
        assertTrue("${ink(1)} ${ink(2)}", ink(1) > ink(2) + 20)
    }

    @Test
    fun notSetToItIsDimLikeTheRestUntilItIsSung() {
        show(4_500, nextLineFirst = false)
        assertEquals(ink(2).toFloat(), ink(1).toFloat(), 15f)
    }

    @Test
    fun setToItWaitsTillTheNextLineIsNearBeforeBrightening() {
        show(3_500, nextLineFirst = true)
        assertEquals(ink(2).toFloat(), ink(1).toFloat(), 15f)
    }
}
