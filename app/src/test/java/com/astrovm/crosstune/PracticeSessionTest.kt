package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.random.Random

/** How a session is put together and what each card shows. Robolectric, for Android's ICU. */
@RunWith(RobolectricTestRunner::class)
class PracticeSessionTest {

    private fun line(text: String, title: String = "Demo", due: Long = 0, start: Long? = null, translation: String? = "Meaning", box: Int = 0) =
        SavedLine(text, translation = translation, title = title, artist = "Band", due = due, startMs = start, endMs = start?.plus(5_000), box = box)

    @Test
    fun aLineThatCanBePlayedIsReadThenHeardThenThoughtOfFromItsMeaningInTurn() {
        val faces = (0..4).map { faceOf(line("Night", start = 1_000, box = it), playable = true) }
        assertEquals(listOf(Face.LINE, Face.LISTEN, Face.MEANING, Face.LINE, Face.LISTEN), faces)
    }

    @Test
    fun aLineThatCantBePlayedTakesTurnsBetweenItsWordsAndItsMeaning() {
        assertEquals(listOf(Face.LINE, Face.MEANING, Face.LINE), (0..2).map { faceOf(line("Night", box = it), playable = false) })
    }

    @Test
    fun aLineWithNoMeaningIsHeardAndAWordIsAGapInItsLine() {
        assertEquals(Face.LISTEN, faceOf(line("Night", start = 0, translation = null), playable = true))
        assertEquals(Face.GAP, faceOf(line("sky", translation = "cielo").copy(line = "Night sky"), playable = false))
        // A word with nothing to say what it means has no gap to fill.
        assertEquals(Face.LISTEN, faceOf(line("sky", translation = null).copy(line = "Night sky"), playable = true))
    }

    @Test
    fun theWordLeftOutIsFoundWhateverItsCase() {
        assertEquals("Night $GAP tonight", withGap("Night Sky tonight", "sky"))
        assertNull(withGap("Night sky", "moon"))
        assertNull(withGap("Night sky", " "))
    }

    @Test
    fun aSessionTakesTheLinesDueLongestEachSongsTogetherInTheOrderTheyreSung() {
        val lines = listOf(
            line("B2", "B", due = 3, start = 20_000),
            line("A2", "A", due = 2, start = 30_000),
            line("A1", "A", due = 5, start = 10_000),
            line("B1", "B", due = 1, start = 5_000),
            line("Untimed", "A", due = 4),
            line("Later", "A", due = 100, start = 1_000),
            line("Nothing to practise", "A", translation = null)
        )
        // B is due longest, so its song comes first; an untimed line goes after the timed ones.
        assertEquals(listOf("B1", "B2", "A1", "A2", "Untimed"), sessionOf(lines, now = 10).map { it.text })
        // At most as many as asked, the ones due longest.
        assertEquals(listOf("B1", "B2", "A2"), sessionOf(lines, now = 10, max = 3).map { it.text })
        assertTrue(sessionOf(emptyList(), now = 10).isEmpty())
    }

    @Test
    fun aCardMissedComesBackAfterTheRestOfItsSong() {
        val queue = listOf(line("A1", "A"), line("A2", "A"), line("A3", "A"), line("B1", "B"))
        assertEquals(listOf("A2", "A3", "A1", "B1"), missedAgain(queue).map { it.text })
        assertEquals(listOf("B1"), missedAgain(listOf(line("B1", "B"))).map { it.text })
        assertTrue(missedAgain(emptyList()).isEmpty())
    }

    @Test
    fun linesOfTheSameSongAreToldApart() {
        assertTrue(sameSongOf(line("A1", "A"), line("A2", "A")))
        assertFalse(sameSongOf(line("A1", "A"), line("B1", "B")))
        assertFalse(sameSongOf(line("A1", "A"), null))
    }

    @Test
    fun soundsAreNotWorthLearningButWordsAre() {
        listOf("La la la", "lalala", "Oh, oh, oh!", "Na na na na", "Yeah yeah", "ラララ", "ナナナー", "...", "♪", "Whoa-oh", "Mmm").forEach {
            assertTrue(it, isFiller(it))
        }
        listOf("La vida loca", "Oh my love", "夜の街を歩いて", "Night sky", "Hey Jude", "あなた").forEach {
            assertFalse(it, isFiller(it))
        }
    }

    @Test
    fun anyLineOfSoundsIsFillerAndAnyWithAWordIsnt() {
        val random = Random(17)
        val sounds = listOf("la", "na", "oh", "ah", "yeah", "hey", "ooh", "da", "mm")
        val words = listOf("love", "night", "sky", "heart", "cielo", "amor")
        repeat(300) {
            val sung = List(random.nextInt(1, 6)) { sounds.random(random).replaceFirstChar { c -> if (random.nextBoolean()) c.uppercaseChar() else c } }
            val line = sung.joinToString(listOf(" ", ", ", "-", "! ").random(random))
            assertTrue(line, isFiller(line))
            val withWord = sung.toMutableList().apply { add(random.nextInt(size + 1), words.random(random)) }.joinToString(" ")
            assertFalse(withWord, isFiller(withWord))
        }
    }

    @Test
    fun aSongsLinesAreOfferedOnceEachWithHowOftenTheyreSung() {
        val offered = songLines(listOf("Night sky", "", "La la la", "night sky ", "Stars shine", "Night sky"), words = listOf("shine", " "))
        assertEquals(
            listOf(
                SongLine(0, "Night sky", times = 3, filler = false, newWords = false),
                SongLine(2, "La la la", times = 1, filler = true, newWords = false),
                SongLine(4, "Stars shine", times = 1, filler = false, newWords = true)
            ),
            offered
        )
    }

    @Test
    fun howLongALineWaitsIsWrittenShortInMinutesHoursOrDays() {
        assertEquals("10m", waitText(10 * 60_000L, Locale.US))
        assertEquals("5h", waitText(5 * 60 * 60_000L, Locale.US))
        assertEquals("3d", waitText(3 * 24 * 60 * 60_000L, Locale.US))
    }
}
