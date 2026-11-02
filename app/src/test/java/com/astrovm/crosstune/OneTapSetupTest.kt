package com.astrovm.crosstune

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
}
