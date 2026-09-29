package com.astrovm.crosstune

import android.app.Application
import android.content.ClipData
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onLast
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Looper
import android.provider.Settings
import androidx.compose.ui.semantics.SemanticsProperties
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
        // Most tests exercise the main screen; first-run setup has its own tests.
        prefs().edit().putBoolean("setup_complete", true).commit()
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

    private fun string(id: Int, vararg args: Any): String = app.getString(id, *args)

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

    /** Resolved titles also appear in the history list, so any matching node counts. */
    private fun assertTextShown(text: String) {
        assertTrue("'$text' not shown", composeRule.onAllNodesWithTextCount(text) > 0)
    }

    private fun assertTextAbsent(text: String) {
        composeRule.onNodeWithText(text).assertDoesNotExist()
    }

    private fun nextStartedActivity(): Intent? = shadowOf(app).nextStartedActivity

    private fun respondWithTrack(title: String?, description: String?) {
        fake.handler = { request -> FakeSpotify.html(request, FakeSpotify.trackPage(title, description)) }
    }

    private fun browserFilter() = IntentFilter(Intent.ACTION_VIEW).apply {
        addCategory(Intent.CATEGORY_DEFAULT)
        addCategory(Intent.CATEGORY_BROWSABLE)
        addDataScheme("https")
    }

    private fun inSettings(block: () -> Unit) {
        click(string(R.string.settings_button))
        block()
        click(string(R.string.done_button))
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
    @Config(sdk = [30])
    fun openLinkSettingsUsesAppDetailsBeforeAndroid12() {
        launch()
        click(string(R.string.open_link_settings_button))

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, nextStartedActivity()!!.action)
    }

    @Test
    @Config(sdk = [32])
    fun copySearchShowsToastBeforeAndroid13() {
        respondWithTrack("Cut To The Feeling", "Carly Rae Jepsen · Song · 2017")
        launch()
        typeUrl(TRACK_ID)
        click(string(R.string.resolve_button))
        waitForText("Cut To The Feeling")

        click(string(R.string.copy_search_button))

        assertEquals(string(R.string.search_copied_to_clipboard), ShadowToast.getTextOfLatestToast())
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
        // Android 13+ confirms clipboard writes itself, so the app stays quiet.
        assertNull(ShadowToast.getTextOfLatestToast())

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
            "https://open.spotify.com/show/$TRACK_ID",
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
    fun unreadableBodyShowsNetworkError() {
        fake.handler = { request: Request -> FakeSpotify.brokenBody(request) }
        launch()

        typeUrl(TRACK_ID)
        click(string(R.string.resolve_button))
        waitForText(string(R.string.error_network))
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
        assertTextAbsent(string(R.string.result_title))
        assertEquals("YOUTUBE", prefs().getString("default_target", null))
    }

    @Test
    fun openFallsBackToBrowserWhenAppIsMissing() {
        shadowOf(app).checkActivities(true)
        installActivity(
            ComponentName("com.example.browser", "com.example.browser.Browser"),
            browserFilter()
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
            // "Variant" stays in the history list, so wait for the result card itself.
            waitForText(string(R.string.result_title))
            assertEquals(input, listOf("https://open.spotify.com/track/$TRACK_ID"), fake.requestedUrls)
            assertTextAbsent(string(R.string.error_invalid_url))
            click(string(R.string.clear_button))
            assertTextAbsent(string(R.string.result_title))
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
            "spotify:episode:$TRACK_ID",
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
        prefs().edit().putString("default_target", "NAPSTER").commit()
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
    fun sharedUnsupportedUriShowsErrorInsteadOfCrashing() {
        val activity = launch(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "spotify:show:$TRACK_ID")
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

    // region lifecycle and error handling

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20L)
        }
        assertTrue("condition not met; requests=${fake.requestedUrls}", condition())
    }

    private fun trackLink() = Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/track/$TRACK_ID"))

    @Test
    fun notFoundPageShowsNotFoundInsteadOfOpeningSpotifyHomepageMetadata() {
        // Spotify's 404 page still carries generic og: tags that used to be read as a track.
        fake.handler = { request ->
            FakeSpotify.html(
                request,
                FakeSpotify.trackPage("Spotify - Web Player: Music for everyone", "Spotify is a digital music service"),
                code = 404
            )
        }
        val activity = launch(trackLink())

        waitForText(string(R.string.error_not_found))
        assertFalse(activity.isFinishing)
        assertNull(nextStartedActivity())
        assertTextAbsent("Spotify - Web Player: Music for everyone")
    }

    @Test
    fun httpErrorsMapToSpecificMessages() {
        launch()
        val cases = listOf(
            410 to R.string.error_not_found,
            429 to R.string.error_rate_limited,
            503 to R.string.error_service_unavailable,
            403 to R.string.error_not_found,
            400 to R.string.error_metadata_unavailable
        )
        for ((code, message) in cases) {
            fake.handler = { request -> FakeSpotify.html(request, FakeSpotify.trackPage("Error", "Page"), code = code) }
            typeUrl(TRACK_ID)
            click(string(R.string.resolve_button))
            waitForText(string(message))
            assertTextAbsent("Error")
        }
    }

    @Test
    fun shortLinkLandingPageWithTrackUrlInHtmlResolves() {
        fake.handler = { request ->
            if (request.url.host == "spotify.link") {
                FakeSpotify.html(
                    request,
                    "<script>window.location='https://open.spotify.com/intl-de/track/$TRACK_ID?si=x'</script>"
                )
            } else {
                FakeSpotify.html(request, FakeSpotify.trackPage("Scripted", "Artist · Song"))
            }
        }
        launch()

        typeUrl("http://spotify.link/AbCdEf")
        click(string(R.string.resolve_button))
        waitForText("Scripted")

        // Cleartext short links are upgraded instead of being blocked by the network security policy.
        assertEquals("https://spotify.link/AbCdEf", fake.requestedUrls.first())
    }

    @Test
    fun missingShortLinkShowsNotFound() {
        fake.handler = { request -> FakeSpotify.html(request, "", code = 404) }
        launch()

        typeUrl("https://spotify.link/gone")
        click(string(R.string.resolve_button))
        waitForText(string(R.string.error_not_found))
    }

    @Test
    fun rotationKeepsResultWithoutRefetching() {
        respondWithTrack("Kept", "Artist · Song")
        launch()
        typeUrl(TRACK_ID)
        click(string(R.string.resolve_button))
        waitForText("Kept")

        controller!!.recreate()
        composeRule.waitForIdle()

        assertTextShown("Kept")
        assertEquals(1, fake.requestedUrls.size)
    }

    @Test
    fun rotationWhileIncomingLinkLoadsOpensSearchOnce() {
        val release = CountDownLatch(1)
        fake.handler = { request ->
            release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            FakeSpotify.html(request, FakeSpotify.trackPage("Once", "Artist · Song"))
        }
        launch(trackLink())
        waitUntil { fake.requestedUrls.size == 1 }

        controller!!.recreate()
        release.countDown()
        val recreated = controller!!.get()
        waitUntil { recreated.isFinishing }

        assertEquals(1, fake.requestedUrls.size)
        assertNotNull(nextStartedActivity())
        assertNull(nextStartedActivity())
    }

    @Test
    fun newerLinkCancelsOlderRequest() {
        val release = CountDownLatch(1)
        fake.handler = { request ->
            if (request.url.pathSegments.last() == TRACK_ID) {
                release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                FakeSpotify.html(request, FakeSpotify.trackPage("Stale", "Old · Song"))
            } else {
                FakeSpotify.html(request, FakeSpotify.trackPage("Fresh", "New · Song"))
            }
        }
        launch()
        typeUrl(TRACK_ID)
        click(string(R.string.resolve_button))
        waitUntil { fake.requestedUrls.size == 1 }

        val onNewIntent = MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
        onNewIntent.isAccessible = true
        onNewIntent.invoke(
            controller!!.get(),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/track/$OTHER_TRACK_ID"))
        )
        waitUntil { controller!!.get().isFinishing }
        release.countDown()
        Thread.sleep(100L)
        shadowOf(Looper.getMainLooper()).idle()

        assertTextShown("Fresh")
        assertTextAbsent("Stale")
    }

    @Test
    fun relaunchFromRecentsDoesNotReopenTheLink() {
        val intent = trackLink().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        val activity = launch(intent)

        assertTrue(fake.requestedUrls.isEmpty())
        assertFalse(activity.isFinishing)
    }

    @Test
    fun missingAppAndBrowserShowsErrorInsteadOfCrashing() {
        shadowOf(app).checkActivities(true)
        respondWithTrack("Nowhere", "Artist · Song")
        val activity = launch(trackLink())

        waitForText(string(R.string.error_no_app_to_open))
        assertFalse(activity.isFinishing)
    }

    @Test
    fun localizedTrackLinksAreRoutedToCrosstune() {
        LinkInterception(app).setEnabled(MusicService.SPOTIFY, true)
        val links = listOf(
            "https://open.spotify.com/track/$TRACK_ID",
            "https://open.spotify.com/intl-es/track/$TRACK_ID",
            "https://open.spotify.com/intl-pt-BR/track/$TRACK_ID?si=1",
            "https://open.spotify.com/album/$TRACK_ID",
            "https://open.spotify.com/intl-es/artist/$TRACK_ID",
            "https://open.spotify.com/playlist/$TRACK_ID"
        )
        for (link in links) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE)
            val handlers = app.packageManager.queryIntentActivities(intent, 0)
            assertTrue(link, handlers.any { it.activityInfo.packageName == app.packageName })
        }
        val podcast = Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/show/$TRACK_ID"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        assertTrue(app.packageManager.queryIntentActivities(podcast, 0).none {
            it.activityInfo.packageName == app.packageName
        })
    }

    // endregion

    // region features

    private fun resolveTyped(input: String = TRACK_ID) {
        typeUrl(input)
        click(string(R.string.resolve_button))
        waitForText(string(R.string.result_title))
    }

    @Test
    fun albumsArtistsAndPlaylistsResolveAndSearchByName() {
        fake.handler = { request ->
            val page = when (request.url.pathSegments.first()) {
                "album" -> FakeSpotify.trackPage("After Hours - Album by The Weeknd | Spotify", "The Weeknd · album · 2020")
                "artist" -> FakeSpotify.trackPage("The Weeknd", "Artist · 114.9M monthly listeners.")
                else -> FakeSpotify.trackPage("Today's Top Hits", "The hottest 50")
            }
            FakeSpotify.html(request, page)
        }
        launch()

        resolveTyped("https://open.spotify.com/intl-es/album/$TRACK_ID")
        assertTextShown("Album · from Spotify")
        click(string(R.string.open_in_youtube_music))
        assertEquals("https://music.youtube.com/search?q=After%20Hours%20The%20Weeknd", nextStartedActivity()!!.dataString)

        click(string(R.string.clear_button))
        resolveTyped("spotify:artist:$TRACK_ID")
        click(string(R.string.open_in_youtube_music))
        assertEquals("https://music.youtube.com/search?q=The%20Weeknd", nextStartedActivity()!!.dataString)

        click(string(R.string.clear_button))
        resolveTyped("https://open.spotify.com/playlist/$TRACK_ID")
        assertTextShown("Playlist · from Spotify")
        assertEquals(
            listOf(
                "https://open.spotify.com/album/$TRACK_ID",
                "https://open.spotify.com/artist/$TRACK_ID",
                "https://open.spotify.com/playlist/$TRACK_ID"
            ),
            fake.requestedUrls
        )
    }

    @Test
    fun everyDestinationBuildsItsOwnSearchUrl() {
        respondWithTrack("Song", "Artist · Song")
        launch()
        resolveTyped()
        val expected = mapOf(
            R.string.target_apple_music to ("com.apple.android.music" to "https://music.apple.com/search?term=Song%20Artist"),
            R.string.target_deezer to ("deezer.android.app" to "https://www.deezer.com/search/Song%20Artist"),
            R.string.target_tidal to ("com.aspiro.tidal" to "https://listen.tidal.com/search?q=Song%20Artist"),
            R.string.target_soundcloud to ("com.soundcloud.android" to "https://soundcloud.com/search?q=Song%20Artist")
        )
        for ((label, destination) in expected) {
            click(string(label))
            composeRule.onNode(hasText(string(label)) and isSelectable()).assertIsSelected()
            click(string(MusicService.entries.first { it.labelRes == label }.openLabelRes))
            val opened = nextStartedActivity()!!
            assertEquals(destination.first, opened.`package`)
            assertEquals(destination.second, opened.dataString)
        }
    }

    @Test
    fun askEachTimeOffersDestinationsForIncomingLinks() {
        respondWithTrack("Pick Me", "Artist · Song")
        launch()
        inSettings { click(string(R.string.setting_ask_each_time)) }
        assertTrue(prefs().getBoolean("ask_each_time", false))
        controller!!.pause().stop().destroy()

        val activity = launch(trackLink())
        waitForText(string(R.string.picker_title))
        assertFalse(activity.isFinishing)
        assertNull(nextStartedActivity())

        click(string(R.string.open_in_deezer))
        waitUntil { activity.isFinishing }
        val opened = nextStartedActivity()!!
        assertEquals("deezer.android.app", opened.`package`)
        // Picking a destination for one link doesn't change the default.
        assertNull(prefs().getString("default_target", null))
    }

    @Test
    fun dismissingTheDestinationPickerKeepsTheResult() {
        prefs().edit().putBoolean("ask_each_time", true).commit()
        respondWithTrack("Stay", "Artist · Song")
        val activity = launch(trackLink())
        waitForText(string(R.string.picker_title))

        click(string(R.string.cancel_button))

        assertTextAbsent(string(R.string.picker_title))
        assertTextShown(string(R.string.result_title))
        assertFalse(activity.isFinishing)
        inSettings { click(string(R.string.setting_ask_each_time)) }
        assertFalse(prefs().getBoolean("ask_each_time", true))
    }

    @Test
    fun exactMatchOpensDirectLinkAndFallsBackToSearch() {
        fake.handler = { request ->
            when (request.url.host) {
                "itunes.apple.com" -> FakeSpotify.html(
                    request,
                    """{"results":[{"trackName":"Exact","artistName":"Artist","trackViewUrl":"https://music.apple.com/us/song/1"}]}"""
                )
                "api.deezer.com" -> FakeSpotify.html(request, """{"data":[]}""")
                else -> FakeSpotify.html(request, FakeSpotify.trackPage("Exact", "Artist · Song"))
            }
        }
        launch()
        inSettings { click(string(R.string.setting_exact_match)) }
        assertTrue(prefs().getBoolean("exact_match", false))
        resolveTyped()

        click(string(R.string.target_apple_music))
        click(string(R.string.open_in_apple_music))
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://music.apple.com/us/song/1", nextStartedActivity()!!.dataString)

        click(string(R.string.target_deezer))
        click(string(R.string.open_in_deezer))
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://www.deezer.com/search/Exact%20Artist", nextStartedActivity()!!.dataString)

        inSettings { click(string(R.string.setting_exact_match)) }
        assertFalse(prefs().getBoolean("exact_match", true))
    }

    @Test
    fun matchingStateIsShownWhileLookingUpExactMatch() {
        val release = CountDownLatch(1)
        fake.handler = { request ->
            if (request.url.host == "api.deezer.com") {
                release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                FakeSpotify.html(request, """{"data":[]}""")
            } else {
                FakeSpotify.html(request, FakeSpotify.trackPage("Slow", "Artist · Song"))
            }
        }
        prefs().edit().putBoolean("exact_match", true).putString("default_target", "DEEZER").commit()
        launch()
        resolveTyped()

        click(string(R.string.open_in_deezer))
        waitForText(string(R.string.matching_text))
        composeRule.onNodeWithText(string(R.string.resolve_button)).assertIsNotEnabled()

        release.countDown()
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        composeRule.waitForIdle()
        assertTextAbsent(string(R.string.matching_text))
    }

    @Test
    fun retryIsOfferedForTemporaryFailuresOnly() {
        fake.handler = { throw IOException("offline") }
        val activity = launch(trackLink())
        waitForText(string(R.string.error_network))

        respondWithTrack("Back Online", "Artist · Song")
        click(string(R.string.retry_button))
        // The retried request keeps the original intent's behaviour and opens the search.
        waitUntil { activity.isFinishing }
        controller!!.pause().stop().destroy()

        fake.handler = { request -> FakeSpotify.html(request, "", code = 404) }
        launch()
        typeUrl(TRACK_ID)
        click(string(R.string.resolve_button))
        waitForText(string(R.string.error_not_found))
        assertTextAbsent(string(R.string.retry_button))
    }

    @Test
    fun historyRemembersResultsAcrossLaunchesAndCanBeCleared() {
        fake.handler = { request ->
            val title = if (request.url.pathSegments.last() == TRACK_ID) "First Song" else "Second Song"
            FakeSpotify.html(request, FakeSpotify.trackPage(title, "Artist · Song"))
        }
        launch()
        resolveTyped(TRACK_ID)
        click(string(R.string.clear_button))
        resolveTyped(OTHER_TRACK_ID)
        controller!!.pause().stop().destroy()

        launch()
        assertTextShown(string(R.string.history_title))
        assertTextAbsent(string(R.string.result_title))
        fake.requestedUrls.clear()

        click("First Song")
        assertTextShown(string(R.string.result_title))
        composeRule.onNodeWithText("https://open.spotify.com/track/$TRACK_ID").assertExists()
        assertTrue(fake.requestedUrls.isEmpty())

        click(string(R.string.clear_history_button))
        assertTextAbsent(string(R.string.history_title))
        assertTrue(HistoryStore(prefs()).load().isEmpty())
    }

    @Test
    fun clipboardShortcutResolvesCopiedLinkOnceFocused() {
        respondWithTrack("Copied", "Artist · Song")
        app.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("link", "Listen https://open.spotify.com/track/$TRACK_ID"))

        launch(Intent(MainActivity.ACTION_PASTE_FROM_CLIPBOARD))
        controller!!.windowFocusChanged(false)
        assertTrue(fake.requestedUrls.isEmpty())

        controller!!.windowFocusChanged(true)
        val activity = controller!!.get()
        waitUntil { activity.isFinishing }
        assertEquals(listOf("https://open.spotify.com/track/$TRACK_ID"), fake.requestedUrls)

        // Focus returning later doesn't read the clipboard again.
        controller!!.windowFocusChanged(true)
        assertEquals(1, fake.requestedUrls.size)
    }

    @Test
    fun clipboardShortcutWithEmptyClipboardExplains() {
        app.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
        launch(Intent(MainActivity.ACTION_PASTE_FROM_CLIPBOARD))
        controller!!.windowFocusChanged(true)

        assertTextShown(string(R.string.error_clipboard_empty))
    }

    // endregion

    // region multi-service

    @Test
    fun linkAlreadyOnTheDestinationOpensAsIsInsteadOfSearching() {
        fake.handler = { request ->
            FakeSpotify.html(request, """{"title":"Blinding Lights","author_name":"The Weeknd - Topic"}""")
        }
        launch()
        resolveTyped("https://music.youtube.com/watch?v=4NRXx6U8ABQ&list=x")
        assertTextShown("Song · from YouTube Music")
        // Opening the source in itself needs no separate "open original" button.
        assertTextAbsent(string(R.string.open_in_youtube))

        click(string(R.string.open_in_youtube_music))
        val opened = nextStartedActivity()!!
        assertEquals("com.google.android.apps.youtube.music", opened.`package`)
        assertEquals("https://music.youtube.com/watch?v=4NRXx6U8ABQ", opened.dataString)

        click(string(R.string.share_search_button))
        @Suppress("DEPRECATION")
        val shared = nextStartedActivity()!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("https://music.youtube.com/watch?v=4NRXx6U8ABQ", shared.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun resultCanStillBeOpenedInTheAppItCameFrom() {
        respondWithTrack("Original", "Artist · Song")
        val activity = launch()
        resolveTyped()

        click(string(R.string.open_in_spotify))

        val opened = nextStartedActivity()!!
        assertEquals("com.spotify.music", opened.`package`)
        assertEquals("https://open.spotify.com/track/$TRACK_ID", opened.dataString)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun unreadableIncomingLinkCanBeOpenedInItsOwnApp() {
        fake.handler = { request -> FakeSpotify.html(request, "", code = 400) }
        val activity = launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.deezer.com/en/track/908604612")))
        waitForText(string(R.string.error_metadata_unavailable))

        click(string(R.string.open_in_deezer))

        val opened = nextStartedActivity()!!
        assertEquals("deezer.android.app", opened.`package`)
        assertEquals("https://www.deezer.com/track/908604612", opened.dataString)
        waitUntil { activity.isFinishing }
    }

    @Test
    fun fallbackNeverOpensCrosstuneItself() {
        LinkInterception(app).setEnabled(MusicService.SPOTIFY, true)
        // Crosstune handles this Spotify link, so without its app installed a plain VIEW intent could loop back.
        shadowOf(app).checkActivities(true)
        installActivity(ComponentName("com.example.browser", "com.example.browser.Browser"), browserFilter())
        respondWithTrack("Loop", "Artist · Song")
        launch()
        resolveTyped()

        click(string(R.string.open_in_spotify))

        val opened = nextStartedActivity()!!
        assertEquals("https://open.spotify.com/track/$TRACK_ID", opened.dataString)
        assertEquals("com.example.browser", opened.component?.packageName ?: opened.`package`)
    }

    @Test
    fun fallbackWithSeveralBrowsersShowsAChooserWithoutCrosstune() {
        LinkInterception(app).setEnabled(MusicService.SPOTIFY, true)
        shadowOf(app).checkActivities(true)
        installActivity(ComponentName("com.example.browser", "com.example.browser.Browser"), browserFilter())
        installActivity(ComponentName("com.example.other", "com.example.other.Browser"), browserFilter())
        // Every device has the system chooser; Robolectric needs it registered once activities are checked.
        installActivity(
            ComponentName("android", "com.android.internal.app.ChooserActivity"),
            IntentFilter(Intent.ACTION_CHOOSER).apply { addCategory(Intent.CATEGORY_DEFAULT) }
        )
        respondWithTrack("Choose", "Artist · Song")
        launch()
        resolveTyped()

        click(string(R.string.open_in_spotify))

        val opened = nextStartedActivity()!!
        assertEquals(Intent.ACTION_CHOOSER, opened.action)
        @Suppress("DEPRECATION")
        val excluded = opened.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS)!!.map { (it as ComponentName).className }
        assertTrue(excluded.toString(), "${LinkInterception.ALIAS_PREFIX}SPOTIFY" in excluded)
    }

    // endregion

    // region settings

    @Test
    fun turningOnASourceInterceptsItsLinksAndAsksAndroidForPermission() {
        prefs().edit().putBoolean("link_settings_helper_dismissed", true).commit()
        launch()
        click(string(R.string.settings_button))
        assertTextAbsent(string(R.string.link_settings_helper_title))

        composeRule.onNode(hasText(string(R.string.target_youtube)) and isToggleable()).performClick()
        composeRule.waitForIdle()

        assertTrue(LinkInterception(app).isEnabled(MusicService.YOUTUBE))
        assertTextShown(string(R.string.link_settings_helper_title))
        composeRule.onNode(hasText(string(R.string.target_youtube)) and isToggleable()).performClick()
        composeRule.waitForIdle()
        assertFalse(LinkInterception(app).isEnabled(MusicService.YOUTUBE))
        // Turning a source off doesn't hide an unanswered permission hint.
        assertTextShown(string(R.string.link_settings_helper_title))
    }

    @Test
    fun perSourceRuleSendsThatServicesLinksElsewhere() {
        fake.handler = { request ->
            FakeSpotify.html(request, """{"title":"The Weeknd - Blinding Lights (Official Video)","author_name":"TheWeekndVEVO"}""")
        }
        launch()
        click(string(R.string.settings_button))
        // The YouTube row's dropdown is the third "Opens in" button (Spotify, YouTube Music, YouTube).
        composeRule.onAllNodes(hasText(string(R.string.rule_opens_in, string(R.string.rule_default, "YouTube Music"))))[2]
            .performClick()
        composeRule.waitForIdle()
        // Apple Music is also a source row; the dropdown item is the last match.
        composeRule.onAllNodes(hasText(string(R.string.target_apple_music))).onLast().performClick()
        composeRule.waitForIdle()
        assertTextShown(string(R.string.rule_opens_in, "Apple Music"))
        click(string(R.string.done_button))
        controller!!.pause().stop().destroy()

        val activity = launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://youtu.be/4NRXx6U8ABQ")))
        waitUntil { activity.isFinishing }
        val opened = nextStartedActivity()!!
        assertEquals("com.apple.android.music", opened.`package`)
        assertEquals("https://music.apple.com/search?term=Blinding%20Lights%20The%20Weeknd", opened.dataString)

        // Spotify links still use the default.
        assertNull(DestinationStore(prefs()).rule(MusicService.SPOTIFY))
    }

    @Test
    fun ruleCanBeResetToTheDefault() {
        DestinationStore(prefs()).setRule(MusicService.SPOTIFY, Destination.Service(MusicService.DEEZER))
        launch()
        click(string(R.string.settings_button))

        click(string(R.string.rule_opens_in, "Deezer"))
        click(string(R.string.rule_default, "YouTube Music"))

        assertNull(DestinationStore(prefs()).rule(MusicService.SPOTIFY))
    }

    @Test
    fun customDestinationsCanBeAddedUsedAndRemoved() {
        shadowOf(app).checkActivities(true)
        installActivity(ComponentName("com.example.browser", "com.example.browser.Browser"), browserFilter())
        respondWithTrack("Custom Song", "Artist · Song")
        launch()
        click(string(R.string.settings_button))

        click(string(R.string.add_custom_destination_button))
        composeRule.onNode(hasSetTextAction() and hasText(string(R.string.custom_name_label))).performTextReplacement("Lyrics")
        composeRule.onNode(hasSetTextAction() and hasText(string(R.string.custom_template_label)))
            .performTextReplacement("https://lyrics.example/search")
        click(string(R.string.add_button))
        assertTextShown(string(R.string.custom_template_invalid))

        composeRule.onNode(hasSetTextAction() and hasText(string(R.string.custom_template_label)))
            .performTextReplacement("https://lyrics.example/search?q={query}")
        assertTextAbsent(string(R.string.custom_template_invalid))
        click(string(R.string.add_button))
        assertTextShown("https://lyrics.example/search?q={query}")
        click(string(R.string.done_button))

        click("Lyrics")
        resolveTyped()
        click(string(R.string.open_in_custom, "Lyrics"))
        val opened = nextStartedActivity()!!
        assertEquals("https://lyrics.example/search?q=Custom%20Song%20Artist", opened.dataString)
        assertEquals(
            "com.example.browser",
            app.packageManager.resolveActivity(opened, 0)!!.activityInfo.packageName
        )

        click(string(R.string.settings_button))
        click(string(R.string.remove_button))
        assertTextAbsent("https://lyrics.example/search?q={query}")
        click(string(R.string.done_button))
        assertTextAbsent("Lyrics")
        assertTextShown(string(R.string.open_in_youtube_music))
    }

    @Test
    fun customDestinationWithAnAppSchemeOpensDirectly() {
        DestinationStore(prefs()).apply { setDefault(addCustom("Player", "player://search/{query}")) }
        respondWithTrack("Scheme", "Artist · Song")
        launch()
        resolveTyped()

        click(string(R.string.open_in_custom, "Player"))

        val opened = nextStartedActivity()!!
        assertEquals("player://search/Scheme%20Artist", opened.dataString)
        assertNull(opened.`package`)
    }

    @Test
    fun addingACustomDestinationCanBeCancelled() {
        launch()
        click(string(R.string.settings_button))
        click(string(R.string.add_custom_destination_button))
        click(string(R.string.cancel_button))

        assertTextAbsent(string(R.string.custom_name_label))
        assertTrue(DestinationStore(prefs()).customDestinations().isEmpty())
    }

    @Test
    fun systemBackLeavesSettings() {
        val activity = launch()
        click(string(R.string.settings_button))
        assertTextShown(string(R.string.settings_links_title))

        activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()

        assertTextAbsent(string(R.string.settings_links_title))
        assertFalse(activity.isFinishing)
    }

    @Test
    fun interceptedPagesCrosstuneCantConvertGoStraightToTheirApp() {
        val activity = launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://soundcloud.com/discover")))

        val opened = nextStartedActivity()!!
        assertEquals("com.soundcloud.android", opened.`package`)
        assertEquals("https://soundcloud.com/discover", opened.dataString)
        waitUntil { activity.isFinishing }
        assertTrue(fake.requestedUrls.isEmpty())
    }

    // endregion

    // region first-run setup

    private fun freshInstall() {
        prefs().edit().clear().commit()
    }

    private fun toggleRow(label: String) {
        composeRule.onNode(hasText(label) and isToggleable()).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun firstLaunchWalksThroughSetupAndAppliesTheChoices() {
        freshInstall()
        FakeDomainVerification.install(app) {
            mapOf("www.youtube.com" to DomainVerificationUserState.DOMAIN_STATE_SELECTED)
        }
        launch()
        assertTextShown(string(R.string.setup_welcome_title))
        assertFalse(prefs().getBoolean("setup_complete", true))

        click(string(R.string.setup_get_started))
        toggleRow(string(R.string.target_youtube))
        toggleRow(string(R.string.service_spotify))
        toggleRow(string(R.string.service_spotify))
        assertEquals(
            setOf(MusicService.YOUTUBE),
            MusicService.entries.filter { LinkInterception(app).isEnabled(it) }.toSet()
        )

        click(string(R.string.next_button))
        // No built-in default: a destination has to be picked before moving on.
        composeRule.onNodeWithText(string(R.string.next_button)).assertIsNotEnabled()
        click(string(R.string.target_deezer))
        composeRule.onNodeWithText(string(R.string.next_button)).assertIsEnabled()

        click(string(R.string.next_button))
        assertTextShown(string(R.string.setup_allowed))
        click(string(R.string.open_link_settings_button))
        assertEquals(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, nextStartedActivity()!!.action)

        click(string(R.string.setup_finish))
        assertTextShown(string(R.string.app_tagline))
        assertTextAbsent(string(R.string.link_settings_helper_title))
        assertTrue(prefs().getBoolean("setup_complete", false))
        assertEquals(Destination.Service(MusicService.DEEZER), DestinationStore(prefs()).defaultDestination())

        controller!!.pause().stop().destroy()
        launch()
        assertTextAbsent(string(R.string.setup_welcome_title))
    }

    @Test
    fun setupShowsWhatStillNeedsAllowingWhenAndroidReportsIt() {
        freshInstall()
        var states = mapOf("open.spotify.com" to DomainVerificationUserState.DOMAIN_STATE_NONE)
        FakeDomainVerification.install(app) { states }
        LinkInterception(app).setEnabled(MusicService.SPOTIFY, true)
        DestinationStore(prefs()).setDefault(Destination.Service(MusicService.TIDAL))
        // A saved default looks like an older install, so mark setup as still pending explicitly.
        prefs().edit().putBoolean("setup_complete", false).commit()
        launch()
        click(string(R.string.setup_get_started))
        click(string(R.string.next_button))
        click(string(R.string.next_button))
        assertTextShown(string(R.string.setup_not_allowed))

        // Returning from Android's settings refreshes the status.
        states = mapOf("open.spotify.com" to DomainVerificationUserState.DOMAIN_STATE_SELECTED)
        controller!!.pause().resume()
        composeRule.waitForIdle()
        assertTextShown(string(R.string.setup_allowed))
    }

    @Test
    fun setupCanGoBackAndHandlesPickingNoServices() {
        freshInstall()
        launch()
        click(string(R.string.setup_get_started))
        click(string(R.string.back_button))
        assertTextShown(string(R.string.setup_welcome_title))

        click(string(R.string.setup_get_started))
        click(string(R.string.next_button))
        click(string(R.string.target_youtube_music))
        click(string(R.string.next_button))
        assertTextShown(string(R.string.setup_allow_none))
        assertTextAbsent(string(R.string.open_link_settings_button))
    }

    @Test
    fun setupListsInstalledAppsFirst() {
        freshInstall()
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = "com.soundcloud.android" })
        launch()
        click(string(R.string.setup_get_started))

        val rows = composeRule.onAllNodes(isToggleable()).fetchSemanticsNodes()
        val firstLabel = rows.first().config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString { it.text }
        assertEquals("${string(R.string.target_soundcloud)}, ${string(R.string.setup_installed)}", firstLabel)
        assertTextShown(string(R.string.setup_installed))
    }

    @Test
    fun linkOpenedBeforeSetupAsksWhereToGoThenSetupFollows() {
        freshInstall()
        respondWithTrack("Early", "Artist · Song")
        val activity = launch(trackLink())

        waitForText(string(R.string.picker_title))
        assertTextAbsent(string(R.string.setup_welcome_title))
        click(string(R.string.open_in_tidal))
        waitUntil { activity.isFinishing }
        assertEquals("com.aspiro.tidal", nextStartedActivity()!!.`package`)
        controller!!.pause().stop().destroy()

        // History from that link must not be mistaken for an install from before setup existed.
        launch()
        assertTextShown(string(R.string.setup_welcome_title))
    }

    @Test
    fun updatingFromAVersionWithoutSetupKeepsSpotifyLinksAndSkipsSetup() {
        freshInstall()
        prefs().edit().putBoolean("link_settings_helper_dismissed", true).commit()
        launch()

        assertTextAbsent(string(R.string.setup_welcome_title))
        assertTrue(LinkInterception(app).isEnabled(MusicService.SPOTIFY))
        assertTrue(prefs().getBoolean("setup_complete", false))
    }

    // endregion

    private companion object {
        const val TRACK_ID = "11dFghVXANMlKmJXsNCbNl"
        const val OTHER_TRACK_ID = "0VjIjW4GlUZAMYd2vXMi3b"
        const val TIMEOUT_MS = 5_000L
    }
}
