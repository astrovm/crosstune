package com.astrovm.crosstune

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Whether problems are reported by itself, and that each song's goes once. */
@RunWith(RobolectricTestRunner::class)
class AutoReportsTest {

    private val preferences = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("auto-reports-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    // Yes, as reports go only then: the tests of the answer itself start from none.
    private val reports = AutoReports(preferences, session = 1).also { it.allowed = true }

    private fun song(title: String, artist: String = "Band") = MusicMetadata(title, artist)

    /** A made-up report, named [name]. */
    private fun json(name: Any) = JSONObject().put("name", "$name").toString()

    /** The names of the reports, as [json] made them. */
    private fun List<AutoReports.Owed>.names() = map { JSONObject(it.json).getString("name") }

    /** [owed] went to the box. */
    private fun paid(owed: AutoReports.Owed, reports: AutoReports = this.reports) = reports.went(checkNotNull(reports.take(owed)), Delivery.SENT)

    @Test
    fun undecidedUntilTheUserSaysAndThenRemembered() {
        preferences.edit().clear().commit()
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
        preferences.edit().clear().commit()
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
        reports.owe(song("Demo"), SongProblem.TIMED_BY_HAND, json(1))
        reports.owe(song("Demo"), SongProblem.TIMED_BY_HAND, json(2))
        assertEquals(listOf("2"), AutoReports(preferences).owed().names())
        paid(reports.owed().single())
        assertEquals(emptyList<AutoReports.Owed>(), reports.owed())
        assertTrue(reports.reported(song("Demo"), SongProblem.TIMED_BY_HAND))
    }

    @Test
    fun aReportThatWentAnotherWayIsNotOwedAnyMore() {
        reports.owe(song("Demo"), SongProblem.TIMED_BY_HAND, json(1))
        reports.remember(song("Demo"), SongProblem.TIMED_BY_HAND)
        assertEquals(emptyList<AutoReports.Owed>(), reports.owed())
        // Gone from what's kept too, not only hidden.
        assertEquals("[]", preferences.getString("auto_reports_owed", null))
    }

    @Test
    fun reportsOwedForSeveralSongsAreKeptAndOneIsPutBackAlone() {
        reports.owe(song("One"), SongProblem.TIMED_BY_HAND, json(1))
        reports.owe(song("Two"), SongProblem.TIMED_BY_HAND, json(2))
        assertEquals(listOf("1", "2"), reports.owed().names())
        reports.owedNothing(song("one"))
        assertEquals(listOf("2"), reports.owed().names())
        reports.owedNothing(song("two"))
        assertEquals(emptyList<AutoReports.Owed>(), reports.owed())
    }

    @Test
    fun onlyTheLatestReportsOwedAreKept() {
        assertEquals(20, AutoReports.MAX_OWED)
        repeat(AutoReports.MAX_OWED + 1) { reports.owe(song("Song $it"), SongProblem.TIMED_BY_HAND, json(it)) }
        assertEquals((1..AutoReports.MAX_OWED).map { "$it" }, reports.owed().names())
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
        reports.owe(song("Demo"), SongProblem.TIMED_BY_HAND, json(1))
        val first = reports.owed().single()
        assertTrue(reports.owes(first))
        // Put in time again: the newer goes, not the one read before.
        reports.owe(song("Demo"), SongProblem.TIMED_BY_HAND, json(2))
        assertFalse(reports.owes(first))
        paid(reports.owed().single())
        assertFalse(reports.owes(first))
    }

    @Test
    fun everySongKeptFromAnEarlierVersionIsOwedBesideTheLatestPutInTimeByHand() {
        val songs = JSONObject()
        repeat(30) { songs.put("song$it\u0000band", JSONObject().put("words", "1").put("points", JSONArray().put(JSONArray().put(0).put(100)))) }
        preferences.edit().putString("lyrics_sync", songs.toString()).commit()
        reports.keepEarlier("2.24.0", "16 (36)")
        repeat(AutoReports.MAX_OWED + 1) { reports.owe(song("Song $it"), SongProblem.TIMED_BY_HAND, json(it)) }
        val owed = reports.owed()
        assertEquals(30, owed.count { it.earlier })
        assertEquals(AutoReports.MAX_OWED, owed.count { !it.earlier })
        // Read once: what's timed after isn't taken for it.
        paid(owed.first { it.earlier })
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
        val owed = reports.owe(song("Song"), SongProblem.TIMED_BY_HAND, json(1))
        reports.went(checkNotNull(reports.take(owed)), Delivery.REFUSED)
        assertEquals(emptyList<AutoReports.Owed>(), reports.owed())
        assertFalse(reports.reported(song("Song"), SongProblem.TIMED_BY_HAND))
    }

    @Test
    fun aProblemFoundIsOwedBesideTheWordsPutInTimeAndOnlyItsLatestAreKept() {
        reports.owe(song("Demo"), SongProblem.TIMED_BY_HAND, json("by hand"))
        repeat(AutoReports.MAX_OWED + 1) { reports.owe(song("Song $it"), SongProblem.NO_LYRICS, json(it)) }
        // The same song's other problem is owed apart, and the same problem again gives way to the newer.
        reports.owe(song("Song 20"), SongProblem.NO_CHORDS, json("chords"))
        reports.owe(song("Song 20"), SongProblem.NO_LYRICS, json("newer"))
        assertEquals(listOf("by hand") + (2 until AutoReports.MAX_OWED).map { "$it" } + listOf("chords", "newer"), reports.owed().names())
    }

    @Test
    fun aProblemTheBoxTurnedAwayIsntSentAgainButWordsPutInTimeByHandStillGo() {
        val byHand = reports.owe(song("Demo"), SongProblem.TIMED_BY_HAND, json(1))
        reports.went(checkNotNull(reports.take(byHand)), Delivery.REFUSED)
        assertEquals(emptyList<AutoReports.Owed>(), reports.owed())
        assertFalse(reports.reported(song("Demo"), SongProblem.TIMED_BY_HAND))
        val found = reports.owe(song("Demo"), SongProblem.NO_LYRICS, json(2))
        reports.went(checkNotNull(reports.take(found)), Delivery.REFUSED)
        assertTrue(reports.reported(song("Demo"), SongProblem.NO_LYRICS))
        assertEquals(emptyList<AutoReports.Owed>(), reports.owed())
        // Neither takes a place of the day's.
        assertEquals(AutoReports.FOUND_A_DAY, sent(owedOnes(AutoReports.FOUND_A_DAY)))
    }

    /** [count] reports owed, put in time by hand and found in turn, each for a song of its own. */
    private fun owedOnes(count: Int, reports: AutoReports = this.reports, from: Int = 0) =
        List(count) { reports.owe(song("Song ${from + it}"), if (it % 2 == 0) SongProblem.TIMED_BY_HAND else SongProblem.NO_LYRICS, json(from + it)) }

    /** How many of [owed] could go, and went. */
    private fun sent(owed: List<AutoReports.Owed>, reports: AutoReports = this.reports) =
        owed.mapNotNull(reports::take).onEach { reports.went(it, Delivery.SENT) }.size

    @Test
    fun onlySoManyReportsGoInADayByUtcCountingThoseOnTheirWayAndTheNextDayMoreDo() {
        var now = 3 * ReportBox.DAY_MS - 1
        val reports = AutoReports(preferences, session = 1, now = { now })
        assertEquals(23, AutoReports.FOUND_A_DAY)
        val owed = owedOnes(AutoReports.FOUND_A_DAY + 2, reports)
        val going = owed.take(AutoReports.FOUND_A_DAY).map { checkNotNull(reports.take(it)) }
        // Those on their way hold their places, so none is left for another meanwhile.
        assertNull(reports.take(owed[AutoReports.FOUND_A_DAY]))
        // One that didn't get there, or ended in an error, stays owed and gives its place back, for one more.
        reports.went(going[0], Delivery.FAILED)
        reports.went(going[1], null)
        assertTrue(reports.owes(owed[0]) && reports.owes(owed[1]))
        val more = owed.drop(AutoReports.FOUND_A_DAY).map { checkNotNull(reports.take(it)) }
        assertNull(reports.take(owed[0]))
        // Sent, each holds its place for the day, in an app started anew too.
        (going.drop(2) + more).forEach { reports.went(it, Delivery.SENT) }
        assertEquals(0, sent(owed.take(2), reports))
        assertEquals(0, sent(owed.take(2), AutoReports(preferences, now = { now })))
        now++
        assertEquals(2, sent(owed.take(2), reports))
        // What was kept of the day, broken, counts as nothing.
        preferences.edit().putString("auto_reports_found_today", "${now / ReportBox.DAY_MS}:many").commit()
        assertEquals(AutoReports.FOUND_A_DAY, sent(owedOnes(AutoReports.FOUND_A_DAY, reports, from = 100), reports))
    }

    @Test
    fun anAppEndedWithReportsOnTheirWayStillOwesThemAndTookNoneOfTheDaysPlaces() {
        val owed = owedOnes(AutoReports.FOUND_A_DAY)
        owed.forEach { checkNotNull(reports.take(it)) }
        // Started anew, as the app was ended with all of them on their way.
        AutoReports.processEnded()
        val anew = AutoReports(preferences, session = 2)
        assertEquals(owed, anew.owed())
        assertEquals(AutoReports.FOUND_A_DAY, sent(owed, anew))
    }

    @Test
    fun aReportOnItsWayIsntTakenTwiceNorOnceItWentAndHowItWentCountsOnce() {
        val owed = reports.owe(song("Demo"), SongProblem.NO_LYRICS, json(1))
        val going = checkNotNull(reports.take(owed))
        assertNull(reports.take(owed))
        reports.went(going, Delivery.SENT)
        assertNull(reports.take(owed))
        assertTrue(reports.reported(song("Demo"), SongProblem.NO_LYRICS))
        // Told twice, it counts once.
        reports.went(going, Delivery.SENT)
        assertEquals(AutoReports.FOUND_A_DAY - 1, sent(owedOnes(AutoReports.FOUND_A_DAY)))
    }

    @Test
    fun aReportReplacedWhileOwedIsntTakenAsItWas() {
        val first = reports.owe(song("Demo"), SongProblem.NO_LYRICS, json("first"))
        reports.owe(song("Demo"), SongProblem.NO_LYRICS, json("later"))
        assertNull(reports.take(first))
    }

    @Test
    fun timingKeptFromAnEarlierVersionTakesNoneOfTheDaysPlaces() {
        val songs = JSONObject()
        repeat(30) { songs.put("song$it\u0000band", JSONObject().put("points", JSONArray().put(JSONArray().put(0).put(100)))) }
        preferences.edit().putString("lyrics_sync", songs.toString()).commit()
        reports.keepEarlier("2.24.0", "16 (36)")
        assertEquals(30, sent(reports.owed()))
        assertEquals(AutoReports.FOUND_A_DAY, sent(owedOnes(AutoReports.FOUND_A_DAY)))
    }

    @Test
    fun withoutAClockGivenTheDayIsTheWallClocks() {
        val reports = AutoReports(preferences, session = 1)
        assertEquals(AutoReports.FOUND_A_DAY, sent(owedOnes(AutoReports.FOUND_A_DAY, reports), reports))
        assertEquals(0, sent(owedOnes(1, from = 100), AutoReports(preferences, session = 1, now = System::currentTimeMillis)))
    }

    @Test
    fun twoScreensOfTheAppTakeEachReportOnceAndTheDaysPlacesOnceBetweenThem() {
        // One opened from a share runs beside the one already open, on the same preferences.
        val other = AutoReports(preferences, session = 1)
        val owed = owedOnes(AutoReports.FOUND_A_DAY + 1)
        val going = checkNotNull(reports.take(owed[0]))
        assertNull(other.take(owed[0]))
        // The day's places on their way are counted for both.
        val more = owed.drop(1).take(AutoReports.FOUND_A_DAY - 1).map { checkNotNull(other.take(it)) }
        assertNull(reports.take(owed.last()))
        // How it went is told to either: the other sees it's done.
        other.went(going, Delivery.SENT)
        assertNull(reports.take(owed[0]))
        more.forEach { reports.went(it, Delivery.SENT) }
        assertEquals(0, sent(owed, other))
        assertSame(reports.sending, other.sending)
    }

    @Test
    fun twoScreensSendingAtOnceFromThreadsOfTheirOwnSendEachOnceAndNoMoreThanTheDays() {
        val owed = owedOnes(2 * AutoReports.MAX_OWED)
        val screens = List(4) { AutoReports(preferences, session = 1) }
        val start = CyclicBarrier(screens.size)
        val went = ConcurrentLinkedQueue<String>()
        screens.map { screen ->
            thread(isDaemon = true) {
                start.await(TIMEOUT_S, TimeUnit.SECONDS)
                owed.forEach { one -> screen.take(one)?.let { went += one.key; screen.went(it, Delivery.SENT) } }
            }
        }.forEach { it.join(TIMEOUT_S * 1000) }
        assertEquals(AutoReports.FOUND_A_DAY, went.size)
        // Each went once, and none of those still owed went.
        assertEquals(owed.map { it.key }.toSet() - reports.owed().map { it.key }.toSet(), went.toSet())
        assertEquals(2 * AutoReports.MAX_OWED - AutoReports.FOUND_A_DAY, reports.owed().size)
    }

    @Test
    fun reportsKeptFromThreadsOfTheirOwnAtOnceAreAllKept() {
        val screens = List(4) { AutoReports(preferences, session = 1) }
        val start = CyclicBarrier(screens.size)
        screens.mapIndexed { at, screen ->
            thread(isDaemon = true) {
                start.await(TIMEOUT_S, TimeUnit.SECONDS)
                repeat(5) { screen.owe(song("Song $at $it"), if (at % 2 == 0) SongProblem.TIMED_BY_HAND else SongProblem.NO_LYRICS, json("$at $it")) }
            }
        }.forEach { it.join(TIMEOUT_S * 1000) }
        assertEquals(20, reports.owed().size)
    }

    /** A made-up report, named [name], made with [id]. */
    private fun json(name: Any, id: String) = JSONObject().put("name", "$name").put(AutoReports.ID, id).toString()

    @Test
    fun eachReportIsMadeWithAnIdOfItsOwnKeptWhenTheSameReportIsMadeAgain() {
        val id = reports.idFor(song("Demo"), SongProblem.TIMED_BY_HAND)
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(id))
        // Before it's kept, another screen of the app making it gets the same.
        assertEquals(id, AutoReports(preferences).idFor(song("demo", "BAND"), SongProblem.TIMED_BY_HAND))
        // Kept as it was made: its id was measured with it, so nothing is added.
        val first = reports.owe(song("Demo"), SongProblem.TIMED_BY_HAND, json(1, id))
        assertEquals(json(1, id), first.json)
        assertEquals(id, first.id)
        // Put in time some more: the newer is the same report, so the box keeps one of them.
        assertEquals(id, reports.idFor(song("Demo"), SongProblem.TIMED_BY_HAND))
        // Another song, or another problem of it, is another report.
        val others = listOf(reports.idFor(song("Other"), SongProblem.TIMED_BY_HAND), reports.idFor(song("Demo"), SongProblem.NO_LYRICS))
        assertEquals(3, (others + id).toSet().size)
        // Turned away, then put in time again: a new report.
        reports.went(checkNotNull(reports.take(first)), Delivery.REFUSED)
        assertFalse(reports.idFor(song("Demo"), SongProblem.TIMED_BY_HAND) in (others + id))
    }

    @Test
    fun aReportAnOlderVersionKeptWithoutAnIdGetsOneWhenMadeAgain() {
        val key = "${SongProblem.TIMED_BY_HAND.key}\u0000demo\u0000band"
        preferences.edit().putString("auto_reports_owed", JSONArray().put(JSONObject().put("key", key).put("json", json(1))).toString()).commit()
        val old = reports.owed().single()
        assertNull(old.id)
        assertEquals(json(1), old.json)
        assertTrue(Regex("[0-9a-f-]{36}").matches(reports.idFor(song("Demo"), SongProblem.TIMED_BY_HAND)))
    }

    @Test
    fun timingKeptFromAnEarlierVersionCarriesAnIdForEachSong() {
        val songs = JSONObject()
        repeat(2) { songs.put("song$it\u0000band", JSONObject().put("points", JSONArray().put(JSONArray().put(0).put(100)))) }
        preferences.edit().putString("lyrics_sync", songs.toString()).commit()
        reports.keepEarlier("2.24.0", "16 (36)")
        assertEquals(2, reports.owed().mapNotNull { it.id }.toSet().size)
    }

    @Test
    fun timingKeptFromAnEarlierVersionAtItsBiggestStillFitsTheBox() {
        // Every text past what the box keeps, of the most bytes a character takes, and more lines than it takes, far apart.
        val long = "\uD83C\uDFB5".repeat(1000)
        val key = "$long\u0000$long\u0000$long\u0000${Long.MAX_VALUE}"
        val points = JSONArray((0..ReportBox.MAX_POINTS).map { JSONArray().put(ReportBox.DAY_MS - it * 7919L).put(-ReportBox.DAY_MS + it * 104_729L) })
        preferences.edit().putString("lyrics_sync", JSONObject().put(key, JSONObject().put("points", points).put("words", long)).toString()).commit()
        reports.keepEarlier("9".repeat(100), "9".repeat(100))
        val owed = reports.owed().single()
        val bytes = owed.json.toByteArray().size
        assertTrue(bytes.toString(), bytes <= ReportBox.MAX_BYTES)
        assertEquals(ReportBox.MAX_POINTS, JSONObject(owed.json).getJSONObject("song").getJSONArray("timing").length())
        assertEquals(ReportBox.LIMITS.getValue("version"), JSONObject(owed.json).getString("version").length)
    }

    @Test
    fun aNoDeletesTheProblemsFoundThatWaitAndKeepsWhatWasPutInTimeByHand() {
        fun found() {
            reports.owe(song("No words"), SongProblem.NO_LYRICS, json("no words"))
            reports.owe(song("Off"), SongProblem.SUNG_OFF, json("off"))
        }
        found()
        reports.owe(song("By hand"), SongProblem.TIMED_BY_HAND, json("by hand"))
        preferences.edit().putString("lyrics_sync", JSONObject().put("earlier\u0000band", JSONObject().put("points", JSONArray().put(JSONArray().put(0).put(100)))).toString()).commit()
        reports.keepEarlier("2.24.0", "16 (36)")
        val earlier = reports.owed().single { it.earlier }
        // A yes keeps them all to go.
        reports.allowed = true
        assertEquals(listOf("no words", "off", "by hand"), reports.owed().filterNot { it.earlier }.names())
        // Turned off, the problems found are gone, and turned on again, they don't go.
        reports.allowed = false
        assertEquals(listOf("by hand"), reports.owed().filterNot { it.earlier }.names())
        assertEquals(listOf(earlier), reports.owed().filter { it.earlier })
        reports.allowed = true
        assertEquals(listOf("by hand"), reports.owed().filterNot { it.earlier }.names())
        // On its way as the no is said, and it didn't get there: it's still gone.
        found()
        val going = checkNotNull(reports.take(reports.owed().first { JSONObject(it.json).optString("name") == "no words" }))
        reports.allowed = false
        reports.went(going, Delivery.FAILED)
        assertEquals(listOf("by hand"), reports.owed().filterNot { it.earlier }.names())
        // Turned off, a problem found isn't kept, words put in time by hand are, and none goes, nor before an answer.
        reports.owe(song("Later"), SongProblem.NO_TIMING, json("later"))
        reports.owe(song("By hand again"), SongProblem.TIMED_BY_HAND, json("again"))
        assertEquals(listOf("by hand", "again"), reports.owed().filterNot { it.earlier }.names())
        for (owed in reports.owed()) assertNull(reports.take(owed))
        reports.allowed = null
        for (owed in reports.owed()) assertNull(reports.take(owed))
        reports.allowed = true
        reports.went(checkNotNull(reports.take(earlier)), Delivery.FAILED)
        // Not answered, they stay; a third close is a no, as one in Settings is.
        found()
        reports.allowed = null
        assertEquals(4, reports.owed().count { !it.earlier })
        repeat(AutoReports.MAX_CLOSES) { reports.closed() }
        assertEquals(false, reports.allowed)
        assertEquals(listOf("by hand", "again"), reports.owed().filterNot { it.earlier }.names())
    }

    @Test
    fun wordsTimedAgainAfterTheirReportWentTakeNoRoomFromOnesStillToGo() {
        reports.owe(song("Still to go"), SongProblem.TIMED_BY_HAND, json("left"))
        repeat(AutoReports.MAX_OWED) {
            reports.remember(song("Song $it"), SongProblem.TIMED_BY_HAND)
            reports.owe(song("Song $it"), SongProblem.TIMED_BY_HAND, json(it))
        }
        assertEquals(listOf("left"), reports.owed().names())
        assertEquals(1, JSONArray(preferences.getString("auto_reports_owed", null)).length())
    }

    @Test
    fun anOlderReportTurnedAwayLeavesTheNewerOneForTheSameWordsOwed() {
        val older = reports.owe(song("Demo"), SongProblem.TIMED_BY_HAND, json(1))
        val going = checkNotNull(reports.take(older))
        // Put in time some more while the older is on its way.
        reports.owe(song("Demo"), SongProblem.TIMED_BY_HAND, json(2))
        reports.went(going, Delivery.REFUSED)
        assertEquals(listOf("2"), reports.owed().names())
    }

    private companion object {
        const val TIMEOUT_S = 10L
    }
}
