package com.astrovm.crosstune

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ApplicationProvider
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

/** The cards of a practice session on their own: what each ask does, and does nothing about, when there's nothing to act on. */
@RunWith(RobolectricTestRunner::class)
class PracticeCardsTest {
    private val screen = mutableStateOf(UiState())
    private val playback = FakePlayback()
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

    private fun waitFor(what: String, seconds: Long = 10, until: () -> Boolean) {
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
        playing(canSeek = false)
        waitFor("it comes back") { playback.cameBack == 1 }
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
        waitFor("it comes back at the end of the wait", seconds = 15) { playback.cameBack == 1 }
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
        // Turned, the line plays there, and that it keeps playing is remembered, too.
        playback.appPlaying.getValue(morphe).value = Following(PlaybackClock(10_000, System.currentTimeMillis(), 20f), "Music", canSeek = true, appPackage = morphe, durationMs = 200_000)
        cards.turn()
        waitFor("it's remembered") { traits.of(morphe).works }
        assertEquals(LineSound.PLAYED, practice?.sound)
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
        waitFor("the player is on the line", seconds = 20) { player.shown.isNotEmpty() }
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
    fun songLinesNeverTakeABlankWordForOneWorthKnowing() {
        val lines = songLines(listOf("Hello there", "hello there", "Other"), listOf(" ", "xyz", "other"))
        assertEquals(listOf(2, 1), lines.map { it.times })
        assertEquals(listOf(false, true), lines.map { it.newWords })
    }
}

private const val SPOTIFY_ID = "1demoAAAAAAAAAAAAAAAAA"
