package com.astrovm.crosstune

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebResourceError
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.ViewModelProvider
import java.io.File
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
    private val creations = AtomicInteger()
    private var pages: ChordWikiPages? = null
    @Volatile private var readId = 0
    private class ProbeRead(val observation: WikiObservation, val capture: WikiProbeCapture, val started: Long)
    @Volatile private var probeRead: ProbeRead? = null
    private val runId = SystemClock.elapsedRealtimeNanos()
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
                    ChordWikiBrowser(host, onPageCreated = page@ { web, observation ->
                        val probe = probeRead?.takeIf { it.observation === observation } ?: return@page
                        val recording = probe.capture
                        val started = probe.started
                        web.webViewClient = MeasuredClient(web.webViewClient, System.identityHashCode(host), recording, started)
                        recording.record { mapOf("type" to "settings_before_load",
                            "t_ms" to SystemClock.elapsedRealtime() - started,
                            "images" to web.settings.loadsImagesAutomatically,
                            "network_images_blocked" to web.settings.blockNetworkImage,
                            "javascript" to web.settings.javaScriptEnabled,
                            "dom_storage" to web.settings.domStorageEnabled,
                            "attached" to web.isAttachedToWindow) }
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
                        val recording = WikiProbeCapture()
                        val observation = WikiObservation()
                        val started = SystemClock.elapsedRealtime()
                        probeRead = ProbeRead(observation, recording, started)
                        val result = runCatching {
                            runBlocking {
                                withTimeout(20_000L) {
                                    withContext(Dispatchers.Main + observation) {
                                        checkNotNull(pages).page(chordWikiEditUrl("練習用ページ"))
                                    }
                                }
                            }
                        }
                        probeRead = null
                        val recorded = recording.close()
                        val body = result.getOrNull()
                        val live = System.identityHashCode(current)
                        status.writeText(JSONObject().put("state", "read").put("run_id", runId).put("request_id", readId)
                            .put("elapsed_ms", android.os.SystemClock.elapsedRealtime() - started)
                            .put("activity_creations", creations.get())
                            .put("live_requests", recorded.activities[live] ?: 0)
                            .put("old_requests", recorded.activities.entries.filter { it.key != live }.sumOf { it.value })
                            .put("request_hosts", JSONObject(recorded.hosts))
                            .put("page_events", JSONArray(recorded.events.map { event ->
                                JSONObject().also { json -> event.forEach { (key, value) -> json.put(key, value) } }
                            }))
                            .put("events_truncated", recorded.truncated)
                            .put("pending_callbacks", recorded.pending)
                            .put("callback_failed", recorded.failed)
                            .put("events_complete", recorded.complete)
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

    /** Decisions come from the production client. This wrapper never changes its policy. */
    private inner class MeasuredClient(
        private val inner: WebViewClient,
        private val owner: Int,
        private val recording: WikiProbeCapture,
        private val started: Long
    ) : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            return recording.resource(owner, { hostName(request.url) }, { response ->
                requestEvent("resource", request) + mapOf(
                    "decision" to if (response == null) "not_intercepted" else "app_response",
                    "t_ms" to SystemClock.elapsedRealtime() - started)
            }) { inner.shouldInterceptRequest(view, request) }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            inner.shouldOverrideUrlLoading(view, request)

        override fun onPageFinished(view: WebView, url: String?) = inner.onPageFinished(view, url)

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            recording.record({
                requestEvent("network_error", request) + mapOf("code" to error.errorCode,
                    "t_ms" to SystemClock.elapsedRealtime() - started)
            }) { inner.onReceivedError(view, request, error) }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            recording.record({
                val check = response.responseHeaders?.entries?.any {
                    it.key.equals("cf-mitigated", true) && it.value.equals("challenge", true)
                } == true
                requestEvent("http_error", request) + mapOf("status" to response.statusCode,
                    "cloudflare_check" to check, "t_ms" to SystemClock.elapsedRealtime() - started)
            }) { inner.onReceivedHttpError(view, request, response) }
        }
    }

    private fun requestEvent(type: String, request: WebResourceRequest): Map<String, Any> {
        val accept = request.requestHeaders.entries.firstOrNull { it.key.equals("Accept", true) }?.value.orEmpty().lowercase()
        val kind = when {
            request.url.host == "challenges.cloudflare.com" && "/ci/" in request.url.path.orEmpty() -> "challenge_image"
            accept.startsWith("image/") -> "image"
            "font/" in accept -> "font"
            request.isForMainFrame -> "document"
            else -> "other"
        }
        return mapOf("type" to type, "host" to hostName(request.url), "kind" to kind,
            "method" to request.method, "main_frame" to request.isForMainFrame)
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
