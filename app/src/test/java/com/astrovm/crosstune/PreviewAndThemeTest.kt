package com.astrovm.crosstune

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.astrovm.crosstune.ui.theme.AppTypography
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import com.astrovm.crosstune.ui.theme.Forest40
import com.astrovm.crosstune.ui.theme.Forest80
import com.astrovm.crosstune.ui.theme.Ember40
import com.astrovm.crosstune.ui.theme.Ember80
import com.astrovm.crosstune.ui.theme.Slate40
import com.astrovm.crosstune.ui.theme.Slate80
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
class PreviewAndThemeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun previewRendersResolvedTrack() {
        composeRule.setContent { CrosstuneScreenPreview() }

        composeRule.onNodeWithText("Cut To The Feeling").assertExists()
        composeRule.onNodeWithText("Carly Rae Jepsen").assertExists()
    }

    @Test
    fun lightThemeUsesLightPalette() {
        val colors = mutableListOf<Color>()
        composeRule.setContent {
            CrosstuneTheme(darkTheme = false) {
                colors += MaterialTheme.colorScheme.primary
                colors += MaterialTheme.colorScheme.secondary
                colors += MaterialTheme.colorScheme.tertiary
                assertEquals(AppTypography, MaterialTheme.typography)
            }
        }
        composeRule.waitForIdle()
        assertEquals(listOf(Forest40, Slate40, Ember40), colors.take(3))
    }

    @Test
    fun darkThemeUsesDarkPalette() {
        val colors = mutableListOf<Color>()
        composeRule.setContent {
            CrosstuneTheme(darkTheme = true) {
                colors += MaterialTheme.colorScheme.primary
                colors += MaterialTheme.colorScheme.secondary
                colors += MaterialTheme.colorScheme.tertiary
            }
        }
        composeRule.waitForIdle()
        assertEquals(listOf(Forest80, Slate80, Ember80), colors.take(3))
    }

    @Test
    @Config(qualifiers = "night")
    fun themeFollowsSystemDarkMode() {
        val colors = mutableListOf<Color>()
        composeRule.setContent {
            CrosstuneTheme {
                colors += MaterialTheme.colorScheme.primary
            }
        }
        composeRule.waitForIdle()
        assertEquals(Forest80, colors.first())
    }

    @Test
    fun explicitThemeSwitchesPaletteWhenToggled() {
        var dark by mutableStateOf(false)
        val primaries = mutableListOf<Color>()
        composeRule.setContent {
            CrosstuneTheme(darkTheme = dark) {
                primaries += MaterialTheme.colorScheme.primary
            }
        }
        composeRule.waitForIdle()

        dark = true
        composeRule.waitForIdle()
        dark = false
        composeRule.waitForIdle()

        assertEquals(listOf(Forest40, Forest80, Forest40), primaries)
    }

    @Test
    fun defaultThemeFollowsSystemDarkModeChangesAtRuntime() {
        var night by mutableStateOf(false)
        val primaries = mutableListOf<Color>()
        composeRule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                CrosstuneTheme {
                    primaries += MaterialTheme.colorScheme.primary
                }
            }
        }
        composeRule.waitForIdle()

        night = true
        composeRule.waitForIdle()

        assertEquals(listOf(Forest40, Forest80), primaries)
    }
}
