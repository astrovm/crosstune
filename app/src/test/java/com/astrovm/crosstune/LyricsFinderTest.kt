package com.astrovm.crosstune

import com.astrovm.crosstune.LyricsFinder.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/** Runs under Robolectric for the real org.json implementation. */
@RunWith(RobolectricTestRunner::class)
class LyricsFinderTest {

    private val fake = FakeSpotify()

    private fun respond(json: String) {
        fake.handler = { request: Request -> FakeSpotify.html(request, json) }
    }

    private fun answer(title: String, artist: String, lyrics: String, synced: String = "") =
        """{"trackName":${quote(title)},"artistName":${quote(artist)},"plainLyrics":${quote(lyrics)},"syncedLyrics":${quote(synced)}}"""

    private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun finder() = LyricsFinder(fake.client(), "2.3.3", busyPauseMs = 0)

    private fun found(song: MusicMetadata) = runBlocking { finder().lyricsOf(song) }

    private fun words(song: MusicMetadata) = (found(song) as? Lyrics.Found)?.words

    @Test
    fun aSongGetsItsOwnWords() {
        respond("""[${answer("Dakare Ni Kita Onna", "Kingo Hamada", "夜が灯りを\n投げるBedで")}]""")
        assertEquals(Lyrics.Found("夜が灯りを\n投げるBedで"), found(MusicMetadata("Dakare Ni Kita Onna", "Kingo Hamada")))
        val asked = fake.requestedUrls.single()
        assertEquals("https://lrclib.net/api/search?track_name=Dakare%20Ni%20Kita%20Onna&artist_name=Kingo%20Hamada", asked)
        // LRCLIB turns away a request that doesn't name itself, so it must say who and which version.
        val agent = fake.requestHeaders.single { it.first == "User-Agent" }.second
        assertEquals("Crosstune/2.3.3 (https://github.com/astrovm/crosstune)", agent)
    }

    @Test
    fun theWordsOfAnotherSongAreNeverTaken() {
        // Someone else's song of the same name is no use at all.
        val namesakes = listOf(
            answer("Downer", "Whitearmor", "Not this one"),
            answer("Downer", "The Cavemen", "Not this either")
        ).joinToString(",")
        respond("""[$namesakes]""")
        assertEquals(Lyrics.None, found(MusicMetadata("Downer", "Someone Else Entirely")))

        // Nor is a different artist's song whose name is close to this one's.
        respond("""[${answer("Love Me Tender", "Elvis Presley", "Wrong song")}]""")
        assertEquals(Lyrics.None, found(MusicMetadata("Love Me Do", "The Beatles")))

        // The right artist but the wrong song among several is still left out.
        val sameName = listOf(
            answer("Perfect Day", "The Cure", "Wrong"),
            answer("Perfect Day", "Brian Eno", "Wrong too")
        ).joinToString(",")
        respond("""[$sameName]""")
        assertEquals(Lyrics.None, found(MusicMetadata("Perfect World", "Van Halen")))
    }

    @Test
    fun timedWordsComeWithWhenEachLineIsSung() {
        // Among the song's own answers, one with timed words wins, so they can follow the song.
        val plainOnly = answer("Song", "Band", "Plain words")
        val timed = answer("Song", "Band", "First\nSecond", synced = "[00:01.00] First\n[00:02.50] \n[00:03.00] Second")
        respond("""[$plainOnly,$timed]""")
        assertEquals(
            Lyrics.Found("First\nSecond", listOf(LyricLine(1_000, "First"), LyricLine(3_000, "Second"))),
            found(MusicMetadata("Song", "Band"))
        )

        // Timed words alone are the words too, once their timings are taken off.
        respond("""[{"trackName":"Song","artistName":"Band","syncedLyrics":"[00:01.00] Only timed"}]""")
        assertEquals(Lyrics.Found("Only timed", listOf(LyricLine(1_000, "Only timed"))), found(MusicMetadata("Song", "Band")))
    }

    @Test
    fun anInstrumentalHasNoWordsRatherThanTheWordNull() {
        // LRCLIB sends an instrumental's words as null, not as an empty text.
        respond("""[{"trackName":"Flight","artistName":"Band","plainLyrics":null,"syncedLyrics":null}]""")
        assertEquals(Lyrics.None, found(MusicMetadata("Flight", "Band")))
    }

    @Test
    fun anotherSongWrittenInAnotherScriptIsNotTheSameSong() {
        // Neither title has a Latin word, which says nothing about whether they are the same song.
        respond("""[${answer("冬の歌", "Artist", "Wrong song")}]""")
        assertEquals(Lyrics.None, found(MusicMetadata("夏の歌", "Artist")))
        respond("""[${answer("夏の歌", "Artist", "夏")}]""")
        assertEquals("夏", words(MusicMetadata("夏の歌", "Artist")))
    }

    @Test
    fun aNameWrittenAnotherWayStillCountsAsTheSameSong() {
        // A title in another script, written out in letters, and an artist credited with a tag.
        respond("""[${answer("FUTARI NO NATSU MONOGATARI NEVER ENDING SUMMER", "Omega Tribe", "夏物語")}]""")
        assertEquals("夏物語", words(MusicMetadata("ふたりの夏物語 NEVER ENDING SUMMER", "S. Kiyotaka & Omega Tribe")))

        // "Randy" is inside "Randy Nota Loca", the same artist as the app already accepts.
        respond("""[${answer("Soy Una Gargola", "Randy Nota Loca", "Vengoñando")}]""")
        assertEquals("Vengoñando", words(MusicMetadata("Soy una Gargola", "Alex Gargolas, Randy Nota Loca")))

        // The video's title keeps the Japanese the song's own service leaves out.
        respond("""[${answer("Dakare Ni Kita Onna", "Kingo Hamada", "夜が灯りを")}]""")
        assertEquals("夜が灯りを", words(MusicMetadata("抱かれに来た女 - Dakare Ni Kita Onna", "Kingo Hamada")))
    }

    @Test
    fun theServiceOwnAnswerWinsOverACloseOne() {
        val candidates = listOf(
            answer("Love Me Do - 2003 Remaster", "The Beatles", "Close one"),
            answer("Love Me Do", "The Beatles", "The right one")
        ).joinToString(",")
        respond("""[$candidates]""")
        assertEquals("The right one", words(MusicMetadata("Love Me Do", "The Beatles")))
    }

    @Test
    fun nothingToShowAndNoAnswerBothSayThereAreNone() {
        // Only a song has words, and without an artist there's nothing to check an answer against.
        assertEquals(Lyrics.None, found(MusicMetadata("Album", "Artist", ItemType.ALBUM)))
        assertEquals(Lyrics.None, found(MusicMetadata("Song", "")))
        assertEquals(Lyrics.None, found(MusicMetadata("", "Artist")))

        // An answer with no words in it, or none at all.
        respond("""[${answer("Song", "Band", "")}]""")
        assertEquals(Lyrics.None, found(MusicMetadata("Song", "Band")))
        respond("""[]""")
        assertEquals(Lyrics.None, found(MusicMetadata("Song", "Band")))
        // An empty list is a real answer of "none"; an object naming an error is no answer at all.
        respond("""{"message":"nothing here"}""")
        assertEquals(Lyrics.Unavailable, found(MusicMetadata("Song", "Band")))

        // Entries that name no song are skipped rather than taken for one.
        val nameless = """{"artistName":"Band","plainLyrics":"Words"},"""
        respond("""[$nameless${answer("Song", "Band", "Words")}]""")
        assertEquals("Words", words(MusicMetadata("Song", "Band")))
        respond("""[null,"nope",7]""")
        assertEquals(Lyrics.None, found(MusicMetadata("Song", "Band")))

        // Timed words with nothing in them are no words either.
        respond("""[{"trackName":"Song","artistName":"Band","syncedLyrics":"[00:01.00] "}]""")
        assertEquals(Lyrics.None, found(MusicMetadata("Song", "Band")))
    }

    @Test
    fun aBusyOrMissingServiceSaysSoRatherThanClaimingThereAreNoWords() {
        // LRCLIB is often busy, and a lookup that failed is not a song without words.
        fake.handler = { throw IOException("offline") }
        assertEquals(Lyrics.Unavailable, found(MusicMetadata("Song", "Band")))
        // Asked again a few times, since LRCLIB turns away many lookups in a row when it's busy.
        assertEquals(5, fake.requestedUrls.count { it.startsWith("https://lrclib.net/") })

        // Busy for a few tries, then answering: the words still come.
        var busy = 3
        fake.handler = { request: Request ->
            if (busy-- > 0) FakeSpotify.html(request, """{"message":"The server is busy","statusCode":503}""")
            else FakeSpotify.html(request, """[${answer("Song", "Band", "Words")}]""")
        }
        assertEquals("Words", words(MusicMetadata("Song", "Band")))

        respond("""[${answer("Song", "Band", "Words")}]""")
        fake.delayMillis = 300
        assertEquals(Lyrics.Unavailable, runBlocking { finder().lyricsOf(MusicMetadata("Song", "Band"), timeoutMs = 20L) })
        fake.delayMillis = 0

        // Its own error page isn't an answer either.
        respond("""{"message":"The server is busy","statusCode":503}""")
        assertEquals(Lyrics.Unavailable, found(MusicMetadata("Song", "Band")))

        respond("not json at all")
        assertEquals(Lyrics.Unavailable, found(MusicMetadata("Song", "Band")))
    }

    @Test
    fun wordsFoundAreKeptSoASongShownAgainNeedsNoLookup() {
        val file = File.createTempFile("lyrics", ".json").apply { delete(); deleteOnExit() }
        val kept = LyricsFinder(fake.client(), "2.3.3", busyPauseMs = 0, cache = LookupCache(file, Dispatchers.Unconfined))
        val timed = "[00:01.00] First\n[00:03.00] Second"
        respond("""[${answer("Song", "Band", "First\nSecond", timed)}]""")
        val first = runBlocking { kept.lyricsOf(MusicMetadata("Song", "Band")) }
        // Asked again, in another case, it comes from what was kept, timings and all.
        fake.handler = { throw IOException("offline") }
        assertEquals(first, runBlocking { kept.lyricsOf(MusicMetadata("song", "BAND")) })
        assertEquals(1, fake.requestedUrls.size)

        // A song without words is kept as such; one that couldn't be looked up isn't kept at all.
        respond("[]")
        assertEquals(Lyrics.None, runBlocking { kept.lyricsOf(MusicMetadata("Quiet", "Band")) })
        fake.handler = { throw IOException("offline") }
        assertEquals(Lyrics.None, runBlocking { kept.lyricsOf(MusicMetadata("Quiet", "Band")) })
        assertEquals(Lyrics.Unavailable, runBlocking { kept.lyricsOf(MusicMetadata("Other", "Band")) })
        respond("""[${answer("Other", "Band", "Words")}]""")
        assertEquals("Words", (runBlocking { kept.lyricsOf(MusicMetadata("Other", "Band")) } as Lyrics.Found).words)
    }

    @Test
    fun anEditionTagNamesNoOtherRecordingSoItsWordsAreTaken() {
        // The tag says which edition the words came from, not that they belong to another song.
        // One word on its own says too little to stand for a longer title, so a lone word is only
        // the whole title when the two titles are one once the tag is taken off: which is what
        // "Yesterday (2012 Remaster)" and "Yesterday" are.
        respond("""[${answer("Yesterday (2012 Remaster)", "The Beatles", "Yesterday's words")}]""")
        assertEquals("Yesterday's words", words(MusicMetadata("Yesterday", "The Beatles")))
        respond("""[${answer("Yesterday", "The Beatles", "Yesterday's words")}]""")
        assertEquals("Yesterday's words", words(MusicMetadata("Yesterday (2012 Remaster)", "The Beatles")))
    }

    @Test
    fun aSecondTrySucceedsWhereTheFirstWasBusy() {
        var calls = 0
        fake.handler = { request ->
            calls++
            if (calls == 1) throw IOException("offline")
            FakeSpotify.html(request, """[${answer("Song", "Band", "Words")}]""")
        }
        assertEquals(Lyrics.Found("Words"), found(MusicMetadata("Song", "Band")))
        assertEquals(2, calls)
    }

    /**
     * A Japanese song a music app names in Latin letters: LRCLIB and NetEase answer as given for each,
     * and iTunes knows it as 初恋 by 三田 寛子 when [ownNames].
     */
    private fun japaneseSong(lrclib: (String) -> String = { "[]" }, ownNames: Boolean = true, netEase: (String) -> String = { """{"result":{}}""" }, lyric: String = "") {
        fake.handler = { request ->
            val url = request.url
            when {
                url.host == "lrclib.net" -> FakeSpotify.html(request, lrclib(url.queryParameter("track_name").orEmpty()))
                url.encodedPath == "/search" -> FakeSpotify.html(
                    request,
                    if (ownNames) """{"results":[{"trackId":7,"trackName":"Another Song","artistName":"Someone"},{"trackId":42,"trackName":"Hatsukoi","artistName":"三田 寛子"}]}""" else """{"results":[]}"""
                )
                url.encodedPath == "/lookup" -> FakeSpotify.html(request, """{"results":[{"trackName":"初恋","artistName":"三田 寛子"}]}""")
                url.encodedPath == "/api/search/get" -> FakeSpotify.html(request, netEase(url.queryParameter("s").orEmpty()))
                else -> FakeSpotify.html(request, """{"lrc":{"lyric":${quote(lyric)}}}""")
            }
        }
    }

    private val hatsukoi = MusicMetadata("Hatsukoi", "Hiroko Mita")

    @Test
    fun aSongLrclibHasUnderItsOwnNamesIsFoundUnderThem() {
        japaneseSong(lrclib = { title -> if (title == "初恋") """[${answer("初恋", "三田 寛子", "五月雨は緑色")}]""" else "[]" })
        assertEquals(Lyrics.Found("五月雨は緑色"), found(hatsukoi))
        // The store that names it as music apps do, then the one in its own country, for that same recording.
        assertEquals(true, fake.requestedUrls.any { it.startsWith("https://itunes.apple.com/search?") && "country=us" in it })
        assertEquals(true, fake.requestedUrls.any { it == "https://itunes.apple.com/lookup?id=42&country=jp" })
    }

    @Test
    fun aSongLrclibHasntComesFromNetEaseWithoutItsCredits() {
        val lrc = "[00:00.000] 作词 : 村下孝蔵\n[00:01.000] 作曲 : 村下孝蔵\n[00:16.000]五月雨は緑色\n[00:23.080]悲しくさせたよ"
        japaneseSong(
            netEase = { query -> if (query == "初恋 三田 寛子") """{"result":{"songs":[{"id":1,"name":"初恋","artists":[{"name":"村下孝蔵"}]},{"id":2,"name":"初恋","artists":[{"name":"三田寛子"}]}]}}""" else """{"result":{}}""" },
            lyric = lrc
        )
        assertEquals(
            Lyrics.Found("五月雨は緑色\n悲しくさせたよ", listOf(LyricLine(16_000, "五月雨は緑色"), LyricLine(23_080, "悲しくさせたよ"))),
            found(hatsukoi)
        )
        // The words of its own singer's recording, not the namesake's.
        assertEquals(true, fake.requestedUrls.any { it.startsWith("https://music.163.com/api/song/lyric?id=2&") })
        assertEquals("https://music.163.com/", fake.requestHeaders.last { it.first == "Referer" }.second)
    }

    @Test
    fun withoutOwnNamesNetEaseIsAskedUnderTheGivenOnes() {
        japaneseSong(
            ownNames = false,
            netEase = { query -> if (query == "Hatsukoi Hiroko Mita") """{"result":{"songs":[{"id":3,"name":"Hatsukoi","artists":[{"name":"Hiroko Mita"}]}]}}""" else """{"result":{}}""" },
            lyric = "Plain words, no timings"
        )
        assertEquals(Lyrics.Found("Plain words, no timings"), found(hatsukoi))
    }

    @Test
    fun anInstrumentalOrAnotherSongOnNetEaseIsNone() {
        val ownSong = """{"result":{"songs":[{"id":2,"name":"初恋","artists":[{"name":"三田寛子"}]}]}}"""
        japaneseSong(netEase = { ownSong }, lyric = "[00:00.000] 纯音乐，请欣赏")
        assertEquals(Lyrics.None, found(hatsukoi))
        japaneseSong(netEase = { ownSong }, lyric = "[00:00.000] 作词 : Someone")
        assertEquals(Lyrics.None, found(hatsukoi))
        japaneseSong(netEase = { """{"result":{"songs":[{"id":9,"name":"Another Song","artists":[{"name":"三田寛子"}]}]}}""" })
        assertEquals(Lyrics.None, found(hatsukoi))
    }

    @Test
    fun noneAnywhereIsBelievedOverAPlaceThatWouldntAnswer() {
        // A store answer that isn't JSON is no names, and the rest still goes on.
        val sunshine = MusicMetadata("Sunshine Kiz", "Piper")
        piperSong(lengthMs = 187_000)
        val piper = fake.handler
        fake.handler = { request -> if (request.url.encodedPath == "/lookup") FakeSpotify.html(request, "{") else piper(request) }
        assertEquals("Sunroofを開けて", words(sunshine))
        // LRCLIB says none, NetEase is down: none.
        japaneseSong(netEase = { "not json" })
        assertEquals(Lyrics.None, found(hatsukoi))
        // Nothing answers at all: worth saying so.
        fake.handler = { request -> FakeSpotify.html(request, "busy", code = 503) }
        assertEquals(Lyrics.Unavailable, found(hatsukoi))
    }


    @Test
    fun noWordsIsAskedAgainAfterAFewDaysAndOnceFromBeforeOtherPlacesWereAsked() {
        val file = File.createTempFile("lyrics", ".json").apply { delete(); deleteOnExit() }
        var clock = 1_000_000L
        val cache = LookupCache(file, Dispatchers.Unconfined)
        val kept = LyricsFinder(fake.client(), "2.3.3", busyPauseMs = 0, cache = cache, now = { clock })
        respond("[]")
        assertEquals(Lyrics.None, runBlocking { kept.lyricsOf(MusicMetadata("Quiet", "Band")) })
        respond("""[${answer("Quiet", "Band", "Words at last")}]""")
        // Still believed a little before the days are up, asked again once they are.
        clock += LyricsFinder.NONE_KEPT_MS - 1
        assertEquals(Lyrics.None, runBlocking { kept.lyricsOf(MusicMetadata("Quiet", "Band")) })
        clock += 1
        assertEquals("Words at last", (runBlocking { kept.lyricsOf(MusicMetadata("Quiet", "Band")) } as Lyrics.Found).words)

        // "None" kept with no time, before other places were asked, is asked again.
        runBlocking { cache.put("${SongNames.normalize("Old")}\u0000${SongNames.normalize("Band")}", "none") }
        respond("""[${answer("Old", "Band", "Found now")}]""")
        assertEquals("Found now", (runBlocking { kept.lyricsOf(MusicMetadata("Old", "Band")) } as Lyrics.Found).words)
    }

    /** Sunshine Kiz, 3:07 long in the US store, which the Japanese one doesn't have; NetEase has it by パイパー, [lengthMs] long. */
    private fun piperSong(lengthMs: Long) {
        fake.handler = { request ->
            val url = request.url
            when {
                url.host == "lrclib.net" -> FakeSpotify.html(request, "[]")
                url.encodedPath == "/search" -> FakeSpotify.html(request, """{"results":[{"trackId":5,"trackName":"Sunshine Kiz","artistName":"Piper","trackTimeMillis":187000}]}""")
                url.encodedPath == "/lookup" -> FakeSpotify.html(request, """{"results":[]}""")
                url.encodedPath == "/api/search/get" -> FakeSpotify.html(request, """{"result":{"songs":[{"id":8,"name":"Sunshine Kiz","artists":[{"name":"パイパー"}],"duration":$lengthMs}]}}""")
                else -> FakeSpotify.html(request, """{"lrc":{"lyric":"[00:01.531]Sunroofを開けて"}}""")
            }
        }
    }

    @Test
    fun anArtistOnlyWrittenInJapaneseOnNetEaseCountsForARecordingAsLong() {
        val sunshine = MusicMetadata("Sunshine Kiz", "Piper")
        piperSong(lengthMs = 187_000 + LyricsFinder.SAME_LENGTH_MS)
        assertEquals("Sunroofを開けて", words(sunshine))
        // A recording of another length is another song of that name.
        piperSong(lengthMs = 187_000 + LyricsFinder.SAME_LENGTH_MS + 1)
        assertEquals(Lyrics.None, found(sunshine))
        piperSong(lengthMs = 187_000 - LyricsFinder.SAME_LENGTH_MS - 1)
        assertEquals(Lyrics.None, found(sunshine))
    }

}