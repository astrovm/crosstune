package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A brace in a pattern is escaped unless it's part of `{2,3}`, `\p{L}` or a `[...]` set. Java's regex takes
 * a lone `}` as the character, and Android's takes it for a syntax error: the app crashes on the phone where
 * every test here passes. So the patterns are read from the app's code instead, built as the app builds them.
 */
class RegexBracesTest {
    @Test
    fun noPatternInTheAppHasALoneBrace() {
        val found = AppPatterns.ofApp().found.filter { loneBrace(it.pattern.pattern()) }.map { "${it.where} ${it.pattern.pattern().take(80)}" }
        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun aBraceIsFoundInAnyLiteralAPatternIsBuiltFrom() {
        // Joined with +, in a quoted string, and in a constant.
        val source = "private const val CLOSE = \"\"\"x}\"\"\"\nval a = Regex(\"\"\"a\"\"\" + \"\"\"b}\"\"\")\n" +
            "val b = Regex(\"c}\")\nval c = Regex(\"\"\"${'$'}CLOSE\"\"\")\nval d = Regex(\"\"\"d\\}\"\"\")\n"
        val found = AppPatterns(listOf("Test.kt" to source)).found.filter { loneBrace(it.pattern.pattern()) }.map { it.holds }
        assertEquals(listOf("a", "b", "c"), found)
    }

    @Test
    fun aLoneBraceIsToldFromOneWithAMeaning() {
        val fine = listOf("""a{2,3}b{4}c{5,}""", """\p{L}+\P{Mn}\{x\}""", """[^}]*[{}][]}]""", """(?<=${'$'}{VOWEL}x)""", """tt\s*\{([^}]*)\}""")
        val lone = listOf("""tt\s*\{([^}]*)}""", """a{b""", """a{2,3}}""", """[a]}""")
        assertEquals(emptyList<String>(), fine.filter(::loneBrace))
        assertEquals(lone, lone.filter(::loneBrace))
    }

    private val meant = Regex("""^(?:\{\d+(?:,\d*)?\}|\$\{\w+\})""")

    private fun loneBrace(pattern: String): Boolean {
        var at = 0
        var inSet = false
        while (at < pattern.length) {
            val char = pattern[at]
            val counted = meant.find(pattern.substring(at))
            when {
                // An escape, with the name it takes: \p{L}.
                char == '\\' -> at += if (pattern.startsWith("p{", at + 1) || pattern.startsWith("P{", at + 1)) pattern.indexOf('}', at) - at else 1
                inSet -> inSet = char != ']'
                char == '[' -> {
                    inSet = true
                    if (pattern.getOrNull(at + 1) == '^') at++
                    // A ] right after the opening is one of the set.
                    if (pattern.getOrNull(at + 1) == ']') at++
                }
                counted != null -> at += counted.value.length - 1
                char == '{' || char == '}' -> return true
            }
            at++
        }
        return false
    }
}
