package com.astrovm.crosstune

import android.app.Application
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Looper
import android.provider.Settings
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
class MainActivityTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeSpotify()
    private var controller: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        MainActivity.httpClientFactory = { fake.client() }
    }

    @After
    fun tearDown() {
        runCatching { controller?.pause()?.stop()?.destroy() }
        MainActivity.httpClientFactory = { okhttp3.OkHttpClient() }
    }

    // region helpers

    private fun launch(intent: Intent = Intent(Intent.ACTION_MAIN)): MainActivity {
        intent.setClass(app, MainActivity::class.java)
        val built = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        controller = built
        composeRule.waitForIdle()
        return built.get()
    }

    private fun string(id: Int): String = app.getString(id)

    private fun prefs() = app.getSharedPreferences("crosstune_preferences", Context.MODE_PRIVATE)

    private fun click(text: String) {
        composeRule.onNodeWithText(text).performClick()
        composeRule.waitForIdle()
    }

    private fun typeUrl(value: String) {
        composeRule.onNode(hasSetTextAction()).performTextReplacement(value)
        composeRule.waitForIdle()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithTextCount(text) > 0
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size

    private fun assertTextShown(text: String) {
        composeRule.onNodeWithText(text).assertExists()
    }

    private fun assertTextAbsent(text: String) {
        composeRule.onNodeWithText(text).assertDoesNotExist()
    }

    private fun nextStartedActivity(): Intent? = shadowOf(app).nextStartedActivity

    private fun respondWithTrack(title: String?, description: String?) {
        fake.handler = { request -> FakeSpotify.html(request, FakeSpotify.trackPage(title, description)) }
    }

    private fun installActivity(component: ComponentName, filter: IntentFilter) {
        val pm = shadowOf(app.packageManager)
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(component, filter)
    }

    // endregion

    @Test
    fun launcherShowsLinkHelperUntilDismissed() {
        launch()
        assertTextShown(string(R.string.link_settings_helper_title))
        assertTextShown(string(R.string.app_tagline))

        click(string(R.string.dismiss_button))

        assertTextAbsent(string(R.string.link_settings_helper_title))
        assertTrue(prefs().getBoolean("link_settings_helper_dismissed", false))
        assertTrue(fake.requestedUrls.isEmpty())
    }

    @Test
    fun linkHelperStaysHiddenOnceDismissed() {
        prefs().edit().putBoolean("link_settings_helper_dismissed", true).commit()
        launch()
        assertTextAbsent(string(R.string.link_settings_helper_title))
    }

    @Test
    fun openLinkSettingsOpensOpenByDefaultScreen() {
        val activity = launch()
        click(string(R.string.open_link_settings_button))

        val started = nextStartedActivity()
        assertNotNull(started)
        assertEquals(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, started!!.action)
        assertEquals(Uri.parse("package:${activity.packageName}"), started.data)
        assertTextAbsent(string(R.string.link_settings_helper_title))
        assertTrue(prefs().getBoolean("link_settings_helper_dismissed", false))
    }

    @Test
    fun openLinkSettingsFallsBackToAppDetails() {
        shadowOf(app).checkActivities(true)
        installActivity(
            ComponentName("com.android.settings", "com.android.settings.AppDetails"),
            IntentFilter(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("package")
            }
        )
        launch()
        click(string(R.string.open_link_settings_button))

        val started = nextStartedActivity()
        assertNotNull(started)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started!!.action)
    }

    @Test
    fun viewIntentResolvesTrackAndOpensYouTubeMusicThenFinishes() {
        respondWithTrack("Cut To The Feeling", "Carly Rae Jepsen · Song · 2017")
        val activity = launch(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/track/$TRACK_ID?si=abc"))
        )

        composeRule.waitUntil(TIMEOUT_MS) { activity.isFinishing }

        assertEquals(listOf("https://open.spotify.com/track/$TRACK_ID"), fake.requestedUrls)
        val started = nextStartedActivity()
        assertNotNull(started)
        assertEquals(Intent.ACTION_VIEW, started!!.action)
        assertEquals("com.google.android.apps.youtube.music", started.`package`)
        assertEquals(
            "https://music.youtube.com/search?q=Cut%20To%20The%20Feeling%20Carly%20Rae%20Jepsen",
            started.dataString
        )
    }

    @Test
    fun viewIntentWithoutDataShowsInvalidUrlError() {
        launch(Intent(Intent.ACTION_VIEW))
        assertTextShown(string(R.string.error_invalid_url))
        assertTrue(fake.requestedUrls.isEmpty())
    }

    @Test
    fun sharedTextOpensPreferredYouTubeTarget() {
        prefs().edit().putString("default_target", "YOUTUBE").commit()
        respondWithTrack("Song &amp; Dance", "The Band · Song · 2020")
        val activity = launch(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Listen: https://open.spotify.com/track/$TRACK_ID?si=x.")
            }
        )

        composeRule.waitUntil(TIMEOUT_MS) { activity.isFinishing }

        assertEquals(listOf("https://open.spotify.com/track/$TRACK_ID"), fake.requestedUrls)
        val started = nextStartedActivity()
        assertNotNull(started)
        assertEquals("com.google.android.youtube", started!!.`package`)
        assertEquals(
            "https://www.youtube.com/results?search_query=Song%20%26%20Dance%20The%20Band",
            started.dataString
        )
    }

    @Test
    fun sharedBlankSubjectShowsInvalidUrlError() {
        launch(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "   ")
            }
        )
        assertTextShown(string(R.string.error_invalid_url))
    }

    @Test
    fun sharedIntentWithoutPayloadIsIgnored() {
        launch(Intent(Intent.ACTION_SEND).apply { type = "text/plain" })
        assertTextAbsent(string(R.string.error_invalid_url))
        assertTrue(fake.requestedUrls.isEmpty())
    }

    @Test
    fun newIntentWhileRunningResolvesTrack() {
        respondWithTrack("Fresh", "Someone · Song · 2024")
        val activity = launch()
        // Deliver the intent the way the framework does for a singleTop activity that is already running.
        val onNewIntent = MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
        onNewIntent.isAccessible = true
        onNewIntent.invoke(activity, Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/track/$TRACK_ID")))

        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!activity.isFinishing && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20L)
        }
        assertTrue("activity should finish; requests=${fake.requestedUrls}", activity.isFinishing)
        assertEquals(listOf("https://open.spotify.com/track/$TRACK_ID"), fake.requestedUrls)
    }

    @Test
    fun manualTrackIdResolvesAndResultActionsWork() {
        respondWithTrack("Cut To The Feeling", "Carly Rae Jepsen · Song · 2017")
        val activity = launch()

        typeUrl(TRACK_ID)
        click(string(R.string.resolve_button))
        waitForText("Cut To The Feeling")

        assertTextShown(string(R.string.result_title))
        assertTextShown("Carly Rae Jepsen")
        assertFalse(activity.isFinishing)
        // Manual resolution keeps the user's input as typed.
        composeRule.onNode(hasSetTextAction()).assertExists()

        click(string(R.string.open_in_youtube_music))
        val opened = nextStartedActivity()
        assertEquals("com.google.android.apps.youtube.music", opened!!.`package`)
        assertFalse(activity.isFinishing)

        click(string(R.string.copy_search_button))
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        assertEquals(
            "Cut To The Feeling Carly Rae Jepsen",
            clipboard.primaryClip!!.getItemAt(0).text.toString()
        )
        assertEquals(string(R.string.search_copied_to_clipboard), ShadowToast.getTextOfLatestToast())

        click(string(R.string.share_search_button))
        val chooser = nextStartedActivity()
        assertEquals(Intent.ACTION_CHOOSER, chooser!!.action)
        @Suppress("DEPRECATION")
        val shared = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertEquals(Intent.ACTION_SEND, shared!!.action)
        assertEquals(
            "https://music.youtube.com/search?q=Cut%20To%20The%20Feeling%20Carly%20Rae%20Jepsen",
            shared.getStringExtra(Intent.EXTRA_TEXT)
        )

        click(string(R.string.target_youtube))
        assertEquals("YOUTUBE", prefs().getString("default_target", null))
        assertTextShown(string(R.string.open_in_youtube))
        click(string(R.string.open_in_youtube))
        assertEquals("com.google.android.youtube", nextStartedActivity()!!.`package`)

        click(string(R.string.target_youtube_music))
        assertEquals("YOUTUBE_MUSIC", prefs().getString("default_target", null))
        assertTextShown(string(R.string.open_in_youtube_music))
    }

    @Test
    fun imeDoneResolvesSpotifyUri() {
        respondWithTrack("Only Title", null)
        launch()

        typeUrl("spotify:track:$TRACK_ID")
        composeRule.onNode(hasSetTextAction()).performImeAction()
        waitForText("Only Title")

        assertEquals(listOf("https://open.spotify.com/track/$TRACK_ID"), fake.requestedUrls)

        click(string(R.string.copy_search_button))
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        assertEquals("Only Title", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    fun embeddedSpotifyUriQueryParameterIsUnderstood() {
        respondWithTrack("Embedded", "Artist · Song")
        launch()

        typeUrl("https://open.spotify.com/embed?uri=spotify%3Atrack%3A$TRACK_ID")
        click(string(R.string.resolve_button))
        waitForText("Embedded")

        assertEquals(listOf("https://open.spotify.com/track/$TRACK_ID"), fake.requestedUrls)
    }

    @Test
    fun invalidInputsShowErrorWithoutNetworkCalls() {
        launch()
        val invalid = listOf(
            "",
            "https://example.com/track/$TRACK_ID",
            "https://open.spotify.com/album/$TRACK_ID",
            "https://open.spotify.com/track",
            "https://open.spotify.com/track/not-a-valid-id",
            "/track/$TRACK_ID",
            "spotify:track:short",
            "just some words"
        )
        for (input in invalid) {
            typeUrl(input)
            click(string(R.string.resolve_button))
            assertTextShown(string(R.string.error_invalid_url))
            click(string(R.string.clear_button))
            assertTextAbsent(string(R.string.error_invalid_url))
        }
        assertTrue(fake.requestedUrls.isEmpty())
    }

    @Test
    fun editingInputClearsPreviousError() {
        launch()
        typeUrl("nope")
        click(string(R.string.resolve_button))
        assertTextShown(string(R.string.error_invalid_url))

        typeUrl("nope again")
        assertTextAbsent(string(R.string.error_invalid_url))
    }

    @Test
    fun shortLinkFollowsRedirectToTrack() {
        fake.handler = { request ->
            if (request.url.host == "spotify.link") {
                FakeSpotify.html(request, "", finalUrl = "https://open.spotify.com/track/$TRACK_ID?si=1")
            } else {
                FakeSpotify.html(request, FakeSpotify.trackPage("Short Song", "Short Artist · Song"))
            }
        }
        launch()

        typeUrl("https://spotify.link/AbCdEf")
        click(string(R.string.resolve_button))
        waitForText("Short Song")

        assertEquals(
            listOf("https://spotify.link/AbCdEf", "https://open.spotify.com/track/$TRACK_ID"),
            fake.requestedUrls
        )
        composeRule.onNodeWithText("https://open.spotify.com/track/$TRACK_ID").assertExists()
    }

    @Test
    fun shortLinkRedirectingElsewhereIsInvalid() {
        fake.handler = { request -> FakeSpotify.html(request, "", finalUrl = "https://www.spotify.com/") }
        launch()

        typeUrl("https://www.spotify.link/AbCdEf")
        click(string(R.string.resolve_button))
        waitForText(string(R.string.error_invalid_url))

        assertEquals(listOf("https://www.spotify.link/AbCdEf"), fake.requestedUrls)
    }

    @Test
    fun shortLinkNetworkFailureShowsNetworkError() {
        fake.handler = { throw IOException("offline") }
        launch()

        typeUrl("https://spotify.link/AbCdEf")
        click(string(R.string.resolve_button))
        waitForText(string(R.string.error_network))
    }

    @Test
    fun trackNetworkFailureShowsNetworkError() {
        fake.handler = { throw IOException("offline") }
        launch()

        typeUrl(TRACK_ID)
        click(string(R.string.resolve_button))
        waitForText(string(R.string.error_network))
    }

    @Test
    fun pageWithoutMetadataShowsMetadataError() {
        respondWithTrack(null, "Artist · Song")
        launch()

        typeUrl(TRACK_ID)
        click(string(R.string.resolve_button))
        waitForText(string(R.string.error_metadata_unavailable))
    }

    @Test
    fun unreadableBodyShowsMetadataError() {
        fake.handler = { request: Request -> FakeSpotify.brokenBody(request) }
        launch()

        typeUrl(TRACK_ID)
        click(string(R.string.resolve_button))
        waitForText(string(R.string.error_metadata_unavailable))
    }

    @Test
    fun loadingStateIsShownWhileRequestIsInFlight() {
        val release = CountDownLatch(1)
        fake.handler = { request ->
            release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            FakeSpotify.html(request, FakeSpotify.trackPage("Late", "Slow · Song"))
        }
        launch()

        typeUrl(TRACK_ID)
        click(string(R.string.resolve_button))
        assertTextShown(string(R.string.loading_text))

        release.countDown()
        waitForText("Late")
        assertTextAbsent(string(R.string.loading_text))
    }

    @Test
    fun clearResetsResultButKeepsTarget() {
        respondWithTrack("Clear Me", "Artist · Song")
        launch()
        click(string(R.string.target_youtube))

        typeUrl(TRACK_ID)
        click(string(R.string.resolve_button))
        waitForText("Clear Me")
        assertTextShown(string(R.string.open_in_youtube))

        click(string(R.string.clear_button))
        assertTextAbsent("Clear Me")
        assertEquals("YOUTUBE", prefs().getString("default_target", null))
    }

    @Test
    fun openFallsBackToBrowserWhenAppIsMissing() {
        shadowOf(app).checkActivities(true)
        installActivity(
            ComponentName("com.example.browser", "com.example.browser.Browser"),
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("https")
            }
        )
        respondWithTrack("Fallback", "Artist · Song")
        launch()

        typeUrl(TRACK_ID)
        click(string(R.string.resolve_button))
        waitForText("Fallback")
        click(string(R.string.open_in_youtube_music))

        val started = nextStartedActivity()
        assertNotNull(started)
        assertNull(started!!.`package`)
        assertEquals(
            "https://music.youtube.com/search?q=Fallback%20Artist",
            started.dataString
        )
    }

    @Test
    fun madeByLinkOpensRepository() {
        launch()
        click(string(R.string.made_by))

        val started = nextStartedActivity()
        assertEquals(Intent.ACTION_VIEW, started!!.action)
        assertEquals(string(R.string.github_repo_url), started.dataString)
    }

    @Test
    fun acceptedTrackLinkVariantsAllResolveToCanonicalTrack() {
        respondWithTrack("Variant", "Artist · Song")
        launch()
        val accepted = listOf(
            "https://spotify.com/track/$TRACK_ID",
            "HTTPS://OPEN.SPOTIFY.COM/track/$TRACK_ID",
            "https://play.spotify.com/intl-es/track/$TRACK_ID/extra?si=1",
            "SPOTIFY:TRACK:$TRACK_ID",
            "  $TRACK_ID  ",
            "Check this out (https://open.spotify.com/track/$TRACK_ID)!",
            // An unusable embedded uri falls back to the track id in the path.
            "https://open.spotify.com/track/$TRACK_ID?uri=spotify:track:short",
            "https://open.spotify.com/track/$TRACK_ID?uri="
        )
        for (input in accepted) {
            fake.requestedUrls.clear()
            typeUrl(input)
            click(string(R.string.resolve_button))
            waitForText("Variant")
            assertEquals(input, listOf("https://open.spotify.com/track/$TRACK_ID"), fake.requestedUrls)
            assertTextAbsent(string(R.string.error_invalid_url))
            click(string(R.string.clear_button))
            assertTextAbsent("Variant")
        }
    }

    @Test
    fun lookalikeHostsAndMalformedIdsAreRejected() {
        launch()
        val rejected = listOf(
            "   ",
            "https://notspotify.com/track/$TRACK_ID",
            "https://spotify.com.evil.example/track/$TRACK_ID",
            "https://open.spotify.com/track/${TRACK_ID.dropLast(1)}",
            "https://open.spotify.com/track/${TRACK_ID}X",
            "${TRACK_ID}X",
            "spotify:album:$TRACK_ID",
            "spotify:album:$TRACK_ID?uri=spotify:track:$TRACK_ID",
            "mailto:someone@example.com",
            "spotify:track:$TRACK_ID:extra",
            "https://open.spotify.com/embed?uri=",
            "https://open.spotify.com/embed?uri=spotify%3Atrack%3Ashort",
            "https://notspotify.link/AbCdEf",
            "spotify.link/AbCdEf"
        )
        for (input in rejected) {
            typeUrl(input)
            click(string(R.string.resolve_button))
            composeRule.onNodeWithText(string(R.string.error_invalid_url)).assertExists(input)
            click(string(R.string.clear_button))
        }
        assertTrue(fake.requestedUrls.toString(), fake.requestedUrls.isEmpty())
    }

    @Test
    fun uppercaseShortLinkHostIsFollowed() {
        fake.handler = { request ->
            if (request.url.host == "spotify.link") {
                FakeSpotify.html(request, "", finalUrl = "https://open.spotify.com/track/$TRACK_ID")
            } else {
                FakeSpotify.html(request, FakeSpotify.trackPage("Loud Link", "Artist · Song"))
            }
        }
        launch()

        typeUrl("HTTPS://SPOTIFY.LINK/AbCdEf")
        click(string(R.string.resolve_button))
        waitForText("Loud Link")

        assertEquals(2, fake.requestedUrls.size)
        assertEquals("https://open.spotify.com/track/$TRACK_ID", fake.requestedUrls.last())
    }

    @Test
    fun blankTitleIsTreatedAsMissingMetadata() {
        respondWithTrack("  &#32; ", "Artist · Song")
        launch()

        typeUrl(TRACK_ID)
        click(string(R.string.resolve_button))
        waitForText(string(R.string.error_metadata_unavailable))

        assertTextAbsent(string(R.string.result_title))
        assertTextAbsent("Artist")
    }

    @Test
    fun metadataIsDecodedAndTrimmedAndArtistIsFirstDescriptionPart() {
        respondWithTrack("  Rock &amp; Roll  ", "  AC&#47;DC  ")
        launch()

        typeUrl(TRACK_ID)
        click(string(R.string.resolve_button))
        waitForText("Rock & Roll")
        assertTextShown("AC/DC")

        click(string(R.string.copy_search_button))
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        assertEquals("Rock & Roll AC/DC", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    fun titleOnlyTrackOpensSearchWithoutArtist() {
        respondWithTrack("Solo", null)
        launch()
        click(string(R.string.target_youtube))

        typeUrl(TRACK_ID)
        click(string(R.string.resolve_button))
        waitForText("Solo")
        click(string(R.string.open_in_youtube))

        assertEquals(
            "https://www.youtube.com/results?search_query=Solo",
            nextStartedActivity()!!.dataString
        )
    }

    @Test
    fun successfulResolveAfterErrorClearsError() {
        fake.handler = { throw IOException("offline") }
        launch()
        typeUrl(TRACK_ID)
        click(string(R.string.resolve_button))
        waitForText(string(R.string.error_network))

        // Retry without editing the input, so only the resolution itself can clear the error.
        respondWithTrack("Recovered", "Artist · Song")
        click(string(R.string.resolve_button))
        waitForText("Recovered")
        assertTextAbsent(string(R.string.error_network))
    }

    @Test
    fun unknownStoredTargetFallsBackToYouTubeMusic() {
        prefs().edit().putString("default_target", "SOUNDCLOUD").commit()
        respondWithTrack("Default", "Artist · Song")
        launch()

        typeUrl(TRACK_ID)
        click(string(R.string.resolve_button))
        waitForText("Default")

        assertTextShown(string(R.string.open_in_youtube_music))
        click(string(R.string.open_in_youtube_music))
        assertEquals("com.google.android.apps.youtube.music", nextStartedActivity()!!.`package`)
    }

    @Test
    fun selectedTargetSurvivesRelaunch() {
        launch()
        click(string(R.string.target_youtube))
        controller!!.pause().stop().destroy()

        respondWithTrack("Remembered", "Artist · Song")
        launch()
        typeUrl(TRACK_ID)
        click(string(R.string.resolve_button))
        waitForText("Remembered")
        assertTextShown(string(R.string.open_in_youtube))
    }

    @Test
    fun viewIntentWithBlankDataShowsInvalidUrlError() {
        val activity = launch(Intent(Intent.ACTION_VIEW, Uri.parse("   ")))
        assertTextShown(string(R.string.error_invalid_url))
        assertFalse(activity.isFinishing)
        assertTrue(fake.requestedUrls.isEmpty())
    }

    @Test
    fun viewIntentWithNonSpotifyLinkShowsErrorAndStaysOpen() {
        val activity = launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/track/$TRACK_ID")))
        assertTextShown(string(R.string.error_invalid_url))
        assertTextShown("https://example.com/track/$TRACK_ID")
        assertFalse(activity.isFinishing)
        assertTrue(fake.requestedUrls.isEmpty())
    }

    @Test
    fun viewIntentMetadataFailureKeepsActivityOpenWithCanonicalUrl() {
        respondWithTrack(null, null)
        val activity = launch(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/track/$TRACK_ID?si=tracking"))
        )
        waitForText(string(R.string.error_metadata_unavailable))

        assertFalse(activity.isFinishing)
        assertTextShown("https://open.spotify.com/track/$TRACK_ID")
        assertNull(nextStartedActivity())
    }

    @Test
    fun sharedSubjectIsUsedWhenTextIsMissing() {
        respondWithTrack("From Subject", "Artist · Song")
        val activity = launch(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "spotify:track:$TRACK_ID")
            }
        )

        composeRule.waitUntil(TIMEOUT_MS) { activity.isFinishing }
        assertEquals(listOf("https://open.spotify.com/track/$TRACK_ID"), fake.requestedUrls)
    }

    @Test
    fun sharedTextWithoutValidLinkShowsError() {
        val activity = launch(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Look at https://example.com/song")
            }
        )
        assertTextShown(string(R.string.error_invalid_url))
        assertTextShown("https://example.com/song")
        assertFalse(activity.isFinishing)
        assertTrue(fake.requestedUrls.isEmpty())
    }

    @Test
    fun sharedOpaqueNonTrackUriShowsErrorInsteadOfCrashing() {
        val activity = launch(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "spotify:album:$TRACK_ID")
            }
        )
        assertTextShown(string(R.string.error_invalid_url))
        assertFalse(activity.isFinishing)
        assertTrue(fake.requestedUrls.isEmpty())
    }

    @Test
    fun unrelatedIntentActionIsIgnored() {
        val activity = launch(Intent(Intent.ACTION_EDIT, Uri.parse("https://open.spotify.com/track/$TRACK_ID")))
        assertTextAbsent(string(R.string.error_invalid_url))
        assertTextAbsent("https://open.spotify.com/track/$TRACK_ID")
        assertFalse(activity.isFinishing)
        assertTrue(fake.requestedUrls.isEmpty())
    }

    @Test
    fun inputAndButtonsAreDisabledWhileLoading() {
        val release = CountDownLatch(1)
        fake.handler = { request ->
            release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            FakeSpotify.html(request, FakeSpotify.trackPage("Done", "Artist · Song"))
        }
        launch()

        typeUrl(TRACK_ID)
        click(string(R.string.resolve_button))

        composeRule.onNodeWithText(string(R.string.resolve_button)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.clear_button)).assertIsNotEnabled()
        composeRule.onNodeWithText(TRACK_ID).assertIsNotEnabled()

        release.countDown()
        waitForText("Done")
        composeRule.onNodeWithText(string(R.string.resolve_button)).assertIsEnabled()
        assertEquals(1, fake.requestedUrls.size)
    }

    private companion object {
        const val TRACK_ID = "11dFghVXANMlKmJXsNCbNl"
        const val TIMEOUT_MS = 5_000L
    }
}
