package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What gave each word its reading, as a report on a wrong one names it, and a word read as it shows over its line. */
@RunWith(RobolectricTestRunner::class)
class ReadersTest {

    private fun by(line: String, word: String, script: Script = Script.JAPANESE) = Readings.words(line, script).let { words -> checkNotNull(words.singleOrNull { it.text == word }) { "$word not in $words" } }.by

    @Test
    fun eachScriptNamesWhatReadIt() {
        assertEquals(listOf(Reader.IPADIC), by("夜の空", "夜"))
        assertEquals(listOf(Reader.PINYIN), by("我们喜欢音乐", "音乐", Script.CHINESE))
        assertEquals(listOf(Reader.ROMAJA), by("사랑해", "사랑해", Script.KOREAN))
        assertEquals(listOf(Reader.LATIN), by("Привет мир", "мир", Script.CYRILLIC))
        // Nothing reads a word in Latin letters.
        assertEquals(emptyList<Reader>(), Readings.words("love", null).single().by)
    }

    @Test
    fun japaneseNamesTheRulesTheLyricsOrUniDicWhereTheyReadIt() {
        // Crosstune's own: 二人 is ふたり, 側 alone そば, 一 runs into a counter.
        assertEquals(listOf(Reader.RULES), by("二人で", "二人で"))
        assertEquals(listOf(Reader.RULES), by("君の側で", "側"))
        assertEquals(listOf(Reader.RULES), by("一歩", "一歩"))
        // The lyrics' own reading in brackets, also of a word's first kanji only.
        assertEquals(listOf(Reader.LYRICS), by("歌（うた）", "歌"))
        // Kana alone names what split it, which says how its particles read.
        assertEquals(listOf(Reader.IPADIC), by("きみは", "は"))
        TestUniDic.reading {
            assertEquals(listOf(Reader.UNIDIC), by("君が溢した", "溢した"))
        }
    }

    @Test
    fun aWordWhoseKanjiWerePutRightReadsAsTheLineShowsIt() {
        val read = Readings.of(listOf("君の花"), Script.JAPANESE)
        val fixed = Readings.corrected(read, listOf(ReadingFix(0, "花", "ばな", Reader.SUNG), ReadingFix(0, "君", "きみ", Reader.CHATGPT)))[0]
        val words = Readings.asShown(Readings.words("君の花", Script.JAPANESE), fixed.parts)
        assertEquals(Word("花", "ばな", "bana", by = listOf(Reader.SUNG)), words.single { it.text == "花" })
        // Said as it's drawn out, typed as it's spelled.
        val word = Readings.asShown(Readings.words("先生", Script.JAPANESE), listOf(Ruby("先生", "せんせい", Reader.CHATGPT))).single()
        assertEquals(listOf("せんせい", "sensee", "sensei"), listOf(word.reading, word.romanized, word.typed))
        assertEquals(listOf(Reader.CHATGPT), word.by)
    }

    @Test
    fun aWordNothingPutRightStaysAsTheDictionaryReadIt() {
        val words = Readings.words("君の花", Script.JAPANESE)
        // No reading put right: the same words.
        assertSame(words, Readings.asShown(words, Readings.of(listOf("君の花"), Script.JAPANESE)[0].parts))
        // Put right elsewhere in the line: this one stays.
        val parts = listOf(Ruby("君", "きみ", Reader.SUNG), Ruby("の"), Ruby("花", "か"))
        assertEquals(words.single { it.text == "花" }, Readings.asShown(words, parts).single { it.text == "花" })
        // A reading that runs past the word is the line's, not the word's.
        val across = listOf(Ruby("君の", "きみの", Reader.SUNG), Ruby("花", "はな"))
        assertEquals(words, Readings.asShown(words, across))
        // A word that isn't in the parts, as words of another line, stays as it was.
        val elsewhere = listOf(Word("星", "ほし", "hoshi", by = listOf(Reader.IPADIC)))
        assertEquals(elsewhere, Readings.asShown(elsewhere, parts))
        // The same word twice: each is read where it is.
        val twice = Readings.words("花の花", Script.JAPANESE)
        val shown = Readings.asShown(twice, listOf(Ruby("花", "か"), Ruby("の"), Ruby("花", "ばな", Reader.SUNG)))
        assertEquals(listOf(twice.first(), Word("花", "ばな", "bana", by = listOf(Reader.SUNG))), listOf(shown.first(), shown.last()))
    }

    @Test
    fun aReadingPutRightKeepsWhatPutItRightWhenItsKanaAreMovedOff() {
        // Kept over the kana among a word's kanji, as it once was, its kanji keep it.
        assertEquals(listOf(Ruby("飛", "と", Reader.SUNG), Ruby("び"), Ruby("立", "た", Reader.SUNG), Ruby("つ")), Readings.rubied(listOf(Ruby("飛び立つ", "とびたつ", Reader.SUNG))))
    }

    @Test
    fun aWordShowsItsReadingThenItsRomajiAsSaidAndTyped() {
        val word = Word("先生", "せんせい", "sensee", typed = "sensei")
        assertEquals(listOf("せんせい", "sensee", "sensei"), word.shown(typedFirst = false))
        assertEquals(listOf("せんせい", "sensei", "sensee"), word.shown(typedFirst = true))
        assertEquals(listOf("mir"), Word("мир", null, "mir").shown(typedFirst = false))
        assertEquals(emptyList<String>(), Word("love").shown(typedFirst = false))
    }
}
