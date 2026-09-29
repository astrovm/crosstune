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
class ClipboardTileServiceTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun assertOpensClipboardFlow(started: Intent) {
        assertEquals(MainActivity.ACTION_PASTE_FROM_CLIPBOARD, started.action)
        assertEquals(MainActivity.PASTE_ALIAS, started.component?.className)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun tileOpensCrosstuneToReadTheClipboard() {
        val service = Robolectric.buildService(ClipboardTileService::class.java).create().get()
        // Android 14+ hands a PendingIntent to the system's hidden Quick Settings service,
        // which Robolectric doesn't provide, so stand in for it and capture the request.
        var launched: PendingIntent? = null
        val qsService = Class.forName("android.service.quicksettings.IQSService")
        val fakeQsService = Proxy.newProxyInstance(qsService.classLoader, arrayOf(qsService)) { _, method, args ->
            if (method.name == "startActivity") launched = args[1] as PendingIntent
            null
        }
        TileService::class.java.getDeclaredField("mService").apply { isAccessible = true }.set(service, fakeQsService)

        service.onClick()

        assertOpensClipboardFlow(shadowOf(launched!!).savedIntent)
    }

    @Test
    @Config(sdk = [33])
    fun tileUsesTheIntentOverloadBeforeAndroid14() {
        Robolectric.buildService(ClipboardTileService::class.java).create().get().onClick()
        assertOpensClipboardFlow(shadowOf(app).nextStartedActivity)
    }
}
