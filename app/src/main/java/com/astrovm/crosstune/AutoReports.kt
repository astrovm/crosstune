package com.astrovm.crosstune

import android.content.SharedPreferences
import android.os.Process
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.WeakHashMap

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
 * problem goes once, and at most [FOUND_A_DAY] a day by the wall clock, [now]. It asks once an app
 * session, [session], and gives up asking after the offer was closed [MAX_CLOSES] times.
 */
internal class AutoReports(
    private val preferences: SharedPreferences,
    private val session: Long = Process.getStartElapsedRealtime(),
    private val now: () -> Long = System::currentTimeMillis
) {

    /**
     * What every [AutoReports] on these preferences in this process shares: see [Shared]. Each read and write of the
     * reports kept is synchronized on it, so two screens of the app open at once, as one opened from a share is, in
     * any thread, never take one report or one place twice.
     */
    private val shared = sharedFor(preferences)

    /** Held while the reports owed go, by whichever screen sends them, so a start and a yes close together don't send one twice. */
    val sending: Mutex get() = shared.sending

    /**
     * Yes or no, or null while the user hasn't said. A no deletes the problems found that wait on the phone, as they
     * were kept only to go: turned on again later, they never do. Words put in time by hand, and the timing an earlier
     * version kept, stay, as they're kept whatever the user answered.
     */
    var allowed: Boolean?
        get() = if (preferences.contains(KEY_ALLOWED)) preferences.getBoolean(KEY_ALLOWED, false) else null
        set(value) {
            synchronized(shared) {
                preferences.edit().apply { if (value == null) remove(KEY_ALLOWED) else putBoolean(KEY_ALLOWED, value) }.apply()
                if (value == false) keep(kept().filter { it.key.startsWith(BY_HAND_START) || it.earlier })
            }
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
    fun closed() = synchronized(shared) {
        val closes = preferences.getInt(KEY_CLOSES, 0) + 1
        preferences.edit().putInt(KEY_CLOSES, closes).apply()
        if (closes >= MAX_CLOSES) allowed = false
    }

    private fun sent(): List<String> =
        preferences.getString(KEY_SENT, null)?.let { runCatching { JSONArray(it) }.getOrNull() }?.let { list -> (0 until list.length()).map(list::getString) }.orEmpty()

    /** Whether [song]'s [problem] was reported already. */
    fun reported(song: MusicMetadata, problem: SongProblem): Boolean = keyOf(song, problem) in sent()

    /** [song]'s [problem] reported, so it isn't again; only the latest are remembered. */
    fun remember(song: MusicMetadata, problem: SongProblem) = synchronized(shared) { remember(keyOf(song, problem)) }

    /**
     * [owed] turned away by the box, as it always will be: a problem found isn't sent again each time it's found, as
     * it would be the same. Words put in time by hand are only no longer owed as they were, not taken as sent, so
     * when they're put in time again, that report still goes, and one made meanwhile stays owed.
     */
    private fun turnedAway(owed: Owed) = if (owed.key.startsWith(BY_HAND_START)) keep(kept() - owed) else remember(owed.key)

    /** [key] done with, as it went to the box or was turned away. */
    private fun remember(key: String) {
        val list = (sent() - key) + key
        // Written at once, so one that went isn't sent again if the app is ended right after.
        preferences.edit().putString(KEY_SENT, JSONArray(list.takeLast(MAX_SENT)).toString()).commit()
        // Reported, it isn't owed: keeping drops it.
        keep(kept())
    }

    /**
     * [owed] taken to go now, in one step with the checks: reports are [allowed], it's still owed, not on its way
     * already, and for a problem found, a place left of today's [FOUND_A_DAY], counting those on their way. Null where
     * it can't go now: one past the day's share waits for a later day, and with reports turned off, every one waits.
     * How it went is told with [went], always.
     */
    fun take(owed: Owed): Going? = synchronized(shared) {
        val going = shared.going
        // Checked under the lock a no takes, so a no between two reports stops the rest.
        if (allowed != true || !owes(owed) || owed.key in going) return null
        val counts = !owed.earlier
        if (counts && foundToday() + going.values.count { it } >= FOUND_A_DAY) return null
        going[owed.key] = counts
        Going(owed)
    }

    /**
     * [going] went as [delivery] says, null where sending it ended in an error. One the box has is done with, and
     * takes its place of the day; one it turned away is never sent again as it is; one that didn't get there stays
     * owed, and takes no place.
     */
    fun went(going: Going, delivery: Delivery?) {
        synchronized(shared) {
            val counts = shared.going.remove(going.owed.key) ?: return
            when (delivery) {
                Delivery.SENT -> {
                    if (counts) preferences.edit().putString(KEY_FOUND_TODAY, "${today()}:${foundToday() + 1}").commit()
                    remember(going.owed.key)
                }
                Delivery.REFUSED -> turnedAway(going.owed)
                Delivery.FAILED, null -> Unit
            }
        }
    }

    /** A report on its way: see [take]. */
    class Going(val owed: Owed)

    /** How many problems found went today. */
    private fun foundToday(): Int =
        preferences.getString(KEY_FOUND_TODAY, null)?.split(':')?.takeIf { it.first() == today() }?.getOrNull(1)?.toIntOrNull() ?: 0

    /** Today, as the box counts its days: by UTC. */
    private fun today(): String = (now() / ReportBox.DAY_MS).toString()

    /**
     * A report on [song]'s [problem], [json] as it'd be sent, kept on the phone until it's sent. Words put in time by
     * hand are owed whatever the user's answer is: they're only left, and reported, a while after, the app may be
     * killed before, and the answer may come later. A problem found is owed when its report didn't get there, or
     * had to wait for a later day. An older one for the same song and problem gives way. [json] is sent as it is, so
     * it has its [ID] from [idFor] already, inside the box's size. The latest [MAX_OWED] of each, by hand and found,
     * are kept, so the oldest go first. Each waits for the next start. With reports turned off, a problem found isn't
     * kept, as one made before the no was said, and kept only after it, would wait to go when they're turned on again.
     */
    fun owe(song: MusicMetadata, problem: SongProblem, json: String): Owed = synchronized(shared) {
        val key = keyOf(song, problem)
        val owed = Owed(key, json)
        if (problem != SongProblem.TIMED_BY_HAND && allowed == false) return owed
        keep(kept().filter { it.key != key } + owed)
        // Kept, it carries its id from now on.
        shared.ids.remove(key)
        owed
    }

    /**
     * The [ID] for a report on [song]'s [problem], to make it with, so the report is measured with it: the one kept
     * for it, as an older version of the same report gives its id to the newer, and the box keeps one of them,
     * whichever got there; else one made now, which every screen gets until the report is kept.
     */
    fun idFor(song: MusicMetadata, problem: SongProblem): String = synchronized(shared) {
        val key = keyOf(song, problem)
        kept().firstOrNull { it.key == key }?.id ?: shared.ids.getOrPut(key) { UUID.randomUUID().toString() }
    }

    /** The key [song]'s timing report is owed under. */
    fun owedKey(song: MusicMetadata) = keyOf(song, SongProblem.TIMED_BY_HAND)

    /** What's owed, unless it went by now. */
    fun owed(): List<Owed> = kept().filter { it.key !in sent() }

    private fun kept(): List<Owed> = runCatching {
        val list = JSONArray(preferences.getString(KEY_OWED, null) ?: return emptyList())
        (0 until list.length()).map { list.getJSONObject(it) }.map { Owed(it.getString("key"), it.getString("json")) }
    }.getOrDefault(emptyList())

    /**
     * The latest [MAX_OWED] put in time by hand are kept, the latest [MAX_OWED] problems found, and every one kept from
     * an earlier version, of which there are only so many. One that went already, as words timed again after their
     * report went are, is never sent again, so it takes no room.
     */
    private fun keep(all: List<Owed>) {
        val sent = sent().toSet()
        val owed = all.filter { it.key !in sent }
        val latest = owed.filterNot { it.earlier }.groupBy { it.key.startsWith(BY_HAND_START) }.values.flatMap { it.takeLast(MAX_OWED) }.toSet()
        val list = JSONArray(owed.filter { it.earlier || it in latest }.map { JSONObject().put("key", it.key).put("json", it.json) })
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
        synchronized(shared) {
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
        // Each text cut to what the box keeps, and no more lines than it takes, so with its id it always fits.
        val json = JSONObject().put("version", ReportBox.cut(version, "version")).put("android", ReportBox.cut(android, "android")).put("problem", EARLIER)
            .put("song", song).put(ID, UUID.randomUUID().toString())
        return Owed("$EARLIER\u0000$key", json.toString())
    }

    /** Nothing owed for [song]: the words were put back as they were. */
    fun owedNothing(song: MusicMetadata) = synchronized(shared) { keep(kept().filter { it.key != owedKey(song) }) }


    /** Whether [owed] is still to go as it is: not sent yet, by this or by another way, nor put back, nor replaced. */
    fun owes(owed: Owed): Boolean = owed in owed()

    /** A report kept to be sent, under the [key] of the song and problem it's for, as [json], which is what's sent. */
    data class Owed(val key: String, val json: String) {
        /** Kept from an earlier version, rather than put in time by hand in this one. */
        val earlier: Boolean get() = key.startsWith(EARLIER_START)

        /** The report's own [ID], or null for one an older version kept without it. */
        val id: String? get() = JSONObject(json).optString(ID).ifEmpty { null }
    }

    /**
     * What the reports kept need held in one place, whichever screen of the app sends them: the reports on their way,
     * by key, and whether each counts for the day, kept in memory, so an app ended on the way takes none of the day's
     * places; and the lock owed reports go under.
     */
    private class Shared {
        val going = mutableMapOf<String, Boolean>()
        val sending = Mutex()

        /** The ids given by [idFor] to reports not kept yet, by key. */
        val ids = mutableMapOf<String, String>()
    }

    companion object {
        /**
         * Each [Shared], by the preferences it's for: Android gives every screen one object for one file. Let go of
         * with them, so a test's are gone with its app.
         */
        private val everyShared = WeakHashMap<SharedPreferences, Shared>()

        private fun sharedFor(preferences: SharedPreferences): Shared = synchronized(everyShared) { everyShared.getOrPut(preferences, ::Shared) }

        /** As if the app's process ended: what was held in memory is gone, and what's on the phone stays. */
        @VisibleForTesting
        fun processEnded() = synchronized(everyShared) { everyShared.clear() }

        /**
         * What a kept report carries as its own: a random UUID, made for it alone and never again, nothing about the
         * phone. The box keeps one report for each, so one sent again, as it got there but the answer didn't, isn't kept twice.
         */
        const val ID = "id"

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

        /** How the keys of reports kept from an earlier version, and of those on words put in time by hand, start. */
        private const val EARLIER_START = "$EARLIER\u0000"
        private val BY_HAND_START = "${SongProblem.TIMED_BY_HAND.key}\u0000"
        private const val KEY_FOUND_TODAY = "auto_reports_found_today"

        /** The reports owed that are kept of each: the latest songs put in time by hand, and the latest problems found. */
        const val MAX_OWED = 20

        /**
         * The most problems found, words put in time by hand among them, that go in a day: the box takes 70 a day from one
         * network, room for 3 phones, and one phone found at most 22 in a day in 115 real reports over 14 days.
         */
        const val FOUND_A_DAY = 23

        /** The offer closed this many times is taken as a no. */
        const val MAX_CLOSES = 3

        /** Plenty for the songs someone plays in a while; one forgotten long ago would just go again. */
        const val MAX_SENT = 500
    }
}
