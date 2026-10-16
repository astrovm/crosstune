package com.astrovm.crosstune

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
}
