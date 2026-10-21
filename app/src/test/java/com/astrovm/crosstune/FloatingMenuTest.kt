package com.astrovm.crosstune

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsOn
import org.junit.Assert.assertEquals
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-rDE-w360dp-h800dp")
class FloatingMenuTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun largeTextKeepsTheDoneLabelOnOneLine() {
        val actions = FloatingActions({}, {}, {}, {}, {}, { _, _ -> }, {})
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                CrosstuneTheme {
                    Box(Modifier.width(280.dp)) {
                        FloatingOverApps(UiState(lyricsFor = MusicMetadata("Demo", "Band")), actions, true, {})
                    }
                }
            }
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        compose.onNodeWithContentDescription(app.getString(R.string.floating_customize)).performClick()
        val done = compose.onNodeWithText(app.getString(R.string.floating_done)).performScrollTo().getUnclippedBoundsInRoot()
        assertTrue("Done should be readable on one line: $done", done.bottom - done.top < 60.dp)
    }
    @Test
    fun floatingWordsUseTheSharedDisplaySettingsAndExposeThemInCustomize() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val learning = mutableStateOf(Learning(script = Script.JAPANESE, readings = true, translation = true,
            lines = listOf(LineReading(listOf(Ruby("夜", "よる")), "よる", "yoru")), translations = listOf("Night")))
        val actions = FloatingActions({}, {}, {}, {}, {}, { _, _ -> }, {}, learning = ScreenActions(
            onWordsScaleChange = { value, _ -> learning.value = learning.value.copy(wordsScale = value) },
            onTranslationChange = { learning.value = learning.value.copy(translation = it) },
            onReadingScaleChange = { value, _ -> learning.value = learning.value.copy(readingScale = value) }
        ))
        compose.setContent {
            CrosstuneTheme {
                FloatingOverApps(UiState(lyricsFor = MusicMetadata("Demo", "Band"), lyricLines = listOf(LyricLine(0, "夜")),
                    following = Following(PlaybackClock(1_000, 0, playing = false), null, false), learning = learning.value), actions, true, {})
            }
        }
        val before = compose.onNodeWithText("夜").getUnclippedBoundsInRoot().height
        compose.onNodeWithContentDescription(app.getString(R.string.floating_customize)).performClick()
        compose.onNodeWithText(app.getString(R.string.lyrics_learn)).performClick()
        compose.onNodeWithText(app.getString(R.string.floating_shared)).assertExists()
        compose.onNodeWithContentDescription(app.getString(R.string.lyrics_words_size)).performSemanticsAction(SemanticsActions.SetProgress) { it(1.5f) }
        assertEquals(1.5f, learning.value.wordsScale, 0f)
        assertTrue(compose.onNodeWithText("夜").getUnclippedBoundsInRoot().height > before)
        compose.onNodeWithText(app.getString(R.string.lyrics_translation)).performScrollTo().performClick()
        assertTrue(!learning.value.translation)
        compose.onNodeWithContentDescription(app.getString(R.string.lyrics_reading_options)).performScrollTo().performClick()
        compose.onNodeWithContentDescription(app.getString(R.string.lyrics_reading_size)).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        assertEquals(1f, learning.value.readingScale, 0f)
        compose.onNodeWithContentDescription(app.getString(R.string.back_button)).performScrollTo().performClick()
        compose.onNodeWithContentDescription(app.getString(R.string.back_button)).performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.floating_overlay_size)).assertExists()
    }

}
