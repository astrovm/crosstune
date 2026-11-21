package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A value that may be null is handled, never asserted to be non-null: when it is null after all, the app
 * crashes instead of doing what it does without it. The code is read as text, without comments and strings, so
 * no type has to be resolved to find an assertion, and none can hide one.
 */
class NoUnsafeCallsTest {
    @Test
    fun codeNeverAssertsAValueIsThere() {
        // Every source folder: the app's, the tests' and any a build type adds.
        val found = AppPatterns.sources("src").flatMap { file ->
            val source = file.readText()
            val code = Code(source, java = file.extension == "java").read()
            assertEquals(file.name, source.lines().size, code.lines().size)
            code.lines().withIndex().filter { "!".repeat(2) in it.value }.map { "${file.path}:${it.index + 1}" }
        }.toList()
        assertEquals(emptyList<String>(), found)
    }

    /**
     * Lint's HTML report reads `"""a` and four quotes as a string closed and a quote that opens another. With a quote in
     * backticks in a comment after it, it reads on to before the file's start and crashes, and the report says nothing
     * of what it found. Such a string ends in ${'"'} instead.
     */
    @Test
    fun noRawStringEndsInAQuote() {
        val found = listOf("src/main/java", "src/test/java").flatMap(AppPatterns::sources)
            .flatMap { file -> Code(file.readText(), java = file.extension == "java").also { it.read() }.endsInAQuote.map { "${file.name}:$it" } }
        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun onlyTheCodeIsRead() {
        val assertion = "!".repeat(2)
        val source = """
            val a = b${assertion} // c${assertion}
            /* d${assertion}
            */ val e = "f${assertion}" + '!' + '\'' + ""${'"'}g${assertion}""${'"'}${'"'} + "${'$'}{h${assertion}.let { "i${assertion}" }}" + "\"j${assertion}"
        """.trimIndent()
        val code = Code(source)
        assertEquals("val a = b${assertion} \n\n val e =  +  +  +  + h${assertion}.let {  } + ", code.read())
        assertEquals(listOf(3), code.endsInAQuote)
    }

    @Test
    fun javaIsReadAsJava() {
        // Java has no templates, and reads escapes in a text block.
        val assertion = "!".repeat(2)
        val source = "String a = \"${'$'}{b${assertion}}\" + \"\"\"\n  c${assertion}\\\"\"\"\"; boolean d = ${assertion}e;"
        val code = Code(source, java = true)
        assertEquals("String a =  + \n; boolean d = ${assertion}e;", code.read())
        assertEquals(emptyList<Int>(), code.endsInAQuote)
    }

    /**
     * Kotlin or, when [java], Java source with comments and string or character literals left out, line breaks kept so
     * lines keep their numbers. What's inside a Kotlin string's ${...} is code, so it stays.
     */
    private class Code(private val source: String, private val java: Boolean = false) {
        private val kept = StringBuilder()
        private var at = 0

        /** The line of each raw string that ends in a quote of its own. */
        val endsInAQuote = mutableListOf<Int>()

        fun read(): String {
            code(inTemplate = false)
            return kept.toString()
        }

        private fun code(inTemplate: Boolean) {
            var depth = 0
            while (at < source.length) {
                val char = source[at]
                when {
                    source.startsWith("//", at) -> at = source.indexOf('\n', at).takeIf { it >= 0 } ?: source.length
                    source.startsWith("/*", at) -> skipTo(source.indexOf("*/", at + 2) + 2)
                    source.startsWith("\"\"\"", at) -> literal("\"\"\"")
                    char == '"' || char == '\'' -> literal(char.toString())
                    inTemplate && char == '}' && depth == 0 -> return
                    else -> {
                        if (char == '{') depth++
                        if (char == '}') depth--
                        kept.append(char)
                        at++
                    }
                }
            }
        }

        private fun literal(quote: String) {
            // A Java text block reads escapes, as a quoted string does.
            val raw = quote == "\"\"\"" && !java
            at += quote.length
            while (!source.startsWith(quote, at)) {
                when {
                    source[at] == '\\' && !raw -> at += 2
                    !java && source.startsWith("\${", at) -> {
                        at += 2
                        code(inTemplate = true)
                        at++
                    }
                    else -> skipTo(at + 1)
                }
            }
            at += quote.length
            if (raw && source.getOrNull(at) == '"') endsInAQuote += source.substring(0, at).count { it == '\n' } + 1
            // A raw string can end in quotes of its own: """a"""" is a" closed.
            while (raw && source.getOrNull(at) == '"') at++
        }

        // Past what isn't code, keeping its line breaks.
        private fun skipTo(end: Int) {
            repeat(source.substring(at, end).count { it == '\n' }) { kept.append('\n') }
            at = end
        }
    }
}
