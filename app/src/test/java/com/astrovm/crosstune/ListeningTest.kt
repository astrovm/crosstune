package com.astrovm.crosstune

import org.junit.Assert.assertFalse
import android.os.Looper
import android.Manifest
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl
import org.robolectric.shadow.api.Shadow
import org.robolectric.android.controller.ServiceController
import android.view.WindowManager
import android.view.View
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
import androidx.compose.ui.test.performClick
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
        prefs().edit().putBoolean("setup_complete", true).putBoolean("exact_match", false)
            .putBoolean(SongRecognizers.KEY_PICK_RESET, true).commit()
        File(app.cacheDir, "lookups.json").delete()
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
    fun theWordsFloatOverOtherAppsWithTheMicrophoneThere() {
        val hearing = FakeHearing()
        demoWords(hearing)
        val activity = controller!!.get()

        // Floating, only the line being sung and the next one show.
        activity.onPictureInPictureModeChanged(true, activity.resources.configuration)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(FLOATING_LYRICS_TAG).assertExists()
        assertTrue(lit("Demo one") || shown("Demo one"))
        assertTrue(shown("Demo two"))

        // The window's microphone stops listening along, and starts it again.
        FloatingListenReceiver().onReceive(app, Intent())
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
        activity.onPictureInPictureModeChanged(false, activity.resources.configuration)
        composeRule.waitUntil(TIMEOUT_MS) { described(string(R.string.lyrics_listen_along)) }
        FloatingListenReceiver().onReceive(app, Intent())
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitUntil(TIMEOUT_MS) { described(string(R.string.lyrics_stop_listening)) }
    }

    @Test
    fun floatingWithoutTimedWordsShowsTheSong() {
        MainActivity.hearingFactory = { FakeHearing().apply { song("Demo", 2.0) } }
        fake.handler = { request -> FakeSpotify.html(request, """[{"trackName":"Demo","artistName":"Band","plainLyrics":"Just words"}]""") }
        allowMicrophone()
        launch()
        recognize()
        waitForText("Demo")
        click(string(R.string.lyrics_button))
        waitForText("Just words")
        val activity = controller!!.get()
        activity.onPictureInPictureModeChanged(true, activity.resources.configuration)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(FLOATING_LYRICS_TAG).assertExists()
        assertTrue(shown("Band"))
    }

    @Test
    @Config(sdk = [30])
    fun beforeAndroid12LeavingWithTheWordsOnScreenFloatsThem() {
        val hearing = FakeHearing()
        demoWords(hearing)
        val activity = controller!!.get()
        controller!!.userLeaving()
        assertTrue(activity.isInPictureInPictureMode)
    }

    @Test
    @Config(sdk = [30])
    fun beforeAndroid12LeavingWithoutWordsDoesntFloat() {
        allowMicrophone()
        launch()
        val activity = controller!!.get()
        controller!!.userLeaving()
        assertFalse(activity.isInPictureInPictureMode)
    }

    /** Leaves Crosstune with Demo's words on screen in [look], with Android letting them over other apps, and floats them there. */
    private fun floatOverApps(hearing: FakeHearing, look: FloatingLook = FloatingLook.NONE): ServiceController<FloatingLyricsService> {
        prefs().edit().putString("floating_look", look.name).commit()
        ShadowSettings.setCanDrawOverlays(true)
        demoWords(hearing)
        controller!!.userLeaving()
        val started = shadowOf(app).nextStartedService
        assertEquals(FloatingLyricsService::class.java.name, started.component!!.className)
        controller!!.pause().stop()
        return Robolectric.buildService(FloatingLyricsService::class.java, started).create().startCommand(0, 1).also { composeRule.waitForIdle() }
    }

    private fun windowViews(service: FloatingLyricsService): List<View> =
        Shadow.extract<ShadowWindowManagerImpl>(service.getSystemService(WindowManager::class.java)).views

    private fun overlay(service: FloatingLyricsService): View =
        windowViews(service).single { (it.layoutParams as WindowManager.LayoutParams).type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY }

    private fun ServiceController<FloatingLyricsService>.command(action: String) {
        withIntent(Intent(app, FloatingLyricsService::class.java).setAction(action)).startCommand(0, 2)
        composeRule.waitForIdle()
    }

    @Test
    fun seeThroughWordsFloatInTheirOwnWindowStillListeningAlong() {
        val hearing = FakeHearing()
        val floating = floatOverApps(hearing)
        val service = floating.get()
        // Not Android's own window: the words float over the app below, which shows through.
        assertFalse(controller!!.get().isInPictureInPictureMode)
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
        composeRule.mainClock.advanceTimeBy(5_000)
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
    fun floatingWordsCloseFromThemselvesTheNotificationOrCrosstuneClosing() {
        prefs().edit().putString("theme", "LIGHT").commit()
        val floating = floatOverApps(FakeHearing(), FloatingLook.SEE_THROUGH)
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
        prefs().edit().putString("floating_look", FloatingLook.NONE.name).commit()
        ShadowSettings.setCanDrawOverlays(true)
        demoWords(FakeHearing())
        controller!!.userLeaving()
        ShadowSettings.setCanDrawOverlays(false)
        val service = Robolectric.buildService(FloatingLyricsService::class.java, shadowOf(app).nextStartedService).create().startCommand(0, 1).get()
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(windowViews(service).none { (it.layoutParams as WindowManager.LayoutParams).type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY })
    }

    @Test
    fun seeThroughWordsNotAllowedOverOtherAppsFloatPlainInAndroidsWindow() {
        prefs().edit().putString("floating_look", FloatingLook.NONE.name).putBoolean("floating_next_line", false).commit()
        ShadowSettings.setCanDrawOverlays(false)
        demoWords(FakeHearing())
        controller!!.userLeaving()
        assertEquals(null, shadowOf(app).nextStartedService)
        val activity = controller!!.get()
        activity.onPictureInPictureModeChanged(true, activity.resources.configuration)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(FLOATING_LYRICS_TAG).assertExists()
        // Only the line being sung, as set.
        assertFalse(shown("Demo two"))
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

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val NO_MATCH = """{"matches":[]}"""
        const val MATCH = """{"matches":[{"id":"1"}],"track":{"title":"Iris","subtitle":"The Goo Goo Dolls",""" +
            """"hub":{"actions":[{"type":"applemusicplay","id":"1109658204"}]}}}"""
    }
}
