package com.astrovm.crosstune

import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The screen stays awake while lyrics follow a song that's playing, and sleeps again once they don't. */
@RunWith(RobolectricTestRunner::class)
class LyricsKeepScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val song = MusicMetadata("River Road", "The Walkers")

    private val lines = listOf(
        LyricLine(1_000, "Walking down the river road"),
        LyricLine(5_000, "Singing to the morning light")
    )

    private fun state(
        following: Following? = null,
        lyrics: String = lines.joinToString("\n") { it.text },
        lyricLines: List<LyricLine> = lines,
        saved: List<SavedLine> = emptyList()
    ) = UiState(
        lyricsFor = song,
        lyrics = lyrics,
        lyricLines = lyricLines,
        following = following,
        canFollowApps = true,
        savedLines = saved
    )

    /** A song being followed, by a music app unless [app] is left out, as when Crosstune heard it. */
    private fun following(playing: Boolean, app: String? = "Spotify") =
        Following(PlaybackClock(1_700, SystemClock.elapsedRealtime(), playing = playing), app, canSeek = app != null)

    private fun screenStaysOn(): Boolean {
        composeRule.waitForIdle()
        return composeRule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
    }

    private fun show(state: UiState) {
        composeRule.setContent { CrosstuneTheme { LyricsScreen(state, ScreenActions()) } }
    }

    @Test
    fun theScreenStaysOnWhileASongPlaysAndSleepsWhenItPauses() {
        var playing by mutableStateOf(true)
        composeRule.setContent { CrosstuneTheme { LyricsScreen(state(following(playing)), ScreenActions()) } }
        assertTrue("screen slept while the song played", screenStaysOn())

        playing = false
        assertFalse("screen stayed on after the song paused", screenStaysOn())

        playing = true
        assertTrue("screen slept when the song played again", screenStaysOn())
    }

    @Test
    fun theScreenSleepsWhileAFollowedSongIsPaused() {
        show(state(following(playing = false)))
        assertFalse(screenStaysOn())
    }

    @Test
    fun theScreenSleepsWhenNothingIsFollowed() {
        show(state())
        assertFalse(screenStaysOn())
    }

    @Test
    fun theScreenStaysOnWhileWhatCrosstuneHeardIsPlaying() {
        show(state(following(playing = true, app = null)))
        assertTrue(screenStaysOn())
    }

    @Test
    fun theScreenStaysOnForWordsWithNoTimesWhileASongPlays() {
        show(state(following(playing = true), lyrics = "Walking down the river road", lyricLines = emptyList()))
        assertTrue(screenStaysOn())
    }

    @Test
    fun theScreenSleepsOnceTheWordsGoAway() {
        var shown by mutableStateOf(true)
        composeRule.setContent {
            if (shown) CrosstuneTheme { LyricsScreen(state(following(playing = true)), ScreenActions()) }
        }
        assertTrue(screenStaysOn())
        shown = false
        assertFalse("screen stayed on after the words closed", screenStaysOn())
    }

    @Test
    fun theScreenSleepsWhileSavedLinesAreOpen() {
        val saved = listOf(SavedLine("Walking down the river road", title = song.title, artist = song.artist))
        show(state(following(playing = true), saved = saved))
        assertTrue(screenStaysOn())
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.lyrics_study)).performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.lyrics_saved_lines)).performClick()
        assertFalse("screen stayed on over saved lines", screenStaysOn())
    }
}
