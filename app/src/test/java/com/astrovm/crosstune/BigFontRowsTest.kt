package com.astrovm.crosstune

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Rows with words and something beside them, read at the biggest font, on a narrow phone and in long languages. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h891dp-xxhdpi", fontScale = 3.2f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BigFontRowsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun string(id: Int, vararg args: Any) = ApplicationProvider.getApplicationContext<Context>().getString(id, *args)

    private fun firstAsksWhole() {
        composeRule.setContent { CrosstuneTheme { Box(Modifier.width(320.dp).verticalScroll(rememberScrollState())) { FirstAsks(UiState(), ScreenActions()) } } }
        val page = composeRule.onRoot().fetchSemanticsNode().size.width - with(composeRule.density) { 32.dp.toPx() }
        assertTrue(composeRule.assertWordsWhole("first asks") { page } > 2)
        // Allow is still there to see, under the words rather than squeezing them.
        val allow = composeRule.onAllNodesWithText(string(R.string.allow_button), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(allow.isNotEmpty())
        val gets = composeRule.onNodeWithText(string(R.string.ask_follow_gets), useUnmergedTree = true).getBoundsInRoot()
        val under = allow.map { with(composeRule.density) { it.boundsInRoot.top.toDp() } }
        assertTrue("Allow at $under, beside $gets", under.any { it >= gets.bottom })
    }

    @Test
    fun whatToAllowKeepsItsWordsWholeInEnglish() = firstAsksWhole()

    @Test
    @Config(qualifiers = "de-w320dp-h891dp-xxhdpi")
    fun whatToAllowKeepsItsWordsWholeInGerman() = firstAsksWhole()

    @Test
    @Config(qualifiers = "es-w320dp-h891dp-xxhdpi")
    fun whatToAllowKeepsItsWordsWholeInSpanish() = firstAsksWhole()

    private fun aboutWhole() {
        composeRule.setContent { CrosstuneTheme { SettingsScreen(UiState(), ScreenActions(), onBack = {}) } }
        // "Made with ♥ by astro", however the language puts it, is read as one line of words.
        val made = composeRule.onNode(hasText("astro", substring = true), useUnmergedTree = true).performScrollTo()
        made.assertIsDisplayed()
        // The page's margins and a row's, 20, on each side.
        val page = composeRule.onRoot().fetchSemanticsNode().size.width - with(composeRule.density) { 72.dp.toPx() }
        // PageTitleFitTest checks the header against its own width; these assertions use the rows' margins.
        composeRule.assertWordsWhole("about", skip = setOf(string(R.string.settings_title))) { page }
        val privacy = composeRule.onNodeWithText(string(R.string.privacy_policy), useUnmergedTree = true).performScrollTo()
        // The heart is in the words, at the start of the row like every other row's words, not a piece squeezed beside it.
        assertEquals(privacy.getBoundsInRoot().left, made.getBoundsInRoot().left)
        // A word wider than the row, as "Datenschutzerklärung", is broken with a hyphen, not cut at its last letter that fits.
        val layouts = mutableListOf<TextLayoutResult>()
        privacy.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val words = layouts.single()
        // Its hyphen is drawn, not a letter, so it's looked for in what's drawn, right after the line's last letter.
        val image = privacy.captureToImage().asAndroidBitmap()
        val ground = image.getPixel(image.width - 1, image.height - 1)
        for (line in 0 until words.lineCount - 1) {
            val end = words.getLineEnd(line, visibleEnd = true)
            val text = words.layoutInput.text.text
            if (text.getOrNull(end)?.isWhitespace() != false || text[end - 1].isWhitespace()) continue
            // Android counts a line's hyphen in its last letter's box, so the letters are measured on their own.
            val letters = words.lettersWide(line)
            val inkEnd = (0 until image.width).last { x -> (words.getLineTop(line).toInt() until words.getLineBottom(line).toInt()).any { y -> image.getPixel(x, y) != ground } }
            val inked = inkEnd > words.getLineLeft(line) + letters + with(composeRule.density) { 2.dp.toPx() }
            assertTrue("\"$text\" broken at $end with no hyphen", inked)
        }
    }

    @Test
    fun aboutKeepsItsWordsWholeInEnglish() = aboutWhole()

    @Test
    @Config(qualifiers = "de-w320dp-h891dp-xxhdpi")
    fun aboutKeepsItsWordsWholeInGerman() = aboutWhole()
}
