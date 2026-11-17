package com.astrovm.crosstune

import android.app.Instrumentation
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Test the real Android session and transport path without opening a music app or making sound. */
class ArtistlessPlaybackCheck : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val results = JSONArray()
        var code = 0
        try {
            check(targetContext.packageName == "com.astrovm.crosstune.dev") { "Use the dev app only" }
            val manager = targetContext.getSystemService(MediaSessionManager::class.java)
            val listener = ComponentName(targetContext, NowPlayingListener::class.java)
            for (fixture in fixtures) {
                val commands = ConcurrentLinkedQueue<String>()
                val session = MediaSession(targetContext, "artistless-fixture")
                try {
                    runOnMainSync {
                        session.setCallback(object : MediaSession.Callback() {
                            override fun onSeekTo(pos: Long) { commands.add("seek") }
                            override fun onPause() { commands.add("pause") }
                        }, Handler(Looper.getMainLooper()))
                        session.setMetadata(MediaMetadata.Builder()
                            .putString(MediaMetadata.METADATA_KEY_TITLE, fixture.title)
                            .putString(MediaMetadata.METADATA_KEY_ARTIST, fixture.artist)
                            .putLong(MediaMetadata.METADATA_KEY_DURATION, 200_000L).build())
                        session.setPlaybackState(PlaybackState.Builder()
                            .setState(PlaybackState.STATE_PLAYING, 42_000L, 1f, SystemClock.elapsedRealtime())
                            .setActions(PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_PAUSE).build())
                        session.isActive = true
                    }
                    runBlocking {
                        withTimeout(3_000L) {
                            while (manager.getActiveSessions(listener).none {
                                it.sessionToken == session.sessionToken &&
                                    it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) == fixture.title
                            }) delay(20L)
                        }
                    }
                    val source = MediaSessionPlayback(targetContext)
                    val song = MusicMetadata(fixture.asked, "Fixture Artist")
                    val selected = source.appFor(song, app = targetContext.packageName) != null
                    val followed = runBlocking {
                        withTimeout(3_000L) {
                            withContext(Dispatchers.Main) { source.follow(song, targetContext.packageName).first() != null }
                        }
                    }
                    source.seekTo(song, 10_000L, targetContext.packageName)
                    source.playPause(song, false, targetContext.packageName)
                    runBlocking {
                        if (fixture.matches) withTimeout(3_000L) {
                            while (commands.size < 2) delay(20L)
                        } else delay(200L)
                    }
                    val controls = commands.toList()
                    val passed = selected == fixture.matches && followed == fixture.matches &&
                        controls == if (fixture.matches) listOf("seek", "pause") else emptyList<String>()
                    if (!passed) code = 1
                    results.put(JSONObject().put("title", fixture.title).put("artist", fixture.artist)
                        .put("asked", fixture.asked).put("expected", fixture.matches)
                        .put("selected", selected).put("followed", followed)
                        .put("controls", JSONArray(controls)).put("passed", passed))
                } finally {
                    runOnMainSync { session.isActive = false; session.release() }
                }
            }
        } catch (error: Exception) {
            code = 1
            results.put(JSONObject().put("error", error.toString()))
        }
        finish(code, Bundle().apply { putString("cases", results.toString()) })
    }

    private data class Fixture(val title: String, val artist: String, val asked: String, val matches: Boolean)

    private companion object {
        val fixtures = listOf(
            Fixture("Glass Lantern Radio", "", "Glass Lantern", false),
            Fixture("Glass Lantern Meadow", "", "Glass Lantern", false),
            Fixture("Glass Lantern", "", "Glass Lantern Meadow", false),
            Fixture("Paperkite (Live)", "", "Paperkite", false),
            Fixture("Glass Lantern", "", "Glass Lantern", true),
            Fixture("Glass Lantern.mp3", "", "Glass Lantern", true),
            Fixture("Glass Lantern.mp3", "<unknown>", "Glass Lantern", true),
            Fixture("Glass Lantern (Live)", "", "Glass Lantern", true),
            Fixture("Fixture Artist - Glass Lantern", "", "Glass Lantern", true)
        )
    }
}
