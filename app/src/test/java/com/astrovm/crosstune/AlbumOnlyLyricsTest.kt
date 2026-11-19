package com.astrovm.crosstune

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AlbumOnlyLyricsTest {
    @Test
    fun onlyTheNamedInstrumentalAlbumCanUseBothMarks() = runBlocking {
        // US iTunes lookup for 41655288, 2026-11-18. No instrumental tag in the track title.
        val track = JSONObject("""{"results":[{"trackName":"Accordian","artistName":"Madlib & Madvillain",
            "collectionName":"Madvillainy Instrumentals","trackTimeMillis":113867}]}""")
        val playing = requireNotNull(MetadataParsers.appleMusic(track, ItemType.TRACK))
        assertEquals("Madvillainy Instrumentals", playing.instrumentalAlbum)

        // The three rows from LRCLIB's exact track/artist search, with invented lyrics in the sung row.
        val exact = listOf(
            """{"id":38645248,"trackName":"Accordian","artistName":"Madlib, Madvillain",
                "albumName":"Madvillainy Instrumentals","duration":114,"instrumental":true}""",
            """{"id":2523046,"trackName":"Accordian (Instrumental)","artistName":"Madvillain & Madlib",
                "albumName":"Madvillainy Instrumentals","duration":114,"instrumental":true}""",
            """{"id":2288911,"trackName":"Accordian","artistName":"Madvillain, Madlib & MF Doom",
                "albumName":"Madvillainy","duration":117,
                "syncedLyrics":"[00:10.00] Studio first\n[00:20.00] Studio second"}"""
        )
        // A second same-title mark from the wider `q=Accordian Madvillainy Instrumentals` search.
        // Put both in one answer to replay the two-vote threshold, not to claim the exact search returns both.
        val second = """{"id":13180878,"trackName":"Accordian","artistName":"Madvillain",
            "albumName":"Madvillainy Instrumentals","duration":114,"instrumental":true}"""
        suspend fun lookup(song: MusicMetadata, rows: List<String>): LyricsFinder.Lyrics.Found {
            val fake = FakeSpotify()
            fake.handler = { request ->
                val body = if (request.url.host == "lrclib.net") rows.joinToString(",", "[", "]") else "{}"
                FakeSpotify.html(request, body)
            }
            val answer = LyricsFinder(fake.client(), "test", busyPauseMs = 0).lyricsOf(song)
            return checkNotNull(answer as? LyricsFinder.Lyrics.Found) { answer.toString() }
        }

        val rows = exact + second
        val unmarked = lookup(playing.copy(instrumentalAlbum = null), rows)
        assertEquals("Studio first\nStudio second", unmarked.words)
        assertEquals(false, unmarked.markedInstrumental)
        assertEquals(false, lookup(playing, exact).markedInstrumental)
        assertEquals(unmarked.copy(markedInstrumental = true), lookup(playing, rows))
    }
}
