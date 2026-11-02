package com.astrovm.crosstune

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What a song and a command look like on their way to the TV, and the phone's player becoming the TV's remote. */
@RunWith(RobolectricTestRunner::class)
class CastTest {
    private val tv = CastTvState("Hush", "Band", playing = true, positionMs = 5_000, durationMs = 200_000, canSeek = true, canSkipNext = true)

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
        assertEquals(CastCommand.Translation(true), CastCommand.fromMessage(CastCommand.Translation(true).message()!!))
        assertEquals(CastCommand.Readings(false), CastCommand.fromMessage(CastCommand.Readings(false).message()!!))
        listOf(CastCommand.Play, CastCommand.Pause, CastCommand.Stop, CastCommand.Seek(1), CastCommand.Next, CastCommand.Previous).forEach { assertNull(it.message()) }
        assertNull(CastCommand.fromMessage("not json"))
        assertNull(CastCommand.fromMessage("""{"type":"explode","on":true}"""))
    }

    @Test
    fun noCastAtAllDoesNothingAndIsNeverAround() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
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

    private fun castPlayback(cast: FakeCast, source: FakePlayback): CastPlayback = CastPlayback(source, cast) { 1_000 }

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
        val following = runBlocking { playback.follow(song).first() }!!
        assertEquals(5_000, following.clock.positionAt(1_000))
        assertTrue(following.clock.playing && following.canSeek && following.canSkipNext && !following.canSkipPrevious)
        assertEquals("Living room", following.app)
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
}
