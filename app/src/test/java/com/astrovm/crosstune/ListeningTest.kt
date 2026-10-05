package com.astrovm.crosstune

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
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

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val NO_MATCH = """{"matches":[]}"""
        const val MATCH = """{"matches":[{"id":"1"}],"track":{"title":"Iris","subtitle":"The Goo Goo Dolls",""" +
            """"hub":{"actions":[{"type":"applemusicplay","id":"1109658204"}]}}}"""
    }
}
