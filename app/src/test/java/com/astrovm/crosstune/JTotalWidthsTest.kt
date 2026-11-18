package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * The widths are what Chromium measures on J-Total's page on Windows, with its own CSS, in thousandths
 * of a column. The letters here were measured, and their lines drawn, by scripts/jtotal-widths.
 */
class JTotalWidthsTest {
    private fun jTotal(name: String) = """<a href="JavaScript:jump_1('../../../cd/$name.html');">$name</a>"""

    /** The index of the letter of [words] a chord lands on when [over] is set over it, one column a letter. */
    private fun landsOn(over: String, words: String): Int =
        ChordSheets.fromJTotal("$over${jTotal("C")}<br>$words").single().chords.single().at

    @Test
    fun anAsciiLetterIsOneColumn() {
        assertEquals(1000, JTotalWidths.of('a'.code))
        assertEquals(1000, JTotalWidths.of(0x7F))
        assertEquals(1000, JTotalWidths.of('a'.code, monospace = true))
    }

    @Test
    fun theMonospaceStackHasItsOwnWidths() {
        // MS Gothic draws common full-width kana at 16 px on each page. A digit takes 7.5 px.
        assertEquals(2133, JTotalWidths.of('あ'.code))
        assertEquals(2133, JTotalWidths.of('あ'.code, monospace = true))
        assertEquals(1144, JTotalWidths.of(0x6F0))
        assertEquals(1200, JTotalWidths.of(0x6F0, monospace = true))
        val kana = "${"x".repeat(41)}${jTotal("C")}<br>${"あ".repeat(22)}"
        assertEquals(19, ChordSheets.fromJTotal(kana).single().chords.single().at)
        assertEquals(19, ChordSheets.fromJTotal(kana, monospace = true).single().chords.single().at)
    }

    @Test
    fun shiftJisLettersUseTheMeasuredOneAndTwoCellWidths() {
        // Inconsolata and other fallbacks usually draw each cell at 7.5 px, MS Gothic at 8 px.
        val measured = setOf(1000, 1067, 2000, 2133)
        val shiftJis = Charset.forName("windows-31j").newEncoder()
        for (code in 0x80..0xFFFF) {
            if (Character.isSurrogate(code.toChar()) || !shiftJis.canEncode(code.toChar())) continue
            for (monospace in listOf(false, true)) {
                val width = JTotalWidths.of(code, monospace)
                assertTrue("U+%04X is %d".format(code, width), width in measured)
            }
        }
    }

    @Test
    fun aLetterInInconsolataIsOneColumnAndOneAbsentFromItIsWhatTheFallbackDraws() {
        assertEquals(1000, JTotalWidths.of(0xC5))
        assertEquals(1067, JTotalWidths.of(0x132))
        assertEquals(1000, JTotalWidths.of(0x134))
        assertEquals(1557, JTotalWidths.of(0x531))
        assertEquals(851, JTotalWidths.of(0x10D0))
        assertEquals(2746, JTotalWidths.of(0x1F600))
    }

    @Test
    fun theControlsBetweenAsciiAndTheFirstRunAreNotListed() {
        // U+0080 is the first code point the table could list, and it lists none up to U+009F.
        assertEquals(2000, JTotalWidths.of(0x80))
        assertEquals(2000, JTotalWidths.of(0x9F))
        // The first run starts right after, and a run counts to its last code point.
        assertEquals(1000, JTotalWidths.of(0xA0))
        assertEquals(1000, JTotalWidths.of(0xAC))
        assertEquals(0, JTotalWidths.of(0xAD))
    }

    @Test
    fun aLetterThatIsNotListedIsTwoColumns() {
        // Between runs, and past the last one.
        assertEquals(2000, JTotalWidths.of(0x20BB7))
        assertEquals(2000, JTotalWidths.of(0x10FFFF))
    }

    @Test
    fun aSoftHyphenAndTheMarksThatAddNothingAreNoColumn() {
        assertEquals(0, JTotalWidths.of(0xAD))
        assertEquals(0, JTotalWidths.of(0x301))
        assertEquals(0, JTotalWidths.of(0x200B))
    }

    @Test
    fun aHalfwidthSoundMarkMovesOnByEightPixels() {
        // It draws over the kana before it, and still moves the line on by 8 px.
        assertEquals(1067, JTotalWidths.of(0xFF9E))
        assertEquals(1067, JTotalWidths.of(0xFF9F, monospace = true))
    }

    @Test
    fun aLetterWiderThanAColumnPutsTheChordWhereItsWidthSays() {
        // Ա is 1.557 columns: four columns are nearer where the fourth letter starts (4.671) than the third (3.114).
        assertEquals(3, landsOn("abcd", "ԱԱԱab"))
    }

    @Test
    fun aLetterNarrowerThanAColumnPutsTheChordWhereItsWidthSays() {
        // ა is 0.851: five of them are 4.255 columns, so four columns are over the a after them.
        assertEquals(5, landsOn("abcd", "აააააab"))
    }

    @Test
    fun fallbackLatinAndHangulWidthsPutTheChordOnTheDrawnLetter() {
        // Ĳ, ĳ, ŉ and the half-width Hangul filler: at page size, four columns are over the a after them.
        assertEquals(4, landsOn("abcd", "Ĳĳŉﾠab"))
    }

    @Test
    fun greekCyrillicAndHangulUseTheirWindowsWidths() {
        assertEquals(2, landsOn("abcd", "αααααb"))
        assertEquals(5, landsOn("abcdefghij", "аааааааааb"))
        assertEquals(5, landsOn("abcdefghij", "가가가가가bc"))
    }

    @Test
    fun aSoftHyphenIsNoColumnAndTheLetterAfterItStartsWhereItDoes() {
        // a is one column and the hyphen none, so b and c start at 1 and 2: two columns are over c, not the hyphen.
        assertEquals(3, landsOn("ab", "a­bc"))
    }

    @Test
    fun aLetterOutsideTheBasicMultilingualPlaneIsOneLetter() {
        // 𠮷 is two columns, a pair of surrogates. Four columns are over the letter after two of them.
        assertEquals(4, landsOn("abcd", "𠮷𠮷abc"))
        // An emoji is 2.746 columns: five columns are nearer the letter after two (5.492) than the second (2.746).
        assertEquals(4, landsOn("abcde", "😀😀abc"))
    }

    @Test
    fun aChordInTheMiddleOfAWideLetterGoesOverTheNextAndOneNearerTheLetterGoesOverIt() {
        // A column is short of the midpoint of a kana. Two columns are near the next kana.
        assertEquals(0, landsOn("a", "あいう"))
        assertEquals(1, landsOn("ab", "あいう"))
        // Exactly halfway across a glyph of two columns, the chord goes on the next letter.
        assertEquals(2, landsOn("a", "𠮷b"))
        // Two columns are nearer where the second Ա starts (1.557) than where b does (3.114).
        assertEquals(1, landsOn("ab", "ԱԱb"))
        // One column is nearer where the second ა starts (0.851) than where b does (1.702).
        assertEquals(1, landsOn("a", "ააb"))
    }

    @Test
    fun letterAtTheStartOfTheWordsKeepsTheFirstOfLettersThatStartTogether() {
        // The mark has no width, so it and the letter after it start in the same place. The chord goes on the first.
        assertEquals(1, landsOn("a", "áb"))
        assertEquals(0, landsOn("", "́ab"))
    }

    @Test
    fun aChordPastTheWordsEndGoesAtTheEnd() {
        assertEquals(3, landsOn("abcdef", "あĲb"))
    }
}
