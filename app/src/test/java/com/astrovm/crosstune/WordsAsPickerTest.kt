package com.astrovm.crosstune

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertTrue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-rDE-w360dp-h800dp")
class WordsAsPickerTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun longLabelsAtDoubleFontSizeKeepTheSegmentsAligned() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                CrosstuneTheme {
                    Box(Modifier.width(280.dp)) {
                        WordsAsPicker(Script.JAPANESE, WordsAs.WRITTEN, {})
                    }
                }
            }
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        val bounds = listOf(R.string.lyrics_as_written, R.string.lyrics_only_kana, R.string.lyrics_only_latin)
            .map { compose.onNodeWithContentDescription(app.getString(it)).getBoundsInRoot() }
        bounds.drop(1).forEach {
            assertEquals(bounds.first().top.value, it.top.value, 1f)
            assertEquals(bounds.first().bottom.value, it.bottom.value, 1f)
        }
    }
    @Test
    fun theFollowOfferGivesLargeTextTheFullWidthBeforeItsAction() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                CrosstuneTheme {
                    Box(Modifier.width(360.dp)) {
                        LyricsScreen(UiState(lyricsFor = MusicMetadata("Demo", "Band"), lyrics = "One",
                            lyricLines = listOf(LyricLine(0, "One"))), ScreenActions())
                    }
                }
            }
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        val text = compose.onNodeWithText(app.getString(R.string.lyrics_follow_allow)).getBoundsInRoot()
        val action = compose.onNodeWithText(app.getString(R.string.follow_turn_on)).getBoundsInRoot()
        assertTrue("The action should follow the explanation: $text, $action", action.top >= text.bottom)
        assertTrue("The explanation needs the full width: $text", text.right - text.left > 300.dp)
    }

    /** The lyrics over visuals set as bright as they go, saying how they're asked to draw. */
    private fun overBrightestVisuals(): () -> VisualsPlay {
        var drawn = VisualsPlay()
        compose.setContent {
            CompositionLocalProvider(LocalVisuals provides { play, _ -> drawn = play }) {
                CrosstuneTheme {
                    LyricsScreen(UiState(lyricsFor = MusicMetadata("Demo", "Band"), lyrics = "One", visuals = true,
                        visualsShade = 0f, canFollowApps = true, lyricLines = listOf(LyricLine(0, "One"))), ScreenActions())
                }
            }
        }
        compose.waitForIdle()
        return { drawn }
    }

    @Test
    fun evenTheBrightestVisualLeavesReadableWordsAndSoftensFurtherBehindAMenu() {
        val play = overBrightestVisuals()
        assertEquals("A bright visual must stay behind the words", VISUALS_CEILING, play().ceiling, 0.001f)
        assertEquals(0.5f, play().shade, 0.001f)
        val app = ApplicationProvider.getApplicationContext<Application>()
        compose.onNodeWithContentDescription(app.getString(R.string.lyrics_learn)).performClick()
        compose.waitForIdle()
        assertEquals("An open menu should soften the background further", 0.75f, play().shade, 0.001f)
        assertEquals(VISUALS_CEILING, play().ceiling, 0.001f)
    }

    @Test
    fun theVisualsAloneAreDrawnAsTheyAreAndTheWordsBringTheirCeilingBack() {
        val play = overBrightestVisuals()
        val app = ApplicationProvider.getApplicationContext<Application>()
        compose.onNodeWithContentDescription(app.getString(R.string.visuals_options)).performClick()
        compose.onNodeWithText(app.getString(R.string.visuals_only)).performClick()
        compose.waitForIdle()
        assertEquals(0f, play().shade, 0f)
        assertEquals(1f, play().ceiling, 0f)
        val showWords = app.getString(R.string.visuals_show_words)
        compose.onNode(SemanticsMatcher("shows the words") {
            SemanticsActions.OnClick in it.config && it.config[SemanticsActions.OnClick].label == showWords
        }).performClick()
        compose.waitForIdle()
        assertEquals(0.5f, play().shade, 0.001f)
        assertEquals(VISUALS_CEILING, play().ceiling, 0.001f)
    }
}
