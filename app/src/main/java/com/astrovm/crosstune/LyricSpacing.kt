package com.astrovm.crosstune

/** Missing spaces supported by the same line in another sheet, without changing a single letter. */
internal class LyricSpacing(copies: List<String>) {
    private val lines = copies.flatMap { it.lines() }.groupBy(::compact)

    /** Each part keeps its letters and its timing, including a part ending halfway through a word. */
    fun apply(parts: List<String>, line: String = parts.joinToString("")): List<String> {
        val text = parts.joinToString("")
        val existing = breaks(line)
        val additions = if (compact(text) == compact(line)) lines[compact(line)].orEmpty().map(::breaks)
            .filter { it.size > existing.size && it.containsAll(existing) }.distinct().singleOrNull().orEmpty() - existing else emptySet()
        var at = 0
        var previous: Char? = null
        return parts.map { part ->
            buildString {
                for (letter in part) {
                    if (!letter.isWhitespace() && at in additions && previous?.isWhitespace() == false) append(' ')
                    append(letter)
                    if (!letter.isWhitespace()) at++
                    previous = letter
                }
            }
        }
    }

    fun apply(text: String) = text.lines().joinToString("\n") { apply(listOf(it)).single() }

    private fun compact(text: String) = text.filterNot(Char::isWhitespace).map(Char::lowercaseChar).joinToString("")

    /** A space's position among the letters, so existing tabs and nonbreaking spaces count too. */
    private fun breaks(text: String): Set<Int> {
        var at = 0
        return text.trim().mapNotNull { letter -> if (letter.isWhitespace()) at else { at++; null } }.toSet()
    }
}
