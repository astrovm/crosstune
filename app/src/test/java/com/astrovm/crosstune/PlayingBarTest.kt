package com.astrovm.crosstune

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
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

/**
 * The bar at the bottom with what an app plays: when it's there, and what it shows for an app that
 * names no song, e.g. a radio station, before and after its song is heard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class PlayingBarTest {

    @get:Rule
    val compose = createComposeRule()

    private val playing = NowPlaying(MusicMetadata("Other Fake Song", "Other Fake Band"), null)
    private val url = "https://open.spotify.com/track/1234"
    private val link = MusicLink(MusicService.SPOTIFY, ItemType.TRACK, url, url)

    /** Each cover the screen asked for, in turn. */
    private val covers = mutableListOf<String>()

    private fun show(state: UiState) {
        compose.setContent {
            CrosstuneTheme {
                CrosstuneScreen(
                    state,
                    ScreenActions(loadArtwork = {
                        covers += it
                        null
                    })
                )
            }
        }
        compose.waitForIdle()
    }

    private fun barShown() = compose.onAllNodesWithTag(NOW_PLAYING_TAG).fetchSemanticsNodes().isNotEmpty()

    private fun shown(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** The bar's words in turn: its name, then who and where. */
    private fun barWords() = compose.onNodeWithTag(NOW_PLAYING_TAG).fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }

    private fun described(description: String) = compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theBarStaysUnderASongShownFromALinkWhileItsLinkIsInTheBox() {
        show(UiState(playingNow = playing, result = MusicMetadata("Fake Song", "Fake Band"), link = link, linkText = url))
        assertTrue(barShown())
    }

    @Test
    fun theBarGoesWhileAnotherLinkIsTypedOverTheSongShown() {
        show(UiState(playingNow = playing, result = MusicMetadata("Fake Song", "Fake Band"), link = link, linkText = "https://open.spotify.com/track/5678"))
        assertFalse(barShown())
    }

    @Test
    fun aStationWithNoNameShowsItsAppsNameAndItsLogo() {
        show(UiState(playingUnnamed = PlayingUnnamed(Unnamed("com.example.radio", "Fake Radio", null, "https://example.com/logo.png"))))
        assertEquals(listOf("Fake Radio", ""), barWords())
        assertEquals(listOf("https://example.com/logo.png"), covers)
    }

    @Test
    fun aStationIsNamedOverItsApp() {
        show(UiState(playingUnnamed = PlayingUnnamed(Unnamed("com.example.radio", "Fake Radio", "Fake FM"))))
        assertEquals(listOf("Fake FM", "Fake Radio"), barWords())
    }

    @Test
    fun aStationsBarShowsItPausedAsItsAppHasIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val paused = Following(PlaybackClock(25_000, 0, playing = false), "Fake Radio", canSeek = false)
        show(UiState(playingUnnamed = PlayingUnnamed(Unnamed("com.example.radio", "Fake Radio", "Fake FM"), following = paused)))
        assertTrue(described(context.getString(R.string.lyrics_play)))
        assertFalse(described(context.getString(R.string.lyrics_pause)))
    }

    @Test
    fun aSongHeardOnAStationShowsItsOwnNameAndCoverOverTheStations() {
        val heard = Heard.Song(MusicMetadata("Fake Song", "Fake Band", artworkUrl = "https://example.com/cover.jpg"), "1234", 50_000, 0)
        show(UiState(playingUnnamed = PlayingUnnamed(Unnamed("com.example.radio", "Fake Radio", "Fake FM", "https://example.com/logo.png"), heard)))
        assertTrue(shown("Fake Song"))
        assertTrue(shown("Fake Band · Fake Radio"))
        assertFalse(shown("Fake FM"))
        assertEquals(listOf("https://example.com/cover.jpg"), covers)
    }
}
