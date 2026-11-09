package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How a sheet lines up with the words shown, at the edges: the letters it needs, the share of them
 * that must line up, and which chords a line that mostly doesn't keeps. The letters are made up so
 * each count can be read off the strings.
 */
class ChordAlignTest {
    private fun sheet(vararg lines: SheetLine) = ChordSheet(lines.toList(), site = ChordSite.ULTIMATE_GUITAR, url = "https://tabs.ultimate-guitar.com/tab/1")

    private val first = "abcdefghij"
    private val second = "klmnopqrst"
    private val third = "uvwxyzabcd"

    @Test
    fun aSheetNeedsSixTenthsOfItsLettersAndFourTenthsInRunsAndGetsThemAtExactlyThat() {
        // 6 of 10 letters line up, in 4 pairs that follow each other. Both are exactly what's asked.
        val aligned = ChordSheets.align(sheet(SheetLine("abcdexxxxj", listOf(Chord(0, "C")))), listOf(first))
        assertEquals(listOf(LineChords(over = listOf(Chord(0, "C")))), aligned.placed)
        assertEquals(6, aligned.matchedLetters)
        assertEquals(10, aligned.letters)
        assertEquals(4, aligned.runs)
        val line = aligned.lines.single()
        assertEquals(listOf("C"), line.chords)
        assertEquals(10, line.letters)
        assertEquals(6, line.matched)
        assertEquals(listOf("C"), line.drawn)
    }

    @Test
    fun aSheetWithOneLetterLessOfEitherIsAnotherSong() {
        // 5 of 10 line up, in 4 pairs.
        val fewer = ChordSheets.align(sheet(SheetLine("abcdexxxxx", listOf(Chord(0, "C")))), listOf(first))
        assertNull(fewer.placed)
        assertEquals(5, fewer.matchedLetters)
        assertEquals(4, fewer.runs)
        assertEquals(emptyList<String>(), fewer.lines.single().drawn)
        // 6 of 10 line up, in 3 pairs.
        val apart = ChordSheets.align(sheet(SheetLine("abcxdexxxj", listOf(Chord(0, "C")))), listOf(first))
        assertNull(apart.placed)
        assertEquals(3, apart.runs)
    }

    @Test
    fun aSheetOfTenLettersIsReadAndOneOfNineIsNot() {
        assertNotNull(ChordSheets.align(sheet(SheetLine(first, listOf(Chord(0, "C")))), listOf(first)).placed)
        val short = ChordSheets.align(sheet(SheetLine(first.dropLast(1), listOf(Chord(0, "C")))), listOf(first))
        assertNull(short.placed)
        // Nothing was compared, so the log says how few letters there were and that none lined up.
        assertEquals(0, short.matchedLetters)
        assertEquals(9, short.letters)
        assertEquals(0, short.runs)
        val line = short.lines.single()
        assertEquals(listOf("C"), line.chords)
        assertEquals(0, line.letters)
        assertEquals(0, line.matched)
        assertEquals(emptyList<String>(), line.drawn)
    }

    @Test
    fun aLineWithHalfItsLettersLinedUpKeepsAllItsChords() {
        val aligned = ChordSheets.align(
            sheet(SheetLine(first, listOf(Chord(0, "G"))), SheetLine("klmnoxxxxx", listOf(Chord(7, "D")))),
            listOf(first, second)
        )
        assertEquals(15, aligned.matchedLetters)
        assertEquals(20, aligned.letters)
        assertEquals(14, aligned.runs)
        // The D is on a letter that didn't line up. The line is half this song, which is enough to take it whole.
        assertEquals(listOf("D"), aligned.lines[1].drawn)
        assertEquals(10, aligned.lines[1].letters)
        assertEquals(5, aligned.lines[1].matched)
        assertEquals(listOf(Chord(0, "G")), aligned.placed!![0].over)
        assertEquals(1, aligned.placed[1].over.size)
        assertEquals("D", aligned.placed[1].over.single().name)
    }

    @Test
    fun aLongLineWithLessThanHalfItsLettersLinedUpKeepsOnlyTheChordsPlayedOnThem() {
        val aligned = ChordSheets.align(
            sheet(SheetLine(first, listOf(Chord(0, "G"))), SheetLine("klmnxxxxxx", listOf(Chord(1, "A"), Chord(6, "B")))),
            listOf(first, second)
        )
        assertEquals(4, aligned.lines[1].matched)
        // Ten letters is long enough to be another verse, so the chord on a letter that didn't line up is left off, and the other stays on its own line.
        assertEquals(listOf("A"), aligned.lines[1].drawn)
        assertEquals(listOf("A", "B"), aligned.lines[1].chords)
        assertEquals(listOf(Chord(1, "A")), aligned.placed!![1].over)
        assertEquals(emptyList<String>(), aligned.placed[1].before)
    }

    @Test
    fun aChordPlayedJustBeforeALetterThatLinedUpIsStillOnOneThatDidNot() {
        val aligned = ChordSheets.align(
            sheet(SheetLine(first, listOf(Chord(0, "G"))), SheetLine("xklmxxxxxx", listOf(Chord(0, "B"), Chord(1, "K")))),
            listOf(first, second)
        )
        assertEquals(3, aligned.lines[1].matched)
        assertEquals(listOf("K"), aligned.lines[1].drawn)
        assertEquals(listOf(Chord(0, "K")), aligned.placed!![1].over)
    }

    @Test
    fun aLongLineWhoseOnlyLetterThatLinedUpIsTheVeryFirstStillKeepsItsChord() {
        val aligned = ChordSheets.align(
            sheet(SheetLine("azzzzzzzzz", listOf(Chord(0, "X"))), SheetLine(second, listOf(Chord(0, "Y"))), SheetLine(third, listOf(Chord(0, "Z")))),
            listOf(first, second, third)
        )
        assertEquals(1, aligned.lines[0].matched)
        assertEquals(listOf("X"), aligned.lines[0].drawn)
        assertEquals(listOf(Chord(0, "X")), aligned.placed!![0].over)
        assertEquals(listOf(Chord(0, "Y")), aligned.placed[1].over)
        assertEquals(listOf(Chord(0, "Z")), aligned.placed[2].over)
    }

    @Test
    fun aChordLineWithNoWordsWaitsForTheNextLineAndTakesTheBlankLineAboveIt() {
        val aligned = ChordSheets.align(
            sheet(
                SheetLine("", listOf(Chord(0, "A"))),
                SheetLine(first, emptyList()),
                SheetLine("", listOf(Chord(0, "B"))),
                SheetLine(second, listOf(Chord(0, "C")))
            ),
            listOf("", first, "", second)
        )
        // Each is above the blank line it was written over. A chord that had landed doesn't land again for the next.
        assertEquals(listOf(listOf("A"), emptyList(), listOf("B"), listOf("C")), aligned.lines.map { it.drawn })
        assertEquals(listOf(listOf("A"), emptyList(), listOf("B"), listOf("C")), aligned.lines.map { it.chords })
        val placed = aligned.placed!!
        assertEquals(listOf("A"), placed[0].before)
        assertEquals(emptyList<String>(), placed[1].before)
        assertEquals(listOf("B"), placed[2].before)
        assertEquals(listOf(Chord(0, "C")), placed[3].over)
    }

    @Test
    fun aChordLineAtTheTopWaitsAboveTheFirstLineWhenNoBlankLineIsThere() {
        val aligned = ChordSheets.align(
            sheet(SheetLine("", listOf(Chord(0, "A"))), SheetLine(first, listOf(Chord(0, "G")))),
            listOf(first)
        )
        // Line 0 is the first line shown, and there is nothing above it to take the chord.
        assertEquals(listOf("A"), aligned.placed!![0].before)
        assertEquals(listOf(Chord(0, "G")), aligned.placed[0].over)
        assertEquals(listOf(listOf("A"), listOf("G")), aligned.lines.map { it.drawn })
    }

    @Test
    fun aChordLineAfterTheLastLineIsPlayedAtTheEndOfTheLastLineWithWords() {
        val aligned = ChordSheets.align(
            sheet(SheetLine(first, listOf(Chord(0, "G"))), SheetLine(second, emptyList()), SheetLine("", listOf(Chord(0, "E")))),
            listOf(first, second, "")
        )
        assertEquals(listOf(Chord(10, "E")), aligned.placed!![1].over)
        assertEquals(emptyList<Chord>(), aligned.placed[2].over)
        assertEquals(listOf(listOf("G"), emptyList(), listOf("E")), aligned.lines.map { it.drawn })
    }

    @Test
    fun aChordLineAfterTheLastLineIsPlayedAtTheEndOfAllTheLinesWhenTheLastOnesAreAllWords() {
        val aligned = ChordSheets.align(
            sheet(SheetLine(first, listOf(Chord(0, "G"))), SheetLine(second, emptyList()), SheetLine("", listOf(Chord(0, "E")))),
            listOf(first, second)
        )
        assertEquals(listOf(Chord(10, "E")), aligned.placed!![1].over)
        assertEquals(listOf("E"), aligned.lines[2].drawn)
    }

    @Test
    fun aShortLineThatMostlyDidNotLineUpWaitsForTheNextOne() {
        val aligned = ChordSheets.align(
            sheet(SheetLine(first, listOf(Chord(0, "G"))), SheetLine("xyz", listOf(Chord(0, "N"))), SheetLine(second, listOf(Chord(0, "C")))),
            listOf(first, second)
        )
        // Nine letters is not enough to be another verse, so the N is held for the line after it.
        assertEquals(listOf("N"), aligned.placed!![1].before)
        assertEquals(listOf(listOf("G"), listOf("N"), listOf("C")), aligned.lines.map { it.drawn })
    }
}
