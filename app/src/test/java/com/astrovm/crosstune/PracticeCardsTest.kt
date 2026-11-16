package com.astrovm.crosstune

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
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
    /** The first error a card's work threw, which the scope would otherwise swallow, leaving a wait to run out. */
    private val crashed = AtomicReference<Throwable?>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> crashed.compareAndSet(null, error) })
    private val store = SavedLinesStore(ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("practice-cards", Context.MODE_PRIVATE))

    @After
    fun stop() = scope.cancel()

    private val sites = FakeSpotify()

    private val player = FakeLinePlayer(ApplicationProvider.getApplicationContext())

    private var wall = 1_000_000L

    /** What the phone has seen each music app do. */
    private val traits = AppTraitsStore(ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("practice-traits", Context.MODE_PRIVATE).also { it.edit().clear().commit() }) { wall }

    private fun cards(
        source: PlaybackSource? = playback, songLoadMs: Long = MainViewModel.LOAD_SONG_MS, player: FakeLinePlayer? = this.player,
        now: () -> Long = { System.currentTimeMillis() }, matcher: ExactMatcher = ExactMatcher(sites.client(), "AR")
    ) = PracticeCards(screen, scope, source, matcher, store, traits, { 1_000_000L }, now, songLoadMs = songLoadMs, newPlayer = player?.let { made -> { made } })

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
            crashed.get()?.let { throw AssertionError("A card's work failed while waiting for: $what", it) }
            check(System.currentTimeMillis() < deadline) { "Never got to: $what" }
            Thread.sleep(20)
        }
    }

    /** Waits for the small player to be on the line, and fails at once when the card settles on no sound, as it then never will be. */
    private fun waitForThePlayer(what: String = "the player is on the line", seconds: Long = 60) {
        waitFor(what, seconds) { player.shown.isNotEmpty() || practice?.sound == LineSound.NONE }
        check(player.shown.isNotEmpty()) { "The card has no sound, so never got to: $what" }
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
    fun aCardsLineIsReadAgainAwayFromTheScreensThread() {
        keep(line("夜の空").copy(reading = "よるのそら", romanized = "yoru no sora", ruby = listOf(Ruby("夜", "よる"), Ruby("の"), Ruby("空", "そら"))))
        // Holds what it's given, until the test runs it.
        val held = LinkedBlockingQueue<Runnable>()
        val reading = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                held += block
            }
        }
        val cards = PracticeCards(screen, scope, null, ExactMatcher(sites.client(), "AR"), store, traits, { 1_000_000L }, { System.currentTimeMillis() }, readDispatcher = reading)
        cards.start(null, before = null)
        cards.readAs(listOf("よ", "そら"))
        // Asked, it's read on the dispatcher given, the dictionary loading there the first time, and not before it runs.
        val read = checkNotNull(held.poll(60, TimeUnit.SECONDS))
        assertEquals("よるのそら", store.load().single().reading)
        read.run()
        waitFor("the line read again") { store.load().single().reading == "よのそら" }
        waitFor("the card read again") { practice?.line?.reading == "よのそら" }
        assertTrue(store.load().single().readByHand)
    }

    @Test
    fun twoEditsOfACardMadeOneAfterTheOtherBothStayWhicheverIsReadFirstAndSoDoesWhatUtaTenPutRightMeanwhile() {
        val kept = line("夜の空と海").copy(
            reading = "よるのそらとうみ", romanized = "yoru no sora to umi",
            ruby = listOf(Ruby("夜", "よる"), Ruby("の"), Ruby("空", "そら"), Ruby("と"), Ruby("海", "うみ"))
        )
        keep(kept)
        val held = LinkedBlockingQueue<Runnable>()
        val reading = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                held += block
            }
        }
        val cards = PracticeCards(screen, scope, null, ExactMatcher(sites.client(), "AR"), store, traits, { 1_000_000L }, { System.currentTimeMillis() }, readDispatcher = reading)
        cards.start(null, before = null)
        // The first edit puts 夜 right, the second, made on the card as shown before the first is read, 空.
        cards.readAs(listOf("よ", "そら", "うみ"))
        val first = checkNotNull(held.poll(60, TimeUnit.SECONDS))
        cards.readAs(listOf("よる", "から", "うみ"))
        // Meanwhile UtaTen puts 海 right, as it's sung.
        screen.value = screen.value.copy(savedLines = store.reread(listOf(kept.copy(reading = "よるのそらとわだつみ", ruby = listOf(Ruby("夜", "よる"), Ruby("の"), Ruby("空", "そら"), Ruby("と"), Ruby("海", "わだつみ", Reader.SUNG))))))
        // Whatever the dispatcher runs first, the later edit's or the earlier's, all three stay.
        held.poll(2, TimeUnit.SECONDS)?.run()
        first.run()
        val deadline = System.currentTimeMillis() + 60_000
        while (store.load().single().ruby?.any { it.by == Reader.USER && it.text == "空" } != true && System.currentTimeMillis() < deadline) {
            held.poll(100, TimeUnit.MILLISECONDS)?.run()
        }
        val parts = listOf(Ruby("夜", "よ", Reader.USER), Ruby("空", "から", Reader.USER), Ruby("海", "わだつみ", Reader.SUNG))
        waitFor("both edits kept, and UtaTen's") { store.load().single().ruby?.filter { it.reading != null } == parts }
        assertEquals("よのからとわだつみ", store.load().single().reading)
        assertTrue(store.load().single().readByHand)
        waitFor("the card read as kept") { practice?.line?.ruby?.filter { it.reading != null } == parts }
        assertEquals(store.load().single(), screen.value.savedLines.single())
    }

    @Test
    fun withoutAMusicAppACardHasNoSoundAndIsStillPractised() {
        keep(line())
        val cards = cards(source = null)
        cards.start(null, before = null)
        // YouTube has no video of it either.
        waitFor("no sound") { practice?.sound == LineSound.NONE }
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
    fun withoutSeeingWhatPlaysNoAppIsFollowedAndACardWithNoVideoIsSilent() {
        keep(line())
        cards().start(null, before = null)
        waitFor("no sound") { practice?.sound == LineSound.NONE }
        assertTrue(playback.followed.isEmpty())
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
    fun aSongNoAppHasOpenAndNoVideoOfLeavesTheCardSilent() {
        keep(line())
        playback.access = true
        cards().start(null, before = null)
        assertEquals(LineSound.LOADING, practice!!.sound)
        waitFor("it gave up") { practice?.sound == LineSound.NONE }
        assertTrue(playback.seeks.isEmpty())
    }

    @Test
    fun stoppingWhileNoSessionHasAnsweredPausesNothing() {
        keep(line())
        playback.access = true
        val cards = cards()
        cards.start(null, before = null)
        cards.stop()
        assertNull(practice)
        assertTrue(playback.plays.isEmpty())
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
        waitFor("the reading put right") { practice?.line?.readByHand == true }
        assertTrue(practice!!.line!!.reading!!.startsWith("りゅう"))
        assertTrue(screen.value.savedLines.single().readByHand)
    }

    @Test
    fun nativeSoundPendingRequestIsStoppedInTheAppItControlled() {
        keep(line(), types = hearIt)
        appPlays(ytMusic, speed = 1f)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        playback.onPlay = { _, play ->
            if (play) {
                entered.countDown()
                check(release.await(60, TimeUnit.SECONDS))
            }
        }
        val cards = cards()
        cards.start(null, before = null)
        try {
            assertTrue(entered.await(60, TimeUnit.SECONDS))
            cards.stop()
            assertEquals(ytMusic, playback.playedApps.last())
            assertFalse(playback.playing.value!!.clock.playing)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun nativeSoundBackgroundCancelsAHeldLookupAndReplayStillWorks() {
        keep(line(), types = hearIt)
        youTubeHasTheSong()
        val (release, entered) = holdHosts("music.youtube.com")
        val cards = cards(source = null)
        cards.start(null, before = null)
        try {
            assertTrue(entered.await(60, TimeUnit.SECONDS))
            val pending = scope.coroutineContext[Job]!!.children.toList()
            cards.background()
            assertEquals(LineSound.PLAYED, practice?.sound)
            release.countDown()
            waitFor("the cancelled lookup completes") { pending.all { it.isCompleted } }
            assertTrue("the late answer started hidden audio", player.shown.isEmpty())
        } finally {
            release.countDown()
        }
        cards.replay()
        waitForThePlayer()
        assertEquals(1, player.shown.size)
        assertTrue(player.shown.single().play)
    }

    @Test
    fun nativeSoundPendingReplayCannotStartAfterBackgrounding() {
        keep(line())
        appPlays(ytMusic)
        val cards = cards()
        cards.start(null, before = null)
        waitFor("the silent front is prepared") { practice?.sound == LineSound.PLAYED }
        val waiting = kotlinx.coroutines.CompletableDeferred<Unit>()
        playback.said = waiting
        val followed = playback.followed.size
        val played = playback.plays.count { it }
        cards.replay()
        waitFor("the replay is waiting for its session") { playback.followed.size > followed }
        assertEquals(LineSound.PLAYED, practice?.sound)
        val pending = scope.coroutineContext[Job]!!.children.toList()
        cards.background()
        waiting.complete(Unit)
        waitFor("the background replay ends") { pending.all { it.isCompleted } }
        assertEquals("a pending replay started hidden sound", played, playback.plays.count { it })
        assertTrue("a pending replay was not cancelled", pending.all { it.isCancelled })
    }

    @Test
    fun nativeSoundFullRecordingWinsOverAPlayingPreviewTooShortForTheLine() {
        keep(line(start = 60_000), types = hearIt)
        appPlays(spotify)
        playback.playing.value = playback.playing.value!!.copy(durationMs = 30_000)
        playback.appPlaying[ytMusic] = MutableStateFlow(Following(PlaybackClock(0, System.currentTimeMillis(), 20f, playing = false), "Music", canSeek = true, appPackage = ytMusic, durationMs = 200_000))
        cards().start(null, before = null)
        waitFor("the chosen sound settles") { practice?.sound == LineSound.PLAYED || practice?.sound == LineSound.NONE }
        assertEquals(listOf(ytMusic), playback.seekedApps.toList())
        assertEquals(LineSound.PLAYED, practice?.sound)
        assertTrue(player.shown.isEmpty())
    }

    @Test
    fun nativeSoundFullRecordingCanBecomeSeekableAfterSelection() {
        keep(line(start = 60_000))
        appPlays(spotify)
        playback.playing.value = playback.playing.value!!.copy(durationMs = 30_000)
        val full = MutableStateFlow<Following?>(Following(PlaybackClock(0, System.currentTimeMillis(), playing = false), "Music", canSeek = false, appPackage = ytMusic, durationMs = 200_000))
        playback.appPlaying[ytMusic] = full
        cards().start(null, before = null)
        waitFor("a session is being followed") { playback.followed.isNotEmpty() }
        full.value = full.value?.copy(canSeek = true)
        waitFor("the late-ready recording settles") { practice?.sound == LineSound.PLAYED || practice?.sound == LineSound.NONE }
        assertEquals(listOf(ytMusic), playback.seekedApps.toList())
        assertEquals(LineSound.PLAYED, practice?.sound)
        assertTrue(player.shown.isEmpty())
    }

    @Test
    fun nativeSoundRemainingSessionCanBecomeReadyWhileTheFirstFails() {
        keep(line())
        appPlays(spotify)
        playback.obeys = false
        val full = MutableStateFlow<Following?>(Following(PlaybackClock(0, System.currentTimeMillis(), playing = false), "Music", canSeek = false, appPackage = ytMusic))
        playback.appPlaying[ytMusic] = full
        playback.onPlay = { app, play ->
            if (app == ytMusic) full.value = full.value?.copy(clock = PlaybackClock(10_000, System.currentTimeMillis(), playing = play))
        }
        cards().start(null, before = null)
        waitFor("the first control is pending") { playback.seeks.isNotEmpty() }
        full.value = full.value?.copy(canSeek = true)
        waitFor("the later candidate settles") { practice?.sound == LineSound.PLAYED || practice?.sound == LineSound.NONE }
        assertEquals(listOf(spotify, ytMusic), playback.seekedApps.toList().distinct())
        assertEquals(LineSound.PLAYED, practice?.sound)
        assertTrue(player.shown.isEmpty())
    }

    @Test
    fun nativeSoundTriesRemainingMatchingSessionsAfterAControlFails() {
        keep(line())
        appPlays(spotify)
        playback.obeys = false
        val full = MutableStateFlow<Following?>(Following(PlaybackClock(0, System.currentTimeMillis(), playing = false), "Music", canSeek = true, appPackage = ytMusic))
        playback.appPlaying[ytMusic] = full
        playback.onPlay = { app, play ->
            if (app == ytMusic) full.value = full.value?.copy(clock = PlaybackClock(10_000, System.currentTimeMillis(), playing = play))
        }
        cards().start(null, before = null)
        waitFor("the fallback session settles") { practice?.sound == LineSound.PLAYED || practice?.sound == LineSound.NONE }
        assertEquals(listOf(spotify, ytMusic), playback.seekedApps.toList().distinct())
        assertEquals(LineSound.PLAYED, practice?.sound)
        assertTrue(player.shown.isEmpty())
    }

    @Test
    fun nativeSoundUsesAnotherMatchingSeekableSession() {
        keep(line(), types = hearIt)
        appPlays(spotify, canSeek = false)
        playback.appPlaying[ytMusic] = MutableStateFlow(Following(PlaybackClock(0, System.currentTimeMillis(), 20f, playing = false), "Music", canSeek = true, appPackage = ytMusic))
        cards().start(null, before = null)
        waitFor("the second session plays the line") { practice?.sound == LineSound.PLAYED }
        assertEquals(listOf(ytMusic), playback.seekedApps.toList())
        assertFalse(playback.playing.value!!.clock.playing)
        assertTrue(player.shown.isEmpty())
    }

    @Test
    fun nativeSoundUsesAnotherMatchingSessionWhenTheFirstHasFailed() {
        keep(line())
        appPlays(spotify)
        playback.playing.value = playback.playing.value!!.copy(ad = true, clock = PlaybackClock(0, System.currentTimeMillis(), playing = false))
        traits.keeps(spotify, false)
        playback.appPlaying[ytMusic] = MutableStateFlow(Following(PlaybackClock(0, System.currentTimeMillis(), playing = false), "Music", canSeek = true, appPackage = ytMusic))
        cards().start(null, before = null)
        waitFor("the suitable session is ready") { practice?.sound == LineSound.PLAYED }
        assertEquals(listOf(spotify, ytMusic), playback.playedApps.toList())
        assertEquals(listOf(ytMusic), playback.seekedApps.toList())
        assertFalse(playback.playing.value!!.clock.playing)
        assertTrue(player.shown.isEmpty())
    }

    @Test
    fun nativeSoundKnownFailedSessionIsPausedEvenOnAReadingFront() {
        keep(line())
        youTubeHasTheSong()
        appPlays(spotify)
        traits.keeps(spotify, false)
        cards().start(null, before = null)
        waitForThePlayer()
        assertFalse(playback.playing.value!!.clock.playing)
        assertFalse(player.shown.single().play)
        assertTrue(playback.seeks.isEmpty())
    }

    @Test
    fun nativeSoundKnownFailedAdIsPausedBeforeTheEmbeddedPlayer() {
        keep(line(), types = hearIt)
        youTubeHasTheSong()
        appPlays(spotify)
        // A native ad reports the song clock as paused, although the ad itself is audible.
        playback.playing.value = playback.playing.value!!.copy(ad = true, clock = PlaybackClock(0, System.currentTimeMillis(), playing = false))
        traits.keeps(spotify, false)
        cards().start(null, before = null)
        waitForThePlayer()
        assertEquals(listOf(spotify), playback.playedApps.toList())
        assertEquals(listOf(false), playback.plays.toList())
    }

    @Test
    fun nativeSoundAnonymousSessionStopsOnBackgroundReplaysAndStopsOnRelease() {
        keep(line(), types = hearIt)
        playback.access = true
        playback.playing.value = Following(PlaybackClock(10_000, System.currentTimeMillis(), playing = false), "Music", canSeek = true)
        val cards = cards()
        cards.start(null, before = null)
        waitFor("the anonymous session plays") { true in playback.plays && practice?.sound == LineSound.PLAYING }
        cards.background()
        assertFalse(playback.playing.value!!.clock.playing)
        assertEquals(LineSound.PLAYED, practice?.sound)
        cards.replay()
        waitFor("the anonymous session replays") { playback.plays.count { it } == 2 && practice?.sound == LineSound.PLAYING }
        cards.release()
        assertFalse(playback.playing.value!!.clock.playing)
        assertEquals(listOf(true, false, true, false), playback.plays.toList())
        assertTrue(playback.playedApps.all { it == null })
    }

    @Test
    fun nativeSoundAnonymousSessionCanFinishTheClip() {
        keep(line(), types = hearIt)
        playback.access = true
        playback.playing.value = Following(PlaybackClock(10_000, System.currentTimeMillis(), 20f), "Music", canSeek = true)
        cards().start(null, before = null)
        waitFor("the anonymous session finishes") { practice?.sound == LineSound.PLAYED }
        assertEquals(listOf(true, false), playback.plays.toList())
        assertTrue(playback.playedApps.all { it == null })
        assertFalse(playback.playing.value!!.clock.playing)
    }

    @Test
    fun nativeSoundClosingAFallbackCancelsItsRepeatedNativePauses() {
        for (leave in listOf<(PracticeCards) -> Unit>({ it.stop() }, { it.background() }, { it.release() })) {
            keep(line())
            youTubeHasTheSong()
            appPlays(spotify)
            playback.playing.value = playback.playing.value!!.copy(durationMs = 8_000)
            val (release, entered) = holdHosts("music.youtube.com")
            val cards = cards()
            cards.start(null, before = null)
            try {
                assertTrue(entered.await(60, TimeUnit.SECONDS))
                val pending = scope.coroutineContext[Job]!!.children.filter { it.isActive }.toList()
                assertTrue("the failed app has no repeated stop to test", pending.size >= 2)
                leave(cards)
                assertTrue("a closed card can still pause the music app", pending.none { it.isActive })
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun nativeSoundBackgroundAlsoCancelsRepeatedPausesOnAPreparedSilentFront() {
        keep(line())
        youTubeHasTheSong()
        appPlays(spotify)
        playback.playing.value = playback.playing.value!!.copy(durationMs = 8_000)
        val cards = cards()
        cards.start(null, before = null)
        waitForThePlayer()
        assertEquals(LineSound.PLAYED, practice?.sound)
        val pending = scope.coroutineContext[Job]!!.children.filter { it.isActive }.toList()
        assertTrue("the repeated stop must still be active", pending.isNotEmpty())
        cards.background()
        assertTrue("a background card can still pause the music app", pending.none { it.isActive })
    }

    @Test
    fun nativeSoundSlowCapabilityDoesNotPersistAFailure() {
        keep(line())
        youTubeHasTheSong()
        appPlays(spotify, canSeek = false)
        val cards = cards()
        cards.start(null, before = null)
        waitForThePlayer()
        assertEquals(AppTraits(), traits.of(spotify))
        appPlays(spotify)
        cards.stop()
        player.shown.clear()
        cards.start(null, before = null)
        waitFor("the later seekable session is ready") { playback.seeks.isNotEmpty() && practice?.sound == LineSound.PLAYED }
        assertTrue(player.shown.isEmpty())
    }

    @Test
    fun nativeSoundLongAdDoesNotPersistAFailureBeforeAnyControl() {
        keep(line())
        youTubeHasTheSong()
        appPlays(ytMusic)
        playback.playing.value = playback.playing.value!!.copy(ad = true)
        val cards = cards()
        cards.start(null, before = null)
        waitForThePlayer()
        assertTrue(playback.seeks.isEmpty())
        assertEquals(AppTraits(), traits.of(ytMusic))
        playback.playing.value = playback.playing.value!!.copy(ad = false)
        cards.stop()
        player.shown.clear()
        cards.start(null, before = null)
        waitFor("the session after its ad is ready") { playback.seeks.isNotEmpty() && practice?.sound == LineSound.PLAYED }
        assertTrue(player.shown.isEmpty())
    }

    @Test
    fun nativeSoundPreviewLengthIsNotTheEmbeddedRecordingLength() {
        keep(line())
        youTubeHasTheSong()
        appPlays(spotify)
        playback.playing.value = playback.playing.value!!.copy(durationMs = 8_000)
        cards().start(null, before = null)
        waitForThePlayer()
        assertNull(player.shown.single().expectedMs)
    }

    @Test
    fun nativeSoundSavedRecordingLengthIsSentToTheEmbeddedPlayer() {
        keep(line().copy(lengthMs = 200_000))
        youTubeHasTheSong()
        appPlays(spotify)
        playback.playing.value = playback.playing.value!!.copy(durationMs = 8_000)
        cards().start(null, before = null)
        waitForThePlayer()
        assertEquals(200_000L, player.shown.single().expectedMs)
    }

    @Test
    fun nativeSoundDelayedPreparationUsesTheVisibleSidesSound() {
        for (preset in listOf(READ_IT, HEAR_IT.copy(on = true), READ_IT.copy(id = "custom", name = "Custom")))
            for (native in listOf(false, true)) for (front in listOf(false, true)) for (back in listOf(false, true)) {
            val type = preset.copy(options = CardOptions(CardSide(words = true, sound = front), CardSide(words = true, sound = back)))
            keep(line(), types = listOf(type))
            youTubeHasTheSong()
            val (release, entered) = holdHosts("music.youtube.com")
            val said = kotlinx.coroutines.CompletableDeferred<Unit>()
            if (native) {
                appPlays(spotify, speed = 20f)
                playback.said = said
            }
            val cards = cards(source = if (native) playback else null)
            cards.start(null, before = null)
            try {
                if (native) waitFor("the held native preparation") { playback.followed.isNotEmpty() }
                else assertTrue(entered.await(60, TimeUnit.SECONDS))
                cards.turn()
            } finally {
                release.countDown()
                said.complete(Unit)
            }
            if (native) {
                waitFor("native preparation completes") { practice?.sound == LineSound.PLAYED }
                assertEquals("front=$front back=$back", back, true in playback.plays)
            } else {
                waitForThePlayer()
                assertEquals("front=$front back=$back", back, player.shown.last().play)
            }
            cards.stop()
            playback.said = null
            playback.plays.clear()
            playback.followed.clear()
            player.shown.clear()
        }
    }

    @Test
    fun nativeSoundTurningToASilentBackStopsActiveSoundButReplayIsExplicit() {
        for (native in listOf(false, true)) {
            keep(line(), types = listOf(READ_IT.copy(options = CardOptions(CardSide(words = true, sound = true), CardSide(words = true, sound = false)))))
            youTubeHasTheSong()
            if (native) appPlays(spotify, speed = 1f)
            val cards = cards(source = if (native) playback else null)
            cards.start(null, before = null)
            waitFor("front is playing") { if (native) true in playback.plays && practice?.sound == LineSound.PLAYING else player.shown.isNotEmpty() }
            val pauses = player.pauses
            cards.turn()
            assertEquals(LineSound.PLAYED, practice?.sound)
            if (native) assertFalse(playback.playing.value!!.clock.playing) else assertTrue(player.pauses > pauses)
            cards.replay()
            waitFor("explicit replay on the silent side") { if (native) playback.plays.count { it } == 2 else player.shown.size == 2 }
            cards.release()
            if (native) assertFalse(playback.playing.value!!.clock.playing)
            playback.plays.clear()
            player.shown.clear()
        }
    }

    @Test
    fun nativeSoundStopsWhenGradingTheLastCard() {
        val audible = HEAR_IT.copy(on = true, options = CardOptions(CardSide(sound = true), CardSide(sound = true)))
        keep(line("One"), types = listOf(audible))
        appPlays(ytMusic, speed = 1f)
        val cards = cards()
        cards.start(null, before = null)
        waitFor("the native line plays") { practice?.sound == LineSound.PLAYING && playback.plays.contains(true) }
        cards.turn()
        assertTrue("the back must still be audible before grading", playback.playing.value!!.clock.playing)
        cards.grade(Grade.GOOD)
        assertNull(practice?.line)
        assertFalse("the previous line keeps playing", playback.playing.value?.clock?.playing == true)
    }

    @Test
    fun nativeSoundGradingToADifferentSongStopsThePreviousSongsTarget() {
        val audible = HEAR_IT.copy(on = true, options = CardOptions(CardSide(sound = true), CardSide(sound = true)))
        keep(line("One"), line("Two", start = null).copy(title = "Other"), types = listOf(audible))
        appPlays(ytMusic, speed = 1f)
        val controlled = java.util.concurrent.CopyOnWriteArrayList<Pair<MusicMetadata, Boolean>>()
        val source = object : PlaybackSource by playback {
            override fun playPause(song: MusicMetadata, play: Boolean, app: String?) {
                controlled += song to play
                playback.playPause(song, play, app)
            }
        }
        val cards = cards(source = source)
        cards.start(null, before = null)
        waitFor("the previous song is audible") { true in playback.plays && practice?.sound == LineSound.PLAYING }
        cards.turn()
        cards.grade(Grade.GOOD)
        assertEquals("Other", practice?.line?.title)
        assertEquals(MusicMetadata("Demo", "Band") to false, controlled.last())
        assertFalse(playback.playing.value!!.clock.playing)
    }

    @Test
    fun nativeSoundClosingALookupDoesNotPauseAnAppTheCardDidNotControl() {
        keep(line())
        youTubeHasTheSong()
        playback.access = true
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val answer = sites.handler
        sites.handler = { request ->
            if (request.url.host == "music.youtube.com") {
                entered.countDown()
                check(release.await(60, TimeUnit.SECONDS)) { "The held lookup was not released" }
            }
            answer(request)
        }
        val cards = cards()
        cards.start(null, before = null)
        try {
            assertTrue(entered.await(60, TimeUnit.SECONDS))
            // The user starts the same song elsewhere while the embedded player's lookup waits.
            appPlays(spotify, speed = 1f)
            playback.plays.clear()
            cards.stop()
            assertTrue("closing the lookup paused an unowned app", playback.plays.isEmpty())
            assertTrue(playback.playing.value?.clock?.playing == true)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun nativeSoundFromAKnownFailedAppDoesNotPlayOverTheEmbeddedPlayer() {
        keep(line(), types = listOf(HEAR_IT.copy(on = true)))
        youTubeHasTheSong()
        traits.seeks(spotify, false)
        appPlays(spotify, speed = 1f)
        cards().start(null, before = null)
        waitForThePlayer()
        assertFalse("both players are playing", playback.playing.value?.clock?.playing == true)
    }

    @Test
    fun nativeSoundBackToBeforeControlsOnlyTheNamedApp() {
        keep(line(start = null))
        val prior = MusicService.TIDAL.packageName
        appPlays(spotify, speed = 1f, positionMs = 20_000)
        playback.appPlaying[prior] = MutableStateFlow(Following(PlaybackClock(0, System.currentTimeMillis(), playing = false), "TIDAL", canSeek = true, appPackage = prior))
        val cards = cards()
        cards.start(null, before = Before(MusicMetadata("Demo", "Band"), 4_000, prior))
        cards.backToBefore()
        waitFor("the previous song is restored") { playback.seeks.isNotEmpty() && playback.plays.contains(true) }
        assertEquals(listOf(prior), playback.seekedApps.toList())
        assertEquals(listOf(prior), playback.playedApps.toList())
        assertEquals(20_000L, playback.playing.value?.clock?.positionMs)
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
    fun backToBeforeAStationPlaysOnInItsAppWithNowhereToGoBackTo() {
        keep(line())
        playing()
        val cards = cards()
        val station = Unnamed("com.example.radio", "Fake Radio", "Fake FM")
        cards.start(null, before = Before(station.played, 0, station.app, songless = true))
        waitFor("the line is ready") { practice?.sound == LineSound.PLAYED }
        playback.seeks.clear()
        playback.plays.clear()
        playback.playedApps.clear()
        cards.backToBefore()
        assertNull(practice)
        assertEquals(listOf(true), playback.plays.toList())
        assertEquals(listOf<String?>(station.app), playback.playedApps.toList())
        assertTrue(playback.seeks.isEmpty())
    }

    @Test
    fun backToBeforeInAnAppThatHasToBeAskedForItLetsItPlay() {
        keep(line())
        val cards = cards()
        val tidal = MusicService.TIDAL.packageName
        cards.start(null, before = Before(MusicMetadata("Before", "Band"), 4_000, tidal))
        cards.backToBefore()
        waitFor("it's asked for the song") { playback.songsAsked.isNotEmpty() }
        // Only the app it played in is asked, by name, and nothing is opened.
        assertEquals(listOf(MusicMetadata("Before", "Band") to tidal), playback.songsAsked.toList())
        assertTrue(playback.opened.isEmpty())
        // The card's own line was paused as practice closed: only what comes after counts.
        playback.plays.clear()
        // It starts the song it was asked for: that's what was playing, so it isn't paused as a card's line would be.
        playback.playing.value = Following(PlaybackClock(0, System.currentTimeMillis()), "TIDAL", canSeek = true, appPackage = tidal)
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
    fun backToBeforeWithNoAppNamedAsksAndOpensNoAppForIt() {
        keep(line())
        playback.access = true
        val cards = cards(songLoadMs = 300)
        cards.start(null, before = Before(MusicMetadata("Before", "Band"), 4_000, null))
        cards.backToBefore()
        waitFor("what played before is looked for") { playback.followed.any { it.title == "Before" } }
        // No app has it open, and with none named, none is picked to ask in its place.
        Thread.sleep(600)
        assertNoAppBroughtUp()
        assertTrue(playback.seeks.isEmpty())
    }

    @Test
    fun backToBeforeInAnAppThatWontStartFromBehindKeepsItPausedAndOpensNothing() {
        keep(line())
        val tidal = MusicService.TIDAL.packageName
        playback.access = true
        playback.startsInBackground = false
        val cards = cards(songLoadMs = 300)
        cards.start(null, before = Before(MusicMetadata("Before", "Band"), 4_000, tidal))
        cards.backToBefore()
        // TIDAL has no link of its own to open, so it is only kept paused, as it may start a moment later.
        waitFor("it's kept paused") { tidal in playback.pausedApps }
        assertTrue(playback.opened.isEmpty())
        assertTrue(playback.seeks.isEmpty())
    }

    @Test
    fun backToBeforeInYouTubeMusicThatWontStartFromBehindOpensItOnTheSongsLinkOnlyOnceCrosstuneCanComeBack() {
        youTubeHasTheSong()
        keep(line())
        playback.access = true
        playback.startsInBackground = false
        playback.comesBackByItself = false
        // Opened, it's given the whole wait to start, as on a phone.
        val cards = cards()
        cards.start(null, before = Before(MusicMetadata("Demo", "Band"), 4_000, ytMusic))
        waitForThePlayer()
        cards.backToBefore()
        // It can't come back over YouTube Music, so YouTube Music isn't opened: it's only kept paused.
        waitFor("it's kept paused") { ytMusic in playback.pausedApps }
        Thread.sleep(400)
        assertTrue(playback.opened.isEmpty())
        assertEquals(0, playback.cameBack)

        // Once it can, YouTube Music is opened on the song's own link, and Crosstune comes back once it can move there.
        playback.comesBackByItself = true
        keep(line("Another"))
        cards.start(null, before = Before(MusicMetadata("Demo", "Band"), 4_000, ytMusic))
        cards.backToBefore()
        waitFor("it's opened") { playback.opened.isNotEmpty() }
        assertEquals(ytMusic, playback.opened.single().first)
        assertTrue(playback.opened.single().second, "demo0000000" in playback.opened.single().second)
        playback.playing.value = Following(PlaybackClock(0, System.currentTimeMillis()), "YouTube Music", canSeek = true, appPackage = ytMusic, durationMs = 200_000)
        waitFor("it went back") { playback.seeks == listOf(4_000L) && playback.plays.lastOrNull() == true }
        // Without Premium, YouTube Music plays only in front, so it's left there.
        assertEquals(0, playback.cameBack)
        assertTrue(playback.songsAsked.isEmpty())
    }

    @Test
    fun backToBeforeInSpotifyOpensItOnItsOwnLinkOnlyOnceCrosstuneCanComeBack() {
        spotifyHasTheSong()
        keep(line())
        playback.access = true
        playback.comesBackByItself = false
        // Opened, it's given the whole wait to start, as on a phone.
        val cards = cards()
        cards.start(null, before = Before(MusicMetadata("Demo", "Band"), 4_000, spotify))
        waitFor("the card settles") { practice?.sound == LineSound.NONE }
        val before = lookups()
        cards.backToBefore()
        waitFor("Spotify is looked for") { playback.followedApps.contains(spotify) }
        Thread.sleep(600)
        // Spotify plays nothing asked of it, and opening it would cover Crosstune for good: nothing is asked about the song.
        assertNoAppBroughtUp()
        assertEquals(before, lookups())

        playback.comesBackByItself = true
        keep(line("Another"))
        cards.start(null, before = Before(MusicMetadata("Demo", "Band"), 4_000, spotify))
        cards.backToBefore()
        waitFor("it's opened") { playback.opened.isNotEmpty() }
        assertEquals(listOf(spotify to "https://open.spotify.com/track/$SPOTIFY_ID"), playback.opened.toList())
        assertTrue(playback.songsAsked.isEmpty())
        playback.playing.value = Following(PlaybackClock(0, System.currentTimeMillis()), "Spotify", canSeek = true, appPackage = spotify, durationMs = 200_000)
        waitFor("it went back") { playback.seeks == listOf(4_000L) && playback.plays.lastOrNull() == true }
        assertEquals(1, playback.cameBack)
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
        waitForThePlayer("the player is on the line")
        assertEquals(Shown("demo0000000", 10_000, 15_000 + MainViewModel.LINE_TAIL_MS, null, play = false), player.shown.single())
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
        waitForThePlayer("the player is on the line")
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
        waitForThePlayer("the player plays it")
        assertTrue(player.shown.single().play)
        assertEquals(LineSound.PLAYING, practice?.sound)
    }

    @Test
    fun aSongNoAppHasIsPlayedInThePlayer() {
        keep(line())
        youTubeHasTheSong()
        // No app has it open.
        playback.access = true
        cards().start(null, before = null)
        waitForThePlayer("the player is on the line")
        assertEquals("demo0000000", practice?.video)
    }

    @Test
    fun aVideoThatIsNotTheSongHasNoSoundAndNoPlayer() {
        keep(line())
        youTubeHasTheSong()
        playing(canSeek = false)
        val cards = cards()
        cards.start(null, before = null)
        waitForThePlayer("the player is on the line")
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
        waitForThePlayer("the player is on the line")
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
        // The stopped player's late failure must not erase replay on the same card.
        player.end(LineEnd.FAILED)
        assertEquals(LineSound.PLAYED, practice?.sound)
        assertEquals("demo0000000", practice?.video)
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
    fun aVideosLeadMovesWhereTheLineEndsToo() {
        youTubeHasTheSong()
        keep(line(lead = 5_000), types = hearIt)
        // A video with 5 s before the song: the line is 15 s to 20 s into it, and takes a second to be over at five times the speed.
        appPlays(morphe, speed = 5f, positionMs = 15_000)
        playback.playing.value = playback.playing.value!!.copy(video = true)
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
        val cards = cards()
        cards.start(null, before = null)
        waitForThePlayer("the player is on the line")
        assertFalse(player.shown.last().play)
        cards.turn()
        waitFor("it plays") { player.shown.last().play }
    }

    @Test
    fun aCardsAppWithNoSongNamedYetIsNeitherAskedForTheSongNorMovedAsIfItHadIt() {
        spotifyHasTheSong()
        keep(line().copy(app = MusicService.TIDAL.packageName))
        // TIDAL loading, its title blank: what it has isn't the card's song, so nothing there is moved or played.
        appPlays(MusicService.TIDAL.packageName, positionMs = 0)
        playback.unnamed.value = Unnamed(MusicService.TIDAL.packageName, "TIDAL", null)
        cards().start(null, before = null)
        waitFor("no sound") { practice?.sound == LineSound.NONE }
        assertTrue(playback.seeks.isEmpty())
        assertTrue(playback.plays.isEmpty())
        assertNoAppBroughtUp()
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

    /** A card that plays its line at once, as a hear-it card does, so the app it plays in shows without a turn. */
    private val hearIt = listOf(READ_IT.copy(on = false), HEAR_IT.copy(on = true), TRANSLATE_IT)

    /** The music apps the phone has, and the one songs open in when none is asked for. */
    private fun installed(vararg apps: String, preferred: Destination = Destination.Service(MusicService.SPOTIFY)) {
        screen.value = screen.value.copy(musicApps = apps.toList(), defaultDestination = preferred)
    }

    /** [app] playing the demo song, moving in it or not, on a clock [speed] times as fast: what Crosstune sees play, followed by name or not. */
    private fun appPlays(app: String, canSeek: Boolean = true, speed: Float = 5f, positionMs: Long = 10_000) {
        playback.access = true
        playback.playing.value = Following(PlaybackClock(positionMs, System.currentTimeMillis(), speed), "Music", canSeek = canSeek, appPackage = app, durationMs = 200_000)
        playback.appPlaying[app] = playback.playing
    }

    @Test
    fun anAppThatPausesItselfBehindCrosstuneIsRememberedAndTheLineGoesOn() {
        youTubeHasTheSong()
        keep(line("One"), line("Two"), types = hearIt)
        appPlays(morphe, speed = 1f)
        val flow = playback.appPlaying.getValue(morphe)
        val cards = cards()
        cards.start(null, before = null)
        waitFor("the line plays in the app") { playback.playedApps.zip(playback.plays).contains(morphe to true) }
        // It plays a moment, and then, behind Crosstune, stops itself.
        Thread.sleep(200)
        flow.value = Following(PlaybackClock(10_200, System.currentTimeMillis(), 1f, playing = false), "Music", canSeek = true, appPackage = morphe, durationMs = 200_000)
        waitForThePlayer("the player takes over")
        assertEquals(AppTraits(seeks = true, keeps = false), traits.of(morphe))
        assertEquals("demo0000000", practice?.video)
        // The next card doesn't go through the app again, though it still has the song open.
        val sent = playback.seeks.size
        cards.turn()
        cards.grade(Grade.GOOD)
        waitFor("the next line is in the player") { player.shown.size >= 2 }
        assertEquals(sent, playback.seeks.size)
    }

    @Test
    fun anAppSeenToFailIsTriedAgainAWeekLater() {
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        traits.keeps(ytMusic, false)
        appPlays(ytMusic)
        wall += AppTraitsStore.FORGET_AFTER_MS
        cards().start(null, before = null)
        waitFor("it plays") { playback.playedApps.zip(playback.plays).contains(ytMusic to true) }
        waitFor("it works") { traits.of(ytMusic).works }
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
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        appPlays(morphe)
        cards().start(null, before = null)
        waitFor("the line is over") { practice?.sound == LineSound.PLAYED }
        assertEquals(emptyList<String>(), steps())
    }

    @Test
    fun aCardLogsTheAppThatPlaysTheLineFromItsStart() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        // At 1x: where it says it played the line from is checked, and a faster clock would multiply how late a busy machine gets there.
        appPlays(morphe, speed = 1f, positionMs = 9_500)
        cards().start(null, before = null)
        waitFor("the line is over") { practice?.sound == LineSound.PLAYED }
        assertEquals(listOf("trying $morphe", "$morphe played the line from its start"), steps())
    }

    @Test
    fun aCardLogsAnAppThatHasTheSongOpenAndWontMoveAndThePlayerThatTookOver() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        youTubeHasTheSong()
        keep(keptFromSpotify, types = hearIt)
        appPlays(spotify, canSeek = false)
        cards().start(null, before = null)
        waitForThePlayer("the player takes over")
        assertEquals(listOf("$spotify plays and does not move", "the YouTube player"), steps())
        assertNoAppBroughtUp()
    }

    @Test
    fun aCardLogsAnAppThatPausedItselfAndThePlayerThatTookOver() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        appPlays(morphe, speed = 1f)
        val flow = playback.appPlaying.getValue(morphe)
        cards().start(null, before = null)
        waitFor("the line plays in the app") { playback.playedApps.zip(playback.plays).contains(morphe to true) }
        Thread.sleep(100)
        flow.value = Following(PlaybackClock(10_100, System.currentTimeMillis(), 1f, playing = false), "Music", canSeek = true, appPackage = morphe, durationMs = 200_000)
        waitForThePlayer("the player takes over")
        assertEquals(listOf("trying $morphe", "$morphe stopped playing the line", "the YouTube player"), steps())
    }

    @Test
    fun aCardLogsThatItHasNoSoundAndAnAppThatWasNeverThere() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        keep(line())
        // No app has the song open, none is asked for it, and YouTube has no video.
        playback.access = true
        cards().start(null, before = null)
        waitFor("no sound") { practice?.sound == LineSound.NONE }
        assertEquals(listOf("no app has the song open", "no sound"), steps())
    }

    @Test
    fun anAppThatKeepsPlayingIsUsedForTheNextCardToo() {
        youTubeHasTheSong()
        keep(line("One"), line("Two"), types = hearIt)
        appPlays(morphe)
        val cards = cards()
        cards.start(null, before = null)
        waitFor("the first line is over") { practice?.sound == LineSound.PLAYED }
        assertEquals(AppTraits(seeks = true, keeps = true), traits.of(morphe))
        assertEquals(true, traits.of(morphe).keeps)
        cards.turn()
        cards.grade(Grade.GOOD)
        waitFor("the second line is played in the app, too") { playback.seekedApps.count { it == morphe } == 2 && practice?.sound == LineSound.PLAYED }
        assertTrue(player.shown.isEmpty())
    }

    @Test
    fun anAppThatPausesAMomentIsStillPlayingTheLine() {
        youTubeHasTheSong()
        keep(line(), types = hearIt)
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
    fun anAppThatHasNoSeekCapabilityHasNoFailedControlToRemember() {
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        // No app is asked for, so the one that plays is the one that's marked.
        playing(canSeek = false)
        cards().start(null, before = null)
        waitForThePlayer("the player takes over")
        assertEquals(AppTraits(), traits.of(spotify))
    }

    @Test
    fun anAppThatPlaysAndWontMoveAndIsNotKnownIsNotRemembered() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        keep(line(), types = hearIt)
        playback.access = true
        playback.playing.value = Following(PlaybackClock(10_000, System.currentTimeMillis()), "Music", canSeek = false)
        cards().start(null, before = null)
        waitFor("no sound") { practice?.sound == LineSound.NONE }
        assertEquals(listOf("the app plays and does not move", "no sound"), steps())
        assertNull(ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("practice-traits", Context.MODE_PRIVATE).getString("app_traits", null))
    }

    @Test
    fun aCardThatHearsItselfIsPlayedAtOnceInThePlayerToo() {
        keep(line(), types = hearIt)
        youTubeHasTheSong()
        playback.access = true
        cards().start(null, before = null)
        waitForThePlayer("the player is on the line")
        assertTrue(player.shown.last().play)
    }

    @Test
    fun aCardTurnedWhileItsAppIsBeingLookedForIsPlayedAtOnceInThePlayer() {
        keep(line())
        youTubeHasTheSong()
        playback.access = true
        val cards = cards()
        cards.start(null, before = null)
        waitFor("it's looking") { practice?.sound == LineSound.LOADING }
        cards.turn()
        waitForThePlayer("the player is on the line")
        assertTrue(player.shown.last().play)
    }

    @Test
    fun aCardNotTurnedIsOnlyGotReadyInTheAppNotPlayed() {
        keep(line())
        youTubeHasTheSong()
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
        appPlays(ytMusic, speed = 1f)
        // As YouTube Music without Premium asked to play from behind Crosstune: its session errs, so it's followed no more.
        val flow = playback.appPlaying.getValue(ytMusic)
        playback.onPlay = { app, play -> if (app == ytMusic && play) flow.value = null }
        cards().start(null, before = null)
        waitForThePlayer("the player takes over")
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
        appPlays(ytMusic, speed = 1f)
        // Asked to play, it does, but from 0, as YouTube Music did after an error.
        val flow = playback.appPlaying.getValue(ytMusic)
        playback.onPlay = { app, play ->
            if (app == ytMusic && play) flow.value = flow.value?.copy(clock = PlaybackClock(0, System.currentTimeMillis(), 1f))
        }
        cards().start(null, before = null)
        waitForThePlayer("the player takes over")
        assertEquals(emptyList<String>(), playedSteps())
        assertEquals(AppTraits(keeps = false), traits.of(ytMusic))
        assertEquals(listOf(ytMusic), playback.pausedApps.distinct())
    }

    @Test
    fun anAppThatGoesOnToAnotherSongDidNotPlayTheLineAndThePlayerDoes() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        val tidal = MusicService.TIDAL.packageName
        appPlays(tidal, speed = 1f)
        // As TIDAL with no account: its preview is over before the line, and it plays the next song, which isn't the card's.
        val flow = playback.appPlaying.getValue(tidal)
        playback.onPlay = { app, play -> if (app == tidal && play) flow.value = null }
        cards().start(null, before = null)
        waitForThePlayer("the player takes over")
        assertEquals(listOf("trying $tidal", "$tidal did not play the line", "the YouTube player"), steps())
        // The other song TIDAL went on to isn't left playing.
        assertEquals(listOf(tidal), playback.pausedApps.distinct())
        assertEquals(AppTraits(keeps = false), traits.of(tidal))
        assertNoAppBroughtUp()
    }

    @Test
    fun anAppThatErrsAndComesBackIsAskedOnceMoreAndPlaysTheLine() {
        youTubeHasTheSong()
        keep(line(), types = hearIt)
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
    fun anAppThatErrsEachTimeItsAskedIsAskedTwiceAndThenThePlayerPlaysTheLine() {
        youTubeHasTheSong()
        keep(line(), types = hearIt)
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
        waitForThePlayer("the player takes over")
        assertEquals(listOf(10_000L, 10_000L), playback.seeks.toList())
        assertEquals(AppTraits(keeps = false), traits.of(ytMusic))
    }

    @Test
    fun anAppInAnAdIsNotTakenToBeAtTheLineBeforeItsSentThere() {
        youTubeHasTheSong()
        keep(line())
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
    fun aSongTheAppPlaysOnlyPartOfBeforeTheLineIsNotPlayedThere() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        val tidal = MusicService.TIDAL.packageName
        appPlays(tidal)
        // As TIDAL without an account: only a 30 s preview, here over before the line starts at 10 s.
        playback.appPlaying.getValue(tidal).value = playback.appPlaying.getValue(tidal).value!!.copy(durationMs = 8_000)
        cards().start(null, before = null)
        waitForThePlayer("the player is on the line")
        // Never sent there, nor played to its end and on into the next song.
        assertTrue(playback.seeks.isEmpty())
        assertFalse(playback.plays.contains(true))
        assertEquals(listOf("trying $tidal", "$tidal did not play the line", "the YouTube player"), steps())
        assertEquals(AppTraits(), traits.of(tidal))
    }

    /**
     * What a music app said after it was sent to a line: [afterMs] after it was asked to pause there, at [positionMs], playing
     * or not, or nothing, as one that falls out. [onTheWay]: paused where playing on from where it was took it by then. Its
     * clock is from when it says it, or from [updatedMs] after it was sent, which may be before, as a report already on its way,
     * or from [atMs] on the card's clock.
     */
    private data class Said(val afterMs: Long, val positionMs: Long?, val playing: Boolean = false, val onTheWay: Boolean = false, val updatedMs: Long? = null, val atMs: Long? = null)

    /**
     * [app], at [from] and playing or not, says [said] once it's sent to [start], as it did on the Samsung (Issue #846), each with
     * a time of its own. When it was first told where it stopped is kept, from the phone's clock, or 0 while it hasn't been.
     */
    private fun replays(app: String, start: Long, from: Long, fromPlaying: Boolean, vararg said: Said): AtomicLong {
        youTubeHasTheSong()
        keep(line(start = start))
        appPlays(app, speed = 1f, positionMs = from)
        return answers(app, from, fromPlaying, *said)
    }

    /** [app], at [from] and playing or not, says [said] once it's next sent to a line, see [replays]. */
    private fun answers(app: String, from: Long, fromPlaying: Boolean, vararg said: Said): AtomicLong {
        val seeksBefore = playback.seeks.size
        playback.obeys = false
        val flow = playback.appPlaying.getValue(app)
        val song = flow.value
        val was = PlaybackClock(from, System.currentTimeMillis(), 1f, fromPlaying)
        flow.value = song?.copy(clock = was)
        val firstSaid = AtomicLong()
        val replayed = AtomicBoolean(false)
        playback.onPlay = { asked, play ->
            if (asked == app && !play && playback.seeks.size > seeksBefore && replayed.compareAndSet(false, true)) {
                val sent = System.nanoTime()
                val sentAt = System.currentTimeMillis()
                thread {
                    said.forEach { (afterMs, positionMs, playing, onTheWay, updatedMs, atMs) ->
                        Thread.sleep(maxOf(0, afterMs - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sent)))
                        val at = atMs ?: updatedMs?.let { sentAt + it } ?: System.currentTimeMillis()
                        flow.value = (if (onTheWay) was.positionAt(at) else positionMs)?.let { song?.copy(clock = PlaybackClock(it, at, 1f, playing)) }
                        firstSaid.compareAndSet(0, System.nanoTime())
                    }
                }
            }
        }
        return firstSaid
    }

    /** How long after [since], on the phone's clock, [app] was given up on, waited for as long as the card may wait. */
    private fun givenUpAfterMs(app: String, since: AtomicLong): Long {
        waitFor("$app is given up on") { "$app did not get to the line" in steps() }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since.get())
    }

    // TIDAL without an account, cold, sent past its 30 s preview: playing its start, it says it's paused at the preview's end.
    @Test
    fun tidalStoppedAtItsPreviewsEndIsGivenUpOnAtOnce() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        val tidal = MusicService.TIDAL.packageName
        val said = replays(tidal, start = 43_270, from = 65, fromPlaying = true, Said(117, 29_952), Said(168, 29_952))
        cards().start(null, before = null)
        val waitedMs = givenUpAfterMs(tidal, said)
        // The wait for an app to get there is 3 s. Given up on as soon as it said it stopped, even on a busy machine.
        assertTrue("TIDAL was given up on $waitedMs ms after it said it stopped", waitedMs < 1_000)
        assertTrue("$tidal stopped at 29952 ms, short of the line" in steps())
        // Stopped short of this line, it may get to the next: it isn't remembered not to seek.
        assertEquals(AppTraits(), traits.of(tidal))
        waitForThePlayer("the player takes over")
    }

    // TIDAL given up on at once is left out till Crosstune starts again: then it's tried first again, as preferred.
    @Test
    fun anAppThatStoppedShortIsTriedAgainNextTime() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        val tidal = MusicService.TIDAL.packageName
        replays(tidal, start = 43_270, from = 65, fromPlaying = true, Said(117, 29_952), Said(168, 29_952))
        val cards = cards()
        cards.start(null, before = null)
        waitForThePlayer("the player takes over")
        // Not again this time: practised again, the card goes straight to the player.
        val tried = steps().count { it == "trying $tidal" }
        cards.stop()
        cards.start(null, before = null)
        waitFor("the player plays it again") { player.shown.size > 1 }
        assertEquals(tried, steps().count { it == "trying $tidal" })
        cards.stop()
        // Next time Crosstune starts, it is.
        cards().start(null, before = null)
        waitFor("$tidal is tried again") { steps().count { it == "trying $tidal" } > tried }
    }

    // TIDAL's answer may say it was made the very moment it was sent, as both clocks count in milliseconds: that's an answer too.
    @Test
    fun anAnswerFromTheMomentTheAppWasSentIsJudged() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        val tidal = MusicService.TIDAL.packageName
        // The card's clock stands still, so it sends at this moment, and TIDAL's answer is from the same one.
        val sentAt = System.currentTimeMillis()
        val said = replays(tidal, start = 43_270, from = 65, fromPlaying = true, Said(117, 29_952, atMs = sentAt))
        cards(now = { sentAt }).start(null, before = null)
        val waitedMs = givenUpAfterMs(tidal, said)
        assertTrue("TIDAL was given up on $waitedMs ms after it said it stopped", waitedMs < 1_000)
        assertTrue("$tidal stopped at 29952 ms, short of the line" in steps())
    }

    // Warm, TIDAL already stopped at its preview's end says that same place again: that isn't told from not having moved yet.
    @Test
    fun tidalSayingAgainWhereItWasIsWaitedFor() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        val tidal = MusicService.TIDAL.packageName
        val said = replays(tidal, start = 43_270, from = 29_952, fromPlaying = false, Said(131, 29_952))
        cards().start(null, before = null)
        assertTrue(givenUpAfterMs(tidal, said) >= LineWindow.SEEK_WAIT_MS - 500)
        assertEquals(false, traits.of(tidal).seeks)
    }

    /** That [app] got to the line, after it said all it did, and no other app or player was needed. */
    private fun reached(app: String) {
        waitFor("$app gets to the line") { practice?.sound == LineSound.PLAYED }
        assertEquals(true, traits.of(app).seeks)
        assertTrue(player.shown.isEmpty())
    }

    // YouTube Music, warm, sent from further on in the song: paused, it says a place a little before the line, as it rounds.
    @Test
    fun youTubeMusicPausedALittleBeforeTheLineIsThere() {
        replays(ytMusic, start = 43_270, from = 56_725, fromPlaying = false, Said(204, 43_000))
        cards().start(null, before = null)
        reached(ytMusic)
    }

    // YouTube Music, warm, falls out a moment and comes back a little before the line: it's sent there once more.
    @Test
    fun youTubeMusicThatFallsOutAndComesBackIsThere() {
        replays(ytMusic, start = 43_270, from = 43_341, fromPlaying = false, Said(141, null), Said(219, 43_000), Said(399, 43_000))
        cards().start(null, before = null)
        reached(ytMusic)
    }

    // Each YouTube Music replay starts further on, as warm it was, not at 43000 paused, as cold: that's at the line already, and the rest would go unread.

    // YouTube Music, cold: it says the song's start, playing, then plays near the line, falls out, and comes back paused there.
    @Test
    fun youTubeMusicColdBackAtTheStartFirstStillGetsThere() {
        replays(ytMusic, start = 43_270, from = 56_725, fromPlaying = false, Said(316, 0, playing = true), Said(366, 43_208, playing = true), Said(466, null), Said(1_011, 43_577))
        cards().start(null, before = null)
        reached(ytMusic)
    }

    // YouTube Music, cold, the same with where it was first: playing a little before the line, falling out, back paused past its start.
    @Test
    fun youTubeMusicColdPlayingOnFromWhereItWasStillGetsThere() {
        replays(ytMusic, start = 43_270, from = 56_725, fromPlaying = false, Said(287, 43_052, playing = true), Said(386, 43_056, playing = true), Said(437, null), Said(963, 43_643))
        cards().start(null, before = null)
        reached(ytMusic)
    }

    // YouTube Music, cold, paused at the song's start, as it was while loading, and never anywhere else, as recorded: the start
    // proves nothing, so it's waited for as long as any app is.
    @Test
    fun anAppPausedAtTheSongsStartIsWaitedFor() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        val said = replays(ytMusic, start = 43_270, from = 56_725, fromPlaying = false, Said(292, 0), Said(408, 0), Said(458, 0), Said(558, null), Said(1_037, 0))
        cards().start(null, before = null)
        assertTrue(givenUpAfterMs(ytMusic, said) >= LineWindow.SEEK_WAIT_MS - 500)
        assertTrue(steps().none { "short of the line" in it })
    }

    // Morphe's YouTube Music, cold: it plays on from where it was to the line, then pauses there.
    @Test
    fun morphePlayingOnToTheLineIsThere() {
        replays(
            morphe, start = 43_270, from = 43_001, fromPlaying = true,
            Said(183, 43_096, playing = true), Said(290, 43_102, playing = true), Said(375, 43_118, playing = true),
            Said(425, 43_306, playing = true), Said(476, 43_492, playing = true), Said(1_144, 43_490),
        )
        cards().start(null, before = null)
        reached(morphe)
    }

    // Morphe's YouTube Music, warm: already paused at the line, it says so again.
    @Test
    fun morpheAlreadyAtTheLineIsThere() {
        replays(morphe, start = 43_270, from = 43_270, fromPlaying = false, Said(270, 43_270))
        cards().start(null, before = null)
        reached(morphe)
    }

    // An app paused before it seeks says where it was by then, playing on: that's where it was, not where it stopped.
    @Test
    fun anAppPausedOnTheWayBeforeItSeeksIsWaitedFor() {
        replays(ytMusic, start = 43_270, from = 10_000, fromPlaying = true, Said(100, null, onTheWay = true), Said(600, 43_270))
        cards().start(null, before = null)
        reached(ytMusic)
    }

    // The same, paused on the way 2 s on: past the slack from where it was, but where playing on took it.
    @Test
    fun anAppPausedLateOnTheWayIsWaitedFor() {
        replays(ytMusic, start = 43_270, from = 10_000, fromPlaying = true, Said(2_000, null, onTheWay = true), Said(2_300, 43_270))
        cards().start(null, before = null)
        reached(ytMusic)
    }

    // Paused on the way, then saying that same place again later, with a new time: where playing on took it by then is past it.
    @Test
    fun anAppSayingAgainWhereItPausedIsWaitedFor() {
        replays(ytMusic, start = 43_270, from = 10_000, fromPlaying = true, Said(100, 10_100), Said(1_700, 10_100), Said(2_000, 43_270))
        cards().start(null, before = null)
        reached(ytMusic)
    }

    // Back at the song's start playing, then paused there 1.7 s on, which Playback keeps as paused at 1700, where it played on to.
    @Test
    fun anAppBackAtTheStartThatPausesLaterIsWaitedFor() {
        replays(ytMusic, start = 43_270, from = 56_725, fromPlaying = false, Said(300, 0, playing = true), Said(2_000, 1_700), Said(2_300, 43_270))
        cards().start(null, before = null)
        reached(ytMusic)
    }

    // A report the app made before it was sent, on its way to Crosstune, isn't its answer, wherever it says it is.
    @Test
    fun aReportFromBeforeTheAppWasSentIsWaitedPast() {
        replays(ytMusic, start = 43_270, from = 5_000, fromPlaying = false, Said(150, 20_000, updatedMs = -200), Said(400, 43_270))
        cards().start(null, before = null)
        reached(ytMusic)
    }

    // Sent on from the last line before the card heard it got there, the app says that line: it isn't where it stopped.
    @Test
    fun theLastLineSaidAfterTheAppIsSentOnIsWaitedPast() {
        youTubeHasTheSong()
        keep(line("First star", start = 20_000))
        appPlays(ytMusic, speed = 1f, positionMs = 5_000)
        val cards = cards()
        cards.start(null, before = null)
        waitFor("the first line is there") { practice?.sound == LineSound.PLAYED }
        cards.stop()
        // The next, practised at once, sends it on while the card still has it where it was, paused.
        answers(ytMusic, from = 5_000, fromPlaying = false, Said(150, 20_000), Said(400, 43_270))
        screen.value = screen.value.copy(savedLines = listOf(line(start = 43_270)))
        cards.start(null, before = null)
        reached(ytMusic)
        assertEquals(listOf(20_000L, 43_270L), playback.seeks)
    }

    @Test
    fun aSongTheAppPlaysOnlyPartOfBeforeTheLineIsNotGotToOnACardsFront() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        youTubeHasTheSong()
        keep(line())
        val tidal = MusicService.TIDAL.packageName
        appPlays(tidal)
        playback.appPlaying.getValue(tidal).value = playback.appPlaying.getValue(tidal).value!!.copy(durationMs = 8_000)
        cards().start(null, before = null)
        waitForThePlayer("the player is on the line")
        assertEquals(listOf("trying $tidal", "$tidal did not get to the line", "the YouTube player"), steps())
        assertEquals(AppTraits(), traits.of(tidal))
    }

    @Test
    fun aSongTheAppPlaysOnlyPartOfBeforeTheLineIsNotPlayedThereWhenTheCardIsTurnedOnTheWay() {
        ShadowLog.setLoggable(CHAIN_LOG, Log.DEBUG)
        youTubeHasTheSong()
        keep(line())
        val tidal = MusicService.TIDAL.packageName
        appPlays(tidal, canSeek = false)
        val flow = playback.appPlaying.getValue(tidal)
        val preview = flow.value!!.copy(durationMs = 8_000, canSeek = true)
        val cards = cards()
        cards.start(null, before = null)
        // Still on its way to where it can be moved as the card's turned: what it plays is then for the back, to be heard.
        waitFor("it's followed") { tidal in playback.followedApps || playback.followed.isNotEmpty() }
        cards.turn()
        flow.value = preview
        waitForThePlayer("the player is on the line")
        assertEquals(listOf("trying $tidal", "$tidal did not play the line", "the YouTube player"), steps())
        assertEquals(AppTraits(), traits.of(tidal))
    }

    @Test
    fun aLineThatRunsPastTheSongsEndIsPausedBeforeTheAppGoesOnToTheNext() {
        youTubeHasTheSong()
        keep(line(), types = hearIt)
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
    fun aSongSpotifyAlreadyPlaysIsReadyThereWithoutItsLinkBeingLookedUp() {
        val (gate, started) = holdSpotifyLink()
        keep(keptFromSpotify)
        appPlays(spotify, speed = 5f, positionMs = 10_000)
        try {
            cards().start(null, before = null)
            waitFor("the line is ready") { practice?.sound == LineSound.PLAYED }
            // The app already has the song, and no card opens an app, so nothing reads a link to open it.
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
        appPlays(tidal, speed = 1f)
        val flow = playback.appPlaying.getValue(tidal)
        playback.onPlay = { app, play -> if (app == tidal && play) flow.value = null }
        tidalCards = cards()
        tidalCards.start(null, before = Before(MusicMetadata("Before", "Band"), 4_000, null))
        waitFor("it's stopped") { playback.pausedApps.isNotEmpty() }
        return tidal to flow
    }

    /** The host each lookup so far went to, in order. */
    private fun lookups() = sites.requestedUrls.map { it.toHttpUrl().host }

    /** Every search answering, and finding nothing: YouTube Music's, Apple's and Deezer's. */
    private fun noSearchFindsTheSong() {
        sites.handler = { request ->
            when (request.url.host) {
                "music.youtube.com" -> FakeSpotify.html(request, "{}")
                "itunes.apple.com" -> FakeSpotify.html(request, """{"results":[]}""")
                "api.deezer.com" -> FakeSpotify.html(request, """{"data":[]}""")
                else -> FakeSpotify.html(request, "{}", code = 404)
            }
        }
    }

    @Test
    fun thePlayersVideoIsLookedForOnceWhenYouTubeMusicFindsNone() {
        noSearchFindsTheSong()
        keep(line().copy(lengthMs = 200_000))
        playback.access = true
        // A song found nowhere is remembered for a while, as the app does.
        cards(matcher = ExactMatcher(sites.client(), "AR", missingForMs = 60_000)).start(null, before = null)
        waitFor("the card has no sound") { practice?.sound == LineSound.NONE }
        // YouTube Music's song and video searches, then Apple's, in the phone's store and the US one, and Deezer's, for the song's
        // other names and its length: once each. Searching again would find the same nothing.
        assertEquals(listOf("api.deezer.com", "itunes.apple.com", "itunes.apple.com", "music.youtube.com", "music.youtube.com"), lookups().sorted())
    }

    @Test
    fun releasingPracticeDestroysItsMadePlayerAndDoesNotMakeAnother() {
        val cards = cards()
        assertEquals(player.view(), cards.playerView())
        cards.release()
        assertTrue(player.released)
        assertNull(cards.playerView())
    }

    @Test
    fun releasingPracticeCancelsItsPendingVideoLookup() {
        val (gate, started) = holdHosts("music.youtube.com")
        keep(line())
        playback.access = true
        val cards = cards()
        try {
            cards.start(null, before = null)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            cards.release()
            val stopped = screen.value
            gate.countDown()
            waitFor("the cancelled lookup has ended") { scope.coroutineContext[kotlinx.coroutines.Job]?.children?.none() == true }
            assertTrue(player.shown.isEmpty())
            assertEquals(stopped, screen.value)
        } finally {
            gate.countDown()
        }
    }

    @Test
    fun releasingPracticeDoesNotMakeAnUnusedPlayer() {
        val cards = cards()
        cards.release()
        assertFalse(player.released)
        assertNull(cards.playerView())
    }

    @Test
    fun aDeviceWithoutWebViewSupportLeavesPracticeWithoutASmallPlayer() {
        val cards = PracticeCards(
            screen, scope, playback, ExactMatcher(sites.client(), "AR"), store, traits,
            { wall }, { System.currentTimeMillis() }, newPlayer = { throw UnsupportedOperationException("WebView unsupported") }
        )
        assertNull(cards.playerView())
    }

    @Test
    fun anOldPlayersAnswerCannotFinishAnotherCardTypeForTheSameWords() {
        youTubeHasTheSong()
        keep(line())
        playback.access = true
        val cards = cards()
        cards.start(null, before = null)
        waitForThePlayer()
        val first = requireNotNull(practice)
        screen.value = screen.value.copy(practice = first.copy(
            queue = listOf(requireNotNull(first.line).copy(cardType = HEAR_TYPE)), sound = LineSound.PLAYING
        ))
        player.end(LineEnd.ENDED)
        assertEquals(LineSound.PLAYING, practice?.sound)
        player.end(LineEnd.FAILED)
        assertEquals(first.video, practice?.video)
    }

    @Test
    fun aMissingWebViewProviderLeavesPracticeWithoutASmallPlayer() {
        var attempts = 0
        val cards = PracticeCards(
            screen, scope, playback, ExactMatcher(sites.client(), "AR"), store, traits,
            { wall }, { System.currentTimeMillis() }, newPlayer = {
                attempts++
                throw android.util.AndroidRuntimeException("No WebView provider")
            }
        )
        assertNull(cards.playerView())
        assertEquals(1, attempts)
    }

    @Test
    fun aVideoSearchThatFailedIsAskedOnceMore() {
        youTubeHasTheSong()
        val found = sites.handler
        val asked = AtomicLong()
        // Its first answer is an error, as when the phone is offline for a moment.
        sites.handler = { request -> if (request.url.host == "music.youtube.com" && asked.getAndIncrement() == 0L) FakeSpotify.html(request, "{}", code = 503) else found(request) }
        keep(line())
        playback.access = true
        cards().start(null, before = null)
        waitForThePlayer("the player is on the line")
        assertEquals("demo0000000", player.shown.single().videoId)
        assertEquals(2L, asked.get())
    }

    /**
     * Every way a card could bring a music app up in front of Crosstune, or have it look for the song: opened on a link, asked
     * for the song by name, brought to the front, asked to play what it has, a screen woken for it, or Crosstune come back over it.
     */
    private fun assertNoAppBroughtUp() {
        assertEquals(emptyList<Pair<String, String>>(), playback.opened.toList())
        assertEquals(emptyList<Pair<MusicMetadata, String?>>(), playback.songsAsked.toList())
        assertEquals(emptyList<MusicMetadata>(), playback.broughtToFront.toList())
        assertEquals(emptyList<String>(), playback.appsPlayed.toList())
        assertEquals(0, playback.woken)
        assertEquals(0, playback.cameBack)
    }

    /** A kind of card of the user's own, which plays the line on its front under the words. */
    private val mine = CardType("mine", "Mine", true, CardOptions(CardSide(words = true, sound = true), CardSide(translation = true)))

    /** Waits for the card shown to settle on its sound, in the player or none: no app has the song open, so it never plays in one. */
    private fun waitForTheCard(what: String) = waitFor(what) { practice?.video != null || practice?.sound == LineSound.NONE }

    @Test
    fun aWholeSessionWithNoAppPlayingTheSongBringsNoAppUpAndGoesOnCardByCard() {
        spotifyHasTheSong()
        youTubeHasTheSong()
        // Kept from Spotify, the app songs open in, with YouTube Music there too, and Crosstune may show over other apps.
        keep(keptFromSpotify, keptFromSpotify.copy(text = "Next", startMs = 20_000, endMs = 25_000), types = listOf(READ_IT, HEAR_IT.copy(on = true), TRANSLATE_IT, mine))
        installed(spotify, ytMusic)
        playback.access = true
        val cards = cards(songLoadMs = 300)
        cards.start(null, before = null)
        val total = practice!!.total
        // Read it, hear it and the user's own kind, for each line.
        assertEquals(6, total)
        repeat(total) { card ->
            waitForTheCard("card ${card + 1} is ready")
            assertNoAppBroughtUp()
            assertEquals("card ${card + 1}", "demo0000000", practice?.video)
            // The line is played again when asked, in the player.
            val shown = player.shown.size
            // A line playing at once, as the question, is stopped by it instead.
            val asked = practice?.sound == LineSound.PLAYING
            cards.replay()
            if (!asked) waitFor("card ${card + 1} plays again") { player.shown.size > shown }
            cards.turn()
            cards.grade(Grade.GOOD)
            assertNoAppBroughtUp()
        }
        assertNull(practice!!.line)
        assertEquals(total, practice!!.done)
        // Nothing was asked about the song for Spotify: only the player's video was looked up.
        assertTrue(lookups().none { "spotify" in it || "listenbrainz" in it })
        assertTrue(playback.seeks.isEmpty())
    }

    @Test
    fun aVideoLookupAnsweredOnlyOnceTheCardWasGradedChangesNothingOnTheNext() {
        val (gate, started) = holdHosts("music.youtube.com")
        keep(keptFromSpotify, keptFromSpotify.copy(text = "Next", startMs = 20_000, endMs = 25_000))
        installed(spotify, ytMusic)
        playback.access = true
        val cards = cards(songLoadMs = 300)
        try {
            cards.start(null, before = null)
            assertTrue(started.await(10, TimeUnit.SECONDS))
            // Graded while its video is still looked up, the first card's answer is for a card gone.
            cards.turn()
            cards.grade(Grade.GOOD)
            assertEquals("Next", practice?.line?.text)
            gate.countDown()
            waitForTheCard("the next card is ready")
            Thread.sleep(300)
            assertEquals(listOf(20_000L), player.shown.map { it.startMs })
            assertEquals("Next", practice?.line?.text)
            assertEquals(LineSound.PLAYED, practice?.sound)
            assertNoAppBroughtUp()
        } finally {
            gate.countDown()
        }
    }

    @Test
    fun offlineACardHasNoSoundBringsNoAppUpAndIsStillGraded() {
        // Every lookup fails, as with no network.
        sites.handler = { request -> FakeSpotify.html(request, "{}", code = 503) }
        keep(keptFromSpotify, keptFromSpotify.copy(text = "Next", startMs = 20_000, endMs = 25_000), types = listOf(READ_IT, HEAR_IT.copy(on = true), TRANSLATE_IT))
        installed(spotify, ytMusic)
        playback.access = true
        val cards = cards(songLoadMs = 300)
        cards.start(null, before = null)
        val total = practice!!.total
        repeat(total) { card ->
            waitFor("card ${card + 1} has no sound") { practice?.sound == LineSound.NONE }
            cards.turn()
            cards.grade(Grade.GOOD)
        }
        assertEquals(total, practice!!.done)
        assertTrue(player.shown.isEmpty())
        assertNoAppBroughtUp()
    }

    @Test
    fun aSpotifyLinkAlreadyKnownAndAnAppSeenToWorkStillBringNoAppUpOnceCrosstuneStartsAgain() {
        spotifyHasTheSong()
        youTubeHasTheSong()
        val matcher = ExactMatcher(sites.client(), "AR", cache = LookupCache(java.io.File.createTempFile("practice-links", ".json"), Dispatchers.Unconfined))
        // Spotify's link and the video were found before, and Spotify was seen to move and play on behind Crosstune.
        kotlinx.coroutines.runBlocking {
            assertTrue(matcher.find(MusicService.SPOTIFY, MusicMetadata("Demo", "Band")) != null)
            assertTrue(matcher.find(MusicService.YOUTUBE_MUSIC, MusicMetadata("Demo", "Band")) != null)
        }
        traits.seeks(spotify, true)
        traits.keeps(spotify, true)
        keep(keptFromSpotify, types = listOf(READ_IT, HEAR_IT.copy(on = true), TRANSLATE_IT))
        installed(spotify, ytMusic)
        playback.access = true
        // Practice made again, as when Crosstune starts again, with nothing learned this run.
        cards(songLoadMs = 300, matcher = matcher).release()
        val asked = sites.requestedUrls.size
        val cards = cards(songLoadMs = 300, matcher = matcher)
        cards.start(null, before = null)
        repeat(practice!!.total) { card ->
            waitForTheCard("card ${card + 1} is ready")
            assertEquals("demo0000000", practice?.video)
            cards.turn()
            cards.grade(Grade.GOOD)
        }
        assertNoAppBroughtUp()
        // Both answers were remembered: nothing was looked up again.
        assertEquals(asked, sites.requestedUrls.size)
    }

    @Test
    fun withoutSeeingWhatPlaysAHearItCardStillPlaysInThePlayer() {
        youTubeHasTheSong()
        keep(line(), types = hearIt)
        // Android doesn't let Crosstune see what plays: the player needs none of it.
        playback.access = false
        cards().start(null, before = null)
        waitForThePlayer("the player plays the line")
        assertTrue(player.shown.single().play)
        assertEquals(LineSound.PLAYING, practice?.sound)
        assertTrue(playback.followed.isEmpty())
        assertNoAppBroughtUp()
    }

    @Test
    fun withNoPlaybackAtAllACardStillPlaysInThePlayer() {
        youTubeHasTheSong()
        keep(line())
        val cards = cards(source = null)
        cards.start(null, before = null)
        waitForThePlayer("the player is on the line")
        assertFalse(player.shown.single().play)
        cards.turn()
        waitFor("it plays on the back") { player.shown.size == 2 }
        assertTrue(player.shown.last().play)
    }

    @Test
    fun aSongAnAppAlreadyHasOpenIsPlayedThereAndNoAppIsBroughtUp() {
        spotifyHasTheSong()
        youTubeHasTheSong()
        keep(keptFromSpotify, types = hearIt)
        installed(spotify, ytMusic)
        // Spotify already has the song open, and moves in it.
        playing()
        cards().start(null, before = null)
        waitFor("the line is over") { practice?.sound == LineSound.PLAYED && playback.plays.contains(true) }
        assertEquals(listOf(10_000L), playback.seeks.toList())
        assertTrue(player.shown.isEmpty())
        assertNoAppBroughtUp()
    }
}

private const val SPOTIFY_ID = "1demoAAAAAAAAAAAAAAAAA"

/** A step's offset from the line's start, as a card logs where an app played it from. */
private val PLAYED_FROM = Regex("played the line from ([+-]?\\d+) ms")

/** The log tag a card's steps go to when it's asked for, which `adb shell setprop` names. */
private const val CHAIN_LOG = "CrosstuneChain"
