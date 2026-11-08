package com.astrovm.crosstune

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** What one tap sets up through Shizuku or root, and what it leaves alone. */
@RunWith(RobolectricTestRunner::class)
class OneTapSetupTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun withNoShellReadyNothingIsRun() = runBlocking {
        val shell = FakeShell(ready = false)
        assertFalse(OneTapSetup(app, listOf(shell)).setUp(listOf("a.b"), links = true, follow = true, microphone = true, floating = true))
        assertEquals(emptyList<List<String>>(), shell.ran)
    }

    @Test
    fun floatingLyricsAskAboutNotificationsOnlyWhereAndroidDoes() = runBlocking {
        val shell = FakeShell()
        assertTrue(OneTapSetup(app, listOf(shell)).setUp(emptyList(), links = false, follow = false, microphone = false, floating = true))
        assertEquals(listOf("appops", "appops", "pm"), shell.ran.map { it.first() })
        assertTrue(shell.ran.last().contains(android.Manifest.permission.POST_NOTIFICATIONS))
    }

    @Test
    @Config(sdk = [32])
    fun beforeAndroid13FloatingLyricsNeedNoNotificationPermission() = runBlocking {
        val shell = FakeShell()
        assertTrue(OneTapSetup(app, listOf(shell)).setUp(emptyList(), links = false, follow = false, microphone = false, floating = true))
        assertEquals(listOf("appops", "appops"), shell.ran.map { it.first() })
    }

    @Test
    fun anySetupLetsLyricsHearMusicAppsCleanlyWithoutAndroidAsking() = runBlocking {
        val shell = FakeShell()
        assertTrue(OneTapSetup(app, listOf(shell)).setUp(emptyList(), links = false, follow = false, microphone = false, floating = false))
        val user = (android.os.Process.myUid() / 100_000).toString()
        assertEquals(listOf(listOf("appops", "set", "--user", user, app.packageName, "android:project_media", "allow")), shell.ran)
    }

    @Test
    @Config(sdk = [31])
    fun onAndroid12OneTapStillSetsThingsUp() = runBlocking {
        val shell = FakeShell()
        val setup = OneTapSetup(app, listOf(shell))
        assertTrue(setup.available())
        assertTrue(setup.setUp(emptyList(), links = false, follow = false, microphone = false, floating = false))
        assertEquals(listOf("appops"), shell.ran.map { it.first() })
    }

    @Test
    @Config(sdk = [30])
    fun beforeAndroid12ThereIsNoOneTap() = runBlocking {
        val shell = FakeShell()
        val setup = OneTapSetup(app, listOf(shell))
        assertFalse(setup.available())
        assertFalse(setup.root())
        assertFalse(setup.shizukuStopped())
        assertFalse(setup.setUp(emptyList(), links = true, follow = true, microphone = true, floating = true))
        assertEquals(emptyList<List<String>>(), shell.ran)
    }

    @Test
    @Config(sdk = [31])
    fun shizukuStoppedIsOnlyWhenItIsInstalledAndNothingElseCanSetUp() {
        val stopped = OneTapSetup(app, listOf(FakeShell(ready = false)))
        assertFalse(stopped.shizukuStopped())
        installShizuku()
        assertTrue(OneTapSetup(app, listOf(FakeShell(ready = false))).shizukuStopped())
        // A shell that's ready does the setup itself, so Shizuku being there isn't a stop.
        assertFalse(OneTapSetup(app, listOf(FakeShell(ready = true))).shizukuStopped())
    }

    @Test
    @Config(sdk = [33])
    fun onAndroid13FloatingLyricsAskAboutNotifications() = runBlocking {
        val shell = FakeShell()
        assertTrue(OneTapSetup(app, listOf(shell)).setUp(emptyList(), links = false, follow = false, microphone = false, floating = true))
        assertTrue(shell.ran.last().contains(android.Manifest.permission.POST_NOTIFICATIONS))
    }

    private fun installShizuku() {
        val shizuku = OneTapSetup.SHIZUKU_PACKAGE
        shadowOf(app.packageManager).installPackage(installedApp(shizuku, "Shizuku"))
        val main = ComponentName(shizuku, "$shizuku.MainActivity")
        shadowOf(app.packageManager).addActivityIfNotPresent(main)
        shadowOf(app.packageManager).addIntentFilterForActivity(main, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
    }
}
