package com.astrovm.crosstune

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Custom kind names must remain distinguishable without hiding the song they belong to. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PracticeKindFitTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun SemanticsNodeInteraction.assertWordsWhole(name: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val drawn = layouts.single()
        assertFalse("$name is ellipsized", (0 until drawn.lineCount).any { drawn.isLineEllipsized(it) })
        for (index in name.indices.filterNot { name[it].isWhitespace() }) {
            val glyph = drawn.getBoundingBox(index)
            assertTrue("$name: character $index at $glyph outside ${drawn.size}", glyph.left >= -0.5f && glyph.right <= drawn.size.width + 0.5f && glyph.top >= -0.5f && glyph.bottom <= drawn.size.height + 0.5f)
        }
        val bounds = getBoundsInRoot()
        assertTrue("$name has no drawn width", bounds.right > bounds.left)
    }

    private fun everyKindIsWhole() {
        val line = SavedLine("A made up line", title = "Night Song", artist = "Band")
        var kind by mutableStateOf(READ_IT)
        composeRule.setContent {
            CrosstuneTheme {
                PracticeScreen(UiState(practice = Practice(queue = listOf(line), total = 1), cardTypes = listOf(kind, HEAR_IT.copy(on = true))), ScreenActions())
            }
        }
        for (name in listOf("Read", "Read this line and recall its meaning Alpha", "Read this line and recall its meaning Beta", "Averylongcustomkindnamethatmustremainvisible")) {
            kind = READ_IT.copy(name = name)
            composeRule.waitForIdle()
            val node = composeRule.onNodeWithText(name, useUnmergedTree = true)
            node.performScrollTo()
            node.assertWordsWhole(name)
            val song = composeRule.onNodeWithText(line.title, useUnmergedTree = true)
            song.performScrollTo()
            song.assertWordsWhole(line.title)
        }
    }

    @Test
    fun longKindNamesKeepTheirEndAndTheSongTitle() = everyKindIsWhole()

    @Test
    @Config(fontScale = 3.2f)
    fun longKindNamesKeepTheirEndAtTheLargestFont() = everyKindIsWhole()

    @Test
    @Config(qualifiers = "w320dp-h891dp-xxhdpi", fontScale = 3.2f)
    fun longKindNamesKeepTheirEndOnANarrowPhone() = everyKindIsWhole()

    @Test
    @Config(fontScale = 3.2f)
    fun theKindListAlsoShowsTheDistinguishingEndOfEachName() {
        val names = listOf("Read this line and recall its meaning Alpha", "Read this line and recall its meaning Beta")
        val types = names.mapIndexed { index, name -> READ_IT.copy(id = "type$index", name = name) }
        composeRule.setContent {
            CrosstuneTheme { CardTypesPanel(SavedLine("A made up line", title = "Night Song", artist = "Band"), types, false, ScreenActions(), onClose = {}) }
        }
        for (name in names) {
            val node = composeRule.onNodeWithText(name, useUnmergedTree = true)
            node.performScrollTo()
            node.assertWordsWhole(name)
        }
    }
}
