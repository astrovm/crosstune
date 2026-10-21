package com.astrovm.crosstune

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-rDE-w360dp-h800dp")
class FloatingMenuTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun largeTextKeepsTheDoneLabelOnOneLine() {
        val actions = FloatingActions({}, {}, {}, {}, {}, { _, _ -> }, {})
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                CrosstuneTheme {
                    Box(Modifier.width(280.dp)) {
                        FloatingOverApps(UiState(lyricsFor = MusicMetadata("Demo", "Band")), actions, true, {})
                    }
                }
            }
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        compose.onNodeWithContentDescription(app.getString(R.string.floating_customize)).performClick()
        val done = compose.onNodeWithText(app.getString(R.string.floating_done)).performScrollTo().getUnclippedBoundsInRoot()
        assertTrue("Done should be readable on one line: $done", done.bottom - done.top < 60.dp)
    }
}
