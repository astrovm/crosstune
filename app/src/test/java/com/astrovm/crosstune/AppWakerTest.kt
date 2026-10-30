package com.astrovm.crosstune

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.MediaSession.Token
import android.os.Bundle
import android.os.Looper
import android.service.media.MediaBrowserService
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [AppWakerTest.Browser::class])
class AppWakerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val waker = MediaBrowserWaker(app)
    private val scope = CoroutineScope(Dispatchers.Main)

    @Before
    fun service() {
        Browser.latest = null
        val browser = ComponentName(PACKAGE, "MusicBrowser")
        shadowOf(app.packageManager).addServiceIfNotPresent(browser)
        shadowOf(app.packageManager).addIntentFilterForService(browser, IntentFilter(MediaBrowserService.SERVICE_INTERFACE))
    }

    @Test
    fun cancellingAWakeDisconnectsTheAppEvenBeforeItAnswers() {
        val job = scope.launch { waker.wake(listOf(PACKAGE)) }
        shadowOf(Looper.getMainLooper()).idle()
        val browser = Browser.latest!!
        assertTrue(browser.connected)
        job.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(browser.disconnected)
        // An answer already on its way cannot reconnect a cancelled request.
        browser.callback.onConnected()
        assertTrue(job.isCancelled)
    }

    @Test
    fun cancellingAnOlderWakeLeavesTheNewerOneAlone() {
        val first = scope.launch { waker.wake(listOf(PACKAGE)) }
        shadowOf(Looper.getMainLooper()).idle()
        val old = Browser.latest!!
        val second = scope.launch { waker.wake(listOf(PACKAGE)) }
        shadowOf(Looper.getMainLooper()).idle()
        val next = Browser.latest!!
        assertTrue(old.disconnected)
        first.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(next.disconnected)
        second.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(next.disconnected)
    }

    @Test
    fun aTimeoutOrRefusalDisconnectsTheApp() {
        var result: Any? = "waiting"
        val job = scope.launch { result = waker.wake(listOf(PACKAGE)) }
        shadowOf(Looper.getMainLooper()).idle()
        val browser = Browser.latest!!
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertTrue(job.isCompleted)
        assertNull(result)
        assertTrue(browser.disconnected)
        val refused = scope.launch { result = waker.wake(listOf(PACKAGE)) }
        shadowOf(Looper.getMainLooper()).idle()
        Browser.latest!!.callback.onConnectionFailed()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(refused.isCompleted)
        assertNull(result)
        assertTrue(Browser.latest!!.disconnected)
    }

    @Test
    fun anAppThatAnswersStaysBoundOnlyLongEnoughToStartPlaying() {
        var result: Any? = null
        val job = scope.launch { result = waker.wake(listOf("com.example.missing", PACKAGE)) }
        shadowOf(Looper.getMainLooper()).idle()
        val browser = Browser.latest!!
        browser.callback.onConnected()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(job.isCompleted)
        assertEquals(android.media.session.MediaController::class.java, result!!::class.java)
        assertFalse(browser.disconnected)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
        assertTrue(browser.disconnected)
    }

    @Test
    fun anAppWithoutABrowserHasNothingToWake() {
        var result: Any? = "waiting"
        val job = scope.launch { result = waker.wake(listOf("com.example.missing")) }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(job.isCompleted)
        assertNull(result)
        assertNull(Browser.latest)
    }

    @Implements(MediaBrowser::class)
    class Browser {
        lateinit var callback: MediaBrowser.ConnectionCallback
        lateinit var token: Token
        var connected = false
        var disconnected = false

        @Implementation
        fun __constructor__(context: Context, service: ComponentName, callback: MediaBrowser.ConnectionCallback, hints: Bundle?) {
            this.callback = callback
            token = MediaSession(context, "test music").sessionToken
            latest = this
        }

        @Implementation
        fun connect() { connected = true }

        @Implementation
        fun disconnect() { disconnected = true }

        @Implementation
        fun getSessionToken(): Token = token

        companion object { var latest: Browser? = null }
    }

    private companion object { const val PACKAGE = "com.example.music" }
}
