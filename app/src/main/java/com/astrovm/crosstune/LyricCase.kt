package com.astrovm.crosstune

/** Capitals from other copies of the same words, only where a capital there can mean a name. */
internal class LyricCase(copies: List<String>) {
    /** Each copy's words, and which of them can say something about being a name. */
    private val inside = copies.flatMap { copy ->
        val words = word.findAll(copy).toList()
        val inside = words.indices.filter { index -> index > 0 && insideASentence(copy.substring(words[index - 1].range.last + 1, words[index].range.first)) }
        // A capital on most words, as a copy in capitals or in title case has, says nothing about any of them.
        if (inside.count { words[it].value.first().isUpperCase() } > inside.size * TITLE_CASE_SHARE) emptyList()
        else inside.map { words to it }
    }

    private val cases = spellings(::context)
    private val between = spellings(::neighbors)

    private fun spellings(key: (List<MatchResult>, Int) -> String) =
        inside.groupBy({ (words, index) -> key(words, index) }, { (words, index) -> words[index].value })
            .mapValues { (_, spellings) -> spellings.distinct().singleOrNull() }

    /** Letter for letter, so even timings which split a word keep their places. */
    fun apply(text: String): String {
        val words = word.findAll(text).toList()
        var index = 0
        return word.replace(text) { token ->
            val here = index++
            val key = context(words, here)
            val spelling = if (key in cases) cases[key] else respelled(token.value, between[neighbors(words, here)])
            token.value.mapIndexed { at, letter ->
                spelling?.getOrNull(at)?.takeIf(Char::isUpperCase) ?: letter
            }.joinToString("")
        }
    }

    /** A name another copy, between the same words, spells a little otherwise: only its first letter says anything here. */
    private fun respelled(word: String, other: String?) = other?.takeIf { sameName(word, it) }?.take(1)

    private fun sameName(one: String, other: String): Boolean {
        val a = plain(one)
        val b = plain(other)
        return a.first() == b.first() && lettersApart(a, b) <= maxOf(a.length, b.length) / LETTERS_PER_MISSPELLING
    }

    /** How many letters to change, add or take away to make [a] into [b]. */
    private fun lettersApart(a: String, b: String): Int {
        var row = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val next = IntArray(b.length + 1)
            next[0] = i + 1
            for (j in b.indices) next[j + 1] = minOf(row[j + 1] + 1, next[j] + 1, row[j] + if (a[i] == b[j]) 0 else 1)
            row = next
        }
        return row[b.length]
    }

    /** [lines] together, so a word's neighbors can be on the next line, each one as long as it was. */
    fun apply(lines: List<String>): List<String> {
        val cased = apply(lines.joinToString("\n"))
        var at = 0
        return lines.map { line -> cased.substring(at, at + line.length).also { at += line.length + 1 } }
    }

    private fun context(words: List<MatchResult>, index: Int) = neighbors(words, index) + "\u0000" + plain(words[index].value)

    private fun neighbors(words: List<MatchResult>, index: Int) =
        plain(words.getOrNull(index - 1)?.value.orEmpty()) + "\u0000" + plain(words.getOrNull(index + 1)?.value.orEmpty())

    private fun plain(word: String) = word.map(Char::lowercaseChar).joinToString("").replace('’', '\'')

    /**
     * Whether what's between two words leaves the second inside a sentence: anything else, a line
     * break, a full stop, an opening bracket or quote, can start one, and then its capital says nothing.
     */
    private fun insideASentence(gap: String) = gap == "-" || gap.all { it == ',' || (it.isWhitespace() && it !in "\r\n") }

    private companion object {
        val word = Regex("""[\p{L}\p{M}]+(?:['’][\p{L}\p{M}]+)*""")

        /** More of a copy's words than this with a capital is title case. German's nouns stay well under it. */
        const val TITLE_CASE_SHARE = 0.4

        /** How long a word is for each letter another copy may spell otherwise and it still be the same name. */
        const val LETTERS_PER_MISSPELLING = 4
    }
}
