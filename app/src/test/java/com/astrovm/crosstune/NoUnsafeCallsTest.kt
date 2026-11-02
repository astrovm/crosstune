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

    @Test
    fun onlyTheCodeIsRead() {
        val source = """
            val a = b!! // c!!
            /* d!!
            */ val e = "f!!" + '!' + '\'' + ""${'"'}g!!""${'"'}${'"'} + "${'$'}{h!!.let { "i!!" }}" + "\"j!!"
        """.trimIndent()
        assertEquals("val a = b!! \n\n val e =  +  +  +  + h!!.let {  } + ", Code(source).read())
    }

    /**
     * Kotlin source with comments and string or character literals left out, line breaks kept so lines
     * keep their numbers. What's inside a string's ${...} is code, so it stays.
     */
    private class Code(private val source: String) {
        private val kept = StringBuilder()
        private var at = 0

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
