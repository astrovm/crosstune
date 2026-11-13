package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * A value that may be null is handled, never asserted with `!!`: when it is null after all, the app
 * crashes instead of doing what it does without it. detekt's rule for this needs the whole classpath
 * resolved, so the app's code is read here instead, without its comments and strings.
 */
class NoUnsafeCallsTest {
    @Test
    fun theAppsCodeNeverAssertsAValueIsThere() {
        val found = File("src/main/java").walk().filter { it.extension == "kt" }.flatMap { file ->
            val source = file.readText()
            val code = Code(source).read()
            assertEquals(file.name, source.lines().size, code.lines().size)
            code.lines().withIndex().filter { "!!" in it.value }.map { "${file.name}:${it.index + 1}" }
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
        val found = listOf("src/main/java", "src/test/java").flatMap { File(it).walk().filter { file -> file.extension == "kt" }.toList() }
            .flatMap { file -> Code(file.readText()).also { it.read() }.endsInAQuote.map { "${file.name}:$it" } }
        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun onlyTheCodeIsRead() {
        val source = """
            val a = b!! // c!!
            /* d!!
            */ val e = "f!!" + '!' + '\'' + ""${'"'}g!!""${'"'}${'"'} + "${'$'}{h!!.let { "i!!" }}" + "\"j!!"
        """.trimIndent()
        val code = Code(source)
        assertEquals("val a = b!! \n\n val e =  +  +  +  + h!!.let {  } + ", code.read())
        assertEquals(listOf(3), code.endsInAQuote)
    }

    /**
     * Kotlin source with comments and string or character literals left out, line breaks kept so lines
     * keep their numbers. What's inside a string's ${...} is code, so it stays.
     */
    private class Code(private val source: String) {
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
            val raw = quote == "\"\"\""
            at += quote.length
            while (!source.startsWith(quote, at)) {
                when {
                    source[at] == '\\' && !raw -> at += 2
                    source.startsWith("\${", at) -> {
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
