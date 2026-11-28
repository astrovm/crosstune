package com.astrovm.crosstune

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.Application
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
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

    /** A no another test left is gone, as the tests share it. */
    @Before
    fun withNoNoYet() = PlaybackCapture.forget()

    /** The grant is back as a phone has it, and a no is forgotten, whatever a test left, as the tests share them. */
    @After
    fun withoutTheGrant() {
        PhoneAppOps.projectMedia = AppOpsManager.MODE_DEFAULT
        PlaybackCapture.forget()
    }

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

    @Test
    fun coveringAnAppThatOnlyPlaysInFrontItAsksNothingAsTheAskWouldStopIt() {
        PhoneAppOps.projectMedia = AppOpsManager.MODE_ALLOWED
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val capture = PlaybackCapture(app)
        val listening = Executors.newSingleThreadExecutor()
        FloatingLyricsService.covering = true
        try {
            assertNull(capture.open())
            assertNull(shadowOf(app).nextStartedActivity)
            // The cover gone, the next listen asks again: under it, that wasn't taken as a no.
            FloatingLyricsService.covering = false
            val heard = listening.submit<Recording?> { capture.open() }
            val asked = waitFor { shadowOf(app).nextStartedActivity }
            assertEquals(CaptureAsk::class.java.name, asked.component?.className)
            val ask = Robolectric.buildActivity(CaptureAsk::class.java, asked).create().get()
            shadowOf(ask).receiveResult(shadowOf(ask).nextStartedActivityForResult.intent, Activity.RESULT_CANCELED, null)
            assertNull(heard.get(1, TimeUnit.SECONDS))
        } finally {
            FloatingLyricsService.covering = false
            listening.shutdownNow()
        }
    }

    @Test
    fun keptFromInFrontItAsksAndOutOfSightOrWithoutTheGrantItAsksNothing() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val inFront = PlaybackCapture.inFront
        val listening = Executors.newSingleThreadExecutor()
        try {
            // In a test, as in front, Android says the process is in front.
            assertTrue(inFront())
            // Without Shizuku's or root's grant, Android would ask the user.
            PlaybackCapture.keep(app)
            assertNull(shadowOf(app).nextStartedActivity)
            // Out of sight, the ask would open over whatever app is in front.
            PhoneAppOps.projectMedia = AppOpsManager.MODE_ALLOWED
            PlaybackCapture.inFront = { false }
            PlaybackCapture.keep(app)
            assertNull(shadowOf(app).nextStartedActivity)
            // In front with the grant, it asks, through the screen that shows nothing.
            PlaybackCapture.inFront = { true }
            val kept = listening.submit { PlaybackCapture.keep(app) }
            val asked = waitFor { shadowOf(app).nextStartedActivity }
            assertEquals(CaptureAsk::class.java.name, asked.component?.className)
            val ask = Robolectric.buildActivity(CaptureAsk::class.java, asked).create().get()
            shadowOf(ask).receiveResult(shadowOf(ask).nextStartedActivityForResult.intent, Activity.RESULT_CANCELED, null)
            kept.get(1, TimeUnit.SECONDS)
            // Nothing was given, so there's nothing to let go of.
            PlaybackCapture.letGo()
        } finally {
            PlaybackCapture.inFront = inFront
            listening.shutdownNow()
        }
    }

    @Test
    fun keptItStaysWhileNothingListensAndGoesAMinuteAfterItsLetGoOf() {
        PhoneAppOps.projectMedia = AppOpsManager.MODE_ALLOWED
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val listening = Executors.newSingleThreadExecutor()
        try {
            val kept = listening.submit { PlaybackCapture.keep(app) }
            val asked = waitFor { shadowOf(app).nextStartedActivity }
            val ask = Robolectric.buildActivity(CaptureAsk::class.java, asked).create().get()
            // Android gives it, to the service that holds it.
            val given = Intent().putExtras(Bundle().apply { putBinder("android.media.projection.extra.EXTRA_MEDIA_PROJECTION", Binder()) })
            shadowOf(ask).receiveResult(shadowOf(ask).nextStartedActivityForResult.intent, Activity.RESULT_OK, given)
            val holds = shadowOf(app).nextStartedService
            assertEquals(CaptureService::class.java.name, holds.component?.className)
            Robolectric.buildService(CaptureService::class.java, holds).create().startCommand(0, 1)
            kept.get(1, TimeUnit.SECONDS)
            // Kept again, as under the same cover, it asks nothing more.
            shadowOf(app).clearNextStartedActivities()
            PlaybackCapture.keep(app)
            assertNull(shadowOf(app).nextStartedActivity)
            // Long after a listen would have let it go, it's still held.
            ShadowLooper.idleMainLooper(5, TimeUnit.MINUTES)
            assertNull(shadowOf(app).nextStoppedService)
            // Let go of, it goes as after any last listen: a minute on.
            PlaybackCapture.letGo()
            ShadowLooper.idleMainLooper(59, TimeUnit.SECONDS)
            assertNull(shadowOf(app).nextStoppedService)
            ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
            assertEquals(CaptureService::class.java.name, shadowOf(app).nextStoppedService.component?.className)
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
