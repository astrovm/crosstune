package com.astrovm.crosstune

import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performTouchInput

import androidx.compose.ui.test.hasClickAction

import androidx.compose.ui.test.longClick

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.graphics.asAndroidBitmap

import androidx.compose.ui.test.performSemanticsAction

import androidx.compose.ui.semantics.SemanticsActions

import androidx.lifecycle.ViewModelProvider

import org.junit.Assert.assertFalse
import android.os.Looper
import android.Manifest
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl
import org.robolectric.shadow.api.Shadow
import org.robolectric.android.controller.ServiceController
import android.view.WindowManager
import android.view.View
import android.widget.ImageView
import android.view.MotionEvent
import android.app.NotificationManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

/** Crosstune naming a song playing nearby itself: asking for the microphone, listening, and what it finds. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
class ListeningTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeSpotify()
    private var microphone = FakeMicrophone()
    private var controller: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        MainActivity.httpClientFactory = { fake.client() }
        MainActivity.systemDispatcher = Dispatchers.Unconfined
        MainActivity.lookupDispatcher = Dispatchers.Unconfined
        MainActivity.microphoneFactory = { microphone }
        // Unit tests have no GPU or native library to draw visuals with.
        MainActivity.milkdropFactory = { NoMilkdrop }
        MainActivity.soundTapFactory = { null }
        prefs().edit().putBoolean("setup_complete", true).putBoolean("exact_match", false)
            .putBoolean(SongRecognizers.KEY_PICK_RESET, true).commit()
        File(app.cacheDir, "lookups.json").delete()
        File(app.cacheDir, "lyrics.json").delete()
        MainActivity.lyricsBusyPauseMs = 0
        shazamAnswers(MATCH)
    }

    @After
    fun tearDown() {
        runCatching { controller?.pause()?.stop()?.destroy() }
        // Anything still listening hears the end.
        microphone.end()
        MainActivity.httpClientFactory = ::httpClient
        MainActivity.systemDispatcher = Dispatchers.Default
        MainActivity.lookupDispatcher = Dispatchers.IO
        MainActivity.microphoneFactory = { AudioRecordMicrophone() }
        MainActivity.listenAlongPauseMs = MainViewModel.LISTEN_ALONG_PAUSE_MS
        MainActivity.hearingFactory = null
        MainActivity.playbackFactory = ::MediaSessionPlayback
        MainActivity.lyricsBusyPauseMs = LYRICS_BUSY_PAUSE_MS
        MainActivity.milkdropFactory = ::NativeMilkdrop
        MainActivity.soundTapFactory = { OutputMixTap.open() }
        FloatingLyricsService.host = null
        ShadowSettings.setCanDrawOverlays(false)
    }

    private fun prefs() = app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun string(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun launch(intent: Intent = Intent(Intent.ACTION_MAIN).setClass(app, MainActivity::class.java)): MainActivity {
        controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        composeRule.waitForIdle()
        return controller!!.get()
    }

    private fun allowMicrophone() = shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

    /** Shazam answers [body]; Apple Music knows Iris. */
    private fun shazamAnswers(body: String, code: Int = 200) {
        fake.handler = { request ->
            when (request.url.host) {
                "amp.shazam.com" -> FakeSpotify.html(request, body, code = code)
                else -> FakeSpotify.html(request, """{"results":[{"trackName":"Iris","artistName":"The Goo Goo Dolls"}]}""")
            }
        }
    }

    private fun shazamRequests() = fake.requestedUrls.count { it.startsWith("https://amp.shazam.com/") }

    private fun click(text: String) {
        composeRule.onNode(hasText(text) or hasContentDescription(text)).performClick()
        composeRule.waitForIdle()
    }

    private fun shown(text: String) = composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String) = composeRule.waitUntil(TIMEOUT_MS) { shown(text) }

    private fun recognize() = click(string(R.string.recognize_button))

    @Test
    fun aSongPlayingOnThePhoneIsNamedByItsAppWithoutListening() {
        val playing = MusicMetadata("Iris", "The Goo Goo Dolls")
        MainActivity.playbackFactory = { FakePlayback().apply { access = true; onThePhone = Heard.Song(playing, null, 42_000, SystemClock.elapsedRealtime()) } }
        allowMicrophone()
        launch()
        recognize()
        waitForText("Iris")
        composeRule.onNodeWithTag(RESULT_TAG).assertExists()
        assertEquals(0, microphone.opened)
        assertEquals(0, shazamRequests())
        assertEquals(playing, HistoryStore(prefs()).load().first().metadata)
    }

    @Test
    fun itListensThenShowsTheSongLikeAShazamLink() {
        allowMicrophone()
        launch()
        // With no other app, Crosstune listens itself.
        recognize()
        // It listens until there's something to hear.
        waitForText(string(R.string.listening_text))
        assertEquals(1, microphone.opened)
        microphone.play(3.0)
        composeRule.waitUntil(TIMEOUT_MS) { composeRule.onAllNodes(hasText("Iris")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(RESULT_TAG).assertExists()
        assertTrue(shown("https://music.apple.com/us/song/1109658204"))
        assertTrue(fake.requestedUrls.contains("https://itunes.apple.com/lookup?id=1109658204&country=us"))
        assertTrue(microphone.closed)
        assertEquals(MusicService.APPLE_MUSIC, HistoryStore(prefs()).load().first().link.service)
        assertTrue(!shown(string(R.string.listening_text)))
    }

    @Test
    fun theWordsOfASongHeardFollowOnFromWhereItWasHeard() {
        // Shazam says the sound sent starts 12.5 seconds into the song.
        val synced = """[{"trackName":"Demo","artistName":"Band","syncedLyrics":"[00:00.00] Start\n[00:10.00] Ten\n[10:00.00] Late"}]"""
        fake.handler = { request ->
            when (request.url.host) {
                "amp.shazam.com" -> FakeSpotify.html(request, """{"matches":[{"id":"1","offset":12.5}],"track":{"title":"Demo","subtitle":"Band"}}""")
                "lrclib.net" -> FakeSpotify.html(request, synced)
                else -> FakeSpotify.html(request, "{}")
            }
        }
        allowMicrophone()
        launch()
        microphone.play(3.0)
        recognize()
        waitForText("Demo")
        click(string(R.string.lyrics_button))
        // No music app plays it, so it follows what Crosstune heard: the line sung from 10 seconds in.
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodes(hasText("Ten") and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasText("Late") and SemanticsMatcher.expectValue(SemanticsProperties.Selected, false)).assertExists()
    }

    @Test
    fun aSongAppleMusicDoesntHaveIsKnownByName() {
        shazamAnswers("""{"matches":[{"id":"1"}],"track":{"title":"Demo","subtitle":"Band","images":{"coverart":"https://example.com/demo.jpg"}}}""")
        allowMicrophone()
        launch()
        microphone.play(3.0)
        recognize()
        waitForText("Demo")
        composeRule.onNodeWithTag(RESULT_TAG).assertExists()
        val saved = HistoryStore(prefs()).load().first()
        assertEquals(MusicMetadata("Demo", "Band", artworkUrl = "https://example.com/demo.jpg"), saved.metadata)
        assertEquals(null, saved.link.service)
        // Shazam's cover is enough; nothing else is looked up.
        assertEquals(listOf("amp.shazam.com", "example.com"), fake.requestedUrls.map { it.toHttpUrl().host }.distinct())
    }

    @Test
    fun noMatchSaysSoAndTryAgainListensAgain() {
        shazamAnswers(NO_MATCH)
        allowMicrophone()
        launch()
        microphone.play(1.0)
        microphone.end()
        recognize()
        waitForText(string(R.string.error_no_match))
        assertEquals(1, shazamRequests())

        shazamAnswers(MATCH)
        microphone.play(1.0)
        microphone.end()
        click(string(R.string.retry_button))
        waitForText("Iris")
        assertEquals(2, microphone.opened)
        assertTrue(!shown(string(R.string.error_no_match)))
    }

    @Test
    fun offlineItsTheNetworkError() {
        fake.handler = { throw IOException("offline") }
        allowMicrophone()
        launch()
        microphone.play(3.0)
        recognize()
        waitForText(string(R.string.error_network))
        // Try again listens again, rather than looking anything up.
        shazamAnswers(MATCH)
        microphone.play(3.0)
        click(string(R.string.retry_button))
        waitForText("Iris")
        assertEquals(2, microphone.opened)
    }

    @Test
    fun cancelStopsListening() {
        allowMicrophone()
        launch()
        recognize()
        waitForText(string(R.string.listening_text))
        click(string(R.string.cancel_button))
        composeRule.waitUntil(TIMEOUT_MS) { microphone.closed }
        composeRule.waitUntil(TIMEOUT_MS) { !shown(string(R.string.listening_text)) }
        assertEquals(0, shazamRequests())
        assertTrue(composeRule.onAllNodes(hasText(string(R.string.error_no_match))).fetchSemanticsNodes().isEmpty())
        // Back stops it too.
        recognize()
        waitForText(string(R.string.listening_text))
        assertEquals(2, microphone.opened)
        controller!!.get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitUntil(TIMEOUT_MS) { microphone.closed }
        composeRule.waitUntil(TIMEOUT_MS) { !shown(string(R.string.listening_text)) }
    }

    @Test
    fun leavingCrosstuneStopsListeningButTurningThePhoneDoesnt() {
        allowMicrophone()
        launch()
        recognize()
        waitForText(string(R.string.listening_text))
        // A rotation recreates the screen, still listening.
        controller = controller!!.recreate()
        composeRule.waitForIdle()
        assertTrue(shown(string(R.string.listening_text)))
        assertTrue(!microphone.closed)
        controller!!.pause().stop()
        composeRule.waitUntil(TIMEOUT_MS) { microphone.closed }
        controller!!.start().resume()
        composeRule.waitForIdle()
        assertTrue(!shown(string(R.string.listening_text)))
    }

    @Test
    fun itAsksForTheMicrophoneFirstAndOffersSettingsWithoutIt() {
        val activity = launch()
        recognize()
        val asked = shadowOf(activity).lastRequestedPermission
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), asked.requestedPermissions.toList())
        assertEquals(0, microphone.opened)
        activity.onRequestPermissionsResult(asked.requestCode, asked.requestedPermissions, intArrayOf(PackageManager.PERMISSION_DENIED))
        composeRule.waitForIdle()
        waitForText(string(R.string.error_microphone))
        // No Try again: Android won't ask again, so the way is in its settings.
        assertTrue(!shown(string(R.string.retry_button)))
        click(string(R.string.open_settings_button))
        val settings = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settings.action)
        assertEquals("package:${app.packageName}", settings.dataString)

        // Allowed, it listens right away.
        controller!!.pause().stop().destroy()
        val next = launch()
        recognize()
        val again = shadowOf(next).lastRequestedPermission
        allowMicrophone()
        next.onRequestPermissionsResult(again.requestCode, again.requestedPermissions, intArrayOf(PackageManager.PERMISSION_GRANTED))
        composeRule.waitForIdle()
        waitForText(string(R.string.listening_text))
        assertEquals(1, microphone.opened)
    }

    @Test
    fun whenShazamIsBusyTheOtherAppsAreOffered() {
        val pm = shadowOf(app.packageManager)
        pm.installPackage(installedApp(SongRecognizers.SHAZAM, "Shazam"))
        val tagging = ComponentName(SongRecognizers.SHAZAM, "Tagging")
        pm.addActivityIfNotPresent(tagging)
        pm.addIntentFilterForActivity(tagging, IntentFilter(SongRecognizers.SHAZAM_LISTEN).apply { addCategory(Intent.CATEGORY_DEFAULT) })
        prefs().edit().putString(SongRecognizers.KEY_PICK, app.packageName).commit()
        shazamAnswers("", code = 429)
        allowMicrophone()
        launch()
        microphone.play(3.0)
        recognize()
        waitForText(string(R.string.error_recognition_unavailable))
        assertTrue(shown(string(R.string.retry_button)))
        // Only the other apps: Crosstune is what just failed.
        assertTrue(!shown(string(R.string.open_app_button, string(R.string.app_name))))
        click(string(R.string.open_app_button, "Shazam"))
        assertEquals(SongRecognizers.SHAZAM_LISTEN, shadowOf(app).nextStartedActivity.action)
    }

    @Test
    fun theWidgetsButtonListensButOtherAppsCant() {
        allowMicrophone()
        launch(Intent(MainActivity.ACTION_LISTEN).setClassName(app, MainActivity.LISTEN_ALIAS))
        waitForText(string(R.string.listening_text))
        assertEquals(1, microphone.opened)
        controller!!.pause().stop().destroy()

        // MainActivity itself is exported; asked directly, it doesn't listen.
        microphone = FakeMicrophone()
        launch(Intent(MainActivity.ACTION_LISTEN).setClass(app, MainActivity::class.java))
        assertTrue(!shown(string(R.string.listening_text)))
        assertEquals(0, microphone.opened)
    }

    /** Hears whatever a test says, one song or silence per listen, and counts the listens. */
    private class FakeHearing : SongHearing {
        val next = Channel<Heard>(Channel.UNLIMITED)

        @Volatile
        var listens = 0

        override suspend fun listen(): Heard {
            listens++
            return next.receive()
        }

        /** [title] by Band, heard [seconds] into it, just now. */
        fun song(title: String, seconds: Double) =
            next.trySend(Heard.Song(MusicMetadata(title, "Band"), null, (seconds * 1000).toLong(), SystemClock.elapsedRealtime()))
    }

    /**
     * Listening along with [hearing] for ears; LRCLIB has "<title> one" at the start of each song,
     * "<title> two" from 10 seconds in, and "<title> three" from 40.
     */
    private fun listeningAlong(hearing: FakeHearing) {
        MainActivity.listenAlongPauseMs = 0
        MainActivity.hearingFactory = { hearing }
        fake.handler = { request ->
            val title = request.url.queryParameter("track_name")
            FakeSpotify.html(request, """[{"trackName":"$title","artistName":"Band","syncedLyrics":"[00:00.00] $title one\n[00:10.00] $title two\n[00:40.00] $title three"}]""")
        }
    }

    private fun described(label: String) = composeRule.onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty()

    private fun lit(line: String) = composeRule.onAllNodes(hasText(line) and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        .fetchSemanticsNodes().isNotEmpty()

    /** Recognizes Demo 2 seconds in, and opens its words, which keep listening along. */
    private fun demoWords(hearing: FakeHearing) {
        listeningAlong(hearing)
        allowMicrophone()
        launch()
        hearing.song("Demo", 2.0)
        recognize()
        waitForText("Demo")
        click(string(R.string.lyrics_button))
        composeRule.waitUntil(TIMEOUT_MS) { lit("Demo one") && described(string(R.string.lyrics_stop_listening)) }
    }

    @Test
    fun aSongHeardByAnotherNameDoesntReplaceTheWordsOfTheOneAnAppPlays() {
        val phone = FakePlayback().apply {
            access = true
            playing.value = Following(PlaybackClock(2_000, SystemClock.elapsedRealtime()), "Music", canSeek = true)
        }
        MainActivity.playbackFactory = { phone }
        val hearing = FakeHearing()
        listeningAlong(hearing)
        allowMicrophone()
        launch()
        hearing.song("Demo", 2.0)
        recognize()
        waitForText("Demo")
        click(string(R.string.lyrics_button))
        // Followed in the app, which shows instead of the microphone, still listening along.
        composeRule.waitUntil(TIMEOUT_MS) { lit("Demo one") && described(string(R.string.lyrics_following_app, "Music")) }
        // Shazam names it otherwise while the app plays it: the words stay.
        hearing.song("Demo (Remix)", 5.0)
        composeRule.waitUntil(TIMEOUT_MS) { hearing.listens >= 3 }
        assertTrue(shown("Demo one"))
        assertFalse(shown("Demo (Remix) one"))
        // The app no longer plays it: the song heard takes over.
        phone.playing.value = null
        hearing.song("Other", 1.0)
        composeRule.waitUntil(TIMEOUT_MS) { lit("Other one") }
    }

    @Test
    fun wordsOutOfTimeArePutInTimeByTappingTheLineHeard() {
        val synced = """[{"trackName":"Demo","artistName":"Band","syncedLyrics":"[00:00.00] Demo one\n[00:10.00] Demo two\n[00:40.00] Demo three"}]"""
        fake.handler = { request -> FakeSpotify.html(request, if (request.url.host == "lrclib.net") synced else "{}") }
        // The music app is paused 5 seconds in, where it's singing the second line, not the first as the words have it.
        val demo = MusicMetadata("Demo", "Band")
        val phone = FakePlayback().apply {
            access = true
            onThePhone = Heard.Song(demo, null, 5_000, SystemClock.elapsedRealtime())
            playing.value = Following(PlaybackClock(5_000, SystemClock.elapsedRealtime(), playing = false), "Music", canSeek = true)
        }
        MainActivity.playbackFactory = { phone }
        allowMicrophone()
        launch()
        recognize()
        waitForText("Demo")
        click(string(R.string.lyrics_button))
        composeRule.waitUntil(TIMEOUT_MS) { lit("Demo one") }

        click(string(R.string.lyrics_sync))
        waitForText(string(R.string.lyrics_sync_hint))
        // Nothing to reset before a line is put in time.
        assertFalse(shown(string(R.string.lyrics_sync_reset)))
        composeRule.onNodeWithText("Demo two").performClick()
        composeRule.waitUntil(TIMEOUT_MS) { lit("Demo two") }
        // Tapped while putting the words in time, the app isn't moved.
        assertEquals(emptyList<Long>(), phone.seeks)
        // Kept for the song, so it's in time next time too.
        assertEquals(listOf(SyncPoint(10_000, 5_000)), LyricsSyncStore(prefs()).load(demo, SyncedLyrics.parse("[00:00.00] Demo one\n[00:10.00] Demo two\n[00:40.00] Demo three")))

        // Reset puts them back as found.
        click(string(R.string.lyrics_sync_reset))
        composeRule.waitUntil(TIMEOUT_MS) { lit("Demo one") }
        assertFalse(shown(string(R.string.lyrics_sync_reset)))

        // Done, a line tapped moves the app again: to where it is now that the words are in time.
        composeRule.onNodeWithText("Demo two").performClick()
        composeRule.waitUntil(TIMEOUT_MS) { lit("Demo two") }
        click(string(R.string.lyrics_sync_done))
        composeRule.waitUntil(TIMEOUT_MS) { !shown(string(R.string.lyrics_sync_hint)) }
        composeRule.onNodeWithText("Demo three").performClick()
        composeRule.waitUntil(TIMEOUT_MS) { phone.seeks.isNotEmpty() }
        assertEquals(listOf(35_000L), phone.seeks)
    }

    @Test
    fun aVideoFollowedIsLinedUpWithItsSongByWhereItWasHeard() {
        // YouTube plays the song's video, which opens with 45 seconds before the song.
        val phone = FakePlayback().apply {
            access = true
            playing.value = Following(PlaybackClock(47_000, SystemClock.elapsedRealtime()), "YouTube", canSeek = true, appPackage = "com.google.android.youtube", video = true)
        }
        MainActivity.playbackFactory = { phone }
        val hearing = FakeHearing()
        listeningAlong(hearing)
        allowMicrophone()
        launch()
        hearing.song("Demo", 2.0)
        recognize()
        waitForText("Demo")
        click(string(R.string.lyrics_button))
        // Heard 2 seconds into the song, so its first line, not where the video is.
        composeRule.waitUntil(TIMEOUT_MS) { lit("Demo one") }
        assertFalse(lit("Demo three"))
        // A line tapped moves the video to it, 45 seconds on.
        composeRule.onNodeWithText("Demo two").performClick()
        composeRule.waitUntil(TIMEOUT_MS) { phone.seeks.isNotEmpty() }
        assertTrue(phone.seeks.toString(), phone.seeks.last() in 54_000..56_000)
    }

    @Test
    fun wordsListeningAlongKeepSteadyFollowSkipsAndTheNextSong() {
        val hearing = FakeHearing()
        demoWords(hearing)

        // Heard far from where the words are, once, it might be the chorus heard as its other time:
        // nothing moves until a second hearing agrees, as a real skip would.
        hearing.song("Demo", 12.5)
        composeRule.waitUntil(TIMEOUT_MS) { hearing.listens == 3 }
        assertTrue(lit("Demo one"))
        // Close to where they are, a hearing changes nothing either.
        hearing.song("Demo", 2.5)
        composeRule.waitUntil(TIMEOUT_MS) { hearing.listens == 4 }
        assertTrue(lit("Demo one"))
        hearing.song("Demo", 12.5)
        hearing.song("Demo", 12.6)
        composeRule.waitUntil(TIMEOUT_MS) { lit("Demo two") }

        // Nothing heard, the song's taken to have stopped; heard again further on, it follows on from there.
        hearing.next.trySend(Heard.Nothing)
        hearing.song("Demo", 41.0)
        composeRule.waitUntil(TIMEOUT_MS) { lit("Demo three") }

        // Shazam busy, it's asked again, and the next song takes over the words.
        hearing.next.trySend(Heard.Failed(AppError.RECOGNITION_UNAVAILABLE))
        hearing.next.trySend(Heard.Song(MusicMetadata("Other", "Band"), "1109658204", 0, SystemClock.elapsedRealtime()))
        composeRule.waitUntil(TIMEOUT_MS) { lit("Other one") }
        assertTrue(shown("Other"))
        // Each song heard along the way joins Recent: by its Apple Music link when Shazam knows one.
        val recent = HistoryStore(prefs()).load()
        assertEquals(listOf("Other", "Demo"), recent.map { it.metadata.title })
        assertEquals(MusicService.APPLE_MUSIC, recent.first().link.service)
        assertEquals(null, recent[1].link.service)
        composeRule.waitUntil(TIMEOUT_MS) { hearing.listens == 10 }

        // Stopped, it listens no more, and the words stay.
        click(string(R.string.lyrics_stop_listening))
        hearing.song("Demo", 0.0)
        composeRule.waitForIdle()
        assertEquals(10, hearing.listens)
        assertTrue(shown("Other one"))
        assertTrue(described(string(R.string.lyrics_listen_along)))
    }

    @Test
    fun withoutTheMicrophoneListeningAlongStopsAndSaysSo() {
        val hearing = FakeHearing()
        demoWords(hearing)
        hearing.next.trySend(Heard.Failed(AppError.MICROPHONE))
        composeRule.waitUntil(TIMEOUT_MS) { described(string(R.string.lyrics_listen_along)) }
        // The words stay; closed, the song says why it stopped.
        click(string(R.string.dismiss_button))
        waitForText(string(R.string.error_microphone))
    }

    @Test
    fun listeningAlongPausesOutOfSightAndTurnsOffAndOn() {
        val hearing = FakeHearing()
        demoWords(hearing)
        // The next song, one with no Apple Music link, joins Recent by its name.
        hearing.song("Third", 0.0)
        composeRule.waitUntil(TIMEOUT_MS) { lit("Third one") }
        val third = HistoryStore(prefs()).load().first()
        assertEquals("Third", third.metadata.title)
        assertEquals(null, third.link.service)

        // Out of sight, it stops listening; back, it listens again.
        composeRule.waitUntil(TIMEOUT_MS) { hearing.listens == 3 }
        controller!!.pause().stop()
        controller!!.start().resume()
        composeRule.waitUntil(TIMEOUT_MS) { hearing.listens == 4 }

        // Turned off and on from the words themselves.
        click(string(R.string.lyrics_stop_listening))
        click(string(R.string.lyrics_listen_along))
        composeRule.waitUntil(TIMEOUT_MS) { hearing.listens == 5 }
    }

    @Test
    fun lyricsFromAnywhereNameTheSongAndShowItsWordsListeningAlong() {
        val hearing = FakeHearing()
        listeningAlong(hearing)
        allowMicrophone()
        hearing.song("Demo", 2.0)
        launch(MainActivity.lyricsIntent(app))
        // No result to tap Lyrics on first: the words open as soon as the song is named, and keep listening.
        composeRule.waitUntil(TIMEOUT_MS) { lit("Demo one") && described(string(R.string.lyrics_stop_listening)) }
    }

    @Test
    fun theLyricsButtonOnTheMainScreenNamesTheSongAndShowsItsWords() {
        val hearing = FakeHearing()
        listeningAlong(hearing)
        allowMicrophone()
        launch()
        // Named without a cover, the song gets Deezer's, which the words show too.
        val words = fake.handler
        val cover = "https://cdn.example/demo.jpg"
        fake.handler = { request ->
            if (request.url.host == "api.deezer.com") {
                FakeSpotify.html(request, """{"data":[{"title":"Demo","artist":{"name":"Band"},"album":{"cover_big":"$cover"}}]}""")
            } else {
                words(request)
            }
        }
        hearing.song("Demo", 2.0)
        click(string(R.string.tile_lyrics_label))
        // No result to tap Lyrics on first: the words open as soon as the song is named.
        composeRule.waitUntil(TIMEOUT_MS) { lit("Demo one") && described(string(R.string.lyrics_stop_listening)) }
        composeRule.waitUntil(TIMEOUT_MS) { fake.requestedUrls.contains(cover) }
    }

    @Test
    fun leavingCrosstuneNeverFloatsTheWordsByThemselves() {
        ShadowSettings.setCanDrawOverlays(true)
        demoWords(FakeHearing())
        // Even with the song playing and listened along to: only Float does.
        controller!!.userLeaving()
        controller!!.pause().stop()
        assertEquals(null, shadowOf(app).nextStartedService)
    }

    @Test
    fun visualsMoveBehindTheWordsUntilTurnedOff() {
        demoWords(FakeHearing())
        assertTrue(visualsShown().isEmpty())
        click(string(R.string.visuals_show))
        composeRule.waitUntil(TIMEOUT_MS) { visualsShown().isNotEmpty() }
        // On, the button opens what else they can do.
        assertTrue(described(string(R.string.visuals_options)))
        // The words are still there, over them.
        assertTrue(lit("Demo one"))
        // Kept for next time.
        assertTrue(prefs().getBoolean("visuals", false))

        click(string(R.string.visuals_options))
        click(string(R.string.visuals_hide))
        composeRule.waitUntil(TIMEOUT_MS) { visualsShown().isEmpty() }
        assertTrue(described(string(R.string.visuals_show)))
        assertFalse(prefs().getBoolean("visuals", true))
    }

    @Test
    fun onlyTheVisualsCanShowUntilATapBringsTheWordsBack() {
        demoWords(FakeHearing())
        click(string(R.string.visuals_show))
        composeRule.waitUntil(TIMEOUT_MS) { visualsShown().isNotEmpty() }
        click(string(R.string.visuals_options))
        click(string(R.string.visuals_only))
        // The words and the buttons make way, the visuals stay.
        composeRule.waitUntil(TIMEOUT_MS) { !shown("Demo one") }
        assertFalse(described(string(R.string.visuals_options)))
        assertTrue(visualsShown().isNotEmpty())
        // A tap anywhere brings the words back.
        val showWords = string(R.string.visuals_show_words)
        composeRule.onNode(SemanticsMatcher("shows the words") {
            androidx.compose.ui.semantics.SemanticsActions.OnClick in it.config && it.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].label == showWords
        }).performClick()
        composeRule.waitUntil(TIMEOUT_MS) { shown("Demo one") }
        assertTrue(visualsShown().isNotEmpty())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theWordsShowThroughTheirFadedEdges() {
        demoWords(FakeHearing())
        val image = composeRule.onRoot().captureToImage().asAndroidBitmap()
        // A row through the first line, which sits clear of the edges, has its letters on it.
        val line = composeRule.onNodeWithText("Demo one").getBoundsInRoot()
        val density = composeRule.density.density
        val y = ((line.top.value + line.bottom.value) / 2 * density).toInt()
        val row = ((line.left.value * density).toInt() until (line.right.value * density).toInt()).map { image.getPixel(it, y) }
        val background = image.getPixel(1, y)
        assertTrue(row.count { kotlin.math.abs(android.graphics.Color.luminance(it) - android.graphics.Color.luminance(background)) > 0.3f } > 10)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theVisualsCanBeDarkenedOrSkippedAndTheBarMakesWayWhileScrolling() {
        demoWords(FakeHearing())
        click(string(R.string.visuals_show))
        composeRule.waitUntil(TIMEOUT_MS) { visualsShown().isNotEmpty() }
        // How dark they're shaded, kept once let go.
        click(string(R.string.visuals_options))
        composeRule.onNode(hasContentDescription(string(R.string.visuals_shade))).performSemanticsAction(SemanticsActions.SetProgress) { it(0.7f) }
        composeRule.waitForIdle()
        assertEquals(0.7f, prefs().getFloat("visuals_shade", 0f), 0.01f)
        // On to the next, with the menu closing.
        click(string(R.string.visuals_next))
        assertFalse(shown(string(R.string.visuals_next)))
        assertTrue(visualsShown().isNotEmpty())

        // Scrolling the words, the bar makes way, and comes back once they rest.
        composeRule.onNode(androidx.compose.ui.test.hasScrollAction() and androidx.compose.ui.test.hasAnyDescendant(hasText("Demo one")))
            .performTouchInput { swipeUp() }
        composeRule.waitUntil(TIMEOUT_MS) { !described(string(R.string.visuals_options)) }
        composeRule.waitUntil(TIMEOUT_MS) { described(string(R.string.visuals_options)) }
    }

    @Test
    fun floatingLyricsCanFloatOverTheVisuals() {
        prefs().edit().putBoolean("floating_visuals", true).commit()
        val floating = floatOverApps(FakeHearing())
        composeRule.onNodeWithTag(FLOATING_LYRICS_TAG).assertExists()
        assertTrue(floating.get().let { service -> windowViews(service).any { root -> root.allViews().any { it is MilkdropView } } })
        floating.destroy()
    }

    @Test
    fun visualsAskForTheMicrophoneFirstWhichAndroidNeedsToShareWhatPlays() {
        demoWords(FakeHearing())
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val activity = controller!!.get()
        click(string(R.string.visuals_show))
        val asked = shadowOf(activity).lastRequestedPermission
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), asked.requestedPermissions.toList())
        assertTrue(visualsShown().isEmpty())
        allowMicrophone()
        activity.onRequestPermissionsResult(asked.requestCode, asked.requestedPermissions, intArrayOf(PackageManager.PERMISSION_GRANTED))
        composeRule.waitForIdle()
        composeRule.waitUntil(TIMEOUT_MS) { visualsShown().isNotEmpty() }
    }

    /** The visuals on screen, which draw nothing here, with no GPU. */
    private fun visualsShown(): List<MilkdropView> {
        fun find(view: View): List<MilkdropView> = when (view) {
            is MilkdropView -> listOf(view)
            is android.view.ViewGroup -> (0 until view.childCount).flatMap { find(view.getChildAt(it)) }
            else -> emptyList()
        }
        return find(controller!!.get().window.decorView)
    }

    /** projectM, when it can't start. */
    private object NoMilkdrop : Milkdrop {
        override fun open(width: Int, height: Int) = false
        override fun size(width: Int, height: Int) = Unit
        override fun show(preset: String, smooth: Boolean) = false
        override fun hear(samples: ByteArray, count: Int) = Unit
        override fun draw() = Unit
        override fun close() = Unit
    }

    @Test
    fun wordsThatArentTimedHaveNothingToFloat() {
        MainActivity.hearingFactory = { FakeHearing().apply { song("Plain", 2.0) } }
        fake.handler = { request -> FakeSpotify.html(request, """[{"trackName":"Plain","artistName":"Band","plainLyrics":"Just words"}]""") }
        allowMicrophone()
        launch()
        recognize()
        waitForText("Plain")
        click(string(R.string.lyrics_button))
        waitForText("Just words")
        assertFalse(described(string(R.string.floating_float)))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun floatingOnToASongWithoutTimedWordsShowsTheSongWhole() {
        val title = "A Song With A Name Long Enough To Need Several Lines Of The Floating Window To Be Read Whole"
        // Made narrow, as far as it goes.
        prefs().edit().putInt("floating_width", 420).commit()
        val hearing = FakeHearing()
        demoWords(hearing)
        floatNow()
        // The next song heard has only plain words.
        fake.handler = { request -> FakeSpotify.html(request, """[{"trackName":"$title","artistName":"Band","plainLyrics":"Just words"}]""") }
        hearing.song(title, 2.0)
        val floating = hasText(title) and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(FLOATING_LYRICS_TAG))
        composeRule.waitUntil(TIMEOUT_MS) { composeRule.onAllNodes(floating).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(shown("Band"))
        // Never cut short: every line it takes is shown.
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeRule.onNode(floating).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().lineCount > 2)
        assertFalse(layouts.single().hasVisualOverflow)
    }

    /** Floats the words on screen with Float, Android letting them over other apps, and Crosstune goes out of sight. */
    private fun floatNow(): ServiceController<FloatingLyricsService> {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        click(string(R.string.floating_float))
        val started = shadowOf(app).nextStartedService
        assertEquals(FloatingLyricsService::class.java.name, started.component!!.className)
        controller!!.pause().stop()
        return Robolectric.buildService(FloatingLyricsService::class.java, started).create().startCommand(0, 1).also { composeRule.waitForIdle() }
    }

    /** Floats Demo's words on [background], with Android letting them over other apps. */
    private fun floatOverApps(hearing: FakeHearing, background: Float = 0f): ServiceController<FloatingLyricsService> {
        prefs().edit().putFloat("floating_background", background).commit()
        demoWords(hearing)
        return floatNow()
    }

    private fun View.allViews(): List<View> =
        listOf(this) + ((this as? android.view.ViewGroup)?.let { group -> (0 until group.childCount).flatMap { group.getChildAt(it).allViews() } } ?: emptyList())

    private fun windowViews(service: FloatingLyricsService): List<View> =
        Shadow.extract<ShadowWindowManagerImpl>(service.getSystemService(WindowManager::class.java)).views

    private fun overlays(service: FloatingLyricsService): List<View> =
        windowViews(service).filter { (it.layoutParams as WindowManager.LayoutParams).type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY }

    private fun overlay(service: FloatingLyricsService): View = overlays(service).single { it !is ImageView }

    /** The button beside locked words, which still takes a touch. */
    private fun unlockButton(service: FloatingLyricsService): ImageView? = overlays(service).filterIsInstance<ImageView>().singleOrNull()

    @Test
    fun wordsLeftAtTheBottomMoveUpToFitAndBackWithoutForgettingWhereTheyWere() {
        // Left lower than they fit, e.g. on a smaller screen, or once their buttons are out.
        prefs().edit().putInt("floating_top", 100_000).commit()
        val floating = floatOverApps(FakeHearing())
        val view = overlay(floating.get())
        shadowOf(Looper.getMainLooper()).idle()
        val window = view.layoutParams as WindowManager.LayoutParams
        assertEquals(app.resources.displayMetrics.heightPixels - view.measuredHeight, window.y)
        assertEquals(100_000, prefs().getInt("floating_top", -1))
    }

    @Test
    fun lockedWordsAlwaysHaveAWayOutEvenFloatingLockedFromLastTime() {
        // Locked when they last floated, and with no notification to unlock them from.
        prefs().edit().putBoolean("floating_locked", true).commit()
        val floating = floatOverApps(FakeHearing())
        val service = floating.get()
        assertTrue((overlay(service).layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        val unlock = unlockButton(service)!!
        assertEquals(string(R.string.floating_unlock), unlock.contentDescription)
        // It takes touches, at the words' top right corner.
        val button = unlock.layoutParams as WindowManager.LayoutParams
        assertTrue(button.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0)
        val words = overlay(service).layoutParams as WindowManager.LayoutParams
        assertEquals(words.y, button.y)
        assertEquals(words.x + words.width - button.width, button.x)

        unlock.performClick()
        composeRule.waitForIdle()
        assertTrue((overlay(service).layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0)
        assertFalse(prefs().getBoolean("floating_locked", true))
        assertEquals(null, unlockButton(service))
        // Locked again, it's back; closed, it goes with the words.
        composeRule.onNodeWithTag(FLOATING_LYRICS_TAG).performClick()
        composeRule.waitForIdle()
        click(string(R.string.floating_lock))
        assertTrue(unlockButton(service) != null)
        floating.destroy()
        assertTrue(overlays(service).isEmpty())
    }

    private fun ServiceController<FloatingLyricsService>.command(action: String) {
        withIntent(Intent(app, FloatingLyricsService::class.java).setAction(action)).startCommand(0, 2)
        composeRule.waitForIdle()
    }

    @Test
    fun seeThroughWordsFloatInTheirOwnWindowStillListeningAlong() {
        val hearing = FakeHearing()
        val floating = floatOverApps(hearing)
        val service = floating.get()
        composeRule.onNodeWithTag(FLOATING_LYRICS_TAG).assertExists()
        assertTrue(shown("Demo two"))
        // Out of sight, it still listens along.
        val listens = hearing.listens
        hearing.song("Demo", 12.0)
        composeRule.waitUntil(TIMEOUT_MS) { hearing.listens > listens }
        val notification = shadowOf(service.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertEquals(string(R.string.floating_notification), shadowOf(notification).contentTitle)

        // Tapped, they show what they can do, for a moment.
        composeRule.onNodeWithTag(FLOATING_LYRICS_TAG).performClick()
        composeRule.waitForIdle()
        assertTrue(described(string(R.string.floating_close)))
        composeRule.mainClock.advanceTimeBy(7_000)
        composeRule.waitForIdle()
        assertFalse(described(string(R.string.floating_close)))
        // Stop listening along, and start it again.
        composeRule.onNodeWithTag(FLOATING_LYRICS_TAG).performClick()
        composeRule.waitForIdle()
        click(string(R.string.lyrics_stop_listening))
        assertTrue(described(string(R.string.lyrics_listen_along)))
        click(string(R.string.lyrics_listen_along))
        assertTrue(described(string(R.string.lyrics_stop_listening)))

        // Locked, touches go through to the app below, and the notification can unlock them.
        click(string(R.string.floating_lock))
        val window = overlay(service).layoutParams as WindowManager.LayoutParams
        assertTrue(window.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertEquals(0.8f, window.alpha)
        assertTrue(prefs().getBoolean("floating_locked", false))
        val locked = shadowOf(service.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertEquals(listOf(string(R.string.floating_unlock), string(R.string.floating_close)), locked.actions.map { it.title.toString() })
        floating.command(FloatingLyricsService.ACTION_UNLOCK)
        assertTrue((overlay(service).layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0)
        assertFalse(prefs().getBoolean("floating_locked", true))

        // Open Crosstune brings it back, and back in sight they stop floating.
        composeRule.onNodeWithTag(FLOATING_LYRICS_TAG).performClick()
        composeRule.waitForIdle()
        click(string(R.string.floating_open))
        assertEquals(MainActivity::class.java.name, shadowOf(service).nextStartedActivity.component!!.className)
        controller!!.start()
        assertEquals(FloatingLyricsService::class.java.name, shadowOf(app).nextStoppedService.component!!.className)
        assertEquals(null, FloatingLyricsService.host)
    }

    @Test
    fun floatingWordsAreDraggedUpAndDownAndStayWhereTheyWereLeft() {
        // Where they were left last time, in the app's dark look.
        prefs().edit().putInt("floating_top", 300).putString("theme", "DARK").commit()
        val floating = floatOverApps(FakeHearing())
        val view = overlay(floating.get())
        val top = (view.layoutParams as WindowManager.LayoutParams).y
        assertEquals(300, top)
        fun touch(action: Int, y: Float) = MotionEvent.obtain(0, 0, action, 100f, y, 0).also { view.dispatchTouchEvent(it) }.recycle()
        // On the words, a finger's wobble is still a tap; past it, a drag.
        touch(MotionEvent.ACTION_DOWN, 20f)
        touch(MotionEvent.ACTION_MOVE, 21f)
        touch(MotionEvent.ACTION_MOVE, 320f)
        touch(MotionEvent.ACTION_MOVE, 420f)
        touch(MotionEvent.ACTION_UP, 420f)
        assertEquals(top + 400, (view.layoutParams as WindowManager.LayoutParams).y)
        assertEquals(top + 400, prefs().getInt("floating_top", -1))
        // Never off the screen.
        touch(MotionEvent.ACTION_DOWN, 20f)
        touch(MotionEvent.ACTION_MOVE, -100_000f)
        touch(MotionEvent.ACTION_CANCEL, -100_000f)
        assertEquals(0, prefs().getInt("floating_top", -1))
        floating.destroy()
        assertTrue(windowViews(floating.get()).none { (it.layoutParams as WindowManager.LayoutParams).type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY })
    }

    @Test
    fun floatingWordsAreMadeWiderNarrowerOrBiggerWhereTheyAre() {
        val floating = floatOverApps(FakeHearing())
        val view = overlay(floating.get())
        val window = view.layoutParams as WindowManager.LayoutParams
        val screen = app.resources.displayMetrics.widthPixels
        val (left, width) = window.x to window.width
        fun touch(action: Int, x: Float, y: Float = 20f) = MotionEvent.obtain(0, 0, action, x, y, 0).also { view.dispatchTouchEvent(it) }.recycle()
        // Dragged from the right side, they're narrower, as far as they can be.
        touch(MotionEvent.ACTION_DOWN, view.width - 2f)
        touch(MotionEvent.ACTION_MOVE, view.width - 100f)
        touch(MotionEvent.ACTION_UP, view.width - 100f)
        assertEquals(width - 98, window.width)
        assertEquals(width - 98, prefs().getInt("floating_width", -1))
        // From the left, the left side moves.
        touch(MotionEvent.ACTION_DOWN, 2f)
        touch(MotionEvent.ACTION_MOVE, 52f)
        touch(MotionEvent.ACTION_UP, 52f)
        assertEquals(left + 50, window.x)
        assertEquals(width - 148, window.width)
        assertEquals(left + 50, prefs().getInt("floating_left", -1))
        // From the middle, sideways, they move, kept on screen.
        touch(MotionEvent.ACTION_DOWN, 100f)
        touch(MotionEvent.ACTION_MOVE, 100_000f)
        touch(MotionEvent.ACTION_UP, 100_000f)
        assertEquals(screen - window.width, window.x)

        // Two fingers spread apart make the words bigger, as big as they go.
        fun pinch(action: Int, spread: Float) {
            val ids = arrayOf(MotionEvent.PointerProperties().apply { id = 0 }, MotionEvent.PointerProperties().apply { id = 1 })
            val at = arrayOf(MotionEvent.PointerCoords().apply { x = 100f; y = 20f }, MotionEvent.PointerCoords().apply { x = 100f + spread; y = 20f })
            MotionEvent.obtain(0, 0, action, 2, ids, at, 0, 0, 1f, 1f, 0, 0, 0, 0).also { view.dispatchTouchEvent(it) }.recycle()
        }
        touch(MotionEvent.ACTION_DOWN, 100f)
        pinch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100f)
        pinch(MotionEvent.ACTION_MOVE, 150f)
        composeRule.waitForIdle()
        assertEquals(1.5f, controllerModel().uiState.floating.scale, 0.01f)
        pinch(MotionEvent.ACTION_MOVE, 10_000f)
        pinch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 0f)
        pinch(MotionEvent.ACTION_MOVE, 0f)
        touch(MotionEvent.ACTION_UP, 100f)
        assertEquals(FloatingOptions.MAX_SCALE, prefs().getFloat("floating_scale", 1f))
        floating.destroy()
        assertTrue(windowViews(floating.get()).none { (it.layoutParams as WindowManager.LayoutParams).type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY })
    }

    private fun controllerModel() = ViewModelProvider(controller!!.get())[MainViewModel::class.java]

    private val sliders = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    @Test
    fun floatingWordsAreChangedOnThemselves() {
        val hearing = FakeHearing()
        val floating = floatOverApps(hearing)
        val view = overlay(floating.get())
        composeRule.onNodeWithTag(FLOATING_LYRICS_TAG).performClick()
        composeRule.waitForIdle()
        click(string(R.string.floating_customize))
        composeRule.onNodeWithTag(FLOATING_CUSTOMIZE_TAG).assertExists()
        // Being changed, the buttons stay out.
        composeRule.mainClock.advanceTimeBy(7_000)
        composeRule.waitForIdle()
        assertTrue(described(string(R.string.floating_close)))

        // The band behind them, and their size, slid, are kept once let go.
        composeRule.onAllNodes(sliders)[0].performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        composeRule.onAllNodes(sliders)[1].performSemanticsAction(SemanticsActions.SetProgress) { it(1.4f) }
        composeRule.waitForIdle()
        assertEquals(0.5f, controllerModel().uiState.floating.background, 0.01f)
        assertEquals(0.5f, prefs().getFloat("floating_background", 0f), 0.01f)
        assertEquals(1.4f, prefs().getFloat("floating_scale", 1f), 0.01f)
        // A drag on a slider is the slider's, and doesn't move them.
        val top = (view.layoutParams as WindowManager.LayoutParams).y
        val slider = composeRule.onAllNodes(sliders)[0].fetchSemanticsNode().boundsInWindow
        fun touch(action: Int, y: Float) = MotionEvent.obtain(0, 0, action, slider.center.x, y, 0).also { view.dispatchTouchEvent(it) }.recycle()
        touch(MotionEvent.ACTION_DOWN, slider.center.y)
        touch(MotionEvent.ACTION_MOVE, slider.center.y + 200f)
        touch(MotionEvent.ACTION_UP, slider.center.y + 200f)
        assertEquals(top, (view.layoutParams as WindowManager.LayoutParams).y)

        // The line before the one sung shows too, once there is one; the next one, no longer.
        click(string(R.string.floating_previous_line))
        click(string(R.string.floating_next_line))
        assertTrue(prefs().getBoolean("floating_previous_line", false))
        assertFalse(prefs().getBoolean("floating_next_line", true))
        hearing.song("Demo", 12.5)
        hearing.song("Demo", 12.6)
        composeRule.waitUntil(TIMEOUT_MS) { shown("Demo two") && shown("Demo one") && !shown("Demo three") }

        // Tapped again, all of it goes, and next time only the buttons come out.
        composeRule.onNodeWithTag(FLOATING_LYRICS_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(FLOATING_LYRICS_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(FLOATING_CUSTOMIZE_TAG).assertDoesNotExist()
    }

    @Test
    fun theFloatButtonFloatsTheWordsAtOnceOnceAndroidAllowsIt() {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        demoWords(FakeHearing())
        click(string(R.string.floating_float))
        assertEquals(FloatingLyricsService::class.java.name, shadowOf(app).nextStartedService.component!!.className)
        // Crosstune goes behind the app the words float over.
        assertTrue(shadowOf(controller!!.get()).isTaskMovedToBack)
    }

    @Test
    fun theFloatButtonSaysWhyAndroidAsksAndFloatsOnceAllowed() {
        ShadowSettings.setCanDrawOverlays(false)
        demoWords(FakeHearing())
        // Said why first, which can be put off.
        click(string(R.string.floating_float))
        waitForText(string(R.string.floating_permission_title))
        click(string(R.string.cancel_button))
        assertFalse(shown(string(R.string.floating_permission_title)))
        assertEquals(null, shadowOf(app).nextStartedActivity)

        click(string(R.string.floating_float))
        click(string(R.string.floating_permission_continue))
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, shadowOf(app).nextStartedActivity.action)
        // Back, allowed: Android 13 asks about their notification once, and they float either way.
        ShadowSettings.setCanDrawOverlays(true)
        controller!!.pause().resume()
        composeRule.waitForIdle()
        assertTrue(prefs().getBoolean("asked_floating_notifications", false))
        shadowOf(controller!!.get()).getLastRequestedPermission()?.let { request ->
            shadowOf(controller!!.get()).grantPermissions(*request.requestedPermissions)
            controller!!.get().onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(PackageManager.PERMISSION_GRANTED))
        }
        composeRule.waitForIdle()
        assertEquals(FloatingLyricsService::class.java.name, shadowOf(app).nextStartedService.component!!.className)
    }

    @Test
    fun theFloatButtonLeftUnallowedFloatsNothing() {
        ShadowSettings.setCanDrawOverlays(false)
        demoWords(FakeHearing())
        click(string(R.string.floating_float))
        click(string(R.string.floating_permission_continue))
        controller!!.pause().resume()
        composeRule.waitForIdle()
        assertEquals(null, shadowOf(app).nextStartedService)
    }

    @Test
    fun floatingWordsCloseFromThemselvesTheNotificationOrCrosstuneClosing() {
        prefs().edit().putString("theme", "LIGHT").commit()
        val floating = floatOverApps(FakeHearing(), background = 0.6f)
        composeRule.onNodeWithTag(FLOATING_LYRICS_TAG).performClick()
        composeRule.waitForIdle()
        click(string(R.string.floating_close))
        assertTrue(shadowOf(floating.get()).isStoppedBySelf)

        floating.command(FloatingLyricsService.ACTION_CLOSE)
        assertTrue(shadowOf(floating.get()).isStoppedBySelf)

        controller!!.destroy()
        controller = null
        assertEquals(null, FloatingLyricsService.host)
    }

    @Test
    fun withoutAnythingToFloatOutOfOrAllowedTheWindowStopsAtOnce() {
        FloatingLyricsService.host = null
        val alone = Robolectric.buildService(FloatingLyricsService::class.java).create().startCommand(0, 1)
        assertTrue(shadowOf(alone.get()).isStoppedBySelf)
        // Nothing binds to it: it's only started.
        assertEquals(null, alone.get().onBind(Intent()))
        // An unlock from an old notification finds nothing to unlock.
        alone.command(FloatingLyricsService.ACTION_UNLOCK)

        // Taken back in Android's settings since Crosstune was left.
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        demoWords(FakeHearing())
        click(string(R.string.floating_float))
        ShadowSettings.setCanDrawOverlays(false)
        val service = Robolectric.buildService(FloatingLyricsService::class.java, shadowOf(app).nextStartedService).create().startCommand(0, 1).get()
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(windowViews(service).none { (it.layoutParams as WindowManager.LayoutParams).type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY })
    }

    @Test
    fun floatingWordsWithoutTheMicrophoneOpenCrosstuneToAskForIt() {
        val hearing = FakeHearing()
        val floating = floatOverApps(hearing)
        composeRule.onNodeWithTag(FLOATING_LYRICS_TAG).performClick()
        composeRule.waitForIdle()
        click(string(R.string.lyrics_stop_listening))
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        click(string(R.string.lyrics_listen_along))
        assertEquals(MainActivity::class.java.name, shadowOf(app).nextStartedActivity.component!!.className)
        floating.get()
    }

    /** MyMemory translating each row of what it's asked into "[row]", or answering [code]. */
    private fun translation(request: okhttp3.Request, code: Int = 200): okhttp3.Response {
        val rows = request.url.queryParameter("q").orEmpty().split("\n").joinToString("\\n") { "[$it]" }
        return FakeSpotify.html(request, """{"responseData":{"translatedText":"$rows"},"responseStatus":200}""", code = code)
    }

    /** Hears Demo, whose words LRCLIB has as [synced] lines, or else as [plain] ones; MyMemory answers [translations]. */
    private fun wordsOf(synced: String?, plain: String, translations: Int = 200) {
        MainActivity.listenAlongPauseMs = 0
        MainActivity.hearingFactory = { FakeHearing().apply { song("Demo", 2.0) } }
        fake.handler = { request ->
            when (request.url.host) {
                "api.mymemory.translated.net" -> translation(request, translations)
                else -> {
                    val timed = synced?.let { ""","syncedLyrics":"$it"""" }.orEmpty()
                    FakeSpotify.html(request, """[{"trackName":"Demo","artistName":"Band","plainLyrics":"$plain"$timed}]""")
                }
            }
        }
        allowMicrophone()
        launch()
        recognize()
        waitForText("Demo")
        click(string(R.string.lyrics_button))
    }

    private fun learnMenu() = click(string(R.string.lyrics_learn))

    @Test
    fun theLineSheetShowsJapaneseWithItsReadingsOverItAndItsWordsToTap() {
        wordsOf("[00:00.00] 夜空に星が\\n[00:30.00] 食べる", "夜空に星が\\n食べる")
        waitForText("食べる")
        learnMenu()
        click(string(R.string.lyrics_readings))
        composeRule.waitUntil(TIMEOUT_MS) { shown("よぞら") }
        composeRule.onAllNodes(hasText("夜空")).onFirst().performTouchInput { longClick() }
        composeRule.waitForIdle()
        // In the sheet, the line as the lyrics show it: its kanji with their readings over them.
        val inSheet = androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(STUDY_LINE_TAG))
        composeRule.waitUntil(TIMEOUT_MS) { composeRule.onAllNodes(hasText("よぞら") and inSheet).fetchSemanticsNodes().isNotEmpty() }
        // Each of its words still taps for its meaning, with its reading over it, once the dictionary
        // that finds them is loaded; the line may show a moment without its readings meanwhile.
        composeRule.waitUntil(TIMEOUT_MS) {
            if (composeRule.onAllNodes(hasText("よぞら") and inSheet).fetchSemanticsNodes().isNotEmpty()) {
                runCatching { composeRule.onAllNodes(hasText("夜空") and inSheet).onFirst().performFirstLinkClick() }
            }
            composeRule.waitForIdle()
            shown("yozora")
        }
    }

    @Test
    fun japaneseWordsShowTheirKanaRomajiAndTranslationAlsoFloating() {
        wordsOf("[00:00.00] 夜空に星が\\n[00:30.00] 食べる", "夜空に星が\\n食べる")
        waitForText("食べる")
        learnMenu()
        click(string(R.string.lyrics_readings))
        composeRule.waitUntil(TIMEOUT_MS) { shown("よぞら") }
        // The kana after it shows as written, each character free to wrap.
        assertTrue(shown("べ") && shown("る"))
        assertTrue(prefs().getBoolean("lyrics_readings", false))
        click(string(R.string.lyrics_romanized))
        composeRule.waitUntil(TIMEOUT_MS) { shown("yozora ni hoshi ga") }
        click(string(R.string.lyrics_translation))
        composeRule.waitUntil(TIMEOUT_MS) { shown("[夜空に星が]") }
        assertTrue(shown("[食べる]"))

        // Floating, the line being sung brings its reading, romaji and translation along.
        val floating = floatNow()
        composeRule.onNodeWithTag(FLOATING_LYRICS_TAG).assertExists()
        assertTrue(shown("よぞらにほしが"))
        assertTrue(shown("yozora ni hoshi ga"))
        assertTrue(shown("[夜空に星が]"))
        floating.destroy()
        controller!!.start().resume()
        composeRule.waitForIdle()

        // Switched off, each goes; kept off for the next song.
        learnMenu()
        click(string(R.string.lyrics_readings))
        click(string(R.string.lyrics_romanized))
        click(string(R.string.lyrics_translation))
        composeRule.waitForIdle()
        assertFalse(shown("よぞら"))
        assertFalse(shown("[食べる]"))
        assertFalse(prefs().getBoolean("lyrics_translation", true))
    }

    @Test
    fun wordsAlreadyInTheAppsLanguageSayNothingAndTheirWordsMeanWhatTheDictionarySays() {
        wordsOf("[00:00.00] Night sky\\n[00:30.00] Stars", "Night sky\\nStars")
        fake.handler = { request ->
            when {
                request.url.host == "api.mymemory.translated.net" -> FakeSpotify.html(
                    request,
                    """{"responseData":{"translatedText":"PLEASE SELECT TWO DISTINCT LANGUAGES"},"responseDetails":"PLEASE SELECT TWO DISTINCT LANGUAGES","responseStatus":"403"}"""
                )
                request.url.encodedPath.endsWith("/night") -> FakeSpotify.html(request, """{"en":[{"definitions":[{"definition":"The time between sunset and sunrise."}]}]}""")
                else -> FakeSpotify.html(request, "{}", code = 404)
            }
        }
        waitForText("Stars")
        learnMenu()
        click(string(R.string.lyrics_translation))
        // Not an error, and nothing worth saying: there's just nothing to translate them into.
        composeRule.waitUntil(TIMEOUT_MS) { fake.requestedUrls.any { it.startsWith("https://api.mymemory.translated.net/") } }
        composeRule.waitUntil(TIMEOUT_MS) { !shown(string(R.string.lyrics_translating)) }
        assertFalse(shown(string(R.string.lyrics_translation_failed)))

        composeRule.onNodeWithText("Night sky").performTouchInput { longClick() }
        composeRule.waitForIdle()
        val line = composeRule.onNode(hasText("Night sky") and androidx.compose.ui.test.hasTestTag(STUDY_LINE_TAG))
        composeRule.waitUntil(TIMEOUT_MS) { runCatching { line.performFirstLinkClick { (it.item as androidx.compose.ui.text.LinkAnnotation.Clickable).tag == "Night" } }.isSuccess }
        waitForText("The time between sunset and sunrise.")
        line.performFirstLinkClick { (it.item as androidx.compose.ui.text.LinkAnnotation.Clickable).tag == "sky" }
        waitForText(string(R.string.lyrics_word_unknown))
    }

    @Test
    fun wordsAreTranslatedIntoTheLanguagePicked() {
        prefs().edit().putBoolean("lyrics_translation", true).putString("translate_into", "pt-BR").commit()
        wordsOf("[00:00.00] Night sky\\n[00:30.00] Stars", "Night sky\\nStars")
        composeRule.waitUntil(TIMEOUT_MS) { shown("[Stars]") }
        assertTrue(fake.requestedUrls.toString(), fake.requestedUrls.any { "langpair=Autodetect%7Cpt-BR" in it })
    }

    @Test
    fun aTranslationThatCantBeDoneSaysSoAndIsTriedAgain() {
        prefs().edit().putBoolean("lyrics_translation", true).commit()
        wordsOf("[00:00.00] Night sky\\n[00:30.00] Stars", "Night sky\\nStars", translations = 503)
        waitForText(string(R.string.lyrics_translation_failed))
        assertFalse(shown("[Stars]"))
        fake.handler = { request -> translation(request) }
        click(string(R.string.retry_button))
        composeRule.waitUntil(TIMEOUT_MS) { shown("[Stars]") }
        assertFalse(shown(string(R.string.lyrics_translation_failed)))
        // English has no readings to offer, only a translation.
        learnMenu()
        assertFalse(shown(string(R.string.lyrics_readings)))
        assertFalse(shown(string(R.string.lyrics_romanized)))
    }

    @Test
    fun untimedKoreanWordsAreRomanizedLineByLine() {
        prefs().edit().putBoolean("lyrics_readings", true).commit()
        wordsOf(null, "사랑해\\n한국")
        waitForText("한국")
        learnMenu()
        // Hangul reads as it's written, so there are only Latin letters.
        assertFalse(shown(string(R.string.lyrics_readings)))
        click(string(R.string.lyrics_romanized))
        composeRule.waitUntil(TIMEOUT_MS) { shown("saranghae") }
        assertTrue(shown("hanguk"))

        // With nothing to follow, a line tapped opens to study, its words looked up as the lines are translated.
        composeRule.onAllNodesWithTag(LYRIC_LINE_TAG)[0].performClick()
        composeRule.waitForIdle()
        assertFalse(shown(string(R.string.lyrics_repeat_line)))
        // The line, once, with its words to tap in it.
        val line = hasText("사랑해") and androidx.compose.ui.test.hasTestTag(STUDY_LINE_TAG)
        composeRule.waitUntil(TIMEOUT_MS) { composeRule.onAllNodes(line).fetchSemanticsNodes().isNotEmpty() }
        // Written once in the sheet: no list of its words besides.
        assertEquals(1, composeRule.onAllNodes(hasText("사랑해") and !androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(LYRIC_LINE_TAG)) and !androidx.compose.ui.test.hasTestTag(LYRIC_LINE_TAG)).fetchSemanticsNodes().size)
        // Its words are found as Readings splits them, a moment after it opens, then tapped.
        composeRule.waitUntil(TIMEOUT_MS) { runCatching { composeRule.onNode(line).performFirstLinkClick() }.isSuccess }
        composeRule.waitUntil(TIMEOUT_MS) { shown("[사랑해]") }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val NO_MATCH = """{"matches":[]}"""
        const val MATCH = """{"matches":[{"id":"1"}],"track":{"title":"Iris","subtitle":"The Goo Goo Dolls",""" +
            """"hub":{"actions":[{"type":"applemusicplay","id":"1109658204"}]}}}"""
    }
}
