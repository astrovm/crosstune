package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.BitSet
import java.util.regex.Pattern

/**
 * The letters and places that Patterns.kt reads in code, and each pattern [phoneRegex] writes out for the JVM, are those
 * a pattern reads on a phone, by the table that `scripts/regextable/regextable.py` measured there, for each letter there is.
 */
class PatternLettersTest {
    @Test
    fun theTableIsTheOneTheSetsWereMadeFrom() {
        assertEquals("ICU 78.3.0.0, Android 17, API 37", PHONE_ENGINE)
        assertEquals(PHONE_ENGINE, TABLE.engine)
        assertEquals(false, ICU)
    }

    @Test
    fun eachLetterIsReadInCodeAsThePhoneReadsIt() {
        val space = TABLE.codes("""\s""")
        val latin = TABLE.codes("(?i)[a-z]")
        val notALineBreak = TABLE.codes(".")
        for (code in Char.MIN_VALUE.code..Char.MAX_VALUE.code) {
            val char = code.toChar()
            assertEquals("U+%04X".format(code), space[code], isPatternSpace(char))
            assertEquals("U+%04X".format(code), latin[code], isLatinLetterIgnoringCase(char))
            assertEquals("U+%04X".format(code), !notALineBreak[code], isLineBreak(char))
        }
        val digit = TABLE.codes("""\d""")
        for (code in 0..Character.MAX_CODE_POINT) assertEquals("U+%04X".format(code), digit[code], isPatternDigit(code))
    }

    @Test
    fun eachSetIsWrittenOutForTheJvmAsThePhoneReadsIt() {
        for (name in TABLE.classes.filterNot { it.startsWith("\\b@") }) {
            val options = setOfNotNull(RegexOption.IGNORE_CASE.takeIf { name.startsWith("(?i)") }, RegexOption.DOT_MATCHES_ALL.takeIf { name.startsWith("(?s)") })
            val regex = phoneRegex(name.removePrefix("(?i)").removePrefix("(?s)"), options, onIcu = false)
            assertEquals(name, TABLE.codes(name), matched(regex.toPattern()))
            // The JVM reads a class's runs one by one, so each is written as the JVM's nearest set and a few runs.
            assertTrue("$name: ${regex.pattern.length}", regex.pattern.length < SHORT_SET)
        }
    }

    @Test
    fun aWordStartsAndEndsWhereThePhoneReadsOne() {
        val written = phoneRegex("""\b""", emptySet(), onIcu = false)
        assertTrue("${written.pattern.length}", written.pattern.length < 6 * SHORT_SET)
        val boundary = written.toPattern().matcher("")
        // After a letter of two chars, a format letter, or a mark of two chars, it steps back over each whole, as a phone does.
        // Each letter after each takes a minute in all: in the unit tests, one letter in [Fuzz.SAMPLED] (Fuzz.kt).
        val codes = 0..Character.MAX_CODE_POINT step Fuzz.every
        for ((row, before) in listOf("\\b@0" to "", "\\b@a" to "a", "\\b@ " to " ") + BOUNDARIES_BEFORE) {
            val found = BitSet()
            val phone = BitSet()
            val table = TABLE.codes(row)
            for (code in codes) {
                if (boundary.reset(before + String(Character.toChars(code))).find(before.length) && boundary.start() == before.length) found.set(code)
                phone[code] = table[code]
            }
            assertEquals(row, phone, found)
        }
    }

    @Test
    fun aLineEndsAndAWordEndsWhereThePhoneReadsOneInEachText() {
        val ends = listOf("$" to phoneRegex("$", emptySet(), onIcu = false), "(?m)$" to phoneRegex("$", setOf(RegexOption.MULTILINE), onIcu = false),
            "(?m)^" to phoneRegex("^", setOf(RegexOption.MULTILINE), onIcu = false), "\\b" to phoneRegex("""\b""", emptySet(), onIcu = false))
        assertEquals(true, TABLE.ends.size > 1000)
        for ((text, places) in TABLE.ends) {
            val found = ends.joinToString("\t") { (name, regex) -> "$name=" + (0..text.length).filter { regex.find(text, it)?.range?.first == it }.joinToString(",") }
            assertEquals(text.map { "U+%04X".format(it.code) }.toString(), places, found)
            val dollar = places.substringAfter("$=").substringBefore('\t').split(',').map(String::toInt)
            for (from in 0..text.length) assertEquals("$text from $from", dollar.first { it >= from }, lineEnd(text, from))
        }
    }

    @Test
    fun eachLetterIgnoringCaseIsReadAsThePhoneReadsIt() {
        for ((letter, codes) in TABLE.folds) {
            val regex = phoneRegex(Regex.escape(letter), setOf(RegexOption.IGNORE_CASE), onIcu = false)
            assertEquals(letter, codes, matched(regex.toPattern(), Char.MAX_VALUE.code))
        }
        // A letter that folds to more than one, as a word of them: "ß" is "ss", and "ﬃ" is "ffi", either way.
        assertEquals(104, TABLE.longer.size)
        for ((code, fold, foldReadsIt, itReadsFold) in TABLE.longer) {
            val text = String(Character.toChars(code))
            assertEquals(text, foldReadsIt, phoneRegex(Regex.escape(fold), setOf(RegexOption.IGNORE_CASE), onIcu = false).matches(text))
            assertEquals(text, itReadsFold, phoneRegex(Regex.escape(text), setOf(RegexOption.IGNORE_CASE), onIcu = false).matches(fold))
            assertEquals(fold, String(WrittenFolds.full(code), 0, WrittenFolds.full(code).size))
        }
        assertEquals(TABLE.simpleFolds, (0..Character.MAX_CODE_POINT).filter { CaseFolds.simple(it) != it }.associateWith(CaseFolds::simple))
    }

    @Test
    fun aLetterIsReadInAWordAsThePhoneReadsIt() {
        // As the table has the letter alone ignoring case, but for one that folds to more than one, as "ﬀ" does.
        for (letter in "fmv/") {
            val reads = TABLE.folds[letter.toString()] ?: BitSet().apply { set(letter.code) }
            for (code in Char.MIN_VALUE.code..Char.MAX_VALUE.code) {
                assertEquals("U+%04X as $letter".format(code), reads[code] && !CaseFolds.foldsLonger(code), readsAs(code.toChar(), letter))
            }
        }
    }

    @Test
    fun aRunOfSpacesIsReadToItsEndsAsAPatternReadsIt() {
        val spaces = phoneRegex("""\s*""")
        val spacesAtTheEnd = phoneRegex("""\s*$""")
        val between = phoneRegex("""\s.\s""", RegexOption.DOT_MATCHES_ALL)
        for (text in listOf("", " ", "a \t\nb  ", "\u000B\u000Cx　 \r", "-", " - ", "a- b", "a -b", "a　- b")) {
            for (at in 0..text.length) {
                assertEquals("$text at $at", at + spaces.matchAt(text, at)?.value.orEmpty().length, spacesEnd(text, at))
                for (from in 0..at) assertEquals("$text at $at from $from", at - spacesAtTheEnd.find(text.substring(from, at))?.value.orEmpty().length, spacesStart(text, at, from))
                assertEquals("$text at $at", at > 0 && between.matchAt(text, at - 1) != null, isBetweenSpaces(text, at))
            }
            assertEquals(text, false, isBetweenSpaces(text, -1))
        }
    }

    @Test
    fun everyPatternInTheAppIsWrittenOutForTheJvm() {
        val found = AppPatterns.ofApp().found
        assertEquals(true, found.size > 200)
        for ((where, _, pattern) in found) {
            val options = setOfNotNull(
                RegexOption.IGNORE_CASE.takeIf { pattern.flags() and Pattern.CASE_INSENSITIVE != 0 },
                RegexOption.DOT_MATCHES_ALL.takeIf { pattern.flags() and Pattern.DOTALL != 0 },
                RegexOption.MULTILINE.takeIf { pattern.flags() and Pattern.MULTILINE != 0 },
            )
            runCatching { IcuPattern(pattern.pattern(), options).written }.onFailure { throw AssertionError("$where ${pattern.pattern()}", it) }
        }
    }

    @Test
    fun onAPhoneAPatternIsItself() {
        val regex = phoneRegex("""\sx""", setOf(RegexOption.MULTILINE), onIcu = true)
        assertEquals("""\sx""", regex.pattern)
        assertEquals(setOf(RegexOption.MULTILINE), regex.options)
        assertEquals(true, phoneRegex("""\s""").matches("　"))
    }

    @Test
    fun aPatternIsWrittenOutWithEachConstructAsThePhoneReadsIt() {
        fun reads(pattern: String, text: String, vararg options: RegexOption) = phoneRegex(pattern, *options).find(text)?.value
        // Groups of each kind, flags for the rest of a group or for one, and quantifiers on a letter alone.
        assertEquals("ab", reads("""(a)(?:b)(?=c)(?!d)""", "abc"))
        assertEquals("bc", reads("""(?<=a)(?<!x)(?>bc)""", "abc"))
        assertEquals("aBMR", reads("""a(?i)b(?-i:MR)""", "aBMRmr"))
        assertEquals(null, reads("""a(?i)b(?-i:MR)""", "aBmr"))
        assertEquals("A\nB", reads("""(?is:a.b)""", "A\nB"))
        // A group read again, as it was: "kk" in a Japanese reading.
        assertEquals("kk", reads("""([kt])\1""", "kt kk"))
        // A part both hold: katakana, but not the long mark.
        assertEquals("アイ", reads("""[\p{sc=Katakana}&&[^ー]]+""", "ーアイー"))
        assertEquals("abbb", reads("""ab{1,3}""", "abbbb"))
        assertEquals("abb", reads("""ab+?b""", "abbb"))
        assertEquals("abb", reads("""ab*+""", "abb"))
        assertEquals("aa", reads("""(?:a)+""", "aa"))
        // Escapes: the letters they stand for, and a word quoted whole.
        assertEquals("\t\n\r\u000C　　-", reads("""\t\n\r\f　\x{3000}\-""", "\t\n\r\u000C　　-"))
        assertEquals("a.b", reads("""\Qa.b\E""", "axb a.b"))
        assertEquals("a.b", reads("""\Qa.b""", "axb a.b"))
        assertEquals("x", reads("""\Ax\z""", "x"))
        assertEquals("x", reads("""\Gx\Z""", "x\n"))
        // Classes: letters, ranges, escapes, sets, the letters they don't hold, and each case of each ignoring case.
        assertEquals("a-　z", reads("""[a\-　]+[x-z]""", "a-　z"))
        assertEquals("b", reads("""[^\sa]""", "　ab"))
        assertEquals("　", reads("""[\S\s]""", "　"))
        assertEquals("K", reads("[k]", "K", RegexOption.IGNORE_CASE))
        assertEquals(null, reads("[^k]", "K", RegexOption.IGNORE_CASE))
        assertEquals("-", reads("""[^\p{L}a]""", "a\u0130-"))
        assertEquals("a", reads("^a", "aa"))
        assertEquals("b", reads("(?m)^b", "b\nb"))
        // A word ignoring case: "ffi" is "ﬃ", and "ss" is "ß", but "s" alone isn't.
        assertEquals("oﬃcial", reads("official", "oﬃcial", RegexOption.IGNORE_CASE))
        assertEquals("oﬀicial", reads("official", "oﬀicial", RegexOption.IGNORE_CASE))
        assertEquals("ß", reads("ss", "ß", RegexOption.IGNORE_CASE))
        assertEquals(null, reads("s", "ß", RegexOption.IGNORE_CASE))
        assertEquals("İ", reads("İ", "İ", RegexOption.IGNORE_CASE))
        assertEquals("x\n", reads("""x\n""", "x\n", RegexOption.IGNORE_CASE))
    }

    @Test
    fun aConstructThePhoneReadsOtherwiseThatIsntWrittenOutThrows() {
        listOf(
            "a)", "(a", "(?x)a", "(?i", "a{2", "a\\", """\p""", """\p{Lu}""", """\h""", "[a", "[[a]]", "[a&&b]", "(?i)[a&&[a]]", "[^a&&[a]]", "[a&&[a]b]", "(?<name>a)", """(?i)(a)\1""",
        ).forEach { pattern -> assertThrows(pattern, IllegalArgumentException::class.java) { phoneRegex(pattern, emptySet(), onIcu = false) } }
        assertThrows(IllegalArgumentException::class.java) { phoneRegex("a", setOf(RegexOption.LITERAL), onIcu = false) }
    }

    /** The code points [pattern] matches whole, each alone, to [last]. */
    private fun matched(pattern: Pattern, last: Int = Character.MAX_CODE_POINT): BitSet {
        val found = BitSet()
        val text = StringBuilder()
        for (code in 0..last) if (code !in Char.MIN_SURROGATE.code..Char.MAX_SURROGATE.code) text.appendCodePoint(code)
        val matcher = pattern.matcher(text)
        var at = 0
        while (at < text.length) {
            val code = Character.codePointAt(text, at)
            val next = at + Character.charCount(code)
            if (matcher.region(at, next).matches()) found.set(code)
            at = next
        }
        for (half in Char.MIN_SURROGATE.code..minOf(last, Char.MAX_SURROGATE.code)) if (pattern.matcher(half.toChar().toString()).matches()) found.set(half)
        return found
    }

    /** The table a phone made, as `scripts/regextable/regextable.py` kept it. */
    private class Table(path: String) {
        private val rows = File(path).readLines().map { it.split('\t') }
        val engine = rows.first { it[0] == "# engine" }[1]
        val classes = rows.filter { it[0] == "class" }.map { it[1] }
        val folds = rows.filter { it[0] == "fold" }.associate { it[1] to bits(it[2]) }
        val ends = rows.filter { it[0] == "ends" }.associate { row -> row[1].split(' ').map { it.toInt(16).toChar() }.joinToString("") to row.drop(2).joinToString("\t") }
        val simpleFolds = rows.first { it[0] == "sfold" }[2].split(',').associate { it.substringBefore('>').toInt(16) to it.substringAfter('>').toInt(16) }
        val longer = rows.filter { it[0] == "multi" }.map { row ->
            Longer(row[1].toInt(16), row[2].split(' ').joinToString("") { String(Character.toChars(it.toInt(16))) }, row[3].toBoolean(), row[4].toBoolean())
        }

        fun codes(name: String) = bits(rows.first { it[0] == "class" && it[1] == name }[2])

        private fun bits(runs: String) = BitSet().apply {
            runs.split(',').filter(String::isNotEmpty).forEach { run -> set(run.substringBefore('-').toInt(16), run.substringAfter('-').toInt(16) + 1) }
        }
    }

    private data class Longer(val code: Int, val fold: String, val foldReadsIt: Boolean, val itReadsFold: Boolean)

    private companion object {
        val TABLE by lazy { Table("src/test/regextable/android-37.tsv") }

        /** Letters at most in a set written for the JVM: about 150 runs of a few letters. */
        const val SHORT_SET = 4_000
    }
}
