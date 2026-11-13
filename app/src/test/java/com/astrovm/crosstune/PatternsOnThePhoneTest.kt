package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Each of the app's patterns finds in [TitleFuzz]'s titles here, written out by [phoneRegex], what it found on an
 * emulator, where `Regex` is ICU as on a phone. `scripts/regextable/regextable.py patterns` measured those there, on
 * the newest Android and on the oldest the app runs on, Android 8.
 */
class PatternsOnThePhoneTest {
    @Test
    fun everyPatternFindsWhatItFindsOnThePhone() {
        val lines = File(KEPT).readLines()
        assertEquals(lines.first(), true, lines.first().startsWith("# engine\tICU "))
        val phone = lines.drop(1).associate { it.substringBefore('\t') to it.substringAfter('\t') }
        val titles = PatternsProbe.titles()
        val found = AppPatterns.ofApp().found
        assertEquals(true, found.size > 100)
        val wrong = found.mapNotNull { pattern ->
            val options = PatternsProbe.options(pattern.pattern.flags())
            val text = pattern.pattern.pattern()
            when (val there = phone[PatternsProbe.key(text, options)]) {
                null -> "${pattern.where}: not measured on the phone, run regextable.py patterns"
                PatternsProbe.digest(phoneRegex(text, options, onIcu = false), titles) -> null
                else -> "${pattern.where}: finds otherwise than on the phone ($there)"
            }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun everyPatternIsReadOnTheOldestAndroid() {
        // Android 8's ICU throws on a script named as "\p{IsHan}", where "\p{sc=Han}" reads on every version.
        val throwing = File(OLDEST).readLines().drop(1).filter { it.endsWith("\t${PatternsProbe.THROWS}") }
        assertEquals(emptyList<String>(), throwing)
        val named = AppPatterns.ofApp().found.filter { SCRIPT_BY_IS.containsMatchIn(it.pattern.pattern()) }.map { it.where }
        assertEquals(emptyList<String>(), named)
    }

    @Test
    fun aPatternIsKeptWithItsOptionsAsItIs() {
        val line = PatternsProbe.line("A.kt:1", "a\\\tb\nc\r", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
        assertEquals("a\\\tb\nc\r" to setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE), PatternsProbe.read(line))
        assertEquals("a" to emptySet<RegexOption>(), PatternsProbe.read(PatternsProbe.line("A.kt:1", "a", emptySet())))
    }

    private companion object {
        const val KEPT = "src/test/regextable/patterns-android-37.tsv"
        const val OLDEST = "src/test/regextable/patterns-android-26.tsv"
        val SCRIPT_BY_IS = Regex("""\\[pP]\{Is""")
    }
}
