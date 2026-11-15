package com.astrovm.crosstune

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
    private lateinit var status: File
    private lateinit var control: File
    @Volatile private var recovered = false
    @Volatile private var state = "ready"

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val oldHttp = MainActivity.httpClientFactory
        val oldConnection = MainActivity.connectionFactory
        val result = Bundle()
        var code = 0
        try {
            require(targetContext.packageName == "com.astrovm.crosstune.dev") { "This fixture needs the dev app" }
            status = File(targetContext.cacheDir, "chordcheck-status.json")
            control = File(targetContext.cacheDir, "chordcheck-control")
            control.delete()
            val network = FixtureConnection()
            val client = OkHttpClient.Builder().addInterceptor { chain -> answer(chain.request()) }.build()
            runOnMainSync {
                MainActivity.httpClientFactory = { client }
                MainActivity.connectionFactory = { network }
            }
            seed()
            writeStatus()
            val end = SystemClock.elapsedRealtime() + 180_000
            while (SystemClock.elapsedRealtime() < end && state != "done") {
                when (control.takeIf(File::isFile)?.readText()?.trim()) {
                    "recover", "drecover" -> if (!recovered) {
                        recovered = true
                        state = "recovered"
                        runBlocking { network.recover() }
                        writeStatus()
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
            runOnMainSync {
                MainActivity.httpClientFactory = oldHttp
                MainActivity.connectionFactory = oldConnection
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

    private fun seed() {
        val prefs = targetContext.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("setup_complete", true).putBoolean("lyrics_chords", true).putBoolean("auto_reports", false).commit()
        File(targetContext.noBackupFilesDir, "$KEPT_OFFLINE_DIR/songs.json").delete()
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

    private fun snapshot() = JSONObject().put("status", state).put("recovered", recovered).put("requests", JSONObject().apply {
        counts.toSortedMap().forEach { (host, count) -> put(host, count.get()) }
    })

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
