package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A pattern with `\G` in it, as [SPACES_START] has, is searched from the start of the text only: `matches`,
 * `matchEntire`, `containsMatchIn`, `find(text)`, `split`, `replace(text, "…")` and `matchesIn(text)`, which looks on
 * from where each match ended as `split` does. Kotlin's `findAll`,
 * `find(text, start)`, `replace(text) { … }` and a match's `next()` search again with `find(start)`, and there
 * Android's ICU reads `\G` apart from the JVM: in "a / /b", from 4, the JVM finds " /" and Android nothing. The
 * tests run on the JVM, so such a call passes here and goes wrong on the phone. So each use is read from the code.
 */
class SpacesStartTest {
    @Test
    fun aPatternThatStartsAtSpacesIsSearchedFromTheStartOnly() {
        assertEquals(emptyList<String>(), searchedAgain(AppPatterns.ofApp()))
    }

    @Test
    fun aSearchFromWhereTheLastMatchEndedIsToldFromOneFromTheStart() {
        val source = """
            private val gap = Regex(${"\"\"\""}\G\s*+/${"\"\"\""})
            private val other = Regex(${"\"\"\""}/${"\"\"\""})
            /** Not [gap].findAll, in words. */
            fun fine(text: String) = gap.matches(text) || gap.find(text.trim()) != null || gap.containsMatchIn(text) ||
                text.replace(gap, " ").isEmpty() || gap.replace(text, "-").isEmpty() || text.split(gap).isEmpty() ||
                listOf(text).any(gap::containsMatchIn) || other.findAll(text).any() || gap.matchesIn(text).iterator().hasNext()
            fun findAll(text: String) = gap.findAll(text)
            fun fromStart(text: String) = gap.find(text, 2)
            fun inBlock(text: String) = gap.replace(text) { "-" }
            fun onString(text: String) = text.replace(gap) { it.value.trim() }
            fun passed(text: String) = text.replace(gap, transform = { "-" })
            fun next(text: String) = gap.find(text)?.next()
            fun handed(text: String) = listOf(gap)
            fun held(text: String, fixer: (MatchResult) -> CharSequence) = gap.replace(text, fixer)
            fun said() = "gap.findAll(text)" + ${"\"\"\""}gap.find(text, 2)${"\"\"\""} + '"'
            fun fromAfter(text: String) = gap.matchesIn(text, 2)
        """.trimIndent()
        val found = searchedAgain(AppPatterns(listOf("Test.kt" to source)))
        assertEquals(listOf(7, 8, 9, 10, 11, 12, 13, 14, 16), found.map { it.substringAfter("Test.kt:").substringBefore(' ').toInt() })
    }

    /** Each use of a pattern with `\G` that isn't a search from the start, by where it is, or a pattern no value holds. */
    private fun searchedAgain(app: AppPatterns): List<String> = app.found.filter { "\\G" in it.pattern.pattern() }.flatMap { found ->
        val name = found.holds ?: return@flatMap listOf("${found.where} is held by no value, so its uses can't be read")
        val file = found.where.substringBefore(':')
        // A private value is used in its own file only, and another file may have something else of that name.
        val own = app.files.filter { it.first == file }
        val sources = if (own.any { (_, source) -> Regex("""private (?:val|var) $name\b""").containsMatchIn(source) }) own else app.files
        sources.flatMap { (other, source) ->
            val code = withoutStrings(withoutComments(source))
            Regex("""\b$name\b""").findAll(code).filterNot { use -> fromTheStart(code, use.range.first, use.range.last + 1) }
                .map { use -> "$other:${code.take(use.range.first).count { it == '\n' } + 1} ${code.substring(use.range.first).lineSequence().first().trim()}" }
                .toList()
        }
    }

    /** Whether the use at [start] until [end] is the value's own declaration, or a search from the start of the text. */
    private fun fromTheStart(code: String, start: Int, end: Int): Boolean {
        val before = code.substring(0, start)
        val after = code.substring(end)
        val handedTo = argumentTo.find(before)
        return when {
            declared.containsMatchIn(before) -> true
            reference.matchesAt(after, 0) -> true
            after.startsWith(".find(") -> args(after, ".find(".length).let { (given, rest) -> given.size == 1 && !next.matchesAt(rest, 0) }
            after.startsWith(".matchesIn(") -> args(after, ".matchesIn(".length).first.size == 1
            after.startsWith(".replace(") -> args(after, ".replace(".length).let { (given, rest) -> given.size == 2 && plain(given[1], rest) }
            plainCall.matchesAt(after, 0) -> true
            // Handed to a String's replace or split, as the first of what it's given.
            handedTo != null -> args(code, handedTo.range.last + 1).let { (given, rest) ->
                handedTo.groupValues[1] != "replace" || given.size == 2 && plain(given[1], rest)
            }
            else -> false
        }
    }

    /** What's given to a call that opens right before [from], split at its commas, and the code after it closes. */
    private fun args(code: String, from: Int): Pair<List<String>, String> {
        val given = mutableListOf(StringBuilder())
        var depth = 0
        var quoted = false
        var at = from
        while (at < code.length) {
            val char = code[at]
            if (!quoted && depth == 0 && char in ")]}") return given.map { it.trim().toString() } to code.substring(at + 1)
            if (!quoted && depth == 0 && char == ',') given += StringBuilder() else given.last().append(char)
            when {
                // An escaped letter in a string, a quote too, is the string's.
                quoted && char == '\\' -> given.last().append(code.getOrElse(at + 1) { ' ' }).also { at++ }
                char == '"' -> quoted = !quoted
                quoted -> Unit
                char in "([{" -> depth++
                char in ")]}" -> depth--
            }
            at++
        }
        return given.map { it.trim().toString() } to ""
    }

    /**
     * A replacement written as text in the call, and no block after it. A value may hold a function as well as
     * text, and a String's replace takes either, so a replacement that isn't written out is read as a function.
     */
    private fun plain(replacement: String, rest: String) = replacement.startsWith("\"") && !block.matchesAt(rest, 0)

    /** The code with what's in each string put as spaces, as a name in one, "a featuring guest", is no use of it. */
    private fun withoutStrings(code: String) = literal.replace(code) { found ->
        val quotes = if (found.value.startsWith("\"\"\"")) 3 else 1
        val text = found.value
        if (text.startsWith("'")) text else text.take(quotes) + text.substring(quotes, text.length - quotes).replace(notALineBreak, " ") + text.takeLast(quotes)
    }

    private fun withoutComments(source: String) = source.lines().joinToString("\n") { line ->
        if (line.trimStart().startsWith("*") || line.trimStart().startsWith("/*") || line.trimStart().startsWith("//")) "" else line
    }

    private companion object {
        val declared = Regex("""\b(?:val|var)\s+$""")
        val reference = Regex("""::(?:matches|matchEntire|containsMatchIn)\b""")
        val plainCall = Regex("""\.(?:matches|matchEntire|containsMatchIn|split)\(""")
        val next = Regex("""\s*\??\.\s*next\(""")
        val block = Regex("""\s*\{""")
        val argumentTo = Regex("""\.(replace|split)\(\s*$""")
        /** A letter in quotes, which may be a quote, or a raw string, or a quoted one. */
        val literal = Regex("""'(?:\\.|[^'\\])'|""" + "\"\"\"(?:[^\"]|\"(?!\"\"))*+\"*\"\"\"" + """|"(?:[^"\\\n]|\\.)*+${'"'}""")
        val notALineBreak = Regex("""[^\n]""")
    }
}
