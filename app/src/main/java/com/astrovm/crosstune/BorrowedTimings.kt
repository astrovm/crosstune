package com.astrovm.crosstune

import java.text.Normalizer
import kotlin.math.abs

/**
 * Words timed from another copy of the same song. One place may have the words as written, timed
 * badly or not at all, and another the same song well timed, even in other letters, e.g. romaji.
 * Both are read as sounds and lined up letter by letter; each line takes its time from where the
 * other copy's lines start, and a line inside one of them, two lines sung as one there, from how
 * far into it it starts.
 */
internal object BorrowedTimings {
    /** A line of the other copy counts once this much of it lines up with the words. */
    private const val GOOD_LINE = 0.6

    /** And the other copy counts once this many of its lines do. */
    private const val GOOD_LINES = 0.6

    /** Lining up longer words than this would take too long and too much memory. */
    private const val MAX_CELLS = 12_000_000L

    /** How far apart, on the middle line, borrowed times and rough ones can be and still agree. */
    const val AGREE_MS = 8_000L

    private val marks = Regex("\\p{Mn}+")
    private val notSound = Regex("[^a-z0-9]")

    /** [text] as the letters it's said with, without accents, spaces or punctuation. */
    fun sound(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(marks, "").lowercase().replace(notSound, "")

    /**
     * [lines], said as [sounds], timed as [other], said as [otherSounds], times them; null when the
     * two don't hold the same words.
     */
    fun retime(lines: List<String>, sounds: List<String>, other: List<LyricLine>, otherSounds: List<String>): List<LyricLine>? {
        val a = sounds.joinToString("")
        val b = otherSounds.joinToString("")
        if (a.isEmpty() || b.isEmpty() || a.length.toLong() * b.length > MAX_CELLS) return null
        val starts = sounds.runningFold(0) { at, sound -> at + sound.length }
        val otherStarts = otherSounds.runningFold(0) { at, sound -> at + sound.length }
        // Where in the words each letter of the other copy lines up, where it does.
        val matchedAt = lineUp(a, b)
        val anchors = mutableListOf<Pair<Int, Long>>()
        var good = 0
        other.indices.forEach { line ->
            val from = otherStarts[line]
            val to = otherStarts[line + 1]
            if (to == from) return@forEach
            val matched = (from until to).count { matchedAt[it] >= 0 }
            if (matched < (to - from) * GOOD_LINE) return@forEach
            good++
            val first = (from until to).first { matchedAt[it] >= 0 }
            anchors += matchedAt[first] to other[line].timeMs
        }
        if (good < other.count { it.text.isNotBlank() } * GOOD_LINES) return null
        // Each later than the last, in the words and in time.
        val kept = anchors.fold(mutableListOf<Pair<Int, Long>>()) { kept, anchor ->
            kept.also { if (it.isEmpty() || (anchor.first > it.last().first && anchor.second > it.last().second)) it += anchor }
        }
        if (kept.size < 2) return null
        return lines.mapIndexed { index, text -> LyricLine(timeAt(starts[index], kept), text) }
    }

    /** Whether [borrowed] times are close to [rough] ones, line for line, as for the same take of the song. */
    fun agrees(rough: List<LyricLine>, borrowed: List<LyricLine>): Boolean {
        if (rough.size != borrowed.size || rough.isEmpty()) return false
        val apart = rough.indices.map { abs(rough[it].timeMs - borrowed[it].timeMs) }.sorted()
        return apart[apart.size / 2] <= AGREE_MS
    }

    /** The time at letter [at] of the words, between the [anchors] around it, or as far on from the nearest. */
    private fun timeAt(at: Int, anchors: List<Pair<Int, Long>>): Long {
        val (firstAt, firstTime) = anchors.first()
        val (lastAt, lastTime) = anchors.last()
        val perLetter = (lastTime - firstTime).toDouble() / (lastAt - firstAt)
        val after = anchors.indexOfFirst { it.first > at }
        return when {
            after == 0 -> (firstTime - (firstAt - at) * perLetter).toLong().coerceAtLeast(0L)
            after < 0 -> (lastTime + (at - lastAt) * perLetter).toLong()
            else -> {
                val (fromAt, fromTime) = anchors[after - 1]
                val (toAt, toTime) = anchors[after]
                fromTime + (toTime - fromTime) * (at - fromAt) / (toAt - fromAt)
            }
        }
    }

    /**
     * For each letter of [b], the letter of [a] it lines up with when both are lined up as closely as
     * they go, or -1 where it doesn't.
     */
    private fun lineUp(a: String, b: String): IntArray {
        val n = a.length
        val m = b.length
        // How each cell was reached: 0 both letters, 1 a letter of [a] alone, 2 one of [b] alone.
        val from = ByteArray((n + 1) * (m + 1))
        var previous = IntArray(m + 1) { -it }
        var current = IntArray(m + 1)
        for (j in 1..m) from[j] = 2
        for (i in 1..n) {
            current[0] = -i
            from[i * (m + 1)] = 1
            for (j in 1..m) {
                val both = previous[j - 1] + if (a[i - 1] == b[j - 1]) 1 else -1
                val skipA = previous[j] - 1
                val skipB = current[j - 1] - 1
                if (both >= skipA && both >= skipB) {
                    current[j] = both
                } else if (skipA >= skipB) {
                    current[j] = skipA
                    from[i * (m + 1) + j] = 1
                } else {
                    current[j] = skipB
                    from[i * (m + 1) + j] = 2
                }
            }
            previous = current.also { current = previous }
        }
        val matchedAt = IntArray(m) { -1 }
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            when (from[i * (m + 1) + j].toInt()) {
                0 -> {
                    if (a[i - 1] == b[j - 1]) matchedAt[j - 1] = i - 1
                    i--
                    j--
                }
                1 -> i--
                else -> j--
            }
        }
        return matchedAt
    }
}
