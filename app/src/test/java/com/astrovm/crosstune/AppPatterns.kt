package com.astrovm.crosstune

import java.io.File
import java.util.regex.Pattern

/**
 * The app's patterns, read from its code as the app builds them: each `phoneRegex(` or `Regex(` of literals, raw or quoted and
 * joined with `+`, with the constants and templates in them filled in, and its options. The tests that look at
 * every pattern read them here, so what one of them reads, the others read too.
 */
internal class AppPatterns(val files: List<Pair<String, String>>) {
    /** A pattern: [where] it is, "File.kt:12", the value that [holds] it, if one does, and the pattern as built. */
    data class Found(val where: String, val holds: String?, val pattern: Pattern)

    /**
     * Each pattern that isn't read whole, by where it is and its line: a `Regex(` of literals that isn't, one built
     * from a value, or a `.toRegex()`. And a template in one that no constant fills.
     */
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
        fun line(at: Int) = source.substring(source.lastIndexOf('\n', at - 1) + 1).lineSequence().first().trim()
        // A pattern named in a comment, as main's was, is no pattern of the app's.
        fun inComment(at: Int) = line(at).let { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }
        if (name.endsWith(".java")) {
            // The app writes its patterns in Kotlin, with phoneRegex, so each is read here as a phone reads it. One in
            // Java is read neither way, so each is named, as a pattern built from a value is.
            javaBuilt.findAll(source).map { it.range.first }.filterNot(::inComment).forEach { unread += "${where(it)} ${line(it)}" }
            return emptyList()
        }
        val calls = call.findAll(source).filterNot { inComment(it.range.first) }.toList()
        val read = call.findAll(source).map { it.range.first }.toSet()
        built.findAll(source).filter { it.range.first !in read }.map { it.range.first }
            .filterNot(::inComment)
            .forEach { unread += "${where(it)} ${line(it)}" }
        return calls.map { found ->
            val where = where(found.range.first)
            val text = literals.findAll(found.groupValues[1]).joinToString("") { literalText(it.value) }
            val built = template.replace(text) { part ->
                val key = part.groupValues[2].ifEmpty { part.groupValues[3] }
                // A letter in a template, as ${'"'} ends a raw string in a quote, is that letter.
                part.groupValues[1].ifEmpty { known[key] ?: run { unread += "$where \$$key"; part.value } }
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
        fun ofApp() = AppPatterns(sources("src/main/java").map { it.name to it.readText() })

        /** The code under [root], Kotlin and Java: a pattern or a call in either is the app's, so neither is left unread. */
        fun sources(root: String): List<File> = File(root).walk().filter { it.isFile && it.extension in CODE }.sortedBy { it.path }.toList()

        private val CODE = setOf("kt", "java")

        /** A raw literal ends at its first `\"\"\"`, and takes in any more quotes right before that. */
        private const val RAW = "\"\"\"(?:[^\"]|\"(?!\"\"))*+\"*\"\"\""
        private const val QUOTED = "\"(?:[^\"\\\\\\n]|\\\\.)*+\""
        private const val HOLDER_REACH = 200
        private val literals = Regex("$RAW|$QUOTED")
        /** `phoneRegex(` or `Regex(` and the literals it's built from, joined with `+`, then its options. */
        private val call = Regex("""\b(?:phoneRegex|Regex)\(\s*((?:$RAW|$QUOTED)(?:\s*\+\s*(?:$RAW|$QUOTED))*+)\s*(,\s*(?:RegexOption\.\w+(?:\s*,\s*RegexOption\.\w+)*+|setOf\([^()]*\)))?\s*\)""")
        /** Any `phoneRegex(` or `Regex(`, read or not, and a `.toRegex(`. */
        private val built = Regex("""\b(?:phoneRegex|Regex)\(|\.toRegex\(""")
        /** Where Java builds a pattern: as Kotlin does, or with `Pattern`, or with a String's own methods, which take one. */
        private val javaBuilt = Regex("""\b(?:Pattern\.(?:compile|matches)|Regex|StringsKt\.toRegex)\(|\.(?:matches|split|replaceAll|replaceFirst)\(""")
        private val constant = Regex("""\bconst val (\w+)\s*=\s*($RAW|$QUOTED)""")
        private val template = Regex("""\$\{'([^'\\])'\}|\$\{(\w+)\}|\$(\w+)""")
        private val escape = Regex("""\\(u[0-9a-fA-F]{4}|.)""")
        /** The value a `Regex(` is given to, right before it: "private val tags = ". */
        private val holder = Regex("""\b(?:val|var)\s+(\w+)(?:\s*:\s*Regex)?\s*=\s*$""")
    }
}
