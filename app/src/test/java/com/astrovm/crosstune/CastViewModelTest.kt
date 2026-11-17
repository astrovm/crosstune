package com.astrovm.crosstune

import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The phone handing a song to the TV and becoming its remote, and the TV answering: the view model's half of casting. */
@RunWith(RobolectricTestRunner::class)
class CastViewModelTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeSpotify()
    private val made = mutableListOf<MainViewModel>()
    private val song = MusicMetadata("Hush", "Band")
    private val tv = CastTvState("Hush", "Band", playing = true, positionMs = 5_000, durationMs = 200_000, canSeek = true, canSkipNext = true, canSkipPrevious = true)

    @Before
    fun setUp() {
        prefs().edit().clear().putBoolean("setup_complete", true).commit()
    }

    @After
    fun tearDown() {
        made.forEach { MainViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(it) }
    }

    private fun prefs() = app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The lookup's requests run on real threads, so the main thread waits for them. */
    private fun until(what: String, done: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!done()) {
            check(System.currentTimeMillis() < end) { "Never $what" }
            idle()
            Thread.sleep(10)
        }
    }

    private fun model(playback: PlaybackSource?, cast: CastSender, phone: Phone = Phone.OTHER, lyricsFinder: LyricsFinder? = null): MainViewModel {
        val client = fake.client()
        return MainViewModel(
            LinkResolver(client, Dispatchers.Unconfined),
            ExactMatcher(client, ioDispatcher = Dispatchers.Unconfined),
            playback = playback,
            preferences = prefs(),
            interception = LinkInterception(app),
            artwork = ArtworkLoader(client, Dispatchers.Unconfined),
            listener = SongHearing { Heard.Nothing },
            lyricsFinder = lyricsFinder,
            systemDispatcher = Dispatchers.Unconfined,
            phone = phone,
            cast = cast,
            mayCapture = { false }
        ).also { made += it }
    }

    private fun following(playing: Boolean = true) =
        Following(PlaybackClock(1_000, SystemClock.elapsedRealtime(), playing = playing), "Spotify", canSeek = true, appPackage = "com.spotify", canSkipNext = true, canSkipPrevious = true)

    /** A model whose music app plays [song], with the player open on it. */
    private fun playingModel(playback: FakePlayback, cast: FakeCast, playing: Boolean = true, lyricsFinder: LyricsFinder? = null): MainViewModel {
        playback.access = true
        playback.onThePhone = Heard.Song(song, null, app = "com.spotify")
        playback.playing.value = following(playing)
        val model = model(playback, cast, lyricsFinder = lyricsFinder)
        model.viewModelScope.launch { model.watchPlayingNow() }
        idle()
        model.peekPlayingNow()
        idle()
        return model
    }

    private fun effects(model: MainViewModel): List<Effect> = mutableListOf<Effect>().also { list -> model.viewModelScope.launch { model.effects.collect { list += it } } }

    private fun lyricsFinder(): LyricsFinder {
        fake.handler = { request ->
            val title = request.url.queryParameter("track_name")
            val duration = if (title == "Next") 130 else 200
            val answer = if (request.url.host == "lrclib.net" && title != null) {
                """[{"trackName":"$title","artistName":"Band","duration":$duration,"syncedLyrics":"[00:00.00] First words of $title\n[00:10.00] Second line\n[00:20.00] Third line"}]"""
            } else ""
            FakeSpotify.html(request, answer)
        }
        return LyricsFinder(fake.inlineClient(), "test", Dispatchers.Unconfined, busyPauseMs = 0, matching = Dispatchers.Unconfined)
    }

    @Test
    fun theCastButtonIsThereOnlyWithATvAroundAndASongToHandIt() {
        val cast = FakeCast()
        val nothing = model(null, cast)
        cast.around.value = true
        idle()
        // A TV, but no song on the screen.
        assertTrue(nothing.uiState.castAround)
        assertFalse(nothing.uiState.canCast)

        val model = playingModel(FakePlayback(), cast)
        assertTrue(model.uiState.canCast)
        cast.around.value = false
        idle()
        assertFalse(model.uiState.castAround)
        assertFalse(model.uiState.canCast)
    }

    @Test
    fun castingAsksWhichTvFirstAndHandsTheSongOverOnceOneIsPickedStoppingItInThePhonesApp() {
        val playback = FakePlayback()
        val cast = FakeCast().apply { around.value = true }
        val model = playingModel(playback, cast)
        val effects = effects(model)

        model.cast()
        idle()
        assertEquals(listOf<Effect>(Effect.ChooseTv), effects)
        assertEquals(0, cast.loaded.size)

        cast.target.value = CastTarget("Living room")
        idle()
        assertEquals(listOf(CastSong("Hush", "Band")), cast.loaded.toList())
        // The phone's app stops, and the phone shows the TV's player.
        assertEquals(listOf(false), playback.plays.toList())
        assertEquals("Living room", model.uiState.castingTo)
        assertTrue(model.uiState.canCast)
        assertEquals(song, model.uiState.player?.song)
        assertNull(model.uiState.player?.following)

        // Picked once, the next TV that connects isn't handed it again.
        cast.target.value = null
        idle()
        cast.target.value = CastTarget("Bedroom")
        idle()
        assertEquals(1, cast.loaded.size)
    }

    @Test
    fun anAppNotPlayingIsLeftAlone() {
        val playback = FakePlayback()
        val cast = FakeCast().apply { around.value = true; target.value = CastTarget("Living room") }
        val model = playingModel(playback, cast, playing = false)
        model.cast()
        idle()
        assertEquals(1, cast.loaded.size)
        assertTrue(playback.plays.isEmpty())
    }

    @Test
    fun withTheTvThePlayersControlsAreItsRemote() {
        val playback = FakePlayback()
        val cast = FakeCast().apply { around.value = true; target.value = CastTarget("Living room") }
        val model = playingModel(playback, cast)
        val effects = effects(model)
        model.cast()
        idle()
        playback.plays.clear()

        // Until the TV says what plays there's nothing to control.
        cast.target.value = CastTarget("Living room", tv)
        idle()
        assertEquals("Living room", model.uiState.castingTo)
        assertEquals("Crosstune", model.uiState.player?.following?.app)
        model.playerPlayPause()
        model.playerSkip(next = true)
        model.playerSkip(next = false)
        model.playerSeek(9_000)
        assertEquals(listOf(CastCommand.Pause, CastCommand.Next, CastCommand.Previous, CastCommand.Seek(9_000)), cast.sent.toList())
        assertTrue(playback.plays.isEmpty() && playback.skips.isEmpty() && playback.seeks.isEmpty())

        // The words' options go to the TV too, and stay the phone's own.
        model.setTranslation(true)
        model.setReadings(true)
        assertEquals(listOf(CastCommand.Translation(true), CastCommand.Readings(true)), cast.sent.takeLast(2))
        assertTrue(model.uiState.learning.translation && model.uiState.learning.readings)

        // The button on the same song shows the TV rather than casting it again.
        model.cast()
        idle()
        assertEquals(1, cast.loaded.size)
        assertEquals(Effect.ShowTv, effects.last())

        // The TV goes: the button is back to casting, and the app is the player's again.
        cast.target.value = null
        idle()
        assertNull(model.uiState.castingTo)
        assertEquals("Spotify", model.uiState.player?.following?.app)
    }

    @Test
    fun anotherSongCastWhileConnectedGoesStraightToTheTv() {
        val playback = FakePlayback()
        val cast = FakeCast().apply { around.value = true; target.value = CastTarget("Living room") }
        val model = playingModel(playback, cast)
        idle()
        model.cast()
        idle()
        assertEquals(1, cast.loaded.size)
        assertEquals(0, cast.chosen)
        // The options changed with no TV connected are the phone's alone.
        cast.target.value = null
        idle()
        model.setTranslation(true)
        assertTrue(cast.sent.none { it is CastCommand.Translation })
    }

    @Test
    fun aConnectedTvWithoutASongDoesNotOpenAPickerOrLoadAnything() {
        val cast = FakeCast().apply { target.value = CastTarget("Living room") }
        val model = model(null, cast)
        val effects = effects(model)
        model.cast()
        idle()
        assertTrue(effects.isEmpty())
        assertTrue(cast.loaded.isEmpty())
    }

    @Test
    fun reconnectingTheRouteKeepsTheSameSongAndDoesNotLoadItTwice() {
        val playback = FakePlayback()
        val cast = FakeCast().apply { target.value = CastTarget("Living room", tv) }
        val model = playingModel(playback, cast)
        model.cast()
        idle()
        cast.target.value = null
        idle()
        cast.target.value = CastTarget("Bedroom", tv)
        idle()
        model.cast()
        idle()
        // A reconnect on the same song shows its route; it does not reload or pause it twice.
        assertEquals(1, cast.loaded.size)
        assertEquals(listOf(false), playback.plays.toList())
        model.playerPlayPause()
        assertEquals(listOf(CastCommand.Pause), cast.sent.toList())
    }

    @Test
    fun aPausedTvCanPlayButCannotSeekOrSkipWithoutThoseControls() {
        val playback = FakePlayback()
        val cast = FakeCast().apply { target.value = CastTarget("Living room") }
        val model = playingModel(playback, cast)
        model.cast()
        idle()
        playback.plays.clear()
        cast.target.value = CastTarget("Living room", tv.copy(playing = false, canSeek = false, canSkipNext = false, canSkipPrevious = false))
        idle()
        assertNotNull(model.uiState.player?.following)
        assertFalse(requireNotNull(model.uiState.player?.following).clock.playing)
        model.playerSeek(9_000)
        model.playerSkip(true)
        model.playerSkip(false)
        assertTrue(cast.sent.isEmpty())
        model.playerPlayPause()
        assertEquals(listOf(CastCommand.Play), cast.sent.toList())
        assertTrue(playback.plays.isEmpty())
    }

    @Test
    fun thePhoneCanTurnBothLearningOptionsOffOnTheTv() {
        val cast = FakeCast().apply { target.value = CastTarget("Living room") }
        val model = model(null, cast)
        model.setTranslation(true)
        model.setReadings(true)
        cast.sent.clear()
        model.setTranslation(false)
        model.setReadings(false)
        assertEquals(listOf(CastCommand.Translation(false), CastCommand.Readings(false)), cast.sent.toList())
        assertFalse(model.uiState.learning.translation)
        assertFalse(model.uiState.learning.readings)
    }

    @Test
    fun theSongHandedToTheRouteCarriesItsDisplayMetadata() {
        val metadata = song.copy(url = "https://music.youtube.com/watch?v=demo", artworkUrl = "https://example.com/cover.jpg", lengthMs = 123_000)
        val playback = FakePlayback().apply {
            access = true
            onThePhone = Heard.Song(metadata, null, app = "com.spotify")
            playing.value = following()
        }
        val cast = FakeCast().apply { target.value = CastTarget("Living room") }
        val model = model(playback, cast)
        model.viewModelScope.launch { model.watchPlayingNow() }
        idle()
        model.peekPlayingNow()
        idle()
        model.cast()
        idle()
        val loaded = cast.loaded.single()
        assertEquals("Hush", loaded.title)
        assertEquals("Band", loaded.artist)
        assertEquals(metadata.url, loaded.link)
        assertEquals(metadata.artworkUrl, loaded.artworkUrl)
        assertEquals(metadata.lengthMs, loaded.durationMs)
    }

    @Test
    fun anotherSongWhileConnectedHandsOffWithoutConfusingItWithTheOldRemote() {
        val playback = FakePlayback()
        val cast = FakeCast().apply { target.value = CastTarget("Living room", tv) }
        val model = playingModel(playback, cast)
        model.cast()
        idle()
        val another = MusicMetadata("Another", "Band")
        playback.onThePhone = Heard.Song(another, null, app = "com.spotify")
        idle()
        model.peekPlayingNow()
        idle()
        model.cast()
        idle()
        assertEquals(listOf(CastSong("Hush", "Band"), CastSong("Another", "Band")), cast.loaded.toList())
        assertEquals(listOf(false, false), playback.plays.toList())
        assertTrue(cast.sent.isEmpty())
        assertEquals(another, model.uiState.player?.song)
        assertNull(model.uiState.player?.following)
        cast.target.value = CastTarget("Living room", tv.copy(positionMs = 28_000))
        idle()
        assertNull(model.uiState.player?.following)
        model.playerPlayPause()
        idle()
        assertTrue(cast.sent.isEmpty())
        assertTrue(playback.songsAsked.isEmpty())
        cast.target.value = CastTarget("Living room", tv.copy(title = another.title, positionMs = 2_000))
        idle()
        assertEquals(2_000L, model.uiState.player?.following?.clock?.positionMs)
    }

    @Test
    fun aNewSongOnTheTvAdvancesThePlayerAndTheWordsUseOnlyItsClock() {
        val playback = FakePlayback()
        val cast = FakeCast().apply { target.value = CastTarget("Living room", tv) }
        val model = playingModel(playback, cast, lyricsFinder = lyricsFinder())
        val effects = effects(model)
        model.cast()
        idle()
        model.playerLyrics()
        idle()
        assertEquals(song, model.uiState.lyricsFor)
        assertEquals("First words of Hush", model.uiState.lyricLines.first().text)
        assertEquals(5_000L, model.uiState.following?.clock?.positionMs)

        val local = MusicMetadata("Local", "Band")
        playback.onThePhone = Heard.Song(local, null, app = "com.spotify")
        playback.playing.value = following()
        idle()
        assertEquals(local, model.uiState.playingNow?.song)
        assertEquals(song, model.uiState.player?.song)
        model.playingNowPlayPause()
        assertEquals(listOf(false, false), playback.plays)

        model.skipSong(next = true)
        val next = tv.copy(title = "Next", positionMs = 8_000, durationMs = 130_000)
        cast.target.value = CastTarget("Living room", next)
        idle()
        assertEquals("Next", model.uiState.player?.song?.title)
        assertEquals("Next", model.uiState.lyricsFor?.title)
        assertEquals("First words of Next", model.uiState.lyricLines.first().text)
        assertEquals(8_000L, model.uiState.player?.following?.clock?.positionMs)
        assertEquals(8_000L, model.uiState.following?.clock?.positionMs)
        playback.onThePhone = Heard.Song(MusicMetadata("Next", "Band"), null, app = "com.spotify")
        playback.playing.value = following()
        idle()
        assertEquals(1_000L, model.uiState.playingNow?.following?.clock?.positionMs)
        model.playingNowPlayPause()
        model.seekLyrics(9_000)
        model.playerPlayPause()
        model.cast()
        idle()
        assertEquals(listOf(false, false, false), playback.plays)
        assertEquals(listOf(CastCommand.Next, CastCommand.Seek(9_000), CastCommand.Pause), cast.sent)
        assertEquals(listOf(Effect.ShowTv), effects.filterIsInstance<Effect.ShowTv>())
        assertEquals(1, cast.loaded.size)
        assertEquals("Living room", model.uiState.castingTo)

        model.pauseFollowingNext()
        cast.target.value = CastTarget("Living room", tv.copy(title = "Later", positionMs = 6_000))
        idle()
        assertEquals("Later", model.uiState.player?.song?.title)
        assertEquals("Next", model.uiState.lyricsFor?.title)
        assertNull(model.uiState.following?.app)
        model.resumeFollowingNext()
        idle()
        assertEquals("Later", model.uiState.lyricsFor?.title)
        assertEquals(6_000L, model.uiState.following?.clock?.positionMs)
    }

    @Test
    fun castingFromOpenWordsStartsFollowingWithoutNotificationAccess() {
        val playback = FakePlayback()
        val cast = FakeCast().apply { target.value = CastTarget("Living room", tv) }
        val model = playingModel(playback, cast, lyricsFinder = lyricsFinder())
        playback.access = false
        model.playerLyrics()
        idle()
        assertNull(model.uiState.following)
        model.cast()
        idle()
        assertEquals(5_000L, model.uiState.following?.clock?.positionMs)

        cast.target.value = CastTarget("Living room", tv.copy(title = "Next", positionMs = 8_000, durationMs = 130_000))
        idle()
        assertEquals("Next", model.uiState.lyricsFor?.title)
        assertEquals(8_000L, model.uiState.following?.clock?.positionMs)
        assertTrue(playback.plays.isEmpty())
    }

    @Test
    fun disconnectingWhileThePhoneAppIsSlowCancelsTheHandoff() {
        val playback = FakePlayback()
        val cast = FakeCast().apply { target.value = CastTarget("Living room", tv) }
        val model = playingModel(playback, cast)
        val ready = CompletableDeferred<Unit>()
        playback.said = ready
        model.cast()
        idle()
        assertTrue(cast.loaded.isEmpty())

        cast.target.value = null
        idle()
        cast.target.value = CastTarget("Bedroom", tv)
        idle()
        ready.complete(Unit)
        idle()

        assertTrue(cast.loaded.isEmpty())
        assertTrue(playback.plays.isEmpty())
        assertEquals("Bedroom", model.uiState.castingTo)
    }

    @Test
    fun withoutNotificationAccessCastingNeverControlsThePhonesApp() {
        val playback = FakePlayback()
        val cast = FakeCast().apply { target.value = CastTarget("Living room") }
        val model = playingModel(playback, cast)
        playback.access = false
        playback.followed.clear()
        model.cast()
        idle()
        assertEquals(1, cast.loaded.size)
        assertTrue(playback.plays.isEmpty())
        assertTrue(playback.followed.isEmpty())
    }

    @Test
    fun aSongLoadedByNameReplacesTheOldPlayer() {
        val playback = FakePlayback()
        val model = playingModel(playback, FakeCast())
        assertEquals(song, model.uiState.player?.song)
        val incoming = CastSong("Another", "Artist", artworkUrl = "https://example.com/cover.jpg", durationMs = 123_000)
        model.castLoad(incoming)
        idle()
        assertEquals(incoming.toMetadata(), model.uiState.result)
        assertNull(model.uiState.lyricsFor)
        assertFalse(model.uiState.player?.song == song)
        assertFalse(model.uiState.handingOff)
    }

    @Test
    fun choosingANewTvForTheSameSongPausesThePhoneBeforeHandingItOver() {
        val playback = FakePlayback()
        val cast = FakeCast().apply { target.value = CastTarget("Living room", tv) }
        val model = playingModel(playback, cast)
        model.cast()
        idle()
        cast.target.value = null
        idle()
        model.cast()
        idle()
        cast.target.value = CastTarget("Bedroom", tv)
        idle()
        assertEquals(2, cast.loaded.size)
        assertEquals(listOf(false, false), playback.plays.toList())
        assertTrue(cast.sent.isEmpty())
        assertEquals("Bedroom", model.uiState.castingTo)
    }

    @Test
    fun aSongCastWithALinkShowsTheLinkAndOneByNameShowsItsName() {
        // The field is read while the song is looked up: once it's found, the field shows what was found instead.
        val answer = CountDownLatch(1)
        fake.handler = { request -> answer.await(10, TimeUnit.SECONDS); FakeSpotify.html(request, "") }
        try {
            val model = model(null, FakeCast(), Phone.TV)
            val link = "https://music.youtube.com/watch?v=sPmul8b17AU"
            model.castLoad(CastSong("Hush", "Band", link))
            idle()
            assertEquals(link, model.uiState.linkText)
            assertTrue(model.uiState.leaveSettings)
            assertEquals(AfterLookup.OPEN, model.uiState.afterLookup)

            model.castLoad(CastSong("Hush", "Band"))
            idle()
            assertEquals("Hush by Band", model.uiState.linkText)
        } finally {
            answer.countDown()
        }
    }

    @Test
    fun openingTheOriginalOfACastSongLeavesCrosstuneOpen() {
        fake.handler = { request -> FakeSpotify.html(request, """{"title":"Hush","author_name":"Band"}""") }
        val model = model(null, FakeCast(), Phone.TV)
        val effects = effects(model)
        val link = "https://music.youtube.com/watch?v=sPmul8b17AU"
        model.castLoad(CastSong("Hush", "Band", link))
        until("looked the cast song up") { model.uiState.link != null && !model.uiState.isLoading }
        model.openOriginal()
        idle()
        val open = effects.filterIsInstance<Effect.Open>().last()
        assertEquals(link, open.url)
        // The TV is where the song plays; opening it elsewhere doesn't close Crosstune behind it.
        assertFalse(open.finishAfterOpen)
    }

    @Test
    fun theTvTellsPhonesWhereItIsInTheSongAndHowLongItIs() {
        val playback = FakePlayback()
        playback.access = true
        playback.onThePhone = Heard.Song(song, null, app = "com.spotify")
        playback.playing.value = following(playing = false).copy(durationMs = 200_000)
        val model = model(playback, FakeCast(), Phone.TV)
        model.viewModelScope.launch { model.watchPlayingNow() }
        idle()
        model.peekPlayingNow()
        idle()
        val state = requireNotNull(model.castTv)
        assertEquals(1_000L, state.positionMs)
        assertEquals(200_000L, state.durationMs)
    }

    @Test
    fun beforeItsAppSaysAnythingTheTvTellsTheSongsOwnLengthFromTheStart() {
        val long = song.copy(lengthMs = 123_000)
        val playback = FakePlayback().apply { access = true; onThePhone = Heard.Song(long, null, app = "com.spotify"); playing.value = following() }
        val cast = FakeCast().apply { target.value = CastTarget("Living room") }
        val model = model(playback, cast)
        model.viewModelScope.launch { model.watchPlayingNow() }
        idle()
        model.peekPlayingNow()
        idle()
        model.cast()
        idle()
        // Handed to a TV that hasn't said what it plays, the player follows nothing yet.
        assertNull(model.uiState.player?.following)
        val state = requireNotNull(model.castTv)
        assertEquals(0L, state.positionMs)
        assertEquals(123_000L, state.durationMs)
    }

    // --- The TV ---

    @Test
    fun whatAPhoneAsksOfTheTvIsWhatItsOwnPlayerDoes() {
        val playback = FakePlayback()
        val cast = FakeCast()
        playback.access = true
        playback.onThePhone = Heard.Song(song, null, app = "com.spotify")
        playback.playing.value = following(playing = false)
        val model = model(playback, cast, Phone.TV)
        model.viewModelScope.launch { model.watchPlayingNow() }
        idle()
        model.peekPlayingNow()
        idle()

        // Paused already: pause does nothing; play plays.
        model.castCommand(CastCommand.Pause)
        assertTrue(playback.plays.isEmpty())
        model.castCommand(CastCommand.Play)
        assertEquals(listOf(true), playback.plays.toList())
        playback.playing.value = following(playing = true)
        idle()
        model.castCommand(CastCommand.Play)
        model.castCommand(CastCommand.Pause)
        assertEquals(listOf(true, false), playback.plays.toList())

        model.castCommand(CastCommand.Seek(7_000))
        model.castCommand(CastCommand.Next)
        model.castCommand(CastCommand.Previous)
        assertEquals(listOf(7_000L), playback.seeks.toList())
        assertEquals(listOf(true, false), playback.skips.toList())

        model.castCommand(CastCommand.Translation(true))
        model.castCommand(CastCommand.Readings(true))
        assertTrue(model.uiState.learning.translation && model.uiState.learning.readings)
        // Asked by a phone, they aren't sent back to one.
        assertTrue(cast.sent.isEmpty())

        // What plays is what the phones see.
        val state = requireNotNull(model.castTv)
        assertEquals("Hush" to "Band", state.title to state.artist)
        assertTrue(state.playing && state.canSeek && state.canSkipNext && state.canSkipPrevious)

        model.castCommand(CastCommand.Stop)
        idle()
        assertEquals(false, playback.plays.last())
        assertNull(model.uiState.player)
        assertNull(model.castTv)
    }

    @Test
    fun theTvCanTurnLearningOptionsOffAndStopWhenAlreadyPaused() {
        val playback = FakePlayback()
        val model = playingModel(playback, FakeCast(), playing = false)
        model.castCommand(CastCommand.Translation(true))
        model.castCommand(CastCommand.Readings(true))
        model.castCommand(CastCommand.Translation(false))
        model.castCommand(CastCommand.Readings(false))
        assertFalse(model.uiState.learning.translation)
        assertFalse(model.uiState.learning.readings)
        model.castCommand(CastCommand.Stop)
        idle()
        assertEquals(listOf(false), playback.plays.toList())
        assertNull(model.uiState.player)
    }

    @Test
    fun stoppingACastDiscardsAnAppOpeningThatWasWaitingForTheActivity() {
        val service = MusicService.YOUTUBE_MUSIC
        shadowOf(app.packageManager).installPackage(installedApp(service.packageName, "YouTube Music"))
        prefs().edit().putString("default_target", service.name).commit()
        fake.handler = { request -> FakeSpotify.html(request, """{"title":"Hush","author_name":"Band"}""") }
        val model = model(null, FakeCast(), Phone.TV)
        model.refreshSystemState()
        model.setCanFloatOverApps(true)
        val link = "https://music.youtube.com/watch?v=castDemo123"

        // No screen is collecting effects yet. Wait until the app has queued the open.
        model.castLoad(CastSong("Hush", "Band", link))
        until("finished casting the first song") { model.uiState.canPlayResult && !model.uiState.playingResult }
        model.castCommand(CastCommand.Stop)
        val effects = effects(model)
        idle()
        assertTrue(effects.isEmpty())

        // The same cast still opens the app when it runs through without a Stop.
        model.castLoad(CastSong("Hush", "Band", link))
        until("opened the next cast") { effects.isNotEmpty() }
        assertEquals(link, effects.filterIsInstance<Effect.Open>().single().url)
    }

    @Test
    fun stoppingACastWhileItResumesAnAppPreventsTheFallbackOpening() {
        val service = MusicService.YOUTUBE_MUSIC
        shadowOf(app.packageManager).installPackage(installedApp(service.packageName, "YouTube Music"))
        prefs().edit().putString("default_target", service.name).commit()
        fake.handler = { request -> FakeSpotify.html(request, """{"title":"Hush","author_name":"Band"}""") }
        val playback = FakePlayback().apply {
            access = true
            playing.value = following(playing = false).copy(app = "YouTube Music", appPackage = service.packageName)
        }
        val holding = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Boolean>()
        val source = object : PlaybackSource by playback {
            override suspend fun resume(song: MusicMetadata, app: String?): Boolean {
                holding.complete(Unit)
                return answer.await()
            }
        }
        val model = model(source, FakeCast(), Phone.TV)
        model.refreshSystemState()
        model.setCanFloatOverApps(true)
        val incoming = CastSong("Hush", "Band", "https://music.youtube.com/watch?v=castDemo123")

        model.castLoad(incoming)
        until("asked the app to resume") { holding.isCompleted }
        model.castCommand(CastCommand.Stop)
        answer.complete(false)
        idle()
        assertNull(model.castTv)
        assertTrue(playback.broughtToFront.isEmpty())

        model.castLoad(incoming)
        until("brought the app forward for the next cast") { playback.broughtToFront.isNotEmpty() }
        assertEquals(listOf(song), playback.broughtToFront.toList())
    }

    @Test
    fun withNothingPlayingTheTvHasNothingToTell() {
        val model = model(null, FakeCast(), Phone.TV)
        assertNull(model.castTv)
        model.castCommand(CastCommand.Play)
        model.castCommand(CastCommand.Stop)
        assertNull(model.uiState.player)
    }
}
