package com.astrovm.crosstune

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Runs under Robolectric for SharedPreferences and the real org.json. */
@RunWith(RobolectricTestRunner::class)
class SavedLinesPracticeTest {

    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("practice-test", Context.MODE_PRIVATE)
    private val line = SavedLine("風が噂をはこんでく", translation = "El viento trae rumores", title = "November Blue", artist = "S.Kiyotaka & Omega Tribe")
    private val day = 24 * 60 * 60 * 1000L

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
    }

    @Test
    fun aLineKnownComesBackLaterEachTimeAndOneMissedSoon() {
        val store = SavedLinesStore(prefs)
        store.toggle(line)
        var now = 1_000_000L
        // Known: back after 1, 3, 7, 16, 35, 75, then 150 days, and 150 from then on.
        listOf(1L, 3L, 7L, 16L, 35L, 75L, 150L, 150L).forEachIndexed { index, days ->
            val kept = store.practised(line, Grade.GOOD, now = now).single()
            assertEquals(minOf(index + 1, 7), kept.box)
            assertEquals(now + days * day, kept.due)
            now = kept.due
        }
        // Missed: back in ten minutes, from the start.
        val missed = store.practised(line, Grade.AGAIN, now = now).single()
        assertEquals(0, missed.box)
        assertEquals(now + 10 * 60 * 1000L, missed.due)
        // Kept as practised; another line isn't touched.
        assertEquals(missed, SavedLinesStore(prefs).load().single())
        assertEquals(listOf(missed), store.practised(line.copy(text = "Other"), Grade.GOOD, now = now))
    }

    @Test
    fun aLineKnownEasilySkipsAStep() {
        val store = SavedLinesStore(prefs)
        store.toggle(line)
        var now = 1_000_000L
        // Easy: back after 3, 16, 75, then 150 days, two steps further each time, to the last.
        listOf(3L to 2, 16L to 4, 75L to 6, 150L to 7, 150L to 7).forEach { (days, box) ->
            val kept = store.practised(line, Grade.EASY, now = now).single()
            assertEquals(box, kept.box)
            assertEquals(now + days * day, kept.due)
            now = kept.due
        }
    }

    @Test
    fun withAllTheRoomTakenTheLineLearnedBestMakesRoom() {
        val store = SavedLinesStore(prefs)
        // The oldest is still being learned; two in the middle are known best, the older of them first.
        repeat(SavedLinesStore.MAX) { store.toggle(line.copy(text = "Line $it")) }
        listOf("Line 5", "Line 9").forEach { text ->
            repeat(3) { store.practised(store.load().first { it.text == text }, Grade.GOOD, now = 0) }
        }
        val kept = store.toggle(line.copy(text = "New"))
        assertEquals(SavedLinesStore.MAX, kept.size)
        assertEquals("New", kept.first().text)
        assertEquals(false, kept.any { it.text == "Line 5" })
        assertEquals(true, kept.any { it.text == "Line 9" } && kept.any { it.text == "Line 0" })
    }

    @Test
    fun linesSavedBeforePracticeAreDueRightAway() {
        prefs.edit().putString("saved_lines", """[{"text":"風","translation":"Viento","title":"T","artist":"A"}]""").commit()
        val old = SavedLinesStore(prefs).load().single()
        assertEquals(0, old.box)
        assertEquals(0L, old.due)
    }

    @Test
    fun savedLinesKeptBrokenAreSkippedAndSavingStillWorks() {
        // Not a list at all: nothing kept, and saving starts over.
        prefs.edit().putString("saved_lines", "{not json").commit()
        val store = SavedLinesStore(prefs)
        assertEquals(emptyList<SavedLine>(), store.load())
        assertEquals(listOf(line.copy(due = 0)), store.toggle(line))

        // A list with bits that aren't lines: only the whole ones come back, with what's missing left out.
        prefs.edit().putString("saved_lines", """[null,"x",{"text":""},{"title":"No text"},{"text":"Kept","reading":"","box":"two"}]""").commit()
        assertEquals(listOf(SavedLine("Kept", title = "", artist = "")), store.load())
    }

    @Test
    fun aLineKeepsWhereItsSungTheAppItPlayedInItsCoverAndItsLine() {
        val store = SavedLinesStore(prefs)
        val timed = line.copy(startMs = 10_000, endMs = 20_000, leadMs = 3_000, app = "com.example.music", artworkUrl = "https://example.com/cover.jpg", line = "風が噂をはこんでく夜")
        store.toggle(timed)
        assertEquals(timed, SavedLinesStore(prefs).load().single())
        assertTrue(timed.timed && timed.practisable)
        // A start at 0 is a start; one missing, or broken, isn't.
        prefs.edit().putString("saved_lines", """[{"text":"A","title":"T","artist":"B","start":0,"end":5},{"text":"B","title":"T","artist":"B","start":"x","end":5}]""").commit()
        val (zero, broken) = store.load()
        assertTrue(zero.timed)
        assertFalse(broken.timed || broken.practisable)
    }

    @Test
    fun aWholeSongsLinesAreKeptInTheirOrderWithoutTheOnesKeptAlready() {
        val store = SavedLinesStore(prefs)
        store.toggle(line)
        val kept = store.addAll(listOf(line.copy(text = "One"), line, line.copy(text = "Two"), line.copy(text = "One")))
        assertEquals(listOf("One", "Two", line.text), kept.map { it.text })
        // With all the room taken, the lines learned best make room for them.
        repeat(SavedLinesStore.MAX) { store.toggle(line.copy(text = "Line $it")) }
        store.practised(store.load().first { it.text == "Line 3" }, Grade.EASY, now = 0)
        val full = store.addAll(listOf(line.copy(text = "New")))
        assertEquals(SavedLinesStore.MAX, full.size)
        assertEquals("New", full.first().text)
        assertFalse(full.any { it.text == "Line 3" })
    }

    @Test
    fun aLineKeptBeforeItCouldBeTimedIsTimedLaterOnce() {
        val store = SavedLinesStore(prefs)
        store.toggle(line.copy(artworkUrl = "https://example.com/old.jpg"))
        val timed = store.timed(line.copy(startMs = 1_000, endMs = 2_000, app = "com.example.music", artworkUrl = "https://example.com/new.jpg")).single()
        assertEquals(1_000L, timed.startMs)
        assertEquals("com.example.music", timed.app)
        // The cover it had stays.
        assertEquals("https://example.com/old.jpg", timed.artworkUrl)
        // Timed already, it stays as it was; with no app known, the one it had stays too.
        assertEquals(timed, store.timed(line.copy(startMs = 9_000, endMs = 9_500)).single())
        store.remove(timed)
        store.toggle(line.copy(app = "com.example.old"))
        assertEquals("com.example.old", store.timed(line.copy(startMs = 1_000, endMs = 2_000)).single().app)
    }

    @Test
    fun daysPractisedInARowAreCountedOncePerDay() {
        val store = SavedLinesStore(prefs)
        store.toggle(line)
        val monday = 1_700_000_000_000L
        assertEquals(0, store.streakOn(monday))
        store.practised(line, Grade.GOOD, now = monday)
        store.practised(line, Grade.GOOD, now = monday + 60_000)
        assertEquals(1, store.streakOn(monday))
        store.practised(line, Grade.AGAIN, now = monday + day)
        assertEquals(2, store.streakOn(monday + day))
        // Still counted the day after, gone the day after that.
        assertEquals(2, store.streakOn(monday + 2 * day))
        assertEquals(0, store.streakOn(monday + 3 * day))
        // A day missed starts it over.
        store.practised(line, Grade.EASY, now = monday + 3 * day)
        assertEquals(1, store.streakOn(monday + 3 * day))
        // A line not kept counts for nothing.
        store.practised(line.copy(text = "Other"), Grade.GOOD, now = monday + 4 * day)
        assertEquals(1, store.streakOn(monday + 4 * day))
    }

    @Test
    fun howLongALineWaitsDependsOnHowWellItsKnown() {
        assertEquals(10 * 60 * 1000L, SavedLinesStore.waitFor(line, Grade.AGAIN))
        assertEquals(day, SavedLinesStore.waitFor(line, Grade.GOOD))
        assertEquals(3 * day, SavedLinesStore.waitFor(line, Grade.EASY))
        assertEquals(150 * day, SavedLinesStore.waitFor(line.copy(box = 7), Grade.EASY))
    }
}
