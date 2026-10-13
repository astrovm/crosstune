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

/** Letras is the last source when the usual services have no words. */
@RunWith(RobolectricTestRunner::class)
class LetrasLyricsTest {
    private val fake = FakeSpotify()
    private val song = MusicMetadata("River Road", "The Walkers")
    private val pageUrl = "https://www.letras.com/walkers/12345"

    private fun doc(title: String = song.title, artist: String = song.artist, type: String = "2") = JSONObject()
        .put("t", type).put("txt", title).put("art", artist).put("dns", "walkers").put("url", "12345")

    private fun search(vararg docs: JSONObject) = "LetrasSug(${JSONObject().put("response", JSONObject().put("docs", JSONArray(docs.toList())))})\n"

    private fun respond(results: String = search(doc()), page: String = "<div class=\"lyric-content\"><p>Walking down the river road<br>Singing to the morning light</p></div>", unavailableElsewhere: Boolean = false, pageCode: Int = 200) {
        fake.handler = { request ->
            when (request.url.host) {
                "solr.sscdn.co" -> FakeSpotify.html(request, results)
                "www.letras.com" -> FakeSpotify.html(request, page, code = pageCode)
                else -> {
                    if (unavailableElsewhere) throw IOException("offline")
                    FakeSpotify.html(request, if (request.url.host == "lrclib.net") "[]" else "{}")
                }
            }
        }
    }

    private fun lookup(timeoutMs: Long = 20_000) = runBlocking {
        LyricsFinder(fake.client(), "test", busyPauseMs = 0).firstLook(song, timeoutMs).lyrics
    }

    @Test
    fun aMatchingSearchResultSuppliesItsOwnPageAndPreservesLinesAndEntities() {
        respond(
            search(doc(type = "1"), doc(title = "Different Road"), doc(artist = "Someone Else"), doc()),
            "<div class=\"lyric-content other-class\"><p>Walking &amp; singing<br/>On the r&iacute;ver<br />&#x1F3B5; &lt;home&gt;</p> <p>Second verse &#99999999999;</p></div>"
        )
        assertEquals(Lyrics.Found("Walking & singing\nOn the ríver\n🎵 <home>\n\nSecond verse &#99999999999;"), lookup())
        assertEquals(listOf(pageUrl), fake.requestedUrls.filter { it.startsWith("https://www.letras.com/") })
        val asked = fake.requestedUrls.first { it.startsWith("https://solr.sscdn.co/") }.toHttpUrl()
        assertEquals("River Road The Walkers", asked.queryParameter("q"))
        assertEquals("json", asked.queryParameter("wt"))
        assertTrue(fake.requestHeaders.any { it.first == "Sec-Fetch-Mode" && it.second == "navigate" })
        assertTrue(fake.requestHeaders.any { it.first == "Accept-Language" && it.second.startsWith("es-AR") })
    }

    @Test
    fun anArtistCreditWithAGuestMatchesButOtherSongsNeverDo() {
        respond(search(doc(artist = "The Walkers feat. A Singer")))
        assertTrue(lookup() is Lyrics.Found)
        for (results in listOf(search(), search(doc(artist = "Someone Else")), search(doc(title = "Another Song")), search(doc(type = "1")))) {
            fake.requestedUrls.clear()
            respond(results)
            assertEquals(Lyrics.None, lookup())
            assertTrue(fake.requestedUrls.none { it.startsWith("https://www.letras.com/") })
        }
    }

    @Test
    fun aPageWithoutWordsOrWithOnlyWhitespaceHasNone() {
        for (page in listOf("<html>Instrumental</html>", "<div class=\"lyric-content\"><p> </p></div>")) {
            respond(page = page)
            assertEquals(Lyrics.None, lookup())
        }
    }

    @Test
    fun brokenOrBlockedResponsesAreUnavailable() {
        for (results in listOf("LetrasSug({broken})", "LetrasSug({})")) {
            respond(results = results, unavailableElsewhere = true)
            assertEquals(Lyrics.Unavailable, lookup())
        }
        for (code in listOf(403, 500)) {
            respond(unavailableElsewhere = true, pageCode = code)
            assertEquals(Lyrics.Unavailable, lookup())
        }
        fake.handler = { throw IOException("offline") }
        assertEquals(Lyrics.Unavailable, lookup())
        respond(unavailableElsewhere = true)
        val answer = fake.handler
        fake.handler = { request -> if (request.url.host == "solr.sscdn.co") FakeSpotify.html(request, "Blocked", code = 403) else answer(request) }
        assertEquals(Lyrics.Unavailable, lookup())
        fake.delayMillis = 80
        assertEquals(Lyrics.Unavailable, lookup(timeoutMs = 10))
    }

    @Test
    fun wordsAlreadyFoundWinOverAnUntimedFallback() {
        respond()
        val answer = fake.handler
        var timed = ""
        fake.handler = { request ->
            if (request.url.host == "lrclib.net") FakeSpotify.html(request, JSONArray().put(JSONObject()
                .put("trackName", song.title).put("artistName", song.artist).put("plainLyrics", "Words already found").put("syncedLyrics", timed)).toString())
            else answer(request)
        }
        assertEquals(Lyrics.Found("Words already found"), lookup())
        assertTrue(fake.requestedUrls.any { it.startsWith("https://solr.sscdn.co/") })
        fake.requestedUrls.clear()
        timed = "[00:01.00] Words already found\n[00:04.00] Singing again"
        assertTrue(lookup() is Lyrics.Found)
        assertTrue(fake.requestedUrls.none { it.startsWith("https://solr.sscdn.co/") })
    }
}
