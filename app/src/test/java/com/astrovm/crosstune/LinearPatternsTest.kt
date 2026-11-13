package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.regex.Pattern

/**
 * Every pattern in the app takes time in step with the text it reads. A page can be long and made of
 * anything, and a pattern that looks to the end of the text from each place it tries takes time squared:
 * 20,000 `<` with no `>` take seconds where they should take a millisecond. So each pattern in the app's
 * code is timed on long text of the kinds that do that, and on the same text twice as long: [Growth].
 */
class LinearPatternsTest {
    @Test
    fun noPatternInTheAppTakesTimeSquaredOnLongText() {
        val slow = AppPatterns.ofApp().found.mapNotNull { (where, _, pattern) ->
            outOfStep(pattern)?.let { "$where $it ${pattern.pattern().take(80)}" }
        }
        assertEquals(emptyList<String>(), slow)
    }

    @Test
    fun everyPatternInTheAppIsRead() {
        val app = AppPatterns.ofApp()
        // As many as the app has, give or take: fewer means the reading lost some.
        assertEquals(true, app.found.size > 200)
        val unread = app.unread
        val explained = unread.filterNot { found -> builtAtRunTime.any { (file, start) -> found.startsWith("$file:") && start in found } }
        assertEquals(emptyList<String>(), explained)
        // Each one listed is still in the app: a pattern that's gone comes off the list.
        assertEquals(builtAtRunTime.size, unread.size)
    }

    /**
     * The patterns built from a value at run time, which can't be read from the code, by file and how they start,
     * with why each takes no more time than its text.
     */
    private val builtAtRunTime = listOf(
        // One word of a short list, between lookarounds of one letter each.
        "Translator.kt" to "\$spoken",
        // A Spotify page's wording, before and after the artist, as plain letters: looked for ahead from each place, and
        // matched at one place.
        "MetadataParsers.kt" to "phoneRegex(\"(?=\${Regex.escape(wording.substringBefore",
        "MetadataParsers.kt" to "phoneRegex(Regex.escape(wording.substringAfter",
        // Where each pattern is built, from what the line above it gives it, once in its name and once where it's called.
        "PhoneRegex.kt" to "phoneRegex(pattern, options.toSet(), ICU)",
        "PhoneRegex.kt" to "phoneRegex(pattern, options.toSet(), ICU)",
        "PhoneRegex.kt" to "fun phoneRegex(pattern: String, options: Set<RegexOption>, onIcu: Boolean)",
        // The pattern as it is on a phone, and written out for the JVM: each a pattern above, so timed there.
        "PhoneRegex.kt" to "Regex(JVM_WRITER.written(pattern, options))",
        "PhoneRegex.kt" to "Regex(JVM_WRITER.written(pattern, options))",
    )

    @Test
    fun aPatternThatLooksToTheEndFromEachPlaceIsOutOfStepAndOneThatDoesNotIsInStep() {
        // Shorter than the app's patterns are read at, as one that's out of step takes seconds there, read again and again.
        val opens = { length: Int -> listOf("<".repeat(length)) }
        assertNotNull(outOfStep(Pattern.compile("<[^>]*>"), SHORT, opens))
        assertNull(outOfStep(Pattern.compile("<[^<>]*+>"), SHORT, opens))
    }

    @Test
    fun aPatternThatRunsOutOfStackIsNamedAndNotTheEndOfTheTest() {
        val run = { length: Int -> listOf("a".repeat(length)) }
        assertEquals("overflows the stack", outOfStep(Pattern.compile("(?:a|b)*c"), texts = run))
        // The same, with no group to repeat and tried only where a run starts.
        assertNull(outOfStep(Pattern.compile("(?<![ab])[ab]*+c"), texts = run))
    }

    @Test
    fun templatesAndJoinedLiteralsAreReadAsTheAppBuildsThem() {
        val source = "private const val TAG = \"\"\"<b>\"\"\"\nprivate const val END = \"</b>\"\n" +
            "val a = Regex(\"\"\"${'$'}TAG(x)\"\"\" + \"\"\"${'$'}{END}\\s\"\"\", RegexOption.IGNORE_CASE)\n" +
            "val b = Regex(\"a\\\\d\\${'$'}\")\nval c = Regex(\"\"\"${'$'}{'${'$'}'}\"\"\")\n" +
            "val d = Regex(\"\"\"\"q\":\"(\\w)\"\"\"\").find(x)\nval e = Regex(\"\"\"^(.+)\"\"\" + words + \"\"\"${'$'}\"\"\")\n" +
            "val f = words.joinToString(\"|\").toRegex()\nval g = Regex(words.first(), RegexOption.IGNORE_CASE)\n" +
            // Patterns named in comments, as what main read: none is the app's, read or not.
            "/**\n * as main's `text.replace(Regex(\"<[^>]+>\"), with)` did, or Regex(words)\n */\n// Regex(\"a*b\") and Regex(x)\n/* Regex(\"c\") */\n"
        val read = AppPatterns(listOf("Test.kt" to source))
        assertEquals(listOf("<b>(x)</b>\\s", "a\\d$", "$", "\"q\":\"(\\w)\""), read.found.map { it.pattern.pattern() })
        assertEquals(listOf(true, false, false, false), read.found.map { it.pattern.flags() and Pattern.CASE_INSENSITIVE != 0 })
        assertEquals(listOf("a", "b", "c", "d"), read.found.map { it.holds })
        assertEquals(
            listOf("Test.kt:7 val e = Regex(\"\"\"^(.+)\"\"\" + words + \"\"\"${'$'}\"\"\")", "Test.kt:8 val f = words.joinToString(\"|\").toRegex()", "Test.kt:9 val g = Regex(words.first(), RegexOption.IGNORE_CASE)"),
            read.unread.sorted()
        )
    }

    /** Text that makes a pattern try each place and look on to the end: runs of one thing, and of how the pattern starts. */
    private fun hostileTexts(pattern: String, length: Int): List<String> {
        val start = plainStart(pattern)
        val starts = if (start.isEmpty()) emptyList() else listOf(start.repeat(length / start.length + 1), start + "x".repeat(length), start + " ".repeat(length))
        // Each run on its own, and then something that isn't a space nor ends the text, so a pattern reads on past it.
        return RUNS.flatMap { listOf(it.repeat(length / it.length), it.repeat(length / it.length) + ")") } + starts
    }

    /**
     * The text a pattern starts with: its plain letters, a space for `\s*` or `\s+`, a set's first letter, and
     * nothing for `\b`, a `[set]*` or a look behind. Repeated, it makes the pattern start again and again.
     */
    private fun plainStart(pattern: String): String {
        val plain = StringBuilder()
        var at = 0
        fun skipQuantifier() {
            while (at < pattern.length && pattern[at] in "*+?") at++
        }
        while (at < pattern.length) {
            val char = pattern[at]
            val next = pattern.getOrNull(at + 1)
            val setEnd = if (char == '[') pattern.indexOf(']', at + 2) else -1
            when {
                pattern.startsWith(SPACES_START, at) -> at += SPACES_START.length
                pattern.startsWith("(?<!", at) || pattern.startsWith("(?<=", at) -> at = pattern.indexOf(')', at) + 1
                char == '\\' && next != null && next in ESCAPED_PLAIN -> {
                    plain.append(next)
                    at += 2
                }
                char == '\\' && next == 'b' -> at += 2
                char == '\\' && next == 's' && pattern.getOrNull(at + 2)?.let { it in "*+" } == true -> {
                    plain.append(' ')
                    at += 2
                    skipQuantifier()
                }
                setEnd > 0 && pattern.getOrNull(setEnd + 1) == '*' -> {
                    at = setEnd + 1
                    skipQuantifier()
                }
                setEnd > 0 && next != null && next != '^' && next != '\\' -> {
                    plain.append(next)
                    at = setEnd + 1
                    skipQuantifier()
                }
                char in OPERATORS -> break
                else -> {
                    plain.append(char)
                    at++
                }
            }
        }
        return plain.toString()
    }

    /**
     * Null when [pattern] reads [texts] in step with their length, else how it doesn't: its times, that it reads on past
     * [GIVE_UP_MS], or that it runs out of stack, as a repeated group does with one call for each repeat. Then that one
     * pattern is named, and the others are still timed.
     */
    private fun outOfStep(pattern: Pattern, length: Int = LENGTH, texts: (Int) -> List<String> = { hostileTexts(pattern.pattern(), it) }): String? = try {
        Growth.outOfStep(texts, length, { it.take(6).replace("\n", "\\n") + "…" }) { readAll(pattern, it) }.firstOrNull()
    } catch (_: Deadline.Passed) {
        "reads on past $GIVE_UP_MS ms"
    } catch (_: StackOverflowError) {
        "overflows the stack"
    }

    /** All the pattern finds in the text, stopped once [GIVE_UP_MS] have passed, as a pattern that's out of step can take minutes. */
    private fun readAll(pattern: Pattern, text: String): Int {
        val matcher = pattern.matcher(Deadline(text, System.nanoTime() + GIVE_UP_MS * 1_000_000))
        return generateSequence { matcher.takeIf { it.find() } }.count()
    }

    /** Text that stops a pattern once its time is up, as [Pattern] has no time limit of its own. */
    private class Deadline(private val text: String, private val until: Long) : CharSequence {
        class Passed : RuntimeException()

        override val length get() = text.length
        override fun get(index: Int): Char {
            if (index % 64 == 0 && System.nanoTime() > until) throw Passed()
            return text[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int) = text.subSequence(startIndex, endIndex)
        override fun toString() = text
    }

    private companion object {
        /** How long a text is read, and then twice it. */
        const val LENGTH = 20_000
        const val SHORT = 5_000
        /** Far past the slowest pattern's time on a busy machine, which is in milliseconds: a pattern this slow is out of step. */
        const val GIVE_UP_MS = 10_000L
        val RUNS = listOf("x", " ", "\n", "<", "<a ", "<p>", "[", "(", "（", "a ", "&#", "-", "歌", "x\"")
        const val OPERATORS = "\\[](){}?*+|^$."
        const val ESCAPED_PLAIN = "<>\"'/=.-:()[]{}?+*|&#;"
    }
}
