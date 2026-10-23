package com.astrovm.crosstune

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
    fun nothingIsCountedToFixUntilAndroidCouldHaveSaid() {
        // Before Android 12 there's nothing to ask, so links just turned on are counted; but not
        // knowing yet isn't the same as Android being unable to say.
        show(UiState(intercepted = setOf(MusicService.SPOTIFY), showLinkSettingsHelper = true, systemStateKnown = false))
        composeRule.onNodeWithTag(FIX_BAR_TAG).assertDoesNotExist()

        state = state.copy(systemStateKnown = true)
        composeRule.onNodeWithTag(FIX_BAR_TAG).assertExists()
    }

    @Test
    fun aListRestoredBeforeAndroidAnswersWaitsForIt() {
        // As when Android ends the app in the background and the list is brought back.
        show(UiState(intercepted = setOf(MusicService.SPOTIFY), fixes = Fixes(list = true), systemStateKnown = false))
        val links = app.getString(R.string.setup_check_allow)
        composeRule.onNodeWithText(app.getString(R.string.fix_title)).assertExists()
        composeRule.onNodeWithText(links).assertDoesNotExist()

        state = state.copy(systemStateKnown = true, unapprovedHosts = mapOf(MusicService.SPOTIFY to listOf("spotify.link")))
        composeRule.onNodeWithText(links).assertExists()
    }

}
