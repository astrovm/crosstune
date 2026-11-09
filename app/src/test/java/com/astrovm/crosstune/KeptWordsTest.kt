package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Runs under Robolectric for the real org.json implementation. */
@RunWith(RobolectricTestRunner::class)
class KeptWordsTest {

    private val lines = listOf(
        LyricLine(1_000, "晴れた日に", words = listOf(TimedWord(1_000, "晴れた"), TimedWord(1_600, "日に")), endMs = 2_400),
        LyricLine(3_000, "Walking down the river road")
    )

    private val all = KeptWords(
        lyrics = "晴れた日に\nWalking down the river road",
        lines = lines,
        synced = true,
        found = LyricsFinder.Lyrics.Found("晴れた日に\nWalking down the river road", lines, lengthMs = 200_000, source = "netease, timed by lrclib"),
        foundLines = lines.take(1),
        japanese = listOf("晴れた日に"),
        syncPoints = listOf(SyncPoint(1_000, 1_250)),
        learning = Learning(
            script = Script.JAPANESE,
            lines = listOf(LineReading(listOf(Ruby("晴", "は"), Ruby("れた")), "はれた", "hareta", typed = "hareta")),
            translations = listOf("On a sunny day", null),
            fromRomaji = true
        ),
        madeWith = "[en, [], true]",
        chords = ChordsState(tried = true, sheet = ChordSheet(listOf(SheetLine("Walking down", listOf(Chord(0, "G"), Chord(8, "C")))), capo = 2, site = ChordSite.ULTIMATE_GUITAR, url = "https://tabs.ultimate-guitar.com/tab/demo-1")),
        pace = 0.97
    )

    @Test
    fun wordsKeptOnDiskReadBackAsTheyWereLeft() {
        assertEquals(all, KeptWords.from(all.json()))
    }

    @Test
    fun wordsWithNothingWorkedOutForThemReadBackSo() {
        val bare = KeptWords("Hello", listOf(LyricLine(0, "Hello")), false, null, emptyList(), null, emptyList(), Learning(), "[]", ChordsState())
        assertEquals(bare, KeptWords.from(bare.json()))
    }

    @Test
    fun readingsKeptOverKanaAmongKanjiReadBackOverTheKanjiOnly() {
        val flying = all.copy(learning = all.learning.copy(lines = listOf(LineReading(listOf(Ruby("飛び立", "とびた"), Ruby("つ")), "とびたつ", "tobitatsu"))))
        val back = KeptWords.from(flying.json())!!
        assertEquals(listOf(Ruby("飛", "と"), Ruby("び"), Ruby("立", "た"), Ruby("つ")), back.learning.lines.single().parts)
    }

    @Test
    fun readingsKeptOverKanaTheySpellAnotherWayReadBackOverTheKanjiOnly() {
        val today = all.copy(learning = all.learning.copy(lines = listOf(LineReading(listOf(Ruby("今日は", "こんにちわ")), "こんにちわ", "konnichiwa"))))
        val back = KeptWords.from(today.json())!!
        assertEquals(listOf(Ruby("今日", "こんにち"), Ruby("は", "わ")), back.learning.lines.single().parts)
    }

    @Test
    fun wordsKeptBeforeTheirPaceWasGoOnAsTimed() {
        val before = org.json.JSONObject(all.json()).apply { remove("pace") }.toString()
        assertEquals(all.copy(pace = 1.0), KeptWords.from(before))
    }

    @Test
    fun wordsThatCantBeReadAreWorkedOutAgain() {
        assertNull(KeptWords.from("not json"))
        assertNull(KeptWords.from("{}"))
        // A script this version doesn't know.
        assertNull(KeptWords.from(all.json().replace("\"JAPANESE\"", "\"KLINGON\"")))
    }
}
