package com.astrovm.crosstune

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Similar preset names must keep the ending that distinguishes them. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h891dp-xxhdpi", fontScale = 3.2f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VisualNameFitTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun longPresetNamesKeepTheirDifferentEndings() {
        val names = listOf("Alpha", "Beta").map { "QA floating lights above the quiet ocean at night $it" }
        composeRule.setContent {
            CrosstuneTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    names.forEach { name -> VisualRow(name, VisualsSettings(), true, ScreenActions(), onSee = {}) }
                }
            }
        }
        names.forEach { name ->
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(name, useUnmergedTree = true).performScrollTo()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse("preset name is cut short: $name", (0 until layout.lineCount).any(layout::isLineEllipsized))
            assertFalse("preset name is clipped: $name", layout.hasVisualOverflow)
        }
    }
}
