package com.astrovm.crosstune

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onLast
import android.content.ClipboardManager
import android.content.ComponentName
import android.app.UiModeManager
import android.content.Context
import androidx.compose.ui.test.SemanticsMatcher
import android.os.SystemClock
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import androidx.compose.ui.test.performTextInput
import android.os.Bundle
import android.os.Looper
import android.Manifest
import android.provider.Settings
import android.view.textclassifier.TextClassifier
import android.text.SpannableString
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import okhttp3.Request
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowSettings
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
        // What Android says about apps and links is read as the screen asks, so each step sees it.
        MainActivity.systemDispatcher = Dispatchers.Unconfined
        MainActivity.lookupDispatcher = Dispatchers.Unconfined
        // Most tests exercise the main screen with links going straight to an app picked already, as searches; the rest have their own tests.
        prefs().edit().putBoolean("setup_complete", true).putString("default_target", "YOUTUBE_MUSIC").putBoolean("links_in_crosstune", false).putBoolean("play_right_away", false).commit()
        // What an earlier test found for a song would be found again without asking.
        File(app.cacheDir, "lookups.json").delete()
        File(app.cacheDir, "lyrics.json").delete()
        MainActivity.lyricsBusyPauseMs = 0
        // Each song shown would look up its lyrics too, which tests counting what's asked don't expect.
        MainActivity.lookAheadLyrics = false
    }

    @After
    fun tearDown() {
        runCatching { controller?.pause()?.stop()?.destroy() }
        MainActivity.httpClientFactory = ::httpClient
        MainActivity.systemDispatcher = Dispatchers.Default
        MainActivity.lookupDispatcher = Dispatchers.IO
        MainActivity.playbackFactory = ::MediaSessionPlayback
        MainActivity.lyricsBusyPauseMs = LYRICS_BUSY_PAUSE_MS
        MainActivity.lookAheadLyrics = true
        MainActivity.shellFactory = { listOf(ShizukuShell(), RootShell()) }
        MainActivity.checksForUpdates = ::looksForUpdates
    }

    // region helpers

    /** What the Quick Settings tile and launcher shortcut send. */
    /**
     * Waits for [condition], meanwhile running what background work hands back to the main thread,
     * which Compose's own wait leaves queued until something else runs it: on a slow machine, words
     * found in the background never reached the screen.
     */
    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) =
        composeRule.waitUntil(timeoutMs) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); condition() }

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
        val action = resultActionText(text)
        composeRule.onNode(hasText(action) or hasContentDescription(action) or hasContentDescription(text)).performClick()
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

    /**
     * Picks from the "Opens in" menu: on the result card it changes just that result; with no
     * result there's none on the main screen, so it changes the default in settings.
     */
    private fun chooseDefault(label: String) {
        val onResult = composeRule.onAllNodesWithTag(DEFAULT_MENU_TAG).fetchSemanticsNodes().isNotEmpty()
        if (onResult) pickFromDefaultMenu(label) else inSettings { pickFromDefaultMenu(label) }
    }

    /** Picks from the menu on the current screen, e.g. the default in settings. */
    private fun chooseDefaultHere(label: String) = pickFromDefaultMenu(label)

    /** Links open in Crosstune, on the song's card, without playing right away. */
    private fun linksShowHere() = prefs().edit().putBoolean("links_in_crosstune", true).putBoolean("play_right_away", false).commit()

    /** Listening in Spotify, with links opening in it, a Spotify link would only go back there, so it asks where. */
    private fun linksAskWhere() = prefs().edit().putString("default_target", "SPOTIFY").putBoolean("links_in_crosstune", false).commit()

    private fun pickFromDefaultMenu(label: String) {
        composeRule.onNodeWithTag(DEFAULT_MENU_TAG).performClick()
        composeRule.waitForIdle()
        // The menu item is the last match: the menu button itself may show the same name.
        composeRule.onAllNodesWithText(label).onLast().performClick()
        composeRule.waitForIdle()
    }

    /** A song from Now Playing only goes online to look for its cover. */
    private fun assertOnlyCoverSearches() =
        assertTrue(fake.requestedUrls.toString(), fake.requestedUrls.all { it.startsWith("https://api.deezer.com/search/track?") })

    private fun assertResultShown() {
        composeRule.onNodeWithTag(RESULT_TAG).assertExists()
    }

    private fun assertResultAbsent() {
        composeRule.onNodeWithTag(RESULT_TAG).assertDoesNotExist()
    }

    private fun waitForResult() {
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodesWithTag(RESULT_TAG).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForDestinationReady() {
        waitUntil(TIMEOUT_MS) {
            !composeRule.onNodeWithContentDescription(string(R.string.copy_link_button)).fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
        }
    }

    private fun typeUrl(value: String) {
        composeRule.onNode(hasSetTextAction()).performTextReplacement(value)
        composeRule.waitForIdle()
    }

    private fun waitForDescription(label: String) =
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String) {
        waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithTextCount(text) > 0
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size

    /**
     * Resolved titles also appear in the history list, so any matching node counts. It may still be
     * on its way, as a song's button that waits for its match while its name already shows.
     */
    private fun assertTextShown(text: String) {
        // The main button says one word, and its description says where.
        fun shown() = resultActionText(text).let { action ->
            composeRule.onAllNodesWithTextCount(action) > 0 || composeRule.onAllNodes(hasContentDescription(action)).fetchSemanticsNodes().isNotEmpty()
        }
        runCatching { composeRule.waitUntil(TIMEOUT_MS) { shown() } }
        assertTrue("'$text' not shown", shown())
    }

    /** An icon that says [description] to screen readers, in place of words on screen. */
    private fun assertMarked(description: String) {
        assertTrue("'$description' not marked", composeRule.onAllNodes(hasContentDescription(description), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue("'$description' written out", composeRule.onAllNodesWithTextCount(description) == 0)
    }

    /** Neither shown nor read out: a button with a short label says its whole name to screen readers. */
    private fun assertTextAbsent(text: String) {
        composeRule.onNode(hasText(text) or hasContentDescription(text)).assertDoesNotExist()
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

    /** In Settings' advanced page, then back out of it and Settings. */
    private fun inAdvancedSettings(block: () -> Unit) = inSettings {
        openAdvanced()
        block()
        click(string(R.string.back_button))
    }

    private fun openAdvanced() {
        composeRule.onNodeWithText(string(R.string.settings_advanced_title)).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    private fun installActivity(component: ComponentName, filter: IntentFilter) {
        val pm = shadowOf(app.packageManager)
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(component, filter)
    }

    // endregion

    @Test
    fun recognizeAlwaysListensInCrosstune() {
        val recognize = string(R.string.recognize_button)
        /** A tap that listens here asks for the microphone, at most, rather than opening another app. */
        fun assertListensHere() {
            click(recognize)
            val started = nextStartedActivity()
            assertTrue("$started", started == null || started.action == "android.content.pm.action.REQUEST_PERMISSIONS")
        }
        val activity = launch()
        // Turned down once, Android would still ask, so a tap that listens here asks again.
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO, true)
        click(recognize)
        assertEquals(android.Manifest.permission.RECORD_AUDIO, shadowOf(activity).lastRequestedPermission.requestedPermissions.single())
        assertListensHere()

        // Shazam's and Google's apps are only for when Crosstune can't reach Shazam, even one picked
        // in Settings back when there was a choice.
        shadowOf(app.packageManager).installPackage(installedApp(SongRecognizers.GOOGLE, "Google"))
        installActivity(ComponentName(SongRecognizers.GOOGLE, "MusicSearch"), IntentFilter(SongRecognizers.GOOGLE_SONG_SEARCH).apply { addCategory(Intent.CATEGORY_DEFAULT) })
        prefs().edit().putString("song_recognizer", SongRecognizers.GOOGLE).commit()
        controller!!.pause().resume()
        composeRule.waitForIdle()
        assertListensHere()

        // Once there's text, the field offers to clear it instead.
        typeUrl(TRACK_ID)
        assertTextAbsent(recognize)
    }

    @Test
    fun recognizedSongMatchesExactTrackInChosenShareSheetApp() {
        prefs().edit().putBoolean("play_right_away", true).commit()
        fake.handler = { request -> FakeSpotify.html(request,
            """{"data":[{"id":123,"title":"A Song","artist":{"name":"Example Band"},"link":"https://www.deezer.com/track/123"}]}""") }
        launch(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, NOW_PLAYING_SHARE)
            putExtra(Intent.EXTRA_SHORTCUT_ID, "open_in:DEEZER")
        })
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://www.deezer.com/track/123", nextStartedActivity()!!.dataString)
        // Deezer has no cover for it, so Apple's is looked for too.
        assertTrue(fake.requestedUrls.all { it.startsWith("https://api.deezer.com/search") || it.startsWith("https://itunes.apple.com/search") })
    }

    @Test
    fun recognizedSongSearchesCustomDestinationInsteadOfOpeningGoogle() {
        DestinationStore(prefs()).apply { setDefault(addCustom("Player", "player://search/{query}")) }
        launch(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, NOW_PLAYING_SHARE)
        })
        // Waits on the link that opened, not on the screen closing, which also waits on Android
        // for the installed apps and can outlast a timeout on a busy machine.
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("player://search/A%20Song%20Example%20Band", nextStartedActivity()!!.dataString)
        waitUntil { fake.requestedUrls.isNotEmpty() }
        assertOnlyCoverSearches()
        val entry = HistoryStore(prefs()).load().first()
        controller!!.pause().stop().destroy()
        launch(Intent(MainActivity.ACTION_OPEN_RECENT).setData(Uri.parse(entry.link.url)))
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("player://search/A%20Song%20Example%20Band", nextStartedActivity()!!.dataString)
    }

    @Test
    fun pixelNowPlayingShareOpensMusicSearchAndCanReopenFromHistory() {
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").commit()
        launch(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, NOW_PLAYING_SHARE)
        })
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        val opened = nextStartedActivity()!!
        assertEquals("com.google.android.apps.youtube.music", opened.`package`)
        assertEquals("https://music.youtube.com/search?q=A%20Song%20Example%20Band", opened.dataString)
        assertOnlyCoverSearches()
        val saved = HistoryStore(prefs()).load().first()
        assertEquals(MusicMetadata("A Song", "Example Band"), saved.metadata)
        controller!!.pause().stop().destroy()
        launch(Intent(MainActivity.ACTION_OPEN_RECENT).setData(Uri.parse(saved.link.url)))
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals(opened.dataString, nextStartedActivity()!!.dataString)
        assertOnlyCoverSearches()
    }

    @Test
    fun aNowPlayingShareInSpanishWithAnAmpersandOpensInTheMusicApp() {
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").commit()
        launch(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Canción de Simon & Garfunkel\nhttps://www.google.com/search?q=Canción+de+Simon+&+Garfunkel")
        })
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://music.youtube.com/search?q=Canci%C3%B3n%20Simon%20%26%20Garfunkel", nextStartedActivity()!!.dataString)
        assertEquals(MusicMetadata("Canción", "Simon & Garfunkel"), HistoryStore(prefs()).load().first().metadata)
    }

    @Test
    fun aNowPlayingSongShowsAndSavesTheCoverFoundForIt() {
        linksShowHere()
        // Opening in an app of the user's own, there's nothing to look it up in but its cover.
        DestinationStore(prefs()).apply { setDefault(addCustom("Player", "player://search/{query}")) }
        val cover = "https://cdn-images.dzcdn.net/images/cover/abc/500x500-000000-80-0-0.jpg"
        fake.handler = { request -> FakeSpotify.html(request,
            """{"data":[{"title":"A Song (Remastered)","artist":{"name":"The Example Band"},"album":{"cover_big":"$cover"}}]}""") }
        launch(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, NOW_PLAYING_SHARE)
        })
        waitForText("A Song")
        assertEquals(cover, HistoryStore(prefs()).load().first().metadata.artworkUrl)
        // One search for it, then the cover itself.
        assertEquals(1, fake.requestedUrls.count { it.startsWith("https://api.deezer.com/search/track?") })
        assertTrue(fake.requestedUrls.toString(), fake.requestedUrls.all { it.startsWith("https://api.deezer.com/search/track?") || it == cover })

        // Opened again from Recent, it already has its cover.
        fake.requestedUrls.clear()
        controller!!.pause().stop().destroy()
        val url = HistoryStore(prefs()).load().first().link.url
        launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)).putExtra(MainActivity.EXTRA_SHOW_SONG, true))
        waitForText("A Song")
        assertTrue(fake.requestedUrls.toString(), fake.requestedUrls.none { it.startsWith("https://api.deezer.com/") })
    }

    @Test
    fun aGoogleSearchThatOnlyContainsByIsNotASong() {
        val activity = launch(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://www.google.com/search?q=what+to+do+by+tomorrow")
        })
        assertTextShown(string(R.string.error_invalid_url))
        assertNull(nextStartedActivity())
        assertFalse(activity.isFinishing)
        assertTrue(HistoryStore(prefs()).load().isEmpty())
    }

    @Test
    fun aRecognizedSongFromTheWidgetShowsFromItsSearchLinkAlone() {
        val url = "https://www.google.com/search?q=A%20Song%20by%20Example%20Band"
        HistoryStore(prefs()).add(HistoryEntry(MusicLink(null, ItemType.TRACK, url, url), MusicMetadata("A Song", "Example Band")))
        val activity = launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)).putExtra(MainActivity.EXTRA_SHOW_SONG, true))
        waitForText("A Song")
        composeRule.onNodeWithTag(RESULT_TEXT_TAG).assertIsDisplayed()
        assertFalse(activity.isFinishing)
        assertOnlyCoverSearches()
    }

    @Test
    fun aCopiedNowPlayingShareOpensFromTheClipboard() {
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").commit()
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("song", NOW_PLAYING_SHARE))
        launch(pasteIntent())
        controller!!.windowFocusChanged(true)
        val activity = controller!!.get()
        waitUntil { activity.isFinishing }
        assertEquals("https://music.youtube.com/search?q=A%20Song%20Example%20Band", nextStartedActivity()!!.dataString)
        assertOnlyCoverSearches()
    }

    @Test
    fun recognizedSongDisplaysWithoutInventingASourceService() {
        linksShowHere()
        launch(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, NOW_PLAYING_SHARE)
        })
        waitForText("A Song")
        assertNull(nextStartedActivity())
        composeRule.onNodeWithTag(RESULT_TEXT_TAG).assertIsDisplayed()
        assertEquals(null, HistoryStore(prefs()).load().first().link.service)
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
    fun viewIntentResolvesTrackAndOpensYouTubeMusicThenFinishes() {
        respondWithTrack("Cut To The Feeling", "Carly Rae Jepsen · Song · 2017")
        launch(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/track/$TRACK_ID?si=abc"))
        )

        waitUntil { shadowOf(app).peekNextStartedActivity() != null }

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
    fun linksCanOpenInCrosstuneWithoutPlayingRightAway() {
        prefs().edit().putString("default_target", "DEEZER").putBoolean("play_right_away", true).commit()
        respondWithTrack("Shown First", "Artist · Song")
        launch()
        // Shown in Crosstune first, songs still play in the music app, right away or not.
        inSettings {
            click(string(R.string.setting_show_in_crosstune_first))
            composeRule.onNodeWithTag(DEFAULT_MENU_TAG).assert(hasText(string(R.string.target_deezer)))
            click(string(R.string.setting_play_right_away))
        }
        assertTrue(prefs().getBoolean("links_in_crosstune", false))
        assertFalse(prefs().getBoolean("play_right_away", true))
        controller!!.pause().stop().destroy()

        val activity = launch(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "https://open.spotify.com/track/$TRACK_ID")
            }
        )
        waitForText("Shown First")
        assertTextShown(string(R.string.open_in_deezer))
        assertFalse(activity.isFinishing)
        assertNull(nextStartedActivity())
        controller!!.pause().stop().destroy()

        // Tapped links too.
        val tapped = launch(trackLink())
        waitForText("Shown First")
        assertFalse(tapped.isFinishing)
        assertNull(nextStartedActivity())
        controller!!.pause().stop().destroy()

        // An "Open in" app picked in the share sheet still opens right away.
        launch(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "https://open.spotify.com/track/$TRACK_ID")
                putExtra(Intent.EXTRA_SHORTCUT_ID, "open_in:DEEZER")
            }
        )
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("deezer.android.app", nextStartedActivity()!!.`package`)
    }

    @Test
    fun aLinkOpenedInCrosstunePlaysRightAwayInTheAppPickedOnceItsThere() {
        val deezer = MusicService.DEEZER.packageName
        val phone = FakePlayback().apply { access = true }
        MainActivity.playbackFactory = { phone }
        prefs().edit().putString("default_target", "DEEZER").putBoolean("links_in_crosstune", true).putBoolean("play_right_away", true).commit()
        respondWithTrack("Right Away", "Artist · Song")
        // Without the app, there's nothing to play it in, so the card has the choices.
        launch(trackLink())
        waitForText("Right Away")
        assertTrue(phone.songsAsked.isEmpty())
        assertNull(nextStartedActivity())
        controller!!.pause().stop().destroy()

        shadowOf(app.packageManager).installPackage(installedApp(deezer, "Deezer"))
        val activity = launch(trackLink())
        waitUntil { phone.songsAsked.isNotEmpty() }
        assertEquals(MusicMetadata("Right Away", "Artist") to deezer, phone.songsAsked.single())
        // Crosstune stays, in its player.
        assertTrue(composeRule.onAllNodes(hasContentDescription(string(R.string.dismiss_button))).fetchSemanticsNodes().isNotEmpty())
        assertFalse(activity.isFinishing)
        assertNull(nextStartedActivity())

        // Picking the app from the card plays nothing by itself.
        phone.songsAsked.clear()
        click(string(R.string.dismiss_button))
        typeUrl(TRACK_ID)
        click(string(R.string.resolve_button))
        waitForResult()
        assertTrue(phone.songsAsked.isEmpty())
    }

    @Test
    fun recentsPlayGoesWhereALinkTappedDoes() {
        val deezer = MusicService.DEEZER.packageName
        val phone = FakePlayback().apply { access = true }
        MainActivity.playbackFactory = { phone }
        shadowOf(app.packageManager).installPackage(installedApp(deezer, "Deezer"))
        val url = "https://open.spotify.com/track/$TRACK_ID"
        HistoryStore(prefs()).add(HistoryEntry(MusicLink(MusicService.SPOTIFY, ItemType.TRACK, TRACK_ID, url), MusicMetadata("Remember", "Artist")))
        prefs().edit().putString("default_target", "DEEZER").putBoolean("links_in_crosstune", true).putBoolean("play_right_away", true).commit()
        respondWithTrack("Remember", "Artist · Song")
        // Shown in Crosstune first, it plays from its card here, and Crosstune stays.
        val activity = launch()
        val model = ViewModelProvider(activity)[MainViewModel::class.java]
        click(string(R.string.history_open, "Remember"))
        waitUntil { phone.songsAsked.isNotEmpty() }
        assertEquals(MusicMetadata("Remember", "Artist") to deezer, phone.songsAsked.single())
        assertEquals("Remember", model.uiState.result?.title)
        assertNull(nextStartedActivity())
        assertFalse(activity.isFinishing)

        // Not playing right away, its card only shows.
        phone.songsAsked.clear()
        composeRule.runOnIdle { model.clear() }
        model.setPlayRightAway(false)
        composeRule.waitForIdle()
        click(string(R.string.history_open, "Remember"))
        waitUntil { model.uiState.result != null && !model.uiState.isMatching }
        composeRule.waitForIdle()
        assertTrue(phone.songsAsked.isEmpty())
        assertNull(nextStartedActivity())

        // From the widget too.
        composeRule.runOnIdle { model.clear() }
        model.setPlayRightAway(true)
        composeRule.runOnIdle { model.openRecent(url) }
        waitUntil { phone.songsAsked.isNotEmpty() }
        assertEquals("Remember", model.uiState.result?.title)
        assertFalse(model.uiState.handingOff)
        assertNull(nextStartedActivity())
    }

    @Test
    fun linksGoStraightToTheAppOnNewInstallsAndShowInCrosstuneForWhoeverHadThemAskOrShowTheSongFirst() {
        fun migrated(vararg before: Pair<String, Boolean>): Pair<Boolean, Boolean> {
            prefs().edit().remove("links_in_crosstune").remove("play_right_away").remove("ask_each_time").remove("show_song_first")
                .apply { before.forEach { (key, on) -> putBoolean(key, on) } }.commit()
            launch()
            controller!!.pause().stop().destroy()
            return prefs().getBoolean("links_in_crosstune", false) to prefs().getBoolean("play_right_away", false)
        }
        assertEquals(false to true, migrated("setup_complete" to false))
        // Links opening in an app keep doing so.
        assertEquals(false to true, migrated("setup_complete" to true))
        assertEquals(true to false, migrated("setup_complete" to true, "ask_each_time" to true))
        assertEquals(true to false, migrated("setup_complete" to true, "show_song_first" to true))
        // Once moved over, it's what was picked.
        prefs().edit().putBoolean("links_in_crosstune", false).putBoolean("show_song_first", true).commit()
        launch()
        assertFalse(prefs().getBoolean("links_in_crosstune", true))
    }

    @Test
    fun openTheExactMatchSwitchedOffIsNotPlayingRightAwayWhenLinksGoStraightToTheApp() {
        fun migrated(linksInCrosstune: Boolean, exact: Boolean): Boolean {
            prefs().edit().putBoolean("links_in_crosstune", linksInCrosstune).putBoolean("play_right_away", true).putBoolean("exact_match", exact).commit()
            launch()
            controller!!.pause().stop().destroy()
            assertFalse(prefs().contains("exact_match"))
            return prefs().getBoolean("play_right_away", false)
        }
        assertFalse(migrated(linksInCrosstune = false, exact = false))
        assertTrue(migrated(linksInCrosstune = false, exact = true))
        // Shown in Crosstune, songs were looked up either way.
        assertTrue(migrated(linksInCrosstune = true, exact = false))
    }

    @Test
    fun linksNoLongerShownInCrosstuneFirstGoStraightToTheMusicAppAgain() {
        prefs().edit().putString("default_target", "DEEZER").commit()
        linksShowHere()
        LinkInterception(app).setEnabled(MusicService.YOUTUBE_MUSIC, true)
        respondWithTrack("Back To Apps", "Artist · Song")
        launch()
        inSettings {
            // Another music app, and links keep showing here first, the app's own too.
            chooseDefaultHere(string(R.string.target_youtube))
            assertEquals("YOUTUBE", prefs().getString("default_target", null))
            chooseDefaultHere(string(R.string.target_youtube_music))
            composeRule.onNodeWithTag(DEFAULT_MENU_TAG).assert(hasText(string(R.string.target_youtube_music)))
            assertTrue(prefs().getBoolean("links_in_crosstune", false))
            assertTrue(LinkInterception(app).isEnabled(MusicService.YOUTUBE_MUSIC))
            click(string(R.string.setting_show_in_crosstune_first))
        }
        assertFalse(prefs().getBoolean("links_in_crosstune", true))
        assertEquals("YOUTUBE_MUSIC", prefs().getString("default_target", null))
        // The app's own links go straight to it again.
        assertFalse(LinkInterception(app).isEnabled(MusicService.YOUTUBE_MUSIC))
        controller!!.pause().stop().destroy()

        val activity = launch(trackLink())
        waitUntil { activity.isFinishing }
        assertEquals(MusicService.YOUTUBE_MUSIC.packageName, nextStartedActivity()!!.`package`)
        controller!!.pause().stop().destroy()

        // Shown here first again, they come here again too.
        launch()
        inSettings { click(string(R.string.setting_show_in_crosstune_first)) }
        assertTrue(prefs().getBoolean("links_in_crosstune", false))
        assertTrue(LinkInterception(app).isEnabled(MusicService.YOUTUBE_MUSIC))
    }

    @Test
    fun anAppOfTheUsersOwnHasNoLinksToTakeWhenLinksShowInCrosstuneFirst() {
        DestinationStore(prefs()).apply { setDefault(addCustom("Player", "player://search/{query}")) }
        launch()
        inSettings { click(string(R.string.setting_show_in_crosstune_first)) }
        assertTrue(prefs().getBoolean("links_in_crosstune", false))
        assertTrue(MusicService.entries.none { LinkInterception(app).isEnabled(it) })
    }

    @Test
    fun sharedTextOpensPreferredYouTubeTarget() {
        prefs().edit().putString("default_target", "YOUTUBE").commit()
        respondWithTrack("Song &amp; Dance", "The Band · Song · 2020")
        launch(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Listen: https://open.spotify.com/track/$TRACK_ID?si=x.")
            }
        )

        waitUntil { shadowOf(app).peekNextStartedActivity() != null }

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
    fun linkSharedFromTheAppTheUserListensInAsksWhereElseToOpenIt() {
        prefs().edit().putString("default_target", "SPOTIFY").commit()
        respondWithTrack("Mine", "Artist · Song")
        launch(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "https://open.spotify.com/track/$TRACK_ID")
            }
        )

        // Opening it in Spotify would only go straight back to Spotify.
        waitForText(string(R.string.picker_title))
        assertNull(nextStartedActivity())
        composeRule.onNode(hasText(string(R.string.open_in_spotify)) and hasAnyAncestor(isDialog())).assertDoesNotExist()
        composeRule.onNode(hasText(string(R.string.open_in_deezer)) and hasAnyAncestor(isDialog())).assertExists()
    }

    private fun shareChooser(): Intent {
        click(string(R.string.share_link_button))
        return nextStartedActivity()!!.also { assertEquals(Intent.ACTION_CHOOSER, it.action) }
    }

    @Test
    fun theShareSheetSharesTheConvertedLinkWithoutCrosstuneInIt() {
        prefs().edit().putString("default_target", "DEEZER").putBoolean("play_right_away", false).commit()
        respondWithTrack("Cut To The Feeling", "Carly Rae Jepsen · Song · 2017")
        launch()
        resolveTyped()
        waitForDestinationReady()

        val chooser = shareChooser()
        assertEquals("https://www.deezer.com/search/Cut%20To%20The%20Feeling%20Carly%20Rae%20Jepsen", chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!.getStringExtra(Intent.EXTRA_TEXT))
        // Crosstune isn't offered in its own share sheet, which would only loop back here.
        assertEquals(
            listOf(ComponentName(app, MainActivity::class.java)),
            chooser.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, ComponentName::class.java)!!.toList()
        )
        assertFalse(chooser.hasExtra(Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS))
    }

    @Test
    fun aTappedPlaylistShowsItsSongsAndEachOpensInTheDefaultApp() {
        prefs().edit().putString("default_target", "DEEZER").putBoolean("play_right_away", true).putString("not_found", "SEARCH").commit()
        val embed = """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":{"trackList":[
            {"title":"First Song","subtitle":"Band"},{"title":"Second Song","subtitle":""}]}}}}}}</script>"""
        fake.handler = { request ->
            when {
                request.url.encodedPath.startsWith("/embed/") -> FakeSpotify.html(request, embed)
                request.url.host == "api.deezer.com" -> FakeSpotify.html(request, """{"data":[]}""")
                else -> FakeSpotify.html(request, FakeSpotify.trackPage("Road Trip | Spotify", "Playlist"))
            }
        }
        val activity = launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")))

        // A playlist can't open as one elsewhere, so its songs show instead of it opening.
        waitForText("First Song")
        assertTextShown(string(R.string.playlist_songs_title))
        assertFalse(activity.isFinishing)
        assertNull(nextStartedActivity())

        // Each song is matched on its own, and opens without leaving Crosstune behind.
        composeRule.onNodeWithText("Second Song").performScrollTo().performClick()
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        val opened = nextStartedActivity()!!
        assertEquals("deezer.android.app", opened.`package`)
        assertEquals("https://www.deezer.com/search/Second%20Song", opened.dataString)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun theAsksKeepAnAppThatStillTakesTheLinksToFixThoughTheyreAllowed() {
        setupWithSpotifyAppInTheWay()
        launch()
        // Its links are allowed, but they don't open in Crosstune while the app takes them.
        assertEquals(1, checkedOff())
        assertTextShown(string(R.string.setup_check_app, string(R.string.service_spotify)))
    }

    @Test
    fun theAppYouListenInSaysSoInTheSourceList() {
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").commit()
        launch()
        openSources()
        assertMarked(string(R.string.settings_where_you_listen))
    }

    private fun collectionWithSongs(title: String = "Road Trip", type: String = "playlist") {
        val embed = """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":{"trackList":[
            {"title":"First Song","subtitle":"Band"},{"title":"Second Song","subtitle":""}]}}}}}}</script>"""
        fake.handler = { request ->
            when {
                request.url.encodedPath.startsWith("/embed/") -> FakeSpotify.html(request, embed)
                request.url.encodedPath == "/watch_videos" -> FakeSpotify.html(request, "").newBuilder().code(303)
                    .header("Location", "https://www.youtube.com/watch?v=first000000&list=TLGGqueue").build()
                request.url.host == "music.youtube.com" -> FakeSpotify.html(request, """{"contents":[{"musicResponsiveListItemRenderer":{
                    "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"First Song"}]}}},
                    {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Band • Album • 3:00"}]}}}],
                    "playlistItemData":{"videoId":"first000000"}}}]}""")
                else -> FakeSpotify.html(request, FakeSpotify.trackPage("$title | Spotify", type))
            }
        }
    }

    @Test
    fun aPlaylistsSongsCanBeCopiedSharedOrPlayedAllAtOnceInYouTubeMusic() {
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").putBoolean("play_right_away", true).commit()
        collectionWithSongs()
        val activity = launch()
        resolveTyped("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
        waitForText("First Song")

        // The buttons' labels are short, since the app's icon and the songs below say the rest; screen
        // readers hear it all.
        composeRule.onNodeWithText(string(R.string.play_all_in, string(MusicService.YOUTUBE_MUSIC.labelRes))).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.copy_songs_button)).assertDoesNotExist()
        // The songs, not a search for the playlist's name, are what's copied and shared.
        click(string(R.string.copy_songs_button))
        assertEquals("Band - First Song\nSecond Song", app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        click(string(R.string.share_songs_button))
        val chooser = nextStartedActivity()!!
        assertEquals("Band - First Song\nSecond Song", chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!.getStringExtra(Intent.EXTRA_TEXT))

        // Playing them all is the main button, rather than a search for the playlist's name.
        assertTextAbsent(string(R.string.search_in_destination, string(MusicService.YOUTUBE_MUSIC.labelRes)))
        // Play all matches each song and opens YouTube's temporary playlist of them.
        click(string(R.string.play_all_in, string(MusicService.YOUTUBE_MUSIC.labelRes)))
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://music.youtube.com/watch?v=first000000&list=TLGGqueue", nextStartedActivity()!!.dataString)
        assertFalse(activity.isFinishing)

        // The songs found are remembered, and for a while so is one not found, so playing them again
        // only asks YouTube for the queue.
        fake.requestBodies.clear()
        click(string(R.string.play_all_in, string(MusicService.YOUTUBE_MUSIC.labelRes)))
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://music.youtube.com/watch?v=first000000&list=TLGGqueue", nextStartedActivity()!!.dataString)
        assertEquals(emptyList<String>(), fake.requestBodies.filter { "\"query\"" in it }.map { JSONObject(it).getString("query") })
    }

    @Test
    fun anAlbumOpensAsItselfAndItsSongsCanStillPlayAllAtOnce() {
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").commit()
        collectionWithSongs(title = "Album", type = "album")
        launch()
        resolveTyped("https://open.spotify.com/album/4yP0hdKOZPNshxUOjY0cZj")
        waitForText("First Song")
        assertTextShown(string(R.string.search_in_destination, string(MusicService.YOUTUBE_MUSIC.labelRes)))
        click(string(R.string.play_all_button))
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://music.youtube.com/watch?v=first000000&list=TLGGqueue", nextStartedActivity()!!.dataString)
    }

    @Test
    fun aPlaylistShowsEachSongsCoverAndPlaysFromItsFirstSongElsewhere() {
        prefs().edit().putString("default_target", "DEEZER").commit()
        val firstCover = "https://i.scdn.co/image/first"
        val secondCover = "https://image-cdn-ak.spotifycdn.com/image/second"
        // The playlist's page has its first songs' covers, in base64-encoded data.
        val state = """{"entities":{"items":{"spotify:playlist:x":{"content":{"items":[{"itemV2":{"data":{"uri":"spotify:track:first",
            "albumOfTrack":{"coverArt":{"sources":[{"width":640,"url":"https://i.scdn.co/image/big"},{"width":300,"url":"$firstCover"}]}}}}}]}}}}}"""
        val page = FakeSpotify.trackPage("Road Trip | Spotify", "Playlist") + """<meta name="music:song_count" content="150"/>""" +
            """<script id="initialState" type="text/plain">${java.util.Base64.getEncoder().encodeToString(state.toByteArray())}</script>"""
        val embed = """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":{"trackList":[
            {"title":"First Song","subtitle":"Band","uri":"spotify:track:first"},{"title":"Second Song","subtitle":"Band","uri":"spotify:track:second"}]}}}}}}</script>"""
        fake.handler = { request ->
            when {
                request.url.encodedPath.startsWith("/embed/") -> FakeSpotify.html(request, embed)
                // The rest are looked up by their own link.
                request.url.encodedPath == "/oembed" -> FakeSpotify.html(request, """{"thumbnail_url":"$secondCover"}""")
                request.url.host == "open.spotify.com" -> FakeSpotify.html(request, page)
                else -> FakeSpotify.image(request, FakeSpotify.png())
            }
        }
        val activity = launch()
        resolveTyped("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
        waitForText("First Song")
        // Spotify only shows the first ones without signing in.
        assertTextShown(string(R.string.songs_shown, 2, 150))
        // Every song's cover shows, and is kept with the playlist in Recent.
        waitUntil { HistoryStore(prefs()).load().first().metadata.tracks.map { it.artworkUrl } == listOf(firstCover, secondCover) }
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodesWithTag(ARTWORK_TAG, useUnmergedTree = true).fetchSemanticsNodes().size == 2 }

        // Deezer can't queue them, so the button plays the first.
        click(string(R.string.play_first_in, string(MusicService.DEEZER.labelRes)))
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://www.deezer.com/search/First%20Song%20Band", nextStartedActivity()!!.dataString)
        assertFalse(activity.isFinishing)

        // Shown again from Recent, it needs no lookups.
        click(string(R.string.clear_button))
        fake.requestedUrls.clear()
        composeRule.onNodeWithText("Road Trip").performScrollTo().performClick()
        waitForText("Second Song")
        assertTrue(fake.requestedUrls.none { "spotify.com" in it })

        // On Spotify itself it opens as itself, and each song by its own link.
        ViewModelProvider(activity)[MainViewModel::class.java].selectResultDestination(Destination.Service(MusicService.SPOTIFY))
        composeRule.waitForIdle()
        assertTextAbsent(string(R.string.play_first_in, string(MusicService.SPOTIFY.labelRes)))
        composeRule.onNodeWithText("Second Song").performScrollTo().performClick()
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://open.spotify.com/track/second", nextStartedActivity()!!.dataString)
    }

    @Test
    fun coversFoundBeforeLeavingAPlaylistAreKeptWithItInRecent() {
        prefs().edit().putString("default_target", "DEEZER").commit()
        val firstCover = "https://i.scdn.co/image/first"
        val playlistUrl = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"
        val embed = """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":{"trackList":[
            {"title":"First Song","subtitle":"Band","uri":"spotify:track:first"},{"title":"Second Song","subtitle":"Band","uri":"spotify:track:second"}]}}}}}}</script>"""
        // The second song's cover is slow, so the playlist is left before it comes.
        val slow = CountDownLatch(1)
        fake.handler = { request ->
            when {
                request.url.encodedPath.startsWith("/embed/") -> FakeSpotify.html(request, embed)
                request.url.encodedPath == "/oembed" && "first" in request.url.toString() -> FakeSpotify.html(request, """{"thumbnail_url":"$firstCover"}""")
                request.url.encodedPath == "/oembed" -> {
                    slow.await(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                    FakeSpotify.html(request, "{}")
                }
                request.url.encodedPath.startsWith("/playlist/") -> FakeSpotify.html(request, FakeSpotify.trackPage("Road Trip | Spotify", "Playlist"))
                request.url.host == "open.spotify.com" -> FakeSpotify.html(request, FakeSpotify.trackPage("Other Song | Spotify", "Artist · Song"))
                else -> FakeSpotify.image(request, FakeSpotify.png())
            }
        }
        val activity = launch()
        resolveTyped(playlistUrl)
        waitForText("First Song")
        waitUntil { fake.requestedUrls.any { "oembed" in it && "first" in it } && fake.requestedUrls.any { "oembed" in it && "second" in it } }
        waitUntil { ViewModelProvider(activity)[MainViewModel::class.java].uiState.result?.tracks?.get(0)?.artworkUrl == firstCover }

        // On to another link while the second cover is still on its way.
        click(string(R.string.clear_button))
        resolveTyped("https://open.spotify.com/track/$TRACK_ID")
        slow.countDown()

        val saved = HistoryStore(prefs()).load().first { it.link.url == playlistUrl }.metadata.tracks
        assertEquals(listOf(firstCover, null), saved.map { it.artworkUrl })
    }

    @Test
    fun aLongPlaylistPlaysInPartsOfWhatYouTubeTakes() {
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").putBoolean("play_right_away", true).commit()
        val songs = (1..60).joinToString(",") { """{"title":"Song $it","subtitle":"Band"}""" }
        val embed = """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":{"trackList":[$songs]}}}}}}</script>"""
        // Holds the song lookups so the progress can be seen.
        val release = CountDownLatch(1)
        fake.handler = { request ->
            // Its own body: songs are looked up several at a time, so the last one sent may be another's.
            val body = okio.Buffer().also { request.body?.writeTo(it) }.readUtf8()
            when {
                request.url.encodedPath.startsWith("/embed/") -> FakeSpotify.html(request, embed)
                request.url.encodedPath == "/watch_videos" -> FakeSpotify.html(request, "").newBuilder().code(303)
                    .header("Location", "https://www.youtube.com/watch?v=x&list=TLGGpart").build()
                // Each song is found as itself, its video named after its number.
                request.url.host == "music.youtube.com" -> {
                    release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    val number = Regex("Song (\\d+)").find(body)!!.groupValues[1]
                    FakeSpotify.html(request, """{"contents":[{"musicResponsiveListItemRenderer":{
                        "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song $number"}]}}},
                        {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Band • Album • 3:00"}]}}}],
                        "playlistItemData":{"videoId":"${number.padStart(11, '0')}"}}}]}""")
                }
                request.url.host == "open.spotify.com" -> FakeSpotify.html(request, FakeSpotify.trackPage("Long | Spotify", "Playlist"))
                // Covers, which don't matter here.
                else -> FakeSpotify.html(request, "", code = 404)
            }
        }
        launch()
        resolveTyped("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
        waitForText("Song 1")
        // The main button says which part it plays; screen readers hear where.
        val youTubeMusic = string(MusicService.YOUTUBE_MUSIC.labelRes)
        val firstPart = string(R.string.play_part_in, 1, 50, youTubeMusic)
        composeRule.onNodeWithContentDescription(firstPart).assertExists()
        assertTextShown(string(R.string.part_range, 1, 50))
        val secondPart = composeRule.onNodeWithText(string(R.string.part_range, 51, 60)).performScrollTo()
        // With the card scrolled away, a floating button stands in for its main one. The second part
        // only just showed at the bottom, so it still plays the first.
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodesWithContentDescription(firstPart, useUnmergedTree = true).fetchSemanticsNodes().size == 2 }
        // Scrolled into the second part, it plays that one.
        composeRule.onNodeWithText("Song 60").performScrollTo()
        val secondPartIn = string(R.string.play_part_in, 51, 60, youTubeMusic)
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodesWithContentDescription(secondPartIn, useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        // Each part's header plays it. The floating button may cover it at the bottom edge, so it's tapped by its action.
        secondPart.performSemanticsAction(SemanticsActions.OnClick)
        // It shows how far it got while it looks the songs up.
        waitForText(string(R.string.play_all_progress, 0, 10))
        release.countDown()
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://music.youtube.com/watch?v=00000000051&list=TLGGpart", nextStartedActivity()!!.dataString)
        // Only those ten were looked up.
        assertEquals(10, fake.requestedUrls.count { "music.youtube.com" in it })
    }

    /** A playlist whose songs are YouTube videos, as a YouTube playlist's are. */
    private fun youtubeSongPlaylist() {
        val lockup = { id: String, title: String ->
            """{"lockupViewModel":{"contentType":"LOCKUP_CONTENT_TYPE_VIDEO","contentId":"$id","metadata":{"lockupMetadataViewModel":{"title":{"content":"$title"},
                "metadata":{"contentMetadataViewModel":{"metadataRows":[{"metadataParts":[{"text":{"content":"Band"}}]}]}}}}}}"""
        }
        fake.handler = { request ->
            when {
                request.url.host == "music.youtube.com" -> FakeSpotify.html(request, """{"contents":[]}""")
                else -> FakeSpotify.html(
                    request,
                    """<meta property="og:title" content="Road Trip"><script>var ytInitialData = {"a":[${lockup("first000000", "First Song")},${lockup("second00000", "Second Song")}]};</script>"""
                )
            }
        }
    }

    @Test
    fun aPlaylistsYouTubeSongOpensAsItselfInYouTubeMusic() {
        // A song of a playlist that came from YouTube plays in YouTube Music as itself, not as a
        // search for its title, which finds another recording or none.
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").commit()
        youtubeSongPlaylist()
        launch()
        resolveTyped("https://www.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI")
        waitForText("First Song")
        composeRule.onNodeWithText("First Song").performScrollTo().performClick()
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://music.youtube.com/watch?v=first000000", nextStartedActivity()!!.dataString)
    }

    @Test
    fun playAllSaysSoWhenNoSongMatchesAndIsOnlyForYouTube() {
        prefs().edit().putString("default_target", "YOUTUBE").putBoolean("play_right_away", true).commit()
        collectionWithSongs()
        launch()
        resolveTyped("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
        waitForText("First Song")
        fake.handler = { request -> FakeSpotify.html(request, """{"contents":[]}""") }
        click(string(R.string.play_all_in, string(MusicService.YOUTUBE.labelRes)))
        waitForText(string(R.string.error_not_found))
        assertNull(nextStartedActivity())

        // Elsewhere there's no queue to make, so it isn't offered.
        click(string(R.string.clear_button))
        prefs().edit().putString("default_target", "DEEZER").commit()
        collectionWithSongs()
        controller!!.pause().resume()
        resolveTyped("https://open.spotify.com/album/4yP0hdKOZPNshxUOjY0cZj")
        waitForText("First Song")
        assertTextAbsent(string(R.string.play_all_button))
    }

    @Test
    fun aYouTubePlaylistOpensAsItselfInYouTubeMusic() {
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").commit()
        fake.handler = { request -> FakeSpotify.html(request, """<meta property="og:title" content="Road Trip">""") }
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI")))
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://music.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI", nextStartedActivity()!!.dataString)
    }

    @Test
    fun aYouTubeVideoOpensAsItselfInTheOtherYouTubeApp() {
        // Both apps play the same video, so a search for its title, which finds another recording
        // or none, never stands in for it.
        prefs().edit().putString("default_target", "YOUTUBE").commit()
        fake.handler = { request -> FakeSpotify.html(request, """{"title":"抱かれに来た女 - Dakare Ni Kita Onna","author_name":"Kingo Hamada"}""") }
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/watch?v=sPmul8b17AU")))
        // Waits on the link that opened rather than on the screen closing, which also waits on
        // Android for the installed apps, and that can outlast a timeout on a busy machine.
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://www.youtube.com/watch?v=sPmul8b17AU", nextStartedActivity()!!.dataString)

        nextStartedActivity()
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").commit()
        fake.handler = { request -> FakeSpotify.html(request, """{"title":"Street Dolphin","author_name":"Kingo Hamada"}""") }
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=VDuDQNkSC6g")))
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://music.youtube.com/watch?v=VDuDQNkSC6g", nextStartedActivity()!!.dataString)
    }

    @Test
    fun aLongRecentListCanBeSearchedByTitleOrArtist() {
        val store = HistoryStore(prefs())
        (1..6).forEach { n ->
            store.add(HistoryEntry(MusicLink(MusicService.DEEZER, ItemType.TRACK, "$n", "https://www.deezer.com/track/$n"), MusicMetadata("Song $n", if (n == 3) "Special Band" else "Band")))
        }
        launch()
        val search = string(R.string.search_recent)
        // The toolbar uses labelled icons; the field appears only when Search is tapped.
        composeRule.onNodeWithText(search).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.clear_history_button)).assertExists()
        composeRule.onNodeWithText(string(R.string.clear_history_button)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(search).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(search).performTextInput("special song")
        composeRule.waitForIdle()
        assertTextShown("Song 3")
        assertTextAbsent("Song 4")
        // Keep the open search and query through a configuration change.
        controller!!.recreate()
        composeRule.waitForIdle()
        assertTextShown("Song 3")
        assertTextAbsent("Song 4")
        composeRule.onNodeWithText("special song").performTextReplacement("不存在 🎵")
        composeRule.waitForIdle()
        assertTextAbsent("Song 3")
        // Closing search clears the filter; reopening starts with an empty field.
        click(string(R.string.close_recent_search))
        assertTextShown("Song 3")
        assertTextShown("Song 4")
        composeRule.onNodeWithText(search).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(search).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(search).assertExists()
        assertTextShown("Song 4")
    }

    @Test
    fun evenOneRecentSongCanBeSearchedAndBackClosesTheSearch() {
        HistoryStore(prefs()).add(HistoryEntry(MusicLink(MusicService.DEEZER, ItemType.TRACK, "1", "https://www.deezer.com/track/1"), MusicMetadata("A Song", "Band")))
        val activity = launch()
        click(string(R.string.search_recent))
        composeRule.onNodeWithText(string(R.string.search_recent)).performTextInput("missing")
        composeRule.waitForIdle()
        assertTextAbsent("A Song")
        composeRule.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertTextShown("A Song")
        composeRule.onNodeWithText(string(R.string.search_recent)).assertDoesNotExist()
        assertFalse(activity.isFinishing)
    }

    @Test
    fun aWidgetSongShowsInCrosstuneEvenWhenLinksOpenRightAway() {
        respondWithTrack("Widget Song", "Artist · Song")
        val activity = launch(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/track/$TRACK_ID"))
                .putExtra(MainActivity.EXTRA_SHOW_SONG, true)
        )
        assertResultShown()
        assertFalse(activity.isFinishing)
    }

    @Test
    fun theWidgetsPlayOpensASavedSongInTheUsualAppWithoutLoadingItAgain() {
        prefs().edit().putString("default_target", "DEEZER").putBoolean("play_right_away", false).commit()
        val url = "https://open.spotify.com/track/$TRACK_ID"
        HistoryStore(prefs()).add(HistoryEntry(MusicLink(MusicService.SPOTIFY, ItemType.TRACK, TRACK_ID, url), MusicMetadata("Saved", "Artist")))

        launch(Intent(MainActivity.ACTION_OPEN_RECENT, Uri.parse(url)))

        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://www.deezer.com/search/Saved%20Artist", nextStartedActivity()!!.dataString)
        assertTrue(fake.requestedUrls.isEmpty())
    }

    @Test
    fun aListKeptWithoutItsSongsIsLookedUpAgainForThem() {
        val url = "https://www.deezer.com/playlist/9"
        // Kept by an older version, which couldn't read its songs.
        HistoryStore(prefs()).add(HistoryEntry(MusicLink(MusicService.DEEZER, ItemType.PLAYLIST, "9", url), MusicMetadata("Mix", "", ItemType.PLAYLIST)))
        fake.handler = { request ->
            FakeSpotify.html(request, """{"title":"Mix","picture_big":"","tracks":{"data":[{"title":"First Tune","artist":{"name":"Band"}}]}}""")
        }
        launch()
        resolveTyped(url)
        assertTrue(fake.requestedUrls.contains("https://api.deezer.com/playlist/9"))
        assertTextShown("First Tune")
    }

    private fun notOnDeezer() {
        fake.handler = { request ->
            if (request.url.host == "api.deezer.com") FakeSpotify.html(request, """{"data":[]}""")
            else FakeSpotify.html(request, FakeSpotify.trackPage("Exact", "Artist · Song"))
        }
    }

    @Test
    fun theWidgetsPlayOnASongNotThereAsksWhereToPlayIt() {
        prefs().edit().putString("default_target", "DEEZER").putBoolean("play_right_away", true).commit()
        val url = "https://open.spotify.com/track/$TRACK_ID"
        val missing = mapOf("DEEZER" to PreparedLink("https://www.deezer.com/search/Saved%20Artist", exact = false, matchingEnabled = true))
        HistoryStore(prefs()).add(HistoryEntry(MusicLink(MusicService.SPOTIFY, ItemType.TRACK, TRACK_ID, url), MusicMetadata("Saved", "Artist"), missing))

        val activity = launch(Intent(MainActivity.ACTION_OPEN_RECENT, Uri.parse(url)))

        // It stays, saying so, rather than opening a search that finds nothing.
        waitForText(string(R.string.not_found_on, string(R.string.target_deezer)))
        assertFalse(activity.isFinishing)
        assertNull(nextStartedActivity())
        click(string(R.string.open_in_spotify))
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        val opened = nextStartedActivity()!!
        assertEquals(url, opened.dataString)
        assertEquals(MusicService.SPOTIFY.packageName, opened.`package`)
        assertTextAbsent(string(R.string.not_found_on, string(R.string.target_deezer)))

        // Asked again, a search anyway; and again, nothing at all.
        val model = ViewModelProvider(activity)[MainViewModel::class.java]
        composeRule.runOnIdle { model.openRecent(url) }
        click(string(R.string.search_anyway, string(R.string.target_deezer)))
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://www.deezer.com/search/Saved%20Artist", nextStartedActivity()!!.dataString)
        composeRule.runOnIdle { model.openRecent(url) }
        waitForText(string(R.string.not_found_on, string(R.string.target_deezer)))
        composeRule.runOnIdle { model.dismissNotFoundOffer() }
        composeRule.waitForIdle()
        assertTextAbsent(string(R.string.not_found_on, string(R.string.target_deezer)))
        assertNull(nextStartedActivity())
        // Nothing offered, there's nothing to take.
        composeRule.runOnIdle { model.takeNotFoundOffer(true) }
        assertNull(nextStartedActivity())

        // Set to, it opens where it's from, or searches where it was going.
        composeRule.runOnIdle { model.selectNotFoundAction(NotFoundAction.ORIGINAL) }
        assertEquals(url, widgetPlays(url))
        composeRule.runOnIdle { model.selectNotFoundAction(NotFoundAction.SEARCH) }
        assertEquals("https://www.deezer.com/search/Saved%20Artist", widgetPlays(url))
    }

    @Test
    fun aTappedLinkNotThereStaysToSaySo() {
        prefs().edit().putString("default_target", "DEEZER").putBoolean("play_right_away", true).commit()
        notOnDeezer()
        val activity = launch(trackLink())

        waitForText(string(R.string.not_found_on, string(R.string.target_deezer)))
        assertFalse(activity.isFinishing)
        assertNull(nextStartedActivity())
        // Sharing it shares where it is, which has it.
        click(string(R.string.share_link_button))
        assertEquals("https://open.spotify.com/track/$TRACK_ID", nextStartedActivity()!!.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!.getStringExtra(Intent.EXTRA_TEXT))
        click(string(R.string.search_anyway, string(R.string.target_deezer)))
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://www.deezer.com/search/Exact%20Artist", nextStartedActivity()!!.dataString)
    }

    @Test
    fun anAppAskedToOpenLongAgoWhileAwayIsntOpenedOnComingBack() {
        prefs().edit().putString("default_target", "DEEZER").commit()
        fake.handler = { request -> FakeSpotify.html(request, FakeSpotify.trackPage("Exact", "Artist · Song")) }
        val activity = launch()
        resolveTyped("https://open.spotify.com/track/$TRACK_ID")
        val model = ViewModelProvider(activity)[MainViewModel::class.java]
        // Asked while away, and back a while later: it's not opened out of the blue.
        controller!!.pause().stop()
        composeRule.runOnIdle { model.openResult() }
        composeRule.waitForIdle()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(61))
        controller!!.restart().resume()
        composeRule.waitForIdle()
        assertNull(nextStartedActivity())
        // Back soon after, it is.
        controller!!.pause().stop()
        composeRule.runOnIdle { model.openResult() }
        composeRule.waitForIdle()
        controller!!.restart().resume()
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://www.deezer.com/search/Exact%20Artist", nextStartedActivity()!!.dataString)
    }

    @Test
    fun aLookupWithNoAnswerSearchesRatherThanSayingItsNotThereAndIsTriedAgain() {
        prefs().edit().putString("default_target", "DEEZER").putBoolean("play_right_away", true).commit()
        var deezerUp = false
        fake.handler = { request ->
            when {
                request.url.host != "api.deezer.com" -> FakeSpotify.html(request, FakeSpotify.trackPage("Exact", "Artist · Song"))
                deezerUp -> FakeSpotify.html(request, """{"data":[]}""")
                else -> FakeSpotify.html(request, "", code = 503)
            }
        }
        val url = "https://open.spotify.com/track/$TRACK_ID"
        val activity = launch(trackLink())

        // Deezer down says nothing about whether it has the song, so it's searched there.
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://www.deezer.com/search/Exact%20Artist", nextStartedActivity()!!.dataString)
        assertTextAbsent(string(R.string.not_found_on, string(R.string.target_deezer)))
        assertEquals(false, HistoryStore(prefs()).load().first().destinationLinks["DEEZER"]?.matchingEnabled)

        // Opened again from Recent, it's looked for again: now Deezer answers that it isn't there.
        deezerUp = true
        composeRule.runOnIdle { ViewModelProvider(activity)[MainViewModel::class.java].openRecent(url) }
        waitForText(string(R.string.not_found_on, string(R.string.target_deezer)))
    }

    @Test
    fun aSongByNameNotThereIsSearchedForWithItsButton() {
        prefs().edit().putString("default_target", "DEEZER").putBoolean("play_right_away", true).commit()
        fake.handler = { request -> FakeSpotify.html(request, """{"data":[]}""") }
        val activity = launch()
        // A song by name only, e.g. one heard, has nowhere else to open.
        composeRule.runOnIdle { ViewModelProvider(activity)[MainViewModel::class.java].openNamedSong("Owaranai Uta", "The Blue Hearts") }
        waitForText(string(R.string.not_found_on, string(R.string.target_deezer)))
        assertNull(nextStartedActivity())

        // So its button searches where it goes, as it says.
        click(string(R.string.search_in_destination, string(R.string.target_deezer)))
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://www.deezer.com/search/Owaranai%20Uta%20The%20Blue%20Hearts", nextStartedActivity()!!.dataString)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun someonesOwnPlaylistWithoutItsSongsIsNotSearchedForElsewhere() {
        prefs().edit().putString("default_target", "DEEZER").putBoolean("play_right_away", true).putString("not_found", "ORIGINAL").commit()
        var askedForAlbum = false
        fake.handler = { request ->
            if (request.url.host == "api.deezer.com") {
                askedForAlbum = askedForAlbum || "/search/album" in request.url.encodedPath
                FakeSpotify.html(request, """{"data":[]}""")
            } else {
                FakeSpotify.html(request, """{"title":"#cumple by Rebeca","author_name":"Rebeca"}""")
            }
        }
        val url = "https://soundcloud.com/bequibequita/sets/cumple"
        launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

        // Its songs unknown, it might have been an album, but isn't one, so it opens where it's from.
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        val opened = nextStartedActivity()!!
        assertEquals(url, opened.dataString)
        assertEquals(MusicService.SOUNDCLOUD.packageName, opened.`package`)
        assertTrue(askedForAlbum)
    }

    @Test
    fun aTappedLinkNotThereOpensWhereItsFromWhenSetSo() {
        prefs().edit().putString("default_target", "DEEZER").putBoolean("play_right_away", true).putString("not_found", "ORIGINAL").commit()
        notOnDeezer()
        val activity = launch(trackLink())

        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        val opened = nextStartedActivity()!!
        assertEquals("https://open.spotify.com/track/$TRACK_ID", opened.dataString)
        assertEquals(MusicService.SPOTIFY.packageName, opened.`package`)
        waitUntil(TIMEOUT_MS) { activity.isFinishing }
    }

    @Test
    fun aPlaylistSongNotThereWithNowhereElseToPlayIsSearched() {
        prefs().edit().putString("default_target", "DEEZER").putBoolean("play_right_away", true).putString("not_found", "ORIGINAL").commit()
        val embed = """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":{"trackList":[
            {"title":"First Song","subtitle":"Band"},{"title":"Second Song","subtitle":""}]}}}}}}</script>"""
        fake.handler = { request ->
            when {
                request.url.encodedPath.startsWith("/embed/") -> FakeSpotify.html(request, embed)
                request.url.host == "api.deezer.com" -> FakeSpotify.html(request, """{"data":[]}""")
                else -> FakeSpotify.html(request, FakeSpotify.trackPage("Road Trip | Spotify", "Playlist"))
            }
        }
        val activity = launch()
        resolveTyped("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
        waitForText("First Song")

        // A playlist's songs come without links of their own, so where it's from is only a search away.
        composeRule.onNodeWithText("Second Song").performScrollTo().performClick()
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://www.deezer.com/search/Second%20Song", nextStartedActivity()!!.dataString)

        // Asked, only the search is offered.
        composeRule.runOnIdle { ViewModelProvider(activity)[MainViewModel::class.java].selectNotFoundAction(NotFoundAction.ASK) }
        composeRule.onNodeWithText("First Song").performScrollTo().performClick()
        waitForText(string(R.string.not_found_on, string(R.string.target_deezer)))
        assertTextAbsent(string(R.string.open_in_spotify))
        assertNull(nextStartedActivity())
        click(string(R.string.search_anyway, string(R.string.target_deezer)))
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://www.deezer.com/search/First%20Song%20Band", nextStartedActivity()!!.dataString)
    }

    @Test
    fun theWidgetsPlayOpensASavedPlaylistAsAQueueNotASearch() {
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").putBoolean("play_right_away", true).commit()
        collectionWithSongs()
        val url = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"
        HistoryStore(prefs()).add(
            HistoryEntry(
                MusicLink(MusicService.SPOTIFY, ItemType.PLAYLIST, "37i9dQZF1DXcBWIGoYBM5M", url),
                MusicMetadata("Road Trip", "", ItemType.PLAYLIST, tracks = listOf(MusicMetadata("First Song", "Band")))
            )
        )

        launch(Intent(MainActivity.ACTION_OPEN_RECENT, Uri.parse(url)))

        // The screen closing waits on Android for the installed apps, which can outlast
        // a timeout on a loaded machine, so the link that opened is what gets awaited.
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://music.youtube.com/watch?v=first000000&list=TLGGqueue", nextStartedActivity()!!.dataString)
    }

    private val savedPlaylistUrl = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"

    private fun savePlaylist(tracks: List<MusicMetadata>, link: MusicLink = MusicLink(MusicService.SPOTIFY, ItemType.PLAYLIST, "37i9dQZF1DXcBWIGoYBM5M", savedPlaylistUrl)) {
        HistoryStore(prefs()).add(HistoryEntry(link, MusicMetadata("Road Trip", "", ItemType.PLAYLIST, tracks = tracks)))
    }

    private fun widgetPlays(url: String): String? {
        launch(Intent(MainActivity.ACTION_OPEN_RECENT, Uri.parse(url)))
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        return nextStartedActivity()!!.dataString
    }

    @Test
    fun theWidgetsPlayOnAPlaylistKeepsToTheFirst50AndTheirOwnVideos() {
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").putBoolean("play_right_away", true).commit()
        savePlaylist((1..51).map { MusicMetadata("Song $it", "Band") })
        // Every song was found before, so only the playlist is asked of YouTube.
        val found = (1..51).joinToString(",") { """["find|YOUTUBE_MUSIC|TRACK|Song $it|Band|","https://music.youtube.com/watch?v=video${it.toString().padStart(6, '0')}"]""" }
        java.io.File(app.cacheDir, "lookups.json").writeText("[$found]")
        var asked = ""
        fake.handler = { request ->
            asked = java.net.URLDecoder.decode(request.url.queryParameter("video_ids")!!, "UTF-8")
            FakeSpotify.html(request, "").newBuilder().code(303).header("Location", "https://www.youtube.com/watch?v=v1&list=TLGGq").build()
        }

        try {
            assertEquals("https://music.youtube.com/watch?v=video000001&list=TLGGq", widgetPlays(savedPlaylistUrl))
            assertEquals(50, asked.split(",").size)
            assertEquals("video000050", asked.split(",").last())
        } finally {
            java.io.File(app.cacheDir, "lookups.json").delete()
        }
    }

    @Test
    fun theWidgetsPlayOnAPlaylistSearchesItsNameWhenNothingCanBeQueued() {
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").putBoolean("play_right_away", true).commit()
        // Nothing found for any song, nothing saved at all, and YouTube refusing the queue each end in a search.
        savePlaylist(listOf(MusicMetadata("Unknown", "Nobody")))
        fake.handler = { request -> FakeSpotify.html(request, """{"contents":{}}""") }
        assertEquals("https://music.youtube.com/search?q=Road%20Trip", widgetPlays(savedPlaylistUrl))
    }

    @Test
    fun theWidgetsPlayOnAPlaylistWithoutSongsSearchesItsNameWhenSetTo() {
        // Not found as an album, which it might have been, it's searched for, as set.
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").putBoolean("play_right_away", true).putString("not_found", "SEARCH").commit()
        savePlaylist(emptyList())
        fake.handler = { request -> FakeSpotify.html(request, """{"contents":{}}""") }
        assertEquals("https://music.youtube.com/search?q=Road%20Trip", widgetPlays(savedPlaylistUrl))
        assertTrue(fake.requestBodies.any { "Road Trip" in it })
    }

    @Test
    fun theWidgetsPlayOnAPlaylistIsOnlyAQueueWhereYouTubeCanMakeOne() {
        prefs().edit().putString("default_target", "DEEZER").putBoolean("play_right_away", true).commit()
        savePlaylist(listOf(MusicMetadata("First Song", "Band")))
        fake.handler = { request -> FakeSpotify.html(request, """{"data":[]}""") }
        assertEquals("https://www.deezer.com/search/Road%20Trip", widgetPlays(savedPlaylistUrl))
        assertTrue(fake.requestedUrls.none { "watch_videos" in it })
    }

    @Test
    fun theWidgetsPlayOnAYouTubePlaylistOpensItAsItselfInYouTubeMusic() {
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").putBoolean("play_right_away", true).commit()
        val url = "https://www.youtube.com/playlist?list=PLabc123"
        savePlaylist(listOf(MusicMetadata("First Song", "Band")), MusicLink(MusicService.YOUTUBE, ItemType.PLAYLIST, "PLabc123", url))
        assertEquals("https://music.youtube.com/playlist?list=PLabc123", widgetPlays(url))
        assertTrue(fake.requestedUrls.isEmpty())
    }

    @Test
    fun theWidgetsPlayOpensASavedAlbumAsTheAlbumNotAsAQueue() {
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").putBoolean("play_right_away", true).putString("not_found", "SEARCH").commit()
        collectionWithSongs(type = "album")
        val url = "https://open.spotify.com/album/4aawyAB9vmqN3uQ7FjRGTy"
        HistoryStore(prefs()).add(
            HistoryEntry(
                MusicLink(MusicService.SPOTIFY, ItemType.ALBUM, "4aawyAB9vmqN3uQ7FjRGTy", url),
                MusicMetadata("Road Trip", "Band", ItemType.ALBUM, tracks = listOf(MusicMetadata("First Song", "Band")))
            )
        )

        val activity = launch(Intent(MainActivity.ACTION_OPEN_RECENT, Uri.parse(url)))

        waitUntil(TIMEOUT_MS) { activity.isFinishing }
        // YouTube Music has no album by that name here, so it's searched for, not played as its songs.
        val opened = nextStartedActivity()!!.dataString!!
        assertTrue(opened, opened.startsWith("https://music.youtube.com/search"))
        assertTrue(fake.requestedUrls.none { it.contains("watch_videos") })
    }

    @Test
    fun theWidgetsPlayOpensASongNoLongerInRecentLikeAnyLink() {
        respondWithTrack("Gone", "Artist · Song")
        launch(Intent(MainActivity.ACTION_OPEN_RECENT, Uri.parse("https://open.spotify.com/track/$TRACK_ID")))
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("com.google.android.apps.youtube.music", nextStartedActivity()!!.`package`)
    }

    @Test
    fun selectedTextOpensInTheDefaultApp() {
        respondWithTrack("Selected", "Artist · Song")
        launch(
            Intent(Intent.ACTION_PROCESS_TEXT)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, "https://open.spotify.com/track/$TRACK_ID")
        )

        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("com.google.android.apps.youtube.music", nextStartedActivity()!!.`package`)
    }

    private fun dynamicShortcuts(): List<ShortcutInfo> = app.getSystemService(ShortcutManager::class.java).dynamicShortcuts

    @Test
    fun recentSongsAreOfferedOnTheLauncherIconUntilHistoryIsCleared() {
        // No app picked, so the share sheet's own entries aren't there, and the song's all there is.
        prefs().edit().remove("default_target").commit()
        respondWithTrack("Recent Song", "Artist · Song")
        launch()
        resolveTyped("https://open.spotify.com/track/$TRACK_ID")

        waitUntil(TIMEOUT_MS) { dynamicShortcuts().isNotEmpty() }
        val song = dynamicShortcuts().single()
        assertEquals("Recent Song", song.shortLabel)
        // Tapping it opens the song like tapping its link, wherever the user listens.
        assertEquals(Intent.ACTION_VIEW, song.intent!!.action)
        assertEquals("https://open.spotify.com/track/$TRACK_ID", song.intent!!.dataString)

        // The song on screen isn't listed again under Recent.
        assertTextAbsent(string(R.string.clear_history_button))
        click(string(R.string.clear_button))
        click(string(R.string.clear_history_button))
        waitUntil(TIMEOUT_MS) { dynamicShortcuts().isEmpty() }
        assertTrue(HistoryStore(prefs()).load().isEmpty())

        // Clearing can be undone for a while, which brings the shortcuts back too.
        click(string(R.string.undo_button))
        waitUntil(TIMEOUT_MS) { dynamicShortcuts().isNotEmpty() }
        assertEquals(1, HistoryStore(prefs()).load().size)

        // Once the offer is gone, the cleared history is too.
        click(string(R.string.clear_history_button))
        composeRule.mainClock.advanceTimeBy(15_000)
        composeRule.waitForIdle()
        assertTextAbsent(string(R.string.undo_button))
        val model = ViewModelProvider(controller!!.get())[MainViewModel::class.java]
        assertFalse(model.uiState.canUndoClearHistory)
        assertTrue(HistoryStore(prefs()).load().isEmpty())
    }

    @Test
    fun recentItemsShowTheAppTheyOpenInAndCanBeSwipedAwayOneAtATime() {
        prefs().edit().putString("default_target", "DEEZER").commit()
        // Apple Music links go to TIDAL instead of the default.
        DestinationStore(prefs()).setRule(MusicService.APPLE_MUSIC, Destination.Service(MusicService.TIDAL))
        val store = HistoryStore(prefs())
        listOf("Third", "Second", "First").forEachIndexed { index, title ->
            store.add(HistoryEntry(MusicLink(MusicService.SPOTIFY, ItemType.TRACK, "id$index", "https://open.spotify.com/track/id$index"), MusicMetadata(title, "Artist")))
        }
        store.add(HistoryEntry(MusicLink(MusicService.APPLE_MUSIC, ItemType.TRACK, "1", "https://music.apple.com/us/song/1"), MusicMetadata("Elsewhere", "Artist")))
        launch()
        // A plain play button on rows going to the default app; only the one going elsewhere shows where.
        composeRule.onAllNodesWithTag("destination-icon:DEEZER", useUnmergedTree = true).assertCountEquals(0)
        composeRule.onAllNodesWithTag("destination-icon:TIDAL", useUnmergedTree = true).assertCountEquals(1)
        composeRule.onNode(hasContentDescription(string(R.string.history_open, "First"))).assertExists()
        fun titles() = HistoryStore(prefs()).load().map { it.metadata.title }

        // Swiped away, it goes, and undo puts it back where it was.
        composeRule.onNodeWithText("Second").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertEquals(listOf("Elsewhere", "First", "Third"), titles())
        assertTextShown(string(R.string.history_removed))
        click(string(R.string.undo_button))
        assertEquals(listOf("Elsewhere", "First", "Second", "Third"), titles())

        // Either way works. Removing another while the offer shows makes the offer about that one.
        composeRule.onNodeWithText("First").performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        assertEquals(listOf("Elsewhere", "Second", "Third"), titles())
        // TalkBack can't swipe a row, so it has Remove instead.
        val row = composeRule.onNodeWithText("Third").fetchSemanticsNode()
        composeRule.runOnUiThread { row.config[SemanticsActions.CustomActions].single { it.label == string(R.string.remove_button) }.action() }
        composeRule.waitForIdle()
        assertEquals(listOf("Elsewhere", "Second"), titles())
        click(string(R.string.undo_button))
        assertEquals(listOf("Elsewhere", "Second", "Third"), titles())
    }

    @Test
    fun theShareSheetOffersTheDefaultAppAndToShareCopyOrShowAfterTheRecentSongs() {
        prefs().edit().putString("default_target", "DEEZER").commit()
        respondWithTrack("No Cover", "Artist · Song")
        launch()
        resolveTyped()

        // Three, as many as Android shows per app.
        waitUntil(TIMEOUT_MS) { dynamicShortcuts().size == 4 }
        val (song, deezer) = dynamicShortcuts().sortedBy { it.rank }
        assertEquals(
            listOf("No Cover", "Deezer", string(R.string.share_link_button), string(R.string.show_song_short)),
            dynamicShortcuts().sortedBy { it.rank }.map { it.shortLabel }
        )
        assertEquals("No Cover", song.shortLabel)
        // Named by the app alone, so a long "Open in …" isn't cut off in the share sheet.
        assertNull(deezer.longLabel)
        assertEquals(setOf(AppShortcuts.SHARE_CATEGORY), deezer.categories)
        // Android drops shortcuts hidden from the launcher, so there they use the copied link.
        assertEquals(MainActivity.ACTION_PASTE_FROM_CLIPBOARD, deezer.intent!!.action)
        assertEquals(MainActivity.PASTE_ALIAS, deezer.intent!!.component!!.className)

        // Android shows them for any shared text, so they can be turned off, leaving the song.
        click(string(R.string.settings_button))
        openAdvanced()
        composeRule.onNodeWithText(string(R.string.setting_share_sheet_apps)).performScrollTo().performClick()
        waitUntil(TIMEOUT_MS) { dynamicShortcuts().size == 1 }
        assertEquals("No Cover", dynamicShortcuts().single().shortLabel)
        assertFalse(prefs().getBoolean("share_sheet_apps", true))
    }

    @Test
    fun visualsCanBeTurnedOffOneByOneAndPickedInSettings() {
        val names = milkdropPresets(app.assets)
        val first = names.first()
        prefs().edit().putStringSet("visuals_heavy", setOf(names[1])).commit()
        launch()
        click(string(R.string.settings_button))
        // The one too heavy for this phone is off already.
        composeRule.onNodeWithText(string(R.string.visuals_list_summary, names.size - 1, names.size)).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText(string(R.string.visual_heavy)).fetchSemanticsNodes().size == 1)
        // Off by hand, and the heavy one on anyway.
        composeRule.onNode(hasContentDescription(first) and isToggleable()).performScrollTo().performClick()
        composeRule.onNode(hasContentDescription(names[1]) and isToggleable()).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(setOf(first), prefs().getStringSet("visuals_off", null))
        assertEquals(setOf(names[1]), prefs().getStringSet("visuals_keep", null))
        // Heavy ones shown too, from the top of the same page.
        composeRule.onNodeWithText(string(R.string.setting_skip_heavy_visuals)).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertFalse(prefs().getBoolean("visuals_skip_heavy", true))
        click(string(R.string.back_button))
        // Back in Settings where it was, not at the top.
        composeRule.onNodeWithText(string(R.string.visuals_list_summary, names.size - 1, names.size)).assertIsDisplayed()
        // Each for a minute, slid to it, and said beside it.
        composeRule.onNode(hasContentDescription(string(R.string.setting_visuals_change))).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(VisualsSettings.CHANGE_EVERY.indexOf(60_000L).toFloat()) }
        composeRule.waitForIdle()
        assertEquals(60_000L, prefs().getLong("visuals_change_ms", 0))
        assertTextShown("1 min")
        // Slid all the way, one stays on.
        composeRule.onNode(hasContentDescription(string(R.string.setting_visuals_change)))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(VisualsSettings.CHANGE_EVERY.lastIndex.toFloat()) }
        composeRule.waitForIdle()
        assertEquals(VisualsSettings.NEVER, prefs().getLong("visuals_change_ms", 0))
        // How dark they are is set over them, not here.
        assertEquals(0, composeRule.onAllNodes(hasContentDescription(string(R.string.visuals_brightness))).fetchSemanticsNodes().size)
    }

    @Test
    @Config(sdk = [29])
    fun beforeAndroid11ShareSheetTargetsAreNotKeptLongLived() {
        prefs().edit().putString("default_target", "DEEZER").commit()
        launch()
        waitUntil(TIMEOUT_MS) { dynamicShortcuts().isNotEmpty() }
        assertEquals("Deezer", dynamicShortcuts().minBy { it.rank }.shortLabel)
    }

    private fun sharedFromTopRow(entry: String) = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "https://open.spotify.com/track/$TRACK_ID")
        putExtra(Intent.EXTRA_SHORTCUT_ID, entry)
    }

    @Test
    fun theTopRowsShareEntrySharesTheConvertedLinkWithoutCrosstune() {
        prefs().edit().putString("default_target", "DEEZER").putBoolean("play_right_away", false).commit()
        respondWithTrack("Shared On", "Artist · Song")
        launch(sharedFromTopRow("action:SHARE"))
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        val chooser = nextStartedActivity()!!
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals("https://www.deezer.com/search/Shared%20On%20Artist", chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun theTopRowsShowEntryShowsTheSongEvenWhenLinksOpenRightAway() {
        prefs().edit().putString("default_target", "DEEZER").commit()
        respondWithTrack("Shown Here", "Artist · Song")
        val activity = launch(sharedFromTopRow("action:SHOW"))
        waitForText("Shown Here")
        assertFalse(activity.isFinishing)
        assertNull(nextStartedActivity())
    }

    private var copies = 0L

    /**
     * [text] on the clipboard, as Android marks it copied [agoMs] ago, as [mimeType], and from
     * Android 12 how sure it is that it's a link, [linkScore], once it's looked.
     */
    private fun copied(text: String, agoMs: Long = 1_000, mimeType: String = ClipDescription.MIMETYPE_TEXT_PLAIN, linkScore: Float? = null) {
        val clip = ClipData(ClipDescription("copied", arrayOf(mimeType)), ClipData.Item(text))
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        // Set after, as Robolectric marks it copied on its own clock; each a moment after the last.
        val description = ClipDescription::class.java
        description.getDeclaredMethod("setTimestamp", Long::class.javaPrimitiveType).invoke(clip.description, System.currentTimeMillis() - agoMs + copies++)
        if (linkScore != null) {
            description.getDeclaredMethod("setConfidenceScores", Map::class.java).invoke(clip.description, mapOf(TextClassifier.TYPE_URL to linkScore))
            description.getDeclaredMethod("setClassificationStatus", Int::class.javaPrimitiveType).invoke(clip.description, ClipDescription.CLASSIFICATION_COMPLETE)
        }
    }

    /** Back to Crosstune from another app. */
    private fun comeBack() {
        controller!!.windowFocusChanged(false)
        controller!!.windowFocusChanged(true)
        composeRule.waitForIdle()
    }

    @Test
    fun aMusicLinkCopiedJustNowIsLookedUpOnComingBackOnce() {
        respondWithTrack("Copied Song", "Artist · Song")
        launch()
        copied("Listen: https://open.spotify.com/track/$TRACK_ID?si=x")
        comeBack()
        waitForText("Copied Song")
        assertResultShown()
        // Looked up, not opened: what to do with it is still up to the user.
        assertNull(nextStartedActivity())

        // Cleared, the same copy isn't brought back.
        click(string(R.string.clear_button))
        val asked = fake.requestedUrls.size
        comeBack()
        composeRule.onNodeWithTag(RESULT_TAG).assertDoesNotExist()
        assertEquals(asked, fake.requestedUrls.size)
    }

    @Test
    fun onlyAMusicLinkCopiedMomentsAgoIsLookedUp() {
        respondWithTrack("Copied Song", "Artist · Song")
        launch()
        // Copied long ago, it's no longer what the user is after.
        copied("https://open.spotify.com/track/$TRACK_ID", agoMs = 11 * 60 * 1000L)
        comeBack()
        // Other text is left alone, as is anything that isn't text, or that Android says isn't a link.
        copied("just some words")
        comeBack()
        copied("https://open.spotify.com/track/$TRACK_ID", mimeType = "image/png")
        comeBack()
        copied("https://open.spotify.com/track/$TRACK_ID", linkScore = 0f)
        comeBack()
        // And a song typed in isn't replaced.
        typeUrl("Some Song")
        copied("https://open.spotify.com/track/$TRACK_ID")
        comeBack()
        assertTextAbsent("Copied Song")
        assertTrue(fake.requestedUrls.isEmpty())
        // Said to be a link, it's looked up.
        click(string(R.string.clear_button))
        copied("https://open.spotify.com/track/$TRACK_ID", linkScore = 0.9f)
        comeBack()
        waitForText("Copied Song")
    }

    @Test
    fun aCopiedYouTubeVideoIsOnlyLookedUpOnceYouTubeMusicSaysItsMusic() {
        val activity = launch()
        val viewModel = ViewModelProvider(activity)[MainViewModel::class.java]
        fun copiedVideo(id: String) {
            copied("https://www.youtube.com/watch?v=$id")
            comeBack()
            waitUntil { fake.requestedUrls.any { it.startsWith("https://music.youtube.com/") } && !viewModel.lookingUp }
        }
        // Someone's own upload, like a vlog, is left alone, as is one YouTube Music can't tell about.
        respondNotMusic()
        copiedVideo("jNQXAC9IVRw")
        fake.handler = { request -> FakeSpotify.html(request, "{}") }
        copiedVideo("jNQXAC9IVRx")
        assertEquals("", viewModel.uiState.linkText)
        assertNull(viewModel.uiState.result)
        assertFalse(viewModel.uiState.isLoading)
        assertTrue(HistoryStore(prefs()).load().isEmpty())

        fake.handler = { request ->
            if (request.url.host == "music.youtube.com") FakeSpotify.html(request, """{"x":{"musicVideoType":"MUSIC_VIDEO_TYPE_OMV"}}""")
            else FakeSpotify.html(request, """{"title":"The Weeknd - Blinding Lights (Official Video)","author_name":"TheWeekndVEVO"}""")
        }
        copiedVideo("4NRXx6U8ABQ")
        waitForText("Blinding Lights")
        assertEquals("https://www.youtube.com/watch?v=4NRXx6U8ABQ", viewModel.uiState.linkText)
        assertNull(nextStartedActivity())
    }

    @Test
    fun aSongTypedWhileACopiedVideoIsCheckedIsntReplaced() {
        val typed = CountDownLatch(1)
        fake.handler = { request ->
            typed.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            FakeSpotify.html(request, """{"x":{"musicVideoType":"MUSIC_VIDEO_TYPE_OMV"}}""")
        }
        val activity = launch()
        val viewModel = ViewModelProvider(activity)[MainViewModel::class.java]
        copied("https://www.youtube.com/watch?v=4NRXx6U8ABQ")
        comeBack()
        typeUrl("Some Song")
        typed.countDown()
        waitUntil { !viewModel.lookingUp }
        assertEquals("Some Song", viewModel.uiState.linkText)
    }

    @Test
    fun aCopiedYouTubeVideoIsLookedUpRightAwayWhenAnyVideoMayBe() {
        prefs().edit().putBoolean("only_music_videos", false).commit()
        fake.handler = { request -> FakeSpotify.html(request, """{"title":"How to tie a tie","author_name":"Someone"}""") }
        launch()
        copied("https://www.youtube.com/watch?v=jNQXAC9IVRw")
        comeBack()
        waitForText("How to tie a tie")
        assertTrue(fake.requestedUrls.none { it.startsWith("https://music.youtube.com/") })
    }

    @Test
    fun aCopiedYouTubePlaylistIsLookedUpWithoutAskingIfItsMusic() {
        launch()
        copied("https://www.youtube.com/playlist?list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG")
        comeBack()
        waitUntil { fake.requestedUrls.any { it.startsWith("https://www.youtube.com/playlist") } }
        assertTrue(fake.requestedUrls.none { it.startsWith("https://music.youtube.com/") })
    }

    @Test
    fun aLinkCopiedInCrosstuneIsNotPastedBack() {
        prefs().edit().putString("default_target", "SPOTIFY").commit()
        respondWithTrack("Cut To The Feeling", "Carly Rae Jepsen · Song · 2017")
        launch()
        typeUrl(TRACK_ID)
        click(string(R.string.resolve_button))
        assertResultShown()
        click(string(R.string.copy_link_button))
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        assertNotNull(MusicLinks.parse(clipboard.primaryClip!!.getItemAt(0).text.toString()))
        // Marked by Android as copied a moment before Crosstune noted it.
        val description = clipboard.primaryClipDescription!!
        ClipDescription::class.java.getDeclaredMethod("setTimestamp", Long::class.javaPrimitiveType)
            .invoke(description, System.currentTimeMillis() - 1_000)
        click(string(R.string.clear_button))
        val asked = fake.requestedUrls.size
        comeBack()
        assertEquals(asked, fake.requestedUrls.size)
        composeRule.onNodeWithTag(RESULT_TAG).assertDoesNotExist()
    }

    @Test
    fun aLinkCopiedBeforeAnAppIsPickedIsFilledInToo() {
        freshInstall()
        respondWithTrack("Copied Song", "Artist · Song")
        launch()
        copied("https://open.spotify.com/track/$TRACK_ID")
        comeBack()
        waitForText("Copied Song")
    }

    @Test
    fun theTopRowsShareEntryFromTheLauncherSharesTheConvertedCopiedLink() {
        prefs().edit().putString("default_target", "DEEZER").putBoolean("play_right_away", false).commit()
        respondWithTrack("From Launcher", "Artist · Song")
        app.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("link", "https://open.spotify.com/track/$TRACK_ID"))
        launch(pasteIntent().putExtra(Intent.EXTRA_SHORTCUT_ID, "action:SHARE"))
        controller!!.windowFocusChanged(true)
        val activity = controller!!.get()
        waitUntil { activity.isFinishing }
        val chooser = nextStartedActivity()!!
        assertEquals("https://www.deezer.com/search/From%20Launcher%20Artist", chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun aShareSheetTargetFromTheLauncherOpensTheCopiedLinkInItsApp() {
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").commit()
        respondWithTrack("Copied", "Artist · Song")
        app.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("link", "https://open.spotify.com/track/$TRACK_ID"))

        launch(pasteIntent().putExtra(Intent.EXTRA_SHORTCUT_ID, "open_in:TIDAL"))
        controller!!.windowFocusChanged(true)
        val activity = controller!!.get()
        waitUntil { activity.isFinishing }
        assertEquals(MusicService.TIDAL.packageName, nextStartedActivity()!!.`package`)
    }

    @Test
    fun aShareSheetTargetOpensTheLinkInItsAppWithoutAsking() {
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").commit()
        linksAskWhere()
        respondWithTrack("Direct", "Artist · Song")
        launch(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "https://open.spotify.com/track/$TRACK_ID")
                putExtra(Intent.EXTRA_SHORTCUT_ID, "open_in:DEEZER")
            }
        )

        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals(MusicService.DEEZER.packageName, nextStartedActivity()!!.`package`)
    }

    @Test
    fun onlyShareSheetTargetIdsNameAnApp() {
        fun chosen(id: String?) = AppShortcuts.chosenDestination(Intent().putExtra(Intent.EXTRA_SHORTCUT_ID, id))
        assertEquals(Destination.Service(MusicService.TIDAL), chosen("open_in:TIDAL"))
        assertNull(chosen("recent:https://open.spotify.com/track/$TRACK_ID"))
        assertNull(chosen("open_in:NOPE"))
        assertNull(chosen(null))
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

        // A name rather than a link is looked up as a song, and the old song goes, since what is
        // on screen is now the songs that name turned up rather than a result to open.
        fake.handler = { request -> FakeSpotify.html(request, """{"data":[]}""") }
        typeUrl("just some words")
        // Searched for, so the button says so rather than offering to convert a link.
        composeRule.onNodeWithText(string(R.string.resolve_button)).assertDoesNotExist()
        click(string(R.string.search_button))

        assertResultAbsent()
        assertTextShown(string(R.string.song_search_title))
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
        assertTextShown(string(R.string.settings_advanced_title))

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

        assertTextAbsent(string(R.string.settings_advanced_title))
        assertTextShown(string(R.string.error_invalid_url))
    }

    @Test
    fun aSharedLinkLeavesThePracticeList() {
        prefs().edit().putString("saved_lines", """[{"text":"Night sky","translation":"Cielo","title":"Demo","artist":"Band"}]""").commit()
        launch()
        click("${string(R.string.saved_practice)}, ${string(R.string.practice_due, 1)}")
        assertTextShown(string(R.string.practice_all_songs))

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

        assertTextAbsent(string(R.string.practice_all_songs))
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

        click(string(R.string.copy_link_button))
        assertEquals(
            "https://music.youtube.com/search?q=Cut%20To%20The%20Feeling%20Carly%20Rae%20Jepsen",
            clipboard.primaryClip!!.getItemAt(0).text.toString()
        )

        click(string(R.string.share_link_button))
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
        click(string(R.string.make_default_named, string(R.string.target_youtube)))
        assertEquals("YOUTUBE", prefs().getString("default_target", null))
        assertTextShown(string(R.string.open_in_youtube))
        click(string(R.string.open_in_youtube))
        assertEquals("com.google.android.youtube", nextStartedActivity()!!.`package`)

        chooseDefault(string(R.string.target_youtube_music))
        click(string(R.string.make_default_named, string(R.string.target_youtube_music)))
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
        // Nothing to convert yet, so there's no button until there's text.
        composeRule.onNodeWithText(string(R.string.resolve_button)).assertDoesNotExist()
        val invalid = listOf(
            "https://example.com/track/$TRACK_ID",
            "https://open.spotify.com/show/$TRACK_ID",
            "https://open.spotify.com/track",
            "https://open.spotify.com/track/not-a-valid-id",
            "/track/$TRACK_ID",
            "spotify:track:short"
        )
        for (input in invalid) {
            typeUrl(input)
            // Each is a link, even one Crosstune can't open, so it's not searched for as a name.
            composeRule.onNodeWithText(string(R.string.search_button)).assertDoesNotExist()
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
        // A link Crosstune can't open, rather than a name, which is looked up instead.
        typeUrl("https://open.spotify.com/track/nope")
        click(string(R.string.resolve_button))
        assertTextShown(string(R.string.error_invalid_url))

        typeUrl("https://open.spotify.com/track/nope-again")
        assertTextAbsent(string(R.string.error_invalid_url))
    }

    @Test
    fun aPastedNowPlayingShareShowsTheSongWithoutRepeatingItInTheBox() {
        launch()
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("song", NOW_PLAYING_SHARE))
        click(string(R.string.paste_button))
        waitForText("A Song")
        // The song shows right under the box, so the box doesn't say it again.
        val box = composeRule.onNode(hasSetTextAction()).fetchSemanticsNode()
        assertEquals("", box.config[SemanticsProperties.EditableText].text)
        assertTextAbsent(string(R.string.error_invalid_url))
        assertOnlyCoverSearches()
    }

    @Test
    fun aRecognizedSongInRecentIsFoundAgainByTheNameTheBoxShows() {
        val url = "https://www.google.com/search?q=A%20Song%20by%20Example%20Band"
        HistoryStore(prefs()).add(HistoryEntry(MusicLink(null, ItemType.TRACK, url, url), MusicMetadata("A Song", "Example Band")))
        fake.handler = { throw IOException("offline") }
        launch()
        typeUrl("A Song by Example Band")
        click(string(R.string.search_button))
        assertResultShown()
        // Read back as the saved song: searched for by name while offline, nothing would show.
        assertTextShown("A Song")
    }

    @Test
    fun pasteLooksUpTheCopiedLinkWithoutOpeningIt() {
        respondWithTrack("Pasted Song", "Pasted Artist · Song")
        val activity = launch()
        app.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("link", "Listen: https://open.spotify.com/track/$TRACK_ID?si=x"))

        click(string(R.string.paste_button))
        waitForText("Pasted Song")

        // The box swaps the pasted text for the clean link, without "?si=".
        composeRule.onNode(hasSetTextAction()).assert(hasText("https://open.spotify.com/track/$TRACK_ID", substring = false))
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
        waitUntil(TIMEOUT_MS) {
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
        waitUntil(TIMEOUT_MS) { "https://img.example/cover.jpg" in fake.requestedUrls }
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
    @Config(qualifiers = "es")
    fun privacyPolicyOpensTheTranslationForTheAppLanguage() {
        launch()
        click(string(R.string.settings_button))
        composeRule.onNodeWithText(string(R.string.privacy_policy)).performScrollTo().performClick()

        assertEquals("https://crosstune.4st.li/es/privacy/", nextStartedActivity()!!.dataString)
    }

    @Test
    fun aWebPageWithNoBrowserToOpenItSaysSoRatherThanCrashing() {
        launch()
        click(string(R.string.settings_button))
        shadowOf(app).checkActivities(true)
        composeRule.onNodeWithText(string(R.string.privacy_policy)).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(string(R.string.error_no_app_to_open), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun madeByLinkOpensRepository() {
        launch()
        click(string(R.string.settings_button))
        composeRule.onNodeWithText(string(R.string.made_with_love, HEART).substringAfter(HEART).trim()).performScrollTo().performClick()

        val started = nextStartedActivity()
        assertEquals(Intent.ACTION_VIEW, started!!.action)
        assertEquals(string(R.string.github_repo_url), started.dataString)
    }

    @Test
    fun feedbackIsSentOnGitHubOrByEmailWithTheAppsVersion() {
        launch()
        click(string(R.string.settings_button))
        composeRule.onNodeWithText(string(R.string.report_problem)).performScrollTo().performClick()
        click(string(R.string.report_on_github))
        val issue = nextStartedActivity()!!.data!!
        assertEquals("https://github.com/astrovm/crosstune/issues/new", issue.toString().substringBefore("?"))
        assertTrue(issue.getQueryParameter("body")!!.contains("Crosstune 2."))
        assertTextAbsent(string(R.string.report_problem_text))
        composeRule.onNodeWithText(string(R.string.report_problem)).performScrollTo().performClick()
        click(string(R.string.report_by_email))
        val email = nextStartedActivity()!!.dataString!!
        assertTrue(email, email.startsWith("mailto:crosstune@4st.li?subject=Crosstune&body="))
    }

    @Test
    fun privacyPolicyOpensPublicPage() {
        launch()
        click(string(R.string.settings_button))
        composeRule.onNodeWithText(string(R.string.privacy_policy)).performScrollTo().performClick()

        val started = nextStartedActivity()
        assertEquals(Intent.ACTION_VIEW, started!!.action)
        assertEquals("https://crosstune.4st.li/privacy/", started.dataString)
    }

    @Test
    fun acceptedTrackLinkVariantsAllResolveToCanonicalTrack() {
        respondWithTrack("Variant", "Artist · Song")
        val activity = launch()
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
            assertEquals(input, "https://open.spotify.com/track/$TRACK_ID", ViewModelProvider(activity)[MainViewModel::class.java].uiState.link?.url)
            // Only the first is looked up; the rest are the same song, already in Recent.
            val looked = if (input == accepted.first()) listOf("https://open.spotify.com/track/$TRACK_ID") else emptyList()
            assertEquals(input, looked, fake.requestedUrls)
            assertTextAbsent(string(R.string.error_invalid_url))
            click(string(R.string.clear_button))
            assertResultAbsent()
        }
    }

    @Test
    fun lookalikeHostsAndMalformedIdsAreRejected() {
        launch()
        typeUrl("   ")
        composeRule.onNodeWithText(string(R.string.resolve_button)).assertDoesNotExist()
        click(string(R.string.clear_button))
        val rejected = listOf(
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
        // Converting the same text again is what Try again is for, so there's one button for it.
        assertTextAbsent(string(R.string.resolve_button))

        // Retry without editing the input, so only the resolution itself can clear the error.
        respondWithTrack("Recovered", "Artist · Song")
        click(string(R.string.retry_button))
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

        waitUntil { activity.isFinishing }
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
        // Converting the same text again would change nothing, so the button goes until it's edited.
        assertTextAbsent(string(R.string.resolve_button))
        assertEquals(1, fake.requestedUrls.size)
        typeUrl(TRACK_ID + "x")
        composeRule.onNodeWithText(string(R.string.resolve_button)).assertIsEnabled()
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
            // A page turned away says the service failed, not that the song's gone.
            403 to R.string.error_service_unavailable,
            400 to R.string.error_metadata_unavailable
        )
        for ((code, message) in cases) {
            fake.handler = { request -> FakeSpotify.html(request, FakeSpotify.trackPage("Error", "Page"), code = code) }
            // Typed afresh, as converting the text an error is shown for again isn't offered.
            typeUrl("")
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
    fun aSongsWordsLookedUpAsItShowsAreTheOnesOpened() {
        MainActivity.lookAheadLyrics = true
        val held = java.util.concurrent.CountDownLatch(1)
        var tapped = false
        fake.handler = { request ->
            if (request.url.host == "lrclib.net") {
                // Only what was asked before the words were opened has them, so words shown came from there.
                val early = !tapped
                held.await(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                FakeSpotify.html(request, if (early) """[{"trackName":"Dakare Ni Kita Onna","artistName":"Kingo Hamada","plainLyrics":"夜が灯りを投げるBedで"}]""" else "[]")
            } else FakeSpotify.html(request, """{"title":"Dakare Ni Kita Onna","author_name":"Kingo Hamada"}""")
        }
        launch()
        resolveTyped("https://music.youtube.com/watch?v=sPmul8b17AU")
        // Looked up as soon as the song shows, and still on its way when the words are opened.
        waitUntil(TIMEOUT_MS) { fake.requestedUrls.any { it.startsWith("https://lrclib.net") } }
        tapped = true
        click(string(R.string.lyrics_button))
        waitForDescription(string(R.string.lyrics_loading))
        held.countDown()
        waitForText("夜が灯りを投げるBedで")
    }

    @Test
    fun aSongOpenedFromRecentHasItsWordsLookedUpAsItShows() {
        fake.handler = { request ->
            if (request.url.host == "lrclib.net") FakeSpotify.html(request, """[{"trackName":"Dakare Ni Kita Onna","artistName":"Kingo Hamada","plainLyrics":"夜が灯りを投げるBedで"}]""")
            else FakeSpotify.html(request, """{"title":"Dakare Ni Kita Onna","author_name":"Kingo Hamada"}""")
        }
        launch()
        resolveTyped("https://music.youtube.com/watch?v=sPmul8b17AU")
        click(string(R.string.clear_button))
        controller!!.pause().stop().destroy()

        MainActivity.lookAheadLyrics = true
        launch()
        assertTrue(fake.requestedUrls.none { it.startsWith("https://lrclib.net") })
        click("Dakare Ni Kita Onna")
        waitUntil(TIMEOUT_MS) { fake.requestedUrls.any { it.startsWith("https://lrclib.net") } }
    }

    @Test
    fun aSongShowsItsWordsAndSaysSoWhenItHasNoneOrTheServiceIsBusy() {
        MainActivity.lookAheadLyrics = true
        fun lyrics(json: String) {
            fake.handler = { request ->
                if (request.url.host == "lrclib.net") FakeSpotify.html(request, json)
                else FakeSpotify.html(request, """{"title":"Dakare Ni Kita Onna","author_name":"Kingo Hamada"}""")
            }
        }
        val words = """[{"trackName":"Dakare Ni Kita Onna","artistName":"Kingo Hamada","plainLyrics":"夜が灯りを投げるBedで"}]"""
        lyrics(words)
        launch()
        // The words take a moment to come back, and the sheet shows they're on their way, with no
        // words of its own, rather than sitting blank while it waits. The delay is what keeps that
        // state on screen long enough to be seen at all, on a loaded machine as well as a quiet one.
        fake.delayMillis = 500
        resolveTyped("https://music.youtube.com/watch?v=sPmul8b17AU")
        waitForResult()

        // They're looked up as soon as the song shows, so they're on their way before the button's tapped.
        waitUntil(TIMEOUT_MS) { fake.requestedUrls.any { it.startsWith("https://lrclib.net") } }
        assertTextShown(string(R.string.lyrics_button))
        assertTextAbsent("夜が灯りを投げるBedで")
        click(string(R.string.lyrics_button))
        waitForDescription(string(R.string.lyrics_loading))
        assertEquals(0, composeRule.onAllNodesWithTextCount(string(R.string.lyrics_loading)))
        waitForText("夜が灯りを投げるBedで")
        fake.delayMillis = 0
        // The words fill the screen, under the song they're of.
        assertTextShown("Kingo Hamada")
        assertResultAbsent()

        // Closed from the screen itself, so the song is shown again.
        click(string(R.string.dismiss_button))
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodesWithText("夜が灯りを投げるBedで").fetchSemanticsNodes().isEmpty() }
        waitForResult()

        // A song with no words of its own shows a note, saying so only to screen readers. Each song
        // is another, since the words found for one are kept.
        click(string(R.string.clear_button))
        fake.handler = { request ->
            if (request.url.host == "lrclib.net") FakeSpotify.html(request, "[]")
            else FakeSpotify.html(request, """{"title":"Wordless","author_name":"Kingo Hamada"}""")
        }
        resolveTyped("https://music.youtube.com/watch?v=Wordless123")
        waitForResult()
        click(string(R.string.lyrics_button))
        waitForDescription(string(R.string.lyrics_none))
        assertEquals(0, composeRule.onAllNodesWithTextCount(string(R.string.lyrics_none)))
        click(string(R.string.dismiss_button))

        // A service that won't answer offers another try.
        click(string(R.string.clear_button))
        fake.handler = { request ->
            if (request.url.host == "lrclib.net") {
                FakeSpotify.html(request, """{"message":"The server is busy","statusCode":503}""")
            } else {
                FakeSpotify.html(request, """{"title":"Busy Song","author_name":"Kingo Hamada"}""")
            }
        }
        resolveTyped("https://music.youtube.com/watch?v=BusySong123")
        waitForResult()
        click(string(R.string.lyrics_button))
        waitForDescription(string(R.string.retry_button))
        click(string(R.string.retry_button))
        waitForDescription(string(R.string.retry_button))
        // Asked once more on the retry, since a busy service is worth asking again.
        assertTrue(fake.requestedUrls.toString(), fake.requestedUrls.count { it.startsWith("https://lrclib.net") } >= 5)
    }

    @Test
    fun playAndOpenStackWhenTheWordsAreTooBigToShareTheWidth() {
        val youTubeMusic = MusicService.YOUTUBE_MUSIC.packageName
        val link = "https://music.youtube.com/watch?v=sPmul8b17AU"
        shadowOf(app.packageManager).installPackage(installedApp(youTubeMusic, "YouTube Music"))
        fake.handler = { request -> FakeSpotify.html(request, """{"title":"Dakare Ni Kita Onna","author_name":"Kingo Hamada"}""") }
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").commit()
        val play = string(R.string.play_in_background, "YouTube Music")
        val open = string(MusicService.YOUTUBE_MUSIC.openLabelRes)
        val bounds = { description: String -> composeRule.onNode(hasContentDescription(description)).fetchSemanticsNode().boundsInRoot }
        launch()
        resolveTyped(link)
        // Side by side: one starts where the other ends.
        assertTrue(bounds(open).left >= bounds(play).right)
        controller!!.pause().stop().destroy()
        RuntimeEnvironment.setFontScale(2f)
        try {
            launch()
            resolveTyped(link)
            // Bigger, one above the other, so neither word breaks.
            assertTrue(bounds(open).top >= bounds(play).bottom)
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    @Test
    fun aSongPlaysInItsAppWithoutLeavingCrosstuneOrOpensThereWhenItCant() {
        val youTubeMusic = MusicService.YOUTUBE_MUSIC.packageName
        val link = "https://music.youtube.com/watch?v=sPmul8b17AU"
        val phone = FakePlayback().apply { access = true }
        MainActivity.playbackFactory = { phone }
        fake.handler = { request -> FakeSpotify.html(request, """{"title":"Dakare Ni Kita Onna","author_name":"Kingo Hamada"}""") }
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").commit()
        val play = string(R.string.play_in_background, "YouTube Music")
        // With no app to play it in, there's nothing to play it with.
        launch()
        resolveTyped(link)
        assertTrue(composeRule.onAllNodes(hasContentDescription(play)).fetchSemanticsNodes().isEmpty())
        controller!!.pause().stop().destroy()
        shadowOf(app.packageManager).installPackage(installedApp(youTubeMusic, "YouTube Music"))
        launch()
        resolveTyped(link)
        val playing = { phone.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = true), "Music", canSeek = true, appPackage = youTubeMusic) }
        val playerShown = { composeRule.onAllNodes(hasContentDescription(string(R.string.dismiss_button))).fetchSemanticsNodes().isNotEmpty() }
        // Tapped on the song's card, back from the player, once it's done with the last time, as a tap while it's asking does nothing.
        val tapPlay = {
            if (playerShown()) click(string(R.string.dismiss_button))
            waitUntil { composeRule.onAllNodes(hasContentDescription(play) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            click(play)
        }

        // Asked in the background, with its link there, and Crosstune stays, in its player.
        tapPlay()
        assertEquals(MusicMetadata("Dakare Ni Kita Onna", "Kingo Hamada") to youTubeMusic, phone.songsAsked.single())
        assertEquals(link, phone.linksAsked.single())
        assertTrue(playerShown())
        playing()
        assertNull(nextStartedActivity())

        // Asked, but never playing it, it's opened there, and left once it plays.
        phone.playing.value = null
        tapPlay()
        assertEquals(2, phone.songsAsked.size)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(MainViewModel.LOAD_SONG_MS))
        waitUntil { phone.opened.isNotEmpty() }
        assertEquals(youTubeMusic to link, phone.opened.single())
        assertEquals(0, phone.cameBack)
        playing()
        waitUntil { phone.cameBack == 1 }

        // An app that can't be asked is opened right away.
        phone.playing.value = null
        phone.running = emptySet()
        tapPlay()
        waitUntil { phone.opened.size == 2 }
        assertEquals(2, phone.songsAsked.size)
        playing()
        waitUntil { phone.cameBack == 2 }

        // Unable to see it play, Crosstune opens it as Open does, and stays out of the way.
        phone.access = false
        tapPlay()
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals(link, nextStartedActivity()?.dataString)
        assertEquals(2, phone.opened.size)
        assertEquals(2, phone.cameBack)
        assertFalse(playerShown())
    }

    @Test
    fun aSongAlreadyInItsAppIsNotStartedAgainOnPlay() {
        val youTubeMusic = MusicService.YOUTUBE_MUSIC.packageName
        val phone = FakePlayback().apply { access = true }
        MainActivity.playbackFactory = { phone }
        fake.handler = { request -> FakeSpotify.html(request, """{"title":"Dakare Ni Kita Onna","author_name":"Kingo Hamada"}""") }
        shadowOf(app.packageManager).installPackage(installedApp(youTubeMusic, "YouTube Music"))
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").commit()
        launch()
        resolveTyped("https://music.youtube.com/watch?v=sPmul8b17AU")
        val play = string(R.string.play_in_background, "YouTube Music")
        val tapPlay = {
            waitUntil { composeRule.onAllNodes(hasContentDescription(play) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            click(play)
        }
        val inTheApp = { playing: Boolean ->
            phone.playing.value = Following(PlaybackClock(60_000, SystemClock.elapsedRealtime(), playing = playing), "YouTube Music", canSeek = true, appPackage = youTubeMusic)
        }

        // Playing there already, it keeps on, and its player follows it.
        inTheApp(true)
        tapPlay()
        waitForDescription(string(R.string.player_playing_in, "YouTube Music"))
        assertTrue(phone.songsAsked.isEmpty())
        assertTrue(phone.plays.isEmpty())

        // Paused there, it plays again from where it was.
        click(string(R.string.dismiss_button))
        inTheApp(false)
        tapPlay()
        waitUntil { phone.plays == listOf(true) }
        assertTrue(phone.songsAsked.isEmpty())

        // Not in that app, it's asked for, as before.
        click(string(R.string.dismiss_button))
        phone.playing.value = null
        tapPlay()
        waitUntil { phone.songsAsked.size == 1 }
    }

    @Test
    fun aSongPlayingInAnotherAppIsntTakenForItPlayingWhereItWasAsked() {
        val youTubeMusic = MusicService.YOUTUBE_MUSIC.packageName
        val link = "https://music.youtube.com/watch?v=sPmul8b17AU"
        val phone = FakePlayback().apply { access = true }
        MainActivity.playbackFactory = { phone }
        fake.handler = { request -> FakeSpotify.html(request, """{"title":"Dakare Ni Kita Onna","author_name":"Kingo Hamada"}""") }
        shadowOf(app.packageManager).installPackage(installedApp(youTubeMusic, "YouTube Music"))
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").commit()
        // Spotify plays it already.
        phone.playing.value = Following(PlaybackClock(60_000, SystemClock.elapsedRealtime(), playing = true), "Spotify", canSeek = true, appPackage = MusicService.SPOTIFY.packageName)
        launch()
        resolveTyped(link)
        val play = string(R.string.play_in_background, "YouTube Music")
        waitUntil { composeRule.onAllNodes(hasContentDescription(play) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        click(play)
        waitUntil { phone.songsAsked.size == 1 }
        // YouTube Music never plays it, so it's opened there, Spotify playing it or not.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(MainViewModel.LOAD_SONG_MS))
        waitUntil { phone.opened.isNotEmpty() }
        assertEquals(youTubeMusic to link, phone.opened.single())
    }

    @Test
    fun thePlayersPlayDoesntAskAgainWhileTheSongIsStillBeingAskedFor() {
        val youTubeMusic = MusicService.YOUTUBE_MUSIC.packageName
        val phone = FakePlayback().apply { access = true }
        MainActivity.playbackFactory = { phone }
        fake.handler = { request -> FakeSpotify.html(request, """{"title":"Dakare Ni Kita Onna","author_name":"Kingo Hamada"}""") }
        shadowOf(app.packageManager).installPackage(installedApp(youTubeMusic, "YouTube Music"))
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").commit()
        launch()
        resolveTyped("https://music.youtube.com/watch?v=sPmul8b17AU")
        val play = string(R.string.play_in_background, "YouTube Music")
        waitUntil { composeRule.onAllNodes(hasContentDescription(play) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        click(play)
        waitUntil { phone.songsAsked.size == 1 }
        // The app is still loading it: asked again, it would start over.
        click(string(R.string.lyrics_play))
        assertEquals(1, phone.songsAsked.size)
    }

    @Test
    fun anAlbumNamedAsTheSongItsAppPlaysOpensAsItself() {
        val spotify = MusicService.SPOTIFY.packageName
        val phone = FakePlayback().apply { access = true }
        MainActivity.playbackFactory = { phone }
        collectionWithSongs(title = "Album", type = "album")
        shadowOf(app.packageManager).installPackage(installedApp(spotify, "Spotify"))
        prefs().edit().putString("default_target", "SPOTIFY").commit()
        // Spotify plays a song of the album's name.
        phone.playing.value = Following(PlaybackClock(60_000, SystemClock.elapsedRealtime(), playing = true), "Spotify", canSeek = true, appPackage = spotify)
        launch()
        resolveTyped("https://open.spotify.com/album/4yP0hdKOZPNshxUOjY0cZj")
        val button = hasContentDescription(string(R.string.open_in_spotify)) and isEnabled()
        waitUntil { composeRule.onAllNodes(button).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(button).performClick()
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals(Intent.ACTION_VIEW, nextStartedActivity()!!.action)
    }

    @Test
    fun aSongAlreadyInItsAppIsBroughtForwardNotStartedAgainOnOpen() {
        val youTubeMusic = MusicService.YOUTUBE_MUSIC.packageName
        val phone = FakePlayback().apply { access = true }
        MainActivity.playbackFactory = { phone }
        fake.handler = { request -> FakeSpotify.html(request, """{"title":"Dakare Ni Kita Onna","author_name":"Kingo Hamada"}""") }
        shadowOf(app.packageManager).installPackage(installedApp(youTubeMusic, "YouTube Music"))
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").commit()
        launch()
        resolveTyped("https://music.youtube.com/watch?v=sPmul8b17AU")
        val open = string(R.string.open_in_youtube_music)
        // The destination chip says the same in words; the button is the one described.
        val button = hasContentDescription(open) and isEnabled()
        val tapOpen = {
            waitUntil { composeRule.onAllNodes(button).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNode(button).performClick()
        }

        // Not in the app, it's opened on the song.
        tapOpen()
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals(Intent.ACTION_VIEW, nextStartedActivity()!!.action)

        // Playing there, the app is only brought forward, which keeps the song going.
        phone.playing.value = Following(PlaybackClock(60_000, SystemClock.elapsedRealtime(), playing = true), "YouTube Music", canSeek = true, appPackage = youTubeMusic)
        tapOpen()
        waitForText(string(R.string.error_no_app_to_open))

        installActivity(ComponentName(youTubeMusic, "Main"), IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        tapOpen()
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        val shown = nextStartedActivity()!!
        assertEquals(Intent.ACTION_MAIN, shown.action)
        assertNull(shown.data)
        assertEquals(youTubeMusic, shown.component?.packageName ?: shown.`package`)
    }

    @Test
    fun thePlayerKeepsTheSongsLinkToolsInAMenuItsAppIconOpensItsAppAndSwipesMoveBetweenLayers() {
        val youTubeMusic = MusicService.YOUTUBE_MUSIC.packageName
        val phone = FakePlayback().apply { access = true }
        MainActivity.playbackFactory = { phone }
        fake.handler = { request -> FakeSpotify.html(request, """{"title":"Dakare Ni Kita Onna","author_name":"Kingo Hamada"}""") }
        shadowOf(app.packageManager).installPackage(installedApp(youTubeMusic, "YouTube Music"))
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").commit()
        launch()
        resolveTyped("https://music.youtube.com/watch?v=sPmul8b17AU")
        val play = string(R.string.play_in_background, "YouTube Music")
        waitUntil { composeRule.onAllNodes(hasContentDescription(play) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        click(play)
        val playingIn = string(R.string.player_playing_in, "YouTube Music")
        waitForDescription(playingIn)
        phone.playing.value = Following(PlaybackClock(60_000, SystemClock.elapsedRealtime(), playing = true), "YouTube Music", canSeek = true, appPackage = youTubeMusic)

        // The link tools are a menu away, not another screen: copy, share, and the apps to open it in.
        val item = { label: String -> hasText(label) and isEnabled() }
        val tapItem = { label: String ->
            click(string(R.string.more_button))
            waitUntil { composeRule.onAllNodes(item(label)).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNode(item(label)).performClick()
            composeRule.waitForIdle()
        }
        tapItem(string(R.string.copy_link_button))
        assertEquals("https://music.youtube.com/watch?v=sPmul8b17AU", app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        tapItem(string(R.string.share_link_button))
        assertEquals(Intent.ACTION_CHOOSER, nextStartedActivity()!!.action)

        // Its app's icon brings that app forward as it is; with nothing to open it with, nothing happens.
        click(playingIn)
        assertNull(nextStartedActivity())
        installActivity(ComponentName(youTubeMusic, "Main"), IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        click(playingIn)
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        val shown = nextStartedActivity()!!
        assertEquals(Intent.ACTION_MAIN, shown.action)
        assertNull(shown.data)

        // Up opens its words, down goes back to the player and then to the song.
        composeRule.onRoot().performTouchInput { swipeUp() }
        waitUntil { composeRule.onAllNodes(hasTestTag(LYRICS_SONG_TAG)).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onRoot().performTouchInput { swipeDown() }
        waitUntil { composeRule.onAllNodes(hasTestTag(LYRICS_SONG_TAG)).fetchSemanticsNodes().isEmpty() }
        waitForDescription(playingIn)
        // Down from the middle of the screen isn't from the top, so the player stays.
        composeRule.onRoot().performTouchInput { swipeDown(startY = centerY, endY = bottom) }
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodes(hasContentDescription(playingIn)).fetchSemanticsNodes().isNotEmpty())
        composeRule.onRoot().performTouchInput { swipeDown() }
        waitForDescription(play)
        assertTrue(composeRule.onAllNodes(hasContentDescription(playingIn)).fetchSemanticsNodes().isEmpty())

        // Open with closes the player on the song, with its apps to choose from.
        waitUntil { composeRule.onAllNodes(hasContentDescription(play) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        click(play)
        waitForDescription(playingIn)
        tapItem(string(R.string.picker_title))
        waitForText(string(R.string.picker_title))
        assertTrue(composeRule.onAllNodes(hasContentDescription(playingIn)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun thePlayersTimesAreNotCutOffAtTheLargestFont() {
        RuntimeEnvironment.setFontScale(2f)
        val youTubeMusic = MusicService.YOUTUBE_MUSIC.packageName
        val phone = FakePlayback().apply { access = true }
        MainActivity.playbackFactory = { phone }
        fake.handler = { request -> FakeSpotify.html(request, """{"title":"Dakare Ni Kita Onna","author_name":"Kingo Hamada"}""") }
        shadowOf(app.packageManager).installPackage(installedApp(youTubeMusic, "YouTube Music"))
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").commit()
        launch()
        resolveTyped("https://music.youtube.com/watch?v=sPmul8b17AU")
        val play = string(R.string.play_in_background, "YouTube Music")
        waitUntil { composeRule.onAllNodes(hasContentDescription(play) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        click(play)
        waitForDescription(string(R.string.player_playing_in, "YouTube Music"))
        phone.playing.value = Following(PlaybackClock(30_000, SystemClock.elapsedRealtime(), playing = true), "YouTube Music", canSeek = true, appPackage = youTubeMusic, durationMs = 200_000)
        waitForText("3:20")

        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNode(hasText("3:20")).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
        assertFalse(layouts.single().didOverflowHeight)
    }

    @Test
    fun aPlayedSongShowsInCrosstunesPlayerWhichPlaysPausesSkipsAndSeeksIt() {
        val youTubeMusic = MusicService.YOUTUBE_MUSIC.packageName
        val phone = FakePlayback().apply { access = true }
        MainActivity.playbackFactory = { phone }
        fake.handler = { request -> FakeSpotify.html(request, """{"title":"Dakare Ni Kita Onna","author_name":"Kingo Hamada"}""") }
        shadowOf(app.packageManager).installPackage(installedApp(youTubeMusic, "YouTube Music"))
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").commit()
        launch()
        resolveTyped("https://music.youtube.com/watch?v=sPmul8b17AU")
        val play = string(R.string.play_in_background, "YouTube Music")
        waitUntil { composeRule.onAllNodes(hasContentDescription(play) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        click(play)

        // It opens on the song at once, waiting for the app to play it.
        waitForText(string(R.string.player_waiting, "YouTube Music"))
        waitForDescription(string(R.string.player_playing_in, "YouTube Music"))
        composeRule.onNode(hasText("Dakare Ni Kita Onna")).assertExists()
        composeRule.onNode(hasContentDescription(string(R.string.lyrics_next_song))).assertIsNotEnabled()
        val now = SystemClock.elapsedRealtime()
        phone.playing.value = Following(
            PlaybackClock(30_000, now, playing = true), "YouTube Music", canSeek = true, appPackage = youTubeMusic,
            canSkipNext = true, durationMs = 200_000
        )
        waitForText("3:20")
        assertTrue(composeRule.onAllNodes(hasText(string(R.string.player_waiting, "YouTube Music"))).fetchSemanticsNodes().isEmpty())

        // Its buttons are the app's: pause, the next song but not the one before, and anywhere in it.
        click(string(R.string.lyrics_pause))
        assertEquals(listOf(false), phone.plays)
        composeRule.onNode(hasContentDescription(string(R.string.lyrics_previous_song))).assertIsNotEnabled()
        click(string(R.string.lyrics_next_song))
        assertEquals(listOf(true), phone.skips)
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performSemanticsAction(SemanticsActions.SetProgress) { it(100_000f) }
        composeRule.waitForIdle()
        assertEquals(listOf(100_000L), phone.seeks)
        // Held where it was dragged a moment, then back to where the app says, when it never says it moved.
        composeRule.onNode(hasText("1:40")).assertExists()
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodes(hasText("1:40")).fetchSemanticsNodes().isEmpty())
        // Said to have moved, it's where the app says, here paused, and then it plays again.
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performSemanticsAction(SemanticsActions.SetProgress) { it(150_000f) }
        composeRule.waitForIdle()
        composeRule.onNode(hasText("2:30")).assertExists()
        phone.playing.value = Following(PlaybackClock(100_000, now, playing = false), "YouTube Music", canSeek = true, appPackage = youTubeMusic, durationMs = 200_000)
        // Paused, the app names the song its own way, which is still the song.
        phone.onThePhone = Heard.Song(MusicMetadata("抱かれに来た女 - Dakare Ni Kita Onna", "Kingo Hamada"), null)
        waitForDescription(string(R.string.lyrics_play))
        composeRule.onNode(hasText("Dakare Ni Kita Onna")).assertExists()
        composeRule.onNode(hasText("1:40")).assertExists()
        assertTrue(composeRule.onAllNodes(hasText("2:30")).fetchSemanticsNodes().isEmpty())
        click(string(R.string.lyrics_play))
        assertEquals(listOf(false, true), phone.plays)
        assertEquals(listOf(100_000L, 150_000L), phone.seeks)

        // Its words open over it, and close back to it.
        click(string(R.string.lyrics_button))
        waitUntil { composeRule.onAllNodes(hasTestTag(LYRICS_SONG_TAG)).fetchSemanticsNodes().isNotEmpty() }
        controller!!.get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        waitForDescription(string(R.string.player_playing_in, "YouTube Music"))

        // The next song the app plays takes over, followed in the same app; with no artist, it has no words to look up.
        phone.playing.value = null
        phone.onThePhone = Heard.Song(MusicMetadata("Other Song", ""), null)
        waitForText("Other Song")
        waitForDescription(string(R.string.player_playing_in, "YouTube Music"))
        assertTrue(composeRule.onAllNodes(hasContentDescription(string(R.string.lyrics_button))).fetchSemanticsNodes().isEmpty())

        // Closed, it's the song's card again.
        controller!!.get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        waitForDescription(play)
        assertTrue(composeRule.onAllNodes(hasContentDescription(string(R.string.player_playing_in, "YouTube Music"))).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun withoutAccessThePlayerSaysHowToAllowItAndPlayAsksTheAppAgain() {
        val youTubeMusic = MusicService.YOUTUBE_MUSIC.packageName
        val phone = FakePlayback()
        MainActivity.playbackFactory = { phone }
        fake.handler = { request -> FakeSpotify.html(request, """{"title":"Dakare Ni Kita Onna","author_name":"Kingo Hamada"}""") }
        shadowOf(app.packageManager).installPackage(installedApp(youTubeMusic, "YouTube Music"))
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").commit()
        launch()
        resolveTyped("https://music.youtube.com/watch?v=sPmul8b17AU")
        val play = string(R.string.play_in_background, "YouTube Music")
        waitUntil { composeRule.onAllNodes(hasContentDescription(play) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        click(play)
        waitForText(string(R.string.player_follow_allow))
        assertEquals(1, phone.songsAsked.size)
        assertNull(nextStartedActivity())
        // Unable to see whether it plays, Play asks the app for it again.
        click(string(R.string.lyrics_play))
        waitUntil { phone.songsAsked.size == 2 }
        // Allowed meanwhile, it follows the app from then on.
        phone.access = true
        phone.playing.value = Following(PlaybackClock(0, SystemClock.elapsedRealtime(), playing = true), "YouTube Music", canSeek = false, appPackage = youTubeMusic)
        controller!!.pause().resume()
        waitForDescription(string(R.string.lyrics_pause))
        assertTrue(composeRule.onAllNodes(hasText(string(R.string.player_follow_allow))).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun youTubeMusicWithOnlyASearchForTheSongIsOpenedOnItRatherThanAsked() {
        val phone = FakePlayback().apply { access = true }
        MainActivity.playbackFactory = { phone }
        shadowOf(app.packageManager).installPackage(installedApp(MusicService.YOUTUBE_MUSIC.packageName, "YouTube Music"))
        shadowOf(app.packageManager).installPackage(installedApp(MusicService.DEEZER.packageName, "Deezer"))
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").commit()
        respondWithTrack("Cut To The Feeling", "Carly Rae Jepsen · Song · 2017")
        launch()
        resolveTyped()
        // YouTube Music plays only its own link, so a search there is opened instead.
        click(string(R.string.play_in_background, "YouTube Music"))
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals(MusicService.YOUTUBE_MUSIC.packageName, nextStartedActivity()?.`package`)
        assertTrue(phone.songsAsked.isEmpty() && phone.opened.isEmpty())
        // Another app finds it asked by name.
        chooseDefaultHere("Deezer")
        click(string(R.string.play_in_background, "Deezer"))
        assertEquals(MusicService.DEEZER.packageName, phone.songsAsked.single().second)
    }

    /** A song whose words come timed, at 1, 20 and 40 seconds in, from a music app the test plays, the [microphone] allowed. */
    private fun timedSong(restricted: Boolean = false, microphone: Boolean = true): FakePlayback {
        val playback = FakePlayback().apply { this.restricted = restricted }
        MainActivity.playbackFactory = { playback }
        if (microphone) shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val words = """[{"trackName":"Dakare Ni Kita Onna","artistName":"Kingo Hamada","plainLyrics":"One\nTwo\nThree",""" +
            """"syncedLyrics":"[00:01.00] One\n[00:20.00] Two\n[00:40.00] Three"}]"""
        fake.handler = { request ->
            if (request.url.host == "lrclib.net") FakeSpotify.html(request, words)
            else FakeSpotify.html(request, """{"title":"Dakare Ni Kita Onna","author_name":"Kingo Hamada"}""")
        }
        launch()
        resolveTyped("https://music.youtube.com/watch?v=sPmul8b17AU")
        click(string(R.string.lyrics_button))
        return playback
    }

    private fun assertLit(line: String) =
        composeRule.onNode(hasText(line) and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertExists()

    @Test
    fun wordsOfASongNotPlayingYetStartItInItsAppThenFollowIt() {
        val youTubeMusic = MusicService.YOUTUBE_MUSIC.packageName
        shadowOf(app.packageManager).installPackage(installedApp(youTubeMusic, "YouTube Music"))
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").commit()
        val playback = FakePlayback().apply { access = true }
        MainActivity.playbackFactory = { playback }
        shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val words = """[{"trackName":"Dakare Ni Kita Onna","artistName":"Kingo Hamada","plainLyrics":"One\nTwo\nThree",""" +
            """"syncedLyrics":"[00:01.00] One\n[00:20.00] Two\n[00:40.00] Three"}]"""
        fake.handler = { request ->
            if (request.url.host == "lrclib.net") FakeSpotify.html(request, words)
            else FakeSpotify.html(request, """{"title":"Dakare Ni Kita Onna","author_name":"Kingo Hamada"}""")
        }
        launch()
        resolveTyped("https://music.youtube.com/watch?v=sPmul8b17AU")
        click(string(R.string.lyrics_button))
        waitForText("Two")

        // Its app is in the bar, to play it there, and the microphone stays, for a song playing nearby.
        val play = string(R.string.play_in_background, "YouTube Music")
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodesWithContentDescription(play).fetchSemanticsNodes().size == 1 }
        composeRule.onNodeWithContentDescription(string(R.string.lyrics_listen_along)).assertExists()
        composeRule.onNodeWithContentDescription(play).performClick()
        waitUntil { playback.songsAsked.isNotEmpty() }
        assertEquals(MusicMetadata("Dakare Ni Kita Onna", "Kingo Hamada") to youTubeMusic, playback.songsAsked.single())
        assertNull(nextStartedActivity())

        // The words stay, and follow it once it plays there.
        playback.playing.value = Following(PlaybackClock(25_000, SystemClock.elapsedRealtime()), "YouTube Music", canSeek = true, appPackage = youTubeMusic)
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodesWithContentDescription(string(R.string.lyrics_following_app, "YouTube Music")).fetchSemanticsNodes().isNotEmpty() }
        assertLit("Two")
        composeRule.onNodeWithContentDescription(play).assertDoesNotExist()
    }

    @Test
    fun timedWordsFollowTheMusicAppPlayingTheSongOnceAllowed() {
        val playback = timedSong()
        // Not allowed yet, the words say how, and Allow shows the steps to Android's page for Crosstune.
        waitForText(string(R.string.lyrics_follow_allow))
        click(string(R.string.follow_turn_on))
        click(string(R.string.fix_open_settings))
        val opened = nextStartedActivity()!!
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, opened.action)
        assertEquals(
            ComponentName(app, NowPlayingListener::class.java).flattenToString(),
            opened.getStringExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME)
        )

        // Allowed and back, with no app playing it yet: the steps and the offer go, and the microphone's still there.
        playback.access = true
        controller!!.pause().resume()
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodesWithText(string(R.string.follow_help_title)).fetchSemanticsNodes().isEmpty() }
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodesWithText(string(R.string.lyrics_follow_allow)).fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithContentDescription(string(R.string.lyrics_listen_along)).assertExists()

        // Its app plays it, paused 25 seconds in: the second line is lit, and the app's in the header's
        // corner instead of the microphone, not over the words.
        playback.playing.value = Following(PlaybackClock(25_000, SystemClock.elapsedRealtime(), playing = false), "Spotify", canSeek = true)
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodesWithContentDescription(string(R.string.lyrics_following_app, "Spotify")).fetchSemanticsNodes().isNotEmpty() }
        assertLit("Two")
        composeRule.onNodeWithContentDescription(string(R.string.lyrics_listen_along)).assertDoesNotExist()
        // Its icon plays the song, paused, and shows it would pause it once it plays.
        composeRule.onNodeWithContentDescription(string(R.string.lyrics_play), useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription(string(R.string.lyrics_following_app, "Spotify")).performClick()
        assertEquals(listOf(true), playback.plays)
        playback.playing.value = Following(PlaybackClock(25_000, SystemClock.elapsedRealtime()), "Spotify", canSeek = true)
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodesWithContentDescription(string(R.string.lyrics_pause), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithContentDescription(string(R.string.lyrics_following_app, "Spotify")).performClick()
        assertEquals(listOf(true, false), playback.plays)
        playback.playing.value = Following(PlaybackClock(25_000, SystemClock.elapsedRealtime(), playing = false), "Spotify", canSeek = true)
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodesWithContentDescription(string(R.string.lyrics_play), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // A line tapped moves the app there.
        composeRule.onNodeWithText("Three").performClick()
        assertEquals(listOf(40_000L), playback.seeks)

        // Playing on, past the last line; an app that can't jump isn't asked to.
        playback.playing.value = Following(PlaybackClock(45_000, SystemClock.elapsedRealtime()), "Spotify", canSeek = false, appPackage = app.packageName)
        waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodes(hasText("Three") and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("One").performClick()
        assertEquals(listOf(40_000L), playback.seeks)

        // Back goes back to the song.
        controller!!.get().onBackPressedDispatcher.onBackPressed()
        waitForResult()
        // Nothing left to follow, a resume changes nothing.
        controller!!.pause().resume()
        assertResultShown()
    }

    @Test
    fun wordsPickedStayTheirsWhenTheAppMovesOnToAnotherSong() {
        shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val playback = timedSong()
        playback.access = true
        controller!!.pause().resume()
        playback.playing.value = Following(PlaybackClock(25_000, SystemClock.elapsedRealtime()), "Spotify", canSeek = true)
        waitUntil(TIMEOUT_MS) { lit("Two") }
        // The app goes on to another song: the words stop where it left them, and stay this song's.
        playback.onThePhone = Heard.Song(MusicMetadata("Other Song", "Someone Else"), null, 5_000, SystemClock.elapsedRealtime())
        playback.playing.value = null
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodesWithContentDescription(string(R.string.lyrics_following_app, "Spotify")).fetchSemanticsNodes().isEmpty() }
        assertLit("Two")
        // Closed and opened again, and even listened along with, they're still this song's, not what plays now.
        controller!!.get().onBackPressedDispatcher.onBackPressed()
        waitForResult()
        composeRule.onNode(hasText(string(R.string.lyrics_button)) and hasAnyAncestor(hasTestTag(RESULT_TAG))).performClick()
        waitForText("Two")
        composeRule.onNodeWithContentDescription(string(R.string.lyrics_listen_along)).performClick()
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodesWithContentDescription(string(R.string.lyrics_stop_listening)).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(500)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Dakare Ni Kita Onna").assertExists()
    }

    private fun lit(line: String) =
        composeRule.onAllNodes(hasText(line) and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aLineTimedWordByWordLightsEachWordAsItsSung() {
        val playback = FakePlayback().apply { access = true }
        MainActivity.playbackFactory = { playback }
        val words = """[{"trackName":"Dakare Ni Kita Onna","artistName":"Kingo Hamada","plainLyrics":"One\nTwo words",""" +
            """"syncedLyrics":"[00:01.00] <00:01.00>One\n[00:20.00] <00:20.00>Two <00:22.00>words"}]"""
        fake.handler = { request ->
            if (request.url.host == "lrclib.net") FakeSpotify.html(request, words)
            else FakeSpotify.html(request, """{"title":"Dakare Ni Kita Onna","author_name":"Kingo Hamada"}""")
        }
        launch()
        resolveTyped("https://music.youtube.com/watch?v=sPmul8b17AU")
        click(string(R.string.lyrics_button))

        // Paused between its words: the first is sung, the second still dim.
        playback.playing.value = Following(PlaybackClock(21_000, SystemClock.elapsedRealtime(), playing = false), "Spotify", canSeek = true)
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodes(hasText("Two words") and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(true, false), sungAlphas("Two words").map { it == 1f })

        // Playing on past it: both are sung.
        playback.playing.value = Following(PlaybackClock(23_000, SystemClock.elapsedRealtime()), "Spotify", canSeek = true)
        waitUntil(TIMEOUT_MS) { sungAlphas("Two words") == listOf(1f, 1f) }
        // A line not being sung is shown whole, as before.
        composeRule.onNodeWithText("One").assertExists()
    }

    /** How bright each word of a line being sung is. */
    private fun sungAlphas(line: String): List<Float> =
        composeRule.onNode(hasText(line), useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text]
            .single().spanStyles.map { it.item.color.alpha }

    /** The line open to study, written once, its words to tap: not the line among the words behind it. */
    private fun sheetLine(text: String) = composeRule.onNode(hasText(text) and hasTestTag(STUDY_LINE_TAG))

    @Test
    fun aGapInTheWordsMovesTheAppThereButHasNothingToStudy() {
        val playback = FakePlayback().apply { access = true }
        MainActivity.playbackFactory = { playback }
        val words = """[{"trackName":"Dakare Ni Kita Onna","artistName":"Kingo Hamada","plainLyrics":"One\nTwo",""" +
            """"syncedLyrics":"[00:01.00] One\n[00:05.00] \n[00:20.00] Two"}]"""
        fake.handler = { request ->
            if (request.url.host == "lrclib.net") FakeSpotify.html(request, words)
            else FakeSpotify.html(request, """{"title":"Dakare Ni Kita Onna","author_name":"Kingo Hamada"}""")
        }
        launch()
        resolveTyped("https://music.youtube.com/watch?v=sPmul8b17AU")
        click(string(R.string.lyrics_button))
        waitForText("♪")
        // Nothing to follow yet: a gap does nothing, held or tapped.
        composeRule.onNodeWithText("♪").performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertTextAbsent(string(R.string.lyrics_save_line))
        composeRule.onNodeWithText("♪").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        // Followed in an app that can be moved, tapping it goes there; held, it still opens nothing.
        playback.playing.value = Following(PlaybackClock(2_000, SystemClock.elapsedRealtime(), playing = false), "Spotify", canSeek = true)
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodesWithContentDescription(string(R.string.lyrics_following_app, "Spotify")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("♪").performClick()
        assertEquals(listOf(5_000L), playback.seeks)
        composeRule.onNodeWithText("♪").performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertTextAbsent(string(R.string.lyrics_save_line))
    }

    @Test
    fun aLineOpensToStudyRepeatsInTheMusicAppAndIsKept() {
        val playback = timedSong()
        playback.access = true
        controller!!.pause().resume()
        playback.playing.value = Following(PlaybackClock(25_000, SystemClock.elapsedRealtime(), playing = false), "Spotify", canSeek = true)
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodesWithContentDescription(string(R.string.lyrics_following_app, "Spotify")).fetchSemanticsNodes().isNotEmpty() }
        // Held, a line opens to study; tapped, it still moves the app there.
        composeRule.onNodeWithText("Two").performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertTextShown(string(R.string.lyrics_repeat_line))
        assertTrue(playback.seeks.isEmpty())

        // Repeated, the app goes to its start, and back again each time it reaches the next line.
        click(string(R.string.lyrics_repeat_line))
        assertEquals(listOf(20_000L), playback.seeks)
        // Just past the next line's start, the last word still sounds: it plays on a moment.
        playback.playing.value = Following(PlaybackClock(40_700, SystemClock.elapsedRealtime(), playing = false), "Spotify", canSeek = true)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_200))
        composeRule.waitForIdle()
        assertEquals(listOf(20_000L), playback.seeks)
        playback.playing.value = Following(PlaybackClock(41_000, SystemClock.elapsedRealtime()), "Spotify", canSeek = true)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        composeRule.waitForIdle()
        assertEquals(listOf(20_000L, 20_000L), playback.seeks)
        // Not sent back again while the app hasn't said where it went.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        assertEquals(2, playback.seeks.size)
        // Somewhere in the line, it plays on.
        playback.playing.value = Following(PlaybackClock(22_000, SystemClock.elapsedRealtime()), "Spotify", canSeek = true)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_200))
        assertEquals(2, playback.seeks.size)
        click(string(R.string.lyrics_stop_repeating))
        assertTextShown(string(R.string.lyrics_repeat_line))
        playback.playing.value = Following(PlaybackClock(41_000, SystemClock.elapsedRealtime()), "Spotify", canSeek = true)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_200))
        assertEquals(2, playback.seeks.size)

        // A word tapped is looked up; with nothing to say what it means, that says so, and it's asked again.
        sheetLine("Two").performFirstLinkClick()
        waitForText(string(R.string.lyrics_word_failed))
        fake.handler = { request -> FakeSpotify.html(request, """{"responseData":{"translatedText":"Dos"},"responseStatus":200}""") }
        sheetLine("Two").performFirstLinkClick()
        waitForText("Dos")

        // Kept, with the song it's from.
        click(string(R.string.lyrics_save_line))
        assertTextShown(string(R.string.lyrics_line_saved))
        assertTrue(prefs().getString("saved_lines", null)!!.contains("\"Two\""))
        click(string(R.string.lyrics_line_saved))
        assertTextShown(string(R.string.lyrics_save_line))
        click(string(R.string.lyrics_save_line))
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).performSemanticsAction(SemanticsActions.Dismiss)
        composeRule.waitForIdle()
        assertTextAbsent(string(R.string.lyrics_save_line))

        // Then found checked among the song's lines, and let go there.
        click(string(R.string.lyrics_study))
        click(string(R.string.choose_lines))
        assertTextShown("Dakare Ni Kita Onna")
        assertTextShown("Kingo Hamada")
        composeRule.onNode(isToggleable() and hasText("Two")).assertIsOn().performClick()
        click(string(R.string.save_button))
        assertFalse(prefs().getString("saved_lines", null)!!.contains("\"Two\""))
        waitForText("Three")
        assertTextAbsent(string(R.string.save_button))
    }

    @Test
    fun lyricsAskForTheMicrophoneTheFirstTimeTheyOpenOnly() {
        timedSong(microphone = false)
        waitForText(string(R.string.lyrics_follow_allow))
        val activity = controller!!.get()
        val asked = shadowOf(activity).lastRequestedPermission
        assertEquals(listOf(android.Manifest.permission.RECORD_AUDIO), asked.requestedPermissions.toList())
        // Turned down, though Android would ask again, nothing is wrong yet: it was only asked.
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO, true)
        activity.onRequestPermissionsResult(asked.requestCode, asked.requestedPermissions, intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED))
        composeRule.waitForIdle()
        assertTextAbsent(string(R.string.error_microphone))

        // Opened again, it isn't asked again.
        activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        click(string(R.string.lyrics_button))
        waitForText(string(R.string.lyrics_follow_allow))
        assertTrue(asked === shadowOf(activity).lastRequestedPermission)
    }

    @Test
    fun whereAndroidHoldsTheAccessBackTheStepsShowWithThePageForEach() {
        // Installed from a file, which Android knows from the start.
        val playback = timedSong(restricted = true)
        waitForText(string(R.string.lyrics_follow_allow))
        click(string(R.string.follow_turn_on))
        // Installed from a file, Android turns the first try down, then offers restricted settings in App info.
        waitForText(string(R.string.follow_help_title))
        assertNull(nextStartedActivity())
        click(string(R.string.follow_help_app_info))
        val appInfo = nextStartedActivity()!!
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appInfo.action)
        assertEquals("package:${app.packageName}", appInfo.dataString)
        click(string(R.string.fix_open_settings))
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, nextStartedActivity()!!.action)

        // Back without it, the steps stay; with it, they go, and the words follow.
        controller!!.pause().resume()
        assertTextShown(string(R.string.follow_help_title))
        playback.access = true
        controller!!.pause().resume()
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodesWithText(string(R.string.follow_help_title)).fetchSemanticsNodes().isEmpty() }
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodesWithText(string(R.string.lyrics_follow_allow)).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun settingsSaysWhetherLyricsFollowMusicAppsAndSwitchingOpensAndroidsPage() {
        val playback = FakePlayback()
        MainActivity.playbackFactory = { playback }
        launch()
        click(string(R.string.settings_button))
        val follow = composeRule.onNodeWithText(string(R.string.setting_lyrics_follow)).performScrollTo()
        follow.assertIsOff()
        // Switched on: the steps to Android's page, which stay till it's allowed there.
        follow.performClick()
        waitForText(string(R.string.follow_help_title))
        click(string(R.string.fix_open_settings))
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, nextStartedActivity()!!.action)
        controller!!.pause().resume()
        assertTextShown(string(R.string.follow_help_title))

        // Allowed there, it's on, and the steps go.
        playback.access = true
        controller!!.pause().resume()
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodesWithText(string(R.string.follow_help_title)).fetchSemanticsNodes().isEmpty() }
        follow.assertIsOn()
        // Switched off: Android's page again, where it's turned off.
        follow.performClick()
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, nextStartedActivity()!!.action)
    }

    @Test
    fun theAppsColorAndPureBlackArePickedInSettingsAndKept() {
        launch()
        click(string(R.string.settings_button))
        composeRule.onNodeWithText(string(R.string.setting_pure_black)).performScrollTo()
        val ocean = composeRule.onNodeWithContentDescription(string(R.string.palette_ocean)).performScrollTo()
        ocean.assertIsNotSelected()
        // Where Android shares the wallpaper's colors, they're the app's until another is picked.
        composeRule.onNodeWithContentDescription(string(R.string.palette_wallpaper)).assertIsSelected()
        ocean.performClick()
        composeRule.waitForIdle()
        ocean.assertIsSelected()
        assertEquals("OCEAN", prefs().getString("palette", null))

        val black = composeRule.onNodeWithText(string(R.string.setting_pure_black)).performScrollTo()
        black.assertIsOff()
        black.performClick()
        black.assertIsOn()
        assertTrue(prefs().getBoolean("pure_black", false))

        // Kept for the next launch.
        controller!!.pause().stop().destroy()
        launch()
        click(string(R.string.settings_button))
        composeRule.onNodeWithContentDescription(string(R.string.palette_ocean)).performScrollTo().assertIsSelected()
        composeRule.onNodeWithText(string(R.string.setting_pure_black)).performScrollTo().assertIsOn()
    }

    @Test
    @Config(qualifiers = "w360dp-h2000dp")
    fun everyColorFitsInItsRowOnANarrowPhone() {
        launch()
        click(string(R.string.settings_button))
        composeRule.onNodeWithText(string(R.string.setting_pure_black)).performScrollTo()
        // The picked one's name ends where the row does.
        val rowEnd = composeRule.onNodeWithText(string(R.string.palette_wallpaper)).fetchSemanticsNode().boundsInRoot.right
        val swatches = listOf(R.string.palette_wallpaper, R.string.palette_violet, R.string.palette_ocean, R.string.palette_forest, R.string.palette_sunset, R.string.palette_rose)
            .map { composeRule.onNodeWithContentDescription(string(it)).fetchSemanticsNode().boundsInRoot }
        // All whole, on one line, each still a full touch.
        assertTrue(swatches.all { it.right <= rowEnd + 1f })
        assertEquals(1, swatches.map { it.top }.distinct().size)
        val touch = with(composeRule.density) { 48.dp.toPx() }
        assertTrue(swatches.all { it.width >= touch - 1f })
    }

    @Test
    fun signedOutTranslationsComeFromGoogleTranslateWithNothingToPick() {
        launch()
        click(string(R.string.settings_button))
        composeRule.onNodeWithText(string(R.string.setting_translate_into)).performScrollTo()
        assertTextAbsent(string(R.string.setting_translation_server))
        // Each language by its flag.
        click(string(R.string.setting_translate_into))
        assertTextShown("🇦🇷")
        assertTextShown("🇯🇵")
    }

    @Test
    fun signingInWithChatGptUsesThePlanSaysSoOnceAndSignsOut() {
        val openAi = FakeOpenAi(System::currentTimeMillis)
        fake.handler = { request -> openAi.answer(request) ?: FakeSpotify.html(request, "") }
        installActivity(ComponentName("com.example.browser", "com.example.browser.Browser"), browserFilter())
        File(app.noBackupFilesDir, "chatgpt.json").delete()
        launch()
        click(string(R.string.settings_button))

        // Android 13 asks about notifications first, without which some phones freeze Crosstune
        // while the browser's open; turned down, it signs in all the same.
        composeRule.onNodeWithText(string(R.string.chatgpt_continue)).performScrollTo().performClick()
        val asked = shadowOf(controller!!.get()).lastRequestedPermission
        assertEquals(android.Manifest.permission.POST_NOTIFICATIONS, asked.requestedPermissions.single())
        assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", nextStartedActivity()!!.action)
        controller!!.get().onRequestPermissionsResult(asked.requestCode, asked.requestedPermissions, intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED))
        composeRule.waitForIdle()
        assertEquals(ChatGptSignInService::class.java.name, shadowOf(app).nextStartedService.component!!.className)
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals(Intent.ACTION_VIEW, nextStartedActivity()!!.action)
        click(string(R.string.cancel_button))
        // Allowed, it isn't asked again.
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        /** Starts signing in, and returns once the browser's open for it. */
        fun startSigningIn() {
            composeRule.onNodeWithText(string(R.string.chatgpt_continue)).performScrollTo().performClick()
            // Kept running while the browser's open, so Android won't freeze it.
            assertEquals(ChatGptSignInService::class.java.name, shadowOf(app).nextStartedService.component!!.className)
            waitUntil { shadowOf(app).peekNextStartedActivity() != null }
            val browser = nextStartedActivity()!!
            assertEquals(Intent.ACTION_VIEW, browser.action)
            openAi.authorize = browser.dataString!!.toHttpUrl()
            assertEquals("https://auth.openai.com/api/accounts/authorize", openAi.authorize.toString().substringBefore("?"))
            assertTextShown(string(R.string.cancel_button))
        }

        // Left in the browser, it can be given up on.
        startSigningIn()
        click(string(R.string.cancel_button))
        assertTextShown(string(R.string.chatgpt_continue))
        // No longer needed once it's over, however it ended.
        val stopped = mutableListOf<String?>()
        waitUntil {
            generateSequence { shadowOf(app).nextStoppedService }.forEach { stopped += it.component?.className }
            ChatGptSignInService::class.java.name in stopped
        }

        // Back with someone else's answer, it didn't work.
        startSigningIn()
        thread { openAi.comeBack("code=c&state=forged&client_id=oaiapp_1") }
        waitUntil { composeRule.onAllNodesWithText(string(R.string.chatgpt_failed)).fetchSemanticsNodes().isNotEmpty() }

        startSigningIn()
        thread { openAi.comeBack("error=access_denied&state=${openAi.authorize.queryParameter("state")}") }
        waitUntil { composeRule.onAllNodesWithText(string(R.string.chatgpt_declined)).fetchSemanticsNodes().isNotEmpty() }

        startSigningIn()
        val page = openAi.comeBack()
        // The page the browser lands on sends it back to Crosstune, which shows Settings as it was.
        assertTrue(page.contains("package=${app.packageName}"))
        val onNewIntent = MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
        onNewIntent.isAccessible = true
        onNewIntent.invoke(controller!!.get(), Intent(Intent.ACTION_VIEW, Uri.parse("crosstune://chatgpt")))
        waitUntil { composeRule.onAllNodesWithText(FakeOpenAi.EMAIL).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(fake.requestedUrls.none { "crosstune" in it })

        // Said once, the first time.
        assertTextShown(string(R.string.chatgpt_welcome_title))
        click(string(R.string.got_it_button))
        assertTextAbsent(string(R.string.chatgpt_welcome_title))
        assertTrue(prefs().getBoolean("chatgpt_welcomed", false))

        click(string(R.string.chatgpt_manage_usage))
        assertEquals("https://chatgpt.com/settings/usage", nextStartedActivity()!!.dataString)

        // Signed in still when Crosstune's opened again.
        controller!!.pause().stop().destroy()
        launch()
        click(string(R.string.settings_button))
        composeRule.onNodeWithText(FakeOpenAi.EMAIL).performScrollTo()
        click(string(R.string.chatgpt_sign_out))
        assertTextShown(string(R.string.chatgpt_continue))
        waitUntil { ChatGpt.REVOKE_URL in fake.requestedUrls }

        // Signed in again, afresh, it isn't said again.
        startSigningIn()
        thread { openAi.comeBack("code=c&state=${openAi.authorize.queryParameter("state")}&client_id=oaiapp_1") }
        waitUntil { composeRule.onAllNodesWithText(FakeOpenAi.EMAIL).fetchSemanticsNodes().isNotEmpty() }
        assertTextAbsent(string(R.string.chatgpt_welcome_title))
    }

    @Test
    @Config(sdk = [30])
    fun beforeAndroid12TheWallpapersColorsArentOfferedAndVioletIsTheApps() {
        // Even picked before, e.g. on a phone since moved back to an Android that doesn't share them.
        prefs().edit().putString("palette", "WALLPAPER").commit()
        launch()
        click(string(R.string.settings_button))
        composeRule.onNodeWithContentDescription(string(R.string.palette_violet)).performScrollTo().assertIsSelected()
        composeRule.onNodeWithContentDescription(string(R.string.palette_wallpaper)).assertDoesNotExist()
    }

    @Test
    fun violetPickedWhenItWasCalledCrosstuneIsStillPicked() {
        prefs().edit().putString("palette", "CROSSTUNE").commit()
        launch()
        click(string(R.string.settings_button))
        composeRule.onNodeWithContentDescription(string(R.string.palette_violet)).performScrollTo().assertIsSelected()
    }

    @Test
    fun theLookCanBeLightOrDarkWhateverThePhoneIs() {
        launch()
        val uiModes = app.getSystemService(UiModeManager::class.java)
        fun pick(label: String) {
            composeRule.onNodeWithText(string(R.string.setting_theme)).performScrollTo().performClick()
            composeRule.waitForIdle()
            composeRule.onAllNodesWithText(label).onLast().performClick()
            composeRule.waitForIdle()
        }
        click(string(R.string.settings_button))
        pick(string(R.string.theme_dark))
        // Android is told too, so its launch screen matches next time.
        assertEquals(UiModeManager.MODE_NIGHT_YES, shadowOf(uiModes).applicationNightMode)
        assertEquals("DARK", prefs().getString("theme", null))
        pick(string(R.string.theme_light))
        assertEquals(UiModeManager.MODE_NIGHT_NO, shadowOf(uiModes).applicationNightMode)
        pick(string(R.string.language_system_default))
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, shadowOf(uiModes).applicationNightMode)
        assertEquals("SYSTEM", prefs().getString("theme", null))

        // Kept for the next launch.
        pick(string(R.string.theme_dark))
        controller!!.pause().stop().destroy()
        launch()
        click(string(R.string.settings_button))
        composeRule.onNodeWithText(string(R.string.theme_dark)).assertExists()
        // The menu ticks the one picked.
        composeRule.onNodeWithText(string(R.string.setting_theme)).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasText(string(R.string.theme_dark)) and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertExists()
        composeRule.onNode(hasText(string(R.string.theme_light)) and SemanticsMatcher.expectValue(SemanticsProperties.Selected, false)).assertExists()
    }

    @Test
    @Config(sdk = [29])
    fun beforeAndroid11AllowOpensTheListOfAppsThatMaySeeWhatPlays() {
        timedSong()
        waitForText(string(R.string.lyrics_follow_allow))
        click(string(R.string.follow_turn_on))
        click(string(R.string.fix_open_settings))
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, nextStartedActivity()!!.action)
    }

    @Test
    fun wordsWithNoTimingsReadAsAPage() {
        MainActivity.playbackFactory = { FakePlayback().apply { access = true } }
        fake.handler = { request ->
            if (request.url.host == "lrclib.net") FakeSpotify.html(request, """[{"trackName":"Dakare Ni Kita Onna","artistName":"Kingo Hamada","plainLyrics":"Only plain"}]""")
            else FakeSpotify.html(request, """{"title":"Dakare Ni Kita Onna","author_name":"Kingo Hamada"}""")
        }
        launch()
        resolveTyped("https://music.youtube.com/watch?v=sPmul8b17AU")
        click(string(R.string.lyrics_button))
        waitForText("Only plain")
        // Nothing to follow, so nothing says how to.
        assertTextAbsent(string(R.string.lyrics_follow_allow))
    }

    @Test
    fun theLyricsButtonIsOnlyForASongWhoseArtistIsKnown() {
        collectionWithSongs(title = "Album", type = "album")
        launch()
        resolveTyped("https://open.spotify.com/album/4yP0hdKOZPNshxUOjY0cZj")
        waitForResult()
        // An album has no words of its own, and without an artist there's nothing to look up.
        assertTextAbsent(string(R.string.lyrics_button))
    }

@Test
    fun aTypedSongNameIsOfferedToPickFromAndOpensWhereTheResultGoes() {
        // A name rather than a link, so the songs it turns up are offered to pick from.
        val cover = "https://cdn-images.dzcdn.net/images/cover/abc/500x500-80-0-0.jpg"
        fake.handler = { request ->
            when {
                request.url.host == "api.deezer.com" && request.url.encodedPath == "/search/track" ->
                    FakeSpotify.html(request, """{"data":[{"id":608098752,"title":"Machi No Dorufin","link":"https://www.deezer.com/track/608098752","artist":{"name":"Kingo Hamada"},"album":{"cover_big":"$cover"}}]}""")
                request.url.host == "open.deezer.com" || request.url.host == "www.deezer.com" ||
                request.url.encodedPath.startsWith("/track/") ->
                    FakeSpotify.html(request, """{"id":608098752,"title":"Machi No Dorufin","link":"https://www.deezer.com/track/608098752","artist":{"name":"Kingo Hamada"},"album":{"cover_big":"$cover"}}""")
                request.url.host == "music.youtube.com" -> FakeSpotify.html(request, """{"contents":[]}""")
                else -> FakeSpotify.html(request, """{"data":[]}""")
            }
        }
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").commit()
        launch()
        typeUrl("Kingo Hamada")
        // A name, not a link, is searched for.
        click(string(R.string.search_button))

        // The sheet offers the song with its artist.
        waitForText(string(R.string.song_search_title))
        composeRule.onNodeWithText("Machi No Dorufin").performScrollTo().performClick()
        waitForResult()

        // The chosen song is shown with its own artist, as if its link had been pasted.
        assertTextShown("Kingo Hamada")
        assertTextShown(string(R.string.open_in_youtube_music))
    }

    @Test
    fun signedInASongDescribedIsFoundByChatGptAheadOfTheOthers() {
        val openAi = FakeOpenAi(System::currentTimeMillis)
        val file = File(app.noBackupFilesDir, "chatgpt.json").apply { delete() }
        fake.handler = { request -> openAi.answer(request) ?: FakeSpotify.html(request, "") }
        runBlocking {
            ChatGpt(fake.client(), file, app.packageName).signIn(open = { url ->
                openAi.authorize = url.toHttpUrl()
                thread { openAi.comeBack() }
            })
        }
        // Named the other way round, as ChatGPT sometimes does; the catalogue has it right.
        openAi.reply = { 200 to FakeOpenAi.stream("[{\"title\": \"Mariya Takeuchi\", \"artist\": \"Plastic Love\"}, {\"title\": \"Made Up\", \"artist\": \"Nobody\"}]") }
        fake.handler = { request ->
            val q = request.url.queryParameter("q").orEmpty()
            openAi.answer(request) ?: when {
                request.url.host == "api.deezer.com" && "Plastic Love" in q ->
                    FakeSpotify.html(request, """{"data":[{"id":1,"title":"Plastic Love","link":"https://www.deezer.com/track/1","artist":{"name":"Mariya Takeuchi"}}]}""")
                request.url.host == "api.deezer.com" && "Made Up" in q ->
                    FakeSpotify.html(request, """{"data":[{"id":3,"title":"Something Else","link":"https://www.deezer.com/track/3","artist":{"name":"Other"}}]}""")
                request.url.host == "api.deezer.com" ->
                    FakeSpotify.html(request, """{"data":[{"id":2,"title":"City Pop Night","link":"https://www.deezer.com/track/2","artist":{"name":"Someone"}}]}""")
                else -> FakeSpotify.html(request, """{"results":[]}""")
            }
        }
        launch()
        typeUrl("japanese 80s plastic song")
        click(string(R.string.search_button))
        waitForText("City Pop Night")
        // ChatGPT's real pick comes first, marked; one the catalogue doesn't have is left out.
        waitUntil { composeRule.onAllNodesWithText("Plastic Love").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(composeRule.onAllNodesWithContentDescription(string(R.string.song_search_chatgpt), useUnmergedTree = true).fetchSemanticsNodes().size == 1)
        assertTextAbsent("Something Else")
        // One song is no playlist.
        assertTextAbsent(string(R.string.song_search_playlist))
        val plastic = composeRule.onNodeWithText("Plastic Love").fetchSemanticsNode().boundsInRoot.top
        val city = composeRule.onNodeWithText("City Pop Night").fetchSemanticsNode().boundsInRoot.top
        assertTrue(plastic < city)
        // A name of two words isn't asked about.
        val asked = openAi.asked.size
        composeRule.onNode(hasText(string(R.string.dismiss_button))).performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).performTextClearance()
        typeUrl("Plastic Love")
        click(string(R.string.search_button))
        waitForText(string(R.string.song_search_title))
        assertEquals(asked, openAi.asked.size)
    }

    @Test
    fun aFoundSongWithNoLinkOfItsOwnIsOpenedByItsNameAndArtist() {
        // A catalogue row with no link can't be opened as itself, so it's matched by name instead,
        // which is what the app already does for a song shared without a link.
        fake.handler = { request ->
            when {
                request.url.host == "api.deezer.com" && request.url.encodedPath == "/search/track" ->
                    FakeSpotify.html(request, """{"data":[{"id":7,"title":"Machi No Dorufin","artist":{"name":"Kingo Hamada"}}]}""")
                request.url.host == "music.youtube.com" -> FakeSpotify.html(request, """{"contents":[]}""")
                else -> FakeSpotify.html(request, """{"data":[]}""")
            }
        }
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").commit()
        launch()
        typeUrl("Kingo Hamada")
        click(string(R.string.search_button))
        waitForText(string(R.string.song_search_title))
        composeRule.onNodeWithText("Machi No Dorufin").performScrollTo().performClick()
        waitForResult()

        assertTextShown("Machi No Dorufin")
        assertTextShown("Kingo Hamada")
    }

@Test
    fun aNameThatFindsNothingSaysSoRatherThanShowingAnEmptyList() {
        fake.handler = { request -> FakeSpotify.html(request, """{"data":[]}""") }
        launch()
        typeUrl("zzzznotarealsong")
        click(string(R.string.search_button))

        waitForText(string(R.string.song_search_none, "zzzznotarealsong"))
        assertResultAbsent()
        // The sheet says it, so no card about a link that went missing is shown on top of it.
        assertTextAbsent(string(R.string.error_not_found))
    }

    @Test
    fun aLinkCrosstuneCantOpenIsStillReportedAsALinkRatherThanASongName() {
        // Something shaped like a link is one, even when Crosstune can't open it, so it isn't
        // looked up as a song that happens to be named after it.
        launch()
        typeUrl("https://notdeezer.com/track/1234")
        click(string(R.string.resolve_button))
        assertTextShown(string(R.string.error_invalid_url))
        assertTextAbsent(string(R.string.song_search_title))
        assertTrue(fake.requestedUrls.toString(), fake.requestedUrls.none { it.contains("/search/track") })
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
        assertTextShown("Album")
        composeRule.onNodeWithContentDescription("from Spotify").assertExists()
        click(string(R.string.open_in_youtube_music))
        assertEquals("https://music.youtube.com/search?q=After%20Hours%20The%20Weeknd", nextStartedActivity()!!.dataString)

        click(string(R.string.clear_button))
        resolveTyped("spotify:artist:$TRACK_ID")
        click(string(R.string.open_in_youtube_music))
        assertEquals("https://music.youtube.com/search?q=The%20Weeknd", nextStartedActivity()!!.dataString)

        click(string(R.string.clear_button))
        resolveTyped("https://open.spotify.com/playlist/$TRACK_ID")
        assertTextShown("Playlist")
        composeRule.onNodeWithContentDescription("from Spotify").assertExists()
        assertEquals(
            listOf(
                "https://open.spotify.com/album/$TRACK_ID",
                // Albums and playlists get their songs from the embed page.
                "https://open.spotify.com/embed/album/$TRACK_ID",
                "https://open.spotify.com/artist/$TRACK_ID",
                "https://open.spotify.com/playlist/$TRACK_ID",
                // Its songs come from the embed page.
                "https://open.spotify.com/embed/playlist/$TRACK_ID"
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
            click(string(MusicService.entries.first { it.labelRes == label }.openLabelRes))
            val opened = nextStartedActivity()!!
            assertEquals(destination.first, opened.`package`)
            assertEquals(destination.second, opened.dataString)
        }
    }

    @Test
    fun openingInCrosstuneFreesTheUsualAppsLinksAndOffersToMakeAnotherAppTheDefault() {
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").commit()
        linksShowHere()
        LinkInterception(app).setEnabled(MusicService.DEEZER, true)
        respondWithTrack("Last One", "Artist · Song")
        launch()

        // No app gets links by itself, so YouTube Music's links can be opened in Crosstune too.
        openSources()
        sourceSwitch(string(R.string.target_youtube_music)).assertIsEnabled()
        click(string(R.string.back_button))
        click(string(R.string.back_button))

        // Another app picked for a result can be made the one songs play in, which leaves its links opening here.
        resolveTyped()
        assertResultShown()
        composeRule.onNodeWithTag(DEFAULT_MENU_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(string(R.string.target_deezer)).onLast().performClick()
        composeRule.waitForIdle()
        assertEquals("YOUTUBE_MUSIC", prefs().getString("default_target", null))
        click(string(R.string.make_default_named, string(R.string.target_deezer)))
        assertEquals("DEEZER", prefs().getString("default_target", null))
        assertTrue(LinkInterception(app).isEnabled(MusicService.DEEZER))
        assertTrue(prefs().getBoolean("links_in_crosstune", false))
    }

    @Test
    fun thePickerMatchesOnlyTheChosenDestination() {
        prefs().edit().putBoolean("play_right_away", true).putString("default_target", "DEEZER").commit()
        linksAskWhere()
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
        linksAskWhere()
        respondWithTrack("Stay", "Artist · Song")
        val activity = launch(trackLink())
        waitForText(string(R.string.picker_title))

        click(string(R.string.cancel_button))

        assertTextAbsent(string(R.string.picker_title))
        assertResultShown()
        assertFalse(activity.isFinishing)
    }

    @Test
    fun dismissingThePickerLeavesTheSongWhereItsFromWithoutOpeningIt() {
        prefs().edit().putBoolean("play_right_away", true).commit()
        linksAskWhere()
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
        // Shown in Spotify, where it's from, it's its own link, with nothing more to ask.
        click(string(R.string.copy_link_button))
        assertEquals("https://open.spotify.com/track/$TRACK_ID",
            app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        assertEquals(1, fake.requestedUrls.size)
        assertNull(nextStartedActivity())
        assertFalse(activity.isFinishing)
    }

    @Test
    fun straightToTheAppWithoutPlayingRightAwayASearchOpensThereAcrossLaunchesAndShownInCrosstuneTheSongIsLookedUp() {
        prefs().edit().putBoolean("play_right_away", true).putString("default_target", "DEEZER").commit()
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
        click(string(R.string.copy_link_button))
        assertEquals("https://www.deezer.com/track/1",
            app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())

        inSettings {
            // Only going straight to the app does turning it off open a search instead.
            assertTextShown(string(R.string.setting_play_right_away_search))
            click(string(R.string.setting_show_in_crosstune_first))
            assertTextAbsent(string(R.string.setting_play_right_away_search))
            click(string(R.string.setting_show_in_crosstune_first))
            click(string(R.string.setting_play_right_away))
        }
        controller!!.pause().stop().destroy()
        fake.requestedUrls.clear()
        val restored = launch()
        assertFalse(ViewModelProvider(restored)[MainViewModel::class.java].uiState.exactMatch)
        resolveTyped()
        click(string(R.string.copy_link_button))
        assertEquals("https://www.deezer.com/search/Default%20Artist",
            app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        // The song comes from Recent, and a search link needs no lookup.
        assertEquals(emptyList<String>(), fake.requestedUrls)

        // Shown in Crosstune first, the song is looked up again, and it's the song itself.
        inSettings { click(string(R.string.setting_show_in_crosstune_first)) }
        waitForDestinationReady()
        click(string(R.string.copy_link_button))
        assertEquals("https://www.deezer.com/track/1",
            app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    fun trackingIsRemovedFromLinksUntilTurnedOff() {
        prefs().edit().putBoolean("play_right_away", true).putString("default_target", "APPLE_MUSIC").commit()
        fake.handler = { request ->
            if (request.url.host == "itunes.apple.com") {
                FakeSpotify.html(request,
                    """{"results":[{"trackName":"Clean","artistName":"Artist","trackViewUrl":"https://music.apple.com/us/album/clean/1?i=2&uo=4"}]}""")
            } else {
                FakeSpotify.html(request, FakeSpotify.trackPage("Clean", "Artist · Song"))
            }
        }
        val activity = launch()
        assertTrue(ViewModelProvider(activity)[MainViewModel::class.java].uiState.cleanLinks)
        resolveTyped()
        waitForDestinationReady()
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        click(string(R.string.copy_link_button))
        assertEquals("https://music.apple.com/us/album/clean/1?i=2", clipboard.primaryClip!!.getItemAt(0).text.toString())

        inAdvancedSettings { composeRule.onNodeWithText(string(R.string.setting_clean_links)).performScrollTo().performClick() }
        assertFalse(prefs().getBoolean("clean_links", true))
        click(string(R.string.copy_link_button))
        assertEquals("https://music.apple.com/us/album/clean/1?i=2&uo=4", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    fun languagePickerSetsTheAppLanguageOrFollowsThePhone() {
        launch()
        click(string(R.string.settings_button))
        composeRule.onNodeWithText(string(R.string.setting_language)).performScrollTo().performClick()
        composeRule.onNodeWithText("Español").performClick()
        composeRule.waitForIdle()
        assertEquals("es", AppLanguage.current(app))

        composeRule.onNodeWithText(string(R.string.setting_language)).performScrollTo().performClick()
        composeRule.onAllNodesWithText(string(R.string.language_system_default)).onLast().performClick()
        composeRule.waitForIdle()
        assertNull(AppLanguage.current(app))
    }

    @Test
    fun lyricsCanBeTranslatedIntoALanguageOtherThanTheApps() {
        launch()
        click(string(R.string.settings_button))
        composeRule.onNodeWithText(string(R.string.setting_translate_into)).performScrollTo().performClick()
        composeRule.onNodeWithText("日本語").performClick()
        composeRule.waitForIdle()
        assertEquals("ja", prefs().getString("translate_into", null))
        composeRule.onNodeWithText("日本語").assertExists()

        // Or into one as it's spoken somewhere; the menu marks the one it's set to.
        composeRule.onNodeWithText(string(R.string.setting_translate_into)).performScrollTo().performClick()
        composeRule.onNode(hasText("日本語") and isSelected()).assertExists()
        composeRule.onNodeWithText("Español (Argentina)").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals("es-AR", prefs().getString("translate_into", null))

        // Back to the app's own language.
        composeRule.onNodeWithText(string(R.string.setting_translate_into)).performScrollTo().performClick()
        composeRule.onAllNodesWithText(string(R.string.translate_into_app_language)).onLast().performClick()
        composeRule.waitForIdle()
        assertNull(prefs().getString("translate_into", null))
    }

    @Test
    fun songsInLanguagesPickedAreLeftUntranslated() {
        launch()
        click(string(R.string.settings_button))
        // None at first, and it says so.
        assertTextShown(string(R.string.untranslated_none))
        composeRule.onNodeWithText(string(R.string.setting_untranslated)).performScrollTo().performClick()
        // As many as wanted, with the list staying open.
        composeRule.onNodeWithText("English").performClick()
        composeRule.onNodeWithText("Français").performClick()
        composeRule.waitForIdle()
        assertEquals(setOf("en", "fr"), prefs().getStringSet("untranslated_languages", null))
        // Picked again, one is translated again.
        composeRule.onNodeWithText("English").performClick()
        composeRule.waitForIdle()
        assertEquals(setOf("fr"), prefs().getStringSet("untranslated_languages", null))
        // Beside the setting, the ones picked, as well as in the list.
        composeRule.onAllNodesWithText("Français").assertCountEquals(2)
        composeRule.onAllNodesWithText("English").assertCountEquals(1)
    }

    @Test
    fun playingRightAwayOpensTheSongItselfAndFallsBackToSearch() {
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
        inSettings { click(string(R.string.setting_play_right_away)) }
        assertTrue(prefs().getBoolean("play_right_away", false))
        resolveTyped()

        chooseDefault(string(R.string.target_apple_music))
        waitForDestinationReady()
        click(string(R.string.copy_link_button))
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        assertEquals("https://music.apple.com/us/song/1", clipboard.primaryClip!!.getItemAt(0).text.toString())
        click(string(R.string.share_link_button))
        val shared = nextStartedActivity()!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("https://music.apple.com/us/song/1", shared.getStringExtra(Intent.EXTRA_TEXT))
        val requestsBeforeOpen = fake.requestedUrls.size
        click(string(R.string.open_in_apple_music))
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://music.apple.com/us/song/1", nextStartedActivity()!!.dataString)
        assertEquals(requestsBeforeOpen, fake.requestedUrls.size)

        // Not on Deezer, it says so, and plays where it's from unless a search is asked for anyway.
        chooseDefault(string(R.string.target_deezer))
        waitForDestinationReady()
        assertTextShown(string(R.string.not_found_on, string(R.string.target_deezer)))
        click(string(R.string.copy_link_button))
        assertEquals("https://open.spotify.com/track/$TRACK_ID", clipboard.primaryClip!!.getItemAt(0).text.toString())
        click(string(R.string.open_in_spotify))
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://open.spotify.com/track/$TRACK_ID", nextStartedActivity()!!.dataString)
        click(string(R.string.search_anyway, string(R.string.target_deezer)))
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://www.deezer.com/search/Exact%20Artist", nextStartedActivity()!!.dataString)

        // Searching anyway picked in settings, it's a search like before.
        inAdvancedSettings {
            composeRule.onNodeWithText(string(R.string.setting_not_found)).performScrollTo().performClick()
            composeRule.onNodeWithText(string(R.string.not_found_search)).performClick()
        }
        assertEquals("SEARCH", prefs().getString("not_found", null))
        assertTextAbsent(string(R.string.not_found_on, string(R.string.target_deezer)))
        click(string(R.string.search_in_destination, string(R.string.target_deezer)))
        waitUntil { shadowOf(app).peekNextStartedActivity() != null }
        assertEquals("https://www.deezer.com/search/Exact%20Artist", nextStartedActivity()!!.dataString)

        val requestsBeforeReturning = fake.requestedUrls.size
        chooseDefault(string(R.string.target_apple_music))
        click(string(R.string.copy_link_button))
        assertEquals("https://music.apple.com/us/song/1", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(requestsBeforeReturning, fake.requestedUrls.size)

        inSettings { click(string(R.string.setting_play_right_away)) }
        assertFalse(prefs().getBoolean("play_right_away", true))
        click(string(R.string.copy_link_button))
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
        prefs().edit().putBoolean("play_right_away", true).putString("default_target", "DEEZER").putString("not_found", "SEARCH").commit()
        val activity = launch()
        resolveTyped()

        waitForText(string(R.string.matching_text))
        assertTextAbsent(string(R.string.resolve_button))
        composeRule.onNodeWithContentDescription(string(R.string.open_in_deezer)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(string(R.string.copy_link_button)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(string(R.string.share_link_button)).assertIsNotEnabled()
        assertNull(ViewModelProvider(activity)[MainViewModel::class.java].destinationUrl())
        assertNull(nextStartedActivity())

        release.countDown()
        waitUntil { composeRule.onAllNodesWithTextCount(string(R.string.matching_text)) == 0 }
        click(string(R.string.copy_link_button))
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
        prefs().edit().putBoolean("play_right_away", true).commit()
        fake.handler = { request ->
            if (request.url.host == "music.youtube.com") {
                val title = if (FakeSpotify.bodyOf(request).contains("Second")) "Second" else "First"
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
        click(string(R.string.copy_link_button))
        assertEquals("https://music.youtube.com/watch?v=first000000", clipboard.primaryClip!!.getItemAt(0).text.toString())
        click(string(R.string.share_link_button))
        assertEquals(
            "https://music.youtube.com/watch?v=first000000",
            nextStartedActivity()!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!.getStringExtra(Intent.EXTRA_TEXT)
        )
        click(string(R.string.open_in_youtube_music))
        assertEquals("https://music.youtube.com/watch?v=first000000", nextStartedActivity()!!.dataString)
        assertEquals(2, fake.requestedUrls.size)

        resolveTyped(OTHER_TRACK_ID)
        waitForDestinationReady()
        click(string(R.string.copy_link_button))
        assertEquals("https://music.youtube.com/watch?v=second00000", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(4, fake.requestedUrls.size)

        click("First")
        waitForDestinationReady()
        click(string(R.string.copy_link_button))
        assertEquals("https://music.youtube.com/watch?v=first000000", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(4, fake.requestedUrls.size)
    }

    @Test
    fun cancellingAnIncomingLinkStaysInCrosstuneAndOpensNothing() {
        val release = CountDownLatch(1)
        prefs().edit().putBoolean("play_right_away", true).putString("default_target", "DEEZER").commit()
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
        // Only the song and where it's going show while it's on its way.
        waitForText(string(R.string.handoff_opening, "Deezer"))
        assertTextShown("Switch")
        assertTextAbsent(string(R.string.resolve_button))

        // Cancelling stays in Crosstune with the result, and the late match opens nothing.
        click(string(R.string.cancel_button))
        assertResultShown()
        release.countDown()
        Thread.sleep(200L)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(nextStartedActivity())
        assertFalse(activity.isFinishing)
    }

    @Test
    fun aNewIncomingLinkCancelsTheOldMatchWithoutReusingItsUrl() {
        val release = CountDownLatch(1)
        prefs().edit().putBoolean("play_right_away", true).putString("default_target", "DEEZER").commit()
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
        waitForText(string(R.string.handoff_opening, "Deezer"))
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
        // Another song: the first is in Recent now, so it wouldn't be looked up.
        typeUrl(OTHER_TRACK_ID)
        click(string(R.string.resolve_button))
        waitForText(string(R.string.error_not_found))
        assertTextAbsent(string(R.string.retry_button))
    }

    @Test
    fun recentPicksUpAShareSavedByAnotherInstanceWhenTheScreenReturns() {
        respondWithTrack("Already There", "Artist · Song")
        launch()
        resolveTyped()
        assertTextShown("Already There")

        HistoryStore(prefs()).add(
            HistoryEntry(
                MusicLink(
                    MusicService.SPOTIFY,
                    ItemType.TRACK,
                    OTHER_TRACK_ID,
                    "https://open.spotify.com/track/$OTHER_TRACK_ID"
                ),
                MusicMetadata("Shared Song", "Artist")
            )
        )
        assertTextAbsent("Shared Song")

        controller!!.pause().resume()
        composeRule.waitForIdle()

        assertTextShown("Shared Song")
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
        // Where it's from is the badge on its cover; a song isn't labelled as one.
        composeRule.onNodeWithContentDescription("from YouTube Music").assertExists()
        assertTextAbsent("Song")
        // Opening the source in itself needs no separate "open original" button.
        assertTextAbsent(string(R.string.open_in_youtube))

        click(string(R.string.open_in_youtube_music))
        val opened = nextStartedActivity()!!
        assertEquals("com.google.android.apps.youtube.music", opened.`package`)
        assertEquals("https://music.youtube.com/watch?v=4NRXx6U8ABQ", opened.dataString)

        click(string(R.string.share_link_button))
        @Suppress("DEPRECATION")
        val shared = nextStartedActivity()!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("https://music.youtube.com/watch?v=4NRXx6U8ABQ", shared.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun resultCanStillBeOpenedInTheAppItCameFrom() {
        respondWithTrack("Original", "Artist · Song")
        val activity = launch()
        resolveTyped()

        openInTheAppItCameFrom()

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

        openInTheAppItCameFrom()

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

        openInTheAppItCameFrom()

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
        FakeDomainVerification.install(app) { emptyMap() }
        launch()
        openSources()
        assertFalse(marked(string(R.string.setup_not_allowed)))

        sourceSwitch(string(R.string.target_youtube)).performClick()
        composeRule.waitForIdle()

        assertTrue(LinkInterception(app).isEnabled(MusicService.YOUTUBE))
        assertTrue(marked(string(R.string.setup_not_allowed)))
        sourceSwitch(string(R.string.target_youtube)).performClick()
        composeRule.waitForIdle()
        assertFalse(LinkInterception(app).isEnabled(MusicService.YOUTUBE))
        assertFalse(marked(string(R.string.setup_not_allowed)))
    }

    @Test
    fun perSourceRuleSendsThatServicesLinksElsewhere() {
        fake.handler = { request ->
            FakeSpotify.html(request, """{"title":"The Weeknd - Blinding Lights (Official Video)","author_name":"TheWeekndVEVO"}""")
        }
        launch()
        openSources()
        // Where a service's links go only shows once Crosstune opens them.
        assertTextAbsent(string(R.string.rule_opens_in, string(R.string.rule_default, "YouTube Music")))
        toggleRow(string(R.string.target_youtube))
        // The list only says where links go when it isn't the default; the menu is on YouTube's own page.
        assertTextAbsent(string(R.string.rule_opens_in, string(R.string.rule_default, "YouTube Music")))
        openSource(string(R.string.target_youtube))
        composeRule.onNodeWithText(string(R.string.rule_opens_in, string(R.string.rule_default, "YouTube Music")))
            .performClick()
        composeRule.waitForIdle()
        // The menu marks where they go now.
        composeRule.onNode(hasText(string(R.string.rule_default, "YouTube Music")) and isSelected()).assertExists()
        composeRule.onAllNodes(hasText(string(R.string.target_apple_music))).onLast().performClick()
        composeRule.waitForIdle()
        assertTextShown(string(R.string.rule_opens_in, "Apple Music"))
        composeRule.onNodeWithText(string(R.string.rule_opens_in, "Apple Music")).performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasText(string(R.string.target_apple_music)) and isSelected()).assertExists()
        composeRule.onAllNodes(hasText(string(R.string.target_apple_music))).onLast().performClick()
        composeRule.waitForIdle()
        click(string(R.string.back_button))
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
        openSources()
        openSource(string(R.string.service_spotify))

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
        // Few add them, so they're in Advanced.
        openAdvanced()

        click(string(R.string.add_custom_destination_button))
        composeRule.onNode(hasSetTextAction() and hasText(string(R.string.custom_name_label))).performTextReplacement("Word Finder")
        composeRule.onNode(hasSetTextAction() and hasText(string(R.string.custom_template_label)))
            .performTextReplacement("https://words.example/search")
        click(string(R.string.add_button))
        assertTextShown(string(R.string.custom_template_invalid))

        composeRule.onNode(hasSetTextAction() and hasText(string(R.string.custom_template_label)))
            .performTextReplacement("https://words.example/search?q={query}")
        assertTextAbsent(string(R.string.custom_template_invalid))
        click(string(R.string.add_button))
        assertTextShown("https://words.example/search?q={query}")
        click(string(R.string.back_button))
        click(string(R.string.back_button))

        chooseDefault("Word Finder")
        resolveTyped()
        click(string(R.string.open_in_custom, "Word Finder"))
        val opened = nextStartedActivity()!!
        assertEquals("https://words.example/search?q=Custom%20Song%20Artist", opened.dataString)
        assertEquals(
            "com.example.browser",
            app.packageManager.resolveActivity(opened, 0)!!.activityInfo.packageName
        )

        click(string(R.string.settings_button))
        openAdvanced()
        click(string(R.string.remove_button))
        assertTextAbsent("https://words.example/search?q={query}")
        click(string(R.string.back_button))
        click(string(R.string.back_button))
        assertTextAbsent("Word Finder")
        assertTextShown(string(R.string.open_in_youtube_music))
    }

    @Test
    fun anInstalledFrontendAppIsOfferedAndOpensTheYouTubeLinkInIt() {
        val newPipe = "org.schabi.newpipe"
        shadowOf(app.packageManager).installPackage(installedApp(newPipe, "NewPipe"))
        shadowOf(app.packageManager).setApplicationIcon(newPipe, android.graphics.drawable.ColorDrawable(android.graphics.Color.RED))
        respondWithTrack("Frontend Song", "Artist · Song")
        launch()
        // Apps that aren't installed aren't offered.
        click(string(R.string.settings_button))
        composeRule.onNodeWithTag(DEFAULT_MENU_TAG).performClick()
        composeRule.waitForIdle()
        assertTextAbsent("LibreTube")
        composeRule.onNodeWithTag("destination-icon:frontend:NEWPIPE", useUnmergedTree = true).assertExists()
        composeRule.onAllNodesWithText("NewPipe").onLast().performClick()
        composeRule.waitForIdle()
        click(string(R.string.back_button))

        resolveTyped()
        click(string(R.string.search_in_destination, "NewPipe"))
        val opened = nextStartedActivity()!!
        assertEquals(newPipe, opened.`package`)
        assertEquals("https://www.youtube.com/results?search_query=Frontend%20Song%20Artist", opened.dataString)
    }

    @Test
    fun webFrontendsOpenOnASiteTheUserCanChange() {
        shadowOf(app).checkActivities(true)
        installActivity(ComponentName("com.example.browser", "com.example.browser.Browser"), browserFilter())
        respondWithTrack("Web Song", "Artist · Song")
        launch()
        openSources()
        // The site is on Invidious's own page, not repeated in the list.
        assertTextAbsent(string(R.string.frontend_site_on, "yewtu.be"))
        openSource("Invidious")
        click(string(R.string.frontend_site_on, "yewtu.be"))
        composeRule.onNode(hasSetTextAction()).performTextReplacement("not a site")
        click(string(R.string.save_button))
        assertTextShown(string(R.string.frontend_address_invalid))
        composeRule.onNode(hasSetTextAction()).performTextReplacement("inv.example.org")
        click(string(R.string.save_button))
        assertTextShown(string(R.string.frontend_site_on, "inv.example.org"))

        // Cancelling keeps the site.
        click(string(R.string.frontend_site_on, "inv.example.org"))
        click(string(R.string.cancel_button))
        assertTextShown(string(R.string.frontend_site_on, "inv.example.org"))
        click(string(R.string.back_button))
        click(string(R.string.back_button))
        click(string(R.string.back_button))

        chooseDefault("Invidious")
        resolveTyped()
        click(string(R.string.search_in_destination, "Invidious"))
        val opened = nextStartedActivity()!!
        assertEquals("https://inv.example.org/search?q=Web%20Song%20Artist", opened.dataString)
        assertNull(opened.`package`)
    }

    @Test
    fun invidiousAndPipedAreSourcesOfTheirOwnFromTheStartAndInSettings() {
        var states = emptyMap<String, Int>()
        FakeDomainVerification.install(app) { states }
        freshInstall()
        DestinationStore(prefs()).setDefault(Destination.Service(MusicService.YOUTUBE))
        // A saved default looks like an older install, so mark it a fresh one explicitly.
        prefs().edit().putBoolean("setup_complete", false).commit()
        launch()

        // Links going straight to YouTube, its own stay there, but its frontends' come here, sources of their own.
        assertFalse(LinkInterception(app).isEnabled(MusicService.YOUTUBE))
        assertTrue(LinkInterception(app).isEnabled(Frontend.INVIDIOUS))
        assertTrue(LinkInterception(app).isEnabled(Frontend.PIPED))
        assertTextShown(string(R.string.setup_check_allow))
        // Settings marks the sources, and each one Android doesn't let Crosstune open yet.
        click(string(R.string.settings_button))
        assertMarked(string(R.string.setup_not_allowed))
        click(string(R.string.settings_links_title))
        assertMarked(string(R.string.setup_not_allowed))
        states = (LinkInterception.HOSTS.values.flatten() + Frontend.SOURCES.flatMap { it.sites }).associateWith { DomainVerificationUserState.DOMAIN_STATE_SELECTED }
        controller!!.pause().resume()
        composeRule.waitForIdle()
        assertTextAbsent(string(R.string.setup_not_allowed))
        toggleRow("Piped")
        assertFalse(LinkInterception(app).isEnabled(Frontend.PIPED))
    }

    @Test
    fun invidiousLinksCanOpenSomewhereElseThanYouTubes() {
        LinkInterception(app).setEnabled(Frontend.INVIDIOUS, true)
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").commit()
        launch()
        openSources()
        openSource("Invidious")
        // Without a rule of its own, Invidious follows YouTube's, here the default.
        composeRule.onNodeWithText(string(R.string.rule_opens_in, string(R.string.rule_default, "YouTube Music"))).performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasText(string(R.string.service_spotify))).onLast().performClick()
        composeRule.waitForIdle()
        assertTextShown(string(R.string.rule_opens_in, "Spotify"))
        assertEquals(Destination.Service(MusicService.SPOTIFY), DestinationStore(prefs()).rule(Frontend.INVIDIOUS))
        click(string(R.string.back_button))
        controller!!.pause().stop().destroy()

        fake.handler = { request ->
            FakeSpotify.html(request, """{"title":"The Weeknd - Blinding Lights (Official Video)","author_name":"TheWeekndVEVO"}""")
        }
        val activity = launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://yewtu.be/watch?v=4NRXx6U8ABQ")))
        waitUntil { activity.isFinishing }
        assertEquals(MusicService.SPOTIFY.packageName, nextStartedActivity()!!.`package`)

        // Piped's links still go where YouTube's do.
        assertNull(DestinationStore(prefs()).rule(Frontend.PIPED))
    }

    /** YouTube Music says the video is someone's own upload, not music; nothing else is answered. */
    private fun respondNotMusic() {
        fake.handler = { request ->
            FakeSpotify.html(request, """{"x":{"musicVideoType":"MUSIC_VIDEO_TYPE_UGC"}}""")
        }
    }

    @Test
    fun aYouTubeVideoThatIsntMusicOpensInYouTubeAsUsual() {
        respondNotMusic()
        val activity = launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=jNQXAC9IVRw")))
        waitUntil { activity.isFinishing }
        val opened = nextStartedActivity()!!
        assertEquals(MusicService.YOUTUBE.packageName, opened.`package`)
        assertEquals("https://www.youtube.com/watch?v=jNQXAC9IVRw", opened.dataString)
        // YouTube Music was asked. The video's own lookup runs alongside, so a music video waits once,
        // and is dropped once it isn't music.
        assertTrue(fake.requestedUrls.any { it.startsWith("https://music.youtube.com/") })

        controller!!.pause().stop().destroy()

        // Opening links in Crosstune, it goes to YouTube all the same, rather than to a card.
        linksShowHere()
        val shown = launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=jNQXAC9IVRw")))
        waitUntil { shown.isFinishing }
        assertEquals(MusicService.YOUTUBE.packageName, nextStartedActivity()!!.`package`)
    }

    @Test
    fun anInvidiousVideoThatIsntMusicStaysOnItsSite() {
        shadowOf(app).checkActivities(true)
        installActivity(ComponentName("com.example.browser", "com.example.browser.Browser"), browserFilter())
        respondNotMusic()
        val activity = launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://yewtu.be/watch?v=jNQXAC9IVRw")))
        waitUntil { activity.isFinishing }
        val opened = nextStartedActivity()!!
        assertEquals("https://yewtu.be/watch?v=jNQXAC9IVRw", opened.dataString)
        assertEquals("com.example.browser", app.packageManager.resolveActivity(opened, 0)!!.activityInfo.packageName)
    }

    @Test
    fun everyVideoGoesToTheUsersAppWhenOnlyMusicIsOff() {
        respondNotMusic()
        launch()
        openSources()
        openSource(string(R.string.target_youtube))
        toggleRow(string(R.string.setting_only_music_videos))
        assertFalse(prefs().getBoolean("only_music_videos", true))
        click(string(R.string.back_button))
        click(string(R.string.back_button))
        controller!!.pause().stop().destroy()

        fake.requestedUrls.clear()
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=jNQXAC9IVRw")))
        waitUntil(TIMEOUT_MS) { fake.requestedUrls.isNotEmpty() }
        // Straight to looking the video up, without asking YouTube Music first.
        assertTrue(fake.requestedUrls.none { "youtubei/v1/next" in it })
    }

    @Test
    fun anInvidiousLinkOpensStraightInYouTubeWhereTheUserListens() {
        prefs().edit().putString("default_target", "YOUTUBE").commit()
        fake.handler = { request ->
            FakeSpotify.html(request, """{"title":"The Weeknd - Blinding Lights (Official Video)","author_name":"TheWeekndVEVO"}""")
        }
        val activity = launch(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "https://yewtu.be/watch?v=4NRXx6U8ABQ")
            }
        )
        waitUntil { activity.isFinishing }
        val opened = nextStartedActivity()!!
        assertEquals(MusicService.YOUTUBE.packageName, opened.`package`)
        assertEquals("https://www.youtube.com/watch?v=4NRXx6U8ABQ", opened.dataString)
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
        openAdvanced()
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

    /** Something opened before, so what's left to fix is the bar's rather than asked under the field. */
    private fun openedBefore() {
        val url = "https://open.spotify.com/track/$OTHER_TRACK_ID"
        HistoryStore(prefs()).add(HistoryEntry(MusicLink(null, ItemType.TRACK, url, url), MusicMetadata("A Song", "Example Band")))
    }

    private fun freshInstall() {
        prefs().edit().clear().commit()
    }

    /** A checkbox row in setup, or a source's switch in settings, which is labelled with its name. */
    private fun sourceSwitch(label: String) = composeRule.onNode((hasText(label) or hasContentDescription(label)) and isToggleable())

    /** The result opens in its own service when picked from the Open button's arrow. */
    private fun openInTheAppItCameFrom() {
        chooseDefault(string(R.string.service_spotify))
        click(string(R.string.open_in_spotify))
    }

    /** The Fix buttons of what's left to fix in setup's list, in its order. */
    /** How many of setup's list are checked off. */
    private fun checkedOff() =
        composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.setup_done))).fetchSemanticsNodes().size

    /** A guide's Done button, shown once there's nothing left to do. */
    private fun clickDoneButton() {
        composeRule.onAllNodes(hasText(string(R.string.setup_done)) and hasClickAction()).onFirst().performClick()
        composeRule.waitForIdle()
    }

    /** Opens settings at the list of sources of links. */
    private fun openSources() {
        click(string(R.string.settings_button))
        click(string(R.string.settings_links_title))
    }

    /** Opens a source's own page from the list in settings. */
    private fun openSource(label: String) {
        // Not the default app's menu, which may show the same name.
        composeRule.onAllNodes(hasText(label) and hasClickAction() and !isToggleable() and !hasTestTag(DEFAULT_MENU_TAG)).onFirst().performClick()
        composeRule.waitForIdle()
    }

    private fun toggleRow(label: String) {
        sourceSwitch(label).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun aFreshInstallOpensEverySourcesLinks() {
        freshInstall()
        launch()
        // Videos' too: only music videos go elsewhere.
        assertEquals(
            MusicService.entries.filter { it.canBeSource }.toSet(),
            MusicService.entries.filter { LinkInterception(app).isEnabled(it) }.toSet()
        )
        assertTrue(Frontend.SOURCES.all { LinkInterception(app).isEnabled(it) })

        // Coming back later leaves the user's choices alone.
        LinkInterception(app).setEnabled(MusicService.SPOTIFY, false)
        controller!!.pause().stop().destroy()
        launch()
        assertFalse(LinkInterception(app).isEnabled(MusicService.SPOTIFY))
    }

    @Test
    fun firstLaunchOpensOnTheMainScreenAskingWhatAndroidHasToAllow() {
        freshInstall()
        FakeDomainVerification.install(app) {
            LinkInterception.HOSTS.getValue(MusicService.YOUTUBE).associateWith { DomainVerificationUserState.DOMAIN_STATE_SELECTED }
        }
        launch()
        // Right under the field, each saying what it gets you; not the bar too.
        assertTextShown(string(R.string.spotify_link_placeholder))
        assertTextShown(string(R.string.ask_links_gets))
        assertTextShown(string(R.string.ask_follow_gets))
        composeRule.onNodeWithTag(FIX_BAR_TAG).assertDoesNotExist()
        assertTextAbsent(string(R.string.empty_hint))
        // No app picked yet, so none's links are left out.
        assertFalse(DestinationStore(prefs()).hasDefault())
        assertTrue(MusicService.entries.filter { it.canBeSource }.all { LinkInterception(app).isEnabled(it) })
        assertTrue(prefs().getBoolean("setup_complete", false))

        // Only some are allowed, so each shows how.
        assertEquals(0, checkedOff())
        click(string(R.string.setup_check_allow))
        click(string(R.string.fix_open_settings))
        assertEquals(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, nextStartedActivity()!!.action)
        click(string(R.string.back_button))
        assertTextShown(string(R.string.ask_links_gets))

        // Still asked next time, until something's been opened.
        controller!!.pause().stop().destroy()
        launch()
        assertTextShown(string(R.string.ask_links_gets))
    }

    @Test
    fun onceSomethingsBeenOpenedWhatsLeftFoldsIntoTheBar() {
        respondWithTrack("Opened Once", "Artist · Song")
        launch()
        assertTextShown(string(R.string.ask_follow_gets))
        resolveTyped()
        click(string(R.string.clear_button))
        // Recent has it now, so what's left is the bar's, out of its way.
        assertTextShown("Opened Once")
        assertTextAbsent(string(R.string.ask_follow_gets))
        composeRule.onNodeWithTag(FIX_BAR_TAG).assertExists()
    }

    @Test
    fun theAsksCheckOffAllowingTheLinksOnceAndroidSaysAllAre() {
        MainActivity.playbackFactory = { FakePlayback() }
        freshInstall()
        var states = mapOf("open.spotify.com" to DomainVerificationUserState.DOMAIN_STATE_NONE)
        FakeDomainVerification.install(app) { states }
        LinkInterception(app).setEnabled(MusicService.SPOTIFY, true)
        DestinationStore(prefs()).setDefault(Destination.Service(MusicService.TIDAL))
        // A saved default looks like an older install, so mark it a fresh one explicitly.
        prefs().edit().putBoolean("setup_complete", false).commit()
        launch()
        assertEquals(0, checkedOff())

        // Some allowed isn't all.
        states = mapOf("open.spotify.com" to DomainVerificationUserState.DOMAIN_STATE_SELECTED)
        controller!!.pause().resume()
        composeRule.waitForIdle()
        assertEquals(0, checkedOff())

        // Done, it's checked off but stays, so the list doesn't move under the user's finger.
        states = LinkInterception.HOSTS.getValue(MusicService.SPOTIFY).associateWith { DomainVerificationUserState.DOMAIN_STATE_SELECTED }
        controller!!.pause().resume()
        composeRule.waitForIdle()
        assertEquals(1, checkedOff())
        assertTextShown(string(R.string.ask_links_gets))
    }

    @Test
    fun crosstuneSetToOpenLinksInTheBrowserIsFlaggedWithHowToFixIt() {
        var ownLinksOn = false
        FakeDomainVerification.installPerPackage(app, linkHandlingAllowed = { it != app.packageName || ownLinksOn }) {
            LinkInterception.HOSTS.getValue(MusicService.SPOTIFY).associateWith { DomainVerificationUserState.DOMAIN_STATE_SELECTED }
        }
        LinkInterception(app).setEnabled(MusicService.SPOTIFY, true)
        openedBefore()
        launch()

        // Every link is allowed, but none reach Crosstune.
        composeRule.onNodeWithTag(FIX_BAR_TAG).performClick()
        composeRule.waitForIdle()
        click(string(R.string.setup_check_allow))
        // Android hides the links to tick until it's switched back, so that's the first step.
        assertTextShown(string(R.string.fix_step_pick, string(R.string.android_in_the_app)))
        click(string(R.string.fix_open_settings))
        assertEquals(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, nextStartedActivity()!!.action)

        // Switched back, the steps go, and the links are checked off.
        ownLinksOn = true
        controller!!.pause().resume()
        waitUntil(TIMEOUT_MS) { checkedOff() == 1 }
        assertTextAbsent(string(R.string.fix_step_pick, string(R.string.android_in_the_app)))
    }

    @Test
    fun linksNotAllowedYetAreFlaggedOnTheirSettingsRowWithTheirSteps() {
        var states = emptyMap<String, Int>()
        FakeDomainVerification.install(app) { states }
        LinkInterception(app).setEnabled(MusicService.SPOTIFY, true)
        launch()

        // Settings marks the service itself.
        click(string(R.string.settings_button))
        assertMarked(string(R.string.setup_not_allowed))
        click(string(R.string.settings_links_title))
        openSource(string(R.string.service_spotify))
        click(string(R.string.allow_button))
        click(string(R.string.fix_open_settings))
        assertEquals(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, nextStartedActivity()!!.action)

        // All allowed, the steps close by themselves, back where they were opened.
        states = LinkInterception.HOSTS.getValue(MusicService.SPOTIFY).associateWith { DomainVerificationUserState.DOMAIN_STATE_SELECTED }
        controller!!.pause().resume()
        waitUntil(TIMEOUT_MS) { composeRule.onAllNodesWithText(string(R.string.fix_open_settings)).fetchSemanticsNodes().isEmpty() }
        assertTextAbsent(string(R.string.setup_not_allowed))
        click(string(R.string.back_button))
        assertTextAbsent(string(R.string.setup_not_allowed))
        click(string(R.string.back_button))
        assertTextAbsent(string(R.string.setup_not_allowed))
    }

    @Test
    fun theAppYouListenInOnlyOpensItsLinksOnceTheyGoSomewhereElse() {
        prefs().edit().putString("default_target", "YOUTUBE_MUSIC").commit()
        launch()
        openSources()
        sourceSwitch(string(R.string.target_youtube_music)).assertIsNotEnabled()
        openSource(string(R.string.target_youtube_music))
        // Locked, the note says how to turn it on.
        val note = string(R.string.settings_listening_locked, string(R.string.target_youtube_music))
        assertTextShown(note)
        sourceSwitch(string(R.string.source_open_links)).assertIsNotEnabled()

        // Sent to Spotify instead, its links are worth opening.
        composeRule.onNodeWithText(string(R.string.rule_opens_in, string(R.string.rule_default, "YouTube Music"))).performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasText(string(R.string.service_spotify))).onLast().performClick()
        composeRule.waitForIdle()
        assertTextAbsent(note)
        toggleRow(string(R.string.source_open_links))
        assertTrue(LinkInterception(app).isEnabled(MusicService.YOUTUBE_MUSIC))

        // Back to the default, they'd only return to YouTube Music, so Crosstune stops opening them.
        composeRule.onNodeWithText(string(R.string.rule_opens_in, "Spotify")).performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasText(string(R.string.rule_default, "YouTube Music"))).onLast().performClick()
        composeRule.waitForIdle()
        assertFalse(LinkInterception(app).isEnabled(MusicService.YOUTUBE_MUSIC))
        assertTextShown(note)
        click(string(R.string.back_button))

        // Making an opened service the default stops opening its links too.
        toggleRow(string(R.string.service_spotify))
        assertTrue(LinkInterception(app).isEnabled(MusicService.SPOTIFY))
        click(string(R.string.back_button))
        chooseDefaultHere(string(R.string.service_spotify))
        assertFalse(LinkInterception(app).isEnabled(MusicService.SPOTIFY))
    }

    @Test
    fun theHandoffShowsASongWithoutAnArtist() {
        val release = CountDownLatch(1)
        prefs().edit().putBoolean("play_right_away", true).putString("default_target", "DEEZER").putString("not_found", "SEARCH").commit()
        fake.handler = { request ->
            if (request.url.host == "api.deezer.com") {
                release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                FakeSpotify.html(request, """{"data":[]}""")
            } else {
                FakeSpotify.html(request, FakeSpotify.trackPage("Untitled Artist Song", null))
            }
        }
        val activity = launch(trackLink())
        waitForText(string(R.string.handoff_opening, "Deezer"))
        assertTextShown("Untitled Artist Song")
        release.countDown()
        waitUntil { activity.isFinishing }
    }

    @Test
    fun noBlockingNoticeForAppsCrosstuneDoesNotIntercept() {
        val spotify = MusicService.SPOTIFY.packageName
        shadowOf(app.packageManager).installPackage(installedApp(spotify, "Spotify"))
        FakeDomainVerification.installPerPackage(app) {
            mapOf("open.spotify.com" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED)
        }
        launch()
        assertTextAbsent(string(R.string.notice_app_still_opens, string(R.string.service_spotify)))
    }

    @Test
    fun theAsksFindAnyAppThatKeepsTheLinksNotJustMusicApps() {
        // Like YouTube Create, which verifies YouTube's links though Crosstune doesn't know it.
        val creator = "com.example.youtube.create"
        shadowOf(app.packageManager).installPackage(installedApp(creator, "YouTube Create"))
        installActivity(
            ComponentName(creator, "$creator.LinkActivity"),
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("https")
                addDataAuthority("youtube.com", null)
            }
        )
        // YouTube's own app is in the way too.
        val youtube = MusicService.YOUTUBE.packageName
        shadowOf(app.packageManager).installPackage(installedApp(youtube, "YouTube"))
        var appAllowsLinks = true
        var youtubeAllowsLinks = true
        FakeDomainVerification.installPerPackage(
            app,
            linkHandlingAllowed = { (it != creator || appAllowsLinks) && (it != youtube || youtubeAllowsLinks) }
        ) { packageName ->
            if (packageName == creator || packageName == youtube) {
                mapOf("youtube.com" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED)
            } else {
                emptyMap()
            }
        }
        freshInstall()
        LinkInterception(app).setEnabled(MusicService.YOUTUBE, true)
        DestinationStore(prefs()).setDefault(Destination.Service(MusicService.YOUTUBE_MUSIC))
        prefs().edit().putBoolean("setup_complete", false).commit()
        launch()

        // Each app in the way has its own row, in name order: YouTube, then YouTube Create.
        assertTextShown(string(R.string.setup_check_app, "YouTube"))
        assertTextShown(string(R.string.setup_check_app, "YouTube Create"))
        click(string(R.string.setup_check_app, "YouTube"))
        click(string(R.string.open_app_link_settings_button, "YouTube"))
        assertEquals(Uri.parse("package:$youtube"), nextStartedActivity()!!.data)
        youtubeAllowsLinks = false
        controller!!.pause().resume()
        waitUntil(TIMEOUT_MS) { checkedOff() == 1 }

        // Fixed, it's checked off but stays, so the list doesn't move under the user's finger.
        assertTextShown(string(R.string.setup_check_app, "YouTube"))
        click(string(R.string.setup_check_app, "YouTube Create"))
        click(string(R.string.open_app_link_settings_button, "YouTube Create"))
        assertEquals(Uri.parse("package:$creator"), nextStartedActivity()!!.data)

        appAllowsLinks = false
        controller!!.pause().resume()
        waitUntil(TIMEOUT_MS) { checkedOff() == 2 }
    }

    @Test
    fun eachAppInTheWayIsListedInNameOrderAndStaysListedOnceFixed() {
        val creator = "com.example.youtube.create"
        shadowOf(app.packageManager).installPackage(installedApp(creator, "YouTube Create"))
        installActivity(
            ComponentName(creator, "$creator.LinkActivity"),
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("https")
                addDataAuthority("youtube.com", null)
            }
        )
        val youtube = MusicService.YOUTUBE.packageName
        shadowOf(app.packageManager).installPackage(installedApp(youtube, "YouTube"))
        var creatorAllowsLinks = true
        var youtubeAllowsLinks = true
        FakeDomainVerification.installPerPackage(
            app,
            linkHandlingAllowed = { (it != creator || creatorAllowsLinks) && (it != youtube || youtubeAllowsLinks) }
        ) { packageName ->
            if (packageName == app.packageName) emptyMap() else mapOf("youtube.com" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED)
        }
        LinkInterception(app).setEnabled(MusicService.YOUTUBE, true)
        openedBefore()
        launch()
        composeRule.onNodeWithTag(FIX_BAR_TAG).performClick()
        composeRule.waitForIdle()
        click(string(R.string.fix_one_by_one))

        // One app at a time, in name order.
        assertTextShown(string(R.string.setup_step, 1, 4))
        click(string(R.string.open_app_link_settings_button, "YouTube"))
        assertEquals(Uri.parse("package:$youtube"), nextStartedActivity()!!.data)
        youtubeAllowsLinks = false
        controller!!.pause().resume()
        waitForText(string(R.string.setup_step, 2, 4))
        click(string(R.string.open_app_link_settings_button, "YouTube Create"))
        assertEquals(Uri.parse("package:$creator"), nextStartedActivity()!!.data)

        // Fixed, both stay listed, checked off, so the list doesn't move under the user's finger.
        creatorAllowsLinks = false
        controller!!.pause().resume()
        waitForText(string(R.string.setup_step, 3, 4))
        click(string(R.string.back_button))
        assertTextShown(string(R.string.setup_check_app, "YouTube"))
        assertTextShown(string(R.string.setup_check_app, "YouTube Create"))
        assertEquals(2, checkedOff())
    }

    @Test
    fun linksAllowedWhileAnAppStillTakesThemLeaveTheAppToFix() {
        val hosts = LinkInterception.HOSTS.getValue(MusicService.SPOTIFY)
        shadowOf(app.packageManager).installPackage(installedApp(MusicService.SPOTIFY.packageName, "Spotify"))
        FakeDomainVerification.installPerPackage(app) { packageName ->
            if (packageName != app.packageName) hosts.associateWith { DomainVerificationUserState.DOMAIN_STATE_VERIFIED } else hosts.associateWith { DomainVerificationUserState.DOMAIN_STATE_SELECTED }
        }
        MainActivity.playbackFactory = { FakePlayback().apply { access = true } }
        LinkInterception(app).setEnabled(MusicService.SPOTIFY, true)
        openedBefore()
        launch()
        // Every link is allowed, but none open in Crosstune while Spotify's app takes them.
        composeRule.onNodeWithTag(FIX_BAR_TAG).assert(hasText("1")).performClick()
        composeRule.waitForIdle()
        assertEquals(2, checkedOff())
        assertTextShown(string(R.string.setup_check_app, "Spotify"))
    }

    @Test
    fun theAsksSayToSwitchCrosstunesLinksBackFromTheBrowserAndShowHeldBackLyricsSteps() {
        // Installed from a file, Android holds the lyrics access back.
        MainActivity.playbackFactory = { FakePlayback().apply { restricted = true } }
        var ownLinksOn = false
        FakeDomainVerification.installPerPackage(app, linkHandlingAllowed = { it != app.packageName || ownLinksOn }) {
            (LinkInterception.HOSTS.values.flatten() + Frontend.SOURCES.flatMap { it.sites }).associateWith { DomainVerificationUserState.DOMAIN_STATE_SELECTED }
        }
        freshInstall()
        LinkInterception(app).setEnabled(MusicService.SPOTIFY, true)
        DestinationStore(prefs()).setDefault(Destination.Service(MusicService.TIDAL))
        prefs().edit().putBoolean("setup_complete", false).commit()
        launch()

        // Set to open in the browser, Android hides the links to tick, so switching back is the step.
        assertEquals(0, checkedOff())
        click(string(R.string.setup_check_allow))
        assertTextShown(string(R.string.fix_step_pick, string(R.string.android_in_the_app)))
        ownLinksOn = true
        controller!!.pause().resume()
        waitUntil(TIMEOUT_MS) { checkedOff() == 1 }

        // Lyrics are next, and held back, so their steps go through App info.
        click(string(R.string.follow_help_title))
        assertTextShown(string(R.string.follow_help_app_info))
    }

    @Test
    fun settingsKeepsAppsWithTheLinksListedSoTheirChoiceCanBeUndone() {
        val youtube = MusicService.YOUTUBE.packageName
        shadowOf(app.packageManager).installPackage(installedApp(youtube, "YouTube"))
        var appAllowsLinks = true
        FakeDomainVerification.installPerPackage(app, linkHandlingAllowed = { it != youtube || appAllowsLinks }) { packageName ->
            if (packageName == youtube) mapOf("youtube.com" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED) else emptyMap()
        }
        LinkInterception(app).setEnabled(MusicService.YOUTUBE, true)
        launch()
        openSources()
        // Not in the list: each source's page shows the apps that can open its links.
        assertTextAbsent(string(R.string.settings_link_owners_title))
        openSource(string(R.string.target_youtube))
        assertTextShown(string(R.string.settings_link_owners_title))
        assertTextShown(string(R.string.link_owner_opens_them))

        // Given up to Crosstune, it stays listed, so it can be given back.
        appAllowsLinks = false
        controller!!.pause().resume()
        composeRule.waitForIdle()
        assertTextShown(string(R.string.link_owner_lets_crosstune))
        click(string(R.string.link_owner_lets_crosstune))
        assertEquals(Uri.parse("package:$youtube"), nextStartedActivity()!!.data)
    }

    @Test
    fun aSourceWhoseLinksAnAppStillOpensIsMarkedLikeOneNotAllowed() {
        // Like YouTube Create, which verifies YouTube's links though Crosstune doesn't know it.
        val creator = "com.example.youtube.create"
        shadowOf(app.packageManager).installPackage(installedApp(creator, "YouTube Create"))
        installActivity(
            ComponentName(creator, "$creator.LinkActivity"),
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("https")
                addDataAuthority("youtube.com", null)
            }
        )
        val youtube = MusicService.YOUTUBE.packageName
        shadowOf(app.packageManager).installPackage(installedApp(youtube, "YouTube"))
        var keeping = setOf(youtube, creator)
        FakeDomainVerification.installPerPackage(app, linkHandlingAllowed = { it == app.packageName || it in keeping }) { packageName ->
            if (packageName == app.packageName) {
                LinkInterception.HOSTS.getValue(MusicService.YOUTUBE).associateWith { DomainVerificationUserState.DOMAIN_STATE_SELECTED }
            } else {
                mapOf("youtube.com" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED)
            }
        }
        LinkInterception(app).setEnabled(MusicService.YOUTUBE, true)
        prefs().edit().putBoolean("link_settings_helper_dismissed", true).commit()
        launch()

        // Allowed, but two apps still open YouTube's links, so none reach Crosstune: the sources are marked, and YouTube in them.
        val both = string(R.string.notice_apps_still_open, "YouTube, YouTube Create")
        click(string(R.string.settings_button))
        assertTrue(marked(both))
        click(string(R.string.settings_links_title))
        assertTrue(marked(both))
        assertFalse(marked(string(R.string.setup_not_allowed)))
        // One left, the mark says which.
        keeping = setOf(creator)
        controller!!.pause().resume()
        composeRule.waitForIdle()
        assertTrue(marked(string(R.string.notice_app_still_opens, "YouTube Create")))
        // None left, nothing is.
        keeping = emptySet()
        controller!!.pause().resume()
        composeRule.waitForIdle()
        assertFalse(marked(string(R.string.notice_app_still_opens, "YouTube Create")))
        click(string(R.string.back_button))
        assertFalse(marked(string(R.string.notice_app_still_opens, "YouTube Create")))
    }

    /** Whether something is marked with [description]: an icon, whatever else says it in words. */
    private fun marked(description: String) =
        composeRule.onAllNodes(hasContentDescription(description), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Setup as if Spotify were picked and its app installed and claiming the links; returns a way to change its switch. */
    private fun setupWithSpotifyAppInTheWay(): (Boolean) -> Unit {
        val spotify = MusicService.SPOTIFY.packageName
        shadowOf(app.packageManager).installPackage(installedApp(spotify, "Spotify"))
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
    fun theAsksListAnInstalledAppTakingTheLinksAndCheckItOffOnceStopped() {
        val setAppAllowsLinks = setupWithSpotifyAppInTheWay()
        MainActivity.playbackFactory = { FakePlayback() }
        launch()

        // Android won't let Crosstune take links the app verified, so it's to stop, with how; not to allow.
        click(string(R.string.setup_check_app, string(R.string.service_spotify)))
        click(string(R.string.open_app_link_settings_button, string(R.string.service_spotify)))
        val started = nextStartedActivity()!!
        assertEquals(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, started.action)
        assertEquals(Uri.parse("package:${MusicService.SPOTIFY.packageName}"), started.data)

        // Fixed in Android's settings, and its links are allowed already: only lyrics are left.
        setAppAllowsLinks(false)
        controller!!.pause().resume()
        waitUntil(TIMEOUT_MS) { checkedOff() == 2 }
        assertTextShown(string(R.string.ask_follow_gets))
    }

    @Test
    fun theAsksListNoAppsWhenNoneIsInTheWay() {
        freshInstall()
        LinkInterception(app).setEnabled(MusicService.SPOTIFY, true)
        DestinationStore(prefs()).setDefault(Destination.Service(MusicService.TIDAL))
        prefs().edit().putBoolean("setup_complete", false).commit()
        launch()
        // Allowing the links, and lyrics following music apps. The microphone is asked where it's used.
        assertTextShown(string(R.string.setup_check_allow))
        assertTextAbsent(string(R.string.fix_mic_title))
        assertTextShown(string(R.string.follow_help_title))
        assertTextAbsent(string(R.string.setup_check_app, string(R.string.service_spotify)))
        // Two to allow, each saying so.
        assertEquals(2, composeRule.onAllNodesWithText(string(R.string.allow_button)).fetchSemanticsNodes().size)
    }

    @Test
    fun withNothingToAllowTheMainScreenJustSaysWhatToDo() {
        MainActivity.playbackFactory = { FakePlayback().apply { access = true } }
        freshInstall()
        FakeDomainVerification.install(app) { LinkInterception.HOSTS.getValue(MusicService.SPOTIFY).associateWith { DomainVerificationUserState.DOMAIN_STATE_SELECTED } }
        LinkInterception(app).setEnabled(MusicService.SPOTIFY, true)
        DestinationStore(prefs()).setDefault(Destination.Service(MusicService.TIDAL))
        prefs().edit().putBoolean("setup_complete", false).commit()
        launch()
        assertTextShown(string(R.string.empty_hint))
        assertTextAbsent(string(R.string.setup_check_allow))
        composeRule.onNodeWithTag(FIX_BAR_TAG).assertDoesNotExist()
    }

    @Test
    @Config(sdk = [30])
    fun beforeAndroid12TheAsksListInstalledAppsWithoutAStatus() {
        MainActivity.playbackFactory = { FakePlayback().apply { access = true } }
        MainActivity.shellFactory = { listOf(FakeShell()) }
        shadowOf(app.packageManager).installPackage(installedApp(MusicService.SPOTIFY.packageName, "Spotify"))
        freshInstall()
        LinkInterception(app).setEnabled(MusicService.SPOTIFY, true)
        DestinationStore(prefs()).setDefault(Destination.Service(MusicService.TIDAL))
        prefs().edit().putBoolean("setup_complete", false).commit()
        launch()

        // Android can't say whether the app still takes the links, so it isn't checked off, nor
        // are the links, just turned on.
        assertTextShown(string(R.string.setup_check_app, string(R.string.service_spotify)))
        assertEquals(1, checkedOff())
        // Nor can Shizuku do them, though it runs.
        assertTextShown(string(R.string.fix_one_by_one))
        assertTextAbsent(string(R.string.fix_all))
    }

    @Test
    @Config(sdk = [30])
    fun beforeAndroid12ShizukuIsntOfferedThoughLyricsAreLeft() {
        MainActivity.shellFactory = { listOf(FakeShell()) }
        freshInstall()
        launch()
        // Android 11's commands can't allow links, and nothing tells Crosstune Shizuku's there to do lyrics alone.
        assertTextShown(string(R.string.fix_one_by_one))
        assertTextAbsent(string(R.string.fix_all))
    }

    @Test
    fun withNoSourcesOnlyLyricsAreAskedAndAllShizukuDoes() {
        val shell = FakeShell()
        MainActivity.shellFactory = { listOf(shell) }
        freshInstall()
        // As when every source was turned off in settings.
        prefs().edit().putBoolean("sources_preselected", true).commit()
        launch()
        assertTextAbsent(string(R.string.setup_check_allow))
        // Lyrics are still there to let hear and follow, which is all Shizuku is asked.
        assertTextShown(string(R.string.follow_help_title))
        click(string(R.string.fix_all))
        assertEquals(listOf("cmd", "notification", "allow_listener"), shell.ran.first().take(3))
        assertEquals(listOf("pm", "grant"), shell.ran.last().take(2))
        assertEquals(2, shell.ran.size)
    }

    @Test
    fun theOnlyInstalledMusicAppIsWhereSongsPlayWithoutAskingButNotWithMore() {
        freshInstall()
        // YouTube comes with most phones, so it doesn't count.
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = MusicService.YOUTUBE.packageName })
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = "com.soundcloud.android" })
        launch()
        waitUntil(TIMEOUT_MS) { DestinationStore(prefs()).hasDefault() }
        assertEquals(Destination.Service(MusicService.SOUNDCLOUD), DestinationStore(prefs()).defaultDestination())
        // Links going straight to it, its own stay there.
        assertFalse(LinkInterception(app).isEnabled(MusicService.SOUNDCLOUD))
        assertTrue(LinkInterception(app).isEnabled(MusicService.SPOTIFY))
        controller!!.pause().stop().destroy()

        // An app picked already stays, though another's the only one now.
        freshInstall()
        DestinationStore(prefs()).setDefault(Destination.Service(MusicService.DEEZER))
        prefs().edit().putBoolean("setup_complete", false).commit()
        launch()
        assertEquals(Destination.Service(MusicService.DEEZER), DestinationStore(prefs()).defaultDestination())
        controller!!.pause().stop().destroy()

        // With two, it's left to the first one picked on a song.
        freshInstall()
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = MusicService.SPOTIFY.packageName })
        launch()
        assertFalse(DestinationStore(prefs()).hasDefault())
    }

    @Test
    fun theFirstAppPickedOnASongIsWhereSongsPlayFromThenOn() {
        freshInstall()
        prefs().edit().putBoolean("play_right_away", false).commit()
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = MusicService.SPOTIFY.packageName })
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = MusicService.DEEZER.packageName })
        respondWithTrack("Picked", "Artist · Song")
        val activity = launch()
        resolveTyped()
        // Nothing picked, the first music app on the phone is offered, but only offered.
        assertEquals(Destination.Service(MusicService.SPOTIFY), ViewModelProvider(activity)[MainViewModel::class.java].uiState.defaultDestination)
        assertFalse(DestinationStore(prefs()).hasDefault())
        chooseDefault(string(R.string.target_deezer))
        assertEquals(Destination.Service(MusicService.DEEZER), DestinationStore(prefs()).defaultDestination())

        // Another picked on the next song is just for it.
        click(string(R.string.clear_button))
        resolveTyped()
        chooseDefault(string(R.string.service_spotify))
        assertEquals(Destination.Service(MusicService.DEEZER), DestinationStore(prefs()).defaultDestination())
    }

    @Test
    fun theOnlyMusicAppKeepsItsLinksOpeningHereOnlyWhenLinksOpenInCrosstune() {
        freshInstall()
        prefs().edit().putBoolean("links_in_crosstune", true).commit()
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = MusicService.YOUTUBE_MUSIC.packageName })
        launch()
        waitUntil(TIMEOUT_MS) { DestinationStore(prefs()).hasDefault() }
        assertTrue(LinkInterception(app).isEnabled(MusicService.YOUTUBE_MUSIC))
        controller!!.pause().stop().destroy()

        // Going straight to the app, Crosstune would only hand YouTube Music its own links back.
        freshInstall()
        launch()
        waitUntil(TIMEOUT_MS) { DestinationStore(prefs()).hasDefault() }
        assertFalse(LinkInterception(app).isEnabled(MusicService.YOUTUBE_MUSIC))
        assertTrue(LinkInterception(app).isEnabled(MusicService.YOUTUBE))
    }

    /** A fresh install with Spotify's app in the way of links not allowed yet, and lyrics not let follow; returns what Shizuku ran. */
    private fun oneTapSetup(shell: (playback: FakePlayback, unblock: () -> Unit, allow: () -> Unit) -> FakeShell): FakeShell {
        val spotify = MusicService.SPOTIFY.packageName
        shadowOf(app.packageManager).installPackage(installedApp(spotify, "Spotify"))
        var appAllowsLinks = true
        var allowed = false
        FakeDomainVerification.installPerPackage(app, linkHandlingAllowed = { it != spotify || appAllowsLinks }) { packageName ->
            val hosts = LinkInterception.HOSTS.getValue(MusicService.SPOTIFY)
            when {
                packageName == spotify -> hosts.associateWith { DomainVerificationUserState.DOMAIN_STATE_VERIFIED }
                allowed -> hosts.associateWith { DomainVerificationUserState.DOMAIN_STATE_SELECTED }
                else -> emptyMap()
            }
        }
        val playback = FakePlayback()
        MainActivity.playbackFactory = { playback }
        val fake = shell(playback, { appAllowsLinks = false }, { allowed = true })
        MainActivity.shellFactory = { listOf(fake) }
        freshInstall()
        LinkInterception(app).setEnabled(MusicService.SPOTIFY, true)
        DestinationStore(prefs()).setDefault(Destination.Service(MusicService.TIDAL))
        prefs().edit().putBoolean("setup_complete", false).commit()
        return fake
    }

    /** Shizuku doing what it's told, as Android would. */
    private fun doingShell(playback: FakePlayback, unblock: () -> Unit, allow: () -> Unit) = FakeShell { command ->
        when {
            "false" in command -> unblock()
            "all" in command -> allow()
            "allow_listener" in command -> playback.access = true
            android.Manifest.permission.RECORD_AUDIO in command -> shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        }
        true
    }

    @Test
    fun withShizukuAllThatsAskedIsDoneInOneTap() {
        val shell = oneTapSetup(::doingShell)
        launch()
        assertTextShown(string(R.string.fix_with_shizuku))
        click(string(R.string.fix_all))

        val user = (android.os.Process.myUid() / 100_000).toString()
        assertEquals(
            listOf(
                // The app in the way first, since Android won't let Crosstune take links it verified.
                listOf("pm", "set-app-links-allowed", "--user", user, "--package", MusicService.SPOTIFY.packageName, "false"),
                listOf("pm", "set-app-links-allowed", "--user", user, "--package", app.packageName, "true"),
                listOf("pm", "set-app-links-user-selection", "--user", user, "--package", app.packageName, "true", "all"),
                listOf("cmd", "notification", "allow_listener", ComponentName(app, NowPlayingListener::class.java).flattenToString(), user),
                // The microphone too: it would be asked where it's used, and this costs no more.
                listOf("pm", "grant", "--user", user, app.packageName, android.Manifest.permission.RECORD_AUDIO)
            ),
            shell.ran
        )
        // Nothing's left to ask, so the main screen just says what to do.
        assertTextAbsent(string(R.string.fix_all))
        assertTextShown(string(R.string.empty_hint))
    }

    @Test
    fun oneTapSetupLeavesAloneWhatsAlreadyDone() {
        val shell = oneTapSetup { playback, unblock, allow -> playback.access = true; doingShell(playback, unblock, allow) }
        shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        launch()
        click(string(R.string.fix_all))
        assertEquals(3, shell.ran.size)
        assertTrue(shell.ran.none { "allow_listener" in it || "grant" in it })
        assertTextShown(string(R.string.empty_hint))
    }

    @Test
    fun oneTapSetupThatCantDoItAllSaysSoAndLeavesTheRestToFix() {
        val shell = oneTapSetup { _, unblock, allow ->
            FakeShell { command ->
                when {
                    "false" in command -> unblock()
                    "all" in command -> allow()
                    android.Manifest.permission.RECORD_AUDIO in command -> shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
                }
                "allow_listener" !in command
            }
        }
        launch()
        click(string(R.string.fix_all))
        assertEquals(5, shell.ran.size)
        assertTextShown(string(R.string.fix_failed))
        assertTextAbsent(string(R.string.fix_with_shizuku))
        // The links are done; following music apps is left, by hand.
        assertEquals(2, checkedOff())
    }

    @Test
    fun oneTapSetupDoesNothingUnlessShizukuIsAllowed() {
        val shell = oneTapSetup { _, _, _ -> FakeShell(allows = false) }
        launch()
        click(string(R.string.fix_all))
        assertTrue(shell.ran.isEmpty())
        assertTextShown(string(R.string.fix_failed))
        assertEquals(0, checkedOff())
    }

    @Test
    fun oneTapSetupRunsOnceWhileTheUserAnswersShizuku() {
        val shell = oneTapSetup { playback, unblock, allow -> doingShell(playback, unblock, allow).apply { answer = CompletableDeferred() } }
        val activity = launch()
        click(string(R.string.fix_all))
        composeRule.onNodeWithText(string(R.string.fix_all)).assertIsNotEnabled()
        ViewModelProvider(activity)[MainViewModel::class.java].setUpInOneTap()

        shell.answer.complete(Unit)
        composeRule.waitForIdle()
        assertEquals(5, shell.ran.size)
        assertTextShown(string(R.string.empty_hint))
    }

    @Test
    fun oneTapSetupIsOfferedWhileShizukuRuns() {
        val shell = oneTapSetup { _, _, _ -> FakeShell(ready = false) }
        launch()
        assertTextAbsent(string(R.string.fix_all))

        shell.ready = true
        shell.changed!!()
        composeRule.waitForIdle()
        assertTextShown(string(R.string.fix_all))

        // Stopped, it's gone again, and coming back looks again too.
        shell.ready = false
        controller!!.pause().resume()
        composeRule.waitForIdle()
        assertTextAbsent(string(R.string.fix_all))

        // Done with, Crosstune stops watching.
        controller!!.pause().stop().destroy()
        assertNull(shell.changed)
    }

    /** Setup done, with Spotify's app still in the way and the links not allowed, as found later. */
    private fun foundWrongAfterSetup(shell: (playback: FakePlayback, unblock: () -> Unit, allow: () -> Unit) -> FakeShell): FakeShell =
        oneTapSetup(shell).also { prefs().edit().putBoolean("setup_complete", true).commit() }

    @Test
    fun afterSetupShizukuFixesOnceWhileTheUserAnswersIt() {
        val shell = foundWrongAfterSetup { playback, unblock, allow -> doingShell(playback, unblock, allow).apply { answer = CompletableDeferred() } }
        val activity = launch()
        openSources()
        openSource(string(R.string.service_spotify))
        click(string(R.string.allow_button))
        // Busy, its button waits, and asking again does nothing more.
        composeRule.onNodeWithText(string(R.string.allow_button)).assertIsNotEnabled()
        assertTrue(ViewModelProvider(activity)[MainViewModel::class.java].fixInOneTap(OneTapFix.LINKS))
        shell.answer.complete(Unit)
        composeRule.waitForIdle()
        assertEquals(2, shell.ran.size)
        assertTextAbsent(string(R.string.fix_open_settings))
    }

    @Test
    fun afterSetupShizukuLetsLyricsFollowMusicAppsInOneTapOrLeavesItToAndroidsPage() {
        val shell = foundWrongAfterSetup(::doingShell)
        launch()
        click(string(R.string.settings_button))
        val follow = composeRule.onNodeWithText(string(R.string.setting_lyrics_follow)).performScrollTo()
        follow.performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("cmd", "notification", "allow_listener"), shell.ran.single().take(3))
        assertEquals(null, nextStartedActivity())
        follow.assertIsOn()
    }

    @Test
    fun lyricsShizukuCouldntLetFollowAreLeftToAndroidsPage() {
        val shell = foundWrongAfterSetup { _, _, _ -> FakeShell { false } }
        launch()
        click(string(R.string.settings_button))
        composeRule.onNodeWithText(string(R.string.setting_lyrics_follow)).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(1, shell.ran.size)
        // What it couldn't do is left to the steps, which say so.
        assertTextShown(string(R.string.fix_failed))
        click(string(R.string.fix_open_settings))
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, nextStartedActivity()!!.action)
    }

    @Test
    fun aLinkOpenedBeforeAnAppIsPickedShowsOnItsCardWithNoAppToPlayItIn() {
        // Links set to go straight to the app, but there isn't one yet.
        freshInstall()
        val phone = FakePlayback().apply { access = true }
        MainActivity.playbackFactory = { phone }
        respondWithTrack("Early", "Artist · Song")
        val activity = launch(trackLink())

        waitForText("Early")
        assertResultShown()
        assertTextAbsent(string(R.string.picker_title))
        assertTrue(ViewModelProvider(activity)[MainViewModel::class.java].uiState.exactMatch)
        assertTrue(phone.songsAsked.isEmpty())
        assertNull(nextStartedActivity())
        assertFalse(activity.isFinishing)
        controller!!.pause().stop().destroy()

        // History from that link must not be mistaken for an install from before sources were asked.
        LinkInterception(app).setEnabled(MusicService.SPOTIFY, false)
        launch()
        assertFalse(LinkInterception(app).isEnabled(MusicService.SPOTIFY))
    }

    @Test
    fun updatingFromAVersionWithoutSetupKeepsSpotifyLinksAndNoMore() {
        freshInstall()
        prefs().edit().putBoolean("link_settings_helper_dismissed", true).commit()
        launch()

        assertTrue(LinkInterception(app).isEnabled(MusicService.SPOTIFY))
        assertFalse(LinkInterception(app).isEnabled(MusicService.APPLE_MUSIC))
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
        prefs().edit().putBoolean("play_right_away", true).putString("default_target", "DEEZER").commit()
        launch()
        resolveTyped()

        waitForText(string(R.string.matching_text))
        openInTheAppItCameFrom()
        release.countDown()
        Thread.sleep(200L)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("com.spotify.music", nextStartedActivity()!!.`package`)
        // The late Deezer match doesn't open a second app.
        assertNull(nextStartedActivity())
        assertTextAbsent(string(R.string.matching_text))
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
        openInTheAppItCameFrom()

        assertEquals("https://open.spotify.com/track/$TRACK_ID", nextStartedActivity()!!.dataString)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun theChosenDefaultIsMarkedSelectedInItsMenu() {
        launch()
        click(string(R.string.settings_button))
        composeRule.onNodeWithTag(DEFAULT_MENU_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasText(string(R.string.target_youtube_music)) and isSelected()).onLast().assertExists()
    }

    @Test
    fun systemBackFromAnAskedStepComesBackToTheAsks() {
        freshInstall()
        val activity = launch()
        click(string(R.string.setup_check_allow))
        assertTextShown(string(R.string.fix_open_settings))

        activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()

        assertTextShown(string(R.string.ask_links_gets))
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
        // Offered right under the Open button, which already shows the app.
        val openBounds = composeRule.onNodeWithTag(DEFAULT_MENU_TAG).fetchSemanticsNode().boundsInRoot
        val makeDefault = string(R.string.make_default_named, "Apple Music")
        assertTrue(composeRule.onNodeWithText(makeDefault).fetchSemanticsNode().boundsInRoot.top >= openBounds.bottom)
        click(makeDefault)
        assertEquals(Destination.Service(MusicService.APPLE_MUSIC), DestinationStore(prefs()).defaultDestination())
        assertTextAbsent(makeDefault)

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
        prefs().edit().putBoolean("play_right_away", true).putString("default_target", "DEEZER").commit()
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

        click(string(R.string.history_open, "Remember"))
        assertEquals("https://www.deezer.com/track/123", nextStartedActivity()!!.dataString)
        assertEquals(requests, fake.requestedUrls.size)
        assertEquals("", model.uiState.linkText)
        assertNull(model.uiState.result)

        model.setPlayRightAway(false)
        composeRule.waitForIdle()
        click(string(R.string.history_open, "Remember"))
        assertEquals("https://www.deezer.com/search/Remember%20Artist", nextStartedActivity()!!.dataString)
        assertNull(model.uiState.result)

        model.setPlayRightAway(true)
        model.selectDefault(Destination.Service(MusicService.SPOTIFY))
        composeRule.waitForIdle()
        click(string(R.string.history_open, "Remember"))
        assertEquals("https://open.spotify.com/track/$TRACK_ID", nextStartedActivity()!!.dataString)
        assertEquals("", model.uiState.linkText)

        // An app with no exact match, only a search.
        model.selectDefault(Destination.Service(MusicService.AMAZON_MUSIC))
        composeRule.waitForIdle()
        click(string(R.string.history_open, "Remember"))
        assertEquals("https://music.amazon.com/search/Remember%20Artist", nextStartedActivity()!!.dataString)
        assertNull(model.uiState.result)
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
        linksAskWhere()
        launch(trackLink())
        waitForText(string(R.string.picker_title))
        assertTextShown("Picker summary")
        composeRule.onNodeWithTag("destination-icon:YOUTUBE", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("destination-icon:" + custom.key, useUnmergedTree = true).assertExists()
        // Apps that aren't on the phone wait behind More.
        composeRule.onNodeWithTag("destination-icon:APPLE_MUSIC", useUnmergedTree = true).assertDoesNotExist()
        click(string(R.string.more_button))
        assertTextAbsent(string(R.string.more_button))
        // Each service's icon is in the picker; the screen behind may show some too.
        MusicService.entries.forEach { service ->
            assertTrue(service.name, composeRule.onAllNodesWithTag("destination-icon:" + service.name, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        }
        // Sites with no logo get a letter tile.
        assertTrue(composeRule.onAllNodesWithTag("destination-icon:frontend:INVIDIOUS", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        click(string(R.string.cancel_button))
        assertTextAbsent(string(R.string.picker_title))
        chooseDefault("Player")
        assertTextShown(string(R.string.search_in_destination, "Player"))
    }

    // endregion

    // region speed

    /** Spotify's app is installed and keeps its links, which Crosstune is set to open. */
    private fun spotifyAppInTheWay(allowsLinks: () -> Boolean = { true }) {
        val spotify = MusicService.SPOTIFY.packageName
        shadowOf(app.packageManager).installPackage(installedApp(spotify, "Spotify"))
        FakeDomainVerification.installPerPackage(app, linkHandlingAllowed = { it != spotify || allowsLinks() }) {
            mapOf("open.spotify.com" to DomainVerificationUserState.DOMAIN_STATE_VERIFIED)
        }
        LinkInterception(app).setEnabled(MusicService.SPOTIFY, true)
    }

    @Test
    fun nothingIsFlaggedUntilAndroidHasBeenAskedAwayFromTheScreen() {
        val android = QueueDispatcher()
        MainActivity.systemDispatcher = android
        spotifyAppInTheWay()
        prefs().edit().putString("default_target", "DEEZER").commit()
        openedBefore()
        launch()

        // The screen is up while Android is still to be asked, with nothing flagged yet rather than something wrong.
        assertTextShown(string(R.string.spotify_link_placeholder))
        composeRule.onNodeWithTag(FIX_BAR_TAG).assertDoesNotExist()
        // Share sheet targets wait for the installed apps too, rather than drop out for a moment.
        assertTrue(dynamicShortcuts().isEmpty())

        android.runAll()
        composeRule.waitForIdle()
        // The app in the way, the links and following music apps.
        composeRule.onNodeWithTag(FIX_BAR_TAG).assert(hasText("3"))
        waitUntil(TIMEOUT_MS) { dynamicShortcuts().isNotEmpty() }
    }

    @Test
    fun comingBackKeepsTheCountUntilAndroidAnswersAndTheLatestAnswerWins() {
        val android = QueueDispatcher()
        MainActivity.systemDispatcher = android
        var spotifyKeepsLinks = true
        spotifyAppInTheWay { spotifyKeepsLinks }
        openedBefore()
        launch()
        android.runAll()
        composeRule.waitForIdle()
        val bar = composeRule.onNodeWithTag(FIX_BAR_TAG)
        bar.assert(hasText("3"))

        // The user turns the app's links off in Android's settings and comes back.
        spotifyKeepsLinks = false
        controller!!.pause().resume()
        composeRule.waitForIdle()
        // Until Android answers the count stays, rather than drop and come back.
        bar.assert(hasText("3"))

        // Coming back again before that answer: the newer look is the one that counts.
        controller!!.pause().resume()
        composeRule.waitForIdle()
        android.runLast()
        composeRule.waitForIdle()
        bar.assert(hasText("2"))

        // The older look finishing last, with a different answer, changes nothing.
        spotifyKeepsLinks = true
        android.runAll()
        composeRule.waitForIdle()
        bar.assert(hasText("2"))
    }

    @Test
    fun recentClearedWhileAndroidIsAskedStaysCleared() {
        val android = QueueDispatcher()
        MainActivity.systemDispatcher = android
        respondWithTrack("Cleared Song", "Artist · Song")
        val activity = launch()
        android.runAll()
        resolveTyped()
        click(string(R.string.clear_button))
        composeRule.onNodeWithContentDescription(string(R.string.clear_history_button)).assertExists()

        val model = ViewModelProvider(activity)[MainViewModel::class.java]
        // Recent is cleared after it was read with the rest, but before that answer arrives.
        FakeDomainVerification.install(app) {
            if (model.uiState.history.isNotEmpty()) model.clearHistory()
            emptyMap()
        }
        controller!!.pause().resume()
        android.runAll()
        composeRule.waitForIdle()
        assertTextAbsent("Cleared Song")
        assertTrue(model.uiState.history.isEmpty())
    }

    @Test
    fun theAsksWaitForWhatAndroidSays() {
        val android = QueueDispatcher()
        MainActivity.systemDispatcher = android
        freshInstall()
        launch()
        // Nothing's known to need fixing until Android's been asked.
        assertTextAbsent(string(R.string.setup_check_allow))

        android.runAll()
        composeRule.waitForIdle()
        assertTextShown(string(R.string.setup_check_allow))
    }

    @Test
    fun aYouTubeSongShowsItsAlbumCoverInsteadOfTheVideoFrame() {
        linksShowHere()
        val cover = "https://cdn-images.dzcdn.net/images/cover/abc/500x500-000000-80-0-0.jpg"
        fun respond(deezer: String) {
            fake.handler = { request ->
                when (request.url.host) {
                    "api.deezer.com" -> FakeSpotify.html(request, deezer)
                    else -> FakeSpotify.html(request,
                        """{"title":"The Weeknd - Blinding Lights (Official Video)","author_name":"TheWeekndVEVO","thumbnail_url":"https://i.ytimg.com/vi/4NRXx6U8ABQ/hqdefault.jpg"}""")
                }
            }
        }
        respond("""{"data":[{"title":"Blinding Lights","artist":{"name":"The Weeknd"},"album":{"cover_big":"$cover"}}]}""")
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=4NRXx6U8ABQ")))
        waitForText("Blinding Lights")
        assertEquals(cover, HistoryStore(prefs()).load().first().metadata.artworkUrl)

        // A video no song matches, like a tutorial, keeps its own picture.
        controller!!.pause().stop().destroy()
        HistoryStore(prefs()).clear()
        File(app.cacheDir, "lookups.json").delete()
        respond("""{"data":[{"title":"Blinding Lights","artist":{"name":"Someone Else"},"album":{"cover_big":"$cover"}}]}""")
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=4NRXx6U8ABQ")))
        waitForText("Blinding Lights")
        assertEquals("https://i.ytimg.com/vi/4NRXx6U8ABQ/mqdefault.jpg", HistoryStore(prefs()).load().first().metadata.artworkUrl)
    }

    @Test
    fun aMusicVideoIsLookedUpWhileYouTubeMusicIsAskedAboutIt() {
        val lookedUp = CountDownLatch(1)
        val answeredWhileLookingUp = AtomicBoolean(false)
        fake.handler = { request ->
            if (request.url.host == "music.youtube.com") {
                // YouTube Music only answers once the video is being looked up too.
                answeredWhileLookingUp.set(lookedUp.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
                FakeSpotify.html(request, """{"x":{"musicVideoType":"MUSIC_VIDEO_TYPE_OMV"}}""")
            } else {
                lookedUp.countDown()
                FakeSpotify.html(request, """{"title":"The Weeknd - Blinding Lights (Official Video)","author_name":"TheWeekndVEVO"}""")
            }
        }
        val activity = launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=4NRXx6U8ABQ")))
        waitUntil { activity.isFinishing }
        assertTrue(answeredWhileLookingUp.get())
        assertEquals(MusicService.YOUTUBE_MUSIC.packageName, nextStartedActivity()!!.`package`)
    }

    @Test
    fun aVideoThatIsntMusicOpensAsIsEvenWhenItsLookupAnswersFirst() {
        val lookedUp = CountDownLatch(1)
        fake.handler = { request ->
            if (request.url.host == "music.youtube.com") {
                lookedUp.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                FakeSpotify.html(request, """{"x":{"musicVideoType":"MUSIC_VIDEO_TYPE_UGC"}}""")
            } else {
                FakeSpotify.html(request, """{"title":"How to tie a tie","author_name":"Someone"}""").also { lookedUp.countDown() }
            }
        }
        val activity = launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=jNQXAC9IVRw")))
        waitUntil { activity.isFinishing }
        val opened = nextStartedActivity()!!
        assertEquals(MusicService.YOUTUBE.packageName, opened.`package`)
        assertEquals("https://www.youtube.com/watch?v=jNQXAC9IVRw", opened.dataString)
        // What the lookup found is neither shown nor kept.
        assertNull(ViewModelProvider(activity)[MainViewModel::class.java].uiState.result)
        assertTrue(HistoryStore(prefs()).load().isEmpty())
    }

    @Test
    fun aSongAlreadyInRecentShowsRightAwayWithoutLookingItUpAgain() {
        fake.handler = { request ->
            val title = if (request.url.pathSegments.last() == TRACK_ID) "First Song" else "Second Song"
            FakeSpotify.html(request, FakeSpotify.trackPage(title, "Artist · Song"))
        }
        launch()
        resolveTyped(TRACK_ID)
        click(string(R.string.clear_button))
        resolveTyped(OTHER_TRACK_ID)
        click(string(R.string.clear_button))
        val requests = fake.requestedUrls.size
        fake.handler = { throw IOException("offline") }

        // The same song, shared with tracking added, is the same link once cleaned up.
        typeUrl("https://open.spotify.com/track/$TRACK_ID?si=abc")
        click(string(R.string.resolve_button))
        assertResultShown()
        assertTextShown("First Song")
        assertEquals(requests, fake.requestedUrls.size)
        // It moves to the top of Recent, as if looked up again.
        assertEquals(listOf(TRACK_ID, OTHER_TRACK_ID), HistoryStore(prefs()).load().map { it.link.id })
    }

    @Test
    fun aSongAlreadyInRecentOpensItsSavedMatchFromAnotherApp() {
        prefs().edit().putBoolean("play_right_away", true).putString("default_target", "DEEZER").commit()
        fake.handler = { request ->
            if (request.url.host == "api.deezer.com") {
                FakeSpotify.html(request, """{"data":[{"title":"Again","artist":{"name":"Artist"},"link":"https://www.deezer.com/track/123"}]}""")
            } else FakeSpotify.html(request, FakeSpotify.trackPage("Again", "Artist · Song"))
        }
        launch()
        resolveTyped()
        waitForDestinationReady()
        controller!!.pause().stop().destroy()
        val requests = fake.requestedUrls.size

        val activity = launch(trackLink())
        waitUntil { activity.isFinishing }
        assertEquals("https://www.deezer.com/track/123", nextStartedActivity()!!.dataString)
        assertEquals(requests, fake.requestedUrls.size)
    }

    @Test
    fun aSearchPageSavedWhenMatchingFailedIsMatchedAgainWhenTheSongComesBack() {
        prefs().edit().putBoolean("play_right_away", true).putString("default_target", "DEEZER").commit()
        var deezerDown = true
        fake.handler = { request ->
            if (request.url.host == "api.deezer.com") {
                if (deezerDown) throw IOException("flaky network")
                FakeSpotify.html(request, """{"data":[{"title":"Again","artist":{"name":"Artist"},"link":"https://www.deezer.com/track/123"}]}""")
            } else FakeSpotify.html(request, FakeSpotify.trackPage("Again", "Artist · Song"))
        }
        launch()
        resolveTyped()
        waitForDestinationReady()
        // Matching failed, so the song is saved with Deezer's search page.
        assertEquals(false, HistoryStore(prefs()).load().single().destinationLinks["DEEZER"]?.exact)
        controller!!.pause().stop().destroy()

        // Shared again once the network is back, it opens the song itself rather than the search.
        deezerDown = false
        val activity = launch(trackLink())
        waitUntil { activity.isFinishing }
        assertEquals("https://www.deezer.com/track/123", nextStartedActivity()!!.dataString)
        assertEquals(true, HistoryStore(prefs()).load().single().destinationLinks["DEEZER"]?.exact)
    }

    @Test
    fun aSearchPageSavedWithMatchingOffIsStillReused() {
        prefs().edit().putString("default_target", "DEEZER").commit()
        respondWithTrack("Again", "Artist · Song")
        launch()
        resolveTyped()
        waitForDestinationReady()
        controller!!.pause().stop().destroy()
        val requests = fake.requestedUrls.size
        fake.handler = { throw IOException("offline") }

        val activity = launch(trackLink())
        waitUntil { activity.isFinishing }
        assertEquals(HistoryStore(prefs()).load().single().destinationLinks.getValue("DEEZER").url, nextStartedActivity()!!.dataString)
        assertEquals(requests, fake.requestedUrls.size)
    }

    @Test
    fun movingAWebFrontendToAnotherSiteOpensSavedSongsThere() {
        prefs().edit().putString("default_target", "frontend:INVIDIOUS").commit()
        respondWithTrack("Again", "Artist · Song")
        val first = launch()
        resolveTyped()
        waitForDestinationReady()
        val other = HistoryEntry(
            MusicLink(MusicService.SPOTIFY, ItemType.TRACK, OTHER_TRACK_ID, "https://open.spotify.com/track/$OTHER_TRACK_ID"),
            MusicMetadata("Other", "Artist"),
            mapOf("YOUTUBE_MUSIC" to PreparedLink("https://music.youtube.com/watch?v=kept", exact = true, matchingEnabled = false))
        )
        HistoryStore(prefs()).remember(TRACK_ID.let { "https://open.spotify.com/track/$it" }, "YOUTUBE_MUSIC", other.destinationLinks.getValue("YOUTUBE_MUSIC"))
        assertTrue(HistoryStore(prefs()).load().single().destinationLinks.getValue("frontend:INVIDIOUS").url.startsWith("https://yewtu.be/"))

        composeRule.runOnIdle { assertTrue(ViewModelProvider(first)[MainViewModel::class.java].setFrontendInstance(Frontend.INVIDIOUS, "https://inv.example.org")) }
        composeRule.waitForIdle()
        // Links saved for other destinations stay; the open song's Invidious link is made again on the new site.
        val saved = HistoryStore(prefs()).load().single().destinationLinks
        assertEquals("https://music.youtube.com/watch?v=kept", saved.getValue("YOUTUBE_MUSIC").url)
        saved["frontend:INVIDIOUS"]?.let { assertTrue(it.url, it.url.startsWith("https://inv.example.org/")) }
        controller!!.pause().stop().destroy()

        // A browser to open the new site in.
        installActivity(ComponentName("com.example.browser", "com.example.browser.Main"), browserFilter())
        val activity = launch(trackLink())
        waitUntil { activity.isFinishing }
        assertTrue(nextStartedActivity()!!.dataString!!.startsWith("https://inv.example.org/"))
    }

    @Test
    fun thePickerWaitsForTheInstalledAppsItListsFirst() {
        val android = QueueDispatcher()
        MainActivity.systemDispatcher = android
        linksAskWhere()
        HistoryStore(prefs()).add(
            HistoryEntry(
                MusicLink(MusicService.SPOTIFY, ItemType.TRACK, TRACK_ID, "https://open.spotify.com/track/$TRACK_ID"),
                MusicMetadata("Saved Song", "Artist")
            )
        )
        launch(trackLink())

        // The song comes from Recent at once, but its rows would move once Android says what's installed.
        assertTextAbsent(string(R.string.picker_title))
        android.runAll()
        composeRule.waitForIdle()
        assertTextShown(string(R.string.picker_title))
    }

    @Test
    fun aShortLinkIsStillFollowedWhenWhereItLeadsIsInRecent() {
        fake.handler = { request ->
            if (request.url.host == "spotify.link") {
                FakeSpotify.html(request, "", finalUrl = "https://open.spotify.com/track/$TRACK_ID")
            } else {
                FakeSpotify.html(request, FakeSpotify.trackPage("Renamed Song", "Artist · Song"))
            }
        }
        HistoryStore(prefs()).add(
            HistoryEntry(
                MusicLink(MusicService.SPOTIFY, ItemType.TRACK, TRACK_ID, "https://open.spotify.com/track/$TRACK_ID"),
                MusicMetadata("Old Name", "Artist")
            )
        )
        launch()
        typeUrl("https://spotify.link/AbCdEf")
        click(string(R.string.resolve_button))
        waitForText("Renamed Song")
        assertEquals(listOf("https://spotify.link/AbCdEf", "https://open.spotify.com/track/$TRACK_ID"), fake.requestedUrls)
    }

    // endregion

    // region updates

    /** GitHub has Crosstune 99.0.0 out, with its APK. */
    private fun newerReleaseOnGitHub() {
        MainActivity.checksForUpdates = { true }
        prefs().edit().putBoolean("link_settings_helper_dismissed", true).commit()
        fake.handler = { request ->
            if (request.url.host == "api.github.com") {
                FakeSpotify.html(request, """{"tag_name":"v99.0.0","assets":[{"name":"Crosstune-v99.0.0.apk","browser_download_url":"$UPDATE_APK"}]}""")
            } else {
                FakeSpotify.html(request, "")
            }
        }
    }

    @Test
    fun aNewerCrosstuneIsOneTapFromDownloading() {
        newerReleaseOnGitHub()
        installActivity(ComponentName("com.example.browser", "com.example.browser.Browser"), browserFilter())
        launch()
        waitForText(string(R.string.notice_update, "99.0.0"))
        click(string(R.string.update_button))
        // The browser downloads it, and Crosstune stays open.
        val opened = nextStartedActivity()!!
        assertEquals(UPDATE_APK, opened.dataString)
        assertFalse(controller!!.get().isFinishing)
    }

    @Test
    fun aClosedUpdateNoticeStaysClosedForThatVersion() {
        newerReleaseOnGitHub()
        launch()
        waitForText(string(R.string.notice_update, "99.0.0"))
        composeRule.onNodeWithContentDescription(string(R.string.dismiss_button)).performClick()
        composeRule.waitForIdle()
        assertTextAbsent(string(R.string.notice_update, "99.0.0"))
        // Settings says nothing of it either.
        click(string(R.string.settings_button))
        assertTextAbsent(string(R.string.notice_update, "99.0.0"))
        click(string(R.string.back_button))

        controller!!.pause().stop().destroy()
        launch()
        composeRule.waitForIdle()
        assertTextAbsent(string(R.string.notice_update, "99.0.0"))
    }

    @Test
    fun aCopyAnAppStoreUpdatesNeverAsksGitHub() {
        newerReleaseOnGitHub()
        MainActivity.checksForUpdates = { false }
        launch()
        composeRule.waitForIdle()
        assertTextAbsent(string(R.string.notice_update, "99.0.0"))
        assertTrue(fake.requestedUrls.none { "api.github.com" in it })
    }

    // endregion

    private companion object {
        const val UPDATE_APK = "https://github.com/astrovm/crosstune/releases/download/v99.0.0/Crosstune-v99.0.0.apk"
        const val TRACK_ID = "11dFghVXANMlKmJXsNCbNl"
        const val OTHER_TRACK_ID = "0VjIjW4GlUZAMYd2vXMi3b"
        const val TIMEOUT_MS = 5_000L
        const val NOW_PLAYING_SHARE = "A Song by Example Band\nhttps://www.google.com/search?q=A+Song+by+Example+Band"
    }
}
