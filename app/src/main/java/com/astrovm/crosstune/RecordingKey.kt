package com.astrovm.crosstune

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Keeps different performances and video edits from sharing lyrics or corrections. */
internal object RecordingKey {
    fun of(song: MusicMetadata): String {
        val name = "${SongNames.normalize(song.title)}\u0000${SongNames.normalize(song.artist)}"
        val link = song.url?.let { MusicLinks.parse(it) as? LinkInput.Link }?.link
        val recording = song.recordingId ?: link?.takeIf { it.type == ItemType.TRACK }?.let { "${it.service}:${it.id}" }
        return name + recording?.let { "\u0000$it" }.orEmpty() + song.lengthMs?.takeIf { it > 0 }?.let { "\u0000$it" }.orEmpty()
    }

    /** YouTube's exact video, when the link is known. A search match isn't the playing video. */
    fun youTubeId(song: MusicMetadata): String? {
        val link = (song.url?.let(MusicLinks::parse) as? LinkInput.Link)?.link ?: return null
        return link.takeIf { it.type == ItemType.TRACK && it.service in setOf(MusicService.YOUTUBE, MusicService.YOUTUBE_MUSIC) }?.id
    }

    /**
     * [song] as the app [playing] it has it: its recording and its length, and its link, or the one it
     * was opened by when the app gives none, as YouTube Music doesn't.
     */
    fun asPlayed(song: MusicMetadata, playing: MusicMetadata): MusicMetadata =
        song.copy(recordingId = playing.recordingId, lengthMs = playing.lengthMs, url = playing.url ?: song.url)

    /** Android players can omit the ID, as YouTube Music does; their duration still separates edits. */
    fun playing(app: String, id: String?, durationMs: Long?): String? {
        if (!id.isNullOrBlank()) return "$app:$id"
        return durationMs?.takeIf { it > 0 }?.let { "$app:duration:$it" }
    }
}

/**
 * How long apps say a song is, kept steady: YouTube Music says the same song is 220 seconds long one
 * moment and 221 the next, which would make it another recording, with its own words and timing
 * kept apart, and no longer the song followed. A length within [SAME_MS] of one said before for the
 * song, by any app, is that one. Kept in [preferences], so it stays the same from day to day.
 */
internal class SteadyLengths(private val preferences: SharedPreferences) {
    private val seen: JSONObject by lazy { runCatching { JSONObject(preferences.getString(KEY, null) ?: "{}") }.getOrElse { JSONObject() } }

    @Synchronized
    fun of(title: String, artist: String, lengthMs: Long): Long {
        val key = "${SongNames.normalize(title)}\u0000${SongNames.normalize(artist)}"
        val known = seen.optJSONArray(key) ?: JSONArray()
        (0 until known.length()).map(known::getLong).firstOrNull { abs(it - lengthMs) <= SAME_MS }?.let { return it }
        seen.remove(key)
        seen.put(key, known.put(lengthMs))
        // The songs heard longest ago go first once there are many.
        while (seen.length() > MAX_SONGS) seen.remove(seen.keys().next())
        preferences.edit { putString(KEY, seen.toString()) }
        return lengthMs
    }

    companion object {
        const val SAME_MS = 1_500L
        private const val MAX_SONGS = 500
        private const val KEY = "song_lengths"
    }
}
