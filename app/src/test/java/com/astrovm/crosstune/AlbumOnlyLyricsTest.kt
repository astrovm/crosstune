package com.astrovm.crosstune

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AlbumOnlyLyricsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun catalogTrack() = requireNotNull(MetadataParsers.appleMusic(JSONObject(ACCORDIAN_INSTRUMENTAL_LOOKUP), ItemType.TRACK))

    @Test
    fun onlyTheNamedInstrumentalAlbumCanUseBothMarks() = runBlocking {
        val playing = catalogTrack()
        assertEquals("Madvillainy Instrumentals", playing.instrumentalAlbum)

        val rows = accordianLyrics()
        suspend fun lookup(song: MusicMetadata, response: String = rows): LyricsFinder.Lyrics.Found {
            val fake = FakeSpotify()
            fake.handler = { request ->
                val body = if (request.url.host == "lrclib.net") response else "{}"
                FakeSpotify.html(request, body)
            }
            val answer = LyricsFinder(fake.client(), "test", busyPauseMs = 0).lyricsOf(song)
            return checkNotNull(answer as? LyricsFinder.Lyrics.Found) { answer.toString() }
        }

        val unmarked = lookup(playing.copy(instrumentalAlbum = null, lengthMs = 117_000))
        assertEquals("Hold the light\nOut in the rain", unmarked.words)
        assertEquals(false, unmarked.markedInstrumental)
        assertEquals(unmarked.copy(markedInstrumental = true), lookup(playing))

        val tagged = JSONArray(rows).getJSONObject(1)
        for (title in listOf("Accordian - Inst.", "Accordian （伴奏版）", "Accordian (Instrumental Version)")) {
            val response = JSONArray(rows).put(1, JSONObject(tagged.toString()).put("trackName", title))
            assertEquals(title, unmarked.copy(markedInstrumental = true), lookup(playing, response.toString()))
        }
        for ((field, other) in listOf(
            "trackName" to "Accordian Extended",
            "trackName" to "Accordian (Remix) (Instrumental)",
            "trackName" to "Accordian (Instrumental Remix)",
            "trackName" to "Accordian (Vocal Mix)",
            "albumName" to "",
            "albumName" to "Other Album (Instrumentals)",
            "artistName" to "Other Artist",
            "duration" to 150
        )) {
            val changed = JSONObject(tagged.toString()).put(field, other)
            val response = JSONArray(rows).put(1, changed)
            assertEquals(changed.toString(), unmarked, lookup(playing, response.toString()))
        }
    }

    @Test
    fun anInstrumentalAlbumsOldWordsAreCheckedAgainWhenOnline() = runBlocking {
        val playing = catalogTrack()
        val cache = LookupCache(temp.newFile(), Dispatchers.Unconfined)
        cache.put("18\u0000${RecordingKey.of(playing)}", """{"words":"Older sheet","lines":[]}""")
        val fake = FakeSpotify()
        val finder = LyricsFinder(fake.client(), "test", busyPauseMs = 0, cache = cache)
        fake.handler = { throw IOException("offline") }
        assertEquals("Older sheet", (finder.lyricsOf(playing) as LyricsFinder.Lyrics.Found).words)

        val rows = accordianLyrics()
        fake.handler = { request -> FakeSpotify.html(request, if (request.url.host == "lrclib.net") rows else "{}") }
        val found = finder.lyricsOf(playing) as LyricsFinder.Lyrics.Found
        assertEquals("Hold the light\nOut in the rain", found.words)
        assertEquals(true, found.markedInstrumental)

        fake.handler = { throw AssertionError("The fresh result is cached") }
        assertEquals(found, finder.lyricsOf(playing))
    }
}

// US iTunes lookup for 41655288. The title has no instrumental tag.
internal const val ACCORDIAN_INSTRUMENTAL_LOOKUP = """{"results":[{"trackName":"Accordian","artistName":"Madlib & Madvillain",
    "collectionName":"Madvillainy Instrumentals","trackTimeMillis":113867}]}"""

// Exact LRCLIB track/artist search on 2026-11-22. The sung row has made-up words.
internal fun accordianLyrics() = requireNotNull(AlbumOnlyLyricsTest::class.java.getResource("/lrclib-accordian.json")).readText()
