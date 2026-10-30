package com.astrovm.crosstune

import com.astrovm.crosstune.LyricsFinder.Lyrics
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** What each lyrics service may send back that isn't what it should: a part missing, the wrong kind, or too little. Nothing is made up from it. */
@RunWith(RobolectricTestRunner::class)
class LyricsFinderShapesTest {

    private val fake = FakeSpotify()
    private val song = MusicMetadata("初恋", "三田寛子")
    private val lrc = "[00:16.000]五月雨は緑色\n[00:23.080]悲しくさせたよ"

    private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    /** Each service answers with what it's given; the ones not given nothing. */
    private fun route(
        lrclib: String = "[]",
        search: String = """{"result":{}}""",
        lyric: String = "{}",
        lookup: String = """{"results":[]}""",
        next: String? = null,
        browse: String = "{}"
    ) {
        fake.handler = { request: Request ->
            val url = request.url
            when {
                url.host == "lrclib.net" -> FakeSpotify.html(request, lrclib)
                url.encodedPath == "/api/search/get" -> FakeSpotify.html(request, search)
                url.encodedPath == "/api/song/lyric" -> FakeSpotify.html(request, lyric)
                url.encodedPath == "/lookup" -> FakeSpotify.html(request, lookup)
                url.encodedPath == "/youtubei/v1/next" -> next?.let { FakeSpotify.html(request, it) } ?: throw IOException("offline")
                url.encodedPath == "/youtubei/v1/browse" -> FakeSpotify.html(request, browse)
                else -> FakeSpotify.html(request, """{"results":[]}""")
            }
        }
    }

    private fun lookUp(metadata: MusicMetadata = song, finder: LyricsFinder = LyricsFinder(fake.client(), "test", busyPauseMs = 0)) =
        runBlocking { finder.lyricsOf(metadata) }

    private fun netEaseSong(extra: String = "", artists: String = """[{"name":"三田寛子"}]""", name: String = "初恋") =
        """{"result":{"songs":[{"id":2,"name":"$name","artists":$artists$extra}]}}"""

    private fun words(result: Lyrics) = (result as? Lyrics.Found)?.words

    @Test
    fun answersLrclibSendsThatArentSongsOrHaveNoNamesAreLeftOut() {
        val named = """{"trackName":"初恋","artistName":"三田寛子","plainLyrics":"Words"}"""
        route(lrclib = """[1,"two",null,{"trackName":"初恋"},{"artistName":"三田寛子"},{"trackName":5,"artistName":"三田寛子"},{"trackName":" ","artistName":"x"},$named]""")
        assertEquals("Words", words(lookUp()))
    }

    @Test
    fun wordsThatArentTextOrTimesThatArentNumbersAreNotTaken() {
        route(lrclib = """[{"trackName":"初恋","artistName":"三田寛子","plainLyrics":5,"syncedLyrics":["x"],"duration":"long"}]""")
        assertEquals(Lyrics.None, lookUp())
        route(lrclib = """[{"trackName":"初恋","artistName":"三田寛子","plainLyrics":"  ","syncedLyrics":" ","duration":-3}]""")
        assertEquals(Lyrics.None, lookUp())
        route(lrclib = """[{"trackName":"初恋","artistName":"三田寛子","plainLyrics":"Kept","duration":0}]""")
        assertEquals(Lyrics.Found("Kept", source = LyricsFinder.LRCLIB), lookUp())
    }

    @Test
    fun timedWordsWithNoWordsOtherwiseAreTheWords() {
        route(lrclib = """[{"trackName":"初恋","artistName":"三田寛子","plainLyrics":null,"syncedLyrics":${quote(lrc)},"duration":200}]""")
        val found = lookUp() as Lyrics.Found
        assertEquals("五月雨は緑色\n悲しくさせたよ", found.words)
        assertEquals(2, found.lines.size)
        assertEquals(200_000L, found.lengthMs)
    }

    @Test
    fun aNetEaseSearchWithoutResultsOrSongsOrTheRightOnesIsNone() {
        route(search = "[]")
        assertEquals(Lyrics.None, lookUp())
        route(search = """{"result":{"songs":[]}}""")
        assertEquals(Lyrics.None, lookUp())
        route(search = """{"result":{"songs":[1,null,{"id":3,"name":"初恋"}]}}""")
        assertEquals(Lyrics.None, lookUp())
        route(search = netSong(artists = """[1,null,{"nome":"x"},{"name":"Someone"}]"""))
        assertEquals(Lyrics.None, lookUp())
        route(search = netSong(artists = "5"))
        assertEquals(Lyrics.None, lookUp())
    }

    private fun netSong(artists: String = """[{"name":"三田寛子"}]""", extra: String = "") = netEaseSong(extra, artists)

    @Test
    fun aNetEaseSongOfAnotherAlbumOrNoneIsTheSameSongWhenItsNameIs() {
        route(search = netSong(extra = ""","album":{"name":"初恋"}"""), lyric = """{"lrc":{"lyric":${quote(lrc)}}}""")
        assertEquals("五月雨は緑色\n悲しくさせたよ", words(lookUp()))
        route(search = netSong(extra = ""","album":5"""), lyric = """{"lrc":{"lyric":${quote(lrc)}}}""")
        assertEquals("五月雨は緑色\n悲しくさせたよ", words(lookUp()))
    }

    @Test
    fun aNetEaseLyricThatIsntAnObjectOrHasNoWordsIsNone() {
        route(search = netSong(), lyric = "[]")
        assertEquals(Lyrics.None, lookUp())
        route(search = netSong(), lyric = "{}")
        assertEquals(Lyrics.None, lookUp())
        route(search = netSong(), lyric = """{"lrc":5,"yrc":5}""")
        assertEquals(Lyrics.None, lookUp())
        route(search = netSong(), lyric = """{"lrc":{},"yrc":{}}""")
        assertEquals(Lyrics.None, lookUp())
        route(search = netSong(), lyric = """{"lrc":{"lyric":""},"yrc":{"lyric":"not timed"}}""")
        assertEquals(Lyrics.None, lookUp())
    }

    @Test
    fun aNetEaseSongsLengthIsKeptWhenItSaysAndNotWhenItDoesnt() {
        route(search = netSong(extra = ""","duration":201000"""), lyric = """{"lrc":{"lyric":${quote(lrc)}}}""")
        assertEquals(201_000L, (lookUp() as Lyrics.Found).lengthMs)
        route(search = netSong(extra = ""","duration":0"""), lyric = """{"lrc":{"lyric":${quote(lrc)}}}""")
        assertEquals(null, (lookUp() as Lyrics.Found).lengthMs)
    }

    @Test
    fun theStoresOwnNamesNeedBeAnswerWithBothNamesAndOtherThanTheGiven() {
        val given = MusicMetadata("Hatsukoi", "Hiroko Mita")
        val found = """{"results":[{"trackId":42,"trackName":"Hatsukoi","artistName":"Hiroko Mita"}]}"""
        for (own in listOf(
            "[]", "{}", """{"results":[]}""", """{"results":[5]}""", """{"results":[{}]}""",
            """{"results":[{"trackName":"初恋"}]}""", """{"results":[{"artistName":"三田寛子"}]}""", """{"results":[{"trackName":" ","artistName":" "}]}""",
            """{"results":[{"trackName":"Hatsukoi","artistName":"Hiroko Mita"}]}"""
        )) {
            route(search = """{"result":{}}""", lookup = own)
            fake.requestedUrls.clear()
            val before = fake.handler
            fake.handler = { request -> if (request.url.encodedPath == "/search") FakeSpotify.html(request, found) else before(request) }
            assertEquals(Lyrics.None, lookUp(given))
            // Not their names, so nothing is asked of NetEase under them.
            assertTrue(fake.requestedUrls.none { it.startsWith("https://music.163.com/") && "%E5%88%9D%E6%81%8B" in it })
        }
    }

    @Test
    fun aStoreNamingTheSongAtHomeOtherwiseIsNotTheSameSong() {
        val given = MusicMetadata("初恋", "三田寛子")
        for (home in listOf("""{"results":[]}""", """{"results":[{"trackName":"別の曲","artistName":"三田寛子"}]}""", """{"results":[{"trackName":"初恋","artistName":"誰か"}]}""")) {
            route(lookup = home, search = netSong())
            assertEquals(Lyrics.None, lookUp(given))
        }
    }

    @Test
    fun youTubeMusicPageMissingAnyPartOfTheWayHasNoWords() {
        val finder = LyricsFinder(fake.client(), "test", busyPauseMs = 0, youTubeMusicSong = { "vid1" })
        val page = """{"tabs":[{"browseEndpoint":{"browseId":"MPLYt_words"}}]}"""
        // The song has no lyrics page.
        route(next = "{}")
        assertEquals(Lyrics.None, lookUp(finder = finder))
        for (browse in listOf(
            "{}", """{"contents":{}}""", """{"contents":{"elementRenderer":{}}}""", """{"contents":{"elementRenderer":{"newElement":{}}}}""",
            """{"contents":{"elementRenderer":{"newElement":{"type":{}}}}}""", """{"contents":{"elementRenderer":{"newElement":{"type":{"componentType":{}}}}}}""",
            """{"contents":{"elementRenderer":{"newElement":{"type":{"componentType":{"model":{}}}}}}}""",
            """{"contents":{"elementRenderer":{"newElement":{"type":{"componentType":{"model":{"timedLyricsModel":{}}}}}}}}""",
            """{"contents":{"elementRenderer":{"newElement":{"type":{"componentType":{"model":{"timedLyricsModel":{"lyricsData":{}}}}}}}}}""",
            """{"contents":{"elementRenderer":{"newElement":{"type":{"componentType":{"model":{"timedLyricsModel":{"lyricsData":{"timedLyricsData":[]}}}}}}}}}""",
            """{"contents":{"elementRenderer":{"newElement":{"type":{"componentType":{"model":{"timedLyricsModel":{"lyricsData":{"timedLyricsData":[{"lyricLine":"♪"},{"lyricLine":" "},3]}}}}}}}}}"""
        )) {
            route(next = page, browse = browse)
            assertEquals(Lyrics.None, lookUp(finder = finder))
        }
    }

    @Test
    fun youTubeMusicLinesWithoutAllTheirTimesAreTheWordsUntimedAndOnesTimedAreInOrder() {
        val finder = LyricsFinder(fake.client(), "test", busyPauseMs = 0, youTubeMusicSong = { "vid1" })
        val next = """{"tabs":[{"browseEndpoint":{"browseId":"MPLYt_words"}}]}"""
        fun page(lines: String) = """{"contents":{"elementRenderer":{"newElement":{"type":{"componentType":{"model":{"timedLyricsModel":{"lyricsData":{"timedLyricsData":[$lines]}}}}}}}}}"""
        route(next = next, browse = page("""{"lyricLine":"One","cueRange":{"startTimeMilliseconds":"1000"}},{"lyricLine":"Two"}"""))
        assertEquals(Lyrics.Found("One\nTwo", source = LyricsFinder.YOUTUBE_MUSIC), lookUp(finder = finder))
        route(next = next, browse = page("""{"lyricLine":"One","cueRange":{"startTimeMilliseconds":"soon"}},{"lyricLine":"Two","cueRange":{}}"""))
        assertEquals(Lyrics.Found("One\nTwo", source = LyricsFinder.YOUTUBE_MUSIC), lookUp(finder = finder))
        route(next = next, browse = page("""{"lyricLine":"Late","cueRange":{"startTimeMilliseconds":"9000"}},{"lyricLine":"♪","cueRange":{"startTimeMilliseconds":"500"}},{"lyricLine":"Early","cueRange":{"startTimeMilliseconds":"1000"}}"""))
        val timed = lookUp(finder = finder) as Lyrics.Found
        assertEquals("Late\nEarly", timed.words)
        assertEquals(listOf("Early", "Late"), timed.lines.map { it.text }.filter { it != "♪" })
    }
}
