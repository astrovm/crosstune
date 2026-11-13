package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.BitSet

/**
 * [IcuPattern] on its own, each pattern written out afresh: what it refuses to write, a part of a class both hold, and
 * the JVM's own sets it writes ICU's from. The app's patterns are written once, when their reader starts.
 */
class PhoneRegexTest {
    @Test
    fun aGroupThatDoesNotCloseOrAFlagThatIsNotWrittenOutIsRefused() {
        for (pattern in listOf("(a", "(?:a", "(?=a", "(?i", "(?x)a", "a)", "[a", "[[a]]")) {
            assertThrows(pattern, IllegalArgumentException::class.java) { IcuPattern(pattern, emptySet()) }
        }
        assertEquals("The flag x in (?x)a isn't written out", assertThrows(IllegalArgumentException::class.java) { IcuPattern("(?x)a", emptySet()) }.message)
    }

    @Test
    fun aPartOfAClassBothHoldIsReadAsBothHoldIt() {
        assertEquals("acd", phoneRegex("[a-d&&[^b]]", emptySet(), onIcu = false).findAll("abcd").joinToString("") { it.value })
        // What follows the class is read too.
        assertEquals(listOf(true, false), listOf("cx", "bx").map { phoneRegex("[a-d&&[^b]]x", emptySet(), onIcu = false).matches(it) })
        // Ignoring case, or turned with ^, ICU folds or turns after taking it, which isn't written out.
        for ((pattern, options) in listOf("[a-d&&[^b]]" to setOf(RegexOption.IGNORE_CASE), "[^a-d&&[^b]]" to emptySet())) {
            assertThrows(pattern, IllegalArgumentException::class.java) { IcuPattern(pattern, options) }
        }
    }

    @Test
    fun theJvmsSetIsEachCodePointTheJvmPutsInIt() {
        val digits = BitSet().apply { for (code in 0..Character.MAX_CODE_POINT) if (Character.isDigit(code)) set(code) }
        assertEquals(digits, jvmCodesNow("""\d"""))
        // A half of a surrogate pair is read alone, as a title can have one.
        assertEquals(BitSet().apply { set(Char.MIN_SURROGATE.code, Char.MAX_SURROGATE.code + 1) }, jvmCodesNow("""\p{Cs}"""))
    }

    @Test
    fun aLookBehindStepsOverALetterPastTheBasicPlaneWhole() {
        // On a phone, as measured on the Android 17 emulator: the guest after "𝟗" stays, as after "9", and goes after "😀".
        val guest = phoneRegex("""(?<!\d)\s+ft""", emptySet(), onIcu = false)
        assertEquals(listOf(false, false, true, true), listOf("Song 9 ft", "Song \uD835\uDFD7 ft", "Song \uD83D\uDE00 ft", "Song \uDFD7 ft").map { guest.containsMatchIn(it) })
        // Two letters behind, each two chars, and one letter that's one char before one that's two.
        val digits = phoneRegex("""(?<=\d{2})x""", emptySet(), onIcu = false)
        assertEquals(listOf(true, true, false), listOf("\uD835\uDFD7\uD835\uDFD7x", "9\uD835\uDFD7x", "a\uD835\uDFD7x").map { digits.containsMatchIn(it) })
        // A letter past the basic plane named in the look itself.
        assertEquals(true, phoneRegex("""(?<=\x{1d7d7})x""", emptySet(), onIcu = false).containsMatchIn("\uD835\uDFD7x"))
    }

    @Test
    fun aGroupInALookBehindKeepsTheNumbersOfThoseAfterIt() {
        val found = phoneRegex("""(?<=(\d)(?:-))x(y)""", emptySet(), onIcu = false).find("1-xy")
        assertEquals(listOf("xy", "1", "y"), found?.groupValues)
    }

    @Test
    fun aTableIsReadRunByRunAndAnEmptyOneHoldsNothing() {
        val runs = CodeRuns("9-d,20")
        assertEquals(listOf(false, true, true, false, true, false), listOf(8, 9, 0xd, 0xe, 0x20, 0x21).map { it in runs })
        // regextable.py writes a set no letter is in as "".
        assertEquals(BitSet(), CodeRuns("").bits())
    }
}
