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
            comment = "  The chorus comes late \n", sung = true, lengthMs = 220_937, timing = listOf(HandTiming("Take me", 34_210, 32_900))
        )
        assertEquals(
            """{"version":"2.12.0","android":"16 (36)","comment":"The chorus comes late","song":{"title":"Demo","artist":"Band","app":"YouTube Music",""" +
                """"timed":true,"synced":true,"sung":true,"lengthMs":220937,"timing":[{"line":"Take me","timedMs":34210,"heardMs":32900}]}}""",
            report.json()
        )
        // With no comment, app, length or timing by hand, they're left out; with no song, all about it is.
        val bare = JSONObject(Report("2.12.0", MusicMetadata("Demo", "Band"), android = "16 (36)", comment = " ").json())
        assertFalse(bare.has("comment"))
        assertEquals(listOf("title", "artist", "timed", "synced", "sung"), bare.getJSONObject("song").keys().asSequence().toList())
        assertEquals("""{"version":"2.12.0","android":"16 (36)"}""", Report("2.12.0", android = "16 (36)").json())
    }

    @Test
    fun aReportIsSentToCrosstunesOwnBoxAndSaysWhetherItGotThere() = runBlocking {
        val fake = FakeSpotify()
        var code = 204
        fake.handler = { request -> FakeSpotify.html(request, "", code = code) }
        val sender = ReportSender(fake.client(), Dispatchers.Unconfined)
        val report = Report("2.12.0", android = "16 (36)")
        assertTrue(sender.send(report))
        assertEquals(listOf("https://report.crosstune.4st.li/reports"), fake.requestedUrls)
        assertEquals(report.json(), fake.requestBodies.single())
        code = 503
        assertFalse(sender.send(report))
        fake.handler = { throw IOException("offline") }
        assertFalse(sender.send(report))
    }
}
