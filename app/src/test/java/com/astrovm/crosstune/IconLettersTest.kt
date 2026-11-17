package com.astrovm.crosstune

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A letter standing in for an icon is as big as the icons beside it, at any font. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class IconLettersTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aDestinationsLetterStaysTheSizeOfItsTileAtTheLargestFont() {
        val custom = Destination.Custom("z", "Zebra", "https://zebra.example/?q={query}")
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                CrosstuneTheme { DestinationIcon(custom, installed = emptySet(), size = 20.dp) }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText("Z", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val input = layouts.single().layoutInput
        // Half the 20 dp tile, as at the usual font: a letter twice as big would fill it and more.
        val letter = with(input.density) { input.style.fontSize.toPx() }
        assertEquals(with(input.density) { 10.dp.toPx() }, letter, 0.5f)
    }
}
