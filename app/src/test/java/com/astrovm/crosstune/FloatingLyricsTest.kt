package com.astrovm.crosstune

import android.app.Application
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.Modifier
import androidx.test.core.app.ApplicationProvider
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The words floating over other apps: which lines show, what helps read them, and the buttons that come out when they're tapped. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FloatingLyricsTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val song = MusicMetadata("Demo", "Band")
    private val lines = listOf(LyricLine(0, "First"), LyricLine(1_000, "Second"), LyricLine(2_000, "Third"))

    /** Paused [positionMs] into the song, so the line sung doesn't move under the test. */
    private fun at(positionMs: Long, app: String? = null) = Following(PlaybackClock(positionMs, 0, playing = false), app, false)

    private fun show(state: UiState) = compose.setContent { CrosstuneTheme { FloatingLyrics(state, Modifier) } }

    private fun actions(onPlayPause: () -> Unit = {}, onToggleListening: () -> Unit = {}, onEditorAt: (Rect?) -> Unit = {}, onLock: () -> Unit = {}) =
        FloatingActions({}, onToggleListening, onPlayPause, onLock, {}, { _, _ -> }, {}, onEditorAt)

    @Test
    fun withNothingToFollowTheSongItselfFloats() {
        show(UiState(lyricsFor = song))
        compose.onNodeWithText("Demo").assertExists()
        compose.onNodeWithText("Band").assertExists()
    }

    @Test
    fun withoutTheNextLineSetTheSongsArtistIsLeftOutToo() {
        show(UiState(lyricsFor = song, floating = FloatingOptions(nextLine = false)))
        compose.onNodeWithText("Demo").assertExists()
        compose.onAllNodesWithText("Band").assertCountEquals(0)
    }

    @Test
    fun withNoSongAndNoLinesNothingIsWritten() {
        show(UiState())
        compose.onNodeWithTag(FLOATING_LYRICS_TAG).assertExists()
    }

    @Test
    fun theLineBeingSungShowsWithTheOnesEitherSideAsSet() {
        show(UiState(lyricsFor = song, lyricLines = lines, following = at(1_500), floating = FloatingOptions(previousLine = true)))
        compose.onNodeWithText("Second").assertExists()
        compose.onNodeWithText("First").assertExists()
        compose.onNodeWithText("Third").assertExists()
    }

    @Test
    fun theLinesEitherSideAreLeftOutWhenTheyreSwitchedOff() {
        show(UiState(lyricsFor = song, lyricLines = lines, following = at(1_500), floating = FloatingOptions(previousLine = false, nextLine = false)))
        compose.onNodeWithText("Second").assertExists()
        compose.onAllNodesWithText("First").assertCountEquals(0)
        compose.onAllNodesWithText("Third").assertCountEquals(0)
    }

    @Test
    fun aBlankLineIsANoteAndTheFirstLineHasNothingBeforeIt() {
        show(UiState(lyricsFor = song, lyricLines = listOf(LyricLine(0, ""), LyricLine(1_000, "Second")), following = at(500), floating = FloatingOptions(previousLine = true)))
        compose.onNodeWithText("♪").assertExists()
        compose.onNodeWithText("Second").assertExists()
    }

    @Test
    fun beforeTheFirstLineTheSongItselfFloatsStill() {
        show(UiState(lyricsFor = song, lyricLines = listOf(LyricLine(5_000, "Late")), following = at(1_000)))
        compose.onNodeWithText("Demo").assertExists()
    }

    @Test
    fun aBandDarkEnoughNeedsNoOutlineOnTheWordsAndVisualsFloatBehindOthers() {
        show(UiState(lyricsFor = song, lyricLines = lines, following = at(1_500), floating = FloatingOptions(background = 0.8f)))
        compose.onNodeWithText("Second").assertExists()
    }

    private val night = LineReading(listOf(Ruby("夜", "よる")), "よる", "yoru")

    @Test
    fun aLineJapaneseIsHelpedByItsReadingItsLatinLettersAndItsTranslation() {
        val learning = Learning(readings = true, romanized = true, translation = true, script = Script.JAPANESE, lines = listOf(night), translations = listOf("Night"))
        show(UiState(lyricsFor = song, lyricLines = listOf(LyricLine(0, "夜")), following = at(500), learning = learning))
        compose.onNodeWithText("夜").assertExists()
        compose.onNodeWithText("yoru").assertExists()
        compose.onNodeWithText("Night").assertExists()
    }

    @Test
    fun aReadingWithNothingOverTheKanjiIsWrittenUnderTheLine() {
        val plain = LineReading(listOf(Ruby("夜")), "よる", "yoru")
        val learning = Learning(readings = true, script = Script.JAPANESE, lines = listOf(plain))
        show(UiState(lyricsFor = song, lyricLines = listOf(LyricLine(0, "夜")), following = at(500), learning = learning))
        compose.onNodeWithText("よる").assertExists()
        // Not asked for, no Latin letters.
        compose.onAllNodesWithText("yoru").assertCountEquals(0)
    }

    @Test
    fun whatReadsTheSameAsTheLineIsntRepeatedAndALineWithNoHelpHasNone() {
        val same = LineReading(listOf(Ruby("Hi")), "Hi", "Hi")
        val learning = Learning(readings = true, romanized = true, script = Script.JAPANESE, lines = listOf(same, LineReading(listOf(Ruby("Yo")), "Yo", " ")))
        show(UiState(lyricsFor = song, lyricLines = listOf(LyricLine(0, "Hi"), LyricLine(1_000, "Yo")), following = at(500), learning = learning))
        compose.onAllNodesWithText("Hi").assertCountEquals(1)
    }

    @Test
    fun wordsShownInLatinLettersAreTheOnesShownInsteadOfTheLine() {
        val learning = Learning(script = Script.JAPANESE, wordsAs = WordsAs.LATIN, lines = listOf(night))
        show(UiState(lyricsFor = song, lyricLines = listOf(LyricLine(0, "夜")), following = at(500), learning = learning))
        compose.onNodeWithText("yoru").assertExists()
        compose.onAllNodesWithText("夜").assertCountEquals(0)
    }

    @Test
    fun aTranslationAndAReadingEachHaveTheirOwnSize() {
        val learning = Learning(readings = true, translation = true, script = Script.JAPANESE, lines = listOf(LineReading(listOf(Ruby("夜")), "よる", "yoru")),
            translations = listOf("Night"), readingScale = 1f, translationScale = 1.5f)
        show(UiState(lyricsFor = song, lyricLines = listOf(LyricLine(0, "夜")), following = at(500), learning = learning))
        compose.onNodeWithText("よる").assertExists()
        compose.onNodeWithText("Night").assertExists()
    }

    private var controls by mutableStateOf(true)

    private fun over(state: UiState, actions: FloatingActions) = compose.setContent {
        CrosstuneTheme { FloatingOverApps(state, actions, controls, { controls = it }) }
    }

    private fun string(id: Int) = app.getString(id)

    @Test
    fun tappingTheWordsBringsOutTheButtonsAndTappingThemAgainPutsThemAway() {
        controls = false
        over(UiState(lyricsFor = song), actions())
        compose.onAllNodesWithContentDescription(string(R.string.floating_close)).assertCountEquals(0)
        compose.onNodeWithTag(FLOATING_LYRICS_TAG).performClick()
        assertTrue(controls)
        compose.onNodeWithContentDescription(string(R.string.floating_close)).assertExists()
        compose.onNodeWithTag(FLOATING_LYRICS_TAG).performClick()
        assertTrue(!controls)
    }

    @Test
    fun withNoAppFollowedTheMicrophoneListensAlongAndSaysSoWhileItDoes() {
        var toggled = 0
        over(UiState(lyricsFor = song), actions(onToggleListening = { toggled++ }))
        compose.onNodeWithText(string(R.string.lyrics_listen_along)).assertExists()
        compose.onNodeWithContentDescription(string(R.string.lyrics_listen_along)).performClick()
        assertEquals(1, toggled)
    }

    @Test
    fun whileListeningAlongTheButtonStopsIt() {
        over(UiState(lyricsFor = song, listeningAlong = true, microphone = MicAccess.ALLOWED), actions())
        compose.onNodeWithText(string(R.string.listening_text)).assertExists()
        compose.onNodeWithContentDescription(string(R.string.lyrics_stop_listening)).assertExists()
    }

    @Test
    fun anAppFollowedIsPlayedAndPausedFromTheButtons() {
        var toggled = 0
        val playing = Following(PlaybackClock(0, 0, playing = true), "Spotify", false)
        over(UiState(lyricsFor = song, following = playing), actions(onPlayPause = { toggled++ }))
        compose.onNodeWithContentDescription(string(R.string.lyrics_pause)).performClick()
        assertEquals(1, toggled)
        compose.onAllNodesWithText(string(R.string.lyrics_listen_along)).assertCountEquals(0)
    }

    @Test
    fun aPausedAppIsPlayedFromTheButtons() {
        over(UiState(lyricsFor = song, following = at(0, app = "Spotify")), actions())
        compose.onNodeWithContentDescription(string(R.string.lyrics_play)).assertExists()
    }

    @Test
    fun aSongFollowedWithoutAnAppStillOffersListeningAlongButNoCaption() {
        over(UiState(lyricsFor = song, following = at(0)), actions())
        compose.onAllNodesWithText(string(R.string.lyrics_listen_along)).assertCountEquals(0)
        compose.onNodeWithContentDescription(string(R.string.lyrics_listen_along)).assertExists()
    }

    @Test
    fun theSettingsSayWhereTheirSlidersAreAndStopSayingSoWhenDone() {
        val editors = mutableListOf<Rect?>()
        var locked = 0
        over(UiState(lyricsFor = song), actions(onEditorAt = { editors += it }, onLock = { locked++ }))
        assertTrue(editors.isEmpty())
        compose.onNodeWithContentDescription(string(R.string.floating_customize)).performClick()
        compose.waitForIdle()
        assertTrue(editors.last() != null)
        compose.onNodeWithText(string(R.string.floating_lock)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, locked)
        assertTrue(!controls)
        // Gone with the settings, so touches there move the words again.
        assertNull(editors.last())
    }

    @Test
    fun whenTheControlsGoAwayTheSettingsDoToo() {
        val editors = mutableListOf<Rect?>()
        over(UiState(lyricsFor = song), actions(onEditorAt = { editors += it }))
        compose.onNodeWithContentDescription(string(R.string.floating_customize)).performClick()
        compose.waitForIdle()
        controls = false
        compose.waitForIdle()
        assertNull(editors.last())
        controls = true
        compose.waitForIdle()
        compose.onNodeWithContentDescription(string(R.string.floating_customize)).assertExists()
    }
}
