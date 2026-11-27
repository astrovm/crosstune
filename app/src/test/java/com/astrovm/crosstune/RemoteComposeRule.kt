package com.astrovm.crosstune

import androidx.compose.ui.input.InputMode
import androidx.compose.ui.test.ComposeUiTestConfig
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule

/**
 * A compose rule for a TV or a keyboard: each test starts out of touch mode, as a remote leaves it. Compose's own rule
 * starts each test in touch mode, and a key sent through it doesn't leave it, so nothing a remote could reach takes focus.
 */
internal fun createRemoteComposeRule() = createEmptyComposeRule(ComposeUiTestConfig(inputMode = InputMode.Keyboard))
