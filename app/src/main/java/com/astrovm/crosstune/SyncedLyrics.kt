package com.astrovm.crosstune

/** One line of a song's words, and when in the song it's sung. */
internal data class LyricLine(val timeMs: Long, val text: String)

/** Timed words, as LRCLIB and most players write them: "[01:02.34] A line". */
internal object SyncedLyrics {
    /** A blank this long or longer is a pause worth showing. */
    private const val PAUSE_MS = 5_000L

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

    /** The line being sung at [positionMs], or -1 before the first. */
    fun indexAt(lines: List<LyricLine>, positionMs: Long): Int = lines.indexOfLast { it.timeMs <= positionMs }

    /** The fraction is decimals of a second: ".5" is half of one, ".05" a twentieth. */
    private fun MatchResult.toMillis(): Long {
        val (minutes, seconds, fraction) = destructured
        val millis = fraction.padEnd(3, '0').take(3).ifEmpty { "0" }.toLong()
        return (minutes.toLong() * 60 + seconds.toLong()) * 1000 + millis
    }
}
