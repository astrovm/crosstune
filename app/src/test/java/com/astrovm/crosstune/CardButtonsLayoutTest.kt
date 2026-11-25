package com.astrovm.crosstune

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/**
 * Where a song card's main button and its icons go: beside it in a row, or under it in the middle when its word has no
 * room beside them, and mirrored in a language read right to left. The screen is tall, so nothing scrolls between reads.
 * Whether the word fits beside the icons, in every language and at the biggest fonts, is [CardToolsFitTest]'s.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardButtonsLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun string(id: Int, vararg args: Any) = ApplicationProvider.getApplicationContext<Context>().getString(id, *args)

    private class Card(val width: Dp, val play: DpRect, val search: DpRect, val open: DpRect)

    private fun card(qualifiers: String, fontScale: Float, rtl: Boolean = false): Card {
        RuntimeEnvironment.setQualifiers("$qualifiers-h3000dp")
        RuntimeEnvironment.setFontScale(fontScale)
        val state = UiState(result = MusicMetadata("Song", "Band", url = "https://open.spotify.com/track/1"), installed = setOf(MusicService.YOUTUBE_MUSIC))
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                CrosstuneTheme { CrosstuneScreen(state, ScreenActions()) }
            }
        }
        val app = string(R.string.target_youtube_music)
        fun at(description: String) = composeRule.onNode(hasContentDescription(description)).getBoundsInRoot()
        val width = qualifiers.substringAfter("-w").substringBefore("dp").toFloat().dp
        val card = Card(width, at(string(R.string.play_in_background, app)), at(string(R.string.search_in_destination, app)), at(string(R.string.open_in_youtube_music)))
        // Each icon is a whole touch target, whichever form the card takes.
        for (icon in listOf(card.search, card.open)) {
            assertDp("an icon's width", 56.dp, icon.right - icon.left)
            assertDp("an icon's height", 56.dp, icon.bottom - icon.top)
        }
        return card
    }

    private fun assertDp(what: String, expected: Dp, actual: Dp) = assertEquals(what, expected.value, actual.value, 0.5f)

    private fun middle(rect: DpRect) = (rect.top + rect.bottom) / 2

    /** Search then Open after Play, in reading order, the last one at the card's end, each on Play's middle. */
    private fun assertBeside(card: Card, rtl: Boolean) {
        val start = if (rtl) card.width - card.play.right else card.play.left
        assertDp("Play starts the row", 16.dp, start)
        val (first, second) = if (rtl) card.play.left - card.search.right to card.search.left - card.open.right else card.search.left - card.play.right to card.open.left - card.search.right
        assertDp("gap after Play", 12.dp, first)
        assertDp("gap after Search", 12.dp, second)
        assertDp("Open ends the row", 16.dp, if (rtl) card.open.left else card.width - card.open.right)
        assertDp("Search on Play's middle", middle(card.play), middle(card.search))
        assertDp("Open on Play's middle", middle(card.play), middle(card.open))
    }

    /** Play the whole width, and Search then Open under it in the middle. */
    private fun assertUnder(card: Card, rtl: Boolean) {
        assertDp("Play's start", 16.dp, card.play.left)
        assertDp("Play's end", card.width - 16.dp, card.play.right)
        assertDp("Search under Play", card.play.bottom + 8.dp, card.search.top)
        assertDp("Open beside Search", card.search.top, card.open.top)
        val (left, right) = if (rtl) card.open to card.search else card.search to card.open
        assertDp("gap between the icons", 12.dp, right.left - left.right)
        assertDp("icons in the middle", (card.play.left + card.play.right) / 2, (left.left + right.right) / 2)
    }

    @Test
    fun theIconsSitBesidePlayWhereItsWordFits() = assertBeside(card("en-w411dp", 1f), rtl = false)

    @Test
    fun rightToLeftTheRowIsMirrored() = assertBeside(card("en-w411dp", 1f, rtl = true), rtl = true)

    @Test
    fun aTallerButtonKeepsTheIconsOnItsMiddle() {
        val card = card("en-w600dp", 3.2f)
        // At this font, Play is taller than the icons.
        assertTrue(card.play.bottom - card.play.top > 56.dp)
        assertBeside(card, rtl = false)
    }

    @Test
    fun theIconsGoUnderPlayWhereItsWordHasNoRoomBeside() = assertUnder(card("de-w320dp", 2f), rtl = false)

    @Test
    fun rightToLeftTheIconsUnderAreMirrored() = assertUnder(card("de-w320dp", 2f, rtl = true), rtl = true)
}
