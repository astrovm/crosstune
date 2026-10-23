package com.astrovm.crosstune

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.provider.Settings
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowSettings
import java.io.File
import java.time.Duration

/** What's broken, from the bar on the main screen: the list of it, fixed in one tap or one by one, with the guide floating over Android's pages. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
class FixCenterTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeSpotify()
    private val playback = FakePlayback()
    private var controller: ActivityController<MainActivity>? = null
    private var guide: ActivityController<GuideActivity>? = null

    private val spotify = MusicService.SPOTIFY.packageName
    private val spotifyHosts = LinkInterception.HOSTS.getValue(MusicService.SPOTIFY)

    /** What Android lets Crosstune open: none of Spotify's links until a test allows them. */
    private var allowed = false

    /** Spotify's app, once installed, still opening its links until a test stops it. */
    private var appKeepsLinks = true

    @Before
    fun setUp() {
        MainActivity.httpClientFactory = { fake.client() }
        MainActivity.systemDispatcher = Dispatchers.Unconfined
        MainActivity.lookupDispatcher = Dispatchers.Unconfined
        MainActivity.playbackFactory = { playback }
        MainActivity.shellFactory = { emptyList() }
        MainActivity.phoneFactory = { Phone.PIXEL }
        MainActivity.checksForUpdates = { false }
        prefs().edit().putBoolean("setup_complete", true).commit()
        File(app.cacheDir, "lookups.json").delete()
        // Before Android 12 there's nothing to ask.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            FakeDomainVerification.installPerPackage(app, linkHandlingAllowed = { it != spotify || appKeepsLinks }) { packageName ->
                when {
                    packageName == spotify -> spotifyHosts.associateWith { DomainVerificationUserState.DOMAIN_STATE_VERIFIED }
                    allowed -> spotifyHosts.associateWith { DomainVerificationUserState.DOMAIN_STATE_SELECTED }
                    else -> emptyMap()
                }
            }
        }
        LinkInterception(app).setEnabled(MusicService.SPOTIFY, true)
    }

    @After
    fun tearDown() {
        runCatching { guide?.pause()?.stop()?.destroy() }
        runCatching { controller?.pause()?.stop()?.destroy() }
        MainActivity.httpClientFactory = ::httpClient
        MainActivity.systemDispatcher = Dispatchers.Default
        MainActivity.lookupDispatcher = Dispatchers.IO
        MainActivity.playbackFactory = ::MediaSessionPlayback
        MainActivity.shellFactory = { listOf(ShizukuShell(), RootShell()) }
        MainActivity.phoneFactory = { Phone.of(android.os.Build.MANUFACTURER, android.os.Build.BRAND) }
        MainActivity.checksForUpdates = ::looksForUpdates
        GuideActivity.host = null
        ShadowSettings.setCanDrawOverlays(false)
    }

    private fun prefs() = app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun string(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun launch(): MainActivity {
        controller = Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_MAIN).setClass(app, MainActivity::class.java)).setup()
        composeRule.waitForIdle()
        return controller!!.get()
    }

    /** Back from Android's settings, where things may have changed. */
    private fun comeBack() {
        controller!!.pause().resume()
        composeRule.waitForIdle()
    }

    private fun waitUntil(condition: () -> Boolean) =
        composeRule.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); condition() }

    private fun shown(text: String) = composeRule.onAllNodes(hasText(text) or hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty()

    private fun assertShown(vararg texts: String) = texts.forEach { assertTrue("'$it' not shown", shown(it)) }

    private fun assertAbsent(vararg texts: String) = texts.forEach { assertFalse("'$it' shown", shown(it)) }

    private fun click(text: String) {
        composeRule.onNode(hasText(text) or hasContentDescription(text)).performClick()
        composeRule.waitForIdle()
    }

    private fun barShown() = composeRule.onAllNodesWithTag(FIX_BAR_TAG).fetchSemanticsNodes().isNotEmpty()

    private fun openFixes() {
        composeRule.onNodeWithTag(FIX_BAR_TAG).performClick()
        composeRule.waitForIdle()
    }

    private fun checkedOff() = composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.setup_done))).fetchSemanticsNodes().size

    private fun nextStarted(): Intent? = shadowOf(app).nextStartedActivity

    private fun allowMicrophone() = shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

    /** Answers Android's question for the microphone, which was just asked. */
    private fun answerMicrophone(granted: Boolean) {
        val activity = controller!!.get()
        val asked = shadowOf(activity).lastRequestedPermission
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), asked.requestedPermissions.toList())
        if (granted) allowMicrophone()
        activity.onRequestPermissionsResult(asked.requestCode, asked.requestedPermissions, intArrayOf(if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED))
        composeRule.waitForIdle()
    }

    private fun installSpotify() {
        shadowOf(app.packageManager).installPackage(installedApp(spotify, "Spotify"))
    }

    /** Shizuku doing what it's told, as Android would. */
    private fun doingShell(root: Boolean = false) = FakeShell(root = root) { command ->
        when {
            "false" in command -> appKeepsLinks = false
            "all" in command -> allowed = true
            "allow_listener" in command -> playback.access = true
            Manifest.permission.RECORD_AUDIO in command -> allowMicrophone()
        }
        true
    }

    private val user get() = (android.os.Process.myUid() / 100_000).toString()

    @Test
    fun theBarCountsWhatsBrokenOpensTheListAndGoesOnceItsAllFixed() {
        launch()
        // The links, the microphone and following music apps: none allowed yet. It can't be closed.
        composeRule.onNodeWithTag(FIX_BAR_TAG).assert(hasText("3"))
        assertAbsent(string(R.string.dismiss_button))
        openFixes()
        assertShown(string(R.string.setup_check_allow), string(R.string.fix_mic_title), string(R.string.follow_help_title), string(R.string.fix_one_by_one))
        assertEquals(0, checkedOff())

        allowMicrophone()
        playback.access = true
        comeBack()
        assertEquals(2, checkedOff())
        click(string(R.string.back_button))
        composeRule.onNodeWithTag(FIX_BAR_TAG).assert(hasText("1"))

        allowed = true
        comeBack()
        assertFalse(barShown())
    }

    @Test
    fun oneByOneEachOpensAndroidsPageAndTheNextShowsOnceItsDone() {
        val activity = launch()
        openFixes()
        click(string(R.string.fix_one_by_one))
        assertShown(string(R.string.setup_step, 1, 3), string(R.string.setup_check_allow))
        click(string(R.string.fix_open_settings))
        val links = nextStarted()!!
        assertEquals(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, links.action)
        assertEquals(Uri.parse("package:${activity.packageName}"), links.data)

        allowed = true
        comeBack()
        waitUntil { shown(string(R.string.setup_step, 2, 3)) }
        // Android still asks for the microphone itself.
        assertShown(string(R.string.fix_mic_title))
        click(string(R.string.allow_button))
        answerMicrophone(granted = true)
        waitUntil { shown(string(R.string.setup_step, 3, 3)) }

        click(string(R.string.fix_open_settings))
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, nextStarted()!!.action)
        playback.access = true
        comeBack()
        // All done, it's back to the list, all checked off.
        waitUntil { checkedOff() == 3 }
        assertAbsent(string(R.string.fix_one_by_one))
        click(string(R.string.back_button))
        assertFalse(barShown())
    }

    @Test
    fun skippedOnesAreLeftListedAndTheStepsCloseBackToTheList() {
        launch()
        openFixes()
        click(string(R.string.fix_one_by_one))
        repeat(3) { click(string(R.string.setup_skip_for_now)) }
        assertShown(string(R.string.fix_one_by_one))
        assertEquals(0, checkedOff())

        click(string(R.string.fix_mic_title))
        assertShown(string(R.string.allow_button))
        click(string(R.string.back_button))
        assertShown(string(R.string.fix_one_by_one))
        click(string(R.string.back_button))
        assertTrue(barShown())
    }

    @Test
    fun theMicrophoneTurnedDownForGoodIsAllowedInAppInfo() {
        val activity = launch()
        openFixes()
        click(string(R.string.fix_mic_title))
        click(string(R.string.allow_button))
        // Turned down with nothing more to say, Android won't ask again: it's in App info now.
        answerMicrophone(granted = false)
        assertShown(string(R.string.fix_step_tap, string(R.string.android_permissions)))
        assertAbsent(string(R.string.error_microphone))
        click(string(R.string.fix_open_settings))
        val appInfo = nextStarted()!!
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appInfo.action)
        assertEquals(Uri.parse("package:${activity.packageName}"), appInfo.data)

        allowMicrophone()
        comeBack()
        waitUntil { checkedOff() == 1 }
        assertFalse(prefs().contains("microphone_denied"))
    }

    @Test
    fun turnedDownOnceAndroidStillAsks() {
        launch()
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO, true)
        openFixes()
        click(string(R.string.fix_mic_title))
        click(string(R.string.allow_button))
        answerMicrophone(granted = false)
        assertShown(string(R.string.allow_button))
        assertFalse(prefs().getBoolean("microphone_denied", false))
    }

    @Test
    fun anAppInTheWayIsListedFirstWithItsOwnPage() {
        installSpotify()
        launch()
        composeRule.onNodeWithTag(FIX_BAR_TAG).assert(hasText("4"))
        openFixes()
        click(string(R.string.fix_one_by_one))
        assertShown(string(R.string.setup_step, 1, 4), string(R.string.setup_check_app, "Spotify"))
        click(string(R.string.open_app_link_settings_button, "Spotify"))
        val started = nextStarted()!!
        assertEquals(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, started.action)
        assertEquals(Uri.parse("package:$spotify"), started.data)

        appKeepsLinks = false
        comeBack()
        waitUntil { shown(string(R.string.setup_step, 2, 4)) }
    }

    @Test
    fun withShizukuItsAllFixedInOneTap() {
        installSpotify()
        val shell = doingShell()
        MainActivity.shellFactory = { listOf(shell) }
        launch()
        openFixes()
        assertShown(string(R.string.fix_with_shizuku))
        click(string(R.string.fix_all))
        assertEquals(
            listOf(
                // The app in the way first, since Android won't let Crosstune take links it verified.
                listOf("pm", "set-app-links-allowed", "--user", user, "--package", spotify, "false"),
                listOf("pm", "set-app-links-allowed", "--user", user, "--package", app.packageName, "true"),
                listOf("pm", "set-app-links-user-selection", "--user", user, "--package", app.packageName, "true", "all"),
                listOf("cmd", "notification", "allow_listener", ComponentName(app, NowPlayingListener::class.java).flattenToString(), user),
                listOf("pm", "grant", "--user", user, app.packageName, Manifest.permission.RECORD_AUDIO)
            ),
            shell.ran
        )
        waitUntil { checkedOff() == 4 }
        assertAbsent(string(R.string.fix_all))
        click(string(R.string.back_button))
        assertFalse(barShown())
    }

    @Test
    fun withRootItSaysSo() {
        MainActivity.shellFactory = { listOf(FakeShell(ready = false), doingShell(root = true)) }
        launch()
        openFixes()
        assertShown(string(R.string.fix_with_root))
    }

    @Test
    fun whatOneTapCouldntDoIsLeftToDoOneByOne() {
        val shell = FakeShell { command -> if ("all" in command) allowed = true; "allow_listener" !in command }
        MainActivity.shellFactory = { listOf(shell) }
        launch()
        openFixes()
        click(string(R.string.fix_all))
        waitUntil { checkedOff() == 1 }
        assertShown(string(R.string.fix_failed), string(R.string.fix_one_by_one))
        click(string(R.string.fix_one_by_one))
        assertShown(string(R.string.setup_step, 1, 2))
        // Back on the list from the bar, it tries again.
        click(string(R.string.back_button))
        click(string(R.string.back_button))
        openFixes()
        assertShown(string(R.string.fix_all))
    }

    @Test
    fun shizukuStoppedIsSaidUntilItsStartedAgain() {
        val shizuku = OneTapSetup.SHIZUKU_PACKAGE
        shadowOf(app.packageManager).installPackage(installedApp(shizuku, "Shizuku"))
        val main = ComponentName(shizuku, "$shizuku.MainActivity")
        shadowOf(app.packageManager).addActivityIfNotPresent(main)
        shadowOf(app.packageManager).addIntentFilterForActivity(main, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        val shell = doingShell().apply { ready = false }
        MainActivity.shellFactory = { listOf(shell) }
        launch()
        openFixes()
        assertShown(string(R.string.fix_shizuku_stopped), string(R.string.fix_one_by_one))
        click(string(R.string.open_button))
        assertEquals(shizuku, nextStarted()!!.component!!.packageName)

        shell.ready = true
        shell.changed!!()
        composeRule.waitForIdle()
        assertAbsent(string(R.string.fix_shizuku_stopped))
        assertShown(string(R.string.fix_all))
    }

    @Test
    fun aSourcesAllowOpensItsStepsOrAllowsItInOneTap() {
        launch()
        click(string(R.string.settings_button))
        click(string(R.string.settings_links_title))
        composeRule.onNode(hasText(string(R.string.service_spotify))).performScrollTo().performClick()
        composeRule.waitForIdle()
        click(string(R.string.allow_button))
        assertShown(string(R.string.setup_check_allow), string(R.string.fix_open_settings))
        // Opened from the source, the steps close back to it.
        click(string(R.string.back_button))
        assertAbsent(string(R.string.fix_open_settings))
        assertShown(string(R.string.allow_button))
    }

    @Test
    fun aSourceAllowedInOneTapNeedsNoSteps() {
        val shell = doingShell()
        MainActivity.shellFactory = { listOf(shell) }
        launch()
        click(string(R.string.settings_button))
        click(string(R.string.settings_links_title))
        composeRule.onNode(hasText(string(R.string.service_spotify))).performScrollTo().performClick()
        composeRule.waitForIdle()
        click(string(R.string.allow_button))
        assertEquals(2, shell.ran.size)
        assertAbsent(string(R.string.fix_open_settings), string(R.string.setup_not_allowed))
    }

    @Test
    fun aSourceOneTapCouldntAllowShowsItsStepsSayingSo() {
        MainActivity.shellFactory = { listOf(FakeShell { false }) }
        launch()
        click(string(R.string.settings_button))
        click(string(R.string.settings_links_title))
        composeRule.onNode(hasText(string(R.string.service_spotify))).performScrollTo().performClick()
        composeRule.waitForIdle()
        click(string(R.string.allow_button))
        assertShown(string(R.string.fix_failed), string(R.string.fix_open_settings))
    }

    @Test
    fun followingMusicAppsHeldBackShowsTheWayThroughAppInfo() {
        playback.restricted = true
        val activity = launch()
        openFixes()
        click(string(R.string.follow_help_title))
        click(string(R.string.follow_help_app_info))
        val appInfo = nextStarted()!!
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appInfo.action)
        assertEquals(Uri.parse("package:${activity.packageName}"), appInfo.data)
    }

    @Test
    fun androidsLinkPageMissingOpensAppInfo() {
        shadowOf(app).checkActivities(true)
        val details = ComponentName("com.android.settings", "com.android.settings.AppDetails")
        shadowOf(app.packageManager).addActivityIfNotPresent(details)
        shadowOf(app.packageManager).addIntentFilterForActivity(
            details,
            IntentFilter(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("package")
            }
        )
        launch()
        openFixes()
        click(string(R.string.setup_check_allow))
        click(string(R.string.fix_open_settings))
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, nextStarted()!!.action)
        // Nor is there a page for one app's notification access, or any.
        click(string(R.string.setup_skip_for_now))
        click(string(R.string.follow_help_title))
        click(string(R.string.fix_open_settings))
        assertNull(nextStarted())
    }

    @Test
    @Config(sdk = [30])
    fun beforeAndroid12ATestLinkSaysTheLinksReachCrosstune() {
        installSpotify()
        launch()
        openFixes()
        // Android can't say whether Spotify's app takes them, so it isn't marked, nor counted.
        assertShown(string(R.string.setup_check_app, "Spotify"))
        click(string(R.string.setup_check_allow))
        click(string(R.string.fix_open_settings))
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, nextStarted()!!.action)
        click(string(R.string.fix_try_link))
        val test = nextStarted()!!
        assertEquals(Intent.ACTION_VIEW, test.action)
        assertEquals("https://open.spotify.com/#$TEST_LINK", test.dataString)

        // It reached Crosstune, which doesn't look it up: the links work, so Spotify's app doesn't take them.
        controller!!.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse(test.dataString)).setClass(app, MainActivity::class.java))
        composeRule.waitForIdle()
        assertTrue(fake.requestedUrls.isEmpty())
        waitUntil { checkedOff() == 2 }
    }

    @Test
    fun theGuideFloatsOverAndroidsPageAndBringsCrosstuneBackOnceDone() {
        installSpotify()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        launch()
        openFixes()
        click(string(R.string.setup_check_app, "Spotify"))
        click(string(R.string.open_app_link_settings_button, "Spotify"))
        val floating = startGuide()
        // Floating, it opens Android's page under it, in a task of its own.
        floating.get().onPictureInPictureModeChanged(true, Configuration())
        val page = nextStarted()!!
        assertEquals(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, page.action)
        assertEquals(Uri.parse("package:$spotify"), page.data)
        assertTrue(page.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertShown(string(R.string.fix_step_pick, string(R.string.android_in_your_browser)))

        // Done, Crosstune is asked back, and says so while Android doesn't let it.
        appKeepsLinks = false
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        val back = nextStarted()!!
        assertEquals(MainActivity::class.java.name, back.component!!.className)
        assertTrue(back.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
        composeRule.waitForIdle()
        assertShown(string(R.string.practice_back_to, string(R.string.app_name)))
        assertFalse(floating.get().isFinishing)

        // Back, the guide is done with.
        backToCrosstune()
        assertTrue(floating.get().isFinishing)
    }

    @Test
    fun theGuideMadeBigOrClosedIsDoneWith() {
        installSpotify()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        launch()
        openFixes()
        click(string(R.string.setup_check_app, "Spotify"))
        click(string(R.string.open_app_link_settings_button, "Spotify"))
        var floating = startGuide()
        // Crosstune back before the page opened leaves it be.
        backToCrosstune()
        assertFalse(floating.get().isFinishing)
        floating.get().onPictureInPictureModeChanged(true, Configuration())
        nextStarted()
        floating.get().onPictureInPictureModeChanged(false, Configuration())
        assertEquals(MainActivity::class.java.name, nextStarted()!!.component!!.className)
        assertTrue(floating.get().isFinishing)
        // Already going, it isn't brought back twice.
        floating.get().onPictureInPictureModeChanged(false, Configuration())
        assertNull(nextStarted())

        click(string(R.string.open_app_link_settings_button, "Spotify"))
        floating = startGuide()
        // Closed by the user.
        floating.pause().stop()
        assertTrue(floating.get().isFinishing)
    }

    @Test
    fun theGuideWithNothingToShowJustGoes() {
        // Made again by Android after Crosstune ended, or with no steps open.
        GuideActivity.host = null
        val orphan = Robolectric.buildActivity(GuideActivity::class.java).setup()
        assertTrue(orphan.get().isFinishing)

        GuideActivity.host = object : GuideHost {
            override val state = UiState()
            override val dark = false
            override fun refresh() = Unit
            override fun open(guide: Context?) = Unit
            override fun back(guide: Context) = Unit
        }
        assertTrue(Robolectric.buildActivity(GuideActivity::class.java).setup().get().isFinishing)
    }

    @Test
    fun theGuideMadeAgainAfterTurningJustGoes() {
        installSpotify()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        launch()
        openFixes()
        click(string(R.string.setup_check_app, "Spotify"))
        click(string(R.string.open_app_link_settings_button, "Spotify"))
        val floating = startGuide()
        floating.recreate()
        assertTrue(floating.get().isFinishing)
    }

    @Test
    @Config(shadows = [NoPictureInPicture::class])
    fun withPictureInPictureOffAndroidsPageJustOpens() {
        installSpotify()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        launch()
        openFixes()
        click(string(R.string.setup_check_app, "Spotify"))
        click(string(R.string.open_app_link_settings_button, "Spotify"))
        NoPictureInPicture.throws = false
        assertTrue(startGuide().get().isFinishing)
        assertEquals(Uri.parse("package:$spotify"), nextStarted()!!.data)
        // Some phones say so by throwing.
        backToCrosstune()
        click(string(R.string.open_app_link_settings_button, "Spotify"))
        NoPictureInPicture.throws = true
        assertTrue(startGuide().get().isFinishing)
        assertEquals(Uri.parse("package:$spotify"), nextStarted()!!.data)
    }

    @Test
    fun withoutPictureInPictureAndroidsPageOpensFromCrosstune() {
        launch()
        openFixes()
        click(string(R.string.follow_help_title))
        click(string(R.string.fix_open_settings))
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, nextStarted()!!.action)
    }

    @Test
    fun closedForGoodCrosstuneLetsTheGuideGo() {
        installSpotify()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        val activity = launch()
        openFixes()
        click(string(R.string.setup_check_app, "Spotify"))
        click(string(R.string.open_app_link_settings_button, "Spotify"))
        assertTrue(GuideActivity.host != null)
        activity.finish()
        controller!!.pause().stop().destroy()
        controller = null
        assertNull(GuideActivity.host)
    }

    private fun backToCrosstune() {
        controller!!.restart().resume()
        composeRule.waitForIdle()
    }

    /** The guide Crosstune just asked for, started as Android would. */
    private fun startGuide(): ActivityController<GuideActivity> {
        val intent = nextStarted()!!
        assertEquals(GuideActivity::class.java.name, intent.component!!.className)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        controller!!.pause().stop()
        return Robolectric.buildActivity(GuideActivity::class.java, intent).setup().also { guide = it }
    }
}

/** A phone or user that won't let Crosstune float: it says no, or, on some phones, [throws]. */
@Implements(Activity::class)
class NoPictureInPicture : ShadowActivity() {
    @Implementation
    override fun enterPictureInPictureMode(params: PictureInPictureParams): Boolean {
        if (throws) throw IllegalStateException("No picture-in-picture")
        return false
    }

    companion object {
        var throws = false
    }
}
