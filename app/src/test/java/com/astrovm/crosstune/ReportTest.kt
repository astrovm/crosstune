package com.astrovm.crosstune

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
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
        // Not one it reads, too big, not as JSON, or not one it takes: the same report always will be.
        for (refused in listOf(400, 413, 415, 422)) {
            code = refused
            assertEquals(refused.toString(), Delivery.REFUSED, sender.send("{}"))
        }
        // Too soon, too many, the box down, or an answer that isn't the box's about the report, as a network's own
        // 403 or a 404 while the box moves: it may get there later.
        for (later in listOf(199, 300, 401, 403, 404, 405, 408, 414, 416, 421, 423, 429, 499, 500, 503)) {
            code = later
            assertEquals(later.toString(), Delivery.FAILED, sender.send("{}"))
        }
        for (sent in listOf(200, 204, 299)) {
            code = sent
            assertEquals(sent.toString(), Delivery.SENT, sender.send("{}"))
        }
    }

    /** What report/worker.js, the box, has as [name]: a number, or a product as "48 * 1024". */
    private fun boxNumber(box: String, name: String): Long =
        boxPart(box, """const $name = ([\d *]+);""").split("*").map { it.trim().toLong() }.reduce(Long::times)

    /** What [pattern]'s group matches in report/worker.js, [box]; it fails when the box has none. */
    private fun boxPart(box: String, pattern: String): String =
        checkNotNull(Regex(pattern).find(box)) { "report/worker.js has nothing like $pattern" }.groupValues[1]

    @Test
    fun everyLimitTheAppKeepsToIsTheBoxs() {
        // The box is in this repository, so a change to either side that the other doesn't follow fails here.
        val box = java.io.File("../report/worker.js").readText()
        assertEquals(boxNumber(box, "MAX_BYTES"), ReportBox.MAX_BYTES.toLong())
        assertEquals(boxNumber(box, "MAX_POINTS"), ReportBox.MAX_POINTS.toLong())
        val limits = boxPart(box, """const LIMITS = \{([^}]*)\}""")
        assertEquals(Regex("""(\w+): (\d+)""").findAll(limits).associate { it.groupValues[1] to it.groupValues[2].toInt() }, ReportBox.LIMITS)
        // A time it takes is a whole day at most, either way, and where the words had a line is never before the start.
        assertTrue(box.contains("value >= 0 && value <= 24 * 60 * 60 * 1000"))
        assertTrue(box.contains("Math.abs(value) <= 24 * 60 * 60 * 1000"))
        assertEquals(24 * 60 * 60 * 1000L, ReportBox.DAY_MS)
        // The characters it never keeps, and what it takes for a line break: the app's are the same, and no others.
        val unseen = Regex("[${boxPart(box, """const UNSEEN = /\[([^\]]+)]/g""")}]")
        val breaks = Regex(boxPart(box, """const BREAK = /([^/]+)/g"""))
        val chars = (0..0xFFFF).map { Char(it).toString() }
        assertEquals(emptyList<String>(), chars.filter { unseen.matches(it) != ReportBox.UNSEEN.matches(it) })
        assertEquals(emptyList<String>(), (chars + "\r\n").filter { breaks.matches(it) != ReportBox.BREAK.matches(it) })
    }

    @Test
    fun aTextIsCleanedAsTheBoxCleansIt() {
        fun title(text: String) = ReportBox.cut(text, "title")
        // Control characters and the marks that turn the text around go; a break or a tab is a space.
        assertEquals("ab", title("a\u0000\u0001\u001f\u007f\u0085\u009f\u202a\u202e\u2066\u2069b"))
        assertEquals("a b c d e f", title("a\nb\r\nc\rd\u2028e\tf"))
        assertEquals("a\u00adb\u200bc", title("a\u00adb\u200bc"))
        // A comment and the log keep their lines, each break made one.
        assertEquals("a\nb\nc\nd", ReportBox.cut("a\r\nb\u2029c\rd", "comment"))
        assertEquals("a\nb", ReportBox.cut("a\nb", "log"))
        // Trimmed as JavaScript trims, which takes off a byte order mark and every space, and only those.
        assertEquals("a", title("\ufeff\u00a0\u1680\u2000\u200a\u202f\u205f\u3000 a \u3000\u2029\u000b\u000c"))
        assertEquals("\u200ba\u180e", title("\u200ba\u180e"))
        // Nothing but what it never keeps, it's blank: the song isn't sent.
        assertFalse(JSONObject(Report("2.30.0", MusicMetadata("\u202e\u0007", "Band"), comment = "Wrong").json()).has("song"))
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
    fun aReportPastTheBoxsSizeLosesTheLinesThatTellLeastAndNoMore() {
        val long = "空".repeat(300)
        val lines = (0 until 300).map { HandTiming(long, it * 1000L, it * 1000L + (it % 7) * 100) }
        val player = PlayerTiming("com.example.player", 1f, 0, 0, "lead 0")
        val report = Report("2.30.0", MusicMetadata("Song", "Band"), timing = lines, player = player)
        val json = report.json()
        val bytes = json.toByteArray().size
        assertTrue(bytes.toString(), bytes <= ReportBox.MAX_BYTES)
        val sent = sentLines(report)
        val times = sent.map { it.getLong("timedMs") }
        assertTrue(times.size in 1 until 300)
        assertTrue(times.containsAll(listOf(0L, 299_000L)))
        // One more line, and the comma before it, wouldn't fit.
        assertTrue(bytes.toString(), bytes + sent.minOf { it.toString().toByteArray().size } + 1 > ReportBox.MAX_BYTES)
        assertEquals("lead 0", JSONObject(json).getJSONObject("song").getString("log"))
    }

    @Test
    fun aReportAsBigAsTheBoxTakesKeepsEveryLineAndOneByteMoreLosesOne() {
        val all = (0 until ReportBox.MAX_POINTS).map { HandTiming("空".repeat(300), it * 1000L, it * 1000L + (it % 7) * 100) }
        fun report(count: Int, comment: String) = Report("2.30.0", MusicMetadata("Song", "Band"), comment = comment, timing = all.take(count))
        // As many lines as fit, then a comment of ASCII letters, a byte each, to fill the report to the byte.
        val count = (1..all.size).last { sentLines(report(it, "x")).size == it }
        val fill = ReportBox.MAX_BYTES - report(count, "x").json().toByteArray().size + 1
        val full = report(count, "x".repeat(fill))
        assertEquals(ReportBox.MAX_BYTES, full.json().toByteArray().size)
        assertEquals(count, sentLines(full).size)
        val over = report(count, "x".repeat(fill + 1))
        assertEquals(count - 1, sentLines(over).size)
        assertTrue(over.json().toByteArray().size <= ReportBox.MAX_BYTES)
    }

    @Test
    fun aReportAsBigAsTheBoxTakesIsKeptToGoWithItsIdAndStillFits() {
        val preferences = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("report-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val reports = AutoReports(preferences)
        val song = MusicMetadata("Song", "Band")
        val all = (0 until ReportBox.MAX_POINTS).map { HandTiming("空".repeat(300), it * 1000L, it * 1000L + (it % 7) * 100) }
        val player = PlayerTiming("com.example.player", 1f, 0, 0, "lead 0")
        // One Crosstune found, and one put in time by hand: each kept to go, as the app keeps it.
        for (problem in listOf(SongProblem.SUNG_OFF, SongProblem.TIMED_BY_HAND)) {
            fun report(count: Int, comment: String) = Report("2.30.0", song, comment = comment, timing = all.take(count), problem = problem, player = player)
            // Filled to the byte without its id, as it was before the id was measured with it.
            val count = (1..all.size).last { sentLines(report(it, "x")).size == it }
            val full = report(count, "x".repeat(ReportBox.MAX_BYTES - report(count, "x").json().toByteArray().size + 1))
            assertEquals(ReportBox.MAX_BYTES, full.json().toByteArray().size)
            val id = reports.idFor(song, problem)
            val owed = reports.owe(song, problem, full.copy(id = id).json())
            // What goes is what's kept, as it is: the box, which counts its bytes as this does, takes it.
            val bytes = owed.json.toByteArray().size
            assertTrue(bytes.toString(), bytes <= ReportBox.MAX_BYTES)
            assertEquals(id, owed.id)
            // A line gives way for the id.
            assertEquals(count - 1, JSONObject(owed.json).getJSONObject("song").getJSONArray("timing").length())
        }
    }

    @Test
    fun everyTextAtItsLongestStillFitsWithoutTheLines() {
        // Four bytes a character, the most any takes, as what's escaped in two is never longer.
        fun longest(field: String) = "\uD83C\uDFB5".repeat(ReportBox.LIMITS.getValue(field))
        val player = PlayerTiming(longest("appPackage"), 1f, 0, 0, longest("log"))
        val report = Report(
            longest("version"), MusicMetadata(longest("title"), longest("artist")), app = longest("app"), android = longest("android"),
            comment = longest("comment"), source = longest("source"), player = player, problem = SongProblem.TIMED_BY_HAND,
            timing = listOf(HandTiming(longest("line"), 0, 0))
        )
        val json = JSONObject(report.json())
        assertTrue(report.json().toByteArray().size <= ReportBox.MAX_BYTES)
        assertEquals(longest("log"), json.getJSONObject("song").getString("log"))
        assertEquals(longest("comment"), json.getString("comment"))
        // And one line still fits beside them.
        assertEquals(1, sentLines(report).size)
    }

    @Test
    fun thousandsOfLinesPutInTimeStillMakeAReportTheBoxTakes() {
        val lines = (0 until 5000).map { HandTiming("空".repeat(300), it * 500L, it * 500L + (it * 37 % 11) * 10) }
        val report = Report("2.30.0", MusicMetadata("Song", "Band"), timing = lines)
        val bytes = report.json().toByteArray().size
        assertTrue(bytes <= ReportBox.MAX_BYTES)
        val sent = sentLines(report)
        assertEquals(listOf(0L, 4999 * 500L), sent.map { it.getLong("timedMs") }.let { listOf(it.first(), it.last()) })
        // Past the count, the lines still go to the size, no fewer: one more, and the comma before it, wouldn't fit.
        assertTrue(bytes.toString(), bytes + sent.minOf { it.toString().toByteArray().size } + 1 > ReportBox.MAX_BYTES)
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
    fun theLineTheOnesEitherSideMissByLeastGoesFirstThenTheNextAsTheyChange() {
        // In time order: 0 heard at 0, 1000 at 1600, 2000 at 2000, 3000 at 3050, 4000 at 4000, given out of order.
        val points = listOf(2000L to 2000L, 0L to 0L, 4000L to 4000L, 1000L to 1600L, 3000L to 3050L)
        // 3000 is 50 from where 2000 and 4000 put it, 2000 is 325 early, 1000 is 600 late: 3000 goes. Then 2000 is 400
        // from where 1000 and 4000 put it, and goes; then 1000. The last goes before the first.
        assertEquals(listOf(4, 0, 3, 2, 1), ReportBox.dropOrder(points))
        // 2000 goes first, 200 off. Then 1000 is 466 off, from where 0 and 3000 put it, no longer 250, and 3000 only 267: 3000 goes.
        assertEquals(listOf(2, 3, 1, 4, 0), ReportBox.dropOrder(listOf(0L to 100L, 1000L to 800L, 2000L to 2000L, 3000L to 3600L, 4000L to 4600L)))
        assertEquals(listOf(0, 1, 2, 3), ReportBox.telling(points, 4))
        assertEquals(listOf(1, 2, 3), ReportBox.telling(points, 3))
        assertEquals(listOf(1, 2), ReportBox.telling(points, 2))
        assertEquals(listOf(1), ReportBox.telling(points, 1))
        assertEquals(points.indices.toList(), ReportBox.telling(points, 6))
    }

    @Test
    fun aWrongReadingIsOfferedOnlyWhenTheBoxTakesIt() {
        val word = Word("花", "か", "ka")
        assertTrue(ReadingReport.takes(MusicMetadata("Song", "Band"), word, listOf("か")))
        assertFalse(ReadingReport.takes(MusicMetadata(" ", "Band"), word, listOf("か")))
        assertFalse(ReadingReport.takes(MusicMetadata("Song", "\u202e"), word, listOf("か")))
        assertFalse(ReadingReport.takes(MusicMetadata("Song", "Band"), Word("\u0007", "か", "ka"), listOf("か")))
        assertFalse(ReadingReport.takes(MusicMetadata("Song", "Band"), word, listOf(" ")))
        assertFalse(ReadingReport.takes(MusicMetadata("Song", "Band"), word, emptyList()))
    }

    @Test
    fun linesPutInTimeAtTheSameMomentOrTooFewToTellApartGoFromTheEnd() {
        // The middle of three at one moment is where the one before it is: it tells nothing.
        assertEquals(listOf(0, 2, 3), ReportBox.telling(listOf(0L to 0L, 0L to 0L, 0L to 7L, 5000L to 0L), 3))
        assertEquals(listOf(0), ReportBox.telling(listOf(0L to 0L, 10L to 0L), 1))
        assertEquals(emptyList<Int>(), ReportBox.telling(listOf(0L to 0L), 0))
    }
}
