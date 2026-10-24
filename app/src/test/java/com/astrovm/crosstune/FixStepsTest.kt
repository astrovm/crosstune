package com.astrovm.crosstune

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The steps to fix each thing, as each kind of phone and Android version shows them, and the list they're picked from. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h4000dp")
class FixStepsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private val spotify = LinkApp(MusicService.SPOTIFY.packageName, "Spotify")

    /** Spotify's links on, one not allowed yet. */
    private val broken = UiState(
        intercepted = setOf(MusicService.SPOTIFY),
        unapprovedHosts = mapOf(MusicService.SPOTIFY to listOf("open.spotify.com")),
        blockingApps = emptySet()
    )

    private var state by mutableStateOf(broken)
    private val done = mutableListOf<String>()

    private val actions = ScreenActions(
        onOpenFix = { done += "open ${it.key}" },
        onTryLink = { done += "try ${it.key}" },
        onNextFix = { done += "next" },
        onFixBack = { done += "back" },
        onOpenAppInfo = { done += "app info" },
        onSetUpInOneTap = { done += "one tap" },
        onFixSteps = { needs -> done += "steps ${needs.joinToString { it.key }}" },
        onOpenShizuku = { done += "shizuku" },
        onCloseFixes = { done += "close" }
    )

    private fun string(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun steps(need: Need, phone: Phone = Phone.PIXEL, change: UiState.() -> UiState = { this }) {
        state = broken.copy(phone = phone, fixes = Fixes(steps = listOf(need))).change()
    }

    private fun showSteps(need: Need, phone: Phone = Phone.PIXEL, change: UiState.() -> UiState = { this }) {
        steps(need, phone, change)
        composeRule.setContent { CrosstuneTheme { FixStepPage(state, actions) } }
        composeRule.waitForIdle()
    }

    private fun showList(change: UiState.() -> UiState = { this }) {
        state = broken.copy(microphone = MicAccess.ASKABLE, fixes = Fixes(list = true)).change()
        composeRule.setContent { CrosstuneTheme { FixesPage(state, actions) } }
        composeRule.waitForIdle()
    }

    private fun shown(text: String) = composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun assertShown(vararg texts: String) = texts.forEach { assertTrue("'$it' not shown", shown(it)) }

    private fun pictures() = composeRule.onAllNodesWithTag(ANDROID_PICTURE_TAG, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun tap(word: Int) = string(R.string.fix_step_tap, string(word))

    private fun turnOn(word: Int) = string(R.string.fix_step_turn_on, string(word))

    private fun click(text: String) {
        composeRule.onNode(hasText(text) or hasContentDescription(text)).performClick()
        composeRule.waitForIdle()
    }

    private fun checkedOff() = composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.setup_done))).fetchSemanticsNodes().size

    @Test
    fun allowingLinksShowsEachPhonesOwnPageAndOtherPhonesInWords() {
        showSteps(Need.Links)
        assertShown(tap(R.string.android_add_link), string(R.string.fix_step_tick_then, string(R.string.android_add)))
        assertEquals(2, pictures())
        // Android's own page, which the one button opens.
        click(string(R.string.fix_open_settings))
        assertEquals(listOf("open links"), done)

        steps(Need.Links, Phone.SAMSUNG)
        composeRule.waitForIdle()
        assertShown(tap(R.string.android_supported_web_addresses), string(R.string.fix_step_switches))
        assertEquals(2, pictures())

        steps(Need.Links, Phone.XIAOMI)
        composeRule.waitForIdle()
        assertShown(string(R.string.fix_step_tick_under, string(R.string.android_links_to_open)))
        assertEquals(1, pictures())

        // Every other phone's pages look different, so the words alone.
        steps(Need.Links, Phone.OTHER)
        composeRule.waitForIdle()
        assertShown(tap(R.string.android_add_link))
        assertEquals(0, pictures())
    }

    @Test
    fun linksSetToOpenInTheBrowserAreSwitchedBackFirst() {
        // All allowed, but set to open in the browser, so every link is there to tick once switched back.
        showSteps(Need.Links) { copy(unapprovedHosts = emptyMap(), ownLinksOff = true) }
        assertShown(string(R.string.fix_step_pick, string(R.string.android_in_the_app)), tap(R.string.android_add_link))
        assertEquals(3, pictures())

        steps(Need.Links, Phone.SAMSUNG) { copy(unapprovedHosts = emptyMap(), ownLinksOff = true) }
        composeRule.waitForIdle()
        assertShown(turnOn(R.string.android_open_supported_links), tap(R.string.android_supported_web_addresses))
        assertEquals(3, pictures())
    }

    @Test
    fun anAppInTheWayHasItsOwnPageOpened() {
        showSteps(Need.StopApp(spotify))
        assertShown(string(R.string.fix_step_pick, string(R.string.android_in_your_browser)), string(R.string.setup_check_app, "Spotify"))
        click(string(R.string.open_app_link_settings_button, "Spotify"))
        assertEquals(listOf("open app:${spotify.packageName}"), done)

        steps(Need.StopApp(spotify), Phone.SAMSUNG)
        composeRule.waitForIdle()
        assertShown(string(R.string.fix_step_turn_off, string(R.string.android_open_supported_links)))
        assertEquals(1, pictures())

        // Xiaomi ticks the choice that's set, rather than a radio button.
        steps(Need.StopApp(spotify), Phone.XIAOMI)
        composeRule.waitForIdle()
        assertShown(string(R.string.fix_step_pick, string(R.string.android_in_your_browser)))
        assertEquals(1, pictures())
    }

    @Test
    fun theMicrophoneTurnedDownForGoodIsAllowedInAppInfo() {
        // While Android still asks, it's asked where it's used, so its steps are only for once it won't.
        showSteps(Need.Microphone) { copy(microphone = MicAccess.DENIED) }
        assertShown(
            tap(R.string.android_permissions),
            tap(R.string.android_microphone),
            string(R.string.fix_step_pick, string(R.string.android_allow_while_using)),
            string(R.string.fix_open_settings)
        )
        assertEquals(3, pictures())

        // Xiaomi calls them App permissions.
        steps(Need.Microphone, Phone.XIAOMI) { copy(microphone = MicAccess.DENIED) }
        composeRule.waitForIdle()
        assertShown(tap(R.string.android_app_permissions))
    }

    @Test
    fun followingMusicAppsIsOneSwitchOrRestrictedSettingsFirst() {
        showSteps(Need.Follow)
        assertShown(turnOn(R.string.android_allow_notification_access), tap(R.string.allow_button))
        assertEquals(2, pictures())
        click(string(R.string.fix_open_settings))
        assertEquals(listOf("open follow"), done)

        // Samsung lists every app, Crosstune among them.
        steps(Need.Follow, Phone.SAMSUNG)
        composeRule.waitForIdle()
        assertShown(turnOn(R.string.app_name))

        // So does a TV, whose switch asks nothing more, and which has no pictures.
        steps(Need.Follow, Phone.TV)
        composeRule.waitForIdle()
        assertShown(turnOn(R.string.app_name))
        assertFalse(shown(tap(R.string.allow_button)))
        assertEquals(0, pictures())

        // Installed from a file, Android turns the first try down, then lets it from App info.
        steps(Need.Follow) { copy(followRestricted = true) }
        composeRule.waitForIdle()
        assertShown(
            string(R.string.fix_step_try_then, string(R.string.android_allow_notification_access), string(R.string.android_close)),
            string(R.string.fix_step_menu, string(R.string.android_allow_restricted)),
            string(R.string.fix_step_turn_on_then, string(R.string.android_allow_notification_access), string(R.string.allow_button))
        )
        click(string(R.string.follow_help_app_info))
        assertEquals(listOf("open follow", "app info"), done)
    }

    @Test
    fun floatingIsSwitchedOnInTheListOfAppsOrRightThereOnSamsung() {
        showSteps(Need.Float)
        assertShown(tap(R.string.app_name), turnOn(R.string.android_display_over_apps), string(R.string.floating_permission_title))
        assertEquals(2, pictures())

        steps(Need.Float, Phone.SAMSUNG)
        composeRule.waitForIdle()
        assertShown(turnOn(R.string.app_name))
        assertFalse(shown(turnOn(R.string.android_display_over_apps)))

        steps(Need.Float, Phone.TV)
        composeRule.waitForIdle()
        assertShown(turnOn(R.string.app_name))
        assertFalse(shown(turnOn(R.string.android_display_over_apps)))
        assertEquals(0, pictures())
    }

    @Test
    @Config(sdk = [29])
    fun beforeAndroid11TheStepsAreAndroidsOlderOnesInWords() {
        showSteps(Need.Microphone) { copy(microphone = MicAccess.DENIED) }
        assertShown(tap(R.string.android_permissions), string(R.string.fix_step_pick, string(R.string.allow_button)))
        assertEquals(0, pictures())

        // Android opens Crosstune's own page.
        steps(Need.Float)
        composeRule.waitForIdle()
        assertShown(turnOn(R.string.android_display_over_apps))
        assertEquals(1, pictures())
    }

    @Test
    @Config(sdk = [30])
    fun beforeAndroid12LinksAreOneChoiceThatATestLinkChecks() {
        showSteps(Need.Links) { copy(unapprovedHosts = null) }
        assertShown(
            tap(R.string.android_open_by_default),
            string(R.string.fix_step_set, string(R.string.android_open_supported_links), string(R.string.android_open_in_this_app)),
            tap(R.string.fix_try_link)
        )
        assertEquals(0, pictures())
        click(string(R.string.fix_try_link))
        assertEquals(listOf("try links"), done)

        // An app in the way is set the other way.
        steps(Need.StopApp(spotify)) { copy(unapprovedHosts = null, blockingApps = null) }
        composeRule.waitForIdle()
        assertShown(string(R.string.fix_step_set, string(R.string.android_open_supported_links), string(R.string.android_dont_open_in_this_app)))

        // Nothing to test with when no links are opened.
        steps(Need.Links) { copy(intercepted = emptySet(), unapprovedHosts = null) }
        composeRule.waitForIdle()
        assertFalse(shown(string(R.string.fix_try_link)))
    }

    @Test
    fun theStepsCountWhenThereAreSeveralAndMoveOnOnceDone() {
        showSteps(Need.Links) { copy(fixes = Fixes(steps = listOf(Need.Links, Need.Follow), failed = true)) }
        assertShown(string(R.string.setup_step, 1, 2), string(R.string.fix_failed))
        click(string(R.string.setup_skip_for_now))
        click(string(R.string.back_button))
        assertEquals(listOf("next", "back"), done)

        // Done, it's checked off, and after a moment the next one shows.
        done.clear()
        state = state.copy(unapprovedHosts = emptyMap())
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodes(hasContentDescription(string(R.string.setup_done))).fetchSemanticsNodes().isNotEmpty())
        composeRule.mainClock.advanceTimeBy(FIX_DONE_PAUSE_MS)
        composeRule.waitForIdle()
        assertEquals(listOf("next"), done)

        // Alone, there's nothing to count.
        steps(Need.Follow)
        composeRule.waitForIdle()
        assertFalse(shown(string(R.string.setup_step, 1, 1)))
    }

    @Test
    fun withNothingToFixThereAreNoSteps() {
        state = broken.copy(fixes = Fixes(list = true))
        composeRule.setContent { CrosstuneTheme { FixStepPage(state, actions) } }
        composeRule.waitForIdle()
        assertFalse(shown(string(R.string.fix_open_settings)))
        state = broken
        composeRule.waitForIdle()
        assertFalse(shown(string(R.string.fix_open_settings)))
    }

    @Test
    fun theListMarksEachThingAndFixesThemOneByOne() {
        showList { copy(canFollowApps = true) }
        assertShown(string(R.string.setup_check_allow), string(R.string.follow_help_title))
        // The microphone is asked where it's used, not listed.
        assertFalse(shown(string(R.string.fix_mic_title)))
        assertEquals(1, checkedOff())
        // Done, a row has nothing left to open.
        click(string(R.string.follow_help_title))
        click(string(R.string.setup_check_allow))
        click(string(R.string.fix_one_by_one))
        click(string(R.string.back_button))
        assertEquals(listOf("steps links", "steps links", "close"), done)
    }

    @Test
    fun withShizukuOrRootItsAllFixedInOneTapAndSaysWhatDidIt() {
        showList { copy(canSetUpInOneTap = true) }
        assertShown(string(R.string.fix_with_shizuku))
        click(string(R.string.fix_all))
        assertEquals(listOf("one tap"), done)

        state = state.copy(oneTapRoot = true)
        composeRule.waitForIdle()
        assertShown(string(R.string.fix_with_root))

        // Busy, it waits.
        state = state.copy(settingUpInOneTap = true)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(string(R.string.fix_all)).assertIsNotEnabled()

        // What it couldn't is left to do one by one, which it says.
        state = state.copy(settingUpInOneTap = false, oneTapFailed = true)
        composeRule.waitForIdle()
        assertShown(string(R.string.fix_failed), string(R.string.fix_one_by_one))
        assertFalse(shown(string(R.string.fix_all)))
    }

    @Test
    fun shizukuStoppedIsSaidWithAWayToStartIt() {
        showList { copy(shizukuStopped = true) }
        click(string(R.string.open_button))
        assertEquals(listOf("shizuku"), done)
        assertShown(string(R.string.fix_one_by_one))

        // Nothing left, there's nothing to do it all with.
        state = state.copy(unapprovedHosts = emptyMap(), canFollowApps = true)
        composeRule.waitForIdle()
        assertEquals(2, checkedOff())
        assertFalse(shown(string(R.string.fix_one_by_one)))
    }

    @Test
    fun theListWaitsForAndroidToSayWhatsBroken() {
        showList { copy(systemStateKnown = false) }
        assertFalse(shown(string(R.string.setup_check_allow)))
        assertShown(string(R.string.fix_title))
    }

    @Test
    fun theFloatingGuideShowsEachStepInTurnThenThatItsDone() {
        composeRule.mainClock.autoAdvance = false
        state = broken.copy(phone = Phone.PIXEL)
        composeRule.setContent { CrosstuneTheme { FloatingGuide(Need.Links, state) } }
        composeRule.mainClock.advanceTimeByFrame()
        assertShown("1/2", tap(R.string.android_add_link))
        composeRule.mainClock.advanceTimeBy(GUIDE_STEP_MS)
        assertShown("2/2", string(R.string.fix_step_tick_then, string(R.string.android_add)))

        state = state.copy(unapprovedHosts = emptyMap())
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        assertShown(string(R.string.practice_back_to, string(R.string.app_name)))
        assertFalse(shown("2/2"))
    }

    @Test
    fun aGuideOfOneStepHasNothingToCount() {
        state = broken.copy(phone = Phone.SAMSUNG)
        composeRule.setContent { CrosstuneTheme { FloatingGuide(Need.Float, state) } }
        composeRule.waitForIdle()
        assertShown(turnOn(R.string.app_name))
        assertFalse(shown("1/1"))
    }

    @Test
    fun aPhoneIsKnownByWhoMadeItOrItsBrand() {
        assertEquals(Phone.PIXEL, Phone.of("Google", "google"))
        assertEquals(Phone.SAMSUNG, Phone.of("samsung", "samsung"))
        assertEquals(Phone.XIAOMI, Phone.of("Xiaomi", "POCO"))
        assertEquals(Phone.XIAOMI, Phone.of("Xiaomi", "Redmi"))
        assertEquals(Phone.OTHER, Phone.of("OnePlus", "OnePlus"))
        // A TV is one whoever made it.
        assertEquals(Phone.TV, Phone.of("Google", "google", tv = true))
    }

    @Test
    fun whatsBrokenIsCountedInTheOrderItsFixed() {
        val state = broken.copy(blockingApps = setOf(spotify), microphone = MicAccess.DENIED)
        // The microphone, even turned down for good, is asked where it's used rather than listed.
        assertEquals(listOf(Need.StopApp(spotify), Need.Links, Need.Follow), state.checks().map { it.need })
        assertEquals(3, state.toFix)
        // Not known yet, nothing is.
        assertEquals(0, state.copy(systemStateKnown = false).toFix)
        // With no links to open, only lyrics are left.
        assertEquals(listOf(Need.Follow), state.copy(intercepted = emptySet()).checks().map { it.need })
        assertEquals("app:${spotify.packageName}", Need.StopApp(spotify).key)
        assertEquals("float", Need.Float.key)
        assertEquals("mic", Need.Microphone.key)
        assertEquals(false, state.done(Need.Float))
    }

    @Test
    fun beforeAndroid12OnlyATestLinkSaysTheLinksWork() {
        val old = UiState(intercepted = setOf(MusicService.SPOTIFY), installedSourceApps = listOf(spotify))
        // Android can't say, so neither is marked.
        assertNull(old.linksAllowed)
        assertNull(old.done(Need.StopApp(spotify)))
        // Just turned on, they're known not to be.
        assertEquals(false, old.copy(showLinkSettingsHelper = true).linksAllowed)
        val tested = old.copy(linksTested = setOf(Need.Links.key, Need.StopApp(spotify).key))
        assertEquals(true, tested.linksAllowed)
        assertEquals(true, tested.done(Need.StopApp(spotify)))
        assertEquals(listOf(spotify), old.appsInTheWay)

        assertEquals("https://open.spotify.com/#$TEST_LINK", testLink(MusicService.SPOTIFY))
        // Any of Bandcamp's sites, made a real one.
        assertEquals("www.bandcamp.com", testHost(MusicService.BANDCAMP))
        assertEquals(MusicService.SPOTIFY, old.testService(Need.StopApp(spotify)))
        assertEquals(MusicService.SPOTIFY, old.testService(Need.Links))
        assertNull(UiState().testService(Need.Links))
    }
}
