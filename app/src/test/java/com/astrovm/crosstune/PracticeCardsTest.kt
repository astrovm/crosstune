package com.astrovm.crosstune

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The cards of a practice session on their own: what each ask does, and does nothing about, when there's nothing to act on. */
@RunWith(RobolectricTestRunner::class)
class PracticeCardsTest {
    private val screen = mutableStateOf(UiState())
    private val playback = FakePlayback()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = SavedLinesStore(ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("practice-cards", Context.MODE_PRIVATE))

    @After
    fun stop() = scope.cancel()

    private fun cards(source: PlaybackSource? = playback) =
        PracticeCards(screen, scope, source, ExactMatcher(FakeSpotify().client(), "AR"), store, { 1_000_000L }, { System.currentTimeMillis() })

    private fun line(text: String = "Night sky", start: Long? = 10_000, lead: Long = 0, type: String = READ_TYPE) =
        SavedLine(text, translation = "Cielo", title = "Demo", artist = "Band", startMs = start, endMs = start?.plus(5_000), leadMs = lead, cardType = type)

    private fun keep(vararg lines: SavedLine, types: List<CardType> = DEFAULT_CARD_TYPES) {
        screen.value = screen.value.copy(savedLines = store.addAll(lines.toList()), cardTypes = types)
    }

    private val practice get() = screen.value.practice

    private fun waitFor(what: String, until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!until()) {
            check(System.currentTimeMillis() < deadline) { "Never got to: $what" }
            Thread.sleep(20)
        }
    }

    /** The music app playing the demo song from just before the line, on a clock 20 times as fast, so the line is over in a moment. */
    private fun playing(video: Boolean = false, canSeek: Boolean = true) {
        playback.access = true
        playback.playing.value = Following(PlaybackClock(10_000, System.currentTimeMillis(), 20f), "Spotify", canSeek = canSeek, video = video)
    }

    @Test
    fun asksThatHaveNothingToActOnDoNothing() {
        val cards = cards()
        // No practice: nothing turns, plays, reads, grades, stops or goes back.
        cards.replay()
        cards.turn()
        cards.readAs(listOf("x"))
        cards.grade(Grade.GOOD)
        cards.stop()
        cards.backToBefore()
        assertNull(practice)
        assertTrue(playback.plays.isEmpty())

        // A practice with no cards left shows nothing, and has nothing to read, grade or replay.
        cards.start(null, before = null)
        assertEquals(0, practice!!.total)
        cards.replay()
        cards.readAs(listOf("x"))
        cards.turn()
        cards.grade(Grade.GOOD)
        assertEquals(0, practice!!.done)
        assertTrue(practice!!.turned)
        // A card turned already isn't turned again.
        cards.turn()
        assertTrue(playback.plays.isEmpty())
    }

    @Test
    fun withoutAMusicAppACardHasNoSoundAndIsStillPractised() {
        keep(line())
        val cards = cards(source = null)
        cards.start(null, before = null)
        assertEquals(LineSound.NONE, practice!!.sound)
        // There's nothing to play again, nor to turn the sound on for.
        cards.replay()
        cards.turn()
        assertTrue(practice!!.turned)
        // Done, there's no app to go back to, so practice stays as it is until stopped.
        cards.stop()
        keep(line("Another"))
        cards.start(null, before = Before(MusicMetadata("Before", "Band"), 4_000, "Spotify"))
        cards.backToBefore()
        assertEquals("Another", practice!!.line!!.text)
        cards.stop()
        assertNull(practice)
    }

    @Test
    fun anAppWithNoAccessLeavesTheCardSilent() {
        keep(line())
        cards().start(null, before = null)
        assertEquals(LineSound.NONE, practice!!.sound)
        assertTrue(playback.seeks.isEmpty())
    }

    @Test
    fun aLineNotTimedIsNotPlayed() {
        keep(line(start = null))
        playing()
        cards().start(null, before = null)
        assertEquals(LineSound.NONE, practice!!.sound)
        assertTrue(playback.seeks.isEmpty())
    }

    @Test
    fun theLineIsGotToWithoutPlayingAndPlayedWhenAskedAgain() {
        keep(line())
        playing()
        val cards = cards()
        cards.start(null, before = null)
        waitFor("the line is ready") { practice?.sound == LineSound.PLAYED }
        // Read it asks about the words, so it was only moved to.
        assertEquals(listOf(10_000L), playback.seeks)
        assertEquals(listOf(false), playback.plays)

        playing()
        cards.replay()
        waitFor("the line was played and paused") { playback.plays == listOf(false, true, false) && practice?.sound == LineSound.PLAYED }
        assertEquals(listOf(10_000L, 10_000L), playback.seeks)
    }

    @Test
    fun aVideoIsPlayedFromWhereItHadTheSong() {
        keep(line(lead = 2_000))
        playing(video = true)
        val cards = cards()
        cards.start(null, before = null)
        waitFor("the line is ready") { practice?.sound == LineSound.PLAYED }
        assertEquals(listOf(12_000L), playback.seeks)
        // An app that isn't a video has no lead to add.
        playback.seeks.clear()
        playing()
        cards.replay()
        waitFor("it moved again") { playback.seeks.isNotEmpty() }
        assertEquals(listOf(10_000L), playback.seeks)
    }

    @Test
    fun aCardTurnedWhileTheAppIsStillGettingThereIsPlayedOnArrival() {
        keep(line())
        // The app is slow to answer: it can't be moved around yet.
        playing(canSeek = false)
        val cards = cards()
        cards.start(null, before = null)
        waitFor("it's loading") { practice?.sound == LineSound.LOADING }
        cards.turn()
        assertTrue(practice!!.turned)
        playing()
        waitFor("it played the line once there") { playback.plays.take(2) == listOf(true, false) && practice?.sound == LineSound.PLAYED }
    }

    @Test
    fun turningACardWhoseBackHasItsSoundPlaysTheLine() {
        keep(line())
        playing()
        val cards = cards()
        cards.start(null, before = null)
        waitFor("the line is ready") { practice?.sound == LineSound.PLAYED }
        playing()
        cards.turn()
        waitFor("it plays on the back") { playback.plays == listOf(false, true, false) && practice?.sound == LineSound.PLAYED }
    }

    @Test
    fun turningACardWhoseBackIsSilentDoesNotPlayIt() {
        val silent = READ_IT.copy(options = CardOptions(back = CardSide(readings = true, latin = true, translation = true, sound = false)))
        keep(line(), types = listOf(silent, HEAR_IT, TRANSLATE_IT))
        playing()
        val cards = cards()
        cards.start(null, before = null)
        waitFor("the line is ready") { practice?.sound == LineSound.PLAYED }
        cards.turn()
        assertTrue(practice!!.turned)
        assertEquals(listOf(false), playback.plays)
    }

    @Test
    fun aCardOfAKindGoneIsAskedLikeReadIt() {
        // Its kind is no longer there, so the line is asked as the words, and only gone to.
        keep(line(type = "gone"))
        playing()
        cards().start(null, before = null)
        waitFor("the line is ready") { practice?.sound == LineSound.PLAYED }
        assertEquals(listOf(false), playback.plays)
    }

    @Test
    fun aCardOfHearItPlaysAtOnce() {
        val hear = HEAR_IT.copy(on = true)
        keep(line(), types = listOf(READ_IT.copy(on = false), hear, TRANSLATE_IT))
        playing()
        cards().start(null, before = null)
        waitFor("it played in front") { playback.plays == listOf(true, false) && practice?.sound == LineSound.PLAYED }
    }

    @Test
    fun aSongThatNeverComesUpLeavesTheCardSilent() {
        keep(line())
        playback.access = true
        playback.canPlaySongs = false
        cards().start(null, before = null)
        assertEquals(LineSound.LOADING, practice!!.sound)
        waitFor("it gave up") { practice?.sound == LineSound.NONE }
        assertTrue(playback.seeks.isEmpty())
    }

    @Test
    fun stoppingWhileTheLineLoadsPausesTheSong() {
        keep(line())
        playback.access = true
        playback.canPlaySongs = false
        val cards = cards()
        cards.start(null, before = null)
        cards.stop()
        assertNull(practice)
        assertEquals(listOf(false), playback.plays)
        // Stopped, a card that's no longer shown isn't touched by the sound it was after.
        Thread.sleep(600)
        assertNull(practice)
    }

    @Test
    fun stoppingACardThatIsSilentPausesNothing() {
        keep(line(start = null))
        val cards = cards()
        cards.start(null, before = null)
        cards.stop()
        assertNull(practice)
        assertTrue(playback.plays.isEmpty())
    }

    @Test
    fun gradingGoesOnAndAMissedCardComesBack() {
        keep(line("One"), line("Two"))
        val cards = cards(source = null)
        cards.start(null, before = null)
        val first = practice!!.line!!.text
        // A card not turned can't be graded.
        cards.grade(Grade.GOOD)
        assertEquals(0, practice!!.done)
        cards.turn()
        cards.grade(Grade.AGAIN)
        assertEquals(1, practice!!.missed)
        assertEquals(2, practice!!.queue.size)
        assertFalse(practice!!.turned)
        cards.turn()
        cards.grade(Grade.GOOD)
        assertEquals(1, practice!!.done)
        assertEquals(first, practice!!.line!!.text)
        cards.turn()
        cards.grade(Grade.EASY)
        // The last one graded, no card is left.
        assertNull(practice!!.line)
        assertEquals(2, practice!!.done)
        assertEquals(first, practice!!.lastSong!!.text)
    }

    @Test
    fun aSongIsPractisedWhenNothingIsDue() {
        keep(line("Due"), line("Not due").copy(title = "Other"))
        screen.value = screen.value.copy(savedLines = store.practised(store.load().first { it.text == "Due" }, Grade.EASY, 1_000_000L))
        val cards = cards(source = null)
        cards.start(MusicMetadata("Demo", "Band"), before = null)
        assertEquals(listOf("Due"), practice!!.queue.map { it.text })
    }

    @Test
    fun aReadingPutRightIsKeptAndOneThatChangesNothingIsNot() {
        val kanji = line("流れる星").copy(
            reading = "ながれるほし", romanized = "nagareru hoshi",
            ruby = listOf(Ruby("流", "なが"), Ruby("れる"), Ruby("星", "ほし"))
        )
        keep(kanji)
        val cards = cards(source = null)
        cards.start(null, before = null)
        val kept = screen.value.savedLines
        // Read as it is already: nothing changes.
        cards.readAs(listOf("なが", "ほし"))
        assertEquals(kept, screen.value.savedLines)
        assertFalse(practice!!.line!!.readByHand)

        cards.readAs(listOf("りゅう", "ほし"))
        assertTrue(practice!!.line!!.readByHand)
        assertTrue(practice!!.line!!.reading!!.startsWith("りゅう"))
        assertTrue(screen.value.savedLines.single().readByHand)
    }

    @Test
    fun backToBeforeMovesTheMusicAppBackToWhereItWasAndClosesPractice() {
        keep(line())
        playing()
        val cards = cards()
        cards.start(null, before = Before(MusicMetadata("Demo", "Band"), 4_000, "Spotify"))
        waitFor("the line is ready") { practice?.sound == LineSound.PLAYED }
        playback.seeks.clear()
        playback.plays.clear()
        cards.backToBefore()
        assertNull(practice)
        waitFor("it went back") { playback.seeks == listOf(4_000L) && playback.plays == listOf(true) }
    }

    @Test
    fun backToBeforeWithNothingToGoBackToLeavesPracticeAlone() {
        keep(line())
        val cards = cards()
        cards.start(null, before = null)
        cards.backToBefore()
        assertEquals("Night sky", practice!!.line!!.text)
    }

    @Test
    fun aKindOfCardTakenAwayMeanwhileIsTurnedLikeReadIt() {
        keep(line())
        playing()
        val cards = cards()
        cards.start(null, before = null)
        waitFor("the line is ready") { practice?.sound == LineSound.PLAYED }
        screen.value = screen.value.copy(cardTypes = emptyList())
        playing()
        cards.turn()
        waitFor("it plays on the back") { playback.plays == listOf(false, true, false) && practice?.sound == LineSound.PLAYED }
    }

    @Test
    fun aSongWithCardsDueIsPractisedWithOnlyThose() {
        keep(line("Due"), line("Not due"))
        screen.value = screen.value.copy(savedLines = store.practised(store.load().first { it.text == "Not due" }, Grade.EASY, 1_000_000L))
        cards(source = null).start(MusicMetadata("Demo", "Band"), before = null)
        assertEquals(listOf("Due"), practice!!.queue.map { it.text })
    }

    @Test
    fun readingOneLineRightLeavesTheOthersOfTheSessionAsTheyAre() {
        val kanji = line("流れる星").copy(reading = "ながれるほし", romanized = "nagareru hoshi", ruby = listOf(Ruby("流", "なが"), Ruby("れる"), Ruby("星", "ほし")))
        keep(kanji, line("Plain"))
        val cards = cards(source = null)
        cards.start(MusicMetadata("Demo", "Band"), before = null)
        cards.readAs(listOf("りゅう", "ほし"))
        assertEquals(listOf("流れる星", "Plain"), practice!!.queue.map { it.text }.sortedByDescending { it == "流れる星" })
        assertFalse(practice!!.queue.first { it.text == "Plain" }.readByHand)
    }

    @Test
    fun theLineIsWaitedForWhileTheAppIsStillOnItsWayToIt() {
        keep(line())
        playback.access = true
        // Ten times as fast, from the start: it's in the line's reach in under a second, and out of it in under two.
        playback.playing.value = Following(PlaybackClock(0, System.currentTimeMillis(), 10f), "Spotify", canSeek = true)
        cards().start(null, before = null)
        waitFor("the line is ready") { practice?.sound == LineSound.PLAYED }
        assertEquals(listOf(10_000L), playback.seeks)
    }

    @Test
    fun theLinePlayingIsPausedWhenTheAppGoesAway() {
        keep(line())
        // Time stands still, inside the line: it never ends by itself.
        playback.access = true
        playback.playing.value = Following(PlaybackClock(12_000, System.currentTimeMillis(), 0f), "Spotify", canSeek = true)
        val cards = cards()
        cards.start(null, before = null)
        waitFor("it's ready") { practice?.sound == LineSound.PLAYED }
        cards.replay()
        waitFor("it plays") { practice?.sound == LineSound.PLAYING }
        playback.playing.value = null
        waitFor("it was paused") { practice?.sound == LineSound.PLAYED }
        assertEquals(listOf(false, true, false), playback.plays)
    }

    @Test
    fun songLinesNeverTakeABlankWordForOneWorthKnowing() {
        val lines = songLines(listOf("Hello there", "hello there", "Other"), listOf(" ", "xyz", "other"))
        assertEquals(listOf(2, 1), lines.map { it.times })
        assertEquals(listOf(false, true), lines.map { it.newWords })
    }
}
