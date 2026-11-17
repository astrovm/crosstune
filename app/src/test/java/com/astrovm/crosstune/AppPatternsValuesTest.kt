package com.astrovm.crosstune

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPatternsValuesTest {
    @Test
    fun derivedDelimiterClassesAreReadAsTheAppBuildsThem() {
        val definitions = """
            object TitleDelimiters {
                const val OPENS = "a[b"
                internal val tagOpens = OPENS.take(2)
            }
        """.trimIndent()
        val source = """
            private val marks = TitleDelimiters.regexClass(TitleDelimiters.tagOpens)
            private val pattern = phoneRegex("[${'$'}marks]")
        """.trimIndent()
        val app = AppPatterns(listOf("TitleDelimiters.kt" to definitions, "Reader.kt" to source))
        assertEquals(emptyList<String>(), app.unread)
        assertEquals(listOf("[a\\[]"), app.found.map { it.pattern.pattern() })
    }

    @Test
    fun immutableAliasesConcatenationAndTemplatesAreResolved() {
        val source = """
            private const val START = "A"
            private val joined = START + "+" + "B"
            private val wrapped = "${'$'}joined-C"
            private val pattern = Regex("${'$'}wrapped")
        """.trimIndent()
        val app = AppPatterns(listOf("Reader.kt" to source))
        assertEquals(emptyList<String>(), app.unread)
        assertEquals(listOf("A+B-C"), app.found.map { it.pattern.pattern() })
    }

    @Test
    fun aTopLevelPropertyDoesNotNeedAnExplicitVisibilityModifier() {
        val source = "const val START = \"<b>\"\nconst val END = \"</b>\"\nval pattern = Regex(\"${'$'}START(x)${'$'}END\")"
        val app = AppPatterns(listOf("Reader.kt" to source))
        assertEquals(emptyList<String>(), app.unread)
        assertEquals("<b>(x)</b>", app.found.single().pattern.pattern())
    }

    @Test
    fun localValuesCannotReplaceTheReadersConstant() {
        val source = """
            private const val WORD = "kept"
            fun other() { val WORD = "other" }
            private val pattern = Regex("${'$'}WORD")
        """.trimIndent()
        val app = AppPatterns(listOf("Reader.kt" to source))
        assertEquals(emptyList<String>(), app.unread)
        assertEquals(listOf("kept"), app.found.map { it.pattern.pattern() })
    }

    @Test
    fun cyclesAndUnsupportedOperationsStayUnread() {
        val source = """
            private val first = second
            private val second = first
            private val other = "x".repeat(2)
            private val pattern = Regex("${'$'}first${'$'}other")
        """.trimIndent()
        val app = AppPatterns(listOf("Reader.kt" to source))
        assertEquals(2, app.unread.size)
        assertEquals(0, app.found.size)
    }

    @Test
    fun ambiguousPropertiesAreNotGuessed() {
        val source = """
            object One { private const val WORD = "one" }
            object Two { private const val WORD = "two" }
            private val pattern = Regex("${'$'}WORD")
        """.trimIndent()
        val app = AppPatterns(listOf("Reader.kt" to source))
        assertEquals(1, app.unread.size)
    }

    @Test
    fun aPropertiesNameDoesNotMakeItAGlobalConstant() {
        val definitions = "private val constantPart = \"other\""
        val app = AppPatterns(listOf("Other.kt" to definitions, "Reader.kt" to "private val pattern = Regex(\"${'$'}constantPart\")"))
        assertEquals(1, app.unread.size)
    }

    @Test
    fun qualifiedPropertiesUseTheDeclaredObjectRatherThanTheFileName() {
        val definitions = "object Marks { internal const val WORD = \"kept\" }"
        val source = "private val word = Marks.WORD\nprivate val pattern = Regex(\"${'$'}word\")"
        val app = AppPatterns(listOf("Definitions.kt" to definitions, "Reader.kt" to source))
        assertEquals(emptyList<String>(), app.unread)
        assertEquals("kept", app.found.single().pattern.pattern())
    }

    @Test
    fun aCommentDoesNotHideAnUnsupportedContinuation() {
        for (comment in listOf("", "// Still followed by a call.")) {
            for (call in listOf(".repeat(2)", "?.repeat(2)", "[0]")) {
                val source = """
                    private val word = "x" $comment
                        $call
                    private val pattern = Regex("${'$'}word")
                """.trimIndent()
                assertEquals("$comment $call", 1, AppPatterns(listOf("Reader.kt" to source)).unread.size)
            }
        }
    }

    @Test
    fun runtimeParametersDoNotBecomeTheObjectsImmutableValue() {
        for (body in listOf("Regex(\"${'$'}marks\")", "run { val pattern = Regex(\"${'$'}marks\"); pattern }")) {
            val source = """
                object Reader {
                    private val marks = "kept"
                    fun read(marks: String) = $body
                }
            """.trimIndent()
            assertEquals(body, 1, AppPatterns(listOf("Reader.kt" to source)).unread.size)
        }
    }

    @Test
    fun constructorParametersDoNotBecomeAnotherObjectsValue() {
        val source = """
            object Other { private val marks = "other" }
            class Reader(private val marks: String) {
                private val pattern = Regex("${'$'}marks")
            }
        """.trimIndent()
        assertEquals(1, AppPatterns(listOf("Reader.kt" to source)).unread.size)
    }

    @Test
    fun separateObjectsKeepTheirOwnImmutableValues() {
        val source = """
            object One {
                private val marks = "one"
                private val pattern = Regex("${'$'}marks")
            }
            object Two {
                private val marks = "two"
                private val pattern = Regex("${'$'}marks")
            }
        """.trimIndent()
        val app = AppPatterns(listOf("Reader.kt" to source))
        assertEquals(emptyList<String>(), app.unread)
        assertEquals(listOf("one", "two"), app.found.map { it.pattern.pattern() })
    }

    @Test
    fun anEscapedDollarInAConstantIsNotExpandedAgain() {
        val source = "private const val WORD = \"other\"\nprivate const val ESCAPED = \"\\${'$'}WORD\"\nprivate val pattern = Regex(\"${'$'}ESCAPED\")"
        val app = AppPatterns(listOf("Reader.kt" to source))
        assertEquals(emptyList<String>(), app.unread)
        assertEquals("${'$'}WORD", app.found.single().pattern.pattern())
    }

    @Test
    fun innerPropertiesBlockOuterPropertiesEvenWithoutAnInitializerOrVisibility() {
        for (inner in listOf("var word = \"other\"", "val word = \"other\"", "val word: String get() = runtime()")) {
            val source = "private val word = \"kept\"\nobject Reader { $inner\n private val pattern = Regex(\"${'$'}word\") }"
            val app = AppPatterns(listOf("Reader.kt" to source))
            if (inner.startsWith("val word =")) {
                assertEquals(emptyList<String>(), app.unread)
                assertEquals("other", app.found.single().pattern.pattern())
            } else assertEquals(inner, 1, app.unread.size)
        }
    }

    @Test
    fun commentsOnLaterLinesDoNotHideAContinuationOrGetter() {
        for (comment in listOf("// Comment", "/* Comment */", "// First\n // Second")) {
            for (next in listOf(".repeat(2)", "get() = field.repeat(2)")) {
                val source = "private val word = \"x\"\n $comment\n $next\nprivate val pattern = Regex(\"${'$'}word\")"
                assertEquals("$comment $next", 1, AppPatterns(listOf("Reader.kt" to source)).unread.size)
            }
        }
    }

    @Test
    fun aCustomGetterDoesNotResolveToItsInitializer() {
        val source = "private val word = \"x\"\n get() = field.repeat(2)\nprivate val pattern = Regex(\"${'$'}word\")"
        assertEquals(1, AppPatterns(listOf("Reader.kt" to source)).unread.size)
    }

    @Test
    fun runtimeContextsDoNotBorrowAnOuterConstant() {
        val source = "object Example { const val part = \"outer\"\n fun pattern(): Regex { val part = \"inner\"; return Regex(\"${'$'}part\") } }"
        assertEquals(1, AppPatterns(listOf("Reader.kt" to source)).unread.size)
    }

    @Test
    fun annotationsBeforeAGetterDoNotMakeItsInitializerTheValue() {
        val source = "object Example { val part = \"initial\"\n @Deprecated(\"Use another value\")\n get() = \"actual\"\n private val pattern = Regex(\"${'$'}part\") }"
        assertEquals(1, AppPatterns(listOf("Reader.kt" to source)).unread.size)
    }

    @Test
    fun anUnsupportedOperatorCannotEndTheExpressionEarly() {
        for (operator in listOf("==", "!=", "in", "to", "?:")) {
            val source = "object Example { val part = \"a\"\n $operator \"b\"\n private val pattern = Regex(\"${'$'}part\") }"
            assertEquals(operator, 1, AppPatterns(listOf("Reader.kt" to source)).unread.size)
        }
    }

    @Test
    fun unsupportedTemplateExpressionsStayUnreadWithoutCompilingThem() {
        val source = "val part = \"a\"\n val pattern = Regex(\"${'$'}{part.uppercase()}\")"
        assertEquals(1, AppPatterns(listOf("Reader.kt" to source)).unread.size)
    }

    @Test
    fun unsupportedUnicodeIdentifiersAreReportedRatherThanTreatedAsPatternText() {
        for (name in listOf("é", "音", "क", "𝛂", "parté", "part音")) {
            val definitions = "val $name = \"actual\""
            val source = "private const val part = \"wrong\"\n private val pattern = Regex(\"${'$'}$name\")"
            assertEquals(name, 1, AppPatterns(listOf("Definitions.kt" to definitions, "Reader.kt" to source)).unread.size)
        }
    }

    @Test
    fun aDollarBeforeADigitIsLiteralPatternText() {
        val app = AppPatterns(listOf("Reader.kt" to "private val pattern = Regex(\"${'$'}1\")"))
        assertEquals(emptyList<String>(), app.unread)
        assertEquals("${'$'}1", app.found.single().pattern.pattern())
    }

    @Test
    fun everyRecordingPatternHasItsDerivedValues() {
        val app = AppPatterns.ofApp()
        assertEquals(emptyList<String>(), app.unread.filter { it.startsWith("SongNames.kt:") })
        val derived = setOf("guestCredit", "editionTag", "coverBy", "coverByOwnName", "singerAfter", "bracketTag", "dashTag")
        assertEquals(derived, app.found.mapNotNull { it.holds }.toSet().intersect(derived))
    }

    @Test
    fun theSharedDelimiterExpressionsMatchTheRealHelper() {
        val definitions = File("src/main/java/com/astrovm/crosstune/TitleDelimiters.kt").readText()
        val source = """
            private val marks = TitleDelimiters.regexClass(TitleDelimiters.normalizedOpens)
            private val pattern = Regex("[${'$'}marks]")
        """.trimIndent()
        val app = AppPatterns(listOf("TitleDelimiters.kt" to definitions, "Reader.kt" to source))
        assertEquals(emptyList<String>(), app.unread)
        assertEquals("[" + TitleDelimiters.regexClass(TitleDelimiters.normalizedOpens) + "]", app.found.single().pattern.pattern())
        assertTrue(app.found.single().pattern.matcher("【").matches())
    }
}
