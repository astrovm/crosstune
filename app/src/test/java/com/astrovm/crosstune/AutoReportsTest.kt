package com.astrovm.crosstune

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
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
        reports.owe(song("Demo"), SongProblem.TIMED_BY_HAND, """{"fake":1}""")
        reports.owe(song("Demo"), SongProblem.TIMED_BY_HAND, """{"fake":2}""")
        assertEquals(listOf("""{"fake":2}"""), AutoReports(preferences).owed().map { it.json })
        reports.paid(reports.owed().single())
        assertEquals(emptyList<AutoReports.Owed>(), reports.owed())
        assertTrue(reports.reported(song("Demo"), SongProblem.TIMED_BY_HAND))
    }

    @Test
    fun aReportThatWentAnotherWayIsNotOwedAnyMore() {
        reports.owe(song("Demo"), SongProblem.TIMED_BY_HAND, "{}")
        reports.remember(song("Demo"), SongProblem.TIMED_BY_HAND)
        assertEquals(emptyList<AutoReports.Owed>(), reports.owed())
        // Gone from what's kept too, not only hidden.
        assertEquals("[]", preferences.getString("auto_reports_owed", null))
    }

    @Test
    fun reportsOwedForSeveralSongsAreKeptAndOneIsPutBackAlone() {
        reports.owe(song("One"), SongProblem.TIMED_BY_HAND, "1")
        reports.owe(song("Two"), SongProblem.TIMED_BY_HAND, "2")
        assertEquals(listOf("1", "2"), reports.owed().map { it.json })
        reports.owedNothing(song("one"))
        assertEquals(listOf("2"), reports.owed().map { it.json })
        reports.owedNothing(song("two"))
        assertEquals(emptyList<AutoReports.Owed>(), reports.owed())
    }

    @Test
    fun onlyTheLatestReportsOwedAreKept() {
        assertEquals(20, AutoReports.MAX_OWED)
        repeat(AutoReports.MAX_OWED + 1) { reports.owe(song("Song $it"), SongProblem.TIMED_BY_HAND, "$it") }
        assertEquals((1..AutoReports.MAX_OWED).map { "$it" }, reports.owed().map { it.json })
    }

    @Test
    fun reportsOwedThatWereKeptBrokenAreForgotten() {
        listOf("not a list", "[{\"key\":1}]", "[3]").forEach {
            preferences.edit().putString("auto_reports_owed", it).commit()
            assertEquals(emptyList<AutoReports.Owed>(), reports.owed())
        }
    }

    @Test
    fun aReportOwedIsStillOwedOnlyAsItWasKept() {
        reports.owe(song("Demo"), SongProblem.TIMED_BY_HAND, "1")
        val first = reports.owed().single()
        assertTrue(reports.owes(first))
        // Put in time again: the newer goes, not the one read before.
        reports.owe(song("Demo"), SongProblem.TIMED_BY_HAND, "2")
        assertFalse(reports.owes(first))
        reports.paid(reports.owed().single())
        assertFalse(reports.owes(first))
    }

    @Test
    fun everySongKeptFromAnEarlierVersionIsOwedBesideTheLatestPutInTimeByHand() {
        val songs = JSONObject()
        repeat(30) { songs.put("song$it\u0000band", JSONObject().put("words", "1").put("points", JSONArray().put(JSONArray().put(0).put(100)))) }
        preferences.edit().putString("lyrics_sync", songs.toString()).commit()
        reports.keepEarlier("2.24.0", "16 (36)")
        repeat(AutoReports.MAX_OWED + 1) { reports.owe(song("Song $it"), SongProblem.TIMED_BY_HAND, "$it") }
        val owed = reports.owed()
        assertEquals(30, owed.count { it.earlier })
        assertEquals(AutoReports.MAX_OWED, owed.count { !it.earlier })
        // Read once: what's timed after isn't taken for it.
        reports.paid(owed.first { it.earlier })
        reports.keepEarlier("2.24.0", "16 (36)")
        assertEquals(29, reports.owed().count { it.earlier })
    }

    @Test
    fun aSongKeptBeforeWithMoreLinesThanTheBoxTakesSendsThoseThatTellMost() {
        val points = JSONArray().apply { repeat(301) { put(JSONArray().put(it * 1000L).put(it * 1000L + if (it == 150) 3000 else 5)) } }
        preferences.edit().putString("lyrics_sync", JSONObject().put("song\u0000band\u0000123", JSONObject().put("points", points)).toString()).commit()
        reports.keepEarlier("2.24.0", "16 (36)")
        val song = JSONObject(reports.owed().single().json).getJSONObject("song")
        val timing = song.getJSONArray("timing")
        assertEquals(300, timing.length())
        val times = (0 until timing.length()).map { timing.getJSONObject(it).getLong("timedMs") }
        // The ends and the line off from the rest stay; one in a straight run goes.
        assertTrue(times.containsAll(listOf(0L, 150_000L, 300_000L)))
        assertFalse(times.contains(1000L))
        // A key with a length and no recording.
        assertEquals(123L, song.getLong("lengthMs"))
        assertFalse(song.has("recording"))
    }

    @Test
    fun aSongKeptBeforeWithAnEmptyLastPartSendsNoLengthNorRecording() {
        val point = JSONArray().put(JSONArray().put(0).put(100))
        preferences.edit().putString("lyrics_sync", JSONObject().put("song\u0000band\u0000", JSONObject().put("points", point)).toString()).commit()
        reports.keepEarlier("2.24.0", "16 (36)")
        val song = JSONObject(reports.owed().single().json).getJSONObject("song")
        assertFalse(song.has("lengthMs"))
        assertFalse(song.has("recording"))
    }

    @Test
    fun aSongKeptBeforeSendsOnlyTheLinesAndTheTextsTheBoxTakes() {
        val points = JSONArray().put(JSONArray().put(-1).put(100)).put(JSONArray().put(0).put(ReportBox.DAY_MS + 1)).put(JSONArray().put(500).put(600))
        val key = "${"t".repeat(301)}\u0000band\u0000${"r".repeat(201)}\u0000"
        preferences.edit().putString("lyrics_sync", JSONObject().put(key, JSONObject().put("points", points)).toString()).commit()
        reports.keepEarlier("2.24.0", "16 (36)")
        val song = JSONObject(reports.owed().single().json).getJSONObject("song")
        assertEquals("""[{"timedMs":500,"heardMs":600}]""", song.getJSONArray("timing").toString())
        assertEquals("t".repeat(300), song.getString("title"))
        assertEquals("r".repeat(200), song.getString("recording"))
    }

    @Test
    fun aSongKeptBeforeWithNoLineTheBoxTakesIsNotOwed() {
        val points = JSONArray().put(JSONArray().put(-1).put(100))
        preferences.edit().putString("lyrics_sync", JSONObject().put("song\u0000band", JSONObject().put("points", points)).toString()).commit()
        reports.keepEarlier("2.24.0", "16 (36)")
        assertEquals(emptyList<AutoReports.Owed>(), reports.owed())
    }

    @Test
    fun aReportTheBoxTurnedAwayIsNoLongerOwedNorTakenAsSent() {
        reports.owe(song("Song"), SongProblem.TIMED_BY_HAND, "{}")
        reports.turnedAway(reports.owed().single())
        assertEquals(emptyList<AutoReports.Owed>(), reports.owed())
        assertFalse(reports.reported(song("Song"), SongProblem.TIMED_BY_HAND))
    }

    @Test
    fun aProblemFoundIsOwedBesideTheWordsPutInTimeAndOnlyItsLatestAreKept() {
        reports.owe(song("Demo"), SongProblem.TIMED_BY_HAND, "by hand")
        repeat(AutoReports.MAX_OWED + 1) { reports.owe(song("Song $it"), SongProblem.NO_LYRICS, "$it") }
        // The same song's other problem is owed apart, and the same problem again gives way to the newer.
        reports.owe(song("Song 20"), SongProblem.NO_CHORDS, "chords")
        reports.owe(song("Song 20"), SongProblem.NO_LYRICS, "newer")
        assertEquals(listOf("by hand") + (2 until AutoReports.MAX_OWED).map { "$it" } + listOf("chords", "newer"), reports.owed().map { it.json })
    }

    @Test
    fun aProblemTheBoxTurnedAwayIsntSentAgainButWordsPutInTimeByHandStillGo() {
        reports.owe(song("Demo"), SongProblem.TIMED_BY_HAND, "{}")
        reports.turnedAway(song("Demo"), SongProblem.TIMED_BY_HAND)
        assertEquals(emptyList<AutoReports.Owed>(), reports.owed())
        assertFalse(reports.reported(song("Demo"), SongProblem.TIMED_BY_HAND))
        reports.turnedAway(song("Demo"), SongProblem.NO_LYRICS)
        assertTrue(reports.reported(song("Demo"), SongProblem.NO_LYRICS))
        // Neither takes a place of the day's.
        assertTrue(List(AutoReports.FOUND_A_DAY) { reports.takePlace() }.all { it != null })
    }

    @Test
    fun onlySoManyProblemsFoundGoInADayByUtcAndTheNextDayMoreDo() {
        var now = 3 * ReportBox.DAY_MS - 1
        val reports = AutoReports(preferences, session = 1, now = { now })
        assertEquals(23, AutoReports.FOUND_A_DAY)
        val places = List(AutoReports.FOUND_A_DAY) { reports.takePlace() }.filterNotNull()
        assertEquals(AutoReports.FOUND_A_DAY, places.size)
        // The check and the count are one step, so none is left for another found meanwhile, here or anew.
        assertNull(reports.takePlace())
        assertNull(AutoReports(preferences, now = { now }).takePlace())
        // Sent, it's remembered, which takes no other place.
        reports.remember(song("Demo"), SongProblem.NO_LYRICS)
        assertNull(reports.takePlace())
        // One that didn't go gives its place back, for one more.
        reports.giveBack(places.first())
        assertEquals(listOf(true, false), List(2) { reports.takePlace() != null })
        now++
        assertTrue(reports.takePlace() != null)
        // A place of the day before is gone with it: given back, it frees none of today's.
        reports.giveBack(places.last())
        assertEquals(AutoReports.FOUND_A_DAY - 1, List(AutoReports.FOUND_A_DAY) { reports.takePlace() }.count { it != null })
        // What was kept of the day, broken, counts as nothing.
        preferences.edit().putString("auto_reports_found_today", "${now / ReportBox.DAY_MS}:many").commit()
        assertTrue(reports.takePlace() != null)
    }

    @Test
    fun withoutAClockGivenTheDayIsTheWallClocks() {
        val reports = AutoReports(preferences, session = 1)
        repeat(AutoReports.FOUND_A_DAY) { reports.takePlace() }
        assertNull(AutoReports(preferences, session = 1, now = System::currentTimeMillis).takePlace())
    }
}
