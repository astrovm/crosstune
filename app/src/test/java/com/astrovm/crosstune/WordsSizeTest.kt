package com.astrovm.crosstune

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The words as big, as far apart and as wide as reads well on each screen: a phone, one on its side, a TV. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class WordsSizeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val density get() = context.resources.displayMetrics.density

    private val long = "And every Sunday morning while the city sleeps I take the long way round to where we used to meet"

    private val lines = listOf(LyricLine(1_000, "Walking down"), LyricLine(5_000, long), LyricLine(9_000, "River road"))

    private fun show(phone: Phone = Phone.OTHER, translated: Boolean = false) {
        val state = UiState(
            lyricsFor = MusicMetadata("River Road", "The Walkers"),
            lyrics = lines.joinToString("\n") { it.text },
            lyricLines = lines,
            following = Following(PlaybackClock(1_500, SystemClock.elapsedRealtime(), playing = false), "Spotify", canSeek = true),
            canFollowApps = true,
            learning = Learning(translation = translated, translations = listOf("Caminando", null, null)),
            phone = phone
        )
        composeRule.setContent { CrosstuneTheme { LyricsScreen(state, ScreenActions()) } }
        composeRule.waitForIdle()
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val node = composeRule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().first()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!(results)
        return results.single()
    }

    private fun sp(text: String) = layoutOf(text).layoutInput.style.fontSize.value

    private fun lineNodes(): List<SemanticsNode> = composeRule.onAllNodesWithTag(LYRIC_LINE_TAG).fetchSemanticsNodes()

    /** The room between one line and the next, in dp. */
    private fun gap(): Float {
        val (first, second) = lineNodes()
        return (second.boundsInRoot.top - first.boundsInRoot.bottom) / density
    }

    private fun width() = lineNodes().first().boundsInRoot.width / density

    /** How far down one row of the long line is from the row above it, in sp. */
    private fun rowStep(): Float {
        val layout = layoutOf(long)
        assertTrue(layout.lineCount > 1)
        return (layout.getLineTop(1) - layout.getLineTop(0)) / density
    }

    @Test
    fun onAPhoneALineThatWrapsSitsCloserThanTheNextLine() {
        show()
        assertEquals(26f, sp("Walking down"), 0.01f)
        assertEquals(26f * 1.2f, rowStep(), 0.5f)
        assertEquals(16f, gap(), 1f)
        assertTrue(rowStep() - 26f < gap())
        assertEquals(411f - 48f, width(), 1f)
    }

    @Test
    @Config(qualifiers = "w915dp-h412dp-land-xxhdpi")
    fun onAWideScreenTheWordsRunNoWiderThanAboutAPhonesRow() {
        show()
        assertEquals(600f, width(), 1f)
        // In the middle, not left at the start with the rest of the screen empty.
        val bounds = lineNodes().first().boundsInRoot
        assertEquals(915f / 2, (bounds.left + bounds.right) / 2 / density, 1f)
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    fun onATvTheWordsAreBiggerToReadFromAcrossTheRoom() {
        show(Phone.TV, translated = true)
        assertEquals(34f, sp("Walking down"), 0.01f)
        assertEquals(34f * 1.2f, rowStep(), 0.5f)
        assertEquals(14f * 1.3f, sp("Caminando"), 0.01f)
        assertEquals(700f, width(), 1f)
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    fun theSameWideScreenOnAPhoneKeepsAPhonesSize() {
        show(translated = true)
        assertEquals(26f, sp("Walking down"), 0.01f)
        assertEquals(14f, sp("Caminando"), 0.01f)
        assertEquals(600f, width(), 1f)
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    fun onATvTheLinesAreFurtherApartToo() {
        show(Phone.TV)
        assertEquals(20f, gap(), 1f)
    }
}
