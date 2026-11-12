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
        // Written at once, so one that went isn't sent again if the app is ended right after.
        preferences.edit().putString(KEY_SENT, JSONArray(list.takeLast(MAX_SENT)).toString()).commit()
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

    /** The latest [MAX_OWED] put in time by hand are kept, and every one kept from an earlier version, of which there are only so many. */
    private fun keep(owed: List<Owed>) {
        val byHand = owed.filterNot { it.earlier }.takeLast(MAX_OWED).toSet()
        val list = JSONArray(owed.filter { it.earlier || it in byHand }.map { JSONObject().put("key", it.key).put("json", it.json) })
        preferences.edit().putString(KEY_OWED, list.toString()).commit()
    }

    /**
     * Once, at the first start of this version: each song an earlier version kept in time ([LyricsSyncStore], up to 200)
     * is owed a report saying so. That store has the words put in time by hand and those placed by ear alike, and only
     * the song's key and its points, so the report says no more than that: the song as the key names it, its
     * recording and length when the key has them, the points, and whether the singing was heard through it
     * ([SungStore]). Read from what this version found, never after, so nothing timed from now on is taken for it.
     */
    fun keepEarlier(version: String, android: String) {
        if (preferences.getBoolean(KEY_EARLIER_READ, false)) return
        val sung = preferences.getString(KEY_SUNG, null)?.split('\n')?.toSet().orEmpty()
        val songs = runCatching { JSONObject(preferences.getString(KEY_SYNC, null) ?: "{}") }.getOrDefault(JSONObject())
        val sent = sent()
        val earlier = songs.keys().asSequence().mapNotNull { key ->
            val owed = earlierReport(key, songs.optJSONObject(key) ?: return@mapNotNull null, sung, version, android) ?: return@mapNotNull null
            // A song whose timing went already, by hand in this version, or kept, isn't sent again.
            owed.takeIf { it.key !in sent && "${SongProblem.TIMED_BY_HAND.key}\u0000${key.split('\u0000').take(2).joinToString("\u0000")}" !in sent }
        }.toList()
        keep(kept() + earlier)
        preferences.edit().putBoolean(KEY_EARLIER_READ, true).commit()
    }

    private fun earlierReport(key: String, saved: JSONObject, sung: Set<String>, version: String, android: String): Owed? {
        val parts = key.split('\u0000')
        val (title, artist) = parts.getOrNull(0).orEmpty() to parts.getOrNull(1).orEmpty()
        if (title.isBlank() || artist.isBlank()) return null
        val array = saved.optJSONArray("points") ?: return null
        // Only those the box takes, and of more than it takes, those that tell most.
        val usable = (0 until array.length()).mapNotNull { array.optJSONArray(it)?.takeIf { point -> point.length() == 2 } }
            .filter { ReportBox.takes(it.optLong(0), it.optLong(1)) }
        val points = usable.slice(ReportBox.telling(usable.map { it.optLong(0) to it.optLong(1) }, ReportBox.MAX_POINTS))
        if (points.isEmpty()) return null
        // The key ends with the recording, then the length, each only when it was known.
        val rest = parts.drop(2)
        val length = rest.lastOrNull()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        val recording = rest.firstOrNull()?.takeIf { it.isNotEmpty() && it != length }
        val heard = "$key\u0000${saved.optString("words")}".replace('\n', ' ') in sung
        val song = JSONObject().put("title", ReportBox.cut(title, "title")).put("artist", ReportBox.cut(artist, "artist")).put("sung", heard)
            .put("timing", JSONArray(points.map { JSONObject().put("timedMs", it.optLong(0)).put("heardMs", it.optLong(1)) }))
        recording?.let { song.put("recording", ReportBox.cut(it, "recording")) }
        length?.let { song.put("lengthMs", it.toLong()) }
        val json = JSONObject().put("version", version).put("android", android).put("problem", EARLIER).put("song", song)
        return Owed("$EARLIER\u0000$key", json.toString())
    }

    /** Nothing owed for [song]: the words were put back as they were. */
    fun owedNothing(song: MusicMetadata) = forget(owedKey(song))

    private fun forget(key: String) = keep(kept().filter { it.key != key })

    /** The report [owed] went. */
    fun paid(owed: Owed) = remember(owed.key)

    /** The report [owed] was turned away by the box, as it always will be: it's no longer owed, and isn't taken as sent. */
    fun dropped(owed: Owed) = forget(owed.key)

    /** Whether [owed] is still to go as it is: not sent yet, by this or by another way, nor put back, nor replaced. */
    fun owes(owed: Owed): Boolean = owed in owed()

    /** A report kept to be sent, under the [key] of the song and problem it's for. */
    data class Owed(val key: String, val json: String) {
        /** Kept from an earlier version, rather than put in time by hand in this one. */
        val earlier: Boolean get() = key.startsWith("$EARLIER\u0000")
    }

    companion object {
        private const val KEY_ALLOWED = "auto_reports"
        private const val KEY_SENT = "auto_reports_sent"
        private const val KEY_ASKED_IN = "auto_reports_asked_in"
        private const val KEY_CLOSES = "auto_reports_closes"
        private const val KEY_OWED = "auto_reports_owed"
        private const val NEVER = -1L
        private const val KEY_EARLIER_READ = "auto_reports_earlier_read"

        /** Where [LyricsSyncStore] and [SungStore] keep theirs. */
        private const val KEY_SYNC = "lyrics_sync"
        private const val KEY_SUNG = "sung_heard"

        /** What a report on a song's timing kept from an earlier version says it is: by hand or by ear, it can't tell. */
        const val EARLIER = "kept_timing"

        /** The reports owed that are kept: the latest songs put in time by hand. */
        const val MAX_OWED = 20

        /** The offer closed this many times is taken as a no. */
        const val MAX_CLOSES = 3

        /** Plenty for the songs someone plays in a while; one forgotten long ago would just go again. */
        const val MAX_SENT = 500
    }
}
