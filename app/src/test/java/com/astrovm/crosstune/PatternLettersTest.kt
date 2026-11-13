package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test

/** The letters and places that Patterns.kt reads in code are those a pattern reads, for each letter there is. */
class PatternLettersTest {
    @Test
    fun eachLetterIsReadAsAPatternReadsIt() {
        val space = Regex("""\s""")
        val latin = Regex("[a-z]", RegexOption.IGNORE_CASE)
        val notALineBreak = Regex(".")
        for (code in Char.MIN_VALUE.code..Char.MAX_VALUE.code) {
            val char = code.toChar()
            val text = char.toString()
            assertEquals("U+%04X".format(code), space.matches(text), isPatternSpace(char))
            assertEquals("U+%04X".format(code), latin.matches(text), isLatinLetterIgnoringCase(char))
            assertEquals("U+%04X".format(code), !notALineBreak.matches(text), isLineBreak(char))
        }
    }

    @Test
    fun aLineEndsWhereAPatternsDollarFirstMatches() {
        val dollar = Regex("$")
        for (text in listOf("", "a", "a\n", "a\r\n", "a\n\r", "a\r", "a\u0085", "a\u2028", "a\u2029", "\n", "\r\n", "a\nb", "ab\n\n")) {
            for (from in 0..text.length) assertEquals("$text from $from", dollar.find(text, from)?.range?.first, lineEnd(text, from))
        }
    }

    @Test
    fun aRunOfSpacesIsReadToItsEndsAsAPatternReadsIt() {
        val spaces = Regex("""\s*""")
        val spacesAtTheEnd = Regex("""\s*$""")
        val between = Regex("""\s.\s""", RegexOption.DOT_MATCHES_ALL)
        for (text in listOf("", " ", "a \t\nb  ", "\u000B\u000Cx\u3000 \r", "-", " - ", "a- b", "a -b")) {
            for (at in 0..text.length) {
                assertEquals("$text at $at", at + spaces.matchAt(text, at)?.value.orEmpty().length, spacesEnd(text, at))
                for (from in 0..at) assertEquals("$text at $at from $from", at - spacesAtTheEnd.find(text.substring(from, at))?.value.orEmpty().length, spacesStart(text, at, from))
                assertEquals("$text at $at", at > 0 && between.matchAt(text, at - 1) != null, isBetweenSpaces(text, at))
            }
            assertEquals(text, false, isBetweenSpaces(text, -1))
        }
    }
}
