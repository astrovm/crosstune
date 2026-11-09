package com.astrovm.crosstune

import android.content.SharedPreferences
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject

/** A problem Crosstune finds with a song by itself, as its reports name it. */
internal enum class SongProblem(
    val key: String,
    /** Whether it's about the words' timing, so its report says how they were put in time: see [PlayerTiming]. */
    val aboutTiming: Boolean = false
) {
    /** Every lyrics service answered, and none has the song's words. */
    NO_LYRICS("no_lyrics"),

    /** Its words were found, but none timed. */
    NO_TIMING("no_timing"),

    /** Its words were put in time by hand, and left so. */
    TIMED_BY_HAND("timed_by_hand", aboutTiming = true),

    /** The singing heard was off from the words. Crosstune moved them itself, so its offer says what it saw. */
    SUNG_OFF("sung_off", aboutTiming = true),

    /** Every chords site answered, and none has its chords. */
    NO_CHORDS("no_chords")
}

/**
 * Whether the user lets Crosstune report problems it finds by itself, and which it has: each song's
 * problem goes once. It asks once an app session, [session], and gives up asking after the offer
 * was closed [MAX_CLOSES] times.
 */
internal class AutoReports(private val preferences: SharedPreferences, private val session: Long = Process.getStartElapsedRealtime()) {

    /** Yes or no, or null while the user hasn't said. */
    var allowed: Boolean?
        get() = if (preferences.contains(KEY_ALLOWED)) preferences.getBoolean(KEY_ALLOWED, false) else null
        set(value) {
            preferences.edit().apply { if (value == null) remove(KEY_ALLOWED) else putBoolean(KEY_ALLOWED, value) }.apply()
        }

    private fun keyOf(song: MusicMetadata, problem: SongProblem) =
        "${problem.key}\u0000${SongNames.normalize(song.title)}\u0000${SongNames.normalize(song.artist)}"

    /** Whether the offer to allow them wasn't made yet in this app session: the process, so it's asked again once the app is started anew. */
    fun mayAsk(): Boolean = preferences.getLong(KEY_ASKED_IN, NEVER) != session

    /** The offer is made now, so it isn't again until the app is started anew. */
    fun asked() {
        preferences.edit().putLong(KEY_ASKED_IN, session).apply()
    }

    /**
     * The offer closed, as not now: counted, and the [MAX_CLOSES]th one is a no, for good. The user can
     * still turn them on in Settings, which this never overrides once it's there.
     */
    fun closed() {
        val closes = preferences.getInt(KEY_CLOSES, 0) + 1
        preferences.edit().putInt(KEY_CLOSES, closes).apply()
        if (closes >= MAX_CLOSES) allowed = false
    }

    private fun sent(): List<String> =
        preferences.getString(KEY_SENT, null)?.let { runCatching { JSONArray(it) }.getOrNull() }?.let { list -> (0 until list.length()).map(list::getString) }.orEmpty()

    /** Whether [song]'s [problem] was reported already. */
    fun reported(song: MusicMetadata, problem: SongProblem): Boolean = keyOf(song, problem) in sent()

    /** [song]'s [problem] reported, so it isn't again; only the latest are remembered. */
    fun remember(song: MusicMetadata, problem: SongProblem) = remember(keyOf(song, problem))

    private fun remember(key: String) {
        val list = (sent() - key) + key
        preferences.edit().putString(KEY_SENT, JSONArray(list.takeLast(MAX_SENT)).toString()).apply()
        // Reported, it isn't owed.
        forget(key)
    }

    /**
     * A report on [song]'s words put in time by hand, [json] as it'd be sent, kept on the phone whatever the
     * user's answer is, until it's sent: the words are only left, and reported, a while after, the app may be
     * killed before, and the answer may come later. An older one for the same song gives way; the latest
     * [MAX_OWED] are kept. One that didn't get there waits for the next start.
     */
    fun owe(song: MusicMetadata, json: String) {
        val key = owedKey(song)
        keep(kept().filter { it.key != key } + Owed(key, json))
    }

    /** The key [song]'s timing report is owed under. */
    fun owedKey(song: MusicMetadata) = keyOf(song, SongProblem.TIMED_BY_HAND)

    /** What's owed, unless it went by now. */
    fun owed(): List<Owed> = kept().filter { it.key !in sent() }

    private fun kept(): List<Owed> = runCatching {
        val list = JSONArray(preferences.getString(KEY_OWED, null) ?: return emptyList())
        (0 until list.length()).map { list.getJSONObject(it) }.map { Owed(it.getString("key"), it.getString("json")) }
    }.getOrDefault(emptyList())

    private fun keep(owed: List<Owed>) {
        val list = JSONArray(owed.takeLast(MAX_OWED).map { JSONObject().put("key", it.key).put("json", it.json) })
        preferences.edit().putString(KEY_OWED, list.toString()).apply()
    }

    /** Nothing owed for [song]: the words were put back as they were. */
    fun owedNothing(song: MusicMetadata) = forget(owedKey(song))

    private fun forget(key: String) = keep(kept().filter { it.key != key })

    /** The report [owed] went. */
    fun paid(owed: Owed) = remember(owed.key)

    /** A report kept to be sent, under the [key] of the song and problem it's for. */
    data class Owed(val key: String, val json: String)

    companion object {
        private const val KEY_ALLOWED = "auto_reports"
        private const val KEY_SENT = "auto_reports_sent"
        private const val KEY_ASKED_IN = "auto_reports_asked_in"
        private const val KEY_CLOSES = "auto_reports_closes"
        private const val KEY_OWED = "auto_reports_owed"
        private const val NEVER = -1L

        /** The reports owed that are kept: the latest songs put in time by hand. */
        const val MAX_OWED = 20

        /** The offer closed this many times is taken as a no. */
        const val MAX_CLOSES = 3

        /** Plenty for the songs someone plays in a while; one forgotten long ago would just go again. */
        const val MAX_SENT = 500
    }
}
