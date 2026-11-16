package com.astrovm.crosstune

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject

/** Reads the site's public practice editor while a device session drives the real Activity. */
class ChordWikiBrowserCheck : Instrumentation() {
    @Volatile private var current: MainActivity? = null
    private val requests = ConcurrentHashMap<Int, AtomicInteger>()
    private val hosts = ConcurrentHashMap<String, AtomicInteger>()
    private val creations = AtomicInteger()
    private var pages: ChordWikiPages? = null
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
                    ChordWikiBrowser(host, onRequest = { url ->
                        requests.getOrPut(System.identityHashCode(host)) { AtomicInteger() }.incrementAndGet()
                        val name = android.net.Uri.parse(url).host.orEmpty()
                        hosts.getOrPut(name) { AtomicInteger() }.incrementAndGet()
                    })
                }
            }
            status.writeText(JSONObject().put("state", "ready").toString())
            val deadline = android.os.SystemClock.elapsedRealtime() + 600_000L
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                when (control.takeIf(File::exists)?.readText()?.trim()) {
                    "read" -> {
                        control.delete()
                        val activity = checkNotNull(current) { "Open MainActivity first" }
                        if (pages == null) runOnMainSync { pages = wikiPages(activity) }
                        requests.clear(); hosts.clear()
                        val observation = WikiObservation()
                        val started = android.os.SystemClock.elapsedRealtime()
                        val result = runCatching {
                            runBlocking {
                                withTimeout(25_000L) {
                                    withContext(Dispatchers.Main + observation) {
                                        checkNotNull(pages).page(chordWikiEditUrl("練習用ページ"))
                                    }
                                }
                            }
                        }
                        val body = result.getOrNull()
                        val live = System.identityHashCode(current)
                        status.writeText(JSONObject().put("state", "read")
                            .put("elapsed_ms", android.os.SystemClock.elapsedRealtime() - started)
                            .put("activity_creations", creations.get())
                            .put("live_requests", requests[live]?.get() ?: 0)
                            .put("old_requests", requests.entries.filter { it.key != live }.sumOf { it.value.get() })
                            .put("request_hosts", JSONObject(hosts.mapValues { it.value.get() }))
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
            status.writeText(JSONObject().put("state", "failed").put("error", e.javaClass.simpleName).toString())
        } finally {
            app.unregisterActivityLifecycleCallbacks(events)
            runOnMainSync { MainActivity.chordWikiFactory = oldFactory }
            control.delete()
        }
        finish(code, Bundle())
    }

    private fun wikiPages(activity: MainActivity): ChordWikiPages {
        fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(owner)
        }
        val finder = checkNotNull(field(ViewModelProvider(activity)[MainViewModel::class.java], "chordFinder"))
        return field(finder, "chordWiki") as ChordWikiPages
    }
}
