package com.astrovm.crosstune

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Reporting a problem with a song's words, in one tap, to Crosstune's own box for reports. At
 * Robolectric's own screen size: on a tall one it never settles a dialog holding a text field.
 */
@RunWith(RobolectricTestRunner::class)
class LyricsReportTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeSpotify()
    private var controller: ActivityController<MainActivity>? = null
    private val playback = FakePlayback().apply { access = true }
    private val link = "https://music.youtube.com/watch?v=sPmul8b17AU"

    /** Whether the box for reports turns them away, as when it can't be reached. */
    @Volatile
    private var reportsDown = false

    /** Holds a report on its way until it's let through. */
    @Volatile
    private var reportsHeld: CountDownLatch? = null

    @Before
    fun setUp() {
        MainActivity.httpClientFactory = { fake.client() }
        MainActivity.systemDispatcher = Dispatchers.Unconfined
        MainActivity.lookupDispatcher = Dispatchers.Unconfined
        MainActivity.shellFactory = { emptyList() }
        MainActivity.checksForUpdates = { false }
        MainActivity.playbackFactory = { playback }
        MainActivity.milkdropFactory = { NoMilkdrop }
        MainActivity.soundTapFactory = { null }
        MainActivity.lyricsBusyPauseMs = 0
        MainActivity.lookAheadLyrics = false
        prefs().edit().clear().putBoolean("setup_complete", true).putString("default_target", "YOUTUBE_MUSIC").putBoolean("links_in_crosstune", false).putBoolean("play_right_away", false).putBoolean("visuals", false).commit()
        File(app.cacheDir, "lookups.json").delete()
        File(app.cacheDir, "lyrics.json").delete()
        fake.handler = { request ->
            when (request.url.host) {
                "report.crosstune.4st.li" -> {
                    reportsHeld?.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    FakeSpotify.html(request, "", code = if (reportsDown) 503 else 204)
                }
                "lrclib.net" -> FakeSpotify.html(request, """[{"trackName":"Demo","artistName":"Band","duration":60,"syncedLyrics":"[00:00.00] Demo one\n[00:10.00] Demo two"}]""")
                else -> FakeSpotify.html(request, """{"title":"Demo","author_name":"Band"}""")
            }
        }
    }

    @After
    fun tearDown() {
        runCatching { controller?.pause()?.stop()?.destroy() }
        MainActivity.httpClientFactory = ::httpClient
        MainActivity.systemDispatcher = Dispatchers.Default
        MainActivity.lookupDispatcher = Dispatchers.IO
        MainActivity.shellFactory = { listOf(ShizukuShell(), RootShell()) }
        MainActivity.checksForUpdates = ::looksForUpdates
        MainActivity.playbackFactory = ::MediaSessionPlayback
        MainActivity.milkdropFactory = ::NativeMilkdrop
        MainActivity.soundTapFactory = { OutputMixTap.open() }
        MainActivity.lyricsBusyPauseMs = LYRICS_BUSY_PAUSE_MS
        MainActivity.lookAheadLyrics = true
    }

    private fun prefs() = app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun string(id: Int): String = app.getString(id)

    private fun model() = ViewModelProvider(controller!!.get())[MainViewModel::class.java]

    private fun waitUntil(condition: () -> Boolean) =
        composeRule.waitUntil(TIMEOUT_MS) { shadowOf(android.os.Looper.getMainLooper()).idle(); condition() }

    private fun shown(text: String) = composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun click(label: String) {
        composeRule.onAllNodes(hasText(label) or hasContentDescription(label))[0].performClick()
        composeRule.waitForIdle()
    }

    /** The song's words open, YouTube Music paused on them; then its sheet, and the report from its corner. */
    private fun report() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClass(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_SONG, true)
        controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        composeRule.waitForIdle()
        val lyrics = hasText(string(R.string.lyrics_button)) or hasContentDescription(string(R.string.lyrics_button))
        waitUntil { composeRule.onAllNodes(lyrics).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodes(lyrics)[0].performScrollTo().performClick()
        waitUntil { shown("Demo one") }
        playback.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = false), "YouTube Music", canSeek = true)
        composeRule.waitForIdle()
        click(string(R.string.lyrics_about_song))
        click(string(R.string.report_problem))
    }

    /** What the last report sent said. */
    private fun sentReport() = JSONObject(fake.requestBodies[fake.requestedUrls.indexOfLast { it == ReportSender.URL }])

    @Test
    fun aProblemWithTheWordsIsSentInOneTap() {
        report()
        // What goes is said where it's sent.
        assertTrue(shown(string(R.string.report_what_goes)))
        click(string(R.string.report_send))
        waitUntil { !shown(string(R.string.report_what_goes)) }
        assertEquals(string(R.string.report_sent), ShadowToast.getTextOfLatestToast())
        val sent = sentReport()
        assertFalse(sent.has("comment"))
        assertTrue(sent.getString("version").isNotEmpty())
        val song = sent.getJSONObject("song")
        assertEquals("Demo", song.getString("title"))
        assertEquals("YouTube Music", song.getString("app"))
        assertTrue(song.getBoolean("timed"))
        assertFalse(song.getBoolean("synced"))
        assertEquals(60_000L, song.getLong("lengthMs"))
        assertFalse(song.getBoolean("sung"))
        assertFalse(song.has("timing"))
        // Nothing else opened: no email app or GitHub.
        val opened = generateSequence { shadowOf(app).nextStartedActivity }.map { it.action }.toList()
        assertFalse(opened.any { it == Intent.ACTION_SENDTO || it == Intent.ACTION_VIEW })
        assertNull(model().uiState.report)
    }

    @Test
    fun aReportThatDoesntGetThereSaysSoAndGoesOnceItCan() {
        reportsDown = true
        report()
        click(string(R.string.report_send))
        waitUntil { shown(string(R.string.report_failed)) }
        reportsDown = false
        click(string(R.string.report_send))
        waitUntil { !shown(string(R.string.report_what_goes)) }
        assertEquals(2, fake.requestedUrls.count { it == ReportSender.URL })
        // Opened again, it starts fresh, and closed unsent, nothing goes.
        click(string(R.string.report_problem))
        assertFalse(shown(string(R.string.report_failed)))
        click(string(R.string.cancel_button))
        assertFalse(shown(string(R.string.report_what_goes)))
        assertEquals(2, fake.requestedUrls.count { it == ReportSender.URL })
    }

    @Test
    fun aReportOnItsWayShowsItAndCantBeSentTwice() {
        // Off the main thread, as on a phone, so it can be caught on its way.
        MainActivity.lookupDispatcher = Dispatchers.IO
        reportsHeld = CountDownLatch(1)
        report()
        click(string(R.string.report_send))
        val send = composeRule.onNode(hasText(string(R.string.report_send)) and hasClickAction())
        send.assertIsNotEnabled()
        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
        reportsHeld!!.countDown()
        waitUntil { !shown(string(R.string.report_what_goes)) }
        assertEquals(1, fake.requestedUrls.count { it == ReportSender.URL })
    }

    /** projectM, when it can't start. */
    private object NoMilkdrop : Milkdrop {
        override fun open(width: Int, height: Int) = false
        override fun size(width: Int, height: Int) = Unit
        override fun show(preset: String, smooth: Boolean) = false
        override fun hear(samples: ByteArray, count: Int) = Unit
        override fun draw() = Unit
        override fun finish() = Unit
        override fun close() = Unit
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
