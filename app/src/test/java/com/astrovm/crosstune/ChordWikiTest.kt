package com.astrovm.crosstune

import android.app.Activity
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.IOException
import java.time.Duration

/** ChordWiki's pages, and the browser that reads them. Runs under Robolectric for the web view. */
@RunWith(RobolectricTestRunner::class)
class ChordWikiTest {
    @Test
    fun addressesUseTheSheetTheEditorAndTheSearch() {
        assertEquals("https://ja.chordwiki.org/wiki.cgi?c=edit&t=Human+System", chordWikiEditUrl("Human System"))
        assertEquals("https://ja.chordwiki.org/search.html?q=Human+System+TM", chordWikiSearchUrl("Human System TM"))
        assertEquals("https://ja.chordwiki.org/wiki/Human+System", chordWikiViewUrl("Human System"))
        assertTrue(chordWikiEditUrl("川の道").contains("%E5%B7%9D"))
    }

    @Test
    fun aScriptResultIsTheStringItReturned() {
        assertNull(jsResult(null))
        assertNull(jsResult("null"))
        assertEquals("{}", jsResult("\"{}\""))
        assertNull(jsResult("not json"))
        assertEquals("", jsResult("\"\""))
        val parsed = chordWikiSeen("""{"href":"https://ja.chordwiki.org/","title":"川","text":"あ","source":"[C]","hasEditor":true,"links":"x"}""")
        assertEquals("[C]", parsed.source)
        assertEquals("x", parsed.links)
        assertTrue(parsed.hasEditor)
    }

    @Test
    fun aLookTellsASheetFromACheckAndFromAPageThatHasntLoaded() {
        val sheet = seen(source = "[C]川", hasEditor = true)
        assertEquals("[C]川", (chordWikiLook(sheet, timedOut = false) as ChordWikiLook.Ready).body)
        // An empty editor is the page, with nothing on it, not a check that failed.
        assertEquals("", (chordWikiLook(seen(source = "", hasEditor = true, text = "Just a moment"), timedOut = true) as ChordWikiLook.Ready).body)
        assertEquals(ChordWikiLook.Waiting, chordWikiLook(seen(href = "about:blank"), timedOut = false))
        assertEquals(ChordWikiLook.Waiting, chordWikiLook(seen(href = ""), timedOut = false))
        assertThrows { chordWikiLook(seen(href = "about:blank"), timedOut = true) }
        assertEquals(ChordWikiLook.Waiting, chordWikiLook(seen(title = "Just a moment...", text = "Checking your browser"), timedOut = false))
        assertEquals(ChordWikiLook.Waiting, chordWikiLook(seen(title = "Verify you are human", text = "I'm not a robot"), timedOut = false))
        assertThrows("click") { chordWikiLook(seen(title = "Verify you are human"), timedOut = true) }
        assertThrows("didn't finish") { chordWikiLook(seen(text = "しばらくお待ちください"), timedOut = true) }
        val links = "川の道 - 歩く人\thttps://ja.chordwiki.org/wiki/kawa"
        assertEquals(links, (chordWikiLook(seen(links = links), timedOut = false) as ChordWikiLook.Ready).body)
        val long = "あ".repeat(201)
        assertEquals("", (chordWikiLook(seen(text = long), timedOut = false) as ChordWikiLook.Ready).body)
        // 200 words is still a page that hasn't arrived. One more, and it's the page.
        assertEquals(ChordWikiLook.Waiting, chordWikiLook(seen(title = "", text = "あ".repeat(200)), timedOut = false))
        assertEquals(ChordWikiLook.Waiting, chordWikiLook(seen(text = "short"), timedOut = false))
        assertThrows("empty") { chordWikiLook(seen(text = "short"), timedOut = true) }
        assertEquals(ChordWikiCheck.NONE, chordWikiCheck("川の道", "川の道を歩いていく"))
        assertEquals(ChordWikiCheck.INTERACTIVE, chordWikiCheck("", "人間であることを確認"))
        assertEquals(ChordWikiCheck.AUTOMATIC, chordWikiCheck("", "Enable JavaScript"))
    }

    @Test
    fun searchHitsAreTheSongsListedAndTheArtistsOwnComesFirst() {
        val lines = """
            not a hit
            example - someone	https://example.com/wiki-no
            	https://ja.chordwiki.org/wiki/blank
            川の道	https://ja.chordwiki.org/wiki/bare
            海の歌 - 歩く人	https://ja.chordwiki.org/wiki/umi
            川の道 - 他の人	https://ja.chordwiki.org/wiki/other
            川の道 - 歩く人	https://ja.chordwiki.org/wiki/kawa
        """.trimIndent()
        val hits = chordWikiHits(lines)
        assertEquals(listOf("川の道", "海の歌", "川の道", "川の道"), hits.map { it.title })
        assertEquals("歩く人", hits.last().artist)
        assertEquals("https://ja.chordwiki.org/wiki/kawa", chordWikiBest(hits, "川の道", "歩く人", "Kawa")?.href)
        // No credit sits ahead of a different singer, and a different song is never the one.
        assertEquals("https://ja.chordwiki.org/wiki/bare", chordWikiBest(hits, "川の道", "誰も", "川の道")?.href)
        assertNull(chordWikiBest(hits, "ない", "歩く人", "ない"))

        val html = """
            <a href="/elsewhere">elsewhere</a>
            <a class="hit" href="https://ja.chordwiki.org/wiki.cgi?c=view&amp;t=Lemon">Lemon - 誰か</a>
            <a href="https://ja.chordwiki.org/wiki/kawa">川の道 - 歩く人</a>
        """.trimIndent()
        assertEquals(listOf("Lemon", "川の道"), chordWikiHits(html).map { it.title })
        assertTrue(chordWikiHits(html).first().href.contains("&"))
        assertEquals("誰か", chordWikiHits(html).first().artist)
        // The title we were given doesn't match. The song's other name does, and that sheet is the one.
        val hitsByOtherName = listOf(
            ChordWikiHit("別の歌", "歩く人", "https://ja.chordwiki.org/wiki/other"),
            ChordWikiHit("川の道", "歩く人", "https://ja.chordwiki.org/wiki/kawa")
        )
        assertEquals("https://ja.chordwiki.org/wiki/kawa", chordWikiBest(hitsByOtherName, "Kawa", "歩く人", "川の道")?.href)
        // No credit still beats another singer when that other sheet is listed first.
        val blankAfterSomeoneElse = listOf(
            ChordWikiHit("川の道", "他の人", "https://ja.chordwiki.org/wiki/other"),
            ChordWikiHit("川の道", "", "https://ja.chordwiki.org/wiki/bare")
        )
        assertEquals("https://ja.chordwiki.org/wiki/bare", chordWikiBest(blankAfterSomeoneElse, "川の道", "歩く人", "川の道")?.href)
    }

    @Test
    fun theBrowserReadsTheEditorFromBehindTheScreen() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        runBlocking(Dispatchers.Main.immediate) {
            try {
                ChordWikiBrowser(activity, screen = { null }).page("https://ja.chordwiki.org/")
                fail()
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("nowhere"))
            }
        }
        val root = activity.window.decorView as ViewGroup
        CookieManager.getInstance().setAcceptCookie(false)
        val done = open(activity, gapMs = 20, waitMs = 10_000)
        val web = findWeb(root)
        assertEquals(0f, web.alpha)
        assertEquals(0, root.indexOfChild(web))
        assertFalse(web.isClickable)
        assertFalse(web.isFocusable)
        assertFalse(web.isFocusableInTouchMode)
        assertTrue(CookieManager.getInstance().acceptCookie())
        assertTrue(web.settings.javaScriptEnabled)
        assertTrue(web.settings.domStorageEnabled)
        assertTrue(web.settings.loadsImagesAutomatically)
        assertFalse(web.settings.blockNetworkImage)
        assertTrue(shadowOf(web).webViewClient.shouldInterceptRequest(web, resource("https://ja.chordwiki.org/icon.png", "image/png")) == null)
        assertFalse(web.settings.userAgentString.orEmpty().contains("Macintosh"))
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, web.importantForAccessibility)
        assertEquals("https://ja.chordwiki.org/wiki.cgi?c=edit", shadowOf(web).lastLoadedUrl)
        answer(web, page(source = "[C]川の道", hasEditor = true))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))
        assertEquals("[C]川の道", runBlocking { done.await() }.getOrThrow())
        assertNull(web.parent)
    }

    @Test
    fun aMeasurementLeavesPicturesAndFontsUnloaded() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val asked = mutableListOf<String>()
        val done = CompletableDeferred<Result<String>>()
        CoroutineScope(Dispatchers.Main).launch {
            done.complete(runCatching {
                ChordWikiBrowser(activity, gapMs = 20, waitMs = 10_000, loadPictures = false, onRequest = { asked += it })
                    .page("https://ja.chordwiki.org/wiki.cgi?c=edit")
            })
        }
        shadowOf(Looper.getMainLooper()).idle()
        val web = findWeb(activity.window.decorView)
        assertFalse(web.settings.loadsImagesAutomatically)
        assertTrue(web.settings.blockNetworkImage)
        val client = shadowOf(web).webViewClient
        asked.clear()
        val picture = resource("https://ja.chordwiki.org/icon.png", "image/png")
        val font = resource("https://ja.chordwiki.org/face.woff2", "font/woff2")
        val document = resource("https://ja.chordwiki.org/wiki.cgi?c=edit", "text/html")
        assertTrue(client.shouldInterceptRequest(web, picture) != null)
        assertTrue(client.shouldInterceptRequest(web, font) != null)
        assertTrue(client.shouldInterceptRequest(web, document) == null)
        assertEquals(
            listOf("https://ja.chordwiki.org/icon.png", "https://ja.chordwiki.org/face.woff2", "https://ja.chordwiki.org/wiki.cgi?c=edit"),
            asked
        )
        answer(web, page(source = "[C]川の道", hasEditor = true))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))
        assertEquals("[C]川の道", runBlocking { done.await() }.getOrThrow())
    }

    @Test
    fun aPageThatNeverArrivesAndACheckAPersonMustClickAreGivenUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = activity.window.decorView as ViewGroup
        var clock = 0L
        val stalled = open(activity, now = { clock }, gapMs = 60_000, waitMs = 10)
        clock = 20
        answer(findWeb(root), null)
        shadowOf(Looper.getMainLooper()).idle()
        val stalledError = runBlocking { stalled.await() }.exceptionOrNull()
        assertTrue(stalledError is IOException && stalledError.message!!.contains("didn't finish loading"))

        clock = 0
        val garbage = open(activity, now = { clock }, gapMs = 60_000, waitMs = 10)
        clock = 20
        answer(findWeb(root), JSONObject.quote("not an object"))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(runBlocking { garbage.await() }.exceptionOrNull() is IOException)

        clock = 0
        val checking = open(activity, now = { clock }, gapMs = 60_000, waitMs = 10)
        clock = 20
        answer(findWeb(root), page(title = "Just a moment...", text = "Checking your browser"))
        shadowOf(Looper.getMainLooper()).idle()
        val check = runBlocking { checking.await() }.exceptionOrNull()
        assertTrue(check is IOException && check.message!!.contains("didn't finish"))

        clock = 0
        val clicked = open(activity, now = { clock }, gapMs = 60_000, waitMs = 10)
        clock = 20
        answer(findWeb(root), page(title = "Verify you are human", text = "cf-turnstile"))
        shadowOf(Looper.getMainLooper()).idle()
        val click = runBlocking { clicked.await() }.exceptionOrNull()
        assertTrue(click is IOException && click.message!!.contains("click"))
    }

    @Test
    fun aPageThatIsStillLoadingIsReadWhenItArrives() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = activity.window.decorView as ViewGroup
        var clock = 0L
        val done = open(activity, now = { clock }, gapMs = 20, waitMs = 10_000)
        val web = findWeb(root)
        answer(web, page(href = "about:blank"))
        // Not timed out, so it waits and asks again, then the editor is there.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))
        shadowOf(web).lastEvaluatedJavascriptCallback.onReceiveValue(page(source = "[C]川", hasEditor = true))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("[C]川", runBlocking { done.await() }.getOrThrow())
        assertNull(web.parent)
    }

    /** onPageFinished never runs. The delayed look still reads the editor. */
    @Test
    fun aPageThatNeverFinishesLoadingIsStillRead() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = activity.window.decorView as ViewGroup
        val done = open(activity, gapMs = 20, waitMs = 10_000)
        val web = findWeb(root)
        assertNull(shadowOf(web).lastEvaluatedJavascriptCallback)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))
        val script = shadowOf(web).lastEvaluatedJavascript
        assertTrue(script, script.orEmpty().contains("textarea"))
        shadowOf(web).lastEvaluatedJavascriptCallback.onReceiveValue(page(source = "[C]川", hasEditor = true))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("[C]川", runBlocking { done.await() }.getOrThrow())
    }

    /**
     * A check that hasn't used up the wait is asked again, from a clock that didn't start at zero.
     * Exactly the wait, and it gives up.
     */
    @Test
    fun aCheckThatHasNotTimedOutIsAskedAgain() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = activity.window.decorView as ViewGroup
        var clock = 100L
        val done = open(activity, now = { clock }, gapMs = 20, waitMs = 10)
        val web = findWeb(root)
        answer(web, page(title = "Just a moment...", text = "Checking your browser"))
        val first = shadowOf(web).lastEvaluatedJavascriptCallback
        assertFalse(done.isCompleted)
        // Five milliseconds in. Adding the clock to the start would already call this too late.
        clock = 105
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))
        val second = shadowOf(web).lastEvaluatedJavascriptCallback
        assertTrue(first !== second)
        second.onReceiveValue(page(title = "Just a moment...", text = "Checking your browser"))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(done.isCompleted)
        clock = 110
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))
        val third = shadowOf(web).lastEvaluatedJavascriptCallback
        assertTrue(second !== third)
        third.onReceiveValue(page(title = "Just a moment...", text = "Checking your browser"))
        shadowOf(Looper.getMainLooper()).idle()
        val error = runBlocking { done.await() }.exceptionOrNull()
        assertTrue(error is IOException && error.message!!.contains("didn't finish"))
    }

    @Test
    fun aResultAfterThePageIsGoneIsIgnored() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = activity.window.decorView as ViewGroup
        val job = CoroutineScope(Dispatchers.Main).launch { ChordWikiBrowser(activity, gapMs = 60_000, waitMs = 60_000).page("https://ja.chordwiki.org/") }
        shadowOf(Looper.getMainLooper()).idle()
        val web = findWeb(root)
        shadowOf(web).webViewClient.onPageFinished(web, shadowOf(web).lastLoadedUrl)
        val callback = shadowOf(web).lastEvaluatedJavascriptCallback
        job.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        callback.onReceiveValue(page(source = "[C]川", hasEditor = true))
        assertNull(web.parent)
        assertTrue(job.isCancelled)
    }

    @Test
    fun closingTheLookupRemovesThePage() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = activity.window.decorView as ViewGroup
        val job = CoroutineScope(Dispatchers.Main).launch { ChordWikiBrowser(activity, gapMs = 60_000, waitMs = 60_000).page("https://ja.chordwiki.org/") }
        shadowOf(Looper.getMainLooper()).idle()
        val web = findWeb(root)
        job.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(web.parent)
        assertTrue(shadowOf(web).wasDestroyCalled())
    }

    /**
     * The first look already ran. A page that is still waiting has to ask again on its own,
     * or the editor that arrives later is never read.
     */
    @Test
    fun aPageThatIsStillWaitingIsAskedAgainOnItsOwn() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = activity.window.decorView as ViewGroup
        var clock = 100L
        val done = open(activity, now = { clock }, gapMs = 20, waitMs = 10_000)
        val web = findWeb(root)
        web.webViewClient = WebViewClient()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))
        val first = shadowOf(web).lastEvaluatedJavascriptCallback
        first.onReceiveValue(page(href = "about:blank"))
        assertFalse(done.isCompleted)
        clock = 105
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))
        val second = shadowOf(web).lastEvaluatedJavascriptCallback
        assertTrue(first !== second)
        second.onReceiveValue(page(source = "[C]川", hasEditor = true))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("[C]川", runBlocking { done.await() }.getOrThrow())
    }

    /** No injected clock. The phone's own clock is what decides the page took too long. */
    @Test
    fun thePhonesOwnClockGivesUpWhenThePageNeverAnswers() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = activity.window.decorView as ViewGroup
        val done = CompletableDeferred<Result<String>>()
        val browser = ChordWikiBrowser(activity, gapMs = 10, waitMs = 40)
        CoroutineScope(Dispatchers.Main).launch { done.complete(runCatching { browser.page("https://ja.chordwiki.org/wiki.cgi?c=edit") }) }
        shadowOf(Looper.getMainLooper()).idle()
        val web = findWeb(root)
        web.webViewClient = WebViewClient()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        val callback = shadowOf(web).lastEvaluatedJavascriptCallback
        callback.onReceiveValue(page(title = "Just a moment...", text = "Checking your browser"))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(done.isCompleted)
        val error = runBlocking { done.await() }.exceptionOrNull()
        assertTrue(error is IOException && error.message!!.contains("didn't finish"))
    }

    /** ChordFinder cancels ChordWiki from a worker when another site already has the sheet. */
    @Test
    fun cancellingFromAnotherThreadRemovesThePage() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val decor = activity.window.decorView as ViewGroup
        val root = object : FrameLayout(activity) {
            override fun removeView(child: View) {
                check(Looper.myLooper() == Looper.getMainLooper()) { "wrong thread" }
                super.removeView(child)
            }
        }
        decor.addView(root)
        val job = CoroutineScope(Dispatchers.Main).launch {
            ChordWikiBrowser(activity, screen = { root }, gapMs = 60_000, waitMs = 60_000).page("https://ja.chordwiki.org/")
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(findWebOrNull(root) != null)
        var failed: Throwable? = null
        val finished = CountDownLatch(1)
        Thread {
            try {
                runBlocking {
                    job.cancel()
                    job.join()
                }
            } catch (e: Throwable) {
                failed = e
            } finally {
                finished.countDown()
            }
        }.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!finished.await(20, TimeUnit.MILLISECONDS) && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
        }
        shadowOf(Looper.getMainLooper()).idle()
        if (!finished.await(0, TimeUnit.MILLISECONDS)) fail("cancel did not finish")
        if (failed != null) fail(failed.toString())
        assertNull(findWebOrNull(root))
    }

    private fun resource(url: String, accept: String): WebResourceRequest = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = false
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = mapOf("Accept" to accept)
    }

    private fun open(activity: Activity, gapMs: Long, waitMs: Long, now: () -> Long = { 0L }): CompletableDeferred<Result<String>> {
        val done = CompletableDeferred<Result<String>>()
        val browser = ChordWikiBrowser(activity, now = now, gapMs = gapMs, waitMs = waitMs)
        CoroutineScope(Dispatchers.Main).launch { done.complete(runCatching { browser.page("https://ja.chordwiki.org/wiki.cgi?c=edit") }) }
        shadowOf(Looper.getMainLooper()).idle()
        return done
    }

    private fun answer(web: WebView, value: String?) {
        // Robolectric only runs the page callbacks on the next load after this is set, and posts them.
        // Calling the client here is the same moment, without a second load.
        shadowOf(web).webViewClient.onPageFinished(web, shadowOf(web).lastLoadedUrl)
        shadowOf(web).lastEvaluatedJavascriptCallback.onReceiveValue(value)
    }

    private fun page(title: String = "川の道", text: String = "川の道", source: String = "", hasEditor: Boolean = false, href: String = "https://ja.chordwiki.org/wiki.cgi?c=edit"): String =
        JSONObject.quote(JSONObject().put("href", href).put("title", title).put("text", text).put("source", source).put("hasEditor", hasEditor).put("links", "").toString())

    private fun findWeb(view: View): WebView = findWebOrNull(view) ?: error("no web view")

    private fun findWebOrNull(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWebOrNull(view.getChildAt(it)) }
        else -> null
    }

    private fun seen(href: String = "https://ja.chordwiki.org/", title: String = "川", text: String = "", source: String = "", hasEditor: Boolean = false, links: String = "") =
        ChordWikiSeen(href, title, text, source, hasEditor, links)

    private fun assertThrows(part: String = "ChordWiki", block: () -> Unit) {
        try {
            block()
            fail()
        } catch (e: IOException) {
            assertTrue(e.message, part in e.message!!)
        }
    }
}
