package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * On a phone each new look with a pattern copies the whole text first. [Regex.findAll], and [Regex.replace] with a
 * function, start a new look for each match: time squared on a long page with many matches. [matchesIn] and
 * [replaceEach] find the same matches by one look, and the app uses only them.
 */
class OneLookTest {
    @Test
    fun eachMatchIsFoundAsFindAllFindsIt() {
        for (pattern in PATTERNS) {
            val regex = Regex(pattern)
            texts().forEach { text ->
                for (start in listOf(0, text.length / 2, text.length)) {
                    val found = regex.matchesIn(text, start).toList()
                    val all = regex.findAll(text, start).toList()
                    assertEquals("$pattern in $text from $start", all.map(::seen), found.map(::seen))
                    // The next of each, by a look of its own, as findAll's.
                    assertEquals("$pattern in $text from $start", all.map { it.next()?.let(::seen) }, found.map { it.next()?.let(::seen) })
                }
            }
        }
    }

    @Test
    fun eachMatchIsPutAsReplacePutsIt() {
        for (pattern in PATTERNS) {
            val regex = Regex(pattern)
            texts().forEach { text ->
                val transform = { match: MatchResult -> "<${match.value}|${match.groupValues.drop(1).joinToString(",")}>" }
                assertEquals("$pattern in $text", regex.replace(text, transform), regex.replaceEach(text, transform))
            }
        }
    }

    @Test
    fun theAppStartsNoLookForEachMatch() {
        val again = File("src/main/java").walk().filter { it.extension == "kt" && it.name != "Patterns.kt" }.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line -> "${file.name}:${index + 1}: ${line.trim()}".takeIf { LOOK_AGAIN.containsMatchIn(line) } }
        }.toList()
        assertEquals(emptyList<String>(), again)
        // What's looked for is what that would be.
        assertEquals(listOf(true, true, true, false, false), listOf("words.findAll(text)", "regex.replace(text) { it.value }", "text.replace(phoneRegex(\"a(b)\")) {", "text.replace(\"a\", \"b\")", "text.replace(regex, f(\"\"))").map(LOOK_AGAIN::containsMatchIn))
    }

    /** All a match says: its range, its value, each group and group value, and what it destructures to. */
    private fun seen(match: MatchResult): List<Any?> =
        listOf(match.range, match.value, match.groups.toList(), match.groups.size, match.groupValues, match.destructured.toList(), match.groups[0])

    /** [COUNT] texts of up to [MAX_PIECES] of [PIECES], the same each run. */
    private fun texts(): Sequence<String> {
        val random = Random(SEED)
        return generateSequence { buildString { repeat(random.nextInt(0, MAX_PIECES)) { append(PIECES[random.nextInt(PIECES.size)]) } } }.take(COUNT)
    }

    private companion object {
        const val SEED = 933
        const val COUNT = 300
        const val MAX_PIECES = 8

        /** Patterns that match with and without letters, groups that match and don't, and look behind and ahead. */
        val PATTERNS = listOf("a", "a*", "(a)|(b)", "(a)(b)?", """\b""", "(?<=x)y?", "x(?=a)", "", "[^a]+", """\s*""")
        val PIECES = listOf("a", "b", "x", "y", " ", "ab", "\uD835\uDC00", "\n")

        /** A `findAll`, or a `replace` of a pattern with a function. */
        val LOOK_AGAIN = Regex("""\.findAll\(|\.replace\((?:[^()]|\((?:[^()]|\([^()]*\))*\))*\)\s*\{""")
    }
}
