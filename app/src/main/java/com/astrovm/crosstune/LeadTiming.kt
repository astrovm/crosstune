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
     * shorter: a story told before the song may take minutes. Not known, only a video's intro of up
     * to a minute is believed, or one a hair behind, as hearings wobble and a video may cut the
     * song's first moment.
     */
    fun heard(app: PlaybackClock, song: PlaybackClock, extraMs: Long? = null): Long? =
        heard(app, song, possible(extraMs))

    /** The same, for a lead that can only be [possible]. */
    fun heard(app: PlaybackClock, song: PlaybackClock, possible: LongRange): Long? {
        if (!app.playing || !song.playing || points.any { abs(it.lineMs - song.positionMs) < 3_000 }) return null
        val lead = app.positionAt(song.atMs) - song.positionMs
        if (lead !in possible || abs(lead) > LONGEST_LEAD_MS) return null
        points += SyncPoint(song.positionMs, song.positionMs + lead)
        if (points.size > 7) points.removeAt(0)
        return AudioTiming.confirmed(points).singleOrNull()?.let { it.songMs - it.lineMs }
    }

    companion object {
        /** The leads a video [extraMs] longer than the words' take could have, as above. */
        fun possible(extraMs: Long?): LongRange = extraMs?.let { min(0L, it) - SLACK_MS..max(0L, it) + SLACK_MS } ?: -WOBBLE_MS..MAX_LEAD_MS

        /** How far off lengths, and hearings, may be. */
        private const val SLACK_MS = 8_000L
        private const val WOBBLE_MS = 2_000L
        private const val MAX_LEAD_MS = 60_000L
        const val LONGEST_LEAD_MS = 300_000L
    }
}

/**
 * How far where the words are timed is from Shazam's place in the song, for each recording Shazam
 * matches it to: one may open with more than the release the words fit, and Shazam may match a
 * release that isn't playing to it. Learnt while what plays is timed by the words, it puts them
 * right on any other release Shazam matches the same way.
 */
internal class ShazamGapStore(private val preferences: SharedPreferences) {
    /** By whatever name the song was learnt, as releases name it in their own ways, while a recording Shazam matches is of one song. */
    fun load(wordsMs: Long, matchId: String): Long? {
        val all = all()
        return all.keys().asSequence().firstOrNull { it.endsWith(suffix(wordsMs, matchId)) }?.let { all.opt(it) as? Number }?.toLong()
    }

    /** Whether a gap was learnt for [song]'s words at all, by any recording. */
    fun knows(song: MusicMetadata, wordsMs: Long): Boolean = all().keys().asSequence().any { it.startsWith(key(song, wordsMs)) }

    fun save(song: MusicMetadata, wordsMs: Long, matchId: String, gapMs: Long) {
        // Learnt again by another name, it's the one gap, since it's found by any.
        val all = without(wordsMs, matchId)
        all.put(key(song, wordsMs) + matchId, gapMs)
        while (all.length() > 200) all.remove(all.keys().next())
        preferences.edit { putString("shazam_gaps", all.toString()) }
    }

    fun forget(wordsMs: Long, matchId: String) {
        preferences.edit { putString("shazam_gaps", without(wordsMs, matchId).toString()) }
    }

    private fun without(wordsMs: Long, matchId: String): JSONObject =
        all().also { all -> all.keys().asSequence().filter { it.endsWith(suffix(wordsMs, matchId)) }.toList().forEach(all::remove) }

    private fun suffix(wordsMs: Long, matchId: String) = "\u0000${wordsMs / 1000}\u0000$matchId"

    /** By the words' length too, as words for another release are timed another way. */
    private fun key(song: MusicMetadata, wordsMs: Long) =
        "${SongNames.normalize(song.title)}\u0000${SongNames.normalize(song.artist)}\u0000${wordsMs / 1000}\u0000"

    private fun all(): JSONObject = try {
        JSONObject(preferences.getString("shazam_gaps", null) ?: "{}")
    } catch (_: JSONException) {
        JSONObject()
    }
}

/** How far ahead an app was heard and confirmed to be, kept for that recording only. */
internal class LeadStore(private val preferences: SharedPreferences) {
    fun load(song: MusicMetadata): Long? = all().opt(RecordingKey.of(song))?.let { it as? Number }?.toLong()?.takeIf { it in -LeadTiming.LONGEST_LEAD_MS..LeadTiming.LONGEST_LEAD_MS }

    fun save(song: MusicMetadata, leadMs: Long) {
        if (song.recordingId == null || leadMs !in -LeadTiming.LONGEST_LEAD_MS..LeadTiming.LONGEST_LEAD_MS) return
        val all = all()
        val key = RecordingKey.of(song)
        all.remove(key)
        all.put(key, leadMs)
        while (all.length() > 200) all.remove(all.keys().next())
        preferences.edit { putString("video_timing", all.toString()) }
    }

    private fun all(): JSONObject = try {
        JSONObject(preferences.getString("video_timing", null) ?: "{}")
    } catch (_: JSONException) {
        JSONObject()
    }
}
