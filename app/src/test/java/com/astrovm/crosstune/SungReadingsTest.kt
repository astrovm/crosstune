package com.astrovm.crosstune

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class SungReadingsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val fake = FakeSpotify()
    private val song = MusicMetadata("夜の都会 ～Night Town～", "Band")
    private val lines = listOf("都会の夜が", "Oh yeah", "存在を知って", "星が光る")

    private fun read(lines: List<String> = this.lines) = Readings.of(lines, Script.JAPANESE)

    private fun ask(cache: LookupCache? = null, song: MusicMetadata = this.song, lines: List<String> = this.lines) =
        runBlocking { SungReadings(fake.client(), cache, Dispatchers.Unconfined).of(song, lines, read(lines)) }

    /** UtaTen's search, with [rows] of page to singer, and its pages, by their address. */
    private fun site(rows: List<Pair<String, String>>, pages: Map<String, String>, failing: Boolean = false): (Request) -> Response = { request ->
        if (failing) throw IOException("offline")
        when (request.url.encodedPath) {
            "/search" -> FakeSpotify.html(request, search(rows))
            in pages.keys -> FakeSpotify.html(request, pages.getValue(request.url.encodedPath))
            else -> FakeSpotify.html(request, "", code = 404)
        }
    }

    private fun search(rows: List<Pair<String, String>>) = """<table class="searchResult artistLyricList">""" + rows.joinToString("") { (page, by) ->
        """<tr><td><p class="searchResult__title"><a href="$page">Song</a></p></td><td class="searchResult__artist"><p><a href="/artist/1/">$by</a></p></td></tr>"""
    } + "</table>"

    private fun ruby(kanji: String, kana: String) = """<span class="ruby"><span class="rb">$kanji</span><span class="rt">$kana</span></span>"""

    /** A song's page as UtaTen writes it: kana over the kanji, typed by someone, so not quite as ours are. */
    private val page = """<html><div class="lyricBody"><div class="medium"><div class="hiragana" >
        ${ruby("都会", "まち")}のよるが<br />
        <br />
        OH YEAH<br />
        ${ruby("存在", "こと")}を${ruby("知", "し")}って &amp; <br />
        ${ruby("星", "ほし")}が${ruby("光", "ひか")}る${ruby("夜", "よる")}<br />
        </div><div class="romaji">machi</div></div></div></html>"""

    @Test
    fun aSongsPageIsReadLineByLineEachKanjiWithItsKana() {
        val theirs = SungReadings.linesOf(page)
        assertEquals(4, theirs.size)
        assertEquals(listOf(Ruby("都会", "まち"), Ruby("のよるが")), theirs[0])
        assertEquals(listOf(Ruby("OH YEAH")), theirs[1])
        // As it shows, its marks written out.
        assertEquals(listOf(Ruby("存在", "こと"), Ruby("を"), Ruby("知", "し"), Ruby("って &")), theirs[2])
        // A page without the words has none.
        assertEquals(emptyList<List<Ruby>>(), SungReadings.linesOf("<html>Not found</html>"))
    }

    @Test
    fun eachLineTakesTheReadingsOfTheLineMostLikeItThere() {
        val fixes = SungReadings.matched(lines, read(), SungReadings.linesOf(page))
        // 都会の夜が is written otherwise there, のよるが, but reads alike; the kanji it has are taken.
        assertTrue(ReadingFix(0, "都会", "まち") in fixes)
        assertTrue(ReadingFix(2, "存在", "こと") in fixes)
        assertTrue(ReadingFix(3, "光", "ひか") in fixes)
        // Only kanji in the line, each in its own line.
        assertTrue(fixes.none { it.line == 1 })
        assertTrue(fixes.none { it.line == 3 && it.kanji == "夜" })
        // A line like none there takes nothing.
        assertEquals(emptyList<ReadingFix>(), SungReadings.matched(listOf("全然違う言葉"), read(listOf("全然違う言葉")), SungReadings.linesOf(page)))
    }

    @Test
    fun linesAreAsAlikeAsThePairsOfLettersTheyShare() {
        assertEquals(1.0, SungReadings.alike("まちのよる", "まちのよる"), 0.0)
        assertEquals(0.0, SungReadings.alike("まちのよる", "ほしがひかる"), 0.0)
        assertEquals(0.5, SungReadings.alike("abcd", "abxy"), 0.2)
        // One letter has no pairs: alike only when it's the same.
        assertEquals(1.0, SungReadings.alike("a", "a"), 0.0)
        assertEquals(0.0, SungReadings.alike("a", "b"), 0.0)
        assertEquals(0.0, SungReadings.alike("", ""), 0.0)
    }

    @Test
    fun theSongIsLookedUpByItsSingerAndItsPageReadOnceThenKept() {
        fake.handler = site(listOf("/lyric/ab0001/" to "Band"), mapOf("/lyric/ab0001/" to page))
        val cache = LookupCache(File(folder.root, "sung.json"), Dispatchers.Unconfined)
        val fixes = ask(cache)!!
        assertTrue(ReadingFix(0, "都会", "まち") in fixes)
        val search = fake.requestedUrls.first().toHttpUrl()
        assertEquals("utaten.com", search.host)
        assertEquals("Band", search.queryParameter("artist_name"))
        assertEquals("夜の都会 ～Night Town～", search.queryParameter("title"))
        assertTrue(fake.requestHeaders.any { (name, value) -> name == "User-Agent" && value == SungReadings.BROWSER })
        // Asked again, it's kept.
        val asked = fake.requestedUrls.size
        assertEquals(fixes, ask(LookupCache(File(folder.root, "sung.json"), Dispatchers.Unconfined)))
        assertEquals(asked, fake.requestedUrls.size)
    }

    @Test
    fun aSongNamedTwiceIsLookedUpByEachNameAndTheSingersPageFirst() {
        val other = """<div class="hiragana" >${ruby("雨", "あめ")}が<br /></div>"""
        fake.handler = { request ->
            val title = request.url.queryParameter("title")
            when {
                request.url.encodedPath == "/search" && title == "Night Town" ->
                    FakeSpotify.html(request, search(listOf("/lyric/zz0002/" to "Someone Else", "/lyric/ab0001/" to "Band")))
                request.url.encodedPath == "/search" -> FakeSpotify.html(request, search(emptyList()))
                request.url.encodedPath == "/lyric/ab0001/" -> FakeSpotify.html(request, page)
                else -> FakeSpotify.html(request, other)
            }
        }
        assertTrue(ReadingFix(0, "都会", "まち") in ask()!!)
        assertTrue(fake.requestedUrls.any { "title=Night%20Town" in it })
        // The singer's own, though listed second.
        assertTrue(fake.requestedUrls.none { "/lyric/zz0002/" in it })
    }

    @Test
    fun anotherSongsPageGivesNoReadings() {
        val other = """<div class="hiragana" >${ruby("雨", "あめ")}が${ruby("降", "ふ")}る<br />${ruby("風", "かぜ")}<br /></div>"""
        fake.handler = site(listOf("/lyric/a1/" to "Band", "/lyric/a2/" to "Band", "/lyric/a3/" to "Band", "/lyric/a4/" to "Band"), mapOf("/lyric/a1/" to other, "/lyric/a2/" to other, "/lyric/a3/" to other, "/lyric/a4/" to other))
        assertEquals(emptyList<ReadingFix>(), ask())
        // At most a few pages are read.
        assertEquals(SungReadings.MAX_PAGES, fake.requestedUrls.count { "/lyric/" in it })
    }

    @Test
    fun aPageWithTooFewLinesLikeOursIsntThisSong() {
        // One line of four alike: another song that shares it.
        val other = """<div class="hiragana" >${ruby("星", "ほし")}が${ruby("光", "ひか")}る<br /></div>"""
        fake.handler = site(listOf("/lyric/ab0001/" to "Band"), mapOf("/lyric/ab0001/" to other))
        assertEquals(emptyList<ReadingFix>(), ask(lines = listOf("都会の夜が", "存在を知って", "星が光る", "雨が降る")))
    }

    @Test
    fun aSongNotThereHasNoneAndOneWithoutKanjiIsntLookedUp() {
        fake.handler = site(emptyList(), emptyMap())
        assertEquals(emptyList<ReadingFix>(), ask())
        fake.requestedUrls.clear()
        assertEquals(emptyList<ReadingFix>(), ask(lines = listOf("ラララ", "Oh yeah")))
        assertTrue(fake.requestedUrls.isEmpty())
    }

    @Test
    fun withoutAnAnswerItSaysNothingAndKeepsNothing() {
        val cache = LookupCache(File(folder.root, "sung.json"), Dispatchers.Unconfined)
        fake.handler = site(emptyList(), emptyMap(), failing = true)
        assertNull(ask(cache))
        // A page that won't open is no answer either.
        fake.handler = site(listOf("/lyric/gone/" to "Band"), emptyMap())
        assertNull(ask(cache))
        // Kept from before, but kept wrong, it's asked again.
        runBlocking { cache.put("utaten|${SungReadings.CACHE_VERSION}|${SongNames.normalize(song.title)}|${SongNames.normalize(song.artist)}|${lines.hashCode()}", "not json") }
        fake.handler = site(listOf("/lyric/ab0001/" to "Band"), mapOf("/lyric/ab0001/" to page))
        assertTrue(ReadingFix(0, "都会", "まち") in ask(cache)!!)
    }
}
