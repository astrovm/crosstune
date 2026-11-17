package com.astrovm.crosstune

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebResourceError
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.ViewModelProvider
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.json.JSONArray

/** Reads the site's public practice editor while a device session drives the real Activity. */
class ChordWikiBrowserCheck : Instrumentation() {
    @Volatile private var current: MainActivity? = null
    private val requests = ConcurrentHashMap<Int, AtomicInteger>()
    private val hosts = ConcurrentHashMap<String, AtomicInteger>()
    private val creations = AtomicInteger()
    private var pages: ChordWikiPages? = null
    @Volatile private var readId = 0
    @Volatile private var pageStarted = 0L
    @Volatile private var eventsTruncated = false
    private val runId = SystemClock.elapsedRealtimeNanos()
    private val main = Handler(Looper.getMainLooper())
    private val pageEvents = mutableListOf<JSONObject>()
    private val events = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (activity is MainActivity) current = activity
        }
        override fun onActivityDestroyed(activity: Activity) {
            if (activity === current) current = null
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    }

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val oldFactory = MainActivity.chordWikiFactory
        val app = targetContext.applicationContext as Application
        val status = File(targetContext.cacheDir, "wikicheck-status.json")
        val control = File(targetContext.cacheDir, "wikicheck-control")
        var code = 0
        try {
            require(targetContext.packageName == "com.astrovm.crosstune.dev")
            control.delete()
            app.registerActivityLifecycleCallbacks(events)
            runOnMainSync {
                MainActivity.chordWikiFactory = { host ->
                    creations.incrementAndGet()
                    ChordWikiBrowser(host, screen = {
                        val root = host.window?.decorView as? ViewGroup
                        watch(root)
                        root
                    }, onRequest = { url ->
                        requests.getOrPut(System.identityHashCode(host)) { AtomicInteger() }.incrementAndGet()
                        val name = hostName(Uri.parse(url))
                        hosts.getOrPut(name) { AtomicInteger() }.incrementAndGet()
                    })
                }
            }
            status.writeText(JSONObject().put("state", "ready").put("run_id", runId).toString())
            val deadline = android.os.SystemClock.elapsedRealtime() + 600_000L
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                when (control.takeIf(File::exists)?.readText()?.trim()) {
                    "read" -> {
                        control.delete()
                        readId++
                        status.writeText(JSONObject().put("state", "reading").put("run_id", runId).put("request_id", readId).toString())
                        val activity = checkNotNull(current) { "Open MainActivity first" }
                        if (pages == null) runOnMainSync { pages = wikiPages(activity) }
                        requests.clear(); hosts.clear()
                        synchronized(pageEvents) { pageEvents.clear(); eventsTruncated = false }
                        val observation = WikiObservation()
                        val started = SystemClock.elapsedRealtime()
                        pageStarted = started
                        val result = runCatching {
                            runBlocking {
                                withTimeout(20_000L) {
                                    withContext(Dispatchers.Main + observation) {
                                        checkNotNull(pages).page(chordWikiEditUrl("練習用ページ"))
                                    }
                                }
                            }
                        }
                        val body = result.getOrNull()
                        val live = System.identityHashCode(current)
                        status.writeText(JSONObject().put("state", "read").put("run_id", runId).put("request_id", readId)
                            .put("elapsed_ms", android.os.SystemClock.elapsedRealtime() - started)
                            .put("activity_creations", creations.get())
                            .put("live_requests", requests[live]?.get() ?: 0)
                            .put("old_requests", requests.entries.filter { it.key != live }.sumOf { it.value.get() })
                            .put("request_hosts", JSONObject(hosts.mapValues { it.value.get() }))
                            .put("page_events", synchronized(pageEvents) { JSONArray(pageEvents.toList()) })
                            .put("events_truncated", eventsTruncated)
                            .put("webview_version", WebView.getCurrentWebViewPackage()?.versionName.orEmpty())
                            .put("check", observation.check.name)
                            .put("editor_chars", body?.length ?: 0)
                            .put("error", result.exceptionOrNull()?.javaClass?.simpleName.orEmpty())
                            .toString())
                    }
                    "done" -> { control.delete(); break }
                }
                Thread.sleep(100L)
            }
        } catch (e: Exception) {
            code = 1
            status.writeText(JSONObject().put("state", "failed").put("run_id", runId).put("error", e.javaClass.simpleName).toString())
        } finally {
            app.unregisterActivityLifecycleCallbacks(events)
            runOnMainSync { MainActivity.chordWikiFactory = oldFactory }
            control.delete()
        }
        finish(code, Bundle())
    }

    /** Wrap the page's existing callbacks after open returns. The settings are observed after loadUrl. */
    private fun watch(root: ViewGroup?) {
        main.postAtFrontOfQueue {
            if (root == null) return@postAtFrontOfQueue
            for (index in 0 until root.childCount) {
                val web = root.getChildAt(index) as? WebView ?: continue
                val inner = web.webViewClient
                if (inner is MeasuredClient) continue
                val id = readId
                web.webViewClient = MeasuredClient(inner, id)
                record(id, JSONObject().put("type", "settings_after_load")
                    .put("images", web.settings.loadsImagesAutomatically)
                    .put("network_images_blocked", web.settings.blockNetworkImage)
                    .put("javascript", web.settings.javaScriptEnabled)
                    .put("dom_storage", web.settings.domStorageEnabled)
                    .put("attached", web.isAttachedToWindow))
            }
        }
    }

    /** Decisions come from the production client. This wrapper never changes its policy. */
    private inner class MeasuredClient(private val inner: WebViewClient, private val id: Int) : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val response = inner.shouldInterceptRequest(view, request)
            record(id, requestEvent("resource", request).put("decision", if (response == null) "network" else "app_response"))
            return response
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            inner.shouldOverrideUrlLoading(view, request)

        override fun onPageFinished(view: WebView, url: String?) = inner.onPageFinished(view, url)

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            record(id, requestEvent("network_error", request).put("code", error.errorCode))
            inner.onReceivedError(view, request, error)
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            val check = response.responseHeaders?.entries?.any { it.key.equals("cf-mitigated", true) && it.value.equals("challenge", true) } == true
            record(id, requestEvent("http_error", request).put("status", response.statusCode).put("cloudflare_check", check))
            inner.onReceivedHttpError(view, request, response)
        }
    }

    private fun requestEvent(type: String, request: WebResourceRequest): JSONObject {
        val accept = request.requestHeaders.entries.firstOrNull { it.key.equals("Accept", true) }?.value.orEmpty().lowercase()
        val kind = when {
            request.url.host == "challenges.cloudflare.com" && "/ci/" in request.url.path.orEmpty() -> "challenge_image"
            accept.startsWith("image/") -> "image"
            "font/" in accept -> "font"
            request.isForMainFrame -> "document"
            else -> "other"
        }
        return JSONObject().put("type", type).put("host", hostName(request.url)).put("kind", kind)
            .put("method", request.method).put("main_frame", request.isForMainFrame)
    }

    private fun record(id: Int, event: JSONObject) {
        synchronized(pageEvents) {
            if (id != readId) return
            if (pageEvents.size >= 500) { eventsTruncated = true; return }
            pageEvents += event.put("t_ms", SystemClock.elapsedRealtime() - pageStarted)
        }
    }

    /** No URL, query, path, cookie or unknown host label is kept. */
    private fun hostName(uri: Uri): String = uri.host.takeIf {
        it in setOf("ja.chordwiki.org", "challenges.cloudflare.com", "brunhild.challenges.cloudflare.com")
    } ?: "other"

    private fun wikiPages(activity: MainActivity): ChordWikiPages {
        fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(owner)
        }
        val finder = checkNotNull(field(ViewModelProvider(activity)[MainViewModel::class.java], "chordFinder"))
        return field(finder, "chordWiki") as ChordWikiPages
    }
}
