package com.astrovm.crosstune

import android.app.Instrumentation
import android.app.Activity
import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.Job
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject

/** Supplies a fake backend while an external device session drives the real app. */
class OfflineChordCheck : Instrumentation() {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    private val filesLock = Any()
    private var closed = false
    private var realNetwork = false
    private val networkEvents = CopyOnWriteArrayList<String>()
    private lateinit var status: File
    private lateinit var control: File
    @Volatile private var recovered = false
    @Volatile private var state = "ready"
    private var model: MainViewModel? = null
    private var observedActivity: Activity? = null
    private var emptyRound = 0
    @Volatile private var offlineRound = 0
    @Volatile private var offlinePending = false
    @Volatile private var offlineIdle = false
    @Volatile private var failedFetchComplete = false
    private val activityEvents = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (activity is MainActivity && activity !== observedActivity) {
                resetObservation()
                observedActivity = activity
                model = ViewModelProvider(activity)[MainViewModel::class.java]
            }
        }
        override fun onActivityDestroyed(activity: Activity) {
            if (activity === observedActivity) resetObservation()
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        realNetwork = arguments?.getString("network") == "real"
        start()
    }

    override fun onStart() {
        val oldHttp = MainActivity.httpClientFactory
        val oldConnection = MainActivity.connectionFactory
        val oldWiki = MainActivity.chordWikiFactory
        val result = Bundle()
        val application = targetContext.applicationContext as Application
        val connectivity = targetContext.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = event("available $network")
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                event("capabilities $network internet=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} unmetered=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)}")
            override fun onLost(network: Network) = event("lost $network")
        }
        var watchingNetwork = false
        var usingVpn = false
        var code = 0
        try {
            require(targetContext.packageName == "com.astrovm.crosstune.dev") { "This fixture needs the dev app" }
            check(targetContext.getSystemService(ConnectivityManager::class.java).activeNetwork == null) {
                "Disconnect device networks before this isolated fixture"
            }
            application.registerActivityLifecycleCallbacks(activityEvents)
            status = File(targetContext.cacheDir, "chordcheck-status.json")
            control = File(targetContext.cacheDir, "chordcheck-control")
            control.delete()
            val network = FixtureConnection()
            val connection = if (realNetwork) object : Connection {
                private val native = AndroidConnection(targetContext)
                override fun online() = native.online()
                override fun unmetered() = native.unmetered().onEach { event("unmetered $it default=${connectivity.activeNetwork}") }
            } else network
            val client = OkHttpClient.Builder().addInterceptor { chain -> answer(chain.request()) }.build()
            runOnMainSync {
                MainActivity.httpClientFactory = { client }
                MainActivity.connectionFactory = { connection }
                MainActivity.chordWikiFactory = { ChordWikiPages { "" } }
            }
            seed()
            if (realNetwork) {
                connectivity.registerDefaultNetworkCallback(callback)
                watchingNetwork = true
            }
            writeStatus()
            val end = SystemClock.elapsedRealtime() + if (realNetwork) 900_000 else 180_000
            while (SystemClock.elapsedRealtime() < end && state != "done") {
                observeOfflineFetch()
                writeStatus()
                when (control.takeIf(File::isFile)?.readText()?.trim()) {
                    "recover", "drecover" -> if (!recovered) {
                        check(failedFetchComplete) { "Wait for offline_failed_complete before recovery" }
                        recovered = true
                        state = "recovered"
                        if (!realNetwork) runBlocking { network.recover() }
                        writeStatus()
                    }
                    "vpn" -> {
                        check(realNetwork && recovered) { "Recover the backend before switching the real network" }
                        control.delete()
                        vpn(true)
                        usingVpn = true
                    }
                    "done" -> state = "done"
                }
                Thread.sleep(100)
            }
            if (state != "done") { state = "deadline"; code = 1 }
            result.putString("result", snapshot().toString())
        } catch (error: Exception) {
            code = 1
            result.putString("error", error.toString())
        } finally {
            if (watchingNetwork) connectivity.unregisterNetworkCallback(callback)
            if (usingVpn) vpn(false)
            runOnMainSync {
                application.unregisterActivityLifecycleCallbacks(activityEvents)
                resetObservation()
                MainActivity.httpClientFactory = oldHttp
                MainActivity.connectionFactory = oldConnection
                MainActivity.chordWikiFactory = oldWiki
            }
            synchronized(filesLock) {
                closed = true
                if (::control.isInitialized) control.delete()
                if (::status.isInitialized) {
                    status.delete()
                    File(status.path + ".tmp").delete()
                }
            }
        }
        finish(code, result)
    }

    private fun event(what: String) {
        networkEvents += "${SystemClock.elapsedRealtime()} $what"
    }

    private fun vpn(start: Boolean) {
        targetContext.sendBroadcast(Intent(if (start) FixtureVpnControl.START else FixtureVpnControl.STOP)
            .setComponent(ComponentName("${targetContext.packageName}.test", FixtureVpnControl::class.java.name)))
    }

    private fun seed() {
        val prefs = targetContext.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("setup_complete", true).putBoolean("lyrics_chords", true).putBoolean("auto_reports", false).commit()
        check(File(targetContext.noBackupFilesDir, KEPT_OFFLINE_DIR).deleteRecursively()) { "Could not clear pinned fixture data" }
        check(targetContext.cacheDir.deleteRecursively()) { "Could not clear fixture caches" }
        check(targetContext.cacheDir.isDirectory || targetContext.cacheDir.mkdirs()) { "Could not create fixture cache" }
        HistoryStore(prefs).replace(listOf(HistoryEntry(
            MusicLink(MusicService.SPOTIFY, ItemType.TRACK, "0000000000000000000000", "https://open.spotify.com/track/0000000000000000000000"),
            MusicMetadata(TITLE, ARTIST, lengthMs = 60_000)
        )))
    }

    private fun answer(request: Request): Response {
        val host = request.url.host
        counts.getOrPut(host) { AtomicInteger() }.incrementAndGet()
        try {
            val body = when (host) {
                "lrclib.net" -> JSONArray().put(JSONObject().put("trackName", TITLE).put("artistName", ARTIST).put("duration", 60)
                    .put("syncedLyrics", "[00:00.00] Paper lanterns light the road\n[00:10.00] Morning brings us home")).toString()
                "www.ultimate-guitar.com" -> {
                    if (!recovered) throw IOException("Fixture chord service unavailable")
                    val data = JSONObject().put("store", JSONObject().put("page", JSONObject().put("data", JSONObject().put("results", JSONArray()))))
                    """<div class="js-store" data-content="${data.toString().replace("&", "&amp;").replace("\"", "&quot;")}"></div>"""
                }
                "solr.sscdn.co" -> "cb({\"response\":{\"docs\":[]}})"
                "itunes.apple.com" -> "{\"results\":[]}"
                "music.163.com" -> "{\"result\":{}}"
                else -> ""
            }
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(body.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
        } finally {
            writeStatus()
        }
    }

    private fun resetObservation() {
        model = null
        observedActivity = null
        emptyRound = 0
        offlineRound = 0
        offlinePending = false
        offlineIdle = false
        failedFetchComplete = false
    }

    private fun observeOfflineFetch() = runOnMainSync {
        model?.let { current ->
            offlineRound = current.keptOfflineRounds
            if (current.uiState.keptOffline.isEmpty()) emptyRound = offlineRound
            offlinePending = current.uiState.keptOffline.singleOrNull()?.done == false
            val field = MainViewModel::class.java.getDeclaredField("keepingOffline").apply { isAccessible = true }
            offlineIdle = (field.get(current) as? Job)?.isCompleted == true
            failedFetchComplete = offlinePending && offlineRound > emptyRound && offlineIdle
        }
    }

    private fun snapshot() = JSONObject().put("offline_round", offlineRound)
        .put("offline_pending", offlinePending).put("offline_idle", offlineIdle).put("offline_failed_complete", failedFetchComplete)
        .put("status", state).put("recovered", recovered).put("requests", JSONObject().apply {
        counts.toSortedMap().forEach { (host, count) -> put(host, count.get()) }
    }).put("network_events", JSONArray(networkEvents.toList()))

    private fun writeStatus() = synchronized(filesLock) {
        if (closed) return@synchronized
        val temporary = File(status.path + ".tmp")
        temporary.writeText(snapshot().toString())
        check(temporary.renameTo(status)) { "Could not write fixture status" }
    }

    private class FixtureConnection : Connection {
        private val online = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 2)
        private val unmetered = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 2)
        init { online.tryEmit(true); unmetered.tryEmit(true) }
        override fun online() = online.asSharedFlow()
        override fun unmetered() = unmetered.asSharedFlow()
        suspend fun recover() { unmetered.emit(false); unmetered.emit(true) }
    }

    companion object {
        private const val TITLE = "Paper Lantern Sky"
        private const val ARTIST = "The Fixture Owls"
    }
}
