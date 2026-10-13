package com.astrovm.crosstune

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/** Runs under Robolectric for the real org.json implementation. */
@RunWith(RobolectricTestRunner::class)
class ChordFinderTest {
    private companion object {
        const val LOOKUPS = "chord lookups"
    }

    private val fake = FakeSpotify()

    private val english = listOf("Walking down the river road", "Singing to the morning light")
    private val japanese = listOf("川の道を歩いていく", "朝の光に歌っている")
    private val river = MusicMetadata("River Road", "The Walkers")
    private val kawa = MusicMetadata("川の道", "歩く人")

    /** Where lookups happen, as they would on the phone: away from whoever asked. */
    private val lookups = Executors.newSingleThreadExecutor { Thread(it, LOOKUPS) }.asCoroutineDispatcher()

    @After
    fun stopLookups() = lookups.close()

    private fun finder(cache: LookupCache? = null) = ChordFinder(fake.client(), lookups, cache)

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

    /** A song in LaCuerda's results: its title, the kind of sheet each take is and the takes best liked first, "RT-21", and its page. */
    private class LaCuerdaSong(val title: String, val takes: String, val file: String)

    /** LaCuerda's results, by artist, as its page has them: each song's page listed apart, from the last row. */
    private fun laCuerdaSearch(vararg artists: Triple<String, String, List<LaCuerdaSong>>): String {
        var row = 0
        val rows = artists.joinToString("\n") { (folder, name, songs) ->
            """<tr><td><a href="/$folder/">$name</A></TD><td><ul class=b_main id=b_main0>""" +
                songs.joinToString("") { """<li id='r${"%03d".format(row++)}' lcd='${it.takes}'> <a href="javascript:">${it.title}</a></li>""" } +
                "</ul></td></tr>"
        }
        val files = artists.flatMap { it.third }.map { "'${it.file}'" }.reversed()
        return """<table id=s_main>$rows</table><script>bMfill(1)
var hds=[];
var fns=[${files.joinToString(",")}];
var NMAX=${files.size - 1};
</script>"""
    }

    /** A sheet of LaCuerda's: its chords in lines of their own over the words. */
    private fun laCuerdaSheet(content: String) = "<div id=tH4><pre id='tCode'></pre></div><div id=t_body><PRE>$content</PRE></div>"

    private val laCuerdaWalking = "<div></div><A>G</A>       <A>C</A>\nWalking down the river road\n<div></div><A>D</A>\nSinging to the morning light"

    /** NetEase's results for a search: songs by their names, with their artists'. */
    private fun netEase(vararg songs: Pair<String, List<String>>) = JSONObject().put(
        "result",
        JSONObject().put("songs", JSONArray(songs.map { (name, artists) -> JSONObject().put("name", name).put("artists", JSONArray(artists.map { JSONObject().put("name", it) })) }))
    ).toString()

    /** U-FRET answering [searches] by what's searched for, and [pages] otherwise. */
    private fun uFret(searches: Map<String, String>, pages: Map<String, String>): (Request) -> okhttp3.Response = { request ->
        if (request.url.host == "www.ufret.jp" && request.url.encodedPath == "/search.php") {
            FakeSpotify.html(request, searches[request.url.queryParameter("key")].orEmpty())
        } else {
            answer(request, pages)
        }
    }

    private val uFretSearches get() = fake.requestedUrls.filter { it.startsWith("https://www.ufret.jp/search.php") }.map { it.toHttpUrl().queryParameter("key") }

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
                    "https://acordes.lacuerda.net/busca.php" to laCuerdaSearch(),
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
                    "https://acordes.lacuerda.net/busca.php" to "<html>Sin resultados</html>",
                    "https://www.ufret.jp/search.php" to "",
                    "https://music.163.com/api/search/get" to "{}"
                )
            )
        }
        assertEquals(ChordFinder.Chords.None, chords(river, english))
    }

    @Test
    fun aChineseSongIsFoundUnderItsTitleInTraditionalCharactersInsideAFilmsName() {
        val sheet = "[tab][ch]C[/ch]            [ch]G[/ch]\nWo ting jian yu di luo zai qing qing cao di[/tab]\n[tab][ch]Am[/ch]\nYou yi ju mei yi ju de ting bu dong[/tab]"
        fake.handler = { request ->
            answer(
                request,
                mapOf(
                    "https://www.ultimate-guitar.com/search.php" to ugSearch(
                        ugResult("https://tabs.ultimate-guitar.com/tab/other-1", "我的少女時代 Our Times - 幸運草", "Misc Soundtrack", 900),
                        ugResult("https://tabs.ultimate-guitar.com/tab/film-1", "我的少女時代 Our Times - 小幸運 Xiao Xing Yun A Little Happiness", "Misc Soundtrack", 50)
                    ),
                    "https://tabs.ultimate-guitar.com/tab/film-1" to ugTab(sheet, capo = null)
                )
            )
        }
        val found = chords(MusicMetadata("小幸运", "田馥甄"), listOf("我听见雨滴落在青青草地", "有一句没一句地听不懂")) as ChordFinder.Chords.Found
        assertEquals("https://tabs.ultimate-guitar.com/tab/film-1", found.sheet.url)
        // One character is too little to say a song by.
        assertFalse(chords(MusicMetadata("运", "田馥甄"), listOf("我听见雨滴落在青青草地")) is ChordFinder.Chords.Found)
    }

    @Test
    fun aSheetUltimateGuitarWontServeIsSkippedForTheNextOne() {
        fake.handler = { request ->
            if (request.url.encodedPath.endsWith("/tab/down-1")) FakeSpotify.html(request, "", code = 500)
            else answer(
                request,
                mapOf(
                    "https://www.ultimate-guitar.com/search.php" to ugSearch(
                        ugResult("https://tabs.ultimate-guitar.com/tab/down-1", "River Road", "The Walkers", 900),
                        ugResult("https://tabs.ultimate-guitar.com/tab/few-1", "River Road", "The Walkers", 3)
                    ),
                    "https://tabs.ultimate-guitar.com/tab/few-1" to ugTab(ugWalking, capo = null)
                )
            )
        }
        val found = chords(river, english) as ChordFinder.Chords.Found
        assertEquals("https://tabs.ultimate-guitar.com/tab/few-1", found.sheet.url)
    }

    @Test
    fun anEntityThatIsNoCharacterDoesNotBreakASheet() {
        fake.handler = { request ->
            answer(
                request,
                mapOf(
                    "https://www.ultimate-guitar.com/search.php" to ugSearch(ugResult("https://tabs.ultimate-guitar.com/tab/few-1", "River Road", "The Walkers", 3)),
                    "https://tabs.ultimate-guitar.com/tab/few-1" to ugTab(ugWalking.replace("morning", "mor&#99999999999;ning").replace("river", "ri&#x110000;ver"))
                )
            )
        }
        assertTrue(chords(river, english) is ChordFinder.Chords.Found)
    }

    @Test
    fun aSearchThatFindsNothingIsNoChordsNotASiteThatWontAnswer() {
        // Ultimate Guitar answers it with a 404 and its usual page, LaCuerda with a 500 and no page at all.
        fake.handler = { request ->
            when {
                request.url.host == "www.ultimate-guitar.com" -> FakeSpotify.html(request, ugSearch(), code = 404)
                request.url.host == "acordes.lacuerda.net" -> FakeSpotify.html(request, "", code = 500)
                else -> answer(request, mapOf("https://www.ufret.jp/search.php" to "", "https://music.163.com/api/search/get" to "{}"))
            }
        }
        assertEquals(ChordFinder.Chords.None, chords(river, english))
    }

    @Test
    fun ultimateGuitarIsSearchedForByTheTitleAloneWhenItNamesTheArtistOtherwise() {
        val searches = mutableListOf<String?>()
        fake.handler = { request ->
            if (request.url.host == "www.ultimate-guitar.com" && request.url.encodedPath == "/search.php") {
                searches += request.url.queryParameter("value")
                // With the artist it finds none: a film's songs are by "Misc Soundtrack".
                if (request.url.queryParameter("value") == "River Road The Walkers") FakeSpotify.html(request, ugSearch(), code = 404)
                else FakeSpotify.html(request, ugSearch(ugResult("https://tabs.ultimate-guitar.com/tab/film-1", "The Film, Part One - River Road", "Misc Soundtrack", 50)))
            } else {
                answer(request, mapOf("https://tabs.ultimate-guitar.com/tab/film-1" to ugTab(ugWalking, capo = null)))
            }
        }
        val found = chords(river, english) as ChordFinder.Chords.Found
        assertEquals("https://tabs.ultimate-guitar.com/tab/film-1", found.sheet.url)
        assertEquals(listOf("River Road The Walkers", "River Road"), searches)
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
            if (request.url.host != "www.ufret.jp") throw IOException("offline")
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
    fun aSheetByTheArtistNamedWithOtherSmallWordsIsTheSongs() {
        val vahos = MusicMetadata("Vahos del Ayer", "Flema")
        val words = listOf("Brindo una vez más por mis amigos", "Los que solo están en mi corazón")
        val sheet = laCuerdaSheet("<div></div><A>D</A>                       <A>B</A>\nBrindo una vez mas por mis amigos\n<div></div> <A>G</A>                     <A>A</A>\nlos que solo estan en mi corazon")
        fake.handler = { request ->
            if (request.url.encodedPath == "/busca.php") {
                FakeSpotify.html(
                    request,
                    laCuerdaSearch(
                        // Someone else's, named the same way, isn't looked at.
                        Triple("otros", "Otros", listOf(LaCuerdaSong("Vahos de ayer", "R-1", "vahos_otros"))),
                        Triple("flema", "Flema", listOf(LaCuerdaSong("Vahos de ayer", "RBT-123", "vahos_de_ayer")))
                    )
                )
            } else {
                answer(
                    request,
                    mapOf(
                        // Ultimate Guitar's, by another artist, needs every word.
                        "https://www.ultimate-guitar.com/search.php" to ugSearch(ugResult("https://tabs.ultimate-guitar.com/tab/otros/vahos", "Vahos de ayer", "Otros", 50)),
                        "https://acordes.lacuerda.net/flema/vahos_de_ayer.shtml" to sheet,
                        "https://acordes.lacuerda.net/otros/vahos_otros.shtml" to sheet
                    )
                )
            }
        }
        val found = chords(vahos, words) as ChordFinder.Chords.Found
        assertEquals("https://acordes.lacuerda.net/flema/vahos_de_ayer.shtml", found.sheet.url)
        assertEquals(listOf("https://acordes.lacuerda.net/flema/vahos_de_ayer.shtml"), fake.requestedUrls.filter { ".shtml" in it || "/tab/" in it })
        // A song with no artist is nobody's, so it needs every word.
        fake.requestedUrls.clear()
        assertEquals(ChordFinder.Chords.Unavailable, chords(MusicMetadata("Vahos del Ayer", ""), words))
        assertTrue(fake.requestedUrls.none { ".shtml" in it || "/tab/" in it })
    }

    @Test
    fun aSongUltimateGuitarHasNoChordsForComesFromLaCuerdasBestLikedSheetByTheArtist() {
        val searches = mutableListOf<String?>()
        fake.handler = { request ->
            if (request.url.encodedPath == "/busca.php") {
                searches += request.url.queryParameter("exp")
                // By the artist, LaCuerda finds it under another name; by the title alone, it does.
                val found = request.url.queryParameter("exp") == "River Road"
                FakeSpotify.html(
                    request,
                    if (!found) laCuerdaSearch(Triple("walkers", "The Walkers", listOf(LaCuerdaSong("Other Song", "R-1", "other_song"))))
                    else laCuerdaSearch(
                        Triple("someone_else", "Someone Else", listOf(LaCuerdaSong("River Road", "R-1", "river_road"))),
                        Triple("walkers", "Los Walkers", listOf(LaCuerdaSong("Other Song", "R-1", "other_song"))),
                        Triple("walkers", "The Walkers", listOf(LaCuerdaSong("River Road", "TRBR-4123", "river_road"), LaCuerdaSong("River R&oacute;ad", "R-1", "river_road_live")))
                    )
                )
            } else {
                answer(
                    request,
                    mapOf(
                        "https://www.ultimate-guitar.com/search.php" to ugSearch(),
                        // The best liked is for other words: the next one's taken.
                        "https://acordes.lacuerda.net/walkers/river_road-4.shtml" to laCuerdaSheet("<div></div><A>Am</A>\nNothing like the song at all, really"),
                        "https://acordes.lacuerda.net/walkers/river_road-2.shtml" to laCuerdaSheet("INTRO: <A>Em</A>\n\n$laCuerdaWalking &amp; more")
                    )
                )
            }
        }
        val found = chords(river, english) as ChordFinder.Chords.Found
        assertEquals(ChordSite.LA_CUERDA, found.sheet.site)
        assertEquals("https://acordes.lacuerda.net/walkers/river_road-2.shtml", found.sheet.url)
        assertEquals(
            listOf(
                // Chords on their own line, words and all, are played on their own.
                SheetLine("", listOf(Chord(7, "Em"))),
                SheetLine("Walking down the river road", listOf(Chord(0, "G"), Chord(8, "C"))),
                SheetLine("Singing to the morning light & more", listOf(Chord(0, "D")))
            ),
            found.sheet.lines
        )
        assertEquals(listOf("River Road The Walkers", "River Road"), searches)
        // Before U-FRET, which has few songs in Spanish.
        assertTrue(fake.requestedUrls.none { "ufret" in it })
        // Words and chords only, the artist's best liked first, then theirs with the same words, then others'.
        assertEquals(
            listOf("https://acordes.lacuerda.net/walkers/river_road-4.shtml", "https://acordes.lacuerda.net/walkers/river_road-2.shtml"),
            fake.requestedUrls.filter { ".shtml" in it }
        )
        fake.requestedUrls.clear()
        fake.handler = { request ->
            if (request.url.encodedPath == "/busca.php") {
                FakeSpotify.html(request, laCuerdaSearch(Triple("walkers", "The Walkers", listOf(LaCuerdaSong("River Road", "TRBR-4x123", "river_road"), LaCuerdaSong("River R&oacute;ad", "R-1", "river_road_live"))), Triple("someone_else", "Someone Else", listOf(LaCuerdaSong("River Road", "R-1", "river_road")))))
            } else {
                answer(
                    request,
                    mapOf(
                        "https://www.ultimate-guitar.com/search.php" to ugSearch(),
                        // Pages that aren't sheets.
                        "https://acordes.lacuerda.net/" to "<html>No encontrado</html>",
                        "https://www.ufret.jp/search.php" to "",
                        "https://music.163.com/api/search/get" to "{}"
                    )
                )
            }
        }
        assertEquals(ChordFinder.Chords.None, chords(river, english))
        assertEquals(
            listOf("walkers/river_road-4", "walkers/river_road-2", "walkers/river_road_live"),
            fake.requestedUrls.filter { ".shtml" in it }.map { it.removePrefix("https://acordes.lacuerda.net/").removeSuffix(".shtml") }
        )
        // Results it doesn't list the pages of are none.
        fake.requestedUrls.clear()
        fake.handler = { request ->
            if (request.url.encodedPath == "/busca.php") {
                FakeSpotify.html(request, laCuerdaSearch(Triple("walkers", "The Walkers", listOf(LaCuerdaSong("River Road", "R-1", "river_road")))).replace("var NMAX=0;", ""))
            } else {
                answer(request, mapOf("https://www.ultimate-guitar.com/search.php" to ugSearch(), "https://www.ufret.jp/search.php" to "", "https://music.163.com/api/search/get" to "{}"))
            }
        }
        assertEquals(ChordFinder.Chords.None, chords(river, english))
        assertTrue(fake.requestedUrls.none { ".shtml" in it })
        // Nor are songs it doesn't say the artist of.
        fake.handler = { request ->
            if (request.url.encodedPath == "/busca.php") {
                FakeSpotify.html(request, laCuerdaSearch(Triple("walkers", "The Walkers", listOf(LaCuerdaSong("River Road", "R-1", "river_road")))).replace("""<a href="/walkers/">The Walkers</A>""", ""))
            } else {
                answer(request, mapOf("https://www.ultimate-guitar.com/search.php" to ugSearch(), "https://www.ufret.jp/search.php" to "", "https://music.163.com/api/search/get" to "{}"))
            }
        }
        assertEquals(ChordFinder.Chords.None, chords(river, english))
        assertTrue(fake.requestedUrls.none { ".shtml" in it })
    }

    @Test
    fun laCuerdasSolfegeChordsAreNamedWithLetters() {
        fake.handler = { request ->
            if (request.url.encodedPath == "/busca.php") {
                FakeSpotify.html(request, laCuerdaSearch(Triple("walkers", "The Walkers", listOf(LaCuerdaSong("River Road", "R-1", "river_road")))))
            } else {
                answer(
                    request,
                    mapOf(
                        "https://www.ultimate-guitar.com/search.php" to ugSearch(),
                        "https://acordes.lacuerda.net/walkers/river_road.shtml" to laCuerdaSheet(
                            "<div></div><A>SOL</A>       <A>REm7*</A>\nWalking down the river road\n<div></div><A>LAm</A>\nSinging to the morning light"
                        )
                    )
                )
            }
        }
        val found = chords(river, english) as ChordFinder.Chords.Found
        assertEquals(listOf("G", "Dm7*"), ChordSheets.place(found.sheet, english)!![0].over.map { it.name })
        assertEquals(listOf("Am"), ChordSheets.place(found.sheet, english)!![1].over.map { it.name })
    }

    @Test
    fun anEntityInAChordUltimateGuitarWritesIsTheCharacterItNames() {
        fake.handler = { request ->
            answer(
                request,
                mapOf(
                    "https://www.ultimate-guitar.com/search.php" to ugSearch(ugResult("https://tabs.ultimate-guitar.com/tab/walkers-1", "River Road", "The Walkers", 10)),
                    "https://tabs.ultimate-guitar.com/tab/walkers-1" to ugTab("[tab][ch]G[/ch]       [ch]D&ordm;[/ch]\nWalking down the river road[/tab]\n[tab][ch]C[/ch]\nSinging to the morning light[/tab]")
                )
            )
        }
        val found = chords(river, english) as ChordFinder.Chords.Found
        assertEquals(listOf("G", "Dº"), ChordSheets.place(found.sheet, english)!![0].over.map { it.name })
    }

    @Test
    fun laCuerdaRetriesTheTitleWhenSearchingWithTheArtistFails() {
        for (code in listOf(500, 403)) {
            fake.requestedUrls.clear()
            fake.handler = { request ->
                when {
                    request.url.queryParameter("exp") == "River Road The Walkers" -> FakeSpotify.html(request, "Search failed", code = code)
                    request.url.encodedPath == "/busca.php" -> FakeSpotify.html(request, laCuerdaSearch(Triple("walkers", "The Walkers", listOf(LaCuerdaSong("River Road", "R-1", "river_road")))))
                    else -> answer(request, mapOf("https://www.ultimate-guitar.com/search.php" to ugSearch(), "https://acordes.lacuerda.net/walkers/river_road.shtml" to laCuerdaSheet(laCuerdaWalking)))
                }
            }
            val found = chords(river, english) as ChordFinder.Chords.Found
            assertEquals("https://acordes.lacuerda.net/walkers/river_road.shtml", found.sheet.url)
            assertEquals(listOf("River Road The Walkers", "River Road"), fake.requestedUrls.filter { "/busca.php" in it }.map { it.toHttpUrl().queryParameter("exp") })
            assertEquals(listOf("G", "C"), ChordSheets.place(found.sheet, english)!![0].over.map { it.name })
        }
    }

    @Test
    fun laCuerdaTriesTheNextSheetWhenOneDownloadFails() {
        fake.handler = { request ->
            when {
                request.url.encodedPath == "/busca.php" -> FakeSpotify.html(request, laCuerdaSearch(Triple("walkers", "The Walkers", listOf(LaCuerdaSong("River Road", "RR-12", "river_road")))))
                request.url.encodedPath == "/walkers/river_road.shtml" -> throw IOException("stream reset")
                else -> answer(request, mapOf("https://www.ultimate-guitar.com/search.php" to ugSearch(), "https://acordes.lacuerda.net/walkers/river_road-2.shtml" to laCuerdaSheet(laCuerdaWalking)))
            }
        }
        val found = chords(river, english) as ChordFinder.Chords.Found
        assertEquals("https://acordes.lacuerda.net/walkers/river_road-2.shtml", found.sheet.url)
        assertEquals(1, fake.requestedUrls.count { "/busca.php" in it })
    }

    @Test
    fun laCuerdaRetriesTheTitleAfterUnusableSheetsWithoutDownloadingTheSameSheetTwice() {
        fake.handler = { request ->
            if (request.url.encodedPath == "/busca.php") {
                val songs = mutableListOf(LaCuerdaSong("River Road", "R-1", "river_road"))
                if (request.url.queryParameter("exp") == "River Road") songs += LaCuerdaSong("River Road", "R-1", "river_road_live")
                FakeSpotify.html(request, laCuerdaSearch(Triple("walkers", "The Walkers", songs)))
            } else {
                answer(request, mapOf(
                    "https://www.ultimate-guitar.com/search.php" to ugSearch(),
                    "https://acordes.lacuerda.net/walkers/river_road.shtml" to laCuerdaSheet("<A>G</A>\nNothing like the song at all, really"),
                    "https://acordes.lacuerda.net/walkers/river_road_live.shtml" to laCuerdaSheet(laCuerdaWalking)
                ))
            }
        }
        val found = chords(river, english) as ChordFinder.Chords.Found
        assertEquals("https://acordes.lacuerda.net/walkers/river_road_live.shtml", found.sheet.url)
        assertEquals(listOf("https://acordes.lacuerda.net/walkers/river_road.shtml", found.sheet.url), fake.requestedUrls.filter { ".shtml" in it })
    }

    @Test
    fun aFailedLaCuerdaRequestStaysUnavailableWhenTheFallbackFindsNothing() {
        for (failedPath in listOf("/busca.php", "/walkers/river_road.shtml")) {
            fake.requestedUrls.clear()
            fake.handler = { request ->
                when {
                    request.url.encodedPath == failedPath && request.url.queryParameter("exp") != "River Road" -> FakeSpotify.html(request, "Unavailable", code = 500)
                    request.url.encodedPath == "/busca.php" -> FakeSpotify.html(request,
                        if (request.url.queryParameter("exp") == "River Road") "No results"
                        else laCuerdaSearch(Triple("walkers", "The Walkers", listOf(LaCuerdaSong("River Road", "R-1", "river_road"))))
                    )
                    else -> answer(request, mapOf("https://www.ultimate-guitar.com/search.php" to ugSearch(), "https://www.ufret.jp/search.php" to "", "https://music.163.com/api/search/get" to "{}"))
                }
            }
            assertEquals(ChordFinder.Chords.Unavailable, chords(river, english))
            assertEquals(2, fake.requestedUrls.count { "/busca.php" in it })
        }
    }

    @Test
    fun laCuerdaSearchesATitleWithoutAnArtistOnlyOnce() {
        fake.handler = { request ->
            answer(
                request,
                mapOf(
                    "https://acordes.lacuerda.net/" to "",
                    "https://www.ultimate-guitar.com/search.php" to ugSearch(),
                    "https://www.ufret.jp/search.php" to "",
                    "https://music.163.com/api/search/get" to "{}"
                )
            )
        }
        assertEquals(ChordFinder.Chords.None, chords(river.copy(artist = ""), english))
        assertEquals(listOf("River Road"), fake.requestedUrls.filter { "/busca.php" in it }.map { it.toHttpUrl().queryParameter("exp") })
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

    @Test
    fun aSongIsLookedUpAwayFromWhoeverAsked() {
        val readFrom = mutableSetOf<String>()
        val texts = object : AbstractList<String>() {
            override val size get() = english.size
            override fun get(index: Int) = english[index].also { readFrom += Thread.currentThread().name.substringBefore(" @") }
        }
        fake.handler = { request ->
            answer(
                request,
                mapOf(
                    "https://www.ultimate-guitar.com/search.php" to ugSearch(ugResult("https://tabs.ultimate-guitar.com/tab/few-1", "River Road", "The Walkers", 3)),
                    "https://tabs.ultimate-guitar.com/tab/few-1" to ugTab(ugWalking)
                )
            )
        }
        assertTrue(chords(river, texts) is ChordFinder.Chords.Found)
        assertEquals(setOf(LOOKUPS), readFrom)
    }

    @Test
    fun aTitleInTwoIsLookedUpByEachAndTheOriginalComesBeforeACover() {
        val song = MusicMetadata("プラスティック・ラブ - Plastic Love", "Mariya Takeuchi")
        val words = listOf("川の道を歩いていく", "朝の光に歌っている")
        // U-FRET knows it by its Japanese name, and the singer by hers, which NetEase has.
        fake.handler = uFret(
            mapOf("プラスティック・ラブ" to uFretResult(5, "プラスティック・ラブ", "eill") + uFretResult(6, "プラスティック・ラブ", "竹内まりや")),
            mapOf(
                "https://music.163.com/api/search/get" to netEase("Plastic Love" to listOf("竹内まりや"), "Plastic Love" to listOf("竹内まりや", "Someone"), "Other" to listOf("Nobody")),
                "https://www.ufret.jp/song.php?data=5" to uFretSong("[C]川の道を歩いていく", "朝の光に歌っている"),
                "https://www.ufret.jp/song.php?data=6" to uFretSong("[Am]川の道を歩いていく", "朝の光に歌っている")
            )
        )
        assertEquals("https://www.ufret.jp/song.php?data=6", (chords(song, words) as ChordFinder.Chords.Found).sheet.url)
        assertEquals(listOf("プラスティック・ラブ - Plastic Love Mariya Takeuchi", "プラスティック・ラブ"), uFretSearches)
        assertTrue(fake.requestHeaders.contains("Referer" to "https://music.163.com/"))
        // Ultimate Guitar knows it by its name in Latin letters.
        fake.requestedUrls.clear()
        fake.handler = { request ->
            if (request.url.host == "www.ultimate-guitar.com" && request.url.queryParameter("value") == "Plastic Love Mariya Takeuchi") {
                FakeSpotify.html(request, ugSearch(ugResult("https://tabs.ultimate-guitar.com/tab/plastic-1", "Plastic Love", "Mariya Takeuchi", 3)))
            } else {
                answer(request, mapOf("https://tabs.ultimate-guitar.com/tab/plastic-1" to ugTab(ugWalking), "https://www.ultimate-guitar.com/search.php" to ugSearch()))
            }
        }
        assertEquals("https://tabs.ultimate-guitar.com/tab/plastic-1", (chords(song, english) as ChordFinder.Chords.Found).sheet.url)
        assertEquals(
            // By the artist, then by the title alone when that finds none, then its second name.
            listOf("プラスティック・ラブ - Plastic Love Mariya Takeuchi", "プラスティック・ラブ - Plastic Love", "Plastic Love Mariya Takeuchi"),
            fake.requestedUrls.filter { "search.php" in it }.map { it.toHttpUrl().queryParameter("value") }
        )
    }

    @Test
    fun aSongNamedInLatinLettersIsLookedUpOnUFretByItsJapaneseNames() {
        // "Kimi ha 1000%" is "君は1000%", as NetEase has it; asked once for all the searches.
        val kimi = MusicMetadata("Kimi ha 1000%", "1986 OMEGA TRIBE")
        fake.handler = uFret(
            mapOf("君は1000%" to uFretResult(8, "君は1000%", "1986オメガトライブ")),
            mapOf(
                "https://music.163.com/api/search/get" to netEase("君は1000%" to listOf("1986オメガトライブ"), "Kimi ha 1000% (Live)" to listOf("Someone"), "君は1000% (Live)" to listOf("1986オメガトライブ")),
                "https://www.ufret.jp/song.php?data=8" to uFretSong("[G]川の道を歩いていく", "朝の光に歌っている")
            )
        )
        assertEquals("https://www.ufret.jp/song.php?data=8", (chords(kimi, japanese) as ChordFinder.Chords.Found).sheet.url)
        assertEquals(listOf("Kimi ha 1000% 1986 OMEGA TRIBE", "君は1000%"), uFretSearches)
        assertEquals(1, fake.requestedUrls.count { "music.163.com" in it })
        // "Lemon" is sung by "米津玄師", as NetEase calls Kenshi Yonezu.
        fake.requestedUrls.clear()
        fake.handler = uFret(
            mapOf("Lemon 米津玄師" to uFretResult(9, "Lemon", "米津玄師")),
            mapOf(
                "https://music.163.com/api/search/get" to netEase("Lemon" to listOf("Kenshi Yonezu", "米津玄師")),
                "https://www.ufret.jp/song.php?data=9" to uFretSong("[Am]川の道を歩いていく", "朝の光に歌っている")
            )
        )
        assertEquals("https://www.ufret.jp/song.php?data=9", (chords(MusicMetadata("Lemon", "Kenshi Yonezu"), japanese) as ChordFinder.Chords.Found).sheet.url)
        assertEquals(listOf("Lemon Kenshi Yonezu", "Lemon 米津玄師"), uFretSearches)
        // A Japanese title by a singer named in Latin letters, the same.
        fake.requestedUrls.clear()
        fake.handler = uFret(
            mapOf("レモン 米津玄師" to uFretResult(9, "レモン", "米津玄師")),
            mapOf(
                "https://music.163.com/api/search/get" to netEase("レモン" to listOf("米津玄師")),
                "https://www.ufret.jp/song.php?data=9" to uFretSong("[Am]川の道を歩いていく", "朝の光に歌っている")
            )
        )
        assertEquals("https://www.ufret.jp/song.php?data=9", (chords(MusicMetadata("レモン", "Kenshi Yonezu"), japanese) as ChordFinder.Chords.Found).sheet.url)
        // Not found, it's looked up by its names in Japanese, then its own, and only so far.
        fake.requestedUrls.clear()
        fake.handler = uFret(
            emptyMap(),
            mapOf(
                "https://music.163.com/api/search/get" to netEase("Elsewhere" to listOf("A"), "一つ" to listOf("A"), "二つ" to listOf("B"), "三つ" to listOf("C")),
                "https://www.ultimate-guitar.com/search.php" to ugSearch()
            )
        )
        assertEquals(ChordFinder.Chords.None, chords(MusicMetadata("Nowhere - 川の道", "Nobody"), japanese))
        assertEquals(listOf("Nowhere - 川の道 Nobody", "川の道", "一つ", "二つ", "Nowhere - 川の道"), uFretSearches)
    }

    @Test
    fun aSongUFretHasOnlyInKatakanaIsFoundAmongTheArtistsSongsByHowItSounds() {
        // No search finds "Plastic Love": U-FRET lists it as "プラスティック・ラブ", on the second page of the singer's.
        val plastic = MusicMetadata("Plastic Love", "Mariya Takeuchi")
        val pages = mapOf(
            "1" to (1..30).joinToString("") { uFretResult(100 + it, "曲$it", "竹内まりや") },
            "2" to uFretResult(7, "プラスチック・ドール", "竹内まりや") + uFretResult(8, "プラスティック・ラブ", "竹内まりや", beginner = true) +
                uFretResult(10, "プラスティック・ラブ", "他の人") + uFretResult(9, "プラスティック・ラブ", "山下達郎 &amp; 竹内まりや") +
                (11..12).joinToString("") { uFretResult(it, "プラスティック・ラブ", "竹内まりや") }
        )
        val sheets = mutableMapOf(
            "https://music.163.com/api/search/get" to netEase("Plastic Love" to listOf("Night Tempo", "竹内まりや"), "川の道" to listOf("竹内まりや")),
            "https://www.ufret.jp/song.php?data=8" to uFretSong("[C]川の道を歩いていく", "朝の光に歌っている"),
            "https://www.ufret.jp/song.php?data=9" to uFretSong("[G]川の道を歩いていく", "朝の光に歌っている"),
            "https://www.ultimate-guitar.com/search.php" to ugSearch()
        )
        fake.handler = { request ->
            val url = request.url
            if (url.encodedPath == "/search.php" && url.host == "www.ufret.jp") {
                FakeSpotify.html(request, if (url.queryParameter("key") == "竹内まりや") pages[url.queryParameter("p")].orEmpty() else "")
            } else {
                answer(request, sheets)
            }
        }
        // The singer's own, not the easier one, nor one by someone else.
        assertEquals("https://www.ufret.jp/song.php?data=9", (chords(plastic, japanese) as ChordFinder.Chords.Found).sheet.url)
        val listed = fake.requestedUrls.filter { it.startsWith("https://www.ufret.jp/search.php") }.map { it.toHttpUrl() }.filter { it.queryParameter("key") == "竹内まりや" }
        assertEquals(listOf("1", "2"), listed.map { it.queryParameter("p") })
        assertTrue(fake.requestedUrls.none { it.endsWith("data=7") || it.endsWith("data=10") })

        // Three sheets at most are tried, the easier one last.
        listOf(9, 11, 12).forEach { sheets["https://www.ufret.jp/song.php?data=$it"] = "" }
        fake.requestedUrls.clear()
        assertEquals(ChordFinder.Chords.None, chords(plastic, japanese))
        assertEquals(listOf("9", "11", "12"), fake.requestedUrls.filter { "song.php" in it }.map { it.toHttpUrl().queryParameter("data") })

        // Not there, the last page, one not full, is the last asked; no sound-alike is asked for a title in Japanese.
        fake.requestedUrls.clear()
        assertEquals(ChordFinder.Chords.None, chords(MusicMetadata("Lonely Hearts", "竹内まりや"), japanese))
        assertEquals(listOf("1", "2"), fake.requestedUrls.filter { it.startsWith("https://www.ufret.jp/search.php") }.map { it.toHttpUrl() }
            .filter { it.queryParameter("p") != null }.map { it.queryParameter("p") })
        fake.requestedUrls.clear()
        assertEquals(ChordFinder.Chords.None, chords(MusicMetadata("川の道", "Mariya Takeuchi"), japanese))
        assertTrue(fake.requestedUrls.none { it.toHttpUrl().queryParameter("p") != null })

        // However many songs the singer has, no more than eight pages of them are looked through.
        fake.handler = { request ->
            val url = request.url
            if (url.encodedPath == "/search.php" && url.host == "www.ufret.jp") {
                FakeSpotify.html(request, if (url.queryParameter("p") != null) (1..30).joinToString("") { uFretResult(100 + it, "曲$it", "竹内まりや") } else "")
            } else {
                answer(request, sheets)
            }
        }
        fake.requestedUrls.clear()
        assertEquals(ChordFinder.Chords.None, chords(MusicMetadata("Lonely Hearts", "竹内まりや"), japanese))
        assertEquals((1..8).map { "$it" }, fake.requestedUrls.mapNotNull { it.toHttpUrl().queryParameter("p") })
    }

    @Test
    fun aTitleIsSplitOnlyWhereItsTwoNames() {
        // Split by a slash too; a letter on its own isn't a name.
        fake.handler = uFret(emptyMap(), mapOf("https://www.ultimate-guitar.com/search.php" to ugSearch()))
        assertEquals(ChordFinder.Chords.None, chords(MusicMetadata("川の道 / A", "歩く人"), japanese))
        assertEquals(listOf("川の道 / A 歩く人", "川の道"), uFretSearches)
        assertEquals(listOf("川の道 / A 歩く人", "川の道 / A"), fake.requestedUrls.filter { "ultimate-guitar" in it }.map { it.toHttpUrl().queryParameter("value") })
        // Sung by a Japanese name, U-FRET's names for them are its own: NetEase isn't asked.
        fake.requestedUrls.clear()
        fake.handler = uFret(
            mapOf("川の道 歩く人" to uFretResult(1, "川の道", "他の人")),
            mapOf("https://www.ufret.jp/song.php?data=1" to "", "https://www.ultimate-guitar.com/search.php" to ugSearch())
        )
        assertEquals(ChordFinder.Chords.None, chords(kawa, japanese))
        assertTrue(fake.requestedUrls.none { "music.163.com" in it })
    }
}
