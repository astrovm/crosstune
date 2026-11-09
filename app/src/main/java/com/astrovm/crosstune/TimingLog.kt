package com.astrovm.crosstune

import android.os.SystemClock
import android.util.Log

/**
 * The last minute of what Crosstune decided about the lyrics' timing: where the player said the song
 * was, what Shazam heard, the lead and gap learnt, the lines put in time. It's kept in memory only
 * (never written down) so a report about timing can say why the words were off, which the lines put
 * in time by hand alone can't. Only positions, times, speeds and Shazam's number for the recording
 * go in: nothing about the user, their other apps or their phone.
 *
 * Each note also goes to logcat under [TIMING_LOG] when that's turned on, as the phone check reads it.
 */
internal object TimingLog {
    /** How far back [lastMinute] goes. */
    const val KEEP_MS = 60_000L

    /** The most notes kept, and the most characters [lastMinute] gives: a bound on what a report carries. */
    const val MAX_NOTES = 120
    const val MAX_CHARS = 8_000

    /** The most characters one note keeps. */
    private const val MAX_NOTE = 300

    /** The clock the notes are stamped with: the phone's since it started, the same as the players' clocks in them. */
    @Volatile
    internal var clock: () -> Long = SystemClock::elapsedRealtime

    private class Note(val atMs: Long, val text: String)

    private val notes = ArrayDeque<Note>()

    /** Keeps [text], stamped with the time; and logs it when [TIMING_LOG] is turned on. */
    fun note(text: String) {
        if (Log.isLoggable(TIMING_LOG, Log.DEBUG)) Log.d(TIMING_LOG, text)
        val now = clock()
        synchronized(notes) {
            notes.addLast(Note(now, text.take(MAX_NOTE)))
            while (notes.size > MAX_NOTES || now - notes.first().atMs > KEEP_MS) notes.removeFirst()
        }
    }

    /** The notes of the last minute, one a line, each after the time it was made at; the latest [MAX_CHARS] of them. */
    fun lastMinute(): String {
        val now = clock()
        val lines = synchronized(notes) { notes.filter { now - it.atMs <= KEEP_MS }.map { "${it.atMs} ${it.text}" } }
        val kept = ArrayDeque<String>()
        var chars = 0
        for (line in lines.asReversed()) {
            if (chars + line.length + 1 > MAX_CHARS) break
            chars += line.length + 1
            kept.addFirst(line)
        }
        return kept.joinToString("\n")
    }

    /** Forgets everything, for tests. */
    fun clear() = synchronized(notes) { notes.clear() }
}
