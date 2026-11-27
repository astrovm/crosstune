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
import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.platform.ValueElement
import androidx.compose.ui.platform.isDebugInspectorInfoEnabled
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What the remote goes back to in a screen: what it was on as the screen was left, or else the screen's main button. */
@RunWith(RobolectricTestRunner::class)
class ReturningFocusTest {

    @get:Rule
    val compose = createComposeRule()

    private val screen = ScreenFocus()

    @Test
    fun theRemoteGoesBackToWhatItWasOnAsTheScreenWasLeftOrElseTheMainButton() {
        var shown by mutableStateOf(true)
        var key by mutableStateOf<Any>("tool")
        compose.setContent {
            CompositionLocalProvider(LocalScreenFocus provides screen) {
                Column {
                    Box(Modifier.size(48.dp).returnsFocus().testTag("main").focusable())
                    if (shown) Box(Modifier.size(48.dp).returnsFocus(key).testTag("tool").focusable())
                    // Something that opens no screen, so not one to come back to.
                    Box(Modifier.size(48.dp).testTag("plain").focusable())
                    // Outside a screen it's only a modifier, which changes nothing.
                    CompositionLocalProvider(LocalScreenFocus provides null) { Box(Modifier.size(48.dp).returnsFocus("loose").testTag("loose").focusable()) }
                }
            }
        }
        val main = compose.onNodeWithTag("main")
        val tool = compose.onNodeWithTag("tool")
        val left = { compose.runOnIdle { screen.left() } }
        // Never on anything, it goes to the main button.
        compose.runOnIdle { assertTrue(screen.restore()) }
        main.assertIsFocused()

        tool.requestFocus()
        left()
        compose.onNodeWithTag("loose").requestFocus()
        compose.runOnIdle { assertTrue(screen.restore()) }
        tool.assertIsFocused()

        // Its key changed, it's still what the remote was on, under the new one.
        left()
        key = "renamed"
        compose.waitForIdle()
        compose.onNodeWithTag("loose").requestFocus()
        compose.runOnIdle { assertTrue(screen.restore()) }
        tool.assertIsFocused()

        // Moved on to something else in the screen and left from there, it's not the tool it was on before.
        compose.onNodeWithTag("plain").requestFocus()
        left()
        compose.onNodeWithTag("loose").requestFocus()
        compose.runOnIdle { assertTrue(screen.restore()) }
        main.assertIsFocused()

        // Gone, the main button takes it instead.
        tool.requestFocus()
        left()
        shown = false
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(screen.restore()) }
        main.assertIsFocused()
    }

    @Test
    fun eachScreenKeepsWhatTheRemoteWasOnAsTheAppTurnedFromIt() {
        val focuses = ScreenFocuses(Screen.MAIN)
        compose.setContent {
            Column {
                CompositionLocalProvider(LocalScreenFocus provides focuses.of(Screen.MAIN)) { Box(Modifier.size(48.dp).returnsFocus("gear").testTag("gear").focusable()) }
                CompositionLocalProvider(LocalScreenFocus provides focuses.of(Screen.SETTINGS)) { Box(Modifier.size(48.dp).returnsFocus("row").testTag("row").focusable()) }
            }
        }
        compose.onNodeWithTag("gear").requestFocus()
        // Turning to the screen it's on keeps nothing; turning away keeps the gear.
        compose.runOnIdle { focuses.turnTo(Screen.MAIN) }
        compose.runOnIdle { focuses.turnTo(Screen.SETTINGS) }
        compose.onNodeWithTag("row").requestFocus()
        compose.runOnIdle { focuses.turnTo(Screen.MAIN) }
        compose.runOnIdle { assertTrue(focuses.of(Screen.MAIN).restore()) }
        compose.onNodeWithTag("gear").assertIsFocused()
        compose.runOnIdle { assertTrue(focuses.of(Screen.SETTINGS).restore()) }
        compose.onNodeWithTag("row").assertIsFocused()
    }

    @Test
    fun theLayoutInspectorNamesItWithItsKey() {
        isDebugInspectorInfoEnabled = true
        try {
            val value = Modifier.returnsFocus("tool") as InspectableValue
            assertEquals("returnsFocus", value.nameFallback)
            assertEquals(listOf(ValueElement("key", "tool")), value.inspectableElements.toList())
        } finally {
            isDebugInspectorInfoEnabled = false
        }
    }

    @Test
    fun aRenamedButtonIsStillTheOneGoneBackToWhenAnotherTakesItsOldKey() {
        var key by mutableStateOf<Any>("tool")
        var other by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalScreenFocus provides screen) {
                Column {
                    Box(Modifier.size(48.dp).returnsFocus(key).testTag("tool").focusable())
                    if (other) Box(Modifier.size(48.dp).returnsFocus("tool").testTag("other").focusable())
                    CompositionLocalProvider(LocalScreenFocus provides null) { Box(Modifier.size(48.dp).testTag("loose").focusable()) }
                }
            }
        }
        compose.onNodeWithTag("tool").requestFocus()
        compose.runOnIdle { screen.left() }
        key = "renamed"
        compose.waitForIdle()
        other = true
        compose.waitForIdle()
        compose.onNodeWithTag("loose").requestFocus()
        compose.runOnIdle { assertTrue(screen.restore()) }
        compose.onNodeWithTag("tool").assertIsFocused()
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
