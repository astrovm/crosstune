package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The widths are what Chromium measures on J-Total's page, with its own CSS, in thousandths of a
 * column. The letters here were measured by scripts/jtotal-widths; Issue #775 lists the first four.
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
        // Kana is two columns on both stacks, as MS Gothic draws it. Ĳ comes from a different fallback.
        assertEquals(2000, JTotalWidths.of('あ'.code, monospace = true))
        assertEquals(1417, JTotalWidths.of(0x132, monospace = true))
        val kana = "${"x".repeat(41)}${jTotal("C")}<br>${"あ".repeat(22)}"
        assertEquals(21, ChordSheets.fromJTotal(kana).single().chords.single().at)
        assertEquals(21, ChordSheets.fromJTotal(kana, monospace = true).single().chords.single().at)
        // Five columns are nearest the fourth Ĳ's start at 1.470 each (4.41), and the b after four at 1.417 each (5.668).
        val ligatures = "abcde${jTotal("C")}<br>\u0132\u0132\u0132\u0132b"
        assertEquals(3, ChordSheets.fromJTotal(ligatures).single().chords.single().at)
        assertEquals(4, ChordSheets.fromJTotal(ligatures, monospace = true).single().chords.single().at)
    }

    @Test
    fun aLetterInInconsolataIsOneColumnAndOneAbsentFromItIsWhatTheFallbackDraws() {
        assertEquals(1000, JTotalWidths.of(0xC5))
        assertEquals(1470, JTotalWidths.of(0x132))
        assertEquals(888, JTotalWidths.of(0x133))
        assertEquals(1000, JTotalWidths.of(0x134))
        assertEquals(1000, JTotalWidths.of(0x148))
        assertEquals(1209, JTotalWidths.of(0x149))
        assertEquals(1841, JTotalWidths.of(0xFFA0))
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
        assertEquals(2000, JTotalWidths.of('あ'.code))
        assertEquals(2000, JTotalWidths.of(0x20BB7))
        assertEquals(2000, JTotalWidths.of(0x10FFFF))
    }

    @Test
    fun aSoftHyphenAndTheMarksThatAddNothingAreNoColumn() {
        assertEquals(0, JTotalWidths.of(0xAD))
        assertEquals(0, JTotalWidths.of(0x301))
        assertEquals(0, JTotalWidths.of(0x200B))
        assertEquals(0, JTotalWidths.of(0xFF9E))
    }

    @Test
    fun aLigatureOfOnePointFourSevenColumnsPutsTheChordOnTheLetterBeyond() {
        // Ĳ is 1.47 columns: two Latin columns are nearest where b starts (2.47), not a (1.47).
        assertEquals(2, landsOn("ab", "\u0132abc"))
    }

    @Test
    fun aLigatureNarrowerThanAColumnPutsTheChordOnTheLetterBeyond() {
        // ĳ is 0.888, so b starts at 1.888 and the chord's two columns are nearest it.
        assertEquals(2, landsOn("ab", "\u0133abc"))
    }

    @Test
    fun aLetterWithAnApostropheTakesItsWidthWhereAChordIsPlaced() {
        // ŉ is 1.209: two columns are nearer the 2.209 where b starts than the 1.209 where a starts.
        assertEquals(2, landsOn("ab", "\u0149abc"))
    }

    @Test
    fun aHangulFillerTakesItsWidthWhereAChordIsPlaced() {
        // Five of them are 9.205 columns, so ten Latin columns are nearest the sixth letter after.
        assertEquals(6, landsOn("abcdefghij", "\uFFA0\uFFA0\uFFA0\uFFA0\uFFA0abc"))
    }

    @Test
    fun greekTakesWhatTheBrowserGivesIt() {
        // α is 1.25 columns, so four columns reach the fourth letter's start (3.75) and not the third's (2.5).
        assertEquals(3, landsOn("abcd", "αααααb"))
    }

    @Test
    fun cyrillicTakesWhatTheBrowserGivesIt() {
        // а is 1.127 columns, so ten are nearest where the tenth letter starts (10.143), not the sixth (two columns each).
        assertEquals(9, landsOn("abcdefghij", "аааааааааb"))
    }

    @Test
    fun hangulSyllablesTakeWhatTheBrowserGivesThem() {
        // A syllable is 1.841 columns. Five are 9.205, so ten columns are nearest where c starts (10.205), not b (two columns each).
        assertEquals(6, landsOn("abcdefghij", "가가가가가bc"))
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
        // An emoji is 2.5 columns: five columns are over the letter after two.
        assertEquals(4, landsOn("abcde", "😀😀abc"))
    }

    @Test
    fun aChordInTheMiddleOfAWideLetterGoesOverTheNextAndOneNearerTheLetterGoesOverIt() {
        assertEquals(1, landsOn("a", "あいう"))
        // Hangul Filler starts the next letter at 1.841, a little before two columns.
        assertEquals(1, landsOn("ab", "ﾠabc"))
        // ĳ ends at 0.888 and Ĳ at 1.47: one column is nearer the end of ĳ.
        assertEquals(1, landsOn("a", "ĳĲb"))
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
