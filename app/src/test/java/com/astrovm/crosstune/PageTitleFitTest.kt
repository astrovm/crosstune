package com.astrovm.crosstune

import android.content.Context
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A page keeps its title, navigation and actions reachable when the title needs more than one row. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageTitleFitTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun titleFits(brand: Boolean = false, font: Float = 3.2f) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val title = context.getString(if (brand) R.string.app_name else R.string.settings_title)
        var backs = 0
        var actions = 0
        var measuredFont = 0f
        composeRule.setContent {
            measuredFont = LocalDensity.current.fontScale
            CrosstuneTheme {
                Page(
                    title = title,
                    titleLeading = if (brand) ({ AppLogo() }) else null,
                    navigationIcon = {
                        IconButton(onClick = { backs++ }) { AppIcon(R.drawable.ic_arrow_back, contentDescription = "Back") }
                    },
                    actions = {
                        IconButton(onClick = { actions++ }) { AppIcon(R.drawable.ic_settings, contentDescription = "Action") }
                    }
                ) { Text("Body") }
            }
        }
        val label = composeRule.onNodeWithText(title, useUnmergedTree = true)
        label.assertIsDisplayed()
        assertEquals("font used by this case", font, measuredFont, 0.01f)
        val layouts = mutableListOf<TextLayoutResult>()
        label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertFalse("title is cut short: $title", (0 until layout.lineCount).any(layout::isLineEllipsized))
        // Paragraph width can include blank space outside the text's measured width. Check each glyph.
        title.forEachIndexed { index, letter ->
            if (!letter.isWhitespace()) {
                val glyph = layout.getBoundingBox(index)
                assertTrue("clipped '$letter' in $title: $glyph in ${layout.size}",
                    glyph.left >= -0.5f && glyph.top >= -0.5f && glyph.right <= layout.size.width + 0.5f && glyph.bottom <= layout.size.height + 0.5f)
            }
        }
        val bounds = label.getBoundsInRoot()
        val body = composeRule.onNodeWithText("Body").getBoundsInRoot()
        assertTrue("body covers the title", body.top >= bounds.bottom)
        val page = composeRule.onRoot().fetchSemanticsNode().size.width - with(composeRule.density) { 8.dp.toPx() }
        assertTrue(composeRule.assertWordsWhole("page title") { page } >= 2)
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithContentDescription("Action").performClick()
        assertEquals(1, backs)
        assertEquals(1, actions)
    }

    @Test
    @Config(fontScale = 3.2f)
    fun englishTitleFitsAtTheLargestFont() = titleFits()

    @Test
    @Config(qualifiers = "de-w320dp-h891dp-xxhdpi", fontScale = 3.2f)
    fun germanTitleFitsAtTheLargestFont() = titleFits()

    @Test
    @Config(qualifiers = "es-w320dp-h891dp-xxhdpi", fontScale = 3.2f)
    fun spanishTitleFitsAtTheLargestFont() = titleFits()

    @Test
    @Config(qualifiers = "de-ldrtl-w320dp-h891dp-xxhdpi", fontScale = 3.2f)
    fun aTitleFitsWithRightToLeftPlacement() = titleFits()

    @Test
    @Config(fontScale = 3.2f)
    fun theBrandAndTitleFitWithBothControls() = titleFits(brand = true)

    @Test
    fun theUsualFontKeepsItsTitleAndControls() = titleFits(brand = true, font = 1f)
}
