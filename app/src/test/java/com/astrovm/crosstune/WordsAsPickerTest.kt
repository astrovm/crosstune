package com.astrovm.crosstune

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
                        LyricsScreen(UiState(lyricsFor = MusicMetadata("Demo", "Band"), lyrics = listOf("One"),
                            lyricLines = listOf(LyricLine(0, "One"))), ScreenActions())
                    }
                }
            }
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        val text = compose.onNodeWithText(app.getString(R.string.lyrics_follow_allow)).getBoundsInRoot()
        val action = compose.onNodeWithText(app.getString(R.string.follow_turn_on)).getBoundsInRoot()
        assertTrue("The action should follow the explanation: $text, $action", action.top >= text.bottom)
        assertTrue("The explanation needs the full width: $text", text.width > 300f)
    }

}
