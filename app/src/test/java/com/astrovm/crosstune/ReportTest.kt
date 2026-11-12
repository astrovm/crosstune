package com.astrovm.crosstune

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.IOException
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
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
        assertEquals(Delivery.SENT, sender.send(report))
        assertEquals(listOf("https://report.crosstune.cc/reports"), fake.requestedUrls)
        assertEquals(report.json(), fake.requestBodies.single())
        code = 503
        assertEquals(Delivery.FAILED, sender.send(report))
        fake.handler = { throw IOException("offline") }
        assertEquals(Delivery.FAILED, sender.send(report))
    }

    private val marked = ReadingReport(
        "2.25.0", MusicMetadata("Made Up", "Some Band"), "lrclib", "空の花 again",
        Word("花", "か", "ka", by = listOf(Reader.IPADIC, Reader.UNIDIC)), listOf("か", "ka"), Script.JAPANESE, right = "  はな ", android = "17 (37)"
    )

    @Test
    fun aWrongReadingSaysOnlyTheSongTheLineTheWordHowItReadWhatReadItAndTheRightOne() {
        val json = JSONObject(marked.json())
        assertEquals(listOf("android", "problem", "reading", "song", "version"), json.keys().asSequence().sorted().toList())
        assertEquals("2.25.0", json.getString("version"))
        assertEquals("17 (37)", json.getString("android"))
        assertEquals("wrong_reading", json.getString("problem"))
        assertEquals("""{"title":"Made Up","artist":"Some Band","source":"lrclib"}""", json.getJSONObject("song").toString())
        assertEquals(
            """{"line":"空の花 again","word":"花","shown":"か \/ ka","reader":"ipadic+unidic","language":"japanese","right":"はな"}""",
            json.getJSONObject("reading").toString()
        )
    }

    @Test
    fun aWrongReadingWithoutTheRightOneOrASourceLeavesThemOut() {
        val json = JSONObject(marked.copy(source = null, right = " ", script = Script.KOREAN).json())
        assertFalse(json.getJSONObject("song").has("source"))
        assertFalse(json.getJSONObject("reading").has("right"))
        assertEquals("korean", json.getJSONObject("reading").getString("language"))
    }

    @Test
    fun aWrongReadingIsSentToTheSameBox() = runBlocking {
        val fake = FakeSpotify()
        var code = 204
        fake.handler = { request -> FakeSpotify.html(request, "", code = code) }
        val sender = ReportSender(fake.client(), "2.25.0", Dispatchers.Unconfined)
        assertEquals(Delivery.SENT, sender.send(marked))
        assertEquals(listOf(ReportSender.URL), fake.requestedUrls)
        assertEquals(marked.json(), fake.requestBodies.single())
        code = 503
        assertEquals(Delivery.FAILED, sender.send(marked))
    }

    @Test
    fun aReportSentLetsGoOfItsConnection() = runBlocking {
        val fake = FakeSpotify()
        var closed = false
        // The box's answer, which holds the connection until it's closed.
        fake.handler = { request ->
            val answer = object : ForwardingSource(Buffer()) {
                override fun close() {
                    closed = true
                    super.close()
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(204).message("No Content").body(answer.buffer().asResponseBody(null, 0)).build()
        }
        assertEquals(Delivery.SENT, ReportSender(fake.client(), "2.25.0", Dispatchers.Unconfined).send(marked))
        assertTrue(closed)
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
        assertEquals(Delivery.SENT, ReportSender(fake.client(), "2.12.0", Dispatchers.Unconfined).send("{}"))
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

    @Test
    fun theBoxTurnsAReportAwayForGoodOnlyWhenItAlwaysWould() = runBlocking {
        val fake = FakeSpotify()
        var code = 400
        fake.handler = { request -> FakeSpotify.html(request, "", code = code) }
        val sender = ReportSender(fake.client(), "2.12.0", Dispatchers.Unconfined)
        // Not one it takes, too big, or not as JSON: the same report always will be.
        for (refused in listOf(400, 404, 405, 413, 415, 499)) {
            code = refused
            assertEquals(refused.toString(), Delivery.REFUSED, sender.send("{}"))
        }
        // Too soon, too many, or the box down, it may get there later.
        for (later in listOf(408, 429, 500, 503)) {
            code = later
            assertEquals(later.toString(), Delivery.FAILED, sender.send("{}"))
        }
        code = 200
        assertEquals(Delivery.SENT, sender.send("{}"))
    }

    /** What report/worker.js, the box, has as [name]: a number, or a product as "48 * 1024". */
    private fun boxNumber(box: String, name: String): Long =
        Regex("""const $name = ([\d *]+);""").find(box)!!.groupValues[1].split("*").map { it.trim().toLong() }.reduce(Long::times)

    @Test
    fun everyLimitTheAppKeepsToIsTheBoxs() {
        // The box is in this repository, so a change to either side that the other doesn't follow fails here.
        val box = java.io.File("../report/worker.js").readText()
        assertEquals(boxNumber(box, "MAX_BYTES"), ReportBox.MAX_BYTES.toLong())
        assertEquals(boxNumber(box, "MAX_POINTS"), ReportBox.MAX_POINTS.toLong())
        val limits = Regex("""const LIMITS = \{([^}]*)\}""").find(box)!!.groupValues[1]
        assertEquals(Regex("""(\w+): (\d+)""").findAll(limits).associate { it.groupValues[1] to it.groupValues[2].toInt() }, ReportBox.LIMITS)
        // A time it takes is a whole day at most, either way, and where the words had a line is never before the start.
        assertTrue(box.contains("value >= 0 && value <= 24 * 60 * 60 * 1000"))
        assertTrue(box.contains("Math.abs(value) <= 24 * 60 * 60 * 1000"))
        assertEquals(24 * 60 * 60 * 1000L, ReportBox.DAY_MS)
    }

    /** The lines put in time in what [report] sends. */
    private fun sentLines(report: Report) = JSONObject(report.json()).getJSONObject("song").optJSONArray("timing")
        ?.let { lines -> (0 until lines.length()).map { lines.getJSONObject(it) } }.orEmpty()

    @Test
    fun ofMoreLinesPutInTimeThanTheBoxKeepsThoseThatTellLeastGo() {
        // 301 lines, all half a second late but one, which is three: each of the rest is where the two either side put it.
        val lines = (0..300).map { HandTiming("Line $it", it * 1000L, it * 1000L + if (it == 150) 3000 else 500) }
        val report = Report("2.30.0", MusicMetadata("Long", "Band"), timing = lines, android = "16 (36)")
        val sent = sentLines(report)
        assertEquals(ReportBox.MAX_POINTS, sent.size)
        val times = sent.map { it.getLong("timedMs") }
        // The first and the last, where the rest go on from, and the one off from the others stay; one in a straight run goes.
        assertTrue(times.containsAll(listOf(0L, 150_000L, 300_000L)))
        assertFalse(times.contains(1000L))
        assertEquals(times.sorted(), times)
        assertTrue(report.json().toByteArray().size <= ReportBox.MAX_BYTES)
    }

    @Test
    fun linesTheBoxTakesNoneOfAreLeftOutAndTheRestGo() {
        val day = ReportBox.DAY_MS
        val lines = listOf(
            HandTiming("Before the start", -1, 0), HandTiming("At the start", 0, -day), HandTiming("A day in", day, day),
            HandTiming("Past a day", day + 1, 0), HandTiming("Heard a day after", 0, day + 1), HandTiming("Heard a day before", 0, -day - 1)
        )
        assertEquals(listOf("At the start", "A day in"), sentLines(Report("2.30.0", MusicMetadata("Song", "Band"), timing = lines)).map { it.getString("line") })
    }

    @Test
    fun aReportPastTheBoxsSizeLosesTheLinesThatTellLeastThenTheLog() {
        val long = "空".repeat(300)
        val lines = (0 until 300).map { HandTiming(long, it * 1000L, it * 1000L + (it % 7) * 100) }
        val player = PlayerTiming("com.example.player", 1f, 0, 0, "lead 0")
        val report = Report("2.30.0", MusicMetadata("Song", "Band"), timing = lines, player = player)
        val json = report.json()
        assertTrue(json.toByteArray().size.toString(), json.toByteArray().size <= ReportBox.MAX_BYTES)
        val times = sentLines(report).map { it.getLong("timedMs") }
        assertTrue(times.size in 1 until 300)
        assertTrue(times.containsAll(listOf(0L, 299_000L)))
        assertEquals("lead 0", JSONObject(json).getJSONObject("song").getString("log"))
        // With no lines left to lose, the log goes, which only an odd one is that long.
        val odd = Report("2.30.0", MusicMetadata("Song", "Band"), player = player.copy(log = "\u0001".repeat(8192)))
        assertTrue(odd.json().toByteArray().size <= ReportBox.MAX_BYTES)
        assertFalse(JSONObject(odd.json()).getJSONObject("song").has("log"))
    }

    @Test
    fun eachTextIsCutToWhatTheBoxKeepsCountingCharactersNotHalves() {
        val title = "\uD83C\uDFB5".repeat(301)
        val report = Report("2.30.0", MusicMetadata("  $title ", "Band"), comment = "x".repeat(2001), player = PlayerTiming(null, null, null, 0, "y".repeat(8193)))
        val json = JSONObject(report.json())
        assertEquals("\uD83C\uDFB5".repeat(300), json.getJSONObject("song").getString("title"))
        assertEquals(2000, json.getString("comment").length)
        assertEquals(8192, json.getJSONObject("song").getString("log").length)
        assertEquals("x".repeat(100), JSONObject(marked.copy(right = "x".repeat(101)).json()).getJSONObject("reading").getString("right"))
    }

    @Test
    fun aSongWithoutItsTitleOrArtistIsLeftOutAsTheBoxTakesNoneSo() {
        assertFalse(JSONObject(Report("2.30.0", MusicMetadata("Song", " "), comment = "Wrong").json()).has("song"))
        assertFalse(JSONObject(Report("2.30.0", MusicMetadata("", "Band"), comment = "Wrong").json()).has("song"))
        assertEquals("Wrong", JSONObject(Report("2.30.0", MusicMetadata("", "Band"), comment = "Wrong").json()).getString("comment"))
    }

    @Test
    fun linesPutInTimeAtTheSameMomentOrTooFewToTellApartGoFromTheEnd() {
        // The middle of three at one moment is where the one before it is: it tells nothing.
        assertEquals(listOf(0, 2, 3), ReportBox.telling(listOf(0L to 0L, 0L to 0L, 0L to 7L, 5000L to 0L), 3))
        assertEquals(listOf(0), ReportBox.telling(listOf(0L to 0L, 10L to 0L), 1))
        assertEquals(emptyList<Int>(), ReportBox.telling(listOf(0L to 0L), 0))
    }
}
