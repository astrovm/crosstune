package com.astrovm.crosstune

import android.content.SharedPreferences
import org.json.JSONArray

/** A problem Crosstune finds with a song by itself, as its reports name it. */
internal enum class SongProblem(val key: String, val offered: Boolean = true) {
    /** Every lyrics service answered, and none has the song's words. */
    NO_LYRICS("no_lyrics"),

    /** Its words were found, but none timed. */
    NO_TIMING("no_timing"),

    /** Its words were put in time by hand, and left so. */
    TIMED_BY_HAND("timed_by_hand"),

    /** The singing heard was off from the words. The words were moved, so the user saw nothing wrong to be asked about. */
    SUNG_OFF("sung_off", offered = false),

    /** Every chords site answered, and none has its chords. */
    NO_CHORDS("no_chords")
}

/**
 * Whether the user lets Crosstune report problems it finds by itself, and which it has: each song's
 * problem goes once.
 */
internal class AutoReports(private val preferences: SharedPreferences) {

    /** Yes or no, or null while the user hasn't said. */
    var allowed: Boolean?
        get() = if (preferences.contains(KEY_ALLOWED)) preferences.getBoolean(KEY_ALLOWED, false) else null
        set(value) {
            preferences.edit().apply { if (value == null) remove(KEY_ALLOWED) else putBoolean(KEY_ALLOWED, value) }.apply()
        }

    private fun keyOf(song: MusicMetadata, problem: SongProblem) =
        "${problem.key}\u0000${SongNames.normalize(song.title)}\u0000${SongNames.normalize(song.artist)}"

    private fun sent(): List<String> =
        preferences.getString(KEY_SENT, null)?.let { runCatching { JSONArray(it) }.getOrNull() }?.let { list -> (0 until list.length()).map(list::getString) }.orEmpty()

    /** Whether [song]'s [problem] was reported already. */
    fun reported(song: MusicMetadata, problem: SongProblem): Boolean = keyOf(song, problem) in sent()

    /** [song]'s [problem] reported, so it isn't again; only the latest are remembered. */
    fun remember(song: MusicMetadata, problem: SongProblem) {
        val list = (sent() - keyOf(song, problem)) + keyOf(song, problem)
        preferences.edit().putString(KEY_SENT, JSONArray(list.takeLast(MAX_SENT)).toString()).apply()
    }

    companion object {
        private const val KEY_ALLOWED = "auto_reports"
        private const val KEY_SENT = "auto_reports_sent"

        /** Plenty for the songs someone plays in a while; one forgotten long ago would just go again. */
        const val MAX_SENT = 500
    }
}
