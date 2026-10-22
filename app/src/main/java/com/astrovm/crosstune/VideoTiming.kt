package com.astrovm.crosstune

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.abs

/** Audio fingerprints at separate places must agree before a video's intro is remembered. */
internal class VideoTiming {
    private val points = mutableListOf<SyncPoint>()

    fun heard(video: PlaybackClock, song: PlaybackClock): Long? {
        if (!video.playing || !song.playing || points.any { abs(it.lineMs - song.positionMs) < 3_000 }) return null
        val lead = video.positionAt(song.atMs) - song.positionMs
        if (abs(lead) > 60_000) return null
        points += SyncPoint(song.positionMs, song.positionMs + lead)
        if (points.size > 7) points.removeAt(0)
        return AudioTiming.confirmed(points).singleOrNull()?.let { it.songMs - it.lineMs }
    }
}

/** An intro heard and confirmed before, kept for that recording only. */
internal class VideoTimingStore(private val preferences: SharedPreferences) {
    fun load(song: MusicMetadata): Long? = all().opt(RecordingKey.of(song))?.let { it as? Number }?.toLong()?.takeIf { it in -60_000..60_000 }

    fun save(song: MusicMetadata, leadMs: Long) {
        if (song.recordingId == null || leadMs !in -60_000..60_000) return
        val all = all()
        val key = RecordingKey.of(song)
        all.remove(key)
        all.put(key, leadMs)
        while (all.length() > 200) all.remove(all.keys().next())
        preferences.edit { putString("video_timing", all.toString()) }
    }

    private fun all(): JSONObject = try {
        JSONObject(preferences.getString("video_timing", "{}").orEmpty())
    } catch (_: JSONException) {
        JSONObject()
    }
}
