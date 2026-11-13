package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What a player says it plays, and whether it names a song, as the bar and listening go by. Names are
 * made up, apart from a radio station's, and each is as an app on a phone sent it.
 */
@RunWith(RobolectricTestRunner::class)
class SonglessTest {

    private fun noSong(title: String, artist: String = "", app: String? = "Fake Player") = Songless.namesNoSong(title, artist, app)

    @Test
    fun aRadioStationAloneNamesNoSong() {
        // As VRadio names RTP Antena 2: the station, no artist, and its own name as the album.
        assertTrue(noSong("RTP Antena 2", "", "VRadio"))
        // As Radio Garden names one: the station, and where it is as the artist.
        assertTrue(noSong("Fake FM", "<unknown>", "Radio Garden"))
        // An app that puts its own name where the artist goes.
        assertTrue(noSong("Fake FM", "VRadio", "VRadio"))
        assertTrue(noSong("Fake FM", " vradio ", "VRadio"))
    }

    @Test
    fun noTitleNamesNoSong() {
        assertTrue(noSong("", "Fake Artist"))
        assertTrue(noSong("   ", ""))
    }

    @Test
    fun aFileNamesNoSong() {
        assertTrue(noSong("Fake Song.mp3", "<unknown>"))
        assertTrue(noSong("fake_song.flac", ""))
        assertTrue(noSong("Fake Song.m4a", "Unknown"))
        assertTrue(noSong("Fake Song", "Unknown Artist"))
        assertTrue(noSong("Track 7.mp3", "<unknown>"))
        assertTrue(noSong("Pista 03", ""))
        assertTrue(noSong("AUD-20240101-WA0003", ""))
        assertTrue(noSong("AUD-20240101-WA0003.opus", "<unknown>"))
        assertTrue(noSong("VID_20240101_123456.mp4", ""))
        assertTrue(noSong("/storage/emulated/0/Music/Fake Artist - Fake Song.mp3", ""))
        assertTrue(noSong("content://media/external/audio/media/42", ""))
        assertTrue(noSong("file:///sdcard/Download/fake.ogg", "Fake Artist"))
    }

    @Test
    fun aSongWithItsArtistIsOne() {
        // As Spotify, YouTube Music, Apple Music and Poweramp with tags name theirs.
        assertFalse(noSong("Fake Song", "Fake Artist", "Spotify"))
        assertFalse(noSong("Fake Song (Remastered 2011)", "Fake Artist", "YouTube Music"))
        assertFalse(noSong("Fake Song", "Fake Artist & Other Fake", "Apple Music"))
        assertFalse(noSong("Fake Song", "Fake Artist", "Poweramp"))
        // No app's name to tell, as when Android won't say it.
        assertFalse(noSong("Fake Song", "Fake Artist", null))
        // Numbers in a song's name aren't a file's.
        assertFalse(noSong("Song 2", "Fake Band"))
        assertFalse(noSong("Summer of 1999", "Fake Artist"))
        assertFalse(noSong("Track 01 Remix Edition", "Fake Artist"))
        // A song an artist named for its place on the album, as some do.
        assertFalse(noSong("Track 10", "Fake Artist"))
        assertFalse(noSong("Pista 03", "Fake Band"))
    }

    @Test
    fun aSongInItsTitleAloneIsOne() {
        // A file, or a station that sends what it plays, "Artist - Song", as ICY does.
        assertFalse(noSong("Fake Artist - Fake Song.mp3", "<unknown>"))
        assertFalse(noSong("Fake Artist - Fake Song", "", "VRadio"))
        assertFalse(noSong("Fake Artist - Fake Song", "VRadio", "VRadio"))
    }

    @Test
    fun theAppsOwnNameIsNoArtistOnlyWhenItIsTheApps() {
        assertEquals("", Songless.ownName("VRadio", "VRadio"))
        assertEquals("Fake Artist", Songless.ownName("Fake Artist", "VRadio"))
        assertEquals("VRadio", Songless.ownName("VRadio", null))
    }
}
