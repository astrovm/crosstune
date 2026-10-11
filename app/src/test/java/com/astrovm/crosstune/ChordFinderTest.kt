package com.astrovm.crosstune

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/** Runs under Robolectric for the real org.json implementation. */
@RunWith(RobolectricTestRunner::class)
class ChordFinderTest {

    private val fake = FakeSpotify()

    private val english = listOf("Walking down the river road", "Singing to the morning light")
    private val japanese = listOf("川の道を歩いていく", "朝の光に歌っている")
    private val river = MusicMetadata("River Road", "The Walkers")
    private val kawa = MusicMetadata("川の道", "歩く人")

    private fun finder(cache: LookupCache? = null) = ChordFinder(fake.client(), Dispatchers.Unconfined, cache)

    private fun chords(song: MusicMetadata, texts: List<String>, finder: ChordFinder = finder()) = runBlocking { finder.chordsOf(song, texts) }

    /** A song in U-FRET's results. */
    private fun uFretResult(id: Int, title: String, artist: String, beginner: Boolean = false) =
        """<li class="c-list__item ${if (beginner) "beginner" else "normal"}-chord">
            <a href="/song.php?data=$id" class="c-list__link"><div class="c-list__text">
            <p class="c-list__title">
                $title ${if (beginner) """<span class="c-icon__rookie">初心者ver</span>""" else ""}
            </p>
            <p class="c-list__artist">$artist</p></div></a></li>"""

    private fun uFretSong(vararg lines: String) = "<script>var ufret_chord_datas = ${JSONArray(lines.toList())};</script>"

    /** A page of Ultimate Guitar's, with what its app shows written into it, escaped as an attribute. */
    private fun ugPage(data: JSONObject): String {
        val store = JSONObject().put("store", JSONObject().put("page", JSONObject().put("data", data))).toString()
        return """<div class="js-store" data-content="${store.replace("&", "&amp;").replace("\"", "&quot;").replace("'", "&#039;")}"></div>"""
    }

    private fun ugResult(url: String, song: String, artist: String, votes: Int, type: String? = "Chords") =
        JSONObject().put("song_name", song).put("artist_name", artist).put("votes", votes).put("tab_url", url).apply { type?.let { put("type", it) } }

    private fun ugSearch(vararg results: JSONObject) = ugPage(JSONObject().put("results", JSONArray(results.toList())))

    private fun ugTab(content: String, capo: Int? = 2) =
        ugPage(JSONObject().put("tab_view", JSONObject().put("wiki_tab", JSONObject().put("content", content)).apply { capo?.let { put("meta", JSONObject().put("capo", it)) } }))

    private val ugWalking = "[tab][ch]G[/ch]       [ch]C[/ch]\nWalking down the river road[/tab]\n[tab][ch]D[/ch]\nSinging to the morning light[/tab]"

    private fun answer(request: Request, pages: Map<String, String>) =
        pages.entries.firstOrNull { request.url.toString().startsWith(it.key) }?.let { FakeSpotify.html(request, it.value) }
            ?: FakeSpotify.html(request, "", code = 404)

    @Test
    fun aSongsChordsComeFromUltimateGuitarsMostVotedSheetByTheArtistWhoseWordsAreTheSongs() {
        fake.handler = { request ->
            answer(
                request,
                mapOf(
                    "https://www.ultimate-guitar.com/search.php" to ugSearch(
                        ugResult("https://tabs.ultimate-guitar.com/tab/tabs-1", "River Road", "The Walkers", 900, type = "Tabs"),
                        ugResult("https://tabs.ultimate-guitar.com/tab/other-1", "River Road", "Someone Else", 800),
                        ugResult("https://tabs.ultimate-guitar.com/tab/few-1", "River Road", "The Walkers", 3),
                        ugResult("https://tabs.ultimate-guitar.com/tab/most-1", "River Road", "The Walkers", 50),
                        ugResult("https://tabs.ultimate-guitar.com/tab/song-1", "Other Song", "The Walkers", 1000),
                        ugResult("https://tabs.ultimate-guitar.com/tab/pro-1", "River Road", "The Walkers", 5000, type = null)
                    ),
                    // The most voted for is for other words: the next one's taken.
                    "https://tabs.ultimate-guitar.com/tab/most-1" to ugTab("[ch]Am[/ch]\nNothing like the song at all, really"),
                    "https://tabs.ultimate-guitar.com/tab/few-1" to ugTab(ugWalking, capo = 2)
                )
            )
        }
        val found = chords(river, english) as ChordFinder.Chords.Found
        assertEquals(ChordSite.ULTIMATE_GUITAR, found.sheet.site)
        assertEquals("https://tabs.ultimate-guitar.com/tab/few-1", found.sheet.url)
        assertEquals(2, found.sheet.capo)
        assertEquals(SheetLine("Walking down the river road", listOf(Chord(0, "G"), Chord(8, "C"))), found.sheet.lines.first())
        // Searched for by title and artist, as a computer's browser, which it shows its results to.
        val search = fake.requestedUrls.first()
        assertTrue(search, search.startsWith("https://www.ultimate-guitar.com/search.php?search_type=title&value=River%20Road%20The%20Walkers"))
        assertTrue(fake.requestHeaders.any { (name, value) -> name == "User-Agent" && "Macintosh" in value })
        // The most voted for was looked at first.
        assertEquals(listOf("https://tabs.ultimate-guitar.com/tab/most-1", "https://tabs.ultimate-guitar.com/tab/few-1"), fake.requestedUrls.filter { "/tab/" in it })
        // A page saying no capo is played without one.
        fake.handler = { request ->
            answer(
                request,
                mapOf(
                    "https://www.ultimate-guitar.com/search.php" to ugSearch(ugResult("https://tabs.ultimate-guitar.com/tab/few-1", "River Road", "The Walkers", 3)),
                    "https://tabs.ultimate-guitar.com/tab/few-1" to ugTab(ugWalking, capo = null)
                )
            )
        }
        assertEquals(0, (chords(river, english) as ChordFinder.Chords.Found).sheet.capo)
    }

    @Test
    fun japaneseWordsAskUFretFirstForTheSongsOwnSheetByTheArtist() {
        fake.handler = { request ->
            answer(
                request,
                mapOf(
                    "https://www.ufret.jp/search.php" to uFretResult(3, "川の道", "歩く人", beginner = true) + uFretResult(1, "川の道", "他の人") +
                        uFretResult(2, "川の道", "歩く人") + uFretResult(4, "別の歌", "歩く人"),
                    "https://www.ufret.jp/song.php?data=2" to uFretSong("[Am]川の[Em]道を歩いていく\r", "[F]朝の光に[G]歌っている\r"),
                    "https://www.ufret.jp/song.php?data=3" to uFretSong("[C]川の道を歩いていく\r", "朝の光に歌っている")
                )
            )
        }
        val found = chords(kawa, japanese) as ChordFinder.Chords.Found
        assertEquals(ChordSheet(
            listOf(SheetLine("川の道を歩いていく", listOf(Chord(0, "Am"), Chord(2, "Em"))), SheetLine("朝の光に歌っている", listOf(Chord(0, "F"), Chord(4, "G")))),
            site = ChordSite.U_FRET,
            url = "https://www.ufret.jp/song.php?data=2"
        ), found.sheet)
        assertEquals(listOf("https://www.ufret.jp/search.php?key=%E5%B7%9D%E3%81%AE%E9%81%93%20%E6%AD%A9%E3%81%8F%E4%BA%BA", "https://www.ufret.jp/song.php?data=2"), fake.requestedUrls)

        // Its own sheet missing from its page, the one made easier is next, and the one by another
        // artist, as U-FRET may write the artist's name in Japanese, last.
        fake.requestedUrls.clear()
        fake.handler = { request ->
            answer(
                request,
                mapOf(
                    "https://www.ufret.jp/search.php" to uFretResult(1, "川の道", "他の人") + uFretResult(3, "川の道", "歩く人", beginner = true) + uFretResult(2, "川の道", "歩く人"),
                    "https://www.ufret.jp/song.php?data=2" to "<html>no sheet here</html>",
                    "https://www.ufret.jp/song.php?data=3" to uFretSong("[Bm]川の道を歩いていく\r", "朝の光に歌っている")
                )
            )
        }
        assertEquals("https://www.ufret.jp/song.php?data=3", (chords(kawa, japanese) as ChordFinder.Chords.Found).sheet.url)
    }

    @Test
    fun aSiteWithoutTheSongGivesWayToTheOther() {
        // English words: Ultimate Guitar, then U-FRET.
        fake.handler = { request ->
            answer(
                request,
                mapOf(
                    "https://www.ultimate-guitar.com/search.php" to ugSearch(),
                    "https://www.ufret.jp/search.php" to uFretResult(7, "River Road", "The Walkers"),
                    "https://www.ufret.jp/song.php?data=7" to uFretSong("[G]Walking down the river road", "[D]Singing to the morning light")
                )
            )
        }
        assertEquals(ChordSite.U_FRET, (chords(river, english) as ChordFinder.Chords.Found).sheet.site)
        // Japanese words: U-FRET, then Ultimate Guitar, whose search page may have no results at all.
        fake.handler = { request ->
            answer(
                request,
                mapOf(
                    "https://www.ufret.jp/search.php" to "<html>見つかりませんでした</html>",
                    "https://www.ultimate-guitar.com/search.php" to ugPage(JSONObject())
                )
            )
        }
        assertEquals(ChordFinder.Chords.None, chords(kawa, japanese))
        // Nor a page that isn't what it should be.
        fake.handler = { request ->
            answer(
                request,
                mapOf(
                    "https://www.ultimate-guitar.com/search.php" to ugSearch(ugResult("https://tabs.ultimate-guitar.com/tab/gone-1", "River Road", "The Walkers", 3)),
                    "https://tabs.ultimate-guitar.com/tab/gone-1" to "<html>Not found</html>",
                    "https://www.ufret.jp/search.php" to ""
                )
            )
        }
        assertEquals(ChordFinder.Chords.None, chords(river, english))
    }

    @Test
    fun aSiteThatWontAnswerIsntTakenForOneWithoutTheSong() {
        fake.handler = { throw IOException("offline") }
        assertEquals(ChordFinder.Chords.Unavailable, chords(river, english))
        // Turned away, as by a page for robots, or with a page that's broken.
        fake.handler = { request -> FakeSpotify.html(request, "Just a moment...", code = 403) }
        assertEquals(ChordFinder.Chords.Unavailable, chords(river, english))
        fake.handler = { request ->
            answer(request, mapOf("https://www.ultimate-guitar.com/search.php" to """<div class="js-store" data-content="{broken"></div>""", "https://www.ufret.jp/search.php" to ""))
        }
        assertEquals(ChordFinder.Chords.Unavailable, chords(river, english))
        // Too slow to wait for.
        fake.handler = { request -> FakeSpotify.html(request, "") }
        fake.delayMillis = 300
        assertEquals(ChordFinder.Chords.Unavailable, runBlocking { finder().chordsOf(river, english, timeoutMs = 20) })
        fake.delayMillis = 0
        // One wouldn't answer, the other did: what it found counts.
        fake.handler = { request ->
            if (request.url.host == "www.ultimate-guitar.com") throw IOException("offline")
            answer(
                request,
                mapOf(
                    "https://www.ufret.jp/search.php" to uFretResult(7, "River Road", "The Walkers"),
                    "https://www.ufret.jp/song.php?data=7" to uFretSong("[G]Walking down the river road", "[D]Singing to the morning light")
                )
            )
        }
        assertEquals(ChordSite.U_FRET, (chords(river, english) as ChordFinder.Chords.Found).sheet.site)
    }

    @Test
    fun chordsFoundAreKeptSoASongPlayedAgainNeedsNoLookup() {
        val file = File.createTempFile("chords", ".json").apply { delete(); deleteOnExit() }
        fake.handler = { request ->
            answer(
                request,
                mapOf(
                    "https://www.ultimate-guitar.com/search.php" to ugSearch(ugResult("https://tabs.ultimate-guitar.com/tab/few-1", "River Road", "The Walkers", 3)),
                    "https://tabs.ultimate-guitar.com/tab/few-1" to ugTab(ugWalking, capo = 3)
                )
            )
        }
        val first = chords(river, english, finder(LookupCache(file, Dispatchers.Unconfined)))
        fake.handler = { throw IOException("offline") }
        assertEquals(first, chords(MusicMetadata("river road", "the walkers"), english, finder(LookupCache(file, Dispatchers.Unconfined))))

        // What was kept by a version that kept something else is looked up again.
        val broken = File.createTempFile("chords", ".json").apply { delete(); deleteOnExit() }
        val cache = LookupCache(broken, Dispatchers.Unconfined)
        val key = "${SongNames.normalize(river.title)}\u0000${SongNames.normalize(river.artist)}"
        runBlocking { cache.put(key, "{}") }
        assertEquals(ChordFinder.Chords.Unavailable, chords(river, english, finder(cache)))
        runBlocking { cache.put(key, """{"site":"NOWHERE","url":"","capo":0,"lines":[]}""") }
        assertEquals(ChordFinder.Chords.Unavailable, chords(river, english, finder(cache)))
    }

    @Test
    fun ultimateGuitarsPagesAreReadWithTheirSpacesAndLetters() {
        fake.handler = { request ->
            answer(
                request,
                mapOf(
                    "https://www.ultimate-guitar.com/search.php" to ugSearch(ugResult("https://tabs.ultimate-guitar.com/tab/accents-1", "Canción del río", "Beyoncé", 3)),
                    "https://tabs.ultimate-guitar.com/tab/accents-1" to ugTab("[ch]G[/ch]   [ch]C[/ch]\nCaminando junto al río\n[ch]D[/ch]\nCantando a la luz del día")
                        .replace("del día", "del d&iacute;a &bogus;").replace("é", "&eacute;").replace("í", "&#237;").replace("ó", "&#xF3;")
                )
            )
        }
        val found = chords(MusicMetadata("Canción del río", "Beyoncé"), listOf("Caminando junto al río", "Cantando a la luz del día &bogus;")) as ChordFinder.Chords.Found
        assertEquals(
            listOf(SheetLine("Caminando junto al río", listOf(Chord(0, "G"), Chord(4, "C"))), SheetLine("Cantando a la luz del día &bogus;", listOf(Chord(0, "D")))),
            found.sheet.lines
        )
    }
}
