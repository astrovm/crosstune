package com.astrovm.crosstune

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Real framework callbacks and controls, with invented metadata and no audio or network. */
@RequiresApi(31)
class PracticeClockCheck : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        try {
            check(Build.VERSION.SDK_INT >= 31) { "This test-only shell adapter needs API 31" }
            check(targetContext.packageName == "com.astrovm.crosstune.dev")
            check(targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            finish(0, verifySession())
        } catch (error: Exception) {
            finish(1, Bundle().apply { putString("error", error.toString()) })
        }
    }

    private fun verifySession(): Bundle {
        val oldLevel = shell("getprop log.tag.CrosstuneClock").trim()
        check(oldLevel.matches(Regex("[a-zA-Z0-9]*")))
        val names = mutableSetOf<String>()
        val prefix = "clock-fixture-${SystemClock.elapsedRealtimeNanos()}-"
        val isolated = object : ContextWrapper(targetContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val fixtureName = prefix + name
                names += fixtureName
                return super.getSharedPreferences(fixtureName, mode)
            }
        }
        uiAutomation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL")
        try {
            shell("setprop log.tag.CrosstuneClock DEBUG")
            check(Log.isLoggable("CrosstuneClock", Log.DEBUG))
            return runBlocking { withTimeout(20_000) { observe(isolated) } }
        } finally {
            try {
                names.forEach { name ->
                    check(targetContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
                    check(targetContext.deleteSharedPreferences(name))
                }
            } finally {
                try {
                    shell("setprop log.tag.CrosstuneClock '$oldLevel'")
                    check(shell("getprop log.tag.CrosstuneClock").trim() == oldLevel) { "The diagnostic tag was not restored" }
                } finally {
                    uiAutomation.dropShellPermissionIdentity()
                }
            }
        }
    }

    private suspend fun observe(context: Context): Bundle = kotlinx.coroutines.coroutineScope {
        val started = SystemClock.elapsedRealtime()
        val fixture = MusicMetadata("Clock Fixture", "Fixture Artist")
        val session = MediaSession(context, "ClockFixture")
        val reports = Channel<Unit>(Channel.UNLIMITED)
        val commands = Channel<String>(Channel.UNLIMITED)
        var controller: MediaController? = null
        var following: Job? = null
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) { reports.trySend(Unit) }
        }
        try {
            val observed = MediaController(context, session.sessionToken).also { controller = it }
            session.setCallback(object : MediaSession.Callback() {
                override fun onSeekTo(pos: Long) { commands.trySend("seek:$pos") }
                override fun onPlay() { commands.trySend("play") }
                override fun onPause() { commands.trySend("pause") }
            }, Handler(Looper.getMainLooper()))
            session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, fixture.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, fixture.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Fixture Album")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 200_000).build())
            session.setPlaybackState(state(started, PlaybackState.ACTION_SEEK_TO))
            session.isActive = true
            val source = MediaSessionPlayback(context)
            val ready = CompletableDeferred<Unit>()
            following = launch(Dispatchers.Main) {
                source.follow(fixture, context.packageName).collect { if (it != null) ready.complete(Unit) }
            }
            ready.await()
            observed.registerCallback(callback, Handler(Looper.getMainLooper()))
            val at = SystemClock.elapsedRealtime()
            repeat(2) {
                session.setPlaybackState(state(at, PlaybackState.ACTION_SEEK_TO))
                reports.receive()
            }
            session.setPlaybackState(state(at + 1, 0))
            reports.receive()
            session.setPlaybackState(null)
            reports.receive()
            val raw = awaitClockRecords(4) { records(started).filter { it.getString("event") == "media_report" } }
            check(raw.size == 4) { "Expected four raw callbacks, got ${raw.size}" }
            check(raw.map { it.getInt("observer") }.distinct().size == 1)
            check(raw.take(3).all { it.getLong("position_ms") == 29_952L && it.getString("speed") == "0.0" })
            check(raw.take(2).all { it.getLong("updated_ms") == at && it.getLong("actions") == PlaybackState.ACTION_SEEK_TO })
            check(raw[2].getLong("updated_ms") == at + 1 && raw[2].getLong("actions") == 0L)
            check(!raw.last().getBoolean("present"))
            session.setPlaybackState(state(SystemClock.elapsedRealtime(),
                PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE))
            reports.receive()
            val target = source.matching(fixture).single { it.app == context.packageName }.source
            target.seekTo(fixture, 43_270, context.packageName)
            check(commands.receive() == "seek:43270")
            target.playPause(fixture, true, context.packageName)
            check(commands.receive() == "play")
            target.playPause(fixture, false, context.packageName)
            check(commands.receive() == "pause")
            val controls = awaitClockRecords(3) { records(started).filter { it.getString("event") == "media_command" } }
            check(controls.map { it.getString("command") } == listOf("seek", "play", "pause"))
            check(controls.first().getLong("target_ms") == 43_270L)
            check(controls.all { it.getInt("session") == raw.first().getInt("session") })
            Bundle().apply {
                putString("result", "pass")
                putInt("raw_callbacks", raw.size)
                putInt("transport_commands", controls.size)
                putLong("seek_target_ms", 43_270)
                putBoolean("audio_generated", false)
            }
        } finally {
            finishClockProbe({ following?.cancelAndJoin() }, { controller?.unregisterCallback(callback) }, session::release)
        }
    }

    private fun state(at: Long, actions: Long) = PlaybackState.Builder()
        .setState(PlaybackState.STATE_PAUSED, 29_952, 0f, at).setActions(actions).build()

    private fun records(since: Long): List<JSONObject> = shell("logcat -d -v raw --pid=${android.os.Process.myPid()} -s CrosstuneClock:D '*:S'")
        .lineSequence().filter { it.startsWith('{') }.map(::JSONObject)
        .filter { it.getLong("observed_ms") >= since && it.optString("app") == targetContext.packageName }.toList()

    private fun shell(command: String): String {
        // executeShellCommand tokenizes its argument without shell quoting. An explicit shell on
        // stdin keeps an empty property value empty, instead of storing two literal quote marks.
        val descriptors = uiAutomation.executeShellCommandRw("sh")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).bufferedReader().use { output ->
            ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).bufferedWriter().use { input ->
                input.write(command)
                input.newLine()
            }
            output.readText()
        }
    }
}
