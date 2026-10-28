package com.astrovm.crosstune

import com.astrovm.crosstune.LyricsFinder.Lyrics
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch

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
    fun duplicatedCopiesCannotOutvoteTheCurrentTimings() {
        val copy = lasting(answer("Asphalt Lady", "S.Kiyotaka & Omega Tribe", "", synced = romaji), 309.0)
        remixTimedEarly(copy, copy, copy, storeSeconds = 318.483)
        assertEquals(SyncedLyrics.parse(early), (found(remix) as Lyrics.Found).lines)
    }

    @Test
    fun aSongLrclibSaysIsInstrumentalHasNoWordsUnlessACopyDoes() {
        val instrumental = """{"trackName":"Song","artistName":"Band","instrumental":true,"plainLyrics":null,"syncedLyrics":null}"""
        respond("[$instrumental]")
        assertEquals(Lyrics.Instrumental, found(song))
        // A copy with words is the song sung, so its words are taken.
        respond("[$instrumental,${answer("Song", "Band", "Sung after all")}]")
        assertEquals("Sung after all", words(song))
        // Another song's being instrumental says nothing of this one.
        respond("""[{"trackName":"Other","artistName":"Band","instrumental":true}]""")
        assertEquals(Lyrics.None, found(song))
    }

    @Test
    fun timestampsAfterTheRecordingEndsAreNotTaken() {
        respond("[${lasting(answer("Song", "Band", "Readable words", synced = "[01:10.00]Too late"), 50.0)}]")
        val words = found(song) as Lyrics.Found
        assertEquals("Readable words", words.words)
        assertTrue(words.lines.isEmpty())
    }

    @Test
    fun eachRecordingHasItsOwnLyricsCache() {
        val file = File.createTempFile("lyrics-recording", ".json").apply { delete(); deleteOnExit() }
        val finder = LyricsFinder(fake.client(), "test", busyPauseMs = 0, cache = LookupCache(file, Dispatchers.Unconfined))
        val one = song.copy(recordingId = "app:one", lengthMs = 200_000)
        respond("[${answer("Song", "Band", "First recording")}]")
        assertEquals("First recording", (runBlocking { finder.lyricsOf(one) } as Lyrics.Found).words)
        respond("[${answer("Song", "Band", "Second recording")}]")
        assertEquals("Second recording", (runBlocking { finder.lyricsOf(one.copy(recordingId = "app:two")) } as Lyrics.Found).words)
        assertEquals("First recording", (runBlocking { finder.lyricsOf(one) } as Lyrics.Found).words)
        assertEquals("Second recording", (runBlocking { finder.lyricsOf(one.copy(lengthMs = 250_000)) } as Lyrics.Found).words)
        file.delete()
    }

    @Test
    fun aSongGetsItsOwnWords() {
        respond("""[${answer("Dakare Ni Kita Onna", "Kingo Hamada", "夜が灯りを\n投げるBedで")}]""")
        assertEquals(Lyrics.Found("夜が灯りを\n投げるBedで", source = LyricsFinder.LRCLIB), found(MusicMetadata("Dakare Ni Kita Onna", "Kingo Hamada")))
        // LRCLIB is asked by title and artist; NetEase, asked at the same time, may come first.
        val asked = fake.requestedUrls.first { it.startsWith("https://lrclib.net/") }
        assertEquals("https://lrclib.net/api/search?track_name=Dakare%20Ni%20Kita%20Onna&artist_name=Kingo%20Hamada", asked)
        // LRCLIB turns away a request that doesn't name itself, so it must say who and which version.
        val agent = fake.requestHeaders.first { it.first == "User-Agent" }.second
        assertEquals("Crosstune/2.3.3 (https://github.com/astrovm/crosstune)", agent)
    }

    @Test
    fun ofTimedCopiesOfDifferentLengthsTheOneAsLongAsTheSongIsTaken() {
        fun copy(words: String, seconds: Int) =
            """{"trackName":"Song","artistName":"Band","duration":$seconds,"plainLyrics":"$words","syncedLyrics":"[00:01.00] $words\n[00:05.00] $words again"}"""
        val copies = "[${copy("Live", 312)},${copy("Studio", 245)}]"
        var storeLength = 0
        fake.handler = { request ->
            if (request.url.host == "itunes.apple.com") {
                FakeSpotify.html(request, """{"results":[{"trackId":1,"trackName":"Song","artistName":"Band","trackTimeMillis":$storeLength}]}""")
            } else {
                FakeSpotify.html(request, copies)
            }
        }
        // As long as the app playing it says, give or take.
        assertEquals("Studio", words(MusicMetadata("Song", "Band", lengthMs = 246_500)))
        assertEquals("Live", words(MusicMetadata("Song", "Band", lengthMs = 311_000)))
        // Not said, the iTunes Store is asked how long it is, only because the copies differ.
        storeLength = 245_300
        fake.requestedUrls.clear()
        assertEquals("Studio", words(MusicMetadata("Song", "Band")))
        assertTrue(fake.requestedUrls.any { "itunes.apple.com" in it })
        // None that long, the first timed one is taken, as before.
        assertEquals("Live", words(MusicMetadata("Song", "Band", lengthMs = 100_000)))
        // Copies about as long as each other are the same take, so nothing's asked.
        fake.handler = { request -> FakeSpotify.html(request, "[${copy("One", 245)},${copy("Two", 247)}]") }
        fake.requestedUrls.clear()
        assertEquals("One", words(MusicMetadata("Song", "Band")))
        assertTrue(fake.requestedUrls.none { "itunes.apple.com" in it })
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
            Lyrics.Found("First\nSecond", listOf(LyricLine(1_000, "First"), LyricLine(3_000, "Second")), source = LyricsFinder.LRCLIB),
            found(MusicMetadata("Song", "Band"))
        )

        // Timed words alone are the words too, once their timings are taken off.
        respond("""[{"trackName":"Song","artistName":"Band","syncedLyrics":"[00:01.00] Only timed"}]""")
        assertEquals(Lyrics.Found("Only timed", listOf(LyricLine(1_000, "Only timed")), source = LyricsFinder.LRCLIB), found(MusicMetadata("Song", "Band")))
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
    fun aSongLrclibWasBusyForIsntSaidToHaveNoWords() {
        val song = MusicMetadata("Song", "Band")
        var busy = Int.MAX_VALUE
        // LRCLIB busy, and the other places without the song.
        fake.handler = { request: Request ->
            when {
                request.url.host == "lrclib.net" && busy-- > 0 -> FakeSpotify.html(request, """{"message":"The server is busy","statusCode":503}""")
                request.url.host == "lrclib.net" -> FakeSpotify.html(request, """[${answer("Song", "Band", "Words")}]""")
                request.url.encodedPath == "/api/search/get" -> FakeSpotify.html(request, """{"result":{}}""")
                else -> FakeSpotify.html(request, """{"results":[]}""")
            }
        }
        assertEquals(Lyrics.Unavailable, found(song))
        // Answering once the others have said they have none, its words come.
        busy = 5
        assertEquals("Words", words(song))

        // Saying it has none, it isn't asked again.
        fake.handler = { request: Request ->
            when {
                request.url.host == "lrclib.net" -> FakeSpotify.html(request, "[]")
                request.url.encodedPath == "/api/search/get" -> FakeSpotify.html(request, """{"result":{}}""")
                else -> FakeSpotify.html(request, """{"results":[]}""")
            }
        }
        fake.requestedUrls.clear()
        assertEquals(Lyrics.None, found(MusicMetadata("Other", "Band")))
        assertEquals(1, fake.requestedUrls.count { it.startsWith("https://lrclib.net/") })
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
            if (request.url.host == "lrclib.net" && busy-- > 0) FakeSpotify.html(request, """{"message":"The server is busy","statusCode":503}""")
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
        assertEquals(1, askedBesidesNetEase())

        // Kept on disk too, so they're there after the app starts again.
        val again = LyricsFinder(fake.client(), "2.3.3", busyPauseMs = 0, cache = LookupCache(file, Dispatchers.Unconfined))
        assertEquals(first, runBlocking { again.lyricsOf(MusicMetadata("Song", "Band")) })
        assertEquals(1, askedBesidesNetEase())

        // Neither a song without words nor one that couldn't be looked up is kept, so both are asked again.
        respond("[]")
        assertEquals(Lyrics.None, runBlocking { kept.lyricsOf(MusicMetadata("Quiet", "Band")) })
        fake.handler = { throw IOException("offline") }
        assertEquals(Lyrics.Unavailable, runBlocking { kept.lyricsOf(MusicMetadata("Quiet", "Band")) })
        assertEquals(Lyrics.Unavailable, runBlocking { kept.lyricsOf(MusicMetadata("Other", "Band")) })
        respond("""[${answer("Other", "Band", "Words")}]""")
        assertEquals("Words", (runBlocking { kept.lyricsOf(MusicMetadata("Other", "Band")) } as Lyrics.Found).words)
    }

    @Test
    fun whatsLeftOutOfNetEasesWordsNowIsLeftOutOfOnesKeptBefore() {
        val file = File.createTempFile("lyrics", ".json").apply { delete(); deleteOnExit() }
        val cache = LookupCache(file, Dispatchers.Unconfined)
        val kept = { song: MusicMetadata, source: String ->
            val lines = org.json.JSONArray(listOf(listOf(0, "Song"), listOf(1_000, "Band"), listOf(2_000, "作词 : Someone"), listOf(4_000, "First"), listOf(9_000, ""), listOf(20_000, "Second"), listOf(25_000, "")))
            val words = org.json.JSONObject().put("words", "Song\nBand\n作词 : Someone\nFirst\n\nSecond").put("source", source).put("lines", lines)
            runBlocking { cache.put("${LyricsFinder.CACHE_VERSION}\u0000${RecordingKey.of(song)}", words.toString()) }
        }
        kept(MusicMetadata("Song", "Band"), LyricsFinder.NETEASE)
        kept(MusicMetadata("Song", "Other band"), LyricsFinder.LRCLIB)
        fake.handler = { throw IOException("offline") }
        val finder = LyricsFinder(fake.client(), "2.3.3", busyPauseMs = 0, cache = cache)

        // The heading and the credit go, and only the pause sung around stays.
        val netEase = runBlocking { finder.lyricsOf(MusicMetadata("Song", "Band")) } as Lyrics.Found
        assertEquals("First\n\nSecond", netEase.words)
        assertEquals(listOf(LyricLine(4_000, "First"), LyricLine(9_000, ""), LyricLine(20_000, "Second")), netEase.lines)
        // Words from anywhere else are kept as they are.
        val lrclib = runBlocking { finder.lyricsOf(MusicMetadata("Song", "Other band")) } as Lyrics.Found
        assertEquals("Song\nBand\n作词 : Someone\nFirst\n\nSecond", lrclib.words)
        assertEquals(7, lrclib.lines.size)
        assertEquals(emptyList<String>(), fake.requestedUrls)
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
            // Only LRCLIB's tries count: words with no timings are looked for elsewhere too.
            if (request.url.host == "lrclib.net") calls++
            if (calls == 1) throw IOException("offline")
            FakeSpotify.html(request, """[${answer("Song", "Band", "Words")}]""")
        }
        assertEquals(Lyrics.Found("Words", source = LyricsFinder.LRCLIB), found(MusicMetadata("Song", "Band")))
        // The second try, then once more for a timed copy of the words to time them by.
        assertEquals(3, calls)
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
        assertEquals(Lyrics.Found("五月雨は緑色", source = LyricsFinder.LRCLIB), found(hatsukoi))
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
            Lyrics.Found("五月雨は緑色\n悲しくさせたよ", listOf(LyricLine(16_000, "五月雨は緑色"), LyricLine(23_080, "悲しくさせたよ")), source = LyricsFinder.NETEASE),
            found(hatsukoi)
        )
        // The words of its own singer's recording, not the namesake's.
        assertEquals(true, fake.requestedUrls.any { it.startsWith("https://music.163.com/api/song/lyric?id=2&") })
        assertEquals("https://music.163.com/", fake.requestHeaders.last { it.first == "Referer" }.second)
    }

    @Test
    fun theCreditsAndHeadingNetEaseOpensASongWithAreLeftOut() {
        val netEase = { _: String -> """{"result":{"songs":[{"id":2,"name":"Hatsukoi","artists":[{"name":"Hiroko Mita"}]}]}}""" }
        val sung = listOf("五月雨は緑色", "Me : still here", "悲しくさせたよ")
        fun lrc(lines: List<String>) = lines.mapIndexed { index, line -> "[00:${10 + index}.000]$line" }.joinToString("\n")
        fun words(lines: List<String>) = Lyrics.Found(lines.joinToString("\n"), lines.mapIndexed { index, line -> LyricLine(10_000L + index * 1_000, line) }, source = LyricsFinder.NETEASE)
        // Credits in English, in Chinese for a Japanese song, a rap's own lyricist, then the song's name and its artist's.
        val opening = listOf("Producers: Someone", "Writers：A, B & C", "母帯工程師：Someone Else", "Rap作词 : 怜", "", "Vocal Directing Engineer: Them", "", "Hatsukoi", "", "Hiroko Mita")
        japaneseSong(netEase = netEase, lyric = lrc(opening + sung))
        assertEquals(words(sung).copy(lines = sung.mapIndexed { index, line -> LyricLine(20_000L + index * 1_000, line) }), found(hatsukoi))
        // Untimed, with the blank lines between them.
        japaneseSong(netEase = netEase, lyric = (opening + sung).joinToString("\n"))
        assertEquals(Lyrics.Found(sung.joinToString("\n"), source = LyricsFinder.NETEASE), found(hatsukoi))
        // Or both on one line, with what they're called in Chinese, and more credits after them.
        for (heading in listOf("Hatsukoi （初恋）-Hiroko Mita （三田寛子）", "Hiroko Mita - Hatsukoi")) {
            japaneseSong(netEase = netEase, lyric = lrc(listOf("作词 : 怜", "", heading, "Written by：A/B （C）") + sung))
            assertEquals(words(sung).copy(lines = sung.mapIndexed { index, line -> LyricLine(14_000L + index * 1_000, line) }), found(hatsukoi))
        }
        // A song that opens with its own name sings it.
        val named = listOf("Hatsukoi") + sung
        japaneseSong(netEase = netEase, lyric = lrc(named))
        assertEquals(words(named), found(hatsukoi))
        // Nor is a time, who sings a line, or a sentence before a colon, a credit.
        for (first in listOf("7:30 in the night", "Chorus : 五月雨は緑色", "I see the light: oh")) {
            japaneseSong(netEase = netEase, lyric = lrc(listOf(first) + sung))
            assertEquals(words(listOf(first) + sung), found(hatsukoi))
        }
    }

    @Test
    fun aNetEaseHeadingWithMoreAroundTheNamesIsLeftOut() {
        val sung = listOf("五月雨は緑色", "悲しくさせたよ")
        fun cleaned(title: String, vararg heading: String) =
            LyricsFinder.cleaned(Lyrics.Found((heading.toList() + sung).joinToString("\n"), source = LyricsFinder.NETEASE), MusicMetadata(title, "Hiroko Mita")).words
        val words = sung.joinToString("\n")
        // The name without what the store adds to it.
        assertEquals(words, cleaned("Hatsukoi (Remastered)", "Hatsukoi", "Hiroko Mita"))
        // The site's word for words, and a guest under it.
        assertEquals(words, cleaned("Hatsukoi", "Hiroko Mita - Hatsukoi lyrics", "（feat. Someone）"))
        // Names in Chinese whose brackets never close, with the guest and "Explicit", unspaced.
        assertEquals(words, cleaned("Hatsukoi (feat. Someone)", "Hatsukoi（feat.Someone（初恋（Explicit-HirokoMita（三田寛子"))
        // A sung line with both names in it is sung, and a guest without a heading isn't one.
        assertEquals((listOf("Hiroko Mita sings Hatsukoi") + sung).joinToString("\n"), cleaned("Hatsukoi", "Hiroko Mita sings Hatsukoi"))
        assertEquals((listOf("ft. Someone") + sung).joinToString("\n"), cleaned("Hatsukoi", "ft. Someone"))
    }

    @Test
    fun aLyricsSitesAdIsLeftOut() {
        val ad = "{more Hiroko Mita lyrics at lyricsify.com/u/Someone}"
        // Found now.
        japaneseSong(lrclib = { title -> if (title == "初恋") """[${answer("初恋", "三田 寛子", "五月雨は緑色\n$ad")}]""" else "[]" })
        assertEquals(Lyrics.Found("五月雨は緑色", source = LyricsFinder.LRCLIB), found(hatsukoi))
        // Or kept from before, timed.
        val kept = Lyrics.Found("五月雨は緑色\n$ad", listOf(LyricLine(1_000, "五月雨は緑色"), LyricLine(2_000, ad)), source = LyricsFinder.LRCLIB)
        assertEquals(
            Lyrics.Found("五月雨は緑色", SyncedLyrics.withPauses(listOf(LyricLine(1_000, "五月雨は緑色"))), source = LyricsFinder.LRCLIB),
            LyricsFinder.cleaned(kept, hatsukoi)
        )
        // Words without one are kept as they are.
        val sung = Lyrics.Found("Meet me at the www\nsite.com is down", source = LyricsFinder.LRCLIB)
        assertSame(sung, LyricsFinder.cleaned(sung, hatsukoi))
    }

    @Test
    fun theCreditsNetEaseClosesASongWithAreLeftOut() {
        val netEase = { _: String -> """{"result":{"songs":[{"id":2,"name":"Hatsukoi","artists":[{"name":"Hiroko Mita"}]}]}}""" }
        val credits = listOf("Lead Vocals : Hiroko Mita", "Recorded at Some Studio, and Another One", "", "Vocal, Rhythm, Synthesizer & String Arrangements : A Singer", "Published by A Publisher Limited", "Mastered at A Third Studio")
        // A sung line can look like one, and stays.
        val sung = listOf("五月雨は緑色", "Me : still here", "悲しくさせたよ")
        val lrc = (sung + "" + credits).mapIndexed { index, line -> "[00:${10 + index}.000]$line" }.joinToString("\n")
        japaneseSong(netEase = netEase, lyric = lrc)
        assertEquals(
            Lyrics.Found(sung.joinToString("\n"), sung.mapIndexed { index, line -> LyricLine(10_000L + index * 1_000, line) }, source = LyricsFinder.NETEASE),
            found(hatsukoi)
        )

        japaneseSong(netEase = netEase, lyric = (sung + credits).joinToString("\n"))
        assertEquals(Lyrics.Found(sung.joinToString("\n"), source = LyricsFinder.NETEASE), found(hatsukoi))

        // A duet's last lines, each with who sings it, are words.
        val duet = sung + listOf("Male : Stay with me", "Female : Never leave", "Both 2 : Together")
        japaneseSong(netEase = netEase, lyric = (duet + credits).joinToString("\n"))
        assertEquals(Lyrics.Found(duet.joinToString("\n"), source = LyricsFinder.NETEASE), found(hatsukoi))
        // One alone could be sung, but not who published it.
        japaneseSong(netEase = netEase, lyric = (sung + "Mastering : Someone").joinToString("\n"))
        assertEquals(Lyrics.Found((sung + "Mastering : Someone").joinToString("\n"), source = LyricsFinder.NETEASE), found(hatsukoi))
        for (publisher in listOf("SP：A Label", "OP : Another")) {
            japaneseSong(netEase = netEase, lyric = (sung + publisher).joinToString("\n"))
            assertEquals(Lyrics.Found(sung.joinToString("\n"), source = LyricsFinder.NETEASE), found(hatsukoi))
        }

        val byWord = (sung + credits).filter { it.isNotEmpty() }.mapIndexed { index, line -> "[${index * 1_000},900](${index * 1_000},900,0)$line" }.joinToString("\n")
        fake.handler = { request ->
            when (request.url.encodedPath) {
                "/api/search/get" -> FakeSpotify.html(request, netEase(""))
                "/api/song/lyric" -> FakeSpotify.html(request, """{"lrc":{"lyric":""},"yrc":{"lyric":${quote(byWord)}}}""")
                else -> FakeSpotify.html(request, if (request.url.host == "lrclib.net") "[]" else """{"results":[]}""")
            }
        }
        assertEquals(sung, (found(hatsukoi) as Lyrics.Found).lines.map { it.text })
    }

    @Test
    fun whatNetEaseFillsASongsQuietPartsWithIsLeftOut() {
        val netEase = { _: String -> """{"result":{"songs":[{"id":2,"name":"Hatsukoi","artists":[{"name":"Hiroko Mita"}]}]}}""" }
        // Its name in the intro, the parts' names, the artist's notes over two lines in a solo, and a dash at the end.
        val filler = listOf("♪三田寛子 - 初恋", "[Verse 1]", "五月雨は緑色", "【セルフライナーノーツ：上京後に", "書いた曲。】", "[Chorus: Someone]", "【A】悲しくさせたよ", "【Chorus】", "-")
        val sung = listOf("五月雨は緑色", "【A】悲しくさせたよ")
        japaneseSong(netEase = netEase, lyric = filler.mapIndexed { index, line -> "[00:${10 + index}.000]$line" }.joinToString("\n"))
        assertEquals(sung, (found(hatsukoi) as Lyrics.Found).lines.map { it.text })
        japaneseSong(netEase = netEase, lyric = filler.joinToString("\n"))
        assertEquals(Lyrics.Found(sung.joinToString("\n"), source = LyricsFinder.NETEASE), found(hatsukoi))
        val byWord = filler.mapIndexed { index, line -> "[${index * 1_000},900](${index * 1_000},900,0)$line" }.joinToString("\n")
        fake.handler = { request ->
            when (request.url.encodedPath) {
                "/api/search/get" -> FakeSpotify.html(request, netEase(""))
                "/api/song/lyric" -> FakeSpotify.html(request, """{"lrc":{"lyric":""},"yrc":{"lyric":${quote(byWord)}}}""")
                else -> FakeSpotify.html(request, if (request.url.host == "lrclib.net") "[]" else """{"results":[]}""")
            }
        }
        assertEquals(sung, (found(hatsukoi) as Lyrics.Found).lines.map { it.text })
        // A note never closed may be words after all, so it's left be.
        japaneseSong(netEase = netEase, lyric = listOf("五月雨は緑色", "【open", "悲しくさせたよ").joinToString("\n"))
        assertEquals(Lyrics.Found("五月雨は緑色\n【open\n悲しくさせたよ", source = LyricsFinder.NETEASE), found(hatsukoi))
    }

    @Test
    fun wordsNetEaseTimesWordByWordBeatOnesTimedALineAtATime() {
        val timed = "[00:16.00] 五月雨は緑色\n[00:23.08] 悲しくさせたよ"
        val byWord = "{\"t\":0,\"c\":[{\"tx\":\"作词: \"}]}\n[00:00.000] 作曲 : 村下孝蔵\n[16100,2000](16100,900,0)五月雨は(17000,800,0)緑色\n[23200,2000](23200,2000,0)悲しくさせたよ"
        fake.handler = { request ->
            val url = request.url
            when {
                url.host == "lrclib.net" -> FakeSpotify.html(request, "[${answer("Hatsukoi", "Hiroko Mita", "五月雨は緑色\n悲しくさせたよ", timed)}]")
                url.encodedPath == "/api/search/get" -> FakeSpotify.html(request, """{"result":{"songs":[{"id":2,"name":"Hatsukoi","artists":[{"name":"Hiroko Mita"}]}]}}""")
                url.encodedPath == "/api/song/lyric" -> FakeSpotify.html(request, """{"lrc":{"lyric":${quote(timed)}},"yrc":{"lyric":${quote(byWord)}}}""")
                else -> FakeSpotify.html(request, """{"results":[]}""")
            }
        }
        val found = found(hatsukoi) as Lyrics.Found
        assertEquals("五月雨は緑色\n悲しくさせたよ", found.words)
        assertEquals(
            listOf(
                LyricLine(16_100, "五月雨は緑色", listOf(TimedWord(16_100, "五月雨は"), TimedWord(17_000, "緑色")), endMs = 18_100),
                LyricLine(23_200, "悲しくさせたよ", listOf(TimedWord(23_200, "悲しくさせたよ")), endMs = 25_200)
            ),
            found.lines
        )
        // Kept, they're the same next time, where each line ends too.
        val file = File.createTempFile("lyrics", ".json").apply { delete(); deleteOnExit() }
        val kept = LyricsFinder(fake.client(), "2.3.3", busyPauseMs = 0, cache = LookupCache(file, Dispatchers.Unconfined))
        val first = runBlocking { kept.lyricsOf(hatsukoi) }
        val handler = fake.handler
        fake.handler = { request -> FakeSpotify.html(request, "", code = 500) }
        assertEquals(first, runBlocking { LyricsFinder(fake.client(), "2.3.3", busyPauseMs = 0, cache = LookupCache(file, Dispatchers.Unconfined)).lyricsOf(hatsukoi) })
        fake.handler = handler
        assertEquals(true, fake.requestedUrls.any { it.startsWith("https://music.163.com/api/song/lyric?id=2&") && "yv=1" in it })
        // Without them, the line-timed words from LRCLIB are the ones.
        val noWords = fake.handler
        fake.handler = { request -> if (request.url.encodedPath == "/api/song/lyric") FakeSpotify.html(request, """{"lrc":{"lyric":${quote(timed)}}}""") else noWords(request) }
        assertEquals(listOf(LyricLine(16_000, "五月雨は緑色"), LyricLine(23_080, "悲しくさせたよ")), (found(hatsukoi) as Lyrics.Found).lines)
    }

    @Test
    fun wordsTimedWordByWordAreKeptSo() {
        val file = File.createTempFile("lyrics", ".json").apply { delete(); deleteOnExit() }
        val byWord = "[1000,900](1000,400,0)Hello (1400,500,0)world"
        fake.handler = { request ->
            when (request.url.encodedPath) {
                "/api/search/get" -> FakeSpotify.html(request, """{"result":{"songs":[{"id":2,"name":"Song","artists":[{"name":"Band"}]}]}}""")
                "/api/song/lyric" -> FakeSpotify.html(request, """{"yrc":{"lyric":${quote(byWord)}}}""")
                else -> FakeSpotify.html(request, "[]")
            }
        }
        val first = runBlocking { LyricsFinder(fake.client(), "2.3.3", busyPauseMs = 0, cache = LookupCache(file, Dispatchers.Unconfined)).lyricsOf(MusicMetadata("Song", "Band")) }
        assertEquals(listOf(TimedWord(1_000, "Hello "), TimedWord(1_400, "world")), (first as Lyrics.Found).lines.single().words)
        fake.handler = { throw IOException("offline") }
        assertEquals(first, runBlocking { LyricsFinder(fake.client(), "2.3.3", busyPauseMs = 0, cache = LookupCache(file, Dispatchers.Unconfined)).lyricsOf(MusicMetadata("Song", "Band")) })
    }

    @Test
    fun withOwnNamesNetEaseIsStillAskedUnderTheGivenOnes() {
        japaneseSong(
            netEase = { query -> if (query == "Hatsukoi Hiroko Mita") """{"result":{"songs":[{"id":3,"name":"Hatsukoi","artists":[{"name":"Hiroko Mita"}]}]}}""" else """{"result":{"songs":[]}}""" },
            lyric = "Plain words, no timings"
        )
        assertEquals(Lyrics.Found("Plain words, no timings", source = LyricsFinder.NETEASE), found(hatsukoi))
    }

    @Test
    fun ownNamesThatAreTheGivenOnesArentAskedAgain() {
        fake.handler = { request ->
            val url = request.url
            when {
                url.host == "lrclib.net" -> FakeSpotify.html(request, "[]")
                url.encodedPath == "/search" -> FakeSpotify.html(request, """{"results":[{"trackId":42,"trackName":"Hatsukoi","artistName":"Hiroko Mita"}]}""")
                url.encodedPath == "/lookup" -> FakeSpotify.html(request, """{"results":[{"trackName":"Hatsukoi","artistName":"Hiroko Mita"}]}""")
                else -> FakeSpotify.html(request, """{"result":{"songs":[]}}""")
            }
        }
        assertEquals(Lyrics.None, found(hatsukoi))
        assertEquals(1, fake.requestedUrls.count { it.startsWith("https://lrclib.net/") })
        assertEquals(1, fake.requestedUrls.count { "/api/search/get" in it })
    }

    @Test
    fun ownNamesWithoutATitleArentUsed() {
        fake.handler = { request ->
            val url = request.url
            when {
                url.host == "lrclib.net" -> FakeSpotify.html(request, "[]")
                url.encodedPath == "/search" -> FakeSpotify.html(request, """{"results":[{"trackId":42,"trackName":"Hatsukoi","artistName":"Hiroko Mita"}]}""")
                url.encodedPath == "/lookup" -> FakeSpotify.html(request, """{"results":[{"trackName":"","artistName":"三田 寛子"}]}""")
                else -> FakeSpotify.html(request, """{"result":{"songs":[]}}""")
            }
        }
        assertEquals(Lyrics.None, found(hatsukoi))
        assertEquals(1, fake.requestedUrls.count { it.startsWith("https://lrclib.net/") })
    }

    @Test
    fun onlyAToZAreLatinLetters() {
        assertEquals(listOf(true, true, true, true), listOf("a", "z", "A", "Z").map { it.hasLatin() })
        assertEquals(listOf(false, false, false, false, false), listOf("`", "{", "@", "[", "아일릿").map { it.hasLatin() })
    }

    @Test
    fun aSongGivenInItsOwnScriptIsFoundUnderItsNamesInLatinLetters() {
        // Korea's store credits Magnetic to 아일릿, LRCLIB to ILLIT, as the US store does.
        fun illit(us: String, lookup: String = """{"results":[]}""") {
            fake.handler = { request ->
                val url = request.url
                when {
                    url.host == "lrclib.net" -> FakeSpotify.html(
                        request,
                        if (url.queryParameter("artist_name") == "ILLIT") """[${answer("Magnetic", "ILLIT", "Baby, I'm just trying to play it cool")}]""" else "[]"
                    )
                    url.encodedPath == "/search" -> FakeSpotify.html(request, """{"results":[$us]}""")
                    url.encodedPath == "/lookup" -> FakeSpotify.html(request, lookup)
                    else -> FakeSpotify.html(request, """{"result":{"songs":[]}}""")
                }
            }
        }
        val words = Lyrics.Found("Baby, I'm just trying to play it cool", source = LyricsFinder.LRCLIB)
        val magnetic = MusicMetadata("Magnetic", "아일릿", lengthMs = 160_688)
        val otherAndIllit = """{"trackId":7,"trackName":"Magnetic","artistName":"Someone Else","trackTimeMillis":200000},""" +
            """{"trackId":42,"trackName":"Magnetic","artistName":"ILLIT","trackTimeMillis":163688}"""
        illit(otherAndIllit)
        assertEquals(words, found(magnetic))
        // The store's song by another name is only theirs when it's as long, give or take 3 seconds.
        assertEquals(Lyrics.None, found(magnetic.copy(lengthMs = 160_687)))
        assertEquals(Lyrics.None, found(magnetic.copy(lengthMs = null)))
        // And an artist given in Latin letters is that one.
        assertEquals(Lyrics.None, found(MusicMetadata("Magnetic", "Some Band", lengthMs = 160_688)))
        // The store naming it as given, its own country's names are asked for, as for a song given in Latin letters.
        illit("""{"trackId":42,"trackName":"Magnetic","artistName":"아일릿","trackTimeMillis":160688}""", lookup = """{"results":[{"trackName":"Magnetic","artistName":"ILLIT"}]}""")
        assertEquals(words, found(magnetic))
    }

    @Test
    fun aSongTheUsStoreTranslatesIsFoundWhenItsHomeStoreNamesItAsGiven() {
        // Korea's store has 체포하겠어 by 아일릿; the US store and LRCLIB, You're Under Arrest by ILLIT.
        fun illit(home: String) {
            fake.handler = { request ->
                val url = request.url
                when {
                    url.host == "lrclib.net" -> FakeSpotify.html(
                        request,
                        if (url.queryParameter("track_name") == "You're Under Arrest") """[${answer("You're Under Arrest", "ILLIT", "Hands up")}]""" else "[]"
                    )
                    url.encodedPath == "/search" -> FakeSpotify.html(
                        request,
                        """{"results":[{"trackId":7,"trackName":"Rush","artistName":"ILLIT","trackTimeMillis":150000},{"trackId":42,"trackName":"You're Under Arrest","artistName":"ILLIT","trackTimeMillis":181912}]}"""
                    )
                    url.encodedPath == "/lookup" && url.queryParameter("country") == "KR" && url.queryParameter("id") == "42" -> FakeSpotify.html(request, """{"results":[$home]}""")
                    url.encodedPath == "/lookup" -> FakeSpotify.html(request, """{"results":[]}""")
                    else -> FakeSpotify.html(request, """{"result":{"songs":[]}}""")
                }
            }
        }
        val words = Lyrics.Found("Hands up", source = LyricsFinder.LRCLIB)
        val arrest = MusicMetadata("체포하겠어", "아일릿", lengthMs = 181_000)
        illit("""{"trackName":"체포하겠어","artistName":"아일릿"}""")
        assertEquals(words, found(arrest))
        // Not knowing how long it is, the first the store found is the one checked.
        assertEquals(Lyrics.None, found(arrest.copy(lengthMs = null)))
        // Under other names at home, it's another song.
        illit("""{"trackName":"Magnetic","artistName":"아일릿"}""")
        assertEquals(Lyrics.None, found(arrest))
        illit("""{"trackName":"체포하겠어","artistName":"키키"}""")
        assertEquals(Lyrics.None, found(arrest))
        // A song given in Latin letters has no home store to ask.
        fake.requestedUrls.clear()
        assertEquals(Lyrics.None, found(MusicMetadata("Arrest", "Some Band", lengthMs = 181_000)))
        assertTrue(fake.requestedUrls.none { "/lookup" in it })
    }

    @Test
    fun namesAreCheckedInTheStoreOfTheCountryThatWritesThemSo() {
        assertEquals(listOf("KR", "KR", "JP", "JP", "TW", null), listOf("아일릿", "사랑 愛", "夜に駆ける", "ヨアソビ", "光年之外", "Magnetic").map(SongNames::homeStore))
    }

    @Test
    fun netEaseHavingNoSuchSongWithLrclibDownIsntNone() {
        fake.handler = { request ->
            val url = request.url
            when {
                url.host == "lrclib.net" -> throw IOException("down")
                url.host == "itunes.apple.com" -> FakeSpotify.html(request, """{"results":[]}""")
                else -> FakeSpotify.html(request, """{"result":{"songs":[{"id":9,"name":"Another Song","artists":[{"name":"Band"}]}]}}""")
            }
        }
        // LRCLIB has most songs' words, so it isn't known the song has none.
        assertEquals(Lyrics.Unavailable, found(MusicMetadata("Song", "Band")))
    }

    @Test
    fun withoutOwnNamesNetEaseIsAskedUnderTheGivenOnes() {
        japaneseSong(
            ownNames = false,
            netEase = { query -> if (query == "Hatsukoi Hiroko Mita") """{"result":{"songs":[{"id":3,"name":"Hatsukoi","artists":[{"name":"Hiroko Mita"}]}]}}""" else """{"result":{}}""" },
            lyric = "Plain words, no timings"
        )
        assertEquals(Lyrics.Found("Plain words, no timings", source = LyricsFinder.NETEASE), found(hatsukoi))
    }

    @Test
    fun anInstrumentalOnNetEaseSaysSoAndAnotherSongIsNone() {
        val ownSong = """{"result":{"songs":[{"id":2,"name":"初恋","artists":[{"name":"三田寛子"}]}]}}"""
        japaneseSong(netEase = { ownSong }, lyric = "[00:00.000] 纯音乐，请欣赏")
        assertEquals(Lyrics.Instrumental, found(hatsukoi))
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
        // Nor is one it won't give at all.
        fake.handler = { request -> if (request.url.encodedPath == "/lookup") FakeSpotify.html(request, "busy", code = 503) else piper(request) }
        assertEquals("Sunroofを開けて", words(sunshine))
        // LRCLIB says none, NetEase is down: none.
        japaneseSong(netEase = { "not json" })
        assertEquals(Lyrics.None, found(hatsukoi))
        // Nothing answers at all: worth saying so.
        fake.handler = { request -> FakeSpotify.html(request, "busy", code = 503) }
        assertEquals(Lyrics.Unavailable, found(hatsukoi))
    }


    @Test
    fun noneKeptByAnEarlierVersionIsAskedAgain() {
        val file = File.createTempFile("lyrics", ".json").apply { delete(); deleteOnExit() }
        val cache = LookupCache(file, Dispatchers.Unconfined)
        runBlocking { cache.put("${SongNames.normalize("Old")}\u0000${SongNames.normalize("Band")}", "none") }
        val kept = LyricsFinder(fake.client(), "2.3.3", busyPauseMs = 0, cache = cache)
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
    fun underItsOwnNamesNetEaseMayStillCreditTheArtistAsTheMusicAppDoes() {
        // 初恋 by "Hiroko Mita" there, not by 三田 寛子 as the Japanese store has it.
        japaneseSong(
            netEase = { query -> if (query == "初恋 三田 寛子") """{"result":{"songs":[{"id":5,"name":"初恋","artists":[{"name":"Hiroko Mita"}]}]}}""" else """{"result":{"songs":[]}}""" },
            lyric = "[00:01.00]五月雨は緑色"
        )
        assertEquals("五月雨は緑色", words(hatsukoi))
    }

    @Test
    fun aTitleInChineseWithAFilmNoteInBracketsIsTheSameSong() {
        respond("""[${answer("小幸运 (电影《我的少女时代》主题曲)", "田馥甄", "我听见雨滴落在青青草地")}]""")
        assertEquals("我听见雨滴落在青青草地", words(MusicMetadata("小幸运", "田馥甄")))
        // Another song of the artist's isn't it, nor a title that only starts alike.
        respond("""[${answer("小幸运吗 (电影主题曲)", "田馥甄", "别的歌")}]""")
        assertEquals(Lyrics.None, found(MusicMetadata("小幸运", "田馥甄")))
    }

    @Test
    fun anArtistOnlyWrittenInJapaneseOnNetEaseCountsForARecordingAsLong() {
        val sunshine = MusicMetadata("Sunshine Kiz", "Piper")
        piperSong(lengthMs = 187_000 + SAME_RECORDING_LENGTH_MS)
        assertEquals("Sunroofを開けて", words(sunshine))
        // A recording of another length is another song of that name.
        piperSong(lengthMs = 187_000 + SAME_RECORDING_LENGTH_MS + 1)
        assertEquals(Lyrics.None, found(sunshine))
        piperSong(lengthMs = 187_000 - SAME_RECORDING_LENGTH_MS - 1)
        assertEquals(Lyrics.None, found(sunshine))
    }

    @Test
    fun anArtistGivenInHangulThatNetEaseWritesInLatinLettersCountsForARecordingAsLong() {
        // 사계 by 양홍원, whom NetEase credits as YANGHONGWON and the US store as Young B.
        fun fourSeasons(lengthMs: Long) {
            fake.handler = { request ->
                val url = request.url
                when {
                    url.host == "lrclib.net" -> FakeSpotify.html(request, "[]")
                    url.host == "itunes.apple.com" -> FakeSpotify.html(request, """{"results":[]}""")
                    url.encodedPath == "/api/search/get" -> FakeSpotify.html(request, """{"result":{"songs":[{"id":8,"name":"사계","artists":[{"name":"YANGHONGWON"}],"duration":$lengthMs}]}}""")
                    else -> FakeSpotify.html(request, """{"lrc":{"lyric":"[00:02.706]샤워한 듯이 풀려 힘이"}}""")
                }
            }
        }
        val song = MusicMetadata("사계", "양홍원", lengthMs = 193_628)
        fourSeasons(lengthMs = 193_628 + SAME_RECORDING_LENGTH_MS)
        assertEquals("샤워한 듯이 풀려 힘이", words(song))
        // A recording of another length is another song of that name.
        fourSeasons(lengthMs = 193_628 + SAME_RECORDING_LENGTH_MS + 1)
        assertEquals(Lyrics.None, found(song))
        // And an artist given in Latin letters is that one.
        fourSeasons(lengthMs = 193_628)
        assertEquals(Lyrics.None, found(song.copy(artist = "Some Band")))
    }

    @Test
    fun aJapaneseTitleNetEaseWritesWithChinasKanjiIsTheSameSong() {
        // YouTube Music's "終わらない夢 - Owaranai Yume"; NetEase writes it 终わらない夢.
        val song = MusicMetadata("終わらない夢 - Owaranai Yume", "Band", lengthMs = 200_000)
        japaneseSong(
            ownNames = false,
            netEase = { """{"result":{"songs":[{"id":3,"name":"终わらない夢","artists":[{"name":"BAND"}],"duration":260000}]}}""" },
            lyric = "[00:01.00]终わらない夢を見よう"
        )
        assertEquals("終わらない夢を見よう", words(song))
        assertTrue((found(song) as Lyrics.Found).lines.isNotEmpty())
        // As its own name would, however long, and not knowing how long, as for a song heard or a Spotify link.
        assertEquals("終わらない夢を見よう", words(song.copy(lengthMs = null)))
    }

    @Test
    fun aTitleWithKanjiJapanSimplifiedItsOwnWayOnlyCountsForARecordingAsLong() {
        // 楽 is how Japan writes 樂, China 乐: no way of writing one as the other, so only a recording as long counts.
        val song = MusicMetadata("楽しい夢", "Band", lengthMs = 200_000)
        japaneseSong(
            ownNames = false,
            netEase = { """{"result":{"songs":[{"id":3,"name":"乐しい夢","artists":[{"name":"Band"}],"duration":200000}]}}""" },
            lyric = "[00:01.00]乐しい夢を見よう"
        )
        assertEquals("楽しい夢を見よう", words(song))
        assertEquals(Lyrics.None, found(song.copy(lengthMs = null)))
        // A recording of another length is another song of that name.
        assertEquals(Lyrics.None, found(song.copy(lengthMs = 200_000 + SAME_RECORDING_LENGTH_MS + 1)))
        assertEquals("楽しい夢を見よう", words(song.copy(lengthMs = 200_000 + SAME_RECORDING_LENGTH_MS)))
        // Other kanji, written either way, are another song.
        assertFalse(SongNames.sameVersion(SongNames.asChinaWrites("東京物語"), SongNames.asChinaWrites("南京故事")))
        assertEquals("Song", SongNames.asChinaWrites("Song"))
    }

    @Test
    fun japaneseWordsNetEaseWritesChinasWayShowAsJapanWritesThem() {
        // NetEase writes 瞬間 as 瞬间 and 僕 as 仆, and ends each line with China's comma.
        fun netEase(name: String, byWord: String) {
            fake.handler = { request ->
                when (request.url.encodedPath) {
                    "/api/search/get" -> FakeSpotify.html(request, """{"result":{"songs":[{"id":3,"name":"$name","artists":[{"name":"Band"}],"duration":200000}]}}""")
                    "/api/song/lyric" -> FakeSpotify.html(request, """{"lrc":{"lyric":""},"yrc":{"lyric":${quote(byWord)}}}""")
                    else -> FakeSpotify.html(request, if (request.url.host == "lrclib.net") "[]" else """{"results":[]}""")
                }
            }
        }
        netEase("夢の歌", "[1000,2000](1000,900,0)真実の(1900,1100,0)瞬间は，\n[3000,2000](3000,900,0)仆や(3900,1100,0)君の(5000,500,0)，")
        val japanese = found(MusicMetadata("夢の歌", "Band", lengthMs = 200_000)) as Lyrics.Found
        assertEquals(listOf("真実の瞬間は", "僕や君の"), japanese.lines.map { it.text })
        assertEquals(listOf(listOf("真実の", "瞬間は"), listOf("僕や", "君の", "")), japanese.lines.map { line -> line.words.map { it.text } })
        // Kept as found, before they're timed better, they show the same again.
        val file = File.createTempFile("lyrics", ".json").apply { delete(); deleteOnExit() }
        val kept = LyricsFinder(fake.client(), "2.3.3", busyPauseMs = 0, cache = LookupCache(file, Dispatchers.Unconfined))
        runBlocking { kept.firstLook(MusicMetadata("夢の歌", "Band")) }
        fake.handler = { throw IOException("offline") }
        assertEquals(japanese, runBlocking { kept.firstLook(MusicMetadata("夢の歌", "Band")) }.lyrics)

        // Chinese, which has no kana, is as China writes it.
        netEase("时间", "[1000,2000](1000,900,0)我爱你的(1900,1100,0)时间，")
        assertEquals(listOf("我爱你的时间，"), (found(MusicMetadata("时间", "Band", lengthMs = 200_000)) as Lyrics.Found).lines.map { it.text })
    }

    @Test
    fun wordsInRomajiUnderOneNameGiveWayToJapaneseUnderTheOther() {
        // LRCLIB has "僕の手 - Boku no Te" timed in romaji; NetEase has 僕の手, written 仆の手, timed in Japanese.
        val romaji = "[00:01.00] boku no te wo shirimasen ka\n[00:05.00] yukue fumei ni narimashita\n[00:09.00] ima sugu sagashi ni ikanai to"
        fun sites(japanese: String) {
            fake.handler = { request ->
                val url = request.url
                when {
                    url.host == "lrclib.net" && url.queryParameter("track_name") == "僕の手 - Boku no Te" ->
                        FakeSpotify.html(request, """[${answer("僕の手 - Boku no Te", "Band", romaji.replace(Regex("""\[[^]]*] """), ""), romaji)}]""")
                    url.host == "lrclib.net" -> FakeSpotify.html(request, "[]")
                    url.host == "itunes.apple.com" -> FakeSpotify.html(request, """{"results":[]}""")
                    url.encodedPath == "/api/search/get" && url.queryParameter("s") == "僕の手 Band" ->
                        FakeSpotify.html(request, """{"result":{"songs":[{"id":6,"name":"仆の手","artists":[{"name":"Band"}],"duration":250000}]}}""")
                    url.encodedPath == "/api/search/get" -> FakeSpotify.html(request, """{"result":{"songs":[]}}""")
                    url.encodedPath == "/api/song/lyric" -> FakeSpotify.html(request, """{"lrc":{"lyric":${quote(japanese)}}}""")
                    else -> FakeSpotify.html(request, "", code = 404)
                }
            }
        }
        val song = MusicMetadata("僕の手 - Boku no Te", "Band")
        sites("[00:01.00]僕の手を知りませんか\n[00:05.00]行方不明になりました")
        assertEquals("僕の手を知りませんか\n行方不明になりました", words(song))
        // Japanese not timed, or none at all, the romaji stays.
        sites("僕の手を知りませんか\n行方不明になりました")
        assertTrue(isRomaji(words(song).orEmpty()))
        sites("")
        assertTrue(isRomaji(words(song).orEmpty()))
    }

    @Test
    fun aVideosWholeTitleSaidAgainInJapaneseIsLookedUpUnderTheSongBeforeIt() {
        fake.handler = { request ->
            val url = request.url
            when {
                url.host == "lrclib.net" && url.queryParameter("track_name") == "Plastic Love" ->
                    FakeSpotify.html(request, """[{"trackName":"Plastic Love","artistName":"Mariya Takeuchi","syncedLyrics":"[00:30.54] 突然のキスや"}]""")
                url.host == "lrclib.net" -> FakeSpotify.html(request, "[]")
                url.host == "itunes.apple.com" -> FakeSpotify.html(request, """{"results":[]}""")
                else -> FakeSpotify.html(request, """{"result":{"songs":[]}}""")
            }
        }
        assertEquals("突然のキスや", words(MusicMetadata("Plastic Love  竹内 まりや - プラスティックラブ 1984", "Mariya Takeuchi")))
    }

    @Test
    fun aVideoNamedWithATagNoOneKeepsHasTheSongsWords() {
        val title = "Everybody Wants To Rule The World"
        fake.handler = { request ->
            val url = request.url
            when {
                url.host == "lrclib.net" && url.queryParameter("track_name") == title ->
                    FakeSpotify.html(request, """[{"trackName":"$title","artistName":"Tears For Fears","syncedLyrics":"[00:20.00] Welcome to your life"}]""")
                url.host == "lrclib.net" -> FakeSpotify.html(request, "[]")
                url.host == "itunes.apple.com" -> FakeSpotify.html(request, """{"results":[]}""")
                else -> FakeSpotify.html(request, """{"result":{"songs":[]}}""")
            }
        }
        assertEquals("Welcome to your life", words(MusicMetadata("$title (Loop y Extendido)", "Tears For Fears")))
        // Under the whole title first, as it's usually right.
        val asked = fake.requestedUrls.filter { it.startsWith("https://lrclib.net/") }.mapNotNull { it.toHttpUrl().queryParameter("track_name") }
        assertEquals("$title (Loop y Extendido)", asked.first())
    }

    @Test
    fun aTitleThatsTwoNamesIsLookedUpUnderEach() {
        fake.handler = { request ->
            val url = request.url
            when {
                url.host == "lrclib.net" -> FakeSpotify.html(request, "[]")
                url.host == "itunes.apple.com" -> FakeSpotify.html(request, """{"results":[]}""")
                url.encodedPath == "/api/search/get" -> FakeSpotify.html(
                    request,
                    if (url.queryParameter("s") == "ミステリー・ガール I Re'in For Re'in") """{"result":{"songs":[{"id":4,"name":"ミステリー・ガール","artists":[{"name":"I Re'in For Re'in"}]}]}}""" else """{"result":{"songs":[]}}"""
                )
                else -> FakeSpotify.html(request, """{"lrc":{"lyric":"[00:21.360]焼けつく アスファルト"}}""")
            }
        }
        assertEquals("焼けつく アスファルト", words(MusicMetadata("ミステリー・ガール - Mystery Girl", "I Re'in For Re'in")))
        // The whole title first, as it's usually right.
        val asked = fake.requestedUrls.filter { it.startsWith("https://lrclib.net/") }
        assertEquals(true, asked.first().contains("track_name=%E3%83%9F%E3%82%B9%E3%83%86%E3%83%AA%E3%83%BC%E3%83%BB%E3%82%AC%E3%83%BC%E3%83%AB%20-%20Mystery%20Girl"))
        // Found under neither name, it's none.
        fake.handler = { request ->
            when (request.url.host) {
                "itunes.apple.com" -> FakeSpotify.html(request, """{"results":[]}""")
                "music.163.com" -> FakeSpotify.html(request, """{"result":{"songs":[]}}""")
                else -> FakeSpotify.html(request, "[]")
            }
        }
        assertEquals(Lyrics.None, found(MusicMetadata("A - B", "Band")))
        // A title with more than one dash is one name, then, last, the name before its first.
        fake.requestedUrls.clear()
        assertEquals(Lyrics.None, found(MusicMetadata("A - B - C", "Band")))
        assertEquals(listOf("A - B - C", "A"), fake.requestedUrls.filter { it.startsWith("https://lrclib.net/") }.mapNotNull { it.toHttpUrl().queryParameter("track_name") })
    }


    @Test
    fun romajiIsJapaneseInLatinLettersNotEnglish() {
        // Lines made up for the test.
        assertEquals(true, isRomaji("ashita no sora wa aoi kara\nkimi to aruku michi de\nboku wa zutto matte iru yo"))
        // Mixed with English, as many songs are, it's still romaji.
        assertEquals(true, isRomaji("Just summer night we drive\nkimi no koe ga kikoeru\nTake me far away\nmachi no akari ni yume wo miru"))
        assertEquals(false, isRomaji("I walked along the river tonight\nthinking about the things you said\nthe city lights were shining bright"))
        // Japanese syllables without its little words, or in Japanese already, isn't.
        assertEquals(false, isRomaji("sakura sakura yayoi no sora"))
        assertEquals(false, isRomaji("空は青い から 君と 歩く ni wa ga wo"))
        assertEquals(false, isRomaji(""))
        // Just under half the words read as Japanese is enough, with three of its little words.
        val english = "street night black dream lights think world green stand river shout"
        assertEquals(true, isRomaji("ni wa ga sora kaze yume hana michi namida $english"))
        assertEquals(false, isRomaji("ni wa ga sora kaze yume hana michi cloud $english"))
        assertEquals(false, isRomaji("ni wa koe sora kaze yume hana michi namida $english"))
    }

    @Test
    fun koreanInLatinLettersIsToldFromRomajiAndOtherLanguages() {
        // Lines made up for the test.
        val korean = "neon nae mameul molla\ngeu ttae uri dasi mannal su isseulkka\nnaega neol bureul ttae"
        assertEquals(true, isRomanizedKorean(korean))
        assertEquals(Script.KOREAN, romanizedScript(korean))
        assertEquals(Script.JAPANESE, romanizedScript("ashita no sora wa aoi kara\nkimi to aruku michi de\nboku wa zutto matte iru yo"))
        assertEquals(false, isRomanizedKorean("ashita no sora wa aoi kara\nkimi to aruku michi de\nboku wa zutto matte iru yo"))
        assertNull(romanizedScript("I walked along the river tonight\nthinking about the things you said"))
        // Hindi reads as Korean syllables too, without its little words.
        assertEquals(false, isRomanizedKorean("main tera intezaar karta hoon\ndil ki baat kehni hai\nyeh raat bahut lambi hai"))
        // In Hangul or kana already, it isn't.
        assertEquals(false, isRomanizedKorean("넌 내 맘을 nae neon geu uri"))
        assertEquals(false, isRomanizedKorean("君と nae neon geu uri"))
        assertEquals(false, isRomanizedKorean(""))
        // Just under half the words read as Korean is enough, with three of its little words.
        val english = "street night black dream lights think world green stand river shout"
        assertEquals(true, isRomanizedKorean("nae neon geu saram haneul baram kkum sarang nunmul $english"))
        assertEquals(false, isRomanizedKorean("nae neon geu saram haneul baram kkum sarang cloud $english"))
        assertEquals(false, isRomanizedKorean("nae neon gippeum saram haneul baram kkum sarang nunmul $english"))
    }

    @Test
    fun koreanInLatinLettersGivesWayToTheWordsInHangul() {
        val korean = "neon nae mameul molla\ngeu ttae uri dasi\nnaega neol bureul ttae"
        betterElsewhere(answer("Song", "Band", korean, "[00:01.00] neon nae mameul molla"), "[00:01.00]넌 내 맘을 몰라")
        assertEquals("넌 내 맘을 몰라", words(MusicMetadata("Song", "Band")))
        // Japanese elsewhere isn't the song's own script: LRCLIB's stays.
        betterElsewhere(answer("Song", "Band", korean, "[00:01.00] neon nae mameul molla"), "[00:01.00]君の声")
        assertEquals(korean, words(MusicMetadata("Song", "Band")))
        // Nor is Hangul for words in romaji.
        val romaji = "kimi no koe ga kikoeru\nboku wa zutto matte iru yo\nyume wo miru"
        betterElsewhere(answer("Song", "Band", romaji, "[00:01.00] kimi no koe ga kikoeru"), "[00:01.00]넌 내 맘을 몰라")
        assertEquals(romaji, words(MusicMetadata("Song", "Band")))
    }

    /** A song LRCLIB has only [lrclib], and NetEase has with [netEase] words. */
    private fun betterElsewhere(lrclib: String, netEase: String) {
        fake.handler = { request ->
            val url = request.url
            when {
                url.host == "lrclib.net" -> FakeSpotify.html(request, "[$lrclib]")
                url.host == "itunes.apple.com" -> FakeSpotify.html(request, """{"results":[]}""")
                url.encodedPath == "/api/search/get" -> FakeSpotify.html(request, """{"result":{"songs":[{"id":1,"name":"Song","artists":[{"name":"Band"}]}]}}""")
                else -> FakeSpotify.html(request, """{"lrc":{"lyric":${quote(netEase)}}}""")
            }
        }
    }

    @Test
    fun romajiGivesWayToTheWordsInJapanese() {
        val romaji = "kimi no koe ga kikoeru\nboku wa zutto matte iru yo\nyume wo miru"
        betterElsewhere(answer("Song", "Band", romaji, "[00:01.00] kimi no koe ga kikoeru"), "[00:01.00]君の声が聞こえる")
        assertEquals("君の声が聞こえる", words(MusicMetadata("Song", "Band")))
        // Romaji elsewhere too isn't better: LRCLIB's stays.
        betterElsewhere(answer("Song", "Band", romaji, "[00:01.00] kimi no koe ga kikoeru"), "[00:01.00]kimi no koe")
        assertEquals(romaji, words(MusicMetadata("Song", "Band")))
    }

    @Test
    fun untimedWordsGiveWayToTimedOnes() {
        betterElsewhere(answer("Song", "Band", "First line"), "[00:02.00]First line")
        assertEquals(Lyrics.Found("First line", listOf(LyricLine(2_000, "First line")), source = LyricsFinder.NETEASE), found(MusicMetadata("Song", "Band")))
        // Untimed elsewhere too: LRCLIB's stays.
        betterElsewhere(answer("Song", "Band", "First line"), "Other words")
        assertEquals(Lyrics.Found("First line", source = LyricsFinder.LRCLIB), found(MusicMetadata("Song", "Band")))
        // Timed words that aren't romaji are taken straight away, with nothing else asked.
        fake.requestedUrls.clear()
        betterElsewhere(answer("Song", "Band", "First line", "[00:01.00] First line"), "[00:02.00]Other")
        assertEquals("First line", words(MusicMetadata("Song", "Band")))
        assertEquals(1, askedBesidesNetEase())
    }


    /** YouTube Music's lyrics page: [lines] as text to start time, or untimed where the time is null. */
    /** Requests made, besides the NetEase ones asked for words timed word by word. */
    private fun askedBesidesNetEase() = fake.requestedUrls.count { !it.startsWith("https://music.163.com/") }

    private fun youTubeMusicPage(vararg lines: Pair<String, Long?>): String {
        val data = lines.joinToString(",") { (text, start) ->
            val cue = start?.let { ""","cueRange":{"startTimeMilliseconds":"$it","endTimeMilliseconds":"0"}""" }.orEmpty()
            """{"lyricLine":${quote(text)}$cue}"""
        }
        return """{"contents":{"elementRenderer":{"newElement":{"type":{"componentType":{"model":{"timedLyricsModel":{"lyricsData":{"timedLyricsData":[$data]}}}}}}}}}"""
    }

    /** LRCLIB answers [lrclib]; YouTube Music has the song with [page] for its words; nothing else has it. */
    private fun everywhere(lrclib: String, page: String?, next: String = """{"tabs":[{"browseEndpoint":{"browseId":"MPLYt_words"}}]}""") {
        fake.handler = { request ->
            val url = request.url
            when {
                url.host == "lrclib.net" -> FakeSpotify.html(request, lrclib)
                url.encodedPath == "/youtubei/v1/next" -> FakeSpotify.html(request, next)
                url.encodedPath == "/youtubei/v1/browse" -> page?.let { FakeSpotify.html(request, it) } ?: throw IOException("offline")
                url.host == "music.163.com" -> FakeSpotify.html(request, """{"result":{"songs":[]}}""")
                else -> FakeSpotify.html(request, """{"results":[]}""")
            }
        }
    }

    private fun withYouTubeMusic(videoId: String? = "vid1") =
        LyricsFinder(fake.client(), "2.3.3", busyPauseMs = 0, youTubeMusicSong = { videoId })

    private val song = MusicMetadata("Song", "Band")
    private val timedOnLrclib = answer("Song", "Band", "First\nSecond", synced = "[00:01.00] First\n[00:10.00] Second")

    @Test
    fun anExactRecordingsOwnTimedLyricsBeatSearchResults() {
        everywhere("[$timedOnLrclib]", youTubeMusicPage("Own first" to 12_000, "Own second" to 15_500))
        val known = song.copy(url = "https://music.youtube.com/watch?v=abcdefghijk")
        assertEquals("Own first\nOwn second", (runBlocking { withYouTubeMusic().lyricsOf(known) } as Lyrics.Found).words)
    }

    @Test
    fun anExactRecordingsOwnTimedLyricsAreShownWithoutWaitingForTheOthers() {
        everywhere("[$timedOnLrclib]", youTubeMusicPage("Own first" to 12_000, "Own second" to 15_500))
        val answers = fake.handler
        val held = CountDownLatch(1)
        // LRCLIB and NetEase answer late, as NetEase does: waiting for them would hold the words up.
        fake.handler = { request ->
            if (request.url.host in setOf("lrclib.net", "music.163.com")) held.await()
            answers(request)
        }
        val known = song.copy(url = "https://music.youtube.com/watch?v=abcdefghijk")
        try {
            val first = runBlocking { withTimeout(10_000) { withYouTubeMusic().firstLook(known, timeoutMs = 30_000) } }
            assertEquals("Own first\nOwn second", (first.lyrics as Lyrics.Found).words)
        } finally {
            held.countDown()
        }
    }

    @Test
    fun exactPlayingVideoIsUsedInsteadOfASearchMatch() {
        everywhere("[]", youTubeMusicPage("First" to 12_000, "Second" to 15_500))
        val known = song.copy(url = "https://music.youtube.com/watch?v=abcdefghijk")
        runBlocking { withYouTubeMusic("wrong000000").lyricsOf(known) }
        assertTrue(fake.requestBodies.any { it.contains("\"videoId\":\"abcdefghijk\"") })
        assertFalse(fake.requestBodies.any { it.contains("wrong000000") })
        fake.requestBodies.clear()
        runBlocking { finder().lyricsOf(known) }
        assertTrue(fake.requestBodies.any { it.contains("\"videoId\":\"abcdefghijk\"") })
    }

    @Test
    fun youTubeMusicsTimedWordsWin() {
        everywhere("[$timedOnLrclib]", youTubeMusicPage("♪" to 0, "Erste" to 12_000, "Zweite" to 15_500))
        assertEquals(
            // The note at the start is a pause, as it's long enough.
            Lyrics.Found("Erste\nZweite", listOf(LyricLine(0, ""), LyricLine(12_000, "Erste"), LyricLine(15_500, "Zweite")), source = LyricsFinder.YOUTUBE_MUSIC),
            runBlocking { withYouTubeMusic().lyricsOf(song) }
        )
        // The song's page is asked for by its video, and the words as YouTube Music's app, which gets them timed.
        val next = fake.requestBodies[fake.requestedUrls.indexOfFirst { it.startsWith("https://music.youtube.com/youtubei/v1/next") }]
        assertEquals(true, next.contains(""""videoId":"vid1"""") && next.contains("WEB_REMIX"))
        val browse = fake.requestBodies[fake.requestedUrls.indexOfFirst { it.startsWith("https://music.youtube.com/youtubei/v1/browse") }]
        assertEquals(true, browse.contains(""""browseId":"MPLYt_words"""") && browse.contains("ANDROID_MUSIC"))
    }

    @Test
    fun youTubeMusicsUntimedWordsGiveWayToTimedOnes() {
        everywhere("[$timedOnLrclib]", youTubeMusicPage("Erste" to null, "Zweite" to null))
        assertEquals(Lyrics.Found("First\nSecond", listOf(LyricLine(1_000, "First"), LyricLine(10_000, "Second")), source = LyricsFinder.LRCLIB), runBlocking { withYouTubeMusic().lyricsOf(song) })
        // With none elsewhere, they're the words.
        everywhere("[]", youTubeMusicPage("Erste" to null, "♪" to null, "Zweite" to null))
        assertEquals(Lyrics.Found("Erste\nZweite", source = LyricsFinder.YOUTUBE_MUSIC), runBlocking { withYouTubeMusic().lyricsOf(song) })
    }

    @Test
    fun wordsTimedBadlyGiveWayToWordsTimedWell() {
        // Two lines of eight syllables each, in about half a second.
        val rushed = answer(
            "Song", "Band", "",
            synced = "[00:43.48] 金色のブレス\n[00:44.64] きらめいたピアス\n[00:45.20] Ah ブローした髪を\n[02:14.35] たそがれのワイン\n[02:14.99] Ah 振りまわす恋を\n[02:22.20] 楽しんで 罪さ"
        )
        everywhere("[$rushed]", youTubeMusicPage("街角のテレフォン" to 24_520, "流し目の彼女" to 28_160))
        assertEquals(listOf(LyricLine(24_520, "街角のテレフォン"), LyricLine(28_160, "流し目の彼女")), (runBlocking { withYouTubeMusic().lyricsOf(song) } as Lyrics.Found).lines)
        // Still timed, they beat untimed words: they mostly follow the song, and can be put in time by hand.
        everywhere("[$rushed]", youTubeMusicPage("街角のテレフォン" to null))
        assertEquals(6, (runBlocking { withYouTubeMusic().lyricsOf(song) } as Lyrics.Found).lines.size)
        // Timed badly, LRCLIB's words don't stop other places from being asked for timed ones.
        everywhere("[$rushed]", null)
        runBlocking { withYouTubeMusic().lyricsOf(song) }
        assertEquals(true, fake.requestedUrls.any { it.startsWith("https://music.163.com/api/search/get") })
    }

    @Test
    fun withoutYouTubeMusicsWordsTheOthersStillCount() {
        val lrclibWords = Lyrics.Found("First\nSecond", listOf(LyricLine(1_000, "First"), LyricLine(10_000, "Second")), source = LyricsFinder.LRCLIB)
        // Not on YouTube Music.
        everywhere("[$timedOnLrclib]", youTubeMusicPage("Erste" to 1))
        assertEquals(lrclibWords, runBlocking { withYouTubeMusic(videoId = null).lyricsOf(song) })
        // On it, but with no lyrics page, or a page that says there are none.
        everywhere("[$timedOnLrclib]", youTubeMusicPage("Erste" to 1), next = "{}")
        assertEquals(lrclibWords, runBlocking { withYouTubeMusic().lyricsOf(song) })
        everywhere("[$timedOnLrclib]", """{"contents":{"elementRenderer":{"newElement":{"type":{"componentType":{"model":{"musicMessageModel":{"text":"Lyrics not available"}}}}}}}}""")
        assertEquals(lrclibWords, runBlocking { withYouTubeMusic().lyricsOf(song) })
        everywhere("[$timedOnLrclib]", youTubeMusicPage("♪" to 0))
        assertEquals(lrclibWords, runBlocking { withYouTubeMusic().lyricsOf(song) })
        // Offline, an error, or something that isn't JSON.
        everywhere("[$timedOnLrclib]", null)
        assertEquals(lrclibWords, runBlocking { withYouTubeMusic().lyricsOf(song) })
        everywhere("[$timedOnLrclib]", "not json")
        assertEquals(lrclibWords, runBlocking { withYouTubeMusic().lyricsOf(song) })
        fake.handler = { request ->
            if (request.url.host == "music.youtube.com") FakeSpotify.html(request, "{}", code = 500) else FakeSpotify.html(request, "[$timedOnLrclib]")
        }
        assertEquals(lrclibWords, runBlocking { withYouTubeMusic().lyricsOf(song) })
        // Too slow to wait for.
        fake.handler = { request ->
            if (request.url.host == "music.youtube.com") Thread.sleep(2_000)
            FakeSpotify.html(request, if (request.url.host == "lrclib.net") "[$timedOnLrclib]" else "{}")
        }
        assertEquals(lrclibWords, runBlocking { withYouTubeMusic().lyricsOf(song, timeoutMs = 500) })
        // Nowhere has them: none. YouTube Music having none doesn't mean the song has none, so with
        // LRCLIB not answering, that's what's said.
        everywhere("[]", null, next = "{}")
        assertEquals(Lyrics.None, runBlocking { withYouTubeMusic().lyricsOf(song) })
        fake.handler = { request ->
            if (request.url.host == "music.youtube.com") FakeSpotify.html(request, "{}") else FakeSpotify.html(request, "busy", code = 503)
        }
        assertEquals(Lyrics.Unavailable, runBlocking { withYouTubeMusic().lyricsOf(song) })
        // Nothing answers at all.
        fake.handler = { request -> FakeSpotify.html(request, "busy", code = 503) }
        assertEquals(Lyrics.Unavailable, runBlocking { withYouTubeMusic().lyricsOf(song) })
    }

    @Test
    fun wordsKeptBeforeYouTubeMusicWasAskedAreAskedAgain() {
        val file = File.createTempFile("lyrics", ".json").apply { delete(); deleteOnExit() }
        val cache = LookupCache(file, Dispatchers.Unconfined)
        val old = """{"words":"Old words","lines":[[1000,"Old words"]]}"""
        runBlocking { cache.put("${SongNames.normalize("Song")}\u0000${SongNames.normalize("Band")}", old) }
        val kept = LyricsFinder(fake.client(), "2.3.3", busyPauseMs = 0, cache = cache)
        respond("[$timedOnLrclib]")
        assertEquals("First\nSecond", (runBlocking { kept.lyricsOf(song) } as Lyrics.Found).words)
    }

    /** Asphalt Lady's opening, timed by guesswork, as NetEase has it. */
    private val guessed = "[00:24.52] 街角のテレフォン\n[00:28.16] 流し目の彼女\n[00:33.40] Ah　グラビアみたいな\n[00:34.00] 長い脚　めまいさ\n" +
        "[00:43.48] 金色のブレス\n[00:44.64] きらめいたピアス\n[00:45.20] Ah　ブローした髪を\n[00:56.28] くびすじに跳ねた\n" +
        "[00:59.80] 誘いの言葉には　ウィンク\n[01:04.08] つれなく返すだけ　シャクだね\n[01:07.24] Shock!　君はアスファルト・レディ"

    /** The same, well timed in romaji, two lines at a time, under the song's bare name. */
    private val romaji = "[00:24.33] Machikado no terefuon nagashime no kanojo\n[00:31.82] Aah gurabia mitaina nagai ashi memaisa\n" +
        "[00:39.77] Kin'iro no buresu kirameita piasu\n[00:47.11] Aah buro- shita kami o ku Bisujini haneta\n" +
        "[00:55.81] Sasoi no kotoba ni wa whinku\n[01:03.53] Tsurenaku kaesu dake shaku da ne\n[01:12.20] Shock! kimi wa asufuaruto redi"

    private val remix = MusicMetadata("ASPHALT LADY (2018 Remix) (2024 Remaster)", "S.Kiyotaka & Omega Tribe")

    /** LRCLIB has the remix's words timed by guesswork, and [bare] under the song's bare name. */
    private fun remixWords(vararg bare: String) {
        fake.handler = { request ->
            val url = request.url
            when {
                url.host == "lrclib.net" && url.queryParameter("track_name") == remix.title ->
                    FakeSpotify.html(request, "[${answer(remix.title, remix.artist, "", synced = guessed)}]")
                url.host == "lrclib.net" -> FakeSpotify.html(request, "[${bare.joinToString(",")}]")
                url.host == "music.163.com" -> FakeSpotify.html(request, """{"result":{"songs":[]}}""")
                else -> FakeSpotify.html(request, """{"results":[]}""")
            }
        }
    }

    @Test
    fun wordsTimedByGuessworkTakeTheTimesOfTheSameWordsWellTimedElsewhere() {
        remixWords(answer("Asphalt Lady", "Sugiyama Kiyotaka & Omega Tribe", "", synced = romaji))
        val found = found(remix) as Lyrics.Found
        // When each line is really sung, to a second or two: the words stay as they were, in Japanese.
        val sung = listOf(24_000L, 28_000L, 31_000L, 35_000L, 39_000L, 43_000L, 46_000L, 50_000L, 54_000L, 63_500L, 71_000L)
        assertEquals(SyncedLyrics.parse(guessed).map { it.text }, found.lines.map { it.text })
        found.lines.forEachIndexed { index, line -> assertTrue("${line.text} at ${line.timeMs}", abs(line.timeMs - sung[index]) <= 2_500) }
    }

    @Test
    fun anotherTakeUnderTheBareNameLendsNoTimes() {
        // A live take, a minute later throughout.
        val live = SyncedLyrics.parse(romaji).joinToString("\n") { "[${"%02d".format((it.timeMs + 60_000) / 60_000)}:${"%05.2f".format((it.timeMs + 60_000) % 60_000 / 1000.0)}] ${it.text}" }
        remixWords(answer("Asphalt Lady", "S.Kiyotaka & Omega Tribe", "", synced = live))
        assertEquals(SyncedLyrics.parse(guessed), (found(remix) as Lyrics.Found).lines)
        // Nor does someone else's song of that name, another song, or words timed by guesswork too.
        remixWords(
            answer("Asphalt Lady", "Someone Else", "", synced = romaji),
            answer("Asphalt Lady", "S.Kiyotaka & Omega Tribe", "", synced = "[00:24.00] Yesterday all my troubles\n[00:31.00] Seemed so far away\n[00:39.00] Now it looks as though"),
            answer("Asphalt Lady", "S.Kiyotaka & Omega Tribe", "", synced = guessed)
        )
        assertEquals(SyncedLyrics.parse(guessed), (found(remix) as Lyrics.Found).lines)
        // LRCLIB not answering for the bare name, the words are as found.
        fake.handler = { request ->
            val url = request.url
            when {
                url.host == "lrclib.net" && url.queryParameter("track_name") == remix.title ->
                    FakeSpotify.html(request, "[${answer(remix.title, remix.artist, "", synced = guessed)}]")
                url.host == "lrclib.net" -> throw IOException("offline")
                else -> FakeSpotify.html(request, """{"results":[]}""")
            }
        }
        assertEquals(SyncedLyrics.parse(guessed), (found(remix) as Lyrics.Found).lines)
    }

    /** [lrc]'s lines, each [byMs] later. */
    private fun shifted(lrc: String, byMs: Long) = SyncedLyrics.parse(lrc).joinToString("\n") {
        val at = it.timeMs + byMs
        "[${"%02d".format(at / 60_000)}:${"%05.2f".format(at % 60_000 / 1000.0)}] ${it.text}"
    }

    /** [answer] saying how long its recording lasts. */
    private fun lasting(answer: String, seconds: Double) = answer.dropLast(1) + ""","duration":$seconds}"""

    /** Asphalt Lady's opening, evenly timed but for another edit, 9 seconds early, as NetEase's other copy has it. */
    private val early = shifted(guessed.replace("[00:34.00]", "[00:35.60]").replace("[00:44.64]", "[00:42.80]"), -9_000)

    /** LRCLIB has the remix's words timed [early] under its full name, the store knows it [storeSeconds] long, and [bare] under its bare name. */
    private fun remixTimedEarly(vararg bare: String, storeSeconds: Double? = null) {
        fake.handler = { request ->
            val url = request.url
            when {
                url.host == "lrclib.net" && url.queryParameter("track_name") == remix.title ->
                    FakeSpotify.html(request, "[${answer(remix.title, remix.artist, "", synced = early)}]")
                url.host == "lrclib.net" -> FakeSpotify.html(request, "[${bare.joinToString(",")}]")
                url.encodedPath == "/search" && storeSeconds != null -> FakeSpotify.html(
                    request,
                    """{"results":[{"trackId":7,"trackName":"Asphalt Lady (2018 Remix) [2024 Remaster]","artistName":"S.Kiyotaka & Omega Tribe","trackTimeMillis":${(storeSeconds * 1000).toLong()}}]}"""
                )
                url.host == "music.163.com" -> FakeSpotify.html(request, """{"result":{"songs":[]}}""")
                else -> FakeSpotify.html(request, """{"results":[]}""")
            }
        }
    }

    private val sungAt = listOf(24_000L, 28_000L, 31_000L, 35_000L, 39_000L, 43_000L, 46_000L, 50_000L, 54_000L, 63_500L, 71_000L)

    @Test
    fun wordsTimedForAnotherEditTakeTheTimesTwoCopiesAgreeOn() {
        val copies = arrayOf(
            lasting(answer("Asphalt Lady", "Sugiyama Kiyotaka & Omega Tribe", "", synced = romaji), 309.0),
            lasting(answer("ASPHALT LADY", "S.Kiyotaka & Omega Tribe", "", synced = shifted(romaji, 600)), 312.0)
        )
        remixTimedEarly(*copies, storeSeconds = 318.483)
        val found = found(remix) as Lyrics.Found
        assertEquals(SyncedLyrics.parse(early).map { it.text }, found.lines.map { it.text })
        found.lines.forEachIndexed { index, line -> assertTrue("${line.text} at ${line.timeMs}", abs(line.timeMs - sungAt[index]) <= 2_500) }
    }

    @Test
    fun theWordsComeFirstAndTheirBetterTimesAfterAndAreKeptSo() {
        val copies = arrayOf(
            lasting(answer("Asphalt Lady", "Sugiyama Kiyotaka & Omega Tribe", "", synced = romaji), 309.0),
            lasting(answer("ASPHALT LADY", "S.Kiyotaka & Omega Tribe", "", synced = shifted(romaji, 600)), 312.0)
        )
        remixTimedEarly(*copies, storeSeconds = 318.483)
        val file = File.createTempFile("lyrics", ".json").apply { delete() }
        val finder = LyricsFinder(fake.client(), "2.3.3", busyPauseMs = 0, cache = LookupCache(file, Dispatchers.Unconfined))
        // First as found, to show at once.
        val first = runBlocking { finder.firstLook(remix) }
        assertEquals(LyricsFinder.FirstLook(Lyrics.Found(SyncedLyrics.parse(early).joinToString("\n") { it.text }, SyncedLyrics.parse(early), source = LyricsFinder.LRCLIB), final = false), first)
        // Kept at once as they are, so they show again with no lookup, still to be timed.
        val asked = fake.requestedUrls.size
        assertEquals(first, runBlocking { LyricsFinder(fake.client(), "2.3.3", busyPauseMs = 0, cache = LookupCache(file, Dispatchers.Unconfined)).firstLook(remix) })
        assertEquals(asked, fake.requestedUrls.size)
        // Then timed as the copies agree, and kept that way.
        val better = runBlocking { finder.retimed(remix, first.lyrics) } as Lyrics.Found
        better.lines.forEachIndexed { index, line -> assertTrue("${line.text} at ${line.timeMs}", abs(line.timeMs - sungAt[index]) <= 2_500) }
        // A report on them says whose timing they took.
        assertEquals("lrclib, timed by lrclib", better.source)
        val again = runBlocking { LyricsFinder(fake.client(), "2.3.3", busyPauseMs = 0, cache = LookupCache(file, Dispatchers.Unconfined)).firstLook(remix) }
        assertEquals(LyricsFinder.FirstLook(better, final = true), again)
        file.delete()
    }

    @Test
    fun wordsTimedWellAreKeptWithoutTwoCopiesAgainstThem() {
        // One copy alone.
        remixTimedEarly(answer("Asphalt Lady", "S.Kiyotaka & Omega Tribe", "", synced = romaji))
        assertEquals(SyncedLyrics.parse(early), (found(remix) as Lyrics.Found).lines)
        // Two that agree with the words.
        remixTimedEarly(answer("Asphalt Lady", "S.Kiyotaka & Omega Tribe", "", synced = shifted(romaji, -9_000)), answer("Asphalt Lady", "S.Kiyotaka & Omega Tribe", "", synced = shifted(romaji, -8_500)))
        assertEquals(SyncedLyrics.parse(early), (found(remix) as Lyrics.Found).lines)
        // Two that disagree with each other.
        remixTimedEarly(answer("Asphalt Lady", "S.Kiyotaka & Omega Tribe", "", synced = romaji), answer("Asphalt Lady", "S.Kiyotaka & Omega Tribe", "", synced = shifted(romaji, 30_000)))
        assertEquals(SyncedLyrics.parse(early), (found(remix) as Lyrics.Found).lines)
        // Two for a recording much shorter than the song: a radio edit, say.
        val short = arrayOf(
            lasting(answer("Asphalt Lady", "S.Kiyotaka & Omega Tribe", "", synced = romaji), 284.0),
            lasting(answer("Asphalt Lady", "S.Kiyotaka & Omega Tribe", "", synced = romaji), 250.0)
        )
        remixTimedEarly(*short, storeSeconds = 318.483)
        assertEquals(SyncedLyrics.parse(early), (found(remix) as Lyrics.Found).lines)
        // A song named without more has nothing under another name to check against.
        fake.handler = { request ->
            FakeSpotify.html(request, if (request.url.host == "lrclib.net") "[${answer("Asphalt Lady", remix.artist, "", synced = early)}]" else """{"results":[]}""")
        }
        val before = fake.requestedUrls.size
        assertEquals(SyncedLyrics.parse(early), (found(MusicMetadata("Asphalt Lady", remix.artist)) as Lyrics.Found).lines)
        assertEquals(1, fake.requestedUrls.drop(before).count { it.startsWith("https://lrclib.net") })
    }

    @Test
    fun aRemixGetsTheWordsOfTheSameRemixNamedOtherwise() {
        val lyric = "[00:16.53]シルク・グレイの風をすりぬけ\n[00:30.85]光りはじめた　街は横文字"
        fake.handler = { request ->
            val url = request.url
            when {
                url.host == "lrclib.net" && url.queryParameter("track_name") == "Twilight Bay City (Remix)" -> FakeSpotify.html(request, "[]")
                // LRCLIB has only the original, on an album that isn't the remix's.
                url.host == "lrclib.net" -> FakeSpotify.html(request, """[{"trackName":"Twilight Bay City","artistName":"S.Kiyotaka & Omega Tribe","albumName":"NEVER ENDING SUMMER","plainLyrics":"Original words"}]""")
                url.encodedPath == "/api/search/get" -> FakeSpotify.html(
                    request,
                    """{"result":{"songs":[
                        {"id":1,"name":"Twilight Bay City","artists":[{"name":"S.Kiyotaka & Omega Tribe"}],"album":{"name":"NEVER ENDING SUMMER"}},
                        {"id":2,"name":"Twilight Bay City (2022 Remix)","artists":[{"name":"S.Kiyotaka & Omega Tribe"}],"album":{"name":"Never Ending Summer Remix"}}
                    ]}}"""
                )
                url.encodedPath == "/api/song/lyric" ->
                    FakeSpotify.html(request, if (url.queryParameter("id") == "2") """{"lrc":{"lyric":"$lyric"}}""" else """{"lrc":{"lyric":"[00:10.00]Wrong one"}}""")
                else -> FakeSpotify.html(request, """{"results":[]}""")
            }
        }
        val found = found(MusicMetadata("Twilight Bay City (Remix)", "S.Kiyotaka & Omega Tribe")) as Lyrics.Found
        assertEquals(listOf(LyricLine(16_530, "シルク・グレイの風をすりぬけ"), LyricLine(30_850, "光りはじめた　街は横文字")), found.lines)
    }

    @Test
    fun wordsKnowHowLongTheRecordingTheyAreTimedForLasts() {
        val timed = Lyrics.Found("Words", lengthMs = 295_000)
        // As long as the one playing, give or take.
        assertTrue(timed.timedFor(297_900))
        assertFalse(timed.timedFor(308_600))
        // With either length unknown there's no telling they aren't.
        assertTrue(timed.timedFor(null))
        assertTrue(Lyrics.Found("Words").timedFor(308_600))
    }

    private val twoTakes = "[${lasting(answer("Song", "Band", "", synced = "[00:01.00] First take one\n[00:10.00] First take two"), 295.0)}]"

    @Test
    fun aRecordingsOwnTimedWordsAreNotRetimedByCopiesOfAnotherEdit() {
        remixTimedEarly(
            lasting(answer("Asphalt Lady", "Sugiyama Kiyotaka & Omega Tribe", "", synced = romaji), 309.0),
            lasting(answer("ASPHALT LADY", "S.Kiyotaka & Omega Tribe", "", synced = shifted(romaji, 600)), 312.0),
            storeSeconds = 318.483
        )
        val others = fake.handler
        val own = SyncedLyrics.parse(early)
        val page = youTubeMusicPage(*own.map { it.text to it.timeMs }.toTypedArray())
        fake.handler = { request ->
            when (request.url.encodedPath) {
                "/youtubei/v1/next" -> FakeSpotify.html(request, """{"tabs":[{"browseEndpoint":{"browseId":"MPLYt_words"}}]}""")
                "/youtubei/v1/browse" -> FakeSpotify.html(request, page)
                else -> others(request)
            }
        }
        // Two copies of another edit agree on other times, which would time words found elsewhere, not the video's own.
        val video = remix.copy(url = "https://music.youtube.com/watch?v=abcdefghijk")
        assertTrue(runBlocking { withYouTubeMusic().firstLook(video) }.final)
        val found = runBlocking { withYouTubeMusic().lyricsOf(video) } as Lyrics.Found
        assertEquals(own.map { it.timeMs to it.text }, found.lines.map { it.timeMs to it.text })
    }

    @Test
    fun anotherTakesWordsTimedWordByWordDoNotBeatThisOnesTimedALineAtATime() {
        val timed = "[00:16.00] 五月雨は緑色\n[00:23.08] 悲しくさせたよ"
        val byWord = "[16100,2000](16100,900,0)五月雨は(17000,800,0)緑色\n[23200,2000](23200,2000,0)悲しくさせたよ"
        fake.handler = { request ->
            val url = request.url
            when {
                url.host == "lrclib.net" -> FakeSpotify.html(request, "[${lasting(answer("Hatsukoi", "Hiroko Mita", "五月雨は緑色\n悲しくさせたよ", timed), 200.0)}]")
                url.encodedPath == "/api/search/get" -> FakeSpotify.html(request, """{"result":{"songs":[{"id":2,"name":"Hatsukoi","artists":[{"name":"Hiroko Mita"}],"duration":240000}]}}""")
                url.encodedPath == "/api/song/lyric" -> FakeSpotify.html(request, """{"yrc":{"lyric":${quote(byWord)}}}""")
                else -> FakeSpotify.html(request, """{"results":[]}""")
            }
        }
        assertEquals(listOf(16_000L, 23_080L), (found(hatsukoi.copy(lengthMs = 200_000)) as Lyrics.Found).lines.map { it.timeMs })
        // Of a take no one knows the length of, they still win.
        assertEquals(listOf(16_100L, 23_200L), (found(hatsukoi) as Lyrics.Found).lines.map { it.timeMs })
    }

    @Test
    fun wordsKeptForAnotherTakeOfTheSongAreLookedUpAgain() {
        fake.handler = { request ->
            when (request.url.host) {
                "lrclib.net" -> FakeSpotify.html(request, twoTakes)
                "music.163.com" -> FakeSpotify.html(request, """{"result":{"songs":[]}}""")
                else -> FakeSpotify.html(request, """{"results":[]}""")
            }
        }
        val file = File.createTempFile("lyrics", ".json").apply { delete() }
        fun finder() = LyricsFinder(fake.client(), "2.3.3", busyPauseMs = 0, cache = LookupCache(file, Dispatchers.Unconfined))
        fun look(lengthMs: Long?) = runBlocking { finder().firstLook(MusicMetadata("Song", "Band", lengthMs = lengthMs)).lyrics }
        // LRCLIB says how long the recording lasts, and it's kept with the words.
        val first = look(null) as Lyrics.Found
        assertEquals(295_000L, first.lengthMs)
        // Learning its length gives it a separate cache; further looks at that recording need no lookup.
        val asked = fake.requestedUrls.size
        assertEquals(first, look(296_000))
        assertTrue(fake.requestedUrls.size > asked)
        val known = fake.requestedUrls.size
        assertEquals(first, look(296_000))
        assertEquals(known, fake.requestedUrls.size)
        // For a longer one they're another take's, so it's asked about again.
        look(308_600)
        assertTrue(fake.requestedUrls.size > asked)
    }

    @Test
    fun wordsNoCopyIsTimedForAreKeptForTheRecordingTheyWereLookedUpFor() {
        fake.handler = { request ->
            when (request.url.host) {
                "lrclib.net" -> FakeSpotify.html(request, twoTakes)
                "music.163.com" -> FakeSpotify.html(request, """{"result":{"songs":[]}}""")
                else -> FakeSpotify.html(request, """{"results":[]}""")
            }
        }
        val file = File.createTempFile("lyrics", ".json").apply { delete() }
        fun finder() = LyricsFinder(fake.client(), "2.3.3", busyPauseMs = 0, cache = LookupCache(file, Dispatchers.Unconfined))
        fun look() = runBlocking { finder().firstLook(MusicMetadata("Song", "Band", lengthMs = 308_600)).lyrics }
        // Every copy is timed for a shorter take, so the closest is what that recording gets, and what's kept for it.
        val first = look() as Lyrics.Found
        assertEquals(295_000L, first.lengthMs)
        val asked = fake.requestedUrls.size
        assertTrue(asked > 0)
        assertEquals(first, look())
        assertEquals(asked, fake.requestedUrls.size)
    }

    @Test
    fun ofSeveralRecordingsOnNetEaseTheOneAsLongAsTheSongPlayingIsTaken() {
        fake.handler = { request ->
            val url = request.url
            when {
                url.host == "lrclib.net" -> FakeSpotify.html(request, twoTakes)
                url.encodedPath == "/api/search/get" -> FakeSpotify.html(
                    request,
                    """{"result":{"songs":[{"id":1,"name":"Song","artists":[{"name":"Band"}],"duration":295000},{"id":2,"name":"Song","artists":[{"name":"Band"}],"duration":308500}]}}"""
                )
                url.encodedPath == "/api/song/lyric" -> {
                    val take = if (url.queryParameter("id") == "2") "Long" else "Short"
                    FakeSpotify.html(request, """{"lrc":{"lyric":${quote("[00:02.000]$take one\n[00:12.000]$take two")}}}""")
                }
                else -> FakeSpotify.html(request, """{"results":[]}""")
            }
        }
        // LRCLIB's words are timed for a recording 13 seconds shorter than this one, so NetEase's own take of it is taken.
        val long = found(MusicMetadata("Song", "Band", lengthMs = 308_600)) as Lyrics.Found
        assertEquals(listOf(LyricLine(2_000, "Long one"), LyricLine(12_000, "Long two")), long.lines)
        assertEquals(308_500L, long.lengthMs)
        // As long as LRCLIB's, they stay.
        val short = found(MusicMetadata("Song", "Band", lengthMs = 295_400)) as Lyrics.Found
        assertEquals(listOf(LyricLine(1_000, "First take one"), LyricLine(10_000, "First take two")), short.lines)
        // With no length to go by, there's no telling LRCLIB's aren't right.
        assertEquals(295_000L, (found(MusicMetadata("Song", "Band")) as Lyrics.Found).lengthMs)
        // Nothing as long anywhere: LRCLIB's stay, as the best there is.
        val none = found(MusicMetadata("Song", "Band", lengthMs = 340_000)) as Lyrics.Found
        assertEquals(295_000L, none.lengthMs)
        // The store knowing it as long as LRCLIB's take doesn't make that the one playing.
        val others = fake.handler
        fake.handler = { request ->
            if (request.url.host == "itunes.apple.com" && request.url.encodedPath == "/search") FakeSpotify.html(request, """{"results":[{"trackId":7,"trackName":"Song","artistName":"Band","trackTimeMillis":295000}]}""")
            else others(request)
        }
        assertEquals(308_500L, (found(MusicMetadata("Song", "Band", lengthMs = 308_600)) as Lyrics.Found).lengthMs)
    }
}
