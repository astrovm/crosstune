package com.astrovm.crosstune

import kotlin.math.abs

/**
 * Where in the song a practice line plays, in the music app's own time: from [start] to [end], the end a moment
 * past where the words say, so the last word isn't cut short.
 */
internal data class LineWindow(val start: Long, val end: Long) {
    /** The app is at the line: at its start, or a little before it as apps round where they are, and not past its end. */
    fun holds(position: Long) = position >= start - SEEK_SLACK_MS && !over(position)

    /**
     * The app went where it was sent: it says it's at the line's start, give or take how apps round where they are. TIDAL
     * sent to 31 s said 29.95 s, Morphe's YouTube Music 31.67 s, so it isn't somewhere else in the song, e.g. back at 0 after an error.
     */
    fun near(position: Long) = abs(position - start) <= SEEK_SLACK_MS

    /** The app has played the line out. */
    fun over(position: Long) = position >= end

    /** How long to give an app that says it's playing the line to get to its end: the line itself, and the time it takes to say where it is. */
    val patience get() = end - start + SEEK_WAIT_MS

    companion object {
        /** How long the music app has to say it went where it was sent. */
        const val SEEK_WAIT_MS = 3_000L

        /** How far from a line's start the app may say it is, having gone there, as apps round where they are. */
        const val SEEK_SLACK_MS = 1_500L

        /** [line]'s window in a video that has [lead] ms before the song, which moves where the line is too. */
        fun of(line: SavedLine, lead: Long): LineWindow =
            LineWindow((line.startMs ?: 0L) + lead, (line.endMs ?: 0L) + lead + MainViewModel.LINE_TAIL_MS)
    }
}

/** Whether something that began at [since] has lasted [atLeast] ms by [now]; never, when it hasn't begun. */
internal fun lasted(since: Long?, now: Long, atLeast: Long) = since != null && now - since >= atLeast
