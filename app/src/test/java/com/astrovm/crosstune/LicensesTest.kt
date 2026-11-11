package com.astrovm.crosstune

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
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

    private fun work(name: String) = composeRule.onNode(hasText(name) and hasClickAction())

    @Test
    fun aboutOpensTheLicensesWithJmdictsAcknowledgementAndEveryWork() {
        openLicenses()
        // JMdict's acknowledgement, as its licence asks of an app: who made it, its licence, and that it was changed.
        work("JMdict").performScrollTo()
        assertTrue(shown("Electronic Dictionary Research and Development Group · CC BY-SA 4.0"))
        assertTrue(shown("word lists made from JMdict are changed"))
        assertTrue(shown("https://www.edrdg.org/edrdg/licence.html"))
        // The works the app is built from, by name, each with a row of its own.
        val names = listOf(
            "Kuromoji", "IPADIC", "UniDic", "projectM", "whisper.cpp and ggml", "Whisper tiny speech model", "MilkDrop presets",
            "MilkDrop textures", "Shizuku API", "OkHttp and Okio", "Guava", "AndroidX, Jetpack Compose and Media3",
            "Kotlin and kotlinx.coroutines", "Reorderable"
        )
        (names + Notices.all.map { it.name }).distinct().forEach { work(it).performScrollTo().assertExists() }
    }

    @Test
    fun aWorkOpensToItsFullLicenceAndWhereItIsWhichOpensOnlyOnATap() {
        openLicenses()
        assertTrue(!shown("GNU LESSER GENERAL PUBLIC LICENSE"))
        work("projectM").performScrollTo().performClick()
        composeRule.waitForIdle()
        // projectM's own notice, then the LGPL's whole text.
        composeRule.onNodeWithText("projectM -- Milkdrop-esque visualisation SDK", substring = true).performScrollTo().assertExists()
        composeRule.onNodeWithText("GNU LESSER GENERAL PUBLIC LICENSE", substring = true).performScrollTo().assertExists()
        composeRule.onNodeWithText("END OF TERMS AND CONDITIONS", substring = true).performScrollTo().assertExists()
        assertEquals(null, shadowOf(app).nextStartedActivity)

        composeRule.onNodeWithText("github.com/projectM-visualizer/projectm").performScrollTo().performClick()
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://github.com/projectM-visualizer/projectm", started.dataString)

        // Tapped again, it closes.
        work("projectM").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(!shown("GNU LESSER GENERAL PUBLIC LICENSE"))
    }

    @Test
    fun theIpadicNoticeIsReproducedInFull() {
        openLicenses()
        work("IPADIC").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Nara Institute of Science and Technology (NAIST)", substring = true).performScrollTo().assertExists()
        composeRule.onNodeWithText("Each user will be deemed to have agreed", substring = true).performScrollTo().assertExists()
    }

    @Test
    fun backGoesToTheSettingsList() {
        openLicenses()
        checkNotNull(controller).get().onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        assertTrue(shown(string(R.string.settings_about_title)))
        assertTrue(!shown("Electronic Dictionary Research and Development Group"))
    }
}
