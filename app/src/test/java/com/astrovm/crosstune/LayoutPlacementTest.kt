package com.astrovm.crosstune

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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

/** Where a label and its value, an icon and its words, and Android's pictured rows and buttons are put. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayoutPlacementTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun at(tag: String): DpRect = composeRule.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()

    private fun textAt(text: String): DpRect = composeRule.onNodeWithText(text, useUnmergedTree = true).getBoundsInRoot()

    private fun block(tag: String, width: Int, height: Int): @Composable () -> Unit = { Box(Modifier.testTag(tag).size(width.dp, height.dp)) }

    private var scene by mutableStateOf<(@Composable () -> Unit)?>(null)
    private var started = false

    /** Shows [content] in place of what was shown: a test sets its content only once. */
    private fun shown(content: @Composable () -> Unit) {
        scene = content
        if (!started) composeRule.setContent { scene?.invoke() }
        started = true
        composeRule.waitForIdle()
    }

    private fun show(direction: LayoutDirection = LayoutDirection.Ltr, content: @Composable () -> Unit) = shown {
        CompositionLocalProvider(LocalDensity provides Density(1f), LocalLayoutDirection provides direction) { Box { content() } }
    }

    private fun assertAt(left: Float, top: Float, right: Float, bottom: Float, rect: DpRect) {
        assertEquals("left of $rect", left, rect.left.value, 0.5f)
        assertEquals("top of $rect", top, rect.top.value, 0.5f)
        assertEquals("right of $rect", right, rect.right.value, 0.5f)
        assertEquals("bottom of $rect", bottom, rect.bottom.value, 0.5f)
    }

    @Test
    fun aValueThatFitsSitsAtTheRowsEndInTheMiddleOfItsLabel() {
        show { LabelAndValue(label = block("label", 100, 40), modifier = Modifier.testTag("row").width(300.dp), value = block("value", 50, 20)) }
        // The label takes the rest of the row, 300 less the value and the 12 between.
        assertAt(0f, 0f, 238f, 40f, at("label"))
        assertAt(250f, 10f, 300f, 30f, at("value"))
        assertAt(0f, 0f, 300f, 40f, at("row"))
    }

    @Test
    fun rightToLeftTheValueSitsAtTheRowsStartOnTheLeft() {
        show(LayoutDirection.Rtl) { LabelAndValue(label = block("label", 100, 40), modifier = Modifier.width(300.dp), value = block("value", 50, 20)) }
        assertAt(0f, 10f, 50f, 30f, at("value"))
        assertAt(62f, 0f, 300f, 40f, at("label"))
    }

    @Test
    fun aValueThatWouldSqueezeItsLabelGoesUnderIt() {
        // 50 + 12 + 100, the label's narrowest, is more than 140.
        show { LabelAndValue(label = block("label", 100, 40), modifier = Modifier.testTag("row").width(140.dp), value = block("value", 50, 20)) }
        assertAt(0f, 0f, 100f, 40f, at("label"))
        assertAt(0f, 42f, 50f, 62f, at("value"))
        assertEquals(62f, at("row").bottom.value, 0.5f)
    }

    @Test
    fun aLabelAndValueIsAsWideAsBothSideBySideAtMostAndItsWidestAtLeast() {
        show { LabelAndValue(label = block("label", 100, 40), modifier = Modifier.testTag("row").width(IntrinsicSize.Max), value = block("value", 50, 20)) }
        assertEquals(162f, at("row").run { right - left }.value, 0.5f)
        show { LabelAndValue(label = block("label", 100, 40), modifier = Modifier.testTag("row").width(IntrinsicSize.Min), value = block("value", 50, 20)) }
        assertEquals(100f, at("row").run { right - left }.value, 0.5f)
    }

    @Test
    fun anIconSitsBesideItsWordsInTheirMiddleOrAtTheirTop() {
        show { LeadAndWords(lead = block("lead", 30, 30), modifier = Modifier.testTag("row").width(300.dp)) { block("words", 100, 60)() } }
        assertAt(0f, 15f, 30f, 45f, at("lead"))
        assertAt(42f, 0f, 142f, 60f, at("words"))
        // As wide as the row it's given.
        assertAt(0f, 0f, 300f, 60f, at("row"))
        show { LeadAndWords(lead = block("lead", 30, 30), modifier = Modifier.width(300.dp), top = true) { block("words", 100, 60)() } }
        assertAt(0f, 0f, 30f, 30f, at("lead"))
    }

    @Test
    fun rightToLeftAnIconSitsOnTheRightOfItsWords() {
        show(LayoutDirection.Rtl) { LeadAndWords(lead = block("lead", 30, 30), modifier = Modifier.width(300.dp)) { block("words", 100, 60)() } }
        assertAt(270f, 15f, 300f, 45f, at("lead"))
        assertAt(158f, 0f, 258f, 60f, at("words"))
    }

    @Test
    fun anIconGoesOverWordsThatWouldNotFitBesideIt() {
        // 30 + 12 + 100 is more than 120.
        show { LeadAndWords(lead = block("lead", 30, 30), modifier = Modifier.testTag("row").width(120.dp)) { block("words", 100, 60)() } }
        assertAt(0f, 0f, 30f, 30f, at("lead"))
        assertAt(0f, 32f, 100f, 92f, at("words"))
        assertAt(0f, 0f, 120f, 92f, at("row"))
    }

    @Test
    fun anIconAndItsWordsAreAsWideAsBothSideBySideAtMostAndItsWidestAtLeast() {
        show { LeadAndWords(lead = block("lead", 30, 30), modifier = Modifier.testTag("row").width(IntrinsicSize.Max)) { block("words", 100, 60)() } }
        assertEquals(142f, at("row").run { right - left }.value, 0.5f)
        show { LeadAndWords(lead = block("lead", 30, 30), modifier = Modifier.testTag("row").width(IntrinsicSize.Min)) { block("words", 100, 60)() } }
        assertEquals(100f, at("row").run { right - left }.value, 0.5f)
    }

    private fun picture(content: @Composable () -> Unit) {
        shown { CrosstuneTheme { Box(Modifier.testTag("page").width(320.dp)) { content() } } }
    }

    /** Whether anything is drawn on the page between those edges, in dp: a control with no words or semantics is found by its ink. */
    private fun inked(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        val image = composeRule.onNodeWithTag("page").captureToImage().asAndroidBitmap()
        val ground = image.getPixel(image.width - 1, 0)
        val px = { dp: Float -> with(composeRule.density) { dp.dp.toPx() }.toInt().coerceIn(0, image.width) }
        val rows = px(top) until minOf(image.height, px(bottom))
        return (px(left) until px(right)).any { x -> rows.any { y -> image.getPixel(x, y) != ground } }
    }

    @Test
    fun aSwitchGoesAfterItsRowsWordsAndARadioButtonBefore() {
        // Ring, 6, and row, 12, around the words with nothing beside them.
        picture { AndroidRow("Open supported links") }
        val plain = textAt("Open supported links")
        assertEquals(18f, plain.left.value, 0.5f)
        picture { AndroidRow("Open supported links", Control.SWITCH) }
        val switched = textAt("Open supported links")
        assertEquals(18f, switched.left.value, 0.5f)
        // A switch, 52 wide, and the 12 before it, at the end of the row.
        assertTrue("$switched leaves no room for its switch", switched.right.value <= 320f - 18f - 52f - 12f + 0.5f)
        // The switch is drawn there, beside its words, and nothing is drawn there without one.
        assertTrue("no switch drawn after \"Open supported links\"", inked(320f - 18f - 52f, switched.top.value, 320f - 18f, switched.bottom.value))
        picture { AndroidRow("Open supported links") }
        assertFalse("something drawn where no switch is", inked(320f - 18f - 52f, plain.top.value, 320f - 18f, plain.bottom.value))
        picture { AndroidRow("In the app", Control.RADIO_ON) }
        val radio = textAt("In the app")
        assertTrue("$radio leaves no room for its radio button before it", radio.left.value >= 18f + 20f + 12f - 0.5f)
        assertTrue("no radio button drawn before \"In the app\"", inked(18f, radio.top.value, radio.left.value - 1f, radio.bottom.value))
        picture { AndroidRow("Crosstune", leading = block("icon", 24, 24)) }
        assertEquals(18f + 24f + 12f, textAt("Crosstune").left.value, 0.5f)
        assertEquals(18f, at("icon").left.value, 0.5f)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi", fontScale = 3.2f)
    fun atTheBiggestFontASwitchGoesUnderWordsThatWouldNotFitBesideIt() {
        picture { AndroidRow("Allow notification access", Control.SWITCH) }
        val words = textAt("Allow notification access")
        val row = at("page")
        // The words keep the whole row, and the switch, 32 tall, has its own room under them.
        assertEquals(18f, words.left.value, 0.5f)
        assertTrue("$words in $row", words.right.value > 320f - 18f - 52f - 12f)
        assertTrue("$words in $row leaves no room for the switch under them", row.bottom.value >= words.bottom.value + 2f + 32f + 4f + 2f - 0.5f)
        assertTrue("no switch drawn under \"Allow notification access\"", inked(18f, words.bottom.value + 1f, 320f - 18f, row.bottom.value))
    }

    @Test
    fun aDialogsButtonsSitSideBySideAtItsEnd() {
        picture { AndroidButtons("Deny", "Allow", ringed = "Allow") }
        val deny = textAt("Deny")
        val allow = textAt("Allow")
        assertEquals(deny.top.value, allow.top.value, 0.5f)
        assertTrue("$deny, $allow", deny.right.value < allow.left.value)
        // Its padding, 14, its ring's, 6, and the row's, 6, from the end.
        assertEquals(320f - 26f, allow.right.value, 0.5f)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi", fontScale = 3.2f)
    fun atTheBiggestFontADialogsButtonsGoOneUnderTheOther() {
        picture { AndroidButtons("Don't allow", "Allow", ringed = "Allow") }
        val deny = textAt("Don't allow")
        val allow = textAt("Allow")
        assertTrue("$deny, $allow", allow.top.value >= deny.bottom.value)
        composeRule.onNodeWithText("Allow", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun aStepsNumberIsDrawnInItsCircleBesideItsWords() {
        picture { NumberedStep(2, AnnotatedString("Tap Allow.")) }
        val number = composeRule.onNodeWithText("2", useUnmergedTree = true)
        number.assertIsDisplayed()
        val digit = number.getBoundsInRoot()
        val words = textAt("Tap Allow.")
        // The circle is at least 28 wide, and the words 16 after it.
        assertTrue("$digit", digit.left.value >= 0f && digit.right.value <= 28f + 0.5f)
        assertTrue("$digit, $words", words.left.value >= 28f + 16f - 0.5f)
        // At the top of the words, not in their middle.
        assertTrue("$digit, $words", digit.top.value <= words.top.value + 6f)
    }
}
