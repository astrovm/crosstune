package com.astrovm.crosstune

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.TileService
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
class LyricsTileServiceTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun assertListensForLyrics(started: Intent) {
        assertEquals(MainActivity.ACTION_LISTEN, started.action)
        assertEquals(MainActivity.LISTEN_ALIAS, started.component?.className)
        assertTrue(started.getBooleanExtra(MainActivity.EXTRA_LYRICS, false))
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun tileListensForTheSongAndItsLyrics() {
        val service = Robolectric.buildService(LyricsTileService::class.java).create().get()
        // As for the clipboard tile, stand in for the hidden Quick Settings service Android 14 hands the request to.
        var launched: PendingIntent? = null
        val qsService = Class.forName("android.service.quicksettings.IQSService")
        val fakeQsService = Proxy.newProxyInstance(qsService.classLoader, arrayOf(qsService)) { _, method, args ->
            if (method.name == "startActivity") launched = args[1] as PendingIntent
            null
        }
        TileService::class.java.getDeclaredField("mService").apply { isAccessible = true }.set(service, fakeQsService)

        service.onClick()

        assertListensForLyrics(shadowOf(launched!!).savedIntent)
    }

    @Test
    @Config(sdk = [33])
    fun tileUsesTheIntentOverloadBeforeAndroid14() {
        Robolectric.buildService(LyricsTileService::class.java).create().get().onClick()
        assertListensForLyrics(shadowOf(app).nextStartedActivity)
    }
}
