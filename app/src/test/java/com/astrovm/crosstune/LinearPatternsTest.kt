package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

/**
 * Every pattern in the app takes time in step with the text it reads. A page can be long and made of
 * anything, and a pattern that looks to the end of the text from each place it tries takes time squared:
 * 20,000 `<` with no `>` take seconds where they should take a millisecond. So each pattern in the app's
 * code is timed on long text of the kinds that do that.
 */
class LinearPatternsTest {
    @Test
    fun noPatternInTheAppTakesTimeSquaredOnLongText() {
        val patterns = appPatterns()
        val slow = patterns.mapNotNull { (where, pattern) ->
            val worst = hostileTexts(pattern.pattern()).maxOf { text -> millisToReadAll(pattern, text) }
            when {
                worst == OVERFLOWED -> "$where overflows the stack ${pattern.pattern().take(80)}"
                worst > LIMIT_MS -> "$where ${worst}ms ${pattern.pattern().take(80)}"
                else -> null
            }
        }
        assertEquals(emptyList<String>(), slow)
    }

    @Test
    fun everyPatternInTheAppIsRead() {
        val patterns = appPatterns()
        // As many as the app has, give or take: fewer means the reading below lost some.
        assertEquals(true, patterns.size > 200)
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
        // A Spotify page's title, from its start only: one place to try, and a title is a line long.
        "MetadataParsers.kt" to "^(.+)(?: [-–‑] |: )",
        // One word of a short list, between lookarounds of one letter each.
        "Translator.kt" to "\$spoken",
    )

    @Test
    fun aPatternThatLooksToTheEndFromEachPlaceIsSlowAndOneThatDoesNotIsFast() {
        val slow = Pattern.compile("<[^>]*>")
        val fast = Pattern.compile("<[^<>]*+>")
        assertEquals(true, hostileTexts(slow.pattern()).maxOf { millisToReadAll(slow, it) } > LIMIT_MS)
        assertEquals(true, hostileTexts(fast.pattern()).maxOf { millisToReadAll(fast, it) } <= LIMIT_MS)
    }

    @Test
    fun aPatternThatRunsOutOfStackIsNamedAndNotTheEndOfTheTest() {
        val deep = Pattern.compile("(?:a|b)*c")
        assertEquals(OVERFLOWED, millisToReadAll(deep, "a".repeat(LENGTH)))
        // The same, with no group to repeat and tried only where a run starts.
        assertEquals(true, millisToReadAll(Pattern.compile("(?<![ab])[ab]*+c"), "a".repeat(LENGTH)) <= LIMIT_MS)
    }

    @Test
    fun templatesAndJoinedLiteralsAreReadAsTheAppBuildsThem() {
        val source = "private const val TAG = \"\"\"<b>\"\"\"\nprivate const val END = \"</b>\"\n" +
            "val a = Regex(\"\"\"${'$'}TAG(x)\"\"\" + \"\"\"${'$'}{END}\\s\"\"\", RegexOption.IGNORE_CASE)\n" +
            "val b = Regex(\"a\\\\d\\${'$'}\")\nval c = Regex(\"\"\"${'$'}{'${'$'}'}\"\"\")\n" +
            "val d = Regex(\"\"\"\"q\":\"(\\w)\"\"\"\").find(x)\nval e = Regex(\"\"\"^(.+)\"\"\" + words + \"\"\"${'$'}\"\"\")\n"
        val read = patternsIn("Test.kt", source, constants(listOf(source)))
        assertEquals(listOf("<b>(x)</b>\\s", "a\\d$", "$", "\"q\":\"(\\w)\""), read.map { it.second.pattern() })
        assertEquals(listOf(true, false, false, false), read.map { it.second.flags() and Pattern.CASE_INSENSITIVE != 0 })
        assertEquals(listOf("Test.kt:7 Regex(\"\"\"^(.+)\"\"\""), unread)
    }

    private val unread = mutableListOf<String>()

    private fun appPatterns(): List<Pair<String, Pattern>> {
        val files = File("src/main/java").walk().filter { it.extension == "kt" }.sortedBy { it.path }.toList()
        val sources = files.map { it.readText() }
        val known = constants(sources)
        return files.zip(sources).flatMap { (file, source) -> patternsIn(file.name, source, known) }
    }

    /** The app's constants, which its patterns are built from. */
    private fun constants(sources: List<String>): Map<String, String> = sources.flatMap { source ->
        constant.findAll(source).map { it.groupValues[1] to literalText(it.groupValues[2]) }
    }.toMap()

    private fun patternsIn(name: String, source: String, known: Map<String, String>): List<Pair<String, Pattern>> {
        fun where(at: Int) = "$name:${source.take(at).count { it == '\n' } + 1}"
        val read = call.findAll(source).map { it.range.first }.toSet()
        opening.findAll(source).filter { it.range.first !in read }.forEach { unread += "${where(it.range.first)} ${it.value.take(60)}" }
        return call.findAll(source).map { found ->
            val where = where(found.range.first)
            val text = literals.findAll(found.groupValues[1]).joinToString("") { literalText(it.value) }
            val built = template.replace(text) { part ->
                val key = part.groupValues[1].ifEmpty { part.groupValues[2] }
                if (part.value == "\${'$'}") "$" else known[key] ?: run { unread += "$where \$$key"; part.value }
            }
            val options = found.groupValues[2]
            // Kotlin's Regex reads every pattern with UNICODE_CASE.
            val flags = Pattern.UNICODE_CASE or (if ("IGNORE_CASE" in options) Pattern.CASE_INSENSITIVE else 0) or
                (if ("DOT_MATCHES_ALL" in options) Pattern.DOTALL else 0) or
                (if ("MULTILINE" in options) Pattern.MULTILINE else 0) or
                (if ("COMMENTS" in options) Pattern.COMMENTS else 0)
            where to Pattern.compile(built, flags)
        }.toList()
    }

    /** A Kotlin string literal's text: a raw one as it is, a quoted one with its escapes read. */
    private fun literalText(literal: String): String =
        if (literal.startsWith("\"\"\"")) literal.removeSurrounding("\"\"\"")
        else escape.replace(literal.removeSurrounding("\"")) { found ->
            val escaped = found.groupValues[1]
            when {
                escaped.startsWith("u") -> escaped.drop(1).toInt(16).toChar().toString()
                escaped == "n" -> "\n"
                escaped == "t" -> "\t"
                escaped == "r" -> "\r"
                else -> escaped
            }
        }

    /** Text that makes a pattern try each place and look on to the end: runs of one thing, and of how the pattern starts. */
    private fun hostileTexts(pattern: String): List<String> {
        val start = plainStart(pattern)
        val starts = if (start.isEmpty()) emptyList() else listOf(start.repeat(LENGTH / start.length + 1), start + "x".repeat(LENGTH), start + " ".repeat(LENGTH))
        return RUNS.map { it.repeat(LENGTH / it.length) } + starts
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
     * How long the pattern takes to find all it finds in the text, stopped at [GIVE_UP_MS], or [OVERFLOWED] when it
     * runs out of stack, as a repeated group does with one call for each repeat: then that one pattern is named, and
     * the others are still timed.
     */
    private fun millisToReadAll(pattern: Pattern, text: String): Long {
        val began = System.nanoTime()
        val matcher = pattern.matcher(Deadline(text, began + GIVE_UP_MS * 1_000_000))
        try {
            generateSequence { matcher.takeIf { it.find() } }.count()
        } catch (_: Deadline.Passed) {
            return GIVE_UP_MS
        } catch (_: StackOverflowError) {
            return OVERFLOWED
        }
        return (System.nanoTime() - began) / 1_000_000
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
        const val LENGTH = 20_000
        const val LIMIT_MS = 300L
        const val GIVE_UP_MS = 1_000L
        const val OVERFLOWED = Long.MAX_VALUE
        val RUNS = listOf("x", " ", "\n", "<", "<a ", "<p>", "[", "(", "（", "a ", "&#", "-", "歌", "x\"")
        const val OPERATORS = "\\[](){}?*+|^$."
        const val ESCAPED_PLAIN = "<>\"'/=.-:()[]{}?+*|&#;"

        /** A raw literal ends at its first `\"\"\"`, and takes in any more quotes right before that. */
        const val RAW = "\"\"\"(?:[^\"]|\"(?!\"\"))*+\"*\"\"\""
        const val QUOTED = "\"(?:[^\"\\\\\\n]|\\\\.)*+\""
        val literals = Regex("$RAW|$QUOTED")
        /** `Regex(` and the literals it's built from, joined with `+`, then its options. */
        val call = Regex("""\bRegex\(\s*((?:$RAW|$QUOTED)(?:\s*\+\s*(?:$RAW|$QUOTED))*+)\s*(,\s*(?:RegexOption\.\w+|setOf\([^()]*\)))?\s*\)""")
        /** Any `Regex(` that starts with a literal, read or not. */
        val opening = Regex("""\bRegex\(\s*(?:$RAW|$QUOTED)""")
        val constant = Regex("""\bconst val (\w+)\s*=\s*($RAW|$QUOTED)""")
        val template = Regex("""\$\{'\$'\}|\$\{(\w+)\}|\$(\w+)""")
        val escape = Regex("""\\(u[0-9a-fA-F]{4}|.)""")
    }
}
