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
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
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

    /** What the box for reports answers each report it's sent, by what it says, when not what [reportsDown] makes it. */
    @Volatile
    private var reportAnswer: ((String) -> Int)? = null

    /** Holds a report on its way until it's let through. */
    @Volatile
    private var reportsHeld: CountDownLatch? = null

    @Before
    fun setUp() {
        TimingLog.clear()
        MainActivity.httpClientFactory = { fake.client() }
        MainActivity.systemDispatcher = Dispatchers.Unconfined
        MainActivity.lookupDispatcher = Dispatchers.Unconfined
        MainActivity.shellFactory = { emptyList() }
        MainActivity.checksForUpdates = { false }
        MainActivity.playbackFactory = { playback }
        MainActivity.milkdropFactory = { NoMilkdrop }
        MainActivity.soundTapFactory = { null }
        MainActivity.lyricsBusyPauseMs = 0
        MainActivity.reportPaceMs = 0
        MainActivity.lookAheadLyrics = false
        prefs().edit().clear().putBoolean("setup_complete", true).putString("default_target", "YOUTUBE_MUSIC").putBoolean("links_in_crosstune", false).putBoolean("play_right_away", false).putBoolean("visuals", false).commit()
        File(app.cacheDir, "lookups.json").delete()
        File(app.cacheDir, "lyrics.json").delete()
        File(app.cacheDir, "kept_words.json").delete()
        fake.handler = { request ->
            when (request.url.host) {
                "report.crosstune.cc" -> {
                    reportsHeld?.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    val body = okio.Buffer().also { request.body?.writeTo(it) }.readUtf8()
                    FakeSpotify.html(request, "", code = reportAnswer?.invoke(body) ?: if (reportsDown) 503 else 204)
                }
                "lrclib.net" -> FakeSpotify.html(request, """[{"trackName":"Demo","artistName":"Band","duration":60,"syncedLyrics":"[00:00.00] Demo one\n[00:10.00] Demo two"}]""")
                else -> FakeSpotify.html(request, """{"title":"Demo","author_name":"Band"}""")
            }
        }
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
        MainActivity.milkdropFactory = ::NativeMilkdrop
        MainActivity.soundTapFactory = MainActivity.phoneSound::open
        MainActivity.lyricsBusyPauseMs = LYRICS_BUSY_PAUSE_MS
        MainActivity.reportPaceMs = MainViewModel.REPORT_PACE_MS
        MainActivity.lookAheadLyrics = true
    }

    private fun prefs() = app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun string(id: Int): String = app.getString(id)

    private fun model() = ViewModelProvider(controller!!.get())[MainViewModel::class.java]

    private fun waitUntil(condition: () -> Boolean) =
        composeRule.waitUntil(TIMEOUT_MS) { shadowOf(android.os.Looper.getMainLooper()).idle(); condition() }

    /** Waits for the words' lookup to end: the problems with them are found in it, so one not sent by then never is. */
    private fun settled() = waitUntil { !model().findingWords }

    private fun shown(text: String) = composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun click(label: String) {
        composeRule.onAllNodes(hasText(label) or hasContentDescription(label))[0].performClick()
        composeRule.waitForIdle()
    }

    /** The song's words open, YouTube Music paused on them; then its sheet, and the report from its corner. */
    private fun report() {
        openLyrics()
        click(string(R.string.lyrics_about_song))
        click(string(R.string.report_problem))
    }

    /** The song's words open, YouTube Music paused on them, once they're [shown]. */
    private fun openLyrics(shown: () -> Boolean = { shown("Demo one") }) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClass(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_SONG, true)
        controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        composeRule.waitForIdle()
        val lyrics = hasText(string(R.string.lyrics_button)) or hasContentDescription(string(R.string.lyrics_button))
        waitUntil { composeRule.onAllNodes(lyrics).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodes(lyrics)[0].performScrollTo().performClick()
        waitUntil(shown)
        playback.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = false), "YouTube Music", canSeek = true)
        composeRule.waitForIdle()
    }

    /** LRCLIB answers with [answer] for the song's words. */
    private fun lyricsAre(answer: String) {
        val others = fake.handler
        fake.handler = { request -> if (request.url.host == "lrclib.net") FakeSpotify.html(request, answer) else others(request) }
    }

    /** What each report sent said. */
    private fun sentReports() = fake.requestedUrls.indices.filter { fake.requestedUrls[it] == ReportSender.URL }.map { JSONObject(fake.requestBodies[it]) }

    private fun problems() = sentReports().map { it.optString("problem") }

    private val offer get() = string(R.string.auto_report_offer)

    /** How many timing reports are owed, kept to be sent once the words are left, or at the next start. */
    private fun owed() = org.json.JSONArray(prefs().getString("auto_reports_owed", "[]")).length()

    /** The offer's Yes, once it takes taps, as it takes none as it comes in. */
    private fun yes() {
        composeRule.mainClock.advanceTimeBy(OFFER_ARMED_MS)
        click(string(R.string.auto_report_yes))
    }

    /** The offer's close, once it takes taps. */
    private fun notNow() {
        composeRule.mainClock.advanceTimeBy(OFFER_ARMED_MS)
        click(string(R.string.auto_report_not_now))
    }

    /** What the box for reports was sent, as the app that is started anew finds it. */
    private fun startedAnew() {
        controller!!.destroyAndSettle()
        // Another process, which isn't the one that asked.
        prefs().edit().putLong("auto_reports_asked_in", -7L).commit()
    }

    /** What the last report sent said. */
    private val LOCAL_BOX = "http://127.0.0.1:8787/reports"

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
        assertEquals("lrclib", song.getString("source"))
        assertFalse(song.getBoolean("sung"))
        assertFalse(song.has("timing"))
        // Nothing else opened: no email app or GitHub.
        val opened = generateSequence { shadowOf(app).nextStartedActivity }.map { it.action }.toList()
        assertFalse(opened.any { it == Intent.ACTION_SENDTO || it == Intent.ACTION_VIEW })
        assertNull(model().uiState.report)
    }

    @Test
    fun aTestBuildSendsToTheLocalAddressItsToldAndTheAppPeopleInstallNever() {
        val flags = app.applicationInfo.flags
        org.robolectric.shadows.ShadowSystemProperties.override("debug.crosstune.report_url", LOCAL_BOX)
        try {
            app.applicationInfo.flags = flags or android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
            report()
            click(string(R.string.report_send))
            waitUntil { !shown(string(R.string.report_what_goes)) }
            assertEquals(listOf(LOCAL_BOX), fake.requestedUrls.filter { "/reports" in it })
            controller?.destroyAndSettle()
            controller = null
            app.applicationInfo.flags = flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE.inv()
            report()
            click(string(R.string.report_send))
            waitUntil { !shown(string(R.string.report_what_goes)) }
            assertEquals(listOf(LOCAL_BOX, ReportSender.URL), fake.requestedUrls.filter { "/reports" in it })
        } finally {
            app.applicationInfo.flags = flags
            org.robolectric.shadows.ShadowSystemProperties.override("debug.crosstune.report_url", "")
        }
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

    @Test
    fun aSongWithNoWordsOffersToReportProblemsLikeItAndYesSendsThisOne() {
        lyricsAre("[]")
        openLyrics { composeRule.onAllNodes(hasContentDescription(string(R.string.lyrics_none))).fetchSemanticsNodes().isNotEmpty() }
        waitUntil { shown(offer) }
        // Nothing goes before the user says so.
        assertEquals(emptyList<String>(), problems())
        yes()
        waitUntil { problems() == listOf("no_lyrics") }
        assertFalse(shown(offer))
        val song = sentReports().single()
        assertEquals("Demo", song.getJSONObject("song").getString("title"))
        assertFalse(song.has("comment"))
        assertEquals(true, model().uiState.autoReports)
        assertTrue(prefs().getBoolean("auto_reports", false))
    }

    @Test
    fun aSongWithNoWordsSaysWhichAppPlaysIt() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        lyricsAre("[]")
        // Playing from the start: it's found to have no words before it's followed.
        playback.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = true), "YouTube Music", canSeek = true)
        openLyrics { composeRule.onAllNodes(hasContentDescription(string(R.string.lyrics_none))).fetchSemanticsNodes().isNotEmpty() }
        waitUntil { problems() == listOf("no_lyrics") }
        assertEquals("YouTube Music", sentReport().getJSONObject("song").getString("app"))
    }

    @Test
    fun aSongNoAppPlaysNamesNone() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        lyricsAre("[]")
        openLyrics { composeRule.onAllNodes(hasContentDescription(string(R.string.lyrics_none))).fetchSemanticsNodes().isNotEmpty() }
        waitUntil { problems() == listOf("no_lyrics") }
        assertFalse(sentReport().getJSONObject("song").has("app"))
    }

    @Test
    fun withoutNotificationAccessNoAppIsAskedAfter() {
        playback.access = false
        prefs().edit().putBoolean("auto_reports", true).commit()
        lyricsAre("[]")
        playback.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = true), "YouTube Music", canSeek = true)
        openLyrics { composeRule.onAllNodes(hasContentDescription(string(R.string.lyrics_none))).fetchSemanticsNodes().isNotEmpty() }
        waitUntil { problems() == listOf("no_lyrics") }
        assertFalse(sentReport().getJSONObject("song").has("app"))
    }

    @Test
    fun wordsLeftAfterTheAppWentOnStillSayWhichItWas() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(500) }
        // On to the next song, which is why the words are left: it no longer plays this one.
        playback.playing.value = null
        composeRule.waitForIdle()
        composeRule.runOnIdle { model().dismissLyrics() }
        waitUntil { problems() == listOf("timed_by_hand") }
        assertEquals("YouTube Music", sentReport().getJSONObject("song").getString("app"))
    }

    @Test
    fun wordsTimedBeforeAnyAppWasFollowedAskForItWhenReportedAndSayHowItPlays() {
        // Without access nothing was followed while the words were put in time, so the report finds the app itself.
        playback.access = false
        prefs().edit().putBoolean("auto_reports", true).commit()
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(500) }
        playback.access = true
        playback.playing.value = Following(
            PlaybackClock(0, SystemClock.elapsedRealtime(), speed = 1.25f, playing = false), "KDE Connect", canSeek = true, appPackage = "org.kde.kdeconnect_tp"
        )
        composeRule.runOnIdle { model().dismissLyrics() }
        waitUntil { problems() == listOf("timed_by_hand") }
        val song = sentReport().getJSONObject("song")
        assertEquals("KDE Connect", song.getString("app"))
        assertEquals("org.kde.kdeconnect_tp", song.getString("appPackage"))
        assertEquals(1.25, song.getDouble("speed"), 0.0)
        assertTrue(song.getString("log").contains("lead unavailable: no player followed"))
    }

    @Test
    fun uncalibratedVideoSaysWhyNoLeadWasUsed() {
        openLyrics()
        playback.playing.value = Following(
            PlaybackClock(0, SystemClock.elapsedRealtime(), playing = false), "Video", canSeek = true, video = true
        )
        composeRule.waitForIdle()
        composeRule.runOnIdle { model().nudgeSync(500) }
        composeRule.runOnIdle { model().sendReport("") }
        waitUntil { sentReports().isNotEmpty() }
        val song = sentReport().getJSONObject("song")
        assertFalse(song.has("leadMs"))
        assertTrue(song.getString("log").contains("lead unavailable: video not calibrated"))
    }

    @Test
    fun timingReportKeepsThePlayerAndLeadFromTheLastCorrection() {
        openLyrics()
        followedInKdeConnect(1.25f)
        composeRule.runOnIdle { model().nudgeSync(500) }
        playback.playing.value = Following(
            PlaybackClock(0, SystemClock.elapsedRealtime(), playing = false), "Video", canSeek = true, appPackage = "example.video", video = true
        )
        composeRule.waitForIdle()
        composeRule.runOnIdle { model().sendReport("") }
        waitUntil { sentReports().isNotEmpty() }
        val song = sentReport().getJSONObject("song")
        assertEquals("KDE Connect", song.getString("app"))
        assertEquals("org.kde.kdeconnect_tp", song.getString("appPackage"))
        assertEquals(1.25, song.getDouble("speed"), 0.0)
        assertEquals(0L, song.getLong("leadMs"))
    }

    @Test
    fun aLearnedLeadIsReportedForAudioAndVideoPlayers() {
        openLyrics()
        val song = model().uiState.lyricsFor ?: error("No song")
        prefs().edit().putString("video_timing", JSONObject().put(RecordingKey.of(song), 2_000).toString()).commit()
        listOf(false, true).forEach { video ->
            playback.playing.value = Following(
                PlaybackClock(10_000, SystemClock.elapsedRealtime(), playing = false), "Player", canSeek = true,
                video = video, durationMs = 70_000
            )
            composeRule.waitForIdle()
            composeRule.runOnIdle { model().nudgeSync(500) }
            composeRule.runOnIdle { model().sendReport("") }
            waitUntil { sentReports().size == if (video) 2 else 1 }
            assertEquals(2_000L, sentReport().getJSONObject("song").getLong("leadMs"))
        }
    }

    /** Followed in KDE Connect, which says the song plays at [speed], the words open. */
    private fun followedInKdeConnect(speed: Float) {
        playback.playing.value = Following(
            PlaybackClock(0, SystemClock.elapsedRealtime(), speed = speed, playing = false), "KDE Connect", canSeek = true, appPackage = "org.kde.kdeconnect_tp"
        )
        composeRule.waitForIdle()
    }

    @Test
    fun wordsPutInTimeByHandSayTheAppFollowedItsSpeedTheLeadAndTheTimingLog() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        openLyrics()
        followedInKdeConnect(1.25f)
        composeRule.runOnIdle { model().nudgeSync(500) }
        // What happens after the last line moved isn't what the report is about.
        composeRule.runOnIdle { TimingLog.note("a moment later") }
        // On to the next song, which is why the words are left: it no longer plays this one.
        playback.playing.value = null
        composeRule.waitForIdle()
        composeRule.runOnIdle { model().dismissLyrics() }
        waitUntil { problems() == listOf("timed_by_hand") }
        val song = sentReport().getJSONObject("song")
        assertEquals("KDE Connect", song.getString("app"))
        assertEquals("org.kde.kdeconnect_tp", song.getString("appPackage"))
        assertEquals(1.25, song.getDouble("speed"), 0.0)
        assertEquals(LINES_EARLY_MS, song.getLong("earlyMs"))
        // An audio player without a learned lead is followed on its own clock.
        assertEquals(0L, song.getLong("leadMs"))
        val log = song.getString("log")
        // Stamped with the phone's clock, which isn't at nothing.
        assertTrue(log, Regex("""(?m)^[1-9]\d* lines in time 0>500""").containsMatchIn(log))
        // Which line was lit, when, and where the player's clock had the song then.
        assertTrue(log, Regex("""lit -?\d+ at \d+ pos \d+""").containsMatchIn(log))
        assertFalse(log, "a moment later" in log)
        // Only positions, times and speeds: nothing of the song or the user.
        assertFalse(log, listOf("Demo", "Band", "KDE", "kdeconnect").any { it in log })
    }

    @Test
    fun wordsMovedByHandAreLoggedAtTheLastLineMovedEvenWhenTheUserAsksToReportLater() {
        openLyrics()
        followedInKdeConnect(1f)
        composeRule.runOnIdle { model().nudgeSync(300) }
        composeRule.runOnIdle { TimingLog.note("a moment later") }
        composeRule.runOnIdle { model().sendReport("Still late") }
        waitUntil { sentReports().isNotEmpty() }
        val song = sentReport().getJSONObject("song")
        assertEquals("org.kde.kdeconnect_tp", song.getString("appPackage"))
        assertFalse(song.getString("log").contains("a moment later"))
        assertTrue(song.getString("log").contains("lines in time 0>300"))
    }

    @Test
    fun aProblemThatIsNotAboutTimingSaysNothingOfIt() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        lyricsAre("[]")
        playback.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = true), "KDE Connect", canSeek = true, appPackage = "org.kde.kdeconnect_tp")
        openLyrics { composeRule.onAllNodes(hasContentDescription(string(R.string.lyrics_none))).fetchSemanticsNodes().isNotEmpty() }
        waitUntil { problems() == listOf("no_lyrics") }
        val song = sentReport().getJSONObject("song")
        assertEquals("KDE Connect", song.getString("app"))
        listOf("appPackage", "speed", "leadMs", "earlyMs", "log").forEach { assertFalse(it, song.has(it)) }
    }

    @Test
    fun aReportOnWordsNobodyTimedByHandSaysNothingOfTiming() {
        report()
        click(string(R.string.report_send))
        waitUntil { sentReports().isNotEmpty() }
        val song = sentReport().getJSONObject("song")
        listOf("appPackage", "speed", "leadMs", "earlyMs", "log").forEach { assertFalse(it, song.has(it)) }
    }

    @Test
    fun theAppOfTheWordsLeftIsNotNamedForTheNextOnes() {
        openLyrics()
        playback.playing.value = null
        composeRule.runOnIdle { model().dismissLyrics() }
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasText(string(R.string.lyrics_button)) or hasContentDescription(string(R.string.lyrics_button)))[0].performScrollTo().performClick()
        waitUntil { shown("Demo one") }
        composeRule.runOnIdle { model().sendReport("") }
        waitUntil { sentReports().isNotEmpty() }
        assertFalse(sentReport().getJSONObject("song").has("app"))
    }

    @Test
    fun anInstrumentalHasNoWordsToReport() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        lyricsAre("""[{"trackName":"Demo","artistName":"Band","instrumental":true}]""")
        openLyrics { composeRule.onAllNodes(hasContentDescription(string(R.string.lyrics_instrumental))).fetchSemanticsNodes().isNotEmpty() }
        settled()
        assertEquals(emptyList<String>(), problems())
        // Nor is it offered, while the user hasn't said.
        controller!!.destroyAndSettle()
        prefs().edit().remove("auto_reports").commit()
        openLyrics { composeRule.onAllNodes(hasContentDescription(string(R.string.lyrics_instrumental))).fetchSemanticsNodes().isNotEmpty() }
        settled()
        assertFalse(shown(offer))
    }

    @Test
    fun saidNoInSettingsItNeverAsksAgainNorSends() {
        prefs().edit().putBoolean("auto_reports", false).commit()
        lyricsAre("[]")
        openLyrics { composeRule.onAllNodes(hasContentDescription(string(R.string.lyrics_none))).fetchSemanticsNodes().isNotEmpty() }
        settled()
        assertFalse(shown(offer))
        assertEquals(false, model().uiState.autoReports)
        // Another song with a problem, words with no timing this time.
        controller!!.destroyAndSettle()
        File(app.cacheDir, "lyrics.json").delete()
        File(app.cacheDir, "kept_words.json").delete()
        lyricsAre("""[{"trackName":"Demo","artistName":"Band","duration":60,"plainLyrics":"Plain one\nPlain two"}]""")
        openLyrics { shown("Plain one") }
        settled()
        assertFalse(shown(offer))
        assertEquals(emptyList<String>(), problems())
    }

    @Test
    fun allowedItReportsWordsWithNoTimingWithoutAsking() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        lyricsAre("""[{"trackName":"Demo","artistName":"Band","duration":60,"plainLyrics":"Plain one\nPlain two"}]""")
        openLyrics { shown("Plain one") }
        waitUntil { problems() == listOf("no_timing") }
        assertFalse(shown(offer))
        assertFalse(sentReports().single().getJSONObject("song").getBoolean("timed"))
        // Once a song: shown again, it doesn't go again.
        controller!!.destroyAndSettle()
        File(app.cacheDir, "lyrics.json").delete()
        File(app.cacheDir, "kept_words.json").delete()
        openLyrics { shown("Plain one") }
        settled()
        assertEquals(listOf("no_timing"), problems())
    }

    @Test
    fun aCoversUntimedWordsTakenFromTheOriginalArentReportedAsUntimed() {
        // Auto report #23: a karaoke track with no words of its own shows its original's, untimed, as a cover's are sung in its own time.
        prefs().edit().putBoolean("auto_reports", true).commit()
        val original = """[{"trackName":"Demo","artistName":"Band","duration":60,"syncedLyrics":"[00:00.00] Demo one\n[00:10.00] Demo two"}]"""
        val others = fake.handler
        fake.handler = { request ->
            when {
                request.url.host == "lrclib.net" -> FakeSpotify.html(request, if ("Karaoke" in request.url.toString()) "[]" else original)
                request.url.host == "report.crosstune.cc" -> others(request)
                else -> FakeSpotify.html(request, """{"title":"Demo (In the Style of Band) [Karaoke Version]","author_name":"Karaoke Hits"}""")
            }
        }
        openLyrics { shown("Demo one") }
        settled()
        assertEquals(emptyList<String>(), problems())
    }

    @Test
    fun wordsPutInTimeByHandAreOfferedThenSentAsTheyreLeft() {
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(500) }
        waitUntil { shown(offer) }
        yes()
        // Not yet: they may be put in time some more.
        assertEquals(emptyList<String>(), problems())
        composeRule.runOnIdle { model().nudgeSync(500) }
        composeRule.runOnIdle { model().dismissLyrics() }
        waitUntil { problems() == listOf("timed_by_hand") }
        val timing = sentReports().single().getJSONObject("song").getJSONArray("timing").getJSONObject(0)
        assertEquals(1_000L, timing.getLong("heardMs") - timing.getLong("timedMs"))
    }

    /** The app opened on words found with no timing, and closed: a problem it found and reports by itself. */
    private fun foundNoTiming() {
        lyricsAre("""[{"trackName":"Demo","artistName":"Band","duration":60,"plainLyrics":"Plain one\nPlain two"}]""")
        openLyrics { shown("Plain one") }
        waitUntil { problems() == listOf("no_timing") }
        controller!!.destroyAndSettle()
    }

    @Test
    fun aProblemFoundThatDidntGetThereIsKeptAndGoesAtTheNextStartWithoutTheSongAgain() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        reportsDown = true
        foundNoTiming()
        assertEquals(1, owed())
        reportsDown = false
        started()
        waitUntil { owed() == 0 }
        assertEquals(listOf("no_timing", "no_timing"), problems())
        // Sent, it's reported, so finding it again sends nothing.
        controller!!.destroyAndSettle()
        File(app.cacheDir, "lyrics.json").delete()
        File(app.cacheDir, "kept_words.json").delete()
        openLyrics { shown("Plain one") }
        settled()
        assertEquals(listOf("no_timing", "no_timing"), problems())
    }

    @Test
    fun aProblemFoundTheBoxTurnsAwayForTodayIsKeptToo() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        reportAnswer = { 429 }
        foundNoTiming()
        assertEquals(1, owed())
        reportAnswer = null
        started()
        waitUntil { owed() == 0 }
        assertEquals(listOf("no_timing", "no_timing"), problems())
    }

    @Test
    fun aProblemFoundTheBoxWillNeverTakeIsDroppedAndNotSentAgain() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        reportAnswer = { 400 }
        foundNoTiming()
        assertEquals(0, owed())
        reportAnswer = null
        File(app.cacheDir, "lyrics.json").delete()
        File(app.cacheDir, "kept_words.json").delete()
        openLyrics { shown("Plain one") }
        settled()
        assertEquals(listOf("no_timing"), problems())
    }

    @Test
    fun aLineTappedIntoTimeOffersTooAndLeftUnansweredNothingGoes() {
        openLyrics()
        composeRule.runOnIdle { model().syncLine(1) }
        waitUntil { shown(offer) }
        composeRule.runOnIdle { model().dismissLyrics() }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), problems())
        assertEquals(false, model().uiState.reportOffer)
    }

    @Test
    fun severalProblemsInOneSongAreOfferedOnceAndYesSendsEveryOneFound() {
        prefs().edit().putBoolean("lyrics_chords", true).commit()
        openLyrics()
        waitUntil { shown(offer) }
        settled()
        // Chords weren't found; then the words are put in time by hand, which is another problem with the same song.
        waitUntil { model().uiState.chords.loading.not() }
        composeRule.runOnIdle { model().nudgeSync(500) }
        composeRule.waitForIdle()
        assertEquals(1, composeRule.onAllNodes(hasText(offer)).fetchSemanticsNodes().size)
        yes()
        // The chords' one goes now; the timing one once the words are left, so the user can go on with them.
        waitUntil { problems() == listOf("no_chords") }
        composeRule.runOnIdle { model().dismissLyrics() }
        waitUntil { problems() == listOf("no_chords", "timed_by_hand") }
    }

    @Test
    fun theOfferClosedIsNotANoAndIsNotMadeAgainInTheSameSessionButIsInTheNext() {
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(500) }
        waitUntil { shown(offer) }
        notNow()
        assertFalse(shown(offer))
        // Not a no: nothing saved as the answer, nothing sent, the switch in Settings as it was.
        assertFalse(prefs().contains("auto_reports"))
        assertNull(model().uiState.autoReports)
        assertEquals(1, prefs().getInt("auto_reports_closes", 0))
        assertEquals(emptyList<String>(), problems())
        // Another problem with the same song, in the same session: not asked again.
        composeRule.runOnIdle { model().nudgeSync(500) }
        composeRule.waitForIdle()
        assertFalse(shown(offer))
        composeRule.runOnIdle { model().dismissLyrics() }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), problems())
        // The app started anew asks again.
        startedAnew()
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(500) }
        waitUntil { shown(offer) }
    }

    @Test
    fun theThirdCloseOfTheOfferIsANoForGoodAndSettingsStillWins() {
        repeat(3) {
            openLyrics()
            composeRule.runOnIdle { model().nudgeSync(500) }
            waitUntil { shown(offer) }
            notNow()
            startedAnew()
        }
        assertEquals(false, prefs().getBoolean("auto_reports", true))
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(500) }
        composeRule.waitForIdle()
        assertFalse(shown(offer))
        assertEquals(false, model().uiState.autoReports)
        composeRule.runOnIdle { model().dismissLyrics() }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), problems())
        // Turned on in Settings, it's on.
        composeRule.runOnIdle { model().allowAutoReports(true) }
        assertEquals(true, model().uiState.autoReports)
        assertEquals(true, prefs().getBoolean("auto_reports", false))
    }

    @Test
    fun theThirdCloseOfTheOfferStillLeavesTheTimingReportOwedForSettings() {
        // The third close is a no, but what was put in time stays on the phone, owed.
        prefs().edit().putInt("auto_reports_closes", 2).commit()
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(500) }
        waitUntil { shown(offer) }
        notNow()
        assertEquals(false, model().uiState.autoReports)
        composeRule.runOnIdle { model().dismissLyrics() }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), problems())
        assertEquals(1, owed())
        // Turned on in Settings, it goes, though the words are no longer there.
        composeRule.runOnIdle { model().allowAutoReports(true) }
        waitUntil { problems() == listOf("timed_by_hand") }
        waitUntil { owed() == 0 }
    }

    @Test
    fun aTimingReportOwedSurvivesTheAppBeingKilledAndGoesAtTheNextStartOnce() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(500) }
        composeRule.waitForIdle()
        // Not sent while the words are still in front of the user, who may put them in time some more; but owed.
        assertEquals(emptyList<String>(), problems())
        assertEquals(1, owed())
        // Killed before they were left.
        controller!!.destroyAndSettle()
        assertEquals(emptyList<String>(), problems())
        openLyrics()
        waitUntil { problems() == listOf("timed_by_hand") }
        val sent = sentReports().single()
        assertEquals("Demo", sent.getJSONObject("song").getString("title"))
        val timing = sent.getJSONObject("song").getJSONArray("timing").getJSONObject(0)
        assertEquals(500L, timing.getLong("heardMs") - timing.getLong("timedMs"))
        waitUntil { owed() == 0 }
        // Once a song: put in time again and left, it doesn't go again.
        composeRule.runOnIdle { model().nudgeSync(500) }
        composeRule.runOnIdle { model().dismissLyrics() }
        composeRule.waitForIdle()
        assertEquals(listOf("timed_by_hand"), problems())
    }

    @Test
    fun aYesAfterTheWordsWerePutInTimeByHandOwesTheReportToo() {
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(500) }
        waitUntil { shown(offer) }
        // Owed from the moment they were put in time, whatever the answer.
        assertEquals(1, owed())
        yes()
        assertEquals(1, owed())
        controller!!.destroyAndSettle()
        openLyrics()
        waitUntil { problems() == listOf("timed_by_hand") }
    }

    @Test
    fun aTimingReportOwedThatDidntGetThereGoesAtALaterStart() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(500) }
        controller!!.destroyAndSettle()
        reportsDown = true
        openLyrics()
        waitUntil { problems() == listOf("timed_by_hand") }
        assertEquals(1, owed())
        controller!!.destroyAndSettle()
        reportsDown = false
        openLyrics()
        waitUntil { problems() == listOf("timed_by_hand", "timed_by_hand") }
        waitUntil { owed() == 0 }
    }

    /** [songs] put in time before, whose reports wait on the phone: made up, as what they say doesn't matter. */
    private fun owedFor(vararg songs: String) = songs.forEach { AutoReports(prefs()).owe(MusicMetadata(it, "Band"), SongProblem.TIMED_BY_HAND, """{"fake":"$it"}""") }

    private fun sentFakes() = fake.requestedUrls.indices.filter { fake.requestedUrls[it] == ReportSender.URL }.map { fake.requestBodies[it] }

    @Test
    fun noTimingReportIsOwedForWordsPutBackAsTheyWere() {
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(500) }
        assertEquals(1, owed())
        composeRule.runOnIdle { model().resetSync() }
        assertEquals(0, owed())
        composeRule.runOnIdle { model().nudgeSync(500) }
        assertEquals(1, owed())
        // Said no in Settings, it still waits, on the phone, for a yes.
        composeRule.runOnIdle { model().allowAutoReports(false) }
        assertEquals(1, owed())
        controller!!.destroyAndSettle()
        openLyrics()
        composeRule.runOnIdle { model().dismissLyrics() }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), problems())
        assertEquals(1, owed())
    }

    @Test
    fun aYesFromTheOfferSendsEveryTimingReportOwedForOtherSongsOnce() {
        owedFor("Old one", "Old two")
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(500) }
        waitUntil { shown(offer) }
        yes()
        waitUntil { sentFakes().size == 2 }
        assertEquals(setOf("""{"fake":"Old one"}""", """{"fake":"Old two"}"""), sentFakes().toSet())
        // The song in front of the user waits to be left, as it may be put in time some more; the others are dropped.
        waitUntil { owed() == 1 }
        composeRule.runOnIdle { model().dismissLyrics() }
        waitUntil { problems().count { it == "timed_by_hand" } == 1 }
        waitUntil { owed() == 0 }
        // Said again, nothing goes twice.
        composeRule.runOnIdle { model().allowAutoReports(true) }
        composeRule.waitForIdle()
        assertEquals(3, sentFakes().size)
    }

    @Test
    fun turningReportsOnInSettingsSendsEveryTimingReportOwed() {
        owedFor("Old one", "Old two")
        openLyrics()
        composeRule.runOnIdle { model().dismissLyrics() }
        composeRule.runOnIdle { model().allowAutoReports(true) }
        waitUntil { sentFakes().size == 2 }
        waitUntil { owed() == 0 }
        assertEquals(setOf("""{"fake":"Old one"}""", """{"fake":"Old two"}"""), sentFakes().toSet())
        assertTrue(AutoReports(prefs()).reported(MusicMetadata("Old one", "Band"), SongProblem.TIMED_BY_HAND))
    }

    @Test
    fun timingReportsOwedThatDidntGetThereWaitAndGoOnceTheUserIsOnline() {
        owedFor("Old one", "Old two")
        reportsDown = true
        openLyrics()
        composeRule.runOnIdle { model().dismissLyrics() }
        composeRule.runOnIdle { model().allowAutoReports(true) }
        // The first didn't get there, so the second isn't tried, as it wouldn't either; both stay.
        waitUntil { sentFakes().size == 1 }
        composeRule.waitForIdle()
        assertEquals(1, sentFakes().size)
        assertEquals(2, owed())
        reportsDown = false
        // The app started anew: they go at the start.
        controller!!.destroyAndSettle()
        openLyrics()
        waitUntil { owed() == 0 }
        assertEquals(3, sentFakes().size)
    }

    @Test
    fun anOwedReportTheBoxWillNeverTakeIsDroppedAndTheNextOneGoes() {
        owedFor("Old one", "Old two")
        reportAnswer = { body -> if ("Old one" in body) 400 else 204 }
        openLyrics()
        composeRule.runOnIdle { model().dismissLyrics() }
        composeRule.runOnIdle { model().allowAutoReports(true) }
        waitUntil { owed() == 0 }
        assertEquals(listOf("""{"fake":"Old one"}""", """{"fake":"Old two"}"""), sentFakes())
    }

    @Test
    fun onlyTheLatestTwentyTimingReportsAreOwed() {
        owedFor(*(1..21).map { "Song $it" }.toTypedArray())
        assertEquals(20, owed())
        openLyrics()
        composeRule.runOnIdle { model().allowAutoReports(true) }
        waitUntil { owed() == 0 }
        assertEquals(20, sentFakes().size)
        assertFalse(sentFakes().contains("""{"fake":"Song 1"}"""))
    }

    @Test
    fun aTimingReportOwedGoesAtEachStartWhileReportsAreAllowedAndNeverWhenTheyAreNot() {
        owedFor("Old one")
        prefs().edit().putBoolean("auto_reports", false).commit()
        openLyrics()
        composeRule.runOnIdle { model().dismissLyrics() }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), sentFakes())
        assertEquals(1, owed())
        controller!!.destroyAndSettle()
        prefs().edit().putBoolean("auto_reports", true).commit()
        openLyrics()
        waitUntil { sentFakes().size == 1 }
        waitUntil { owed() == 0 }
    }

    /** Two lines, as an earlier version found them for a song. */
    private val oldLines = listOf(LyricLine(0, "Old one"), LyricLine(10_000, "Old two"))

    /** A song an earlier version kept in time, [n]th of many: from a player that names its recording, and how long it is. */
    private fun oldSong(n: Int) = MusicMetadata("Song $n", "Band", recordingId = "com.fake.player:$n", lengthMs = 200_000L)

    /**
     * What an earlier version kept for [count] songs, written by the stores it wrote them with: a line put
     * 1.5 s later in each, and the singing heard through the first when [sungFirst].
     */
    private fun keptBefore(count: Int = 1, sungFirst: Boolean = false) {
        (1..count).forEach { LyricsSyncStore(prefs()).save(oldSong(it), oldLines, listOf(SyncPoint(10_000, 11_500))) }
        if (sungFirst) SungStore(prefs()).save(oldSong(1), oldLines)
    }

    /** The reports sent on timing kept from an earlier version. */
    private fun keptSent() = sentReports().filter { it.optString("problem") == AutoReports.EARLIER }

    /** The app started, without opening anything. */
    private fun started() {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        composeRule.waitForIdle()
    }

    @Test
    fun timingKeptBeforeTheUpdateGoesAtTheFirstStartWhenReportsWereAllowedSayingOnlyWhatWasKept() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        keptBefore(sungFirst = true)
        LyricsSyncStore(prefs()).save(MusicMetadata("Only named", "Band"), oldLines, listOf(SyncPoint(0, 300)))
        started()
        waitUntil { keptSent().size == 2 }
        waitUntil { owed() == 0 }
        val sent = keptSent().first()
        assertTrue(sent.getString("version").isNotEmpty())
        assertTrue(sent.getString("android").isNotEmpty())
        val song = sent.getJSONObject("song")
        // As the key kept them: names as matched, with no case or spaces.
        assertEquals("song1", song.getString("title"))
        assertEquals("band", song.getString("artist"))
        assertEquals("com.fake.player:1", song.getString("recording"))
        assertEquals(200_000L, song.getLong("lengthMs"))
        assertTrue(song.getBoolean("sung"))
        val point = song.getJSONArray("timing").getJSONObject(0)
        assertEquals(10_000L, point.getLong("timedMs"))
        assertEquals(11_500L, point.getLong("heardMs"))
        // Nothing it didn't keep is made up.
        assertEquals(setOf("timedMs", "heardMs"), point.keys().asSequence().toSet())
        assertEquals(setOf("title", "artist", "recording", "lengthMs", "sung", "timing"), song.keys().asSequence().toSet())
        assertEquals(setOf("version", "android", "problem", "song"), sent.keys().asSequence().toSet())
        // A song kept with no recording or length says neither, and wasn't heard sung.
        val named = keptSent()[1].getJSONObject("song")
        assertEquals(setOf("title", "artist", "sung", "timing"), named.keys().asSequence().toSet())
        assertFalse(named.getBoolean("sung"))
        // Started again, nothing goes twice, and what this version times isn't taken for it.
        controller!!.destroyAndSettle()
        LyricsSyncStore(prefs()).save(oldSong(9), oldLines, listOf(SyncPoint(0, 300)))
        started()
        assertEquals(2, keptSent().size)
        assertEquals(0, owed())
    }

    @Test
    fun timingKeptBeforeTheUpdateWaitsForSettingsAndGoesWhenTurnedOn() {
        keptBefore(count = 2)
        started()
        // Not said yet: nothing goes, and it's kept.
        assertEquals(emptyList<JSONObject>(), keptSent())
        assertEquals(2, owed())
        controller!!.destroyAndSettle()
        started()
        assertEquals(2, owed())
        composeRule.runOnIdle { model().allowAutoReports(true) }
        waitUntil { keptSent().size == 2 }
        waitUntil { owed() == 0 }
        assertEquals(setOf("song1", "song2"), keptSent().map { it.getJSONObject("song").getString("title") }.toSet())
    }

    @Test
    fun timingKeptBeforeTheUpdateGoesOnAYesFromTheOffer() {
        keptBefore()
        openLyrics()
        assertEquals(1, owed())
        composeRule.runOnIdle { model().nudgeSync(500) }
        waitUntil { shown(offer) }
        yes()
        waitUntil { keptSent().size == 1 }
        // The song in front of the user still waits to be left.
        assertEquals(1, owed())
    }

    @Test
    fun timingKeptBeforeTheUpdateNeverGoesWhenReportsAreOffButIsKeptForSettings() {
        prefs().edit().putBoolean("auto_reports", false).commit()
        keptBefore()
        started()
        composeRule.waitForIdle()
        assertEquals(emptyList<JSONObject>(), sentReports())
        assertEquals(1, owed())
    }

    @Test
    fun timingKeptBeforeThatWentAlreadyIsntSentAgain() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        keptBefore(count = 2)
        // The first went as put in time by hand in the version before; the second as kept, before a kill.
        AutoReports(prefs()).remember(oldSong(1), SongProblem.TIMED_BY_HAND)
        val second = org.json.JSONArray(prefs().getString("auto_reports_sent", "[]")).put("${AutoReports.EARLIER}\u0000song2\u0000band\u0000com.fake.player:2\u0000200000")
        prefs().edit().putString("auto_reports_sent", second.toString()).commit()
        keptBefore(count = 3)
        started()
        waitUntil { keptSent().size == 1 }
        composeRule.waitForIdle()
        assertEquals(listOf("song3"), keptSent().map { it.getJSONObject("song").getString("title") })
    }

    @Test
    fun brokenTimingFromBeforeIsSkippedAndTheAppStarts() {
        prefs().edit().putBoolean("auto_reports", true).putString("lyrics_sync", "{not json").putString("sung_heard", "\u0000").commit()
        started()
        assertEquals(0, owed())
        controller!!.destroyAndSettle()
        // Songs with no points, or no name, or points that aren't two numbers each, are no timing to send.
        val odd = JSONObject()
            .put("\u0000band", JSONObject().put("points", org.json.JSONArray().put(org.json.JSONArray().put(1).put(2))))
            .put("a\u0000b", JSONObject().put("points", org.json.JSONArray()))
            .put("c\u0000d", JSONObject().put("points", org.json.JSONArray().put(org.json.JSONArray().put(1))))
            .put("e\u0000f", JSONObject())
            .put("g\u0000h", "not an object")
        prefs().edit().remove("auto_reports_earlier_read").putString("lyrics_sync", odd.toString()).commit()
        started()
        composeRule.waitForIdle()
        assertEquals(0, owed())
        assertEquals(emptyList<JSONObject>(), sentReports())
    }

    @Test
    fun twoHundredSongsKeptBeforeGoEachOnceAndWhatDidntGetThereWaitsForTheNextStart() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        keptBefore(count = 200)
        // The box takes a few, then turns the rest away, as past its limit.
        var taken = 0
        val others = fake.handler
        fake.handler = { request ->
            if (request.url.host == "report.crosstune.cc") FakeSpotify.html(request, "", code = if (++taken <= 5) 204 else 429) else others(request)
        }
        started()
        waitUntil { owed() == 195 }
        composeRule.waitForIdle()
        // One turned away stops the rest.
        assertEquals(6, keptSent().size)
        controller!!.destroyAndSettle()
        fake.handler = others
        started()
        waitUntil { owed() == 0 }
        val titles = keptSent().map { it.getJSONObject("song").getString("title") }
        assertEquals(201, titles.size)
        // The one turned away went again; every other went once.
        assertEquals(200, titles.toSet().size)
    }

    @Test
    fun reportsOwedArePacedUnderTheBoxsLimit() {
        MainActivity.reportPaceMs = MainViewModel.REPORT_PACE_MS
        prefs().edit().putBoolean("auto_reports", true).commit()
        keptBefore(count = 3)
        started()
        waitUntil { keptSent().size == 1 }
        composeRule.waitForIdle()
        assertEquals(1, keptSent().size)
        // The next waits its turn.
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(MainViewModel.REPORT_PACE_MS))
        waitUntil { keptSent().size == 2 }
        assertEquals(1, owed())
    }

    @Test
    fun aReportOwedThatWasPutBackWhileWaitingItsTurnIsntSent() {
        MainActivity.reportPaceMs = MainViewModel.REPORT_PACE_MS
        owedFor("Old one", "Old two")
        prefs().edit().putBoolean("auto_reports", true).commit()
        started()
        waitUntil { sentFakes().size == 1 }
        // The words of the second were put back as they were meanwhile.
        AutoReports(prefs()).owedNothing(MusicMetadata("Old two", "Band"))
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(MainViewModel.REPORT_PACE_MS))
        composeRule.waitForIdle()
        assertEquals(listOf("""{"fake":"Old one"}"""), sentFakes())
    }

    @Test
    fun wordsPutBackAsTheyWereArentSent() {
        prefs().edit().putBoolean("auto_reports", true).commit()
        openLyrics()
        composeRule.runOnIdle { model().nudgeSync(500) }
        composeRule.runOnIdle { model().resetSync() }
        composeRule.runOnIdle { model().dismissLyrics() }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), problems())
        assertFalse(shown(offer))
    }

    @Test
    fun noChordsForTheWordsAreReportedWhenAllowed() {
        prefs().edit().putBoolean("auto_reports", true).putBoolean("lyrics_chords", true).commit()
        openLyrics()
        waitUntil { problems() == listOf("no_chords") }
    }

    /** projectM, when it can't start. */
    private object NoMilkdrop : Milkdrop {
        override fun open(width: Int, height: Int) = false
        override fun size(width: Int, height: Int) = Unit
        override fun show(preset: String, smooth: Boolean) = false
        override fun hear(samples: ByteArray, count: Int) = Unit
        override fun draw() = Unit
        override fun darken(shade: Float, ceiling: Float) = Unit
        override fun finish() = Unit
        override fun close() = Unit
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        /** The offer takes no tap for 500 ms from the frame it first shows, so a frame more. */
        const val OFFER_ARMED_MS = 600L
    }

    @Test
    fun aWrongReadingIsSentWithOnlyTheSongTheLineTheWordAndHowItRead() {
        // Sent only when the user taps Send: whatever they said about reports sent by themselves.
        prefs().edit().putBoolean("auto_reports", false).commit()
        // Off the main thread, as on a phone, so it can be caught on its way.
        MainActivity.lookupDispatcher = Dispatchers.IO
        reportsHeld = CountDownLatch(1)
        lyricsAre("""[{"trackName":"Demo","artistName":"Band","duration":60,"syncedLyrics":"[00:00.00] 花の空\n[00:10.00] 星の海"}]""")
        openLyrics { model().uiState.learning.lines.isNotEmpty() && shown("星の海") }
        // The line held, its first word tapped, and the flag on it.
        composeRule.onAllNodes(hasText("星の海", substring = true))[0].performSemanticsAction(SemanticsActions.OnLongClick)
        waitUntil { runCatching { composeRule.onAllNodes(hasText("星")).onLast().performFirstLinkClick() }.isSuccess }
        val flag = hasContentDescription(string(R.string.reading_wrong)) and hasClickAction()
        waitUntil { composeRule.onAllNodes(flag).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(flag).performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput(" ほし ")
        val send = composeRule.onNode(hasText(string(R.string.report_send)) and hasClickAction())
        send.performClick()
        // On its way, it can't be sent twice.
        waitUntil { model().uiState.report == ReportStatus.SENDING }
        send.assertIsNotEnabled()
        reportsHeld!!.countDown()
        waitUntil { model().uiState.report == null && !shown(string(R.string.reading_what_goes)) }
        assertEquals(1, fake.requestedUrls.count { it == ReportSender.URL })
        val sent = sentReport()
        assertEquals(listOf("android", "problem", "reading", "song", "version"), sent.keys().asSequence().sorted().toList())
        assertEquals("wrong_reading", sent.getString("problem"))
        assertEquals("""{"title":"Demo","artist":"Band","source":"lrclib"}""", sent.getJSONObject("song").toString())
        val reading = sent.getJSONObject("reading")
        assertEquals("星の海", reading.getString("line"))
        assertEquals("星", reading.getString("word"))
        assertEquals("ほし / hoshi", reading.getString("shown"))
        assertEquals("ipadic", reading.getString("reader"))
        assertEquals("japanese", reading.getString("language"))
        assertEquals("ほし", reading.getString("right"))
    }

    @Test
    fun aWrongReadingThatDoesntGetThereSaysSo() {
        reportsDown = true
        lyricsAre("""[{"trackName":"Demo","artistName":"Band","duration":60,"syncedLyrics":"[00:00.00] 花の空"}]""")
        openLyrics { model().uiState.learning.lines.isNotEmpty() }
        composeRule.runOnIdle { model().reportReading(Word("花", "か", "ka"), 0, "") }
        waitUntil { model().uiState.report == ReportStatus.FAILED }
        assertFalse(sentReport().getJSONObject("reading").has("right"))
    }

    @Test
    fun wordsInLatinLettersHaveNoReadingToSend() {
        openLyrics()
        composeRule.runOnIdle { model().reportReading(Word("Demo", "demo"), 0, "") }
        composeRule.waitForIdle()
        assertTrue(sentReports().isEmpty())
        assertNull(model().uiState.report)
    }

    @Test
    fun aLineThatIsntThereHasNoReadingToSend() {
        lyricsAre("""[{"trackName":"Demo","artistName":"Band","duration":60,"syncedLyrics":"[00:00.00] 花の空"}]""")
        openLyrics { model().uiState.learning.lines.isNotEmpty() }
        composeRule.runOnIdle { model().reportReading(Word("花", "か", "ka"), 5, "") }
        composeRule.waitForIdle()
        assertTrue(sentReports().isEmpty())
        assertNull(model().uiState.report)
    }
}
