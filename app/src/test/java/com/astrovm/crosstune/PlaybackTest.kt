package com.astrovm.crosstune

import android.app.Application
import android.content.ComponentName
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.media.AudioAttributes
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URI
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.PlaybackInfoBuilder
import org.robolectric.shadows.ShadowMediaSessionManager

/** Following what a music app plays, through the media sessions Android shows Crosstune once allowed. */
@RunWith(RobolectricTestRunner::class)
class PlaybackTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val sessions = app.getSystemService(MediaSessionManager::class.java)
    /** Each app woken to be asked for a song, as its packages, and the player it wakes as; none, when null. */
    private val woken = mutableListOf<List<String>>()
    private var wakes: MediaController? = null
    private val playback = MediaSessionPlayback(app, now = { NOW }, waker = { woken += it; wakes })
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

    private fun player(packageName: String, label: String, title: String, artist: String?, state: Int = PlaybackState.STATE_PLAYING, actions: Long = PlaybackState.ACTION_SEEK_TO, art: Map<String, String> = emptyMap(), at: Long = PLAYED_AT, album: String? = null, position: Long = 42_000, duration: Long? = null): MediaController {
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
                .apply { if (album != null) putString(MediaMetadata.METADATA_KEY_ALBUM, album) }
                .apply { if (duration != null) putLong(MediaMetadata.METADATA_KEY_DURATION, duration) }.build()
        )
        shadowOf(controller).setPlaybackState(PlaybackState.Builder().setState(state, position, 1f, at).setActions(actions).build())
        return controller
    }

    private fun followed(song: MusicMetadata = this.song): MutableList<Following?> {
        val seen = mutableListOf<Following?>()
        collecting += CoroutineScope(Dispatchers.Unconfined).launch { playback.follow(song).collect { seen += it } }
        shadowOf(Looper.getMainLooper()).idle()
        return seen
    }

    private fun offered(): MutableList<Heard.Song?> {
        val seen = mutableListOf<Heard.Song?>()
        collecting += CoroutineScope(Dispatchers.Unconfined).launch { playback.nowPlayingUpdates().collect { seen += it } }
        shadowOf(Looper.getMainLooper()).idle()
        return seen
    }

    @Test
    fun aPausedRecordingStillIdentifiesTheLyricsCache() {
        assertNull(playback.recording(song))
        allow()
        assertNull(playback.recording(song))
        val paused = player("com.google.android.apps.youtube.music", "YouTube Music", "ブラインディング - Blinding Lights", song.artist,
            state = PlaybackState.STATE_PAUSED, duration = 240_000)
        shadowOf(sessions).addController(paused)
        assertEquals("com.google.android.apps.youtube.music:duration:240000", playback.recording(song)?.recordingId)
        assertEquals(song.title, playback.recording(song)?.title)
        assertEquals(song.artist, playback.recording(song)?.artist)
        assertNull(playback.recording(song.copy(title = "Another song")))
        assertNull(playback.nowPlaying())
    }

    @Test
    fun theOfferedSongTracksPlaybackAndMetadataWithoutReopeningTheApp() {
        allow()
        sounding()
        val seen = offered()
        assertEquals(listOf<Heard.Song?>(null), seen)
        val spotify = player("com.spotify.music", "Spotify", song.title, song.artist)
        shadowOf(sessions).addController(spotify)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(song, seen.last()?.metadata)

        // Repeated position updates and pause/resume sequences keep agreeing with Android.
        val random = kotlin.random.Random(42)
        repeat(30) {
            val state = listOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED)[random.nextInt(3)]
            shadowOf(spotify).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(state, it * 1000L, 1f, NOW).build())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(if (state == PlaybackState.STATE_PLAYING) song else null, seen.last()?.metadata)
        }
        shadowOf(spotify).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f, NOW).build())
        shadowOf(spotify).executeOnMetadataChanged(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Next Song")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Next Artist").build())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(MusicMetadata("Next Song", "Next Artist"), seen.last()?.metadata)
        shadowOf(spotify).executeOnMetadataChanged(null)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(seen.last())
    }

    @Test
    fun theOfferTracksAudioAndStopsWatchingWhenClosed() {
        allow()
        val spotify = player("com.spotify.music", "Spotify", song.title, song.artist)
        shadowOf(sessions).addController(spotify)
        val seen = offered()
        assertNull(seen.last())
        fun audio(on: Boolean) {
            sounding(on)
            shadowOf(app.getSystemService(AudioManager::class.java)).setActivePlaybackConfigurationsFor(
                if (on) listOf(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()) else emptyList(), true)
            shadowOf(Looper.getMainLooper()).idle()
        }
        audio(true)
        assertEquals(song, seen.last()?.metadata)
        audio(false)
        assertNull(seen.last())
        audio(true)
        shadowOf(sessions).clearControllers()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(seen.last())
        assertTrue(shadowOf(spotify).callbacks.isEmpty())
        shadowOf(sessions).addController(spotify)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, shadowOf(spotify).callbacks.size)
        // A dead session's last metadata must not be offered, even before its list is updated.
        sounding(false)
        shadowOf(spotify).executeOnSessionDestroyed()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(seen.last())
        collecting.forEach(Job::cancel)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(spotify).callbacks.isEmpty())
        val count = seen.size
        audio(true)
        shadowOf(sessions).addController(player("com.other", "Other", "New", "Artist", album = "Album"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(count, seen.size)
    }

    @Test
    fun anOfferCannotReadSessionsWithoutAccess() {
        sounding()
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", song.title, song.artist))
        assertEquals(listOf<Heard.Song?>(null), offered())
    }

    @Test
    fun revokingAccessOrReceivingAnOldCallbackCannotKeepAnOldOffer() {
        allow()
        sounding()
        val spotify = player("com.spotify.music", "Spotify", song.title, song.artist)
        shadowOf(sessions).addController(spotify)
        val seen = offered()
        assertEquals(song, seen.last()?.metadata)
        val oldCallbacks = shadowOf(spotify).callbacks.toList()
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", "")
        oldCallbacks.forEach { it.onPlaybackStateChanged(null) }
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(seen.last())
        allow()
        shadowOf(sessions).clearControllers()
        shadowOf(Looper.getMainLooper()).idle()
        oldCallbacks.forEach { it.onMetadataChanged(null) }
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(seen.last())
    }

    @Test
    @Config(shadows = [DeniedSessions::class])
    fun androidRefusingTheSubscriptionOffersNothingAndStillCloses() {
        allow()
        sounding()
        assertEquals(listOf<Heard.Song?>(null), offered())
    }

    @Implements(MediaSessionManager::class)
    class DeniedSessions : ShadowMediaSessionManager() {
        @Implementation
        override fun addOnActiveSessionsChangedListener(listener: MediaSessionManager.OnActiveSessionsChangedListener, component: ComponentName?, handler: android.os.Handler?) {
            throw SecurityException("Notification access was revoked")
        }
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
        assertEquals(Following(PlaybackClock(42_000, PLAYED_AT, 1f, true), "YouTube", canSeek = true, appPackage = "com.google.android.youtube", video = true), followed().last())
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
    fun aPauseSaidAMomentBehindPlaysOnFromWhereItReallyStopped() {
        allow()
        // Playing from 42 s since PLAYED_AT, so 10 s later it's at 52 s; YouTube Music says it paused 400 ms before.
        val music = player("com.google.android.apps.youtube.music", "YouTube Music", "Blinding Lights", "The Weeknd", album = "After Hours")
        shadowOf(sessions).addController(music)
        val seen = followed()
        fun says(state: Int, position: Long, at: Long) {
            shadowOf(music).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(state, position, 1f, at).build())
            shadowOf(Looper.getMainLooper()).idle()
        }

        says(PlaybackState.STATE_PAUSED, 51_600, PLAYED_AT + 10_000)
        assertEquals(PlaybackClock(52_000, PLAYED_AT + 10_000, 1f, false), seen.last()!!.clock)
        // Played again from where it said, it goes on from where it really stopped.
        says(PlaybackState.STATE_PLAYING, 51_600, PLAYED_AT + 20_000)
        assertEquals(PlaybackClock(52_000, PLAYED_AT + 20_000, 1f, true), seen.last()!!.clock)
        // The same again, e.g. as the app names the song anew, changes nothing.
        shadowOf(music).executeOnMetadataChanged(music.metadata!!)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(52_000L, seen.last()!!.clock.positionMs)

        // Moved elsewhere, it's where the app says.
        says(PlaybackState.STATE_PLAYING, 90_000, PLAYED_AT + 25_000)
        assertEquals(90_000L, seen.last()!!.clock.positionMs)
        // Said to pause right where it was, or ahead, or well behind, e.g. moved back as it paused, it's believed.
        says(PlaybackState.STATE_PAUSED, 95_000, PLAYED_AT + 30_000)
        assertEquals(95_000L, seen.last()!!.clock.positionMs)
        says(PlaybackState.STATE_PLAYING, 95_000, PLAYED_AT + 40_000)
        says(PlaybackState.STATE_PAUSED, 100_500, PLAYED_AT + 45_000)
        assertEquals(100_500L, seen.last()!!.clock.positionMs)
        says(PlaybackState.STATE_PLAYING, 100_500, PLAYED_AT + 50_000)
        says(PlaybackState.STATE_PAUSED, 105_500 - STALE_PAUSE_MS - 1, PLAYED_AT + 55_000)
        assertEquals(105_500 - STALE_PAUSE_MS - 1, seen.last()!!.clock.positionMs)
        // Up to a second behind is still taken as where it stopped.
        says(PlaybackState.STATE_PLAYING, 110_000, PLAYED_AT + 60_000)
        says(PlaybackState.STATE_PAUSED, 115_000 - STALE_PAUSE_MS, PLAYED_AT + 65_000)
        assertEquals(115_000L, seen.last()!!.clock.positionMs)
        // Moved while paused, it's where it was moved to.
        says(PlaybackState.STATE_PAUSED, 30_000, PLAYED_AT + 70_000)
        assertEquals(30_000L, seen.last()!!.clock.positionMs)
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
    fun aPlayingRecordingKeepsItsIdAndLink() {
        allow()
        sounding()
        val link = "https://music.youtube.com/watch?v=abcdefghijk"
        val controller = player("com.google.android.apps.youtube.music", "YouTube Music", "Demo", "Band",
            art = mapOf(MediaMetadata.METADATA_KEY_MEDIA_ID to "video-one", MediaMetadata.METADATA_KEY_MEDIA_URI to link))
        shadowOf(sessions).addController(controller)
        assertEquals("com.google.android.apps.youtube.music:video-one", playback.nowPlaying()?.metadata?.recordingId)
        assertEquals(link, playback.nowPlaying()?.metadata?.url)
        val other = playback.nowPlaying()!!.metadata.copy(recordingId = "com.google.android.apps.youtube.music:video-two")
        assertNull(followed(other).last())
        val uriId = player("com.spotify.music", "Spotify", "Demo", "Band", art = mapOf(MediaMetadata.METADATA_KEY_MEDIA_ID to "spotify:track:4Z8W4fKeB5YxbusRsdQVPb"))
        shadowOf(sessions).clearControllers()
        shadowOf(sessions).addController(uriId)
        assertEquals("spotify:track:4Z8W4fKeB5YxbusRsdQVPb", playback.nowPlaying()?.metadata?.url)
    }

    @Test
    fun aSongPlayingSaysHowLongItIs() {
        allow()
        sounding()
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd", album = "After Hours", duration = 200_040))
        assertEquals(200_040L, playback.nowPlaying()?.metadata?.lengthMs)
    }

    @Test
    fun aVideosLengthIsntTheSongs() {
        allow()
        sounding()
        // A video's length is the video's, which may open with more than the song.
        shadowOf(sessions).addController(player("com.google.android.youtube", "YouTube", "The Weeknd - Blinding Lights (Official Video)", "TheWeekndVEVO", duration = 262_000))
        assertEquals("Blinding Lights", playback.nowPlaying()?.metadata?.title)
        assertNull(playback.nowPlaying()?.metadata?.lengthMs)
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
    fun aTabLeftBehindWithoutAPlaceIsNoSong() {
        allow()
        sounding()
        // Says it plays "YouTube Music", but not where, and hasn't said anything for long.
        shadowOf(sessions).addController(player("com.brave.browser", "Brave", "YouTube Music", "music.youtube.com", at = NOW - LEFT_BEHIND_MS - 1, position = -1))
        assertNull(playback.nowPlaying())
        // Said just now, it's still named, but with no place to follow.
        ShadowMediaSessionManager.reset()
        shadowOf(sessions).addController(player("com.radio", "Radio", "Blinding Lights", "The Weeknd", album = "Live", at = NOW, position = -1))
        assertEquals(song, playback.nowPlaying()?.metadata)
        assertNull(playback.nowPlaying()?.clock)
        assertNull(followed().last())
    }

    @Test
    fun whatPlaysOnAnotherDeviceIsntWhatThePhonePlays() {
        allow()
        sounding()
        // A video on the computer, shown through KDE Connect, says where it is more often than the music app on the phone.
        val computer = player("org.kde.kdeconnect_tp", "KDE Connect", "Fake Talk Show - Episode 12", "Fake Channel", at = NOW)
        shadowOf(computer).setPlaybackInfo(PlaybackInfoBuilder.newBuilder().setVolumeType(MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE).build())
        shadowOf(sessions).addController(computer)
        assertNull(playback.nowPlaying())
        shadowOf(sessions).addController(player("com.music", "Music", "Blinding Lights", "The Weeknd", album = "After Hours", at = NOW - 60_000))
        assertEquals(song, playback.nowPlaying()?.metadata)
    }

    @Test
    fun aSongAMusicAppCastsToATvIsWhatThePhonePlays() {
        allow()
        fun remote(controller: MediaController) = controller.also {
            shadowOf(it).setPlaybackInfo(PlaybackInfoBuilder.newBuilder().setVolumeType(MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE).build())
        }
        // Cast, the phone itself plays nothing, and the computer's video shown through KDE Connect still isn't it.
        shadowOf(sessions).addController(remote(player("org.kde.kdeconnect_tp", "KDE Connect", "Fake Talk Show - Episode 12", "Fake Channel", at = NOW)))
        shadowOf(sessions).addController(remote(player("com.google.android.apps.youtube.music", "YouTube Music", "Blinding Lights", "The Weeknd", album = "After Hours", at = NOW - 60_000)))
        assertEquals(song, playback.nowPlaying()?.metadata)
        assertEquals(song.title, playback.recording(song)?.title)

        // A song the phone itself plays comes first, though the cast one said where it is later.
        ShadowMediaSessionManager.reset()
        shadowOf(sessions).addController(remote(player("com.spotify.music", "Spotify", "Save Your Tears", "The Weeknd", album = "After Hours", at = NOW)))
        shadowOf(sessions).addController(player("com.music", "Music", "Blinding Lights", "The Weeknd", album = "After Hours", at = NOW - 60_000))
        assertEquals("Save Your Tears", playback.nowPlaying()?.metadata?.title)
        sounding()
        assertEquals(song, playback.nowPlaying()?.metadata)
    }

    @Test
    fun aVideosPlaceIsntGivenAsItsSongsButIsFollowedAsAVideo() {
        allow()
        sounding()
        shadowOf(sessions).addController(player("com.google.android.youtube", "YouTube", "The Weeknd - Blinding Lights (Official Video)", "TheWeekndVEVO"))
        assertEquals(song, playback.nowPlaying()?.metadata)
        assertNull(playback.nowPlaying()?.clock)
        assertEquals(true, followed().last()?.video)
        // A music app's place is the song's.
        ShadowMediaSessionManager.reset()
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd", album = "After Hours"))
        assertEquals(42_000L, playback.nowPlaying()?.clock?.positionMs)
        assertEquals(false, followed().last()?.video)
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
        ShadowMediaSessionManager.reset()
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd", album = "After Hours"))
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

        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd", album = "After Hours"))
        assertEquals(song, playback.nowPlaying()?.metadata)
    }

    @Test
    fun anAppThatStillSaysItPlaysLongAfterItStoppedIsNotBelieved() {
        allow()
        // A browser tab left saying it plays, and the app really playing, which said where it is since.
        shadowOf(sessions).addController(player("com.brave.browser", "Brave", "Cuando Perriabas", "Bad Bunny", album = "Single", at = PLAYED_AT - 10_000_000))
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd", album = "After Hours"))
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
    fun anArtistsOwnVideoAndLocalizedMusicTagsAreOfferedAsPlaying() {
        allow()
        sounding()
        for ((title, artist) in listOf("The Weeknd - Blinding Lights" to "The Weeknd", "Blinding Lights (Video Oficial)" to "The Weeknd")) {
            ShadowMediaSessionManager.reset()
            shadowOf(sessions).addController(player("com.google.android.youtube", "YouTube", title, artist))
            assertEquals(song, playback.nowPlaying()?.metadata)
        }
    }

    @Test
    fun aVideoThatDoesntSayItsMusicIsNoSong() {
        allow()
        sounding()
        shadowOf(sessions).addController(player("com.google.android.youtube", "YouTube", "A New Approach To An Ancient Linux Terminal Problem", "Brodie Robertson"))
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
    fun musicAppsWithoutAnAlbumKeepTheirSongNamesAndPlaybackPosition() {
        allow()
        sounding()
        for (service in MusicService.entries.filter { it != MusicService.YOUTUBE && it != MusicService.YOUTUBE_MUSIC }) {
            ShadowMediaSessionManager.reset()
            shadowOf(sessions).addController(player(service.packageName, service.name, "River - Road", "The Walkers", duration = 180_000))
            val song = MusicMetadata("River - Road", "The Walkers", lengthMs = 180_000, recordingId = "${service.packageName}:duration:180000")
            val heard = playback.nowPlaying()
            assertEquals(service.name, song, heard?.metadata)
            assertEquals(service.name, 42_000L, heard?.offsetMs)
            assertEquals(service.name, false, followed(song).last()?.video)
        }
    }

    @Test
    fun youTubeMusicVideosAreReadAsTheSongTheyNameAndItsSongsStayWhole() = MusicService.YOUTUBE_MUSIC.packageNames.forEach { music ->
        allow()
        sounding()
        // ReVanced's and the like, patched from it, too.
        // As YouTube Music names them: a video, its official one included, has no album; a song has its own.
        for ((playing, heard) in listOf(
            Triple("Queen - Bohemian Rhapsody (Lyrics)", "7clouds", null) to MusicMetadata("Bohemian Rhapsody", "Queen"),
            Triple("Never Gonna Give You Up", "Rick Astley", null) to MusicMetadata("Never Gonna Give You Up", "Rick Astley"),
            Triple("Bohemian Rhapsody - Remastered 2011", "Queen", "A Night at the Opera") to MusicMetadata("Bohemian Rhapsody - Remastered 2011", "Queen", lengthMs = 354_000)
        )) {
            val (title, artist, album) = playing
            ShadowMediaSessionManager.reset()
            shadowOf(sessions).addController(player(music, "YouTube Music", title, artist, album = album, duration = 354_000))
            val now = playback.nowPlaying()
            assertEquals(title, heard.copy(recordingId = "$music:duration:354000"), now?.metadata)
            // Where a video is isn't where its song is.
            assertEquals(title, if (album == null) null else 42_000L, now?.offsetMs)
            assertEquals(title, album == null, followed(heard).last()?.video)
        }
    }

    @Test
    fun aBuildPatchedFromAnAppIsAskedAndOpenedAsThatApp() {
        val patched = "app.morphe.android.apps.youtube.music"
        val official = MusicService.YOUTUBE_MUSIC.packageName
        allow()
        shadowOf(sessions).addController(player(patched, "YouTube Music", "Other", "Band"))
        assertTrue(runBlocking { playback.playSong(song, official, "https://music.youtube.com/watch?v=other000000") })
        assertTrue(woken.isEmpty())
        // Only the patched build installed, the song's opened there.
        shadowOf(app).checkActivities(true)
        val opens = ComponentName(patched, "$patched.UrlActivity")
        shadowOf(app.packageManager).addActivityIfNotPresent(opens)
        shadowOf(app.packageManager).addIntentFilterForActivity(
            opens, IntentFilter(Intent.ACTION_VIEW).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("https") }
        )
        assertTrue(playback.openSong(official, "https://music.youtube.com/watch?v=other000000"))
        assertEquals(patched, shadowOf(app).nextStartedActivity.`package`)
        assertFalse(playback.openSong("com.example.gone", "https://music.youtube.com/watch?v=other000000"))
    }

    @Test
    fun onATvAYouTubeMusicSongOpensInYouTubesTvApp() {
        // YouTube Music's TV app opens its home for a song's link; YouTube's plays it, as YouTube's.
        shadowOf(app).checkActivities(true)
        val tv = ComponentName(MusicService.YOUTUBE_TV, "${MusicService.YOUTUBE_TV}.ShellActivity")
        shadowOf(app.packageManager).addActivityIfNotPresent(tv)
        shadowOf(app.packageManager).addIntentFilterForActivity(
            tv, IntentFilter(Intent.ACTION_VIEW).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("https") }
        )
        assertTrue(playback.openSong(MusicService.YOUTUBE_MUSIC.packageName, "https://music.youtube.com/watch?v=other000000&list=RDAMVMother000000"))
        val opened = shadowOf(app).nextStartedActivity
        assertEquals(MusicService.YOUTUBE_TV, opened.`package`)
        assertEquals("https://www.youtube.com/watch?v=other000000&list=RDAMVMother000000", opened.dataString)
        // What it plays is YouTube's.
        assertEquals(MusicService.YOUTUBE, MusicService.ofPackage(MusicService.YOUTUBE_TV))
        // Other services' links, and YouTube Music's that aren't its site, go to their own apps only.
        assertEquals(listOf(MusicService.SPOTIFY.packageName to "https://open.spotify.com/track/x"), MusicService.SPOTIFY.appLinks("https://open.spotify.com/track/x"))
        assertEquals(MusicService.YOUTUBE_MUSIC.packageNames, MusicService.YOUTUBE_MUSIC.appLinks("vnd.youtube.music:x").map { it.first })
        assertEquals(MusicService.YOUTUBE_MUSIC.packageNames, MusicService.YOUTUBE_MUSIC.appLinks("https://www.youtube.com/watch?v=x").map { it.first })
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
            player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd", album = "After Hours", art = mapOf(MediaMetadata.METADATA_KEY_ART_URI to "content://com.spotify/cover", MediaMetadata.METADATA_KEY_ALBUM_ART_URI to cover))
        )
        assertEquals(cover, playback.nowPlaying()?.metadata?.artworkUrl)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun itsCoverIsTheOneItsAppShowsWhenThatsAllItGives() {
        allow()
        sounding()
        val controller = player("com.google.android.apps.youtube.music", "YouTube Music", "Moon Song", "Some Singer", album = "Best Of")
        val shown = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        shadowOf(controller).setMetadata(MediaMetadata.Builder(controller.metadata).putBitmap(MediaMetadata.METADATA_KEY_ART, shown).build())
        shadowOf(sessions).addController(controller)
        val cover = playback.nowPlaying()?.metadata?.artworkUrl!!
        // Kept with the covers, the same each time it's asked for.
        assertEquals(File(app.cacheDir, ARTWORK_DIR).canonicalFile, File(URI(cover)).canonicalFile.parentFile)
        assertEquals(cover, playback.nowPlaying()?.metadata?.artworkUrl)
        val loaded = runBlocking { ArtworkLoader(FakeSpotify().client(), Dispatchers.Unconfined, File(app.cacheDir, ARTWORK_DIR)).load(cover) }
        assertEquals(8, loaded?.width)
        // With none at all, there's none.
        shadowOf(sessions).addController(player("com.google.android.apps.youtube.music", "YouTube Music", "Other Song", "Some Singer", album = "Best Of", at = PLAYED_AT + 1))
        assertNull(playback.nowPlaying()?.metadata?.artworkUrl)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aCoverThatCouldntBeKeptIsntTakenForOneTheNextTime() {
        allow()
        sounding()
        val controller = player("com.google.android.apps.youtube.music", "YouTube Music", "Moon Song", "Some Singer", album = "Best Of")
        val gone = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { recycle() }
        shadowOf(controller).setMetadata(MediaMetadata.Builder(controller.metadata).putBitmap(MediaMetadata.METADATA_KEY_ART, gone).build())
        shadowOf(sessions).addController(controller)
        assertNull(playback.nowPlaying()?.metadata?.artworkUrl)
        // Given whole the next time, it's kept whole.
        val shown = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        shadowOf(controller).setMetadata(MediaMetadata.Builder(controller.metadata).putBitmap(MediaMetadata.METADATA_KEY_ART, shown).build())
        val cover = playback.nowPlaying()?.metadata?.artworkUrl!!
        val loaded = runBlocking { ArtworkLoader(FakeSpotify().client(), Dispatchers.Unconfined, File(app.cacheDir, ARTWORK_DIR)).load(cover) }
        assertEquals(8, loaded?.width)
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

    @Test
    fun theAppPlayingItIsTheOneControlledAsItsTheOneFollowed() {
        allow()
        // Spotify, first, is paused on the song, and YouTube Music plays it.
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd", state = PlaybackState.STATE_PAUSED))
        shadowOf(sessions).addController(player("com.google.android.apps.youtube.music", "YouTube Music", "Blinding Lights", "The Weeknd", album = "After Hours"))
        assertEquals("com.google.android.apps.youtube.music", followed().last()!!.appPackage)
        assertEquals("com.google.android.apps.youtube.music", playback.appFor(song)?.packageName)
        assertNull(playback.appFor(MusicMetadata("Other", "Band")))
    }

    @Test
    fun theAppPlayingItIsAskedToPlayOrPause() {
        allow()
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd"))
        playback.playPause(song, play = false)
        playback.playPause(song, play = true)
        // Nothing playing it, nothing to ask.
        playback.playPause(MusicMetadata("Other", "Band"), play = true)
    }

    @Test
    fun theAppPlayingItSaysHowLongTheSongIsWhenItKnows() {
        allow()
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd", duration = 200_000))
        assertEquals(200_000L, followed().last()!!.durationMs)
        // One that says nothing of it, or nothing sensible, has none.
        shadowOf(sessions).addController(player("com.soundcloud.android", "SoundCloud", "Other Song", "Band", duration = 0))
        assertNull(followed(MusicMetadata("Other Song", "Band")).last()!!.durationMs)
    }

    @Test
    fun theAppPlayingItSaysWhetherItCanGoOnToTheNextSongOrBack() {
        allow()
        val skips = PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        val spotify = player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd", actions = skips)
        shadowOf(sessions).addController(spotify)
        val seen = followed()
        assertTrue(seen.last()!!.canSkipNext)
        assertTrue(seen.last()!!.canSkipPrevious)
        assertFalse(seen.last()!!.canSeek)
        shadowOf(spotify).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 1_000, 1f, PLAYED_AT).setActions(PlaybackState.ACTION_SKIP_TO_NEXT).build())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(seen.last()!!.canSkipNext)
        assertFalse(seen.last()!!.canSkipPrevious)
        shadowOf(spotify).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 1_000, 1f, PLAYED_AT).build())
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(seen.last()!!.canSkipNext)
    }

    @Test
    fun theAppPlayingItIsAskedToGoOnToTheNextSongOrBack() {
        allow()
        // Robolectric doesn't pass what's asked on to the app, so this only checks asking is safe; the phone shows the rest.
        val spotify = player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd")
        shadowOf(sessions).addController(spotify)
        playback.skip(song, next = true)
        playback.skip(song, next = false)
        shadowOf(spotify).setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 1_000, 0f, PLAYED_AT).build())
        playback.skip(song, next = true)
        // Nothing playing it, nothing to ask.
        playback.skip(MusicMetadata("Other", "Band"), next = true)
    }

    @Test
    fun aSongIsAskedForByNameFromTheAppItWasIn() = runBlocking {
        // Without access, or with no music app to ask, there's no one to ask.
        assertFalse(playback.playSong(song, "com.spotify.music"))
        allow()
        assertFalse(playback.playSong(song, "com.spotify.music"))
        // Crosstune's own session isn't asked.
        shadowOf(sessions).addController(player(app.packageName, "Crosstune", "Other", "Band"))
        assertFalse(playback.playSong(song, null))
        // The app it was in is asked; without it, the one that played last. Robolectric doesn't pass
        // what's asked on to the app, so this only checks asking is safe; the phone shows the rest.
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Other", "Band", at = PLAYED_AT - 1))
        shadowOf(sessions).addController(player("com.google.android.apps.youtube.music", "YouTube Music", "Other", "Band"))
        assertTrue(playback.playSong(song, "com.spotify.music"))
        assertTrue(playback.playSong(song, "com.example.gone"))
        assertTrue(playback.playSong(song, null))
        // YouTube Music is given the song's link there; another app, asked by name, has no use for one.
        assertTrue(playback.playSong(song, "com.google.android.apps.youtube.music", "https://music.youtube.com/watch?v=other000000"))
        assertTrue(playback.playSong(song, "com.spotify.music", "https://music.youtube.com/watch?v=other000000"))
    }

    @Test
    fun whatAnAppPlaysIsFollowedWithItsPlaceInItsQueue() {
        val music = MusicService.YOUTUBE_MUSIC.packageName
        val seen = mutableListOf<AppPlays?>()
        collecting += CoroutineScope(Dispatchers.Unconfined).launch { playback.plays(music).collect { seen += it } }
        shadowOf(Looper.getMainLooper()).idle()
        // Without access, nothing.
        assertEquals(listOf<AppPlays?>(null), seen)
        allow()
        val youTubeMusic = player(music, "YouTube Music", "Blinding Lights", "The Weeknd", album = "After Hours")
        val queued = { state: Int, id: Long -> PlaybackState.Builder().setState(state, 0, 1f, PLAYED_AT).setActiveQueueItemId(id).build() }
        shadowOf(youTubeMusic).setPlaybackState(queued(PlaybackState.STATE_PLAYING, 107))
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Other", "Band", album = "Album"))
        shadowOf(sessions).addController(youTubeMusic)
        assertEquals("Blinding Lights" to 107L, seen.last()?.let { it.song.title to it.queueId })
        // Paused it still plays it; stopped, it plays nothing.
        shadowOf(youTubeMusic).setPlaybackState(queued(PlaybackState.STATE_PAUSED, 108))
        assertEquals(108L, runBlocking { playback.plays(music).first() }?.queueId)
        shadowOf(youTubeMusic).setPlaybackState(queued(PlaybackState.STATE_STOPPED, 108))
        assertNull(runBlocking { playback.plays(music).first() })
        // An app with no queue gives no place in it.
        shadowOf(youTubeMusic).setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f, PLAYED_AT).build())
        assertNull(runBlocking { playback.plays(music).first() }?.queueId)
        // Each app's is its own.
        assertEquals("Other", runBlocking { playback.plays("com.spotify.music").first() }?.song?.title)
        assertNull(runBlocking { playback.plays("com.example.gone").first() })
        // Stopped or paused at its song's end, it played it through, as YouTube stops after a list's last song, a little past it.
        val youTube = MusicService.YOUTUBE.packageName
        // Named as YouTube names a song, with no album, as any video: not one that says it's music, but what it was asked to play.
        val atEnd = player(youTube, "YouTube", "Self Control", "Laura Branigan", PlaybackState.STATE_STOPPED, position = 246_479, duration = 246_000)
        shadowOf(sessions).addController(atEnd)
        val ended = { state: Int, position: Long ->
            shadowOf(atEnd).setPlaybackState(PlaybackState.Builder().setState(state, position, 1f, PLAYED_AT).build())
            runBlocking { playback.plays(youTube).first() }?.ended
        }
        assertEquals(AppPlays(MusicMetadata("Self Control", "Laura Branigan"), null, ended = true), runBlocking { playback.plays(youTube).first() })
        assertEquals(true, ended(PlaybackState.STATE_PAUSED, 244_000))
        // Short of it, paused it's still playing it, and stopped it plays nothing.
        assertEquals(false, ended(PlaybackState.STATE_PAUSED, 243_999))
        assertNull(ended(PlaybackState.STATE_STOPPED, 243_999))
        assertEquals(false, ended(PlaybackState.STATE_PLAYING, 246_000))
        assertNull(ended(PlaybackState.STATE_ERROR, 246_000))
        // A video that says it's music is named as its song.
        shadowOf(atEnd).setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Laura Branigan - Self Control (Official Video)").putString(MediaMetadata.METADATA_KEY_ARTIST, "LauraBraniganVEVO").build())
        assertEquals(false, ended(PlaybackState.STATE_PLAYING, 0))
        assertEquals("Self Control", runBlocking { playback.plays(youTube).first() }?.song?.title)
        // Nothing named, or saying nothing of how it plays, nothing to follow.
        shadowOf(atEnd).setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, " ").putString(MediaMetadata.METADATA_KEY_ARTIST, "Laura Branigan").build())
        assertNull(ended(PlaybackState.STATE_PLAYING, 0))
        shadowOf(atEnd).setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Self Control").putString(MediaMetadata.METADATA_KEY_ARTIST, "Laura Branigan").putLong(MediaMetadata.METADATA_KEY_DURATION, 246_000).build())
        shadowOf(atEnd).setPlaybackState(null)
        assertNull(runBlocking { playback.plays(youTube).first() })
    }

    @Test
    fun aListIsAskedOfTheAppOrTheAppWokenForIt() = runBlocking {
        val music = MusicService.YOUTUBE_MUSIC.packageName
        val list = "https://music.youtube.com/watch?v=first000000&list=TLGG"
        // Nothing running, and nothing to wake.
        assertFalse(playback.playList(music, list))
        assertEquals(MusicService.YOUTUBE_MUSIC.packageNames, woken.single())
        // Woken, it's asked.
        wakes = player(music, "YouTube Music", "Other", "Band")
        assertTrue(playback.playList(music, list))
        // Running, it's asked as it is.
        woken.clear()
        allow()
        shadowOf(sessions).addController(player(music, "YouTube Music", "Other", "Band"))
        assertTrue(playback.playList(music, list))
        assertTrue(woken.isEmpty())
    }

    @Test
    fun anAppNotRunningIsWokenInTheBackgroundToBeAsked() = runBlocking {
        val youTubeMusic = MusicService.YOUTUBE_MUSIC.packageName
        val link = "https://music.youtube.com/watch?v=other000000"
        // Even without access, as itself or a build patched from it, whichever is installed.
        wakes = player(youTubeMusic, "YouTube Music", "Other", "Band")
        assertTrue(playback.playSong(song, youTubeMusic, link))
        assertEquals(MusicService.YOUTUBE_MUSIC.packageNames, woken.single())
        assertTrue(playback.playSong(song, "com.example.player"))
        assertEquals(listOf("com.example.player"), woken.last())
        // It can't be: with the song's link there, no other app is asked; without, the one that played last.
        wakes = null
        allow()
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Other", "Band"))
        assertFalse(playback.playSong(song, youTubeMusic, link))
        assertTrue(playback.playSong(song, youTubeMusic))
        // An app running isn't woken; with no app given, none is.
        woken.clear()
        assertTrue(playback.playSong(song, "com.spotify.music", link))
        assertTrue(playback.playSong(song, null))
        assertTrue(woken.isEmpty())
    }

    @Test
    fun anAppNotRunningIsOpenedOnTheSongAndCrosstuneComesBackOverIt() {
        val youTubeMusic = "com.google.android.apps.youtube.music"
        // Opened on the song's link, in that app.
        assertTrue(playback.openSong(youTubeMusic, "https://music.youtube.com/watch?v=other000000"))
        val opened = shadowOf(app).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_VIEW, opened.action)
        assertEquals("https://music.youtube.com/watch?v=other000000", opened.dataString)
        assertEquals(youTubeMusic, opened.`package`)
        // Then Crosstune's brought back over it, as it was.
        playback.comesBack()
        val back = shadowOf(app).nextStartedActivity
        assertEquals(MainActivity::class.java.name, back.component?.className)
        assertTrue(back.flags and android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
    }

    private companion object {
        const val NOW = 500_000L
        const val PLAYED_AT = 400_000L
    }
}
