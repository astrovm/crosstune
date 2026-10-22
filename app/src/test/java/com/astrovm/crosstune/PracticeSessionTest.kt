package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.random.Random

/** How a session is put together and what each card shows. Robolectric, for Android's ICU. */
@RunWith(RobolectricTestRunner::class)
class PracticeSessionTest {

    private fun line(text: String, title: String = "Demo", due: Long = 0, start: Long? = null, translation: String? = "Meaning") =
        SavedLine(text, translation = translation, title = title, artist = "Band", due = due, startMs = start, endMs = start?.plus(5_000))

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
        // One with no meaning is practised when it can be heard, and only then.
        assertEquals(listOf("Heard"), sessionOf(listOf(line("Heard", start = 1_000, translation = null), line("Half", translation = null).copy(startMs = 1_000)), now = 10).map { it.text })
        // Due this very moment, it's in.
        assertEquals(listOf("Later"), sessionOf(listOf(line("Later", due = 100, start = 1_000)), now = 100).map { it.text })
    }

    @Test
    fun cardsPractisedBeforeComeAheadOfNewOnes() {
        val learnt = (1..SESSION_SIZE).map { line("Learnt $it", due = it.toLong(), start = it * 1_000L) }
        val translating = DEFAULT_CARD_TYPES.map { if (it.id == TRANSLATE_TYPE) it.copy(on = true) else it }
        // Translate it just turned on makes a new card of every line, and none pushes back one that's due.
        val session = sessionOf(learnt + line("Just saved", "B", start = 1_000), now = 100, types = translating)
        assertEquals(learnt.map { it.text }, session.map { it.text })
        assertTrue(session.all { it.cardType == READ_TYPE })
        // With room left, new ones fill it.
        assertEquals(listOf(READ_TYPE, TRANSLATE_TYPE, READ_TYPE), sessionOf(learnt.take(2), now = 100, max = 3, types = translating).map { it.cardType })
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
        assertFalse(sameSongOf(line("A1", "A"), line("A1", "A").copy(artist = "Other band")))
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
    fun howLongALineWaitsIsWrittenShortInMinutesHoursDaysMonthsOrYears() {
        val day = 24 * 60 * 60_000L
        assertEquals("10m", waitText(10 * 60_000L, Locale.US))
        assertEquals("6m", waitText(330_000L, Locale.US))
        assertEquals("5h", waitText(5 * 60 * 60_000L, Locale.US))
        assertEquals("3d", waitText(3 * day, Locale.US))
        assertEquals("1 mth", waitText(30 * day, Locale.US))
        assertEquals("4 mths", waitText(130 * day, Locale.US))
        assertEquals("1 yr", waitText(400 * day, Locale.US))
    }
}
