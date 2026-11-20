package com.astrovm.crosstune

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What the remote goes back to in a screen: what it was last on, or else the screen's main button. */
@RunWith(RobolectricTestRunner::class)
class ReturningFocusTest {

    @get:Rule
    val compose = createComposeRule()

    private val screen = ScreenFocus()

    @Test
    fun theRemoteGoesBackToWhatItWasLastOnOrElseTheMainButton() {
        var shown by mutableStateOf(true)
        var key by mutableStateOf<Any>("tool")
        compose.setContent {
            CompositionLocalProvider(LocalScreenFocus provides screen) {
                Column {
                    Box(Modifier.size(48.dp).returnsFocus().testTag("main").focusable())
                    if (shown) Box(Modifier.size(48.dp).returnsFocus(key).testTag("tool").focusable())
                    // Outside a screen it's only a modifier, which changes nothing.
                    CompositionLocalProvider(LocalScreenFocus provides null) { Box(Modifier.size(48.dp).returnsFocus("loose").testTag("loose").focusable()) }
                }
            }
        }
        val main = compose.onNodeWithTag("main")
        val tool = compose.onNodeWithTag("tool")
        // Never on anything, it goes to the main button.
        compose.runOnIdle { assertTrue(screen.restore()) }
        main.assertIsFocused()

        tool.requestFocus()
        compose.onNodeWithTag("loose").requestFocus()
        compose.runOnIdle { assertTrue(screen.restore()) }
        tool.assertIsFocused()

        // Its key changed, it's still what the remote was last on, under the new one.
        tool.requestFocus()
        key = "renamed"
        compose.waitForIdle()
        compose.onNodeWithTag("loose").requestFocus()
        compose.runOnIdle { assertTrue(screen.restore()) }
        tool.assertIsFocused()

        // Gone, the main button takes it instead.
        shown = false
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(screen.restore()) }
        main.assertIsFocused()
    }

    @Test
    fun aScreenWithNothingToGoBackToTakesNothing() {
        compose.setContent {
            CompositionLocalProvider(LocalScreenFocus provides screen) { Box(Modifier.size(48.dp).testTag("plain").focusable()) }
        }
        compose.runOnIdle { assertFalse(screen.restore()) }
        compose.onNodeWithTag("plain").assertIsNotFocused()
    }
}
