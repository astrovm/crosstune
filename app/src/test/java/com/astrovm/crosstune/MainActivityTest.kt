package com.astrovm.crosstune

import android.app.Application
import android.content.ClipData
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onLast
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
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
        // Most tests exercise the main screen with search fallback; defaults and exact matching have their own tests.
        prefs().edit().putBoolean("setup_complete", true).putBoolean("exact_match", false).commit()
    }

    @After
    fun tearDown() {
        runCatching { controller?.pause()?.stop()?.destroy() }
        MainActivity.httpClientFactory = ::httpClient
    }

    // region helpers

    /** What the Quick Settings tile and launcher shortcut send. */
    private fun pasteIntent() =
        Intent(MainActivity.ACTION_PASTE_FROM_CLIPBOARD).setClassName(app, MainActivity.PASTE_ALIAS)

    private fun launch(intent: Intent = Intent(Intent.ACTION_MAIN)): MainActivity {
        if (intent.component == null) intent.setClass(app, MainActivity::class.java)
        val built = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        controller = built
        composeRule.waitForIdle()
        return built.get()
    }

    private fun string(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun prefs() = app.getSharedPreferences("crosstune_preferences", Context.MODE_PRIVATE)

    /** Buttons are found by their text, or by their accessibility label when they're icons. */
    private fun clickResultText() {
        composeRule.onNodeWithTag(RESULT_TEXT_TAG).performClick()
        composeRule.waitForIdle()
    }

    private fun click(text: String) {
        composeRule.onNode(hasText(resultActionText(text)) or hasContentDescription(text)).performClick()
        composeRule.waitForIdle()
    }

    /** A prepared search uses the destination-specific Search label; source buttons keep Open. */
    private fun resultActionText(text: String): String {
        if (composeRule.onAllNodesWithTextCount(text) > 0) return text
        val service = MusicService.entries.firstOrNull { string(it.openLabelRes) == text }
        val custom = DestinationStore(prefs()).customDestinations().firstOrNull { string(R.string.open_in_custom, it.name) == text }
        val label = service?.let { string(it.labelRes) } ?: custom?.name ?: return text
        return string(R.string.search_in_destination, label)
    }

    /** Picks the default destination from the "Opens in" menu under the link field. */
    private fun chooseDefault(label: String) {
        composeRule.onNodeWithTag(DEFAULT_MENU_TAG).performClick()
        composeRule.waitForIdle()
        // The menu item is the last match: the menu button itself may show the same name.
        composeRule.onAllNodesWithText(label).onLast().performClick()
        composeRule.waitForIdle()
    }

    private fun assertResultShown() {
        composeRule.onNodeWithTag(RESULT_TAG).assertExists()
    }

    private fun assertResultAbsent() {
        composeRule.onNodeWithTag(RESULT_TAG).assertDoesNotExist()
    }

    private fun waitForResult() {
        composeRule.waitUntil(TIMEOUT_MS) { composeRule.onAllNodesWithTag(RESULT_TAG).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForDestinationReady() {
        composeRule.waitUntil(TIMEOUT_MS) {
            !composeRule.onNodeWithText(string(R.string.copy_button)).fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
        }
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
        assertTrue("'$text' not shown", composeRule.onAllNodesWithTextCount(resultActionText(text)) > 0)
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
        click(string(R.string.back_button))
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

        clickResultText()

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
    fun textThatIsNotALinkReplacesThePreviousResult() {
        respondWithTrack("Old Song", "Artist · Song")
        launch()
        resolveTyped()
        assertResultShown()

        typeUrl("just some words")
        click(string(R.string.resolve_button))

        // The error stands alone: no old song above it, and no offer to open that song.
        assertTextShown(string(R.string.error_invalid_url))
        assertResultAbsent()
        assertTextAbsent(string(R.string.open_in_spotify))
    }

    @Test
    fun aSharedNonLinkReplacesThePreviousResult() {
        respondWithTrack("Old Song", "Artist · Song")
        launch()
        resolveTyped()
        assertResultShown()

        val onNewIntent = MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
        onNewIntent.isAccessible = true
        onNewIntent.invoke(
            controller!!.get(),
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "not a music link")
            }
        )
        composeRule.waitForIdle()

        assertTextShown(string(R.string.error_invalid_url))
        assertResultAbsent()
        assertTextAbsent(string(R.string.open_in_spotify))
    }

    @Test
    fun aSharedLinkLeavesSettings() {
        launch()
        click(string(R.string.settings_button))
        assertTextShown(string(R.string.setting_exact_match))

        val onNewIntent = MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
        onNewIntent.isAccessible = true
        onNewIntent.invoke(
            controller!!.get(),
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "not a music link")
            }
        )
        composeRule.waitForIdle()

        assertTextAbsent(string(R.string.setting_exact_match))
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

        assertResultShown()
        assertTextShown("Carly Rae Jepsen")
        assertFalse(activity.isFinishing)
        // Manual resolution keeps the user's input as typed.
        composeRule.onNode(hasSetTextAction()).assertExists()

        click(string(R.string.open_in_youtube_music))
        val opened = nextStartedActivity()
        assertEquals("com.google.android.apps.youtube.music", opened!!.`package`)
        assertFalse(activity.isFinishing)

        clickResultText()
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        assertEquals(
            "Cut To The Feeling Carly Rae Jepsen",
            clipboard.primaryClip!!.getItemAt(0).text.toString()
        )
        // Android 13+ confirms clipboard writes itself, so the app stays quiet.
        assertNull(ShadowToast.getTextOfLatestToast())

        click(string(R.string.copy_button))
        assertEquals(
            "https://music.youtube.com/search?q=Cut%20To%20The%20Feeling%20Carly%20Rae%20Jepsen",
            clipboard.primaryClip!!.getItemAt(0).text.toString()
        )

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

        chooseDefault(string(R.string.target_youtube))
        click(string(R.string.make_default))
        assertEquals("YOUTUBE", prefs().getString("default_target", null))
        assertTextShown(string(R.string.open_in_youtube))
        click(string(R.string.open_in_youtube))
        assertEquals("com.google.android.youtube", nextStartedActivity()!!.`package`)

        chooseDefault(string(R.string.target_youtube_music))
        click(string(R.string.make_default))
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

        clickResultText()
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
            // An empty field offers Paste instead of Clear; typing clears the error too.
            if (input.isEmpty()) typeUrl("x")
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
    fun pasteLooksUpTheCopiedLinkWithoutOpeningIt() {
        respondWithTrack("Pasted Song", "Pasted Artist · Song")
        val activity = launch()
        app.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("link", "Listen: https://open.spotify.com/track/$TRACK_ID?si=x"))

        click(string(R.string.paste_button))
        waitForText("Pasted Song")

        composeRule.onNode(hasSetTextAction()).assert(hasText("https://open.spotify.com/track/$TRACK_ID?si=x"))
        assertResultShown()
        assertNull(shadowOf(app).peekNextStartedActivity())
        assertFalse(activity.isFinishing)
    }

    @Test
    fun pasteWithAnEmptyClipboardExplains() {
        launch()
        app.getSystemService(ClipboardManager::class.java).clearPrimaryClip()

        click(string(R.string.paste_button))

        assertTextShown(string(R.string.error_clipboard_empty))
        assertTrue(fake.requestedUrls.isEmpty())
    }

    @Test
    fun resultShowsTheCoverWhenItLoads() {
        fake.handler = { request ->
            if (request.url.host == "img.example") {
                FakeSpotify.image(request, FakeSpotify.png())
            } else {
                FakeSpotify.html(request, FakeSpotify.trackPage("Cover Song", "Cover Artist · Song", "https://img.example/cover.jpg"))
            }
        }
        launch()

        typeUrl("https://open.spotify.com/track/$TRACK_ID")
        click(string(R.string.resolve_button))
        waitForText("Cover Song")
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodes(hasTestTag(ARTWORK_TAG)).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("https://img.example/cover.jpg", HistoryStore(prefs()).load().first().metadata.artworkUrl)
    }

    @Test
    fun resultHasNoCoverWhenItFailsToLoad() {
        fake.handler = { request ->
            if (request.url.host == "img.example") {
                FakeSpotify.image(request, ByteArray(0), code = 404)
            } else {
                FakeSpotify.html(request, FakeSpotify.trackPage("Plain Song", "Plain Artist · Song", "https://img.example/cover.jpg"))
            }
        }
        launch()

        typeUrl("https://open.spotify.com/track/$TRACK_ID")
        click(string(R.string.resolve_button))
        waitForText("Plain Song")
        composeRule.waitUntil(TIMEOUT_MS) { "https://img.example/cover.jpg" in fake.requestedUrls }
        composeRule.waitForIdle()
        composeRule.onNode(hasTestTag(ARTWORK_TAG)).assertDoesNotExist()
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
        chooseDefault(string(R.string.target_youtube))

        typeUrl(TRACK_ID)
        click(string(R.string.resolve_button))
        waitForText("Clear Me")
        assertTextShown(string(R.string.open_in_youtube))

        click(string(R.string.clear_button))
        assertResultAbsent()
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
    fun settingsShowTheAppVersion() {
        shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).versionName = "v9.8.7"
        launch()
        assertTextAbsent("v9.8.7")
        click(string(R.string.settings_button))
        assertTextShown(string(R.string.settings_version))
        assertTextShown("v9.8.7")
    }

    @Test
    fun theLogoSitsBesideTheTitleOnTheHomeScreen() {
        launch()
        assertTextShown(string(R.string.app_name))
        composeRule.onNodeWithTag(LOGO_TAG).assertIsDisplayed()
        click(string(R.string.settings_button))
        composeRule.onNodeWithTag(LOGO_TAG).assertDoesNotExist()
    }

    @Test
    fun madeByLinkOpensRepository() {
        launch()
        click(string(R.string.settings_button))
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
            waitForResult()
            assertEquals(input, listOf("https://open.spotify.com/track/$TRACK_ID"), fake.requestedUrls)
            assertTextAbsent(string(R.string.error_invalid_url))
            click(string(R.string.clear_button))
            assertResultAbsent()
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
            "notspotify.link/AbCdEf"
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

        assertResultAbsent()
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

        clickResultText()
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        assertEquals("Rock & Roll AC/DC", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    fun titleOnlyTrackOpensSearchWithoutArtist() {
        respondWithTrack("Solo", null)
        launch()
        chooseDefault(string(R.string.target_youtube))

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
        chooseDefault(string(R.string.target_youtube))
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
        composeRule.onNode(hasContentDescription(string(R.string.clear_button))).assertIsNotEnabled()
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
        waitForResult()
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
        assertTextShown("Album from Spotify")
        click(string(R.string.open_in_youtube_music))
        assertEquals("https://music.youtube.com/search?q=After%20Hours%20The%20Weeknd", nextStartedActivity()!!.dataString)

        click(string(R.string.clear_button))
        resolveTyped("spotify:artist:$TRACK_ID")
        click(string(R.string.open_in_youtube_music))
        assertEquals("https://music.youtube.com/search?q=The%20Weeknd", nextStartedActivity()!!.dataString)

        click(string(R.string.clear_button))
        resolveTyped("https://open.spotify.com/playlist/$TRACK_ID")
        assertTextShown("Playlist from Spotify")
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
            chooseDefault(string(label))
            composeRule.onNodeWithTag(DEFAULT_MENU_TAG).assert(hasText(string(label)))
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
    fun askEachTimeMatchesOnlyTheChosenDestination() {
        prefs().edit().putBoolean("ask_each_time", true).putBoolean("exact_match", true)
            .putString("default_target", "DEEZER").commit()
        fake.handler = { request ->
            if (request.url.host == "itunes.apple.com") {
                FakeSpotify.html(request,
                    """{"results":[{"trackName":"Pick Me","artistName":"Artist","trackViewUrl":"https://music.apple.com/us/song/1"}]}""")
            } else {
                FakeSpotify.html(request, FakeSpotify.trackPage("Pick Me", "Artist · Song"))
            }
        }
        val activity = launch(trackLink())
        waitForText(string(R.string.picker_title))
        assertEquals(listOf("https://open.spotify.com/track/$TRACK_ID"), fake.requestedUrls)
        assertTextAbsent(string(R.string.matching_text))
        assertNull(nextStartedActivity())

        // Refreshing the stored destination while the picker is open must still wait for a choice.
        ViewModelProvider(activity)[MainViewModel::class.java].selectDefault(Destination.Service(MusicService.YOUTUBE_MUSIC))
        composeRule.waitForIdle()
        assertEquals(1, fake.requestedUrls.size)

        click(string(R.string.open_in_apple_music))
        waitUntil { activity.isFinishing }
        assertEquals("https://music.apple.com/us/song/1", nextStartedActivity()!!.dataString)
        assertEquals(2, fake.requestedUrls.size)
        assertTrue(fake.requestedUrls.last().startsWith("https://itunes.apple.com/search?"))
    }

    @Test
    fun dismissingTheDestinationPickerKeepsTheResult() {
        prefs().edit().putBoolean("ask_each_time", true).commit()
        respondWithTrack("Stay", "Artist · Song")
        val activity = launch(trackLink())
        waitForText(string(R.string.picker_title))

        click(string(R.string.cancel_button))

        assertTextAbsent(string(R.string.picker_title))
        assertResultShown()
        assertFalse(activity.isFinishing)
        inSettings { click(string(R.string.setting_ask_each_time)) }
        assertFalse(prefs().getBoolean("ask_each_time", true))
    }

    @Test
    fun dismissingThePickerPreparesTheDisplayedDestinationWithoutOpeningIt() {
        prefs().edit().putBoolean("ask_each_time", true).putBoolean("exact_match", true)
            .putString("default_target", "DEEZER").commit()
        fake.handler = { request ->
            if (request.url.host == "api.deezer.com") {
                FakeSpotify.html(request,
                    """{"data":[{"title":"Stay","artist":{"name":"Artist"},"link":"https://www.deezer.com/track/1"}]}""")
            } else {
                FakeSpotify.html(request, FakeSpotify.trackPage("Stay", "Artist · Song"))
            }
        }
        val activity = launch(trackLink())
        waitForText(string(R.string.picker_title))
        assertEquals(1, fake.requestedUrls.size)
        click(string(R.string.cancel_button))
        waitForDestinationReady()
        click(string(R.string.copy_button))
        assertEquals("https://www.deezer.com/track/1",
            app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        assertEquals(2, fake.requestedUrls.size)
        assertNull(nextStartedActivity())
        assertFalse(activity.isFinishing)
    }

    @Test
    fun exactMatchingDefaultsToEnabledAndKeepsAnExplicitOptOutAcrossLaunches() {
        prefs().edit().remove("exact_match").putString("default_target", "DEEZER").commit()
        fake.handler = { request ->
            if (request.url.host == "api.deezer.com") {
                FakeSpotify.html(request,
                    """{"data":[{"title":"Default","artist":{"name":"Artist"},"link":"https://www.deezer.com/track/1"}]}""")
            } else {
                FakeSpotify.html(request, FakeSpotify.trackPage("Default", "Artist · Song"))
            }
        }
        val activity = launch()
        assertTrue(ViewModelProvider(activity)[MainViewModel::class.java].uiState.exactMatch)
        resolveTyped()
        waitForDestinationReady()
        click(string(R.string.copy_button))
        assertEquals("https://www.deezer.com/track/1",
            app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())

        inSettings { click(string(R.string.setting_exact_match)) }
        controller!!.pause().stop().destroy()
        fake.requestedUrls.clear()
        val restored = launch()
        assertFalse(ViewModelProvider(restored)[MainViewModel::class.java].uiState.exactMatch)
        resolveTyped()
        click(string(R.string.copy_button))
        assertEquals("https://www.deezer.com/search/Default%20Artist",
            app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        assertEquals(listOf("https://open.spotify.com/track/$TRACK_ID"), fake.requestedUrls)
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

        chooseDefault(string(R.string.target_apple_music))
        waitForDestinationReady()
        click(string(R.string.copy_button))
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        assertEquals("https://music.apple.com/us/song/1", clipboard.primaryClip!!.getItemAt(0).text.toString())
        click(string(R.string.share_search_button))
        val shared = nextStartedActivity()!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("https://music.apple.com/us/song/1", shared.getStringExtra(Intent.EXTRA_TEXT))
        val requestsBeforeOpen = fake.requestedUrls.size
        click(string(R.string.open_in_apple_music))
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://music.apple.com/us/song/1", nextStartedActivity()!!.dataString)
        assertEquals(requestsBeforeOpen, fake.requestedUrls.size)

        chooseDefault(string(R.string.target_deezer))
        waitForDestinationReady()
        click(string(R.string.open_in_deezer))
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://www.deezer.com/search/Exact%20Artist", nextStartedActivity()!!.dataString)

        val requestsBeforeReturning = fake.requestedUrls.size
        chooseDefault(string(R.string.target_apple_music))
        click(string(R.string.copy_button))
        assertEquals("https://music.apple.com/us/song/1", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(requestsBeforeReturning, fake.requestedUrls.size)

        inSettings { click(string(R.string.setting_exact_match)) }
        assertFalse(prefs().getBoolean("exact_match", true))
        click(string(R.string.copy_button))
        assertEquals("https://music.apple.com/search?term=Exact%20Artist", clipboard.primaryClip!!.getItemAt(0).text.toString())
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
        val activity = launch()
        resolveTyped()

        waitForText(string(R.string.matching_text))
        composeRule.onNodeWithText(string(R.string.resolve_button)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.open_in_deezer)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.copy_button)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.share_search_button)).assertIsNotEnabled()
        assertNull(ViewModelProvider(activity)[MainViewModel::class.java].destinationUrl())
        assertNull(nextStartedActivity())

        release.countDown()
        waitUntil { composeRule.onAllNodesWithTextCount(string(R.string.matching_text)) == 0 }
        click(string(R.string.copy_button))
        assertEquals(
            "https://www.deezer.com/search/Slow%20Artist",
            app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
        )
        val requestsBeforeOpen = fake.requestedUrls.size
        click(string(R.string.open_in_deezer))
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals(requestsBeforeOpen, fake.requestedUrls.size)
        composeRule.waitForIdle()
        assertTextAbsent(string(R.string.matching_text))
    }

    @Test
    fun youtubeMusicLinksArePreparedOnceAndCachedForRecentLinks() {
        prefs().edit().putBoolean("exact_match", true).commit()
        fake.handler = { request ->
            if (request.url.host == "music.youtube.com") {
                val title = if (fake.requestBodies.last().contains("Second")) "Second" else "First"
                val videoId = if (title == "Second") "second00000" else "first000000"
                FakeSpotify.html(request, """{"contents":[{"musicResponsiveListItemRenderer":{
                    "flexColumns":[
                        {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$title"}]}}},
                        {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist • Album • 3:00"}]}}}
                    ],"playlistItemData":{"videoId":"$videoId"}
                }}]}""")
            } else {
                val title = if (request.url.pathSegments.last() == OTHER_TRACK_ID) "Second" else "First"
                FakeSpotify.html(request, FakeSpotify.trackPage(title, "Artist · Song"))
            }
        }
        launch()
        resolveTyped()
        waitForDestinationReady()
        assertEquals(2, fake.requestedUrls.size)
        assertNull(nextStartedActivity())

        val clipboard = app.getSystemService(ClipboardManager::class.java)
        click(string(R.string.copy_button))
        assertEquals("https://music.youtube.com/watch?v=first000000", clipboard.primaryClip!!.getItemAt(0).text.toString())
        click(string(R.string.share_search_button))
        assertEquals(
            "https://music.youtube.com/watch?v=first000000",
            nextStartedActivity()!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!.getStringExtra(Intent.EXTRA_TEXT)
        )
        click(string(R.string.open_in_youtube_music))
        assertEquals("https://music.youtube.com/watch?v=first000000", nextStartedActivity()!!.dataString)
        assertEquals(2, fake.requestedUrls.size)

        resolveTyped(OTHER_TRACK_ID)
        waitForDestinationReady()
        click(string(R.string.copy_button))
        assertEquals("https://music.youtube.com/watch?v=second00000", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(4, fake.requestedUrls.size)

        click("First")
        waitForDestinationReady()
        click(string(R.string.copy_button))
        assertEquals("https://music.youtube.com/watch?v=first000000", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(4, fake.requestedUrls.size)
    }

    @Test
    fun changingDestinationDuringAnIncomingMatchKeepsTheAutomaticOpen() {
        val release = CountDownLatch(1)
        prefs().edit().putBoolean("exact_match", true).putString("default_target", "DEEZER").commit()
        fake.handler = { request ->
            when (request.url.host) {
                "api.deezer.com" -> {
                    release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    FakeSpotify.html(request, """{"data":[]}""")
                }
                "itunes.apple.com" -> FakeSpotify.html(request,
                    """{"results":[{"trackName":"Switch","artistName":"Artist","trackViewUrl":"https://music.apple.com/us/song/1"}]}""")
                else -> FakeSpotify.html(request, FakeSpotify.trackPage("Switch", "Artist · Song"))
            }
        }
        val activity = launch(trackLink())
        waitForText(string(R.string.matching_text))
        chooseDefault(string(R.string.target_apple_music))
        waitUntil { activity.isFinishing }
        assertEquals("https://music.apple.com/us/song/1", nextStartedActivity()!!.dataString)
        release.countDown()
        Thread.sleep(200L)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(nextStartedActivity())
    }

    @Test
    fun aNewIncomingLinkCancelsTheOldMatchWithoutReusingItsUrl() {
        val release = CountDownLatch(1)
        prefs().edit().putBoolean("exact_match", true).putString("default_target", "DEEZER").commit()
        fake.handler = { request ->
            if (request.url.host == "api.deezer.com") {
                val first = request.url.queryParameter("q") == "First Artist"
                if (first) release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                val title = if (first) "First" else "Second"
                val id = if (first) 1 else 2
                FakeSpotify.html(request,
                    """{"data":[{"title":"$title","artist":{"name":"Artist"},"link":"https://www.deezer.com/track/$id"}]}""")
            } else {
                val title = if (request.url.pathSegments.last() == OTHER_TRACK_ID) "Second" else "First"
                FakeSpotify.html(request, FakeSpotify.trackPage(title, "Artist · Song"))
            }
        }
        val activity = launch(trackLink())
        waitForText(string(R.string.matching_text))
        controller!!.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/track/$OTHER_TRACK_ID")))
        waitUntil { activity.isFinishing }
        assertEquals("https://www.deezer.com/track/2", nextStartedActivity()!!.dataString)
        release.countDown()
        Thread.sleep(200L)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(nextStartedActivity())
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
        assertResultAbsent()
        fake.requestedUrls.clear()

        click("First Song")
        assertResultShown()
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

        launch(pasteIntent())
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
        launch(pasteIntent())
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
        assertTextShown("Song from YouTube Music")
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
        click(string(R.string.back_button))
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
        click(string(R.string.back_button))

        chooseDefault("Lyrics")
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
        click(string(R.string.back_button))
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
            LinkInterception.HOSTS.getValue(MusicService.YOUTUBE).associateWith { DomainVerificationUserState.DOMAIN_STATE_SELECTED }
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
        // Android lists every service's links, so setup names the ones to select.
        assertTextShown("open.spotify.com, spotify.link, www.spotify.link")

        // Returning from Android's settings refreshes the status; short links still need allowing.
        states = mapOf("open.spotify.com" to DomainVerificationUserState.DOMAIN_STATE_SELECTED)
        controller!!.pause().resume()
        composeRule.waitForIdle()
        assertTextShown(string(R.string.setup_not_allowed))
        assertTextShown("spotify.link, www.spotify.link")

        states = LinkInterception.HOSTS.getValue(MusicService.SPOTIFY)
            .associateWith { DomainVerificationUserState.DOMAIN_STATE_SELECTED }
        controller!!.pause().resume()
        composeRule.waitForIdle()
        assertTextShown(string(R.string.setup_allowed))
        assertTextAbsent("spotify.link, www.spotify.link")
    }

    @Test
    fun anInstalledAppThatStillTakesTheLinksIsFlaggedAndOpensItsOwnSettings() {
        val spotify = MusicService.SPOTIFY.packageName
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = spotify })
        var appAllowsLinks = true
        FakeDomainVerification.installPerPackage(app, linkHandlingAllowed = { it != spotify || appAllowsLinks }) { packageName ->
            val approved = DomainVerificationUserState.DOMAIN_STATE_VERIFIED
            if (packageName == spotify) {
                mapOf("open.spotify.com" to approved)
            } else {
                LinkInterception.HOSTS.getValue(MusicService.SPOTIFY).associateWith { approved }
            }
        }
        LinkInterception(app).setEnabled(MusicService.SPOTIFY, true)
        prefs().edit().putBoolean("link_settings_helper_dismissed", true).commit()
        launch()

        val openButton = string(R.string.open_app_link_settings_button, string(R.string.service_spotify))
        assertTextShown(string(R.string.blocking_apps_title))
        click(openButton)
        val started = nextStartedActivity()!!
        assertEquals(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, started.action)
        assertEquals(Uri.parse("package:$spotify"), started.data)

        // Back from Android's settings with the app's link handling off, the notice is gone.
        appAllowsLinks = false
        controller!!.pause().resume()
        composeRule.waitForIdle()
        assertTextAbsent(string(R.string.blocking_apps_title))
    }

    @Test
    fun noBlockingNoticeForAppsCrosstuneDoesNotIntercept() {
        val spotify = MusicService.SPOTIFY.packageName
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = spotify })
        FakeDomainVerification.installPerPackage(app) {
            mapOf("open.spotify.com" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED)
        }
        launch()
        assertTextAbsent(string(R.string.blocking_apps_title))
    }

    /** Setup as if Spotify were picked and its app installed and claiming the links; returns a way to change its switch. */
    private fun setupWithSpotifyAppInTheWay(): (Boolean) -> Unit {
        val spotify = MusicService.SPOTIFY.packageName
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = spotify })
        var appAllowsLinks = true
        FakeDomainVerification.installPerPackage(app, linkHandlingAllowed = { it != spotify || appAllowsLinks }) {
            LinkInterception.HOSTS.getValue(MusicService.SPOTIFY).associateWith { DomainVerificationUserState.DOMAIN_STATE_VERIFIED }
        }
        freshInstall()
        LinkInterception(app).setEnabled(MusicService.SPOTIFY, true)
        DestinationStore(prefs()).setDefault(Destination.Service(MusicService.TIDAL))
        // A saved default looks like an older install, so mark setup as still pending explicitly.
        prefs().edit().putBoolean("setup_complete", false).commit()
        return { allowed -> appAllowsLinks = allowed }
    }

    @Test
    fun setupAsksToStopAnInstalledAppTakingTheLinksAsAFourthStep() {
        val setAppAllowsLinks = setupWithSpotifyAppInTheWay()
        launch()
        click(string(R.string.setup_get_started))
        assertTextShown(string(R.string.setup_step, 1, 4))
        click(string(R.string.next_button))
        click(string(R.string.next_button))
        assertTextShown(string(R.string.setup_allow_step_tick))
        // Stopping the app has its own step, so the allow step no longer carries that task.
        assertTextAbsent(string(R.string.setup_apps_title))
        click(string(R.string.next_button))

        assertTextShown(string(R.string.setup_step, 4, 4))
        assertTextShown(string(R.string.setup_apps_title))
        assertTextShown(string(R.string.setup_still_opens))
        click(string(R.string.open_app_link_settings_button, string(R.string.service_spotify)))
        val started = nextStartedActivity()!!
        assertEquals(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, started.action)
        assertEquals(Uri.parse("package:${MusicService.SPOTIFY.packageName}"), started.data)

        // Fixed in Android's settings: the row stays, marked done, rather than vanishing.
        setAppAllowsLinks(false)
        controller!!.pause().resume()
        composeRule.waitForIdle()
        assertTextShown(string(R.string.setup_done))
        assertTextAbsent(string(R.string.setup_still_opens))
        assertTextShown(string(R.string.service_spotify))

        click(string(R.string.setup_finish))
        assertTrue(prefs().getBoolean("setup_complete", false))
    }

    @Test
    fun setupHasThreeStepsWhenNoInstalledAppIsInTheWay() {
        freshInstall()
        LinkInterception(app).setEnabled(MusicService.SPOTIFY, true)
        DestinationStore(prefs()).setDefault(Destination.Service(MusicService.TIDAL))
        prefs().edit().putBoolean("setup_complete", false).commit()
        launch()
        click(string(R.string.setup_get_started))
        assertTextShown(string(R.string.setup_step, 1, 3))
        click(string(R.string.next_button))
        click(string(R.string.next_button))
        assertTextShown(string(R.string.setup_step, 3, 3))
        assertTextShown(string(R.string.setup_finish))
    }

    @Test
    @Config(sdk = [30])
    fun beforeAndroid12SetupListsInstalledAppsWithoutAStatus() {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = MusicService.SPOTIFY.packageName })
        freshInstall()
        LinkInterception(app).setEnabled(MusicService.SPOTIFY, true)
        DestinationStore(prefs()).setDefault(Destination.Service(MusicService.TIDAL))
        prefs().edit().putBoolean("setup_complete", false).commit()
        launch()
        click(string(R.string.setup_get_started))
        click(string(R.string.next_button))
        click(string(R.string.next_button))
        click(string(R.string.next_button))

        assertTextShown(string(R.string.setup_step, 4, 4))
        assertTextShown(string(R.string.open_app_link_settings_button, string(R.string.service_spotify)))
        // Android can't say whether the app still takes the links, so there is nothing to mark.
        assertTextAbsent(string(R.string.setup_still_opens))
        assertTextAbsent(string(R.string.setup_done))
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
        fake.handler = { request ->
            if (request.url.host == "api.deezer.com") {
                FakeSpotify.html(request,
                    """{"data":[{"title":"Early","artist":{"name":"Artist"},"link":"https://www.deezer.com/track/1"}]}""")
            } else {
                FakeSpotify.html(request, FakeSpotify.trackPage("Early", "Artist · Song"))
            }
        }
        val activity = launch(trackLink())

        waitForText(string(R.string.picker_title))
        assertTextAbsent(string(R.string.setup_welcome_title))
        assertTrue(ViewModelProvider(activity)[MainViewModel::class.java].uiState.exactMatch)
        assertEquals(listOf("https://open.spotify.com/track/$TRACK_ID"), fake.requestedUrls)
        click(string(R.string.open_in_deezer))
        waitUntil { activity.isFinishing }
        val opened = nextStartedActivity()!!
        assertEquals("deezer.android.app", opened.`package`)
        assertEquals("https://www.deezer.com/track/1", opened.dataString)
        assertEquals(2, fake.requestedUrls.size)
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

    // region review fixes

    @Test
    fun mainActivityIsSingleTopSoRepeatedLaunchesReachOnNewIntent() {
        val info = app.packageManager.getActivityInfo(ComponentName(app, MainActivity::class.java), 0)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, info.launchMode)

        val activity = launch()
        val delivered = trackLink()
        controller!!.newIntent(delivered)
        assertEquals(delivered, activity.intent)
    }

    @Test
    fun secondTileTapReadsTheNewClipboard() {
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        clipboard.clearPrimaryClip()
        launch(pasteIntent())
        controller!!.windowFocusChanged(true)
        assertTextShown(string(R.string.error_clipboard_empty))

        respondWithTrack("Second", "Artist · Song")
        clipboard.setPrimaryClip(ClipData.newPlainText("link", "https://open.spotify.com/track/$TRACK_ID"))
        controller!!.windowFocusChanged(false)
        controller!!.newIntent(pasteIntent())
        controller!!.windowFocusChanged(true)

        waitUntil { controller!!.get().isFinishing }
        assertEquals(listOf("https://open.spotify.com/track/$TRACK_ID"), fake.requestedUrls)
    }

    @Test
    fun otherAppsCantTriggerAClipboardRead() {
        app.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("link", "https://open.spotify.com/track/$TRACK_ID"))
        // Straight to MainActivity, not through the unexported alias.
        launch(Intent(MainActivity.ACTION_PASTE_FROM_CLIPBOARD))
        controller!!.windowFocusChanged(true)

        assertTrue(fake.requestedUrls.isEmpty())
        assertFalse(controller!!.get().isFinishing)
    }

    @Test
    fun incomingLinkIsLookedUpAgainAfterProcessDeath() {
        val release = CountDownLatch(1)
        fake.handler = { request ->
            release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            FakeSpotify.html(request, FakeSpotify.trackPage("Survivor", "Artist · Song"))
        }
        launch(trackLink())
        waitUntil { fake.requestedUrls.size == 1 }
        val saved = Bundle()
        controller!!.saveInstanceState(saved)
        controller!!.pause().stop().destroy()
        release.countDown()

        // A new controller has a new ViewModel, as after Android ends the process.
        fake.handler = { request -> FakeSpotify.html(request, FakeSpotify.trackPage("Survivor", "Artist · Song")) }
        val restored = Robolectric.buildActivity(MainActivity::class.java, trackLink().setClass(app, MainActivity::class.java))
            .setup(saved)
        controller = restored
        waitUntil { restored.get().isFinishing }
        assertEquals(2, fake.requestedUrls.size)
    }

    @Test
    fun typedLinkIsNotReopenedAfterProcessDeath() {
        respondWithTrack("Typed", "Artist · Song")
        launch()
        resolveTyped()
        val saved = Bundle()
        controller!!.saveInstanceState(saved)
        controller!!.pause().stop().destroy()

        val restored = Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_MAIN).setClass(app, MainActivity::class.java))
            .setup(saved)
        controller = restored
        composeRule.waitForIdle()
        assertEquals(1, fake.requestedUrls.size)
        assertFalse(restored.get().isFinishing)
    }

    @Test
    fun pendingClipboardReadSurvivesRecreation() {
        respondWithTrack("Later", "Artist · Song")
        app.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("link", "https://open.spotify.com/track/$TRACK_ID"))
        launch(pasteIntent())
        controller!!.windowFocusChanged(false)
        val saved = Bundle()
        controller!!.saveInstanceState(saved)
        controller!!.pause().stop().destroy()

        val restored = Robolectric.buildActivity(MainActivity::class.java, pasteIntent()).setup(saved)
        controller = restored
        restored.windowFocusChanged(true)
        waitUntil { restored.get().isFinishing }
        assertEquals(listOf("https://open.spotify.com/track/$TRACK_ID"), fake.requestedUrls)
    }

    @Test
    fun sharedStyledTextIsRead() {
        respondWithTrack("Styled", "Artist · Song")
        val activity = launch(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, SpannableString("https://open.spotify.com/track/$TRACK_ID"))
            }
        )
        waitUntil { activity.isFinishing }
        assertEquals(listOf("https://open.spotify.com/track/$TRACK_ID"), fake.requestedUrls)
    }

    @Test
    fun sharedBlankTextFallsBackToTheSubject() {
        respondWithTrack("Subject", "Artist · Song")
        val activity = launch(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, " ")
                putExtra(Intent.EXTRA_SUBJECT, "https://open.spotify.com/track/$TRACK_ID")
            }
        )
        waitUntil { activity.isFinishing }
        assertEquals(listOf("https://open.spotify.com/track/$TRACK_ID"), fake.requestedUrls)
    }

    @Test
    fun openingTheOriginalDuringAnExactMatchOpensOnlyOneApp() {
        val release = CountDownLatch(1)
        fake.handler = { request ->
            if (request.url.host == "api.deezer.com") {
                release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                FakeSpotify.html(request, """{"data":[{"title":"Slow","artist":{"name":"Artist"},"link":"https://www.deezer.com/track/1"}]}""")
            } else {
                FakeSpotify.html(request, FakeSpotify.trackPage("Slow", "Artist · Song"))
            }
        }
        prefs().edit().putBoolean("exact_match", true).putString("default_target", "DEEZER").commit()
        launch()
        resolveTyped()

        waitForText(string(R.string.matching_text))
        click(string(R.string.open_in_spotify))
        release.countDown()
        Thread.sleep(200L)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("com.spotify.music", nextStartedActivity()!!.`package`)
        assertNull(nextStartedActivity())
        assertTextAbsent(string(R.string.matching_text))
        // Opening first must reuse the canceled fallback too, without restarting the matcher.
        val requestsBeforeFallback = fake.requestedUrls.toList()
        click(string(R.string.open_in_deezer))
        assertEquals("https://www.deezer.com/search/Slow%20Artist", nextStartedActivity()!!.dataString)
        assertEquals(requestsBeforeFallback, fake.requestedUrls)
        // Copy, Share and another Open keep using the same prepared fallback.
        click(string(R.string.copy_button))
        assertEquals(
            "https://www.deezer.com/search/Slow%20Artist",
            app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
        )
        val requests = fake.requestedUrls.toList()
        click(string(R.string.share_search_button))
        val share = nextStartedActivity()!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("https://www.deezer.com/search/Slow%20Artist", share.getStringExtra(Intent.EXTRA_TEXT))
        click(string(R.string.open_in_deezer))
        assertEquals("https://www.deezer.com/search/Slow%20Artist", nextStartedActivity()!!.dataString)
        assertEquals(requests, fake.requestedUrls)
    }

    @Test
    fun historyEntryAfterAFailedIncomingLinkDoesNotCloseCrosstune() {
        respondWithTrack("Remembered", "Artist · Song")
        launch()
        resolveTyped()
        controller!!.pause().stop().destroy()

        fake.handler = { throw IOException("offline") }
        val activity = launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/track/$OTHER_TRACK_ID")))
        waitForText(string(R.string.error_network))

        click("Remembered")
        assertTextAbsent(string(R.string.retry_button))
        click(string(R.string.open_in_spotify))

        assertEquals("https://open.spotify.com/track/$TRACK_ID", nextStartedActivity()!!.dataString)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun theChosenDefaultIsMarkedSelectedInItsMenu() {
        launch()
        composeRule.onNodeWithTag(DEFAULT_MENU_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasText(string(R.string.target_youtube_music)) and isSelected()).onLast().assertExists()
    }

    @Test
    fun systemBackStepsBackThroughSetup() {
        freshInstall()
        val activity = launch()
        click(string(R.string.setup_get_started))
        assertTextShown(string(R.string.setup_sources_title))

        activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()

        assertTextShown(string(R.string.setup_welcome_title))
        assertFalse(activity.isFinishing)
    }

    @Test
    fun resultDestinationIsOneTimeUntilMadeDefaultAndResetsForNewLinks() {
        respondWithTrack("One time", "Artist · Song")
        val activity = launch()
        val model = ViewModelProvider(activity)[MainViewModel::class.java]
        resolveTyped()
        assertTextShown(string(R.string.search_in_destination, "YouTube Music"))

        chooseDefault("Apple Music")
        assertEquals(Destination.Service(MusicService.YOUTUBE_MUSIC), DestinationStore(prefs()).defaultDestination())
        assertEquals(Destination.Service(MusicService.APPLE_MUSIC), model.uiState.resultDestination)
        val destinationBounds = composeRule.onNodeWithTag(DEFAULT_MENU_TAG).fetchSemanticsNode().boundsInRoot
        val makeDefaultBounds = composeRule.onNodeWithText(string(R.string.make_default)).fetchSemanticsNode().boundsInRoot
        assertTrue(makeDefaultBounds.left >= destinationBounds.right)
        assertTrue(makeDefaultBounds.top < destinationBounds.bottom && makeDefaultBounds.bottom > destinationBounds.top)
        click(string(R.string.make_default))
        assertEquals(Destination.Service(MusicService.APPLE_MUSIC), DestinationStore(prefs()).defaultDestination())
        assertTextAbsent(string(R.string.make_default))

        chooseDefault("Spotify")
        assertEquals(Destination.Service(MusicService.APPLE_MUSIC), DestinationStore(prefs()).defaultDestination())
        resolveTyped(OTHER_TRACK_ID)
        assertEquals(Destination.Service(MusicService.APPLE_MUSIC), model.uiState.resultDestination)
        assertNull(model.uiState.selectedDestination)

        model.setRule(MusicService.SPOTIFY, Destination.Service(MusicService.DEEZER))
        composeRule.waitForIdle()
        chooseDefault("YouTube Music")
        assertEquals(Destination.Service(MusicService.DEEZER), DestinationStore(prefs()).rule(MusicService.SPOTIFY))
        assertEquals(Destination.Service(MusicService.YOUTUBE_MUSIC), model.uiState.resultDestination)
        model.clear()
        composeRule.waitForIdle()
        assertNull(model.uiState.selectedDestination)
    }

    @Test
    fun historyShortcutsReuseSavedExactLinksAcrossLaunchesAndRespectMatchingPreference() {
        prefs().edit().putBoolean("exact_match", true).putString("default_target", "DEEZER").commit()
        fake.handler = { request ->
            if (request.url.host == "api.deezer.com") {
                FakeSpotify.html(request, """{"data":[{"title":"Remember","artist":{"name":"Artist"},"link":"https://www.deezer.com/track/123"}]}""")
            } else FakeSpotify.html(request, FakeSpotify.trackPage("Remember", "Artist · Song"))
        }
        launch()
        resolveTyped()
        waitForDestinationReady()
        val requests = fake.requestedUrls.size
        controller!!.pause().stop().destroy()
        val activity = launch()
        val model = ViewModelProvider(activity)[MainViewModel::class.java]

        click(string(R.string.history_copy, "Remember"))
        assertEquals("https://www.deezer.com/track/123", app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        click(string(R.string.history_open, "Remember"))
        assertEquals("https://www.deezer.com/track/123", nextStartedActivity()!!.dataString)
        assertEquals(requests, fake.requestedUrls.size)

        model.setExactMatch(false)
        composeRule.waitForIdle()
        click(string(R.string.history_copy, "Remember"))
        assertEquals("https://www.deezer.com/search/Remember%20Artist", app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        assertEquals(requests, fake.requestedUrls.size)
        click(string(R.string.history_open, "Remember"))
        assertEquals("https://www.deezer.com/search/Remember%20Artist", nextStartedActivity()!!.dataString)
    }

    @Test
    fun destinationChoicesShowEveryServiceIconAndPutInstalledAppsFirst() {
        val installed = MusicService.YOUTUBE
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = installed.packageName
            applicationInfo = android.content.pm.ApplicationInfo().apply {
                packageName = installed.packageName
                icon = android.R.drawable.ic_media_play
            }
        })
        shadowOf(app.packageManager).setApplicationIcon(installed.packageName, android.graphics.drawable.ColorDrawable(android.graphics.Color.RED))
        val custom = DestinationStore(prefs()).addCustom("Player", "player://search/{query}")
        val choices = listOf(Destination.Service(MusicService.SPOTIFY), custom, Destination.Service(installed))
        assertEquals(listOf(Destination.Service(installed), Destination.Service(MusicService.SPOTIFY), custom), choices.installedFirst(setOf(installed)))
        respondWithTrack("Picker summary", "Artist · Song")
        prefs().edit().putBoolean("ask_each_time", true).commit()
        launch(trackLink())
        waitForText(string(R.string.picker_title))
        assertTextShown("Picker summary")
        composeRule.onNodeWithTag("destination-icon:YOUTUBE", useUnmergedTree = true).assertExists()
        MusicService.entries.forEach { service ->
            composeRule.onNodeWithTag("destination-icon:" + service.name, useUnmergedTree = true).assertExists()
        }
        assertTextAbsent(string(R.string.setup_installed))
        click(string(R.string.cancel_button))
        assertTextAbsent(string(R.string.picker_title))
        chooseDefault("Player")
        assertTextShown(string(R.string.search_in_destination, "Player"))
    }

    @Test
    fun customTemplatePreviewEncodesAnEditableSampleBeforeSaving() {
        launch()
        click(string(R.string.settings_button))
        click(string(R.string.add_custom_destination_button))
        composeRule.onNode(hasSetTextAction() and hasText(string(R.string.custom_template_label)))
            .performTextReplacement("https://example.com/search?q={query}")
        composeRule.onNode(hasSetTextAction() and hasText(string(R.string.custom_preview_query)))
            .performTextReplacement("Song & Artist")
        composeRule.waitForIdle()
        assertTextShown(string(R.string.custom_preview_title))
        assertTextShown("https://example.com/search?q=Song%20%26%20Artist")
        assertTrue(DestinationStore(prefs()).customDestinations().isEmpty())
        click(string(R.string.cancel_button))
    }

    // endregion

    private companion object {
        const val TRACK_ID = "11dFghVXANMlKmJXsNCbNl"
        const val OTHER_TRACK_ID = "0VjIjW4GlUZAMYd2vXMi3b"
        const val TIMEOUT_MS = 5_000L
    }
}
