package com.astrovm.crosstune

import android.app.Application
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
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
import org.robolectric.annotation.Config
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
        // AndroidX keeps the allowed listeners until the setting changes, which a cleared one doesn't
        // count as, so the access would carry over into the tests after.
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", "")
        NotificationManagerCompat.getEnabledListenerPackages(app)
    }

    /** Something on the phone is making sound, as Android tells it. */
    private fun sounding(on: Boolean = true) = shadowOf(app.getSystemService(AudioManager::class.java)).setIsMusicActive(on)

    private fun allow() {
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", ComponentName(app, NowPlayingListener::class.java).flattenToString())
    }

    private fun player(packageName: String, label: String, title: String, artist: String?, state: Int = PlaybackState.STATE_PLAYING, actions: Long = PlaybackState.ACTION_SEEK_TO, art: Map<String, String> = emptyMap(), at: Long = PLAYED_AT, album: String? = null): MediaController {
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
                .apply { if (artist != null) putString(MediaMetadata.METADATA_KEY_ARTIST, artist) }
                .apply { art.forEach { (key, uri) -> putString(key, uri) } }
                .apply { if (album != null) putString(MediaMetadata.METADATA_KEY_ALBUM, album) }.build()
        )
        shadowOf(controller).setPlaybackState(PlaybackState.Builder().setState(state, 42_000, 1f, at).setActions(actions).build())
        return controller
    }

    private fun followed(song: MusicMetadata = this.song): MutableList<Following?> {
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
    fun androidHoldsItBackForAnAppInstalledFromAFile() {
        // Installed by a store, or with nothing known about it, nothing's held back.
        assertFalse(playback.restricted())
        shadowOf(app.packageManager).setInstallSourceInfo(app.packageName, null, null, null, null, null, PackageInstaller.PACKAGE_SOURCE_STORE)
        assertFalse(playback.restricted())
        // A file downloaded, or one copied over, has to have restricted settings allowed first.
        shadowOf(app.packageManager).setInstallSourceInfo(app.packageName, null, null, null, null, null, PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        assertTrue(playback.restricted())
        shadowOf(app.packageManager).setInstallSourceInfo(app.packageName, null, null, null, null, null, PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
        assertTrue(playback.restricted())
    }

    @Test
    @Config(sdk = [32])
    fun beforeAndroid13NothingIsHeldBack() {
        assertFalse(playback.restricted())
    }

    @Test
    fun theAppPlayingTheSongIsFollowedWithWhereItIs() {
        allow()
        // Another song in one app, and this one, as a video, in another.
        shadowOf(sessions).addController(player("com.other.player", "Other", "Something Else", "Someone"))
        shadowOf(sessions).addController(player("com.google.android.youtube", "YouTube", "The Weeknd - Blinding Lights (Official Video)", "TheWeekndVEVO"))
        assertEquals(Following(PlaybackClock(42_000, PLAYED_AT, 1f, true), "YouTube", canSeek = true, appPackage = "com.google.android.youtube"), followed().last())
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
    fun aJapaneseVideoIsFollowedAndNamedAsTheSongInItsBrackets() {
        allow()
        sounding()
        val seen = followed(MusicMetadata("曲名", "アーティスト"))
        shadowOf(sessions).addController(player("com.google.android.youtube", "YouTube", "アーティスト「曲名」 Official Music Video", "アーティスト"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("YouTube", seen.last()?.app)
        assertEquals(MusicMetadata("曲名", "アーティスト"), playback.nowPlaying()?.metadata)
    }

    @Test
    fun anAppMovingOnToTheSongIsFollowedFromThen() {
        allow()
        // YouTube plays an ad first, then the video, in the same session.
        val youtube = player("com.google.android.youtube", "YouTube", "An Ad", "Advertiser")
        shadowOf(sessions).addController(youtube)
        val seen = followed()
        assertNull(seen.last())
        val video = MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "The Weeknd - Blinding Lights (Official Video)")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "TheWeekndVEVO").build()
        shadowOf(youtube).setMetadata(video)
        shadowOf(youtube).executeOnMetadataChanged(video)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("YouTube", seen.last()?.app)
    }

    @Test
    fun followingStopsListeningToEveryApp() {
        allow()
        val playing = player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd")
        val other = player("com.other", "Other", "Another Song", "Someone")
        shadowOf(sessions).addController(playing)
        shadowOf(sessions).addController(other)
        followed()
        assertEquals(true, shadowOf(other).callbacks.isNotEmpty())
        // Another app starting doesn't leave the others listened to twice.
        val before = shadowOf(playing).callbacks.size
        shadowOf(sessions).addController(player("com.third", "Third", "Third Song", "Someone"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, shadowOf(playing).callbacks.size)
        collecting.forEach(Job::cancel)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<Any>(), shadowOf(playing).callbacks + shadowOf(other).callbacks)
        // An app showing up later is listened to in its place.
        val later = player("com.later", "Later", "Song", "Band")
        followed()
        shadowOf(sessions).addController(later)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(true, shadowOf(later).callbacks.isNotEmpty())
    }

    @Test
    fun theOnePlayingWinsOverOnePausedOnTheSameSong() {
        allow()
        shadowOf(sessions).addController(player("com.paused", "Paused", "Blinding Lights", "The Weeknd", state = PlaybackState.STATE_PAUSED))
        shadowOf(sessions).addController(player("com.playing", "Playing", "Blinding Lights", "The Weeknd"))
        assertEquals("Playing", followed().last()?.app)
    }

    @Test
    fun theSongPlayingOnThePhoneIsNamedWithWhereItIs() {
        // Not allowed yet, Android shows nothing. Empty rather than unset, which would keep what another test allowed.
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", "")
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd"))
        assertNull(playback.nowPlaying())
        allow()
        sounding()
        assertEquals(Heard.Song(song, appleMusicId = null, offsetMs = 42_000, startedAtMs = PLAYED_AT), playback.nowPlaying())
    }

    @Test
    fun onlyAnAppPlayingASongWithItsArtistNamesIt() {
        allow()
        sounding()
        // Paused, with no artist, with no title, with no song at all, or Crosstune itself: none are a song playing.
        shadowOf(sessions).addController(player("com.paused", "Paused", "Something Else", "Someone", state = PlaybackState.STATE_PAUSED))
        shadowOf(sessions).addController(player("com.noartist", "No Artist", "A Voice Memo", null))
        shadowOf(sessions).addController(player("com.notitle", "No Title", "", "Someone"))
        shadowOf(sessions).addController(MediaController(app, MediaSession(app, "com.nothing").sessionToken).also { shadowOf(it).setPackageName("com.nothing") })
        shadowOf(sessions).addController(player(app.packageName, "Crosstune", "Its Own Preview", "Someone"))
        assertNull(playback.nowPlaying())

        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd"))
        assertEquals(song, playback.nowPlaying()?.metadata)
    }

    @Test
    fun anAppThatStillSaysItPlaysLongAfterItStoppedIsNotBelieved() {
        allow()
        // A browser tab left saying it plays, and the app really playing, which said where it is since.
        shadowOf(sessions).addController(player("com.brave.browser", "Brave", "Cuando Perriabas", "Bad Bunny", at = PLAYED_AT - 10_000_000))
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd"))
        // Nothing sounding: neither is, really.
        sounding(false)
        assertNull(playback.nowPlaying())
        sounding()
        assertEquals(song, playback.nowPlaying()?.metadata)
    }

    @Test
    fun aVideoIsNamedAsTheSongItsTitleNames() {
        allow()
        sounding()
        shadowOf(sessions).addController(player("com.brave.browser", "Brave", "The Weeknd - Blinding Lights (Official Video)", "TheWeekndVEVO"))
        assertEquals(song, playback.nowPlaying()?.metadata)
        // A title that's only what a video adds names nothing.
        ShadowMediaSessionManager.reset()
        shadowOf(sessions).addController(player("com.brave.browser", "Brave", "(Official Video)", "Someone"))
        assertNull(playback.nowPlaying())
    }

    @Test
    fun aMusicAppsSongIsTakenAsItsNamed() {
        allow()
        sounding()
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Here Comes The Sun - Remastered 2019", "The Beatles", album = "Abbey Road"))
        assertEquals(MusicMetadata("Here Comes The Sun - Remastered 2019", "The Beatles"), playback.nowPlaying()?.metadata)
    }

    @Test
    fun aFilePlayingInABrowserIsNoSong() {
        allow()
        sounding()
        shadowOf(sessions).addController(player("com.brave.browser", "Brave", "example.com/music/song.mp3", "example.com"))
        assertNull(playback.nowPlaying())
        // A song named with dots, by an artist named with one, is one.
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "will.i.am/remix", "will.i.am band", at = PLAYED_AT - 1))
        shadowOf(sessions).addController(player("com.other.player", "Other", "Mr. Brightside", "The Killers", at = PLAYED_AT - 2))
        assertEquals("will.i.am/remix", playback.nowPlaying()?.metadata?.title)
        // Nor is one whose title starts with an artist that's no site.
        shadowOf(sessions).addController(player("com.deezer.android.app", "Deezer", "Band/Remix", "Band", at = PLAYED_AT + 1))
        assertEquals("Band/Remix", playback.nowPlaying()?.metadata?.title)
    }

    @Test
    fun itsCoverComesAlongWhenItsOnTheWeb() {
        allow()
        sounding()
        val cover = "https://i.scdn.co/image/cover"
        // A cover only the app itself can open is left out; the album's is used when the song has none.
        shadowOf(sessions).addController(
            player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd", art = mapOf(MediaMetadata.METADATA_KEY_ART_URI to "content://com.spotify/cover", MediaMetadata.METADATA_KEY_ALBUM_ART_URI to cover))
        )
        assertEquals(cover, playback.nowPlaying()?.metadata?.artworkUrl)
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
