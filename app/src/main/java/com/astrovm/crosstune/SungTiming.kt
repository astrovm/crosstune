package com.astrovm.crosstune

import java.text.Normalizer
import kotlin.math.abs

/** A while of the song heard, from [fromMs] to [toMs] in it, and each word heard sung in it, with where in the song it starts. */
internal data class HeardStretch(val fromMs: Long, val toMs: Long, val words: List<Pair<String, Long>>)

/**
 * A piece of a word heard: where it's sung, about, and where its word starts, with [within] letters of the word before it.
 * [opening] if its word is the first thing heard in its while.
 */
internal class HeardPiece(val text: String, val ms: Long, val wordMs: Long, val within: Int, val opening: Boolean)

/** Words as they sound, near enough to tell the same word heard and written. */
internal object SpokenWords {
    /** Match unspaced writing by letters, as the model can split the same phrase anywhere. */
    fun pieces(text: String): List<String> = text.split(' ').flatMap { word -> writing.matchesIn(plain(word)).map { it.value }.toList() }

    /**
     * [text]'s pieces heard from [ms], each with where it starts: a word of unspaced writing is matched a letter
     * at a time, and each letter is sung a little after the one before, not all at the word's start. [opening] if
     * it's the first thing heard in its while.
     */
    fun heardFrom(text: String, ms: Long, opening: Boolean): List<HeardPiece> {
        var before = 0
        return pieces(text).map { piece -> HeardPiece(piece, ms + before * SungTiming.LETTER_MS, ms, before, opening).also { before += sungLetters(piece) } }
    }

    /**
     * How many letters [piece] takes to sing, each about [SungTiming.LETTER_MS]: a letter of unspaced writing is a
     * whole syllable, or a word, and takes as long as [UNSPACED_LETTERS] of ours.
     */
    fun sungLetters(piece: String): Int = if (unspaced.matches(piece)) UNSPACED_LETTERS else piece.length

    /** Picked on the songs heard, as scripts/syncreplay/measurements.md has it: a Japanese letter, as whisper hears it, is near 300 ms. */
    private const val UNSPACED_LETTERS = 4

    private val unspaced = phoneRegex("[\\p{sc=Han}\\p{sc=Hiragana}\\p{sc=Katakana}\\p{sc=Hangul}]")
    private val writing = phoneRegex("[\\p{sc=Han}\\p{sc=Hiragana}\\p{sc=Katakana}\\p{sc=Hangul}]|[^\\p{sc=Han}\\p{sc=Hiragana}\\p{sc=Katakana}\\p{sc=Hangul}]+")

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
        val total = written.last().letters + SpokenWords.sungLetters(written.last().text)
        val byLine = written.groupBy { it.line }
        val candidates = byLine.flatMap { (line, pieces) ->
            val expected = lengthMs * (INTRO + (1 - INTRO - OUTRO) * pieces.first().letters / total)
            // Each while alone, as words heard at the end of one and the start of the next weren't sung together.
            stretches.flatMap { found(sung(it.words, it.fromMs), pieces) }.map { (ms, score, alike, heardMs, owned) ->
                Placed(line, ms, score - PRIOR * maxOf(0.0, abs(ms - expected) / lengthMs - TOLERANCE), alike, heardMs, pieces.first().written, owned)
            }
        }.filter { it.support >= MIN_SUPPORT }
        val firstLetters = byLine.mapValues { (_, its) -> its.first().letters }
        val durations = byLine.mapValues { (_, pieces) -> pieces.sumOf { SpokenWords.sungLetters(it.text) } * LETTER_MS }
        val chain = backed(sure(candidates, byLine.keys.max() + 2, durations), firstLetters)
        val placed = chain + filled(chain, firstLetters, stretches, byLine)
        return placed.filter { placed -> stretches.any { placed.ms in it.fromMs - EDGE_MS..it.toMs } }.associate { it.line to it.ms }
    }

    /** A raw ASR word has one time even when it covers many written syllables. */
    private fun singleWord(placed: Placed) = placed.heardMs.start.ms == placed.heardMs.endInclusive.ms

    /** A piece's place in the singing: its word's time, then its position in that word. No guessed time between letters. */
    internal data class HeardAt(val ms: Long, val within: Int) : Comparable<HeardAt> {
        override fun compareTo(other: HeardAt): Int {
            val time = ms.compareTo(other.ms)
            return if (time != 0) time else within.compareTo(other.within)
        }
    }

    /** A line put at [ms], by [words] heard alike, over [heardMs]. An empty span for a line filled between two. */
    internal class Placed(val line: Int, val ms: Long, val support: Double, val words: Int, val heardMs: ClosedRange<HeardAt>, writtenBefore: Int, val owned: Set<HeardAt> = emptySet()) {
        // b.ms - a.ms >= (b.writtenBefore - a.writtenBefore) * FAST_LETTER_MS, rearranged once.
        val orderMs = ms - writtenBefore * FAST_LETTER_MS
    }

    /** Where a line is found in a while: where it starts, how sure, by how many words heard, and when the first and last of them start. */
    private data class Found(val ms: Long, val score: Double, val words: Int, val heardMs: ClosedRange<HeardAt>, val owned: Set<HeardAt>)

    /**
     * The lines of the best chain of [candidates] that it can't do about as well without: with one gone,
     * another line found at its time often takes its place, as a chorus sung again has the same words.
     * Of two such chains, nothing heard says which is right, so the line isn't put at all.
     */
    internal fun sure(candidates: List<Placed>, lines: Int, durations: Map<Int, Long>): List<Placed> {
        val sorted = candidates.sortedBy { it.ms }
        val (ending, from) = ending(sorted)
        val best = chained(sorted, ending, from)
        val weight = best.sumOf { it.support }
        val (starting, after) = starting(sorted, lines)
        return best.filter { placed ->
            val elsewhere = sorted.indices.filter { sorted[it].line == placed.line && elsewhere(placed, sorted[it], durations.getValue(placed.line)) }
                .maxOfOrNull { ending[it] + starting[it] - sorted[it].support } ?: 0.0
            val before = sorted.indices.filter { sorted[it].line < placed.line }
            val skipped = maxOf(
                before.maxOfOrNull { ending[it] + after[it][placed.line + 1] } ?: 0.0,
                sorted.indices.filter { sorted[it].line > placed.line }.maxOfOrNull { starting[it] } ?: 0.0,
            )
            maxOf(elsewhere, skipped) < weight - placed.support * SURE_SHARE
        }
    }

    /** One phrase heard as one word has no internal timing to make two separate singings one observation. */
    private fun elsewhere(a: Placed, b: Placed, durationMs: Long): Boolean {
        val singleWords = singleWord(a) && singleWord(b)
        val samePlaceMs = if (singleWords) minOf(SAME_PLACE_MS, durationMs) else SAME_PLACE_MS
        return abs(a.ms - b.ms) > samePlaceMs
    }

    /** Best weight and predecessor at each candidate, from the earlier lines that can precede it. */
    private fun ending(sorted: List<Placed>): Pair<DoubleArray, IntArray> {
        val ending = DoubleArray(sorted.size) { sorted[it].support }
        val from = IntArray(sorted.size)
        val scores = ChainScores(sorted, ending, reverse = false)
        for (group in sorted.indices.groupBy { sorted[it].line }.toSortedMap().values) {
            for (k in group) {
                val before = scores.best(k)
                if (before >= 0) {
                    ending[k] += ending[before]
                    from[k] = before + 1
                }
            }
            group.forEach(scores::add)
        }
        return ending to from
    }

    /** Best weight from each candidate, and from each later line that can follow it. */
    private fun starting(sorted: List<Placed>, lines: Int): Pair<DoubleArray, Array<DoubleArray>> {
        val starting = DoubleArray(sorted.size) { sorted[it].support }
        val after = Array(sorted.size) { DoubleArray(lines) }
        val scores = ChainScores(sorted, starting, reverse = true)
        val groups = sorted.indices.groupBy { sorted[it].line }
        for (line in lines - 2 downTo 0) {
            val group = groups[line].orEmpty()
            for (k in group) starting[k] += scores.weight(k)
            group.forEach(scores::add)
            for (k in sorted.indices) {
                if (sorted[k].line >= line) continue
                after[k][line] = if (group.isEmpty()) after[k][line + 1] else scores.weight(k)
            }
        }
        return starting to after
    }

    /**
     * Best chain weights by two bounds: the start corrected for the letters before
     * it, and the first or last heard piece. A prior line must have orderMs no greater than this
     * one and end no later than its first heard piece. Lines enter together, so none follows itself.
     * A two-dimensional prefix tree answers each bound without comparing every pair of matches.
     */
    internal class ChainScores(candidates: List<Placed>, private val weights: DoubleArray, reverse: Boolean) {
        private val times = candidates.map { it.orderMs }.distinct().sorted().toLongArray()
        private val places = candidates.flatMap { listOf(it.heardMs.start, it.heardMs.endInclusive) }.distinct().sorted()
        private val xs = IntArray(candidates.size) { k ->
            val at = times.binarySearch(candidates[k].orderMs)
            if (reverse) times.size - at else at + 1
        }
        private val inserted = IntArray(candidates.size) { k ->
            val at = places.binarySearch(if (reverse) candidates[k].heardMs.start else candidates[k].heardMs.endInclusive)
            if (reverse) places.lastIndex - at else at
        }
        private val asked = IntArray(candidates.size) { k ->
            val at = places.binarySearch(if (reverse) candidates[k].heardMs.endInclusive else candidates[k].heardMs.start)
            if (reverse) places.lastIndex - at else at
        }
        private val rows = Array(times.size + 1) { mutableListOf<Int>() }.let { pending ->
            for (k in candidates.indices) {
                var x = xs[k]
                while (x < pending.size) {
                    pending[x] += inserted[k]
                    x += x and -x
                }
            }
            pending.map { it.distinct().sorted().toIntArray() }
        }
        private val best = rows.map { IntArray(it.size + 1) { -1 } }
        // These bounds do not change as weights enter. Look up each path once, including its y bound.
        private val updates = Array(candidates.size) { k ->
            buildList {
                var x = xs[k]
                while (x < rows.size) {
                    val at = rows[x].binarySearch(inserted[k])
                    check(at >= 0) { "A chain point is missing from its row" }
                    add(x)
                    add(at + 1)
                    x += x and -x
                }
            }.toIntArray()
        }
        private val queries = Array(candidates.size) { k ->
            buildList {
                var x = xs[k]
                while (x > 0) {
                    val at = rows[x].binarySearch(asked[k])
                    add(x)
                    add(if (at >= 0) at + 1 else -at - 1)
                    x -= x and -x
                }
            }.toIntArray()
        }

        fun add(k: Int) {
            val path = updates[k]
            for (at in path.indices step 2) {
                val row = best[path[at]]
                var y = path[at + 1]
                while (y < row.size) {
                    if (better(k, row[y])) row[y] = k
                    y += y and -y
                }
            }
        }

        fun best(k: Int): Int {
            var found = -1
            val path = queries[k]
            for (at in path.indices step 2) {
                val row = best[path[at]]
                var y = path[at + 1]
                while (y > 0) {
                    if (better(row[y], found)) found = row[y]
                    y -= y and -y
                }
            }
            return found
        }

        fun weight(k: Int): Double {
            val found = best(k)
            return if (found >= 0) weights[found] else 0.0
        }

        // The pairwise pass keeps the first candidate on equal weights. Keep that tie rule here too.
        private fun better(a: Int, b: Int): Boolean =
            a >= 0 && (b < 0 || weights[a] > weights[b] || weights[a] == weights[b] && a < b)
    }

    /**
     * Lines found by few words, kept only beside a line placed where they'd follow: two words alike
     * by chance are common enough, two lines in a row by chance aren't. Closer to one than its syllables take
     * at a quick pace, it may be that line's own words: the end of a Japanese line can start the next.
     */
    private fun backed(chain: List<Placed>, firstLetters: Map<Int, Int>): List<Placed> = chain.filterIndexed { k, placed ->
        val beside = listOfNotNull(chain.getOrNull(k - 1), chain.getOrNull(k + 1)).map { other -> if (other.line < placed.line) other to placed else placed to other }
        val letters = { a: Placed, b: Placed -> firstLetters.getValue(b.line) - firstLetters.getValue(a.line) }
        placed.words >= SURE_WORDS || beside.none { (a, b) -> b.ms - a.ms < letters(a, b) * FAST_LETTER_MS } && beside.any { (a, b) ->
            b.line - a.line <= NEIGHBOR_LINES && b.ms - a.ms <= letters(a, b) * SLOW_LETTER_MS + NEIGHBOR_MS
        }
    }

    /**
     * Lines between two placed close together, all heard, where their letters put them between: each
     * only with a voice heard while it'd be sung, as a line is never put where nothing was heard.
     */
    private fun filled(chain: List<Placed>, firstLetters: Map<Int, Int>, stretches: List<HeardStretch>, written: Map<Int, List<Written>>): List<Placed> =
        chain.zipWithNext().flatMap { (a, b) ->
            val between = (a.line + 1 until b.line).filter { it in firstLetters }
            // Lines next to each other, or with only lines with no words between, have nothing to put.
            if (between.isEmpty()) return@flatMap emptyList()
            val heard = stretches.filter { a.ms >= it.fromMs && b.ms <= it.toMs }
            val from = firstLetters.getValue(a.line)
            val to = firstLetters.getValue(b.line)
            val put = between.map { line -> Placed(line, a.ms + (b.ms - a.ms) * (firstLetters.getValue(line) - from) / (to - from), 0.0, 0, HeardAt(1, 0)..HeardAt(0, 0), written.getValue(line).first().written) }
            // Heard in no one while from one to the other, no voice says anything is sung between.
            val wordsHeard = heard.flatMap { sung(it.words, it.fromMs) }
            val fills = if (b.ms - a.ms > FILL_MS) farFills(put, a, b, written, wordsHeard)
                else nearFills(put, a, b, written, wordsHeard)
            // Use the chain's minimum pace too. Conflicting words do not say which fill is right; keep neither.
            val latestBefore = fills.runningFold(a.orderMs) { latest, placed -> maxOf(latest, placed.orderMs) }
            val earliestAfter = fills.asReversed().runningFold(b.orderMs) { earliest, placed -> minOf(earliest, placed.orderMs) }.asReversed()
            fills.filterIndexed { k, placed -> placed.orderMs >= latestBefore[k] && placed.orderMs <= earliestAfter[k + 1] }
        }

    /** Keep voice slots fixed, but carry each actual start's minimum pace into the next guess. */
    private fun nearFills(put: List<Placed>, a: Placed, b: Placed, written: Map<Int, List<Written>>, heard: List<HeardPiece>): List<Placed> {
        val voice = heard.filter { HeardAt(it.wordMs, it.within) !in a.owned && HeardAt(it.wordMs, it.within) !in b.owned }
        var previousOrder = a.orderMs
        return put.mapIndexedNotNull { k, guess ->
            val slotEnd = put.getOrNull(k + 1)?.ms ?: b.ms
            if (voice.none { it.ms in guess.ms until slotEnd }) return@mapIndexedNotNull null
            val pieces = written.getValue(guess.line)
            // Sung and written letters weigh scripts differently. Actual starts can also be later than guesses.
            val nearGuess = guess.ms + (guess.orderMs.coerceIn(previousOrder, b.orderMs) - guess.orderMs)
            val after = maxOf(nearGuess, a.heardMs.endInclusive.ms)
            // A one-word match stays in its original voice slot, so it cannot borrow a later line's word.
            val word = if (pieces.size == 1) voice.filter { it.ms in after until slotEnd && likeness(it.text, pieces.single().text) >= ALIKE }
                .minByOrNull { abs(it.ms - nearGuess) }?.ms else null
            val placed = Placed(guess.line, word ?: after, guess.support, guess.words, guess.heardMs, pieces.first().written)
            // Leave room for every written letter before the next anchor. No later guess can move that anchor.
            if (placed.orderMs > b.orderMs) null else {
                previousOrder = placed.orderMs
                placed
            }
        }
    }

    /** Across a long gap, only a unique word between both anchors can identify a missing line. */
    private fun farFills(put: List<Placed>, a: Placed, b: Placed, written: Map<Int, List<Written>>, heard: List<HeardPiece>): List<Placed> {
        val wordLines = (listOf(a.line) + put.map { it.line } + b.line).flatMap { written.getValue(it) }.groupBy { it.text }
            .mapValues { (_, pieces) -> pieces.map { it.line }.distinct().size }
        val inside = heard.filter { HeardAt(it.wordMs, it.within) > a.heardMs.endInclusive && HeardAt(it.wordMs, it.within) < b.heardMs.start }
        val span = maxOf(a.ms, a.heardMs.endInclusive.ms) until b.ms
        return put.mapNotNull { guess ->
            val pieces = written.getValue(guess.line)
            distinctWordStarts(pieces, inside, span, wordLines).singleOrNull()?.takeIf { it in span }?.let { ms ->
                Placed(guess.line, ms, guess.support, guess.words, guess.heardMs, pieces.first().written)
            }
        }
    }

    /** One of the line's longest words, unique among these lines and their anchors, heard exactly once. */
    private fun distinctWordStarts(pieces: List<Written>, heard: List<HeardPiece>, span: LongRange, wordLines: Map<String, Int>): List<Long> {
        val longest = pieces.maxOf { it.text.length }
        return heard.filter { it.ms in span }.flatMap { word ->
            pieces.filter { it.text == word.text && it.text.length > 1 && it.text.length == longest && wordLines.getValue(it.text) == 1 && matchScore(word.text, it) >= MIN_SUPPORT }
                .map { word.ms - it.before * LETTER_MS }
        }.distinct()
    }

    /**
     * Each place in [heard] a line's [pieces] are sung, as far as they can be told: where the line
     * starts there, and how sure. Local matches, so one line can be found where each chorus is.
     */
    private fun found(heard: List<HeardPiece>, pieces: List<Written>): List<Found> {
        val n = heard.size
        val m = pieces.size
        val best = Array(n + 1) { DoubleArray(m + 1) }
        val score = Array(n) { i -> DoubleArray(m) { j -> matchScore(heard[i].text, pieces[j]) } }
        for (i in 1..n) for (j in 1..m) {
            best[i][j] = maxOf(0.0, best[i - 1][j - 1] + score[i - 1][j - 1], best[i - 1][j] - SKIP_HEARD, best[i][j - 1] - SKIP_WRITTEN)
        }
        val ends = (1..n).flatMap { i -> (1..m).map { j -> i to j } }.filter { (i, j) -> best[i][j] >= MIN_SUPPORT }.sortedByDescending { (i, j) -> best[i][j] }
        val taken = mutableSetOf<List<Pair<Int, Int>>>()
        val out = mutableListOf<Found>()
        for ((endI, endJ) in ends) {
            val pairs = traced(best, score, endI, endJ)
            val alike = pairs.filter { (h, w) -> likeness(heard[h].text, pieces[w].text) >= ALIKE }
            // Followed back from the end, the pairs come last word first.
            for (from in 0..alike.size - MIN_WORDS) {
                val selected = alike.dropLast(from)
                if (!taken.add(selected) || !supported(selected, pieces)) continue
                val match = matched(heard, pieces, score, selected)
                if (match.score >= MIN_SUPPORT) out += match
            }
        }
        return out
    }

    /** The local match, last pair first. Keep its shorter matches too, so a neighbor can own its own words. */
    private fun traced(best: Array<DoubleArray>, score: Array<DoubleArray>, endI: Int, endJ: Int): List<Pair<Int, Int>> {
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
        return pairs
    }

    /** A fragment needs two words with more than one letter; an unspaced syllable is more than one letter sung. */
    private fun supported(pairs: List<Pair<Int, Int>>, pieces: List<Written>) =
        pairs.size == pieces.size || pairs.count { (_, w) -> SpokenWords.sungLetters(pieces[w].text) > 1 } >= MIN_WORDS

    /** The support and span of just these pairs, without charging the words a neighboring line consumes. */
    private fun matched(heard: List<HeardPiece>, pieces: List<Written>, score: Array<DoubleArray>, pairs: List<Pair<Int, Int>>): Found {
        val skipped = pairs.reversed().zipWithNext().sumOf { (a, b) ->
            (b.first - a.first - 1) * SKIP_HEARD + (b.second - a.second - 1) * SKIP_WRITTEN
        }
        val support = pairs.sumOf { (h, w) -> score[h][w] } - skipped
        val owned = pairs.map { (h, _) -> HeardAt(heard[h].wordMs, heard[h].within) }.toSet()
        val span = owned.min()..owned.max()
        return Found(startOf(pairs.map { (h, w) -> heard[h] to pieces[w] }), support, pairs.size, span, owned)
    }

    /**
     * Where a line starts, by the words heard in it, [alike] to its pieces: each word heard starts where in
     * the line its first letter is. The first says where the line starts, and with others after it, how fast
     * the letters before it go by; a line is sung as fast as a rap or held a while. With one, they go by a
     * letter each [LETTER_MS].
     */
    private fun startOf(alike: List<Pair<HeardPiece, Written>>): Long {
        // Each word heard once, by the first of its pieces found: how many letters into the line it starts, and when.
        // The speech model puts the first word of a while at the while's start, however late it's sung, so a line's
        // first word heard first says nothing of where the line starts, unless it's all that's heard of it.
        val timed = alike.filter { (heard, written) ->
            !heard.opening || written.before - heard.within > 0 || alike.any { (other, next) ->
                !other.opening && other.wordMs > heard.wordMs &&
                    other.wordMs - heard.wordMs <= (next.before - written.before) * LETTER_MS
            }
        }.ifEmpty { alike }
        val words = timed.groupBy { (heard, _) -> heard.wordMs }
            .map { (ms, its) -> its.minBy { (_, written) -> written.before }.let { (heard, written) -> written.before - heard.within to ms } }
            .sortedBy { it.first }
        val (firstAt, firstMs) = words.first()
        val (lastAt, lastMs) = words.last()
        val pace = if (lastAt > firstAt) ((lastMs - firstMs).toDouble() / (lastAt - firstAt)).coerceIn(MIN_PACE_MS, LETTER_MS.toDouble()) else LETTER_MS.toDouble()
        return words.minOf { (at, ms) -> ms - (at * pace).toLong() }
    }

    /**
     * The most [candidates] that can all be right at once: one place for each line at most, in order,
     * and each far enough after the last for the lines between to be sung, however fast.
     */
    private fun chained(sorted: List<Placed>, best: DoubleArray, from: IntArray): List<Placed> {
        if (sorted.isEmpty()) return emptyList()
        var k = best.indices.maxBy { best[it] } + 1
        val chain = mutableListOf<Placed>()
        while (k > 0) {
            chain += sorted[k - 1]
            k = from[k - 1]
        }
        return chain.reversed()
    }

    /** The pieces of [lines]' words, each with its line, the letters sung before it in its line and in all, and the letters written before it in all. */
    private fun written(lines: List<String>): List<Written> {
        var all = 0
        var allWritten = 0
        val pieces = lines.withIndex().flatMap { (line, text) ->
            var before = 0
            SpokenWords.pieces(text).map { piece ->
                Written(piece, line, before, all, allWritten).also {
                    before += SpokenWords.sungLetters(piece)
                    all += SpokenWords.sungLetters(piece)
                    allWritten += piece.length
                }
            }
        }
        // A word in many lines says little of which it is, though a line sung again says nothing against its words; one letter, as "a" or "の", even less.
        val counts = pieces.groupBy { it.text }.mapValues { (_, its) -> its.map { lines[it.line] }.distinct().size }
        pieces.forEach { it.weight = (if (it.text.length < 2) SHORT else 1.0) / kotlin.math.sqrt(counts.getValue(it.text).toDouble()) }
        return pieces
    }

    /** Pieces of the words sung, with where they start, less the sounds the model tells of, as "[MUSIC]" or "♪". */
    private fun sung(words: List<Pair<String, Long>>, fromMs: Long): List<HeardPiece> {
        var told = false
        return words.mapIndexed { index, (text, ms) ->
            val sound = told || text.startsWith('[') || text.startsWith('(')
            told = sound && !text.endsWith(']') && !text.endsWith(')')
            // A sound told of first, as "[Music]", takes the while's start, so the first word after it is heard where it's sung.
            if (sound) emptyList() else SpokenWords.heardFrom(text, ms - HEARD_LATE_MS, opening = index == 0 && ms - HEARD_LATE_MS <= fromMs + EDGE_MS)
        }.flatten()
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

    private class Written(val text: String, val line: Int, val before: Int, val letters: Int, val written: Int) {
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

    /**
     * How fast a letter may go by, as the words heard in a line say: a rap's. Never slower than [LETTER_MS], as a
     * word held or a rest makes the words after it look slow, and the earliest word heard then says where it starts.
     */
    private const val MIN_PACE_MS = 20.0

    /** Faster than anyone sings, a letter written at a time, as a Japanese or Korean one too: lines found closer together than that can't both be right. */
    private const val FAST_LETTER_MS = 60L

    /** How far before what was heard a line found in it may start: its first words may be just before. */
    private const val EDGE_MS = 1_000L

    /** About how late the speech model has a word start: a little after it's sung. */
    const val HEARD_LATE_MS = 550L

    /** About how long a line takes to sing, a letter written at a time, at an everyday pace, and a moment more it stays lit after. */
    private const val SUNG_LETTER_MS = 120L
    private const val LINGER_MS = 1_000L
}
