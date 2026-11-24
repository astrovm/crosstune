package com.astrovm.crosstune

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsDisplayed
import org.robolectric.RuntimeEnvironment
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Crosstune on a TV: nowhere to allow links in, and a remote rather than a finger. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp")
@OptIn(ExperimentalTestApi::class)
class TvTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeSpotify()
    private var controller: ActivityController<MainActivity>? = null
    private var phone = Phone.TV

    /** Put back after, so other tests still tell the phone as the app does. */
    private val realPhone = MainActivity.phoneFactory

    @Before
    fun setUp() {
        MainActivity.httpClientFactory = { fake.client() }
        MainActivity.systemDispatcher = Dispatchers.Unconfined
        MainActivity.lookupDispatcher = Dispatchers.Unconfined
        MainActivity.phoneFactory = { phone }
        MainActivity.checksForUpdates = { false }
        MainActivity.shellFactory = { emptyList() }
        prefs().edit().clear().commit()
    }

    @After
    fun tearDown() {
        controller?.destroyAndSettle()
        MainActivity.httpClientFactory = ::httpClient
        MainActivity.systemDispatcher = Dispatchers.Default
        MainActivity.lookupDispatcher = Dispatchers.IO
        MainActivity.phoneFactory = realPhone
        MainActivity.checksForUpdates = ::looksForUpdates
        MainActivity.shellFactory = { listOf(ShizukuShell(), RootShell()) }
        MainActivity.playbackFactory = ::MediaSessionPlayback
        MainActivity.hearingFactory = null
    }

    private fun prefs() = app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun string(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun launch() {
        controller = Robolectric.buildActivity(MainActivity::class.java, Intent(app, MainActivity::class.java)).setup()
        composeRule.waitForIdle()
    }

    private fun shown(text: String) = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun field() = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))

    private fun fieldText() = field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun settings() = composeRule.onNode(hasContentDescription(string(R.string.settings_button)))

    private fun press(key: Key) {
        field().performKeyInput { pressKey(key) }
        composeRule.waitForIdle()
    }

    @Test
    fun aTvOpensNoLinksAndNeverAsksToAllowThem() {
        launch()
        assertEquals(emptyList<MusicService>(), MusicService.entries.filter { LinkInterception(app).isEnabled(it) })
        // Following the music is still asked; links, which a TV has nowhere to allow, aren't.
        assertTrue(shown(string(R.string.ask_follow_gets)))
        assertTrue(!shown(string(R.string.ask_links_gets)))

        // Nor are they in settings.
        composeRule.onNode(hasContentDescription(string(R.string.settings_button))).performClick()
        composeRule.waitForIdle()
        assertTrue(shown(string(R.string.setting_show_in_crosstune_first)))
        assertTrue(!shown(string(R.string.settings_links_title)))
    }

    @Test
    fun onATvFollowingMusicIsTurnedOnFromTheListOfApps() {
        launch()
        composeRule.onNode(hasText(string(R.string.allow_button)) and hasClickAction()).performClick()
        composeRule.waitForIdle()
        // Google TV's own page, opened straight away, can leave the remote stuck, as on the Chromecast.
        assertTrue(shown(string(R.string.fix_step_pick, string(R.string.android_special_app_access))))
        assertTrue(shown(string(R.string.fix_step_pick, string(R.string.android_notification_access))))
        assertTrue(shown(string(R.string.fix_step_turn_on, string(R.string.app_name))))
        composeRule.onNode(hasText(string(R.string.fix_open_settings))).performClick()
        composeRule.waitForIdle()
        assertEquals(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, shadowOf(app).nextStartedActivity.action)
    }

    @Test
    fun onATvFollowingMusicIsTurnedOffFromTheListOfAppsToo() {
        MainActivity.playbackFactory = { FakePlayback().apply { access = true } }
        launch()
        composeRule.onNode(hasContentDescription(string(R.string.settings_button))).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(string(R.string.setting_lyrics_follow)).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, shadowOf(app).nextStartedActivity.action)
    }

    @Test
    fun onATvTheLinkFieldIsTypedInOncePressed() {
        launch()
        field().requestFocus()
        // The remote on it, the keyboard waits: it can't be typed in yet.
        field().assert(!hasSetTextAction())
        press(Key.DirectionCenter)
        field().performTextInput("never gonna")
        assertEquals("never gonna", fieldText())
        assertTrue(shown(string(R.string.search_button)))

        // A keyboard plugged in still types in it.
        press(Key.Backspace)
        assertEquals("never gonn", fieldText())

        // Up and down leave it, typing or not, and coming back it waits again.
        press(Key.DirectionDown)
        field().assertIsNotFocused()
        settings().assertIsNotFocused()
        field().requestFocus()
        field().assert(!hasSetTextAction())
        press(Key.DirectionUp)
        composeRule.onNode(hasContentDescription(string(R.string.tile_lyrics_label))).assertIsFocused()
    }

    @Test
    fun onATvLeftAndRightReachTheLinkFieldsButtons() {
        launch()
        field().requestFocus()
        press(Key.DirectionRight)
        val recognize = composeRule.onNode(hasContentDescription(string(R.string.recognize_button)))
        recognize.assertIsFocused()
        // On from one button to the next, which are pressed rather than typed in.
        recognize.performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.waitForIdle()
        val paste = composeRule.onNode(hasContentDescription(string(R.string.paste_button)))
        paste.assertIsFocused()
        field().assert(!hasSetTextAction())
        paste.performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.waitForIdle()
        recognize.assertIsFocused()
        recognize.performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.waitForIdle()
        field().assertIsFocused()
        // With a link in it, its one button is clear, there and back too.
        press(Key.DirectionCenter)
        field().performTextInput("ab")
        press(Key.DirectionDown)
        field().requestFocus()
        press(Key.DirectionRight)
        val clear = composeRule.onNode(hasContentDescription(string(R.string.clear_button)))
        clear.assertIsFocused()
        clear.performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.waitForIdle()
        field().assertIsFocused()

        // Typing, they move its cursor instead.
        press(Key.DirectionCenter)
        press(Key.DirectionLeft)
        field().assertIsFocused()
        press(Key.DirectionRight)
        field().assertIsFocused()
    }

    @Test
    fun onATvTheKeyboardShowsOnlyOnceTheLinkFieldIsPressed() {
        var shows = 0
        val keyboard = object : SoftwareKeyboardController {
            override fun show() { shows++ }
            override fun hide() {}
        }
        val screen = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        screen.get().setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                CrosstuneTheme { CrosstuneScreen(UiState(phone = Phone.TV), ScreenActions()) }
            }
        }
        composeRule.waitForIdle()
        field().requestFocus()
        composeRule.waitForIdle()
        assertEquals(0, shows)
        press(Key.DirectionCenter)
        assertTrue(shows > 0)
        screen.pause().stop().destroy()
    }

    @Test
    fun onATvUpAndDownMoveOnFromASliderRatherThanSlideIt() {
        launch()
        composeRule.onNode(hasContentDescription(string(R.string.settings_button))).performClick()
        composeRule.waitForIdle()
        val slider = composeRule.onNode(hasContentDescription(string(R.string.setting_visuals_change)))
        fun shown() = slider.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        val before = shown()
        slider.performScrollTo().requestFocus()
        slider.performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.waitForIdle()
        slider.assertIsNotFocused()
        assertEquals(before, shown())

        // Left and right still slide it.
        slider.requestFocus()
        slider.performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.waitForIdle()
        slider.assertIsFocused()
        assertNotEquals(before, shown())
    }

    @Test
    fun onAPhoneTheLinkFieldTakesTypingAndKeysAsEver() {
        phone = Phone.PIXEL
        prefs().edit().putBoolean("setup_complete", true).commit()
        launch()
        field().requestFocus()
        field().performTextInput("never gonna")
        press(Key.DirectionDown)
        field().assertIsFocused()
        assertEquals("never gonna", fieldText())
        field().assert(hasText("never gonna"))
    }

    @Test
    fun onATvTheSongsANameTurnsUpOpenOnTheFirstSoOkPicksIt() {
        // They come in after the picker opens, as they do over a real network, with Dismiss all there is meanwhile.
        val found = CountDownLatch(1)
        // The song picked is looked up after the picker's gone, and the field's busy till then.
        val looked = CountDownLatch(1)
        fake.handler = { request ->
            when {
                request.url.encodedPath.startsWith("/search") -> {
                    found.await(5, TimeUnit.SECONDS)
                    FakeSpotify.html(
                        request,
                        """{"data":[{"id":1,"title":"Machi No Dorufin","link":"https://www.deezer.com/track/1","artist":{"name":"Kingo Hamada"}},""" +
                            """{"id":2,"title":"Machi No Dorufin (Live)","link":"https://www.deezer.com/track/2","artist":{"name":"Kingo Hamada"}}]}"""
                    )
                }
                request.url.encodedPath.startsWith("/track/") -> {
                    looked.await(5, TimeUnit.SECONDS)
                    FakeSpotify.html(request, """{"id":1,"title":"Machi No Dorufin","link":"https://www.deezer.com/track/1","artist":{"name":"Kingo Hamada"}}""")
                }
                else -> FakeSpotify.html(request, """{"data":[],"contents":[]}""")
            }
        }
        launch()
        field().requestFocus()
        press(Key.DirectionCenter)
        field().performTextInput("Kingo Hamada")
        field().performImeAction()
        composeRule.waitUntil(5_000) { shown(string(R.string.song_search_title)) }
        assertTrue(!shown("Machi No Dorufin (Live)"))
        found.countDown()
        composeRule.waitUntil(5_000) { shown("Machi No Dorufin (Live)") }
        composeRule.waitForIdle()
        val first = composeRule.onNode(hasText("Machi No Dorufin") and hasClickAction() and hasAnyAncestor(isDialog()))
        first.assertIsFocused()
        first.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitUntil(5_000) { !shown("Machi No Dorufin (Live)") && fake.requestedUrls.any { it.contains("/track/1") } }
        looked.countDown()
        // Back on the field once the song's looked up, as after a link typed.
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitForIdle()
        field().assertIsFocused()

        // Only the once: a song looked up later from elsewhere leaves the remote where it is.
        val down = AtomicBoolean(true)
        // Looked up again, it takes a moment, as over a real network, so the screen sees it busy.
        val again = CountDownLatch(1)
        val searched = fake.handler
        fake.handler = { request ->
            if (down.get()) FakeSpotify.html(request, "", code = 503) else searched(request.also { again.await(5, TimeUnit.SECONDS) })
        }
        composeRule.onNode(hasContentDescription(string(R.string.clear_button))).performClick()
        field().requestFocus()
        press(Key.DirectionCenter)
        field().performTextInput("https://www.deezer.com/track/3")
        field().performImeAction()
        val retry = composeRule.onNode(hasContentDescription(string(R.string.retry_button)))
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasContentDescription(string(R.string.retry_button))).fetchSemanticsNodes().isNotEmpty() }
        down.set(false)
        // The remote on something that stays, rather than Try again, which goes, and with it the remote, anywhere.
        settings().requestFocus()
        retry.performClick()
        composeRule.waitUntil(5_000) { shown(string(R.string.loading_text)) }
        again.countDown()
        composeRule.waitUntil(5_000) { shown("Kingo Hamada") }
        composeRule.waitForIdle()
        settings().assertIsFocused()
    }

    @Test
    fun onATvADialogOpensInTheScreenWithTheRemoteInItAndBackPutsItAwayWhereItWas() {
        launch()
        settings().performClick()
        composeRule.waitForIdle()
        val report = composeRule.onNode(hasText(string(R.string.report_problem)) and hasClickAction())
        // The remote's, as a TV is: Robolectric starts as a touch screen, where rows take no focus.
        composeRule.onRoot().performKeyInput { pressKey(Key.ShiftLeft) }
        report.performScrollTo().requestFocus()
        report.performClick()
        composeRule.waitForIdle()
        // In the screen, not a window of its own, which a TV's cover over another app can't open.
        val email = composeRule.onNode(hasText(string(R.string.report_by_email)) and hasAnyAncestor(isDialog()))
        email.assertIsFocused()
        // The remote stays in it, as what's under it is out of reach.
        email.performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.waitForIdle()
        email.assertIsFocused()
        email.performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.waitForIdle()
        composeRule.onNode(hasText(string(R.string.report_on_github)) and hasAnyAncestor(isDialog())).assertIsFocused()

        checkNotNull(controller) { "Expected controller to be non-null" }.get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        assertTrue(!shown(string(R.string.report_on_github)))
        // Still in settings, and the remote where it was.
        assertTrue(shown(string(R.string.setting_show_in_crosstune_first)))
        report.assertIsFocused()

        // A tap beside it puts it away too.
        report.performClick()
        composeRule.waitForIdle()
        assertTrue(shown(string(R.string.report_on_github)))
        composeRule.onRoot().performTouchInput { click(Offset(2f, 2f)) }
        composeRule.waitForIdle()
        assertTrue(!shown(string(R.string.report_on_github)))
    }

    @Test
    fun onAPhoneADialogIsStillAWindowOfItsOwn() {
        phone = Phone.PIXEL
        prefs().edit().putBoolean("setup_complete", true).commit()
        launch()
        settings().performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasText(string(R.string.report_problem)) and hasClickAction()).performScrollTo().performClick()
        composeRule.waitForIdle()
        // Its own window has its own tree, over the screen's.
        assertEquals(2, composeRule.onAllNodes(isRoot()).fetchSemanticsNodes().size)
    }

    @Test
    fun onATvTheAppsToOpenASongInOpenOnTheFirst() {
        // There as the picker opens, unlike songs still being looked up, so the first is where the remote starts.
        prefs().edit().putString("default_target", "SPOTIFY").commit()
        fake.handler = { request -> FakeSpotify.html(request, FakeSpotify.trackPage("Mine", "Artist · Song")) }
        controller = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "https://open.spotify.com/track/11dFghVXANMlKmJXsNCbNl")
            }
        ).setup()
        composeRule.waitUntil(5_000) { shown(string(R.string.picker_title)) }
        composeRule.waitForIdle()
        val choices = composeRule.onAllNodes(hasAnyAncestor(isDialog()) and hasClickAction()).fetchSemanticsNodes()
        assertEquals(true, choices.first().config.getOrElseNullable(SemanticsProperties.Focused) { null })
    }

    /** Settings on the remote's terms: Robolectric starts as a touch screen, where rows take no focus. */
    private fun settingsByRemote() {
        launch()
        settings().performClick()
        composeRule.waitForIdle()
        composeRule.onRoot().performKeyInput { pressKey(Key.ShiftLeft) }
    }

    private fun focused() = composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true))

    private fun pressOnFocused(key: Key) {
        focused().performKeyInput { pressKey(key) }
        composeRule.waitForIdle()
    }

    @Test
    fun onATvTheLicensesStartOnTheFirstWorkStepThroughItsLicenceAndBackLandsOnTheirRow() {
        settingsByRemote()
        val licenses = composeRule.onNode(hasText(string(R.string.settings_licenses)) and hasClickAction())
        licenses.performScrollTo().requestFocus()
        pressOnFocused(Key.DirectionCenter)
        val jmdict = composeRule.onNode(hasText("JMdict") and hasClickAction())
        jmdict.assertIsFocused()

        // Opened, the remote steps down to where it is, then through the licence a piece at a time.
        pressOnFocused(Key.DirectionCenter)
        pressOnFocused(Key.DirectionDown)
        composeRule.onNode(hasText("www.edrdg.org/dokuwiki/doku.php?id=wiki_index.php_jmdict-edict_dictionary_project") and hasClickAction()).assertIsFocused()
        val licence = licenseUnits(Notices.text(Notices.all.first())).map { it.text }
        repeat(30) { pressOnFocused(Key.DirectionDown) }
        val at = focused().fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        assertEquals(licence[29], at)
        // Scrolled with it, far from the top: the list no longer draws the first work.
        assertTrue(composeRule.onAllNodes(hasText("JMdict") and hasClickAction()).fetchSemanticsNodes().isEmpty())

        // Back closes the open licence and returns to its row. The next Back leaves the page.
        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        jmdict.assertIsFocused()
        assertTrue(composeRule.onAllNodesWithText("ELECTRONIC DICTIONARY RESEARCH AND DEVELOPMENT GROUP").fetchSemanticsNodes().isEmpty())
        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        licenses.assertIsFocused()
    }

    private fun licensesByRemote() {
        settingsByRemote()
        composeRule.onNode(hasText(string(R.string.settings_licenses)) and hasClickAction()).performScrollTo().requestFocus()
        pressOnFocused(Key.DirectionCenter)
    }

    private fun list() = composeRule.onNode(hasScrollToNodeAction())

    private fun scrollTo(matcher: SemanticsMatcher) {
        list().performScrollToNode(matcher)
        composeRule.waitForIdle()
    }

    @Test
    fun onATvAtTheLargestFontEveryWorkAndEachPieceOfTheLongestLicencesFitsTheScreen() {
        RuntimeEnvironment.setFontScale(2f)
        try {
            licensesByRemote()
            val screen = list().fetchSemanticsNode().boundsInRoot.height
            Notices.all.forEach { notice ->
                val row = hasText(notice.name) and hasClickAction()
                scrollTo(row)
                assertTrue(notice.name, composeRule.onNode(row).fetchSemanticsNode().boundsInRoot.height <= screen)
            }
            // The pieces that take the most lines, each in its work, opened.
            val tallest = Notices.all.flatMap { notice -> licenseUnits(Notices.text(notice)).map { notice to it.text } }
                .sortedByDescending { (_, text) -> text.lines().sumOf { it.length / 40 + 1 } }.take(3)
            tallest.forEach { (notice, text) ->
                val row = hasText(notice.name) and hasClickAction()
                scrollTo(row)
                composeRule.onNode(row).performClick()
                composeRule.waitForIdle()
                val piece = SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString(text)))
                scrollTo(piece)
                assertTrue(text.take(60), composeRule.onNode(piece).fetchSemanticsNode().boundsInRoot.height <= screen)
                scrollTo(row)
                composeRule.onNode(row).performClick()
                composeRule.waitForIdle()
            }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    @Test
    fun onATvTheLicensesMadeAgainKeepTheWorkOpenWhereItWasAndWhatTheRemoteWasOn() {
        licensesByRemote()
        // A later work, opened and read a way in.
        val work = Notices.all.first { it.name == "projectM" }
        val row = hasText(work.name) and hasClickAction()
        scrollTo(row)
        composeRule.onNode(row).requestFocus()
        pressOnFocused(Key.DirectionCenter)
        repeat(12) { pressOnFocused(Key.DirectionDown) }
        val reading = focused().fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        assertTrue(reading in licenseUnits(Notices.text(work)).map { it.text })

        controller = checkNotNull(controller).recreate()
        composeRule.waitForIdle()

        // Still open, still there, the remote still on what it was on, not thrown back to the first work.
        val piece = SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString(reading)))
        composeRule.onNode(piece).assertIsDisplayed().assertIsFocused()
        assertTrue(composeRule.onAllNodes(hasText("JMdict") and hasClickAction() and isFocused()).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun onATvTheLicensesMadeAgainAfterAndroidStoppedTheAppPutTheRemoteBackOnWhatItWasOn() {
        licensesByRemote()
        val row = hasText("projectM") and hasClickAction()
        scrollTo(row)
        composeRule.onNode(row).requestFocus()
        pressOnFocused(Key.DirectionCenter)
        repeat(12) { pressOnFocused(Key.DirectionDown) }
        val reading = focused().fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }

        // What was split is gone with the app; the page's state comes back, and the text is split again.
        ViewModelProvider(checkNotNull(controller).get())[LicenseCacheOwner::class.java].leave()
        controller = checkNotNull(controller).recreate()
        composeRule.waitForIdle()

        val piece = SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString(reading)))
        composeRule.onNode(piece).assertIsDisplayed().assertIsFocused()
    }

    @Test
    fun onATvBackFromInsideALongLicenceClosesItAndLandsOnItsRowThenLeavesThePage() {
        licensesByRemote()
        val row = hasText("projectM") and hasClickAction()
        scrollTo(row)
        composeRule.onNode(row).requestFocus()
        pressOnFocused(Key.DirectionCenter)
        repeat(20) { pressOnFocused(Key.DirectionDown) }
        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText(string(R.string.settings_about_title)).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("GNU LESSER GENERAL PUBLIC LICENSE").fetchSemanticsNodes().isEmpty())
        composeRule.onNode(row).assertIsFocused()

        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        assertTrue(shown(string(R.string.settings_about_title)))
        composeRule.onNode(hasText(string(R.string.settings_licenses)) and hasClickAction()).assertIsFocused()
    }

    @Test
    fun onATvBackClosesTheLicenceTheRemoteIsInOrTheLastOneOpened() {
        licensesByRemote()
        val jmdict = hasText("JMdict") and hasClickAction()
        composeRule.onNode(jmdict).requestFocus()
        pressOnFocused(Key.DirectionCenter)
        pressOnFocused(Key.DirectionDown)
        pressOnFocused(Key.DirectionDown)
        val piece = focused().fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        val kuromoji = hasText("Kuromoji") and hasClickAction()
        scrollTo(kuromoji)
        composeRule.onNode(kuromoji).requestFocus()
        pressOnFocused(Key.DirectionCenter)
        val pieceNode = SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString(piece)))
        scrollTo(pieceNode)
        composeRule.onNode(pieceNode).requestFocus()
        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodes(pieceNode).fetchSemanticsNodes().isEmpty())
        composeRule.onNode(jmdict).assertIsFocused()
        // The heading sits inside a longer piece, so the match is a part of that piece, not the whole of it.
        val heading = hasText("Kuromoji Japanese Morphological Analyzer", substring = true)
        scrollTo(heading)
        assertTrue(composeRule.onAllNodes(heading).fetchSemanticsNodes().isNotEmpty())

        val ipadic = hasText("IPADIC") and hasClickAction()
        scrollTo(ipadic)
        composeRule.onNode(ipadic).requestFocus()
        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodes(heading).fetchSemanticsNodes().isEmpty())
        composeRule.onNode(kuromoji).assertIsFocused()
    }

    @Test
    fun onATvBackFromAPageLandsOnTheRowThatOpenedIt() {
        settingsByRemote()
        val advanced = composeRule.onNode(hasText(string(R.string.settings_advanced_title)) and hasClickAction())
        advanced.performScrollTo().requestFocus()
        pressOnFocused(Key.DirectionCenter)
        assertTrue(shown(string(R.string.settings_custom_title)))
        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        advanced.assertIsFocused()

        val visuals = composeRule.onNode(hasText(string(R.string.setting_visuals_list)) and hasClickAction())
        visuals.performScrollTo().requestFocus()
        pressOnFocused(Key.DirectionCenter)
        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        visuals.assertIsFocused()
    }

    /** The rows of things to fix, each opening its steps; their click is labelled Fix. */
    private fun fixRows() = composeRule.onAllNodes(SemanticsMatcher("opens its steps") { it.config.getOrElseNullable(SemanticsActions.OnClick) { null }?.label == string(R.string.fix_button) })

    @Test
    fun onATvBackFromAFixStepLandsOnTheRowThatOpenedIt() {
        launch()
        composeRule.onRoot().performKeyInput { pressKey(Key.ShiftLeft) }
        composeRule.runOnIdle { model().openFixes() }
        composeRule.waitUntil(5_000) { shown(string(R.string.fix_title)) && fixRows().fetchSemanticsNodes().isNotEmpty() }
        val row = fixRows()[0]
        val title = row.fetchSemanticsNode().config[SemanticsProperties.Text].first().text
        row.requestFocus()
        pressOnFocused(Key.DirectionCenter)
        composeRule.waitUntil(5_000) { fixRows().fetchSemanticsNodes().isEmpty() }
        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitUntil(5_000) { fixRows().fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitForIdle()
        composeRule.onNode(hasText(title, substring = true) and hasClickAction()).assertIsFocused()
    }

    @Test
    fun onATvBackFromTheFixListToSettingsLandsOnTheSettingTheRemoteWasOn() {
        settingsByRemote()
        val follow = composeRule.onNode(hasText(string(R.string.setting_lyrics_follow)))
        follow.performScrollTo().requestFocus()
        // The fix list is as deep as settings, and goes back to it when opened over it.
        composeRule.runOnIdle { model().openFixes() }
        composeRule.waitUntil(5_000) { shown(string(R.string.fix_title)) && !shown(string(R.string.setting_lyrics_follow)) }
        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitUntil(5_000) { shown(string(R.string.setting_lyrics_follow)) }
        composeRule.waitForIdle()
        follow.assertIsFocused()
    }

    @Test
    fun onATvBackLandsOnTheWordsButtonThatOpenedAScreenNotOnSettingsPassedBefore() {
        launch()
        composeRule.onRoot().performKeyInput { pressKey(Key.ShiftLeft) }
        val words = composeRule.onNode(hasContentDescription(string(R.string.tile_lyrics_label)))
        settings().requestFocus()
        words.requestFocus()
        // As the words of what's playing open a screen, by way of listening or the microphone's steps.
        composeRule.runOnIdle { model().openFixes() }
        val onMain = { composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitUntil(5_000) { !onMain() }
        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitUntil(5_000) { onMain() }
        composeRule.waitForIdle()
        words.assertIsFocused()
    }

    /** Back on the main screen from the fix list with songs looked up by name meanwhile: their picker shows there, and keeps the remote. */
    private fun backToThePicker(): SemanticsNodeInteraction {
        launch()
        composeRule.onRoot().performKeyInput { pressKey(Key.ShiftLeft) }
        val words = composeRule.onNode(hasContentDescription(string(R.string.tile_lyrics_label)))
        words.requestFocus()
        composeRule.runOnIdle { model().openFixes() }
        val onMain = { composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitUntil(5_000) { !onMain() }
        composeRule.runOnIdle { model().searchSongs("moon") }
        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitUntil(5_000) { onMain() && shown(string(R.string.song_search_title)) }
        composeRule.waitForIdle()
        words.assertIsNotFocused()
        return words
    }

    @Test
    fun onATvTheSongPickerPutAwayOnTheScreenComeBackGivesTheRemoteToTheField() {
        val words = backToThePicker()
        // Its own rule, the field as after a link, rather than what the remote was on before the fix list.
        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitUntil(5_000) { !shown(string(R.string.song_search_title)) }
        composeRule.waitForIdle()
        field().assertIsFocused()
        words.assertIsNotFocused()
    }

    @Test
    fun onATvASongPickedOnTheScreenComeBackGivesTheRemoteToTheField() {
        val words = backToThePicker()
        composeRule.runOnIdle { model().chooseSong(MusicMetadata("Moon", "Band")) }
        composeRule.waitUntil(5_000) { !shown(string(R.string.song_search_title)) && !model().uiState.isLoading }
        composeRule.waitForIdle()
        field().assertIsFocused()
        words.assertIsNotFocused()
    }

    @Test
    fun onATvBackFromPracticeLandsOnTheRowThatStartedIt() {
        prefs().edit().putString("saved_lines", """[{"text":"夜の空","translation":"Night sky","title":"Demo","artist":"Band","interval":3,"due":1}]""").commit()
        launch()
        composeRule.onRoot().performKeyInput { pressKey(Key.ShiftLeft) }
        val practice = composeRule.onNode(hasContentDescription(string(R.string.saved_practice), substring = true) and hasClickAction())
        practice.requestFocus()
        pressOnFocused(Key.DirectionCenter)
        val all = hasText(string(R.string.practice_all_songs)) and hasClickAction()
        val song = hasText("Demo") and hasClickAction()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(all).fetchSemanticsNodes().isNotEmpty() }
        for (row in listOf(song, all)) {
            composeRule.onNode(row).requestFocus()
            pressOnFocused(Key.DirectionCenter)
            composeRule.waitUntil(5_000) { model().uiState.practice != null && composeRule.onAllNodes(all).fetchSemanticsNodes().isEmpty() }
            checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
            composeRule.waitUntil(5_000) { composeRule.onAllNodes(all).fetchSemanticsNodes().isNotEmpty() }
            composeRule.waitForIdle()
            composeRule.onNode(row).assertIsFocused()
        }
    }

    @Test
    fun onATvRightAndLeftGoBetweenAPracticeSongAndItsPencil() {
        prefs().edit().putString("saved_lines", """[{"text":"夜の空","translation":"Night sky","title":"Demo","artist":"Band","interval":3,"due":1}]""").commit()
        launch()
        composeRule.onRoot().performKeyInput { pressKey(Key.ShiftLeft) }
        composeRule.onNode(hasContentDescription(string(R.string.saved_practice), substring = true) and hasClickAction()).requestFocus()
        pressOnFocused(Key.DirectionCenter)
        val all = composeRule.onNode(hasText(string(R.string.practice_all_songs)) and hasClickAction())
        val song = composeRule.onNode(hasText("Demo") and hasClickAction())
        val pencil = composeRule.onNode(hasContentDescription(string(R.string.choose_lines)) and hasClickAction())
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Demo") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        // All songs has no pencil, so the remote stays, and Down, not the rows' to take, still goes to the song.
        all.requestFocus()
        pressOnFocused(Key.DirectionRight)
        all.assertIsFocused()
        pressOnFocused(Key.DirectionDown)
        song.assertIsFocused()
        pressOnFocused(Key.DirectionRight)
        pencil.assertIsFocused()
        pressOnFocused(Key.DirectionLeft)
        song.assertIsFocused()
        pressOnFocused(Key.DirectionRight)
        pressOnFocused(Key.DirectionCenter)
        composeRule.waitUntil(5_000) { shown("夜の空") }
        // Put away, the sheet gives the remote back to the pencil that opened it, not to the screen's Back.
        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitUntil(5_000) { !shown("夜の空") }
        pencil.assertIsFocused()
    }

    @Test
    fun onATvLettingGoOfASongsLastLineLeavesTheRemoteInPractice() {
        prefs().edit().putString(
            "saved_lines",
            """[{"text":"夜の空","translation":"Night sky","title":"Demo","artist":"Band","interval":3,"due":1},""" +
                """{"text":"朝の海","translation":"Morning sea","title":"Other","artist":"Group","interval":3,"due":1}]"""
        ).commit()
        launch()
        composeRule.onRoot().performKeyInput { pressKey(Key.ShiftLeft) }
        composeRule.onNode(hasContentDescription(string(R.string.saved_practice), substring = true) and hasClickAction()).requestFocus()
        pressOnFocused(Key.DirectionCenter)
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Demo") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(hasText("Demo") and hasClickAction()).requestFocus()
        pressOnFocused(Key.DirectionRight)
        pressOnFocused(Key.DirectionCenter)
        composeRule.waitUntil(5_000) { shown("夜の空") }
        composeRule.onNode(hasText("夜の空")).performClick()
        composeRule.onNode(hasText(string(R.string.save_button)) and hasClickAction()).performClick()
        composeRule.waitUntil(5_000) { !shown("Demo") }
        // Its pencil went with it: the remote goes to the screen, on Practice's Back, rather than nowhere.
        assertTrue(shown("Other"))
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() }
        button(R.string.back_button).assertIsFocused()
    }

    @Test
    fun onATvASongOpenedFromRecentHandsTheRemoteToItsMainButton() {
        for (n in 1..3) {
            val url = "https://open.spotify.com/track/song$n"
            HistoryStore(prefs()).add(HistoryEntry(MusicLink(MusicService.SPOTIFY, ItemType.TRACK, "song$n", url), MusicMetadata("Song $n", "Band")))
        }
        launch()
        // The middle one, so rows stay above and below where it was, for Android to move the remote to.
        composeRule.onNode(hasText("Song 2") and hasClickAction()).performScrollTo().requestFocus()
        pressOnFocused(Key.DirectionCenter)
        composeRule.waitUntil(5_000) { model().uiState.result?.title == "Song 2" && !model().uiState.isMatching }
        composeRule.waitForIdle()
        focused().assert(hasAnyAncestor(hasTestTag(RESULT_TAG)) and hasClickAction())
    }

    @Test
    fun onATvBackFromASongOpenedFromRecentLandsOnItsRow() {
        for (n in 1..3) {
            val url = "https://open.spotify.com/track/song$n"
            HistoryStore(prefs()).add(HistoryEntry(MusicLink(MusicService.SPOTIFY, ItemType.TRACK, "song$n", url), MusicMetadata("Song $n", "Band")))
        }
        launch()
        composeRule.onNode(hasText("Song 2") and hasClickAction()).performScrollTo().requestFocus()
        pressOnFocused(Key.DirectionCenter)
        composeRule.waitUntil(5_000) { model().uiState.result?.title == "Song 2" && !model().uiState.isMatching }
        composeRule.waitForIdle()
        val activity = checkNotNull(controller).get()
        activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        assertEquals(null, model().uiState.result)
        assertFalse(activity.isFinishing)
        composeRule.onNode(hasText("Song 2") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun onATvBackFromTheOnlySongInRecentLandsOnItsRow() {
        val url = "https://open.spotify.com/track/song1"
        HistoryStore(prefs()).add(HistoryEntry(MusicLink(MusicService.SPOTIFY, ItemType.TRACK, "song1", url), MusicMetadata("Song 1", "Band")))
        launch()
        composeRule.onNode(hasText("Song 1") and hasClickAction()).performScrollTo().requestFocus()
        pressOnFocused(Key.DirectionCenter)
        composeRule.waitUntil(5_000) { model().uiState.result?.title == "Song 1" && !model().uiState.isMatching }
        composeRule.waitForIdle()
        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        // Recent came back whole, its one row too, and the remote waited for it.
        composeRule.onNode(hasText("Song 1") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun onATvARecentPlayThatGoesStraightToTheAppLeavesTheRemoteOnIt() {
        for (n in 1..3) {
            val url = "https://open.spotify.com/track/song$n"
            HistoryStore(prefs()).add(HistoryEntry(MusicLink(MusicService.SPOTIFY, ItemType.TRACK, "song$n", url), MusicMetadata("Song $n", "Band")))
        }
        launch()
        composeRule.onNode(hasText("Song 2") and hasClickAction()).performScrollTo().requestFocus()
        pressOnFocused(Key.DirectionCenter)
        composeRule.waitUntil(5_000) { model().uiState.result?.title == "Song 2" && !model().uiState.isMatching }
        composeRule.waitForIdle()
        // With links not shown in Crosstune, ▶ opens the song in the user's app, and Song 2's card stays.
        val play = composeRule.onNode(hasContentDescription(string(R.string.history_open, "Song 1")) and hasClickAction())
        play.performScrollTo().requestFocus()
        pressOnFocused(Key.DirectionCenter)
        assertEquals("Song 2", model().uiState.result?.title)
        play.assertIsFocused()
    }

    @Test
    fun onATvRightAndLeftGoBetweenARecentRowAndItsPlay() {
        val url = "https://open.spotify.com/track/song1"
        HistoryStore(prefs()).add(HistoryEntry(MusicLink(MusicService.SPOTIFY, ItemType.TRACK, "song1", url), MusicMetadata("Song 1", "Band")))
        launch()
        val row = composeRule.onNode(hasText("Song 1") and hasClickAction())
        val play = composeRule.onNode(hasContentDescription(string(R.string.history_open, "Song 1")) and hasClickAction())
        row.performScrollTo().requestFocus()
        pressOnFocused(Key.DirectionRight)
        play.assertIsFocused()
        pressOnFocused(Key.DirectionLeft)
        row.assertIsFocused()
        // Any other key is left to the screen: with nothing past the row's start, the remote stays on it.
        pressOnFocused(Key.DirectionLeft)
        row.assertIsFocused()
    }

    @Test
    fun onATvBackFromTheStepsASettingOpensLandsOnThatSetting() {
        settingsByRemote()
        val follow = composeRule.onNode(hasText(string(R.string.setting_lyrics_follow)))
        follow.performScrollTo().requestFocus()
        pressOnFocused(Key.DirectionCenter)
        composeRule.waitUntil(5_000) { shown(string(R.string.fix_step_turn_on, string(R.string.app_name))) }
        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitUntil(5_000) { !shown(string(R.string.fix_step_turn_on, string(R.string.app_name))) }
        composeRule.waitForIdle()
        follow.assertIsFocused()
    }

    private fun button(id: Int) = composeRule.onNode(hasContentDescription(string(id)) and hasClickAction())

    @Test
    fun onATvTheBarOfWhatsPlayingReachesItsButtonsAndComesBack() {
        val playing = MusicMetadata("Demo", "Band")
        MainActivity.playbackFactory = {
            FakePlayback().apply {
                access = true
                onThePhone = Heard.Song(playing, null, 1_000, SystemClock.elapsedRealtime())
                this.playing.value = Following(
                    PlaybackClock(1_000, SystemClock.elapsedRealtime(), playing = true), "Music",
                    canSeek = true, canSkipNext = true, appPackage = MusicService.YOUTUBE_MUSIC.packageName
                )
            }
        }
        launch()
        composeRule.waitUntil(5_000) { shown("Demo") }
        val bar = composeRule.onNodeWithTag(NOW_PLAYING_TAG)
        bar.requestFocus()
        // The bar is one tap target, so its buttons sit inside it, where the remote doesn't look on its own.
        pressOnFocused(Key.DirectionRight)
        button(R.string.lyrics_pause).assertIsFocused()
        pressOnFocused(Key.DirectionRight)
        button(R.string.lyrics_next_song).assertIsFocused()
        pressOnFocused(Key.DirectionLeft)
        button(R.string.lyrics_pause).assertIsFocused()
        pressOnFocused(Key.DirectionLeft)
        bar.assertIsFocused()
    }

    @Test
    fun onATvAStationWithNoSongNamedReachesItsButtonsAsASongDoes() {
        // VRadio names only the station (#998), and has its buttons all the same.
        MainActivity.playbackFactory = {
            FakePlayback().apply {
                access = true
                unnamed.value = Unnamed("com.ilv.vradio", "VRadio", "RTP Antena 2")
                playing.value = Following(PlaybackClock(1_000, SystemClock.elapsedRealtime(), playing = true), "VRadio", canSeek = false, canSkipNext = true, appPackage = "com.ilv.vradio")
            }
        }
        val heard = CompletableDeferred<Heard>()
        MainActivity.hearingFactory = { SongHearing { heard.await() } }
        launch()
        composeRule.waitUntil(5_000) { shown("RTP Antena 2") }
        val bar = composeRule.onNodeWithTag(NOW_PLAYING_TAG)
        field().requestFocus()
        repeat(4) { pressOnFocused(Key.DirectionDown) }
        bar.assertIsFocused()
        pressOnFocused(Key.DirectionRight)
        button(R.string.lyrics_pause).assertIsFocused()
        pressOnFocused(Key.DirectionRight)
        button(R.string.lyrics_next_song).assertIsFocused()
        pressOnFocused(Key.DirectionLeft)
        pressOnFocused(Key.DirectionLeft)
        bar.assertIsFocused()
        // Its song heard, the bar keeps the remote as its name changes.
        heard.complete(Heard.Song(MusicMetadata("Fake Song", "Fake Band"), null))
        composeRule.waitUntil(5_000) { shown("Fake Song") }
        bar.assertIsFocused()
        pressOnFocused(Key.DirectionUp)
        bar.assertIsNotFocused()
    }
    private fun openStation() = composeRule.onNode(hasContentDescription(string(R.string.open_app_button, "Simple Radio")) and hasClickAction())

    private fun stationPlayerGone() =
        composeRule.onAllNodes(hasContentDescription(string(R.string.open_app_button, "Simple Radio"))).fetchSemanticsNodes().isEmpty()

    private fun model() = ViewModelProvider(checkNotNull(controller).get())[MainViewModel::class.java]

    @Test
    fun onATvAStationWithNoStateStartsOnItsOpenButtonKeepsItAsTheStationChangesAndBackLandsOnTheBar() {
        // Simple Radio names only the station and reports no state, so its player has the one button.
        val radio = FakePlayback().apply {
            access = true
            unnamed.value = Unnamed("com.streema.simpleradio", "Simple Radio", "Blue 100.7 FM", reportsState = false)
        }
        MainActivity.playbackFactory = { radio }
        MainActivity.hearingFactory = { SongHearing { Heard.Nothing } }
        launch()
        composeRule.waitUntil(5_000) { shown("Blue 100.7 FM") }
        // The bar doesn't take the remote just by showing.
        composeRule.onNodeWithTag(NOW_PLAYING_TAG).assertIsNotFocused()
        composeRule.onNodeWithTag(NOW_PLAYING_TAG).performClick()
        composeRule.waitUntil(5_000) { openStation().isDisplayed() }
        // A filled button with its icon and one word, not an icon alone, and the remote is already on it.
        assertTrue(shown(string(R.string.open_button)))
        val icon = composeRule.onNodeWithTag(OPEN_STATION_ICON_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val word = composeRule.onNodeWithTag(OPEN_STATION_WORD_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(icon.right > icon.left && icon.right < word.left)
        openStation().assertIsFocused()

        composeRule.runOnIdle { radio.unnamed.value = Unnamed("com.streema.simpleradio", "Simple Radio", "Rock & Pop", reportsState = false) }
        composeRule.waitUntil(5_000) { shown("Rock & Pop") }
        openStation().assertIsFocused()

        // Back closes the player and the remote lands on the station's bar, which stays, each time.
        repeat(2) {
            if (it > 0) {
                composeRule.onNodeWithTag(NOW_PLAYING_TAG).performClick()
                composeRule.waitUntil(5_000) { openStation().isDisplayed() }
                openStation().assertIsFocused()
            }
            checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
            composeRule.waitUntil(5_000) { stationPlayerGone() }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(NOW_PLAYING_TAG).assertIsFocused()
        }

        // The remote was handed back once: a bar that comes and goes on its own doesn't take it again.
        composeRule.runOnIdle { radio.unnamed.value = null }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(NOW_PLAYING_TAG).fetchSemanticsNodes().isEmpty() }
        composeRule.runOnIdle { radio.unnamed.value = Unnamed("com.streema.simpleradio", "Simple Radio", "Blue 100.7 FM", reportsState = false) }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(NOW_PLAYING_TAG).fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(NOW_PLAYING_TAG).assertIsNotFocused()
    }

    @Test
    fun onATvBackFromAStationsPlayerGoesStraightToTheBarNotByWhatMainWasLeftOn() {
        val radio = FakePlayback().apply {
            access = true
            unnamed.value = Unnamed("com.streema.simpleradio", "Simple Radio", "Blue 100.7 FM", reportsState = false)
        }
        MainActivity.playbackFactory = { radio }
        MainActivity.hearingFactory = { SongHearing { Heard.Nothing } }
        launch()
        composeRule.waitUntil(5_000) { shown("Blue 100.7 FM") }
        composeRule.onRoot().performKeyInput { pressKey(Key.ShiftLeft) }
        // Main is left with the remote on the gear, which Back would go back to, were the bar not owed it.
        settings().requestFocus()
        composeRule.runOnIdle { model().peekPlayingUnnamed() }
        composeRule.waitUntil(5_000) { openStation().isDisplayed() }
        openStation().assertIsFocused()
        // A frame at a time, as the gear lit up for one before the bar took the remote.
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread {
            checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
            Snapshot.sendApplyNotifications()
        }
        val bar = composeRule.onNodeWithTag(NOW_PLAYING_TAG)
        var frames = 0
        while (!(stationPlayerGone() && composeRule.onAllNodes(hasTestTag(NOW_PLAYING_TAG) and isFocused()).fetchSemanticsNodes().isNotEmpty())) {
            assertTrue("Back took longer than ${frames} frames", frames++ < 200)
            composeRule.mainClock.advanceTimeByFrame()
            settings().assertIsNotFocused()
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        bar.assertIsFocused()
    }

    @Test
    fun onATvTheSongPickerUpAsAStationsPlayerClosesGivesTheRemoteToTheFieldOncePutAway() {
        val radio = FakePlayback().apply {
            access = true
            unnamed.value = Unnamed("com.streema.simpleradio", "Simple Radio", "Blue 100.7 FM", reportsState = false)
        }
        MainActivity.playbackFactory = { radio }
        MainActivity.hearingFactory = { SongHearing { Heard.Nothing } }
        launch()
        composeRule.waitUntil(5_000) { shown("Blue 100.7 FM") }
        composeRule.onRoot().performKeyInput { pressKey(Key.ShiftLeft) }
        settings().requestFocus()
        composeRule.runOnIdle { model().peekPlayingUnnamed() }
        composeRule.waitUntil(5_000) { openStation().isDisplayed() }
        // Songs looked up by name close the player, and their sheet on the main screen keeps the remote.
        composeRule.runOnIdle { model().searchSongs("moon") }
        composeRule.waitUntil(5_000) { stationPlayerGone() && shown(string(R.string.song_search_title)) }
        composeRule.waitForIdle()
        settings().assertIsNotFocused()
        // Put away, the picker's own rule has the last word over the bar the remote was handed to: the field, as after a link.
        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitUntil(5_000) { !shown(string(R.string.song_search_title)) }
        composeRule.waitForIdle()
        field().assertIsFocused()
        settings().assertIsNotFocused()
    }

    @Test
    fun onATvAStationThatLosesItsPlayerToAppReportedStateHandsTheRemoteToItsBar() {
        val radio = FakePlayback().apply {
            access = true
            unnamed.value = Unnamed("com.streema.simpleradio", "Simple Radio", "Blue 100.7 FM", reportsState = false)
        }
        MainActivity.playbackFactory = { radio }
        MainActivity.hearingFactory = { SongHearing { Heard.Nothing } }
        launch()
        composeRule.waitUntil(5_000) { shown("Blue 100.7 FM") }
        composeRule.onNodeWithTag(NOW_PLAYING_TAG).performClick()
        composeRule.waitUntil(5_000) { openStation().isDisplayed() }
        openStation().assertIsFocused()
        // Once the app reports its state the read-only player has nothing left to show, so it closes.
        composeRule.runOnIdle { radio.unnamed.value = Unnamed("com.streema.simpleradio", "Simple Radio", "Blue 100.7 FM", reportsState = true) }
        composeRule.waitUntil(5_000) { stationPlayerGone() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(NOW_PLAYING_TAG).assertIsFocused()
    }

    @Test
    fun onATvAPlayerThatIsNotAStationLeavesTheRemoteWhereAndroidPutsIt() {
        MainActivity.playbackFactory = {
            FakePlayback().apply {
                access = true
                onThePhone = Heard.Song(MusicMetadata("Demo", "Band"), null, 1_000, SystemClock.elapsedRealtime())
                playing.value = Following(
                    PlaybackClock(1_000, SystemClock.elapsedRealtime(), playing = true), "Music",
                    canSeek = true, canSkipNext = true, appPackage = MusicService.YOUTUBE_MUSIC.packageName
                )
            }
        }
        launch()
        composeRule.waitUntil(5_000) { shown("Demo") }
        composeRule.onNodeWithTag(NOW_PLAYING_TAG).performClick()
        composeRule.waitUntil(5_000) { model().uiState.player != null }
        composeRule.runOnIdle { model().closePlayer() }
        composeRule.waitUntil(5_000) { model().uiState.player == null }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(NOW_PLAYING_TAG).assertIsNotFocused()
    }
}
