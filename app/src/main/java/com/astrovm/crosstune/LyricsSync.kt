package com.astrovm.crosstune

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.abs

/** A line put in time by hand: the words had it at [lineMs], and the user heard it at [songMs]. */
internal data class SyncPoint(val lineMs: Long, val songMs: Long)

/**
 * Timed words put right by hand. Words timed for another take of the song, or timed badly, drift
 * from it; a line tapped as it's heard sets where it really is. Between two such lines the others
 * stretch to fit, and past the last one they move along with it.
 */
internal object LyricsSync {
    /** [lines] where the user says they are, by [points]. */
    fun adjust(lines: List<LyricLine>, points: List<SyncPoint>): List<LyricLine> =
        if (points.isEmpty()) lines else lines.map { line ->
            line.copy(timeMs = timeOf(line.timeMs, points), words = line.words.map { it.copy(timeMs = timeOf(it.timeMs, points)) }, endMs = line.endMs?.let { timeOf(it, points) })
        }

    /** Where in the song [lineMs] of the words is, by [points] in order. */
    fun timeOf(lineMs: Long, points: List<SyncPoint>): Long {
        val after = points.indexOfFirst { it.lineMs > lineMs }
        val before = (if (after < 0) points.lastIndex else after - 1).takeIf { it >= 0 }?.let(points::get)
        val next = points.getOrNull(after)
        return when {
            before == null -> lineMs + (next!!.songMs - next.lineMs)
            next == null -> lineMs + (before.songMs - before.lineMs)
            else -> before.songMs + (lineMs - before.lineMs) * (next.songMs - before.songMs) / (next.lineMs - before.lineMs)
        }
    }

    /**
     * [points] with [point] added. One for the same line goes, and so do those it contradicts, i.e. a
     * line before it heard later or one after it heard sooner, so the words keep their order.
     */
    fun with(points: List<SyncPoint>, point: SyncPoint): List<SyncPoint> =
        withoutSlips((points.filterNot { it.lineMs == point.lineMs || (it.lineMs < point.lineMs) != (it.songMs < point.songMs) } + point).sortedBy { it.lineMs })

    /**
     * [points] less those that are off alone: a line heard far from where the lines either side of
     * it agree it should be is a slip of the finger, and would bend every line between them. The ends
     * need the next two to agree.
     */
    fun withoutSlips(points: List<SyncPoint>): List<SyncPoint> {
        if (points.size < 3) return points
        val shifts = points.map { it.songMs - it.lineMs }
        return points.filterIndexed { index, _ ->
            val around = if (index == 0) shifts.slice(1..2) else if (index == points.lastIndex) shifts.slice(index - 2 until index) else listOf(shifts[index - 1], shifts[index + 1])
            abs(around[0] - around[1]) > AGREE_MS || abs(shifts[index] - around.average()) <= SLIP_MS
        }
    }

    /** How far apart the lines around one may be heard, and still agree. */
    private const val AGREE_MS = 1_500L

    /** How far from where they agree a line may be heard before it's a slip. */
    private const val SLIP_MS = 2_500L
}

/**
 * The lines put in time by hand, per song, on the phone only. They hold for the words they were
 * set on: other words for the song, e.g. from another place, are timed their own way, so they start over.
 */
internal class LyricsSyncStore(private val preferences: SharedPreferences) {

    fun load(song: MusicMetadata, lines: List<LyricLine>): List<SyncPoint> {
        val json = all()?.optJSONObject(keyOf(song))?.takeIf { it.optString("words") == fingerprint(lines) } ?: return emptyList()
        val array = json.optJSONArray("points") ?: return emptyList()
        return LyricsSync.withoutSlips((0 until array.length()).mapNotNull { index ->
            array.optJSONArray(index)?.let { SyncPoint(it.optLong(0), it.optLong(1)) }
        }.sortedBy { it.lineMs })
    }

    fun save(song: MusicMetadata, lines: List<LyricLine>, points: List<SyncPoint>) {
        val all = all() ?: JSONObject()
        val key = keyOf(song)
        all.remove(key)
        if (points.isNotEmpty()) {
            val array = JSONArray().apply { points.forEach { put(JSONArray().put(it.lineMs).put(it.songMs)) } }
            all.put(key, JSONObject().put("words", fingerprint(lines)).put("points", array))
        }
        // The oldest go first once there are many; JSONObject keeps the order they were put in.
        while (all.length() > MAX_SONGS) all.remove(all.keys().next())
        preferences.edit { putString(KEY, all.toString()) }
    }

    private fun all(): JSONObject? = preferences.getString(KEY, null)?.let {
        try {
            JSONObject(it)
        } catch (_: JSONException) {
            null
        }
    }

    private fun keyOf(song: MusicMetadata) = RecordingKey.of(song)

    /** Which words these are, timings and all, in a few characters. */
    private fun fingerprint(lines: List<LyricLine>) = lines.joinToString("\n") { "${it.timeMs} ${it.text}" }.hashCode().toString(16)

    private companion object {
        const val KEY = "lyrics_sync"
        const val MAX_SONGS = 200
    }
}
