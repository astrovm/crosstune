package com.astrovm.crosstune

import java.text.Normalizer
import kotlin.math.abs

/** A while of the song heard, from [fromMs] to [toMs] in it, and each word heard sung in it, with where in the song it starts. */
internal data class HeardStretch(val fromMs: Long, val toMs: Long, val words: List<Pair<String, Long>>)

/** Words as they sound, near enough to tell the same word heard and written. */
internal object SpokenWords {
    /** Match unspaced writing by letters, as the model can split the same phrase anywhere. */
    fun pieces(text: String): List<String> = text.split(' ').flatMap { word -> writing.findAll(plain(word)).map { it.value }.toList() }

    private val writing = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]|[^\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]+")

    /** A word as it sounds, near enough: lower case, without accents or anything but letters and digits. */
    private fun plain(word: String): String =
        Normalizer.normalize(word.lowercase(), Normalizer.Form.NFKC).flatMap { letter ->
            if (letter < '\u0250') Normalizer.normalize(letter.toString(), Normalizer.Form.NFKD).toList() else listOf(letter)
        }.filter { it.isLetterOrDigit() }.joinToString("")
}

/**
 * Words found with no timing put in time by the singing heard. Each line is looked for all through
 * what was heard, as a chorus is sung more than once; then the most lines that can all be right at
 * once, in order and far enough apart to be sung, say where each starts.
 */
internal object SungTiming {
    /**
     * [lines] put in time by what was [heard] of the song, [lengthMs] long: each line heard starts where
     * it's sung, and ends where the next starts or, with the next not heard, once it's sung through. A
     * line not heard is [LyricLine.NOT_HEARD], so it's never lit. None at all when none is heard.
     */
    fun timed(lines: List<String>, heard: List<HeardStretch>, lengthMs: Long): List<LyricLine> {
        val starts = linesMs(lines, heard, lengthMs)
        if (starts.isEmpty()) return emptyList()
        return lines.mapIndexed { index, text ->
            val start = starts[index] ?: return@mapIndexed LyricLine(LyricLine.NOT_HEARD, text)
            LyricLine(start, text, endMs = if (index + 1 in starts) null else start + SpokenWords.pieces(text).sumOf { it.length } * SUNG_LETTER_MS + LINGER_MS)
        }
    }

    /** Where each of [lines] heard sung starts in the song, [lengthMs] long, by index. Lines not heard have none. */
    fun linesMs(lines: List<String>, heard: List<HeardStretch>, lengthMs: Long): Map<Int, Long> {
        val stretches = heard.sortedBy { it.fromMs }
        val written = written(lines)
        if (written.isEmpty()) return emptyMap()
        val total = written.last().letters + written.last().text.length
        val candidates = written.groupBy { it.line }.flatMap { (line, pieces) ->
            val expected = lengthMs * (INTRO + (1 - INTRO - OUTRO) * pieces.first().letters / total)
            // Each while alone, as words heard at the end of one and the start of the next weren't sung together.
            stretches.flatMap { found(sung(it.words), pieces) }.map { (ms, score, alike) ->
                Placed(line, ms, score - PRIOR * maxOf(0.0, abs(ms - expected) / lengthMs - TOLERANCE), alike)
            }
        }.filter { it.support >= MIN_SUPPORT }
        val firstLetters = written.groupBy { it.line }.mapValues { (_, its) -> its.first().letters }
        val chain = backed(sure(candidates, firstLetters), firstLetters)
        val placed = chain + filled(chain, firstLetters, stretches)
        return placed.filter { placed -> stretches.any { placed.ms in it.fromMs - EDGE_MS..it.toMs } }.associate { it.line to it.ms }
    }

    private class Placed(val line: Int, val ms: Long, val support: Double, val words: Int)

    /**
     * The lines of the best chain of [candidates] that it can't do about as well without: with one gone,
     * another line found at its time often takes its place, as a chorus sung again has the same words.
     * Of two such chains, nothing heard says which is right, so the line isn't put at all.
     */
    private fun sure(candidates: List<Placed>, firstLetters: Map<Int, Int>): List<Placed> {
        val best = chained(candidates, firstLetters)
        if (best.isEmpty()) return best
        val weight = best.sumOf { it.support }
        val sorted = candidates.sortedBy { it.ms }
        // The best chain ending at each, and starting at each; one that can follow another is always later.
        val ending = DoubleArray(sorted.size) { sorted[it].support }
        val starting = DoubleArray(sorted.size) { sorted[it].support }
        for (k in sorted.indices) for (j in 0 until k) if (follows(sorted[j], sorted[k], firstLetters)) ending[k] = maxOf(ending[k], ending[j] + sorted[k].support)
        for (k in sorted.indices.reversed()) for (j in k + 1 until sorted.size) if (follows(sorted[k], sorted[j], firstLetters)) starting[k] = maxOf(starting[k], starting[j] + sorted[k].support)
        // For each, the best chain that can follow it from a line on: a chain with no line between the two.
        val lines = sorted.maxOf { it.line } + 2
        val after = Array(sorted.size) { DoubleArray(lines) }
        for (k in sorted.indices) {
            for (j in k + 1 until sorted.size) if (follows(sorted[k], sorted[j], firstLetters)) after[k][sorted[j].line] = maxOf(after[k][sorted[j].line], starting[j])
            for (line in lines - 2 downTo 0) after[k][line] = maxOf(after[k][line], after[k][line + 1])
        }
        return best.filter { placed ->
            val elsewhere = sorted.indices.filter { sorted[it].line == placed.line && abs(sorted[it].ms - placed.ms) > SAME_PLACE_MS }
                .maxOfOrNull { ending[it] + starting[it] - sorted[it].support } ?: 0.0
            val before = sorted.indices.filter { sorted[it].line < placed.line }
            val skipped = maxOf(
                before.maxOfOrNull { ending[it] + after[it][placed.line + 1] } ?: 0.0,
                sorted.indices.filter { sorted[it].line > placed.line }.maxOfOrNull { starting[it] } ?: 0.0,
            )
            maxOf(elsewhere, skipped) < weight - placed.support * SURE_SHARE
        }
    }

    /**
     * Lines found by few words, kept only beside a line placed where they'd follow: two words alike
     * by chance are common enough, two lines in a row by chance aren't.
     */
    private fun backed(chain: List<Placed>, firstLetters: Map<Int, Int>): List<Placed> = chain.filterIndexed { k, placed ->
        placed.words >= SURE_WORDS || listOfNotNull(chain.getOrNull(k - 1), chain.getOrNull(k + 1)).any { other ->
            val (a, b) = if (other.line < placed.line) other to placed else placed to other
            b.line - a.line <= NEIGHBOR_LINES && b.ms - a.ms <= (firstLetters.getValue(b.line) - firstLetters.getValue(a.line)) * SLOW_LETTER_MS + NEIGHBOR_MS
        }
    }

    /**
     * Lines between two placed close together, all heard, where their letters put them between: each
     * only with a voice heard while it'd be sung, as a line is never put where nothing was heard.
     */
    private fun filled(chain: List<Placed>, firstLetters: Map<Int, Int>, stretches: List<HeardStretch>): List<Placed> =
        chain.zipWithNext().flatMap { (a, b) ->
            val heard = stretches.filter { a.ms >= it.fromMs && b.ms <= it.toMs }
            if (b.line - a.line < 2 || b.ms - a.ms > FILL_MS || heard.isEmpty()) return@flatMap emptyList()
            val from = firstLetters.getValue(a.line)
            val to = firstLetters.getValue(b.line)
            val put = (a.line + 1 until b.line).filter { it in firstLetters }.map { line ->
                Placed(line, a.ms + (b.ms - a.ms) * (firstLetters.getValue(line) - from) / (to - from), 0.0, 0)
            }
            val voice = heard.flatMap { sung(it.words) }.map { it.second }
            put.filterIndexed { k, placed -> voice.any { it in placed.ms until (put.getOrNull(k + 1)?.ms ?: b.ms) } }
        }

    /**
     * Each place in [heard] a line's [pieces] are sung, as far as they can be told: where the line
     * starts there, and how sure. Local matches, so one line can be found where each chorus is.
     */
    private fun found(heard: List<Pair<String, Long>>, pieces: List<Written>): List<Triple<Long, Double, Int>> {
        val n = heard.size
        val m = pieces.size
        val best = Array(n + 1) { DoubleArray(m + 1) }
        val score = Array(n) { i -> DoubleArray(m) { j -> matchScore(heard[i].first, pieces[j]) } }
        for (i in 1..n) for (j in 1..m) {
            best[i][j] = maxOf(0.0, best[i - 1][j - 1] + score[i - 1][j - 1], best[i - 1][j] - SKIP_HEARD, best[i][j - 1] - SKIP_WRITTEN)
        }
        val ends = (1..n).flatMap { i -> (1..m).map { j -> i to j } }.filter { (i, j) -> best[i][j] >= MIN_SUPPORT }.sortedByDescending { (i, j) -> best[i][j] }
        val taken = mutableListOf<IntRange>()
        val out = mutableListOf<Triple<Long, Double, Int>>()
        for ((endI, endJ) in ends) {
            var i = endI
            var j = endJ
            val pairs = mutableListOf<Pair<Int, Int>>()
            while (i > 0 && j > 0 && best[i][j] > 0) {
                when {
                    abs(best[i][j] - best[i - 1][j - 1] - score[i - 1][j - 1]) < 1e-9 -> { pairs += (i - 1 to j - 1); i--; j-- }
                    abs(best[i][j] - best[i - 1][j] + SKIP_HEARD) < 1e-9 -> i--
                    else -> j--
                }
            }
            val alike = pairs.filter { (h, w) -> likeness(heard[h].first, pieces[w].text) >= ALIKE }
            // Followed back from the end, the pairs come last word first.
            val span = pairs.last().first..pairs.first().first
            if (alike.size < MIN_WORDS || taken.any { it.first <= span.last && span.first <= it.last }) continue
            taken += span
            // Each word says where the line starts, by the letters before it. The earliest says it: a line is
            // often sung slower than a letter each LETTER_MS, held or with a rest in it, so later words say too late.
            val start = alike.minOf { (h, w) -> heard[h].second - pieces[w].before * LETTER_MS }
            out += Triple(start, best[endI][endJ], alike.size)
        }
        return out
    }

    /**
     * The most [candidates] that can all be right at once: one place for each line at most, in order,
     * and each far enough after the last for the lines between to be sung, however fast.
     */
    private fun chained(candidates: List<Placed>, firstLetters: Map<Int, Int>): List<Placed> {
        if (candidates.isEmpty()) return emptyList()
        // Candidates come in line order, and the sort keeps it for lines at one time.
        val sorted = candidates.sortedBy { it.ms }
        val best = DoubleArray(sorted.size)
        // Where each one's chain comes from, counted from 1, or 0 where it starts.
        val from = IntArray(sorted.size)
        for (k in sorted.indices) {
            best[k] = sorted[k].support
            for (j in 0 until k) {
                if (follows(sorted[j], sorted[k], firstLetters) && best[j] + sorted[k].support > best[k]) {
                    best[k] = best[j] + sorted[k].support
                    from[k] = j + 1
                }
            }
        }
        var k = best.indices.maxBy { best[it] } + 1
        val chain = mutableListOf<Placed>()
        while (k > 0) {
            chain += sorted[k - 1]
            k = from[k - 1]
        }
        return chain.reversed()
    }

    /** If [b] can be sung after [a]: a later line, and far enough after for the lines between to be sung, however fast. */
    private fun follows(a: Placed, b: Placed, firstLetters: Map<Int, Int>) =
        b.line > a.line && b.ms - a.ms >= (firstLetters.getValue(b.line) - firstLetters.getValue(a.line)) * FAST_LETTER_MS

    /** The pieces of [lines]' words, each with its line, the letters before it in its line, and before it in all. */
    private fun written(lines: List<String>): List<Written> {
        var all = 0
        val pieces = lines.withIndex().flatMap { (line, text) ->
            var before = 0
            SpokenWords.pieces(text).map { piece -> Written(piece, line, before, all).also { before += piece.length; all += piece.length } }
        }
        // A word in many lines says little of which it is, though a line sung again says nothing against its words; one letter, as "a" or "の", even less.
        val counts = pieces.groupBy { it.text }.mapValues { (_, its) -> its.map { lines[it.line] }.distinct().size }
        pieces.forEach { it.weight = (if (it.text.length < 2) SHORT else 1.0) / kotlin.math.sqrt(counts.getValue(it.text).toDouble()) }
        return pieces
    }

    /** Pieces of the words sung, with where they start, less the sounds the model tells of, as "[MUSIC]" or "♪". */
    private fun sung(words: List<Pair<String, Long>>): List<Pair<String, Long>> {
        var told = false
        return words.flatMap { (text, ms) ->
            val sound = told || text.startsWith('[') || text.startsWith('(')
            told = sound && !text.endsWith(']') && !text.endsWith(')')
            var before = 0L
            // A word of unspaced writing is matched a letter at a time; each letter is a little after the one before.
            if (sound) emptyList() else SpokenWords.pieces(text).map { piece -> (piece to ms - HEARD_LATE_MS + before * LETTER_MS).also { before += piece.length } }
        }
    }

    /** How alike a word heard and one written sound: 1 the same, 0 nothing alike, by the letters one needs changed to make the other. */
    private fun likeness(heard: String, written: String): Double {
        if (heard == written) return 1.0
        if (heard.length < 2 || written.length < 2) return 0.0
        val row = IntArray(written.length + 1) { it }
        for (i in 1..heard.length) {
            var diagonal = row[0]
            row[0] = i
            for (j in 1..written.length) {
                val above = row[j]
                row[j] = minOf(row[j] + 1, row[j - 1] + 1, diagonal + if (heard[i - 1] == written[j - 1]) 0 else 1)
                diagonal = above
            }
        }
        return 1.0 - row[written.length].toDouble() / maxOf(heard.length, written.length)
    }

    private fun matchScore(heard: String, written: Written): Double {
        val like = likeness(heard, written.text)
        return if (like < ALIKE) -MISMATCH else MATCH * written.weight * (like - ALIKE) / (1 - ALIKE) + MATCH_BASE
    }

    private class Written(val text: String, val line: Int, val before: Int, val letters: Int) {
        var weight = 1.0
    }

    // Measured with `scripts/syncreplay/syncreplay.py untimed` on the recordings there: each the one that timed
    // the most lines within a second of when they're sung, of those tried, with few placed far off.

    /** Words alike a line has to have to be found: one alike by chance is common. */
    private const val MIN_WORDS = 2

    /** Words alike a line found needs to stand alone; with fewer, it needs a line next to it found where it'd follow. */
    private const val SURE_WORDS = 3
    private const val NEIGHBOR_LINES = 1
    private const val SLOW_LETTER_MS = 200L
    private const val NEIGHBOR_MS = 2_000L

    /** Lines between two found this close together, in one while heard, are put between them. */
    private const val FILL_MS = 8_000L

    /**
     * A line in the best chain is sure only if the best without it, found this near where it is, weighs
     * less by more than this share of its own weight.
     */
    private const val SAME_PLACE_MS = 6_000L
    private const val SURE_SHARE = 0.5

    /** How sure a line found has to be, by its words alike, as [matchScore] has them. */
    private const val MIN_SUPPORT = 1.0

    /** How alike, by its letters, a word heard has to be to be taken for one written. */
    private const val ALIKE = 0.5
    private const val MATCH = 2.0
    private const val MATCH_BASE = 0.2
    private const val MISMATCH = 1.0

    /** How much less a word of one letter says, as "a" or "の". */
    private const val SHORT = 0.5

    /** What a word heard and not written costs, as one misheard; or one written and not heard. */
    private const val SKIP_HEARD = 0.5
    private const val SKIP_WRITTEN = 0.5

    /**
     * Where a line is likely sung, by the letters before it: from [INTRO] of the song to all but its
     * [OUTRO]. Found further from there than [TOLERANCE] of the song, it's that much less sure, as a
     * chorus sung again elsewhere.
     */
    private const val PRIOR = 4.0
    private const val TOLERANCE = 0.25
    private const val INTRO = 0.1
    private const val OUTRO = 0.1

    /** About how long a letter takes to sing, to tell where a line starts by a word heard in it. */
    const val LETTER_MS = 70L

    /** Faster than anyone sings, a letter at a time: lines found closer together than that can't both be right. */
    private const val FAST_LETTER_MS = 60L

    /** How far before what was heard a line found in it may start: its first words may be just before. */
    private const val EDGE_MS = 1_000L

    /** About how late the speech model has a word start: a little after it's sung. */
    const val HEARD_LATE_MS = 550L

    /** About how long a line takes to sing, a letter at a time, at an everyday pace, and a moment more it stays lit after. */
    private const val SUNG_LETTER_MS = 120L
    private const val LINGER_MS = 1_000L
}
