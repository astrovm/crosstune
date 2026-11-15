package com.astrovm.crosstune

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The steps to fix something, read at the biggest font Android has. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi", fontScale = 3.2f)
// Real text measuring, so words take the room they would on a phone.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FixStepsBigFontTest {

    @get:Rule
    val composeRule = createComposeRule()


    private fun layoutOf(node: SemanticsNodeInteraction): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    @Test
    fun aStepsNumberIsWholeInsideItsCircle() {
        composeRule.setContent { CrosstuneTheme { Column { NumberedStep(2, AnnotatedString("Tap Allow.")) } } }
        val number = layoutOf(composeRule.onNodeWithText("2", useUnmergedTree = true))
        // Clipped, the circle shows only the top of the digit.
        assertTrue("${number.multiParagraph.height} px of digit in ${number.size.height} px", number.multiParagraph.height <= number.size.height && !number.didOverflowHeight)
    }

    private var state by mutableStateOf(UiState())

    /** Every step page there is, on every kind of phone, its words and its pictures' words whole. */
    private fun everyStepWhole() {
        val spotify = LinkApp(MusicService.SPOTIFY.packageName, "Spotify")
        val broken = UiState(intercepted = setOf(MusicService.SPOTIFY), unapprovedHosts = mapOf(MusicService.SPOTIFY to listOf("open.spotify.com")), blockingApps = emptySet())
        val scenes = Phone.entries.flatMap { phone ->
            listOf(Need.Links, Need.Microphone, Need.Follow, Need.Float, Need.Exact, Need.StopApp(spotify)).map { need -> broken.copy(phone = phone, fixes = Fixes(steps = listOf(need))) } +
                broken.copy(phone = phone, followRestricted = true, fixes = Fixes(steps = listOf(Need.Follow)))
        }
        // The pictures' words are left out for TalkBack, but each is still laid out where tests can see it.
        composeRule.setContent { CrosstuneTheme { FixStepPage(state, ScreenActions()) } }
        var words = 0
        for (scene in scenes) {
            state = scene
            composeRule.waitForIdle()
            // The page scrolls, and says so to a screen reader, so steps under the buttons can be reached.
            composeRule.onNode(hasScrollAction()).assertExists()
            val page = with(composeRule.density) { (composeRule.onRoot().getBoundsInRoot().right - 32.dp).toPx() }
            val pictures = composeRule.onAllNodesWithTag(ANDROID_PICTURE_TAG, useUnmergedTree = true).fetchSemanticsNodes()
            val texts = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            for (index in texts.fetchSemanticsNodes().indices) {
                val node = texts[index]
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.singleOrNull() ?: continue
                val text = layout.layoutInput.text.text
                // A picture's words have the picture's room, the page's others.
                // Where each is laid out, scrolled into view or not.
                fun SemanticsNode.whole() = Rect(positionInRoot, size.toSize())
                val center = texts[index].fetchSemanticsNode().whole().center
                val room = pictures.firstOrNull { it.whole().contains(center) }?.size?.width?.toFloat()?.let { it - with(composeRule.density) { DRAWN_MARGINS.toPx() } } ?: page
                val where = "${scene.phone} ${scene.fixes?.need?.key}${if (scene.followRestricted) " restricted" else ""}: \"$text\""
                assertFalse("$where is cut short", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
                assertFalse("$where is cut off", layout.didOverflowHeight || layout.multiParagraph.height > layout.size.height + 0.5f)
                // A line ends only between words, or inside one wider than the page, which nothing could fit.
                val input = layout.layoutInput
                for (line in 0 until layout.lineCount - 1) {
                    val end = layout.getLineEnd(line, visibleEnd = true)
                    val between = text.getOrNull(end)?.isWhitespace() != false || text.getOrNull(end - 1)?.let { it.isWhitespace() || it == '-' || it == '/' } == true
                    if (between) continue
                    val start = text.lastIndexOf(' ', end - 1) + 1
                    val stop = text.indexOf(' ', end).takeIf { it >= 0 } ?: text.length
                    val word = text.substring(start, stop)
                    // In its own bold or not, as it's drawn.
                    val drawn = input.text.subSequence(start, stop)
                    val wide = Paragraph(word, input.style, Constraints(), input.density, input.fontFamilyResolver, spanStyles = drawn.spanStyles).maxIntrinsicWidth
                    assertTrue("$where breaks inside \"$word\", ${wide.toInt()} px in ${room.toInt()} px", wide > room)
                }
                words++
            }
        }
        assertTrue(words > scenes.size)
    }

    @Test
    fun everyStepAndItsPictureKeepsItsWordsWholeInEnglish() = everyStepWhole()

    @Test
    @Config(qualifiers = "es-w411dp-h891dp-xxhdpi")
    fun everyStepAndItsPictureKeepsItsWordsWholeInSpanish() = everyStepWhole()

    @Test
    @Config(qualifiers = "de-w411dp-h891dp-xxhdpi")
    fun everyStepAndItsPictureKeepsItsWordsWholeInGerman() = everyStepWhole()

    @Test
    @Config(qualifiers = "de-w320dp-h891dp-xxhdpi")
    fun everyStepAndItsPictureKeepsItsWordsWholeInGermanOnANarrowPhone() = everyStepWhole()
}

/** The most a picture of Android's page puts around a word: a dialog button's margins, its ring's and its own, on both sides. */
private val DRAWN_MARGINS = 52.dp
