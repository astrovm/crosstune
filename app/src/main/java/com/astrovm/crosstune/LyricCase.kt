package com.astrovm.crosstune

/** Capitals from another copy of the same words, without treating its line starts as names. */
internal class LyricCase(copies: List<String>) {
    private val cases = copies.filterNot(::inCapitals).flatMap { copy ->
        val words = word.findAll(copy).toList()
        words.mapIndexedNotNull { index, token ->
            val previous = words.getOrNull(index - 1) ?: return@mapIndexedNotNull null
            val gap = copy.substring(previous.range.last + 1, token.range.first)
            // A capital starting a line or sentence says nothing about whether this is a name.
            if (gap.any { it in "\n.!?:" }) null else context(words, index) to token.value
        }
    }.groupBy({ it.first }, { it.second }).mapValues { (_, spellings) -> spellings.distinct().singleOrNull() }

    /** Letter for letter, so even timings which split a word keep their places. */
    fun apply(text: String): String {
        val words = word.findAll(text).toList()
        var index = 0
        return word.replace(text) { token ->
            val spelling = cases[context(words, index++)]
            token.value.mapIndexed { at, letter ->
                if (spelling?.getOrNull(at)?.isUpperCase() == true) letter.uppercaseChar() else letter
            }.joinToString("")
        }
    }

    private fun context(words: List<MatchResult>, index: Int) = (index - 1..index + 1).joinToString("\u0000") { at ->
        words.getOrNull(at)?.value.orEmpty().map(Char::lowercaseChar).joinToString("").replace('’', '\'')
    }

    private companion object {
        val word = Regex("""[\p{L}\p{M}]+(?:['’][\p{L}\p{M}]+)*""")
    }
}

/** Whether [words] are in capitals, nearly every letter that has them, as some sites keep a whole song. */
internal fun inCapitals(words: String): Boolean {
    val cased = words.filter { it.isLetter() && it.lowercaseChar() != it.uppercaseChar() }
    return cased.length >= MIN_CASED_LETTERS && cased.count(Char::isUpperCase) >= cased.length * CAPITALS_SHARE
}

/** Letters with a case a song needs, and the share of them in capitals, to count as written in capitals. */
private const val MIN_CASED_LETTERS = 20
private const val CAPITALS_SHARE = 0.9

