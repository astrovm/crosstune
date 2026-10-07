package com.astrovm.crosstune

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
import com.astrovm.crosstune.ui.theme.Lime
import com.astrovm.crosstune.ui.theme.LimeDark
import com.astrovm.crosstune.ui.theme.Night
import com.astrovm.crosstune.ui.theme.Violet
import com.astrovm.crosstune.ui.theme.VioletLight
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
    fun settingsPreviewRendersSourcesRulesAndCustomDestinations() {
        composeRule.setContent { SettingsScreenPreview() }

        composeRule.onNodeWithText("Opens in YouTube Music").assertExists()
        composeRule.onNodeWithText("https://yewtu.be/search?q={query}").assertExists()
    }

    @Test
    fun setupPreviewRendersTheWelcomeStep() {
        composeRule.setContent { SetupScreenPreview() }
        composeRule.onNodeWithText("Welcome to Crosstune").assertExists()
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
        assertEquals(listOf(Violet, LimeDark, LimeDark), colors.take(3))
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
        assertEquals(listOf(VioletLight, Lime, Lime), colors.take(3))
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
        assertEquals(VioletLight, colors.first())
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

        assertEquals(listOf(Violet, VioletLight, Violet), primaries)
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

        assertEquals(listOf(Violet, VioletLight), primaries)
    }

    @Test
    fun theAppKeepsItsOwnColorsWhateverTheWallpaper() {
        // The covers bring their colors in; the app's own stay the logo's, on Android 12 and later too.
        val colors = mutableListOf<Color>()
        composeRule.setContent {
            CrosstuneTheme(darkTheme = true) {
                colors += MaterialTheme.colorScheme.primary
                colors += MaterialTheme.colorScheme.surface
            }
        }
        composeRule.waitForIdle()
        assertEquals(listOf(VioletLight, Night), colors.take(2))
    }

    @Test
    fun themeIsSkippedWhenParentRecomposesWithSameInputs() {
        var tick by mutableStateOf(0)
        var parentCompositions = 0
        var themeContentCompositions = 0
        // Hoisted so the same content instance is passed on every recomposition.
        val content: @Composable () -> Unit = { themeContentCompositions++ }
        composeRule.setContent {
            parentCompositions += tick.let { 1 }
            CrosstuneTheme(darkTheme = false, content = content)
        }
        composeRule.waitForIdle()

        tick++
        composeRule.waitForIdle()

        assertEquals(2, parentCompositions)
        assertEquals(1, themeContentCompositions)
    }
}
