package com.astrovm.crosstune

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.click
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Dialogs and sheets as windows, and, on a TV, drawn inside the screen where the remote can reach them. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenOverlaysTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var alert by mutableStateOf(true)
    private var dismissed = 0
    private var confirmed = 0

    private fun screen(inside: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        // The remote's, as a TV is: Robolectric starts as a touch screen, where buttons take no focus.
        val keys = LocalInputModeManager.current
        remember { keys.requestInputMode(InputMode.Keyboard) }
        CrosstuneTheme { WithOverlays(inside) { Box { content() } } }
    }

    @Test
    fun whereDialogsAreWindowsTheyAreAndTheScreenIsLeftAlone() {
        screen(inside = false) {
            Column {
                Text("Behind")
                if (alert) ScreenAlert({ dismissed++ }, { Button(onClick = { confirmed++ }) { Text("Yes") } }, title = { Text("Title") }, text = { Text("Words") })
            }
        }
        compose.onNodeWithText("Behind").assertExists()
        compose.onNodeWithText("Title").assertExists()
        compose.onNodeWithText("Words").assertExists()
        compose.onNodeWithText("Yes").performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun whereDialogsAreWindowsASheetIsOneToo() {
        screen(inside = false) {
            ScreenSheet({ dismissed++ }) { Text("Sheet words") }
        }
        compose.onNodeWithText("Sheet words").assertExists()
    }

    @Test
    fun insideTheScreenAnAlertHasItsIconTitleTextAndBothButtons() {
        screen(inside = true) {
            Text("Behind")
            ScreenAlert({ dismissed++ }, { Button(onClick = { confirmed++ }) { Text("Yes") } }, dismissButton = { Button(onClick = { dismissed++ }) { Text("No") } },
                icon = { Text("Icon") }, title = { Text("Title") }, text = { Text("Words") })
        }
        listOf("Behind", "Icon", "Title", "Words", "Yes", "No").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("No").performClick()
        assertEquals(1, dismissed)
        compose.onNodeWithText("Yes").performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun anAlertWithOnlyAButtonHasNoIconTitleOrText() {
        screen(inside = true) {
            ScreenAlert({ dismissed++ }, { Button(onClick = { confirmed++ }) { Text("Ok") } })
        }
        compose.onNodeWithText("Ok").assertExists()
    }

    @Test
    fun anAlertWithATitleAndNoIconStartsItAtTheStart() {
        screen(inside = true) {
            ScreenAlert({ dismissed++ }, { Button(onClick = {}) { Text("Ok") } }, title = { Text("Title") })
        }
        compose.onNodeWithText("Title").assertExists()
    }

    @Test
    fun backPutsAnAlertAwayAndSoDoesATapBesideIt() {
        screen(inside = true) {
            ScreenAlert({ dismissed++ }, { Button(onClick = {}) { Text("Ok") } }, text = { Text("Words") })
        }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, dismissed)
        // The corner is the scrim's, beside the dialog.
        compose.onRoot().performTouchInput { click(Offset(2f, 2f)) }
        assertEquals(2, dismissed)
    }

    @Test
    fun aSheetInsideTheScreenIsPutAwayByBackOrATapBesideIt() {
        screen(inside = true) {
            ScreenSheet({ dismissed++ }) { Text("Sheet words") }
        }
        compose.onNodeWithText("Sheet words").assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, dismissed)
        compose.onRoot().performTouchInput { click(Offset(2f, 2f)) }
        assertEquals(2, dismissed)
    }

    @Test
    fun putAwayTheRemoteGoesBackToWhatOpenedIt() {
        val opener = FocusRequester()
        screen(inside = true) {
            Button(onClick = {}, modifier = Modifier.focusRequester(opener)) { Text("Opener") }
            if (alert) ScreenAlert({ alert = false }, { Button(onClick = { alert = false }) { Text("Ok") } }, opener = opener)
        }
        compose.waitForIdle()
        compose.onNodeWithText("Ok").assertIsFocused()
        compose.onNodeWithText("Ok").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Ok").assertCountEquals(0)
        compose.onNodeWithText("Opener").assertIsFocused()
    }

    @Test
    fun anOverlayOpenedFromAnotherOneGivesTheRemoteBackToIt() {
        var second by mutableStateOf(true)
        screen(inside = true) {
            Button(onClick = {}) { Text("Screen") }
            ScreenAlert({ alert = false }, { Button(onClick = {}) { Text("First") } })
            if (second) ScreenAlert({ second = false }, { Button(onClick = { second = false }) { Text("Second") } })
        }
        compose.waitForIdle()
        compose.onNodeWithText("Second").assertIsFocused()
        compose.onNodeWithText("Second").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("First").assertIsFocused()
    }

    @Test
    fun theRemoteCantWanderFromAnOverlayToTheScreenBehindIt() {
        screen(inside = true) {
            Column {
                Button(onClick = {}) { Text("Behind") }
                ScreenAlert({ dismissed++ }, { Button(onClick = {}) { Text("Ok") } })
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Ok").assertIsFocused()
        compose.onNodeWithText("Ok").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("Ok").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithText("Ok").assertIsFocused()
    }
}
