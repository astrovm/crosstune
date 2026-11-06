package com.astrovm.crosstune

/** Missing spaces agreed on by matching lines, without changing a letter; a tie keeps this copy. */
internal class LyricSpacing(copies: List<String>) {
    private val lines = copies.distinct().flatMap { it.lines() }.groupBy(::compact)

    /** Each part keeps its letters and its timing, including a part ending halfway through a word. */
    fun apply(parts: List<String>, line: String = parts.joinToString("")): List<String> {
        val text = parts.joinToString("")
        val existing = breaks(line)
        val votes = lines[compact(line)].orEmpty().map(::breaks)
        val agreed = votes.groupingBy { it }.eachCount().filterValues { it * 2 > votes.size }.keys.singleOrNull().orEmpty()
        val additions = if (compact(text) == compact(line) && agreed.size > existing.size && agreed.containsAll(existing)) agreed - existing else emptySet()
        var at = 0
        var previous: Char? = null
        return parts.map { part ->
            buildString {
                for (letter in part) {
                    if (letter.isLetter() && at in additions && previous?.isLetter() == true) append(' ')
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
