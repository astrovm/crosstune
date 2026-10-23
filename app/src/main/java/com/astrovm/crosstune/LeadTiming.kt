package com.astrovm.crosstune

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Audio fingerprints at separate places must agree before how far what an app plays is ahead of
 * the words, e.g. a video's intro, is remembered.
 */
internal class LeadTiming {
    private val points = mutableListOf<SyncPoint>()

    /**
     * [app] is where the app says it is, [song] where the song was heard. [extraMs] is how much longer
     * what the app plays is than what the words are timed for, when both are known: more before the
     * song, after it or some of each, so it's ahead by no more than that, or behind, when it's
     * shorter. Not known, only a video's intro is believed.
     */
    fun heard(app: PlaybackClock, song: PlaybackClock, extraMs: Long? = null): Long? {
        if (!app.playing || !song.playing || points.any { abs(it.lineMs - song.positionMs) < 3_000 }) return null
        val lead = app.positionAt(song.atMs) - song.positionMs
        val possible = extraMs?.let { min(0L, it) - SLACK_MS..max(0L, it) + SLACK_MS } ?: 0L..MAX_LEAD_MS
        if (lead !in possible || abs(lead) > MAX_LEAD_MS) return null
        points += SyncPoint(song.positionMs, song.positionMs + lead)
        if (points.size > 7) points.removeAt(0)
        return AudioTiming.confirmed(points).singleOrNull()?.let { it.songMs - it.lineMs }
    }

    private companion object {
        /** How far off lengths, and hearings, may be. */
        const val SLACK_MS = 8_000L
        const val MAX_LEAD_MS = 60_000L
    }
}

/** How far ahead an app was heard and confirmed to be, kept for that recording only. */
internal class LeadStore(private val preferences: SharedPreferences) {
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
