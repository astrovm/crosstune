package com.astrovm.crosstune

import android.app.Application
import android.app.AlarmManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/** What one tap sets up through Shizuku or root, and what it leaves alone. */
@RunWith(RobolectricTestRunner::class)
class OneTapSetupTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun withNoShellReadyNothingIsRun() = runBlocking {
        val shell = FakeShell(ready = false)
        assertFalse(OneTapSetup(app, listOf(shell)).setUp(listOf("a.b"), links = true, follow = true, microphone = true, floating = true, exact = false))
        assertEquals(emptyList<List<String>>(), shell.ran)
    }

    @Test
    fun floatingLyricsAskAboutNotificationsOnlyWhereAndroidDoes() = runBlocking {
        val shell = FakeShell()
        assertTrue(OneTapSetup(app, listOf(shell)).setUp(emptyList(), links = false, follow = false, microphone = false, floating = true, exact = false))
        assertEquals(listOf("appops", "appops", "pm"), shell.ran.map { it.first() })
        assertTrue(shell.ran.last().contains(android.Manifest.permission.POST_NOTIFICATIONS))
    }

    @Test
    @Config(sdk = [32])
    fun beforeAndroid13FloatingLyricsNeedNoNotificationPermission() = runBlocking {
        val shell = FakeShell()
        assertTrue(OneTapSetup(app, listOf(shell)).setUp(emptyList(), links = false, follow = false, microphone = false, floating = true, exact = false))
        assertEquals(listOf("appops", "appops"), shell.ran.map { it.first() })
    }

    @Test
    fun anySetupLetsLyricsHearMusicAppsCleanlyWithoutAndroidAsking() = runBlocking {
        val shell = FakeShell()
        assertTrue(OneTapSetup(app, listOf(shell)).setUp(emptyList(), links = false, follow = false, microphone = false, floating = false, exact = false))
        val user = (android.os.Process.myUid() / 100_000).toString()
        assertEquals(listOf(listOf("appops", "set", "--user", user, app.packageName, "android:project_media", "allow")), shell.ran)
    }

    @Test
    fun aUidDenialIsClearedBeforeExactAccessIsReportedAsAllowed() = runBlocking {
        var uidMode = "deny"
        var packageMode = "allow"
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val shell = FakeShell { command ->
            if (OneTapSetup.EXACT_ALARM_OP in command) {
                if ("--uid" in command) uidMode = "allow" else packageMode = "allow"
                ShadowAlarmManager.setCanScheduleExactAlarms(uidMode == "allow" || uidMode == "default" && packageMode == "allow")
            }
            true
        }
        assertTrue(OneTapSetup(app, listOf(shell)).setUp(emptyList(), links = false, follow = false, microphone = false, floating = false, exact = true))
        assertTrue(app.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
    }

    @Test
    fun aSuccessfulCommandThatLeavesExactAccessDeniedIsNotAWorkingFix() = runBlocking {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(OneTapSetup(app, listOf(FakeShell())).setUp(emptyList(), links = false, follow = false, microphone = false, floating = false, exact = true))
    }

    @Test
    fun aLateReminderGetsAlarmsAndRemindersAllowedLikeAndroidsSwitch() = runBlocking {
        val shell = FakeShell { command ->
            if (OneTapSetup.EXACT_ALARM_OP in command) ShadowAlarmManager.setCanScheduleExactAlarms(true)
            true
        }
        assertTrue(OneTapSetup(app, listOf(shell)).setUp(emptyList(), links = false, follow = false, microphone = false, floating = false, exact = true))
        val user = (android.os.Process.myUid() / 100_000).toString()
        assertEquals(listOf("appops", "set", "--user", user, "--uid", app.packageName, "SCHEDULE_EXACT_ALARM", "allow"), shell.ran.last())
        assertEquals(2, shell.ran.size)
    }
}
