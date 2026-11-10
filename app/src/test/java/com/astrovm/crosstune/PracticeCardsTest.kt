package com.astrovm.crosstune

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/** The cards of a practice session on their own: what each ask does, and does nothing about, when there's nothing to act on. */
@RunWith(RobolectricTestRunner::class)
class PracticeCardsTest {
    private val screen = mutableStateOf(UiState())
    private val playback = FakePlayback().apply { obeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = SavedLinesStore(ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("practice-cards", Context.MODE_PRIVATE))

    @After
    fun stop() = scope.cancel()

    private val sites = FakeSpotify()

    private val player = FakeLinePlayer(ApplicationProvider.getApplicationContext())

    private var wall = 1_000_000L

    /** What the phone has seen each music app do. */
    private val traits = AppTraitsStore(ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("practice-traits", Context.MODE_PRIVATE).also { it.edit().clear().commit() }) { wall }

    private fun cards(source: PlaybackSource? = playback, songLoadMs: Long = MainViewModel.LOAD_SONG_MS, player: FakeLinePlayer? = this.player) =
        PracticeCards(screen, scope, source, ExactMatcher(sites.client(), "AR"), store, traits, { 1_000_000L }, { System.currentTimeMillis() }, songLoadMs = songLoadMs, newPlayer = player?.let { made -> { made } })

    /** YouTube Music having the demo song, 3:20 long, as its video "demo0000000". */
    private fun youTubeHasTheSong() {
        val before = sites.handler
        sites.handler = { request ->
            if (request.url.host == "music.youtube.com") FakeSpotify.html(request, """{"contents":[{"musicResponsiveListItemRenderer":{
                "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Demo"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Band • Album • 3:20"}]}}}],
                "playlistItemData":{"videoId":"demo0000000"}}}]}""")
            else before(request)
        }
    }

    /** Spotify having the demo song, under [SPOTIFY_ID]. */
    private fun spotifyHasTheSong() {
        sites.handler = { request ->
            when {
                request.url.host == "labs.api.listenbrainz.org" -> FakeSpotify.html(request, """[{"spotify_track_ids":["$SPOTIFY_ID"]}]""")
                request.url.encodedPath == "/track/$SPOTIFY_ID" -> FakeSpotify.html(request, """<meta property="og:title" content="Demo"/><meta property="og:description" content="Band · Album · Song · 2000"/><meta name="music:duration" content="200"/>""")
                else -> FakeSpotify.html(request, "{}", code = 404)
            }
        }
    }

    private val keptFromSpotify get() = line().copy(app = MusicService.SPOTIFY.packageName)

    private fun line(text: String = "Night sky", start: Long? = 10_000, lead: Long = 0, type: String = READ_TYPE) =
        SavedLine(text, translation = "Cielo", title = "Demo", artist = "Band", startMs = start, endMs = start?.plus(5_000), leadMs = lead, cardType = type)

    private fun keep(vararg lines: SavedLine, types: List<CardType> = DEFAULT_CARD_TYPES) {
        screen.value = screen.value.copy(savedLines = store.addAll(lines.toList()), cardTypes = types)
    }

    private val practice get() = screen.value.practice

    /** Waits for [until], as long as a busy machine may take: what's being waited for is exact, and how soon it came is checked apart, where it matters. */
    private fun waitFor(what: String, seconds: Long = 60, until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + seconds * 1_000
        while (!until()) {
            check(System.currentTimeMillis() < deadline) { "Never got to: $what" }
            Thread.sleep(20)
        }
    }

    /** The music app playing the demo song from just before the line, on a clock 20 times as fast, so the line is over in a moment. */
    private fun playing(video: Boolean = false, canSeek: Boolean = true, playing: Boolean = true) {
        playback.access = true
        playback.playing.value = Following(PlaybackClock(10_000, System.currentTimeMillis(), 20f, playing = playing), "Spotify", canSeek = canSeek, appPackage = MusicService.SPOTIFY.packageName, video = video, durationMs = 200_000)
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
        assertFalse(playback.quiet)
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
    fun backToBeforeInAnAppThatHasToBeAskedForItLetsItPlay() {
        keep(line())
        val cards = cards()
        cards.start(null, before = Before(MusicMetadata("Before", "Band"), 4_000, "Spotify"))
        cards.backToBefore()
        waitFor("it's asked for the song") { playback.songsAsked.isNotEmpty() }
        // It starts the song it was asked for: that's what was playing, so it isn't paused as a card's line would be.
        playback.playing.value = Following(PlaybackClock(0, System.currentTimeMillis()), "Spotify", canSeek = true)
        waitFor("it went back") { playback.seeks == listOf(4_000L) && playback.plays == listOf(true) }
        Thread.sleep(200)
        assertEquals(listOf(true), playback.plays.toList())
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
    fun aSongAlreadyPlayingThatCantMoveIsPlayedInThePlayerAndPausedInTheApp() {
        keep(line())
        youTubeHasTheSong()
        // Already this song, and the session offers no seek, as Spotify's free plan. It isn't opened or asked for again.
        playing(canSeek = false)
        val cards = cards()
        cards.start(null, before = null)
        waitFor("the player is on the line") { player.shown.isNotEmpty() }
        assertEquals(Shown("demo0000000", 10_000, 15_000 + MainViewModel.LINE_TAIL_MS, 200_000, play = false), player.shown.single())
        assertEquals("demo0000000", practice?.video)
        assertEquals(listOf(false), playback.plays)
        assertTrue(playback.seeks.isEmpty())
        assertTrue(playback.opened.isEmpty())
        assertTrue(playback.songsAsked.isEmpty())
        assertEquals(0, playback.cameBack)
        // Turned, the line plays, and the player says when it ends.
        cards.turn()
        waitFor("the player plays it") { player.shown.size == 2 }
        assertTrue(player.shown.last().play)
        assertEquals(LineSound.PLAYING, practice?.sound)
        player.end(LineEnd.ENDED)
        assertEquals(LineSound.PLAYED, practice?.sound)
        // Asked again, it is played again; asked while it plays, it stops.
        cards.replay()
        waitFor("the player plays it again") { player.shown.size == 3 }
        assertEquals(LineSound.PLAYING, practice?.sound)
        val paused = player.pauses
        cards.replay()
        assertEquals(LineSound.PLAYED, practice?.sound)
        assertEquals(paused + 1, player.pauses)
        // The app is never asked to play again.
        assertEquals(listOf(false), playback.plays)
    }

    @Test
    fun aSongPausedThatCantMoveIsPlayedInThePlayerToo() {
        keep(line())
        youTubeHasTheSong()
        playing(canSeek = false, playing = false)
        val cards = cards()
        cards.start(null, before = null)
        waitFor("the player is on the line") { player.shown.isNotEmpty() }
        // Nothing played, so nothing is paused.
        assertTrue(playback.plays.isEmpty())
        assertTrue(playback.seeks.isEmpty())
        assertTrue(playback.opened.isEmpty())
        assertEquals("demo0000000", practice?.video)
    }

    @Test
    fun aHearItCardPlaysInThePlayerAtOnce() {
        keep(line(), types = listOf(READ_IT.copy(on = false), HEAR_IT.copy(on = true), TRANSLATE_IT))
        youTubeHasTheSong()
        playing(canSeek = false)
        cards().start(null, before = null)
        waitFor("the player plays it") { player.shown.isNotEmpty() }
        assertTrue(player.shown.single().play)
        assertEquals(LineSound.PLAYING, practice?.sound)
    }

    @Test
    fun aSongNoAppHasIsPlayedInThePlayer() {
        keep(line())
        youTubeHasTheSong()
        // No app has it, and asking by name finds nothing.
        playback.access = true
        playback.canPlaySongs = false
        cards(songLoadMs = 300).start(null, before = null)
        waitFor("the player is on the line") { player.shown.isNotEmpty() }
        assertEquals("demo0000000", practice?.video)
    }

    @Test
    fun aVideoThatIsNotTheSongHasNoSoundAndNoPlayer() {
        keep(line())
        youTubeHasTheSong()
        playing(canSeek = false)
        val cards = cards()
        cards.start(null, before = null)
        waitFor("the player is on the line") { player.shown.isNotEmpty() }
        // The player finds the video isn't as long as the song, or can't load it.
        player.end(LineEnd.FAILED)
        assertEquals(LineSound.NONE, practice?.sound)
        assertNull(practice?.video)
        cards.replay()
        assertEquals(1, player.shown.size)
    }

    @Test
    fun aSongYouTubeDoesNotHaveHasNoSoundAndNoPlayer() {
        keep(line())
        playing(canSeek = false)
        cards().start(null, before = null)
        waitFor("no sound") { practice?.sound == LineSound.NONE }
        assertTrue(player.shown.isEmpty())
        assertNull(practice?.video)
    }

    @Test
    fun withNoPlayerToMakeThereIsNoSound() {
        keep(line())
        youTubeHasTheSong()
        playing(canSeek = false)
        cards(player = null).start(null, before = null)
        waitFor("no sound") { practice?.sound == LineSound.NONE }
        assertNull(cards(player = null).playerView())
        // With one, the card shows its picture.
        assertEquals(player.view(), cards().playerView())
    }

    @Test
    fun aVideoOfNoKnownLengthIsOnlyTakenFromYouTubeMusic() {
        keep(line())
        // Only a plain video, which can't be told from another song's without the song's length.
        sites.handler = { request ->
            if (request.url.host == "www.youtube.com") FakeSpotify.html(request, """{"contents":{"twoColumnSearchResultsRenderer":{}}}""") else FakeSpotify.html(request, "{}")
        }
        playing(canSeek = false)
        cards().start(null, before = null)
        waitFor("no sound") { practice?.sound == LineSound.NONE }
        assertTrue(player.shown.isEmpty())
    }

    @Test
    fun theCardsPlayerIsPausedWhenTheCardTurnsIsGradedOrPracticeStops() {
        keep(line("One"), line("Two"))
        youTubeHasTheSong()
        playing(canSeek = false)
        val cards = cards()
        cards.start(null, before = null)
        waitFor("the player is on the line") { player.shown.isNotEmpty() }
        val first = player.pauses
        cards.turn()
        waitFor("it plays") { player.shown.size == 2 }
        cards.grade(Grade.GOOD)
        assertTrue(player.pauses > first)
        waitFor("the next card's line") { player.shown.size == 3 }
        val graded = player.pauses
        cards.stop()
        assertTrue(player.pauses > graded)
        assertNull(practice)
        // Spotify is paused, never resumed.
        assertTrue(playback.plays.isNotEmpty() && playback.plays.none { it })
    }

    @Test
    fun theLineInThePlayerStopsWhenCrosstuneIsOutOfSight() {
        keep(line(), types = listOf(READ_IT.copy(on = false), HEAR_IT.copy(on = true), TRANSLATE_IT))
        youTubeHasTheSong()
        playing(canSeek = false)
        val cards = cards()
        cards.start(null, before = null)
        waitFor("the player plays") { practice?.sound == LineSound.PLAYING }
        val paused = player.pauses
        cards.background()
        assertEquals(LineSound.PLAYED, practice?.sound)
        assertEquals(paused + 1, player.pauses)
        // Nothing playing, nothing to stop.
        cards.background()
        assertEquals(paused + 1, player.pauses)
    }

    @Test
    fun aSongOnASeekingAppNeverUsesThePlayer() {
        keep(line())
        youTubeHasTheSong()
        playing()
        cards().start(null, before = null)
        waitFor("the line is ready") { practice?.sound == LineSound.PLAYED }
        assertTrue(player.shown.isEmpty())
        assertEquals(listOf(10_000L), playback.seeks.toList())
        // That the app moves is remembered under the package the app says it is.
        assertEquals(true, traits.of(MusicService.SPOTIFY.packageName).seeks)
    }

    @Test
    fun aSpotifySongThatWontMoveIsPlayedInThePlayerAndSpotifyIsNotAskedAgain() {
        spotifyHasTheSong()
        youTubeHasTheSong()
        keep(keptFromSpotify, keptFromSpotify.copy(text = "Next"))
        playback.access = true
        val cards = cards(songLoadMs = 1_500)
        cards.start(null, before = null)
        waitFor("Spotify opens") { playback.opened.isNotEmpty() }
        playback.playing.value = Following(PlaybackClock(0, System.currentTimeMillis(), playing = true), "Spotify", canSeek = false, appPackage = MusicService.SPOTIFY.packageName)
        waitFor("the player is on the line") { player.shown.isNotEmpty() }
        assertTrue(playback.seeks.isEmpty())
        assertTrue(playback.songsAsked.isEmpty())
        assertEquals(1, playback.cameBack)
        // What Spotify was playing is paused for the player.
        assertEquals(listOf(false), playback.plays)
        // The next card of the same app goes straight to the player, without opening Spotify again.
        val opened = playback.opened.size
        cards.turn()
        waitFor("it plays") { player.shown.size == 2 }
        cards.grade(Grade.GOOD)
        waitFor("the next line") { player.shown.size >= 3 }
        assertEquals(opened, playback.opened.size)
    }

    @Test
    fun aSpotifySongThatStartsUnmovableSeeksOnceItCan() {
        spotifyHasTheSong()
        keep(keptFromSpotify)
        playback.access = true
        cards().start(null, before = null)
        waitFor("Spotify opens") { playback.opened.isNotEmpty() }
        playback.playing.value = Following(PlaybackClock(0, System.currentTimeMillis(), 20f), "Spotify", canSeek = true)
        waitFor("the line is reached") { practice?.sound == LineSound.PLAYED }
        assertEquals(listOf(10_000L), playback.seeks.toList())
        assertTrue(player.shown.isEmpty())
    }

    @Test
    fun aSpotifySongThatOffersASeekAfterAMomentOfPlayingIsMovedAndNotGivenUp() {
        spotifyHasTheSong()
        keep(keptFromSpotify)
        playback.access = true
        cards().start(null, before = null)
        waitFor("Spotify opens") { playback.opened.isNotEmpty() }
        // It plays a moment without a seek, as a session does before it has said what it can do, and then offers one.
        playback.playing.value = Following(PlaybackClock(0, System.currentTimeMillis(), 20f), "Spotify", canSeek = false, appPackage = spotify)
        Thread.sleep(300)
        playback.playing.value = Following(PlaybackClock(0, System.currentTimeMillis(), 20f), "Spotify", canSeek = true, appPackage = spotify)
        waitFor("the line is reached") { practice?.sound == LineSound.PLAYED }
        assertEquals(listOf(10_000L), playback.seeks.toList())
        assertEquals(1, playback.cameBack)
        assertEquals(AppTraits(seeks = true), traits.of(spotify).copy(keeps = null))
        assertTrue(player.shown.isEmpty())
    }

    @Test
    fun aVideosLeadMovesWhereTheLineEndsToo() {
        youTubeHasTheSong()
        keep(line(lead = 5_000), types = hearIt)
        installed(morphe)
        // A video with 5 s before the song: the line is 15 s to 20 s into it, and takes a second to be over at five times the speed.
        playback.access = true
        playback.appPlaying[morphe] = MutableStateFlow(Following(PlaybackClock(15_000, System.currentTimeMillis(), 5f), "Music", canSeek = true, appPackage = morphe, video = true, durationMs = 200_000))
        cards().start(null, before = null)
        waitFor("it's asked to play") { playback.playedApps.zip(playback.plays).contains(morphe to true) }
        assertEquals(listOf(15_000L), playback.seeks.toList())
        Thread.sleep(300)
        assertEquals(LineSound.PLAYING, practice?.sound)
        waitFor("the line is over") { practice?.sound == LineSound.PLAYED }
    }

    @Test
    fun aCardInThePlayerOnlyPlaysOnceItIsTurnedOrPlayedAtOnce() {
        keep(line())
        youTubeHasTheSong()
        playback.access = true
        playback.canPlaySongs = false
        val cards = cards(songLoadMs = 300)
        cards.start(null, before = null)
        waitFor("the player is on the line") { player.shown.isNotEmpty() }
        assertFalse(player.shown.last().play)
        cards.turn()
        waitFor("it plays") { player.shown.last().play }
    }

    @Test
    fun aVideoOpenedOnItsLinkSeeksPastItsLead() {
        spotifyHasTheSong()
        // The line is 10 s into the song, and the video has 2 s before the song. The open path's follow is what says so.
        keep(keptFromSpotify.copy(leadMs = 2_000))
        playback.access = true
        cards().start(null, before = null)
        waitFor("Spotify opens") { playback.opened.isNotEmpty() }
        playback.playing.value = Following(PlaybackClock(0, System.currentTimeMillis(), 20f), "Spotify", canSeek = true, video = true)
        waitFor("the line is reached") { practice?.sound == LineSound.PLAYED }
        assertEquals(listOf(12_000L), playback.seeks.toList())
    }

    @Test
    fun aSongKeptFromSpotifyOpensThereOnItsOwnLink() {
        spotifyHasTheSong()
        keep(keptFromSpotify)
        playback.access = true
        cards().start(null, before = null)
        waitFor("Spotify opens") { playback.opened.isNotEmpty() }
        assertEquals(listOf(MusicService.SPOTIFY.packageName to "https://open.spotify.com/track/$SPOTIFY_ID"), playback.opened.toList())
        // It ignores songs asked for by name.
        assertTrue(playback.songsAsked.isEmpty())
        // It plays, so the line can be reached, and Crosstune comes back to the card.
        playing()
        waitFor("the line is reached") { playback.seeks.isNotEmpty() }
        assertEquals(listOf(10_000L), playback.seeks.toList())
        assertEquals(1, playback.cameBack)
    }

    @Test
    fun spotifyIsLeftOnceItPlaysAndTheLineGoesToThePlayerWhenItWontLetItBeReached() {
        spotifyHasTheSong()
        youTubeHasTheSong()
        keep(keptFromSpotify)
        playback.access = true
        cards().start(null, before = null)
        waitFor("Spotify opens") { playback.opened.isNotEmpty() }
        // Not playing yet, so nothing comes back and the notice, if one is needed, doesn't cover it early.
        Thread.sleep(300)
        assertEquals(0, playback.cameBack)
        // Playing, but with no way to move around in it: Crosstune comes back now, not 12 s later.
        val playingAt = System.currentTimeMillis()
        playing(canSeek = false)
        waitFor("it comes back") { playback.cameBack == 1 }
        assertTrue("it came back before the wait to be sure", System.currentTimeMillis() - playingAt < MainViewModel.LOAD_SONG_MS / 2)
        assertTrue(playback.seeks.isEmpty())
        // It's never moved, and the line is played in the player, without coming back twice.
        waitFor("the player is on the line") { player.shown.isNotEmpty() }
        assertTrue(playback.seeks.isEmpty())
        assertEquals(1, playback.cameBack)
    }

    @Test
    fun aPausedSpotifyIsNotLeftUntilItPlays() {
        spotifyHasTheSong()
        keep(keptFromSpotify)
        playback.access = true
        cards().start(null, before = null)
        waitFor("Spotify opens") { playback.opened.isNotEmpty() }
        playback.playing.value = Following(PlaybackClock(10_000, System.currentTimeMillis(), playing = false), "Spotify", canSeek = false)
        Thread.sleep(300)
        assertEquals(0, playback.cameBack)
        playback.playing.value = Following(PlaybackClock(10_000, System.currentTimeMillis()), "Spotify", canSeek = false)
        waitFor("it comes back") { playback.cameBack == 1 }
    }

    /** The only test that waits out the whole 12 s the music app is given to start. */
    @Test
    fun aSongThatNeverPlaysIsLeftAllTheSame() {
        spotifyHasTheSong()
        keep(keptFromSpotify)
        playback.access = true
        cards().start(null, before = null)
        waitFor("Spotify opens") { playback.opened.isNotEmpty() }
        Thread.sleep(1_000)
        assertEquals(0, playback.cameBack)
        waitFor("it comes back at the end of the wait") { playback.cameBack == 1 }
        assertTrue(playback.seeks.isEmpty())
    }

    @Test
    fun aSongSpotifyCantBeFoundForIsAskedOfYouTubeMusicAsBefore() {
        sites.handler = { request ->
            when {
                request.url.host == "labs.api.listenbrainz.org" -> FakeSpotify.html(request, """[{"spotify_track_ids":[]}]""")
                else -> FakeSpotify.html(request, "{}", code = 404)
            }
        }
        keep(keptFromSpotify)
        playback.access = true
        cards().start(null, before = null)
        waitFor("it's asked") { playback.songsAsked.isNotEmpty() }
        assertTrue(playback.opened.isEmpty())
    }

    @Test
    fun spotifyThatWontOpenFallsBackToAsking() {
        spotifyHasTheSong()
        playback.opens = false
        keep(keptFromSpotify)
        playback.access = true
        cards().start(null, before = null)
        waitFor("it's asked") { playback.songsAsked.isNotEmpty() }
        assertEquals(0, playback.cameBack)
    }

    @Test
    fun aSongKeptFromAnAppThatPlaysWhatsAskedIsAskedOfIt() {
        spotifyHasTheSong()
        keep(line().copy(app = MusicService.TIDAL.packageName))
        playback.access = true
        cards().start(null, before = null)
        waitFor("it's asked") { playback.songsAsked.isNotEmpty() }
        assertEquals(MusicService.TIDAL.packageName, playback.songsAsked.single().second)
        assertTrue(playback.opened.isEmpty())
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
    fun theLinePlayingIsPausedWhenTheAppGoesAwayAndIsNotTakenAsPlayed() {
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
        // Gone before the line's end, it didn't play it, and with no video of the song there's no sound to offer.
        waitFor("it was paused") { practice?.sound == LineSound.NONE }
        assertEquals(listOf(false, true, false), playback.plays)
    }

    private val spotify = MusicService.SPOTIFY.packageName
    private val ytMusic = MusicService.YOUTUBE_MUSIC.packageName
    private val morphe = "app.morphe.android.apps.youtube.music"
    private val revanced = "app.revanced.android.apps.youtube.music"

    /** A card that plays its line at once, as a hear-it card does, so the app it plays in shows without a turn. */
    private val hearIt = listOf(READ_IT.copy(on = false), HEAR_IT.copy(on = true), TRANSLATE_IT)

    /** The music apps the phone has, and the one songs open in when none is asked for. */
    private fun installed(vararg apps: String, preferred: Destination = Destination.Service(MusicService.SPOTIFY)) {
        screen.value = screen.value.copy(musicApps = apps.toList(), defaultDestination = preferred)
    }

    /** [app] playing the demo song, moving in it or not, on a clock [speed] times as fast. */
    private fun appPlays(app: String, canSeek: Boolean = true, speed: Float = 5f, positionMs: Long = 10_000) {
        playback.access = true
        playback.appPlaying[app] = MutableStateFlow(Following(PlaybackClock(positionMs, System.currentTimeMillis(), speed), "Music", canSeek = canSeek, appPackage = app, durationMs = 200_000))
    }

    @Test
    fun aSpotifyFreeCardIsPlayedInAnotherAppThatMovesAndKeepsPlaying() {
        spotifyHasTheSong()
        youTubeHasTheSong()
        keep(keptFromSpotify)
        installed(spotify, morphe, preferred = Destination.Service(MusicService.SPOTIFY))
        // Spotify, the app the user prefers, plays and won't move; the patched YouTube Music moves.
        appPlays(spotify, canSeek = false)
        appPlays(morphe, speed = 1f, positionMs = 9_500)
        val cards = cards()
        cards.start(null, before = null)
        waitFor("the line is ready") { practice?.sound == LineSound.PLAYED }
        assertEquals(listOf<String?>(morphe), playback.seekedApps.toList())
        assertTrue(player.shown.isEmpty())
        // Spotify is paused where it plays, and remembered not to move; the other moved.
        assertTrue(playback.playedApps.zip(playback.plays).contains(spotify to false))
        assertEquals(AppTraits(seeks = false), traits.of(spotify))
        assertEquals(AppTraits(seeks = true), traits.of(morphe))
        // Both already had the song, neither opened nor asked for it: the music was never muted.
        assertTrue(playback.quiets.isEmpty())
        // Turned, the line plays there, and that it keeps playing is remembered, too.
        playback.appPlaying.getValue(morphe).value = Following(PlaybackClock(10_000, System.currentTimeMillis(), 20f), "Music", canSeek = true, appPackage = morphe, durationMs = 200_000)
        cards.turn()
        waitFor("it's remembered and the line is over") { traits.of(morphe).works && practice?.sound == LineSound.PLAYED }
        assertTrue(playback.playedApps.zip(playback.plays).contains(morphe to true))
        assertTrue(player.shown.isEmpty())
        // Spotify isn't followed again for the card.
        assertEquals(1, playback.followedApps.count { it == spotify })
    }

    @Test
    fun anAppThatPausesItselfBehindCrosstuneIsRememberedAndTheLineGoesOn() {
        youTubeHasTheSong()
        keep(line("One"), line("Two"), types = hearIt)
        installed(morphe)
        appPlays(morphe, speed = 1f)
        val flow = playback.appPlaying.getValue(morphe)
        val cards = cards()
        cards.start(null, before = null)
        waitFor("the line plays in the app") { playback.playedApps.zip(playback.plays).contains(morphe to true) }
        // It plays a moment, and then, behind Crosstune, stops itself.
        Thread.sleep(200)
        flow.value = Following(PlaybackClock(10_200, System.currentTimeMillis(), 1f, playing = false), "Music", canSeek = true, appPackage = morphe, durationMs = 200_000)
        waitFor("the player takes over") { player.shown.isNotEmpty() }
        assertEquals(AppTraits(seeks = true, keeps = false), traits.of(morphe))
        assertEquals("demo0000000", practice?.video)
        // The next card doesn't go through the app again.
        val tried = playback.followedApps.size
        cards.turn()
        cards.grade(Grade.GOOD)
        waitFor("the next line is in the player") { player.shown.size >= 2 }
        assertEquals(tried, playback.followedApps.size)
    }

    @Test
    fun appsSeenToWorkAreTriedFirstAndOnesSeenToFailAreLeftOut() {
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        // Not seen yet comes first in the list, seen to work comes after it but ahead, and one that failed is out.
        installed(revanced, ytMusic, morphe)
        traits.seeks(morphe, true)
        traits.keeps(morphe, true)
        traits.keeps(ytMusic, false)
        listOf(revanced, ytMusic, morphe).forEach { appPlays(it) }
        cards().start(null, before = null)
        waitFor("the line plays") { playback.playedApps.zip(playback.plays).contains(morphe to true) }
        waitFor("it's over") { practice?.sound == LineSound.PLAYED }
        assertEquals(listOf<String?>(morphe), playback.followedApps.distinct())
        assertTrue(player.shown.isEmpty())
    }

    @Test
    fun anAppSeenToFailIsTriedAgainAWeekLater() {
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        installed(ytMusic)
        traits.keeps(ytMusic, false)
        appPlays(ytMusic)
        wall += AppTraitsStore.FORGET_AFTER_MS
        cards().start(null, before = null)
        waitFor("it plays") { playback.playedApps.zip(playback.plays).contains(ytMusic to true) }
        waitFor("it works") { traits.of(ytMusic).works }
    }

    /**
     * Turns a card the player has, which plays its line again there. In the app, a tap to grade comes after that's begun, on
     * the same thread; here, graded at once, that replay could land on the next card instead.
     */
    private fun turnInThePlayer(cards: PracticeCards) {
        val shown = player.shown.size
        cards.turn()
        waitFor("the line plays again") { player.shown.size > shown }
    }

    /**
     * The steps a card logged. Where an app played the line from is as far as its clock ran between being sent there and
     * asked to play, real time a busy machine stretches: within what the card takes as the line's start, it reads as the start.
     */
    private fun steps(): List<String> = ShadowLog.getLogsForTag(CHAIN_LOG).map { step ->
        step.msg.replace(PLAYED_FROM) { found -> if (abs(found.groupValues[1].toLong()) <= LineWindow.SEEK_SLACK_MS) "played the line from its start" else found.value }
    }

    @Test
    fun theStepsOfACardAreLoggedOnlyWhenTheLogIsAskedFor() {
        spotifyHasTheSong()
        youTubeHasTheSong()
        keep(keptFromSpotify, types = hearIt)
        installed(spotify, morphe)
        appPlays(spotify, canSeek = false)
        appPlays(morphe)
        cards().start(null, before = null)
        waitFor("the line is over") { practice?.sound == LineSound.PLAYED }
        assertEquals(emptyList<String>(), steps())
    }

    @Test
    fun aCardLogsEachAppItTriesAndWhatCameOfIt() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        spotifyHasTheSong()
        youTubeHasTheSong()
        keep(keptFromSpotify, types = hearIt)
        installed(spotify, morphe)
        appPlays(spotify, canSeek = false)
        // At 1x: where it says it played the line from is checked, and a faster clock would multiply how late a busy machine gets there.
        appPlays(morphe, speed = 1f)
        cards().start(null, before = null)
        waitFor("the line is over") { practice?.sound == LineSound.PLAYED }
        assertEquals(
            listOf("trying $spotify", "$spotify plays and does not move", "$spotify did not move to the line", "trying $morphe", "$morphe played the line from its start"),
            steps()
        )
    }

    @Test
    fun aCardLogsAnAppThatPausedItselfAndThePlayerThatTookOver() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        installed(morphe)
        appPlays(morphe, speed = 1f)
        val flow = playback.appPlaying.getValue(morphe)
        cards().start(null, before = null)
        waitFor("the line plays in the app") { playback.playedApps.zip(playback.plays).contains(morphe to true) }
        Thread.sleep(100)
        flow.value = Following(PlaybackClock(10_100, System.currentTimeMillis(), 1f, playing = false), "Music", canSeek = true, appPackage = morphe, durationMs = 200_000)
        waitFor("the player takes over") { player.shown.isNotEmpty() }
        assertEquals(listOf("trying $morphe", "$morphe stopped playing the line", "the YouTube player"), steps())
    }

    @Test
    fun aCardLogsThatItHasNoSoundAndAnAppThatWasNeverThere() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        keep(line())
        // Nothing is playing and nothing is asked for, so the app never moves, and YouTube has no video.
        playback.access = true
        playback.canPlaySongs = false
        cards(songLoadMs = 300).start(null, before = null)
        waitFor("no sound") { practice?.sound == LineSound.NONE }
        assertEquals(listOf("trying any app", "any app did not move to the line", "no sound"), steps())
    }

    @Test
    fun anAppOpenedThatNeverStartsIsNotOpenedAgainForTheNextCard() {
        spotifyHasTheSong()
        youTubeHasTheSong()
        keep(keptFromSpotify, keptFromSpotify.copy(text = "Next"))
        playback.access = true
        val cards = cards(songLoadMs = 1_500)
        cards.start(null, before = null)
        // Opened on its own link, Spotify never starts, as YouTube Music cold opened sometimes doesn't: nothing's seen of what it can do.
        waitFor("the player is on the line") { player.shown.isNotEmpty() }
        assertEquals(1, playback.opened.size)
        assertEquals(AppTraits(), traits.of(MusicService.SPOTIFY.packageName))
        turnInThePlayer(cards)
        cards.grade(Grade.GOOD)
        waitFor("the next line") { player.shown.size >= 3 }
        assertEquals(1, playback.opened.size)
    }

    @Test
    fun anAppThatKeepsPlayingIsUsedForTheNextCardToo() {
        youTubeHasTheSong()
        keep(line("One"), line("Two"), types = hearIt)
        installed(morphe)
        appPlays(morphe)
        val cards = cards()
        cards.start(null, before = null)
        waitFor("the first line is over") { practice?.sound == LineSound.PLAYED }
        assertEquals(AppTraits(seeks = true, keeps = true), traits.of(morphe))
        assertEquals(true, traits.of(morphe).keeps)
        cards.turn()
        cards.grade(Grade.GOOD)
        waitFor("the second line is played in the app, too") { playback.followedApps.count { it == morphe } == 2 && practice?.sound == LineSound.PLAYED }
        assertTrue(player.shown.isEmpty())
    }

    @Test
    fun anAppThatPausesAMomentIsStillPlayingTheLine() {
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        installed(morphe)
        appPlays(morphe, speed = 5f)
        val flow = playback.appPlaying.getValue(morphe)
        cards().start(null, before = null)
        waitFor("the line plays in the app") { playback.playedApps.zip(playback.plays).contains(morphe to true) }
        // It stops for a moment, much less than it takes to be sure it won't go on, and then goes on.
        Thread.sleep(100)
        val at = flow.value!!.clock.positionAt(System.currentTimeMillis())
        flow.value = Following(PlaybackClock(at, System.currentTimeMillis(), 5f, playing = false), "Music", canSeek = true, appPackage = morphe, durationMs = 200_000)
        Thread.sleep(400)
        flow.value = Following(PlaybackClock(at, System.currentTimeMillis(), 5f), "Music", canSeek = true, appPackage = morphe, durationMs = 200_000)
        waitFor("the line is over") { practice?.sound == LineSound.PLAYED }
        assertEquals(AppTraits(seeks = true, keeps = true), traits.of(morphe))
        assertTrue(player.shown.isEmpty())
    }

    @Test
    fun anAppThatHasntStartedYetIsNotTakenForOneThatStopped() {
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        installed(morphe)
        // Paused where the line starts for longer than a stop is waited for, since it hasn't played yet; then it plays.
        appPlays(morphe, speed = 5f)
        val flow = playback.appPlaying.getValue(morphe)
        flow.value = Following(PlaybackClock(10_000, System.currentTimeMillis(), 5f, playing = false), "Music", canSeek = true, appPackage = morphe, durationMs = 200_000)
        cards().start(null, before = null)
        waitFor("the line is asked of the app") { playback.playedApps.zip(playback.plays).contains(morphe to true) }
        Thread.sleep(1_800L)
        flow.value = Following(PlaybackClock(10_000, System.currentTimeMillis(), 5f), "Music", canSeek = true, appPackage = morphe, durationMs = 200_000)
        waitFor("the line is over") { practice?.sound == LineSound.PLAYED }
        assertEquals(AppTraits(seeks = true, keeps = true), traits.of(morphe))
    }

    @Test
    fun theAppTheUserPrefersIsTriedEvenWhenItsServiceHasNoLinkForTheSong() {
        keep(line(), types = hearIt)
        installed(spotify)
        appPlays(spotify)
        cards().start(null, before = null)
        waitFor("the line is over") { practice?.sound == LineSound.PLAYED }
        assertEquals(listOf<String?>(spotify), playback.followedApps.distinct())
    }

    @Test
    fun withAnotherKindOfDestinationAnAppIsTriedOnlyWhenItHasTheSong() {
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        installed(spotify, preferred = Destination.Alternative(Frontend.entries.first()))
        appPlays(spotify)
        cards().start(null, before = null)
        waitFor("the player is on the line") { player.shown.isNotEmpty() }
        assertTrue(playback.followedApps.isEmpty())
    }

    @Test
    fun anAppWithoutTheSongIsNotTriedUnlessItIsPreferredOrKeptTheLine() {
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        // TIDAL has nothing for it, and isn't the app the user prefers or the line came from.
        installed(MusicService.TIDAL.packageName)
        appPlays(MusicService.TIDAL.packageName)
        cards().start(null, before = null)
        waitFor("the player is on the line") { player.shown.isNotEmpty() }
        assertTrue(playback.followedApps.isEmpty())
    }

    @Test
    fun aCardTriesThreeAppsAtMostBeforeThePlayer() {
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        val apps = listOf(morphe, ytMusic, revanced, "org.example.android.apps.youtube.music")
        installed(*apps.toTypedArray())
        apps.forEach { appPlays(it, canSeek = false) }
        cards().start(null, before = null)
        waitFor("the player is on the line") { player.shown.isNotEmpty() }
        assertEquals(3, playback.followedApps.distinct().size)
        assertEquals(3, apps.count { traits.of(it).seeks == false })
    }

    @Test
    fun theAppTheUserPrefersIsTriedFirstAndItsBuildBeforeTheOfficialApp() {
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        installed(ytMusic, morphe, preferred = Destination.Service(MusicService.YOUTUBE_MUSIC, build = morphe))
        listOf(ytMusic, morphe).forEach { appPlays(it) }
        val cards = cards()
        cards.start(null, before = null)
        waitFor("it plays") { practice?.sound == LineSound.PLAYED }
        assertEquals(morphe, playback.seekedApps.first())
        cards.stop()
        // Preferring the official app puts it first.
        playback.seekedApps.clear()
        installed(morphe, ytMusic, preferred = Destination.Service(MusicService.YOUTUBE_MUSIC))
        listOf(ytMusic, morphe).forEach { appPlays(it) }
        cards.start(null, before = null)
        waitFor("it plays again") { playback.seekedApps.isNotEmpty() }
        assertEquals(ytMusic, playback.seekedApps.first())
    }

    @Test
    fun anAppThatPlaysAndWontMoveIsRememberedUnderThePackageItSaysItIs() {
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        // No app is asked for, so the one that plays is the one that's marked.
        playing(canSeek = false)
        cards().start(null, before = null)
        waitFor("the player takes over") { player.shown.isNotEmpty() }
        assertEquals(AppTraits(seeks = false), traits.of(spotify))
    }

    @Test
    fun anAppThatPlaysAndWontMoveIsRememberedUnderTheNameItWasTriedUnder() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        installed(spotify)
        // It doesn't say which app it is.
        playback.access = true
        playback.appPlaying[spotify] = MutableStateFlow(Following(PlaybackClock(10_000, System.currentTimeMillis()), "Music", canSeek = false))
        cards().start(null, before = null)
        waitFor("the player takes over") { player.shown.isNotEmpty() }
        assertEquals(AppTraits(seeks = false), traits.of(spotify))
        assertEquals(listOf("trying $spotify", "$spotify plays and does not move", "$spotify did not move to the line", "the YouTube player"), steps())
    }

    @Test
    fun anAppThatPlaysAndWontMoveAndIsNotKnownIsNotRemembered() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        keep(line(), types = hearIt)
        playback.access = true
        playback.playing.value = Following(PlaybackClock(10_000, System.currentTimeMillis()), "Music", canSeek = false)
        cards().start(null, before = null)
        waitFor("no sound") { practice?.sound == LineSound.NONE }
        assertEquals(listOf("trying any app", "the app plays and does not move", "any app did not move to the line", "no sound"), steps())
        assertNull(ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("practice-traits", Context.MODE_PRIVATE).getString("app_traits", null))
    }

    @Test
    fun aCardThatHearsItselfIsPlayedAtOnceInThePlayerToo() {
        keep(line(), types = hearIt)
        youTubeHasTheSong()
        playback.access = true
        playback.canPlaySongs = false
        cards(songLoadMs = 300).start(null, before = null)
        waitFor("the player is on the line") { player.shown.isNotEmpty() }
        assertTrue(player.shown.last().play)
    }

    @Test
    fun aCardTurnedWhileItsAppIsBeingLookedForIsPlayedAtOnceInThePlayer() {
        keep(line())
        youTubeHasTheSong()
        playback.access = true
        playback.canPlaySongs = false
        val cards = cards(songLoadMs = 1_500)
        cards.start(null, before = null)
        waitFor("it's looking") { practice?.sound == LineSound.LOADING }
        cards.turn()
        waitFor("the player is on the line") { player.shown.isNotEmpty() }
        assertTrue(player.shown.last().play)
    }

    @Test
    fun aCardNotTurnedIsOnlyGotReadyInTheAppNotPlayed() {
        keep(line())
        youTubeHasTheSong()
        installed(morphe)
        appPlays(morphe)
        val cards = cards()
        cards.start(null, before = null)
        waitFor("the line is ready") { practice?.sound == LineSound.PLAYED }
        assertEquals(listOf(false), playback.plays.toList())
        cards.replay()
        waitFor("it plays when asked to") { playback.plays.contains(true) }
    }

    @Test
    fun songLinesNeverTakeABlankWordForOneWorthKnowing() {
        val lines = songLines(listOf("Hello there", "hello there", "Other"), listOf(" ", "xyz", "other"))
        assertEquals(listOf(2, 1), lines.map { it.times })
        assertEquals(listOf(false, true), lines.map { it.newWords })
    }

    /** What the card logged that an app played, which it may only once the app played the line. */
    private fun playedSteps() = steps().filter { "played the line" in it }

    @Test
    fun anAppThatErrsWhenAskedToPlayTheLineDidNotPlayItAndThePlayerDoes() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        installed(ytMusic, preferred = Destination.Service(MusicService.YOUTUBE_MUSIC))
        appPlays(ytMusic, speed = 1f)
        // As YouTube Music without Premium asked to play from behind Crosstune: its session errs, so it's followed no more.
        val flow = playback.appPlaying.getValue(ytMusic)
        playback.onPlay = { app, play -> if (app == ytMusic && play) flow.value = null }
        cards().start(null, before = null)
        waitFor("the player takes over") { player.shown.isNotEmpty() }
        assertEquals("demo0000000", practice?.video)
        assertEquals(emptyList<String>(), playedSteps())
        assertEquals(listOf("trying $ytMusic", "$ytMusic did not play the line", "the YouTube player"), steps())
        assertEquals(AppTraits(keeps = false), traits.of(ytMusic))
        // What it may still play is paused.
        assertEquals(listOf(ytMusic), playback.pausedApps.distinct())
    }

    @Test
    fun anAppThatPlaysFromTheStartInsteadOfTheLineDidNotPlayIt() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        installed(ytMusic, preferred = Destination.Service(MusicService.YOUTUBE_MUSIC))
        appPlays(ytMusic, speed = 1f)
        // Asked to play, it does, but from 0, as YouTube Music did after an error.
        val flow = playback.appPlaying.getValue(ytMusic)
        playback.onPlay = { app, play ->
            if (app == ytMusic && play) flow.value = flow.value?.copy(clock = PlaybackClock(0, System.currentTimeMillis(), 1f))
        }
        cards().start(null, before = null)
        waitFor("the player takes over") { player.shown.isNotEmpty() }
        assertEquals(emptyList<String>(), playedSteps())
        assertEquals(AppTraits(keeps = false), traits.of(ytMusic))
        assertEquals(listOf(ytMusic), playback.pausedApps.distinct())
    }

    @Test
    fun anAppThatGoesOnToAnotherSongDidNotPlayTheLineAndTheNextAppDoes() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        val tidal = MusicService.TIDAL.packageName
        installed(tidal, ytMusic, preferred = Destination.Service(MusicService.TIDAL))
        appPlays(tidal, speed = 1f)
        // At 1x: where it says it played the line from is checked, and a faster clock would multiply how late a busy machine gets there.
        appPlays(ytMusic, speed = 1f)
        // As TIDAL with no account: its preview is over before the line, and it plays the next song, which isn't the card's.
        val flow = playback.appPlaying.getValue(tidal)
        playback.onPlay = { app, play -> if (app == tidal && play) flow.value = null }
        cards().start(null, before = null)
        waitFor("the next app played the line") { "$ytMusic played the line from its start" in steps() }
        assertEquals(listOf("trying $tidal", "$tidal did not play the line", "trying $ytMusic", "$ytMusic played the line from its start"), steps())
        // The other song TIDAL went on to isn't left playing.
        assertEquals(listOf(tidal), playback.pausedApps.distinct())
        assertEquals(AppTraits(keeps = false), traits.of(tidal))
        assertTrue(player.shown.isEmpty())
    }

    @Test
    fun anAppThatErrsAndComesBackIsAskedOnceMoreAndPlaysTheLine() {
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        installed(ytMusic, preferred = Destination.Service(MusicService.YOUTUBE_MUSIC))
        appPlays(ytMusic)
        val flow = playback.appPlaying.getValue(ytMusic)
        val ready = flow.value
        // The first time it's asked to play, it errs, as YouTube Music still loading; then it's back, paused at the start.
        playback.onPlay = { app, play -> if (app == ytMusic && play && playback.plays.count { it } == 1) flow.value = null }
        cards().start(null, before = null)
        waitFor("it erred") { playback.plays.contains(true) && flow.value == null }
        Thread.sleep(300)
        flow.value = ready?.copy(clock = PlaybackClock(0, System.currentTimeMillis(), 5f, playing = false))
        waitFor("it played the line") { traits.of(ytMusic).works && practice?.sound == LineSound.PLAYED }
        assertEquals(listOf(10_000L, 10_000L), playback.seeks.toList())
        assertTrue(player.shown.isEmpty())
    }

    @Test
    fun anAppInAnAdIsNotSentToTheLineUntilTheAdIsOver() {
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        installed(ytMusic, preferred = Destination.Service(MusicService.YOUTUBE_MUSIC))
        appPlays(ytMusic)
        val flow = playback.appPlaying.getValue(ytMusic)
        val song = flow.value
        // YouTube Music's free plan plays an ad first, under the song's name, the song waiting at its start.
        flow.value = song?.copy(clock = PlaybackClock(0, System.currentTimeMillis(), 1f, playing = false), ad = true)
        cards().start(null, before = null)
        Thread.sleep(500)
        assertTrue(playback.seeks.isEmpty())
        assertTrue(playback.plays.isEmpty())
        flow.value = song?.copy(clock = PlaybackClock(0, System.currentTimeMillis(), 5f))
        waitFor("it played the line") { traits.of(ytMusic).works && practice?.sound == LineSound.PLAYED }
        assertEquals(listOf(10_000L), playback.seeks.toList())
    }

    @Test
    fun anAppThatNeverGetsToTheLineIsNotReadyAndThePlayerIs() {
        youTubeHasTheSong()
        keep(line())
        installed(ytMusic, preferred = Destination.Service(MusicService.YOUTUBE_MUSIC))
        appPlays(ytMusic, positionMs = 0)
        // It says it can move, but stays at 0 when sent to the line.
        playback.obeys = false
        cards().start(null, before = null)
        waitFor("the player has the line") { practice?.video == "demo0000000" }
        assertEquals(LineSound.PLAYED, practice?.sound)
        assertEquals(AppTraits(seeks = false), traits.of(ytMusic))
        assertEquals(listOf(ytMusic), playback.pausedApps.distinct())
    }

    @Test
    fun aBuildPatchedFromYouTubeMusicIsOpenedItselfNotYouTubeMusic() {
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        // Morphe's YouTube Music, picked, isn't running and can't be woken to play in the background.
        installed(ytMusic, morphe, preferred = Destination.Service(MusicService.YOUTUBE_MUSIC, build = morphe))
        playback.access = true
        playback.running = emptySet()
        cards(songLoadMs = 300).start(null, before = null)
        waitFor("it was opened") { playback.opened.isNotEmpty() }
        assertEquals(morphe, playback.opened.first().first)
        // Opened at the line, 10 s in, so it never plays the song's start: rounded down, it's never past it either.
        assertTrue(playback.opened.first().second, playback.opened.first().second.endsWith("&t=10"))
        assertEquals(listOf<String?>(morphe), playback.followedApps.take(1))
    }

    @Test
    fun anAppOpenedOnceAskingItGaveNothingIsNotKeptPausedWhileItPlaysTheLine() {
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        installed(ytMusic, morphe, preferred = Destination.Service(MusicService.YOUTUBE_MUSIC, build = morphe))
        playback.access = true
        playback.running = emptySet()
        val flow = MutableStateFlow<Following?>(null)
        playback.appPlaying[morphe] = flow
        cards().start(null, before = null)
        waitFor("it was opened") { playback.opened.isNotEmpty() }
        // Opened at the line, it plays there and can move.
        flow.value = Following(PlaybackClock(10_000, System.currentTimeMillis(), 1f), "Music", canSeek = true, appPackage = morphe, durationMs = 200_000)
        waitFor("the line is heard") { practice?.sound == LineSound.PLAYING }
        // Asked first, and kept stopped once that gave nothing, it's let go when opened: the line goes on.
        Thread.sleep(300)
        assertEquals(true, flow.value?.clock?.playing)
        assertEquals(LineSound.PLAYING, practice?.sound)
    }

    @Test
    fun anAppThatErrsEachTimeItsAskedIsAskedTwiceAndThenThePlayerPlaysTheLine() {
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        installed(ytMusic, preferred = Destination.Service(MusicService.YOUTUBE_MUSIC))
        appPlays(ytMusic)
        val flow = playback.appPlaying.getValue(ytMusic)
        val ready = flow.value
        // Each time it's asked to play, it errs, and is soon back, paused at the song's start.
        playback.onPlay = { app, play ->
            if (app == ytMusic && play) {
                flow.value = null
                thread {
                    Thread.sleep(150)
                    flow.value = ready?.copy(clock = PlaybackClock(0, System.currentTimeMillis(), 5f, playing = false))
                }
            }
        }
        cards().start(null, before = null)
        waitFor("the player takes over") { player.shown.isNotEmpty() }
        assertEquals(listOf(10_000L, 10_000L), playback.seeks.toList())
        assertEquals(AppTraits(keeps = false), traits.of(ytMusic))
    }

    @Test
    fun anAppInAnAdIsNotTakenToBeAtTheLineBeforeItsSentThere() {
        youTubeHasTheSong()
        keep(line())
        installed(ytMusic, preferred = Destination.Service(MusicService.YOUTUBE_MUSIC))
        appPlays(ytMusic)
        val flow = playback.appPlaying.getValue(ytMusic)
        val song = flow.value
        // An ad under the song's name, which the app says is paused right where the line is.
        flow.value = song?.copy(clock = PlaybackClock(10_000, System.currentTimeMillis(), 1f, playing = false), ad = true)
        cards().start(null, before = null)
        Thread.sleep(500)
        assertTrue(playback.seeks.isEmpty())
        assertEquals(AppTraits(), traits.of(ytMusic))
        assertEquals(LineSound.LOADING, practice?.sound)
        flow.value = song?.copy(clock = PlaybackClock(0, System.currentTimeMillis(), 1f, playing = false))
        waitFor("it's at the line") { practice?.sound == LineSound.PLAYED }
        assertEquals(listOf(10_000L), playback.seeks.toList())
        assertEquals(AppTraits(seeks = true), traits.of(ytMusic))
    }

    @Test
    fun aCardTurnedWhileItsAppGetsToTheLinePlaysItThere() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        youTubeHasTheSong()
        keep(line())
        installed(ytMusic, preferred = Destination.Service(MusicService.YOUTUBE_MUSIC))
        // At 1x: where it says it played the line from is checked, and a faster clock would multiply how late a busy machine gets there.
        appPlays(ytMusic, speed = 1f)
        val flow = playback.appPlaying.getValue(ytMusic)
        val song = flow.value
        // The app is in an ad as the card's shown, so it's still getting to the line when the card is turned.
        flow.value = song?.copy(clock = PlaybackClock(0, System.currentTimeMillis(), 1f, playing = false), ad = true)
        val cards = cards()
        cards.start(null, before = null)
        Thread.sleep(300)
        cards.turn()
        flow.value = song?.copy(clock = PlaybackClock(0, System.currentTimeMillis(), 1f, playing = false))
        waitFor("it played the line") { traits.of(ytMusic).works && practice?.sound == LineSound.PLAYED }
        // Sent there paused, then played once turned, and paused again at the line's end.
        assertEquals(listOf(false, true, false), playback.plays.toList())
        assertEquals(listOf("trying $ytMusic", "$ytMusic played the line from its start"), steps())
        assertTrue(player.shown.isEmpty())
    }

    @Test
    fun withoutShowingOverOtherAppsACardOpensNoAppAndPlaysTheLineInThePlayerAtOnce() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        spotifyHasTheSong()
        youTubeHasTheSong()
        keep(keptFromSpotify)
        // Spotify plays only its own links, which open it; YouTube Music without Premium won't play in the background either.
        installed(spotify, ytMusic)
        playback.access = true
        playback.startsInBackground = false
        playback.comesBackByItself = false
        val shownAt = System.currentTimeMillis()
        cards().start(null, before = null)
        waitFor("the player is on the line") { player.shown.isNotEmpty() }
        // Not the 12 s each app opened behind Crosstune would wait for nothing: neither was opened, as neither could be come back from.
        assertTrue("the player waited ${System.currentTimeMillis() - shownAt} ms", System.currentTimeMillis() - shownAt < MainViewModel.LOAD_SONG_MS)
        assertTrue(playback.opened.isEmpty())
        assertEquals(0, playback.cameBack)
        assertTrue(practice!!.cantComeBack)
        assertTrue("$spotify would cover the card" in steps())
        assertTrue("$ytMusic would cover the card" in steps())
        // Nothing's learned of them: it's Crosstune that couldn't, not the apps.
        assertEquals(AppTraits(), traits.of(spotify))
        assertEquals(AppTraits(), traits.of(ytMusic))
    }

    @Test
    fun anAppLeftOutForWantOfShowingOverOtherAppsIsTriedOnceThatsAllowed() {
        spotifyHasTheSong()
        youTubeHasTheSong()
        keep(keptFromSpotify.copy(text = "One"), keptFromSpotify.copy(text = "Two", startMs = 20_000, endMs = 25_000))
        playback.access = true
        playback.comesBackByItself = false
        val cards = cards()
        cards.start(null, before = null)
        waitFor("the player is on the line") { player.shown.isNotEmpty() }
        assertTrue(playback.opened.isEmpty())
        // Allowed, the next card opens Spotify, which wasn't put aside for the session.
        playback.comesBackByItself = true
        turnInThePlayer(cards)
        cards.grade(Grade.GOOD)
        waitFor("Spotify opens") { playback.opened.isNotEmpty() }
        assertEquals(spotify, playback.opened.single().first)
        playing()
        waitFor("the line is ready") { practice?.sound == LineSound.PLAYED }
        // Nothing left out of this card's chain, it doesn't say it can't come back.
        assertFalse(practice!!.cantComeBack)
    }

    @Test
    fun aCardPlayedInAnAppAfterOneWasLeftOutStillSaysWhatTheLeftOutOneNeeds() {
        spotifyHasTheSong()
        youTubeHasTheSong()
        keep(keptFromSpotify)
        installed(spotify, morphe)
        playback.comesBackByItself = false
        // Spotify would have to be opened on its own link; Morphe already plays the song.
        appPlays(morphe, speed = 1f, positionMs = 9_500)
        cards().start(null, before = null)
        waitFor("the line is ready") { practice?.sound == LineSound.PLAYED }
        assertEquals(listOf<String?>(morphe), playback.seekedApps.toList())
        assertTrue(practice!!.cantComeBack)
    }

    @Test
    fun anAppTriedAfterOneThatWasOpenedIsNotPausedBeforeItsSentToTheLine() {
        spotifyHasTheSong()
        youTubeHasTheSong()
        keep(keptFromSpotify)
        installed(spotify, morphe)
        playback.access = true
        appPlays(morphe, speed = 1f, positionMs = 9_500)
        // Whether Morphe had been sent to the line yet each time it was paused.
        val sentWhenPaused = CopyOnWriteArrayList<Boolean>()
        playback.onPlay = { app, play -> if (app == morphe && !play) sentWhenPaused += morphe in playback.seekedApps }
        cards().start(null, before = null)
        waitFor("Spotify opens") { playback.opened.isNotEmpty() }
        // Opened, Spotify plays and never moves, so it's left while still kept quiet.
        playing(canSeek = false)
        waitFor("the line is ready") { practice?.sound == LineSound.PLAYED }
        assertTrue(sentWhenPaused.isNotEmpty())
        assertTrue("$sentWhenPaused", sentWhenPaused.all { it })
    }

    @Test
    fun anAppOpenedOnTheSongIsPausedAsSoonAsItPlaysAndOnlyPlayedFromTheLine() {
        spotifyHasTheSong()
        keep(keptFromSpotify, types = hearIt)
        playback.access = true
        // What had been asked of it when it was first paused. plays grows before onPlay writes this.
        val seeksAtFirstPause = AtomicReference<Int?>(null)
        // Whether its music was muted each time it was paused, or played. Written in onPlay, after plays grows.
        val quietAt = CopyOnWriteArrayList<Pair<Boolean, Boolean>>()
        playback.onPlay = { _, play ->
            if (!play) seeksAtFirstPause.compareAndSet(null, playback.seeks.size)
            quietAt += play to playback.quiet
        }
        // The card must already say the line plays when the sound is let out. Read on that call, not after it.
        val soundAtUnmute = AtomicReference<LineSound?>(null)
        playback.onQuiet = { on -> if (!on) soundAtUnmute.set(practice?.sound) }
        cards().start(null, before = null)
        waitFor("Spotify opens") { playback.opened.isNotEmpty() }
        // Opened cold, it starts the song from its start, not yet able to move.
        playback.playing.value = Following(PlaybackClock(0, System.currentTimeMillis()), "Spotify", canSeek = false, appPackage = spotify, durationMs = 200_000)
        waitFor("it's paused") { quietAt.isNotEmpty() && seeksAtFirstPause.get() != null }
        assertEquals(listOf(false), playback.plays.toList())
        assertEquals(0, seeksAtFirstPause.get())
        assertFalse(playback.playing.value!!.clock.playing)
        // Once it can move, it's sent to the line, and only then played, there.
        playback.playing.value = playback.playing.value!!.copy(canSeek = true)
        waitFor("it plays the line") { quietAt.any { (play, _) -> play } }
        assertEquals(listOf(10_000L), playback.seeks.toList())
        assertEquals(listOf(false, true), playback.plays.take(2))
        // Muted from before it was opened until it said it plays the line: the song's start was never heard.
        assertEquals(listOf(false to true, true to true), quietAt.take(2))
        // Heard while it plays the line, not only once it's over.
        waitFor("the line is heard") { soundAtUnmute.get() != null }
        assertEquals(LineSound.PLAYING, soundAtUnmute.get())
        assertEquals(LineSound.PLAYING, practice?.sound)
        assertEquals(listOf(true, false), playback.quiets.toList())
    }

    @Test
    fun anAppThatPlaysOnAMomentWhenPausedIsHeardOnlyOncePausedAtTheLine() {
        spotifyHasTheSong()
        keep(keptFromSpotify)
        playback.access = true
        // As Morphe just opened, it goes on playing however it's paused.
        val ignoresPauses = AtomicBoolean(true)
        playback.onPlay = { _, play ->
            if (!play && ignoresPauses.get()) playback.playing.value = playback.playing.value?.let { following ->
                val now = System.currentTimeMillis()
                following.copy(clock = PlaybackClock(following.clock.positionAt(now), now))
            }
        }
        cards().start(null, before = null)
        waitFor("Spotify opens") { playback.opened.isNotEmpty() }
        assertTrue(playback.quiet)
        playback.playing.value = Following(PlaybackClock(0, System.currentTimeMillis()), "Spotify", canSeek = true, appPackage = spotify, durationMs = 200_000)
        waitFor("it's sent to the line") { playback.seeks.isNotEmpty() }
        // At the line but still playing it, on a card's front, where hearing it is the answer: muted still.
        Thread.sleep(300)
        assertTrue(playback.playing.value!!.clock.playing)
        assertTrue(playback.quiet)
        ignoresPauses.set(false)
        playback.playing.value = playback.playing.value!!.copy(clock = PlaybackClock(10_000, System.currentTimeMillis(), playing = false))
        waitFor("the line is ready") { practice?.sound == LineSound.PLAYED }
        assertFalse(playback.quiet)
        assertEquals(listOf(true, false), playback.quiets.toList())
    }

    @Test
    fun anAppThatOnlyPlaysAndWontMoveIsLearnedSoWhilePaused() {
        spotifyHasTheSong()
        youTubeHasTheSong()
        keep(keptFromSpotify)
        playback.access = true
        cards().start(null, before = null)
        waitFor("Spotify opens") { playback.opened.isNotEmpty() }
        playing(canSeek = false)
        // It's paused at once, and still known to be stuck, as Spotify's free plan is: the line goes to the player.
        waitFor("the player is on the line") { player.shown.isNotEmpty() }
        assertEquals(false, playback.plays.first())
        assertEquals(AppTraits(seeks = false), traits.of(spotify))
        assertTrue(playback.seeks.isEmpty())
        // Muted while it was tried, and heard again for the player.
        assertEquals(listOf(true, false), playback.quiets.toList())
    }

    @Test
    fun aSongTheAppPlaysOnlyPartOfBeforeTheLineIsNotPlayedThere() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        val tidal = MusicService.TIDAL.packageName
        installed(tidal, preferred = Destination.Service(MusicService.TIDAL))
        appPlays(tidal)
        // As TIDAL without an account: only a 30 s preview, here over before the line starts at 10 s.
        playback.appPlaying.getValue(tidal).value = playback.appPlaying.getValue(tidal).value!!.copy(durationMs = 8_000)
        cards().start(null, before = null)
        waitFor("the player is on the line") { player.shown.isNotEmpty() }
        // Never sent there, nor played to its end and on into the next song.
        assertTrue(playback.seeks.isEmpty())
        assertFalse(playback.plays.contains(true))
        assertEquals(listOf("trying $tidal", "$tidal did not play the line", "the YouTube player"), steps())
        assertEquals(AppTraits(keeps = false), traits.of(tidal))
    }

    @Test
    fun aSongTheAppPlaysOnlyPartOfBeforeTheLineIsNotGotToOnACardsFront() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        youTubeHasTheSong()
        keep(line())
        val tidal = MusicService.TIDAL.packageName
        installed(tidal, preferred = Destination.Service(MusicService.TIDAL))
        appPlays(tidal)
        playback.appPlaying.getValue(tidal).value = playback.appPlaying.getValue(tidal).value!!.copy(durationMs = 8_000)
        cards().start(null, before = null)
        waitFor("the player is on the line") { player.shown.isNotEmpty() }
        assertEquals(listOf("trying $tidal", "$tidal did not get to the line", "the YouTube player"), steps())
        assertEquals(AppTraits(seeks = false), traits.of(tidal))
    }

    @Test
    fun aSongTheAppPlaysOnlyPartOfBeforeTheLineIsNotPlayedThereWhenTheCardIsTurnedOnTheWay() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        youTubeHasTheSong()
        keep(line())
        val tidal = MusicService.TIDAL.packageName
        installed(tidal, preferred = Destination.Service(MusicService.TIDAL))
        appPlays(tidal, canSeek = false)
        val flow = playback.appPlaying.getValue(tidal)
        val preview = flow.value!!.copy(durationMs = 8_000, canSeek = true)
        val cards = cards()
        cards.start(null, before = null)
        // Still on its way to where it can be moved as the card's turned: what it plays is then for the back, to be heard.
        waitFor("it's tried") { steps().isNotEmpty() }
        cards.turn()
        flow.value = preview
        waitFor("the player is on the line") { player.shown.isNotEmpty() }
        assertEquals(listOf("trying $tidal", "$tidal did not play the line", "the YouTube player"), steps())
        assertEquals(AppTraits(keeps = false), traits.of(tidal))
    }

    @Test
    fun aLineThatRunsPastTheSongsEndIsPausedBeforeTheAppGoesOnToTheNext() {
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        installed(ytMusic, preferred = Destination.Service(MusicService.YOUTUBE_MUSIC))
        appPlays(ytMusic, speed = 1f)
        val flow = playback.appPlaying.getValue(ytMusic)
        // The song ends at 11.5 s, before the line's end and its tail.
        flow.value = flow.value!!.copy(durationMs = 11_500)
        var pausedAt: Long? = null
        playback.onPlay = { _, play -> if (!play && playback.plays.contains(true)) pausedAt = pausedAt ?: flow.value!!.clock.positionAt(System.currentTimeMillis()) }
        cards().start(null, before = null)
        waitFor("it played the line") { traits.of(ytMusic).works && practice?.sound == LineSound.PLAYED }
        // Paused before its end, so it never starts the next song, rather than at the line's.
        assertTrue("paused at $pausedAt", pausedAt!! in 11_500L - LineWindow.END_GUARD_MS until 15_000L)
        assertTrue(player.shown.isEmpty())
    }

    @Test
    fun anAppThatGoesOnToAnotherSongIsStoppedAtOnceNotAMomentLater() {
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        val tidal = MusicService.TIDAL.packageName
        installed(tidal, preferred = Destination.Service(MusicService.TIDAL))
        appPlays(tidal, speed = 1f)
        val flow = playback.appPlaying.getValue(tidal)
        var goneAt = 0L
        playback.onPlay = { app, play ->
            if (app == tidal && play) thread {
                Thread.sleep(200)
                goneAt = System.currentTimeMillis()
                flow.value = null
            }
        }
        cards().start(null, before = null)
        waitFor("it's paused") { playback.pausedApps.isNotEmpty() }
        val heardFor = System.currentTimeMillis() - goneAt
        // Not the 1.5 s an app that only pauses a moment is given, at the least: the next song would be heard all that time.
        assertTrue("the next song played $heardFor ms", heardFor < 1_500)
    }

    @Test
    fun anAppThatPlaysSomewhereElseThanTheLineIsStoppedAtOnce() {
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        val tidal = MusicService.TIDAL.packageName
        installed(tidal, preferred = Destination.Service(MusicService.TIDAL))
        appPlays(tidal, speed = 1f)
        val flow = playback.appPlaying.getValue(tidal)
        var movedAt = 0L
        // As TIDAL past its 30 s preview: still the song it says, whole, but playing from 0, which is the next one.
        playback.onPlay = { app, play ->
            if (app == tidal && play) thread {
                Thread.sleep(200)
                movedAt = System.currentTimeMillis()
                flow.value = flow.value?.copy(clock = PlaybackClock(0, movedAt, 1f))
            }
        }
        cards().start(null, before = null)
        waitFor("it's paused") { playback.pausedApps.isNotEmpty() }
        val heardFor = System.currentTimeMillis() - movedAt
        // Not the 1.5 s an app that only pauses a moment is given: what it plays would be heard all that time.
        assertTrue("it played elsewhere $heardFor ms", heardFor < 1_500)
    }

    @Test
    fun anAppStoppedOnItsWayToItsNextSongIsKeptPausedWhenItStartsIt() {
        val (tidal, flow) = tidalGoesOnToItsNextSong()
        // As TIDAL: the pause came while it was between songs, so it starts the next one anyway, a moment later.
        flow.value = Following(PlaybackClock(0, System.currentTimeMillis(), 1f), "Music", canSeek = true, appPackage = tidal, durationMs = 200_000)
        waitFor("it's paused again") { flow.value?.clock?.playing == false }
    }

    @Test
    fun anAppKeptPausedStaysPausedWhileTheNextAppPlaysTheLine() {
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        val tidal = MusicService.TIDAL.packageName
        installed(tidal, morphe, preferred = Destination.Service(MusicService.TIDAL))
        appPlays(tidal, speed = 1f)
        appPlays(morphe, speed = 1f, positionMs = 9_500)
        val flow = playback.appPlaying.getValue(tidal)
        playback.onPlay = { app, play -> if (app == tidal && play) flow.value = null }
        cards().start(null, before = null)
        waitFor("Morphe is followed") { morphe in playback.followedApps }
        // TIDAL, stopped on its way to its next song, starts it while Morphe plays the line: it's still kept paused.
        flow.value = Following(PlaybackClock(0, System.currentTimeMillis(), 1f), "Music", canSeek = true, appPackage = tidal, durationMs = 200_000)
        waitFor("it's paused again") { flow.value?.clock?.playing == false }
    }

    @Test
    fun anAppAskedFromBehindThatStartsOnceGivenUpOnIsKeptPaused() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        youTubeHasTheSong()
        keep(line())
        val tidal = MusicService.TIDAL.packageName
        installed(tidal, preferred = Destination.Service(MusicService.TIDAL))
        playback.access = true
        playback.startsInBackground = false
        val flow = MutableStateFlow<Following?>(null)
        playback.appPlaying[tidal] = flow
        cards().start(null, before = null)
        waitFor("the player is on the line") { player.shown.isNotEmpty() }
        assertEquals(listOf("trying $tidal", "$tidal did not move to the line", "the YouTube player"), steps())
        // As TIDAL woken cold: it starts the song from its start a second after it's given up on.
        flow.value = Following(PlaybackClock(0, System.currentTimeMillis(), 1f), "Music", canSeek = true, appPackage = tidal, durationMs = 200_000)
        waitFor("it's paused") { flow.value?.clock?.playing == false }
    }

    @Test
    fun aCardDoesNotWaitOnAnAppThatCantBeAskedWhileAnotherPlays() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        youTubeHasTheSong()
        keep(line())
        val tidal = MusicService.TIDAL.packageName
        val qobuz = MusicService.QOBUZ.packageName
        installed(qobuz, preferred = Destination.Service(MusicService.QOBUZ))
        // TIDAL plays something else; Qobuz isn't running and can't be woken.
        appPlays(tidal)
        playback.running = setOf(tidal)
        playback.appPlaying[qobuz] = MutableStateFlow(null)
        val started = System.currentTimeMillis()
        cards().start(null, before = null)
        waitFor("the player is on the line") { player.shown.isNotEmpty() }
        assertEquals(listOf("trying $qobuz", "$qobuz did not move to the line", "the YouTube player"), steps())
        // Not swapped for TIDAL, which would play the song while the card waits on Qobuz until it gives up.
        assertTrue(playback.songsAsked.isEmpty())
        assertTrue(System.currentTimeMillis() - started < MainViewModel.LOAD_SONG_MS)
    }

    /**
     * Holds answers from a host whose name contains one of [parts] until the test lets them go.
     * The fakes answer at once, so a wait only shows when one is held. These are the fakes
     * [MainActivity]'s test factories install. The first latch lets the answers go. The second
     * opens once the first held request has started. The wait has no limit: a limit would let the
     * answer through, and the card would look ready while the latch still said the lookup was open.
     */
    private fun holdHosts(vararg parts: String): Pair<CountDownLatch, CountDownLatch> {
        spotifyHasTheSong()
        youTubeHasTheSong()
        val answer = sites.handler
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        sites.handler = { request ->
            if (parts.any { it in request.url.host }) {
                started.countDown()
                gate.await()
            }
            answer(request)
        }
        return gate to started
    }

    /** Spotify's own link, which a card reads from ListenBrainz and then from the song's page. */
    private fun holdSpotifyLink() = holdHosts("listenbrainz.org", "spotify.com")

    @Test
    fun withoutTheOverlayThePlayerStartsWhileASpotifyLookupTheLineDoesNotNeedIsStillWaiting() {
        val (gate, started) = holdSpotifyLink()
        keep(keptFromSpotify)
        installed(spotify, ytMusic)
        playback.access = true
        playback.comesBackByItself = false
        // YouTube Music without Premium will not play behind Crosstune, so the line goes to the player.
        playback.startsInBackground = false
        val cards = cards()
        val startedAt = System.currentTimeMillis()
        try {
            cards.start(null, before = null)
            waitFor("the player is on the line", seconds = 15) { player.shown.isNotEmpty() }
            val waited = System.currentTimeMillis() - startedAt
            // The phone measured 6.05 s for this card, and 1.84 s once the link was remembered.
            // A lookup that is still open is given up after 5 s, so the player has to be up before that.
            assertTrue("the player waited $waited ms", waited < 3_000)
            assertEquals(1, gate.count)
            assertTrue(practice!!.cantComeBack)
            assertTrue(playback.opened.isEmpty())
            assertEquals(Shown("demo0000000", 10_000, 15_000 + MainViewModel.LINE_TAIL_MS, null, play = false), player.shown.single())
            // The link is still read, so a later card that can open Spotify already has it. Dropping that read leaves this closed.
            assertTrue(started.await(5, TimeUnit.SECONDS))
            cards.replay()
            waitFor("the line plays again") { player.shown.size >= 2 }
            assertEquals(1, gate.count)
        } finally {
            gate.countDown()
        }
    }

    @Test
    fun withoutTheOverlayALineAlreadyPlayingInAnotherAppIsReadyWhileASpotifyLookupIsStillWaiting() {
        val (gate, started) = holdSpotifyLink()
        keep(keptFromSpotify)
        installed(spotify, morphe)
        playback.comesBackByItself = false
        // Spotify would have to be opened on its own link. Morphe already plays the song, so the line does not need that link.
        appPlays(morphe, speed = 1f, positionMs = 9_500)
        val startedAt = System.currentTimeMillis()
        try {
            cards().start(null, before = null)
            waitFor("the line is ready", seconds = 15) { practice?.sound == LineSound.PLAYED }
            val waited = System.currentTimeMillis() - startedAt
            assertTrue("the line waited $waited ms", waited < 3_000)
            assertEquals(1, gate.count)
            assertEquals(listOf<String?>(morphe), playback.seekedApps.toList())
            assertTrue(player.shown.isEmpty())
            assertTrue(practice!!.cantComeBack)
            assertTrue(playback.opened.isEmpty())
            assertTrue(started.await(5, TimeUnit.SECONDS))
        } finally {
            gate.countDown()
        }
    }

    @Test
    fun withoutTheOverlayTheCardSaysItCantComeBackWhileTheVideoLookupIsStillWaiting() {
        val (gate, started) = holdHosts("music.youtube.com")
        keep(keptFromSpotify)
        installed(spotify, ytMusic)
        playback.access = true
        playback.comesBackByItself = false
        playback.startsInBackground = false
        val cards = cards()
        try {
            cards.start(null, before = null)
            // The video is what the player needs. The card can say why the apps were left out before that answer arrives.
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertEquals(1, gate.count)
            assertTrue(practice!!.cantComeBack)
            assertTrue(player.shown.isEmpty())
            assertTrue(playback.opened.isEmpty())
            gate.countDown()
            waitFor("the player is on the line") { player.shown.isNotEmpty() }
            assertEquals(Shown("demo0000000", 10_000, 15_000 + MainViewModel.LINE_TAIL_MS, null, play = false), player.shown.single())
        } finally {
            gate.countDown()
        }
    }

    @Test
    fun withoutTheOverlayAnAppThatCanBeAskedFromBehindIsStillAsked() {
        youTubeHasTheSong()
        keep(line())
        val tidal = MusicService.TIDAL.packageName
        installed(tidal, preferred = Destination.Service(MusicService.TIDAL))
        playback.access = true
        playback.comesBackByItself = false
        cards(songLoadMs = 500).start(null, before = null)
        waitFor("the player is on the line", seconds = 15) { player.shown.isNotEmpty() }
        // TIDAL plays a song it is asked for by name. Leaving it out before that ask would skip an app that can play the line.
        assertEquals(tidal, playback.songsAsked.single().second)
        assertTrue(playback.opened.isEmpty())
    }

    @Test
    fun withTheOverlaySpotifyStaysClosedWhileItsOwnLinkIsStillBeingLookedUp() {
        val (gate, started) = holdSpotifyLink()
        keep(keptFromSpotify)
        installed(spotify)
        playback.access = true
        val cards = cards()
        try {
            cards.start(null, before = null)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            Thread.sleep(300)
            // Showing over other apps, the link is what opens Spotify. The card waits for it.
            assertEquals(1, gate.count)
            assertTrue(playback.opened.isEmpty())
            assertTrue(player.shown.isEmpty())
            gate.countDown()
            waitFor("Spotify opens") { playback.opened.isNotEmpty() }
            assertEquals(spotify, playback.opened.single().first)
        } finally {
            gate.countDown()
        }
    }

    @Test
    fun withTheOverlayASongTheAppAlreadyPlaysIsReadyWhileASpotifyLookupIsStillWaiting() {
        val (gate, started) = holdSpotifyLink()
        keep(keptFromSpotify)
        installed(spotify)
        playback.comesBackByItself = true
        appPlays(spotify, speed = 5f, positionMs = 10_000)
        try {
            cards().start(null, before = null)
            waitFor("the line is ready") { practice?.sound == LineSound.PLAYED }
            // The app already has the song, so the card does not read a link to open it.
            assertEquals(1, gate.count)
            assertEquals(1, started.count)
            assertTrue(playback.opened.isEmpty())
            assertTrue(player.shown.isEmpty())
            assertEquals(listOf<String?>(spotify), playback.seekedApps.toList())
        } finally {
            gate.countDown()
        }
    }

    @Test
    fun anAppKeptPausedTwiceInOneCardIsLetGoOfBothTimesWhenItPlaysTheLine() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        youTubeHasTheSong()
        val amazon = MusicService.AMAZON_MUSIC.packageName
        val bandcamp = MusicService.BANDCAMP.packageName
        // Kept from Amazon, it's tried though its store has no link for the song.
        keep(line().copy(app = amazon), types = hearIt)
        // Bandcamp and Amazon play nothing asked of them, and neither plays from its own link, so YouTube Music is asked in
        // their place. Without showing over other apps it can't be opened, and asked from behind it doesn't start: it's kept
        // paused, once for each. Spotify is not one of them: its link is not needed to leave it out, so it is not asked here.
        installed(bandcamp, amazon, ytMusic, preferred = Destination.Service(MusicService.BANDCAMP))
        appPlays(ytMusic, speed = 1f)
        playback.comesBackByItself = false
        playback.startsInBackground = false
        playback.obeys = true
        cards().start(null, before = null)
        waitFor("the line is played, there or in the player") { practice?.sound == LineSound.PLAYED || player.shown.isNotEmpty() }
        // Followed itself next, it's let go of both, and plays the line through: paused by the first, it would stop on the way.
        assertEquals(listOf("trying $bandcamp", "$ytMusic would cover the card", "$bandcamp did not move to the line", "trying $amazon", "$ytMusic would cover the card", "$amazon did not move to the line", "trying $ytMusic", "$ytMusic played the line from its start"), steps())
    }

    @Test
    fun anAppKeptPausedIsLetPlayWhatPlayedBeforePractice() {
        val (tidal, flow) = tidalGoesOnToItsNextSong()
        // Done, what played before goes on in whichever app has it, here TIDAL, which is then left to play.
        tidalCards.backToBefore()
        flow.value = Following(PlaybackClock(0, System.currentTimeMillis(), 1f), "Music", canSeek = true, appPackage = tidal, durationMs = 200_000)
        Thread.sleep(300)
        assertEquals(true, flow.value?.clock?.playing)
    }

    private lateinit var tidalCards: PracticeCards

    /** TIDAL, asked to play a card's line, goes on to its next song instead, and is stopped: its package and what it plays. */
    private fun tidalGoesOnToItsNextSong(): Pair<String, MutableStateFlow<Following?>> {
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        val tidal = MusicService.TIDAL.packageName
        installed(tidal, preferred = Destination.Service(MusicService.TIDAL))
        appPlays(tidal, speed = 1f)
        val flow = playback.appPlaying.getValue(tidal)
        playback.onPlay = { app, play -> if (app == tidal && play) flow.value = null }
        tidalCards = cards()
        tidalCards.start(null, before = Before(MusicMetadata("Before", "Band"), 4_000, null))
        waitFor("it's stopped") { playback.pausedApps.isNotEmpty() }
        return tidal to flow
    }
}

private const val SPOTIFY_ID = "1demoAAAAAAAAAAAAAAAAA"

/** A step's offset from the line's start, as a card logs where an app played it from. */
private val PLAYED_FROM = Regex("played the line from ([+-]?\\d+) ms")

/** The log tag a card's steps go to when it's asked for, which `adb shell setprop` names. */
private const val CHAIN_LOG = "CrosstuneChain"
