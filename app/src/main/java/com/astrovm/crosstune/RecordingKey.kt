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
        val recording = song.recordingId ?: track(song.url)?.let { "${it.service}:${it.id}" }
        return name + recording?.let { "\u0000$it" }.orEmpty() + song.lengthMs?.takeIf { it > 0 }?.let { "\u0000$it" }.orEmpty() +
            instrumentalAlbumKey(song)?.let { "\u0000$it" }.orEmpty()
    }

    /** Words from another take can only be borrowed offline within the same instrumental album, or with none known for either. */
    fun sameInstrumentalAlbum(key: String, song: MusicMetadata): Boolean =
        key.substringAfterLast('\u0000').takeIf { it.startsWith(INSTRUMENTAL_ALBUM_PREFIX) } == instrumentalAlbumKey(song)

    private const val INSTRUMENTAL_ALBUM_PREFIX = "album:"

    private val pluralInstrumental = Regex("(?<=instrumental)s\\b", RegexOption.IGNORE_CASE)

    /** Whether [a] and [b] name one album, as "Album (Instrumental)" and "Album (Instrumentals)" do. */
    fun sameAlbum(a: String, b: String) = SongNames.normalize(a.replace(pluralInstrumental, "")) == SongNames.normalize(b.replace(pluralInstrumental, ""))

    private fun instrumentalAlbumKey(song: MusicMetadata): String? =
        song.instrumentalAlbum?.let { INSTRUMENTAL_ALBUM_PREFIX + SongNames.normalize(it) }

    /** YouTube's exact video, when the link is known. A search match isn't the playing video. */
    fun youTubeId(song: MusicMetadata): String? {
        return track(song.url)?.takeIf { it.service in setOf(MusicService.YOUTUBE, MusicService.YOUTUBE_MUSIC) }?.id
    }

    /**
     * [song] as the app [playing] it has it: its recording and its length, and its link, or the one it
     * was opened by when the app gives none, as YouTube Music doesn't.
     */
    fun asPlayed(song: MusicMetadata, playing: MusicMetadata): MusicMetadata =
        song.copy(
            recordingId = playing.recordingId, lengthMs = playing.lengthMs, heardLengthMs = song.heardLengthMs ?: song.lengthMs,
            url = playing.url ?: song.url,
            instrumentalAlbum = playing.instrumentalAlbum ?: song.instrumentalAlbum?.takeIf { sameRecording(song, playing) }
        )

    private fun track(url: String?): MusicLink? =
        (url?.let(MusicLinks::parse) as? LinkInput.Link)?.link?.takeIf { it.type == ItemType.TRACK }

    /** A player can omit the album. Keep the link's evidence only for an identified recording. */
    private fun sameRecording(song: MusicMetadata, playing: MusicMetadata): Boolean {
        val asked = track(song.url)
        val played = track(playing.url)
        if (asked != null && played != null) return asked.service != null && asked.service == played.service && asked.id == played.id
        val id = playing.recordingId ?: return false
        return id == song.recordingId && !id.substringAfter(':').startsWith("duration:")
    }

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
        val key = keyOf(title, artist)
        val known = seen.optJSONArray(key) ?: JSONArray()
        (0 until known.length()).map(known::getLong).firstOrNull { abs(it - lengthMs) <= SAME_MS }?.let { return it }
        seen.remove(key)
        seen.put(key, known.put(lengthMs))
        // The songs heard longest ago go first once there are many.
        while (seen.length() > MAX_SONGS) seen.remove(seen.keys().next())
        preferences.edit { putString(KEY, seen.toString()) }
        return lengthMs
    }

    /** The lengths said before for the song, by any app. */
    @Synchronized
    fun known(title: String, artist: String): List<Long> = seen.optJSONArray(keyOf(title, artist))?.let { known -> (0 until known.length()).map(known::getLong) }.orEmpty()

    private fun keyOf(title: String, artist: String) = "${SongNames.normalize(title)}\u0000${SongNames.normalize(artist)}"

    companion object {
        const val SAME_MS = 1_500L
        private const val MAX_SONGS = 500
        private const val KEY = "song_lengths"
    }
}
