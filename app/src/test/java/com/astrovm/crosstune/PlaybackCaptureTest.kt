package com.astrovm.crosstune

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Clean hearing is a Shizuku or root extra: without their grant, Android's never asked, so it never asks the user. */
@RunWith(RobolectricTestRunner::class)
class PlaybackCaptureTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun withoutShizukuOrRootsGrantNothingIsCapturedOrAsked() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertFalse(PlaybackCapture.allowed(app))
        // What lyrics hear through by default.
        val clean = MainActivity.cleanSoundFactory(app) as PlaybackCapture
        assertNull(clean.open())
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun itsAllowedOnlyWithTheGrantAndTheMicrophoneBoth() {
        PhoneAppOps.projectMedia = AppOpsManager.MODE_ALLOWED
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(PlaybackCapture.allowed(app))
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertFalse(PlaybackCapture.allowed(app))
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        PhoneAppOps.projectMedia = AppOpsManager.MODE_IGNORED
        assertFalse(PlaybackCapture.allowed(app))
    }

    @Test
    fun aProjectionAndroidWontGiveIsntAskedForAgainOnEachListen() {
        PhoneAppOps.projectMedia = AppOpsManager.MODE_ALLOWED
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val capture = PlaybackCapture(app)
        val listening = Executors.newSingleThreadExecutor()
        try {
            val heard = listening.submit<Recording?> { capture.open() }
            // Allowed, it asks Android, through a screen that shows nothing.
            val asked = waitFor { shadowOf(app).nextStartedActivity }
            assertEquals(CaptureAsk::class.java.name, asked.component?.className)
            val ask = Robolectric.buildActivity(CaptureAsk::class.java, asked).create().get()
            val request = shadowOf(ask).nextStartedActivityForResult
            // Android says no, and the listen goes on without it, without waiting out the ask.
            shadowOf(ask).receiveResult(request.intent, Activity.RESULT_CANCELED, null)
            assertNull(heard.get(1, TimeUnit.SECONDS))
            assertTrue(ask.isFinishing)
            // The next listen hears through the visuals' tap at once, asking nothing.
            shadowOf(app).clearNextStartedActivities()
            assertNull(capture.open())
            assertNull(shadowOf(app).nextStartedActivity)
        } finally {
            listening.shutdownNow()
        }
    }

    /** What [find] finds, once it does, while the main thread runs. */
    private fun <T : Any> waitFor(find: () -> T?): T {
        repeat(200) {
            ShadowLooper.idleMainLooper()
            find()?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("Never found")
    }
}
