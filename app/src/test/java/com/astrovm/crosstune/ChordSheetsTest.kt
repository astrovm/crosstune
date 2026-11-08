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
    fun jTotalsChordsPastTheWordsEndLandAtTheEndAndWordsWithNoChordsStay() {
        assertEquals(
            listOf(SheetLine("川の道", listOf(Chord(3, "Am"))), SheetLine("朝の光 2", emptyList())),
            ChordSheets.fromJTotal("\u3000\u3000\u3000\u3000\u3000${jTotal("Am")}<br>川の道<br>\n<br>朝の光 2<br />")
        )
    }

    @Test
    fun jTotalsWideLettersStartRightPastLatinAndOnEitherSideOfHalfWidthKana() {
        // What comes before the chord is what's measured; words in Latin letters say how many columns it took.
        val columnsOf = listOf('\u00FF' to 1, '\u0100' to 2, '\uFF60' to 2, '\uFF61' to 1, '\uFF9F' to 1, '\uFFA0' to 2)
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
