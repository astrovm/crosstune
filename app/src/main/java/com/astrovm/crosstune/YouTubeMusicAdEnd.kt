package com.astrovm.crosstune

import kotlin.math.abs

/**
 * Free YouTube Music gives an ad the next item's name and queue ID. At the end it can give the
 * next length and two starts: one as the old playback ends, and another as the new playback starts.
 * A length change alone can be a correction. Look for playback getting to the old length, then
 * returning to the start, before using the change to interpret a load or reset a follower's clock.
 */
internal class YouTubeMusicAdEnd {
    data class Item(val title: String?, val artist: String?, val length: Long, val queueId: Long?) {
        fun sameTrack(other: Item): Boolean =
            title == other.title && artist == other.artist &&
                (queueId == other.queueId || queueId == null || other.queueId == null)
    }

    private data class Report(val item: Item, val clock: PlaybackClock?)
    private data class Reset(val at: Long, val clock: PlaybackClock)
    private data class End(val at: Long, val old: Item, val next: Item, val before: PlaybackClock?, val first: PlaybackClock?, val deadlineElapsed: Long)

    private var last: Report? = null
    private var reset: Reset? = null
    private var end: End? = null

    val waitingSince: Long? get() = end?.at

    val sawReset: Boolean get() = end?.first != null

    fun waitsFor(item: Item): Boolean = sawReset && end?.next == item

    /**
     * Records the current getter, including on a late callback. [clock] is null for a pause or
     * stop, and non-playing for buffering. A real pause always wins. Returns the old item only
     * when the new start is confirmed, so a tentative length change cannot erase a saved length.
     */
    fun heard(item: Item, clock: PlaybackClock?, at: Long, elapsed: Long): Item? {
        val previous = last?.takeIf { it.item.sameTrack(item) }
        // An unknown queue ID does not erase the last known ID for the same title and artist.
        val identified = if (item.queueId == null) item.copy(queueId = previous?.item?.queueId) else item
        last = Report(identified, clock)
        if (previous == null || clock == null) {
            reset = null
            end = null
            return null
        }

        val priorReset = reset?.takeIf { at - it.at < AD_END_MS }
        val nearEndReset = previous.clock?.takeIf { it.reachesEnd(item = previous.item, at = clock.start()) }
            ?.let { clock.takeIf { it.nearStart(elapsed) } }
        if (previous.item.length != item.length) {
            reset = null
            end = beginEnd(previous, item, clock, priorReset?.clock ?: nearEndReset, at, elapsed)
        } else {
            reset = priorReset ?: nearEndReset?.let { Reset(at, it) }
        }
        return advanceEnd(item, clock, elapsed)
    }

    private fun advanceEnd(item: Item, clock: PlaybackClock, elapsed: Long): Item? {
        val pending = end ?: return null
        if (pending.next != item || clock.start() >= pending.deadlineElapsed) {
            end = null
            return null
        }
        val first = pending.first
        if (first == null) {
            // Metadata can arrive first, while the old clock still plays. Its next reset is tentative.
            if (pending.before?.reachesEnd(pending.old, clock.start()) == true && clock.nearStart(elapsed)) {
                end = pending.copy(before = null, first = clock)
            }
            return null
        }
        if (clock.playing && clock.startsAfter(first)) {
            end = null
            return pending.old
        }
        return null
    }

    fun expire(at: Long) {
        if (end?.let { at - it.at >= AD_END_MS } == true) end = null
    }

    private fun beginEnd(previous: Report, item: Item, clock: PlaybackClock, first: PlaybackClock?, at: Long, elapsed: Long): End? {
        val old = previous.item
        if (old.length <= 0 || item.length <= 0 || abs(item.length - old.length) <= SteadyLengths.SAME_MS) return null
        val time = maxOf(elapsed, clock.atMs)
        val deadline = time + AD_END_MS
        if (first != null) return End(at, old, item, null, first, deadline)
        val playing = previous.clock ?: return null
        val continuing = clock.playing && abs(clock.positionAt(time) - playing.positionAt(time)) <= SAME_CLOCK_MS
        return if (continuing && playing.reachesEnd(old, time)) End(at, old, item, playing, null, deadline) else null
    }

    // Project the last playing clock, not the time since we first heard this item. Time paused does not count.
    private fun PlaybackClock.reachesEnd(item: Item, at: Long): Boolean =
        playing && positionAt(at).let { it >= AD_END_MS && abs(item.length - it) <= SteadyLengths.SAME_MS }

    private fun PlaybackClock.nearStart(elapsed: Long): Boolean = positionAt(maxOf(atMs, elapsed)) in 0 until AD_END_MS

    private fun PlaybackClock.start(): Long = if (playing) atMs - (positionMs / speed.toDouble()).toLong() else atMs

    private fun PlaybackClock.startsAfter(first: PlaybackClock): Boolean =
        if (first.playing) first.positionAt(atMs) - positionMs > SAME_CLOCK_MS * first.speed else positionAt(first.atMs) <= first.positionMs
}
