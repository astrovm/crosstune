package com.astrovm.crosstune

/**
 * One line of a song's words, and when in the song it's sung; with when each of its [words] is,
 * when the words are timed that closely, and when it ends, when that's known. The words, put
 * together, are the line.
 */
internal data class LyricLine(val timeMs: Long, val text: String, val words: List<TimedWord> = emptyList(), val endMs: Long? = null)

/** A word of a line, or a syllable, as it's timed, and when in the song it's sung. */
internal data class TimedWord(val timeMs: Long, val text: String)

/** Timed words, as LRCLIB and most players write them: "[01:02.34] A line". */
internal object SyncedLyrics {
    /** A blank this long or longer is a pause worth showing. */
    private const val PAUSE_MS = 5_000L

    /** Faster than the fastest rap, by syllables or words. */
    private const val SOUNDS_PER_SECOND = 12

    /** Too short to tell: "Oh, yeah" can go by in a moment. */
    private const val MIN_SOUNDS = 3
    private const val RUSHED_LINES = 2
    private val syllable = Regex("[\\u3040-\\u30FF\\u4E00-\\u9FFF\\uAC00-\\uD7AF]")
    private val latinWord = Regex("[A-Za-z\\u00C0-\\u024F']+")

    private val stamp = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** A word's time in a line, as enhanced LRC writes it: "<01:02.34>". */
    private val wordStamp = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")

    /** A line as NetEase times it word by word: "[start,length]" and then "(start,length,0)" before each word. */
    private val netEaseLine = Regex("""^\[(\d+),(\d+)](.*)""")
    private val netEaseWord = Regex("""\((\d+),\d+,\d+\)""")

    /** "[offset:+500]": how many milliseconds sooner every line comes, or later when it's less than none. */
    private val offsetTag = Regex("""^\[offset:\s*([+-]?\d+)\s*]""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    /**
     * The timed lines in [lrc], in the order they're sung, moved as its offset tag says. A line sung
     * more than once carries a stamp for each time; tags such as "[ar: Artist]" and lines with no
     * stamp aren't words, and are left out.
     */
    fun parse(lrc: String): List<LyricLine> {
        val offset = offsetTag.find(lrc)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return lrc.lineSequence().flatMap { raw ->
            val stamps = generateSequence(stamp.matchAt(raw, 0)) { stamp.matchAt(raw, it.range.last + 1) }.toList()
            val rest = raw.substring(stamps.lastOrNull()?.range?.last?.plus(1) ?: return@flatMap emptySequence())
            // Enhanced LRC times each word too; a line sung more than once can't say which time they're for.
            val words = if (stamps.size == 1) timedWords(rest, wordStamp) { it.toMillis() - offset } else emptyList()
            val text = wordStamp.replace(rest, "").trim()
            stamps.asSequence().map { LyricLine((it.toMillis() - offset).coerceAtLeast(0), text, words) }
        }.sortedBy { it.timeMs }.toList()
    }

    /**
     * The lines NetEase times word by word, "[12340,2000](12340,500,0)Hello (12840,600,0)world", in
     * the order they're sung. The credits it opens with are written another way, and left out.
     */
    fun parseWordByWord(yrc: String): List<LyricLine> =
        yrc.lineSequence().mapNotNull { raw ->
            val (start, length, rest) = netEaseLine.matchEntire(raw.trim())?.destructured ?: return@mapNotNull null
            val words = timedWords(rest, netEaseWord) { it.groupValues[1].toLong() }
            LyricLine(start.toLong(), words.joinToString("") { it.text }.trim(), words, (start.toLong() + length.toLong()).takeIf { length.toLong() > 0 })
        }.filter { it.text.isNotEmpty() }.sortedBy { it.timeMs }.toList()

    /**
     * The words in [text], each after the stamp that times it, found by [stamps] and read by [timeOf]; none
     * unless every word has one. Spaces stay with the word before them, so the words together are the line.
     */
    private fun timedWords(text: String, stamps: Regex, timeOf: (MatchResult) -> Long): List<TimedWord> {
        val found = stamps.findAll(text).toList()
        if (found.isEmpty() || text.substring(0, found.first().range.first).isNotBlank()) return emptyList()
        val words = mutableListOf<TimedWord>()
        found.forEachIndexed { index, match ->
            val piece = text.substring(match.range.last + 1, found.getOrNull(index + 1)?.range?.first ?: text.length)
            // Only a space, it goes with the word before; a last stamp with nothing after only says when the line ends.
            if (piece.isNotBlank()) words += TimedWord(timeOf(match).coerceAtLeast(0), piece)
            else if (words.isNotEmpty()) words[words.lastIndex] = words.last().let { it.copy(text = it.text + piece) }
        }
        if (words.isEmpty()) return emptyList()
        words[0] = words[0].let { it.copy(text = it.text.trimStart()) }
        words[words.lastIndex] = words.last().let { it.copy(text = it.text.trimEnd()) }
        return words
    }

    /** How many of a line's [words] have been sung by [positionMs]. */
    fun wordsSungBy(words: List<TimedWord>, positionMs: Long): Int = words.count { it.timeMs <= positionMs }

    /**
     * [lines] without the blank ones that only end a line, which most timed words have after each:
     * a blank stays only where nothing is sung for a while, e.g. a solo, and only once.
     */
    fun withPauses(lines: List<LyricLine>): List<LyricLine> =
        lines.filterIndexed { index, line ->
            if (line.text.isNotBlank()) return@filterIndexed true
            val next = lines.getOrNull(index + 1) ?: return@filterIndexed false
            next.text.isNotBlank() && next.timeMs - line.timeMs >= PAUSE_MS
        }

    /**
     * Whether [lines] are timed badly: lines that go by faster than anyone sings them, more than once.
     * One such line can be a slip; more mean they were timed by guesswork, or for another take.
     */
    fun rushed(lines: List<LyricLine>): Boolean =
        lines.zipWithNext().count { (line, next) ->
            val sounds = soundsIn(line.text)
            sounds >= MIN_SOUNDS && (next.timeMs - line.timeMs) * SOUNDS_PER_SECOND < sounds * 1000L
        } >= RUSHED_LINES

    /**
     * About how many sounds a line has, to sing it: a word for each in Latin letters, and a syllable
     * for each Japanese kana, Chinese character or Korean block.
     */
    private fun soundsIn(text: String): Int = syllable.findAll(text).count() + latinWord.findAll(text).count()

    /** The line being sung at [positionMs], or -1 before the first. */
    fun indexAt(lines: List<LyricLine>, positionMs: Long): Int = lines.indexOfLast { it.timeMs <= positionMs }

    /** The fraction is decimals of a second: ".5" is half of one, ".05" a twentieth. */
    private fun MatchResult.toMillis(): Long {
        val (minutes, seconds, fraction) = destructured
        val millis = fraction.padEnd(3, '0').take(3).ifEmpty { "0" }.toLong()
        return (minutes.toLong() * 60 + seconds.toLong()) * 1000 + millis
    }
}
