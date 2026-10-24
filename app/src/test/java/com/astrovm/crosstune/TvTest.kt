package com.astrovm.crosstune

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.ui.input.key.Key
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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
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
        MainActivity.phoneFactory = { Phone.of(android.os.Build.MANUFACTURER, android.os.Build.BRAND) }
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

        // Up and down leave it, typing or not, and coming back it waits again.
        press(Key.DirectionDown)
        field().assertIsNotFocused()
        field().requestFocus()
        field().assert(!hasSetTextAction())
        press(Key.DirectionUp)
        field().assertIsNotFocused()
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
