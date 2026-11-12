package com.astrovm.crosstune

import java.io.File
import java.util.regex.Pattern

/**
 * The app's patterns, read from its code as the app builds them: each `Regex(` of literals, raw or quoted and
 * joined with `+`, with the constants and templates in them filled in, and its options. The tests that look at
 * every pattern read them here, so what one of them reads, the others read too.
 */
internal class AppPatterns(val files: List<Pair<String, String>>) {
    /** A pattern: [where] it is, "File.kt:12", the value that [holds] it, if one does, and the pattern as built. */
    data class Found(val where: String, val holds: String?, val pattern: Pattern)

    /** Each `Regex(` that starts with a literal and isn't read whole, or a template in one no constant fills. */
    val unread = mutableListOf<String>()

    val found: List<Found> = constants(files.map { it.second }).let { known ->
        files.flatMap { (name, source) -> patternsIn(name, source, known) }
    }

    /** The app's constants, which its patterns are built from. */
    private fun constants(sources: List<String>): Map<String, String> = sources.flatMap { source ->
        constant.findAll(source).map { it.groupValues[1] to literalText(it.groupValues[2]) }
    }.toMap()

    private fun patternsIn(name: String, source: String, known: Map<String, String>): List<Found> {
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
            val holds = holder.find(source.substring(maxOf(0, found.range.first - HOLDER_REACH), found.range.first))?.groupValues?.get(1)
            Found(where, holds, Pattern.compile(built, flags))
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

    companion object {
        /** The app's own patterns, from its code. */
        fun ofApp() = AppPatterns(
            File("src/main/java").walk().filter { it.extension == "kt" }.sortedBy { it.path }.map { it.name to it.readText() }.toList()
        )

        /** A raw literal ends at its first `\"\"\"`, and takes in any more quotes right before that. */
        private const val RAW = "\"\"\"(?:[^\"]|\"(?!\"\"))*+\"*\"\"\""
        private const val QUOTED = "\"(?:[^\"\\\\\\n]|\\\\.)*+\""
        private const val HOLDER_REACH = 200
        private val literals = Regex("$RAW|$QUOTED")
        /** `Regex(` and the literals it's built from, joined with `+`, then its options. */
        private val call = Regex("""\bRegex\(\s*((?:$RAW|$QUOTED)(?:\s*\+\s*(?:$RAW|$QUOTED))*+)\s*(,\s*(?:RegexOption\.\w+|setOf\([^()]*\)))?\s*\)""")
        /** Any `Regex(` that starts with a literal, read or not. */
        private val opening = Regex("""\bRegex\(\s*(?:$RAW|$QUOTED)""")
        private val constant = Regex("""\bconst val (\w+)\s*=\s*($RAW|$QUOTED)""")
        private val template = Regex("""\$\{'\$'\}|\$\{(\w+)\}|\$(\w+)""")
        private val escape = Regex("""\\(u[0-9a-fA-F]{4}|.)""")
        /** The value a `Regex(` is given to, right before it: "private val tags = ". */
        private val holder = Regex("""\b(?:val|var)\s+(\w+)(?:\s*:\s*Regex)?\s*=\s*$""")
    }
}
