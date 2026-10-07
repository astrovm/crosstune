package com.astrovm.crosstune

import android.app.Application
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowMediaSessionManager

/** Following what a music app plays, through the media sessions Android shows Crosstune once allowed. */
@RunWith(RobolectricTestRunner::class)
class PlaybackTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val sessions = app.getSystemService(MediaSessionManager::class.java)
    private val playback = MediaSessionPlayback(app, now = { NOW })
    private val song = MusicMetadata("Blinding Lights", "The Weeknd")
    private val collecting = mutableListOf<Job>()

    @After
    fun tearDown() {
        collecting.forEach(Job::cancel)
        ShadowMediaSessionManager.reset()
    }

    private fun allow() {
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", ComponentName(app, NowPlayingListener::class.java).flattenToString())
    }

    private fun player(packageName: String, label: String, title: String, artist: String?, state: Int = PlaybackState.STATE_PLAYING, actions: Long = PlaybackState.ACTION_SEEK_TO): MediaController {
        shadowOf(app.packageManager).installPackage(
            PackageInfo().apply {
                this.packageName = packageName
                applicationInfo = ApplicationInfo().apply { this.packageName = packageName; nonLocalizedLabel = label }
            }
        )
        val controller = MediaController(app, MediaSession(app, packageName).sessionToken)
        shadowOf(controller).setPackageName(packageName)
        shadowOf(controller).setMetadata(
            MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .apply { if (artist != null) putString(MediaMetadata.METADATA_KEY_ARTIST, artist) }.build()
        )
        shadowOf(controller).setPlaybackState(PlaybackState.Builder().setState(state, 42_000, 1f, PLAYED_AT).setActions(actions).build())
        return controller
    }

    private fun followed(): MutableList<Following?> {
        val seen = mutableListOf<Following?>()
        collecting += CoroutineScope(Dispatchers.Unconfined).launch { playback.follow(song).collect { seen += it } }
        shadowOf(Looper.getMainLooper()).idle()
        return seen
    }

    @Test
    fun itsAllowedOnlyOnceTheUserSaysSo() {
        assertFalse(playback.hasAccess())
        allow()
        assertTrue(playback.hasAccess())
        // Its part of Android's notification access is only there to be allowed.
        assertNotNull(NowPlayingListener())
    }

    @Test
    fun theAppPlayingTheSongIsFollowedWithWhereItIs() {
        allow()
        // Another song in one app, and this one, as a video, in another.
        shadowOf(sessions).addController(player("com.other.player", "Other", "Something Else", "Someone"))
        shadowOf(sessions).addController(player("com.google.android.youtube", "YouTube", "The Weeknd - Blinding Lights (Official Video)", "TheWeekndVEVO"))
        assertEquals(Following(PlaybackClock(42_000, PLAYED_AT, 1f, true), "YouTube", canSeek = true), followed().last())
    }

    @Test
    fun itFollowsAlongAsTheAppPausesStopsOrStarts() {
        allow()
        val youtube = player("com.google.android.youtube", "YouTube", "Blinding Lights", null)
        shadowOf(sessions).addController(youtube)
        val seen = followed()
        assertTrue(seen.last()!!.clock.playing)

        shadowOf(youtube).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 50_000, 0f, 0).build())
        shadowOf(Looper.getMainLooper()).idle()
        // Paused, at 0 speed, it's still followed, just not moving; with no time of its own, it's now.
        assertEquals(PlaybackClock(50_000, NOW, 1f, false), seen.last()!!.clock)
        assertFalse(seen.last()!!.canSeek)

        // A video that won't play, e.g. in the background without an account, plays nothing to follow.
        shadowOf(youtube).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_ERROR, 0, 1f, 0).build())
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(seen.last())
        // Back to playing, then gone quiet with no state at all.
        shadowOf(youtube).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 1_000, 1f, PLAYED_AT).build())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1_000L, seen.last()!!.clock.positionMs)
        shadowOf(youtube).executeOnPlaybackStateChanged(null)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(seen.last())
    }

    @Test
    fun onlyTheSameSongByTheSameArtistIsFollowed() {
        allow()
        // Same name, someone else's; no name at all; no session at all.
        shadowOf(sessions).addController(player("com.a", "A", "Blinding Lights", "A Cover Band"))
        shadowOf(sessions).addController(player("com.b", "B", "", "The Weeknd"))
        assertNull(followed().last())

        // Its app starts playing it later.
        val seen = followed()
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Spotify", seen.last()?.app)
    }

    @Test
    fun theOnePlayingWinsOverOnePausedOnTheSameSong() {
        allow()
        shadowOf(sessions).addController(player("com.paused", "Paused", "Blinding Lights", "The Weeknd", state = PlaybackState.STATE_PAUSED))
        shadowOf(sessions).addController(player("com.playing", "Playing", "Blinding Lights", "The Weeknd"))
        assertEquals("Playing", followed().last()?.app)
    }

    @Test
    fun aLineTappedMovesTheAppThere() {
        allow()
        val spotify = player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd")
        shadowOf(sessions).addController(spotify)
        playback.seekTo(song, 61_000)
        // Nothing playing it, nothing to move.
        playback.seekTo(MusicMetadata("Other", "Band"), 1_000)
    }

    private companion object {
        const val NOW = 500_000L
        const val PLAYED_AT = 400_000L
    }
}
