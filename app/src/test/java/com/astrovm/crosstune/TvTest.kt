package com.astrovm.crosstune

import android.app.Application
import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

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
        settings().assertIsFocused()
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
}
