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
    private val reports = AutoReports(preferences, session = 1)

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

    @Test
    fun theOfferIsMadeOnceAnAppSessionAndAgainOnceTheAppStartsAnew() {
        assertTrue(reports.mayAsk())
        reports.asked()
        assertFalse(reports.mayAsk())
        assertFalse(AutoReports(preferences, session = 1).mayAsk())
        assertTrue(AutoReports(preferences, session = 2).mayAsk())
    }

    @Test
    fun theOfferClosedIsNotANoUntilTheThirdTime() {
        reports.closed()
        reports.closed()
        assertNull(reports.allowed)
        reports.closed()
        assertEquals(false, AutoReports(preferences).allowed)
        // The switch in Settings still wins, and turned on, it isn't turned off by what was counted.
        reports.allowed = true
        assertEquals(true, AutoReports(preferences).allowed)
    }

    @Test
    fun aReportOwedIsKeptUntilItGoesAndThenForgotten() {
        assertEquals(emptyList<AutoReports.Owed>(), reports.owed())
        reports.owe(song("Demo"), """{"fake":1}""")
        reports.owe(song("Demo"), """{"fake":2}""")
        assertEquals(listOf("""{"fake":2}"""), AutoReports(preferences).owed().map { it.json })
        reports.paid(reports.owed().single())
        assertEquals(emptyList<AutoReports.Owed>(), reports.owed())
        assertTrue(reports.reported(song("Demo"), SongProblem.TIMED_BY_HAND))
    }

    @Test
    fun aReportThatWentAnotherWayIsNotOwedAnyMore() {
        reports.owe(song("Demo"), "{}")
        reports.remember(song("Demo"), SongProblem.TIMED_BY_HAND)
        assertEquals(emptyList<AutoReports.Owed>(), reports.owed())
        // Gone from what's kept too, not only hidden.
        assertEquals("[]", preferences.getString("auto_reports_owed", null))
    }

    @Test
    fun reportsOwedForSeveralSongsAreKeptAndOneIsPutBackAlone() {
        reports.owe(song("One"), "1")
        reports.owe(song("Two"), "2")
        assertEquals(listOf("1", "2"), reports.owed().map { it.json })
        reports.owedNothing(song("one"))
        assertEquals(listOf("2"), reports.owed().map { it.json })
        reports.owedNothing(song("two"))
        assertEquals(emptyList<AutoReports.Owed>(), reports.owed())
    }

    @Test
    fun onlyTheLatestReportsOwedAreKept() {
        assertEquals(20, AutoReports.MAX_OWED)
        repeat(AutoReports.MAX_OWED + 1) { reports.owe(song("Song $it"), "$it") }
        assertEquals((1..AutoReports.MAX_OWED).map { "$it" }, reports.owed().map { it.json })
    }

    @Test
    fun reportsOwedThatWereKeptBrokenAreForgotten() {
        listOf("not a list", "[{\"key\":1}]", "[3]").forEach {
            preferences.edit().putString("auto_reports_owed", it).commit()
            assertEquals(emptyList<AutoReports.Owed>(), reports.owed())
        }
    }
}
