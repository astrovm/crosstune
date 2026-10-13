package com.astrovm.crosstune

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Runs under Robolectric for the real org.json implementation. */
@RunWith(RobolectricTestRunner::class)
class ChordSheetsTest {

    private fun sheet(vararg lines: SheetLine) = ChordSheet(lines.toList(), site = ChordSite.ULTIMATE_GUITAR, url = "https://tabs.ultimate-guitar.com/tab/1")

    @Test
    fun uFretsChordsAreWhereTheyAreInTheWords() {
        val lines = JSONArray(listOf("[Am]五月[Em]雨は\r", "[C]　[G]\r", "ただの[ ]行", "\r"))
        assertEquals(
            listOf(
                SheetLine("五月雨は", listOf(Chord(0, "Am"), Chord(2, "Em"))),
                SheetLine("　", listOf(Chord(0, "C"), Chord(1, "G"))),
                // Brackets with no chord in them are nothing.
                SheetLine("ただの行", emptyList()),
                SheetLine("", emptyList())
            ),
            ChordSheets.fromUFret(lines)
        )
    }

    @Test
    fun arabicWordsAreLinedUpWithASheetThatWritesThemAgainInLatinLettersUnderEachLine() {
        val texts = listOf("حبيبي يا نور العين يا ساكن خيالي", "عاشق بقالي سنين ولا غيرك في بالي")
        val sheet = sheet(
            SheetLine("حبيبي يا نور العين يا ساكن خيالي", listOf(Chord(0, "Cm"), Chord(15, "Fm"))),
            SheetLine("habibi ya nur aleayn ya sakin khayali", emptyList()),
            SheetLine("عاشق بقالي سنين ولا غيرك في بالي", listOf(Chord(0, "G"))),
            SheetLine("eashiq biqali sinin wala ghyrk fi baly", emptyList())
        )
        val placed = ChordSheets.place(sheet, texts)!!
        assertEquals(listOf("Cm", "Fm"), placed[0].over.map { it.name })
        assertEquals(listOf("G"), placed[1].over.map { it.name })
        // A sheet with only the Latin lines is not this song's words.
        assertNull(ChordSheets.place(sheet(SheetLine("habibi ya nur aleayn ya sakin khayali", listOf(Chord(0, "Cm")))), texts))
    }

    @Test
    fun koreanWordsAreLinedUpWithASheetThatWritesThemInLatinLetters() {
        val texts = listOf("오빤 강남스타일", "강남스타일", "낮에는 따사로운 인간적인 여자", "커피 한 잔의 여유를 아는 품격 있는 여자")
        val read = Readings.of(texts, Script.KOREAN).map { it.romanized }
        val sheet = ChordSheet(
            listOf(
                SheetLine(read[0], listOf(Chord(0, "Am"))),
                SheetLine(read[1], listOf(Chord(0, "E"))),
                SheetLine(read[2], listOf(Chord(0, "F"), Chord(read[2].length / 2, "G"))),
                SheetLine(read[3], listOf(Chord(0, "A")))
            ),
            site = ChordSite.ULTIMATE_GUITAR, url = "https://tabs.ultimate-guitar.com/tab/psy/gangnam-style-chords-1176046"
        )
        val placed = ChordSheets.place(sheet, texts)!!
        assertEquals(listOf("Am"), placed[0].over.map { it.name })
        assertEquals(listOf("F", "G"), placed[2].over.map { it.name })
        // The second chord is about half way along the line, wherever its letters are now.
        assertEquals((texts[2].length / 2).toDouble(), placed[2].over[1].at.toDouble(), 3.0)
        // Words in Latin letters are never read as anything else.
        assertNull(ChordSheets.place(sheet, listOf("Walking down the river road", "Singing to the morning light")))
    }

    @Test
    fun solfegeChordsAreNamedWithLetters() {
        assertEquals("C", ChordSheets.letterName("DO"))
        assertEquals("Am", ChordSheets.letterName("LAm"))
        assertEquals("Dm7*", ChordSheets.letterName("REm7*"))
        assertEquals("F#m", ChordSheets.letterName("FA#m"))
        assertEquals("Bb", ChordSheets.letterName("SIb"))
        assertEquals("G7/B", ChordSheets.letterName("SOL7/SI"))
        // A word that only starts like a chord isn't one.
        for (word in listOf("SOLO", "FADE", "LAST", "MIDI")) assertEquals(word, ChordSheets.letterName(word))
        assertEquals("C/E", ChordSheets.letterName("DO/MI"))
        // Chords in letters stay, even those that begin like a note's name.
        for (name in listOf("C", "Dm", "Fadd9", "Dsus4", "Em7", "A7(sus4)", "Bb", "Gdim")) assertEquals(name, ChordSheets.letterName(name))
    }

    @Test
    fun solfegeChordsInASheetAreChordsInLettersOverTheSameLetters() {
        val sheet = ChordSheets.fromUltimateGuitar("[ch]LAm[/ch]    [ch]SOL[/ch]\nQue yeta tengo")
        assertEquals(listOf(SheetLine("Que yeta tengo", listOf(Chord(0, "Am"), Chord(7, "G")))), sheet)
    }

    @Test
    fun ultimateGuitarsChordsAreOverTheLetterTheyStartOn() {
        val content = "[Intro]\r\n[ch]G[/ch]  [ch]D[/ch]\r\n\r\n[Verse 1]\r\n[tab][ch]G[/ch]       [ch]C/B[/ch]\r\n  Walking down the road[/tab]\r\n" +
            "[tab]                          [ch]D[/ch]\r\nSinging[/tab]\r\nJust words here   \r\n[ch]Em[/ch]\r\n[Chorus]\r\n[ch]A[/ch]\r\n[ch]B[/ch]"
        assertEquals(
            listOf(
                SheetLine("", listOf(Chord(0, "G"), Chord(3, "D"))),
                SheetLine("  Walking down the road", listOf(Chord(0, "G"), Chord(8, "C/B"))),
                // Past the end of its words, it's played after them.
                SheetLine("Singing", listOf(Chord(7, "D"))),
                SheetLine("Just words here", emptyList()),
                // With a heading, more chords or nothing under them, chords are played on their own.
                SheetLine("", listOf(Chord(0, "Em"))),
                SheetLine("", listOf(Chord(0, "A"))),
                SheetLine("", listOf(Chord(0, "B")))
            ),
            ChordSheets.fromUltimateGuitar(content)
        )
    }

    @Test
    fun eachChordLandsOnTheLetterItsPlayedOnInTheLinesShown() {
        val sheet = sheet(
            SheetLine("", listOf(Chord(0, "G"), Chord(3, "D"))),
            // The sheet spells it "Walking", the words shown "Walkin".
            SheetLine("Walking down the river road", listOf(Chord(0, "G"), Chord(8, "C"))),
            SheetLine("", listOf(Chord(0, "Bm"))),
            SheetLine("Singing to the morning light", listOf(Chord(28, "D"))),
            // A verse the song doesn't have.
            SheetLine("Xylophones quack jazzily", listOf(Chord(0, "Em"))),
            SheetLine("", listOf(Chord(0, "Am"))),
            SheetLine("Coming home tonight", listOf(Chord(7, "G"), Chord(0, "F")))
        )
        val texts = listOf("Walkin down the river road", "Singing to the morning light", "", "Coming home tonight")
        assertEquals(
            listOf(
                // The intro's chords before the first line.
                LineChords(listOf("G", "D"), listOf(Chord(0, "G"), Chord(7, "C"))),
                LineChords(listOf("Bm"), listOf(Chord(28, "D"))),
                // Those played in a gap the words show, in it.
                LineChords(listOf("Am")),
                LineChords(over = listOf(Chord(0, "F"), Chord(7, "G")))
            ),
            ChordSheets.place(sheet, texts)
        )
        // A line the sheet splits in two: chords played between the halves go before the line, not into the gap filled already.
        val split = sheet(
            SheetLine("Walk down the river road", emptyList()),
            SheetLine("", listOf(Chord(0, "Am"))),
            SheetLine("Singing to the", emptyList()),
            SheetLine("", listOf(Chord(0, "F"))),
            SheetLine("morning light", listOf(Chord(0, "C")))
        )
        assertEquals(
            listOf(LineChords(), LineChords(listOf("Am")), LineChords(listOf("F"), listOf(Chord(15, "C")))),
            ChordSheets.place(split, listOf("Walk down the river road", "", "Singing to the morning light"))
        )
        assertEquals(true, LineChords().isEmpty)
        assertEquals(false, LineChords(listOf("Am")).isEmpty)
        assertEquals(false, LineChords(over = listOf(Chord(0, "Am"))).isEmpty)
    }

    @Test
    fun aChordOnALetterThatDoesntLineUpGoesByTheNearestThatDoes() {
        val sheet = sheet(SheetLine("Walking home again tonight", listOf(Chord(6, "A7"))))
        // "g" isn't in "Walkin": the chord goes one letter on from the "n" before it.
        assertEquals(listOf(LineChords(over = listOf(Chord(7, "A7")))), ChordSheets.place(sheet, listOf("Walkin home again tonight")))
        // Full-width letters are the same letters.
        val wide = sheet(SheetLine("Ｗａｌｋｉｎｇ ｈｏｍｅ ａｇａｉｎ", listOf(Chord(8, "C"))))
        assertEquals(listOf(LineChords(over = listOf(Chord(8, "C")))), ChordSheets.place(wide, listOf("walking home again")))
        // Letters the words shown don't have at the end of a line: its chord stays on that line, on its last letter.
        val extra = sheet(SheetLine("Walking home xyzqv", listOf(Chord(17, "G"))))
        assertEquals(
            listOf(LineChords(over = listOf(Chord(11, "G"))), LineChords()),
            ChordSheets.place(extra, listOf("Walking home", "Big fat cat"))
        )
    }

    @Test
    fun aSheetForOtherWordsIsntPlaced() {
        val sheet = sheet(SheetLine("Walking down the river road", listOf(Chord(0, "G"))))
        assertNull(ChordSheets.place(sheet, listOf("Nothing like it at all, honestly")))
        // Ten letters are enough to tell.
        assertEquals(listOf(LineChords(over = listOf(Chord(0, "G")))), ChordSheets.place(sheet(SheetLine("Hello world", listOf(Chord(0, "G")))), listOf("Hello world")))
        // Too few letters to tell, no words shown, or too many to line up.
        assertNull(ChordSheets.place(sheet(SheetLine("Hi you", listOf(Chord(0, "G")))), listOf("Hi you")))
        assertNull(ChordSheets.place(sheet, emptyList()))
        val long = "ab".repeat(2_000)
        assertNull(ChordSheets.place(sheet(SheetLine(long, listOf(Chord(0, "G")))), listOf(long)))
    }

    @Test
    fun aChordIsPlayedWhenItsLetterIsSung() {
        val byWord = LyricLine(10_000, "Two words", listOf(TimedWord(10_000, "Two "), TimedWord(11_000, "words")))
        assertEquals(10_000L, ChordSheets.timeOf(byWord, 0, 13_000))
        assertEquals(11_000L, ChordSheets.timeOf(byWord, 4, 13_000))
        // Two letters into the last word, two fifths of the way to the next line.
        assertEquals(11_800L, ChordSheets.timeOf(byWord, 6, 13_000))
        assertEquals(13_000L, ChordSheets.timeOf(byWord, 99, 13_000))
        // Past the words timed, as for a mark after the last one: at the end of the last word.
        assertEquals(13_000L, ChordSheets.timeOf(byWord.copy(text = "Two words!"), 9, 13_000))
        // A line timed as a whole: that far through it, up to the next line, or a moment a letter.
        val whole = LyricLine(10_000, "abcd")
        assertEquals(12_000L, ChordSheets.timeOf(whole, 2, 14_000))
        assertEquals(10_300L, ChordSheets.timeOf(whole, 2, null))
        assertEquals(10_000L, ChordSheets.timeOf(LyricLine(10_000, ""), 0, null))

        val chords = listOf(Chord(0, "G"), Chord(4, "D"))
        assertEquals(-1, ChordSheets.playing(byWord, chords, 13_000, 9_000))
        assertEquals(0, ChordSheets.playing(byWord, chords, 13_000, 10_000))
        assertEquals(0, ChordSheets.playing(byWord, chords, 13_000, 10_500))
        assertEquals(1, ChordSheets.playing(byWord, chords, 13_000, 11_500))
    }
}
