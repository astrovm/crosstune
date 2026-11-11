package com.astrovm.crosstune

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A kept line read again as its song's words read now, keeping what UtaTen, ChatGPT or the user put right in it
 * where it was put right: by where it is in the line, not by its text, which may be there twice.
 */
@RunWith(RobolectricTestRunner::class)
class SavedLinesRereadTest {

    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("reread-test", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
    }

    /** [text] kept as [parts] read it, by an older dictionary than this version's. */
    private fun kept(text: String, vararg parts: Ruby) =
        SavedLine(text, reading = parts.joinToString("") { it.reading ?: it.text }, title = "Demo", artist = "Band", ruby = parts.toList(), readWith = 3)

    /** [line] as the dictionary reads it now. */
    private fun now(line: SavedLine) = line.readBy(Readings.of(listOf(line.text), Script.JAPANESE).single())

    /** Its kanji, each with what it reads and what put that right. */
    private fun SavedLine.read() = ruby.orEmpty().filter { it.reading != null }

    @Test
    fun aWordPutRightWhereItsSungTheSecondTimeStaysThereAndTheFirstIsTheDictionarys() {
        val line = kept(
            "都会の夜と都会の朝",
            Ruby("都会", "とかい"), Ruby("の"), Ruby("夜", "よる"), Ruby("と"), Ruby("都会", "まち", Reader.SUNG), Ruby("の"), Ruby("朝", "あさ")
        )
        val again = line.rereadAs(now(line))
        assertEquals(listOf(Ruby("都会", "とかい"), Ruby("夜", "よる"), Ruby("都会", "まち", Reader.SUNG), Ruby("朝", "あさ")), again.read())
        assertEquals("とかいのよるとまちのあさ", again.reading)
        assertEquals("tokai no yoru to machi no asa", again.romanized)
    }

    @Test
    fun kanjiPutRightOneByOneThatTheDictionaryNowReadsAsOneWordKeepTheirReadings() {
        val line = kept("三本の花", Ruby("三", "さん", Reader.SUNG), Ruby("本", "もと", Reader.SUNG), Ruby("の"), Ruby("花", "はな"))
        // The dictionary reads 三本 as one word, さんぼん, now: it can't take one of the two, so both stay as they were put right.
        assertEquals(Ruby("三本", "さんぼん"), now(line).read().first())
        val again = line.rereadAs(now(line))
        assertEquals(listOf(Ruby("三", "さん", Reader.SUNG), Ruby("本", "もと", Reader.SUNG), Ruby("花", "はな")), again.read())
        assertEquals("さんもとのはな", again.reading)
    }

    @Test
    fun aKanjiPutRightAloneThatTheDictionaryNowReadsInOneWordWithAnotherKeepsItsReading() {
        // Only 本 was put right; 三本 is one word now, which can't take a reading for 本 alone, so the two stay as they were.
        val line = kept("三本の花", Ruby("三", "さん"), Ruby("本", "もと", Reader.SUNG), Ruby("の"), Ruby("花", "はな"))
        val again = line.rereadAs(now(line))
        assertEquals(listOf(Ruby("三", "さん"), Ruby("本", "もと", Reader.SUNG), Ruby("花", "はな")), again.read())
        assertEquals("さんもとのはな", again.reading)
    }

    @Test
    fun whatsPutRightAtTheLinesStartAndEndStaysAndTheRestIsReadAgain() {
        val line = kept("都会の三本の夜", Ruby("都会", "まち", Reader.SUNG), Ruby("の"), Ruby("三本", "さんほん"), Ruby("の"), Ruby("夜", "ばん", Reader.CHATGPT))
        val again = line.rereadAs(now(line))
        assertEquals(listOf(Ruby("都会", "まち", Reader.SUNG), Ruby("三本", "さんぼん"), Ruby("夜", "ばん", Reader.CHATGPT)), again.read())
        assertEquals("まちのさんぼんのばん", again.reading)
    }

    @Test
    fun theSameWordPutRightTwoWaysKeepsEachWhereItIs() {
        val line = kept("都会の都会", Ruby("都会", "みやこ", Reader.CHATGPT), Ruby("の"), Ruby("都会", "まち", Reader.SUNG))
        val again = line.rereadAs(now(line))
        assertEquals(listOf(Ruby("都会", "みやこ", Reader.CHATGPT), Ruby("都会", "まち", Reader.SUNG)), again.read())
        assertEquals("みやこのまち", again.reading)
    }

    @Test
    fun whatsPutRightInTheSongsWordsNowWinsOverWhatWasKept() {
        val line = kept("都会の夜", Ruby("都会", "まち", Reader.SUNG), Ruby("の"), Ruby("夜", "よる"))
        val now = line.copy(reading = "みやこのよる", ruby = listOf(Ruby("都会", "みやこ", Reader.CHATGPT), Ruby("の"), Ruby("夜", "よる")), readWith = Readings.VERSION)
        val again = line.rereadAs(now)
        assertEquals(listOf(Ruby("都会", "みやこ", Reader.CHATGPT), Ruby("夜", "よる")), again.read())
        assertEquals("みやこのよる", again.reading)
    }

    @Test
    fun aReadingTheUserPutRightStaysWhereItIs() {
        val line = kept("夜の都会の夜", Ruby("夜", "よる"), Ruby("の"), Ruby("都会", "とかい"), Ruby("の"), Ruby("夜", "よ", Reader.USER))
        val again = line.rereadAs(now(line))
        assertEquals(listOf(Ruby("夜", "よる"), Ruby("都会", "とかい"), Ruby("夜", "よ", Reader.USER)), again.read())
    }

    @Test
    fun aReadingTheUserPutRightWinsOverOneSungNow() {
        val line = kept("都会の夜", Ruby("都会", "とかい"), Ruby("の"), Ruby("夜", "よ", Reader.USER)).copy(readByHand = true)
        val now = line.copy(reading = "まちのよる", ruby = listOf(Ruby("都会", "まち", Reader.SUNG), Ruby("の"), Ruby("夜", "よる", Reader.SUNG)), readWith = Readings.VERSION)
        val again = line.rereadAs(now)
        assertEquals(listOf(Ruby("都会", "まち", Reader.SUNG), Ruby("夜", "よ", Reader.USER)), again.read())
        assertEquals("まちのよ", again.reading)
    }

    @Test
    fun aLineTheWordsMovedToCarriesWhatWasPutRightInTheLineItWas() {
        val was = kept("都会の夜", Ruby("都会", "まち", Reader.SUNG), Ruby("の"), Ruby("夜", "よ", Reader.USER)).copy(readByHand = true)
        // Not read yet as it moved: the dictionary reads it first.
        val moved = SavedLine("都会の夜に", reading = null, title = "Demo", artist = "Band")
        val carried = moved.carrying(was)
        assertEquals(listOf(Ruby("都会", "まち", Reader.SUNG), Ruby("夜", "よ", Reader.USER)), carried.read())
        assertEquals("まちのよに", carried.reading)
        assertEquals(Readings.VERSION, carried.readWith)
        assertTrue(carried.readByHand)
    }

    @Test
    fun whatTheWordsNowPutRightWinsOverWhatALineCarriesButTheUsers() {
        val was = kept("都会の夜", Ruby("都会", "まち", Reader.SUNG), Ruby("の"), Ruby("夜", "よ", Reader.USER))
        val moved = SavedLine(
            "都会の夜に", reading = "みやこのよるに", title = "Demo", artist = "Band",
            ruby = listOf(Ruby("都会", "みやこ", Reader.CHATGPT), Ruby("の"), Ruby("夜", "よる", Reader.CHATGPT), Ruby("に")), readWith = Readings.VERSION
        )
        val carried = moved.carrying(was)
        assertEquals(listOf(Ruby("都会", "みやこ", Reader.CHATGPT), Ruby("夜", "よ", Reader.USER)), carried.read())
        assertEquals("みやこのよに", carried.reading)
        // The line it was wasn't read by hand, so it isn't either.
        assertFalse(carried.readByHand)
    }

    @Test
    fun aLineWithNothingPutRightCarriesNothingAndOneReadByHandWithoutTheUsersMarkIsntSaidToBeAnyMore() {
        val moved = SavedLine("都会の夜に", reading = "とかいのよるに", title = "Demo", artist = "Band", ruby = listOf(Ruby("都会", "とかい"), Ruby("の"), Ruby("夜", "よる"), Ruby("に")), readWith = Readings.VERSION)
        assertEquals(moved, moved.carrying(kept("都会の夜", Ruby("都会", "とかい"), Ruby("の"), Ruby("夜", "よる")).copy(readByHand = true)))
        // Only UtaTen's reading goes with it: the line isn't read by hand any more.
        val sung = moved.carrying(kept("都会の夜", Ruby("都会", "とかい", Reader.SUNG), Ruby("の"), Ruby("夜", "よる")).copy(readByHand = true))
        assertFalse(sung.readByHand)
    }

    @Test
    fun keptLinesCarryWhatWasPutRightOnlyWhereALineMovedAndNoneWhenNothingChanges() {
        val was = kept("都会の夜", Ruby("都会", "まち", Reader.SUNG), Ruby("の"), Ruby("夜", "よる"))
        val moved = SavedLine("都会の夜に", reading = null, title = "Demo", artist = "Band")
        val other = SavedLine("花", reading = "はな", title = "Demo", artist = "Band", ruby = listOf(Ruby("花", "はな")), readWith = Readings.VERSION)
        val kept = carried(listOf(moved, other), listOf(was to moved))
        assertEquals("まちのよるに", kept?.first()?.reading)
        assertEquals(other, kept?.last())
        assertNull(carried(listOf(other), listOf(was to moved)))
    }

    @Test
    fun aKeptLineReadAgainInTheStoreKeepsWhatWasPutRightWhereItWas() {
        val store = SavedLinesStore(prefs)
        val line = kept(
            "都会の夜と都会の朝",
            Ruby("都会", "とかい"), Ruby("の"), Ruby("夜", "よる"), Ruby("と"), Ruby("都会", "まち", Reader.SUNG), Ruby("の"), Ruby("朝", "あさ")
        )
        store.addAll(listOf(line))
        val again = store.reread(listOf(now(line))).single()
        assertEquals("とかいのよるとまちのあさ", again.reading)
        assertEquals(again, store.load().single())
    }

    @Test
    fun aLineEditedByHandWhereTheSameWordIsTwiceTakesTheEditWhereItWasMade() {
        val line = kept(
            "都会の夜と都会の朝",
            Ruby("都会", "とかい"), Ruby("の"), Ruby("夜", "よる"), Ruby("と"), Ruby("都会", "とかい"), Ruby("の"), Ruby("朝", "あさ")
        )
        val read = line.readAs(listOf("とかい", "よる", "まち", "あさ"))
        assertEquals(listOf(Ruby("都会", "とかい"), Ruby("夜", "よる"), Ruby("都会", "まち", Reader.USER), Ruby("朝", "あさ")), read.read())
        assertEquals("とかいのよるとまちのあさ", read.reading)
    }

    @Test
    fun whatReadALineKeptAsSomethingElseIsntKnown() {
        prefs.edit().putString(
            "saved_lines",
            """[{"text":"三本の花","reading":"さんほんのはな","title":"Demo","artist":"Band","ruby":[["三本","さんほん"],["の",null],["花","はな"]],"readWith":"three"},""" +
                """{"text":"三本の木","reading":"さんほんのき","title":"Demo","artist":"Band","ruby":[["三本","さんほん"],["の",null],["木","き"]],"readWith":null}]"""
        ).commit()
        val store = SavedLinesStore(prefs)
        store.load().forEach { line ->
            assertNull(line.readWith)
            // Not known to be the dictionary's alone, it isn't read again as an older one's.
            assertFalse(line.readByOlder)
        }
        // Kept again, it's still not said.
        store.reread(emptyList())
        assertFalse("readWith" in prefs.getString("saved_lines", null).orEmpty())
    }

    @Test
    fun aLineReadAgainIsPractisedAsFarAsItWas() {
        val store = SavedLinesStore(prefs)
        val line = kept("三本の花", Ruby("三本", "さんほん"), Ruby("の"), Ruby("花", "はな"))
            .copy(interval = 12, ease = 230, step = 0, due = 1_234_567L, reviews = mapOf("listen" to Review(3, 250, 1, 7_654_321L)), translation = "Three flowers")
        store.addAll(listOf(line))
        val again = store.reread(listOf(now(line))).single()
        assertEquals("さんぼんのはな", again.reading)
        assertEquals(line.copy(reading = again.reading, romanized = again.romanized, ruby = again.ruby, typed = again.typed, readWith = Readings.VERSION), again)
        assertEquals(again, store.load().single())
    }

    @Test
    fun aKanjiOnlyLineIsReadAgainFromWhatItKeepsAndAChineseLineWithNoReadingIsLeft() {
        // No kana in the line, and no other line of the song has any: it was still read as Japanese.
        val kanji = SavedLine("三本", reading = "さんほん", title = "Demo", artist = "Band", ruby = listOf(Ruby("三本", "さんほん")), readWith = 3)
        val unread = SavedLine("你好", title = "Song", artist = "Singer", translation = "hola")
        // Pinyin, not kana: a Chinese line that was read stays a Chinese line. readWith alone does not make it Japanese.
        val pinyin = SavedLine("爱", reading = "ai", title = "Song", artist = "Singer", ruby = listOf(Ruby("爱", "ai")), readWith = 3)
        val marked = SavedLine("谢谢", title = "Song", artist = "Singer", translation = "gracias", readWith = 3)
        val again = readAgain(listOf(kanji, unread, pinyin, marked))
        assertNotNull("a kanji-only line was left, because no line of its song has kana", again)
        val read = again!!.first { it.text == "三本" }
        assertEquals("さんぼん", read.reading)
        assertEquals(Readings.VERSION, read.readWith)
        assertEquals(unread, again.first { it.text == "你好" })
        assertEquals(pinyin, again.first { it.text == "爱" })
        assertEquals(marked, again.first { it.text == "谢谢" })
        // Nothing Japanese in the list: leave it, rather than replace the kept lines with none.
        assertNull(readAgain(listOf(unread, pinyin, marked)))
        // The only kana is the reading over the kanji. The line's own reading field is empty.
        val rubyOnly = SavedLine("三本", title = "Demo", artist = "Band", ruby = listOf(Ruby("三本", "さんほん")), readWith = 3)
        val fromRuby = readAgain(listOf(rubyOnly))
        assertNotNull(fromRuby)
        assertEquals("さんぼん", fromRuby!!.single().reading)
    }

    @Test
    fun aSessionsCardTakesTheReadingKeptNowAndOneTheRefreshDoesntHoldStays() {
        val card = SavedLine(
            "三本の花", reading = "さんほんのはな", title = "Demo", artist = "Band", interval = 4, due = 9, readWith = 3,
            ruby = listOf(Ruby("三本", "さんほん"), Ruby("の"), Ruby("花", "はな")),
        )
        val other = SavedLine("別", reading = "べつ", title = "Other", artist = "X", interval = 2, due = 1)
        val stored = card.copy(
            reading = "さんぼんのはな", romanized = "sanbon no hana", typed = "sanbonnohana", readWith = Readings.VERSION,
            ruby = listOf(Ruby("三本", "さんぼん"), Ruby("の"), Ruby("花", "はな")),
        )
        val practice = Practice(queue = listOf(card, other), total = 2, lastSong = card)
        val shown = practice.withReadings(listOf(stored))
        val first = shown.queue.first()
        assertEquals("さんぼんのはな", first.reading)
        assertEquals("sanbon no hana", first.romanized)
        assertEquals("sanbonnohana", first.typed)
        assertEquals(stored.ruby, first.ruby)
        assertEquals(Readings.VERSION, first.readWith)
        assertEquals(4, first.interval)
        assertEquals(9L, first.due)
        assertEquals(listOf("三本の花", "別"), shown.queue.map { it.text })
        assertEquals(other, shown.queue[1])
        assertEquals("さんぼんのはな", shown.lastSong?.reading)
        assertEquals(4, shown.lastSong?.interval)
        assertNull(practice.copy(lastSong = null).withReadings(listOf(stored)).lastSong)
    }

    @Test
    fun aLineWhosePartsDontWriteItIsLeftAsItWas() {
        // Kept broken: its parts write another line than it is.
        val broken = kept("都会の夜", Ruby("都会", "まち", Reader.SUNG), Ruby("の"), Ruby("朝", "あさ"))
        assertEquals(broken, broken.rereadAs(now(broken)))
    }

    @Test
    fun anEditOfACardIsLaidOverTheLineAsItsKeptNowAndOnlyWhatItChanged() {
        val seen = kept("夜の空", Ruby("夜", "よる"), Ruby("の"), Ruby("空", "そら"))
        val edited = seen.readAs(listOf("よ", "そら"))
        // Kept now with 空 put right by UtaTen meanwhile: the edit didn't change 空, so that stays.
        val now = seen.copy(reading = "よるのから", ruby = listOf(Ruby("夜", "よる"), Ruby("の"), Ruby("空", "から", Reader.SUNG)))
        val read = checkNotNull(now.editedAs(seen, edited))
        assertEquals(listOf(Ruby("夜", "よ", Reader.USER), Ruby("空", "から", Reader.SUNG)), read.read())
        assertEquals("よのから", read.reading)
        assertTrue(read.readByHand)
        // Kept as the edit makes it already, it changes nothing.
        assertNull(read.editedAs(seen, edited))
        // Nor on a line kept another way now, nor one kept with no parts now.
        assertNull(now.copy(text = "夜の海", ruby = listOf(Ruby("夜", "よる"), Ruby("の"), Ruby("海", "うみ"))).editedAs(seen, edited))
        assertNull(now.copy(ruby = null).editedAs(seen, edited))
    }

    @Test
    fun anEditOfAWordsCardTakesItsReading() {
        val word = SavedLine("都会", reading = "とかい", romanized = "tokai", title = "Demo", artist = "Band", line = "都会の夜")
        val edited = word.readAs(listOf("まち"))
        assertEquals(word.copy(reading = "まち", romanized = "machi", readByHand = true), word.editedAs(word, edited))
        assertNull(edited.editedAs(word, edited))
        // A word kept as a line with parts now isn't put right as a word.
        assertNull(word.copy(ruby = listOf(Ruby("都会", "とかい"))).editedAs(word, edited))
    }
}

/** Puts how [lines] read on the same lines kept, as the app does once a song's words are read again; see [rereadAs]. */
internal fun SavedLinesStore.reread(lines: List<SavedLine>): List<SavedLine> = checkNotNull(runBlocking { change(Dispatchers.Unconfined) { rereadAs(it, lines) } })
