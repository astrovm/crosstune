package com.astrovm.crosstune

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A name with what it's set to, and an icon with its words: beside each other where they fit, else one over the other, each drawn whole. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LabelAndValueTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(width: Dp, direction: LayoutDirection = LayoutDirection.Ltr, content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                CrosstuneTheme { Box(Modifier.width(width).testTag(ROOM)) { content() } }
            }
        }
    }

    private fun bounds(tag: String) = composeRule.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()

    private fun DpRect.overlaps(other: DpRect) = left < other.right && other.left < right && top < other.bottom && other.top < bottom

    /** Both drawn, with room of their own, inside the row and apart. */
    private fun assertDrawnApart(first: DpRect, second: DpRect) {
        val room = bounds(ROOM)
        for (it in listOf(first, second)) {
            assertTrue("$it has no size", it.right > it.left && it.bottom > it.top)
            assertTrue("$it is outside $room", it.left >= room.left - 0.5.dp && it.right <= room.right + 0.5.dp && it.top >= room.top - 0.5.dp && it.bottom <= room.bottom + 0.5.dp)
        }
        assertTrue("$first and $second overlap", !first.overlaps(second))
    }

    private fun labelAndValue(name: String, value: String, gap: Dp = 12.dp) = @androidx.compose.runtime.Composable {
        LabelAndValue(label = { Text(name, Modifier.testTag(LABEL)) }, gap = gap) { Text(value, Modifier.testTag(VALUE)) }
    }

    @Test
    fun aShortValueIsBesideItsNameAtTheRowsEndAndBothAreInTheMiddleOfIt() {
        show(300.dp, content = labelAndValue("Language", "English"))
        val name = bounds(LABEL)
        val value = bounds(VALUE)
        assertDrawnApart(name, value)
        // The value at the end, the name at the start, on one line, the gap between them at least.
        assertEquals(bounds(ROOM).right.value, value.right.value, 0.5f)
        assertEquals(bounds(ROOM).left.value, name.left.value, 0.5f)
        assertTrue(value.left - name.right >= 11.5.dp)
        assertEquals((name.top + name.bottom).value / 2, (value.top + value.bottom).value / 2, 0.5f)
    }

    @Test
    fun rightToLeftTheValueIsOnTheLeft() {
        show(300.dp, LayoutDirection.Rtl, labelAndValue("Language", "English"))
        val name = bounds(LABEL)
        val value = bounds(VALUE)
        assertDrawnApart(name, value)
        assertEquals(bounds(ROOM).left.value, value.left.value, 0.5f)
        assertEquals(bounds(ROOM).right.value, name.right.value, 0.5f)
    }

    @Test
    fun aValueThatLeavesTheNameTooLittleRoomGoesUnderItWithTheRowAsTallAsBoth() {
        show(160.dp, content = labelAndValue("Translate songs into", "Brazilian Portuguese"))
        val name = bounds(LABEL)
        val value = bounds(VALUE)
        assertDrawnApart(name, value)
        // Under it, at the same start, 2 dp below, and the row holds them both.
        assertEquals(name.left.value, value.left.value, 0.5f)
        assertEquals((name.bottom + 2.dp).value, value.top.value, 0.5f)
        assertEquals(value.bottom.value, bounds(ROOM).bottom.value, 0.5f)
    }

    @Test
    fun rightToLeftAValueUnderItsNameStartsOnTheRight() {
        show(160.dp, LayoutDirection.Rtl, labelAndValue("Translate songs into", "Brazilian Portuguese"))
        val name = bounds(LABEL)
        val value = bounds(VALUE)
        assertDrawnApart(name, value)
        assertEquals(bounds(ROOM).right.value, name.right.value, 0.5f)
        assertEquals(bounds(ROOM).right.value, value.right.value, 0.5f)
    }

    @Test
    fun anIconIsBesideItsWordsInTheMiddleOrAtTheirTop() {
        var atTop by mutableStateOf(false)
        show(300.dp) {
            LeadAndWords(lead = { Box(Modifier.size(24.dp).testTag(LABEL)) }, gap = 16.dp, top = atTop) { Text("Tap Allow.\nThen go back.", Modifier.testTag(VALUE)) }
        }
        for (top in listOf(false, true)) {
            atTop = top
            composeRule.waitForIdle()
            val icon = bounds(LABEL)
            val words = bounds(VALUE)
            assertDrawnApart(icon, words)
            assertEquals((icon.right + 16.dp).value, words.left.value, 0.5f)
            if (top) assertEquals(words.top.value, icon.top.value, 0.5f) else assertEquals((words.top + words.bottom).value / 2, (icon.top + icon.bottom).value / 2, 0.5f)
        }
    }

    @Test
    fun anIconGoesOverWordsThatWouldBreakBesideIt() {
        show(120.dp) {
            LeadAndWords(lead = { Box(Modifier.size(48.dp).testTag(LABEL)) }, gap = 16.dp) { Text("Notification", Modifier.testTag(VALUE)) }
        }
        val icon = bounds(LABEL)
        val words = bounds(VALUE)
        assertDrawnApart(icon, words)
        assertEquals(icon.left.value, words.left.value, 0.5f)
        assertEquals((icon.bottom + 2.dp).value, words.top.value, 0.5f)
        assertEquals(words.bottom.value, bounds(ROOM).bottom.value, 0.5f)
    }

    @Test
    fun askedHowWideTheyAreTheyAnswerSideBySideAtMostAndOneOverTheOtherAtLeast() {
        // A row as wide as the widest it needs, and one as narrow as the least: both drawn whole.
        var size by mutableStateOf(IntrinsicSize.Max)
        composeRule.setContent {
            CrosstuneTheme {
                Row {
                    Box(Modifier.width(size).testTag(ROOM)) {
                        LabelAndValue(label = { Text("Language", Modifier.testTag(LABEL)) }) { Text("English", Modifier.testTag(VALUE)) }
                    }
                    Box(Modifier.width(size).testTag(ICON_ROOM)) {
                        LeadAndWords(lead = { Box(Modifier.size(24.dp).testTag(ICON)) }) { Text("Tap Allow", Modifier.testTag(WORDS)) }
                    }
                }
            }
        }
        for (asked in listOf(IntrinsicSize.Max, IntrinsicSize.Min)) {
            size = asked
            composeRule.waitForIdle()
            assertDrawnApart(bounds(LABEL), bounds(VALUE))
            val icon = bounds(ICON)
            val words = bounds(WORDS)
            val room = bounds(ICON_ROOM)
            assertTrue(!icon.overlaps(words) && words.right <= room.right + 0.5.dp)
            val sideBySide = bounds(VALUE).left >= bounds(LABEL).right
            // Widest beside each other; narrowest, the value under its name and the words under their icon.
            assertEquals("$size", size == IntrinsicSize.Max, sideBySide)
            assertEquals("$size", size == IntrinsicSize.Max, words.left >= icon.right)
        }
    }

    private companion object {
        const val ROOM = "room"
        const val LABEL = "label"
        const val VALUE = "value"
        const val ICON_ROOM = "icon room"
        const val ICON = "icon"
        const val WORDS = "words"
    }
}

