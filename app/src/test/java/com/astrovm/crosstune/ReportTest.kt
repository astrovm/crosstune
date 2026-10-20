package com.astrovm.crosstune

import android.net.Uri
import org.junit.Assert.assertEquals
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
}
