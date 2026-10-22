package com.astrovm.crosstune

import kotlin.math.abs

/** Separate audio observations must agree before anything is corrected or remembered. */
internal object AudioTiming {
    /** Three separate lines must support the same shift or a gentle drift before anything moves. */
    fun confirmed(points: List<SyncPoint>): List<SyncPoint> {
        val ordered = points.distinctBy { it.lineMs }.sortedBy { it.lineMs }
        if (ordered.size < 3 || ordered.last().lineMs - ordered.first().lineMs < 8_000) return emptyList()
        val shifts = ordered.map { it.songMs - it.lineMs }.sorted()
        val median = shifts[shifts.size / 2]
        val steady = ordered.filter { abs(it.songMs - it.lineMs - median) <= 1_500 }
        if (steady.size >= 3 && steady.last().lineMs - steady.first().lineMs >= 8_000) {
            val agreed = steady.map { it.songMs - it.lineMs }.sorted().let { it[it.size / 2] }
            return listOf(SyncPoint(steady.first().lineMs, steady.first().lineMs + agreed))
        }
        val first = ordered.first()
        val last = ordered.last()
        val span = last.lineMs - first.lineMs
        val rate = (last.songMs - first.songMs).toDouble() / span
        if (span < 30_000 || rate !in 0.9..1.1) return emptyList()
        if (ordered.any { abs(it.songMs - (first.songMs + (it.lineMs - first.lineMs) * rate)) > 1_000 }) return emptyList()
        return listOf(first, last)
    }
}
