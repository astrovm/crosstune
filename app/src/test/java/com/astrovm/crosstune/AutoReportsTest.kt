package com.astrovm.crosstune

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Whether problems are reported by itself, and that each song's goes once. */
@RunWith(RobolectricTestRunner::class)
class AutoReportsTest {

    private val preferences = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("auto-reports-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    private val reports = AutoReports(preferences)

    private fun song(title: String, artist: String = "Band") = MusicMetadata(title, artist)

    @Test
    fun undecidedUntilTheUserSaysAndThenRemembered() {
        assertNull(reports.allowed)
        reports.allowed = true
        assertEquals(true, AutoReports(preferences).allowed)
        reports.allowed = false
        assertEquals(false, AutoReports(preferences).allowed)
        reports.allowed = null
        assertNull(AutoReports(preferences).allowed)
    }

    @Test
    fun eachSongsProblemGoesOnceHoweverItsWritten() {
        reports.remember(song("Demo"), SongProblem.NO_LYRICS)
        assertTrue(AutoReports(preferences).reported(song("demo", "BAND"), SongProblem.NO_LYRICS))
        assertFalse(reports.reported(song("Demo"), SongProblem.NO_CHORDS))
        assertFalse(reports.reported(song("Demo", "Another"), SongProblem.NO_LYRICS))
    }

    @Test
    fun onlyTheLatestAreRemembered() {
        repeat(AutoReports.MAX_SENT + 1) { reports.remember(song("Song $it"), SongProblem.NO_TIMING) }
        assertFalse(reports.reported(song("Song 0"), SongProblem.NO_TIMING))
        assertTrue(reports.reported(song("Song 1"), SongProblem.NO_TIMING))
        // Reported again, one is the latest again, so it stays.
        reports.remember(song("Song 1"), SongProblem.NO_TIMING)
        reports.remember(song("Song new"), SongProblem.NO_TIMING)
        assertTrue(reports.reported(song("Song 1"), SongProblem.NO_TIMING))
        assertFalse(reports.reported(song("Song 2"), SongProblem.NO_TIMING))
    }

    @Test
    fun whatsKeptBrokenIsForgotten() {
        preferences.edit().putString("auto_reports_sent", "not a list").commit()
        assertFalse(reports.reported(song("Demo"), SongProblem.NO_LYRICS))
    }
}
