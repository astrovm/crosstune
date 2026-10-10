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
        // Known: back after 1, 3, 7, 16, then 35 days, and 35 from then on.
        listOf(1L, 3L, 7L, 16L, 35L, 35L).forEachIndexed { index, days ->
            val kept = store.practised(line, knew = true, now = now).single()
            assertEquals(minOf(index + 1, 5), kept.box)
            assertEquals(now + days * day, kept.due)
            now = kept.due
        }
        // Missed: back in ten minutes, from the start.
        val missed = store.practised(line, knew = false, now = now).single()
        assertEquals(0, missed.box)
        assertEquals(now + 10 * 60 * 1000L, missed.due)
        // Kept as practised; another line isn't touched.
        assertEquals(missed, SavedLinesStore(prefs).load().single())
        assertEquals(listOf(missed), store.practised(line.copy(text = "Other"), knew = true, now = now))
    }

    @Test
    fun linesSavedBeforePracticeAreDueRightAway() {
        prefs.edit().putString("saved_lines", """[{"text":"風","translation":"Viento","title":"T","artist":"A"}]""").commit()
        val old = SavedLinesStore(prefs).load().single()
        assertEquals(0, old.box)
        assertEquals(0L, old.due)
    }
}
