package com.astrovm.crosstune

import android.content.Context
import android.webkit.WebResourceRequest
import android.net.Uri
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The small YouTube player in its WebView: set up to play on its own, loaded once per video, and telling how a line went. */
@RunWith(RobolectricTestRunner::class)
class YouTubeLinePlayerTest {
    private val player = YouTubeLinePlayer(ApplicationProvider.getApplicationContext<Context>())
    private val web get() = player.view() as WebView
    private val ends = mutableListOf<LineEnd>()

    @Test
    fun itPlaysOnItsOwnWithoutAnyoneTouchingIt() {
        assertTrue(web.settings.javaScriptEnabled)
        assertTrue(!web.settings.mediaPlaybackRequiresUserGesture)
    }

    @Test
    fun aVideoIsLoadedOnceAndItsNextLinesAreOnlyToldToThePage() {
        player.show("abc", 10_000, 15_000, 200_000, play = false) { ends += it }
        val loaded = shadowOf(web).lastLoadDataWithBaseURL
        assertEquals(LinePlayerPage.BASE_URL, loaded.baseUrl)
        assertEquals(LinePlayerPage.html("abc", LinePlayerPage.call(10_000, 15_000, 200_000, false)), loaded.data)
        assertEquals("text/html", loaded.mimeType)
        assertEquals("utf-8", loaded.encoding)
        // The same video again is told the line, not loaded again.
        player.show("abc", 20_000, 25_000, 200_000, play = true) { ends += it }
        assertEquals(LinePlayerPage.call(20_000, 25_000, 200_000, true), shadowOf(web).lastEvaluatedJavascript)
        assertEquals(loaded, shadowOf(web).lastLoadDataWithBaseURL)
        // Another video is loaded.
        player.show("xyz", 1_000, 2_000, null, play = true) { ends += it }
        assertEquals(LinePlayerPage.html("xyz", LinePlayerPage.call(1_000, 2_000, null, true)), shadowOf(web).lastLoadDataWithBaseURL.data)
    }

    @Test
    fun pausingTellsThePageToPauseIfItHasWoken() {
        player.pause()
        assertEquals("if (typeof pause == 'function') pause()", shadowOf(web).lastEvaluatedJavascript)
    }

    @Test
    fun thePageSaysHowTheLineWentAndAPageThatFailsToLoadIsFailed() {
        player.show("abc", 0, 1_000, null, play = true) { ends += it }
        val page = shadowOf(web).getJavascriptInterface("Line")
        page.javaClass.getMethod("ended").apply { isAccessible = true }.invoke(page)
        page.javaClass.getMethod("failed").apply { isAccessible = true }.invoke(page)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(listOf(LineEnd.ENDED, LineEnd.FAILED), ends)
        // Only the page itself failing counts, not something it loads.
        val client = shadowOf(web).webViewClient
        client.onReceivedError(web, request(mainFrame = false), error)
        assertEquals(2, ends.size)
        client.onReceivedError(web, request(mainFrame = true), error)
        assertEquals(listOf(LineEnd.ENDED, LineEnd.FAILED, LineEnd.FAILED), ends)
    }

    private val error = android.webkit.FakeWebResourceError()

    private fun request(mainFrame: Boolean) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse("https://www.youtube.com/")
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
