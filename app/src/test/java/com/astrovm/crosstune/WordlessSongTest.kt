package com.astrovm.crosstune

import com.astrovm.crosstune.LyricsFinder.Lyrics
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A song no service has words for is an instrumental when the store files it where only those are. */
@RunWith(RobolectricTestRunner::class)
class WordlessSongTest {
    private val fake = FakeSpotify()
    private val song = MusicMetadata("Rain Piano", "Fake Trio")

    private fun store(genre: String, title: String = song.title) =
        """{"results":[{"trackId":7,"trackName":"$title","artistName":"Fake Trio","trackTimeMillis":200000,"primaryGenreName":"$genre"}]}"""

    private fun respond(storeAnswer: String, genius: String = "{}") {
        fake.handler = { request ->
            when (request.url.host) {
                "itunes.apple.com" -> FakeSpotify.html(request, storeAnswer)
                "lrclib.net" -> FakeSpotify.html(request, "[]")
                "genius.com" -> FakeSpotify.html(request, genius)
                else -> FakeSpotify.html(request, "{}")
            }
        }
    }

    private fun lookup() = runBlocking { LyricsFinder(fake.client(), "test", busyPauseMs = 0).lyricsOf(song) }

    @Test
    fun aSongOfAnInstrumentalGenreWithNoWordsAnywhereIsAnInstrumental() {
        for (genre in listOf("Classical", "New Age", "Jazz", "Latin Jazz", "Bop")) {
            respond(store(genre))
            assertEquals(genre, Lyrics.Instrumental, lookup())
        }
    }

    @Test
    fun aSongOfAnyOtherGenreWithNoWordsIsOneNotFound() {
        for (genre in listOf("Pop", "Hip-Hop/Rap", "Latin", "Folk", "")) {
            respond(store(genre))
            assertEquals(genre, Lyrics.None, lookup())
        }
        respond("""{"results":[]}""")
        assertEquals(Lyrics.None, lookup())
        // The genre is the song's own, not another's of the same artist.
        respond(store("Jazz", title = "Another Tune"))
        assertEquals(Lyrics.None, lookup())
    }

    @Test
    fun wordsFoundAnywhereAreNeverHiddenByTheGenre() {
        val hit = """{"response":{"sections":[{"hits":[{"type":"song","result":{"title":"Rain Piano","artist_names":"Fake Trio","primary_artist_names":"Fake Trio","path":"/Fake-trio-rain-piano-lyrics"}}]}]}}"""
        fake.handler = { request ->
            when {
                request.url.host == "itunes.apple.com" -> FakeSpotify.html(request, store("Jazz"))
                request.url.host == "genius.com" && request.url.encodedPath == "/api/search/multi" -> FakeSpotify.html(request, hit)
                request.url.host == "genius.com" -> FakeSpotify.html(request, """<div data-lyrics-container="true">Rain falls down</div>""")
                request.url.host == "lrclib.net" -> FakeSpotify.html(request, "[]")
                else -> FakeSpotify.html(request, "{}")
            }
        }
        assertEquals(Lyrics.Found("Rain falls down", source = LyricsFinder.GENIUS), lookup())
    }
}
