package com.astrovm.crosstune

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Clean hearing is a Shizuku or root extra: without their grant, Android's never asked, so it never asks the user. */
@RunWith(RobolectricTestRunner::class)
class PlaybackCaptureTest {
    @Test
    fun withoutShizukuOrRootsGrantNothingIsCapturedOrAsked() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertFalse(PlaybackCapture.allowed(app))
        // What lyrics hear through by default.
        val clean = MainActivity.cleanSoundFactory(app) as PlaybackCapture
        assertNull(clean.open())
        assertNull(shadowOf(app).nextStartedActivity)
    }
}
