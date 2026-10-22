package com.astrovm.crosstune

import org.junit.Assert.*
import org.junit.Test

class RecordingKeyTest {
    private val song = MusicMetadata("Demo", "Band")

    @Test fun anAppThatGivesNoLinkLeavesTheOneTheSongWasOpenedBy() {
        val opened = song.copy(url = "https://music.youtube.com/watch?v=abcdefghijk")
        val played = RecordingKey.asPlayed(opened, song.copy(lengthMs = 200_000, recordingId = "music:duration:200000"))
        assertEquals("abcdefghijk", RecordingKey.youTubeId(played))
        assertEquals(200_000L, played.lengthMs)
        assertEquals("music:duration:200000", played.recordingId)
        // One the app gives is the one it plays.
        assertEquals("https://example.com/other", RecordingKey.asPlayed(opened, song.copy(url = "https://example.com/other", recordingId = "music:other")).url)
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
