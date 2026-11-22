package com.astrovm.crosstune

import kotlin.math.abs

/** Separate audio observations must agree before anything is corrected or remembered. */
internal object AudioTiming {
    /**
     * How far the song is from the words, once three separate lines at least 8 s apart heard it so, within 1.5 s
     * of each other; null until then. A video's intro moves every line by the same amount, so lines that drift
     * apart as the song goes on are no lead to remember.
     */
    fun confirmedShift(points: List<SyncPoint>): Long? {
        val ordered = points.distinctBy { it.lineMs }.sortedBy { it.lineMs }
        // Fewer than three lines can't be three steady ones; none would have no middle shift at all.
        if (ordered.isEmpty()) return null
        val shifts = ordered.map { it.songMs - it.lineMs }.sorted()
        val median = shifts[shifts.size / 2]
        val steady = ordered.filter { abs(it.songMs - it.lineMs - median) <= 1_500 }
        if (steady.size < 3 || steady.last().lineMs - steady.first().lineMs < 8_000) return null
        return steady.map { it.songMs - it.lineMs }.sorted().let { it[it.size / 2] }
    }
}
