package com.astrovm.crosstune

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** What a song and a command look like on their way to the TV, and the phone's player becoming the TV's remote. */
@RunWith(RobolectricTestRunner::class)
class CastTest {
    private val tv = CastTvState("Hush", "Band", playing = true, positionMs = 5_000, durationMs = 200_000, canSeek = true, canSkipNext = true)
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @After
    fun stopWatching() {
        scope.cancel()
    }

    @Test
    fun aSongSurvivesTheTripAsJsonWithOnlyWhatItHas() {
        val full = CastSong("Hush", "Band", "https://music.youtube.com/watch?v=x", "https://art/x.jpg", 200_000)
        assertEquals(full, CastSong.fromJson(JSONObject().put("other", 1).put("song", full.toJson().getJSONObject("song"))))
        val bare = CastSong("Hush", "")
        assertEquals(bare, CastSong.fromJson(bare.toJson()))
        assertEquals(MusicMetadata("Hush", "Band", artworkUrl = "https://art/x.jpg", url = "https://music.youtube.com/watch?v=x", lengthMs = 200_000), full.toMetadata())
    }

    @Test
    fun withoutASongOrItsTitleThereIsNothingToPlay() {
        assertNull(CastSong.fromJson(null))
        assertNull(CastSong.fromJson(JSONObject()))
        assertNull(CastSong.fromJson(CastSong(" ", "Band").toJson()))
    }

    @Test
    fun onlyTheCommandsCastHasNoWordForGoOverTheChannel() {
        assertEquals(CastCommand.Translation(true), CastCommand.fromMessage(requireNotNull(CastCommand.Translation(true).message())))
        assertEquals(CastCommand.Readings(false), CastCommand.fromMessage(requireNotNull(CastCommand.Readings(false).message())))
        listOf(CastCommand.Play, CastCommand.Pause, CastCommand.Stop, CastCommand.Seek(1), CastCommand.Next, CastCommand.Previous).forEach { assertNull(it.message()) }
        assertNull(CastCommand.fromMessage("not json"))
        assertNull(CastCommand.fromMessage("""{"type":"explode","on":true}"""))
    }

    @Test
    fun translationCanBeTurnedOffOverTheChannel() {
        val message = requireNotNull(CastCommand.Translation(false).message())
        assertFalse(JSONObject(message).getBoolean("on"))
        val command = CastCommand.fromMessage(message)
        require(command is CastCommand.Translation)
        assertFalse(command.on)
    }

    @Test
    fun aPausedTvDoesNotAdvanceAndDoesNotInventControls() {
        val cast = FakeCast().apply { target.value = CastTarget("Living room", tv.copy(playing = false, canSeek = false, canSkipNext = false)) }
        val playback = castPlayback(cast, FakePlayback())
        playback.casting(song)
        val following = requireNotNull(runBlocking { playback.follow(song).first() })
        assertFalse(following.clock.playing)
        assertEquals(5_000, following.clock.positionAt(11_000))
        assertFalse(following.canSeek)
        assertFalse(following.canSkipNext)
        assertFalse(following.canSkipPrevious)
    }

    @Test
    fun noCastAtAllDoesNothingAndIsNeverAround() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        NoCast.chooseTv(context)
        NoCast.showTv(context)
        NoCast.load(CastSong("Hush", "Band"))
        NoCast.send(CastCommand.Play)
        NoCast.disconnect()
        NoCast.start({ error("no song") }, { error("no command") })
        NoCast.publish(tv)
        NoCast.stop()
        assertFalse(NoCast.handle(Intent()))
        assertFalse(NoCast.around.value)
        assertNull(NoCast.target.value)
    }

    private val song = MusicMetadata("Hush", "Band")
    private val other = MusicMetadata("Another", "Band")

    private fun castPlayback(cast: FakeCast, source: FakePlayback): CastPlayback = CastPlayback(source, cast, scope) { 1_000 }

    @Test
    fun theTvIsWhereTheSongPlaysOnceItIsCastAndTheAppsIsEverythingElse() {
        val source = FakePlayback()
        val cast = FakeCast()
        val playback = castPlayback(cast, source)
        val sourceSong = Following(PlaybackClock(0, 0, playing = true), "Spotify", canSeek = true)
        source.playing.value = sourceSong

        // Not cast: the app's.
        assertEquals(sourceSong, runBlocking { playback.follow(song).first() })
        assertFalse(playback.hasAccess())

        // Cast, with no TV yet: still the app's.
        playback.casting(song)
        cast.target.value = null
        assertEquals(sourceSong, runBlocking { playback.follow(song).first() })
        assertFalse(playback.hasAccess())

        // On the TV, before it says what plays, nothing is known; then what it says.
        cast.target.value = CastTarget("Living room")
        assertTrue(playback.hasAccess())
        assertNull(runBlocking { playback.follow(song).first() })
        cast.target.value = CastTarget("Living room", tv)
        val following = requireNotNull(runBlocking { playback.follow(song).first() })
        assertEquals(5_000, following.clock.positionAt(1_000))
        assertTrue(following.clock.playing && following.canSeek && following.canSkipNext && !following.canSkipPrevious)
        assertEquals("Crosstune", following.app)
        assertEquals(200_000L, following.durationMs)

        // Another song is still the app's.
        assertEquals(sourceSong, runBlocking { playback.follow(other).first() })
    }

    @Test
    fun theControlsOfTheCastSongGoToTheTvAndOthersToTheApp() {
        val source = FakePlayback()
        val cast = FakeCast().apply { target.value = CastTarget("Living room", tv) }
        val playback = castPlayback(cast, source)
        playback.casting(song)

        playback.seekTo(song, 9_000)
        playback.playPause(song, true)
        playback.playPause(song, false)
        playback.skip(song, true)
        playback.skip(song, false)
        assertEquals(listOf(CastCommand.Seek(9_000), CastCommand.Play, CastCommand.Pause, CastCommand.Next, CastCommand.Previous), cast.sent)
        assertTrue(source.seeks.isEmpty() && source.plays.isEmpty() && source.skips.isEmpty())

        // The song is the TV's, so the phone's app isn't asked to start it again; any other is.
        assertTrue(runBlocking { playback.playSong(song, "com.spotify", null, false) })
        assertTrue(source.songsAsked.isEmpty())
        runBlocking { playback.playSong(other, "com.spotify", null, false) }
        assertEquals(listOf(other to "com.spotify"), source.songsAsked.toList())

        playback.seekTo(other, 1)
        playback.playPause(other, true)
        playback.skip(other, true)
        assertEquals(listOf(1L), source.seeks.toList())
        assertEquals(listOf(true), source.plays.toList())
        assertEquals(listOf(true), source.skips.toList())
        assertEquals(5, cast.sent.size)

        // Let go, the song is the app's again.
        playback.casting(null)
        playback.playPause(song, true)
        assertEquals(listOf(true, true), source.plays.toList())
    }

    @Test
    fun aPracticeRequestForAnAppStaysOnThePhoneWhileTheSameSongCasts() {
        val app = "com.spotify.music"
        val local = Following(PlaybackClock(3_000, 0, playing = false), "Spotify", canSeek = true, appPackage = app)
        val source = FakePlayback().apply { playing.value = local }
        val cast = FakeCast().apply { target.value = CastTarget("Living room", tv) }
        val playback = castPlayback(cast, source)
        playback.casting(song)

        assertEquals(local, runBlocking { playback.follow(song, app).first() })
        playback.seekTo(song, 3_700, app)
        playback.playPause(song, true, app)
        assertTrue(runBlocking { playback.playSong(song, app, orLastPlayed = false) })
        assertEquals(listOf(app), source.followedApps)
        assertEquals(listOf(3_700L), source.seeks)
        assertEquals(listOf(true), source.plays)
        assertEquals(listOf(song to app), source.songsAsked)
        assertTrue(cast.sent.isEmpty())
    }

    @Test
    fun aRemoteNextHasItsOwnClockAndControlsWhileThePhonesAppStaysIndependent() {
        val app = "com.spotify.music"
        val local = Following(PlaybackClock(3_000, 0, playing = false), "Spotify", canSeek = true, appPackage = app)
        val source = FakePlayback().apply { playing.value = local }
        val cast = FakeCast().apply { target.value = CastTarget("Living room", tv) }
        val playback = castPlayback(cast, source)
        playback.casting(song)
        runBlocking {
            withTimeout(1_000) {
                val old = playback.follow(song).produceIn(this)
                assertEquals(5_000L, old.receive()?.clock?.positionAt(1_000))
                playback.skip(song, next = true)
                cast.target.value = CastTarget("Living room", tv.copy(title = other.title, artist = other.artist, positionMs = 8_000))
                assertNull(old.receive())
                old.cancel()
            }
        }

        val next = requireNotNull(runBlocking { playback.follow(other).first() })
        assertEquals(8_000L, next.clock.positionAt(1_000))
        assertEquals(local, runBlocking { playback.follow(song).first() })
        assertEquals(local, runBlocking { playback.follow(other, app).first() })
        playback.seekTo(other, 9_000)
        playback.seekTo(other, 4_000, app)
        assertTrue(runBlocking { playback.playSong(other, app, orLastPlayed = false) })
        assertEquals(listOf(CastCommand.Next, CastCommand.Seek(9_000)), cast.sent)
        assertEquals(listOf(4_000L), source.seeks)
        assertEquals(listOf(other to app), source.songsAsked)
    }

    @Test
    fun resumingTheCastSongPlaysOnTheTvAndOtherResumesStayWithThePhone() {
        val source = FakePlayback().apply { startsInBackground = false }
        val cast = FakeCast().apply { target.value = CastTarget("Living room", tv.copy(playing = false)) }
        val playback = castPlayback(cast, source)
        playback.casting(song)
        assertTrue(runBlocking { playback.resume(song) })
        assertEquals(listOf(CastCommand.Play), cast.sent.toList())
        assertTrue(source.plays.isEmpty())
        assertFalse(runBlocking { playback.resume(other) })
        assertEquals(listOf(true), source.plays.toList())
        cast.target.value = null
        source.startsInBackground = true
        assertTrue(runBlocking { playback.resume(song) })
        assertEquals(listOf(true, true), source.plays.toList())
        assertEquals(listOf(CastCommand.Play), cast.sent.toList())
    }

    @Test
    fun aTvCastToAndLaterGoneLeavesTheSongToTheApp() {
        val source = FakePlayback().apply { access = false }
        val cast = FakeCast()
        val playback = castPlayback(cast, source)
        playback.casting(song)
        cast.target.value = CastTarget("Living room", tv)
        assertTrue(playback.hasAccess())
        cast.target.value = null
        assertFalse(playback.hasAccess())
        playback.playPause(song, true)
        assertEquals(listOf(true), source.plays.toList())
    }

    @Test
    fun theAppKeepsOneSenderAndOneReceiverHoweverOftenTheScreenIsMadeAgain() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        var made = 0
        val fake = FakeCast()
        MainActivity.castSenderFactory = { made++; fake }
        MainActivity.castReceiverFactory = { made++; fake }
        try {
            assertSame(MainActivity.castSender(app), MainActivity.castSender(app))
            assertSame(MainActivity.castReceiver(app), MainActivity.castReceiver(app))
            assertEquals(2, made)
        } finally {
            MainActivity.castSenderFactory = ::platformCastSender
            MainActivity.castReceiverFactory = ::platformCastReceiver
        }
    }

    @Test
    fun aTvNeverCastsToAnother() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        assertSame(NoCast, platformCastSender(app))
    }
    @Test
    fun castingNeverBorrowsTheLinkOfAnotherSongAndPrefersTheOpenPlayer() {
        val song = MusicMetadata("Song", "Artist", url = "https://example.com/metadata")
        val other = song.copy(title = "Other")
        val link = MusicLink(MusicService.YOUTUBE_MUSIC, ItemType.TRACK, "demo", "https://example.com/result")
        val state = UiState(result = song, link = link, player = PlayerState(song, null, "https://example.com/player"))
        assertEquals("https://example.com/player", state.linkToCast(song))
        assertEquals(link.url, state.copy(player = null).linkToCast(song))
        assertEquals(link.url, state.copy(player = state.player?.copy(song = other)).linkToCast(song))
        assertEquals(song.url, state.copy(result = other, player = null).linkToCast(song))
        assertEquals(song.url, UiState().linkToCast(song))
        assertEquals(link.url, state.copy(player = state.player?.copy(link = null)).linkToCast(song))
        assertNull(UiState().linkToCast(song.copy(url = null)))
    }

    @Test
    fun aRadioStationThatNamesNoSongIsNeverHandedToTheTv() {
        val song = MusicMetadata("Hush", "Band")
        val station = PlayerState(MusicMetadata("Blue 100.7 FM", ""), RadioNotice.PACKAGE, songless = true, stationAppLabel = "Simple Radio")
        val playing = UiState(player = station, castAround = true)
        assertNull(playing.songToCast)
        assertFalse(playing.canCast)
        // The song's card under it still goes.
        assertEquals(song, playing.copy(result = song).songToCast)
        assertEquals(song, playing.copy(player = PlayerState(song, null)).songToCast)
    }
}
