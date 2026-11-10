package com.astrovm.crosstune

import com.astrovm.crosstune.LyricsFinder.Lyrics
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
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

    /** LRCLIB, which has [songs], by song and artist, each with its own words, and finds them by any of their words. */
    private fun lrclibHas(vararg songs: Pair<String, String>, searches: MutableList<String> = mutableListOf()) {
        fun json(found: List<Pair<String, String>>) = found.joinToString(",", "[", "]") { (title, artist) -> answer(title, artist, "Words of $title") }
        fake.handler = { request ->
            val url = request.url
            val said = url.queryParameter("q")
            FakeSpotify.html(request, when {
                url.host != "lrclib.net" -> "{}"
                said != null -> json(songs.filter { (title, artist) -> said.split(' ').all { it in "$title $artist" } }).also { searches += said }
                else -> json(songs.filter { it == url.queryParameter("track_name") to url.queryParameter("artist_name") })
            })
        }
    }

    @Test
    fun aVideosTitleThatNamesTheArtistAndSongFindsTheirWords() {
        lrclibHas("夜の歌" to "FakeBand", "Lonely Road" to "Other Singer")
        // A fan's upload, by a channel that isn't the artist, either way around.
        assertEquals("Words of 夜の歌", words(MusicMetadata("FakeBand 夜の歌", "Fan Pics")))
        assertEquals("Words of 夜の歌", words(MusicMetadata("夜の歌 FakeBand", "Fan Pics")))
        // A song LRCLIB has by that name alone is someone else's: the title isn't theirs and their song.
        assertEquals(Lyrics.None, found(MusicMetadata("Lonely Road", "Fan Pics")))
        assertEquals(Lyrics.None, found(MusicMetadata("Lonely Road Other", "Fan Pics")))
    }

    @Test
    fun aLabelsTitleInPartsFindsTheSongByWhicheverPartIsItsSinger() {
        val searches = mutableListOf<String>()
        lrclibHas("Fake Tune" to "Fake Singer", "Fake Tune" to "Fake Film", searches = searches)
        assertEquals("Words of Fake Tune", words(MusicMetadata("Fake Tune | Fake Singer | Some Film | Old Song", "Fake Label")))
        // The singer named after the film is found too; one past the parts asked about isn't.
        lrclibHas("Fake Tune" to "Fake Singer", searches = searches)
        assertEquals("Words of Fake Tune", words(MusicMetadata("Fake Tune | Other Film | Fake Singer", "Fake Label")))
        searches.clear()
        assertEquals(Lyrics.None, found(MusicMetadata("Fake Tune | A | B | C | Fake Singer", "Fake Label")))
        assertEquals(listOf("Fake Tune A", "Fake Tune B", "Fake Tune C"), searches)
    }

    @Test
    fun aBlankPartInALabelsTitleIsSkippedAndTheSongIsStillFound() {
        fake.handler = { request ->
            val url = request.url
            val said = url.queryParameter("q")
            val track = url.queryParameter("track_name") to url.queryParameter("artist_name")
            when {
                url.host != "lrclib.net" -> FakeSpotify.html(request, "{}")
                said == "Fake Tune Fake Singer" || track == "Fake Tune" to "Fake Singer" -> FakeSpotify.html(request, "[${answer("Fake Tune", "Fake Singer", "Words of Fake Tune")}]")
                // A blank part would ask for the song with no singer, and that answer is no use.
                said == "Fake Tune" || said?.endsWith(" ") == true -> FakeSpotify.html(request, "", code = 503)
                else -> FakeSpotify.html(request, "[]")
            }
        }
        assertEquals("Words of Fake Tune", words(MusicMetadata("Fake Tune |  | Fake Singer", "Fake Label")))
    }

    @Test
    fun aTitleThatNamesTheSingerFirstSkipsADifferentSong() {
        fake.handler = { request ->
            val url = request.url
            val said = url.queryParameter("q")
            val track = url.queryParameter("track_name") to url.queryParameter("artist_name")
            when {
                url.host != "lrclib.net" -> FakeSpotify.html(request, "{}")
                // Another song comes back first. It is not this one, so its words stay out.
                said == "Fake Singer Fake Tune" -> FakeSpotify.html(request, "[${answer("Other Tune", "Other Band", "Words of Other")},${answer("Fake Tune", "Fake Singer", "Words of Fake Tune")}]")
                track == "Fake Tune" to "Fake Singer" -> FakeSpotify.html(request, "[${answer("Fake Tune", "Fake Singer", "Words of Fake Tune")}]")
                track == "Other Tune" to "Other Band" -> FakeSpotify.html(request, "[${answer("Other Tune", "Other Band", "Words of Other")}]")
                else -> FakeSpotify.html(request, "[]")
            }
        }
        assertEquals("Words of Fake Tune", words(MusicMetadata("Fake Singer | Fake Tune", "Fake Label")))
    }

    @Test
    fun aSearchLrclibWontAnswerLeavesTheSongWithoutWords() {
        fake.handler = { request ->
            val url = request.url
            when {
                url.queryParameter("q") != null -> FakeSpotify.html(request, "", code = 503)
                url.host == "lrclib.net" -> FakeSpotify.html(request, "[]")
                else -> FakeSpotify.html(request, "{}")
            }
        }
        assertEquals(Lyrics.None, found(MusicMetadata("FakeBand 夜の歌", "Fan Pics")))
        fake.handler = { request -> FakeSpotify.html(request, if (request.url.queryParameter("q") != null) "{\"message\":\"busy\"}" else "[]") }
        assertEquals(Lyrics.None, found(MusicMetadata("FakeBand 夜の歌", "Fan Pics")))
        // An answer cut short is no answer either.
        fake.handler = { request -> FakeSpotify.html(request, if (request.url.queryParameter("q") != null) "[{\"trackName\":" else "[]") }
        assertEquals(Lyrics.None, found(MusicMetadata("FakeBand 夜の歌", "Fan Pics")))
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
    fun ofTimedCopiesOfDifferentLengthsTheOneAsLongAsItWasHeardIsTaken() {
        fun copy(words: String, seconds: Int) =
            """{"trackName":"Song","artistName":"Band","duration":$seconds,"plainLyrics":"$words","syncedLyrics":"[00:01.00] $words\n[00:05.00] $words again"}"""
        fake.handler = { request -> FakeSpotify.html(request, "[${copy("Live", 312)},${copy("Studio", 245)}]") }
        assertEquals("Studio", words(MusicMetadata("Song", "Band", heardLengthMs = 245_300)))
        assertTrue(fake.requestedUrls.none { "itunes.apple.com" in it })
        // The app playing it knows its own recording better than a hearing does.
        assertEquals("Live", words(MusicMetadata("Song", "Band", lengthMs = 311_000, heardLengthMs = 245_300)))
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

    /** The iTunes Store's songs named [title], as it answered a search for it alone, trimmed to what's read. */
    private fun storeSongs(title: String) = javaClass.getResource("/itunes/${title.lowercase().replace(' ', '-')}.json")!!.readText()

    /** LRCLIB with [words] for songs by [artist] alone, the iTunes Store as it answered for [title]; nothing anywhere else. */
    private fun onlyBy(artist: String, title: String, words: String) {
        fake.handler = { request ->
            val asked = request.url.toString()
            when {
                request.url.host == "itunes.apple.com" && request.url.queryParameter("term") == title -> FakeSpotify.html(request, storeSongs(title))
                request.url.host == "itunes.apple.com" -> FakeSpotify.html(request, """{"results":[]}""")
                request.url.host == "lrclib.net" && (artist.replace(" ", "+") in asked || artist.replace(" ", "%20") in asked) -> FakeSpotify.html(request, "[$words]")
                else -> FakeSpotify.html(request, "[]")
            }
        }
    }

    @Test
    fun aCoverWithNoWordsOfItsOwnHasTheOriginals() {
        // Auto report #21: an instrumental cover no one keeps words for, of a song whose first singer most of the store's songs of that name are by.
        val evanescence = lasting(answer("Bring Me to Life", "Evanescence", "Wake me up inside", synced = "[00:20.00]Wake me up inside"), 235.0)
        onlyBy("Evanescence", "Bring Me to Life", evanescence)
        val cover = MusicMetadata("Bring Me to Life (Cyberpunk Shamisen Cover)", "TOKYO SAMURAI SYNDICATE", lengthMs = 201_000)
        // The words, untimed, as they're timed for the original, which isn't as long, asked for by the original's name.
        assertEquals(Lyrics.Found("Wake me up inside", lengthMs = 235_000, source = "lrclib, the original's"), found(cover))
        assertTrue(fake.requestedUrls.any { it.startsWith("https://lrclib.net/api/search?track_name=Bring%20Me%20to%20Life&artist_name=Evanescence") })
        // Timed too, when it's as long.
        assertEquals(listOf(LyricLine(20_000, "Wake me up inside")), (found(cover.copy(lengthMs = 235_500)) as Lyrics.Found).lines)
        // A karaoke track names whose song it is.
        onlyBy("Evanescence", "nothing", evanescence)
        assertEquals("Wake me up inside", words(MusicMetadata("Bring Me to Life (In the Style of Evanescence) [Karaoke Version]", "Ameritz Karaoke")))
        // A title that doesn't say it's a cover is someone's own song.
        onlyBy("Evanescence", "Bring Me to Life", evanescence)
        assertEquals(Lyrics.None, found(MusicMetadata("Bring Me to Life", "TOKYO SAMURAI SYNDICATE")))
        // The store's answer cut off, whose song it covers isn't known.
        val store = fake.handler
        fake.handler = { request -> if (request.url.host == "itunes.apple.com") FakeSpotify.html(request, """{"results":[{"trackName":""") else store(request) }
        assertEquals(Lyrics.None, found(cover))
        // Nor with the store down.
        fake.handler = { request -> if (request.url.host == "itunes.apple.com") FakeSpotify.html(request, "", code = 503) else store(request) }
        assertEquals(Lyrics.None, found(cover))
    }

    @Test
    fun theOriginalsSingerIsTheOneMostOfTheStoresSongsOfThatNameAreBy() {
        val cover = MusicMetadata("Song (Piano Cover)", "Pianist")
        /** The store's songs named "Song", so many by each artist, and LRCLIB's words by Band alone. */
        fun store(vararg byArtist: Pair<String, Int>): Boolean {
            var id = 0
            val songs = byArtist.flatMap { (artist, count) -> List(count) { """{"trackId":${++id},"trackName":"Song","artistName":"$artist"}""" } }
            onlyBy("Band", "Song", answer("Song", "Band", "La la la"))
            val lrclib = fake.handler
            fake.handler = { request -> if (request.url.host == "itunes.apple.com") FakeSpotify.html(request, """{"results":[${songs.joinToString(",")}]}""") else lrclib(request) }
            return found(cover) is Lyrics.Found
        }
        // Five of them are enough, with no one else singing it.
        assertTrue(store("Band" to 5))
        assertFalse(store("Band" to 4))
        // Someone else singing it too, three times as many of theirs.
        assertTrue(store("Band" to 6, "Other" to 2))
        assertFalse(store("Band" to 5, "Other" to 2))
    }

    /** Apple Music's credits for the store's song [id], as its page lists them. */
    private fun credits(id: Long) = javaClass.getResource("/applemusic/$id.html")!!.readText()

    @Test
    fun aCoverItsTitleDoesntSayIsOneHasTheOriginalsWordsWhenWrittenBySame() {
        // Auto report #11: Marc Strain's "Smalltown Boy" is Bronski Beat's song, which most of the store's songs of that name are by.
        onlyBy("Bronski Beat", "Smalltown Boy", lasting(answer("Smalltown Boy", "Bronski Beat", "Run away, turn away", synced = "[00:30.00]Run away, turn away"), 302.0))
        val store = fake.handler
        var ownCredits = credits(MARC_STRAIN)
        fake.handler = { request ->
            when {
                request.url.queryParameter("term") == "Smalltown Boy Marc Strain" ->
                    FakeSpotify.html(request, """{"results":[{"trackId":$MARC_STRAIN,"trackName":"Smalltown Boy","artistName":"Marc Strain","trackTimeMillis":205054}]}""")
                request.url.toString() == "${LyricsFinder.APPLE_MUSIC_SONG_URL}$MARC_STRAIN" -> FakeSpotify.html(request, ownCredits)
                request.url.toString() == "${LyricsFinder.APPLE_MUSIC_SONG_URL}$BRONSKI_BEAT" -> FakeSpotify.html(request, credits(BRONSKI_BEAT))
                else -> store(request)
            }
        }
        val cover = MusicMetadata("Smalltown Boy", "Marc Strain", lengthMs = 205_054)
        // Apple credits both to Jimmy Somerville and the rest, so it's a cover: the words, untimed, as the original is longer.
        assertEquals(Lyrics.Found("Run away, turn away", lengthMs = 302_000, source = "lrclib, the original's"), found(cover))
        // Written by someone else, it's their own song of that name, as Haley Reinhart's "Creep" isn't Stone Temple Pilots'.
        ownCredits = """{"name":"Marc Strain","roleNames":["Songwriter"]}"""
        assertEquals(Lyrics.None, found(cover))
        // Apple saying nothing of who wrote it, there's no telling: a singer of the original, credited for singing, isn't a writer.
        ownCredits = """{"name":"Jimmy Somerville","roleNames":["Vocals"]}"""
        assertEquals(Lyrics.None, found(cover))
        // Nor with Apple's page down.
        val pages = fake.handler
        fake.handler = { request -> if (request.url.host == "music.apple.com") FakeSpotify.html(request, "", code = 503) else pages(request) }
        ownCredits = credits(MARC_STRAIN)
        assertEquals(Lyrics.None, found(cover))
    }

    @Test
    fun aCoverSayingSoHasTheWordsOfASingerNotFarAheadWhenWrittenBySame() {
        // A store's count can come too close to tell by itself, here five to two, as it has for a reported song.
        val cover = MusicMetadata("Song (Shamisen Cover)", "Trio")
        onlyBy("Band", "Song", answer("Song", "Band", "La la la"))
        val lrclib = fake.handler
        val songs = (List(5) { """{"trackId":${100 + it},"trackName":"Song","artistName":"Band"}""" } + List(2) { """{"trackId":${200 + it},"trackName":"Song","artistName":"Other"}""" }).joinToString(",")
        var ownCredits = """{"name":"First Writer","roleNames":["Songwriter"]}"""
        fake.handler = { request ->
            val own = """{"results":[{"trackId":300,"trackName":"Song (Shamisen Cover)","artistName":"Trio"}]}"""
            when {
                request.url.queryParameter("term") == "Song (Shamisen Cover) Trio" -> FakeSpotify.html(request, own)
                request.url.queryParameter("term") == "Song" -> FakeSpotify.html(request, """{"results":[$songs]}""")
                request.url.toString() == "${LyricsFinder.APPLE_MUSIC_SONG_URL}300" -> FakeSpotify.html(request, ownCredits)
                request.url.toString() == "${LyricsFinder.APPLE_MUSIC_SONG_URL}100" -> FakeSpotify.html(request, """{"name":"First Writer","roleNames":["Songwriter"]}""")
                else -> lrclib(request)
            }
        }
        assertEquals(Lyrics.Found("La la la", source = "lrclib, the original's"), found(cover))
        // Written by others, it covers someone else's song of that name.
        ownCredits = """{"name":"Another Writer","roleNames":["Songwriter"]}"""
        assertEquals(Lyrics.None, found(cover))
    }

    @Test
    fun aCoverOfANameManySongsShareHasNoOnesWords() {
        // The store's songs named "Hello" are by many, none far ahead, Lionel Richie's 4 to Aqyila's 3: it's not known whose this covers.
        onlyBy("Adele", "Hello", answer("Hello", "Adele", "Hello, it's me"))
        assertEquals(Lyrics.None, found(MusicMetadata("Hello (Acoustic Cover)", "Someone")))
        onlyBy("Lionel Richie", "Hello", answer("Hello", "Lionel Richie", "Is it me you're looking for"))
        assertEquals(Lyrics.None, found(MusicMetadata("Hello (Acoustic Cover)", "Someone")))
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
    fun offlineAnotherRecordingsKeptWordsShow() {
        val file = File.createTempFile("lyrics", ".json").apply { delete(); deleteOnExit() }
        val kept = LyricsFinder(fake.client(), "2.3.3", busyPauseMs = 0, cache = LookupCache(file, Dispatchers.Unconfined))
        respond("""[${answer("Song", "Band", "First\nSecond", "[00:01.00] First\n[00:03.00] Second")}]""")
        val first = runBlocking { kept.lyricsOf(MusicMetadata("Song", "Band", lengthMs = 200_000)) }
        // Played from another app, as another recording, with nothing reachable.
        fake.handler = { throw IOException("offline") }
        assertEquals(first, runBlocking { kept.lyricsOf(MusicMetadata("song", "BAND", lengthMs = 201_000, recordingId = "app:7")) })
        // Not another song whose name starts the same.
        assertEquals(Lyrics.Unavailable, runBlocking { kept.lyricsOf(MusicMetadata("Song", "Ban")) })
        // Reachable, the recording's own are looked up.
        respond("""[${answer("Song", "Band", "Own words")}]""")
        assertEquals("Own words", (runBlocking { kept.lyricsOf(MusicMetadata("Song", "Band", lengthMs = 205_000)) } as Lyrics.Found).words)
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
    private fun japaneseSong(lrclib: (String) -> String = { "[]" }, ownNames: Boolean = true, netEase: (String) -> String = { """{"result":{}}""" }, lyric: String = "", pureMusic: Boolean = false) {
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
                else -> FakeSpotify.html(request, """{"lrc":{"lyric":${quote(lyric)}},"pureMusic":$pureMusic}""")
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

    private val loudLines = "WE WALK WITH MIRA THROUGH THE NIGHT\nWE WAIT BY THE RIVER UNTIL DAWN"
    private val namedLines = "We walk\nWith Mira through the night\nWe wait by the river until dawn"
    private val recasedLines = "We walk with Mira through the night\nWe wait by the river until dawn"

    /** NetEase has the song, as [lyric] says; elsewhere [others] answers. */
    private fun onNetEase(lyric: String, others: (Request) -> Response) {
        fake.handler = { request ->
            when {
                request.url.host != "music.163.com" -> others(request)
                request.url.encodedPath == "/api/search/get" -> FakeSpotify.html(request, """{"result":{"songs":[{"id":2,"name":"Song","artists":[{"name":"Band"}]}]}}""")
                else -> FakeSpotify.html(request, lyric)
            }
        }
    }

    private val loudByWord = """{"yrc":{"lyric":${quote(loudLines.lines().mapIndexed { i, text ->
        "[${i * 10_000},9000]" + text.split(" ").mapIndexed { j, word -> "(${i * 10_000 + j * 500},500,0)$word " }.joinToString("")
    }.joinToString("\n"))}}}"""

    @Test
    fun capitalsBorrowNamesFromTheCopyAlreadyFetchedWithoutBorrowingLineStarts() {
        // LRCLIB's words have no timings, so they give way to NetEase's, in capitals, and are only a copy to learn from.
        var searches = 0
        onNetEase(loudByWord) { request ->
            if (request.url.host == "lrclib.net") {
                searches++
                FakeSpotify.html(request, "[${answer("Song", "Band", namedLines)}]")
            } else FakeSpotify.html(request, """{"results":[]}""")
        }
        val result = found(song) as Lyrics.Found
        assertEquals(recasedLines, result.words)
        assertEquals(result.words.lines(), result.lines.map { it.text.trim() })
        assertEquals("Mira ", result.lines.first().words[3].text)
        // Nothing more is asked for to learn it.
        assertEquals(1, searches)
        assertEquals(1, fake.requestedUrls.count { "/api/song/lyric" in it })
    }

    @Test
    fun capitalsBorrowNamesFromNetEaseWhenItIsntTheOneChosen() {
        val loud = loudLines.lines().mapIndexed { i, line -> "[00:${i + 10}.00]$line" }.joinToString("\n")
        val timed = namedLines.lines().mapIndexed { i, line -> "[00:${i + 10}.00]$line" }.joinToString("\n")
        onNetEase("""{"lrc":{"lyric":${quote(timed)}}}""") { request ->
            if (request.url.host == "lrclib.net") FakeSpotify.html(request, "[${answer("Song", "Band", loudLines, synced = loud)}]")
            else FakeSpotify.html(request, """{"results":[]}""")
        }
        val result = found(song) as Lyrics.Found
        assertEquals("lrclib", result.source)
        assertEquals(recasedLines, result.words)
    }

    @Test
    fun capitalsBorrowNamesFromYouTubeMusicWhenItIsntTheOneChosen() {
        everywhere("[]", youTubeMusicPage("We walk" to 1_000L, "With Mira through the night" to 12_000L, "We wait by the river until dawn" to 23_000L))
        val others = fake.handler
        onNetEase(loudByWord, others)
        val result = runBlocking { withYouTubeMusic().lyricsOf(song) } as Lyrics.Found
        assertEquals("netease", result.source)
        assertEquals(recasedLines, result.words)
    }

    @Test
    fun aCoverInCapitalsBorrowsNamesFromTheOriginalsCopies() {
        val cover = MusicMetadata("Song (In the Style of Band) [Karaoke Version]", "Ameritz Karaoke", lengthMs = 201_000)
        fake.handler = { request ->
            val asked = request.url.toString()
            when {
                request.url.host == "itunes.apple.com" -> FakeSpotify.html(request, """{"results":[]}""")
                request.url.host == "lrclib.net" && "artist_name=Band" in asked -> FakeSpotify.html(request, "[${answer("Song", "Band", namedLines)}]")
                request.url.host == "lrclib.net" -> FakeSpotify.html(request, "[]")
                request.url.encodedPath == "/api/search/get" && "Band" in asked ->
                    FakeSpotify.html(request, """{"result":{"songs":[{"id":2,"name":"Song","artists":[{"name":"Band"}]}]}}""")
                request.url.encodedPath == "/api/song/lyric" -> FakeSpotify.html(request, loudByWord)
                else -> FakeSpotify.html(request, """{"result":{"songs":[]}}""")
            }
        }
        val result = found(cover) as Lyrics.Found
        assertEquals("netease, the original's", result.source)
        assertEquals(recasedLines, result.words)
    }

    @Test
    fun capitalsWithNoOtherCopyStayInSentenceCase() {
        onNetEase(loudByWord) { request -> FakeSpotify.html(request, if (request.url.host == "lrclib.net") "[]" else """{"results":[]}""") }
        assertEquals("We walk with mira through the night\nWe wait by the river until dawn", (found(song) as Lyrics.Found).words)
    }

    @Test
    fun halfTheMatchingLinesCannotOutvoteTwoDifferentWordBreaks() {
        val copy = Lyrics.Found("Bird song\nBird song\nBir d song")
        val chosen = Lyrics.Found("Birdsong")
        assertEquals(chosen, LyricsFinder.cleaned(chosen, song, listOf(copy)))
    }

    @Test
    fun repeatedOtherWordsCannotSupplySpacesForThisLine() {
        val text = "Birdsong"
        val copy = Lyrics.Found("Bird wing\nBird wing")
        assertEquals(text, LyricsFinder.cleaned(Lyrics.Found(text), song, listOf(copy)).words)
    }

    @Test
    fun aWholeCapitalSongAlsoNormalizesItsSingleWordLines() {
        val text = "WE WALK THROUGH THE NIGHT TOGETHER\nHELLO"
        val lines = text.lines().mapIndexed { index, line -> LyricLine(index * 1000L, line) }
        val result = LyricsFinder.cleaned(Lyrics.Found(text, lines), song)
        assertEquals("We walk through the night together\nHello", result.words)
        assertEquals(result.words.lines(), result.lines.map { it.text })
    }

    @Test
    fun capitalTimingLinesAreCleanedEvenWithoutPlainWords() {
        val result = LyricsFinder.cleaned(Lyrics.Found("", listOf(LyricLine(1000, "WE WALK THROUGH THE NIGHT"))), song)
        assertEquals("", result.words)
        assertEquals(listOf(LyricLine(1000, "We walk through the night")), result.lines)
    }

    @Test
    fun oneCopysCompoundWordPreferenceDoesNotOverrideTheChosenWords() {
        val text = "We wait in the staffroom\nWe lay out the tablecloth"
        val copy = Lyrics.Found("We wait in the staff room\nWe lay out the table cloth")
        assertEquals(text, LyricsFinder.cleaned(Lyrics.Found(text), song, listOf(copy, copy)).words)
    }

    @Test
    fun spelledOutAcronymsKeepTheirCapitalsBesideACapitalPhrase() {
        val text = "We stay at the Y M C A\nWe meet NASA tonight\nWe meet Mira AND WALK HOME"
        assertEquals("We stay at the Y M C A\nWe meet NASA tonight\nWe meet Mira and walk home",
            LyricsFinder.cleaned(Lyrics.Found(text), song).words)
    }

    @Test
    fun nearlyCapitalCopiesCannotSupplyBrokenCapitals() {
        val text = "MESSAGE IN A BOTTLE\nMESSAGE IN A BOTTLe\nMESSAGE IN A BOTTLE"
        assertEquals("Message in a bottle\nMessage in a bottle\nMessage in a bottle",
            LyricsFinder.cleaned(Lyrics.Found(text), song).words)
    }

    @Test
    fun newSpacesDoNotSeparatePunctuationFromItsWord() {
        val text = "Blast off, we stay here\nRadio what is new?"
        val copy = Lyrics.Found(List(2) { "Blast off , we stay here\nRadio what is new ?" }.joinToString("\n"))
        assertEquals(text, LyricsFinder.cleaned(Lyrics.Found(text), song, listOf(copy)).words)
    }

    @Test
    fun savedSentenceCaseIsStillReadableOfflineDuringTheRefresh() {
        val file = File.createTempFile("lyrics-old-offline", ".json").apply { delete(); deleteOnExit() }
        val cache = LookupCache(file, Dispatchers.Unconfined)
        runBlocking { cache.put("15\u0000${RecordingKey.of(song)}", """{"words":"We walk with mira through the night","lines":[],"provisional":false}""") }
        fake.handler = { throw IOException("offline") }
        val result = runBlocking { LyricsFinder(fake.client(), "test", cache = cache, busyPauseMs = 0).lyricsOf(song) } as Lyrics.Found
        assertEquals("We walk with mira through the night", result.words)
        file.delete()
    }

    @Test
    fun savedSentenceCaseFromBeforeNameRestorationIsRefreshed() {
        val file = File.createTempFile("lyrics-old-case", ".json").apply { delete(); deleteOnExit() }
        val cache = LookupCache(file, Dispatchers.Unconfined)
        runBlocking {
            cache.put("15\u0000${RecordingKey.of(song)}", """{"words":"We walk with mira through the night","lines":[],"provisional":false}""")
            cache.save()
        }
        respond("[${answer("Song", "Band", "We walk with Mira through the night")}]")
        val finder = LyricsFinder(fake.client(), "test", cache = LookupCache(file, Dispatchers.Unconfined), busyPauseMs = 0)
        assertEquals("We walk with Mira through the night", (runBlocking { finder.lyricsOf(song) } as Lyrics.Found).words)
        file.delete()
    }

    @Test
    fun ownCapitalWordsWaitForLateCopiesWithoutLosingTheirTimings() {
        val named = "We walk with Mira through the night"
        everywhere("[${answer("Song", "Band", named)}]", youTubeMusicPage(named.uppercase() to 1000L))
        val others = fake.handler
        fake.handler = { request ->
            if (request.url.host == "lrclib.net") Thread.sleep(100)
            others(request)
        }
        val video = song.copy(url = "https://music.youtube.com/watch?v=abcdefghijk")
        val result = runBlocking { withYouTubeMusic().firstLook(video) }
        assertTrue(result.final)
        assertEquals(named, (result.lyrics as Lyrics.Found).words)
        assertEquals(1000L, result.lyrics.lines.single().timeMs)
    }

    @Test
    fun aCapitalLineReadsLikeItsNeighborsAndKeepsTheirNames() {
        val text = "We walk with Mira through the night\nWE WALK WITH MIRA THROUGH THE NIGHT\nWe send NASA a message tonight"
        val found = Lyrics.Found(text, text.lines().mapIndexed { i, line ->
            LyricLine(i * 1000L, line, listOf(TimedWord(i * 1000L, line)))
        })
        val result = LyricsFinder.cleaned(found, song)
        val expected = "We walk with Mira through the night\nWe walk with Mira through the night\nWe send NASA a message tonight"
        assertEquals(expected, result.words)
        assertEquals(expected.lines(), result.lines.map { it.text })
        assertEquals(expected.lines(), result.lines.map { it.words.single().text })
        assertEquals(result, LyricsFinder.cleaned(result, song))
    }

    @Test
    fun spacingNeverChangesLettersPunctuationOrExistingWordBreaks() {
        fun clean(text: String, copy: String) = LyricsFinder.cleaned(Lyrics.Found(text), song, listOf(Lyrics.Found(copy))).words
        assertEquals("Send me a letter", clean("Sendme a letter", "Send me a letter\nSend me a letter"))
        assertEquals("Send me a letter", clean("Send me a letter", "Sendme a letter"))
        assertEquals("Sendme a letter", clean("Sendme a letter", "Send me a letter!"))
        assertEquals("Sendme a letter", clean("Sendme a letter", "Send me another letter"))
        assertEquals("", clean("", ""))
        assertEquals("五月雨は緑色", clean("五月雨は緑色", "五月雨は緑色"))
        assertEquals("Sendm e a letter", clean("Sendm e a letter", "Send me a le tter\nSend me a le tter"))
        assertEquals("Send\tme a letter", clean("Send\tme a letter", "Send me a letter"))
        assertEquals("Sendme a letter", clean("Sendme a letter", "Send me\na letter"))
    }

    @Test
    fun timedTextCanLearnItsSpacesFromItsOwnPlainWords() {
        assertEquals("Mira put on the red light", LyricsFinder.cleaned(Lyrics.Found("Mira PUT ON THE RED LIGHT"), song).words)
        val plain = "Send me a letter"
        val line = LyricLine(1000, "Sendme a letter", listOf(TimedWord(1000, "Sendme "), TimedWord(2000, "a letter")))
        val result = LyricsFinder.cleaned(Lyrics.Found(plain, listOf(line)), song)
        assertEquals(plain, result.lines.single().text)
        assertEquals(listOf(TimedWord(1000, "Send me "), TimedWord(2000, "a letter")), result.lines.single().words)
        assertEquals(plain, result.words)
    }

    @Test
    fun missingSpacesComeFromACopyWithTheSameLettersAndKeepWordTimes() {
        val line = LyricLine(1000, "SENDME A LETTER", listOf(TimedWord(1000, "SEND"), TimedWord(2000, "ME A LETTER")))
        val found = Lyrics.Found(line.text, listOf(line))
        val copy = Lyrics.Found("Send me a letter\nSend me a letter")
        val result = LyricsFinder.cleaned(found, song, listOf(copy))
        assertEquals("Send me a letter", result.words)
        assertEquals("Send me a letter", result.lines.single().text)
        assertEquals(listOf(TimedWord(1000, "Send"), TimedWord(2000, " me a letter")), result.lines.single().words)
        assertEquals(result, LyricsFinder.cleaned(result, song))
    }

    @Test
    fun restoredCapitalsKeepSplitWordTimingsAndSurviveCleaningAgain() {
        val text = "WE MEET MIRA BESIDE THE RIVER TONIGHT"
        val line = LyricLine(1000, text, listOf(TimedWord(1000, "WE MEET MI"), TimedWord(2000, "RA BESIDE THE RIVER TONIGHT")))
        val copy = Lyrics.Found("We meet Mira beside the river tonight")
        val result = LyricsFinder.cleaned(Lyrics.Found(text, listOf(line)), song, listOf(copy))
        assertEquals("We meet Mira beside the river tonight", result.words)
        assertEquals(result.words, result.lines.single().text)
        assertEquals(listOf(TimedWord(1000, "We meet Mi"), TimedWord(2000, "ra beside the river tonight")), result.lines.single().words)
        assertEquals(result, LyricsFinder.cleaned(result, song))
        assertEquals(copy, LyricsFinder.cleaned(copy, song, listOf(Lyrics.Found(text))))
        val plain = LyricsFinder.cleaned(Lyrics.Found(text), song, listOf(copy))
        assertEquals(result.words, plain.words)
        assertTrue(plain.lines.isEmpty())
    }

    @Test
    fun aSongInCapitalsReadsInSentenceCaseWordsAndAllAndItsSpacesBreak() {
        val nb = "\u00a0"
        fun line(ms: Long, vararg words: String) = LyricLine(ms, words.joinToString(nb), words.mapIndexed { i, word -> TimedWord(ms + i * 300L, if (i < words.size - 1) word + nb else word) })
        val lines = listOf(
            line(12_490, "THOUGH", "I'VE", "TRIED", "BEFORE.", "I'M", "HERE"),
            line(15_460, "OF", "THE", "FEELING", "I", "HAVE", "FOR", "HER"),
            line(17_980, "EVERY", "LITTLE", "THING", "SHE", "DOES", "IS", "MAGIC")
        )
        val found = LyricsFinder.cleaned(Lyrics.Found(lines.joinToString("\n") { it.text }, lines = lines, source = LyricsFinder.NETEASE), MusicMetadata("Every Little Thing She Does Is Magic", "The Police"))
        val said = listOf("Though I've tried before. I'm here", "Of the feeling I have for her", "Every little thing she does is magic")
        assertEquals(said, found.lines.map { it.text })
        assertEquals(said.joinToString("\n"), found.words)
        assertEquals(listOf("Though ", "I've ", "tried ", "before. ", "I'm ", "here"), found.lines[0].words.map { it.text })
        // A song that only shouts a line, any site's, keeps it as it is.
        val shout = listOf("Walking down the road", "HEY!", "Nobody calls me now")
        assertEquals(shout.joinToString("\n"), LyricsFinder.cleaned(Lyrics.Found(shout.joinToString("\n"), source = "lrclib"), MusicMetadata("Road", "Band")).words)
        // Nor does a song with no capitals to speak of change.
        assertEquals("五月雨は緑色", LyricsFinder.cleaned(Lyrics.Found("五月雨は緑色", source = "lrclib"), MusicMetadata("Satsuki", "Band")).words)
    }

    @Test
    fun aNameNetEaseHeadsASongWithAloneIsLeftOutWhenCreditsFollowIt() {
        val sung = listOf("Rain on the window", "Nobody calls")
        fun cleaned(song: MusicMetadata, vararg around: String, after: List<String> = emptyList()) =
            LyricsFinder.cleaned(Lyrics.Found((around.toList() + sung + after).joinToString("\n"), source = LyricsFinder.NETEASE), song).words
        val words = sung.joinToString("\n")
        // The name alone, then who wrote it, in any of the ways NetEase's contributors put it.
        assertEquals(words, cleaned(MusicMetadata("Calm Night", "The Band"), "CALM NIGHT", "words:Someone music:The Band"))
        assertEquals(words, cleaned(MusicMetadata("Calm Night", "The Band"), "CALM NIGHT", "（Some Writer / Another Writer）", "The Band（1972）"))
        assertEquals(words, cleaned(MusicMetadata("Calm Night", "The Band"), "\"Calm Night\"", "（Who is this, the night asks?）", "BY THEBANDOFFICIAL"))
        // A credit in Chinese with no colon, and the artist alone, as the first line.
        assertEquals(words, cleaned(MusicMetadata("Calm Night", "The Band"), "编曲 Someone"))
        assertEquals(words, cleaned(MusicMetadata("Calm Night", "The Band"), "The Band"))
        // The heading at both ends, the artist written with "And" for "+".
        assertEquals(words, cleaned(MusicMetadata("Calm Night", "Florence + the Band"), "Florence And The Band - Calm Night", after = listOf("Florence And The Band - Calm Night")))
        // What a contributor ends it with.
        for (end in listOf("END", "End", "THE END", "music...")) assertEquals(words, cleaned(MusicMetadata("Calm Night", "The Band"), after = listOf(end)))
        // The name alone, then the song, is sung.
        assertEquals((listOf("Calm Night") + sung).joinToString("\n"), cleaned(MusicMetadata("Calm Night", "The Band"), "Calm Night"))
        // A credit in English, the name and the artist with blank lines between them.
        assertEquals(words, cleaned(MusicMetadata("Calm Night", "The Band"), "Mixed by: Someone", "", "Calm Night", "", "The Band", ""))
        assertEquals(words, cleaned(MusicMetadata("Calm Night", "The Band"), "Mixed by: Someone", "", "Mastered by: Another"))
        // The name alone, a blank line, then the song, is sung too.
        assertEquals((listOf("Calm Night", "") + sung).joinToString("\n"), cleaned(MusicMetadata("Calm Night", "The Band"), "Calm Night", ""))
        // A year under the name is a note only beside the artist's name: with no artist known, it's sung.
        assertEquals((listOf("Calm Night", "Summer of 1985") + sung).joinToString("\n"), cleaned(MusicMetadata("Calm Night", ""), "Calm Night", "Summer of 1985"))
        // A song whose words are only its name keeps them.
        assertEquals("Calm Night", LyricsFinder.cleaned(Lyrics.Found("Calm Night", source = LyricsFinder.NETEASE), MusicMetadata("Calm Night", "The Band")).words)
    }

    @Test
    fun aNoteOnHowASongIsPlayedIsLeftOutOfItsWordsButSungWordsInBracketsStay() {
        val song = MusicMetadata("Message in a Bottle", "The Police")
        fun cleaned(vararg lines: String, source: String = LyricsFinder.NETEASE) =
            LyricsFinder.cleaned(Lyrics.Found(lines.joinToString("\n"), source = source), song).words
        // In either kind of bracket, with or without a space, after the words or on a line of its own.
        assertEquals("Sending out an sos\nA hundred", cleaned("Sending out an sos（repeat and fade）", "A hundred"))
        assertEquals("Sending out an sos\nA hundred", cleaned("Sending out an sos (repeat and fade)", "A hundred"))
        assertEquals("Sending out an sos\nA hundred", cleaned("Sending out an sos", "（Repeat and fade）", "A hundred"))
        assertEquals("Sending out an sos\n\nA hundred", cleaned("Sending out an sos", "(x2)", "", "A hundred"))
        // What other notes look like, from any site.
        for (note in listOf("(x2)", "(X2)", "(2x)", "（×4）", "(ｘ２)", "(Fade out)", "(fade-out)", "(Repeat to fade)", "(repeat chorus)", "(Repeat 2 times)", "(2 times)", "(Chorus x2)", "[Repeat and fade]", "【Fade out】")) {
            assertEquals("Sos\nA hundred", cleaned("Sos $note", "A hundred", source = "lrclib"))
        }
        // At the start, or between words, with the space it was set off by.
        assertEquals("Sending out an sos", cleaned("(x2) Sending out an sos"))
        assertEquals("Sending out an sos", cleaned("Sending (fade out) out an sos"))
        assertEquals("Sending out an sos", cleaned("Sending out (x2) (fade out) an sos"))
        // Sung words in brackets, and words a note could be but a singer says too.
        val sung = listOf(
            "(Every breath you take)", "I love you (I love you)", "Stayin' alive (ah, ah, ah, ah)", "Roar (repeat)", "Love me twice (twice)",
            "(repeat, repeat)", "(Repeat after me)", "Fade (fade away)", "(Again!)", "Time (2)", "Take it (out)", "Wait (in the dark)", "Roar (repeat!)", "Roar (...fade)", "(x)", "(fade)", "(fades)", "(times)", "()",
            "Sos (repeat and fade", "Sos repeat and fade)", "Sos repeat and fade"
        )
        assertEquals(sung.joinToString("\n"), cleaned(*sung.toTypedArray()))
        assertEquals(sung.joinToString("\n"), cleaned(*sung.toTypedArray(), source = "lrclib"))
    }

    @Test
    fun aNoteOnHowASongIsPlayedIsLeftOutOfTimedLinesAndWordsTimedOneByOneWithTheirTimes() {
        val song = MusicMetadata("Message in a Bottle", "The Police")
        fun word(ms: Long, text: String) = TimedWord(ms, text)
        val lines = listOf(
            LyricLine(1_000, "Just a castaway"),
            // The note apart from the words before it, and on a word with them.
            LyricLine(
                2_000, "Sending out an sos（repeat and fade）",
                listOf(word(2_000, "Sending "), word(2_300, "out "), word(2_600, "an "), word(2_900, "sos（repeat "), word(3_000, "and "), word(3_100, "fade）")), endMs = 3_500
            ),
            LyricLine(4_000, "Sending out an sos (x2)", listOf(word(4_000, "Sending "), word(4_300, "out "), word(4_600, "an "), word(4_900, "sos "), word(5_000, "(x2)")), endMs = 5_500),
            // A note first, then the words; a note that is all there is; and a pause, which stays.
            LyricLine(6_000, "(x2) Hello", listOf(word(6_000, "(x2) "), word(6_100, "Hello")), endMs = 6_900),
            LyricLine(7_000, "(fade out)", listOf(word(7_000, "(fade "), word(7_100, "out)"))),
            LyricLine(8_000, "（fade out）"),
            // Two notes before the words leave no space in front of them; a note after the first word only.
            LyricLine(10_000, "(x2) (fade out) Again", listOf(word(10_000, "(x2) "), word(10_100, "(fade "), word(10_200, "out) "), word(10_300, "Again"))),
            LyricLine(11_000, "Hello (x2)", listOf(word(11_000, "Hello "), word(11_100, "(x2)")), endMs = 11_500),
            // Lines whose text isn't their words joined are cut by their text.
            LyricLine(12_000, "Sos, again (x2)", listOf(word(12_000, "Sos,"), word(12_100, " (x2)"))),
            LyricLine(13_000, "Hello there", listOf(word(13_000, "Hello"), word(13_100, "there"))),
            // A note between words leaves its space with the word before.
            LyricLine(13_500, "Hello (x2) there", listOf(word(13_500, "Hello "), word(13_600, "(x2) "), word(13_700, "there"))),
            // A note first in the same word as the words after it.
            LyricLine(13_800, "(x2) Hello there", listOf(word(13_800, "(x2) Hello "), word(13_900, "there"))),
            LyricLine(14_000, ""),
            LyricLine(20_000, "I love you (I love you)", listOf(word(20_000, "I "), word(20_100, "love "), word(20_200, "you "), word(20_300, "(I "), word(20_400, "love "), word(20_500, "you)")))
        )
        val found = LyricsFinder.cleaned(Lyrics.Found(lines.joinToString("\n") { it.text }, lines, source = LyricsFinder.NETEASE), song)
        assertEquals(listOf("Just a castaway", "Sending out an sos", "Sending out an sos", "Hello", "Again", "Hello", "Sos, again", "Hello there", "Hello there", "Hello there", "", "I love you (I love you)"), found.lines.map { it.text })
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 6_000L, 10_000L, 11_000L, 12_000L, 13_000L, 13_500L, 13_800L, 14_000L, 20_000L), found.lines.map { it.timeMs })
        assertEquals("Just a castaway\nSending out an sos\nSending out an sos\nHello\nAgain\nHello\nSos, again\nHello there\nHello there\nHello there\n\nI love you (I love you)", found.words)
        assertEquals(listOf("Again"), found.lines[4].words.map { it.text })
        assertEquals(11_100L, found.lines[5].endMs)
        assertEquals(listOf("Hello"), found.lines[5].words.map { it.text })
        assertEquals(listOf("Sos,"), found.lines[6].words.map { it.text })
        assertEquals(listOf("Hello", "there"), found.lines[7].words.map { it.text })
        assertEquals(listOf("Hello ", "there"), found.lines[8].words.map { it.text })
        assertEquals(listOf("Hello ", "there"), found.lines[9].words.map { it.text })
        // The words keep their times and make up the line, as before.
        assertEquals(listOf("Sending ", "out ", "an ", "sos"), found.lines[1].words.map { it.text })
        assertEquals(listOf(2_000L, 2_300L, 2_600L, 2_900L), found.lines[1].words.map { it.timeMs })
        assertEquals(listOf("Sending ", "out ", "an ", "sos"), found.lines[2].words.map { it.text })
        // The line ends when its words do, where the note was timed apart from them; not guessed where it wasn't.
        assertEquals(listOf(3_500L, 5_000L, 6_900L), found.lines.slice(1..3).map { it.endMs })
        assertEquals(listOf("Hello"), found.lines[3].words.map { it.text })
        assertEquals(listOf(6_100L), found.lines[3].words.map { it.timeMs })
        for (line in found.lines.filter { it.words.isNotEmpty() && it.text.startsWith("Sos,").not() && it.text != "Hello there" }) assertEquals(line.text, line.words.joinToString("") { it.text })
        assertEquals(6, found.lines[11].words.size)
        // A different site's lines are cleaned the same.
        assertEquals("Hello", LyricsFinder.cleaned(Lyrics.Found("(x2) Hello", listOf(LyricLine(0, "(x2) Hello")), source = "lrclib"), song).lines.single().text)
    }

    @Test
    fun brokenTextIsMendedWhereItCanBe() {
        fun cleaned(words: String, source: String = LyricsFinder.NETEASE) = LyricsFinder.cleaned(Lyrics.Found(words, source = source), hatsukoi).words
        // An apostrophe stored escaped, and a lost character NetEase writes as 锟斤拷.
        assertEquals("Trouble is he's back again", cleaned("Trouble is he\\'s back again"))
        assertEquals("Don't look, I'm alright", cleaned("Don't锟斤拷锟斤拷look, I'm alright"))
        // Words NetEase has with apostrophes escaped have their commas as apostrophes, timed or not; one in a word stays.
        val commas = "Trouble is her only friend' and he\\'s back again\nDown' down' down'\nI'm watching you"
        val mended = "Trouble is her only friend, and he's back again\nDown, down, down,\nI'm watching you"
        assertEquals(mended, cleaned(commas))
        val timed = LyricsFinder.cleaned(Lyrics.Found(commas, commas.lines().mapIndexed { index, line -> LyricLine(index * 1_000L, line) }, source = LyricsFinder.NETEASE), hatsukoi)
        assertEquals(mended.lines(), timed.lines.map { it.text }.filter { it.isNotEmpty() })
        // With none escaped, an apostrophe ending a word is one.
        assertEquals("Goin' home", cleaned("Goin' home"))
        // UTF-8 read once as Latin-1 or Windows-1252, from any site, is read again right.
        assertEquals("El baraka daret aâlat\nIt’s mañana", cleaned("El baraka daret aÃ¢lat\nItâ€™s maÃ±ana", LyricsFinder.LRCLIB))
        // Words that only look so are left be.
        assertEquals("Ã la carte, Âme", cleaned("Ã la carte, Âme", LyricsFinder.LRCLIB))
    }

    @Test
    fun koreanWordsNetEaseKeepsGarbledAreNoWords() {
        val korean = "누군가 나를 좀 말려줘요\n어떻게 이렇게 당할수가 있나요"
        // As NetEase has some: the words' UTF-8 read as GBK.
        val garbled = String(korean.toByteArray(Charsets.UTF_8), charset("GBK"))
        val netEase = { _: String -> """{"result":{"songs":[{"id":2,"name":"Hatsukoi","artists":[{"name":"Hiroko Mita"}]}]}}""" }
        japaneseSong(netEase = netEase, lyric = garbled)
        assertEquals(Lyrics.None, found(hatsukoi))
        // Chinese and Japanese words are words.
        for (words in listOf("五月雨は緑色\n悲しくさせたよ", "我们一起走过的路\n你还记得吗")) {
            japaneseSong(netEase = netEase, lyric = words)
            assertEquals(words, words(hatsukoi))
        }
    }

    @Test
    fun wordsAreGarbledKoreanOnlyWithEnoughChineseCharactersMostlyReadingBackAsKorean() {
        fun garbled(korean: String) = String(korean.toByteArray(Charsets.UTF_8), charset("GBK"))
        // Each pair of these syllables reads as 3 Chinese characters, and back as the 2 Korean ones.
        // 9 Chinese characters are too few to tell; 10 are enough, 6 of them reading back as Korean.
        assertFalse(readAsGbk(garbled("나나나다나라")))
        assertTrue(readAsGbk(garbled("나나나다나라") + "中"))
        // 4 of 10 reading back as Korean, the first and last syllables there are, is garbled; 4 of 11 isn't.
        assertTrue(readAsGbk(garbled("나가나힣") + "中文中文"))
        assertFalse(readAsGbk(garbled("나가나힣") + "中文中文中"))
        // The characters just past Korean's first and last syllables aren't Korean: 4 of 12 isn't garbled, where 5 would be.
        assertFalse(readAsGbk(garbled("나\uD7A4") + garbled("\uABFF나") + garbled("나나") + "中文中"))
    }

    @Test
    fun anLrclibAnswerWithOnlyTheSongsNamesIsNoWords() {
        val header = "Artist: Hiroko Mita\nAlbum: First Album\nTitle: Hatsukoi"
        japaneseSong(lrclib = { title -> if (title == "Hatsukoi") """[${answer("Hatsukoi", "Hiroko Mita", header)}]""" else "[]" })
        assertEquals(Lyrics.None, found(hatsukoi))
        // Words under it are its words.
        japaneseSong(lrclib = { title -> if (title == "Hatsukoi") """[${answer("Hatsukoi", "Hiroko Mita", "$header\n五月雨は緑色")}]""" else "[]" })
        assertEquals("五月雨は緑色", words(hatsukoi))
        // Two of the names are a header too; one alone is a line like any.
        japaneseSong(lrclib = { title -> if (title == "Hatsukoi") """[${answer("Hatsukoi", "Hiroko Mita", "Artist: Hiroko Mita\nTitle: Hatsukoi\n五月雨は緑色")}]""" else "[]" })
        assertEquals("五月雨は緑色", words(hatsukoi))
        japaneseSong(lrclib = { title -> if (title == "Hatsukoi") """[${answer("Hatsukoi", "Hiroko Mita", "Title: Hatsukoi\n五月雨は緑色")}]""" else "[]" })
        assertEquals("Title: Hatsukoi\n五月雨は緑色", words(hatsukoi))
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
    fun aClosingCreditNeedsNoSpaceBeforeItsColonAndATimeIsStillSung() {
        val netEase = { _: String -> """{"result":{"songs":[{"id":2,"name":"Hatsukoi","artists":[{"name":"Hiroko Mita"}]}]}}""" }
        val sung = "五月雨は緑色"
        fun timed(lines: List<String>) = lines.mapIndexed { index, line -> "[00:${10 + index}.000]$line" }.joinToString("\n")
        // A player and a name, with either colon and no space around it, is still a closing credit.
        japaneseSong(netEase = netEase, lyric = timed(listOf(sung, "貝斯：Player One", "鼓:Player Two")))
        assertEquals(Lyrics.Found(sung, listOf(LyricLine(10_000, sung)), source = LyricsFinder.NETEASE), found(hatsukoi))
        // One such line can be sung, so it stays. The kanji is already the form Japan writes.
        japaneseSong(netEase = netEase, lyric = timed(listOf(sung, "貝斯：Player One")))
        assertEquals(
            Lyrics.Found("$sung\n貝斯：Player One", listOf(LyricLine(10_000, sung), LyricLine(11_000, "貝斯：Player One")), source = LyricsFinder.NETEASE),
            found(hatsukoi)
        )
        // A time is a number before the colon, even two of them at the end.
        val times = listOf(sung, "7:30 in the night", "When it's 11:30 in the night")
        japaneseSong(netEase = netEase, lyric = timed(times))
        assertEquals(
            Lyrics.Found(times.joinToString("\n"), times.mapIndexed { index, line -> LyricLine(10_000L + index * 1_000, line) }, source = LyricsFinder.NETEASE),
            found(hatsukoi)
        )
        // Punctuation isn't who played, and neither is a colon with no label.
        val marks = listOf(sung, "???: still here", "!!!: still here")
        japaneseSong(netEase = netEase, lyric = timed(marks))
        assertEquals(
            Lyrics.Found(marks.joinToString("\n"), marks.mapIndexed { index, line -> LyricLine(10_000L + index * 1_000, line) }, source = LyricsFinder.NETEASE),
            found(hatsukoi)
        )
        // The stamp's text is trimmed, so a colon with no label stays as a line.
        val blank = listOf(sung, ": Someone", ": Else")
        japaneseSong(netEase = netEase, lyric = timed(listOf(sung, " : Someone", "  : Else")))
        assertEquals(
            Lyrics.Found(blank.joinToString("\n"), blank.mapIndexed { index, line -> LyricLine(10_000L + index * 1_000, line) }, source = LyricsFinder.NETEASE),
            found(hatsukoi)
        )
    }

    @Test
    fun whatNetEaseOpensASongWithForAMomentIsntSung() {
        val netEase = { _: String -> """{"result":{"songs":[{"id":2,"name":"Hatsukoi","artists":[{"name":"Hiroko Mita"}]}]}}""" }
        japaneseSong(netEase = netEase)
        // Timed word by word, a contributor's note first passes in 105 ms a word, far quicker than singing;
        // 150 ms a word is sung, and so is a quick line once the song has begun.
        val byWord = listOf(
            "[0,0](0,0,0) 作词 : Someone",
            "[1000,210](1000,105,0)Lucky (1105,105,0)guy",
            "[2000,300](2000,150,0)Hear (2150,150,0)me",
            "[3000,3000](3000,1500,0)I am woman, (4500,1500,0)hear me roar",
            "[7000,200](7000,100,0)Shayo (7100,100,0)shayo",
        ).joinToString("\n")
        fake.handler = { request ->
            when (request.url.encodedPath) {
                "/api/search/get" -> FakeSpotify.html(request, netEase(""))
                "/api/song/lyric" -> FakeSpotify.html(request, """{"lrc":{"lyric":""},"yrc":{"lyric":${quote(byWord)}}}""")
                else -> FakeSpotify.html(request, if (request.url.host == "lrclib.net") "[]" else """{"results":[]}""")
            }
        }
        assertEquals(listOf("Hear me", "I am woman, hear me roar", "Shayo shayo"), (found(hatsukoi) as Lyrics.Found).lines.map { it.text }.filter { it.isNotEmpty() })
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
        assertEquals(true, fake.requestedUrls.any { it.startsWith("https://music.163.com/api/song/lyric?id=2&") && "lv=-1" in it && "yv=-1" in it })
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
    fun withOwnNamesNetEasesUntimedWordsUnderTheGivenOnesDontBetterLrclibsUntimedOnes() {
        japaneseSong(
            lrclib = { title -> if (title == "Hatsukoi") """[${answer("Hatsukoi", "Hiroko Mita", "LRCLIB's plain words")}]""" else "[]" },
            netEase = { query -> if (query == "Hatsukoi Hiroko Mita") """{"result":{"songs":[{"id":3,"name":"Hatsukoi","artists":[{"name":"Hiroko Mita"}]}]}}""" else """{"result":{}}""" },
            lyric = "Plain words, no timings"
        )
        assertEquals(Lyrics.Found("LRCLIB's plain words", source = LyricsFinder.LRCLIB), found(hatsukoi))
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
        // A composer's credit and no lyricist's, with no words, is how it keeps many recordings no one sings on, and
        // also some sung songs whose words it lacks: LRCLIB has the words of some. That says nothing, so it isn't instrumental.
        japaneseSong(netEase = { ownSong }, lyric = "[00:00.00-1] 作曲 : Someone")
        assertEquals(Lyrics.None, found(hatsukoi))
        japaneseSong(netEase = { ownSong }, lyric = "[00:00.00-1] 作词 : Someone\n[00:00.00-1] 作曲 : Someone")
        assertEquals(Lyrics.None, found(hatsukoi))
        // It flags an instrumental itself, and then it credits its players, not only who wrote it.
        japaneseSong(netEase = { ownSong }, lyric = "[00:00.00] 作曲 : Someone\n[00:01.00] 大提琴 : Another\n[00:02.00] 钢琴 : Another", pureMusic = true)
        assertEquals(Lyrics.Instrumental, found(hatsukoi))
        // Words under a composer's credit are words, even when they're kept garbled and so don't count.
        japaneseSong(netEase = { ownSong }, lyric = "[00:00.00] 作曲 : Someone\n[00:05.00] Sung line")
        assertEquals("Sung line", (found(hatsukoi) as Lyrics.Found).words)
        val garbled = String("나는 너를 사랑해 나는 너를 사랑해".toByteArray(Charsets.UTF_8), charset("GBK"))
        japaneseSong(netEase = { ownSong }, lyric = "[00:00.00] 作曲 : Someone\n[00:05.00] $garbled")
        assertEquals(Lyrics.None, found(hatsukoi))
        // Credits of any kind are no words, whatever stamp they have, and a stamp is never part of the words.
        japaneseSong(netEase = { ownSong }, lyric = "[00:00.00-1] 编曲 : Someone\n[00:00.00-1] 制作人 : Another")
        assertEquals(Lyrics.None, found(hatsukoi))
        japaneseSong(netEase = { ownSong }, lyric = "[00:00.00-1] 作曲 : Someone\n[00:07.50-1] Sung line")
        assertEquals("Sung line", (found(hatsukoi) as Lyrics.Found).words)
        japaneseSong(netEase = { """{"result":{"songs":[{"id":9,"name":"Another Song","artists":[{"name":"三田寛子"}]}]}}""" })
        assertEquals(Lyrics.None, found(hatsukoi))
    }

    @Test
    fun aCreditLabelOutsideTheKnownListIsStillNoWords() {
        val ownSong = """{"result":{"songs":[{"id":2,"name":"初恋","artists":[{"name":"三田寛子"}]}]}}"""
        // The label isn't one of the usual few. The line is still a credit: a short label, a colon, a name, and nothing is sung.
        japaneseSong(netEase = { ownSong }, lyric = "[00:00.00-1] 作词 : Ada Writer\n[00:00.00-1] 作曲 : Ada Writer\n[00:00.00-1] 制作人 : A Producer\n[00:01.000] 编程 : Player One")
        assertEquals(Lyrics.None, found(hatsukoi))
        japaneseSong(netEase = { ownSong }, lyric = "[00:00.00-1] 作词 : Ada Writer\n[00:00.00-1] 作曲 : Ada Writer\n[00:01.000] 人声 : Singer One/Singer Two")
        assertEquals(Lyrics.None, found(hatsukoi))
    }

    @Test
    fun anInstrumentalMarkWithNoSungLineIsInstrumental() {
        val ownSong = """{"result":{"songs":[{"id":2,"name":"初恋","artists":[{"name":"三田寛子"}]}]}}"""
        // Credits, then the mark and nothing sung. The word alone, or in the other brackets, is the same mark.
        japaneseSong(netEase = { ownSong }, lyric = "作词 : Ada Writer\n作曲 : Ada Writer\n[Instrumental]")
        assertEquals(Lyrics.Instrumental, found(hatsukoi))
        japaneseSong(netEase = { ownSong }, lyric = "[00:00.00-1] 作词 : Ada Writer\n[00:01.000] [Instrumental]")
        assertEquals(Lyrics.Instrumental, found(hatsukoi))
        japaneseSong(netEase = { ownSong }, lyric = "（instrumental）")
        assertEquals(Lyrics.Instrumental, found(hatsukoi))
        japaneseSong(netEase = { ownSong }, lyric = "(Instrumental)")
        assertEquals(Lyrics.Instrumental, found(hatsukoi))
        japaneseSong(netEase = { ownSong }, lyric = "【instrumental】")
        assertEquals(Lyrics.Instrumental, found(hatsukoi))
        japaneseSong(netEase = { ownSong }, lyric = "INSTRUMENTAL")
        assertEquals(Lyrics.Instrumental, found(hatsukoi))
        // A bracket that doesn't close is a line, not the mark.
        japaneseSong(netEase = { ownSong }, lyric = "(Instrumental]")
        assertEquals(Lyrics.Found("(Instrumental]", source = LyricsFinder.NETEASE), found(hatsukoi))
        // Word by word, the mark and nothing sung. The search names it as the lookup does, so no other store has to.
        val named = """{"result":{"songs":[{"id":2,"name":"Hatsukoi","artists":[{"name":"Hiroko Mita"}]}]}}"""
        val byWord = "[0,1000](0,1000,0)[Instrumental]"
        fake.handler = { request ->
            when (request.url.encodedPath) {
                "/api/search/get" -> FakeSpotify.html(request, named)
                "/api/song/lyric" -> FakeSpotify.html(request, """{"lrc":{"lyric":""},"yrc":{"lyric":${quote(byWord)}}}""")
                else -> FakeSpotify.html(request, if (request.url.host == "lrclib.net") "[]" else """{"results":[]}""")
            }
        }
        assertEquals(Lyrics.Instrumental, found(hatsukoi))
        // Garbled words beside the mark are still words, so another site is tried. The mark doesn't decide it.
        val garbled = String("나는 너를 사랑해 나는 너를 사랑해".toByteArray(Charsets.UTF_8), charset("GBK"))
        japaneseSong(netEase = { ownSong }, lyric = "[00:00.00] [Instrumental]\n[00:05.00] $garbled")
        assertEquals(Lyrics.None, found(hatsukoi))
    }

    @Test
    fun anInstrumentalMarkBetweenRealLinesIsOnlyABreak() {
        val ownSong = """{"result":{"songs":[{"id":2,"name":"初恋","artists":[{"name":"三田寛子"}]}]}}"""
        val sung = "First line of the song\nLove this instrumental part\nSecond line of the song"
        japaneseSong(netEase = { ownSong }, lyric = "First line of the song\n[Instrumental]\nLove this instrumental part\nSecond line of the song")
        assertEquals(Lyrics.Found(sung, source = LyricsFinder.NETEASE), found(hatsukoi))
        japaneseSong(netEase = { ownSong }, lyric = "[00:10.00] First line of the song\n[00:20.00] [Instrumental]\n[00:30.00] Love this instrumental part\n[00:40.00] Second line of the song")
        assertEquals(sung, (found(hatsukoi) as Lyrics.Found).words)
    }

    @Test
    fun aKeptInstrumentalMarkWithNoSungLineIsInstrumental() {
        val file = File.createTempFile("lyrics", ".json").apply { delete(); deleteOnExit() }
        val cache = LookupCache(file, Dispatchers.Unconfined)
        val song = MusicMetadata("Song", "Band")
        val creditSong = MusicMetadata("Quiet", "Band")
        fun keep(forSong: MusicMetadata, words: String) {
            val lines = org.json.JSONArray(words.lines().mapIndexed { index, line -> listOf(index * 1_000, line) })
            val kept = org.json.JSONObject().put("words", words).put("source", LyricsFinder.NETEASE).put("lines", lines)
            runBlocking { cache.put("${LyricsFinder.CACHE_VERSION}\u0000${RecordingKey.of(forSong)}", kept.toString()) }
        }
        keep(song, "[Instrumental]")
        keep(creditSong, "作词 : Someone")
        fake.handler = { throw IOException("offline") }
        val finder = LyricsFinder(fake.client(), "2.3.3", busyPauseMs = 0, cache = cache)
        assertEquals(Lyrics.Instrumental, runBlocking { finder.lyricsOf(song) })
        // A credit and nothing sung is still none of the mark, so it isn't instrumental.
        val credit = runBlocking { finder.lyricsOf(creditSong) }
        assertEquals(true, credit is Lyrics.Found && credit.words.isBlank())
        assertEquals(emptyList<String>(), fake.requestedUrls)
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
    fun untimedWordsCachedBeforeAlternateNamesWereCheckedAreLookedUpAgain() {
        val song = MusicMetadata("夜の歌 - Night Song", "Band")
        val file = File.createTempFile("lyrics-alternate", ".json").apply { delete(); deleteOnExit() }
        val cache = LookupCache(file, Dispatchers.Unconfined)
        runBlocking { cache.put("13\u0000${RecordingKey.of(song)}", """{"words":"古い歌","lines":[],"provisional":false}""") }
        respond("[${answer(song.title, song.artist, "架空の歌", "[00:01.00]架空の歌")}]")
        val result = runBlocking { LyricsFinder(fake.client(), "test", busyPauseMs = 0, cache = cache).lyricsOf(song) } as Lyrics.Found
        assertEquals(listOf(LyricLine(1000, "架空の歌")), result.lines)
        file.delete()
    }

    @Test
    fun untimedWordsUnderABilingualTitleGiveWayToTimingUnderEitherName() {
        for (title in listOf("夜の歌 - Night Song", "Night Song - 夜の歌", "Night Song - Evening Song")) {
            val names = SongNames.titleNames(title)
            for (timedName in names) {
                fake.requestedUrls.clear()
                fake.handler = { request ->
                    val name = request.url.queryParameter("track_name")
                    val body = when (request.url.host) {
                        "lrclib.net" -> if (name in names) "[${answer(name.orEmpty(), "Band", "架空の歌", if (name == timedName) "[00:01.00]架空の歌" else "")}]" else "[]"
                        "itunes.apple.com" -> """{"results":[]}"""
                        else -> """{"result":{"songs":[]}}"""
                    }
                    FakeSpotify.html(request, body)
                }
                val result = runBlocking { finder().firstLook(MusicMetadata(title, "Band", lengthMs = 213000)).lyrics } as Lyrics.Found
                assertEquals("$title / $timedName", listOf(LyricLine(1000, "架空の歌")), result.lines)
                val asked = fake.requestedUrls.filter { it.startsWith("https://lrclib.net/") }
                    .mapNotNull { it.toHttpUrl().queryParameter("track_name") }
                assertEquals(names.take(names.indexOf(timedName) + 1), asked)
            }
        }
    }

    @Test
    fun untimedAlternateNamesKeepTheFirstWordsAndSingleNamesAreNotRetried() {
        for (title in listOf("夜の歌 - Night Song", "Night Song", "夜の歌")) {
            fake.requestedUrls.clear()
            fake.handler = { request ->
                val name = request.url.queryParameter("track_name")
                val body = when (request.url.host) {
                    "lrclib.net" -> "[${answer(name.orEmpty(), "Band", if (name == title) "最初の歌" else "別の歌")}]"
                    "itunes.apple.com" -> """{"results":[]}"""
                    else -> """{"result":{"songs":[]}}"""
                }
                FakeSpotify.html(request, body)
            }
            assertEquals("最初の歌", (runBlocking { finder().firstLook(MusicMetadata(title, "Band")).lyrics } as Lyrics.Found).words)
            assertEquals(SongNames.titleNames(title), fake.requestedUrls.filter { it.startsWith("https://lrclib.net/") }
                .mapNotNull { it.toHttpUrl().queryParameter("track_name") })
        }
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

    /** YouTube Music's page for video vid1, a song [length] long, or a video, with a page for its words. */
    private fun watching(length: String, type: String = "ATV") =
        """{"tabs":[{"browseEndpoint":{"browseId":"MPLYt_words"}}],"panel":{"playlistPanelVideoRenderer":{"videoId":"vid1","title":{"runs":[{"text":"Song"}]},""" +
            """"longBylineText":{"runs":[{"text":"Band"}]},"lengthText":{"runs":[{"text":"$length"}]},"musicVideoType":"MUSIC_VIDEO_TYPE_$type"}}}"""

    @Test
    fun youTubeMusicsWordsForAnotherEditGiveWayToWordsForThisOne() {
        val edit = youTubeMusicPage("Erste" to 10_200, "Zweite" to 13_100)
        // LRCLIB's, timed for a recording as long as the one playing, 4:22.
        val asLong = timedOnLrclib.dropLast(1) + ""","duration":262}"""
        val lrclibWords = Lyrics.Found("First\nSecond", listOf(LyricLine(1_000, "First"), LyricLine(10_000, "Second")), 262_000, LyricsFinder.LRCLIB)
        val playing = song.copy(lengthMs = 262_000)
        everywhere("[$asLong]", edit, next = watching("2:54"))
        assertEquals(lrclibWords, runBlocking { withYouTubeMusic().lyricsOf(playing) })
        // Up to 3 seconds apart is the same recording, so YouTube Music's, as it's first.
        everywhere("[$asLong]", edit, next = watching("4:24"))
        assertEquals(264_000L, (runBlocking { withYouTubeMusic().lyricsOf(playing) } as Lyrics.Found).lengthMs)
        everywhere("[$asLong]", edit, next = watching("4:25"))
        assertEquals(LyricsFinder.YOUTUBE_MUSIC, (runBlocking { withYouTubeMusic().lyricsOf(playing) } as Lyrics.Found).source)
        // With nothing timed as well for this one, they stay, saying what they're timed for, so they can be put in time by hand.
        val untimed = answer("Song", "Band", "First\nSecond").dropLast(1) + ""","duration":262}"""
        val other = timedOnLrclib.dropLast(1) + ""","duration":174}"""
        listOf("[$untimed]", "[$other]", "[$timedOnLrclib]", "[]").forEach { lrclib ->
            everywhere(lrclib, edit, next = watching("2:54"))
            val found = runBlocking { withYouTubeMusic().lyricsOf(playing) } as Lyrics.Found
            assertEquals(lrclib, LyricsFinder.YOUTUBE_MUSIC to 174_000L, found.source to found.lengthMs)
            assertEquals(false, found.timedFor(playing.lengthMs))
        }
        // With no telling which edit plays, they're as good as any.
        everywhere("[$asLong]", edit, next = watching("2:54"))
        assertEquals(LyricsFinder.YOUTUBE_MUSIC, (runBlocking { withYouTubeMusic().lyricsOf(song) } as Lyrics.Found).source)
        // A video's length isn't its song's, which it may open with more than.
        everywhere("[$asLong]", edit, next = watching("2:54", type = "OMV"))
        assertEquals(LyricsFinder.YOUTUBE_MUSIC to null, (runBlocking { withYouTubeMusic().lyricsOf(playing) } as Lyrics.Found).let { it.source to it.lengthMs })
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
    fun wordsTimedForARecordingAsLongButNotAsAnyTakeIsSungGiveWayToAnotherTakesWellTimedOnes() {
        val words = listOf("Every word I sing here", "Falls down around my feet", "Nothing left to say now", "Under the city lights", "Waiting for the morning", "Holding on to nothing", "Running from the thunder", "Calling out your name")
        val lrclib = words.mapIndexed { line, text -> 10_000L + line * 4_000L to text }
        fun sheet(lines: List<Pair<Long, String>>) = lines.joinToString("\n") { (ms, text) -> "[%02d:%02d.%02d]%s".format(ms / 60_000, ms / 1_000 % 60, ms % 1_000 / 10, text) }
        fun netEase(lines: List<Pair<Long, String>>): (Request) -> Response = { request ->
            val url = request.url
            when {
                url.host == "lrclib.net" -> FakeSpotify.html(request, "[${lasting(answer("Song", "Band", "", synced = sheet(lrclib)), 260.0)}]")
                url.encodedPath == "/api/search/get" -> FakeSpotify.html(request, """{"result":{"songs":[{"id":1,"name":"Song","artists":[{"name":"Band"}],"duration":266000}]}}""")
                url.encodedPath == "/api/song/lyric" -> FakeSpotify.html(request, """{"lrc":{"lyric":${quote(sheet(lines))}}}""")
                else -> FakeSpotify.html(request, """{"results":[]}""")
            }
        }
        val playing = MusicMetadata("Song", "Band", lengthMs = 266_100)
        // NetEase's, for a recording as long as this one, is all over the place against LRCLIB's: a few
        // seconds later, then hardly, then more again. No take moves its lines like that, so it's timed
        // badly, not for this take, and LRCLIB's stay.
        val jumbled = listOf(13_000L, 19_500L, 20_200L, 27_000L, 27_600L, 33_500L, 34_100L, 42_000L)
        // With a line of its own to open with, as NetEase's sometimes have.
        fake.handler = netEase(listOf(8_000L to "Count us in before we start") + words.indices.map { jumbled[it] to words[it] })
        assertEquals(lrclib.map { (ms, text) -> LyricLine(ms, text) }, (found(playing) as Lyrics.Found).lines)
        // Six seconds later all through, as for a take with a longer intro, NetEase's are this take's.
        fake.handler = netEase(lrclib.map { (ms, text) -> ms + 6_000 to text })
        assertEquals(lrclib.map { (ms, text) -> LyricLine(ms + 6_000, text) }, (found(playing) as Lyrics.Found).lines)
    }

    /** LRCLIB with [lrclib], timed a line at a time, and NetEase with [netEase] word by word: when each line starts, and how long each of its words takes. */
    private fun rushedSheets(lrclib: List<Pair<Long, String>>, netEase: List<Triple<Long, Long, String>>) {
        val sheet = lrclib.joinToString("\n") { (ms, text) -> "[%02d:%02d.%02d]%s".format(ms / 60_000, ms / 1_000 % 60, ms % 1_000 / 10, text) }
        val byWord = netEase.joinToString("\n") { (start, wordMs, text) ->
            val words = text.split(' ')
            "[$start,${words.size * wordMs}]" + words.mapIndexed { at, word -> "(${start + at * wordMs},$wordMs,0)$word${if (at < words.lastIndex) " " else ""}" }.joinToString("")
        }
        fake.handler = { request ->
            val url = request.url
            when {
                url.host == "lrclib.net" -> FakeSpotify.html(request, "[${lasting(answer("Song", "Band", "", synced = sheet), 260.0)}]")
                url.encodedPath == "/api/search/get" -> FakeSpotify.html(request, """{"result":{"songs":[{"id":1,"name":"Song","artists":[{"name":"Band"}],"duration":260000}]}}""")
                url.encodedPath == "/api/song/lyric" -> FakeSpotify.html(request, """{"lrc":{"lyric":""},"yrc":{"lyric":${quote(byWord)}}}""")
                else -> FakeSpotify.html(request, """{"results":[]}""")
            }
        }
    }

    private val rushedSong = MusicMetadata("Song", "Band", lengthMs = 260_000)

    private fun evenSheet(count: Int = sungWords.size) = sungWords.take(count).mapIndexed { line, text -> 10_000L + line * 4_000L to text }

    /** The words each as [evenSheet] has them, 200 ms later, unhurried, except as [rushed] says: a line's index, its start, and how long each of its words takes. */
    private fun netEaseSheet(count: Int = sungWords.size, shiftMs: Long = 200, vararg rushed: Triple<Int, Long, Long>) = sungWords.take(count).mapIndexed { line, text ->
        rushed.firstOrNull { it.first == line }?.let { Triple(it.second, it.third, text) } ?: Triple(10_000L + line * 4_000L + shiftMs, 400L, text)
    }

    @Test
    fun linesGoneByFasterThanAnyoneSingsStartWhereAnotherSheetHasThem() {
        // Seven words a second and more: timed late, in a hurry to catch up with the next line, as NetEase has Expresso Love.
        rushedSheets(evenSheet(), netEaseSheet(rushed = arrayOf(Triple(0, 15_000L, 160L), Triple(1, 15_900L, 100L))))
        val lines = (found(rushedSong) as Lyrics.Found).lines
        assertEquals(sungWords.indices.map { 10_200L + it * 4_000L }, lines.map { it.timeMs })
        // Their words spread out over the time they now have, up to the next line; the rest as they were.
        assertEquals(listOf(10_200L, 11_000L, 11_800L, 12_600L, 13_400L), lines[0].words.map { it.timeMs })
        assertEquals(14_200L, lines[0].endMs)
        assertEquals(listOf(14_200L, 14_640L, 15_080L, 15_520L, 15_960L), lines[1].words.map { it.timeMs })
        assertEquals(16_400L, lines[1].endMs)
        assertEquals(listOf(18_200L, 18_600L, 19_000L, 19_400L, 19_800L), lines[2].words.map { it.timeMs })
    }

    @Test
    fun anotherTakesSheetPutsRushedLinesBackBySoMuchLess() {
        // All six seconds later: the take is another, and the rushed line still comes back, as far from the rest as that sheet has it.
        rushedSheets(evenSheet(), netEaseSheet(shiftMs = 6_000, rushed = arrayOf(Triple(0, 20_000L, 160L))))
        assertEquals(sungWords.indices.map { 16_000L + it * 4_000L }, (found(rushedSong) as Lyrics.Found).lines.map { it.timeMs })
    }

    @Test
    fun linesThatOnlySeemRushedStayAsTimed() {
        fun times() = (found(rushedSong) as Lyrics.Found).lines.map { it.timeMs }
        val others = sungWords.indices.drop(1).map { 10_200L + it * 4_000L }
        // Rushed, but where the other sheet has them, near enough: Into the Groove opens with one sung on time.
        rushedSheets(evenSheet(), netEaseSheet(rushed = arrayOf(Triple(0, 11_700L, 160L))))
        assertEquals(listOf(11_700L) + others, times())
        // Off, but unhurried.
        rushedSheets(evenSheet(), netEaseSheet(rushed = arrayOf(Triple(0, 13_000L, 400L))))
        assertEquals(listOf(13_000L) + others, times())
        // Rushed and earlier than the other sheet has it: it isn't catching up with anything.
        rushedSheets(evenSheet().map { (ms, text) -> if (ms == 10_000L) 13_000L to text else ms to text }, netEaseSheet(rushed = arrayOf(Triple(0, 10_200L, 160L))))
        assertEquals(listOf(10_200L) + others, times())
    }

    @Test
    fun rushedLinesStayWithoutASheetToGoBy() {
        fun times() = (found(rushedSong) as Lyrics.Found).lines.map { it.timeMs }
        // Five lines in common aren't enough to tell where the rest are.
        rushedSheets(evenSheet(5), netEaseSheet(5, rushed = arrayOf(Triple(0, 13_000L, 160L))))
        assertEquals(listOf(13_000L) + (1..4).map { 10_200L + it * 4_000L }, times())
        // Nor is a sheet timed all over the place.
        val jumbled = listOf(0L, 6_500L, 200L, 7_000L, 600L, 6_500L, 100L, 8_000L, 300L)
        rushedSheets(evenSheet(), sungWords.indices.map { Triple(if (it == 0) 13_000L else 10_000L + it * 4_000L + jumbled[it], if (it == 0) 160L else 400L, sungWords[it]) })
        assertEquals(13_000L, times().first())
    }

    @Test
    fun sixLinesInCommonAreEnoughToTellWhereARushedLineBelongs() {
        // Five aren't (above); one more is.
        rushedSheets(evenSheet(6), netEaseSheet(6, rushed = arrayOf(Triple(0, 13_000L, 160L))))
        assertEquals(listOf(10_200L) + (1..5).map { 10_200L + it * 4_000L }, (found(rushedSong) as Lyrics.Found).lines.map { it.timeMs })
    }

    @Test
    fun aLineIsOnlyRushedWhenItsWordsGoByFasterThanTwoHundredMillisecondsEach() {
        fun times() = (found(rushedSong) as Lyrics.Found).lines.map { it.timeMs }
        val others = sungWords.indices.drop(1).map { 10_200L + it * 4_000L }
        // Exactly that is sung quickly, not hurried.
        rushedSheets(evenSheet(), netEaseSheet(rushed = arrayOf(Triple(0, 13_000L, 200L))))
        assertEquals(listOf(13_000L) + others, times())
        // A little faster is.
        rushedSheets(evenSheet(), netEaseSheet(rushed = arrayOf(Triple(0, 13_000L, 199L))))
        assertEquals(listOf(10_200L) + others, times())
    }

    /** LRCLIB with [lrclib], timed for a recording 6 seconds shorter than [anotherTake]'s, and NetEase with [netEase], as long as it. */
    private fun twoSheets(lrclib: List<Pair<Long, String>>, netEase: List<Pair<Long, String>>) {
        fun sheet(lines: List<Pair<Long, String>>) = lines.joinToString("\n") { (ms, text) -> "[%02d:%02d.%02d]%s".format(ms / 60_000, ms / 1_000 % 60, ms % 1_000 / 10, text) }
        fake.handler = { request ->
            val url = request.url
            when {
                url.host == "lrclib.net" -> FakeSpotify.html(request, "[${lasting(answer("Song", "Band", "", synced = sheet(lrclib)), 260.0)}]")
                url.encodedPath == "/api/search/get" -> FakeSpotify.html(request, """{"result":{"songs":[{"id":1,"name":"Song","artists":[{"name":"Band"}],"duration":266000}]}}""")
                url.encodedPath == "/api/song/lyric" -> FakeSpotify.html(request, """{"lrc":{"lyric":${quote(sheet(netEase))}}}""")
                else -> FakeSpotify.html(request, """{"results":[]}""")
            }
        }
    }

    private val anotherTake = MusicMetadata("Song", "Band", lengthMs = 266_100)

    private val sungWords = listOf("Every word I sing here", "Falls down around my feet", "Nothing left to say now", "Under the city lights", "Waiting for the morning", "Holding on to nothing", "Running from the thunder", "Calling out your name", "Singing till the sun comes")

    private fun takenLines() = (found(anotherTake) as Lyrics.Found).lines.map { it.timeMs to it.text }

    @Test
    fun wordsAreOnlyToldTimedApartWithSixLinesInCommon() {
        // Each line a few seconds later, then hardly: no take is sung like that.
        fun lines(count: Int) = sungWords.take(count).mapIndexed { line, text -> 10_000L + line * 4_000L to text }
        fun jumping(count: Int) = lines(count).mapIndexed { line, (ms, text) -> ms + (if (line % 2 == 0) 3_000 else 500) to text }
        twoSheets(lines(6), jumping(6))
        assertEquals(lines(6), takenLines())
        // Five lines are too few to tell a take from words timed badly, so NetEase's, as long as the recording, stay.
        twoSheets(lines(5), jumping(5))
        assertEquals(jumping(5), takenLines())
    }

    @Test
    fun wordsAreToldTimedApartWhenTheGapBetweenThemJumpsMoreOftenThanNot() {
        val lrclib = sungWords.mapIndexed { line, text -> 10_000L + line * 4_000L to text } + (50_000L to "And that's where it ends")
        // NetEase's have a line to open with and one in the middle LRCLIB's don't, and lack LRCLIB's last.
        fun netEase(laterBy: List<Long>) = listOf(8_000L to "Count us in before we start") +
            sungWords.take(5).mapIndexed { line, text -> lrclib[line].first + laterBy[line] to text } + (35_500L to "A line only they have") +
            sungWords.drop(5).mapIndexed { line, text -> lrclib[line + 5].first + laterBy[line + 5] to text }
        // Six seconds later, then nine from the fifth line, back and forth: 4 of 8 gaps jump, as in a take cut here and there.
        val half = netEase(listOf(6_000L, 6_000, 6_000, 6_000, 6_000, 9_000, 6_000, 9_000, 6_000))
        twoSheets(lrclib, half)
        assertEquals(half, takenLines())
        // 5 of 8: more often than not, so no take is sung like that.
        twoSheets(lrclib, netEase(listOf(6_000L, 6_000, 6_000, 6_000, 9_000, 6_000, 9_000, 6_000, 9_000)))
        assertEquals(lrclib, takenLines())
    }

    @Test
    fun wordsInOtherLettersCantBeToldTimedApartSoTheOnesTimedForARecordingAsLongStay() {
        val words = listOf("ここにいて", "あなたのてを", "ゆびのさきが", "みゃくはくを", "すこしは", "ほらみどりの", "しあわせの", "なつにきらめいて")
        val lrclib = words.mapIndexed { line, text -> 10_000L + line * 4_000L to text }
        val jumbled = listOf(13_000L, 19_500L, 20_200L, 27_000L, 27_600L, 33_500L, 34_100L, 42_000L).zip(words)
        twoSheets(lrclib, jumbled)
        assertEquals(jumbled, takenLines())
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

    private companion object {
        /** Marc Strain's "Smalltown Boy" in the iTunes Store, and Bronski Beat's. */
        const val MARC_STRAIN = 1741946001L
        const val BRONSKI_BEAT = 1320069110L
    }
}
