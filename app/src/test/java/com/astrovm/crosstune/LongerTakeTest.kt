package com.astrovm.crosstune

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File

/** A correction heard early in a longer take cannot place its unheard ending. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h900dp")
class LongerTakeTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeSpotify()
    private val playback = FakePlayback().apply { access = true }
    private var controller: ActivityController<MainActivity>? = null
    private val song = MusicMetadata("Demo", "Band", lengthMs = 309_000)
    private val lines = listOf(
        LyricLine(122_780, "Dancing through the night"),
        LyricLine(131_730, "Remember every word"),
        LyricLine(234_820, "Driving down the highway"),
        LyricLine(312_670, "Halogen lights shining"),
        LyricLine(322_620, "Cold as the ice")
    )
    private val points = listOf(SyncPoint(122_780, 95_380), SyncPoint(131_730, 104_330))
    private val prefs get() = app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        MainActivity.httpClientFactory = { fake.client() }
        MainActivity.systemDispatcher = Dispatchers.Unconfined
        MainActivity.lookupDispatcher = Dispatchers.Unconfined
        MainActivity.shellFactory = { emptyList() }
        MainActivity.checksForUpdates = { false }
        MainActivity.playbackFactory = { playback }
        MainActivity.soundTapFactory = { null }
        MainActivity.lyricsBusyPauseMs = 0
        MainActivity.lookAheadLyrics = false
        prefs.edit().clear().putBoolean("setup_complete", true).putString("default_target", "YOUTUBE_MUSIC")
            .putBoolean("links_in_crosstune", false).putBoolean("play_right_away", false).putBoolean("visuals", false).commit()
        File(app.cacheDir, "lyrics.json").delete()
        File(app.cacheDir, "lookups.json").delete()
        val synced = lines.joinToString("\n") { "[%02d:%02d.%02d] %s".format(it.timeMs / 60_000, it.timeMs / 1000 % 60, it.timeMs % 1000 / 10, it.text) }
        val words = JSONArray().put(JSONObject().put("trackName", "Demo").put("artistName", "Band").put("duration", 477).put("syncedLyrics", synced)).toString()
        fake.handler = { FakeSpotify.html(it, if (it.url.host == "lrclib.net") words else "{}") }
    }

    @After
    fun tearDown() {
        controller?.destroyAndSettle()
        MainActivity.httpClientFactory = ::httpClient
        MainActivity.systemDispatcher = Dispatchers.Default
        MainActivity.lookupDispatcher = Dispatchers.IO
        MainActivity.shellFactory = { listOf(ShizukuShell(), RootShell()) }
        MainActivity.checksForUpdates = ::looksForUpdates
        MainActivity.playbackFactory = ::MediaSessionPlayback
        MainActivity.soundTapFactory = MainActivity.phoneSound::open
        MainActivity.lyricsBusyPauseMs = LYRICS_BUSY_PAUSE_MS
        MainActivity.lookAheadLyrics = true
    }

    private fun open(metadata: MusicMetadata = song, byHand: Boolean = false, corrections: List<SyncPoint> = points): MainViewModel {
        LyricsSyncStore(prefs).save(metadata, lines, corrections, byHand = byHand)
        controller = Robolectric.buildActivity(MainActivity::class.java, Intent(app, MainActivity::class.java)).setup()
        val model = ViewModelProvider(checkNotNull(controller).get())[MainViewModel::class.java]
        composeRule.runOnIdle { model.chooseSong(metadata) }
        val lyrics = (hasText(app.getString(R.string.lyrics_button)) or hasContentDescription(app.getString(R.string.lyrics_button))) and isEnabled()
        composeRule.waitUntil(10_000) { shadowOf(android.os.Looper.getMainLooper()).idle(); composeRule.onAllNodes(lyrics).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodes(lyrics)[0].performScrollTo().performClick()
        composeRule.waitUntil(10_000) { shadowOf(android.os.Looper.getMainLooper()).idle(); model.uiState.lyricLines.size == lines.size }
        return model
    }

    @Test
    fun anAutomaticMoveDoesNotLightTheLongerTakesUnheardEnding() {
        playback.access = false
        val shown = open().uiState.lyricLines
        assertEquals(95_380L, shown[0].timeMs)
        assertTrue(shown[2].heard)
        assertEquals("The early correction cannot place a line sung across an extra musical stretch", lines[3], shown[3])
        assertEquals(lines[4], shown[4])
        assertEquals(lines.map { it.text }, shown.map { it.text })
        assertFalse(SyncedLyrics.indexAt(shown, 285_270, false) == 3)
        assertFalse(SyncedLyrics.indexAt(shown, 295_220, false) == 4)
    }

    private fun recordingLengthArrives(metadata: MusicMetadata, byHand: Boolean = false): List<LyricLine> {
        val model = open(metadata, byHand)
        assertEquals(285_270L, model.uiState.lyricLines[3].timeMs)
        playback.playing.value = Following(PlaybackClock(285_270, SystemClock.elapsedRealtime(), playing = false), "YouTube Music", canSeek = true, durationMs = 309_000)
        composeRule.waitUntil(10_000) { shadowOf(android.os.Looper.getMainLooper()).idle(); model.uiState.following?.durationMs == 309_000L }
        return model.uiState.lyricLines
    }

    @Test
    fun thePlayingRecordingCanSupplyTheLengthAfterTheWordsOpen() {
        val shown = recordingLengthArrives(song.copy(lengthMs = null))
        assertEquals(lines[3], shown[3])
        assertEquals(lines[4], shown[4])
    }

    @Test
    fun thePlayingRecordingLengthTakesPrecedenceOverTheNamedRelease() {
        val shown = recordingLengthArrives(song.copy(lengthMs = 477_000))
        assertEquals(lines[3], shown[3])
    }

    @Test
    fun thePlayingRecordingDoesNotLimitAMoveByHand() {
        val shown = recordingLengthArrives(song.copy(lengthMs = null), byHand = true)
        assertEquals(285_270L, shown[3].timeMs)
    }

    @Test
    fun aSheetWithNoCorrectionStaysAsFoundWhenTheRecordingLengthArrives() {
        val model = open(metadata = song.copy(lengthMs = null), corrections = emptyList())
        playback.playing.value = Following(PlaybackClock(285_270, SystemClock.elapsedRealtime(), playing = false), "YouTube Music", canSeek = true, durationMs = 309_000)
        composeRule.waitUntil(10_000) { shadowOf(android.os.Looper.getMainLooper()).idle(); model.uiState.following?.durationMs == 309_000L }
        assertEquals(lines, model.uiState.lyricLines)
    }

    @Test
    fun aMoveByHandStillPlacesTheWholeSheet() {
        playback.access = false
        val shown = open(byHand = true).uiState.lyricLines
        assertEquals(lines.map { it.timeMs - 27_400 }, shown.map { it.timeMs })
        assertEquals(3, SyncedLyrics.indexAt(shown, 285_270, false))
    }
}
