package com.astrovm.crosstune

import org.junit.Assert.*
import org.junit.Test

class RecordingKeyTest {
    private val song = MusicMetadata("Demo", "Band")

    @Test fun anExactPlayingLinkCanKeepItsInstrumentalAlbumWhenTheSessionOmitsIt() {
        val album = "Album (Instrumentals)"
        val opened = song.copy(url = "https://www.deezer.com/us/track/123", album = album)
        val played = song.copy(url = "https://www.deezer.com/track/123", recordingId = "deezer:123")
        assertEquals(album, RecordingKey.asPlayed(opened, played).instrumentalAlbum)
        assertNull(RecordingKey.asPlayed(opened, played.copy(url = "https://www.deezer.com/track/456")).instrumentalAlbum)
        assertEquals(album, RecordingKey.asPlayed(opened.copy(recordingId = played.recordingId), played.copy(url = null)).instrumentalAlbum)
        val opaque = played.copy(url = null, recordingId = "deezer:catalog:duration:200000")
        assertEquals(album, RecordingKey.asPlayed(opened.copy(recordingId = opaque.recordingId), opaque).instrumentalAlbum)
        val approximate = song.copy(recordingId = "deezer:duration:200000")
        assertNull(RecordingKey.asPlayed(opened.copy(recordingId = approximate.recordingId), approximate).instrumentalAlbum)
        // A player that names no recording says nothing of the one opened, whatever the opened one names.
        assertNull(RecordingKey.asPlayed(opened.copy(recordingId = "deezer:123"), song.copy(lengthMs = 200_000)).instrumentalAlbum)
        assertNull(RecordingKey.asPlayed(opened, song.copy(recordingId = "player:other")).instrumentalAlbum)
    }

    @Test fun anAppThatGivesNoLinkLeavesTheOneTheSongWasOpenedBy() {
        val opened = song.copy(url = "https://music.youtube.com/watch?v=abcdefghijk")
        val played = RecordingKey.asPlayed(opened, song.copy(lengthMs = 200_000, recordingId = "music:duration:200000"))
        assertEquals("abcdefghijk", RecordingKey.youTubeId(played))
        assertEquals(200_000L, played.lengthMs)
        assertEquals("music:duration:200000", played.recordingId)
        // One the app gives is the one it plays.
        assertEquals("https://example.com/other", RecordingKey.asPlayed(opened, song.copy(url = "https://example.com/other", recordingId = "music:other")).url)
    }

    @Test fun theLengthItWasHeardOrOpenedAtStaysAHintToItsTakes() {
        val silent = song.copy(recordingId = "music:one")
        val played = RecordingKey.asPlayed(song.copy(lengthMs = 200_000), silent)
        assertNull(played.lengthMs)
        assertEquals(200_000L, played.heardLengthMs)
        // Played again by what says nothing either, it's still what was heard.
        assertEquals(200_000L, RecordingKey.asPlayed(played, silent).heardLengthMs)
    }

    @Test fun recordingsAndLengthsKeepTheirOwnWords() {
        val keys = listOf(song, song.copy(lengthMs = 100_000), song.copy(lengthMs = 101_000),
            song.copy(recordingId = "player:one"), song.copy(recordingId = "player:two"),
            song.copy(url = "https://music.youtube.com/watch?v=abcdefghijk"),
            song.copy(url = "https://music.youtube.com/watch?v=lmnopqrstuv"))
            .map(RecordingKey::of)
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(RecordingKey.of(song), RecordingKey.of(song.copy(title = "DEMO", artist = "BAND")))
        assertEquals(RecordingKey.of(song), RecordingKey.of(song.copy(lengthMs = 0, url = "https://example.com")))
        assertEquals(RecordingKey.of(song), RecordingKey.of(song.copy(url = "https://open.spotify.com/artist/4Z8W4fKeB5YxbusRsdQVPb")))
        assertEquals(RecordingKey.of(song.copy(url = "https://youtu.be/abcdefghijk")), RecordingKey.of(song.copy(url = "https://www.youtube.com/watch?v=abcdefghijk&t=4")))
        assertEquals(RecordingKey.of(song.copy(recordingId = "player:one")), RecordingKey.of(song.copy(recordingId = "player:one", url = "https://youtu.be/abcdefghijk")))
    }

    @Test fun takesNothingTellsApartShareOneKey() {
        // The same title, artist and length, and a player that names neither an album nor its own recording: nothing
        // better can be known, so they share one cache entry. A named album or recording would part them.
        val one = song.copy(lengthMs = 200_000)
        val other = song.copy(lengthMs = 200_000, artworkUrl = "https://example.com/other.jpg")
        assertEquals(RecordingKey.of(one), RecordingKey.of(other))
        assertNotEquals(RecordingKey.of(one), RecordingKey.of(other.copy(album = "Album (Instrumentals)")))
        assertNotEquals(RecordingKey.of(one), RecordingKey.of(other.copy(recordingId = "player:other")))
    }

    @Test fun anAlbumOfInstrumentalsIsTheSameAlbumWhateverItsPlural() {
        val plural = song.copy(lengthMs = 200_000, album = "Album (Instrumentals)")
        val singular = plural.copy(album = "Album (Instrumental)")
        assertEquals(RecordingKey.of(plural), RecordingKey.of(singular))
        assertNotEquals(RecordingKey.of(plural), RecordingKey.of(plural.copy(album = "Other Album (Instrumentals)")))
    }

    @Test fun theLengthAKeyHoldsComesAheadOfItsAlbum() {
        val album = "Album (Instrumentals)"
        assertEquals(200_000L, RecordingKey.lengthIn("17\u0000demo\u0000band\u0000200000"))
        assertEquals(200_000L, RecordingKey.lengthIn(RecordingKey.of(song.copy(lengthMs = 200_000, album = album)).let { "17\u0000$it" }))
        assertEquals(200_000L, RecordingKey.lengthIn(RecordingKey.of(song.copy(recordingId = "a:1", lengthMs = 200_000, album = album)).let { "17\u0000$it" }))
        assertNull(RecordingKey.lengthIn("17\u0000demo\u0000band"))
        assertNull(RecordingKey.lengthIn("17\u0000" + RecordingKey.of(song.copy(album = album))))
        assertNull(RecordingKey.lengthIn("17\u0000demo\u0000band\u0000player:one"))
    }

    @Test fun onlyAnActualYouTubeLinkIdentifiesTheVideo() {
        assertEquals("abcdefghijk", RecordingKey.youTubeId(song.copy(url = "https://youtu.be/abcdefghijk")))
        assertEquals("abcdefghijk", RecordingKey.youTubeId(song.copy(url = "https://music.youtube.com/watch?v=abcdefghijk")))
        assertNull(RecordingKey.youTubeId(song))
        assertNull(RecordingKey.youTubeId(song.copy(url = "invalid")))
        assertNull(RecordingKey.youTubeId(song.copy(url = "https://open.spotify.com/track/4Z8W4fKeB5YxbusRsdQVPb")))
        assertNull(RecordingKey.youTubeId(song.copy(url = "https://www.youtube.com/playlist?list=PLabcdefghijklmn")))
    }

    @Test fun aPlayerWithoutIdsCanStillSeparateVideoEdits() {
        assertEquals("app:one", RecordingKey.playing("app", "one", 10_000))
        assertEquals("app:duration:10000", RecordingKey.playing("app", "", 10_000))
        assertEquals("app:duration:10000", RecordingKey.playing("app", null, 10_000))
        assertNull(RecordingKey.playing("app", " ", 0))
        assertNull(RecordingKey.playing("app", null, null))
    }
}
