package com.astrovm.crosstune

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
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

    /** A song or artist in Cifra Club's search: what kind it is, its name, its artist's, and its page's address, the artist's and its own. */
    private fun cifraClubDoc(kind: String, name: String, artist: String, folder: String, page: String) =
        JSONObject().put("t", kind).put("m", name).put("a", artist).put("d", folder).put("u", page)

    /** Cifra Club's search's answer, as a call to a function. */
    private fun cifraClubSearch(vararg docs: JSONObject) = "(${JSONObject().put("response", JSONObject().put("numFound", docs.size).put("docs", JSONArray(docs.toList())))})"

    /** A Cifra Club song page: its sheet, then what its app shows, escaped into a script, with the fret its capo is on. */
    private fun cifraClubPage(sheet: String, capo: Int = 0) =
        """<pre class="_crVx" data-chord-content="true" data-song-part="true">$sheet</pre><script>self.push("{\"config\":{\"capo\":$capo,\"tuning\":\"E A D G B E\"}}")</script>"""

    private val cifraClubWalking = """<div class="kvMV"><b data-chord-name="G">G</b>       <b>C</b>
Walking down the river road
<b>D</b>
Singing to the morning light</div>"""

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
            // Cifra Club's search, Apple's and J-Total's, where a test doesn't say, find nothing.
            ?: when (request.url.host) {
                "solr.sscdn.co" -> FakeSpotify.html(request, cifraClubSearch())
                "music.j-total.net" -> FakeSpotify.html(request, "")
                "itunes.apple.com" -> FakeSpotify.html(request, """{"results":[]}""")
                else -> FakeSpotify.html(request, "", code = 404)
            }

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

    /** J-Total's page, in Shift_JIS as it serves them, saying nothing of that. */
    private fun jTotalPage(request: Request, page: String) = okhttp3.Response.Builder()
        .request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
        .body(page.toByteArray(java.nio.charset.Charset.forName("Shift_JIS")).toResponseBody("text/html".toMediaType()))
        .build()

    /** A song in J-Total's results. */
    private fun jTotalResult(path: String, title: String, artist: String = "歩く人") =
        """<td><a href="//music.j-total.net/data/$path.html" target="_blank"><li></a> <a href="https://music.j-total.net/data/$path.html" target=""><font size="3"><b>$title</b></font></a>
        <tr><td>\u3000\u3000歌：$artist/詞：誰か/曲：誰か</td></tr>"""

    private fun jTotalSong(vararg lines: String) = "<tt><!--HPSTART-->${lines.joinToString("<br>\n")}<!--HPEND--></tt>"

    /** J-Total answering what's searched for with [searches], and each sheet's page from [pages], neither of the other sites having anything. */
    private fun jTotal(searches: Map<String, String>, pages: Map<String, String> = emptyMap()) {
        fake.handler = { request ->
            val path = request.url.encodedPath
            when {
                request.url.host == "www.ultimate-guitar.com" -> FakeSpotify.html(request, ugSearch())
                request.url.host != "music.j-total.net" -> FakeSpotify.html(request, "")
                path == "/db/search.cgi" -> jTotalPage(request, searches.entries.firstOrNull { "word=${java.net.URLEncoder.encode(it.key, "Shift_JIS")}&" in request.url.encodedQuery.orEmpty() }?.value.orEmpty())
                else -> jTotalPage(request, pages[path].orEmpty())
            }
        }
    }

    private val jTotalChord = """<a href="JavaScript:jump_1('../../../cd/Am.html');">Am</a>"""

    @Test
    fun japaneseWordsOnNoOtherSiteAreFoundOnJTotalByTheSongsJapaneseName() {
        jTotal(
            mapOf("川の道" to jTotalResult("kawa/002", "川の道〜初心者向け簡単コードVer.〜") + jTotalResult("kawa/001", "川の道") + jTotalResult("kawa/003", "別の歌")),
            mapOf("/data/kawa/001.html" to jTotalSong(jTotalChord, "川の道を歩いていく", jTotalChord, "朝の光に歌っている"))
        )
        val found = chords(kawa, japanese) as ChordFinder.Chords.Found
        assertEquals(ChordSite.J_TOTAL, found.sheet.site)
        assertEquals("https://music.j-total.net/data/kawa/001.html", found.sheet.url)
        assertEquals(listOf(SheetLine("川の道を歩いていく", listOf(Chord(0, "Am"))), SheetLine("朝の光に歌っている", listOf(Chord(0, "Am")))), found.sheet.lines)
        // Asked in Shift_JIS, as it reads its searches.
        assertTrue(fake.requestedUrls.any { it.startsWith("https://music.j-total.net/db/search.cgi") && "word=%90%EC%82%CC%93%B9" in it })
        // Its easier version comes after, and another song is never read.
        assertFalse(fake.requestedUrls.any { it.endsWith("kawa/003.html") })
    }

    @Test
    fun jTotalsSongOfTheSameNameBySomeoneElseWaitsForTheArtists() {
        jTotal(
            mapOf("川の道" to jTotalResult("other/001", "川の道", "別の人") + jTotalResult("kawa/001", "川の道")),
            mapOf(
                "/data/other/001.html" to jTotalSong(jTotalChord, "川の道を歩いていく"),
                "/data/kawa/001.html" to jTotalSong(jTotalChord, "川の道を歩いていく", jTotalChord, "朝の光に歌っている")
            )
        )
        assertEquals("https://music.j-total.net/data/kawa/001.html", (chords(kawa, japanese) as ChordFinder.Chords.Found).sheet.url)
    }

    @Test
    fun jTotalsEasierVersionIsReadWhenItsOwnWontDo() {
        jTotal(
            mapOf("川の道" to jTotalResult("kawa/002", "川の道〜初心者向け簡単コードVer.〜") + jTotalResult("kawa/001", "川の道")),
            mapOf("/data/kawa/001.html" to "<html>no sheet here</html>", "/data/kawa/002.html" to jTotalSong(jTotalChord, "川の道を歩いていく", jTotalChord, "朝の光に歌っている"))
        )
        assertEquals("https://music.j-total.net/data/kawa/002.html", (chords(kawa, japanese) as ChordFinder.Chords.Found).sheet.url)
    }

    @Test
    fun aSongNamedInLatinLettersIsLookedUpOnJTotalByItsNameInJapan() {
        val named = MusicMetadata("Kawa no Michi", "Aruku Hito")
        val jTotalPages = mapOf("/data/kawa/001.html" to jTotalSong(jTotalChord, "川の道を歩いていく", jTotalChord, "朝の光に歌っている"))
        jTotal(mapOf("川の道" to jTotalResult("kawa/001", "川の道")), jTotalPages)
        val pages = fake.handler
        fake.handler = { request ->
            if (request.url.host == "music.163.com") FakeSpotify.html(request, """{"result":{"songs":[{"name":"川の道","artists":[{"name":"歩く人"}]}]}}""") else pages(request)
        }
        assertEquals("https://music.j-total.net/data/kawa/001.html", (chords(named, japanese) as ChordFinder.Chords.Found).sheet.url)
    }

    @Test
    fun aNameNetEaseGivesWithItsVersionAndOneJTotalCantReadAreNotWhatJTotalIsAskedFor() {
        val named = MusicMetadata("Kawa no Michi", "Aruku Hito")
        jTotal(mapOf("川の道" to jTotalResult("kawa/001", "川の道")), mapOf("/data/kawa/001.html" to jTotalSheet))
        val pages = fake.handler
        // A Chinese song first, with a kanji Shift_JIS has no place for, then the song's own name with its version after it.
        fake.handler = { request ->
            if (request.url.host == "music.163.com") FakeSpotify.html(request, netEase("另一半" to listOf("別の人"), "川の道(Single Version)(オリジナルカラオケ)" to listOf("歩く人"))) else pages(request)
        }
        assertEquals("https://music.j-total.net/data/kawa/001.html", (chords(named, japanese) as ChordFinder.Chords.Found).sheet.url)
        // The name Shift_JIS can't write is never sent as a mangled "?".
        assertFalse(fake.requestedUrls.any { it.startsWith("https://music.j-total.net/db/search.cgi") && "%3F" in it })
    }

    private val jTotalSheet = jTotalSong(jTotalChord, "川の道を歩いていく", jTotalChord, "朝の光に歌っている")

    @Test
    fun anotherSongOnJTotalIsNeverRead() {
        jTotal(mapOf("川の道" to jTotalResult("kawa/003", "別の歌")), mapOf("/data/kawa/003.html" to jTotalSheet))
        assertEquals(ChordFinder.Chords.None, chords(kawa, japanese))
        assertFalse(fake.requestedUrls.any { it.endsWith("kawa/003.html") })
    }

    @Test
    fun jTotalsSheetByTheArtistComesFirstAndItsOwnBeforeItsEasierVersion() {
        // Each of them would fit, so only the order says which is taken.
        val pages = listOf("other/001", "kawa/001", "kawa/002").associate { "/data/$it.html" to jTotalSheet }
        val other = jTotalResult("other/001", "川の道", "別の人")
        val own = jTotalResult("kawa/001", "川の道")
        val easier = jTotalResult("kawa/002", "川の道〜初心者向け簡単コードVer.〜")
        jTotal(mapOf("川の道" to other + easier + own), pages)
        assertEquals("https://music.j-total.net/data/kawa/001.html", (chords(kawa, japanese) as ChordFinder.Chords.Found).sheet.url)
        jTotal(mapOf("川の道" to other + easier), pages)
        assertEquals("https://music.j-total.net/data/kawa/002.html", (chords(kawa, japanese) as ChordFinder.Chords.Found).sheet.url)
    }

    @Test
    fun aJTotalSheetThatWontAnswerIsUnavailableNotNoChords() {
        jTotal(mapOf("川の道" to jTotalResult("kawa/001", "川の道")))
        val site = fake.handler
        fake.handler = { request -> if (request.url.encodedPath == "/data/kawa/001.html") FakeSpotify.html(request, "", code = 500) else site(request) }
        assertEquals(ChordFinder.Chords.Unavailable, chords(kawa, japanese))
    }

    @Test
    fun aSongNamedInJapaneseAlreadyIsNeverLookedUpByOtherNamesForJTotal() {
        jTotal(emptyMap())
        assertEquals(ChordFinder.Chords.None, chords(kawa, japanese))
        assertFalse(fake.requestedUrls.any { "music.163.com" in it })
    }

    @Test
    fun aJapaneseTitleOrArtistAloneStillLooksUpTheOtherNameForJTotal() {
        val pages = mapOf("/data/kawa/001.html" to jTotalSong(jTotalChord, "川の道を歩いていく", jTotalChord, "朝の光に歌っている"))
        for (song in listOf(MusicMetadata("川の歌", "Aruku Hito"), MusicMetadata("Kawa no Michi", "歩く人"))) {
            jTotal(mapOf("川の道" to jTotalResult("kawa/001", "川の道")), pages)
            val site = fake.handler
            fake.handler = { request ->
                if (request.url.host == "music.163.com") FakeSpotify.html(request, """{"result":{"songs":[{"name":"川の道","artists":[{"name":"歩く人"}]}]}}""") else site(request)
            }
            assertEquals("https://music.j-total.net/data/kawa/001.html", (chords(song, japanese) as ChordFinder.Chords.Found).sheet.url)
        }
    }

    @Test
    fun wordsInOtherLettersNeverAskJTotal() {
        jTotal(emptyMap())
        chords(river, english)
        assertFalse(fake.requestedUrls.any { "j-total" in it })
    }

    @Test
    fun aSheetOfOtherWordsOnJTotalIsNoSheet() {
        jTotal(mapOf("川の道" to jTotalResult("kawa/001", "川の道")), mapOf("/data/kawa/001.html" to jTotalSong(jTotalChord, "まるで違う歌の言葉です", jTotalChord, "それも別の話になります")))
        assertEquals(ChordFinder.Chords.None, chords(kawa, japanese))
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
    fun traditionalTitlesMatchByTheArtistTooButLongerNamesDoNot() {
        val url = "https://tabs.ultimate-guitar.com/tab/song-1"
        val texts = listOf("我听见雨滴落在青青草地", "有一句没一句地听不懂")
        val sheet = texts.joinToString("\n") { "[ch]C[/ch]\n$it" }
        for (artist in listOf("田馥甄", "Misc Soundtrack")) {
            for (title in listOf("小幸運", "Our Times - 小幸運 Xiao Xing Yun", "小幸運嗎", "好小幸運")) {
                fake.requestedUrls.clear()
                fake.handler = { request ->
                    answer(request, mapOf(
                        "https://www.ultimate-guitar.com/search.php" to ugSearch(ugResult(url, title, artist, 50)),
                        url to ugTab(sheet),
                        "https://acordes.lacuerda.net/busca.php" to "",
                        "https://www.ufret.jp/search.php" to "",
                        "https://music.163.com/api/search/get" to "{}"
                    ))
                }
                val expected = title == "小幸運" || title.startsWith("Our Times")
                assertEquals("$artist: $title", expected, chords(MusicMetadata("小幸运", "田馥甄"), texts) is ChordFinder.Chords.Found)
                assertEquals("$artist: $title page fetched", expected, url in fake.requestedUrls)
            }
        }
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
    fun aUFretSearchThatWontAnswerIsUnavailableNotNoChords() {
        fake.handler = { request ->
            when {
                request.url.host == "www.ufret.jp" -> FakeSpotify.html(request, "", code = 500)
                request.url.host == "www.ultimate-guitar.com" -> FakeSpotify.html(request, ugSearch(), code = 404)
                request.url.host == "acordes.lacuerda.net" -> FakeSpotify.html(request, "", code = 500)
                else -> answer(request, mapOf("https://music.163.com/api/search/get" to "{}"))
            }
        }
        assertEquals(ChordFinder.Chords.Unavailable, chords(kawa, japanese))
    }

    @Test
    fun eachSitesWayOfSayingItFoundNothingIsOnlyTakenFromThatSite() {
        // A bare 500 from Ultimate Guitar, or a 404 from LaCuerda, is a site that wouldn't answer.
        fake.handler = { request ->
            when {
                request.url.host == "www.ultimate-guitar.com" -> FakeSpotify.html(request, "", code = 500)
                request.url.host == "acordes.lacuerda.net" -> FakeSpotify.html(request, ugSearch(), code = 404)
                else -> answer(request, mapOf("https://www.ufret.jp/search.php" to "", "https://music.163.com/api/search/get" to "{}"))
            }
        }
        assertEquals(ChordFinder.Chords.Unavailable, chords(river, english))
        // Nor is a 404 without Ultimate Guitar's page, as from a proxy in the way.
        fake.handler = { request ->
            when {
                request.url.host == "www.ultimate-guitar.com" -> FakeSpotify.html(request, "Not Found", code = 404)
                request.url.host == "acordes.lacuerda.net" -> FakeSpotify.html(request, "", code = 500)
                else -> answer(request, mapOf("https://www.ufret.jp/search.php" to "", "https://music.163.com/api/search/get" to "{}"))
            }
        }
        assertEquals(ChordFinder.Chords.Unavailable, chords(river, english))
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
    fun filmPrefixedSingleWordTitlesMatchWithTheRightArtistToo() {
        val url = "https://tabs.ultimate-guitar.com/tab/film-1"
        for (artist in listOf(river.artist, "Misc Soundtrack")) {
            for (title in listOf("The Film - Moon", "The Film - Moonlight")) {
                fake.handler = { request ->
                    answer(request, mapOf(
                        "https://www.ultimate-guitar.com/search.php" to ugSearch(ugResult(url, title, artist, 50)),
                        url to ugTab(ugWalking),
                        "https://acordes.lacuerda.net/busca.php" to "",
                        "https://www.ufret.jp/search.php" to "",
                        "https://music.163.com/api/search/get" to "{}"
                    ))
                }
                assertEquals("$artist: $title", title == "The Film - Moon", chords(river.copy(title = "Moon"), english) is ChordFinder.Chords.Found)
            }
        }
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
    fun aSongUltimateGuitarHasNoChordsForComesFromCifraClubsSheetByTheArtistWithoutItsTabs() {
        val searches = mutableListOf<String?>()
        fake.handler = { request ->
            if (request.url.host == "solr.sscdn.co") {
                searches += request.url.queryParameter("q")
                FakeSpotify.html(
                    request,
                    cifraClubSearch(
                        // The artist, and another's song of the same name, which come first.
                        cifraClubDoc("1", "The Walkers", "The Walkers", "the-walkers", ""),
                        cifraClubDoc("2", "River Road", "Someone Else", "someone-else", "river-road"),
                        cifraClubDoc("2", "River Road", "The Walkers", "the-walkers", "river-road")
                    )
                )
            } else {
                answer(
                    request,
                    mapOf(
                        "https://www.ultimate-guitar.com/search.php" to ugSearch(),
                        "https://www.cifraclub.com.br/the-walkers/river-road/" to cifraClubPage(
                            "<div class=\"kvMV\">\u200B[Intro] <b>Em</b>\n\n</div>" +
                                "<div class=\"kvMV\"><div class=\"tabs\"><span class=\"tab\">[Tab - Intro]\n<b>Em</b>\nE|-3---0---|\nB|-3---0---|\n</span></div>\n</div>" +
                                cifraClubWalking.replace("light", "light &amp; more"),
                            capo = 3
                        )
                    )
                )
            }
        }
        val found = chords(river, english) as ChordFinder.Chords.Found
        assertEquals(ChordSite.CIFRA_CLUB, found.sheet.site)
        assertEquals("https://www.cifraclub.com.br/the-walkers/river-road/", found.sheet.url)
        assertEquals(3, found.sheet.capo)
        assertEquals(
            listOf(
                // Chords on their own line, words and all, are played on their own.
                SheetLine("", listOf(Chord(8, "Em"))),
                SheetLine("Walking down the river road", listOf(Chord(0, "G"), Chord(8, "C"))),
                SheetLine("Singing to the morning light & more", listOf(Chord(0, "D")))
            ),
            found.sheet.lines
        )
        // Found by the artist, the artist's own first, and before LaCuerda and U-FRET.
        assertEquals(listOf("River Road The Walkers"), searches)
        assertEquals(listOf("https://www.cifraclub.com.br/the-walkers/river-road/"), fake.requestedUrls.filter { "cifraclub" in it })
        assertTrue(fake.requestedUrls.none { "lacuerda" in it || "ufret" in it })
        // Its pages only answer a phone's browser.
        assertTrue(fake.requestHeaders.contains("User-Agent" to LyricsFinder.LETRAS_BROWSER))
        assertTrue(fake.requestHeaders.contains("Sec-Fetch-Mode" to "navigate"))
    }

    @Test
    fun aSheetASearchListsThatsGoneIsNoChordsNotASiteThatWontAnswer() {
        // Each site lists a sheet whose page answers with a 404, as LaCuerda's search keeps ones it took down.
        fake.handler = { request ->
            when {
                request.url.host == "solr.sscdn.co" -> FakeSpotify.html(request, cifraClubSearch(cifraClubDoc("2", "River Road", "The Walkers", "the-walkers", "river-road")))
                request.url.encodedPath == "/busca.php" -> FakeSpotify.html(request, laCuerdaSearch(Triple("walkers", "The Walkers", listOf(LaCuerdaSong("River Road", "R-1", "river_road")))))
                request.url.encodedPath == "/search.php" && request.url.host == "www.ufret.jp" -> FakeSpotify.html(request, uFretResult(2, "川の道", "歩く人"))
                request.url.encodedPath == "/search.php" -> FakeSpotify.html(request, ugSearch(ugResult("https://tabs.ultimate-guitar.com/tab/gone-1", "River Road", "The Walkers", 900)))
                request.url.encodedPath == "/db/search.cgi" -> jTotalPage(request, jTotalResult("gone/001", "川の道"))
                request.url.host == "music.163.com" -> FakeSpotify.html(request, "{}")
                request.url.host == "itunes.apple.com" -> FakeSpotify.html(request, """{"results":[]}""")
                else -> FakeSpotify.html(request, "", code = 404)
            }
        }
        assertEquals(ChordFinder.Chords.None, chords(river, english))
        assertTrue(fake.requestedUrls.containsAll(listOf(
            "https://tabs.ultimate-guitar.com/tab/gone-1",
            "https://www.cifraclub.com.br/the-walkers/river-road/",
            "https://acordes.lacuerda.net/walkers/river_road.shtml"
        )))
        assertEquals(ChordFinder.Chords.None, chords(kawa, japanese))
        assertTrue("https://www.ufret.jp/song.php?data=2" in fake.requestedUrls)
        assertTrue("https://music.j-total.net/data/gone/001.html" in fake.requestedUrls)
    }

    @Test
    fun aCifraClubSearchThatFindsNoSheetOfTheSongsWordsLeavesItToLaCuerda() {
        fake.handler = { request ->
            when {
                request.url.host == "solr.sscdn.co" -> FakeSpotify.html(request, cifraClubSearch(cifraClubDoc("2", "River Road", "The Walkers", "the-walkers", "river-road")))
                request.url.encodedPath == "/busca.php" -> FakeSpotify.html(request, laCuerdaSearch(Triple("walkers", "The Walkers", listOf(LaCuerdaSong("River Road", "R-1", "river_road")))))
                else -> answer(
                    request,
                    mapOf(
                        "https://www.ultimate-guitar.com/search.php" to ugSearch(),
                        // Another song's words, and a page with no sheet at all.
                        "https://www.cifraclub.com.br/the-walkers/river-road/" to cifraClubPage("<b>Am</b>\nNothing like the song at all, really"),
                        "https://acordes.lacuerda.net/walkers/river_road.shtml" to laCuerdaSheet(laCuerdaWalking)
                    )
                )
            }
        }
        assertEquals(ChordSite.LA_CUERDA, (chords(river, english) as ChordFinder.Chords.Found).sheet.site)
        fake.handler = { request ->
            if (request.url.host == "solr.sscdn.co") FakeSpotify.html(request, cifraClubSearch(cifraClubDoc("2", "River Road", "The Walkers", "the-walkers", "river-road")))
            else if (request.url.encodedPath == "/busca.php") FakeSpotify.html(request, laCuerdaSearch(Triple("walkers", "The Walkers", listOf(LaCuerdaSong("River Road", "R-1", "river_road")))))
            else answer(request, mapOf("https://www.ultimate-guitar.com/search.php" to ugSearch(), "https://www.cifraclub.com.br/" to "<html></html>", "https://acordes.lacuerda.net/walkers/river_road.shtml" to laCuerdaSheet(laCuerdaWalking)))
        }
        assertEquals(ChordSite.LA_CUERDA, (chords(river, english) as ChordFinder.Chords.Found).sheet.site)
        // A search with nothing in it at all.
        fake.handler = { request ->
            if (request.url.host == "solr.sscdn.co") FakeSpotify.html(request, "({})")
            else if (request.url.encodedPath == "/busca.php") FakeSpotify.html(request, laCuerdaSearch(Triple("walkers", "The Walkers", listOf(LaCuerdaSong("River Road", "R-1", "river_road")))))
            else answer(request, mapOf("https://www.ultimate-guitar.com/search.php" to ugSearch(), "https://acordes.lacuerda.net/walkers/river_road.shtml" to laCuerdaSheet(laCuerdaWalking)))
        }
        assertEquals(ChordSite.LA_CUERDA, (chords(river, english) as ChordFinder.Chords.Found).sheet.site)
    }

    @Test
    fun aTitleWithWhatItAddsInBracketsIsLookedUpWithoutThemWhenNothingIsFoundByIt() {
        val searches = mutableListOf<String?>()
        fake.handler = { request ->
            if (request.url.host == "www.ultimate-guitar.com") {
                searches += request.url.queryParameter("value")
                val bare = request.url.queryParameter("value")?.startsWith("River Road (") == false
                FakeSpotify.html(request, if (bare) ugSearch(ugResult("https://tabs.ultimate-guitar.com/tab/the-walkers/river-road-chords-1", "River Road", "The Walkers", 10)) else ugSearch())
            } else {
                answer(request, mapOf("https://tabs.ultimate-guitar.com/tab/the-walkers/river-road-chords-1" to ugTab(ugWalking)))
            }
        }
        val found = chords(MusicMetadata("River Road (Take 2)", "The Walkers"), english) as ChordFinder.Chords.Found
        assertEquals("https://tabs.ultimate-guitar.com/tab/the-walkers/river-road-chords-1", found.sheet.url)
        assertEquals(listOf("River Road (Take 2) The Walkers", "River Road (Take 2)", "River Road The Walkers"), searches)
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
        val key = "${ChordFinder.CACHE_VERSION}\u0000${SongNames.normalize(river.title)}\u0000${SongNames.normalize(river.artist)}"
        runBlocking { cache.put(key, "{}") }
        assertEquals(ChordFinder.Chords.Unavailable, chords(river, english, finder(cache)))
        runBlocking { cache.put(key, """{"site":"NOWHERE","url":"","capo":0,"lines":[]}""") }
        assertEquals(ChordFinder.Chords.Unavailable, chords(river, english, finder(cache)))
        // A sheet kept before sheets were read this way, e.g. with its chords still in solfège.
        val old = """{"site":"LA_CUERDA","url":"https://acordes.lacuerda.net/walkers/river_road.shtml","capo":0,"lines":[["Walking down the river road",[[0,"LAm"]]]]}"""
        runBlocking { cache.put("${SongNames.normalize(river.title)}\u0000${SongNames.normalize(river.artist)}", old) }
        assertEquals(ChordFinder.Chords.Unavailable, chords(river, english, finder(cache)))
        // A page of the words alone, kept before those stopped counting as chords.
        val words = english.joinToString(",") { """["$it",[]]""" }
        runBlocking { cache.put(key, """{"site":"CIFRA_CLUB","url":"https://www.cifraclub.com.br/the-walkers/river-road/","capo":0,"lines":[$words]}""") }
        assertEquals(ChordFinder.Chords.Unavailable, chords(river, english, finder(cache)))
    }

    @Test
    fun ultimateGuitarsPagesAreReadWithTheirSpacesAndLetters() {
        fake.handler = { request ->
            answer(
                request,
                mapOf(
                    "https://www.ultimate-guitar.com/search.php" to ugSearch(ugResult("https://tabs.ultimate-guitar.com/tab/accents-1", "Canción del río", "Beyoncé", 3)),
                    "https://tabs.ultimate-guitar.com/tab/accents-1" to ugTab("[ch]G[/ch]   [ch]C[/ch]\nCaminando junto al río\n[ch]D[/ch]\nCantando a la luz del día\n[ch]Em[/ch]\nSo&shy;ñando c&oelig;ur wei&szlig; &bdquo;hoy&ldquo;")
                        .replace("del día", "del d&iacute;a &bogus;").replace("é", "&eacute;").replace("í", "&#237;").replace("ó", "&#xF3;")
                )
            )
        }
        val words = listOf("Caminando junto al río", "Cantando a la luz del día &bogus;", "Soñando cœur weiß „hoy“")
        val found = chords(MusicMetadata("Canción del río", "Beyoncé"), words) as ChordFinder.Chords.Found
        assertEquals(
            listOf(
                SheetLine("Caminando junto al río", listOf(Chord(0, "G"), Chord(4, "C"))),
                SheetLine("Cantando a la luz del día &bogus;", listOf(Chord(0, "D"))),
                SheetLine("Soñando cœur weiß „hoy“", listOf(Chord(0, "Em")))
            ),
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
    fun aTitleIsLookedUpWithoutWhatItAddsInBrackets() {
        val words = listOf("川の道を歩いていく", "朝の光に歌っている")
        val inTwo = "プラスティック・ラブ (Directors Edit Version) - Plastic Love(Directors Edit Version)"
        // As YouTube Music names it: U-FRET only finds it by its name alone.
        fake.handler = uFret(
            mapOf("プラスティック・ラブ" to uFretResult(6, "プラスティック・ラブ", "竹内まりや")),
            mapOf(
                "https://music.163.com/api/search/get" to netEase("Plastic Love" to listOf("竹内まりや")),
                "https://www.ufret.jp/song.php?data=6" to uFretSong("[Am]川の道を歩いていく", "朝の光に歌っている")
            )
        )
        for (title in listOf(inTwo, "プラスティック・ラブ (Directors Edit Version)")) {
            assertEquals(title, "https://www.ufret.jp/song.php?data=6", (chords(MusicMetadata(title, "Mariya Takeuchi"), words) as ChordFinder.Chords.Found).sheet.url)
        }
        // Ultimate Guitar only finds it by its name alone in Latin letters.
        val sheet = "https://tabs.ultimate-guitar.com/tab/plastic-1"
        fake.handler = { request ->
            if (request.url.host == "www.ultimate-guitar.com" && request.url.queryParameter("value") == "Plastic Love Mariya Takeuchi") {
                FakeSpotify.html(request, ugSearch(ugResult(sheet, "Plastic Love", "Mariya Takeuchi", 3)))
            } else {
                answer(request, mapOf(sheet to ugTab(ugWalking), "https://www.ultimate-guitar.com/search.php" to ugSearch()))
            }
        }
        val english = listOf("Walking down the river road", "Singing to the morning light")
        assertEquals(sheet, (chords(MusicMetadata(inTwo, "Mariya Takeuchi"), english) as ChordFinder.Chords.Found).sheet.url)
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
    fun aSongNamedInEnglishThatNetEaseKnowsNoJapaneseNameForIsLookedUpOnUFretByJapansAppleMusicName() {
        // NetEase knows it only by its English name; Japan's Apple Music store has it as "川の歌", by "山田太郎".
        val song = MusicMetadata("The River Song (Long Version)", "Taro Yamada")
        val store = JSONObject().put(
            "results",
            JSONArray(
                listOf("川の歌 (Long Version)" to "山田太郎", "The River Song" to "Taro Yamada", "川の歌" to "山田太郎", "川の歌 2009" to "山田太郎", "海の歌" to "山田太郎")
                    .map { (title, artist) -> JSONObject().put("trackName", title).put("artistName", artist) }
            )
        ).toString()
        fake.handler = uFret(
            mapOf("川の歌 2009 山田太郎" to uFretResult(3, "川の歌 2009", "山田太郎"), "川の歌 山田太郎" to uFretResult(4, "川の歌", "山田太郎")),
            mapOf(
                "https://music.163.com/api/search/get" to netEase("The River Song" to listOf("Someone")),
                "https://itunes.apple.com/search" to store,
                "https://www.ufret.jp/song.php?data=4" to uFretSong("[C]川の道を歩いていく", "朝の光に歌っている")
            )
        )
        assertEquals("https://www.ufret.jp/song.php?data=4", (chords(song, japanese) as ChordFinder.Chords.Found).sheet.url)
        // Its name there without what the version adds, and only the first two names.
        assertEquals(listOf("The River Song (Long Version) Taro Yamada", "川の歌 山田太郎"), uFretSearches)
        val asked = fake.requestedUrls.filter { it.startsWith("https://itunes.apple.com/search") }.map { it.toHttpUrl() }
        assertEquals(listOf("jp"), asked.map { it.queryParameter("country") })
        assertEquals(listOf("The River Song (Long Version) Taro Yamada"), asked.map { it.queryParameter("term") })
        assertEquals(listOf("song"), asked.map { it.queryParameter("entity") })
        // A name in Japanese from NetEase needs no other.
        fake.requestedUrls.clear()
        fake.handler = uFret(
            mapOf("川の歌" to uFretResult(4, "川の歌", "山田太郎")),
            mapOf(
                "https://music.163.com/api/search/get" to netEase("川の歌" to listOf("山田太郎")),
                "https://www.ufret.jp/song.php?data=4" to uFretSong("[C]川の道を歩いていく", "朝の光に歌っている")
            )
        )
        assertEquals("https://www.ufret.jp/song.php?data=4", (chords(song, japanese) as ChordFinder.Chords.Found).sheet.url)
        assertTrue(fake.requestedUrls.none { "itunes.apple.com" in it })
        // Not there, it's looked up by its first two names there, then its own by the singer's.
        fake.requestedUrls.clear()
        fake.handler = uFret(
            emptyMap(),
            mapOf("https://music.163.com/api/search/get" to "{}", "https://itunes.apple.com/search" to store, "https://www.ultimate-guitar.com/search.php" to ugSearch())
        )
        assertEquals(ChordFinder.Chords.None, chords(song, japanese))
        assertEquals(
            listOf("The River Song (Long Version) Taro Yamada", "川の歌 山田太郎", "川の歌 2009 山田太郎", "The River Song (Long Version) 山田太郎", "山田太郎"),
            uFretSearches
        )
        // Apple not answering may hide the sheet: that's chords unavailable, not none.
        fake.handler = uFret(
            emptyMap(),
            mapOf("https://music.163.com/api/search/get" to "{}", "https://itunes.apple.com/search" to "", "https://www.ultimate-guitar.com/search.php" to ugSearch())
        )
        assertEquals(ChordFinder.Chords.Unavailable, chords(song, japanese))
    }

    @Test
    fun aLookupAsksNetEaseAndAppleForASongsOtherNamesOnceWhicheverStepsNeedThem() {
        val song = MusicMetadata("The River Song", "Taro Yamada")
        val namesAsked = { fake.requestedUrls.count { "music.163.com" in it } to fake.requestedUrls.count { "itunes.apple.com" in it } }
        // U-FRET and J-Total both want them, and nowhere has a sheet.
        fake.handler = uFret(emptyMap(), mapOf("https://music.163.com/api/search/get" to "{}", "https://www.ultimate-guitar.com/search.php" to ugSearch()))
        assertEquals(ChordFinder.Chords.None, chords(song, japanese))
        assertEquals(1 to 1, namesAsked())
        // J-Total reads the names U-FRET was given, and finds the sheet by one of them.
        fake.requestedUrls.clear()
        jTotal(mapOf("川の歌" to jTotalResult("kawa/001", "川の歌")), mapOf("/data/kawa/001.html" to jTotalSheet))
        val pages = fake.handler
        fake.handler = { request ->
            if (request.url.host == "music.163.com") FakeSpotify.html(request, netEase("川の歌" to listOf("山田太郎"))) else pages(request)
        }
        assertEquals("https://music.j-total.net/data/kawa/001.html", (chords(song, japanese) as ChordFinder.Chords.Found).sheet.url)
        assertEquals(1 to 0, namesAsked())
        // A song named in Japanese needs no other names at all.
        fake.requestedUrls.clear()
        fake.handler = uFret(emptyMap(), mapOf("https://www.ultimate-guitar.com/search.php" to ugSearch()))
        assertEquals(ChordFinder.Chords.None, chords(kawa, japanese))
        assertEquals(0 to 0, namesAsked())
        // Each lookup asks once for itself: nothing is kept from one to the next.
        fake.handler = uFret(emptyMap(), mapOf("https://music.163.com/api/search/get" to "{}", "https://www.ultimate-guitar.com/search.php" to ugSearch()))
        assertEquals(ChordFinder.Chords.None, chords(song, japanese))
        assertEquals(ChordFinder.Chords.None, chords(song, japanese))
        assertEquals(2 to 2, namesAsked())
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

    private fun notFound(result: ChordFinder.Chords) = assertFalse(result.toString(), result is ChordFinder.Chords.Found)

    @Test
    fun pagesWithPartsMissingAreNoSheetAndNeverACrash() {
        val result = ugResult("https://tabs.ultimate-guitar.com/tab/river-1", "River Road", "The Walkers", 900)
        val laCuerda = laCuerdaSearch(Triple("walkers", "The Walkers", listOf(LaCuerdaSong("River Road", "R-1", "river_road"))))
        fun everyoneElse(request: Request) = when {
            request.url.host == "solr.sscdn.co" -> FakeSpotify.html(request, cifraClubSearch())
            request.url.encodedPath == "/busca.php" -> FakeSpotify.html(request, "<table></table>")
            else -> FakeSpotify.html(request, "", code = 404)
        }
        // Ultimate Guitar: a search page with no app data, with data but no results, a sheet with no page data, with no sheet in it.
        listOf("<html></html>", ugPage(JSONObject()), ugSearch(result)).forEachIndexed { index, search ->
            fake.handler = { request ->
                when {
                    request.url.encodedPath == "/search.php" -> FakeSpotify.html(request, search)
                    request.url.host == "tabs.ultimate-guitar.com" -> FakeSpotify.html(request, if (index == 2) "<html></html>" else ugTab(ugWalking))
                    else -> everyoneElse(request)
                }
            }
            notFound(chords(river, english))
        }
        fake.handler = { request ->
            when {
                request.url.encodedPath == "/search.php" -> FakeSpotify.html(request, ugSearch(result))
                request.url.host == "tabs.ultimate-guitar.com" -> FakeSpotify.html(request, ugPage(JSONObject().put("tab_view", JSONObject())))
                else -> everyoneElse(request)
            }
        }
        notFound(chords(river, english))

        // U-FRET lists a sheet whose page has no chords in it.
        fake.handler = uFret(mapOf("川の道 歩く人" to uFretResult(2, "川の道", "歩く人")), mapOf("https://www.ufret.jp/song.php?data=2" to "<html></html>"))
        notFound(chords(kawa, japanese))

        // LaCuerda: results with no list of pages, or with none for their row.
        listOf(laCuerda.substringBefore("<script>"), laCuerda.replace(Regex("var fns=\\[.*?];"), "var fns=[];")).forEach { search ->
            fake.handler = { request ->
                if (request.url.encodedPath == "/busca.php") FakeSpotify.html(request, search) else if (request.url.host == "solr.sscdn.co") FakeSpotify.html(request, cifraClubSearch()) else FakeSpotify.html(request, "", code = 404)
            }
            notFound(chords(river, english))
        }

        // Cifra Club: an answer with no response in it, and a sheet with no capo (so, none).
        fake.handler = { request -> if (request.url.host == "solr.sscdn.co") FakeSpotify.html(request, """({"response":{}})""") else everyoneElse(request) }
        notFound(chords(river, english))
        fake.handler = { request ->
            when {
                request.url.host == "solr.sscdn.co" -> FakeSpotify.html(request, cifraClubSearch(cifraClubDoc("2", "River Road", "The Walkers", "the-walkers", "river-road")))
                request.url.host == "www.cifraclub.com.br" -> FakeSpotify.html(request, cifraClubPage(cifraClubWalking).replace("""\"capo\":0,""", ""))
                else -> everyoneElse(request)
            }
        }
        val found = chords(river, english) as ChordFinder.Chords.Found
        assertEquals(ChordSite.CIFRA_CLUB, found.sheet.site)
        assertEquals(0, found.sheet.capo)
    }

    @Test
    fun whatNetEaseCallsASongNeedsAnArtistAndAJapaneseNameToMatter() {
        val kimi = MusicMetadata("Kimi ha 1000%", "1986 OMEGA TRIBE")
        // No result in its answer, no songs in it, a song with no artists, and one of another name with some.
        listOf("""{"result":{}}""", """{"code":200}""", """{"result":{"songs":[{"name":"君は1000%"}]}}""", """{"result":{"songs":[{"name":"Other","artists":[{"name":"誰か"}]}]}}""").forEach { answer ->
            fake.requestedUrls.clear()
            fake.handler = uFret(emptyMap(), mapOf("https://music.163.com/api/search/get" to answer))
            notFound(chords(kimi, japanese))
        }
    }

    @Test
    fun aSheetPageMissingWhatTheSiteAlwaysHasIsNoSheetAndTheNextSiteIsAsked() {
        val ugUrl = "https://tabs.ultimate-guitar.com/tab/walkers-1"
        val ugListed = ugSearch(ugResult(ugUrl, "River Road", "The Walkers", 10))
        val cifraListed = cifraClubSearch(cifraClubDoc("2", "River Road", "The Walkers", "the-walkers", "river-road"))
        val laCuerdaListed = laCuerdaSearch(Triple("walkers", "The Walkers", listOf(LaCuerdaSong("River Road", "RT-21", "river_road"))))
        val uFretListed = uFretResult(7, "River Road", "The Walkers")
        fun pages(ug: String, cifra: String, laCuerda: String, uFretSong: String) = { request: Request ->
            when {
                request.url.host == "solr.sscdn.co" -> FakeSpotify.html(request, cifraListed)
                request.url.host == "acordes.lacuerda.net" && request.url.encodedPath == "/busca.php" -> FakeSpotify.html(request, laCuerdaListed)
                request.url.host == "acordes.lacuerda.net" -> FakeSpotify.html(request, laCuerda)
                request.url.host == "www.ufret.jp" && request.url.encodedPath == "/search.php" -> FakeSpotify.html(request, uFretListed)
                request.url.host == "www.ufret.jp" -> FakeSpotify.html(request, uFretSong)
                request.url.toString().startsWith(ugUrl) -> FakeSpotify.html(request, ug)
                request.url.host == "www.cifraclub.com.br" -> FakeSpotify.html(request, cifra)
                request.url.host == "www.ultimate-guitar.com" -> FakeSpotify.html(request, ugListed)
                else -> FakeSpotify.html(request, "{}")
            }
        }
        // No store in Ultimate Guitar's page, no sheet in Cifra Club's, no row list in LaCuerda's and no chords in U-FRET's.
        fake.handler = pages(ug = "<html></html>", cifra = "<html></html>", laCuerda = "<html></html>", uFretSong = "<html></html>")
        assertEquals(ChordFinder.Chords.None, chords(river, english))
        // A store with no page in it, and a page with no sheet view.
        fake.requestedUrls.clear()
        val storeWithoutPage = """<div class="js-store" data-content="{&quot;store&quot;:{}}"></div>"""
        fake.handler = pages(ug = storeWithoutPage, cifra = "<html></html>", laCuerda = "<html></html>", uFretSong = "<html></html>")
        assertEquals(ChordFinder.Chords.None, chords(river, english))
        fake.handler = pages(ug = ugPage(JSONObject()), cifra = "<html></html>", laCuerda = "<html></html>", uFretSong = "<html></html>")
        assertEquals(ChordFinder.Chords.None, chords(river, english))
    }

    @Test
    fun aKanaOnlyTitleOrAnArtistListThatIsNoObjectIsTakenFromNetEaseAsItIs() {
        val bad = JSONObject().put(
            "result",
            JSONObject().put("songs", JSONArray(listOf(JSONObject().put("name", "ひとつ").put("artists", JSONArray(listOf("not an object", JSONObject().put("name", "歩く人")))), "no song at all")))
        ).toString()
        fake.handler = uFret(
            mapOf("ひとつ 歩く人" to uFretResult(5, "ひとつ", "歩く人")),
            mapOf("https://music.163.com/api/search/get" to bad, "https://www.ufret.jp/song.php?data=5" to uFretSong("[C]川の道を歩いていく", "朝の光に歌っている"))
        )
        assertEquals("https://www.ufret.jp/song.php?data=5", (chords(MusicMetadata("ひとつ", "Aruku Hito"), japanese) as ChordFinder.Chords.Found).sheet.url)
        // A sheet of other words than the song's is none of its own.
        val uFretOnly = uFret(
            mapOf("ひとつ Aruku Hito" to uFretResult(5, "ひとつ", "Aruku Hito")),
            mapOf("https://music.163.com/api/search/get" to "{}", "https://www.ufret.jp/song.php?data=5" to uFretSong("[C]全然違う歌詞です"))
        )
        fake.handler = { request ->
            when (request.url.host) {
                "www.ultimate-guitar.com" -> FakeSpotify.html(request, ugSearch(), code = 404)
                "acordes.lacuerda.net" -> FakeSpotify.html(request, "", code = 500)
                else -> uFretOnly(request)
            }
        }
        assertEquals(ChordFinder.Chords.None, chords(MusicMetadata("ひとつ", "Aruku Hito"), japanese))
    }

    private val chordPro = """
        {title:川の道}
        {subtitle:歌:歩く人}
        {capo:2}
        [Am]川の道を歩いていく
        [F]朝の光に歌っている
    """.trimIndent()

    private fun wikiHref(page: String) = "https://ja.chordwiki.org/wiki/" + java.net.URLEncoder.encode(page, "UTF-8")

    private fun withWiki(pages: ChordWikiPages, cache: LookupCache? = null, pauseMs: Long = 0) =
        ChordFinder(fake.client(), lookups, cache, pages, chordWikiPauseMs = pauseMs)

    /** The other chord sites have nothing, so only ChordWiki can answer. */
    private fun noOtherChordSites() {
        fake.handler = { request ->
            when (request.url.host) {
                "www.ultimate-guitar.com" -> FakeSpotify.html(request, ugSearch())
                "music.j-total.net" -> FakeSpotify.html(request, "")
                "www.ufret.jp" -> FakeSpotify.html(request, "")
                "music.163.com" -> FakeSpotify.html(request, """{"result":{}}""")
                "itunes.apple.com" -> FakeSpotify.html(request, """{"results":[]}""")
                else -> answer(request, emptyMap())
            }
        }
    }

    @Test
    fun englishWordsNeverAskChordWiki() {
        var asked = false
        fake.handler = { request -> answer(request, mapOf("https://www.ultimate-guitar.com/search.php" to ugSearch())) }
        chords(river, english, withWiki(ChordWikiPages { asked = true; error("ChordWiki was asked") }))
        assertFalse(asked)
    }

    @Test
    fun chordWikiIsLookedUpAlongsideUFretAndGivesWayWhenUFretHasTheSheet() {
        val started = CompletableDeferred<Unit>()
        fake.handler = uFret(
            mapOf("川の道 歩く人" to uFretResult(1, "川の道", "歩く人")),
            mapOf("https://www.ufret.jp/song.php?data=1" to uFretSong("[Am]川の道を歩いていく", "[F]朝の光に歌っている"))
        )
        val pages = ChordWikiPages {
            started.complete(Unit)
            suspendCancellableCoroutine { }
        }
        val began = System.nanoTime()
        val found = chords(kawa, japanese, withWiki(pages)) as ChordFinder.Chords.Found
        // Cancelling the wiki lookup is what lets this return. Waiting out its own timeout would take 20s.
        assertTrue(System.nanoTime() - began < 8_000_000_000L)
        assertTrue(started.isCompleted)
        assertEquals(ChordSite.U_FRET, found.sheet.site)
        assertEquals("https://www.ufret.jp/song.php?data=1", found.sheet.url)
    }

    @Test
    fun chordWikiSuppliesTheSheetWhenNoOtherSiteHasIt() {
        noOtherChordSites()
        val urls = mutableListOf<String>()
        val found = chords(kawa, japanese, withWiki(ChordWikiPages { url -> urls += url; chordPro })) as ChordFinder.Chords.Found
        assertEquals(ChordSite.CHORDWIKI, found.sheet.site)
        assertEquals(2, found.sheet.capo)
        assertEquals("https://ja.chordwiki.org/wiki/${java.net.URLEncoder.encode("川の道", "UTF-8")}", found.sheet.url)
        assertEquals(listOf(SheetLine("川の道を歩いていく", listOf(Chord(0, "Am"))), SheetLine("朝の光に歌っている", listOf(Chord(0, "F")))), found.sheet.lines)
        // The edit page, once, and not the search: the title was already the sheet's name.
        assertEquals(listOf(chordWikiEditUrl("川の道")), urls)
        assertFalse(fake.requestedUrls.any { "music.163.com" in it })
    }

    @Test
    fun aLatinTitleIsReadOnChordWikiByTheJapaneseNameAndAMissThereIsSearched() {
        noOtherChordSites()
        val song = MusicMetadata("Kawa no Michi", "歩く人")
        val urls = mutableListOf<String>()
        val sites = fake.handler
        // Named in Japanese by NetEase, so the edit page is that name and the search is never opened.
        fake.handler = { request ->
            if (request.url.host == "music.163.com") FakeSpotify.html(request, """{"result":{"songs":[{"name":"川の道","artists":[{"name":"歩く人"}]}]}}""") else sites(request)
        }
        var edits = 0
        val found = chords(
            song,
            japanese,
            withWiki(ChordWikiPages { url ->
                urls += url
                when {
                    "search.html" in url -> error("searched")
                    else -> { edits++; chordPro }
                }
            })
        ) as ChordFinder.Chords.Found
        assertEquals(chordWikiViewUrl("川の道"), found.sheet.url)
        assertTrue(urls.first().contains(java.net.URLEncoder.encode("川の道", "UTF-8")))
        assertEquals(1, edits)

        // The name search fails, and the edit page for the Latin title is empty: one search, then that sheet, not another song's.
        urls.clear()
        fake.handler = { request ->
            if (request.url.host == "music.163.com") FakeSpotify.html(request, "", code = 500) else sites(request)
        }
        // The title we could ask for is the Latin one, so a Japanese hit of another name isn't this song.
        // The first edit of that title is empty; the same address after the search is the sheet the singer owns.
        val hits = "海の歌 - 歩く人\t${wikiHref("海の歌")}\nKawa no Michi - 他の人\t${wikiHref("Kawa no Michi (2)")}\nKawa no Michi - 歩く人\t${wikiHref("Kawa no Michi")}"
        edits = 0
        val searched = chords(
            song,
            japanese,
            withWiki(
                ChordWikiPages { url ->
                    urls += url
                    when {
                        "search.html" in url -> hits
                        else -> if (edits++ == 0) "" else chordPro
                    }
                },
                pauseMs = 1
            )
        ) as ChordFinder.Chords.Found
        assertEquals(chordWikiViewUrl("川の道"), searched.sheet.url)
        assertTrue(urls.any { "search.html" in it })
        assertTrue(urls.none { java.net.URLDecoder.decode(it, "UTF-8").contains("海の歌") })
    }

    @Test
    fun aLatinTitleWhoseJapaneseSearchHitIsAnotherSongIsStillReadOnChordWiki() {
        // The search that names "This Night" also names "BEYOND THE TIME (メビウスの宇宙を越えて)",
        // and the one for "IT'S SO DELICIOUS" leads with a longer Japanese title. Neither page is
        // the song. The name it was given still has the sheet, and that is the one to show.
        noOtherChordSites()
        val sites = fake.handler
        val own = """
            {title:This Night}
            {subtitle:歌:TM NETWORK}
            [G]川の道を歩いていく
            [A]朝の光に歌っている
        """.trimIndent()
        val other = """
            {title:別の歌}
            [C]全然違う歌詞です
            これも別の話です
        """.trimIndent()
        val beyond = "BEYOND THE TIME (メビウスの宇宙を越えて) -2025 Version-"
        val medley = "决戦は金曜日~IT'S SO DELICIOUS~决戦は金曜日"
        val urls = mutableListOf<String>()
        fun pages(ownTitle: String) = ChordWikiPages { url ->
            urls += url
            val decoded = java.net.URLDecoder.decode(url, "UTF-8")
            when {
                "search.html" in url -> ""
                beyond in decoded || medley in decoded -> other
                decoded.contains("t=$ownTitle") -> own.replace("This Night", ownTitle)
                else -> error(decoded)
            }
        }
        fake.handler = { request ->
            if (request.url.host == "music.163.com") {
                FakeSpotify.html(request, netEase("This Night" to listOf("TM NETWORK"), beyond to listOf("TM NETWORK")))
            } else {
                sites(request)
            }
        }
        val found = chords(MusicMetadata("This Night", "TM NETWORK"), japanese, withWiki(pages("This Night"))) as ChordFinder.Chords.Found
        assertEquals(chordWikiViewUrl("This Night"), found.sheet.url)
        assertEquals(listOf(beyond, "This Night"), urls.map { java.net.URLDecoder.decode(it.substringAfter("t="), "UTF-8") })

        urls.clear()
        fake.handler = { request ->
            if (request.url.host == "music.163.com") {
                FakeSpotify.html(request, netEase(medley to listOf("DREAMS COME TRUE"), "IT'S SO DELICIOUS" to listOf("DREAMS COME TRUE")))
            } else {
                sites(request)
            }
        }
        val delicious = "IT'S SO DELICIOUS"
        val second = chords(MusicMetadata(delicious, "DREAMS COME TRUE"), japanese, withWiki(pages(delicious))) as ChordFinder.Chords.Found
        assertEquals(chordWikiViewUrl(delicious), second.sheet.url)
        assertEquals(listOf(medley, delicious), urls.map { java.net.URLDecoder.decode(it.substringAfter("t="), "UTF-8") })
    }

    @Test
    fun chordWikiWaitsBetweenPagesAndLeavesABlankArtistOutOfTheSearch() {
        noOtherChordSites()
        val urls = mutableListOf<String>()
        var edits = 0
        val marks = mutableListOf<Long>()
        val found = chords(
            MusicMetadata("川の道", " "),
            japanese,
            withWiki(
                ChordWikiPages { url ->
                    marks += System.nanoTime()
                    urls += url
                    when {
                        "search.html" in url -> "川の道\t${wikiHref("川の道")}"
                        else -> if (edits++ == 0) "" else chordPro
                    }
                },
                pauseMs = 400
            )
        ) as ChordFinder.Chords.Found
        assertEquals(3, marks.size)
        // The pause is between the pages. A slow test around it doesn't count as that pause.
        assertTrue(marks[1] - marks[0] >= 350_000_000L)
        assertTrue(marks[2] - marks[1] >= 350_000_000L)
        val query = java.net.URLDecoder.decode(urls.first { "search.html" in it }.substringAfter("q="), "UTF-8")
        assertEquals("川の道", query)
        assertEquals(ChordSite.CHORDWIKI, found.sheet.site)
    }

    @Test
    fun chordWikiLeavesAnEmptyArtistOutOfTheSearch() {
        noOtherChordSites()
        val urls = mutableListOf<String>()
        var edits = 0
        chords(
            MusicMetadata("川の道", ""),
            japanese,
            withWiki(ChordWikiPages { url ->
                urls += url
                when {
                    "search.html" in url -> "川の道\t${wikiHref("川の道")}"
                    else -> if (edits++ == 0) "" else chordPro
                }
            })
        )
        val query = java.net.URLDecoder.decode(urls.first { "search.html" in it }.substringAfter("q="), "UTF-8")
        assertEquals("川の道", query)
    }

    @Test
    fun chordWikiGivesUpAPageThatIsntTheSongAndACheckItCantPass() {
        noOtherChordSites()
        val wrong = "{title:別の歌}\n[C]全然違う歌詞です\nこれも別の話です"
        var edits = 0
        val found = chords(
            kawa,
            japanese,
            withWiki(ChordWikiPages { url ->
                when {
                    "search.html" in url -> "川の道 - 歩く人\t${wikiHref("川の道")}"
                    else -> { edits++; if (edits == 1) wrong else chordPro }
                }
            })
        ) as ChordFinder.Chords.Found
        assertEquals(ChordSite.CHORDWIKI, found.sheet.site)
        assertEquals(2, edits)

        assertEquals(
            ChordFinder.Chords.Unavailable,
            chords(kawa, japanese, withWiki(ChordWikiPages { throw IOException("ChordWiki asked for a check a person has to click") }))
        )
        // Words of a check aren't sung, and a page that never arrives isn't "no chords" when the sites did answer.
        assertEquals(ChordFinder.Chords.None, chords(kawa, japanese, withWiki(ChordWikiPages { "Just a moment. Nothing like the song." })))
        val slow = withWiki(ChordWikiPages { delay(1_000); chordPro })
        assertEquals(ChordFinder.Chords.Unavailable, runBlocking { slow.chordsOf(kawa, japanese, timeoutMs = 40) })
    }

    @Test
    fun aChordWikiSheetIsKeptSoThePageIsntOpenedAgain() {
        noOtherChordSites()
        val file = File.createTempFile("chords", ".json").apply { delete(); deleteOnExit() }
        val cache = LookupCache(file, Dispatchers.Unconfined)
        var calls = 0
        val first = chords(kawa, japanese, withWiki(ChordWikiPages { calls++; chordPro }, cache)) as ChordFinder.Chords.Found
        assertEquals(ChordSite.CHORDWIKI, first.sheet.site)
        val again = chords(kawa, japanese, withWiki(ChordWikiPages { error("again") }, cache))
        assertEquals(first, again)
        assertEquals(1, calls)
    }

    @Test
    fun aGameVersionTagStillReadsTheChordWikiPageNamedWithoutIt() {
        // Apple names the recording "キラッ! 満開スマイル (GAME VERSION)". ChordWiki's page omits the tag.
        // The edit page of the whole name is empty, and a search hit of the page without the tag is the sheet.
        noOtherChordSites()
        val full = "キラッ! 満開スマイル (GAME VERSION)"
        // The page itself has no space and a fullwidth bang. Guessing an edit address from Apple's
        // spelling doesn't open it. The search lists it, and that hit is this song once the tag is gone.
        val page = "キラッ！満開スマイル"
        val answer = chords(MusicMetadata(full, "島村卯月"), japanese, withWiki(ChordWikiPages { url ->
            val decoded = java.net.URLDecoder.decode(url, "UTF-8")
            when {
                "search.html" in url -> "$page (アニメ「アイドルマスター シンデレラ ...\t${wikiHref(page)}"
                decoded.contains("t=$page") -> chordPro.replace("川の道", page)
                else -> ""
            }
        }))
        val found = answer as? ChordFinder.Chords.Found
        assertEquals(chordWikiViewUrl(page), found?.sheet?.url)
        assertEquals(2, found?.sheet?.lines?.sumOf { it.chords.size })
    }

    @Test
    fun aCreditWithVoiceActorsStillFindsTheChordWikiPage() {
        // Apple credits the game recording to every character and the actor behind each, and
        // ChordWiki's search lists nothing for a query that carries all of that. The song and its lead
        // singer are enough to find the page.
        noOtherChordSites()
        val full = "キラッ! 満開スマイル (GAME VERSION)"
        val credit = "島村卯月 (CV: 大橋彩香), 小日向美穂 (CV: 津田美波) & 双葉杏 (CV: 五十嵐裕美)"
        val page = "キラッ！満開スマイル"
        val queries = mutableListOf<String>()
        val answer = chords(MusicMetadata(full, credit), japanese, withWiki(ChordWikiPages { url ->
            val decoded = java.net.URLDecoder.decode(url, "UTF-8")
            when {
                "search.html" in url -> {
                    queries += decoded.substringAfter("q=")
                    if ("CV:" in decoded || "GAME" in decoded) "" else "$page (アニメ「アイドルマスター シンデレラ ...\t${wikiHref(page)}"
                }
                decoded.contains("t=$page") -> chordPro.replace("川の道", page)
                else -> ""
            }
        }))
        assertEquals(chordWikiViewUrl(page), (answer as? ChordFinder.Chords.Found)?.sheet?.url)
        assertTrue(queries.toString(), queries.none { "CV:" in it })
    }

    @Test
    fun aHalfwidthBangStillOpensTheFullwidthChordWikiPage() {
        // Apple writes a halfwidth "!". ChordWiki's page uses the fullwidth one, so the edit page of
        // the name Apple gives is empty. The search lists nothing for that halfwidth name either.
        noOtherChordSites()
        val half = "回レ!雪月花"
        val full = "回レ！雪月花"
        val answer = chords(MusicMetadata(half, "歌組雪月花"), japanese, withWiki(ChordWikiPages { url ->
            val decoded = java.net.URLDecoder.decode(url, "UTF-8")
            when {
                "search.html" in url -> ""
                decoded.contains("t=$full") -> chordPro.replace("川の道", full)
                else -> ""
            }
        }))
        assertEquals(chordWikiViewUrl(full), (answer as? ChordFinder.Chords.Found)?.sheet?.url)
    }

    @Test
    fun aFullwidthBangStillOpensTheHalfwidthChordWikiPage() {
        // The name we're given uses the fullwidth bang. ChordWiki's page uses the halfwidth one.
        // The search lists nothing, so the edit page of the halfwidth name is the sheet.
        noOtherChordSites()
        val full = "回レ！雪月花"
        val half = "回レ!雪月花"
        val answer = chords(MusicMetadata(full, "歌組雪月花"), japanese, withWiki(ChordWikiPages { url ->
            val decoded = java.net.URLDecoder.decode(url, "UTF-8")
            when {
                "search.html" in url -> ""
                decoded.contains("t=$half") -> chordPro.replace("川の道", half)
                else -> ""
            }
        }))
        assertEquals(chordWikiViewUrl(half), (answer as? ChordFinder.Chords.Found)?.sheet?.url)
    }

    @Test
    fun anUpperCaseTitleIsFoundWhenTheJapaneseAliasPageIsNotTheSong() {
        // NetEase's Japanese name is a different song. The edit page of THIS NIGHT is empty because
        // ChordWiki's page is "This Night". The search for the alias does not list it. The search for
        // the name the song was given does.
        noOtherChordSites()
        val beyond = "BEYOND THE TIME (メビウスの宇宙を越えて) -2025 Version-"
        val sites = fake.handler
        fake.handler = { request ->
            if (request.url.host == "music.163.com") {
                FakeSpotify.html(request, netEase(beyond to listOf("TM NETWORK")))
            } else {
                sites(request)
            }
        }
        val answer = chords(MusicMetadata("THIS NIGHT", "TM NETWORK"), japanese, withWiki(ChordWikiPages { url ->
            val decoded = java.net.URLDecoder.decode(url, "UTF-8")
            when {
                "search.html" in url && "THIS NIGHT" in decoded -> "This Night - TM NETWORK\t${wikiHref("This Night")}"
                "search.html" in url -> ""
                decoded.contains("t=This Night") -> chordPro.replace("川の道", "This Night")
                else -> ""
            }
        }))
        assertEquals(chordWikiViewUrl("This Night"), (answer as? ChordFinder.Chords.Found)?.sheet?.url)
    }
}
