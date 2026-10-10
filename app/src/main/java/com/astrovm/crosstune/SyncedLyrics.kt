package com.astrovm.crosstune

/** One line of a song's words, and when in the song it's sung. */
internal data class LyricLine(val timeMs: Long, val text: String)

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

    /**
     * The timed lines in [lrc], in the order they're sung. A line sung more than once carries a stamp
     * for each time; tags such as "[ar: Artist]" and lines with no stamp aren't words, and are left out.
     */
    fun parse(lrc: String): List<LyricLine> =
        lrc.lineSequence().flatMap { raw ->
            val stamps = generateSequence(stamp.matchAt(raw, 0)) { stamp.matchAt(raw, it.range.last + 1) }.toList()
            val text = raw.substring(stamps.lastOrNull()?.range?.last?.plus(1) ?: return@flatMap emptySequence()).trim()
            stamps.asSequence().map { LyricLine(it.toMillis(), text) }
        }.sortedBy { it.timeMs }.toList()

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
