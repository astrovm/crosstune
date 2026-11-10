package com.astrovm.crosstune

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun theSongsWordsWithNoChordsAreNotItsChords() {
        // Cifra Club gave Silverchair's Dearest Helpless as its words alone, and the song showed chords found with none.
        val texts = listOf("Walking down the river road", "Singing to the morning light")
        val words = sheet(SheetLine("Walking down the river road", emptyList()), SheetLine("Singing to the morning light", emptyList()))
        assertNull(ChordSheets.place(words, texts))
        // Chords only on a verse the song doesn't have are none of its chords either.
        val elsewhere = sheet(*words.lines.toTypedArray(), SheetLine("Xylophones quack jazzily all night", listOf(Chord(0, "Em"))))
        assertNull(ChordSheets.place(elsewhere, texts))
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
        // Flat extensions and altered chords too.
        assertEquals("Am7b5", ChordSheets.letterName("LAm7b5"))
        assertEquals("G7b9", ChordSheets.letterName("SOL7b9"))
        assertEquals("Dm7(b5)", ChordSheets.letterName("REm7(b5)"))
        assertEquals("A7alt", ChordSheets.letterName("LA7alt"))
        assertEquals("Ebmaj7", ChordSheets.letterName("MIbmaj7"))
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
    fun aSheetOfAnotherSongWhoseLettersHideInLongWordsIsntPlaced() {
        val words = listOf(
            "Rolling through the city with the windows down", "Money on my mind and my team all around",
            "Told my mama that I made it out the trenches", "Now we pulling up in foreign cars and benches",
            "Every night we in the studio cooking", "Haters on the side but they only looking",
            "I been grinding since the sun came up today", "Nothing gonna stop me get out of my way",
            "Diamonds on my neck and my wrist so cold", "Started from the bottom now the story told",
            "Pull up to the party with the gang in tow", "Everybody know that we running the show",
            "Late night thinking bout the days gone by", "Keep it real with me and never tell a lie"
        )
        // Most of its letters can be found in these, one here, one there, but hardly two in a row.
        val other = sheet(SheetLine("Carry me home by the river tonight", listOf(Chord(0, "G"))), SheetLine("Sing to me softly till the morning light", listOf(Chord(0, "C"))))
        assertNull(ChordSheets.place(other, words))
        // The song's own lines are placed, a word left out or put differently.
        val own = sheet(SheetLine("Told my mama that I made it out the trench", listOf(Chord(0, "Am"))), SheetLine("Now we pull up in foreign cars and benches", listOf(Chord(0, "F"))))
        assertEquals(listOf(Chord(0, "Am")), ChordSheets.place(own, words)!![2].over)
    }

    @Test
    fun aSheetWithTheSongTwiceOrMoreThanTheWordsShownIsStillTheSongs() {
        val words = listOf("Walking down the river road", "Singing to the morning light")
        val once = listOf(SheetLine("Walking down the river road", listOf(Chord(0, "G"))), SheetLine("Singing to the morning light", listOf(Chord(0, "D"))))
        // Once in standard tuning, once half a step down, with a note on each.
        val twice = sheet(
            *(listOf(SheetLine("Tuned to standard, as was asked for in the comments", emptyList())) + once +
                SheetLine("Below, the same tuned half a step down, as the record is", emptyList()) + once).toTypedArray()
        )
        assertEquals(listOf(Chord(0, "G")), ChordSheets.place(twice, words)!![0].over)
        // The words shown are only the first lines of a longer song.
        val longer = sheet(*(once + SheetLine("Carry me home by the water tonight", listOf(Chord(0, "C"))) + SheetLine("Under the stars where the lanterns are bright", listOf(Chord(0, "Am")))).toTypedArray())
        assertEquals(listOf(Chord(0, "D")), ChordSheets.place(longer, words)!![1].over)
    }

    @Test
    fun aSheetWithJustEnoughOfItsLettersTheSongsIsPlaced() {
        // Six letters of ten line up: just the share that's enough.
        assertNotNull(ChordSheets.place(sheet(SheetLine("Walker roam", listOf(Chord(0, "Em")))), listOf("Walker sing")))
    }

    @Test
    fun aLongSheetOfAnotherSongIsntPlacedOverAFewLinesWhoseLettersHideInIt() {
        val rap = listOf(
            "Rolling through the city with the windows down", "Money on my mind and my team all around",
            "Told my mama that I made it out the trenches", "Now we pulling up in foreign cars and benches",
            "Every night we in the studio cooking", "Haters on the side but they only looking",
            "I been grinding since the sun came up today", "Nothing gonna stop me get out of my way",
            "Diamonds on my neck and my wrist so cold", "Started from the bottom now the story told",
            "Pull up to the party with the gang in tow", "Everybody know that we running the show",
            "Late night thinking bout the days gone by", "Keep it real with me and never tell a lie"
        )
        val sheet = sheet(*rap.map { SheetLine(it, listOf(Chord(0, "Em"))) }.toTypedArray())
        assertNull(ChordSheets.place(sheet, listOf("Carry me home by the river tonight", "Sing to me softly till the morning light")))
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

    @Test
    fun wordsInJapaneseAgainstASheetOfAnotherSongAreNotPlacedAndPunctuationAloneIsKept() {
        val texts = listOf("君が好きだと言えないまま", "…", "夜空に星が流れていく")
        val other = sheet(SheetLine("Walking down the river road", listOf(Chord(0, "G"))), SheetLine("Singing to the morning light", listOf(Chord(0, "D"))))
        assertNull(ChordSheets.place(other, texts))
        // Words in no script at all are not read in Latin letters either.
        assertNull(ChordSheets.place(other, listOf("12345", "67890", "…", "!!!!!!")))
    }

    @Test
    fun wordsInFullWidthLettersLineUpWithASheetInPlainOnes() {
        val sheet = sheet(SheetLine("I love you so much tonight", listOf(Chord(0, "Am"), Chord(7, "G"))))
        val placed = ChordSheets.place(sheet, listOf("Ｉ　ｌｏｖｅ　ｙｏｕ　ｓｏ　ｍｕｃｈ　ｔｏｎｉｇｈｔ"))!!
        assertEquals(listOf("Am", "G"), placed.single().over.map { it.name })
    }

    @Test
    fun chordsMoveOntoTheLineInKanaOverTheReadingOfWhatTheyWereOver() {
        val parts = listOf(Ruby("夜空", "よぞら"), Ruby("の"), Ruby("星", "ほし"), Ruby("を"))
        // Over 夜, 空, の, を and past the end: the first two over よぞら's start, the rest where their letters went.
        val over = listOf(Chord(0, "Am"), Chord(1, "F"), Chord(2, "C"), Chord(4, "G"), Chord(5, "E"))
        assertEquals(
            listOf(0, 0, 3, 6, 7),
            ChordSheets.onto(LineChords(over = over), "夜空の星を", "よぞらのほしを", parts).over.map { it.at }
        )
    }

    @Test
    fun chordsMoveOntoTheLineInLatinLettersAsFarAlongAtTheStartOfThatWord() {
        val over = listOf(Chord(0, "Am"), Chord(2, "F"), Chord(4, "G"))
        // 夜空の星を, 5 letters, as "yozora no hoshi wo", 18: 2 of 5 along is 7, in "no"; 4 of 5 is 14, in "hoshi".
        assertEquals(
            listOf(0, 7, 10),
            ChordSheets.onto(LineChords(over = over), "夜空の星を", "yozora no hoshi wo", listOf(Ruby("夜空の星を"))).over.map { it.at }
        )
        assertEquals(listOf(0), ChordSheets.onto(LineChords(over = listOf(Chord(3, "C"))), "abcd", "", null).over.map { it.at })
    }

    /** A chord as J-Total's page links it, to the picture of how it's played. */
    private fun jTotal(name: String) = """<a href="JavaScript:jump_1('../../../cd/$name.html');">$name</a>"""

    @Test
    fun jTotalsChordsLandOnTheLetterAtTheirColumnInAFontWhereJapaneseTakesTwo() {
        // E, six wide spaces and a space put Am 14 columns in: letter 7 of the words, as each of theirs takes two.
        val sheet = "${jTotal("E")}\u3000\u3000\u3000\u3000\u3000\u3000 ${jTotal("Am")}<br>\n  川の道を歩いていく"
        assertEquals(
            listOf(SheetLine("川の道を歩いていく", listOf(Chord(0, "E"), Chord(7, "Am")))),
            ChordSheets.fromJTotal(sheet)
        )
    }

    @Test
    fun jTotalsBassIsWrittenAfterItsLinkAsOn() {
        assertEquals(
            listOf(SheetLine("川の道", listOf(Chord(0, "E/G#"), Chord(3, "Am")))),
            ChordSheets.fromJTotal("${jTotal("E")}onG#\u3000 ${jTotal("Am")}<br>川の道")
        )
    }

    @Test
    fun jTotalsPagesWrapChordsAcrossLinesOfTheirSourceWithoutEndingALine() {
        // Its source wraps anywhere, as a browser takes that for one space; only <br> ends a line.
        assertEquals(
            listOf(SheetLine("", listOf(Chord(0, "E"), Chord(0, "G#m"), Chord(0, "Am")))),
            ChordSheets.fromJTotal("${jTotal("E")} \n  / ${jTotal("G#m")}\n  / ${jTotal("Am")} /<BR>\n")
        )
    }

    @Test
    fun jTotalsBrokenSourceLineLandsWhereTheLivePageDoes() {
        // IGNITE: Chromium collapses the source newlines to spaces, it does not break the line.
        val sheet = "　　　　${jTotal("F")}　　　　　　 \n" +
            "                        ${jTotal("G")}　　　　 \n" +
            "                        ${jTotal("Em")}　　　${jTotal("Am")}　　　/ \n" +
            "                        ${jTotal("Am")} \n                        /<br>\n" +
            "                        迷わずに今　矛盾だらけの世界を　その手で撃ち放て<br>"
        assertEquals(
            listOf(SheetLine("迷わずに今　矛盾だらけの世界を　その手で撃ち放て", listOf(
                Chord(4, "F"), Chord(11, "G"), Chord(16, "Em"), Chord(20, "Am"), Chord(24, "Am")
            ))),
            ChordSheets.fromJTotal(sheet)
        )
    }

    @Test
    fun jTotalCollapsesSpacesAcrossInlineTags() {
        val sheet = " </tt><tt> ${jTotal("Am7")} ${jTotal("G")}<br>目　と"
        assertEquals(
            listOf(SheetLine("目　と", listOf(Chord(0, "Am7"), Chord(2, "G")))),
            ChordSheets.fromJTotal(sheet)
        )
    }

    @Test
    fun jTotalCollapsesSpacesInsideChordLinks() {
        val sheet = "<a href='../../../cd/C.html'> C</a> ${jTotal("F")}<br>abcd"
        assertEquals(
            listOf(SheetLine("abcd", listOf(Chord(0, "C"), Chord(2, "F")))),
            ChordSheets.fromJTotal(sheet)
        )
    }

    @Test
    fun jTotalIgnoresAChordLinkWithOnlyWhitespace() {
        val sheet = "${jTotal("C")}<a href='../../../cd/C.html'> \n </a> ${jTotal("G")}<br>abcd"
        assertEquals(
            listOf(SheetLine("abcd", listOf(Chord(0, "C"), Chord(2, "G")))),
            ChordSheets.fromJTotal(sheet)
        )
    }

    @Test
    fun jTotalCountsOneSpaceForSpacesAndEmptyLinksInARow() {
        // A browser draws the space, the empty link and the spaces after it as one space.
        val sheet = "${jTotal("D")} <a href='../../../cd/A.html'> \n </a> \u3000\u3000${jTotal("Em")}<br>あいうえおかき"
        assertEquals(
            listOf(SheetLine("あいうえおかき", listOf(Chord(0, "D"), Chord(3, "Em")))),
            ChordSheets.fromJTotal(sheet)
        )
    }

    @Test
    fun jTotalKeepsAChordWhoseLinkClosesAfterTheBreakOnTheLineOfItsName() {
        val sheet = "${jTotal("F")}\u3000 <a href='../../../cd/C.html'>C<br>\n </a><br>あいうえお"
        assertEquals(
            listOf(SheetLine("あいうえお", listOf(Chord(0, "F"), Chord(2, "C")))),
            ChordSheets.fromJTotal(sheet)
        )
    }

    @Test
    fun jTotalDoesNotTrimBracketsInTheMonospaceStack() {
        val sheet = "\u3000${jTotal("C")}<br>」\u3000い"
        assertEquals(2, ChordSheets.fromJTotal(sheet).single().chords.single().at)
        assertEquals(1, ChordSheets.fromJTotal(sheet, monospace = true).single().chords.single().at)
    }

    @Test
    fun jTotalsReferenceMarkFollowsTheScriptBesideItOnTheMonospaceStack() {
        // Measured in Chromium on the monospace stack: U+203B is 2.000 columns or 1.650.
        // A chord over the middle of a two-column mark lands on the later letter.
        // abc is three columns. On the main stack あ is 2.000, so the chord ties and lands on い.
        // On the monospace stack あ is 2.050. The narrow mark also lands on い. The wide mark lands on あ.
        val narrow = "abc${jTotal("C")}<br>※あいう"
        assertEquals(2, ChordSheets.fromJTotal(narrow).single().chords.single().at)
        assertEquals(2, monoAt(narrow))
        // Kana on the chord line, or on the line above a blank break, keeps the wide mark.
        assertEquals(1, monoAt("abc${jTotal("C")}よ<br>※あいう"))
        assertEquals(1, monoAt("abc${jTotal("D")}よ<br><br>※あいう"))
        assertEquals(2, monoAt("abc${jTotal("D")}<br><br>※あいう"))
        // Latin, an ideographic space, then kana. A kana line above the same words keeps the narrow mark.
        assertEquals(3, monoAt("abcdef${jTotal("C")}<br>※A\u3000ああ"))
        assertEquals(4, monoAt("abcdef${jTotal("C")}よ<br>※A\u3000ああ"))
        assertEquals(6, monoAt("abcdefghi${jTotal("C")}<br>※It's\u3000あ"))
        assertEquals(4, monoAt("abcdefg${jTotal("C")}<br>※A1\u3000あ"))
        assertEquals(3, monoAt("abcdefg${jTotal("C")}<br>※Ａ\u3000ああ"))
        assertEquals(4, monoAt("abcdefg${jTotal("C")}<br>※A \u3000ああ"))
        assertEquals(5, monoAt("abcdefg${jTotal("C")}<br>※A\u3000Bあ"))
        // The same script on both sides is wide. Kana then kanji is narrow. Both Han blocks count.
        assertEquals(3, monoAt("abcde${jTotal("C")}よ<br>※恋ああ"))
        assertEquals(2, monoAt("abcde${jTotal("C")}字<br>※恋ああ"))
        assertEquals(2, monoAt("abcde${jTotal("C")}\u3400<br>※恋ああ"))
        assertEquals(2, monoAt("abcde${jTotal("C")}\uF900<br>※恋ああ"))
        assertEquals(1, monoAt("abc${jTotal("C")}ｶ<br>※あいう"))
        assertEquals(1, monoAt("abc${jTotal("C")}\u31F0<br>※あいう"))
        // The letter touching the mark wins over the line above.
        assertEquals(2, monoAt("abcde${jTotal("C")}<br>恋※ああ"))
        assertEquals(2, monoAt("abcd${jTotal("C")}<br>ｶ※あいう"))
        assertEquals(2, monoAt("abcd${jTotal("C")}<br>あ ※Bあ"))
        assertEquals(3, monoAt("abcdefg${jTotal("C")}<br>（※印あ"))
        assertEquals(2, monoAt("abcde${jTotal("C")}<br>\u3000※あいう"))
        // A mark at the end of a line that leads with an ideographic space.
        // Kana with no Latin after it stays wide when the line above is kana too.
        assertEquals(3, monoAt("abcdef${jTotal("C")}よ<br>\u3000あ…※"))
        assertEquals(7, monoAt("abcdefghij${jTotal("C")}<br>\u3000このnew※"))
        assertEquals(14, monoAt("abcdefghijkよよ${jTotal("C")}よ<br>\u3000ĲLovin' you…※"))
        assertEquals(11, monoAt("\u2103abcdefghijk${jTotal("C")}<br>\u3000glory days※"))
        // No script yet: kana after the mark is wide, Latin after it is narrow.
        // The main stack keeps the table width of 2.000, so the same Latin line lands one letter later.
        val latinAfter = "※${jTotal("C")}<br>\u0132bcdef"
        assertEquals(2, ChordSheets.fromJTotal(latinAfter).single().chords.single().at)
        assertEquals(1, monoAt(latinAfter))
        assertEquals(3, monoAt("※あ${jTotal("C")}<br>あ\u0132bc"))
        // The chord's own letters are the script of the line above the words.
        assertEquals(1, monoAt("よ<br>※${jTotal("C")}\u3000あ<br>\u0132bcdef"))
        assertEquals(2, monoAt("※${jTotal("C")}\u3000あ<br>\u0132bcdef"))
        // D is one column. A wide mark then puts C past the last letter. A narrow mark would stop on c.
        val split = "${jTotal("D")}あ※${jTotal("C")}<br>あ\u0132bc"
        assertEquals(listOf(0, 4), ChordSheets.fromJTotal(split, monospace = true).single().chords.map { it.at })
    }

    private fun monoAt(sheet: String) = ChordSheets.fromJTotal(sheet, monospace = true).last().chords.last().at

    @Test
    fun jTotalDoesNotReadLinksToOtherVersionsAsChords() {
        val sheet = "<a href='song-e.html'>Easy version</a><br>${jTotal("C")}<br>abcd"
        assertEquals(
            listOf(SheetLine("Easy version", emptyList()), SheetLine("abcd", listOf(Chord(0, "C")))),
            ChordSheets.fromJTotal(sheet)
        )
    }

    @Test
    fun jTotalUsesTheLastActiveTtFontRule() {
        val normal = "<style>tt { font-family: 'Inconsolata', sans-serif; }</style>"
        val mono = "<style><!-- tt { font-family: 'Inconsolata', monospace; } --></style>"
        assertEquals(false, ChordSheets.jTotalMonospace(""))
        assertEquals(false, ChordSheets.jTotalMonospace("$normal<!-- $mono -->"))
        assertEquals(true, ChordSheets.jTotalMonospace(normal + mono))
        assertEquals(false, ChordSheets.jTotalMonospace(mono + normal))
        assertEquals(false, ChordSheets.jTotalMonospace(normal + "<style>/* tt { font-family: 'Inconsolata', monospace; } */</style>"))
    }

    @Test
    fun jTotalsChordsPastTheWordsEndLandAtTheEndAndWordsWithNoChordsStay() {
        assertEquals(
            listOf(SheetLine("川の道", listOf(Chord(3, "Am"))), SheetLine("朝の光 2", emptyList())),
            ChordSheets.fromJTotal("\u3000\u3000\u3000\u3000\u3000${jTotal("Am")}<br>川の道<br>\n<br>朝の光 2<br />")
        )
    }

    @Test
    fun jTotalsColumnsFollowTheFontItsPageAsksFor() {
        // The letter before the chord is what's measured. Leading spaces would be trimmed, as the browser does.
        // Inconsolata, the font the page names first, draws an ellipsis, an em dash and Ā at one column.
        assertEquals(listOf(SheetLine("abcd", listOf(Chord(1, "C")))), ChordSheets.fromJTotal("…${jTotal("C")}<br>abcd"))
        assertEquals(listOf(SheetLine("abcd", listOf(Chord(1, "C")))), ChordSheets.fromJTotal("—${jTotal("C")}<br>abcd"))
        assertEquals(listOf(SheetLine("abcd", listOf(Chord(1, "C")))), ChordSheets.fromJTotal("‘${jTotal("C")}<br>abcd"))
        assertEquals(listOf(SheetLine("abcd", listOf(Chord(1, "C")))), ChordSheets.fromJTotal("↑${jTotal("C")}<br>abcd"))
        assertEquals(listOf(SheetLine("abcd", listOf(Chord(1, "C")))), ChordSheets.fromJTotal("\u0100${jTotal("C")}<br>abcd"))
        // A full-width ） and a corner bracket are two columns in the Japanese font under that.
        // → is not in Inconsolata, so it is two as well. A zero-width mark adds none.
        assertEquals(listOf(SheetLine("abcd", listOf(Chord(2, "C")))), ChordSheets.fromJTotal("）${jTotal("C")}<br>abcd"))
        assertEquals(listOf(SheetLine("abcd", listOf(Chord(2, "C")))), ChordSheets.fromJTotal("（${jTotal("C")}<br>abcd"))
        assertEquals(listOf(SheetLine("abcd", listOf(Chord(2, "C")))), ChordSheets.fromJTotal("「${jTotal("C")}<br>abcd"))
        assertEquals(listOf(SheetLine("abcd", listOf(Chord(2, "C")))), ChordSheets.fromJTotal("→${jTotal("C")}<br>abcd"))
        assertEquals(listOf(SheetLine("abcd", listOf(Chord(0, "C")))), ChordSheets.fromJTotal("\u200B${jTotal("C")}<br>abcd"))
        assertEquals(listOf(SheetLine("abcd", listOf(Chord(0, "C")))), ChordSheets.fromJTotal("\u0301${jTotal("C")}<br>abcd"))
        assertEquals(listOf(SheetLine("abcd", listOf(Chord(0, "C")))), ChordSheets.fromJTotal("\uFF9E${jTotal("C")}<br>abcd"))
        assertEquals(listOf(SheetLine("abcd", listOf(Chord(0, "C")))), ChordSheets.fromJTotal("\uFF9F${jTotal("C")}<br>abcd"))
        // Four Latin columns reach い: あ and ） are two each, because い is not punctuation.
        assertEquals(listOf(SheetLine("あ）い", listOf(Chord(2, "C")))), ChordSheets.fromJTotal("abcd${jTotal("C")}<br>あ）い"))
    }

    @Test
    fun jTotalsFullwidthBracketsTakeOneColumnBesideThePunctuationChromiumTrims() {
        // A closing mark before one of these is one column, so four Latin columns land on い.
        val closingBefore = listOf(
            '\u3000', '\u30FB', '\u00B7', '\u2027', '\u2019', '\u201D',
            '）', '、', '。', '，', '．', ')'
        )
        for (next in closingBefore) {
            assertEquals(
                "U+%04X".format(next.code),
                listOf(SheetLine("あ）${next}い", listOf(Chord(3, "C")))),
                ChordSheets.fromJTotal("abcd${jTotal("C")}<br>あ）${next}い")
            )
        }
        // The same for a stop, a corner bracket, and the other full-width block, not only ）.
        for (mark in listOf('、', '。', '，', '．', '」', '\uFF60')) {
            assertEquals(
                "U+%04X".format(mark.code),
                listOf(SheetLine("あ${mark}\u3000い", listOf(Chord(3, "C")))),
                ChordSheets.fromJTotal("abcd${jTotal("C")}<br>あ${mark}\u3000い")
            )
        }
        // An opening mark after one of these is one column. Six Latin columns then land past い, on z.
        val openingAfterWide = listOf('\u3000', '\u30FB', '\u2027', '）', '（', '「')
        for (prev in openingAfterWide) {
            assertEquals(
                "U+%04X".format(prev.code),
                listOf(SheetLine("あ${prev}（iz", listOf(Chord(4, "C")))),
                ChordSheets.fromJTotal("abcdef${jTotal("C")}<br>あ${prev}（iz")
            )
        }
        // A one-column neighbor needs the chord one column earlier to show the same trim.
        for (prev in listOf('\u00B7', '\u2018', '\u201C', '(')) {
            assertEquals(
                "U+%04X".format(prev.code),
                listOf(SheetLine("あ${prev}（iz", listOf(Chord(4, "C")))),
                ChordSheets.fromJTotal("abcde${jTotal("C")}<br>あ${prev}（iz")
            )
        }
        // An opening corner bracket from the other block, and a small-form bracket that does not trim.
        assertEquals(
            listOf(SheetLine("あ\u3000「iz", listOf(Chord(4, "C")))),
            ChordSheets.fromJTotal("abcdef${jTotal("C")}<br>あ\u3000「iz")
        )
        assertEquals(
            listOf(SheetLine("あ\uFE5A\u3000い", listOf(Chord(2, "C")))),
            ChordSheets.fromJTotal("abcd${jTotal("C")}<br>あ\uFE5A\u3000い")
        )
        // At the end of the chord's own gap there is no following letter, so ） stays two columns.
        assertEquals(
            listOf(SheetLine("abcdef", listOf(Chord(4, "C")))),
            ChordSheets.fromJTotal("あ）${jTotal("C")}<br>abcdef")
        )
        // Kana beside a bracket does not trim it. Column 6 is い, not the letter after it.
        assertEquals(
            listOf(SheetLine("ああ（iz", listOf(Chord(3, "C")))),
            ChordSheets.fromJTotal("abcdef${jTotal("C")}<br>ああ（iz")
        )
    }

    @Test
    fun jTotalsFullwidthColonAndSemicolonTrimTheMarkBesideThem() {
        // CSS Text 4 puts these with the middle dots for Japanese. Chromium does that with no lang too,
        // so four Latin columns land on い, and six land on z.
        for (mark in listOf('\uFF1A', '\uFF1B')) {
            assertEquals(
                "U+%04X".format(mark.code),
                listOf(SheetLine("あ）${mark}い", listOf(Chord(3, "C")))),
                ChordSheets.fromJTotal("abcd${jTotal("C")}<br>あ）${mark}い")
            )
            assertEquals(
                "U+%04X stop".format(mark.code),
                listOf(SheetLine("あ、${mark}い", listOf(Chord(3, "C")))),
                ChordSheets.fromJTotal("abcd${jTotal("C")}<br>あ、${mark}い")
            )
            assertEquals(
                "U+%04X open".format(mark.code),
                listOf(SheetLine("あ${mark}（iz", listOf(Chord(4, "C")))),
                ChordSheets.fromJTotal("abcdef${jTotal("C")}<br>あ${mark}（iz")
            )
        }
    }

    @Test
    fun jTotalsMarksOutsideTheSpacingClassesStayTwoColumns() {
        // Measured in Chromium, beside the page font: these are not middle dots, quotes or Ps/Pe,
        // so ） stays two columns and four Latin columns land on the mark, not on い.
        for (next in listOf('\uFF65', '\u00BB', '\uFE10', '\uFE11', '\uFE13', '\uFE15')) {
            assertEquals(
                "U+%04X".format(next.code),
                listOf(SheetLine("あ）${next}い", listOf(Chord(2, "C")))),
                ChordSheets.fromJTotal("abcd${jTotal("C")}<br>あ）${next}い")
            )
        }
        // An opening mark after a guillemet (one column) or a vertical comma (two) stays two.
        assertEquals(
            listOf(SheetLine("あ\u00AB（iz", listOf(Chord(4, "C")))),
            ChordSheets.fromJTotal("abcdef${jTotal("C")}<br>あ\u00AB（iz")
        )
        assertEquals(
            listOf(SheetLine("あ\uFE10（iz", listOf(Chord(3, "C")))),
            ChordSheets.fromJTotal("abcdef${jTotal("C")}<br>あ\uFE10（iz")
        )
        // A right quote trims the closing mark before it, and not the opening mark after it.
        assertEquals(
            listOf(SheetLine("あ”（iz", listOf(Chord(3, "C")))),
            ChordSheets.fromJTotal("abcde${jTotal("C")}<br>あ”（iz")
        )
    }

    @Test
    fun jTotalsEmptyLinksAddNoColumnAndNoLine() {
        // Measured in Chromium: an empty link is not drawn, so "C / " is four columns and the next C is on e.
        val empty = """<a href="JavaScript:jump_1('../../../cd/C.html');"></a>"""
        assertEquals(
            listOf(SheetLine("abcdefghijklmnopqrstuvwxyz", listOf(Chord(0, "C"), Chord(4, "C")))),
            ChordSheets.fromJTotal("${jTotal("C")} / $empty${jTotal("C")}<br>abcdefghijklmnopqrstuvwxyz")
        )
        // A line that is only that link is blank on the page, so the chord stays over the words under it.
        assertEquals(
            listOf(SheetLine("abcd", listOf(Chord(0, "C")))),
            ChordSheets.fromJTotal("${jTotal("C")}<br>$empty<br>abcd")
        )
        assertEquals(
            listOf(SheetLine("", listOf(Chord(0, "C"))), SheetLine("abcd", listOf(Chord(0, "Am")))),
            ChordSheets.fromJTotal("${jTotal("C")}<br>$empty<br>${jTotal("Am")}<br>abcd")
        )
        // Two empty links with a space between them are still a blank line once the tags are gone.
        assertEquals(
            listOf(SheetLine("abcd", listOf(Chord(0, "C")))),
            ChordSheets.fromJTotal("${jTotal("C")}<br>$empty $empty<br>abcd")
        )
    }

    @Test
    fun jTotalsALinkSplitByALineBreakStillNamesItsChord() {
        // Measured: the page breaks before Em, Em is two columns, and F is on d.
        val split = """<a href="JavaScript:jump_1('../../../cd/Em.html');"><br>Em</a>"""
        assertEquals(
            listOf(SheetLine("abcdef", listOf(Chord(0, "Em"), Chord(3, "F")))),
            ChordSheets.fromJTotal("$split ${jTotal("F")}<br>abcdef")
        )
        // The bass stays on the name, same as a link the line break did not cut. "EmonG#" is six columns.
        val splitBass = """<a href="JavaScript:jump_1('../../../cd/Em.html');"><br>Em</a>onG#"""
        assertEquals(
            listOf(SheetLine("abcdefgh", listOf(Chord(0, "Em/G#"), Chord(7, "F")))),
            ChordSheets.fromJTotal("$splitBass ${jTotal("F")}<br>abcdefgh")
        )
        // The cut link is its own line, so the chord above it does not take Em as its words.
        assertEquals(
            listOf(SheetLine("", listOf(Chord(0, "C"))), SheetLine("abcdef", listOf(Chord(0, "Em")))),
            ChordSheets.fromJTotal("${jTotal("C")}<br>$split<br>abcdef")
        )
    }

    @Test
    fun jTotalsInconsolataLettersTakeOneColumn() {
        val letters = listOf(
            '\u0100', '\u0131', '\u0134', '\u0148', '\u014A', '\u017E', '\u018F', '\u0192', '\u0198',
            '\u01A0', '\u01A1', '\u01AF', '\u01B0', '\u01B8', '\u01B9', '\u01C7', '\u01C9', '\u01E6',
            '\u01E7', '\u01EA', '\u01EB', '\u01FA', '\u021B', '\u022A', '\u022D', '\u0230', '\u0233',
            '\u0237', '\u024D', '\u0259', '\u027B', '\u0298', '\u029A', '\u02B9', '\u02BC', '\u02BE',
            '\u02BF', '\u02C6', '\u02CC', '\u02DA', '\u02DC', '\u02DD', '\u1E08', '\u1E09', '\u1E0C',
            '\u1E0F', '\u1E14', '\u1E17', '\u1E1C', '\u1E1D', '\u1E20', '\u1E21', '\u1E24', '\u1E25',
            '\u1E2A', '\u1E2B', '\u1E2E', '\u1E2F', '\u1E36', '\u1E37', '\u1E3A', '\u1E3B', '\u1E42',
            '\u1E49', '\u1E4C', '\u1E53', '\u1E5A', '\u1E5B', '\u1E5E', '\u1E69', '\u1E6C', '\u1E6F',
            '\u1E78', '\u1E7B', '\u1E80', '\u1E85', '\u1E8E', '\u1E8F', '\u1E92', '\u1E93', '\u1E97',
            '\u1E9E', '\u1EA0', '\u1EF9', '\u2009', '\u2013', '\u2014', '\u2018', '\u201A', '\u201C',
            '\u201E', '\u2020', '\u2022', '\u2026', '\u2032', '\u2033', '\u2039', '\u203A', '\u2044',
            '\u20A1', '\u20A3', '\u20A4', '\u20A6', '\u20A7', '\u20A9', '\u20AB', '\u20AD', '\u20B1',
            '\u20B2', '\u20B5', '\u20B9', '\u20BA', '\u20BC', '\u20BD', '\u2113', '\u2122', '\u2191',
            '\u2193', '\u2212', '\u2215', '\uFFFD'
        )
        for (letter in letters) {
            assertEquals(
                "U+%04X".format(letter.code),
                listOf(SheetLine("abcd", listOf(Chord(1, "C")))),
                ChordSheets.fromJTotal("$letter${jTotal("C")}<br>abcd")
            )
        }
        for (letter in listOf('\u0300', '\u0303', '\u0304', '\u0308', '\u0309', '\u0323', '\uFEFF')) {
            assertEquals(
                "U+%04X".format(letter.code),
                listOf(SheetLine("abcd", listOf(Chord(0, "C")))),
                ChordSheets.fromJTotal("$letter${jTotal("C")}<br>abcd")
            )
        }
    }


    @Test
    fun jTotalsWideLettersStartRightPastLatinAndOnEitherSideOfHalfWidthKana() {
        // What comes before the chord is what's measured; words in Latin letters say how many columns it took.
        // Ā is one column: Inconsolata has it. The last half-width kana letter is one; the voice marks are none, covered above.
        val columnsOf = listOf('\u00FF' to 1, '\u0100' to 1, '\uFF60' to 2, '\uFF61' to 1, '\uFF9D' to 1, '\uFFA0' to 2)
        for ((letter, columns) in columnsOf) {
            assertEquals(
                "U+%04X".format(letter.code),
                listOf(SheetLine("abcd", listOf(Chord(columns, "C")))),
                ChordSheets.fromJTotal("$letter${jTotal("C")}<br>abcd")
            )
        }
    }

    @Test
    fun chordWikisChordsSitOnTheWordsAndItsNotesAreNotSung() {
        val source = """
            {title:川の道}
            {subtitle:歌:歩く人　作詞:誰か}
            {capo:2}
            {c:BPM=90}
            # [C]この行はメモ
            |[Am]---- [G]----|
            (4/4)[Am]川の道を歩いていく
            [F]朝の光に[C]歌っている
            {soc}
            [G]川の道
            {eoc}
        """.trimIndent()
        val song = ChordSheets.fromChordWiki(source)
        assertEquals("川の道", song.title)
        assertEquals("歩く人", song.artist)
        assertEquals(2, song.capo)
        assertEquals(
            listOf(
                SheetLine("", listOf(Chord(0, "Am"), Chord(5, "G"))),
                SheetLine("川の道を歩いていく", listOf(Chord(0, "Am"))),
                SheetLine("朝の光に歌っている", listOf(Chord(0, "F"), Chord(4, "C"))),
                SheetLine("川の道", listOf(Chord(0, "G")))
            ),
            song.lines
        )
    }

    @Test
    fun aReadingInBracketsAfterAKanjiIsNotWordsOfTheSong() {
        val song = ChordSheets.fromChordWiki(
            """
            [C]川(かわ)の道(みち)を[F]歩(ある)く人(ひと)
            [G]今(い[F]----ま)の[Am]夜(ﾖﾙ)
            [Dm]あ(パパヤ)[C]回(マワ)レ
            """.trimIndent()
        )
        assertEquals(
            listOf(
                // The chord before 歩 stays before it, with the readings gone.
                SheetLine("川の道を歩く人", listOf(Chord(0, "C"), Chord(4, "F"))),
                // A chord inside a reading sits where the reading was.
                SheetLine("今の夜", listOf(Chord(0, "G"), Chord(1, "F"), Chord(2, "Am"))),
                // Brackets that follow no kanji are sung.
                SheetLine("あ(パパヤ)回レ", listOf(Chord(0, "Dm"), Chord(6, "C")))
            ),
            song.lines
        )
    }

    @Test
    fun aLineWhoseReadingsAreLongerThanItsWordsKeepsAllItsChords() {
        val texts = listOf("朝の光に歌っているよ川の道", "風が吹いて花が咲く春の日", "夜明けの空に鳥が歌う", "遠い海へ船を漕ぎ出そう")
        val written = ChordSheets.fromChordWiki(
            """
            [Am]朝(あさひかりのなか)の光(ひかりのさき)に[F]歌(うたごえがきこえる)っているよ[G]川(かわのながれ)の道(みちのり)[C]
            [C]風が吹いて[Am]花が咲く春の日
            [F]夜(よるのさき)明けの空に[G]鳥が歌(うたごえ)う
            [C]遠い海へ[Am]船を漕ぎ出そう
            """.trimIndent()
        ).lines
        val placed = ChordSheets.place(sheet(*written.toTypedArray()), texts)!!
        // The last one is played after the line, past readings the song doesn't have.
        assertEquals(listOf("Am", "F", "G", "C"), placed[0].over.map { it.name })
        assertEquals(listOf("C", "Am"), placed[1].over.map { it.name })
        assertEquals(listOf("F", "G"), placed[2].over.map { it.name })
        assertEquals(listOf("C", "Am"), placed[3].over.map { it.name })
        // And each over the letter it was played on.
        assertEquals(listOf(0, 4, 10, 13), placed[0].over.map { it.at })
    }

    @Test
    fun chordWikisEditorIsItsLongestTextareaAndAnEmptyOneIsNotThePageAroundIt() {
        val page = """
            <p>{title:外}</p>
            <textarea>short</textarea>
            <TEXTAREA id="chord">{title:川&amp;道}
            {subtitle:歌：歩く人}
            [F]あ</TEXTAREA>
        """.trimIndent()
        assertEquals(ChordWikiSong(listOf(SheetLine("あ", listOf(Chord(0, "F")))), title = "川&道", artist = "歩く人"), ChordSheets.fromChordWiki(page))
        assertEquals(ChordWikiSong(emptyList()), ChordSheets.fromChordWiki("<textarea name=\"source\"></textarea>{title:外}[C]川"))
    }

    @Test
    fun chordWikiKeepsRealChordsDropsWordsInBracketsAndSkipsTabsNotesAndASecondName() {
        val source = """
            {title:}
            {title:一}
            {t:二}
            {subtitle:}
            {subtitle:作詞:誰か}
            {subtitle:歩く人　作曲:誰か}
            {subtitle:歌:甲　作詞:誰か}
            {st:歌：乙　歌詞:誰か}
            {capo:no}
            {capo:99}
            {capo:2}
            {capo:4}
            {c:BPM=90}
            {comment:メモ}
            {ci:もう一つ}
            {soc}
            {eoc}
            # [C]この行はメモ
            {sot}
            [D]----
            {eot}
            {start_of_tab}
            [D]----
            {end_of_tab}
            {sog}
            [D]----
            {eog}
            {start_of_grid}
            [D]----
            {end_of_grid}
            [C#m]川 # [G]メモ
            [サビ][Chorus][A B][Good][Caaa][Cabcdefghijklmno]川[C!]の道
            [Cmaj7]の[N.C.]道[NC]よ[Dbadd9][Eb7(9)][Am7b5][F/G]ね
            {川}
            朝の光
            ----
            |(4/4)[Am]川|の[n.c.]道
        """.trimIndent()
        val song = ChordSheets.fromChordWiki(source)
        assertEquals("一", song.title)
        assertEquals("歩く人", song.artist)
        assertEquals(2, song.capo)
        assertEquals(
            listOf(
                SheetLine("川", listOf(Chord(0, "C#m"))),
                SheetLine("川の道", emptyList()),
                SheetLine("の道よね", listOf(Chord(0, "Cmaj7"), Chord(1, "N.C."), Chord(2, "NC"), Chord(3, "Dbadd9"), Chord(3, "Eb7(9)"), Chord(3, "Am7b5"), Chord(3, "F/G"))),
                SheetLine("{川}", emptyList()),
                SheetLine("朝の光", emptyList()),
                SheetLine("川の道", listOf(Chord(0, "Am"), Chord(2, "n.c.")))
            ),
            song.lines
        )
    }

    @Test
    fun chordWikiKeepsTheLastFretAChordOfFifteenCharactersAndCutsANoteAfterAStrayBracket() {
        val kept = "Cmaj7sus4add11x"
        val dropped = "Cmaj7sus4add11xx"
        assertEquals(15, kept.length)
        assertEquals(16, dropped.length)
        assertEquals(0, ChordSheets.fromChordWiki("{capo:25}\n[C]川").capo)
        val song = ChordSheets.fromChordWiki(
            """
            {capo:25}
            {capo:24}
            [$kept]川
            [$dropped]道
            ]#[Am]メモ
            """.trimIndent()
        )
        assertEquals(24, song.capo)
        assertEquals(listOf(SheetLine("川", listOf(Chord(0, kept))), SheetLine("道", emptyList())), song.lines)
    }

    @Test
    fun chordWikiTakesTheShortTitleAndSubtitleAndAChordOnceATabHasEnded() {
        assertEquals("一", ChordSheets.fromChordWiki("{t:一}\n[C]川").title)
        assertEquals("歩く人", ChordSheets.fromChordWiki("{st:歌：歩く人}\n[C]川").artist)
        // A blank name after 歌： is nobody, so the subtitle's own words stay.
        assertEquals("歌：", ChordSheets.fromChordWiki("{subtitle:歌： }\n[C]川").artist)
        // A name of nothing but a space that isn't one of the plain ones is no name either.
        assertEquals("歌：", ChordSheets.fromChordWiki("{st:歌：\u2003 作詞：山田}\n[C]川").artist)
        val song = ChordSheets.fromChordWiki(
            """
            {c:BPM=90}
            [C]残
            {sot}
            [D]----
            {eot}
            [E]甲
            {start_of_tab}
            [D]----
            {end_of_tab}
            [F]乙
            {sog}
            [D]----
            {eog}
            [G]丙
            {start_of_grid}
            [D]----
            {end_of_grid}
            [A]丁
            """.trimIndent()
        )
        assertEquals(
            listOf(
                SheetLine("残", listOf(Chord(0, "C"))),
                SheetLine("甲", listOf(Chord(0, "E"))),
                SheetLine("乙", listOf(Chord(0, "F"))),
                SheetLine("丙", listOf(Chord(0, "G"))),
                SheetLine("丁", listOf(Chord(0, "A")))
            ),
            song.lines
        )
    }

    @Test
    fun aShortLineTheWordsDontHaveStillKeepsItsChordInOrder() {
        // F7 sits on three letters the lyric lines don't have, between chords that do get drawn.
        // Those three letters are too few to call the line another verse, so the chord stays, in order.
        // Chords on their own after the last line stay too.
        val sheet = sheet(
            SheetLine("", listOf(Chord(0, "DbM7"), Chord(4, "E"))),
            SheetLine("Hey", listOf(Chord(0, "F7"))),
            SheetLine("", listOf(Chord(0, "C7"))),
            SheetLine("Walking down the river road", listOf(Chord(0, "G"))),
            SheetLine("Singing to the morning light", listOf(Chord(0, "D"))),
            SheetLine("", listOf(Chord(0, "Em")))
        )
        val placed = ChordSheets.place(sheet, listOf("Walking down the river road", "Singing to the morning light"))!!
        val names = placed.flatMap { line -> line.before + line.over.map { it.name } }
        assertEquals(listOf("DbM7", "E", "F7", "C7", "G", "D", "Em"), names)
    }

    @Test
    fun aChordOnLettersThatLinedUpStaysWhenTheRestOfTheLineDoesNot() {
        // Most of the line is words the song never sings, so it is under half. The chord on the
        // words that did line up stays. The one on the words that didn't is not this song's.
        val lyric = "Walking down the river road"
        val sheet = sheet(
            SheetLine("$lyric ${"q".repeat(30)}", listOf(Chord(0, "G"), Chord(lyric.length + 1, "C"))),
            SheetLine("Singing to the morning light", listOf(Chord(0, "D")))
        )
        val placed = ChordSheets.place(sheet, listOf(lyric, "Singing to the morning light"))!!
        assertEquals(listOf("G", "D"), placed.flatMap { line -> line.before + line.over.map { it.name } })
    }

    @Test
    fun jTotalsHalfWidthLettersTakeOneColumnAndChordsBeforeOthersHaveNoWords() {
        assertEquals(
            listOf(SheetLine("", listOf(Chord(0, "C"))), SheetLine("ab川ｶ道", listOf(Chord(3, "G")))),
            ChordSheets.fromJTotal("${jTotal("C")}<br>\u3000 ${jTotal("G")}<br>ab川ｶ道")
        )
    }
}
