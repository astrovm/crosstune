package com.astrovm.crosstune

import com.astrovm.crosstune.LyricsFinder.Lyrics
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** Genius is the last source, for songs the usual services have no words for. */
@RunWith(RobolectricTestRunner::class)
class GeniusLyricsTest {
    private val fake = FakeSpotify()
    private val song = MusicMetadata("River Road", "The Walkers")
    private val pageUrl = "https://genius.com/The-walkers-river-road-lyrics"

    private fun hit(
        title: String = song.title, artists: String = song.artist, type: String = "song", instrumental: Boolean = false,
        path: String = "/The-walkers-river-road-lyrics", primary: String = artists
    ) = JSONObject().put("type", type).put(
        "result",
        JSONObject().put("title", title).put("artist_names", artists).put("primary_artist_names", primary).put("path", path).put("instrumental", instrumental)
    )

    private fun search(vararg hits: JSONObject) = JSONObject().put(
        "response",
        JSONObject().put("sections", JSONArray().put(JSONObject().put("type", "top_hit").put("hits", JSONArray(hits.toList()))))
    ).toString()

    private val words = """<div class="Lyrics__Container" data-lyrics-container="true" class="x">River Road Lyrics[Letra de "River Road"]<br/>[Verse 1: The Walkers]<br/>Walking down the river road<br/><a href="/1"><span>Singing to the morning light</span></a><br/><br/>[Chorus]<br/>Oh, oh &amp; home</div>""" +
        """<div data-lyrics-container="true"></div><div data-lyrics-container="true">Last light fades<br/>[Outro]</div>"""

    private fun respond(results: String = search(hit()), page: String = words, pageCode: Int = 200) {
        fake.handler = { request ->
            when (request.url.host) {
                "genius.com" -> if (request.url.encodedPath == "/api/search/multi") FakeSpotify.html(request, results) else FakeSpotify.html(request, page, code = pageCode)
                else -> FakeSpotify.html(request, if (request.url.host == "lrclib.net") "[]" else "{}")
            }
        }
    }

    private fun lookup(timeoutMs: Long = 20_000, of: MusicMetadata = song) = runBlocking {
        LyricsFinder(fake.client(), "test", busyPauseMs = 0).firstLook(of, timeoutMs).lyrics
    }

    @Test
    fun aMatchingSearchResultSuppliesItsPageWithoutItsHeaderAndVerseTags() {
        respond(search(hit(type = "article"), hit(title = "Different Road"), hit(artists = "Someone Else"), hit()))
        assertEquals(
            Lyrics.Found("Walking down the river road\nSinging to the morning light\n\nOh, oh & home\nLast light fades", source = LyricsFinder.GENIUS),
            lookup()
        )
        assertEquals(listOf(pageUrl), fake.requestedUrls.filter { it.startsWith("https://genius.com/") && "/api/" !in it })
        assertEquals("River Road The Walkers", fake.requestedUrls.first { "/api/search/multi" in it }.toHttpUrl().queryParameter("q"))
        // It asks as a phone's browser does.
        assertTrue(fake.requestHeaders.any { it.first == "User-Agent" && "Mobile Safari" in it.second })
    }

    @Test
    fun aSongOnlyCreditedToOneOfItsArtistsIsTheirsStill() {
        respond(search(hit(artists = "The Walkers & Someone", primary = "The Walkers")))
        assertEquals(LyricsFinder.GENIUS, (lookup() as Lyrics.Found).source)
        respond(search(hit(artists = "Someone Else & The Walkers", primary = "Someone Else")))
        assertEquals(LyricsFinder.GENIUS, (lookup() as Lyrics.Found).source)
    }

    @Test
    fun aSongUnderAnotherScriptCanMatchItsLatinNameAndArtist() {
        respond(search(hit(title = "별빛 (River Road)", artists = "걷는 사람 (The Walkers)")))
        assertEquals(LyricsFinder.GENIUS, (lookup() as Lyrics.Found).source)
        respond(search(hit(title = "별빛 (River Rood)", artists = "걷는 사람 (The Walkers)")))
        assertEquals(LyricsFinder.GENIUS, (lookup() as Lyrics.Found).source)
        respond(search(hit(title = "별빛 (River Rood)", artists = "Someone Else")))
        assertEquals(Lyrics.None, lookup())
        respond(search(hit(title = "별빛 (River Road) (Live)", artists = "걷는 사람 (The Walkers)")))
        assertEquals(Lyrics.None, lookup())
    }

    @Test
    fun aLatinNameInTheOwnScriptsTitleIsOnlyTakenWhenItIsTheOneAndOnly() {
        val korean = "걷는 사람 (The Walkers)"
        // Two different pages under one name: not known which is the song.
        respond(search(hit(title = "별빛 (River Road)", artists = korean, path = "/a"), hit(title = "달빛 (River Road)", artists = korean, path = "/b")))
        assertEquals(Lyrics.None, lookup())
        // The same page listed twice is one.
        respond(search(hit(title = "별빛 (River Road)", artists = korean, path = "/a"), hit(title = "별빛 (River Road)", artists = korean, path = "/a")))
        assertEquals(LyricsFinder.GENIUS, (lookup() as Lyrics.Found).source)
        // A Latin name before brackets is not another script's, and a short or wrong-length name is not guessed at.
        respond(search(hit(title = "River Rood (Live)", artists = korean)))
        assertEquals(Lyrics.None, lookup())
        respond(search(hit(title = "별빛 (River Roads Home)", artists = korean)))
        assertEquals(Lyrics.None, lookup())
        respond(search(hit(title = "별빛 (Rivr)", artists = korean)))
        assertEquals(Lyrics.None, lookup(of = MusicMetadata("River", song.artist)))
        // A title in another script has no Latin spelling to compare.
        respond(search(hit(title = "별빛 (Byeolbit)", artists = korean)))
        assertEquals(Lyrics.None, lookup(of = MusicMetadata("별빛", song.artist)))
    }

    @Test
    fun aSongItMarksAsInstrumentalHasNoWords() {
        respond(search(hit(instrumental = true)))
        assertEquals(Lyrics.Instrumental, lookup())
        assertTrue(fake.requestedUrls.none { it == pageUrl })
    }

    @Test
    fun aSongItDoesntHaveOrACrowdOfOthersIsNone() {
        respond(search(hit(title = "Other"), hit(type = "lyric"), hit(artists = "")))
        assertEquals(Lyrics.None, lookup())
        respond("""{"response":{"sections":[{"hits":[]}]}}""")
        assertEquals(Lyrics.None, lookup())
    }

    @Test
    fun aPageWithoutWordsOrAPathIsNone() {
        respond(page = "<html><div>Nothing</div><div data-lyrics-container=\"true\">River Road Lyrics<br/>[Verse]</div></html>")
        assertEquals(Lyrics.None, lookup())
        respond(search(hit(path = "")))
        assertEquals(Lyrics.None, lookup())
    }

    @Test
    fun aPageThatEndsMidBoxStillGivesItsWords() {
        respond(page = "<div data-lyrics-container=\"true\">Walking home")
        assertEquals(Lyrics.Found("Walking home", source = LyricsFinder.GENIUS), lookup())
    }

    @Test
    fun aBoxThatDoesntOpenWithTheNameKeepsItsFirstLine() {
        respond(page = "<div data-lyrics-container=\"true\">River Road is where we walk<br/>Home again</div>")
        assertEquals(Lyrics.Found("River Road is where we walk\nHome again", source = LyricsFinder.GENIUS), lookup())
    }

    @Test
    fun aServiceThatIsDownOrAnswersBadlyIsNotAnAnswer() {
        respond(pageCode = 500)
        assertEquals(Lyrics.None, lookup())
        respond("not json")
        assertEquals(Lyrics.None, lookup())
        respond("{}")
        assertEquals(Lyrics.None, lookup())
        fake.handler = { request -> if (request.url.host == "genius.com") throw IOException("offline") else FakeSpotify.html(request, if (request.url.host == "lrclib.net") "[]" else "{}") }
        assertEquals(Lyrics.None, lookup())
    }

    @Test
    fun itIsNotAskedWhenAnotherServiceHasTheWords() {
        respond()
        val others = fake.handler
        fake.handler = { request ->
            if (request.url.host == "lrclib.net") FakeSpotify.html(request, """[{"trackName":"River Road","artistName":"The Walkers","plainLyrics":"Walking down\nthe road","duration":200}]""") else others(request)
        }
        assertEquals(LyricsFinder.LRCLIB, (lookup() as Lyrics.Found).source)
        assertTrue(fake.requestedUrls.none { it.startsWith("https://genius.com/") })
    }

    @Test
    fun aSongGivenInLatinLettersIsLookedUpUnderItsOwnNamesToo() {
        // Genius keeps it under the Japanese store's names, which the usual services don't have it under.
        val results = search(hit(title = "川の道", artists = "歩く人", path = "/Walkers-kawa-lyrics"))
        respond(results.replace("川の道", "River Road").replace("歩く人", "Nobody"), page = words.replace("River Road Lyrics", "川の道 Lyrics").replace("\"River Road\"", "\"川の道\""))
        val others = fake.handler
        fake.handler = { request ->
            when {
                request.url.host == "genius.com" && request.url.encodedPath == "/api/search/multi" ->
                    FakeSpotify.html(request, if (request.url.queryParameter("q")?.contains("川の道") == true) results else search())
                request.url.host == "itunes.apple.com" && request.url.encodedPath == "/search" -> FakeSpotify.html(request, """{"results":[{"trackId":42,"trackName":"River Road","artistName":"The Walkers"}]}""")
                request.url.encodedPath == "/lookup" -> FakeSpotify.html(request, """{"results":[{"trackName":"川の道","artistName":"歩く人"}]}""")
                else -> others(request)
            }
        }
        assertEquals(Lyrics.Found("Walking down the river road\nSinging to the morning light\n\nOh, oh & home\nLast light fades", source = LyricsFinder.GENIUS), lookup())
        assertTrue(fake.requestedUrls.any { it == "https://genius.com/Walkers-kawa-lyrics" })
    }

    @Test
    fun aBoxThatHoldsAnotherBoxKeepsAllItsWordsAndNothingAfterIt() {
        respond(page = "<div data-lyrics-container=\"true\">River Road Lyrics<br/>One<br/><div>Two</div><br/>Three</div><div>Footer</div>")
        assertEquals(Lyrics.Found("One\nTwo\nThree", source = LyricsFinder.GENIUS), lookup())
    }

    @Test
    fun anArtistIsOneOfTheFirstOnesNamesOrNoneAtAll() {
        // Named only as the song's primary artist.
        respond(search(hit(artists = "Someone Else", primary = "The Walkers")))
        assertEquals(LyricsFinder.GENIUS, (lookup() as Lyrics.Found).source)
        // Naming none is not naming any, so it can't be the song's.
        val unnamed = JSONObject().put("type", "song").put("result", JSONObject().put("title", song.title).put("artist_names", "Someone Else").put("path", "/x"))
        respond(search(unnamed))
        assertEquals(Lyrics.None, lookup())
    }

    @Test
    fun itIsNotAskedForWordsThatOnlyNeedTimingEither() {
        // LRCLIB has them untimed: Genius's are untimed too, so they can't be better.
        respond()
        val others = fake.handler
        fake.handler = { request ->
            when {
                request.url.host == "lrclib.net" -> FakeSpotify.html(request, """[{"trackName":"River Road","artistName":"The Walkers","plainLyrics":"Walking down\nthe road","duration":200}]""")
                request.url.host == "itunes.apple.com" && request.url.encodedPath == "/search" -> FakeSpotify.html(request, """{"results":[{"trackId":42,"trackName":"River Road","artistName":"The Walkers"}]}""")
                request.url.encodedPath == "/lookup" -> FakeSpotify.html(request, """{"results":[{"trackName":"川の道","artistName":"歩く人"}]}""")
                else -> others(request)
            }
        }
        assertEquals(LyricsFinder.LRCLIB, (lookup() as Lyrics.Found).source)
        assertTrue(fake.requestedUrls.any { it.startsWith("https://itunes.apple.com/lookup") })
        assertTrue(fake.requestedUrls.none { it.startsWith("https://genius.com/") })
    }
}
