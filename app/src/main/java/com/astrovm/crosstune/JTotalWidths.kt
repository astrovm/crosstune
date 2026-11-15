package com.astrovm.crosstune

/**
 * How wide a letter is on J-Total's page, in thousandths of a column (a column is the width of a
 * digit), as a Japanese reader's browser on Windows draws it. The page sets a sheet in Inconsolata,
 * then MS Gothic, then whatever the browser finds: a letter in neither is drawn from a proportional
 * font, so ۰ is 1144, not a whole number of columns. The widths are measured on Windows with the
 * page's own CSS and Windows' own fonts, see `scripts/jtotal-widths`, and kept in `jtotal-widths.txt`
 * and `jtotal-widths-mono.txt`, one for each of the page's font stacks. Each lists every letter from
 * U+0080 up that is not two columns: that is what the rest are.
 */
internal object JTotalWidths {
    const val COLUMN = 1000

    /** The letters under this are the ASCII Inconsolata draws, a column each. The file starts above them. */
    private const val LISTED_FROM = 0x80

    private const val RESOURCE = "/com/astrovm/crosstune/jtotal-widths.txt"

    /** One run of the file: the first and last code point, and their width. */
    private class Runs(val first: IntArray, val last: IntArray, val width: IntArray)

    // The file is read the first time a sheet needs a width, not when the app starts. It is looked up
    // by its whole path: the build may move the class to another package, and not the file.
    private val runs: Runs by lazy { readRuns(RESOURCE) }
    private val monospaceRuns: Runs by lazy { readRuns("/com/astrovm/crosstune/jtotal-widths-mono.txt") }

    private fun readRuns(resource: String): Runs {
        val lines = checkNotNull(JTotalWidths::class.java.getResourceAsStream(resource)).bufferedReader().use {
            it.readLines().filterNot { line -> line.startsWith('#') }
        }
        val ranges = lines.map { it.substringBefore(' ') }
        return Runs(
            first = ranges.map { it.substringBefore('-').toInt(16) }.toIntArray(),
            last = ranges.map { it.substringAfter('-', it).toInt(16) }.toIntArray(),
            width = lines.map { it.substringAfter(' ').toInt() }.toIntArray(),
        )
    }

    /** The width of [codePoint] in thousandths of a column. */
    fun of(codePoint: Int, monospace: Boolean = false): Int {
        if (codePoint < LISTED_FROM) return COLUMN
        val runs = if (monospace) monospaceRuns else runs
        val found = runs.first.binarySearch(codePoint)
        // Not the start of a run: the run before it, if it reaches this far.
        val run = if (found >= 0) found else -found - 2
        return if (run >= 0 && codePoint <= runs.last[run]) runs.width[run] else 2 * COLUMN
    }
}
