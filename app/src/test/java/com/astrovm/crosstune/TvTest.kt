package com.astrovm.crosstune

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
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
        runCatching { controller?.pause()?.stop()?.destroy() }
        MainActivity.httpClientFactory = ::httpClient
        MainActivity.systemDispatcher = Dispatchers.Default
        MainActivity.lookupDispatcher = Dispatchers.IO
        MainActivity.phoneFactory = realPhone
        MainActivity.checksForUpdates = ::looksForUpdates
        MainActivity.shellFactory = { listOf(ShizukuShell(), RootShell()) }
        MainActivity.playbackFactory = ::MediaSessionPlayback
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
}
