package com.astrovm.crosstune

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.IOException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReportTest {

    @Test
    fun aReportOnTheWordsSaysTheSongHowTheyFollowedAndTheVersions() {
        val report = Report("2.8.19", MusicMetadata("Moon & Song?", "Some Singer"), timed = true, synced = true, app = "YouTube Music", android = "16 (36)")
        val body = "\n\n---\nSong: Moon & Song?\nArtist: Some Singer\nLyrics: timed, put in time by hand\nFollowing: YouTube Music\nCrosstune 2.8.19, Android 16 (36)"
        assertEquals(body, report.body)
        // Escaped, so its &, ? and lines go through whole.
        val issue = Uri.parse(report.githubUrl())
        assertEquals("Lyrics of Moon & Song? by Some Singer", issue.getQueryParameter("title"))
        assertEquals(body, issue.getQueryParameter("body"))
        val email = Uri.parse(report.emailUrl().replaceFirst("mailto:", "mailto://"))
        assertEquals("Lyrics of Moon & Song? by Some Singer", email.getQueryParameter("subject"))
        assertEquals(body, email.getQueryParameter("body"))
    }

    @Test
    fun feedbackWithNoSongOnlySaysTheVersions() {
        val report = Report("2.8.19", timed = true, app = "Spotify", android = "16 (36)")
        assertEquals("", report.title)
        assertEquals("\n\n---\nCrosstune 2.8.19, Android 16 (36)", report.body)
        assertEquals("mailto:crosstune@4st.li?subject=Crosstune&body=%0A%0A---%0ACrosstune%202.8.19%2C%20Android%2016%20(36)", report.emailUrl())
        val untimed = Report("2.8.19", MusicMetadata("Song", "Band"), android = "16 (36)")
        assertEquals("\n\n---\nSong: Song\nArtist: Band\nLyrics: not timed\nCrosstune 2.8.19, Android 16 (36)", untimed.body)
    }

    @Test
    fun aReportSentSaysOnlyWhatsInIt() {
        val report = Report(
            "2.12.0", MusicMetadata("Demo", "Band"), timed = true, synced = true, app = "YouTube Music", android = "16 (36)",
            comment = "  The chorus comes late \n", sung = true, lengthMs = 220_937, timing = listOf(HandTiming("Take me", 34_210, 32_900)),
            source = "letras, timed by lrclib"
        )
        assertEquals(
            """{"version":"2.12.0","android":"16 (36)","comment":"The chorus comes late","song":{"title":"Demo","artist":"Band","app":"YouTube Music",""" +
                """"timed":true,"synced":true,"sung":true,"lengthMs":220937,"source":"letras, timed by lrclib","timing":[{"line":"Take me","timedMs":34210,"heardMs":32900}]}}""",
            report.json()
        )
        assertEquals("letras, timed by lrclib", report.source)
        // With no comment, app, length or timing by hand, they're left out; with no song, all about it is.
        val bare = JSONObject(Report("2.12.0", MusicMetadata("Demo", "Band"), android = "16 (36)", comment = " ").json())
        assertFalse(bare.has("comment"))
        assertEquals(listOf("title", "artist", "timed", "synced", "sung"), bare.getJSONObject("song").keys().asSequence().toList())
        assertEquals("""{"version":"2.12.0","android":"16 (36)"}""", Report("2.12.0", android = "16 (36)").json())
        // One Crosstune sent by itself says what it found.
        assertEquals("no_chords", JSONObject(Report("2.12.0", MusicMetadata("Demo", "Band"), problem = SongProblem.NO_CHORDS).json()).getString("problem"))
    }

    @Test
    fun aReportAboutTimingAlsoSaysTheAppItsSpeedTheLeadAndTheLog() {
        val report = Report(
            "2.22.3", MusicMetadata("Demo", "Band"), timed = true, synced = true, app = "KDE Connect", android = "17 (37)",
            timing = listOf(HandTiming("Take me", 34_210, 32_900)),
            problem = SongProblem.TIMED_BY_HAND,
            player = PlayerTiming("org.kde.kdeconnect_tp", 1.1f, -1_450, 400, "1000 said 5000 at 1000\n2000 lines in time 1>2")
        )
        val song = JSONObject(report.json()).getJSONObject("song")
        assertEquals("org.kde.kdeconnect_tp", song.getString("appPackage"))
        // A Float's own digits (1.100000023841858) aren't sent.
        assertEquals("1.1", song.get("speed").toString())
        assertEquals(-1_450L, song.getLong("leadMs"))
        assertEquals(400L, song.getLong("earlyMs"))
        assertEquals("1000 said 5000 at 1000\n2000 lines in time 1>2", song.getString("log"))
        // Only those, on top of what a report says.
        assertEquals(
            listOf("title", "artist", "app", "timed", "synced", "sung", "appPackage", "speed", "leadMs", "earlyMs", "log", "timing"),
            song.keys().asSequence().toList()
        )
    }

    @Test
    fun whatTheAppDoesntKnowOrHasNothingForIsLeftOut() {
        val song = JSONObject(Report("2.22.3", MusicMetadata("Demo", "Band"), player = PlayerTiming(null, null, null, 0, "")).json()).getJSONObject("song")
        assertEquals(listOf("title", "artist", "timed", "synced", "sung", "earlyMs"), song.keys().asSequence().toList())
        assertEquals(0L, song.getLong("earlyMs"))
    }

    @Test
    fun aReportAboutTimingStaysWithinWhatTheBoxTakesNoMatterHowBusyTheMinuteWas() {
        var now = 5_000_000L
        TimingLog.clear()
        TimingLog.clock = { now }
        // Fingerprints are the longest notes: every one with its clocks, a few a second.
        repeat(1_000) { index ->
            now += 20
            TimingLog.note("fingerprint $index at 4000000 match 123456789 received $now speed 1.0 length 220000 app PlaybackClock(positionMs=$index, atMs=$now, speed=1.0, playing=true) video true duration 62000 lead -1450 guessed null")
        }
        val log = TimingLog.lastMinute()
        TimingLog.clock = android.os.SystemClock::elapsedRealtime
        TimingLog.clear()
        assertTrue(log.length <= TimingLog.MAX_CHARS)
        // The longest comment, and twenty lines put in time by hand with the longest words a line has, as well.
        val report = Report(
            "2.22.3", MusicMetadata("T".repeat(300), "A".repeat(300)), app = "A".repeat(100), android = "17 (37)", comment = "c".repeat(2_000),
            timing = List(20) { HandTiming("y".repeat(300), 1, 2) }, problem = SongProblem.TIMED_BY_HAND,
            player = PlayerTiming("com.spotify.music", 1f, 120_000, 400, log)
        )
        // The Worker turns away more than 48 KB.
        assertTrue(report.json().toByteArray().size.toString(), report.json().toByteArray().size < 48 * 1024)
        assertEquals(log, JSONObject(report.json()).getJSONObject("song").getString("log"))
    }

    @Test
    fun aReportIsSentToCrosstunesOwnBoxAndSaysWhetherItGotThere() = runBlocking {
        val fake = FakeSpotify()
        var code = 204
        fake.handler = { request -> FakeSpotify.html(request, "", code = code) }
        val sender = ReportSender(fake.client(), "2.12.0", Dispatchers.Unconfined)
        val report = Report("2.12.0", android = "16 (36)")
        assertTrue(sender.send(report))
        assertEquals(listOf("https://report.crosstune.4st.li/reports"), fake.requestedUrls)
        assertEquals(report.json(), fake.requestBodies.single())
        code = 503
        assertFalse(sender.send(report))
        fake.handler = { throw IOException("offline") }
        assertFalse(sender.send(report))
    }

    @Test
    fun aReportSentLetsGoOfTheAnswerSoTheConnectionIsFreeForTheNext() = runBlocking {
        val fake = FakeSpotify()
        var closed = 0
        fake.handler = { request ->
            val body = object : okhttp3.ResponseBody() {
                private val source = okio.Buffer()
                override fun contentType(): okhttp3.MediaType? = null
                override fun contentLength() = 0L
                override fun source() = source
                override fun close() {
                    closed++
                }
            }
            FakeSpotify.html(request, "").newBuilder().body(body).build()
        }
        assertTrue(ReportSender(fake.client(), "2.12.0", Dispatchers.Unconfined).send("{}"))
        assertEquals(1, closed)
    }

    @Test
    fun onlyABuildThatCanBeDebuggedSendsReportsToABoxOnThePhonesOwnAddress() {
        assertEquals("http://127.0.0.1:8787/reports", ReportSender.urlFor(debuggable = true, asked = "http://127.0.0.1:8787/reports"))
        assertEquals("http://localhost:8787/reports", ReportSender.urlFor(debuggable = true, asked = "http://localhost:8787/reports"))
        // A released one never; and nowhere but the phone itself, nor when nothing was asked.
        assertEquals(ReportSender.URL, ReportSender.urlFor(debuggable = false, asked = "http://127.0.0.1:8787/reports"))
        assertEquals(ReportSender.URL, ReportSender.urlFor(debuggable = true, asked = "https://example.com/reports"))
        assertEquals(ReportSender.URL, ReportSender.urlFor(debuggable = true, asked = "http://127.0.0.1.example.com/reports"))
        assertEquals(ReportSender.URL, ReportSender.urlFor(debuggable = true, asked = ""))
        assertEquals(ReportSender.URL, ReportSender.urlFor(debuggable = true, asked = null))
    }
}
