package com.astrovm.crosstune

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** Settings → About → Licenses: every work by others in the app, with who made it and its licence. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
class LicensesTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeSpotify()
    private var controller: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        MainActivity.httpClientFactory = { fake.client() }
        MainActivity.systemDispatcher = Dispatchers.Unconfined
        MainActivity.lookupDispatcher = Dispatchers.Unconfined
        MainActivity.checksForUpdates = { false }
        app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().putBoolean("setup_complete", true).commit()
    }

    @After
    fun tearDown() {
        controller?.destroyAndSettle()
        MainActivity.httpClientFactory = ::httpClient
        MainActivity.systemDispatcher = Dispatchers.Default
        MainActivity.lookupDispatcher = Dispatchers.IO
        MainActivity.checksForUpdates = ::looksForUpdates
    }

    private fun string(id: Int): String = app.getString(id)

    private fun shown(text: String) = composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /** Opens Settings, then About's Licenses. */
    private fun openLicenses() {
        controller = Robolectric.buildActivity(MainActivity::class.java, Intent(app, MainActivity::class.java)).setup()
        composeRule.waitForIdle()
        composeRule.onNode(hasContentDescription(string(R.string.settings_button))).performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasText(string(R.string.settings_licenses)) and hasClickAction()).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    /** The list draws only what's on screen: scrolled to [matcher] first, so it's there to find. */
    private fun scrollTo(matcher: SemanticsMatcher) {
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
        composeRule.waitForIdle()
    }

    private fun work(name: String) = (hasText(name) and hasClickAction()).let { scrollTo(it); composeRule.onNode(it) }

    private fun text(words: String) = scrollTo(hasText(words, substring = true)).let { composeRule.onAllNodesWithText(words, substring = true).onFirst() }

    @Test
    fun aboutOpensTheLicensesWithJmdictsAcknowledgementAndEveryWork() {
        openLicenses()
        // JMdict's acknowledgement, as its licence asks of an app: who made it, its licence, and that it was changed.
        work("JMdict")
        assertTrue(shown("Electronic Dictionary Research and Development Group · CC BY-SA 4.0"))
        assertTrue(shown("word lists made from JMdict are changed"))
        assertTrue(shown("https://www.edrdg.org/edrdg/licence.html"))
        // Opened, its licence, CC BY-SA's and JMdict's documentation, as the licence asks.
        work("JMdict").performClick()
        composeRule.waitForIdle()
        text("ELECTRONIC DICTIONARY RESEARCH AND DEVELOPMENT GROUP").assertExists()
        text("Attribution-ShareAlike 4.0 International").assertExists()
        text("JMdict-EDICT Dictionary Project").assertExists()
        work("JMdict").performClick()
        composeRule.waitForIdle()
        // The works the app is built from, by name, each with a row of its own.
        val names = listOf(
            "Kuromoji", "IPADIC", "UniDic", "projectM", "whisper.cpp and ggml", "Whisper", "MilkDrop presets",
            "MilkDrop textures", "Shizuku API", "OkHttp and Okio", "Guava", "AndroidX, Jetpack Compose and Media3",
            "Kotlin and kotlinx.coroutines", "Reorderable"
        )
        (names + Notices.all.map { it.name }).distinct().forEach { work(it).assertExists() }
    }

    @Test
    fun aWorkOpensToItsFullLicenceAndWhereItIsWhichOpensOnlyOnATap() {
        openLicenses()
        assertTrue(!shown("GNU LESSER GENERAL PUBLIC LICENSE"))
        work("projectM").performClick()
        composeRule.waitForIdle()
        // projectM's own notice, then the LGPL's whole text.
        text("projectM -- Milkdrop-esque visualisation SDK").assertExists()
        text("GNU LESSER GENERAL PUBLIC LICENSE").assertExists()
        text("END OF TERMS AND CONDITIONS").assertExists()
        assertEquals(null, shadowOf(app).nextStartedActivity)

        scrollTo(hasText("github.com/projectM-visualizer/projectm"))
        composeRule.onNodeWithText("github.com/projectM-visualizer/projectm").performClick()
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://github.com/projectM-visualizer/projectm", started.dataString)

        // Tapped again, it closes.
        work("projectM").performClick()
        composeRule.waitForIdle()
        assertTrue(!shown("GNU LESSER GENERAL PUBLIC LICENSE"))
    }

    @Test
    fun theIpadicNoticeIsReproducedInFull() {
        openLicenses()
        work("IPADIC").performClick()
        composeRule.waitForIdle()
        text("Nara Institute of Science and Technology (NAIST)").assertExists()
        text("Each user will be deemed to have agreed").assertExists()
    }

    @Test
    fun backGoesToTheSettingsList() {
        openLicenses()
        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        assertTrue(shown(string(R.string.settings_about_title)))
        assertTrue(!shown("Electronic Dictionary Research and Development Group"))
    }

    @Test
    fun openingAnotherWorkDoesNotSplitAnOpenOneAgain() {
        LicenseSplits.clear()
        openLicenses()
        work("projectM").performClick()
        composeRule.waitForIdle()
        assertEquals(1, LicenseSplits.times["projectM"])
        work("JMdict").performClick()
        composeRule.waitForIdle()
        assertEquals(1, LicenseSplits.times["projectM"])
        assertEquals(1, LicenseSplits.times["JMdict"])
    }

    @Test
    fun aLicenceSplitForThePageIsLetGoOnceThePageIsLeft() {
        LicenseSplits.clear()
        openLicenses()
        work("projectM").performClick()
        composeRule.waitForIdle()
        assertNotNull(LicenseSplits.of("projectM"))
        // Once to close the licence, once to leave the page.
        repeat(2) {
            checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
            composeRule.waitForIdle()
        }
        assertTrue(shown(string(R.string.settings_about_title)))
        assertNull(LicenseSplits.of("projectM"))
    }

    /** A phone, as a TV: Back from inside a long licence closes it and returns to its row, and the next Back leaves. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun backFromInsideALongLicenceClosesItAndASecondBackLeavesThePage() {
        openLicenses()
        composeRule.onRoot().performKeyInput { pressKey(Key.ShiftLeft) }
        val row = hasText("projectM") and hasClickAction()
        scrollTo(row)
        composeRule.onNode(row).requestFocus()
        composeRule.onNode(row).performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        repeat(6) {
            composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)).performKeyInput { pressKey(Key.DirectionDown) }
            composeRule.waitForIdle()
        }
        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText(string(R.string.settings_about_title)).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("GNU LESSER GENERAL PUBLIC LICENSE").fetchSemanticsNodes().isEmpty())
        composeRule.onNode(row).assertIsFocused()

        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText(string(R.string.settings_about_title)).fetchSemanticsNodes().isNotEmpty())
        composeRule.onNode(hasText(string(R.string.settings_licenses)) and hasClickAction()).assertIsFocused()
    }
}
