package com.astrovm.crosstune

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The odd shapes a kept line can have: unread, read halfway, kept by an older version, or broken. Runs under Robolectric for SharedPreferences. */
@RunWith(RobolectricTestRunner::class)
class SavedLinesEdgesTest {

    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("saved-edges-test", Context.MODE_PRIVATE)
    private val night = SavedLine(
        "都会の夜", reading = "とかいのよる", romanized = "tokai no yoru", title = "Demo", artist = "Band",
        ruby = listOf(Ruby("都会", "とかい"), Ruby("の"), Ruby("夜", "よる"))
    )

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
    }

    @Test
    fun aLineInLatinLettersIsTheOneTypedWhenAskedSoAndTheOneSaidOtherwise() {
        val line = SavedLine("先生", romanized = "sensee", typed = "sensei", title = "Demo", artist = "Band")
        assertEquals("sensei", line.latin(typed = true))
        assertEquals("sensee", line.latin(typed = false))
        // Spelled one way only, typed is the same.
        assertEquals("sensee", line.copy(typed = null).latin(typed = true))
        assertNull(line.copy(romanized = " ", typed = null).latin(typed = true))
        assertNull(SavedLine("先生", title = "Demo", artist = "Band").latin(typed = false))
    }

    @Test
    fun aWordIsReadOnlyWhenItKnowsWhereItsSungAndHasAReading() {
        assertEquals(listOf(Ruby("都会", "とかい")), SavedLine("都会", reading = "とかい", title = "Demo", artist = "Band", line = "都会の夜").readable)
        // Without the line it's from, a reading is the line's whole, not a word's.
        assertEquals(emptyList<Ruby>(), SavedLine("都会", reading = "とかい", title = "Demo", artist = "Band").readable)
        assertEquals(emptyList<Ruby>(), SavedLine("都会", title = "Demo", artist = "Band", line = "都会の夜").readable)
        // A line read piece by piece is read by those pieces with a reading.
        assertEquals(listOf(Ruby("都会", "とかい"), Ruby("夜", "よる")), night.readable)
    }

    @Test
    fun aWordReadByHandNeedsAKanaReadingAndTheLineItIsFrom() {
        val word = SavedLine("先生", reading = "せんせい", romanized = "sensee", title = "Demo", artist = "Band", line = "先生の歌")
        // Typed differently from said: を is o, as it's said, and wo, as it's typed.
        val read = word.readAs(listOf(" かぜを "))
        assertEquals("かぜを", read.reading)
        assertEquals("kazeo", read.romanized)
        assertEquals("kazewo", read.typed)
        assertTrue(read.readByHand)
        // Said and typed the same, only one is kept.
        assertNull(word.readAs(listOf("かぜ")).typed)
        // Katakana is read as hiragana.
        assertEquals("かぜ", word.readAs(listOf("カゼ")).reading)
        // Nothing to read it by, or not kana, or no line it's from: it stays as it was.
        assertEquals(word, word.readAs(emptyList()))
        assertEquals(word, word.readAs(listOf("  ")))
        assertEquals(word, word.readAs(listOf("kaze")))
        val noLine = word.copy(line = null)
        assertEquals(noLine, noLine.readAs(listOf("かぜ")))
    }

    @Test
    fun aLineReadByHandFromFewerReadingsThanItHasKeepsTheRest() {
        val read = night.readAs(listOf("まち"))
        assertEquals(listOf(Ruby("都会", "まち"), Ruby("の"), Ruby("夜", "よる")), read.ruby)
        assertEquals("まちのよる", read.reading)
        assertTrue(read.readByHand)
    }

    @Test
    fun aLineReadByHandAsItAlreadyIsStaysAsItWas() {
        assertEquals(night, night.readAs(listOf("とかい", "よる")))
    }

    @Test
    fun aLineWithoutAWholeReadingIsReadByHandFromItsPieces() {
        val bare = night.copy(reading = null, romanized = null, typed = null)
        val read = bare.readAs(listOf("まち", "よる"))
        assertEquals(listOf(Ruby("都会", "まち"), Ruby("の"), Ruby("夜", "よる")), read.ruby)
        assertEquals("まちのよる", read.reading)
        assertEquals("machi no yoru", read.romanized)
        assertTrue(read.readByHand)
    }

    @Test
    fun aLineOfAJapaneseSongKeptAsChinaWritesItLoadsAsJapanWritesItWhereverItWasSung() {
        val sung = SavedLine("终わり", reading = "おわり", title = "Song", artist = "Band", line = "仆の终わり")
        val japanese = SavedLine("歌", title = "Song", artist = "Band", line = "うた", readByHand = true, reading = "うた")
        val sungHere = SavedLine("終わり", reading = "おわり", title = "Song", artist = "Band", line = "僕の终わり", readByHand = true)
        // Its words are as Japan writes them already, but the line it's from isn't.
        SavedLinesStore(prefs).addAll(listOf(sung, japanese, sungHere))
        val loaded = SavedLinesStore(prefs).load()
        assertEquals("終わり", loaded[0].text)
        assertEquals("僕の終わり", loaded[0].line)
        assertNull(loaded[0].reading)
        // Put right by hand, a word with no pieces keeps its reading, and what it's sung in is as Japan writes it.
        assertEquals("うた", loaded[1].reading)
        assertNull(loaded[1].ruby)
        assertEquals("僕の終わり", loaded[2].line)
        assertTrue(loaded[2].readByHand)
        assertNull(loaded[2].ruby)
    }

    @Test
    fun replacingLinesLeavesTheOthersAndEmptyChangesLeaveAll() {
        val store = SavedLinesStore(prefs)
        val other = SavedLine("星", title = "Demo", artist = "Band")
        store.addAll(listOf(night, other))
        assertEquals(listOf(night, other), store.replaceAll(emptyList()))
        val changed = other.copy(translation = "Star")
        // The first of the changes that is the line is the one used.
        val replaced = store.replaceAll(listOf(night.copy(text = "x") to night, other to changed, other to other.copy(translation = "Never")))
        assertEquals(listOf(night, changed), replaced)
        assertEquals(replaced, SavedLinesStore(prefs).load())
    }

    @Test
    fun readingLinesAgainWithNothingNewLeavesThemAsTheyWere() {
        val store = SavedLinesStore(prefs)
        val other = SavedLine("星", reading = "ほし", title = "Demo", artist = "Band")
        store.addAll(listOf(night, other))
        assertEquals(listOf(night, other), store.reread(emptyList()))
        // Only the line asked is read again.
        val again = store.reread(listOf(other.copy(reading = "せい")))
        assertEquals(listOf(night, other.copy(reading = "せい")), again)
        // A line put right by hand isn't read over.
        val byHand = SavedLine("月", reading = "つき", readByHand = true, title = "Demo", artist = "Band")
        store.addAll(listOf(byHand))
        assertEquals("つき", store.reread(listOf(byHand.copy(reading = "げつ"))).first { it.text == "月" }.reading)
    }

    @Test
    fun aLineKeptWithBrokenPiecesAndReviewsStillLoadsWhatIsRight() {
        val broken = JSONObject().put("text", "夜").put("title", "Demo").put("artist", "Band")
            .put("ruby", JSONArray().put("not a pair").put(JSONArray().put("夜").put("よる")).put(JSONArray().put("の").put(JSONObject.NULL)))
            .put("reviews", JSONObject().put("cloze", JSONObject().put("interval", 4).put("step", 1).put("due", 99)).put("bad", "not a review"))
        val onlyBroken = JSONObject().put("text", "星").put("title", "Demo").put("artist", "Band").put("ruby", JSONArray().put("nope"))
        prefs.edit().putString("saved_lines", JSONArray().put(broken).put(onlyBroken).toString()).commit()
        val loaded = SavedLinesStore(prefs).load()
        assertEquals(listOf(Ruby("夜", "よる"), Ruby("の")), loaded[0].ruby)
        assertEquals(mapOf("cloze" to Review(4, 250, 1, 99)), loaded[0].reviews)
        // Nothing of the pieces is right, so it has none.
        assertNull(loaded[1].ruby)
        assertFalse(loaded[1].readByHand)
    }

    @Test
    fun aLineIsFoundInNoWordsAndInOneLetterWords() {
        assertEquals(-1, lineNow("hello", null, emptyList(), null))
        assertEquals(1, lineNow("a", null, listOf("b", "a!"), null))
        assertEquals(-1, lineNow("a", null, listOf("b", "ab"), null))
        assertEquals(-1, lineNow("ab", null, listOf("b", "c"), null))
    }
}
