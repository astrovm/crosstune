package com.astrovm.crosstune

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The steps floating over Android's page, in its small window, at a big font and in long languages. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi", fontScale = 3.2f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FloatingGuideBigFontTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var shown by mutableStateOf<Pair<Need, UiState>?>(null)

    /** Every guide that floats, on every kind of phone, and the one saying it's done: its words whole. */
    private fun everyGuideWhole() {
        val spotify = LinkApp(MusicService.SPOTIFY.packageName, "Spotify")
        val broken = UiState(intercepted = setOf(MusicService.SPOTIFY), unapprovedHosts = mapOf(MusicService.SPOTIFY to listOf("open.spotify.com")), blockingApps = setOf(spotify), canFloatOverApps = false)
        val needs = listOf(Need.Links, Need.Microphone, Need.Float, Need.Exact, Need.StopApp(spotify))
        val scenes = Phone.entries.flatMap { phone -> needs.map { need -> need to broken.copy(phone = phone, fixes = Fixes(steps = listOf(need))) } }
            .filter { (need, state) -> state.guideFloats(need, sdk = 30) } +
            (Need.Float to broken.copy(canFloatOverApps = true, fixes = Fixes(steps = listOf(Need.Float))))
        // The window Android floats it in: small, 3 wide by 4 tall.
        composeRule.setContent { Box(Modifier.size(180.dp, 240.dp)) { shown?.let { (need, state) -> CrosstuneTheme { GuideActivity.Shrunk { FloatingGuide(need, state) } } } } }
        var words = 0
        for ((need, state) in scenes) {
            shown = need to state
            composeRule.waitForIdle()
            // The guide's px to its dp, as shrunk to the window: laid out its width, as much wider as the font is bigger.
            val window = composeRule.onRoot().fetchSemanticsNode().size.width.toFloat()
            val perDp = window / (260f * composeRule.density.grows())
            val pictures = composeRule.onAllNodesWithTag(ANDROID_PICTURE_TAG, useUnmergedTree = true).fetchSemanticsNodes()
            fun SemanticsNode.whole() = Rect(positionInRoot, size.toSize())
            words += composeRule.assertWordsWhole("${state.phone} ${need.key} done ${state.done(need)}") { node ->
                val center = node.whole().center
                // A picture's words have the picture's room, the rest the guide's, within its margins.
                pictures.firstOrNull { it.whole().contains(center) }?.let { it.size.width / it.layoutInfo.density.density * perDp - DRAWN_MARGINS.value * perDp } ?: (window - 24 * perDp)
            }
        }
        assertTrue(words > scenes.size)
    }

    @Test
    fun everyFloatingGuideKeepsItsWordsWholeInEnglish() = everyGuideWhole()

    @Test
    @Config(qualifiers = "de-w411dp-h891dp-xxhdpi")
    fun everyFloatingGuideKeepsItsWordsWholeInGerman() = everyGuideWhole()

    @Test
    @Config(qualifiers = "es-w320dp-h891dp-xxhdpi", fontScale = 2f)
    fun everyFloatingGuideKeepsItsWordsWholeInSpanishAtFontTwo() = everyGuideWhole()
}
