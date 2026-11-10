package com.astrovm.crosstune

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What follows a playing song's place moves on as the song does: the bar's thin line, the player's
 * clock and its peek, the line sung, its words and its chords. Between two changes the app leaves
 * the song alone: it asks where the song is only when something shown is due to change, never every
 * frame, which kept a phone busy the whole song.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi", shadows = [HeldSystemClock::class])
class FollowsTheSongTest {

    @get:Rule
    val compose = createComposeRule()

    /** Where the song is, as the music app says. */
    private var following by mutableStateOf<Following?>(null)

    private fun pausedAt(ms: Long) = Following(PlaybackClock(ms, SystemClock.elapsedRealtime(), playing = false), "Spotify", canSeek = true, durationMs = 200_000)

    /** The song, 200 s long, playing from [ms] since now. */
    private fun playingFrom(ms: Long) = pausedAt(ms).copy(clock = PlaybackClock(ms, SystemClock.elapsedRealtime(), playing = true))

    /** Android's clock, held, so the song is only ever where the test moved it. */
    @Before
    fun holdTheClock() {
        HeldSystemClock.hold()
    }

    /**
     * Shows [screen] and lets it settle, then holds the screen's clock too, so from here both move
     * only as [passes] moves them, together.
     */
    private fun show(screen: @Composable () -> Unit) {
        compose.setContent { CrosstuneTheme { screen() } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    /** The song plays on from [ms], said as long ago as the screen takes to show it. */
    private fun playsFrom(ms: Long) {
        following = playingFrom(ms - SETTLE_MS)
        settles()
    }

    /** Long enough for a change to show: it's composed, looked at again and drawn, a frame each, and a lazy list's lines take more. */
    private fun settles() = passes(SETTLE_MS)

    /** [ms] of the song go by, a frame at a time, as on a phone: Android's clock and the screen's both move on. */
    private fun passes(ms: Long) {
        // What the test changed shows in the next frame, as it would have if the app had changed it.
        Snapshot.sendApplyNotifications()
        var left = ms
        while (left > 0) {
            val step = minOf(left, FRAME_MS)
            HeldSystemClock.moveOn(step)
            compose.mainClock.advanceTimeBy(step)
            left -= step
        }
        compose.waitForIdle()
    }

    /**
     * [ms] go by, and the app asked where the song is less than once each 10 ms: what's shown changes
     * every 100 ms at most, and a screen drawn again each frame, at 120 Hz, would ask more often.
     */
    private fun passesAskingRarely(ms: Long) {
        val before = HeldSystemClock.asked
        passes(ms)
        val asked = HeldSystemClock.asked - before
        assertTrue("Asked where the song is $asked times in $ms ms", asked < ms / 10)
    }

    private fun exists(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    // The bar at the bottom of the main screen.

    private fun bar(lengthMs: Long? = null) = show {
        CrosstuneScreen(UiState(playingNow = following?.let { NowPlaying(MusicMetadata("Song", "Band", lengthMs = lengthMs), it) }), ScreenActions())
    }

    private val playedSoFar = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo) and hasAnyAncestor(hasTestTag(NOW_PLAYING_TAG))

    /** How far along the bar's line is, or null when it has none. */
    private fun played(): Float? = compose.onAllNodes(playedSoFar, useUnmergedTree = true).fetchSemanticsNodes()
        .singleOrNull()?.config?.get(SemanticsProperties.ProgressBarRangeInfo)?.current

    private fun assertPlayed(ms: Long, of: Long) = assertEquals(ms.toFloat() / of, requireNotNull(played()) { "The bar has no line" }, 0.0001f)

    @Test
    fun theBarsLineShowsHowFarTheSongIsAndMovesOnAsItPlays() {
        following = pausedAt(0)
        bar()
        // 200 s long, so the line takes steps of 400 ms. Partway into one, it shows where that step started.
        playsFrom(100_150)
        assertPlayed(100_000, of = 200_000)
        // Into the next step, it moves on with it.
        passes(400)
        assertPlayed(100_400, of = 200_000)
        passesAskingRarely(2_000)
        assertPlayed(102_400, of = 200_000)
    }

    @Test
    fun theBarHasALineOnlyForASongOfKnownLength() {
        following = pausedAt(30_000).copy(durationMs = null)
        bar()
        assertNull(played())
    }

    @Test
    fun theBarGoesByTheSongsOwnLengthWhenTheAppDoesntSay() {
        following = pausedAt(30_000).copy(durationMs = null)
        bar(lengthMs = 120_000)
        assertPlayed(30_000, of = 120_000)
    }

    // The player.

    private fun player(lines: List<LyricLine> = emptyList()) = show {
        PlayerScreen(UiState(canFollowApps = true, linesEarlyMs = 0, player = PlayerState(MusicMetadata("Song", "Band"), null, following = following, lines = lines)), ScreenActions())
    }

    @Test
    fun thePlayersClockShowsTheSecondTheSongIsInAndTicksOnTheNext() {
        following = pausedAt(0)
        player()
        // Almost at the end of the second second, it's still that one.
        playsFrom(1_990)
        assertTrue(exists("0:01"))
        // The next starts as the song gets there.
        passes(160)
        assertTrue(exists("0:02"))
        passesAskingRarely(2_000)
        assertTrue(exists("0:04"))
    }

    @Test
    fun thePlayersClockPausedLateInASecondShowsThatSecond() {
        following = pausedAt(1_960)
        player()
        assertTrue(exists("0:01"))
    }

    private val peekLines = listOf(LyricLine(1_000, "One"), LyricLine(20_000, "Two"), LyricLine(40_000, "Three"))

    @Test
    fun thePlayersPeekMovesToTheNextLineAsItsSung() {
        following = pausedAt(0)
        player(peekLines)
        playsFrom(19_800)
        assertTrue(exists("One"))
        assertFalse(exists("Two"))
        passes(300)
        assertTrue(exists("Two"))
        passesAskingRarely(2_000)
        assertTrue(exists("Two"))
        assertFalse(exists("One"))
    }

    @Test
    fun thePlayersPeekShowsALineFromTheMomentItStarts() {
        following = pausedAt(20_000)
        player(peekLines)
        assertTrue(exists("Two"))
        assertFalse(exists("One"))
    }

    // The words.

    private val lines = listOf(
        LyricLine(1_000, "Walking down the river road", listOf(TimedWord(1_000, "Walking "), TimedWord(1_600, "down "), TimedWord(2_000, "the "), TimedWord(2_300, "river "), TimedWord(2_900, "road"))),
        LyricLine(5_000, ""),
        LyricLine(9_000, "Singing to the morning light")
    )

    private val sheet = ChordSheet(
        listOf(SheetLine(lines[0].text, listOf(Chord(0, "G"), Chord(8, "C"), Chord(16, "Em"), Chord(27, "D")))),
        site = ChordSite.ULTIMATE_GUITAR,
        url = "https://tabs.ultimate-guitar.com/tab/river-1"
    )

    private var lyricLines by mutableStateOf(lines)

    private fun words(chords: ChordsState = ChordsState()) = show {
        LyricsScreen(
            UiState(
                lyricsFor = MusicMetadata("River Road", "The Walkers"),
                lyrics = lyricLines.joinToString("\n") { it.text },
                lyricLines = lyricLines,
                following = following,
                canFollowApps = true,
                chords = chords,
                // Lit on the voice, and none ahead, so the times here are when each line and word lights.
                linesEarlyMs = 0,
                nextLineAheadMs = 0
            ),
            ScreenActions()
        )
    }

    /** The line showing [text], the one with its chords over it when it has them. */
    private fun lineOf(text: String) = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes()
        .let { nodes -> nodes.firstOrNull { it.config.contains(ChordsShown) } ?: nodes.first() }

    /** Which of the first line's chords is lit, once they're placed over it, which happens off the main thread. */
    private fun chordLit(): Int {
        fun placed() = lineOf(lines[0].text).config.contains(ChordsShown)
        compose.waitUntil(5_000) { placed() || run { settles(); placed() } }
        return lineOf(lines[0].text).config[ChordLit]
    }

    /** How many of [text]'s letters are lit as sung, before the dimmed ones; all of them when none are dimmed. */
    private fun litLetters(text: String): Int {
        val shown = lineOf(text).config[SemanticsProperties.Text].first()
        return shown.spanStyles.filter { it.item.color.alpha < 1f }.minOfOrNull { it.start } ?: shown.length
    }

    @Test
    fun theLineSungLightsAsItStartsAndItsWordsAsTheyreSung() {
        following = pausedAt(0)
        words()
        playsFrom(700)
        // Before it, it's only words.
        assertEquals(lines[0].text.length, litLetters(lines[0].text))
        passes(500)
        assertEquals("Walking ".length, litLetters(lines[0].text))
        passes(600)
        assertEquals("Walking down ".length, litLetters(lines[0].text))
        passesAskingRarely(1_000)
        assertEquals("Walking down the river ".length, litLetters(lines[0].text))
    }

    @Test
    fun theChordPlayedMovesOnAsTheLineIsSung() {
        following = pausedAt(1_700)
        words(ChordsState(on = true, sheet = sheet))
        assertEquals(1, chordLit())
        playsFrom(1_700)
        assertEquals(1, chordLit())
        assertEquals("Walking down ".length, litLetters(lines[0].text))
        passesAskingRarely(800)
        assertEquals(2, chordLit())
        assertEquals("Walking down the river ".length, litLetters(lines[0].text))
    }

    @Test
    fun wordsTimedAgainLightWithTheirNewTimesUnderChords() {
        following = pausedAt(1_700)
        words(ChordsState(on = true, sheet = sheet))
        assertEquals(1, chordLit())
        assertEquals("Walking down ".length, litLetters(lines[0].text))
        // Its words lined up to the singing again, the line still starting when it did: "down" is sung later.
        lyricLines = listOf(lines[0].copy(words = lines[0].words.map { if (it.timeMs == 1_000L) it else it.copy(timeMs = it.timeMs + 500) })) + lines.drop(1)
        settles()
        assertEquals(0, chordLit())
        assertEquals("Walking ".length, litLetters(lines[0].text))
    }

    private companion object {
        /** About a frame on a 60 Hz screen. */
        const val FRAME_MS = 16L

        /** Ten frames, which a change takes at most to show here. */
        const val SETTLE_MS = 10 * FRAME_MS
    }
}
