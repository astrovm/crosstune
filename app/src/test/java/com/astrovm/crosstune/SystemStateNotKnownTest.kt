package com.astrovm.crosstune

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Before Android has been asked about apps and links, the screen flags nothing and shows no guide's findings. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
class SystemStateNotKnownTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private var state by mutableStateOf(UiState())

    private fun show(initial: UiState) {
        state = initial
        composeRule.setContent { CrosstuneTheme { CrosstuneScreen(state, ScreenActions()) } }
    }

    @Test
    fun theReminderToAllowLinksWaitsUntilAndroidCouldHaveSaidTheyAre() {
        // Before Android 12 there's nothing to ask, so the reminder shows until dismissed; but not
        // knowing yet isn't the same as Android being unable to say.
        show(UiState(intercepted = setOf(MusicService.SPOTIFY), showLinkSettingsHelper = true, systemStateKnown = false))
        val reminder = app.getString(R.string.link_settings_helper_title)
        composeRule.onNodeWithText(reminder).assertDoesNotExist()

        state = state.copy(systemStateKnown = true)
        composeRule.onNodeWithText(reminder).assertExists()
    }

    @Test
    fun aGuideRestoredBeforeAndroidAnswersWaitsForIt() {
        show(UiState(intercepted = setOf(MusicService.SPOTIFY), unapprovedHosts = mapOf(MusicService.SPOTIFY to listOf("spotify.link"))))
        composeRule.onNodeWithText(app.getString(R.string.allow_button)).performClick()
        val openSettings = app.getString(R.string.open_link_settings_button)
        composeRule.onNodeWithText(openSettings).assertExists()

        // As when Android ends the app in the background and the guide is brought back.
        state = state.copy(systemStateKnown = false, unapprovedHosts = null)
        composeRule.onNodeWithText(app.getString(R.string.setup_allow_title)).assertExists()
        composeRule.onNodeWithText(openSettings).assertDoesNotExist()

        state = state.copy(systemStateKnown = true, unapprovedHosts = mapOf(MusicService.SPOTIFY to emptyList()))
        composeRule.onNodeWithText(openSettings).assertExists()
    }
}
