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

    /**
     * The app, sent here from [from], says it stopped short: paused before the line, as TIDAL at its 30 s preview's end, at a
     * place it says is new. Where it was may be said again before it moves (paused, where playing on from there took it by
     * then, as one paused before it seeks says), and so may the song's start, where YouTube Music sits while it loads: neither
     * says it went anywhere. A real place short of the line does, so the app needn't be waited for.
     */
    fun stoppedShort(clock: PlaybackClock, from: PlaybackClock): Boolean =
        !clock.playing && clock.positionMs in SEEK_SLACK_MS..<start - SEEK_SLACK_MS && abs(clock.positionMs - from.positionAt(clock.atMs)) > SEEK_SLACK_MS

    /** The app has played the line out. */
    fun over(position: Long) = position >= end

    /**
     * The window in what the app plays when that's [lengthMs] long: ending a moment before its end, so the app is paused before
     * it goes on to its next song, or null when the line starts past that, as what plays is shorter than the song, e.g. a preview.
     * Unchanged when the length isn't known.
     */
    fun within(lengthMs: Long?): LineWindow? {
        val last = lengthMs?.takeIf { it > 0 }?.minus(END_GUARD_MS) ?: return this
        return if (start >= last) null else copy(end = minOf(end, last))
    }

    /** How long to give an app that says it's playing the line to get to its end: the line itself, and the time it takes to say where it is. */
    val patience get() = end - start + SEEK_WAIT_MS

    companion object {
        /** How long the music app has to say it went where it was sent. */
        const val SEEK_WAIT_MS = 3_000L

        /** How long before what it plays ends an app is paused, so it doesn't start its next song first: a few looks at where it is. */
        const val END_GUARD_MS = 300L

        /** How far from a line's start the app may say it is, having gone there, as apps round where they are. */
        const val SEEK_SLACK_MS = 1_500L

        /** [line]'s window in a video that has [lead] ms before the song, which moves where the line is too. */
        fun of(line: SavedLine, lead: Long): LineWindow {
            val start = (line.startMs ?: 0L) + lead
            // The next line sits that same lead later in the video.
            val next = line.nextMs?.plus(lead)
            return LineWindow(start, playedUntil(start, (line.endMs ?: 0L) + lead, next))
        }

        /**
         * Where play stops. It is a moment past [endMs], so the last word is not cut off. When [nextMs]
         * is inside that moment, play stops there, so the next line is not heard. A line kept before
         * that start was stored has no [nextMs], so the moment past stays.
         */
        fun playedUntil(startMs: Long, endMs: Long, nextMs: Long?): Long {
            val tailed = endMs + MainViewModel.LINE_TAIL_MS
            return if (nextMs != null && nextMs > startMs) minOf(tailed, nextMs) else tailed
        }
    }
}

/** Whether something that began at [since] has lasted [atLeast] ms by [now]; never, when it hasn't begun. */
internal fun lasted(since: Long?, now: Long, atLeast: Long) = since != null && now - since >= atLeast
