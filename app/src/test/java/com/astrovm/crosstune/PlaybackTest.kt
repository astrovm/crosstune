package com.astrovm.crosstune

import android.app.ActivityOptions
import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.app.SearchManager
import android.content.ComponentName
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.URI
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.time.Duration
import org.junit.Test
import org.json.JSONObject
import org.robolectric.shadows.ShadowLog
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.PlaybackInfoBuilder
import org.robolectric.shadows.ShadowMediaSessionManager
import org.robolectric.shadows.ShadowSettings

/** Following what a music app plays, through the media sessions Android shows Crosstune once allowed. */
@RunWith(RobolectricTestRunner::class)
class PlaybackTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val sessions = app.getSystemService(MediaSessionManager::class.java)
    /** Each app woken to be asked for a song, as its packages, and the player it wakes as; none, when null. */
    private val woken = mutableListOf<List<String>>()
    private var wakes: MediaController? = null
    private val modes = FakeModes()
    /** Each app's queue, by its package, as Robolectric shows none. */
    private val queues = mutableMapOf<String, List<MediaSession.QueueItem>>()
    private val radioNotices = MutableStateFlow<Set<MediaSession.Token>>(emptySet())
    private val playback = MediaSessionPlayback(app, now = { NOW }, waker = { woken += it; wakes }, startMs = 200, playsOnMs = 200, modes = modes,
        queueOf = { queues[it.packageName] }, clockLog = PracticeClockLog(true), radioNotices = radioNotices)
    private val song = MusicMetadata("Blinding Lights", "The Weeknd")
    private val collecting = mutableListOf<Job>()

    @Before
    fun nothingAskedYet() {
        AppAsked.calls.clear()
    }

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
        val seen = java.util.concurrent.CopyOnWriteArrayList<Heard.Song?>()
        collecting += CoroutineScope(Dispatchers.Unconfined).launch { playback.nowPlayingUpdates().collect { seen += it } }
        shadowOf(Looper.getMainLooper()).idle()
        return seen
    }

    @Test
    fun clockEvidenceKeepsEveryRawCallbackIncludingRepeatsAndMissingState() {
        ShadowLog.setLoggable("CrosstuneClock", android.util.Log.DEBUG)
        allow()
        val fixture = MusicMetadata("Fixture Track", "Fixture Artist")
        val controller = player("com.example.fixture", "Fixture", fixture.title, fixture.artist,
            state = PlaybackState.STATE_PAUSED, position = 29_952, at = 100)
        shadowOf(sessions).addController(controller)
        followed(fixture)
        val report = PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 29_952, 0f, 200)
            .setActions(PlaybackState.ACTION_SEEK_TO).build()
        repeat(2) { shadowOf(controller).executeOnPlaybackStateChanged(report) }
        shadowOf(controller).executeOnPlaybackStateChanged(null)
        shadowOf(Looper.getMainLooper()).idle()
        val reports = ShadowLog.getLogsForTag("CrosstuneClock").map { JSONObject(it.msg) }.filter { it.getString("event") == "media_report" }
        assertEquals(3, reports.size)
        assertEquals(listOf(29_952L, 29_952L), reports.take(2).map { it.getLong("position_ms") })
        assertEquals(listOf(200L, 200L), reports.take(2).map { it.getLong("updated_ms") })
        assertTrue(reports.take(2).all { it.getDouble("speed") == 0.0 && it.getLong("actions") == PlaybackState.ACTION_SEEK_TO })
        assertTrue(reports.all { it.getString("app") == "com.example.fixture" })
        assertEquals(1, reports.map { it.getInt("session") }.distinct().size)
        assertFalse(reports.last().getBoolean("present"))
        assertTrue(reports.all { !it.has("title") && !it.has("artist") && !it.has("extras") })
    }

    @Test
    fun clockEvidenceKeepsUnknownPositionsAndNonFiniteSpeedWithoutErrorText() {
        ShadowLog.setLoggable("CrosstuneClock", android.util.Log.DEBUG)
        allow()
        val fixture = MusicMetadata("Fixture Track", "Fixture Artist")
        val controller = player("com.example.fixture", "Fixture", fixture.title, fixture.artist)
        shadowOf(sessions).addController(controller)
        followed(fixture)
        val error = PlaybackState.Builder().setState(PlaybackState.STATE_ERROR, -1, Float.NaN, 200)
            .setErrorMessage("Fixture private detail").build()
        shadowOf(controller).executeOnPlaybackStateChanged(error)
        shadowOf(Looper.getMainLooper()).idle()
        val row = ShadowLog.getLogsForTag("CrosstuneClock").map { JSONObject(it.msg) }.single { it.getString("event") == "media_report" }
        assertEquals(-1L, row.getLong("position_ms"))
        assertEquals("NaN", row.getString("speed"))
        assertEquals(PlaybackState.STATE_ERROR, row.getInt("state"))
        assertFalse(row.toString().contains("Fixture private detail"))
    }

    @Test
    fun clockEvidenceSeparatesParallelFollowersWithoutDroppingRepeats() {
        ShadowLog.setLoggable("CrosstuneClock", android.util.Log.DEBUG)
        allow()
        val fixture = MusicMetadata("Fixture Track", "Fixture Artist")
        val controller = player("com.example.fixture", "Fixture", fixture.title, fixture.artist)
        shadowOf(sessions).addController(controller)
        followed(fixture)
        followed(fixture)
        val report = PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 29_952, 1f, 200).build()
        repeat(2) { shadowOf(controller).executeOnPlaybackStateChanged(report) }
        shadowOf(Looper.getMainLooper()).idle()
        val raw = ShadowLog.getLogsForTag("CrosstuneClock").map { JSONObject(it.msg) }.filter { it.getString("event") == "media_report" }
        assertEquals(4, raw.size)
        val streams = raw.groupBy { it.getInt("observer") }
        assertEquals(2, streams.size)
        val began = ShadowLog.getLogsForTag("CrosstuneClock").map { JSONObject(it.msg) }
            .filter { it.getString("event") == "follow_begin" }.map { it.getInt("observer") }.toSet()
        assertEquals(streams.keys, began)
        collecting.forEach(Job::cancel)
        shadowOf(Looper.getMainLooper()).idle()
        val ended = ShadowLog.getLogsForTag("CrosstuneClock").map { JSONObject(it.msg) }
            .filter { it.getString("event") == "follow_end" }.map { it.getInt("observer") }.toSet()
        assertEquals(began, ended)
        assertTrue(streams.values.all { it.size == 2 })
    }

    @Test
    fun peekingAtASessionsNumberNeitherNumbersItNorKeepsItLonger() {
        val numbers = SessionNumbers(2)
        val (first, second, third) = List(3) { MediaSession(app, "session$it").sessionToken }
        assertNull(numbers.peek(first))
        assertEquals(0, numbers.of(first))
        assertEquals(1, numbers.of(second))
        assertEquals(0, numbers.peek(first))
        assertEquals(2, numbers.of(third))
        assertNull(numbers.peek(first))
        assertEquals(3, numbers.of(first))
    }

    @Test
    fun aSessionNumberedFromManyThreadsAtOnceGetsOneNumber() {
        val tokens = List(200) { MediaSession(app, "shared$it").sessionToken }
        val numbers = SessionNumbers(tokens.size)
        val threads = 8
        // Each thread waits for all the others before each new session, so they number it together.
        val together = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val got = List(threads) { pool.submit<List<Int>> { tokens.map { together.await(5, TimeUnit.SECONDS); numbers.of(it) } } }
            val seen = got.map { it.get(30, TimeUnit.SECONDS) }
            assertTrue(seen.all { it == seen.first() })
            assertEquals(tokens.size, seen.first().toSet().size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun clockEvidenceDoesNotAllocateControlNumbersForUnrelatedReports() {
        ShadowLog.setLoggable("CrosstuneClock", android.util.Log.DEBUG)
        allow()
        val fixture = MusicMetadata("Fixture Track", "Fixture Artist")
        val selected = player("com.example.selected", "Selected", fixture.title, fixture.artist)
        val unrelated = player("com.example.unrelated", "Unrelated", "Other Track", "Other Artist")
        shadowOf(sessions).addController(selected)
        shadowOf(sessions).addController(unrelated)
        followed(fixture)
        val report = PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 20, 1f, 200).build()
        shadowOf(unrelated).executeOnPlaybackStateChanged(report)
        shadowOf(Looper.getMainLooper()).idle()
        playback.playPause(MusicMetadata("Other Track", "Other Artist"), false, "com.example.unrelated")
        val unrelatedReports = ShadowLog.getLogsForTag("CrosstuneClock").map { JSONObject(it.msg) }
            .filter { it.optString("app") == "com.example.unrelated" }
        assertTrue(unrelatedReports.isNotEmpty())
        assertTrue(unrelatedReports.all { it.isNull("session") })
        val next = MusicMetadata("Next Fixture", "Fixture Artist")
        shadowOf(sessions).addController(player("com.example.next", "Next", next.title, next.artist))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, playback.matching(next).single().session)
    }

    @Test
    fun anotherPlaybackFollowsASessionByTheNumberThisOneGaveIt() {
        allow()
        val other = MusicMetadata("Other Track", "Other Artist")
        shadowOf(sessions).addController(player("com.example.other", "Other", other.title, other.artist))
        shadowOf(sessions).addController(player("com.example.music", "Music", song.title, song.artist))
        // The sleep timer's own playback, which numbered another app's session first, then is handed the player's number.
        val timers = MediaSessionPlayback(app, now = { NOW }, modes = modes, queueOf = { queues[it.packageName] }, clockLog = PracticeClockLog(true), radioNotices = radioNotices)
        timers.matching(other)
        val session = playback.matching(song).single().session
        val seen = mutableListOf<Following?>()
        collecting += CoroutineScope(Dispatchers.Unconfined).launch { timers.follow(song, "com.example.music", session).collect { seen += it } }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("com.example.music"), seen.map { it?.appPackage })
    }

    @Test
    fun clockEvidenceKeepsALateCallbacksOriginalSession() {
        ShadowLog.setLoggable("CrosstuneClock", android.util.Log.DEBUG)
        allow()
        val fixture = MusicMetadata("Fixture Track", "Fixture Artist")
        val first = player("com.example.first", "First", fixture.title, fixture.artist)
        shadowOf(sessions).addController(first)
        val seen = followed(fixture)
        val oldCallbacks = shadowOf(first).callbacks.toList()
        val next = player("com.example.next", "Next", fixture.title, fixture.artist)
        shadowOf(sessions).clearControllers()
        shadowOf(sessions).addController(next)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("com.example.next", seen.last()?.appPackage)
        val late = PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 29_952, 1f, 200).build()
        oldCallbacks.forEach { it.onPlaybackStateChanged(late) }
        shadowOf(Looper.getMainLooper()).idle()
        val raw = ShadowLog.getLogsForTag("CrosstuneClock").map { JSONObject(it.msg) }.filter { it.getString("event") == "media_report" }
        assertEquals(listOf("com.example.first"), raw.map { it.getString("app") })
        assertEquals("com.example.next", seen.last()?.appPackage)
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun clockEvidenceRecordsTheSessionThatGetsEachControl() {
        ShadowLog.setLoggable("CrosstuneClock", android.util.Log.DEBUG)
        allow()
        val fixture = MusicMetadata("Fixture Track", "Fixture Artist")
        val controller = player("com.example.fixture", "Fixture", fixture.title, fixture.artist)
        shadowOf(sessions).addController(controller)
        val target = playback.matching(fixture).single()
        target.source.seekTo(fixture, 43_270, target.app)
        target.source.playPause(fixture, true, target.app)
        target.source.playPause(fixture, false, target.app)
        target.source.pauseApp(checkNotNull(target.app))
        val commands = ShadowLog.getLogsForTag("CrosstuneClock").map { JSONObject(it.msg) }.filter { it.getString("event") == "media_command" }
        assertEquals(listOf("seek", "play", "pause", "pause"), commands.map { it.getString("command") })
        assertTrue(commands.all { it.getInt("session") == target.session && it.getString("app") == target.app })
        assertEquals(43_270L, commands.first().getLong("target_ms"))
        assertEquals(4, asked().size)
    }

    @Test
    fun clockEvidenceIncludesTheCorrectedClockTheFollowerUses() {
        ShadowLog.setLoggable("CrosstuneClock", android.util.Log.DEBUG)
        allow()
        val fixture = MusicMetadata("Fixture Track", "Fixture Artist")
        val controller = player("com.example.fixture", "Fixture", fixture.title, fixture.artist,
            state = PlaybackState.STATE_PAUSED, position = 1_234, at = 100, duration = 56_789)
        shadowOf(sessions).addController(controller)
        val seen = followed(fixture)
        val state = checkNotNull(seen.last())
        val row = ShadowLog.getLogsForTag("CrosstuneClock").map { JSONObject(it.msg) }
            .last { it.getString("event") == "followed_clock" }
        assertEquals(state.session, row.getInt("session"))
        assertEquals(state.appPackage, row.getString("app"))
        assertTrue(row.getBoolean("present"))
        assertEquals(state.clock.positionMs, row.getLong("position_ms"))
        assertEquals(state.clock.atMs, row.getLong("updated_ms"))
        assertEquals(state.clock.positionAt(NOW), row.getLong("projected_ms"))
        assertEquals(state.clock.speed.toString(), row.getString("speed"))
        assertEquals(state.clock.playing, row.getBoolean("playing"))
        assertEquals(state.canSeek, row.getBoolean("can_seek"))
        assertEquals(state.ad, row.getBoolean("ad"))
        assertEquals(state.durationMs, row.getLong("duration_ms"))
        assertEquals(state.session, row.getInt("reported_session"))
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun clockEvidenceRecordsNoPauseForAMismatchedPackageButAllowsAnUnspecifiedOne() {
        ShadowLog.setLoggable("CrosstuneClock", android.util.Log.DEBUG)
        allow()
        val fixture = MusicMetadata("Fixture Track", "Fixture Artist")
        val controller = player("com.example.fixture", "Fixture", fixture.title, fixture.artist)
        shadowOf(sessions).addController(controller)
        val target = playback.matching(fixture).single().source
        target.playPause(fixture, false, "com.example.other")
        assertTrue(asked().isEmpty())
        assertTrue(ShadowLog.getLogsForTag("CrosstuneClock").isEmpty())
        target.playPause(fixture, false, null)
        assertEquals(1, asked().size)
        val row = JSONObject(ShadowLog.getLogsForTag("CrosstuneClock").single().msg)
        assertEquals("media_command", row.getString("event"))
        assertEquals("pause", row.getString("command"))
        assertEquals("com.example.fixture", row.getString("app"))
    }

    @Test
    fun musicAnOlderBuildLeftMutedForACardIsHeardAtTheNextStartOnce() {
        val audio = app.getSystemService(AudioManager::class.java)
        val preferences = app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        preferences.edit().putBoolean(QUIETED_KEY, true).commit()
        MediaSessionPlayback(app, now = { NOW })
        assertFalse(audio.isStreamMute(AudioManager.STREAM_MUSIC))
        assertFalse(preferences.contains(QUIETED_KEY))
        // A mute the user made afterwards is theirs: the next start leaves it.
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        MediaSessionPlayback(app, now = { NOW })
        assertTrue(audio.isStreamMute(AudioManager.STREAM_MUSIC))
    }

    @Test
    fun thePhoneIsUnlockedWithItsScreenOnAndNoLockShowing() {
        assertTrue(playback.unlocked())
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        assertFalse(playback.unlocked())
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        shadowOf(app.getSystemService(PowerManager::class.java)).turnScreenOn(false)
        assertFalse(playback.unlocked())
    }

    @Test
    fun thePhoneIsLocklessWithNoPinPatternOrPasswordAndItsScreenIsWokenAndHeldASecond() {
        assertTrue(playback.lockless())
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(true)
        assertFalse(playback.lockless())
        playback.wakeScreen()
        val lock = checkNotNull(org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock())
        assertEquals("crosstune:next song", shadowOf(lock).tag)
        assertTrue(lock.isHeld)
        // Let go a second later, after which the screen times out as usual.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(999))
        assertTrue(lock.isHeld)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2))
        assertFalse(lock.isHeld)
    }

    @Test
    fun anAdYouTubeMusicPlaysUnderTheNextSongsNameIsntTakenForTheSong() {
        allow()
        sounding()
        val lengths = SteadyLengths(app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE))
        // The song played before, 5:25 long.
        lengths.of("It's Raining Men", "The Weather Girls", 325_474)
        // Free YouTube Music plays a 15 second ad named as the song up next, with all of the song's details but its length.
        val music = player("com.google.android.apps.youtube.music", "YouTube Music", "It's Raining Men", "The Weather Girls", album = "Success",
            position = 5_000, duration = 15_069)
        shadowOf(sessions).addController(music)
        val raining = MusicMetadata("It's Raining Men", "The Weather Girls")
        val seen = followed(raining)
        // It's the song, waiting at its start, as long as the song is, and the ad's length is never kept as one of the song's.
        assertEquals(PlaybackClock(0, PLAYED_AT, playing = false), checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock)
        assertTrue(checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.ad)
        assertTrue(checkNotNull(seen.last()).playingInApp)
        assertEquals(325_474L, checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.durationMs)
        assertEquals(325_474L, checkNotNull(playback.nowPlaying()) { "Expected playback.nowPlaying() to be non-null" }.metadata.lengthMs)
        assertNull(checkNotNull(playback.nowPlaying()) { "Expected playback.nowPlaying() to be non-null" }.offsetMs)
        assertTrue(checkNotNull(playback.nowPlaying()) { "Expected playback.nowPlaying() to be non-null" }.ad)
        assertEquals("com.google.android.apps.youtube.music:duration:325474", playback.recording(raining)?.recordingId)
        assertEquals(listOf(325_474L), lengths.known("It's Raining Men", "The Weather Girls"))
        // A paused ad has the same song clock but is no longer running in the app.
        val paused = PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 5_000, 0f, PLAYED_AT).build()
        shadowOf(music).setPlaybackState(paused)
        assertFalse(checkNotNull(followed(raining).last()).playingInApp)
        // Then the song itself starts over from 0, and plays.
        val song = MediaMetadata.Builder(music.metadata).putLong(MediaMetadata.METADATA_KEY_DURATION, 325_474).build()
        shadowOf(music).setMetadata(song)
        shadowOf(music).setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f, PLAYED_AT + 15_069).setActions(PlaybackState.ACTION_SEEK_TO).build())
        shadowOf(music).executeOnPlaybackStateChanged(music.playbackState)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(PlaybackClock(0, PLAYED_AT + 15_069), checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock)
        assertFalse(checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.ad)
        assertEquals(325_474L, checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.durationMs)
        assertFalse(checkNotNull(playback.nowPlaying()) { "Expected playback.nowPlaying() to be non-null" }.ad)
    }

    @Test
    fun theNowPlayingBarKeepsTheRequestedSongClockThroughAnAdWithoutAVideoId() {
        allow()
        sounding()
        val youtube = MusicService.YOUTUBE_MUSIC.packageName
        val title = "Bohemian Rhapsody (Digital Remaster)"
        val link = "https://music.youtube.com/watch?v=fJ9rUzIMcZQ"
        playback.rememberYouTubeLength("fJ9rUzIMcZQ", 360_000)
        val ad = player(youtube, "YouTube Music", title, "Queen", album = "A Night at the Opera",
            duration = 60_046, position = 6_407)
        shadowOf(sessions).addController(ad)
        val asked = followed(MusicMetadata(title, "Queen", url = link, lengthMs = 360_000))
        assertTrue(checkNotNull(asked.last()).ad)
        // Crosstune comes into view during the ad and follows what the app reports for the bar.
        val heard = checkNotNull(playback.nowPlaying())
        assertTrue("The bar must not see a song playing while the player is waiting for an ad", heard.ad)
        assertEquals(360_000L, heard.metadata.lengthMs)
        assertNull(heard.offsetMs)
        val bar = followed(heard.metadata)
        assertEquals(asked.last()?.clock, bar.last()?.clock)
        val offered = offered()
        val paused = PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 6_407, 1f, PLAYED_AT).build()
        shadowOf(ad).setPlaybackState(paused)
        shadowOf(ad).executeOnPlaybackStateChanged(paused)
        shadowOf(Looper.getMainLooper()).idle()
        val state = checkNotNull(bar.last())
        assertTrue(state.ad)
        assertFalse(state.playingInApp)
        assertEquals(0L, state.clock.positionMs)
        assertEquals(360_000L, state.durationMs)

        // The first report of the song starts a new clock, not where the ad paused.
        val song = MediaMetadata.Builder(ad.metadata).putLong(MediaMetadata.METADATA_KEY_DURATION, 359_491).build()
        shadowOf(ad).setMetadata(song)
        val started = PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 85, 1f, PLAYED_AT).build()
        shadowOf(ad).setPlaybackState(started)
        shadowOf(ad).executeOnMetadataChanged(song)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(checkNotNull(asked.last()).ad)
        assertEquals(85L, asked.last()?.clock?.positionMs)
        assertEquals(PlaybackClock(85, PLAYED_AT), followed(checkNotNull(offered.last()).metadata).last()?.clock)
    }

    @Test
    fun aFollowAlreadyRunningLearnsOfTheAdWithoutANewAndroidReport() {
        allow()
        sounding()
        val song = MusicMetadata("Bohemian Rhapsody", "Queen")
        val controller = player(MusicService.YOUTUBE_MUSIC.packageName, "YouTube Music", song.title, song.artist,
            album = "A Night at the Opera", duration = 60_046, position = 500)
        shadowOf(sessions).addController(controller)
        val heard = offered()
        val bare = followed(song)
        assertFalse(checkNotNull(bare.last()).ad)
        assertFalse(checkNotNull(heard.last()).ad)
        playback.rememberYouTubeLength("fJ9rUzIMcZQ", 360_000)
        val asked = followed(song.copy(url = "https://music.youtube.com/watch?v=fJ9rUzIMcZQ", lengthMs = 360_000))
        assertTrue(checkNotNull(asked.last()).ad)
        assertTrue(checkNotNull(heard.last()).ad)
        assertEquals(360_000L, heard.last()?.metadata?.lengthMs)
        assertTrue(checkNotNull(bare.last()).ad)
        assertEquals(0L, bare.last()?.clock?.positionMs)
    }

    @Test
    fun anExactShortEditStillWinsWithAnAdOfTheSameName() {
        allow()
        sounding()
        val song = MusicMetadata("Bohemian Rhapsody", "Queen")
        val session = player(MusicService.YOUTUBE_MUSIC.packageName, "YouTube Music", song.title, song.artist,
            album = "A Night at the Opera", duration = 60_046, position = 6_407)
        shadowOf(sessions).addController(session)
        playback.rememberYouTubeLength("fJ9rUzIMcZQ", 360_000)
        assertTrue(checkNotNull(followed(song.copy(url = "https://music.youtube.com/watch?v=fJ9rUzIMcZQ")).last()).ad)
        assertTrue(checkNotNull(playback.nowPlaying()).ad)

        val short = song.copy(url = "https://music.youtube.com/watch?v=abcdefghijk", lengthMs = 60_000)
        val state = checkNotNull(followed(short).last())
        assertFalse(state.ad)
        assertEquals(PlaybackClock(6_407, PLAYED_AT), state.clock)
        assertEquals(60_000L, state.durationMs)
    }

    @Test
    fun theConfirmedAdDoesNotChangeTheLengthOfAnotherReportOrSession() {
        allow()
        sounding()
        val youtube = MusicService.YOUTUBE_MUSIC.packageName
        val song = MusicMetadata("Bohemian Rhapsody", "Queen")
        val controller = player(youtube, "YouTube Music", song.title, song.artist, album = "A Night at the Opera", duration = 60_046)
        shadowOf(sessions).addController(controller)
        val report = controller.metadata
        playback.rememberYouTubeLength("fJ9rUzIMcZQ", 360_000)
        val asked = song.copy(url = "https://music.youtube.com/watch?v=fJ9rUzIMcZQ", lengthMs = 360_000)
        assertTrue(checkNotNull(followed(asked).last()).ad)
        assertTrue(checkNotNull(playback.nowPlaying()).ad)

        val edit = MediaMetadata.Builder(report).putString(MediaMetadata.METADATA_KEY_ALBUM, "Live").build()
        shadowOf(controller).setMetadata(edit)
        assertFalse(checkNotNull(playback.nowPlaying()).ad)
        shadowOf(controller).setMetadata(report)
        assertFalse(checkNotNull(playback.nowPlaying()).ad)
        assertTrue(checkNotNull(followed(asked).last()).ad)
        // No exact-link reader should give the replacement session new evidence of its own.
        collecting.forEach(Job::cancel)

        shadowOf(sessions).clearControllers()
        assertNull(playback.nowPlaying())
        val newSession = player(youtube, "YouTube Music", song.title, song.artist, album = "A Night at the Opera", duration = 60_046)
        shadowOf(sessions).addController(newSession)
        assertFalse(checkNotNull(playback.nowPlaying()).ad)
    }

    @Test
    fun anAdIsKnownByTheLengthOfTheSongAskedFor() {
        allow()
        sounding()
        // Never played before, but opened from a link that says how long it is.
        val music = player("com.google.android.apps.youtube.music", "YouTube Music", "Cross My Heart", "Jody Watley", album = "Larger Than Life",
            position = 5_000, duration = 17_066)
        shadowOf(sessions).addController(music)
        val asked = MusicMetadata("Cross My Heart", "Jody Watley", lengthMs = 210_674)
        // The home bar can read the media session before the player follows the requested length.
        playback.nowPlaying()
        assertEquals(emptyList<Long>(), SteadyLengths(app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE))
            .known(asked.title, asked.artist))
        assertEquals(PlaybackClock(0, PLAYED_AT, playing = false), checkNotNull(followed(asked).last()) { "Expected followed(asked).last() to be non-null" }.clock)
        assertEquals(210_674L, playback.recording(asked)?.lengthMs)
    }

    @Test
    fun aShortEditAskedByItsLinkWinsOverTheLongVersionHeardBefore() {
        allow()
        val title = "Bizarre Love Triangle"
        val artist = "New Order"
        SteadyLengths(app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)).of(title, artist, 262_000)
        val short = player(MusicService.YOUTUBE_MUSIC.packageName, "YouTube Music", title, artist, album = "Substance",
            position = 10_000, duration = 124_000)
        shadowOf(sessions).addController(short)
        val edit = MusicMetadata(title, artist, url = "https://music.youtube.com/watch?v=abcdefghijk", lengthMs = 124_000)
        val state = checkNotNull(followed(edit).last())
        assertFalse(state.ad)
        assertEquals(PlaybackClock(10_000, PLAYED_AT), state.clock)
        assertEquals(124_000L, state.durationMs)
        // YouTube Music can round the session length differently from one update to the next.
        shadowOf(short).setMetadata(MediaMetadata.Builder(short.metadata).putLong(MediaMetadata.METADATA_KEY_DURATION, 124_800).build())
        assertEquals(124_000L, playback.recording(edit)?.lengthMs)
    }

    @Test
    fun youtubeMusicLengthsKeptByVideoDoNotLeakBetweenEditsWithTheSameName() {
        allow()
        sounding()
        val yt = MusicService.YOUTUBE_MUSIC.packageName
        val long = player(yt, "YouTube Music", "Edit", "Band", album = "Album", duration = 240_000,
            art = mapOf(MediaMetadata.METADATA_KEY_MEDIA_ID to "longVersion"))
        shadowOf(sessions).addController(long)
        assertEquals(240_000L, playback.nowPlaying()?.metadata?.lengthMs)
        shadowOf(long).setMetadata(MediaMetadata.Builder(long.metadata).putLong(MediaMetadata.METADATA_KEY_DURATION, 30_000).build())
        assertEquals(true, playback.nowPlaying()?.ad)
        assertEquals(240_000L, playback.nowPlaying()?.metadata?.lengthMs)

        val link = "https://music.youtube.com/watch?v=abcdefghijk"
        val short = MediaMetadata.Builder(long.metadata).putString(MediaMetadata.METADATA_KEY_MEDIA_URI, link)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, 124_000).build()
        shadowOf(long).setMetadata(short)
        assertEquals(false, playback.nowPlaying()?.ad)
        val edit = MusicMetadata("Edit", "Band", url = link, lengthMs = 124_000)
        playback.rememberYouTubeLength("abcdefghijk", 124_000)
        val kept = SteadyLengths(app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE))
        assertEquals(124_000L, kept.video("abcdefghijk"))
        assertEquals(240_000L, kept.video("longVersion"))
        assertEquals(124_000L, playback.knownYouTubeLength("abcdefghijk"))
        assertEquals(false, playback.nowPlaying()?.ad)
        assertEquals(124_000L, playback.recording(edit)?.lengthMs)
    }

    @Test
    fun aVideoJustOverTheAdLimitKeepsItsOwnLength() {
        allow()
        sounding()
        val id = "longVersion"
        val lengths = SteadyLengths(app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE))
        lengths.of("Edit", "Band", LONGEST_AD_MS - 1_000)
        val music = player(MusicService.YOUTUBE_MUSIC.packageName, "YouTube Music", "Edit", "Band", album = "Album",
            duration = LONGEST_AD_MS + 400, art = mapOf(MediaMetadata.METADATA_KEY_MEDIA_ID to id))
        shadowOf(sessions).addController(music)
        // Rounding for the title must never make the exact video look shorter than the ad limit.
        val edit = MusicMetadata("Edit", "Band", url = "https://music.youtube.com/watch?v=$id")
        assertEquals(LONGEST_AD_MS + 400, followed(edit).last()?.durationMs)
        assertEquals(LONGEST_AD_MS + 400, playback.nowPlaying()?.metadata?.lengthMs)
        assertEquals(LONGEST_AD_MS + 400, playback.knownYouTubeLength(id))
        shadowOf(music).setMetadata(MediaMetadata.Builder(music.metadata).putLong(MediaMetadata.METADATA_KEY_DURATION, LONGEST_AD_MS - 200).build())
        assertEquals(false, playback.nowPlaying()?.ad)
        assertEquals(LONGEST_AD_MS + 400, followed(edit).last()?.durationMs)
        assertEquals(LONGEST_AD_MS + 400, playback.nowPlaying()?.metadata?.lengthMs)
        shadowOf(music).setMetadata(MediaMetadata.Builder(music.metadata).putLong(MediaMetadata.METADATA_KEY_DURATION, 30_000).build())
        assertEquals(true, playback.nowPlaying()?.ad)
    }

    @Test
    fun aYouTubeMusicVideoWithAnotherKnownIdIsNotTheAskedRecording() {
        allow()
        val official = player(MusicService.YOUTUBE_MUSIC.packageName, "YouTube Music", "Edit", "Band",
            state = PlaybackState.STATE_PAUSED, duration = 240_000,
            art = mapOf(MediaMetadata.METADATA_KEY_MEDIA_ID to "longVersion"))
        shadowOf(sessions).addController(official)
        val audio = MusicMetadata("Edit", "Band", url = "https://music.youtube.com/watch?v=abcdefghijk")
        assertNull(followed(audio).last())
        assertNull(playback.appFor(audio))
        assertEquals(official, playback.appFor(audio.copy(url = "https://music.youtube.com/watch?v=longVersion")))
    }

    @Test
    fun aTrimmedVideoStillTooLongToBeAnAdReplacesItsOldLength() {
        allow()
        val id = "longVersion"
        val music = player(MusicService.YOUTUBE_MUSIC.packageName, "YouTube Music", "Edit", "Band",
            album = "Album", duration = 180_000, art = mapOf(MediaMetadata.METADATA_KEY_MEDIA_ID to id))
        shadowOf(sessions).addController(music)
        playback.rememberYouTubeLength(id, 240_000)
        val edit = MusicMetadata("Edit", "Band", url = "https://music.youtube.com/watch?v=$id", lengthMs = 240_000)
        assertEquals(180_000L, followed(edit).last()?.durationMs)
        assertEquals(180_000L, playback.knownYouTubeLength(id))
        shadowOf(music).setMetadata(MediaMetadata.Builder(music.metadata).putLong(MediaMetadata.METADATA_KEY_DURATION, 30_000).build())
        assertEquals(180_000L, followed(edit).last()?.durationMs)
        assertEquals(true, followed(edit).last()?.ad)
    }

    @Test
    fun theExactVideosAdThresholdsAndItsDurationRoundingAgree() {
        allow()
        val id = "other000000"
        val yt = MusicService.YOUTUBE_MUSIC.packageName
        val video = player(yt, "YouTube Music", song.title, song.artist, album = "Album", position = 100,
            art = mapOf(MediaMetadata.METADATA_KEY_MEDIA_ID to id))
        shadowOf(sessions).addController(video)
        fun follows(said: Long, expected: Long?): Following {
            shadowOf(video).setMetadata(MediaMetadata.Builder(video.metadata).putLong(MediaMetadata.METADATA_KEY_DURATION, said).build())
            return checkNotNull(followed(song.copy(url = "https://music.youtube.com/watch?v=$id", lengthMs = expected)).last())
        }
        assertEquals(500L, follows(500, 0).durationMs)
        assertEquals(124_000L, follows(124_000 + SteadyLengths.SAME_MS, 124_000).durationMs)
        assertFalse(follows(30_000, LONGEST_AD_MS - 1).ad)
        assertFalse(follows(0, LONGEST_AD_MS).ad)
        val runningAd = follows(1, LONGEST_AD_MS)
        assertTrue(runningAd.ad)
        assertTrue(runningAd.adPlaying)
        shadowOf(video).setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 100, 0f, PLAYED_AT).build())
        val pausedAd = follows(1, LONGEST_AD_MS)
        assertTrue(pausedAd.ad)
        assertFalse(pausedAd.adPlaying)
        val rounded = follows(LONGEST_AD_MS - SteadyLengths.SAME_MS, LONGEST_AD_MS)
        assertFalse(rounded.ad)
        assertEquals(LONGEST_AD_MS, rounded.durationMs)
        assertTrue(follows(LONGEST_AD_MS - SteadyLengths.SAME_MS - 1, LONGEST_AD_MS).ad)
        assertTrue(follows(LONGEST_AD_MS - 1, 240_000).ad)
        assertFalse(follows(LONGEST_AD_MS, 240_000).ad)
        assertEquals(LONGEST_AD_MS, follows(LONGEST_AD_MS + SteadyLengths.SAME_MS, null).durationMs)
        assertEquals(LONGEST_AD_MS + SteadyLengths.SAME_MS + 1, follows(LONGEST_AD_MS + SteadyLengths.SAME_MS + 1, null).durationMs)
    }

    @Test
    fun aSessionWithoutAVideoIdCanUseTheCataloguesLengthOrTheNamesHistory() {
        allow()
        val yt = MusicService.YOUTUBE_MUSIC.packageName
        val video = player(yt, "YouTube Music", song.title, song.artist, album = "Album", position = 100, duration = 500)
        shadowOf(sessions).addController(video)
        val lengths = SteadyLengths(app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE))
        lengths.of(song.title, song.artist, LONGEST_AD_MS)
        assertEquals(LONGEST_AD_MS, followed(song).last()?.durationMs)
        val exact = song.copy(url = "https://music.youtube.com/watch?v=other000000")
        assertEquals(214_000L, followed(exact.copy(lengthMs = 214_000)).last()?.durationMs)
        playback.rememberYouTubeLength("other000000", 214_000)
        assertEquals(214_000L, followed(exact).last()?.durationMs)
        assertEquals(true, followed(exact).last()?.ad)
        assertEquals(true, followed(song).last()?.ad)
        assertEquals(214_000L, followed(song).last()?.durationMs)
        // Once the session identifies its cut, the name's length can no longer tell us it is an ad.
        shadowOf(video).setMetadata(MediaMetadata.Builder(video.metadata).putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "longVersion").build())
        assertEquals(false, followed(song).last()?.ad)
        assertEquals(500L, followed(song).last()?.durationMs)
        // A length from the caller is still usable if the caller doesn't identify a different cut.
        assertEquals(true, followed(song.copy(lengthMs = 214_000)).last()?.ad)
    }

    @Test
    fun anAdIsShorterThanTheLongestAdForASongNoShorterThanIt() {
        allow()
        val asked = MusicMetadata("Cross My Heart", "Jody Watley", lengthMs = 210_674)
        fun said(duration: Long, album: String? = "Larger Than Life") = player("com.google.android.apps.youtube.music", "YouTube Music", "Cross My Heart", "Jody Watley", album = album,
            position = 1_000, duration = duration).also { ShadowMediaSessionManager.reset(); shadowOf(sessions).addController(it) }
        // Just before each song, YouTube Music says it's 0 long for a moment: the song, where it says.
        said(0)
        assertEquals(PlaybackClock(1_000, PLAYED_AT), checkNotNull(followed(asked).last()) { "Expected followed(asked).last() to be non-null" }.clock)
        // As long as the longest ad, it's a version of the song, though a longer one played before.
        SteadyLengths(app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)).of("Cross My Heart", "Jody Watley", 210_674)
        said(LONGEST_AD_MS)
        assertEquals(LONGEST_AD_MS, playback.recording(asked.copy(lengthMs = null))?.lengthMs)
        // With no video ID there is no exact cache entry to replace the longer catalogue duration.
        said(LONGEST_AD_MS, album = null)
        val atCutoff = checkNotNull(followed(asked).last())
        assertFalse(atCutoff.ad)
        assertEquals(PlaybackClock(1_000, PLAYED_AT), atCutoff.clock)
        // And a song as long as the longest ad is longer than any.
        said(17_066)
        assertEquals(LONGEST_AD_MS, playback.recording(asked.copy(lengthMs = LONGEST_AD_MS))?.lengthMs)
    }

    @Test
    fun aShortSongIsntTakenForAnAd() {
        allow()
        sounding()
        // Under the longest ad, but no longer length is known for it: it's the song, as long as it says.
        val music = player("com.google.android.apps.youtube.music", "YouTube Music", "Her Majesty", "The Beatles", album = "Abbey Road", duration = 23_000)
        shadowOf(sessions).addController(music)
        val majesty = MusicMetadata("Her Majesty", "The Beatles")
        assertEquals(PlaybackClock(42_000, PLAYED_AT), checkNotNull(followed(majesty).last()) { "Expected followed(majesty).last() to be non-null" }.clock)
        assertEquals(23_000L, checkNotNull(playback.nowPlaying()) { "Expected playback.nowPlaying() to be non-null" }.metadata.lengthMs)
        // Nor a song said shorter in another app, where no ads play under a song's name.
        val lengths = SteadyLengths(app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE))
        lengths.of("Bizarre Love Triangle", "New Order", 262_000)
        val other = player("com.spotify.music", "Spotify", "Bizarre Love Triangle", "New Order", album = "Substance", duration = 120_000)
        shadowOf(sessions).addController(other)
        assertEquals(120_000L, playback.recording(MusicMetadata("Bizarre Love Triangle", "New Order"))?.lengthMs)
    }

    @Test
    fun aSongSaidASecondLongerIsStillTheSameRecording() {
        allow()
        // YouTube Music says the same song is 220 seconds long, then 221.
        val music = player("com.google.android.apps.youtube.music", "YouTube Music", "Blinding Lights", "The Weeknd", album = "After Hours", duration = 220_000)
        shadowOf(sessions).addController(music)
        val seen = followed()
        assertEquals(220_000L, checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.durationMs)
        val longer = MediaMetadata.Builder(music.metadata).putLong(MediaMetadata.METADATA_KEY_DURATION, 221_000).build()
        shadowOf(music).setMetadata(longer)
        shadowOf(music).executeOnMetadataChanged(longer)
        shadowOf(Looper.getMainLooper()).idle()
        // Still followed, as long as it was, and keyed the same; any app saying so the next day too.
        assertEquals(220_000L, checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.durationMs)
        val played = MusicMetadata("Blinding Lights", "The Weeknd")
        assertEquals("com.google.android.apps.youtube.music:duration:220000", playback.recording(played)?.recordingId)
        assertEquals(220_000L, playback.recording(played)?.lengthMs)
        assertEquals(220_000L, SteadyLengths(app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)).of("Blinding Lights", "The Weeknd", 218_500))
        // Further apart, it's another recording, e.g. a longer edit.
        val edit = MediaMetadata.Builder(music.metadata).putLong(MediaMetadata.METADATA_KEY_DURATION, 221_501).build()
        shadowOf(music).setMetadata(edit)
        assertEquals(221_501L, playback.recording(played)?.lengthMs)
    }

    @Test
    fun anotherVersionOfASongPausedInItsAppIsntTheOneAskedFor() {
        allow()
        // The 4:22 album take paused in YouTube Music, which tells its versions apart by length alone.
        val album = player("com.google.android.apps.youtube.music", "YouTube Music", "Bizarre Love Triangle", "New Order", album = "Brotherhood",
            state = PlaybackState.STATE_PAUSED, duration = 262_000)
        shadowOf(sessions).addController(album)
        // The 2:54 single, as Recent has it, isn't that one, so it's played, not carried on.
        val single = MusicMetadata("Bizarre Love Triangle", "New Order", lengthMs = 174_000)
        assertNull(playback.recording(single))
        assertNull(followed(single).last())
        // The album take is, said a second longer elsewhere too, or as far off as one take's length is said to be, and so is a song of no known length.
        assertEquals(262_000L, playback.recording(single.copy(lengthMs = 263_000))?.lengthMs)
        assertEquals(262_000L, playback.recording(single.copy(lengthMs = 262_000 + SAME_RECORDING_LENGTH_MS))?.lengthMs)
        assertEquals(262_000L, playback.recording(single.copy(lengthMs = null))?.lengthMs)
        // A video may open with more than its song, so its length tells nothing.
        val video = player("com.google.android.apps.youtube.music", "YouTube Music", "Bizarre Love Triangle", "New Order", state = PlaybackState.STATE_PAUSED, duration = 262_000)
        shadowOf(sessions).clearControllers()
        shadowOf(sessions).addController(video)
        assertEquals("Bizarre Love Triangle", playback.recording(single)?.title)
    }

    @Test
    fun songLengthsKeptAreTheLatestFiveHundred() {
        val prefs = app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)
        val lengths = SteadyLengths(prefs)
        repeat(501) { lengths.of("Song $it", "Band", 200_000) }
        val again = SteadyLengths(prefs)
        // The 500th newest entry is still there after reopening the stored lengths.
        assertEquals(listOf(200_000L), again.known("Song 1", "Band"))
        // The first is gone, so it's taken as said; the last is kept.
        assertEquals(201_000L, again.of("Song 0", "Band", 201_000))
        assertEquals(200_000L, again.of("Song 500", "Band", 201_000))
        // Kept the wrong way, it starts over.
        prefs.edit().putString("song_lengths", "not json").commit()
        assertEquals(201_000L, SteadyLengths(prefs).of("Song 500", "Band", 201_000))
    }

    @Test
    fun readingTheSameVideoLengthAgainDoesNotWriteTheCache() {
        val prefs = app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)
        val lengths = SteadyLengths(prefs)
        lengths.rememberVideo("longVersion", 214_000)
        lengths.rememberVideo("other000000", 150_000)
        val stored = prefs.all
        lengths.rememberVideo("longVersion", 214_000)
        assertEquals(stored, prefs.all)
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
    fun anEmptyMediaUriLeavesTheTrackIdAvailableToIdentifyTheRecording() {
        allow()
        val id = "1234567890Abcdefghijkl"
        val opened = song.copy(url = "https://open.spotify.com/track/$id", album = "Fixture Album (Instrumentals)")
        val linked = player("com.spotify.music", "Spotify", song.title, song.artist, art = mapOf(
            MediaMetadata.METADATA_KEY_MEDIA_URI to "",
            MediaMetadata.METADATA_KEY_MEDIA_ID to "spotify:track:$id"
        ))
        shadowOf(sessions).addController(linked)
        val recording = requireNotNull(playback.recording(opened))
        assertEquals("spotify:track:$id", recording.url)
        assertEquals(opened.instrumentalAlbum, RecordingKey.asPlayed(opened, recording).instrumentalAlbum)
    }

    @Test
    fun anInstrumentalAlbumNamedByThePlayerLetsTheLyricsLookupRecognizeTheRecording() {
        allow()
        sounding()
        val song = MusicMetadata("Fixture Song", "Fixture Artist")
        val playing = player("com.spotify.music", "Spotify", song.title, song.artist, album = "Fixture Album (Instrumentals)", duration = 200_000)
        shadowOf(sessions).addController(playing)
        val heard = requireNotNull(playback.nowPlaying()).metadata
        val fake = FakeSpotify()
        fake.handler = { request ->
            val answer = if (request.url.host == "lrclib.net") """[
                {"id":1,"trackName":"Fixture Song","artistName":"Fixture Artist","albumName":"Fixture Album","duration":200,
                 "syncedLyrics":"[00:01.000] Studio first\n[00:05.000] Studio second"},
                {"id":2,"trackName":"Fixture Song","artistName":"Fixture Artist","albumName":"Fixture Album (Instrumentals)","duration":200,"instrumental":true},
                {"id":3,"trackName":"Fixture Song","artistName":"Fixture Artist","albumName":"Fixture Album (Instrumentals)","duration":200,"instrumental":true}
            ]""" else "{}"
            FakeSpotify.html(request, answer)
        }
        val recording = RecordingKey.asPlayed(song, heard)
        val found = runBlocking { LyricsFinder(fake.client(), "2.3.3", busyPauseMs = 0).lyricsOf(recording) }
        assertEquals(found.toString(), true, (found as? LyricsFinder.Lyrics.Found)?.markedInstrumental)
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
    @Config(sdk = [31])
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
        assertTrue(checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock.playing)

        shadowOf(youtube).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 50_000, 0f, 0).build())
        shadowOf(Looper.getMainLooper()).idle()
        // Paused, at 0 speed, it's still followed, just not moving; with no time of its own, it's now.
        assertEquals(PlaybackClock(50_000, NOW, 1f, false), checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock)
        assertFalse(checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.canSeek)

        // A video that won't play, e.g. in the background without an account, plays nothing to follow.
        shadowOf(youtube).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_ERROR, 0, 1f, 0).build())
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(seen.last())
        // Back to playing, then gone quiet with no state at all.
        shadowOf(youtube).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 1_000, 1f, PLAYED_AT).build())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1_000L, checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock.positionMs)
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
        assertEquals(PlaybackClock(52_000, PLAYED_AT + 10_000, 1f, false), checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock)
        // Played again from where it said, it goes on from where it really stopped.
        says(PlaybackState.STATE_PLAYING, 51_600, PLAYED_AT + 20_000)
        assertEquals(PlaybackClock(52_000, PLAYED_AT + 20_000, 1f, true), checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock)
        // The same again, e.g. as the app names the song anew, changes nothing.
        shadowOf(music).executeOnMetadataChanged(checkNotNull(music.metadata) { "Expected music.metadata to be non-null" })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(52_000L, checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock.positionMs)

        // Moved elsewhere, it's where the app says.
        says(PlaybackState.STATE_PLAYING, 90_000, PLAYED_AT + 25_000)
        assertEquals(90_000L, checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock.positionMs)
        // Said to pause right where it was, or ahead, or well behind, e.g. moved back as it paused, it's believed.
        says(PlaybackState.STATE_PAUSED, 95_000, PLAYED_AT + 30_000)
        assertEquals(95_000L, checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock.positionMs)
        says(PlaybackState.STATE_PLAYING, 95_000, PLAYED_AT + 40_000)
        says(PlaybackState.STATE_PAUSED, 100_500, PLAYED_AT + 45_000)
        assertEquals(100_500L, checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock.positionMs)
        says(PlaybackState.STATE_PLAYING, 100_500, PLAYED_AT + 50_000)
        says(PlaybackState.STATE_PAUSED, 105_500 - STALE_CLOCK_MS - 1, PLAYED_AT + 55_000)
        assertEquals(105_500 - STALE_CLOCK_MS - 1, checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock.positionMs)
        // Up to a second behind is still taken as where it stopped.
        says(PlaybackState.STATE_PLAYING, 110_000, PLAYED_AT + 60_000)
        says(PlaybackState.STATE_PAUSED, 115_000 - STALE_CLOCK_MS, PLAYED_AT + 65_000)
        assertEquals(115_000L, checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock.positionMs)
        // Moved while paused, it's where it was moved to.
        says(PlaybackState.STATE_PAUSED, 30_000, PLAYED_AT + 70_000)
        assertEquals(30_000L, checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock.positionMs)
        // Played again from a moment behind where it stopped, up to two seconds, it plays on from where it stopped.
        says(PlaybackState.STATE_PLAYING, 30_000 - STALE_CLOCK_MS, PLAYED_AT + 75_000)
        assertEquals(PlaybackClock(30_000, PLAYED_AT + 75_000, 1f, true), checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock)
        says(PlaybackState.STATE_PAUSED, 40_000, PLAYED_AT + 85_000)
        says(PlaybackState.STATE_PLAYING, 39_999, PLAYED_AT + 90_000)
        assertEquals(40_000L, checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock.positionMs)
        // Further behind, or right there, or ahead, it's where the app says.
        says(PlaybackState.STATE_PAUSED, 50_000, PLAYED_AT + 95_000)
        says(PlaybackState.STATE_PLAYING, 50_000 - STALE_CLOCK_MS - 1, PLAYED_AT + 100_000)
        assertEquals(50_000 - STALE_CLOCK_MS - 1, checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock.positionMs)
        says(PlaybackState.STATE_PAUSED, 60_000, PLAYED_AT + 105_000)
        says(PlaybackState.STATE_PLAYING, 60_000, PLAYED_AT + 110_000)
        assertEquals(60_000L, checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock.positionMs)
        says(PlaybackState.STATE_PAUSED, 70_000, PLAYED_AT + 115_000)
        says(PlaybackState.STATE_PLAYING, 70_500, PLAYED_AT + 120_000)
        assertEquals(70_500L, checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock.positionMs)
    }

    @Test
    fun aClockSaidAMomentBehindWhileItPlaysOnIsStale() {
        allow()
        // YouTube Music starts the song at PLAYED_AT, then says it's at the start again 700 ms later.
        val music = player("com.google.android.apps.youtube.music", "YouTube Music", "Blinding Lights", "The Weeknd", position = 0)
        shadowOf(sessions).addController(music)
        val seen = followed()
        fun says(position: Long, at: Long, speed: Float = 1f) {
            shadowOf(music).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, position, speed, at).setActions(PlaybackState.ACTION_SEEK_TO).build())
            shadowOf(Looper.getMainLooper()).idle()
        }
        val started = PlaybackClock(0, PLAYED_AT, 1f, true)
        assertEquals(started, checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock)

        says(0, PLAYED_AT + 700)
        assertEquals(started, checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock)
        says(899, PLAYED_AT + 900)
        assertEquals(started, checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock)
        // Up to a second behind, it's still where it was playing on to.
        says(1_000, PLAYED_AT + 1_000 + STALE_CLOCK_MS)
        assertEquals(started, checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock)
        // Right where it was playing on to, or ahead, it's believed.
        says(2_000, PLAYED_AT + 2_000)
        assertEquals(PlaybackClock(2_000, PLAYED_AT + 2_000, 1f, true), checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock)
        says(3_200, PLAYED_AT + 3_000)
        assertEquals(PlaybackClock(3_200, PLAYED_AT + 3_000, 1f, true), checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock)
        // Further back is a move to an earlier part.
        says(4_200 - STALE_CLOCK_MS - 1, PLAYED_AT + 4_000)
        assertEquals(PlaybackClock(4_200 - STALE_CLOCK_MS - 1, PLAYED_AT + 4_000, 1f, true), checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock)
        // Played at another speed, it's believed even a moment back.
        says(5_000, PLAYED_AT + 6_000, 1.5f)
        assertEquals(PlaybackClock(5_000, PLAYED_AT + 6_000, 1.5f, true), checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock)

        // Moved a moment back by Crosstune, e.g. to the start of the line being sung, it's where it was moved to.
        playback.seekTo(song, 6_000)
        says(6_000, PLAYED_AT + 7_000, 1.5f)
        assertEquals(PlaybackClock(6_000, PLAYED_AT + 7_000, 1.5f, true), checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock)
        // And from then on, a moment back is stale again.
        says(6_000, PLAYED_AT + 7_500, 1.5f)
        assertEquals(PlaybackClock(6_000, PLAYED_AT + 7_000, 1.5f, true), checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock)
    }

    @Test
    fun aClockPickedUpWhereTheAppFellBehindIsAsFarBehindAsItFell() {
        allow()
        val music = player("com.google.android.apps.youtube.music", "YouTube Music", "Blinding Lights", "The Weeknd", position = 0)
        shadowOf(sessions).addController(music)
        fun says(position: Long, at: Long, state: Int = PlaybackState.STATE_PLAYING, speed: Float = 1f) {
            shadowOf(music).setPlaybackState(PlaybackState.Builder().setState(state, position, speed, at).setActions(PlaybackState.ACTION_SEEK_TO).build())
            shadowOf(music).executeOnPlaybackStateChanged(music.playbackState)
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun pickedUp(song: MusicMetadata = this.song): PlaybackClock? {
            var clock: PlaybackClock? = null
            CoroutineScope(Dispatchers.Unconfined).launch { playback.follow(song).collect { clock = it?.clock } }.also { shadowOf(Looper.getMainLooper()).idle() }.cancel()
            return clock
        }
        // Before Crosstune has seen it fall behind, it's where the app says.
        assertEquals(PlaybackClock(0, PLAYED_AT, 1f, true), pickedUp())

        // Started from a link: it says so again 100 ms later, just how long saying it takes, then 800 ms on, which it goes on from.
        val seen = followed()
        says(0, PLAYED_AT + 100)
        says(0, PLAYED_AT + 800)
        assertEquals(PlaybackClock(0, PLAYED_AT, 1f, true), checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock)
        collecting.forEach(Job::cancel)
        // Another screen picks it up as far behind, even said just now.
        says(NOW - 5 - PLAYED_AT - 800, NOW - 5)
        assertEquals(PlaybackClock(NOW - 5 - PLAYED_AT, NOW - 5, 1f, true), pickedUp())
        // Within a moment of it, it's the same clock; further, it isn't.
        says(NOW - PLAYED_AT - 800 + SAME_CLOCK_MS, NOW)
        assertEquals(PlaybackClock(NOW - PLAYED_AT + SAME_CLOCK_MS, NOW, 1f, true), pickedUp())
        says(NOW - PLAYED_AT - 800 - SAME_CLOCK_MS - 1, NOW)
        assertEquals(PlaybackClock(NOW - PLAYED_AT - 800 - SAME_CLOCK_MS - 1, NOW, 1f, true), pickedUp())
        // Paused, or at another speed, it's where the app says.
        says(10_000, NOW - PLAYED_AT - 800 + 10_000 / 2, speed = 2f)
        says(10_000, NOW, PlaybackState.STATE_PAUSED)
        assertEquals(PlaybackClock(10_000, NOW, 1f, false), pickedUp())
        says(NOW - PLAYED_AT - 800, NOW, speed = 2f)
        assertEquals(PlaybackClock(NOW - PLAYED_AT - 800, NOW, 2f, true), pickedUp())

        // Played from the start again in the app, it's right, so it's where the app says.
        says(0, NOW - 30_000)
        assertEquals(PlaybackClock(30_000, NOW, 1f, true), pickedUp().let { checkNotNull(it) { "Expected it to be non-null" }.copy(positionMs = it.positionAt(NOW), atMs = NOW) })

        // Another app it didn't happen in is where it says.
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Save Your Tears", "The Weeknd", position = NOW - 5 - PLAYED_AT - 800, at = NOW - 5))
        assertEquals(PlaybackClock(NOW - 5 - PLAYED_AT - 800, NOW - 5, 1f, true), pickedUp(MusicMetadata("Save Your Tears", "The Weeknd")))
    }

    @Test
    fun aClockFallenBehindIsMovedOnByHowFarItFellAtAnySpeed() {
        allow()
        val music = player("com.google.android.apps.youtube.music", "YouTube Music", "Blinding Lights", "The Weeknd", position = 0)
        shadowOf(sessions).addController(music)
        fun says(position: Long, at: Long, speed: Float) {
            shadowOf(music).setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, position, speed, at).setActions(PlaybackState.ACTION_SEEK_TO).build())
            shadowOf(music).executeOnPlaybackStateChanged(music.playbackState)
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun pickedUp(): PlaybackClock? {
            var clock: PlaybackClock? = null
            CoroutineScope(Dispatchers.Unconfined).launch { playback.follow(song).collect { clock = it?.clock } }.also { shadowOf(Looper.getMainLooper()).idle() }.cancel()
            return clock
        }
        // At twice the speed, said again from the start 400 ms on: 800 ms of the song behind.
        says(0, PLAYED_AT, 2f)
        val seen = followed()
        says(0, PLAYED_AT + 400, 2f)
        assertEquals(PlaybackClock(0, PLAYED_AT, 2f, true), checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.clock)
        collecting.forEach(Job::cancel)
        // Picked up later, it's those 800 ms ahead, where the first said it would be.
        says(2 * (NOW - PLAYED_AT - 400), NOW, 2f)
        assertEquals(PlaybackClock(2 * (NOW - PLAYED_AT), NOW, 2f, true), pickedUp())
    }

    @Test
    fun aClockSaidJustLongEnoughBehindToBeLateIsLearnt() {
        allow()
        val music = player("com.google.android.apps.youtube.music", "YouTube Music", "Blinding Lights", "The Weeknd", position = 0)
        shadowOf(sessions).addController(music)
        fun says(position: Long, at: Long) {
            shadowOf(music).setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, position, 1f, at).setActions(PlaybackState.ACTION_SEEK_TO).build())
            shadowOf(music).executeOnPlaybackStateChanged(music.playbackState)
            shadowOf(Looper.getMainLooper()).idle()
        }
        followed()
        says(0, PLAYED_AT + SAYING_MS)
        collecting.forEach(Job::cancel)
        var clock: PlaybackClock? = null
        CoroutineScope(Dispatchers.Unconfined).launch { playback.follow(song).collect { clock = it?.clock } }.also { shadowOf(Looper.getMainLooper()).idle() }.cancel()
        assertEquals(PlaybackClock(SAYING_MS, PLAYED_AT + SAYING_MS, 1f, true), clock)
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

    /** What a fresh follow of [asked] sees when [apps], as package to its song's title, play by [RecordingTagPairs.ARTIST]. */
    private fun followsAmong(asked: String, vararg apps: Pair<String, String>): Following? {
        ShadowMediaSessionManager.reset()
        val fresh = MediaSessionPlayback(app, now = { NOW }, waker = { woken += it; wakes }, startMs = 200, modes = modes, queueOf = { queues[it.packageName] }, clockLog = PracticeClockLog(true))
        apps.forEach { (packageName, title) -> shadowOf(sessions).addController(player(packageName, packageName, title, RecordingTagPairs.ARTIST)) }
        var seen: Following? = null
        val job = CoroutineScope(Dispatchers.Unconfined).launch { fresh.follow(MusicMetadata(asked, RecordingTagPairs.ARTIST)).collect { seen = it } }
        shadowOf(Looper.getMainLooper()).idle()
        job.cancel()
        return seen
    }

    @Test
    fun onlyTheExactTaggedNameIsFollowedNeverAnotherBaseNameWithTheSameTag() {
        allow()
        sounding()
        val wrong = mutableListOf<String>()
        for ((asked, other) in RecordingTagPairs.wrongPicks) {
            followsAmong(asked, "com.fake.other" to other)?.let { wrong += "$asked followed $other in ${it.app}" }
            // Both playing: the one named exactly as asked is followed.
            val both = followsAmong(asked, "com.fake.other" to other, "com.fake.exact" to asked)?.app
            if (both != "com.fake.exact") wrong += "both: $asked followed $both, wanted its own"
        }
        for (name in RecordingTagPairs.exactNames) {
            val app = followsAmong(name, "com.fake.exact" to name)?.app
            if (app != "com.fake.exact") wrong += "exact: $name followed $app"
        }
        for ((asked, playing) in RecordingTagPairs.same) {
            val app = followsAmong(asked, "com.fake.exact" to playing)?.app
            if (app != "com.fake.exact") wrong += "same: $asked followed $app, wanted $playing"
        }
        assertEquals("${wrong.size} wrong follows", emptyList<String>(), wrong)
    }

    @Test
    fun prestoMusicsSongIsNamedAsOtherServicesNameItAndFollowed() {
        allow()
        sounding()
        // As Presto names it on a phone: who wrote it first, and every musician with what they play.
        val seen = followed(MusicMetadata("So What", "Miles Davis"))
        shadowOf(sessions).addController(
            player("com.prestomusic.app", "Presto Music", "Davis, M: So What", "Cannonball Adderley (alto saxophone), Bill Evans (piano), Miles Davis (trumpet)")
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Presto Music", seen.last()?.app)
        assertEquals(MusicMetadata("So What", "Miles Davis"), playback.nowPlaying()?.metadata?.let { MusicMetadata(it.title, it.artist) })
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
    fun aVideoYouTubeMusicPatchedWithMorphePlaysIsNamedByTheAppThoughItsNamesSayLittle() {
        allow()
        sounding()
        // As seen on a phone: an upload with no album, titled oddly, under a channel that isn't the artist.
        val morphe = "app.morphe.android.apps.youtube.music"
        shadowOf(sessions).addController(player(morphe, "YouTube Music", "Police, The   Every Little Thing She Does Is Magic Audio Flac", "keyloginhot"))
        val playing = checkNotNull(playback.nowPlaying())
        assertEquals(morphe, playing.app)
        assertTrue(SongNames.wordsMatch(playing.metadata.title, "Every Little Thing She Does Is Magic"))
        // A video's place isn't its song's, so it's heard for that.
        assertNull(playing.clock)
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
        val other = checkNotNull(playback.nowPlaying()) { "Expected playback.nowPlaying() to be non-null" }.metadata.copy(recordingId = "com.google.android.apps.youtube.music:video-two")
        assertNull(followed(other).last())
        val uriId = player("com.spotify.music", "Spotify", "Demo", "Band", art = mapOf(MediaMetadata.METADATA_KEY_MEDIA_ID to "spotify:track:4Z8W4fKeB5YxbusRsdQVPb"))
        shadowOf(sessions).clearControllers()
        shadowOf(sessions).addController(uriId)
        assertEquals("spotify:track:4Z8W4fKeB5YxbusRsdQVPb", playback.nowPlaying()?.metadata?.url)
        // An id that isn't a link, or none, gives no link.
        for (art in listOf(mapOf(MediaMetadata.METADATA_KEY_MEDIA_ID to "video-one"), emptyMap())) {
            shadowOf(sessions).clearControllers()
            shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Demo", "Band", art = art))
            assertNull(playback.nowPlaying()?.metadata?.url)
        }
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
    fun aLoneStationWithNoPlaybackStateNeedsSoundAndItsOwnMediaNotice() {
        allow()
        val station = player("com.streema.simpleradio", "Simple Radio", "Fixture FM", null)
        shadowOf(station).setPlaybackState(null)
        shadowOf(station).setPlaybackInfo(PlaybackInfoBuilder.newBuilder().setVolumeType(MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL).build())
        shadowOf(sessions).addController(station)
        radioNotices.value = setOf(station.sessionToken)
        assertNull(playback.whatPlays())

        sounding()
        shadowOf(sessions).addController(player("com.brave.browser", "Brave", "Old Fixture", "Old Artist", at = NOW - LEFT_BEHIND_MS - 1, position = -1))
        assertEquals(Unnamed("com.streema.simpleradio", "Simple Radio", "Fixture FM", reportsState = false), playback.playingUnnamed().sessionless())
        assertNull(playback.nowPlaying())

        sounding(false)
        assertNull(playback.whatPlays())
    }

    @Test
    fun theStationOnlyCountsTheSoundWhenNoOtherSessionCouldBeItsSource() {
        allow()
        sounding()
        val station = player(RadioNotice.PACKAGE, "Simple Radio", "Blue 100.7 FM", null)
        shadowOf(station).setPlaybackState(null)
        shadowOf(station).setPlaybackInfo(PlaybackInfoBuilder.newBuilder().setVolumeType(MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL).build())
        shadowOf(sessions).addController(station)
        radioNotices.value = setOf(station.sessionToken)
        // An app that says it stopped is not where the sound comes from, so the station still shows.
        val stopped = player("com.spotify.music", "Spotify", "Old Song", "Old Artist", state = PlaybackState.STATE_PAUSED)
        shadowOf(sessions).addController(stopped)
        assertEquals("Blue 100.7 FM", playback.playingUnnamed()?.name)

        // A second session with no state could be the one making the sound, so it is no one's.
        val other = player("com.example.player", "Player", song.title, song.artist, album = "Album")
        val reported = checkNotNull(other.playbackState)
        shadowOf(other).setPlaybackState(null)
        shadowOf(sessions).addController(other)
        assertNull(playback.whatPlays())

        // An app that reports playing wins, as it always did.
        shadowOf(other).setPlaybackState(reported)
        assertEquals(song.title, (playback.whatPlays() as WhatPlays.Named).song.metadata.title)
    }

    @Test
    fun aStoppedRadioSessionDoesNotBorrowAnotherAppsSound() {
        allow()
        sounding()
        val station = player(RadioNotice.PACKAGE, "Simple Radio", "Blue 100.7 FM", null)
        val old = player(RadioNotice.PACKAGE, "Simple Radio", "Other FM", null)
        shadowOf(station).setPlaybackState(null)
        shadowOf(station).setPlaybackInfo(PlaybackInfoBuilder.newBuilder().setVolumeType(MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL).build())
        shadowOf(sessions).addController(station)
        val shown = mutableListOf<String?>()
        val watch = CoroutineScope(Dispatchers.Unconfined).launch { playback.unnamedUpdates().collect { shown += it?.name } }
        collecting += watch
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(playback.whatPlays())
        radioNotices.value = setOf(old.sessionToken)
        assertNull(playback.whatPlays())
        radioNotices.value = setOf(station.sessionToken)
        assertEquals("Blue 100.7 FM", playback.playingUnnamed()?.name)
        shadowOf(Looper.getMainLooper()).idle()

        // The session and global audio stay active. Only the notification for Blue went away.
        radioNotices.value = emptySet()
        assertNull(playback.whatPlays())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(null, "Blue 100.7 FM", null), shown)
        watch.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, radioNotices.subscriptionCount.value)
    }

    @Test
    fun radioNoticeKeepsOnlyOngoingForegroundMediaSessionTokensFromSimpleRadio() {
        val station = player(RadioNotice.PACKAGE, "Simple Radio", "Blue 100.7 FM", null)
        val listener = org.robolectric.Robolectric.buildService(NowPlayingListener::class.java).create().get()
        fun notice(pkg: String, ongoing: Boolean = true, service: Boolean = true, media: Boolean = true, token: Boolean = true): StatusBarNotification {
            val notification = Notification().apply {
                category = if (media) Notification.CATEGORY_TRANSPORT else Notification.CATEGORY_MESSAGE
                if (ongoing) flags = flags or Notification.FLAG_ONGOING_EVENT
                if (service) flags = flags or Notification.FLAG_FOREGROUND_SERVICE
                if (token) extras.putParcelable(Notification.EXTRA_MEDIA_SESSION, station.sessionToken)
            }
            listener.cancelAllNotifications()
            shadowOf(listener).addActiveNotification(pkg, 1, notification)
            return listener.activeNotifications.single()
        }
        try {
            RadioNotice.refresh(arrayOf(notice("other.app"), notice(RadioNotice.PACKAGE, ongoing = false),
                notice(RadioNotice.PACKAGE, service = false), notice(RadioNotice.PACKAGE, media = false), notice(RadioNotice.PACKAGE, token = false)))
            assertEquals(emptySet<MediaSession.Token>(), RadioNotice.tokens.value)
            RadioNotice.refresh(arrayOf(notice(RadioNotice.PACKAGE)))
            assertEquals(setOf(station.sessionToken), RadioNotice.tokens.value)
        } finally {
            RadioNotice.refresh(emptyArray())
        }
        assertEquals(emptySet<MediaSession.Token>(), RadioNotice.tokens.value)
    }

    @Test
    fun aRadioNotificationChangesTheStationUntilItIsRemovedOrAccessIsLost() {
        val station = player(RadioNotice.PACKAGE, "Simple Radio", "Blue 100.7 FM", null)
        val service = org.robolectric.Robolectric.buildService(NowPlayingListener::class.java).create().get()
        val notice = Notification().apply {
            category = Notification.CATEGORY_TRANSPORT
            flags = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE
            extras.putParcelable(Notification.EXTRA_MEDIA_SESSION, station.sessionToken)
        }
        val shadow = shadowOf(service)
        val key = shadow.addActiveNotification(RadioNotice.PACKAGE, 1, notice)
        val posted = service.activeNotifications.single()
        try {
            service.onListenerConnected()
            assertEquals(setOf(station.sessionToken), RadioNotice.tokens.value)
            service.cancelNotification(key)
            service.onNotificationRemoved(posted)
            assertEquals(emptySet<MediaSession.Token>(), RadioNotice.tokens.value)
            shadow.addActiveNotification(RadioNotice.PACKAGE, 1, notice)
            service.onNotificationPosted(service.activeNotifications.single())
            assertEquals(setOf(station.sessionToken), RadioNotice.tokens.value)
        } finally {
            service.onListenerDisconnected()
        }
        assertEquals(emptySet<MediaSession.Token>(), RadioNotice.tokens.value)
    }

    @Test
    fun radioWithoutPlaybackStateDoesNotOfferASongOrTransport() {
        allow()
        sounding()
        val station = player(RadioNotice.PACKAGE, "Simple Radio", song.title, song.artist)
        shadowOf(station).setPlaybackState(null)
        shadowOf(station).setPlaybackInfo(PlaybackInfoBuilder.newBuilder().setVolumeType(MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL).build())
        shadowOf(sessions).addController(station)
        radioNotices.value = setOf(station.sessionToken)
        assertNull(playback.nowPlaying())
        assertEquals(false, playback.playingUnnamed()?.reportsState)
        assertNull(playback.appFor(song, app = RadioNotice.PACKAGE))
        assertNull(playback.appFor(song))
        assertTrue(playback.matching(song).isEmpty())
        val paused = player("com.example.music", "Music", song.title, song.artist, state = PlaybackState.STATE_PAUSED)
        shadowOf(sessions).addController(paused)
        assertEquals(paused, playback.appFor(song))
        assertNull(playback.nowPlaying())
    }

    @Test
    fun aNoticeCannotMakeAnUnknownOrRemoteRouteLocal() {
        allow()
        sounding()
        val station = player(RadioNotice.PACKAGE, "Simple Radio", "Blue 100.7 FM", null)
        shadowOf(station).setPlaybackState(null)
        shadowOf(sessions).addController(station)
        radioNotices.value = setOf(station.sessionToken)
        assertNull(playback.whatPlays())
        shadowOf(station).setPlaybackInfo(PlaybackInfoBuilder.newBuilder().setVolumeType(MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE).build())
        assertNull(playback.whatPlays())
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun playByPackageDoesNotAskASessionWithoutPlaybackState() {
        val radio = player(RadioNotice.PACKAGE, "Simple Radio", "Blue 100.7 FM", null)
        val paused = checkNotNull(radio.playbackState)
        shadowOf(radio).setPlaybackState(null)
        shadowOf(sessions).addController(radio)
        playback.playApp(RadioNotice.PACKAGE)
        assertEquals(emptyList<String>(), asked())

        shadowOf(radio).setPlaybackState(paused)
        playback.playApp(RadioNotice.PACKAGE)
        assertEquals(listOf("play"), asked())
    }

    @Test
    fun aRouteChangeAloneUpdatesTheNotifiedRadioStation() {
        allow()
        sounding()
        val station = player(RadioNotice.PACKAGE, "Simple Radio", "Blue 100.7 FM", null)
        val local = PlaybackInfoBuilder.newBuilder().setVolumeType(MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL).build()
        val remote = PlaybackInfoBuilder.newBuilder().setVolumeType(MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE).build()
        shadowOf(station).setPlaybackState(null)
        shadowOf(station).setPlaybackInfo(local)
        shadowOf(sessions).addController(station)
        radioNotices.value = setOf(station.sessionToken)
        val shown = mutableListOf<String?>()
        collecting += CoroutineScope(Dispatchers.Unconfined).launch { playback.unnamedUpdates().collect { shown += it?.name } }
        shadowOf(Looper.getMainLooper()).idle()
        for (route in listOf(remote, local)) {
            shadowOf(station).setPlaybackInfo(route)
            shadowOf(station).callbacks.toList().forEach { it.onAudioInfoChanged(route) }
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(listOf("Blue 100.7 FM", null, "Blue 100.7 FM"), shown)
    }

    @Test
    fun aPlayerThatReportsItsStateLateIsFollowedWithoutAnotherMetadataEvent() {
        allow()
        val controller = player("com.example.music", "Music", song.title, song.artist)
        shadowOf(controller).setPlaybackState(null)
        shadowOf(sessions).addController(controller)
        val updates = followed()
        assertNull(updates.last())
        val callbacks = shadowOf(controller).callbacks.toList()
        assertTrue(callbacks.isNotEmpty())
        val started = PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 500L, 1f, PLAYED_AT).build()
        shadowOf(controller).setPlaybackState(started)
        // Robolectric changes the getter but does not deliver the platform callback.
        callbacks.forEach { it.onPlaybackStateChanged(started) }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("com.example.music", updates.last()?.appPackage)
        assertEquals(true, updates.last()?.clock?.playing)
    }

    @Test
    fun audioAloneDoesNotPickBetweenUnknownSessionsOrHideAReportedSong() {
        allow()
        sounding()
        val station = player("com.streema.simpleradio", "Simple Radio", "Fixture FM", null)
        val other = player("com.example.radio", "Other Radio", "Other FM", null)
        for (controller in listOf(station, other)) {
            shadowOf(controller).setPlaybackState(null)
            shadowOf(sessions).addController(controller)
        }
        assertNull(playback.whatPlays())

        shadowOf(sessions).addController(player("com.example.music", "Music", song.title, song.artist, album = "Fixture Album"))
        assertEquals(song.copy(album = "Fixture Album"), playback.nowPlaying()?.metadata)
    }

    @Test
    fun localSoundDoesNotPromoteAStatelessRemoteOrAPlayerWithoutATitle() {
        allow()
        sounding()
        val remote = player("com.example.remote", "Remote", "Remote FM", null)
        shadowOf(remote).setPlaybackState(null)
        shadowOf(remote).setPlaybackInfo(PlaybackInfoBuilder.newBuilder().setVolumeType(MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE).build())
        shadowOf(sessions).addController(remote)
        assertNull(playback.whatPlays())

        val local = player("com.example.local", "Local", " ", null)
        shadowOf(local).setPlaybackState(null)
        shadowOf(sessions).addController(local)
        assertNull(playback.whatPlays())
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
        assertEquals(song.copy(album = "Live"), playback.nowPlaying()?.metadata)
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
        assertEquals(song.copy(album = "After Hours"), playback.nowPlaying()?.metadata)
    }

    @Test
    fun aSongNamedByItsPlayerKeepsItsPlaybackRate() {
        allow()
        sounding()
        val music = player("com.music", "Music", "Blinding Lights", "The Weeknd", album = "After Hours", at = NOW)
        shadowOf(music).setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 42_000, 1.05f, NOW).build())
        shadowOf(sessions).addController(music)
        assertEquals(1.05f, playback.nowPlaying()?.clock?.speed)
    }

    @Test
    fun aSongAComputerPlaysIsFollowedWhenThePhonePlaysNothing() {
        allow()
        fun computer(title: String, artist: String, album: String? = null) = player("org.kde.kdeconnect_tp", "KDE Connect", title, artist, album = album, at = NOW).also {
            shadowOf(it).setPlaybackInfo(PlaybackInfoBuilder.newBuilder().setVolumeType(MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE).build())
        }
        // A music player on the computer, shown through KDE Connect (#609): the phone itself is quiet.
        val strawberry = computer("Blinding Lights", "The Weeknd", album = "After Hours")
        shadowOf(sessions).addController(strawberry)
        assertEquals(song.copy(album = "After Hours"), playback.nowPlaying()?.metadata)
        assertEquals(42_000L, playback.nowPlaying()?.clock?.positionMs)
        assertEquals(song.title, playback.recording(song)?.title)
        // Followed, so its controls reach it.
        assertEquals(strawberry.sessionToken, playback.appFor(song)?.sessionToken)
        assertEquals(false, followed().last()?.video)

        // What the phone itself plays comes first, though the computer said where it is later.
        sounding()
        shadowOf(sessions).addController(player("com.music", "Music", "Save Your Tears", "The Weeknd", album = "After Hours", at = NOW - 60_000))
        assertEquals("Save Your Tears", playback.nowPlaying()?.metadata?.title)

        // A browser's music video on the computer, saying it's one, is its song, followed as a video.
        ShadowMediaSessionManager.reset()
        shadowOf(sessions).addController(computer("The Weeknd - Blinding Lights (Official Video)", "TheWeekndVEVO"))
        assertEquals(song, playback.nowPlaying()?.metadata)
        assertNull(playback.nowPlaying()?.clock)
        // Spotify's page in a browser names it by the page's title.
        ShadowMediaSessionManager.reset()
        shadowOf(sessions).addController(computer("Blinding Lights - song and lyrics by The Weeknd | Spotify", "Spotify"))
        assertEquals(song, playback.nowPlaying()?.metadata)
        // A computer's video that only puts a dash in its name isn't taken for a song.
        ShadowMediaSessionManager.reset()
        shadowOf(sessions).addController(computer("Fake Talk Show - Episode 12", "Fake Channel"))
        assertNull(playback.nowPlaying())
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
        assertEquals(song.copy(album = "After Hours"), playback.nowPlaying()?.metadata)
        assertEquals(song.title, playback.recording(song)?.title)

        // A song the phone itself plays comes first, though the cast one said where it is later.
        ShadowMediaSessionManager.reset()
        shadowOf(sessions).addController(remote(player("com.spotify.music", "Spotify", "Save Your Tears", "The Weeknd", album = "After Hours", at = NOW)))
        shadowOf(sessions).addController(player("com.music", "Music", "Blinding Lights", "The Weeknd", album = "After Hours", at = NOW - 60_000))
        assertEquals("Save Your Tears", playback.nowPlaying()?.metadata?.title)
        sounding()
        assertEquals(song.copy(album = "After Hours"), playback.nowPlaying()?.metadata)
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
    fun theSongPlayingOnThePhoneIsNamedWithWhereItIsAndTheAppPlayingIt() {
        // Not allowed yet, Android shows nothing. Empty rather than unset, which would keep what another test allowed.
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", "")
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd"))
        assertNull(playback.nowPlaying())
        allow()
        sounding()
        ShadowMediaSessionManager.reset()
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd", album = "After Hours"))
        assertEquals(Heard.Song(song.copy(album = "After Hours"), appleMusicId = null, offsetMs = 42_000, startedAtMs = PLAYED_AT, app = "com.spotify.music"), playback.nowPlaying())
    }

    @Test
    fun onlyAnAppPlayingSomethingNamesIt() {
        allow()
        sounding()
        // Paused, with no title, with no metadata, or Crosstune itself: none are a song playing.
        shadowOf(sessions).addController(player("com.paused", "Paused", "Something Else", "Someone", state = PlaybackState.STATE_PAUSED))
        shadowOf(sessions).addController(player("com.notitle", "No Title", "", "Someone"))
        shadowOf(sessions).addController(MediaController(app, MediaSession(app, "com.nothing").sessionToken).also { shadowOf(it).setPackageName("com.nothing") })
        shadowOf(sessions).addController(player(app.packageName, "Crosstune", "Its Own Preview", "Someone"))
        assertNull(playback.nowPlaying())

        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd", album = "After Hours"))
        assertEquals(song.copy(album = "After Hours"), playback.nowPlaying()?.metadata)
    }

    @Test
    fun localFilesWithThinTagsStillCountAsPlaying() {
        allow()
        sounding()
        // A file that names its artist is a song; one that names nobody plays with no song named, and is listened to.
        for ((input, expected) in listOf(
            Triple("org.videolan.vlc", "Fake Artist - Fake Song.mp3", null) to MusicMetadata("Fake Song", "Fake Artist"),
            Triple("org.videolan.vlc", "Fake Song", "Unknown Artist") to null,
            Triple("com.sec.android.app.music", "Fake Artist - Fake Song", "<unknown>") to MusicMetadata("Fake Song", "Fake Artist"),
            Triple("com.maxmpz.audioplayer", "Fake Song", "") to null,
            Triple("com.example.player", "Fake Artist - Fake Song.FLAC", "unknown") to MusicMetadata("Fake Song", "Fake Artist"),
            Triple("com.example.player", "Unknown Artist - Fake Song.ogg", null) to null,
            Triple("com.example.player", "AUD-20240101-WA0003.opus", "<unknown>") to null
        )) {
            val (packageName, title, artist) = input
            ShadowMediaSessionManager.reset()
            shadowOf(sessions).addController(player(packageName, "Player", title, artist))
            assertEquals(title, expected, playback.nowPlaying()?.metadata)
            // Followed by what the app has where the artist goes, as the app has it, and shown by its title as a song's is read.
            val shown = mapOf("Unknown Artist - Fake Song.ogg" to "Fake Song", "AUD-20240101-WA0003.opus" to "AUD-20240101-WA0003")[title] ?: title
            assertEquals(title, if (expected == null) Unnamed(packageName, "Player", shown, artist = artist.orEmpty()) else null, playback.playingUnnamed().sessionless())
        }
        // A title with a real artist is a song, even one shaped like a recording's name.
        ShadowMediaSessionManager.reset()
        shadowOf(sessions).addController(player("com.example.player", "Player", "Demo 20230415", "Fake Artist", album = "Fake Album"))
        assertEquals(MusicMetadata("Demo 20230415", "Fake Artist", album = "Fake Album"), playback.nowPlaying()?.metadata)
        assertNull(playback.playingUnnamed())
    }

    @Test
    fun aRadioSongsTrailingSeparatorDoesNotReachItsLookups() {
        allow()
        sounding()
        val expected = MusicMetadata("Fake Song", "Fake Artist", album = "Fake Radio Stream")
        for (dash in listOf("-", "–", "—")) {
            ShadowMediaSessionManager.reset()
            shadowOf(sessions).addController(player("com.example.radio", "Fake Radio", "Fake Song $dash ", "Fake Artist", album = "Fake Radio Stream"))
            assertEquals(dash, expected, playback.nowPlaying()?.metadata)
            assertEquals(dash, "com.example.radio", playback.appFor(expected)?.packageName)
            assertEquals(dash, "com.example.radio", followed(expected).last()?.appPackage)
        }
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
        assertEquals(song.copy(album = "After Hours"), playback.nowPlaying()?.metadata)
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
        assertEquals(MusicMetadata("Here Comes The Sun - Remastered 2019", "The Beatles", album = "Abbey Road"), playback.nowPlaying()?.metadata)
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
            assertEquals(title, heard.copy(recordingId = "$music:duration:354000", album = album), now?.metadata)
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
    fun qobuzOpensThroughAScreenThatLeavesTimeToOpenItAgainAndOtherAppsDirectly() {
        val qobuz = MusicService.QOBUZ.packageName
        val link = "https://play.qobuz.com/track/1"
        // With nothing to open it, that's said, and nothing's started.
        assertFalse(playback.openSong(qobuz, link))
        assertNull(shadowOf(app).nextStartedActivity)
        val opens = ComponentName(qobuz, "$qobuz.DeepLink")
        shadowOf(app.packageManager).addActivityIfNotPresent(opens)
        shadowOf(app.packageManager).addIntentFilterForActivity(
            opens, IntentFilter(Intent.ACTION_VIEW).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("https"); addDataAuthority("open.qobuz.com", null) }
        )
        assertTrue(playback.openSong(qobuz, link))
        val through = shadowOf(app).nextStartedActivity
        assertEquals(OpenSongActivity::class.java.name, through.component?.className)
        assertTrue(through.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        // It opens the song there, the way the app's own link does, and closes.
        val screen = org.robolectric.Robolectric.buildActivity(OpenSongActivity::class.java, through).create().get()
        val opened = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals(qobuz, opened.`package`)
        assertEquals("https://open.qobuz.com/track/1", opened.dataString)
        assertTrue(opened.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(screen.isFinishing)
        // Any other app, with nothing to open again, is opened as it was.
        assertTrue(playback.openSong("com.spotify.music", "https://open.spotify.com/track/1"))
        val direct = shadowOf(app).nextStartedActivity
        assertEquals("com.spotify.music", direct.`package`)
        assertEquals("https://open.spotify.com/track/1", direct.dataString)
        assertTrue(direct.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun tidalSearchReplacesThePreviousQueryAndCancelsAnOlderPendingSearch() {
        shadowOf(app).checkActivities(true)
        val tidal = MusicService.TIDAL.packageName
        val target = ComponentName(tidal, "$tidal.DeepLink")
        shadowOf(app.packageManager).addActivityIfNotPresent(target)
        shadowOf(app.packageManager).addIntentFilterForActivity(target, IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataScheme("tidal")
        })
        for (query in listOf("First", "Second")) {
            assertTrue(playback.openSong(tidal, MusicService.TIDAL.searchUrl(query)))
            val through = requireNotNull(shadowOf(app).nextStartedActivity)
            assertEquals(OpenSongActivity::class.java.name, through.component?.className)
            org.robolectric.Robolectric.buildActivity(OpenSongActivity::class.java, through).create()
            val before = requireNotNull(shadowOf(app).nextStartedActivity)
            assertEquals("tidal://my-collection", before.dataString)
            assertTrue(before.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        }
        // A second request replaces the pending first search instead of reopening the old one later.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        val second = requireNotNull(shadowOf(app).nextStartedActivity)
        assertEquals("tidal://search?q=Second", second.dataString)
        assertEquals(tidal, second.`package`)
        assertTrue(second.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNull(shadowOf(app).nextStartedActivity)

        // Switching services also cancels the search, through either launch path.
        val spotify = MusicService.SPOTIFY.packageName
        val spotifyScreen = ComponentName(spotify, "$spotify.Player")
        shadowOf(app.packageManager).addActivityIfNotPresent(spotifyScreen)
        shadowOf(app.packageManager).addIntentFilterForActivity(spotifyScreen, IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataScheme("https")
        })
        for (throughScreen in listOf(false, true)) {
            assertTrue(playback.openSong(tidal, MusicService.TIDAL.searchUrl("Old")))
            val waiting = requireNotNull(shadowOf(app).nextStartedActivity)
            org.robolectric.Robolectric.buildActivity(OpenSongActivity::class.java, waiting).create()
            assertEquals("tidal://my-collection", shadowOf(app).nextStartedActivity.dataString)
            val next = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://open.spotify.com/track/new")).setPackage(spotify)
            if (throughScreen) {
                assertTrue(app.openLeavingTimeToOpenAgain(next))
                val bridge = requireNotNull(shadowOf(app).nextStartedActivity)
                org.robolectric.Robolectric.buildActivity(OpenSongActivity::class.java, bridge).create()
            } else {
                assertTrue(app.tryStartMusicActivity(next))
            }
            assertEquals(next.dataString, shadowOf(app).nextStartedActivity.dataString)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
            assertNull("The old TIDAL search must not cover the new service", shadowOf(app).nextStartedActivity)
        }
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
    fun aSongOpensInAnAppThatTakesOnlyItsOwnLinksThroughOneOfThose() {
        // Qobuz's app opens open.qobuz.com, as Qobuz shares, and no web player link (#673).
        shadowOf(app).checkActivities(true)
        val qobuz = ComponentName(MusicService.QOBUZ.packageName, "${MusicService.QOBUZ.packageName}.DeepLinkActivity")
        shadowOf(app.packageManager).addActivityIfNotPresent(qobuz)
        shadowOf(app.packageManager).addIntentFilterForActivity(
            qobuz, IntentFilter(Intent.ACTION_VIEW).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("https"); addDataAuthority("open.qobuz.com", null) }
        )
        assertTrue(playback.openSong(MusicService.QOBUZ.packageName, "https://play.qobuz.com/track/12"))
        org.robolectric.Robolectric.buildActivity(OpenSongActivity::class.java, shadowOf(app).nextStartedActivity).create()
        assertEquals("https://open.qobuz.com/track/12", shadowOf(app).nextStartedActivity.dataString)
    }

    @Test
    fun linksAnAppDoesntOpenAreTriedAfterInTheFormItDoes() {
        val qobuz = MusicService.QOBUZ.packageName
        // Qobuz's app has no link to a search for something: a path after /search opens its home, and it ignores a query (#673).
        assertEquals(
            listOf(qobuz to "https://play.qobuz.com/search?q=Song%20Band", qobuz to "https://open.qobuz.com/search"),
            MusicService.QOBUZ.appLinks("https://play.qobuz.com/search?q=Song%20Band")
        )
        assertEquals("https://open.qobuz.com/album/studio2", MusicService.QOBUZ.appLinks("https://play.qobuz.com/album/studio2?ref=x").last().second)
        assertEquals("https://open.qobuz.com/search", MusicService.QOBUZ.appLinks("https://play.qobuz.com/search").last().second)
        assertEquals(
            "deezer-query://www.deezer.com/search?query=Song%20Band%20%26%20Co",
            MusicService.DEEZER.appLinks(MusicService.DEEZER.searchUrl("Song Band & Co")).last().second
        )
        assertEquals("tidal://search?q=Song%20Band%20%26%20Co", MusicService.TIDAL.appLinks(MusicService.TIDAL.searchUrl("Song Band & Co")).last().second)
        // What the apps open themselves goes as it is, and nothing else.
        for (link in listOf(
            "https://www.deezer.com/track/1", "https://www.deezer.com/search", "https://www.deezer.com/en/search/x", "https://deezer.page.link/x",
            "https://listen.tidal.com/search/x", "https://listen.tidal.com/search", "https://tidal.com/browse/track/1", "deezer://www.deezer.com/search/x"
        )) {
            val service = if ("deezer" in link) MusicService.DEEZER else MusicService.TIDAL
            assertEquals(link, listOf(service.packageName to link), service.appLinks(link))
        }
        assertEquals(listOf(qobuz to "https://open.qobuz.com/track/12"), MusicService.QOBUZ.appLinks("https://open.qobuz.com/track/12"))
        assertEquals(listOf(MusicService.SPOTIFY.packageName to "https://play.qobuz.com/track/12"), MusicService.SPOTIFY.appLinks("https://play.qobuz.com/track/12"))
        assertEquals(listOf(MusicService.DEEZER.packageName to "https://listen.tidal.com/search?q=x"), MusicService.DEEZER.appLinks("https://listen.tidal.com/search?q=x"))
    }

    @Test
    fun bandcampsAppIsAskedForASongBySearchAsItOpensNoPageOfItsWebsite() {
        val bandcamp = MusicService.BANDCAMP.packageName
        // A search link holds what to ask; a song's page doesn't, so it's asked for by the name it's given.
        val search = MusicService.BANDCAMP.searchUrl("Song Band & Co")
        assertEquals("Song Band & Co", MusicService.BANDCAMP.appSearch(search, asking = null))
        assertEquals("Song Band & Co", MusicService.BANDCAMP.appSearch(search, asking = "Other"))
        assertEquals("Song Band", MusicService.BANDCAMP.appSearch("https://band.bandcamp.com/track/song", asking = "Song Band"))
        assertNull(MusicService.BANDCAMP.appSearch("https://band.bandcamp.com/track/song", asking = null))
        assertNull(MusicService.BANDCAMP.appSearch("https://band.bandcamp.com/track/song", asking = " "))
        assertNull(MusicService.BANDCAMP.appSearch("https://bandcamp.com/search", asking = null))
        assertNull(MusicService.BANDCAMP.appSearch("not a link", asking = "Song Band"))
        // Other apps open their links, or are asked another way.
        assertNull(MusicService.QOBUZ.appSearch(MusicService.QOBUZ.searchUrl("Song"), asking = "Song"))
        assertNull(MusicService.searchIn(MusicService.SPOTIFY.packageName, "https://open.spotify.com/track/x", "Song"))
        assertNull(MusicService.searchIn(bandcamp, "https://band.bandcamp.com/track/song", null))
        val intent = checkNotNull(MusicService.searchIn(bandcamp, "https://band.bandcamp.com/track/song", "Song Band")) { "Expected MusicService.searchIn(bandcamp, \"https://band.bandcamp.com/track/song\", \"Song Band\") to be non-null" }
        assertEquals(Intent.ACTION_SEARCH, intent.action)
        assertEquals(ComponentName(bandcamp, MusicService.BANDCAMP_SEARCH_ACTIVITY), intent.component)
        assertEquals("Song Band", intent.getStringExtra(SearchManager.QUERY))
    }

    @Test
    fun aSearchIsSentToBandcampsAppTwiceAndAnAppThatIsGoneIsLeftToTheBrowser() {
        shadowOf(app).checkActivities(true)
        val bandcamp = MusicService.BANDCAMP.packageName
        val component = ComponentName(bandcamp, MusicService.BANDCAMP_SEARCH_ACTIVITY)
        shadowOf(app.packageManager).addActivityIfNotPresent(component)
        shadowOf(app.packageManager).addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_SEARCH).apply { addCategory(Intent.CATEGORY_DEFAULT) })
        assertTrue(playback.openSong(bandcamp, MusicService.BANDCAMP.searchUrl("Song Band")))
        // Through a screen of its own, which leaves time for the second however long Crosstune's been up.
        val through = shadowOf(app).nextStartedActivity
        assertEquals(OpenSongActivity::class.java.name, through.component?.className)
        assertTrue(org.robolectric.Robolectric.buildActivity(OpenSongActivity::class.java, through).create().get().isFinishing)
        // The second goes a moment after the first, once the app has started.
        val first = shadowOf(app).nextStartedActivity
        assertNull(shadowOf(app).nextStartedActivity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SECOND_START_MS))
        val sent = listOf(first, shadowOf(app).nextStartedActivity)
        assertTrue(sent.all { it.action == Intent.ACTION_SEARCH && it.getStringExtra(SearchManager.QUERY) == "Song Band" && it.component == component })
        assertNull(shadowOf(app).nextStartedActivity)
        // A song's page holds no words to ask for, and an app that isn't there takes nothing.
        assertFalse(playback.openSong(bandcamp, "https://band.bandcamp.com/track/song"))
        shadowOf(app.packageManager).removeActivity(component)
        assertFalse(playback.openSong(bandcamp, MusicService.BANDCAMP.searchUrl("Song Band")))
    }

    @Test
    fun aLinkAnAppDoesntOpenDoesntCountAsOpeningIt() = runBlocking {
        allow()
        val qobuz = MusicService.QOBUZ.packageName
        val session = player(qobuz, "Qobuz", "Blinding Lights", "The Weeknd", state = PlaybackState.STATE_PAUSED)
        shadowOf(sessions).addController(session)
        val fails = CoroutineScope(Dispatchers.Unconfined).async { playback.resume(song) }
        shadowOf(session).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_ERROR, 0, 1f, PLAYED_AT).build())
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(fails.await())
        assertFalse(playback.openSong(qobuz, "https://play.qobuz.com/track/12"))
        // Nothing of Qobuz's is in front, so there's nothing to stay behind.
        playback.comesBack()
        assertEquals(MainActivity::class.java.name, shadowOf(app).nextStartedActivity.component?.className)
    }

    @Test
    fun comingBackWithNothingOpenedYetComesBack() {
        playback.comesBack()
        assertEquals(MainActivity::class.java.name, shadowOf(app).nextStartedActivity.component?.className)
    }

    @Test
    fun comingBackWithoutOverlayLeavesANotificationThatBringsTheCardBack() {
        ShadowSettings.setCanDrawOverlays(false)
        playback.comesBack()
        assertEquals(MainActivity::class.java.name, shadowOf(app).nextStartedActivity.component?.className)
        val notice = app.getSystemService(NotificationManager::class.java).activeNotifications.single()
        assertEquals(app.getString(R.string.return_title), notice.notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(app.getString(R.string.return_text), notice.notification.extras.getString(Notification.EXTRA_TEXT))
        assertTrue(notice.notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, app.getSystemService(NotificationManager::class.java).getNotificationChannel("return").importance)
        val back = shadowOf(notice.notification.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, back.component?.className)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT, back.flags and (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        assertEquals(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS, returnNoticeMode(checkNotNull(notice.notification.contentIntent) { "Expected notice.notification.contentIntent to be non-null" }))
        app.dismissReturnToCard()
        assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }

    /** Android 14 is the first release that records who may start an activity from a notification. */
    @Test
    @Config(sdk = [34])
    fun theWayBackNoticeMayStartTheCardFromAndroidFourteen() {
        ShadowSettings.setCanDrawOverlays(false)
        playback.comesBack()
        val notice = app.getSystemService(NotificationManager::class.java).activeNotifications.single()
        assertEquals(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS, returnNoticeMode(checkNotNull(notice.notification.contentIntent) { "Expected notice.notification.contentIntent to be non-null" }))
    }

    @Test
    fun theWayBackIsAskedOnceUntilNotificationsAreAllowed() {
        ShadowSettings.setCanDrawOverlays(false)
        val asked = mutableListOf<String>()
        assertTrue(app.askToReturnToCard { asked += it })
        assertEquals(listOf(android.Manifest.permission.POST_NOTIFICATIONS), asked)
        // Asked already, a later card doesn't ask again.
        assertFalse(app.askToReturnToCard { asked += it })
        assertEquals(1, asked.size)
        // Allowed, or able to show over other apps, there's nothing to ask.
        app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE).edit().remove(KEY_ASKED_RETURN).commit()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(app.askToReturnToCard { asked += it })
        shadowOf(app).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        ShadowSettings.setCanDrawOverlays(true)
        assertFalse(app.askToReturnToCard { asked += it })
        assertEquals(1, asked.size)
    }

    /** Before Android 13 a notification needs no permission, so nothing is asked. */
    @Test
    @Config(sdk = [31])
    fun beforeAndroidThirteenTheWayBackIsNotAsked() {
        ShadowSettings.setCanDrawOverlays(false)
        assertFalse(app.askToReturnToCard { error("asked") })
    }

    /** Android 13 is the first release that can ask, and it does when notifications are still off. */
    @Test
    @Config(sdk = [33])
    fun onAndroidThirteenTheWayBackIsAskedWhenNotificationsAreOff() {
        ShadowSettings.setCanDrawOverlays(false)
        app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE).edit().remove(KEY_ASKED_RETURN).commit()
        val asked = mutableListOf<String>()
        assertTrue(app.askToReturnToCard { asked += it })
        assertEquals(listOf(android.Manifest.permission.POST_NOTIFICATIONS), asked)
    }

    private fun returnNoticeMode(open: android.app.PendingIntent) =
        shadowOf(open).options?.getInt("android.activity.pendingIntentCreatorBackgroundActivityStartMode", -1) ?: -1

    @Test
    fun comingBackOverOtherAppsDoesntPostThatNotification() {
        ShadowSettings.setCanDrawOverlays(true)
        playback.comesBack()
        assertEquals(MainActivity::class.java.name, shadowOf(app).nextStartedActivity.component?.className)
        assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }

    @Test
    fun qobuzOpenedIsLeftInFrontWhenItOnlyPlaysThere() = runBlocking {
        allow()
        val qobuz = MusicService.QOBUZ.packageName
        val session = player(qobuz, "Qobuz", "Blinding Lights", "The Weeknd", state = PlaybackState.STATE_PAUSED)
        shadowOf(sessions).addController(session)
        val fails = CoroutineScope(Dispatchers.Unconfined).async { playback.resume(song) }
        shadowOf(session).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_ERROR, 0, 1f, PLAYED_AT).build())
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(fails.await())
        assertTrue(playback.playsOnlyInFront(qobuz))
        val component = ComponentName(qobuz, "$qobuz.DeepLinkActivity")
        shadowOf(app.packageManager).addActivityIfNotPresent(component)
        shadowOf(app.packageManager).addIntentFilterForActivity(
            component, IntentFilter(Intent.ACTION_VIEW).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("https"); addDataAuthority("play.qobuz.com", null) }
        )
        assertTrue(playback.openSong(qobuz, "https://play.qobuz.com/track/12"))
        assertEquals(OpenSongActivity::class.java.name, shadowOf(app).nextStartedActivity.component?.className)
        // Behind Crosstune it would stop, and the screen that opened it counts as opening it.
        playback.comesBack()
        assertNull(shadowOf(app).nextStartedActivity)
        assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }

    @Test
    fun bandcampAskedBySearchIsLeftInFrontWhenItOnlyPlaysThere() = runBlocking {
        allow()
        val bandcamp = MusicService.BANDCAMP.packageName
        val session = player(bandcamp, "Bandcamp", "Blinding Lights", "The Weeknd", state = PlaybackState.STATE_PAUSED)
        shadowOf(sessions).addController(session)
        val fails = CoroutineScope(Dispatchers.Unconfined).async { playback.resume(song) }
        shadowOf(session).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_ERROR, 0, 1f, PLAYED_AT).build())
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(fails.await())
        assertTrue(playback.playsOnlyInFront(bandcamp))
        val component = ComponentName(bandcamp, MusicService.BANDCAMP_SEARCH_ACTIVITY)
        shadowOf(app.packageManager).addActivityIfNotPresent(component)
        shadowOf(app.packageManager).addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_SEARCH).apply { addCategory(Intent.CATEGORY_DEFAULT) })
        assertTrue(playback.openSong(bandcamp, MusicService.BANDCAMP.searchUrl("Song Band")))
        org.robolectric.Robolectric.buildActivity(OpenSongActivity::class.java, shadowOf(app).nextStartedActivity).create()
        assertEquals(bandcamp, shadowOf(app).nextStartedActivity.component?.packageName)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SECOND_START_MS))
        shadowOf(app).nextStartedActivity
        // Behind Crosstune it would stop, and the search that opened it counts as opening it.
        playback.comesBack()
        assertNull(shadowOf(app).nextStartedActivity)
        assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }

    @Test
    fun aSpotifyPagePlayingInABrowserIsItsSong() {
        allow()
        sounding()
        // A browser names a page that says nothing of what it plays by its title, on its site (#590).
        shadowOf(sessions).addController(player("com.android.chrome", "Chrome", "new trick – música, letra e videoclipe de ROSÉ | Spotify", "open.spotify.com"))
        assertEquals(MusicMetadata("new trick", "ROSÉ"), playback.nowPlaying()?.metadata)
    }

    @Test
    fun aFileOfNoArtistPlaysWithoutOneAndACompilationsIsReadFromItsTitle() {
        allow()
        sounding()
        // Android's word for a file with no artist is no artist (#591): the file plays (#780), with no song named, so it's listened to.
        shadowOf(sessions).addController(player("com.other.player", "Other", "Your FYP, but make it a playlist (256k)", "<unknown>", album = "Downloads"))
        assertNull(playback.nowPlaying())
        assertEquals(Unnamed("com.other.player", "Other", "Your FYP, but make it a playlist (256k)", artist = "<unknown>"), playback.playingUnnamed().sessionless())
        // A compilation's file names its artist in the title, whatever the language it says so in.
        for (various in listOf("Various Artists", "Varios Artistas", "VA")) {
            ShadowMediaSessionManager.reset()
            shadowOf(sessions).addController(player("com.other.player", "Other", "Vanishing Point - Provision", various, album = "Metal Compilation"))
            assertEquals(MusicMetadata("Provision", "Vanishing Point", album = "Metal Compilation"), playback.nowPlaying()?.metadata?.copy(lengthMs = null))
            assertEquals(MusicMetadata("Provision", "Vanishing Point", album = "Metal Compilation"), playback.recording(MusicMetadata("Provision", "Vanishing Point"))?.copy(lengthMs = null, recordingId = null))
        }
        // With no artist in its title either, it plays, but there's nobody to name, so it's listened to.
        ShadowMediaSessionManager.reset()
        shadowOf(sessions).addController(player("com.other.player", "Other", "Provision", "Various Artists", album = "Metal Compilation"))
        assertNull(playback.nowPlaying())
        assertEquals("Provision", playback.playingUnnamed()?.name)
    }

    @Test
    fun aRadioStationIsPlayedWithNoSongNamed() {
        allow()
        sounding()
        // As VRadio names a station it has no song for: no artist, its own name as the album, a length that grows (#998).
        val cover = "https://example.com/logo.png"
        shadowOf(sessions).addController(player("com.ilv.vradio", "VRadio", "RTP Antena 2", "", album = "VRadio", duration = 120_000, art = mapOf(MediaMetadata.METADATA_KEY_ART_URI to cover)))
        assertNull(playback.nowPlaying())
        assertEquals(Unnamed("com.ilv.vradio", "VRadio", "RTP Antena 2", cover), playback.playingUnnamed().sessionless())
        // An artist named as the app that plays them is a song, as "Trust Me" by Pandora in the Pandora app.
        ShadowMediaSessionManager.reset()
        shadowOf(sessions).addController(player("com.pandora.android", "Pandora", "Trust Me", "Pandora", album = "Fake Album"))
        assertEquals(MusicMetadata("Trust Me", "Pandora", album = "Fake Album"), playback.nowPlaying()?.metadata?.copy(lengthMs = null))
        assertNull(playback.playingUnnamed())
        // As Radio Garden names one: the station, where it is as the artist, and no length, as a video has.
        ShadowMediaSessionManager.reset()
        shadowOf(sessions).addController(player("com.jonathanpuckey.radiogarden", "Radio Garden", "Fake FM", "Lisbon, Portugal", duration = -1))
        assertNull(playback.nowPlaying())
        assertEquals("Fake FM", playback.playingUnnamed()?.name)
        // A station that sends what it plays, as ICY says it, names the song.
        ShadowMediaSessionManager.reset()
        shadowOf(sessions).addController(player("com.ilv.vradio", "VRadio", "Fake Artist - Fake Song", "", album = "VRadio"))
        assertEquals(MusicMetadata("Fake Song", "Fake Artist", album = "VRadio"), playback.nowPlaying()?.metadata?.copy(lengthMs = null))
        assertNull(playback.playingUnnamed())
    }

    @Test
    fun pausedRadioGardenDoesNotUseAnotherAppsSound() {
        allow()
        sounding()
        val station = "1LIVE"
        val radio = player(RadioGarden.PACKAGE, "Radio Garden", station, null, at = NOW)
        val morphe = player("app.morphe.android.apps.youtube.music", "YT Music Morphe", song.title, song.artist,
            state = PlaybackState.STATE_PAUSED, album = "After Hours")
        shadowOf(sessions).addController(radio)
        shadowOf(sessions).addController(morphe)
        val stations = mutableListOf<String?>()
        collecting += CoroutineScope(Dispatchers.Unconfined).launch { playback.unnamedUpdates().collect { stations += it?.name } }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(station, stations.last())

        fun switchTo(playing: MediaController, paused: MediaController) {
            val started = PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f, PLAYED_AT).build()
            val stopped = PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0, 0f, NOW).build()
            shadowOf(playing).setPlaybackState(started)
            shadowOf(paused).setPlaybackState(stopped)
            shadowOf(paused).executeOnPlaybackStateChanged(stopped)
            shadowOf(playing).executeOnPlaybackStateChanged(started)
            shadowOf(Looper.getMainLooper()).idle()
        }

        // The phone stays audible while focus moves between the apps. Radio Garden keeps its station metadata.
        switchTo(morphe, radio)
        assertNull(stations.last())
        assertEquals(morphe.packageName, playback.nowPlaying()?.app)

        switchTo(radio, morphe)
        assertEquals(station, stations.last())
        assertNull(playback.nowPlaying())
    }

    @Test
    fun radioGardenStationMetadataCannotBeOpenedOrMatchedAsASong() {
        allow()
        sounding()
        val station = "Artist - Song"
        val packageName = RadioGarden.PACKAGE
        shadowOf(sessions).addController(player(packageName, "Radio Garden", station, "Cologne", album = "Radio Garden"))
        val item = MediaSession.QueueItem(MediaDescription.Builder().setTitle(station).setSubtitle(station).build(), 0)
        queues[packageName] = listOf(item)

        val unnamed = checkNotNull(playback.playingUnnamed())
        assertEquals(station, unnamed.name)
        assertNull(unnamed.queueSong)
        assertNull(unnamed.asSong)
        assertNull(playback.songForRecognition())
        assertTrue(playback.matching(MusicMetadata(station, "Cologne")).isEmpty())
        assertNotNull(playback.appFor(unnamed.played, app = packageName))
    }

    @Test
    fun anotherAppsQueueDoesNotNameItsStation() {
        allow()
        sounding()
        val packageName = "com.example.radio"
        shadowOf(sessions).addController(player(packageName, "Other Radio", "1LIVE", null))
        val item = MediaSession.QueueItem(MediaDescription.Builder().setTitle("1LIVE").setSubtitle("The Kid LAROI - A PLACE TO GO").build(), 0)
        queues[packageName] = listOf(item)
        assertNull(checkNotNull(playback.playingUnnamed()).queueSong)
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun aRadioStationWithABroadcastCaptionKeepsItsWholeName() {
        allow()
        sounding()
        val title = "Fake FM - Hits 24/7"
        val app = "com.ilv.vradio"
        shadowOf(sessions).addController(player(app, "VRadio", title, "", album = "VRadio", actions = PlaybackState.ACTION_SEEK_TO))
        assertNull(playback.nowPlaying())
        val station = Unnamed(app, "VRadio", title, caption = true)
        assertEquals(WhatPlays.Nameless(station), playback.whatPlays().sessionless())
        val wrong = MusicMetadata("Hits 24/7", "Fake FM")
        playback.seekTo(wrong, 10_000, app)
        playback.playPause(wrong, false, app)
        assertEquals(emptyList<String>(), asked())
        playback.playPause(station.played, false, app)
        assertEquals(listOf("pause"), asked())
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun aBroadcastCaptionCannotOpenOrControlASongWithItsFullName() {
        allow()
        sounding()
        val title = "Fake FM - Hits 24/7"
        val app = "com.ilv.vradio"
        shadowOf(sessions).addController(player(app, "VRadio", title, "", album = "VRadio", actions = PlaybackState.ACTION_SEEK_TO))
        val song = MusicMetadata(title, "Fake Artist")
        playback.seekTo(song, 10_000, app)
        playback.playPause(song, false, app)
        assertEquals(emptyList<String>(), asked())
        val station = checkNotNull(playback.playingUnnamed())
        assertNull(station.asSong)
        assertTrue(station.said)
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun aCreditedAudioFilenameCanBeFollowedAndControlled() {
        allow()
        val packageName = "com.example.player"
        for (name in listOf("Live.mp3", "Paperkite.flac", "Acoustic.OPUS")) {
            ShadowMediaSessionManager.reset()
            val session = player(packageName, "Player", "Fixture Artist - $name", "", duration = 200_000L)
            shadowOf(sessions).addController(session)
            val song = MusicMetadata(name.substringBeforeLast('.'), "Fixture Artist")
            assertNull(name, playback.appFor(song.copy(artist = "Wrong Artist"), app = packageName))
            assertNull(name, playback.appFor(MusicMetadata("Fixture Artist", ""), app = packageName))
            val prefix = MusicMetadata("Fixture Artist", "Wrong Artist")
            assertNull(name, playback.appFor(prefix, app = packageName))
            playback.seekTo(prefix, 10_000, packageName)
            playback.playPause(prefix, false, packageName)
            assertEquals(name, emptyList<String>(), asked())
            assertEquals(name, session, playback.appFor(song, app = packageName))
            playback.seekTo(song, 10_000, packageName)
            playback.playPause(song, false, packageName)
            assertEquals(name, listOf("seekTo 10000", "pause"), asked())
            assertEquals(name, session, playback.appFor(song.copy(artist = ""), app = packageName))
            val fullTitle = MusicMetadata("Fixture Artist - ${song.title}", "")
            assertEquals(name, session, playback.appFor(fullTitle, app = packageName))
            val fullBy = fullTitle.copy(title = "Fixture Artist – ${song.title}", artist = "Fixture Artist")
            assertEquals(name, session, playback.appFor(fullBy, app = packageName))
            assertNull(name, playback.appFor(fullBy.copy(artist = "Wrong Artist"), app = packageName))
            if (name == "Paperkite.flac") {
                assertNull(name, playback.appFor(song.copy(title = "Paperkite (Live)"), app = packageName))
            }
        }
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun anArtistlessRecordingLabelCannotControlTheTitleWithoutItsTag() {
        allow()
        sounding()
        val app = "com.other.player"
        shadowOf(sessions).addController(player(app, "Other", "Fake Song - Remastered", "", album = "Fake Album"))
        playback.playPause(MusicMetadata("Fake Song", "Fake Artist"), false, app)
        assertEquals(emptyList<String>(), asked())
        assertNull(checkNotNull(playback.playingUnnamed()).asSong)
    }

    @Test
    fun whatPlayedLastOnThePhoneComesFirstNamedOrNot() {
        allow()
        sounding()
        // With no title at all, it still plays, named by nothing.
        shadowOf(sessions).addController(player("com.example.player", "Player", "", null, at = PLAYED_AT + 1))
        assertEquals(Unnamed("com.example.player", "Player", null), playback.playingUnnamed().sessionless())
        // The one that played last is the one told.
        shadowOf(sessions).addController(player("com.ilv.vradio", "VRadio", "RTP Antena 2", "", at = PLAYED_AT + 2))
        assertEquals(Unnamed("com.ilv.vradio", "VRadio", "RTP Antena 2"), playback.playingUnnamed().sessionless())
        // A song that played before it doesn't take its place, as the sleep timer and practice have the station too.
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd", at = PLAYED_AT))
        assertNull(playback.nowPlaying())
        assertEquals(Unnamed("com.ilv.vradio", "VRadio", "RTP Antena 2"), playback.playingUnnamed().sessionless())
        // A song played since does.
        ShadowMediaSessionManager.reset()
        shadowOf(sessions).addController(player("com.ilv.vradio", "VRadio", "RTP Antena 2", "", at = PLAYED_AT + 2))
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd", at = PLAYED_AT + 3))
        assertEquals(song, playback.nowPlaying()?.metadata)
        assertNull(playback.playingUnnamed())
    }

    @Test
    fun anAppNamingNoSongCountsOnlyWhilePlaying() {
        allow()
        sounding()
        shadowOf(sessions).addController(player("com.ilv.vradio", "VRadio", "RTP Antena 2", "", state = PlaybackState.STATE_PAUSED))
        assertNull(playback.playingUnnamed())
        // Played on a speaker elsewhere, it counts, away, as the phone has nothing of it to hear.
        ShadowMediaSessionManager.reset()
        val cast = player("com.ilv.vradio", "VRadio", "RTP Antena 2", "")
        shadowOf(cast).setPlaybackInfo(PlaybackInfoBuilder.newBuilder().setVolumeType(MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE).build())
        shadowOf(sessions).addController(cast)
        assertEquals(true, playback.playingUnnamed()?.away)
        // Nor with no access to what apps play.
        ShadowMediaSessionManager.reset()
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", "")
        NotificationManagerCompat.getEnabledListenerPackages(app)
        shadowOf(sessions).addController(player("com.ilv.vradio", "VRadio", "RTP Antena 2", ""))
        assertNull(playback.playingUnnamed())
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun anAppNamingNoSongIsFollowedAndAskedByItsPackageWhateverItCallsItPausedToo() {
        allow()
        sounding()
        // A file with no title at all, as a player may name one.
        val file = player("com.example.player", "Fake Player", "", null)
        shadowOf(sessions).addController(file)
        val unnamed = checkNotNull(playback.playingUnnamed())
        assertEquals(Unnamed("com.example.player", "Fake Player", null), unnamed.sessionless())
        val seen = mutableListOf<Following?>()
        collecting += CoroutineScope(Dispatchers.Unconfined).launch { playback.follow(unnamed.played, unnamed.app).collect { seen += it } }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(true, seen.last()?.clock?.playing)
        // Its buttons reach it by its package; by its name, which is none, nothing does.
        playback.playPause(unnamed.played, false, unnamed.app)
        playback.skip(unnamed.played, true, unnamed.app)
        assertEquals(listOf("pause", "skipToNext"), asked())
        playback.playPause(unnamed.played, false)
        playback.skip(unnamed.played, true)
        assertEquals(emptyList<String>(), asked())
        // Paused, it no longer counts as playing, but it's still followed, paused, as a song is.
        shadowOf(file).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 42_000, 0f, PLAYED_AT).build())
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(playback.playingUnnamed())
        assertEquals(false, seen.last()?.clock?.playing)
        playback.playPause(unnamed.played, true, unnamed.app)
        assertEquals(listOf("play"), asked())
        // Stopped, it has nothing to follow.
        shadowOf(file).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0, 0f, PLAYED_AT).build())
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(seen.last())
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun aSongIsNeverTakenForWhatItsAppPlaysWithNoSongNamed() {
        allow()
        sounding()
        // The same app plays a song now: asked by package for what named none, it isn't that.
        shadowOf(sessions).addController(player("com.example.player", "Fake Player", "Fake Song", "Fake Band"))
        playback.playPause(MusicMetadata("", ""), false, "com.example.player")
        assertEquals(emptyList<String>(), asked())
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun aSongAskedOfAnAppIsNeverWhatItPlaysWithNoSongNamed() {
        allow()
        sounding()
        val fakeSong = MusicMetadata("Fake Song", "Fake Band")
        // A card's song asked of its app, as it loads with no title yet, or as it plays a station: it isn't that.
        listOf(player("com.example.player", "Fake Player", "", null), player("com.example.player", "Fake Player", "Fake FM", "")).forEach { other ->
            ShadowMediaSessionManager.reset()
            shadowOf(sessions).addController(other)
            val seen = mutableListOf<Following?>()
            collecting += CoroutineScope(Dispatchers.Unconfined).launch { playback.follow(fakeSong, "com.example.player").collect { seen += it } }
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(seen.last())
            playback.seekTo(fakeSong, 10_000, "com.example.player")
            playback.playPause(fakeSong, false, "com.example.player")
            assertEquals(emptyList<String>(), asked())
        }
        // The station is reached as it's called, there.
        playback.playPause(MusicMetadata("Fake FM", ""), false, "com.example.player")
        assertEquals(listOf("pause"), asked())
    }

    /** [controller] played elsewhere, on a speaker or a computer, as Android says. */
    private fun elsewhere(controller: MediaController) = controller.also {
        shadowOf(it).setPlaybackInfo(PlaybackInfoBuilder.newBuilder().setVolumeType(MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE).build())
    }

    @Test
    fun aMirroredBrowserTitleCanHaveTheBarWhileRecognitionListensToTheRoom() {
        allow()
        sounding(false)
        val nearby = Heard.Song(MusicMetadata("Demo", "Fake Band"), null)
        val listener = PlayingFirst(playback, SongHearing { nearby }, SongHearing { error("The phone is quiet") })
        for (app in listOf("org.kde.kdeconnect_tp", "com.example.desktop")) {
            ShadowMediaSessionManager.reset()
            shadowOf(sessions).addController(elsewhere(player(app, "Desktop player", "Fake Streamer - Twitch", null)))
            assertEquals("Twitch", playback.nowPlaying()?.metadata?.title)
            assertFalse(playback.playsHereWithoutSong())
            assertEquals(app, nearby, runBlocking { listener.listen() })
        }
    }

    @Test
    fun aBrowserTitleOnThePhoneWithNoArtistStillNeedsAudioToNameTheSong() {
        allow()
        sounding()
        shadowOf(sessions).addController(player("com.example.browser", "Browser", "Fake Show - Episode 12", "<unknown>"))
        assertEquals("Episode 12", playback.nowPlaying()?.metadata?.title)
        assertTrue(playback.playsHereWithoutSong())
        val nearby = Heard.Song(MusicMetadata("Demo", "Fake Band"), null)
        val listener = PlayingFirst(playback, SongHearing { nearby }, SongHearing { Heard.Failed(AppError.MICROPHONE) })
        assertEquals(nearby, runBlocking { listener.listen() })
        assertEquals(nearby.copy(inRoom = true), runBlocking { listener.listenToPlaying() })
    }

    @Test
    fun aMusicAppWithARemoteSpeakerCanStillNameItsSong() {
        allow()
        sounding(false)
        val music = elsewhere(player("com.example.player", "Music Player", "Demo", "Fake Band", album = "Fake Album"))
        shadowOf(sessions).addController(music)
        val playing = checkNotNull(playback.nowPlaying())
        val listener = PlayingFirst(playback, SongHearing { Heard.Nothing })
        assertEquals(playing, runBlocking { listener.listen() })
    }

    @Test
    fun aMirroredMusicPlayerCanNameTheSongWhenItSuppliesItsArtist() {
        allow()
        sounding(false)
        shadowOf(sessions).addController(elsewhere(player(
            "org.kde.kdeconnect_tp", "Desktop player", "Demo", "Fake Band", album = "Fake Album"
        )))
        val playing = checkNotNull(playback.nowPlaying())
        assertEquals("Demo", playing.metadata.title)
        val listener = PlayingFirst(playback, SongHearing { Heard.Nothing })
        assertEquals(playing, runBlocking { listener.listen() })
    }

    @Test
    fun whatPlaysFirstIsRankedNamedOrNotOnThePhoneOrNot() {
        allow()
        // A station cast to a speaker plays, and has the bar, though the phone has nothing of it to hear.
        shadowOf(sessions).addController(elsewhere(player("com.ilv.vradio", "VRadio", "Fake FM", "")))
        assertNull(playback.nowPlaying())
        assertEquals(Unnamed("com.ilv.vradio", "VRadio", "Fake FM", where = Place.ELSEWHERE), playback.playingUnnamed().sessionless())
        assertEquals(WhatPlays.Nameless(Unnamed("com.ilv.vradio", "VRadio", "Fake FM", where = Place.ELSEWHERE)), playback.whatPlays().sessionless())
        // A station on the phone comes before a song on a computer, though the computer's said so since: the bar has the station.
        ShadowMediaSessionManager.reset()
        sounding()
        shadowOf(sessions).addController(player("com.ilv.vradio", "VRadio", "Fake FM", ""))
        shadowOf(sessions).addController(elsewhere(player("org.kde.kdeconnect_tp", "KDE Connect", "Fake Song", "Fake Band", album = "Fake Album", at = PLAYED_AT + 5)))
        assertNull(playback.nowPlaying())
        assertEquals(Unnamed("com.ilv.vradio", "VRadio", "Fake FM"), playback.playingUnnamed().sessionless())
        assertEquals(WhatPlays.Nameless(Unnamed("com.ilv.vradio", "VRadio", "Fake FM")), playback.whatPlays().sessionless())
        // A music app's item with no song named, cast to a TV, is the phone's own, so it comes before a newer song on a computer.
        ShadowMediaSessionManager.reset()
        shadowOf(sessions).addController(elsewhere(player("com.spotify.music", "Spotify", "Fake Episode", "")))
        shadowOf(sessions).addController(elsewhere(player("org.kde.kdeconnect_tp", "KDE Connect", "Fake Song", "Fake Band", album = "Fake Album", at = PLAYED_AT + 5)))
        assertEquals(WhatPlays.Nameless(Unnamed("com.spotify.music", "Spotify", "Fake Episode", where = Place.CAST)), playback.whatPlays().sessionless())
        // Both on the phone, and both saying what they play, the one that said where it is last comes first, either way.
        ShadowMediaSessionManager.reset()
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd"))
        shadowOf(sessions).addController(player("com.ilv.vradio", "VRadio", "Fake FM", "", at = PLAYED_AT + 5))
        assertEquals(WhatPlays.Nameless(Unnamed("com.ilv.vradio", "VRadio", "Fake FM")), playback.whatPlays().sessionless())
        ShadowMediaSessionManager.reset()
        shadowOf(sessions).addController(player("com.ilv.vradio", "VRadio", "Fake FM", ""))
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd", at = PLAYED_AT + 5))
        assertEquals(song, (playback.whatPlays() as WhatPlays.Named).song.metadata)
        // A song on the phone comes before a station cast.
        ShadowMediaSessionManager.reset()
        shadowOf(sessions).addController(elsewhere(player("com.ilv.vradio", "VRadio", "Fake FM", "", at = PLAYED_AT + 5)))
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd"))
        assertEquals(song, (playback.whatPlays() as WhatPlays.Named).song.metadata)
        assertEquals(song, playback.nowPlaying()?.metadata)
        assertNull(playback.playingUnnamed())
        // Nothing plays.
        ShadowMediaSessionManager.reset()
        assertNull(playback.whatPlays())
    }

    @Test
    fun whatSaysNoSongComesAfterEveryAppThatSaysOne() {
        allow()
        sounding()
        // A video that doesn't say it's music, on the phone, comes after a song cast to a speaker.
        val vlog = { player("com.google.android.youtube", "YouTube", "Fake Vlog Day 3", "Fake Channel", at = PLAYED_AT + 5) }
        shadowOf(sessions).addController(vlog())
        shadowOf(sessions).addController(elsewhere(player("com.spotify.music", "Spotify", "Fake Song", "Fake Band", album = "Fake Album")))
        assertEquals(MusicMetadata("Fake Song", "Fake Band", album = "Fake Album"), playback.nowPlaying()?.metadata?.copy(lengthMs = null))
        assertNull(playback.playingUnnamed())
        assertEquals(MusicMetadata("Fake Song", "Fake Band", album = "Fake Album"), (playback.whatPlays() as WhatPlays.Named).song.metadata.copy(lengthMs = null))
        // And after a song on a computer.
        ShadowMediaSessionManager.reset()
        shadowOf(sessions).addController(vlog())
        shadowOf(sessions).addController(elsewhere(player("org.kde.kdeconnect_tp", "KDE Connect", "Fake Song", "Fake Band", album = "Fake Album")))
        assertEquals(MusicMetadata("Fake Song", "Fake Band", album = "Fake Album"), playback.nowPlaying()?.metadata?.copy(lengthMs = null))
        // A session with no title comes after a song, though it said where it is since.
        ShadowMediaSessionManager.reset()
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Fake Song", "Fake Band", album = "Fake Album"))
        shadowOf(sessions).addController(player("com.example.player", "Player", "", null, at = PLAYED_AT + 5))
        assertEquals(MusicMetadata("Fake Song", "Fake Band", album = "Fake Album"), playback.nowPlaying()?.metadata?.copy(lengthMs = null))
        // With nothing else playing, it's what plays.
        ShadowMediaSessionManager.reset()
        shadowOf(sessions).addController(vlog())
        assertNull(playback.nowPlaying())
        assertEquals(Unnamed("com.google.android.youtube", "YouTube", "Fake Vlog Day 3", artist = "Fake Channel", said = false), playback.playingUnnamed().sessionless())
    }

    @Test
    fun anArtistlessVideoWithARecordingLabelDoesNotHideASong() {
        allow()
        sounding()
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Fake Song", "Fake Band", album = "Fake Album"))
        val video = player("com.google.android.youtube", "YouTube", "Fake Show - Live", "", at = PLAYED_AT + 5)
        shadowOf(sessions).addController(video)
        assertEquals("Fake Song", playback.nowPlaying()?.metadata?.title)
        ShadowMediaSessionManager.reset()
        shadowOf(sessions).addController(video)
        assertEquals(Unnamed("com.google.android.youtube", "YouTube", "Fake Show - Live", said = false), playback.playingUnnamed().sessionless())
    }

    @Test
    fun aMusicVideoKeepsItsCreditEvenWhenItsSongIsCalledLive() {
        allow()
        sounding()
        val title = "Fake Artist - Live"
        val video = player("com.google.android.youtube", "YouTube", title, "")
        shadowOf(sessions).addController(video)
        assertNull(playback.nowPlaying())
        ShadowMediaSessionManager.reset()
        val musicApp = "com.google.android.apps.youtube.music"
        shadowOf(sessions).addController(player(musicApp, "YouTube Music", title, ""))
        val song = MusicMetadata("Live", "Fake Artist")
        assertEquals(song, playback.nowPlaying()?.metadata)
        assertNotNull(playback.appFor(song, app = musicApp))
        ShadowMediaSessionManager.reset()
        shadowOf(sessions).addController(video)
        assertNull(playback.nowPlaying())
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun aStationCastToASpeakerIsReachedThereByItsApp() {
        allow()
        shadowOf(sessions).addController(elsewhere(player("com.ilv.vradio", "VRadio", "Fake FM", "", actions = PlaybackState.ACTION_SKIP_TO_NEXT)))
        val away = checkNotNull(playback.playingUnnamed())
        playback.playPause(away.played, false, away.app)
        playback.skip(away.played, true, away.app)
        assertEquals(listOf("pause", "skipToNext"), asked())
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun aStationIsNeverTheOneBeforeThatSharesItsWords() {
        allow()
        sounding()
        // A station isn't the one before it, sharing its words: "Los 40" isn't "Los 40 Classic", the next one.
        val radio = player("com.ilv.vradio", "VRadio", "Los 40", "")
        shadowOf(sessions).addController(radio)
        val seen = mutableListOf<Following?>()
        collecting += CoroutineScope(Dispatchers.Unconfined).launch { playback.follow(MusicMetadata("Los 40", "", unnamed = true), "com.ilv.vradio").collect { seen += it } }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(true, seen.last()?.clock?.playing)
        shadowOf(radio).executeOnMetadataChanged(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Los 40 Classic").putString(MediaMetadata.METADATA_KEY_ARTIST, "").build())
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(seen.last())
        // Asked with no app, a station isn't reached by its name either.
        playback.playPause(MusicMetadata("Los 40 Classic", "", unnamed = true), false)
        assertEquals(emptyList<String>(), asked())
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun aSongWithNoArtistIsFollowedInAnAppThatNamesItFully() {
        allow()
        sounding()
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Fake Song", "Fake Band", album = "Fake Album"))
        // As Google shares a song, or Recent kept one from a file named by its title alone.
        val shared = MusicMetadata("Fake Song", "")
        listOf("com.spotify.music", null).forEach { app ->
            val seen = mutableListOf<Following?>()
            collecting += CoroutineScope(Dispatchers.Unconfined).launch { playback.follow(shared, app).collect { seen += it } }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("$app", true, seen.last()?.clock?.playing)
        }
        playback.playPause(shared, false)
        assertEquals(listOf("pause"), asked())
        // What names no song is never a song, though it's called the same.
        assertNull(playback.appFor(shared.copy(unnamed = true)))
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun aSongStaysFollowedAndTimedAsItsAppNamesWhoSingsItMidSong() {
        allow()
        sounding()
        val file = player("com.example.player", "Player", "Fake Song", "", position = 0, duration = 200_000)
        shadowOf(sessions).addController(file)
        // Asked as a song with no artist, and as what the app plays with no song named, as its bar and timer have it.
        val unnamed = checkNotNull(playback.playingUnnamed()).played
        val asks = listOf(MusicMetadata("Fake Song", "") to null, unnamed to "com.example.player")
        val seen = asks.map { (song, app) ->
            mutableListOf<Following?>().also { seen -> collecting += CoroutineScope(Dispatchers.Unconfined).launch { playback.follow(song, app).collect { seen += it } } }
        }
        val scope = CoroutineScope(Dispatchers.Main.immediate)
        val timer = SleepTimer(playback, scope)
        timer.afterSong(unnamed, "com.example.player")
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(file).executeOnMetadataChanged(MediaMetadata.Builder(file.metadata).putString(MediaMetadata.METADATA_KEY_ARTIST, "Fake Band").build())
        shadowOf(Looper.getMainLooper()).idle()
        seen.forEach { assertEquals(true, it.last()?.clock?.playing) }
        // The timer waits for the song's end: nothing is paused yet.
        assertEquals(emptyList<String>(), asked())
        assertEquals(Sleep(), timer.set.value)
        scope.cancel()
    }

    @Test
    fun aSongIsFollowedInAnAppThatSendsNoArtistByItsTitleAsTheAppDressesIt() {
        allow()
        sounding()
        val asked = MusicMetadata("Fake Song", "Fake Band")
        val dressed = listOf("Fake Song [Explicit]", "Fake Song (Official Audio)", "Fake Song (From \"Fake Movie\")", "01 Fake Song", "01. Fake Song", "Fake Song (Live)", "Fake Song (Acoustic)", "Fake Song (Extended Mix)")
        for (title in dressed + listOf("1. Fake Song", "01 Fake Song (Live) [Explicit].mp3")) {
            ShadowMediaSessionManager.reset()
            shadowOf(sessions).addController(player("com.example.player", "Player", title, ""))
            assertEquals(title, "com.example.player", playback.appFor(asked)?.packageName)
            assertEquals(title, "com.example.player", playback.appFor(asked, app = "com.example.player")?.packageName)
        }
    }

    @Test
    fun anAppGoingOnInANewSessionThatSaysWhatTheLastOneDidIsFollowedAsANewOne() {
        allow()
        sounding()
        shadowOf(sessions).addController(player("com.example.player", "Fake Player", "", null))
        val unnamed = checkNotNull(playback.playingUnnamed())
        val seen = mutableListOf<Following?>()
        collecting += CoroutineScope(Dispatchers.Unconfined).launch { playback.follow(unnamed.played, unnamed.app).collect { seen += it } }
        shadowOf(Looper.getMainLooper()).idle()
        val first = checkNotNull(seen.last()).session
        // It goes on to its next file in a new session, which says just what the last one did.
        shadowOf(sessions).clearControllers()
        val next = player("com.example.player", "Fake Player", "", null)
        shadowOf(sessions).addController(next)
        shadowOf(Looper.getMainLooper()).idle()
        val second = checkNotNull(seen.last()).session
        assertNotEquals(first, second)
        // Saying where it is again, it's the same session still.
        shadowOf(next).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 43_000, 1f, PLAYED_AT).build())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(43_000L, seen.last()?.clock?.positionMs)
        assertEquals(second, seen.last()?.session)
    }

    @Test
    fun aTitleAndArtistAskedOfLeastLatelyIsReadAgainOnceMoreThanTheKeptAreAsked() {
        val reads = mutableListOf<String>()
        val songs = KeptReads<String, MusicMetadata?>(2) { title -> reads += title; MusicMetadata(title, "Fake Band").takeIf { title != "Untitled" } }
        assertEquals(MusicMetadata("One", "Fake Band"), songs.of("One"))
        assertNull(songs.of("Untitled"))
        // Both kept, each read once, even one that names no song; "One" is asked of last.
        assertEquals(MusicMetadata("One", "Fake Band"), songs.of("One"))
        assertEquals(listOf("One", "Untitled"), reads)
        // A third takes the place of the one asked of least lately.
        assertEquals(MusicMetadata("Three", "Fake Band"), songs.of("Three"))
        assertEquals(MusicMetadata("One", "Fake Band"), songs.of("One"))
        assertNull(songs.of("Untitled"))
        assertEquals(listOf("One", "Untitled", "Three", "Untitled"), reads)
    }

    @Test
    fun aSessionKeepsItsNumberInAFollowStartedOverAndANewOneGetsAnother() {
        allow()
        sounding()
        shadowOf(sessions).addController(player("com.example.player", "Fake Player", "", null))
        val unnamed = checkNotNull(playback.playingUnnamed())
        fun follow(): Pair<Job, MutableList<Following?>> {
            val seen = mutableListOf<Following?>()
            val job = CoroutineScope(Dispatchers.Unconfined).launch { playback.follow(unnamed.played, unnamed.app).collect { seen += it } }
            shadowOf(Looper.getMainLooper()).idle()
            return job to seen
        }
        val (first, before) = follow()
        val one = checkNotNull(before.last()).session
        first.cancel()
        // Followed again, as when Crosstune comes back in sight, it's the same session, by the same number.
        val (again, after) = follow()
        assertEquals(one, checkNotNull(after.last()).session)
        // The app goes on in a new session, which says just what the last one did: another number.
        shadowOf(sessions).clearControllers()
        shadowOf(sessions).addController(player("com.example.player", "Fake Player", "", null))
        shadowOf(Looper.getMainLooper()).idle()
        val two = checkNotNull(after.last()).session
        assertNotEquals(one, two)
        again.cancel()
        // A follow started over on it has its number, never the first one's.
        val (last, seen) = follow()
        assertEquals(two, checkNotNull(seen.last()).session)
        last.cancel()
    }

    @Test
    fun vRadioSaysItHasPlayedLongerEachMinuteInOneSession() {
        allow()
        sounding()
        val radio = player("com.ilv.vradio", "VRadio", "RTP Antena 2", "", album = "VRadio", duration = 60_000)
        shadowOf(sessions).addController(radio)
        val unnamed = checkNotNull(playback.playingUnnamed())
        val seen = mutableListOf<Following?>()
        collecting += CoroutineScope(Dispatchers.Unconfined).launch { playback.follow(unnamed.played, unnamed.app).collect { seen += it } }
        shadowOf(Looper.getMainLooper()).idle()
        for (length in listOf(120_000L, 180_000L)) {
            val grown = MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "RTP Antena 2").putString(MediaMetadata.METADATA_KEY_ARTIST, "")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "VRadio").putLong(MediaMetadata.METADATA_KEY_DURATION, length).build()
            shadowOf(radio).setMetadata(grown)
            shadowOf(radio).executeOnMetadataChanged(grown)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(listOf(60_000L, 120_000L, 180_000L), seen.map { it?.durationMs })
        assertEquals(1, seen.map { it?.session }.distinct().size)
        assertEquals(unnamed, playback.playingUnnamed())
    }

    @Test
    fun eachTitleAndArtistAnAppSaysIsReadOnce() {
        allow()
        sounding()
        val reads = mutableListOf<Pair<String, String>>()
        val playback = MediaSessionPlayback(app, now = { NOW }, waker = { null }, modes = modes, queueOf = { queues[it.packageName] }, readSong = { title, artist, _ ->
            reads += title to artist
            MetadataParsers.playerSong(title, artist)
        })
        shadowOf(sessions).addController(player("com.example.player", "Fake Player", "Fake Song.mp3", ""))
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd", state = PlaybackState.STATE_PAUSED))
        // What plays first, what it's called, and following it in its app, as the bar asks for each change.
        val unnamed = checkNotNull(playback.playingUnnamed())
        playback.whatPlays()
        playback.nowPlaying()
        playback.unnamedUpdates()
        val seen = mutableListOf<Following?>()
        collecting += CoroutineScope(Dispatchers.Unconfined).launch { playback.follow(unnamed.played, unnamed.app).collect { seen += it } }
        collecting += CoroutineScope(Dispatchers.Unconfined).launch { playback.follow(song).collect { seen += it } }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, seen.size)
        assertEquals(reads.distinct(), reads)
    }

    @Test
    fun aFileNamedWithANumberIsNeverASongOfTheWordAfterIt() {
        allow()
        sounding()
        shadowOf(sessions).addController(player("com.example.player", "Player", "07 Years.mp3", ""))
        // The bar shows the title as a song's was read from a file, past its extension.
        assertEquals("07 Years", playback.playingUnnamed()?.name)
        // A song is had by what the app sends, as SongNames takes it: one word only as the whole title.
        assertNull(playback.appFor(MusicMetadata("Years", "Fake Band")))
        assertNull(playback.appFor(MusicMetadata("Years", "Fake Band"), app = "com.example.player"))
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun aFileIsShownByItsTitleAsASongsWasReadFromIt() {
        allow()
        sounding()
        for ((title, artist) in listOf("Fake Song.mp3" to "", "Unknown Artist - Fake Song.ogg" to null, "Fake Song" to "")) {
            ShadowMediaSessionManager.reset()
            shadowOf(sessions).addController(player("com.example.player", "Player", title, artist))
            val unnamed = checkNotNull(playback.playingUnnamed())
            assertEquals(title, "Fake Song", unnamed.name)
            // Its buttons still reach it by that name.
            playback.playPause(unnamed.played, false, unnamed.app)
            assertEquals(title, listOf("pause"), asked())
        }
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun aSongIsFollowedInAnAppThatSendsNoArtist() {
        allow()
        sounding()
        // The app names the song, with no one who sings it: what's asked is the song, as heard.
        val plain = player("com.example.player", "Player", "Fake Song", "")
        shadowOf(sessions).addController(plain)
        val asked = MusicMetadata("Fake Song", "Fake Band")
        listOf("com.example.player", null).forEach { app ->
            val seen = mutableListOf<Following?>()
            collecting += CoroutineScope(Dispatchers.Unconfined).launch { playback.follow(asked, app).collect { seen += it } }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(true, seen.last()?.clock?.playing)
            assertEquals("com.example.player", playback.appFor(asked, app = app)?.packageName)
        }
        playback.playPause(asked, false)
        assertEquals(listOf("pause"), asked())
        // A one-word name only by the whole title: "Fake" isn't "Fake Song".
        assertNull(playback.appFor(MusicMetadata("Fake", "Fake Band")))
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun anArtistInTheTitleDoesNotSupportAPartialSongOrAnotherArtist() {
        allow()
        sounding()
        for ((title, artist) in listOf("Fake Band - Fake Song Two" to "Fake Band", "Other Band - Fake Song" to "Fake Band")) {
            ShadowMediaSessionManager.reset()
            shadowOf(sessions).addController(player("com.example.player", "Fake Player", title, ""))
            val wanted = MusicMetadata("Fake Song", artist)
            assertNull(title, playback.appFor(wanted))
            playback.seekTo(wanted, 10_000)
            playback.playPause(wanted, false)
        }
        assertEquals(emptyList<String>(), asked())
    }

    @Test
    fun aParsedArtistCannotBeTakenFromTheSongsOwnTitle() {
        allow()
        sounding()
        shadowOf(sessions).addController(player("com.example.player", "Fake Player", "Other Band - Fake Band Song", ""))
        assertNull(playback.appFor(MusicMetadata("Fake Band Song", "Fake Band")))
    }

    @Test
    fun aWholePlayerTitleKeepsNonLatinVowels() {
        allow()
        sounding()
        shadowOf(sessions).addController(player("com.example.player", "Fake Player", "काला", ""))
        assertNull(playback.appFor(MusicMetadata("किला", "Fake Band")))
        assertFalse(SongNames.wholeTitle("काला", "किला"))
    }

    @Test
    fun aWholePlayerTitleKeepsItsWordBoundaries() {
        allow()
        sounding()
        for ((title, asked) in listOf("Apart" to "A Part", "A Part" to "Apart", "Glass Lantern" to "Glasslantern", "ガラス" to "カラス")) {
            ShadowMediaSessionManager.reset()
            shadowOf(sessions).addController(player("com.example.player", "Fake Player", title, ""))
            assertNull(title, playback.appFor(MusicMetadata(asked, "Fake Band")))
        }
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun aBoundSongPauseDoesNotReachANewTitleInTheSameSession() {
        allow()
        val wanted = MusicMetadata("Fake Song", "Fake Band")
        val controller = player("com.example.player", "Fake Player", wanted.title, wanted.artist)
        shadowOf(sessions).addController(controller)
        val chosen = playback.matching(wanted).single()
        shadowOf(controller).setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Fake Song Radio").putString(MediaMetadata.METADATA_KEY_ARTIST, "").build())
        chosen.source.playPause(wanted, false, chosen.app)
        assertEquals(emptyList<String>(), asked())
        // An explicit app pause still reaches that session regardless of its song.
        chosen.source.pauseApp(requireNotNull(chosen.app))
        assertEquals(listOf("pause"), asked())
    }

    @Test
    fun aMissingArtistDoesNotEraseAOneWordTitlesRecording() {
        allow()
        sounding()
        for ((title, asked) in listOf("Hello (Live)" to "Hello", "Hello" to "Hello (Live)")) {
            ShadowMediaSessionManager.reset()
            shadowOf(sessions).addController(player("com.example.player", "Fake Player", title, ""))
            assertNull("$title is not $asked", playback.appFor(MusicMetadata(asked, "Fake Band")))
        }
    }

    @Test
    fun anUnknownArtistStillNeedsTheWholeTitleBeforeItsSessionIsSelected() {
        allow()
        sounding()
        for (artist in listOf("", "<unknown>", "Unknown Artist", "Various Artists")) {
            ShadowMediaSessionManager.reset()
            val source = MediaSessionPlayback(app, now = { NOW }, modes = modes)
            shadowOf(sessions).addController(player("com.example.player", "Fake Player", "Fake Song.mp3", artist))
            assertEquals(artist, "com.example.player", source.appFor(MusicMetadata("Fake Song", "Fake Band"))?.packageName)
            assertNull(artist, source.appFor(MusicMetadata("Fake Song Two", "Fake Band")))
        }
        assertTrue(SongNames.wholeTitle("Canción Azul", "Cancion Azul"))
        assertTrue(SongNames.wholeTitle("ガラス", "カ\u3099ラス"))
        assertTrue(SongNames.wholeTitle("Fake-Song", "Fake Song"))
        ShadowMediaSessionManager.reset()
        shadowOf(sessions).addController(player("com.example.player", "Fake Player", "Fake Band - Fake Song", ""))
        assertEquals("com.example.player", playback.appFor(MusicMetadata("Fake Band - Fake Song", ""))?.packageName)
        assertFalse(SongNames.wholeTitle("\u093E", "\u093E"))
        assertFalse(SongNames.wholeTitle("", ""))
        assertFalse(SongNames.wholeTitle("", "Fake Song"))
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun aSessionWithNoArtistCannotBeControlledByWordsItsTitleShares() {
        allow()
        sounding()
        val cases = listOf(
            "Hotel California Radio" to "Hotel California",
            "Los 40 Classic" to "Los 40",
            "Fake Song" to "Fake Song Two",
            "Fake Song Two" to "Fake Song",
            "Hello World (Live)" to "Hello (Live)"
        )
        for ((title, askedTitle) in cases) {
            ShadowMediaSessionManager.reset()
            val source = MediaSessionPlayback(app, now = { NOW }, modes = modes)
            shadowOf(sessions).addController(player("com.example.player", "Fake Player", title, ""))
            val song = MusicMetadata(askedTitle, "Fake Band")
            for (of in listOf("com.example.player", null)) {
                assertNull("$title is not $askedTitle in $of", source.appFor(song, app = of))
                val seen = mutableListOf<Following?>()
                val job = CoroutineScope(Dispatchers.Unconfined).launch { source.follow(song, of).collect { seen += it } }
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals("$title is not followed for $askedTitle in $of", listOf<Following?>(null), seen)
                job.cancel()
                source.seekTo(song, 10_000, of)
                source.playPause(song, false, of)
            }
        }
        assertEquals(emptyList<String>(), asked())
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun anAppIsAskedToPlayWhateverItHas() {
        allow()
        shadowOf(sessions).addController(player("com.example.player", "Fake Player", "Fake Song.mp3", "<unknown>", state = PlaybackState.STATE_PAUSED))
        playback.playApp("com.example.player")
        playback.playApp("com.example.gone")
        assertEquals(listOf("play"), asked())
    }

    @Test
    fun aStationIsFollowedOnlyUntilItsAppIsOnToTheNext() {
        allow()
        sounding()
        val radio = player("com.ilv.vradio", "VRadio", "Fake FM", "")
        shadowOf(sessions).addController(radio)
        val seen = mutableListOf<Following?>()
        collecting += CoroutineScope(Dispatchers.Unconfined).launch { playback.follow(MusicMetadata("Fake FM", ""), "com.ilv.vradio").collect { seen += it } }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(true, seen.last()?.clock?.playing)
        // Skipped to the next station, it plays on, but not the one followed.
        shadowOf(radio).executeOnMetadataChanged(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Other FM").putString(MediaMetadata.METADATA_KEY_ARTIST, "").build())
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(seen.last())
    }

    @Test
    fun anAppNamingNoSongIsToldAsItChanges() {
        allow()
        sounding()
        val seen = mutableListOf<Unnamed?>()
        collecting += CoroutineScope(Dispatchers.Unconfined).launch { playback.unnamedUpdates().collect { seen += it } }
        shadowOf(Looper.getMainLooper()).idle()
        val radio = player("com.ilv.vradio", "VRadio", "RTP Antena 2", "")
        shadowOf(sessions).addController(radio)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Unnamed("com.ilv.vradio", "VRadio", "RTP Antena 2"), seen.last().sessionless())
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
    @Config(shadows = [CoverBitmap::class])
    fun aBitmapCoverNeverEncodesOnTheScreenThread() {
        val controller = aBitmapCover()
        shadowOf(sessions).addController(controller)
        coverWhenReady { playback.nowPlaying()?.metadata?.artworkUrl }
        assertEquals(0, CoverBitmap.mainEncodes.get())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(shadows = [CoverBitmap::class])
    fun repeatedPlaybackReadsEncodeABitmapOnlyOnce() {
        val controller = aBitmapCover()
        shadowOf(sessions).addController(controller)
        coverWhenReady { playback.nowPlaying()?.metadata?.artworkUrl }
        repeat(10) { playback.nowPlaying() }
        assertEquals(1, CoverBitmap.encodes.get())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(shadows = [CoverBitmap::class])
    fun parcelledBitmapCopiesReuseTheSessionsSavedCover() {
        val controller = aBitmapCover()
        shadowOf(sessions).addController(controller)
        val first = coverWhenReady { playback.nowPlaying()?.metadata?.artworkUrl }
        repeat(10) {
            val metadata = requireNotNull(controller.metadata)
            val bitmap = requireNotNull(metadata.getBitmap(MediaMetadata.METADATA_KEY_ART))
            val copy = requireNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
            shadowOf(controller).setMetadata(MediaMetadata.Builder(metadata).putBitmap(MediaMetadata.METADATA_KEY_ART, copy).build())
            assertEquals(first, playback.nowPlaying()?.metadata?.artworkUrl)
        }
        assertEquals(1, CoverBitmap.encodes.get())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(shadows = [CoverBitmap::class])
    fun aFailedSessionCoverCanSaveOnTheNextRead() {
        val controller = aBitmapCover()
        shadowOf(sessions).addController(controller)
        val directory = File(app.cacheDir, ARTWORK_DIR)
        directory.deleteRecursively()
        directory.writeText("not a directory")
        val failed = java.util.concurrent.CountDownLatch(1)
        CoverBitmap.before = Runnable { failed.countDown() }
        try {
            assertNull(playback.nowPlaying()?.metadata?.artworkUrl)
            assertTrue(failed.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // Wait for the writer to finish its failed attempt, not only enter compress().
            val writer = PlaybackCovers.shared(app)
            val lock = PlaybackCovers::class.java.getDeclaredField("writing").apply { isAccessible = true }
                .get(writer) as kotlinx.coroutines.sync.Mutex
            runBlocking { lock.lock(); lock.unlock() }
            assertTrue(directory.delete())
            assertNotNull(coverWhenReady { playback.nowPlaying()?.metadata?.artworkUrl })
        } finally {
            CoverBitmap.before = Runnable { }
            if (directory.isFile) directory.delete()
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(shadows = [CoverBitmap::class])
    fun newArtForTheSameSessionAndSongReplacesTheSavedCover() {
        val controller = aBitmapCover()
        val seen = offered()
        shadowOf(sessions).addController(controller)
        val first = coverWhenReady { shadowOf(Looper.getMainLooper()).idle(); seen.lastOrNull()?.metadata?.artworkUrl }
        val blue = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val metadata = MediaMetadata.Builder(controller.metadata).putBitmap(MediaMetadata.METADATA_KEY_ART, blue).build()
        shadowOf(controller).setMetadata(metadata)
        shadowOf(controller).executeOnMetadataChanged(metadata)
        val second = coverWhenReady {
            shadowOf(Looper.getMainLooper()).idle()
            seen.lastOrNull()?.metadata?.artworkUrl?.takeIf { it != first }
        }
        assertNotEquals(first, second)
        assertEquals(second, playback.nowPlaying()?.metadata?.artworkUrl)
        collecting.forEach(Job::cancel)
        shadowOf(Looper.getMainLooper()).idle()
        val green = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        shadowOf(controller).setMetadata(MediaMetadata.Builder(metadata).putBitmap(MediaMetadata.METADATA_KEY_ART, green).build())
        val reopened = offered()
        assertNotEquals(second, coverWhenReady {
            shadowOf(Looper.getMainLooper()).idle()
            reopened.lastOrNull()?.metadata?.artworkUrl?.takeIf { it != second }
        })
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun collectingATemporaryTokenCannotDiscardAnActiveSessionsCover() {
        val controller = aBitmapCover()
        shadowOf(sessions).addController(controller)
        val binderType = MediaSession.Token::class.java.getDeclaredField("mBinder").type
        val binder = android.os.Binder()
        val stable = java.lang.reflect.Proxy.newProxyInstance(binderType.classLoader, arrayOf(binderType)) { _, method, _ ->
            check(method.name == "asBinder") { "Unexpected token call: ${method.name}" }
            binder
        }
        val token = copyCoverToken(controller.sessionToken, stable)
        // Release the bitmap written by a temporary token wrapper. Keep the live Binder token.
        val writer = PlaybackCovers(File(app.cacheDir, "temporary-session-cover"))
        val (cover, bitmap) = saveThroughATemporaryToken(writer, controller, token)
        assertEquals(1L, runBlocking { withTimeout(5_000L) { writer.updates.first { it > 0 } } })
        val end = System.nanoTime() + 5_000_000_000L
        while (bitmap.get() != null && System.nanoTime() < end) {
            System.gc()
            System.runFinalization()
            Thread.sleep(10)
        }
        assertNull("the temporary bitmap was still retained", bitmap.get())
        val original = requireNotNull(requireNotNull(controller.metadata).getBitmap(MediaMetadata.METADATA_KEY_ART))
        val name = listOf("Fake Song", "Fake Artist", "Fake Album", null, null)
        writer.retainSessions(setOf(token))
        assertEquals(cover, writer.of(original, token, name))
        assertEquals(1L, writer.updates.value)
        // Removing the alias starts a new save, which returns the same file once it is ready.
        writer.retainSessions(emptySet())
        assertNull(writer.of(original, token, name))
        assertEquals(cover, coverWhenReady { writer.of(original, token, name) })
        assertEquals(2L, runBlocking { withTimeout(5_000L) { writer.updates.first { it >= 2 } } })
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(shadows = [CoverBitmap::class])
    fun readingActiveSessionsDropsAnInactiveCoversAlias() {
        val controller = aBitmapCover()
        shadowOf(sessions).addController(controller)
        val original = requireNotNull(requireNotNull(controller.metadata).getBitmap(MediaMetadata.METADATA_KEY_ART))
        val copied = Bitmap.createBitmap(original)
        val writer = PlaybackCovers.shared(app)
        val name = listOf("Fake Song", "Fake Artist", "Fake Album", null, null)
        val saved = coverWhenReady { writer.of(copied, controller.sessionToken, name) }
        assertEquals(saved, writer.of(original, controller.sessionToken, name))
        shadowOf(sessions).clearControllers()
        assertNull(playback.nowPlaying())
        assertNull(writer.of(original, controller.sessionToken, name))
        assertEquals(saved, coverWhenReady { writer.of(original, controller.sessionToken, name) })
        assertEquals(2, CoverBitmap.encodes.get())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(shadows = [CoverBitmap::class])
    fun aFailedCoverDoesNotDiscardAnotherSessionsSavedAlias() {
        val controller = aBitmapCover()
        val other = player("com.other", "Other", "Other Song", "Fake Artist")
        val original = requireNotNull(requireNotNull(controller.metadata).getBitmap(MediaMetadata.METADATA_KEY_ART))
        val copied = Bitmap.createBitmap(original)
        val writer = PlaybackCovers.shared(app)
        val name = listOf("Fake Song", "Fake Artist", "Fake Album", null, null)
        val saved = coverWhenReady { writer.of(copied, controller.sessionToken, name) }
        val directory = File(app.cacheDir, ARTWORK_DIR)
        val moved = File(app.cacheDir, "held-artwork")
        assertTrue(directory.renameTo(moved))
        directory.writeText("not a directory")
        val failed = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            assertNull(writer.of(failed, other.sessionToken, listOf("Other Song")))
            val field = PlaybackCovers::class.java.getDeclaredField("entries").apply { isAccessible = true }
            val entries = field.get(writer) as Map<*, *>
            val end = System.nanoTime() + 5_000_000_000L
            while (synchronized(entries) { entries.containsKey(failed) } && System.nanoTime() < end) Thread.sleep(5)
            assertFalse(synchronized(entries) { entries.containsKey(failed) })
            assertTrue(directory.delete())
            assertTrue(moved.renameTo(directory))
            assertEquals(saved, writer.of(original, controller.sessionToken, name))
            assertEquals(2, CoverBitmap.encodes.get())
        } finally {
            if (directory.isFile) directory.delete()
            if (moved.isDirectory) moved.renameTo(directory)
        }
    }

    private fun copyCoverToken(original: MediaSession.Token, binder: Any): MediaSession.Token {
        val uid = MediaSession.Token::class.java.getDeclaredField("mUid").apply { isAccessible = true }.get(original)
        val constructor = MediaSession.Token::class.java.declaredConstructors.single { it.parameterCount == 2 }.apply { isAccessible = true }
        return constructor.newInstance(uid, binder) as MediaSession.Token
    }

    private fun saveThroughATemporaryToken(writer: PlaybackCovers, controller: MediaController, originalToken: MediaSession.Token): Pair<String, java.lang.ref.WeakReference<Bitmap>> {
        // Two different token wrappers share the same Binder, as across system queries.
        val binder = requireNotNull(MediaSession.Token::class.java.getDeclaredField("mBinder").apply { isAccessible = true }.get(originalToken))
        val token = copyCoverToken(originalToken, binder)
        org.junit.Assert.assertNotSame(originalToken, token)
        assertEquals(originalToken, token)
        val original = requireNotNull(requireNotNull(controller.metadata).getBitmap(MediaMetadata.METADATA_KEY_ART))
        val bitmap = requireNotNull(original.copy(Bitmap.Config.ARGB_8888, false))
        val name = listOf("Fake Song", "Fake Artist", "Fake Album", null, null)
        val cover = coverWhenReady { writer.of(bitmap, token, name) }
        return cover to java.lang.ref.WeakReference(bitmap)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(shadows = [CoverBitmap::class])
    fun aSavedCoverUpdatesTheSongWithoutAnotherPlaybackEvent() {
        val controller = aBitmapCover()
        val seen = offered()
        shadowOf(sessions).addController(controller)
        val cover = coverWhenReady {
            shadowOf(Looper.getMainLooper()).idle()
            seen.lastOrNull()?.metadata?.artworkUrl
        }
        assertEquals(cover, playback.nowPlaying()?.metadata?.artworkUrl)
        assertEquals(1, CoverBitmap.encodes.get())
        assertEquals(0, CoverBitmap.mainEncodes.get())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(shadows = [CoverBitmap::class])
    fun playbackReadersShareTheSameSavedBitmapAndRecreateAnEvictedFile() {
        shadowOf(sessions).addController(aBitmapCover())
        val cover = coverWhenReady { playback.nowPlaying()?.metadata?.artworkUrl }
        val other = MediaSessionPlayback(app)
        assertEquals(cover, other.nowPlaying()?.metadata?.artworkUrl)
        assertEquals(1, CoverBitmap.encodes.get())
        assertTrue(File(URI(cover)).delete())
        assertEquals(cover, coverWhenReady { other.nowPlaying()?.metadata?.artworkUrl })
        assertEquals(2, CoverBitmap.encodes.get())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(shadows = [CoverBitmap::class])
    fun changingABitmapGivesItANewFileWithoutChangingItsOldOne() {
        val controller = aBitmapCover()
        shadowOf(sessions).addController(controller)
        val first = coverWhenReady { playback.nowPlaying()?.metadata?.artworkUrl }
        val bytes = File(URI(first)).readBytes()
        requireNotNull(requireNotNull(controller.metadata).getBitmap(MediaMetadata.METADATA_KEY_ART)).eraseColor(Color.BLUE)
        val second = coverWhenReady { playback.nowPlaying()?.metadata?.artworkUrl }
        assertNotEquals(first, second)
        org.junit.Assert.assertArrayEquals(bytes, File(URI(first)).readBytes())
        assertEquals(2, CoverBitmap.encodes.get())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(shadows = [CoverBitmap::class])
    fun aSlowOldCoverCannotBeShownForTheNextSong() {
        val first = aBitmapCover()
        val entered = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        CoverBitmap.before = Runnable { entered.countDown(); finish.await() }
        val seen = offered()
        try {
            shadowOf(sessions).addController(first)
            playback.nowPlaying()
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val second = player("com.example.next", "Next", "Next Song", "Next Artist", at = PLAYED_AT + 1, album = "Next Album")
            val blue = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            shadowOf(second).setMetadata(MediaMetadata.Builder(second.metadata).putBitmap(MediaMetadata.METADATA_KEY_ART, blue).build())
            shadowOf(sessions).addController(second)
            assertEquals("Next Song", playback.nowPlaying()?.metadata?.title)
            finish.countDown()
            val cover = coverWhenReady {
                shadowOf(Looper.getMainLooper()).idle()
                seen.lastOrNull()?.takeIf { it.metadata.title == "Next Song" }?.metadata?.artworkUrl
            }
            assertEquals(cover, playback.nowPlaying()?.metadata?.artworkUrl)
            val old = coverWhenReady { PlaybackCovers.shared(app).of(requireNotNull(requireNotNull(first.metadata).getBitmap(MediaMetadata.METADATA_KEY_ART))) }
            assertNotEquals(old, cover)
            assertTrue(seen.filter { it?.metadata?.title == "Next Song" }.none { it?.metadata?.artworkUrl == old })
        } finally {
            finish.countDown()
            CoverBitmap.before = Runnable { }
        }
    }

    @Test
    fun aCoverRefreshPostedAfterClosingCannotReadPlaybackAgain() {
        allow()
        sounding()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val source = MediaSessionPlayback(app, readSong = { title, artist, _ ->
            reads.incrementAndGet()
            MusicMetadata(title, artist)
        })
        val callbacks = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val held = object : android.os.Handler(Looper.getMainLooper()) {
            override fun sendMessageAtTime(message: android.os.Message, uptimeMillis: Long): Boolean {
                message.callback?.let { callbacks.add(it); return true }
                return super.sendMessageAtTime(message, uptimeMillis)
            }
        }
        MediaSessionPlayback::class.java.getDeclaredField("handler").apply { isAccessible = true }.set(source, held)
        val controller = player("com.example.player", "Player", "First Song", "Fake Artist", album = "Fake Album")
        shadowOf(sessions).addController(controller)
        val job = CoroutineScope(Dispatchers.Unconfined).launch { source.nowPlayingUpdates().collect {} }
        collecting += job
        shadowOf(Looper.getMainLooper()).idle()
        val late = requireNotNull(callbacks.poll(5, java.util.concurrent.TimeUnit.SECONDS))
        job.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        val before = reads.get()
        shadowOf(controller).setMetadata(MediaMetadata.Builder(controller.metadata).putString(MediaMetadata.METADATA_KEY_TITLE, "Next Song").build())
        late.run()
        assertEquals(before, reads.get())
    }

    private fun coverWhenReady(read: () -> String?): String {
        val end = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < end) {
            read()?.let { return it }
            Thread.sleep(5)
        }
        error("The bitmap cover was not saved: encodes=${CoverBitmap.encodes.get()}, result=${CoverBitmap.result}, named=${playback.nowPlaying()?.metadata}, unnamed=${playback.playingUnnamed()}, artDir=${File(app.cacheDir, ARTWORK_DIR).let { it.toString() + ":file=" + it.isFile + ":dir=" + it.isDirectory }}")
    }

    private fun aBitmapCover(): MediaController {
        allow()
        sounding()
        CoverBitmap.before = Runnable { }
        CoverBitmap.encodes.set(0)
        CoverBitmap.mainEncodes.set(0)
        val controller = player("com.example.player", "Player", "Fake Song", "Fake Artist", album = "Fake Album")
        val shown = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        shadowOf(controller).setMetadata(MediaMetadata.Builder(controller.metadata).putBitmap(MediaMetadata.METADATA_KEY_ART, shown).build())
        return controller
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
        val cover = coverWhenReady { playback.nowPlaying()?.metadata?.artworkUrl }
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
    fun filesWithTheSameTitleUseTheirOwnCovers() {
        allow()
        sounding()
        val first = player("com.example.first", "First", "Fake Song", "")
        val second = player("com.example.second", "Second", "Fake Song", "")
        val red = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val blue = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        shadowOf(first).setMetadata(MediaMetadata.Builder(first.metadata).putBitmap(MediaMetadata.METADATA_KEY_ART, red).build())
        shadowOf(second).setMetadata(MediaMetadata.Builder(second.metadata).putBitmap(MediaMetadata.METADATA_KEY_ART, blue).build())
        // Named by nobody, they're listened to, and shown by their covers meanwhile.
        shadowOf(sessions).addController(first)
        val firstCover = coverWhenReady { playback.playingUnnamed()?.artworkUrl }
        ShadowMediaSessionManager.reset()
        shadowOf(sessions).addController(second)
        val secondCover = coverWhenReady { playback.playingUnnamed()?.artworkUrl }
        assertNotNull(firstCover)
        assertNotNull(secondCover)
        assertNotEquals(firstCover, secondCover)
        shadowOf(second).setMetadata(MediaMetadata.Builder(second.metadata).putBitmap(MediaMetadata.METADATA_KEY_ART, null).build())
        assertNull(playback.playingUnnamed()?.artworkUrl)
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
        val cover = coverWhenReady { playback.nowPlaying()?.metadata?.artworkUrl }
        val loaded = runBlocking { ArtworkLoader(FakeSpotify().client(), Dispatchers.Unconfined, File(app.cacheDir, ARTWORK_DIR)).load(cover) }
        assertEquals(8, loaded?.width)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aCoverIsNotReturnedWhenItsFileCannotBeWritten() {
        allow()
        sounding()
        val controller = player("com.example.player", "Player", "Fake Song", "")
        val cover = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        shadowOf(controller).setMetadata(MediaMetadata.Builder(controller.metadata).putBitmap(MediaMetadata.METADATA_KEY_ART, cover).build())
        shadowOf(sessions).addController(controller)
        File(app.cacheDir, ARTWORK_DIR).writeText("not a directory")
        assertNull(playback.nowPlaying()?.metadata?.artworkUrl)
        // Named by nobody, it's shown with no cover too.
        assertEquals("Fake Song", playback.playingUnnamed()?.name)
        assertNull(playback.playingUnnamed()?.artworkUrl)
        // Named by somebody, it's a song, still with no cover.
        ShadowMediaSessionManager.reset()
        val named = player("com.example.player", "Player", "Fake Song", "Fake Artist", album = "Fake Album")
        shadowOf(named).setMetadata(MediaMetadata.Builder(named.metadata).putBitmap(MediaMetadata.METADATA_KEY_ART, cover).build())
        shadowOf(sessions).addController(named)
        assertEquals("Fake Song", playback.nowPlaying()?.metadata?.title)
        assertNull(playback.nowPlaying()?.metadata?.artworkUrl)
    }

    @Test
    fun aSelectedFullSessionIsFollowedInsteadOfItsAppsPlayingPreview() {
        allow()
        val preview = player("com.example.player", "Player", song.title, song.artist, duration = 30_000)
        val full = player("com.example.player", "Player", song.title, song.artist, state = PlaybackState.STATE_PAUSED, duration = 200_000)
        shadowOf(sessions).addController(preview)
        shadowOf(sessions).addController(full)
        val chosen = playback.matching(song).single { it.following?.durationMs == 200_000L }
        val source = chosen.source
        val seen = mutableListOf<Following?>()
        collecting += CoroutineScope(Dispatchers.Unconfined).launch { source.follow(song).collect { seen += it } }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(200_000L, seen.last()?.durationMs)
    }

    @Test
    fun aSelectedSessionsControlsCannotReachAnotherSessionInTheSameApp() {
        allow()
        val preview = player("com.example.player", "Player", song.title, song.artist, duration = 30_000)
        val full = player("com.example.player", "Player", song.title, song.artist, state = PlaybackState.STATE_PAUSED, duration = 200_000)
        shadowOf(sessions).addController(preview)
        shadowOf(sessions).addController(full)
        val chosen = playback.matching(song).single { it.following?.durationMs == 200_000L }
        val source = chosen.source
        source.pauseApp("com.example.other")
        assertEquals(0L, shadowOf(full.transportControls).lastPerformedAction)
        assertEquals(0L, shadowOf(preview.transportControls).lastPerformedAction)
        source.seekTo(song, 60_000)
        assertEquals(60_000L, shadowOf(full.transportControls).seekToPositionMs)
        source.playPause(song, true, chosen.app)
        assertEquals(PlaybackState.ACTION_PLAY, shadowOf(full.transportControls).lastPerformedAction)
        source.playPause(song, false, chosen.app)
        assertEquals(PlaybackState.ACTION_PAUSE, shadowOf(full.transportControls).lastPerformedAction)
        source.playPause(song, false)
        source.pauseApp("com.example.player")
        assertEquals(PlaybackState.ACTION_PAUSE, shadowOf(full.transportControls).lastPerformedAction)
        assertEquals(0L, shadowOf(preview.transportControls).lastPerformedAction)
    }

    @Test
    fun aBoundSessionWaitsForItsClockAndNeverFollowsItsAppsOtherSession() {
        allow()
        val preview = player("com.example.player", "Player", song.title, song.artist, duration = 30_000)
        val full = player("com.example.player", "Player", song.title, song.artist, state = PlaybackState.STATE_PAUSED, position = -1, duration = 200_000)
        shadowOf(sessions).addController(preview)
        shadowOf(sessions).addController(full)
        val chosen = playback.matching(song).single { it.following == null }
        val seen = mutableListOf<Following?>()
        collecting += CoroutineScope(Dispatchers.Unconfined).launch { chosen.source.follow(song, chosen.app).collect { seen += it } }
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(seen.last())
        shadowOf(full).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 60_000, 1f, NOW).setActions(PlaybackState.ACTION_SEEK_TO).build())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(200_000L, seen.last()?.durationMs)
        assertEquals(60_000L, seen.last()?.clock?.positionMs)
        shadowOf(full).executeOnMetadataChanged(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Other fake song").putString(MediaMetadata.METADATA_KEY_ARTIST, "Fake artist").build())
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(seen.last())
        chosen.source.seekTo(song, 60_000, chosen.app)
        chosen.source.playPause(song, true, chosen.app)
        assertEquals(0L, shadowOf(preview.transportControls).lastPerformedAction)
        // Cleanup still reaches the owned session after its metadata changed.
        chosen.source.pauseApp("com.example.player")
        assertEquals(PlaybackState.ACTION_PAUSE, shadowOf(full.transportControls).lastPerformedAction)
        assertEquals(0L, shadowOf(preview.transportControls).lastPerformedAction)
    }

    @Test
    fun matchingSessionCountsIncludeItsAppsOtherSongs() {
        allow()
        shadowOf(sessions).addController(player("com.example.player", "Player", song.title, song.artist, album = "Album"))
        shadowOf(sessions).addController(player("com.example.player", "Player", "Another fake song", "Fake artist"))
        val match = playback.matching(song).single()
        assertEquals("com.example.player", match.app)
        assertEquals(2, match.appSessions)
        assertEquals(2, playback.matching(MusicMetadata("Another fake song", "Fake artist")).single().appSessions)
    }

    @Test
    fun matchingSessionsIncludeASeekablePausedAppBehindAnUnseekablePlayingApp() {
        allow()
        val free = player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd", actions = 0)
        val seekable = player("com.example.player", "Player", "Blinding Lights", "The Weeknd", state = PlaybackState.STATE_PAUSED)
        shadowOf(sessions).addController(free)
        shadowOf(sessions).addController(seekable)
        shadowOf(sessions).addController(player("com.example.other", "Other", "Another Song", "The Weeknd"))
        val matches = playback.matching(song).map { requireNotNull(it.following) }.associateBy { it.appPackage }
        assertEquals(setOf("com.spotify.music", "com.example.player"), matches.keys)
        assertFalse(matches.getValue("com.spotify.music").canSeek)
        assertTrue(matches.getValue("com.spotify.music").clock.playing)
        assertTrue(matches.getValue("com.example.player").canSeek)
        assertFalse(matches.getValue("com.example.player").clock.playing)
        assertTrue(woken.isEmpty())
        assertTrue(AppAsked.calls.isEmpty())
        assertTrue(playback.matching(MusicMetadata("Absent", "Band")).isEmpty())
    }

    @Test
    @Config(shadows = [DeniedMatchingSessions::class])
    fun matchingSessionsAreEmptyWhenAccessWasRevoked() {
        assertTrue(playback.matching(song).isEmpty())
    }

    @Implements(MediaSessionManager::class)
    class DeniedMatchingSessions : ShadowMediaSessionManager() {
        @Implementation
        override fun getActiveSessions(component: ComponentName?): List<MediaController> {
            throw SecurityException("Notification access was revoked")
        }
    }

    @Test
    fun matchingSessionsWithNoClockKeepTheirHandlesForLaterReports() {
        allow()
        shadowOf(sessions).addController(player("com.example.stopped", "Stopped", "Blinding Lights", "The Weeknd", state = PlaybackState.STATE_STOPPED))
        shadowOf(sessions).addController(player("com.example.unknown", "Unknown", "Blinding Lights", "The Weeknd", position = -1))
        val matches = playback.matching(song)
        assertEquals(setOf("com.example.stopped", "com.example.unknown"), matches.map { it.app }.toSet())
        assertTrue(matches.all { it.following == null })
    }

    @Test
    fun anAppAskedForIsTheOnlyOneControlledEvenWhenAnotherPlaysTheSong() {
        allow()
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd", state = PlaybackState.STATE_PAUSED))
        shadowOf(sessions).addController(player("com.google.android.apps.youtube.music", "YouTube Music", "Blinding Lights", "The Weeknd", album = "After Hours"))
        assertEquals("com.spotify.music", playback.appFor(song, app = "com.spotify.music")?.packageName)
        assertEquals("com.google.android.apps.youtube.music", playback.appFor(song, app = "com.google.android.apps.youtube.music")?.packageName)
        assertNull(playback.appFor(song, app = "com.example.other"))
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun aLineTappedMovesTheAppThere() {
        allow()
        val spotify = player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd")
        shadowOf(sessions).addController(spotify)
        playback.seekTo(song, 61_000)
        assertEquals(listOf("seekTo 61000"), asked())
        // Nothing playing it, nothing to move.
        playback.seekTo(MusicMetadata("Other", "Band"), 1_000)
        assertEquals(emptyList<String>(), asked())
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun aSeekTheAppDoesntOfferIsntSent() {
        allow()
        // Playing, with skip and pause, and no seek: what Spotify's free plan advertises.
        val playingButStuck = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT
        val spotify = player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd", actions = playingButStuck)
        shadowOf(sessions).addController(spotify)
        playback.seekTo(song, 45_000)
        // No playback state at all is the same: nothing was offered, so nothing is sent.
        shadowOf(spotify).setPlaybackState(null)
        playback.seekTo(song, 45_000)
        assertEquals(emptyList<String>(), asked())
    }

    @Test
    fun theAppPlayingItIsTheOneControlledAsItsTheOneFollowed() {
        allow()
        // Spotify, first, is paused on the song, and YouTube Music plays it.
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd", state = PlaybackState.STATE_PAUSED))
        shadowOf(sessions).addController(player("com.google.android.apps.youtube.music", "YouTube Music", "Blinding Lights", "The Weeknd", album = "After Hours"))
        assertEquals("com.google.android.apps.youtube.music", checkNotNull(followed().last()) { "Expected followed().last() to be non-null" }.appPackage)
        assertEquals("com.google.android.apps.youtube.music", playback.appFor(song)?.packageName)
        assertNull(playback.appFor(MusicMetadata("Other", "Band")))
    }

    @Test
    fun ofTwoAppsPlayingItTheFirstIsTheOneControlled() {
        allow()
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd"))
        shadowOf(sessions).addController(player("com.google.android.apps.youtube.music", "YouTube Music", "Blinding Lights", "The Weeknd", album = "After Hours"))
        assertEquals("com.spotify.music", playback.appFor(song)?.packageName)
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun anAppIsPausedByItsNameWhateverItPlaysOnlyWhenItPlays() {
        allow()
        // TIDAL went on from the card's song into another; YouTube Music is paused already.
        shadowOf(sessions).addController(player(MusicService.TIDAL.packageName, "TIDAL", "Wake Me Up Before You Go-Go", "Wham!"))
        shadowOf(sessions).addController(player(MusicService.YOUTUBE_MUSIC.packageName, "YouTube Music", "Blinding Lights", "The Weeknd", state = PlaybackState.STATE_PAUSED))
        playback.pauseApp(MusicService.YOUTUBE_MUSIC.packageName)
        assertEquals(emptyList<String>(), asked())
        playback.pauseApp(MusicService.TIDAL.packageName)
        assertEquals(listOf("pause"), asked())
    }

    @Test
    fun anAppFollowedThatSaysItPlaysBeforeSayingItsNewSongIsNoLongerFollowed() {
        allow()
        val tidal = player(MusicService.TIDAL.packageName, "TIDAL", "Blinding Lights", "The Weeknd", album = "After Hours")
        shadowOf(sessions).addController(tidal)
        val seen = followed()
        assertEquals(MusicService.TIDAL.packageName, seen.last()?.appPackage)
        // Its preview over, it goes on to the next song, and says it plays before it says it's another.
        shadowOf(tidal).setMetadata(
            MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Wake Me Up Before You Go-Go").putString(MediaMetadata.METADATA_KEY_ARTIST, "Wham!").build()
        )
        shadowOf(tidal).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f, PLAYED_AT).setActions(PlaybackState.ACTION_SEEK_TO).build())
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(seen.last())
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun theAppPlayingItIsAskedToPlayOrPause() {
        allow()
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd"))
        playback.playPause(song, play = false)
        assertEquals(listOf("pause"), asked())
        playback.playPause(song, play = true)
        assertEquals(listOf("play"), asked())
        // Nothing playing it, nothing to ask.
        playback.playPause(MusicMetadata("Other", "Band"), play = true)
        playback.playPause(MusicMetadata("Other", "Band"), play = false)
        assertEquals(emptyList<String>(), asked())
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun anAppStoppedASecondAfterItSaysItPlaysHasntPlayedOn() = runBlocking {
        val youTube = MusicService.YOUTUBE.packageName
        allow()
        val stopped = player(youTube, "YouTube", "Blinding Lights", "The Weeknd", state = PlaybackState.STATE_STOPPED)
        shadowOf(sessions).addController(stopped)
        val says = { state: Int -> shadowOf(stopped).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(state, 0, 1f, PLAYED_AT).build()); shadowOf(Looper.getMainLooper()).idle() }
        val resumes = { by: MediaSessionPlayback -> CoroutineScope(Dispatchers.Unconfined).async { by.resume(song) } }
        // Stopped for a moment, then playing again, it did play on.
        val flickers = resumes(playback)
        says(PlaybackState.STATE_PLAYING)
        says(PlaybackState.STATE_STOPPED)
        says(PlaybackState.STATE_PLAYING)
        assertTrue(flickers.await())
        assertFalse(playback.playsOnlyInFront(youTube))
        // Asked from behind, YouTube without Premium says it plays, then stops at once: it only plays in front.
        shadowOf(stopped).setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0, 1f, PLAYED_AT).build())
        val fails = resumes(playback)
        says(PlaybackState.STATE_PLAYING)
        says(PlaybackState.STATE_STOPPED)
        assertFalse(fails.await())
        assertEquals(listOf("play", "play"), asked())
        assertTrue(playback.playsOnlyInFront(youTube))
        // Saying it plays before Crosstune looks, then failing, it didn't either.
        val fresh = MediaSessionPlayback(app, now = { NOW }, startMs = 200, playsOnMs = 200, modes = modes)
        shadowOf(stopped).setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f, PLAYED_AT).build())
        val quick = resumes(fresh)
        says(PlaybackState.STATE_ERROR)
        assertFalse(quick.await())
        assertTrue(fresh.playsOnlyInFront(youTube))
        // Failing before it ever says it plays, it isn't held to the second.
        val other = MediaSessionPlayback(app, now = { NOW }, startMs = 200, playsOnMs = 200, modes = modes)
        shadowOf(stopped).setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0, 1f, PLAYED_AT).build())
        val failsAtOnce = resumes(other)
        says(PlaybackState.STATE_ERROR)
        assertTrue(failsAtOnce.isCompleted)
        assertFalse(failsAtOnce.await())
        assertTrue(other.playsOnlyInFront(youTube))
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun aSongPausedPlaysOnFromBehindOrInFrontWhereItWas() = runBlocking {
        val music = MusicService.YOUTUBE_MUSIC.packageName
        allow()
        val youTubeMusic = player(music, "YouTube Music", "Blinding Lights", "The Weeknd", state = PlaybackState.STATE_PAUSED)
        shadowOf(sessions).addController(youTubeMusic)
        val says = { state: Int -> shadowOf(youTubeMusic).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(state, 0, 1f, PLAYED_AT).build()); shadowOf(Looper.getMainLooper()).idle() }
        val resumes = { CoroutineScope(Dispatchers.Unconfined).async { playback.resume(song) } }
        // Playing on, it did.
        val plays = resumes()
        says(PlaybackState.STATE_PLAYING)
        assertTrue(plays.await())
        assertEquals(listOf("play"), asked())
        assertFalse(playback.playsOnlyInFront(music))
        // Failing to, as YouTube Music without Premium does from behind, it only plays in front.
        shadowOf(youTubeMusic).setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0, 1f, PLAYED_AT).build())
        val fails = resumes()
        says(PlaybackState.STATE_ERROR)
        assertFalse(fails.await())
        assertEquals(listOf("play"), asked())
        assertTrue(playback.playsOnlyInFront(music))
        // Known to now, it isn't asked from behind again, nor waited on.
        val known = resumes()
        assertTrue(known.isCompleted)
        assertFalse(known.await())
        assertEquals(emptyList<String>(), asked())
        // Brought to the front as from its icon, on the screen it was on, not its link; and left there.
        assertFalse(playback.bringToFront(song))
        val icon = ComponentName(music, "$music.MusicActivity")
        shadowOf(app.packageManager).addActivityIfNotPresent(icon)
        shadowOf(app.packageManager).addIntentFilterForActivity(icon, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        // Asked to play once it says it's back, paused where it was.
        val brought = CoroutineScope(Dispatchers.Unconfined).async { playback.bringToFront(song) }
        val opened = shadowOf(app).nextStartedActivity
        assertEquals(icon, opened.component)
        assertTrue(opened.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertFalse(brought.isCompleted)
        // Still stopped, or loading, it isn't back yet.
        says(PlaybackState.STATE_STOPPED)
        says(PlaybackState.STATE_BUFFERING)
        assertFalse(brought.isCompleted)
        assertEquals(emptyList<String>(), asked())
        says(PlaybackState.STATE_PAUSED)
        assertTrue(brought.isCompleted)
        assertTrue(brought.await())
        assertEquals(listOf("play"), asked())
        assertTrue(shadowOf(youTubeMusic).getCallbacks().isEmpty())
        playback.comesBack()
        assertNull(shadowOf(app).nextStartedActivity)
        // Back already playing, it's back too.
        val playing = CoroutineScope(Dispatchers.Unconfined).async { playback.bringToFront(song) }
        says(PlaybackState.STATE_PLAYING)
        assertTrue(playing.isCompleted)
        assertEquals(listOf("play"), asked())
        // Saying nothing, it's asked anyway, a moment later.
        assertTrue(playback.bringToFront(song))
        assertEquals(listOf("play"), asked())
        // Gone by the time it's opened, it can't be.
        val gone = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) = throw ActivityNotFoundException()
        }
        assertFalse(MediaSessionPlayback(gone, now = { NOW }, startMs = 200, modes = modes).bringToFront(song))
        assertTrue(shadowOf(youTubeMusic).getCallbacks().isEmpty())
        // No app has it: nothing to play on, or bring.
        val other = MusicMetadata("Other", "Band")
        assertFalse(playback.resume(other))
        assertFalse(playback.bringToFront(other))
        assertEquals(emptyList<String>(), asked())
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun bringingAPlayerForwardDoesNotSendPlayAfterItsSessionLosesState() = runTest {
        val radio = RadioNotice.PACKAGE
        allow()
        val original = player(radio, "Simple Radio", song.title, song.artist, state = PlaybackState.STATE_PAUSED)
        val other = player(radio, "Simple Radio", song.title, song.artist, state = PlaybackState.STATE_PAUSED)
        shadowOf(sessions).addController(original)
        val icon = ComponentName(radio, "$radio.RadioActivity")
        shadowOf(app.packageManager).addActivityIfNotPresent(icon)
        shadowOf(app.packageManager).addIntentFilterForActivity(icon, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })

        val brought = async(start = CoroutineStart.UNDISPATCHED) { playback.bringToFront(song, radio) }
        assertEquals(icon, shadowOf(app).nextStartedActivity.component)
        shadowOf(original).setPlaybackState(null)
        shadowOf(sessions).addController(other)

        assertFalse(brought.await())
        assertTrue(asked().isEmpty())
        assertTrue(shadowOf(original).getCallbacks().isEmpty())
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun youTubeIsBackEvenIfItsSessionStartsAnUnnamedAdBeforeItCanBeAskedToPlay() = runTest {
        val youtube = MusicService.YOUTUBE.packageName
        allow()
        val controller = player(youtube, "YouTube", song.title, song.artist, state = PlaybackState.STATE_PAUSED)
        shadowOf(sessions).addController(controller)
        val icon = ComponentName(youtube, "$youtube.VideoActivity")
        shadowOf(app.packageManager).addActivityIfNotPresent(icon)
        shadowOf(app.packageManager).addIntentFilterForActivity(icon, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })

        val session = shadowOf(controller)
        val named = checkNotNull(controller.metadata)
        val paused = checkNotNull(controller.playbackState)
        for (unnamed in listOf(MediaMetadata.Builder().build(), null)) {
            session.setMetadata(named)
            session.setPlaybackState(paused)
            val returning = async(start = CoroutineStart.UNDISPATCHED) { playback.bringToFront(song, youtube) }
            assertEquals(icon, shadowOf(app).nextStartedActivity.component)
            session.setMetadata(unnamed)
            session.executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f, PLAYED_AT).build())
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(returning.await())
            assertTrue(asked().isEmpty())
            assertTrue(session.getCallbacks().isEmpty())
        }
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun returningDoesNotMistakeANamedOrPausedSessionForARunningUnnamedYouTubeAd() = runTest {
        allow()
        val youtube = MusicService.YOUTUBE.packageName
        val music = MusicService.YOUTUBE_MUSIC.packageName
        val original = player(youtube, "YouTube", song.title, song.artist, state = PlaybackState.STATE_PAUSED)
        val replacements = listOf(
            player(youtube, "YouTube", "Another video", "Different artist"),
            player(youtube, "YouTube", "", "", state = PlaybackState.STATE_PAUSED),
            player(youtube, "YouTube", "", "").also { shadowOf(it).setPlaybackState(null) },
            player(music, "YouTube Music", "", "")
        )
        val icon = ComponentName(youtube, "$youtube.VideoActivity")
        shadowOf(app.packageManager).addActivityIfNotPresent(icon)
        shadowOf(app.packageManager).addIntentFilterForActivity(icon, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        for (replacement in replacements) {
            shadowOf(sessions).clearControllers()
            shadowOf(sessions).addController(original)
            val returned = async(start = CoroutineStart.UNDISPATCHED) { playback.bringToFront(song, youtube) }
            assertEquals(icon, shadowOf(app).nextStartedActivity.component)
            shadowOf(sessions).clearControllers()
            shadowOf(sessions).addController(replacement)
            assertFalse(returned.await())
            assertTrue(asked().isEmpty())
        }
    }

    @Test
    @Config(shadows = [DeniedOnReturn::class, AppAsked::class])
    fun returningStopsWithoutPlayingIfNotificationAccessIsRevokedWhileItWaits() = runTest {
        allow()
        val youtube = MusicService.YOUTUBE.packageName
        val original = player(youtube, "YouTube", song.title, song.artist, state = PlaybackState.STATE_PAUSED)
        shadowOf(sessions).addController(original)
        val icon = ComponentName(youtube, "$youtube.VideoActivity")
        shadowOf(app.packageManager).addActivityIfNotPresent(icon)
        shadowOf(app.packageManager).addIntentFilterForActivity(icon, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        val returned = async(start = CoroutineStart.UNDISPATCHED) { playback.bringToFront(song, youtube) }
        assertEquals(icon, shadowOf(app).nextStartedActivity.component)
        org.robolectric.shadow.api.Shadow.extract<DeniedOnReturn>(sessions).revoked = true
        assertFalse(returned.await())
        assertTrue(asked().isEmpty())
        assertTrue(shadowOf(original).getCallbacks().isEmpty())
    }

    @Implements(MediaSessionManager::class)
    class DeniedOnReturn : ShadowMediaSessionManager() {
        var revoked = false

        @Implementation
        override fun getActiveSessions(component: ComponentName?): List<MediaController> {
            if (revoked) throw SecurityException("Notification access was revoked")
            return super.getActiveSessions(component)
        }
    }

    @Test
    fun theAppPlayingItSaysHowLongTheSongIsWhenItKnows() {
        allow()
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd", duration = 200_000))
        assertEquals(200_000L, checkNotNull(followed().last()) { "Expected followed().last() to be non-null" }.durationMs)
        // One that says nothing of it, or nothing sensible, has none.
        shadowOf(sessions).addController(player("com.soundcloud.android", "SoundCloud", "Other Song", "Band", duration = 0))
        assertNull(checkNotNull(followed(MusicMetadata("Other Song", "Band")).last()) { "Expected followed(MusicMetadata(\"Other Song\", \"Band\")).last() to be non-null" }.durationMs)
    }

    @Test
    fun theAppPlayingItSaysWhetherItCanGoOnToTheNextSongOrBack() {
        allow()
        val skips = PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        val spotify = player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd", actions = skips)
        shadowOf(sessions).addController(spotify)
        val seen = followed()
        assertTrue(checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.canSkipNext)
        assertTrue(checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.canSkipPrevious)
        assertFalse(checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.canSeek)
        shadowOf(spotify).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 1_000, 1f, PLAYED_AT).setActions(PlaybackState.ACTION_SKIP_TO_NEXT).build())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.canSkipNext)
        assertFalse(checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.canSkipPrevious)
        shadowOf(spotify).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 1_000, 1f, PLAYED_AT).build())
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.canSkipNext)
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun theAppPlayingItIsAskedToGoOnToTheNextSongOrBack() {
        allow()
        val spotify = player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd")
        shadowOf(sessions).addController(spotify)
        // Playing, it goes on playing.
        playback.skip(song, next = true)
        assertEquals(listOf("skipToNext"), asked())
        playback.skip(song, next = false)
        assertEquals(listOf("skipToPrevious"), asked())
        playback.playQueued(song, 3)
        assertEquals(listOf("skipToQueueItem 3"), asked())
        // Paused, the song moved on to is played, or it would wait unheard.
        shadowOf(spotify).setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 1_000, 0f, PLAYED_AT).build())
        playback.skip(song, next = true)
        assertEquals(listOf("skipToNext", "play"), asked())
        playback.skip(song, next = false)
        assertEquals(listOf("skipToPrevious", "play"), asked())
        playback.playQueued(song, 3)
        assertEquals(listOf("skipToQueueItem 3", "play"), asked())
        // Nothing playing it, nothing to ask.
        playback.skip(MusicMetadata("Other", "Band"), next = true)
        playback.playQueued(MusicMetadata("Other", "Band"), 3)
        assertEquals(emptyList<String>(), asked())
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun aQueueTapDoesNotMoveToAnotherSessionWithTheSameTitle() {
        val radio = RadioNotice.PACKAGE
        allow()
        val original = player(radio, "Simple Radio", song.title, song.artist)
        val other = player(radio, "Simple Radio", song.title, song.artist, state = PlaybackState.STATE_PAUSED)
        shadowOf(sessions).addController(original)
        shadowOf(sessions).addController(other)
        val session = requireNotNull(followed().last()).session
        playback.playQueued(song, 3, radio, session)
        assertEquals(listOf("skipToQueueItem 3"), asked())

        shadowOf(original).setPlaybackState(null)
        playback.playQueued(song, 3, radio, session)
        assertTrue(asked().isEmpty())
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun aPlayerControlDoesNotReachAnotherSessionAfterItsOwnLosesState() = runBlocking {
        val radio = RadioNotice.PACKAGE
        allow()
        val original = player(radio, "Simple Radio", song.title, song.artist)
        val other = player(radio, "Simple Radio", song.title, song.artist, state = PlaybackState.STATE_PAUSED)
        shadowOf(sessions).addController(original)
        shadowOf(sessions).addController(other)
        val session = requireNotNull(followed().last()).session

        playback.playPause(song, false, radio, session)
        playback.playPause(song, true, radio, session)
        assertTrue(playback.resume(song, radio, session))
        playback.seekTo(song, 1_000, radio, session)
        playback.skip(song, true, radio, session)
        playback.playApp(radio, session)
        assertEquals(listOf("pause", "play", "play", "seekTo 1000", "skipToNext", "play"), asked())
        playback.shuffle(song, true, radio, session)
        playback.repeat(song, Repeat.ALL, radio, session)
        assertEquals(listOf(radio to true), modes.told)
        assertEquals(listOf(radio to Repeat.ALL), modes.toldRepeat)

        shadowOf(original).setPlaybackState(null)
        playback.playPause(song, false, radio, session)
        playback.playPause(song, true, radio, session)
        playback.seekTo(song, 1_000, radio, session)
        playback.skip(song, true, radio, session)
        playback.playApp(radio, session)
        playback.shuffle(song, false, radio, session)
        playback.repeat(song, Repeat.ONE, radio, session)
        playback.like(song, radio, session)
        assertFalse(playback.resume(song, radio, session))
        assertFalse(playback.bringToFront(song, radio, session))
        assertNull(playback.follow(song, radio, session).first())
        assertTrue(asked().isEmpty())
        assertEquals(listOf(radio to true), modes.told)
        assertEquals(listOf(radio to Repeat.ALL), modes.toldRepeat)
    }

    /** A music app's shuffle and repeat as the app has them, what it's told, and who's watching them. */
    private class FakeModes : AppModes {
        var on: Boolean? = null
        val told = mutableListOf<Pair<String, Boolean>>()
        var repeats: Repeat? = null
        val toldRepeat = mutableListOf<Pair<String, Repeat>>()
        val watching = mutableListOf<() -> Unit>()

        override fun shuffles(app: MediaController) = on

        override fun shuffle(app: MediaController, on: Boolean) {
            told += app.packageName to on
        }

        override fun repeats(app: MediaController) = repeats

        override fun repeat(app: MediaController, how: Repeat) {
            toldRepeat += app.packageName to how
        }

        override fun watch(app: MediaController, handler: android.os.Handler, changed: () -> Unit): () -> Unit {
            watching += changed
            return { watching -= changed }
        }
    }

    @Test
    fun theAppPlayingItSaysWhetherItShufflesAndIsToldTo() {
        allow()
        val spotify = player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd")
        shadowOf(sessions).addController(spotify)
        // One that can't be told to shows nothing of it.
        val seen = followed()
        assertNull(checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.shuffle)
        modes.on = false
        modes.watching.single()()
        assertEquals(false, checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.shuffle)
        // Turned on in the app, it shows at once, though nothing else changed.
        modes.on = true
        modes.watching.single()()
        assertEquals(true, checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.shuffle)

        playback.shuffle(song, on = false)
        assertEquals(listOf("com.spotify.music" to false), modes.told)
        // Nothing playing it, nothing to tell.
        playback.shuffle(MusicMetadata("Other", "Band"), on = true)
        assertEquals(1, modes.told.size)

        // An app no longer followed, or no longer followed at all, is no longer watched.
        shadowOf(sessions).clearControllers()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(modes.watching.isEmpty())
        shadowOf(sessions).addController(spotify)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, modes.watching.size)
        collecting.forEach(Job::cancel)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(modes.watching.isEmpty())
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun theAppPlayingItSaysHowItRepeatsAndIsToldTo() {
        allow()
        val spotify = player("com.spotify.music", "Spotify", "Blinding Lights", "The Weeknd")
        shadowOf(sessions).addController(spotify)
        val seen = followed()
        assertNull(checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.repeat)
        // Changed in the app, it shows at once, though nothing else changed.
        modes.repeats = Repeat.ONE
        modes.watching.single()()
        assertEquals(Repeat.ONE, checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.repeat)

        playback.repeat(song, Repeat.ALL)
        assertEquals(listOf("com.spotify.music" to Repeat.ALL), modes.toldRepeat)
        assertEquals(emptyList<String>(), asked())
        // Nothing playing it, nothing to tell.
        playback.repeat(MusicMetadata("Other", "Band"), Repeat.OFF)
        assertEquals(1, modes.toldRepeat.size)
        // With a repeat button of its own, it's that button that's pressed, as YouTube Music won't be told.
        shadowOf(spotify).setPlaybackState(
            PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 1_000, 1f, PLAYED_AT)
                .addCustomAction(PlaybackState.CustomAction.Builder("loop_mode_action", "Repeat off", 1).build()).build()
        )
        playback.repeat(song, Repeat.ALL)
        assertEquals(1, modes.toldRepeat.size)
        assertEquals(listOf("sendCustomAction loop_mode_action"), asked())
    }

    @Test
    fun whetherASongIsLikedIsToldByHowItsAppDrawsTheLikeInAnyLanguage() {
        allow()
        // As YouTube Music and TIDAL name their drawings.
        val drawings = mapOf(1 to "yt_fill_experimental_thumb_up_vd_theme_24", 2 to "yt_outline_experimental_thumb_up_vd_theme_24", 3 to "ic_favorite_filled", 4 to "ic_favorite", 5 to "thumb_up_selected")
        val drawn = MediaSessionPlayback(app, now = { NOW }, modes = modes, drawingName = { _, id -> drawings[id] })
        val music = player(MusicService.YOUTUBE_MUSIC.packageName, "YouTube Music", "Blinding Lights", "The Weeknd", album = "After Hours")
        fun liked(action: String, icon: Int): Boolean? {
            shadowOf(music).setPlaybackState(
                PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 1_000, 1f, PLAYED_AT)
                    .addCustomAction(PlaybackState.CustomAction.Builder(action, "Gefällt mir", icon).build()).build()
            )
            var seen: Following? = null
            val watching = CoroutineScope(Dispatchers.Unconfined).launch { drawn.follow(song).collect { seen = it } }
            shadowOf(Looper.getMainLooper()).idle()
            watching.cancel()
            return seen?.like?.liked
        }
        shadowOf(sessions).addController(music)
        assertEquals(true, liked("thumbs_up_action", 1))
        assertEquals(false, liked("thumbs_up_action", 2))
        assertEquals(true, liked("com.aspiro.tidal.player.action.TOGGLE_FAVORITE", 3))
        assertEquals(false, liked("com.aspiro.tidal.player.action.TOGGLE_FAVORITE", 4))
        // A drawing it can't read, or none it can find, says nothing, so the like is never pressed blind.
        assertNull(liked("waze.thumbUp", 5))
        assertNull(liked("waze.thumbUp", 6))
        // Without a drawing of its own to go by, an app that isn't on the phone says nothing either.
        shadowOf(music).setPlaybackState(
            PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 1_000, 1f, PLAYED_AT)
                .addCustomAction(PlaybackState.CustomAction.Builder("thumbs_up_action", "Like", 1).build()).build()
        )
        assertEquals(AppAction("thumbs_up_action", "Like", 1), followed().last()?.like)
    }

    @Test
    @Config(shadows = [FixtureResourceNames::class, FixtureResourceNames.Packages::class])
    fun theDefaultDrawingLookupReadsTheMusicAppsResourceName() {
        allow()
        val fixture = MusicMetadata("Fixture Track", "Fixture Artist")
        val controller = player(FixtureResourceNames.MUSIC_PACKAGE, "Fixture", fixture.title, fixture.artist, album = "Fixture Album")
        shadowOf(controller).setPlaybackState(PlaybackState.Builder()
            .setState(PlaybackState.STATE_PLAYING, 1_000, 1f, PLAYED_AT)
            .addCustomAction(PlaybackState.CustomAction.Builder("thumbs_up_action", "Fixture favorite", R.drawable.ic_thumb_up).build()).build())
        shadowOf(sessions).addController(controller)
        FixtureResourceNames.Packages.requestedPackages.clear()
        assertEquals(true, followed(fixture).last()?.like?.liked)
        assertEquals(setOf(FixtureResourceNames.MUSIC_PACKAGE), FixtureResourceNames.Packages.requestedPackages.toSet())
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun theAppsOwnLikeIsFollowedAsItShowsIt() {
        allow()
        val like = { name: String, icon: Int -> PlaybackState.CustomAction.Builder("thumbs_up_action", name, icon).build() }
        val music = player(MusicService.YOUTUBE_MUSIC.packageName, "YouTube Music", "Blinding Lights", "The Weeknd", album = "After Hours")
        fun state(vararg actions: PlaybackState.CustomAction) =
            PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 1_000, 1f, PLAYED_AT).apply { actions.forEach(::addCustomAction) }.build()
        shadowOf(music).setPlaybackState(state(PlaybackState.CustomAction.Builder("shuffle_action", "Shuffle off", 3).build()))
        shadowOf(sessions).addController(music)
        val seen = followed()
        // An app's other buttons aren't its like.
        assertNull(checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.like)
        // As the app names and draws it, which changes once it's liked.
        shadowOf(music).executeOnPlaybackStateChanged(state(like("Like", 1)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AppAction("thumbs_up_action", "Like", 1), checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.like)
        shadowOf(music).executeOnPlaybackStateChanged(state(like("Remove like", 2)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AppAction("thumbs_up_action", "Remove like", 2), checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.like)
        // TIDAL's and Amazon Music's are theirs.
        shadowOf(music).executeOnPlaybackStateChanged(state(PlaybackState.CustomAction.Builder("com.aspiro.tidal.player.action.TOGGLE_FAVORITE", "Add to My Collection", 4).build()))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Add to My Collection", checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.like?.name)
        shadowOf(music).executeOnPlaybackStateChanged(state(PlaybackState.CustomAction.Builder("waze.thumbUp", "Thumbs up on", 5).build()))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Thumbs up on", checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.like?.name)

        // Spotify's is one of two, the second once the song's in the user's collection.
        shadowOf(music).executeOnPlaybackStateChanged(state(PlaybackState.CustomAction.Builder("ADD_TO", "Add to collection", 6).build()))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AppAction("ADD_TO", "Add to collection", 6, liked = false), seen.last()?.like)
        shadowOf(music).executeOnPlaybackStateChanged(state(PlaybackState.CustomAction.Builder("CHECK_FILL", "Remove from collection", 7).build()))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AppAction("CHECK_FILL", "Remove from collection", 7, liked = true), seen.last()?.like)

        // The like it shows now is the one pressed.
        shadowOf(music).setPlaybackState(state(PlaybackState.CustomAction.Builder("shuffle_action", "Shuffle off", 3).build(), like("Like", 1)))
        playback.like(song)
        assertEquals(listOf("sendCustomAction thumbs_up_action"), asked())
        // With none, or no app playing it, nothing is pressed, and no other button is.
        shadowOf(music).setPlaybackState(state(PlaybackState.CustomAction.Builder("shuffle_action", "Shuffle off", 3).build()))
        playback.like(song)
        shadowOf(music).setPlaybackState(state())
        playback.like(song)
        playback.like(MusicMetadata("Other", "Band"))
        assertEquals(emptyList<String>(), asked())
    }

    @Test
    fun whatTheAppPlaysNextIsFollowedAndPlayedAtOnce() {
        allow()
        val music = player(MusicService.YOUTUBE_MUSIC.packageName, "YouTube Music", "Blinding Lights", "The Weeknd", album = "After Hours")
        val at = { id: Long, state: Int -> PlaybackState.Builder().setState(state, 1_000, 1f, PLAYED_AT).setActiveQueueItemId(id).build() }
        shadowOf(music).setPlaybackState(at(2, PlaybackState.STATE_PLAYING))
        shadowOf(sessions).addController(music)
        val seen = followed()
        // No queue, nothing next.
        assertEquals(emptyList<Queued>(), checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.upNext)
        val item = { id: Long, title: String, artist: String?, icon: String? ->
            MediaSession.QueueItem(
                MediaDescription.Builder().setMediaId("$id").setTitle(title).apply { if (artist != null) setSubtitle(artist) }.setIconUri(icon?.let(android.net.Uri::parse)).build(),
                id
            )
        }
        queues[music.packageName] = listOf(
            item(1, "Before", "Band", null),
            item(2, "Blinding Lights", "The Weeknd", null),
            item(3, "Save Your Tears", "The Weeknd", "https://yt3.googleusercontent.com/cover=w60-h60-l90-rj"),
            item(4, "Untitled", null, "content://covers/4")
        )
        // Seen as the app's queue changes, each with its cover, as big as it's shown when the app sizes it.
        shadowOf(music).getCallbacks().forEach { it.onQueueChanged(null) }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            listOf(Queued("Save Your Tears", "The Weeknd", 3, "https://yt3.googleusercontent.com/cover=w180-h180-l90-rj"), Queued("Untitled", "", 4, "content://covers/4")),
            checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.upNext
        )
        // First in its queue, all the rest is next.
        shadowOf(music).executeOnPlaybackStateChanged(at(1, PlaybackState.STATE_PLAYING))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(3L, 4L), checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.upNext.drop(1).map { it.id })
        assertEquals("Blinding Lights", checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.upNext.first().title)
        // Not saying where in it it is, there's no telling what's next.
        shadowOf(music).executeOnPlaybackStateChanged(at(9, PlaybackState.STATE_PLAYING))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<Queued>(), checkNotNull(seen.last()) { "Expected seen.last() to be non-null" }.upNext)

        // Robolectric doesn't pass what's asked on to the app, so this only checks asking is safe, playing or paused; the phone shows the rest.
        playback.playQueued(song, 3)
        shadowOf(music).setPlaybackState(at(2, PlaybackState.STATE_PAUSED))
        playback.playQueued(song, 3)
        playback.playQueued(MusicMetadata("Other", "Band"), 3)
    }

    /**
     * What an app is asked, which Robolectric doesn't pass on to it: every call on its transport controls, in
     * order, with what it was asked. A test that needs them says `@Config(shadows = [AppAsked::class])`.
     */
    @Implements(MediaController.TransportControls::class)
    class AppAsked {
        @Implementation
        fun seekTo(pos: Long) {
            calls += "seekTo $pos"
        }

        @Implementation
        fun play() {
            calls += "play"
        }

        @Implementation
        fun pause() {
            calls += "pause"
        }

        @Implementation
        fun skipToNext() {
            calls += "skipToNext"
        }

        @Implementation
        fun skipToPrevious() {
            calls += "skipToPrevious"
        }

        @Implementation
        fun skipToQueueItem(id: Long) {
            calls += "skipToQueueItem $id"
        }

        @Implementation
        fun sendCustomAction(action: PlaybackState.CustomAction, args: android.os.Bundle?) {
            calls += "sendCustomAction ${action.action}"
        }

        @Implementation
        fun playFromUri(uri: android.net.Uri?, extras: android.os.Bundle?) {
            calls += "playFromUri $uri"
        }

        @Implementation
        fun playFromSearch(query: String?, extras: android.os.Bundle?) {
            val of = { key: String -> extras?.getString(key) }
            calls += "playFromSearch $query | ${of(android.provider.MediaStore.EXTRA_MEDIA_TITLE)} | ${of(android.provider.MediaStore.EXTRA_MEDIA_ARTIST)} | ${of(android.provider.MediaStore.EXTRA_MEDIA_FOCUS)}"
        }

        companion object {
            val calls = mutableListOf<String>()
        }
    }

    /** What the apps were asked since last looked, in order. */
    private fun asked() = AppAsked.calls.toList().also { AppAsked.calls.clear() }

    @Test
    @Config(shadows = [AppAsked::class])
    fun anAppIsAskedForASongByTheNameItsStoreKnowsIt() = runBlocking {
        allow()
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Other", "Band"))
        // Searched as a video calls it, Qobuz lists only tributes (Issue #673).
        assertTrue(playback.playSong(MusicMetadata("Despacito ft. Daddy Yankee", "Luis Fonsi"), "com.spotify.music"))
        assertEquals(listOf("playFromSearch Despacito Luis Fonsi | Despacito ft. Daddy Yankee | Luis Fonsi | vnd.android.cursor.item/audio"), asked())
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
    fun withNoAppToAskTheOneThatPlayedLastIsAskedWhereverItIsListed() = runBlocking {
        allow()
        // The one that played last plays still, so it's started at once; one paused since earlier would never start.
        val last = { player("com.spotify.music", "Spotify", "Other", "Band", at = PLAYED_AT) }
        val earlier = { player("com.example.player", "Player", "Other", "Band", state = PlaybackState.STATE_PAUSED, at = PLAYED_AT - 1) }
        for (listed in listOf(listOf(earlier, last), listOf(last, earlier))) {
            shadowOf(sessions).clearControllers()
            listed.forEach { shadowOf(sessions).addController(it()) }
            assertTrue(playback.playSong(song, null, mustStart = true))
        }
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
        assertEquals(true, seen.last()?.playing)
        // Paused it still plays it, though not now; stopped, it plays nothing.
        shadowOf(youTubeMusic).setPlaybackState(queued(PlaybackState.STATE_PAUSED, 108))
        assertEquals(108L to false, runBlocking { playback.plays(music).first() }?.let { it.queueId to it.playing })
        // Loading, not yet.
        shadowOf(youTubeMusic).setPlaybackState(queued(PlaybackState.STATE_BUFFERING, 108))
        assertEquals(false, runBlocking { playback.plays(music).first() }?.playing)
        shadowOf(youTubeMusic).setPlaybackState(queued(PlaybackState.STATE_STOPPED, 108))
        assertNull(runBlocking { playback.plays(music).first() })
        // What's before it in the queue, by its number; none for the first, or a song not in it.
        shadowOf(youTubeMusic).setPlaybackState(queued(PlaybackState.STATE_PLAYING, 108))
        val item = { id: Long -> MediaSession.QueueItem(MediaDescription.Builder().setMediaId("TRACK::$id").build(), id) }
        queues[music] = listOf(item(155), item(108), item(143))
        // Seen again as the queue changes, as TIDAL puts a song there after it starts playing it.
        shadowOf(youTubeMusic).getCallbacks().forEach { it.onQueueChanged(queues[music]) }
        assertEquals(155L, seen.last()?.previousId)
        shadowOf(youTubeMusic).setPlaybackState(queued(PlaybackState.STATE_PLAYING, 155))
        assertNull(runBlocking { playback.plays(music).first() }?.previousId)
        shadowOf(youTubeMusic).setPlaybackState(queued(PlaybackState.STATE_PLAYING, 9))
        assertNull(runBlocking { playback.plays(music).first() }?.previousId)
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
        assertEquals(AppPlays(MusicMetadata("Self Control", "Laura Branigan"), null, ended = true, playing = false), runBlocking { playback.plays(youTube).first() })
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
    @Config(shadows = [AppAsked::class])
    fun aListIsAskedOfTheAppOrTheAppWokenForIt() = runBlocking {
        val music = MusicService.YOUTUBE_MUSIC.packageName
        val list = "https://music.youtube.com/watch?v=first000000&list=TLGG"
        // Nothing running, and nothing to wake.
        assertFalse(playback.playList(music, list))
        assertEquals(MusicService.YOUTUBE_MUSIC.packageNames, woken.single())
        // Woken, it's asked.
        assertEquals(emptyList<String>(), asked())
        wakes = player(music, "YouTube Music", "Other", "Band")
        assertTrue(playback.playList(music, list))
        // The list's own link, as a list is only played by its link.
        assertEquals(listOf("playFromUri $list"), asked())
        // Running, it's asked as it is.
        woken.clear()
        allow()
        shadowOf(sessions).addController(player(music, "YouTube Music", "Other", "Band"))
        assertTrue(playback.playList(music, list))
        assertEquals(listOf("playFromUri $list"), asked())
        assertTrue(woken.isEmpty())
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun aPlaylistUsesAPatchedAppOnlyWhenTheOfficialAppIsMissing() = runBlocking {
        val official = MusicService.YOUTUBE_MUSIC.packageName
        val patched = "app.morphe.android.apps.youtube.music"
        val link = "https://music.youtube.com/watch?v=fake0000000&list=fake-list"
        allow()
        shadowOf(sessions).addController(player(patched, "Patched Player", "Fake Song", "Fake Artist"))
        assertTrue(playback.playList(official, link))
        assertEquals(listOf("playFromUri $link"), asked())
        assertTrue(woken.isEmpty())
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = official })
        assertFalse(playback.playList(official, link))
        assertTrue(asked().isEmpty())
        assertEquals(listOf(MusicService.YOUTUBE_MUSIC.packageNames), woken)
    }

    @Test
    fun anAppAskedToStartIsTakenToPlayOnlyOnceItSaysSo() = runBlocking {
        val music = MusicService.YOUTUBE_MUSIC.packageName
        val link = "https://music.youtube.com/watch?v=other000000"
        allow()
        val youTubeMusic = player(music, "YouTube Music", "Other", "Band", state = PlaybackState.STATE_PAUSED)
        shadowOf(sessions).addController(youTubeMusic)
        val says = { state: Int -> shadowOf(youTubeMusic).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(state, 0, 1f, PLAYED_AT).build()); shadowOf(Looper.getMainLooper()).idle() }
        // Each failing way is seen by a Crosstune that hasn't learned yet that the app only plays in front.
        val fresh = { MediaSessionPlayback(app, now = { NOW }, waker = { woken += it; wakes }, startMs = 200, modes = modes, queueOf = { queues[it.packageName] }, clockLog = PracticeClockLog(true)) }
        val asks = { by: MediaSessionPlayback -> CoroutineScope(Dispatchers.Unconfined).async { by.playSong(song, music, link, mustStart = true) } }
        // Playing it, it did.
        val plays = asks(playback)
        says(PlaybackState.STATE_PLAYING)
        assertTrue(plays.await())
        // Failing to, as YouTube Music does in the background without Premium, it can't, however soon it says so.
        shadowOf(youTubeMusic).setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0, 1f, PLAYED_AT).build())
        val fails = asks(playback)
        says(PlaybackState.STATE_ERROR)
        assertTrue(fails.isCompleted)
        assertFalse(fails.await())
        // Known to play only in front now, it isn't asked from behind again, nor waited on.
        val known = asks(playback)
        assertTrue(known.isCompleted)
        assertFalse(known.await())
        // Saying nothing, or only that it's loading, it hasn't started in time.
        assertFalse(asks(fresh()).await())
        val loading = asks(fresh())
        says(PlaybackState.STATE_BUFFERING)
        assertFalse(loading.await())
        // With no state at all, it hasn't started either.
        shadowOf(youTubeMusic).setPlaybackState(null)
        assertFalse(asks(fresh()).await())
        shadowOf(youTubeMusic).setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0, 1f, PLAYED_AT).build())
        // Already playing, as after another song or in front, it goes on to this one; not asked to start, it's only asked.
        shadowOf(youTubeMusic).setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f, PLAYED_AT).build())
        assertTrue(playback.playSong(song, music, link, mustStart = true))
        shadowOf(youTubeMusic).setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0, 1f, PLAYED_AT).build())
        assertTrue(playback.playSong(song, music, link))
        // Nothing keeps listening to it once it has answered.
        assertTrue(shadowOf(youTubeMusic).getCallbacks().isEmpty())
        // A list is the same.
        val lists = fresh()
        val list = CoroutineScope(Dispatchers.Unconfined).async { lists.playList(music, link, mustStart = true) }
        says(PlaybackState.STATE_ERROR)
        assertFalse(list.await())
        val played = CoroutineScope(Dispatchers.Unconfined).async { fresh().playList(music, link, mustStart = true) }
        val playing = PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f, PLAYED_AT).build()
        shadowOf(youTubeMusic).setPlaybackState(playing)
        shadowOf(youTubeMusic).executeOnMetadataChanged(checkNotNull(youTubeMusic.metadata))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("A playlist still needs a transport callback", played.isCompleted)
        says(PlaybackState.STATE_PLAYING)
        assertTrue(played.await())
        // It wouldn't play in the background, so opened on the song, it's left in front, where it plays: behind Crosstune it'd stop.
        assertTrue(playback.playsOnlyInFront(music))
        assertTrue(playback.openSong(music, link))
        assertEquals(music, shadowOf(app).nextStartedActivity.`package`)
        playback.comesBack()
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun playFromUriWaitsForTheRequestedVideoEvenIfAnotherVideoStartsFirst() = runTest {
        allow()
        val music = MusicService.YOUTUBE_MUSIC.packageName
        val link = "https://music.youtube.com/watch?v=other000000"
        val controller = player(music, "YouTube Music", song.title, song.artist, state = PlaybackState.STATE_PAUSED,
            art = mapOf(MediaMetadata.METADATA_KEY_MEDIA_ID to "longVersion"))
        shadowOf(sessions).addController(controller)
        val session = shadowOf(controller)
        val confirmed = async(start = CoroutineStart.UNDISPATCHED) { playback.playSong(song, music, link, mustStart = true) }
        val old = checkNotNull(controller.metadata)
        val requested = MediaMetadata.Builder(old).putString(MediaMetadata.METADATA_KEY_MEDIA_URI, link).build()
        session.executeOnMetadataChanged(requested)
        shadowOf(Looper.getMainLooper()).idle()
        testScheduler.runCurrent()
        assertFalse("The right metadata while paused is not a start confirmation", confirmed.isCompleted)
        session.setMetadata(old)
        val playing = PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 5_000, 1f, PLAYED_AT).build()
        session.executeOnPlaybackStateChanged(playing)
        session.executeOnMetadataChanged(old)
        shadowOf(Looper.getMainLooper()).idle()
        testScheduler.runCurrent()
        assertFalse("The old video is not a start confirmation", confirmed.isCompleted)

        // YouTube Music can switch metadata without sending a new transport state.
        session.executeOnMetadataChanged(requested)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(confirmed.await())
        assertTrue(session.getCallbacks().isEmpty())
    }

    @Test
    fun aDifferentVideoThatKeepsPlayingIsNotMistakenForNeedingTheForeground() = runTest {
        allow()
        val music = MusicService.YOUTUBE_MUSIC.packageName
        val link = "https://music.youtube.com/watch?v=other000000"
        val other = player(music, "YouTube Music", song.title, song.artist,
            art = mapOf(MediaMetadata.METADATA_KEY_MEDIA_ID to "longVersion"))
        shadowOf(sessions).addController(other)
        assertFalse(playback.playSong(song, music, link, mustStart = true))
        assertFalse(playback.playsOnlyInFront(music))
        assertTrue(shadowOf(other).getCallbacks().isEmpty())
    }

    @Test
    fun anExactStartWaitsForPlaybackButDoesNotRequireASessionVideoId() = runTest {
        allow()
        val music = MusicService.YOUTUBE_MUSIC.packageName
        val link = "https://music.youtube.com/watch?v=other000000"
        val controller = player(music, "YouTube Music", song.title, song.artist, state = PlaybackState.STATE_PAUSED)
        shadowOf(sessions).addController(controller)
        val session = shadowOf(controller)
        val started = async(start = CoroutineStart.UNDISPATCHED) { playback.playSong(song, music, link, mustStart = true) }
        session.executeOnPlaybackStateChanged(null)
        session.executeOnMetadataChanged(null)
        shadowOf(Looper.getMainLooper()).idle()
        testScheduler.runCurrent()
        assertFalse(started.isCompleted)
        session.executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f, PLAYED_AT).build())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(started.await())
    }

    @Test
    fun aBuildSeenToPlayOnlyInFrontStandsInForTheAppItsBuiltFrom() = runBlocking {
        val official = MusicService.YOUTUBE_MUSIC.packageName
        val morphe = "app.morphe.android.apps.youtube.music"
        allow()
        val build = player(morphe, "YT Music Morphe", "Other", "Band", state = PlaybackState.STATE_PAUSED)
        shadowOf(sessions).addController(build)
        val fails = CoroutineScope(Dispatchers.Unconfined).async { playback.playSong(song, morphe, "https://music.youtube.com/watch?v=other000000", mustStart = true) }
        shadowOf(build).executeOnPlaybackStateChanged(PlaybackState.Builder().setState(PlaybackState.STATE_ERROR, 0, 1f, PLAYED_AT).build())
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(fails.await())
        // YouTube Music asked for while only the build is installed is the build; installed, it's an app of its own, not seen yet.
        assertTrue(playback.playsOnlyInFront(official))
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = official })
        assertFalse(playback.playsOnlyInFront(official))
    }

    @Test
    @Config(shadows = [AppAsked::class])
    fun anAppNotRunningIsWokenInTheBackgroundToBeAsked() = runBlocking {
        val youTubeMusic = MusicService.YOUTUBE_MUSIC.packageName
        val link = "https://music.youtube.com/watch?v=other000000"
        val byName = "playFromSearch Blinding Lights The Weeknd | Blinding Lights | The Weeknd | vnd.android.cursor.item/audio"
        // Even without access, as itself or a build patched from it, whichever is installed.
        wakes = player(youTubeMusic, "YouTube Music", "Other", "Band")
        assertTrue(playback.playSong(song, youTubeMusic, link))
        assertEquals(MusicService.YOUTUBE_MUSIC.packageNames, woken.single())
        // YouTube Music says it plays what's asked for by name, but doesn't: it's given the song's link.
        assertEquals(listOf("playFromUri $link"), asked())
        assertTrue(playback.playSong(song, "com.example.player"))
        assertEquals(listOf("com.example.player"), woken.last())
        // With no link to give, it's asked by name, as YouTube Music is too.
        assertEquals(listOf(byName), asked())
        assertTrue(playback.playSong(song, youTubeMusic))
        assertEquals(listOf(byName), asked())
        // It can't be: with the song's link there, no other app is asked; without, the one that played last.
        wakes = null
        allow()
        shadowOf(sessions).addController(player("com.spotify.music", "Spotify", "Other", "Band"))
        assertFalse(playback.playSong(song, youTubeMusic, link))
        assertEquals(emptyList<String>(), asked())
        assertTrue(playback.playSong(song, youTubeMusic))
        assertEquals(listOf(byName), asked())
        // For a practice card, which follows the app it names alone, no other is asked for it; with none named, the one that played last still is.
        assertFalse(playback.playSong(song, youTubeMusic, orLastPlayed = false))
        assertEquals(emptyList<String>(), asked())
        assertTrue(playback.playSong(song, null, orLastPlayed = false))
        assertEquals(listOf(byName), asked())
        // An app running isn't woken; with no app given, none is.
        woken.clear()
        // Another app than YouTube Music has no use for the link, so it's asked by name.
        assertTrue(playback.playSong(song, "com.spotify.music", link))
        assertEquals(listOf(byName), asked())
        assertTrue(playback.playSong(song, null))
        assertEquals(listOf(byName), asked())
        assertTrue(woken.isEmpty())
    }

    @Test
    fun aBuildChosenByNameIsAskedAndOpenedAsThatBuildAlone() {
        val morphe = "app.morphe.android.apps.youtube.music"
        val revanced = "app.revanced.android.apps.youtube.music"
        val official = MusicService.YOUTUBE_MUSIC.packageName
        val link = "https://music.youtube.com/watch?v=other000000"
        // The official app and another build have a session too, which the chosen one is not.
        wakes = player(morphe, "YT Music Morphe", "Other", "Band", state = PlaybackState.STATE_PAUSED)
        shadowOf(sessions).addController(player(official, "YouTube Music", "Other", "Band"))
        shadowOf(sessions).addController(player(revanced, "YT Music", "Other", "Band"))
        allow()
        assertTrue(runBlocking { playback.playSong(song, morphe, link) })
        assertEquals(listOf(morphe), woken.single())
        val installed = { packageName: String -> packageName == official }
        assertTrue(sameApp(morphe, morphe, installed))
        assertFalse(sameApp(morphe, official, installed))
        assertFalse(sameApp(morphe, revanced, installed))
        // Installed, the official app is only itself; without it, a build stands in for it.
        assertFalse(sameApp(official, morphe, installed))
        assertTrue(sameApp(official, morphe) { false })
        assertFalse(sameApp(null, morphe) { false })
        // Opened in it, though the official app would take the link too.
        shadowOf(app).checkActivities(true)
        for (opener in listOf(official, morphe)) {
            val opens = ComponentName(opener, "$opener.UrlActivity")
            shadowOf(app.packageManager).addActivityIfNotPresent(opens)
            shadowOf(app.packageManager).addIntentFilterForActivity(
                opens, IntentFilter(Intent.ACTION_VIEW).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("https") }
            )
        }
        assertTrue(playback.openSong(morphe, link))
        assertEquals(morphe, shadowOf(app).nextStartedActivity.`package`)
        assertEquals(listOf(morphe to link), MusicService.appLinksIn(morphe, link))
        assertEquals(MusicService.YOUTUBE_MUSIC.appLinks(link), MusicService.appLinksIn(official, link))
        assertEquals(MusicService.YOUTUBE_MUSIC.packageNames, MusicService.appsFor(official))
    }

    @Test
    fun anAppNotRunningIsOpenedOnTheSongAndCrosstuneComesBackOverIt() {
        val youTubeMusic = "com.google.android.apps.youtube.music"
        // Never seen failing to play in the background, it's taken to play there.
        assertFalse(playback.playsOnlyInFront(youTubeMusic))
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

    /** The session number a station carries is checked on its own; these compare what else it says. */
    private fun Unnamed?.sessionless() = this?.copy(session = null)

    private fun WhatPlays?.sessionless() = if (this is WhatPlays.Nameless) WhatPlays.Nameless(playing.copy(session = null)) else this

    private companion object {
        const val NOW = 500_000L
        const val PLAYED_AT = 400_000L
    }
}
