package com.astrovm.crosstune

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** Runs under Robolectric for the real org.json implementation. */
@RunWith(RobolectricTestRunner::class)
class SongSearcherTest {

    private val fake = FakeSpotify()

    /** Deezer answers unless a request is for Apple, so one handler covers a whole search. */
    private fun respond(deezer: String, itunes: String = """{"results":[]}""") {
        fake.handler = { request: Request ->
            if (request.url.host == "itunes.apple.com") FakeSpotify.html(request, itunes) else FakeSpotify.html(request, deezer)
        }
    }

    private fun found(query: String) = runBlocking { SongSearcher(fake.client(), country = "AR").search(query) }

    private val cover = "https://cdn-images.dzcdn.net/images/cover/abc/500x500-80-0-0.jpg"

    @Test
    fun aSongIsFoundByItsNameAndKeepsItsOwnLink() {
        respond(
            """{"data":[{"id":608098722,"title":"Dakare Ni Kita Onna","link":"https://www.deezer.com/track/608098722",
                "artist":{"name":"Kingo Hamada"},"album":{"cover_big":"$cover"}}]}"""
        )
        val songs = found("Dakare Ni Kita Onna")
        assertEquals(
            listOf(MusicMetadata("Dakare Ni Kita Onna", "Kingo Hamada", url = "https://www.deezer.com/track/608098722", artworkUrl = cover)),
            songs
        )
        // Apple is asked alongside, so either can come first.
        val asked = fake.requestedUrls.first { it.startsWith("https://api.deezer.com/") }
        assertTrue(asked, asked.startsWith("https://api.deezer.com/search/track?q="))
        assertTrue(asked, asked.contains("Dakare%20Ni%20Kita%20Onna"))
    }

    @Test
    fun appleAnswersWhenDeezerHasNothing() {
        respond(
            """{"data":[]}""",
            """{"results":[{"trackName":"Love Me Do","artistName":"The Beatles","trackViewUrl":"https://music.apple.com/us/song/1","artworkUrl100":"https://is1/cover.jpg"}]}"""
        )
        assertEquals(
            listOf(MusicMetadata("Love Me Do", "The Beatles", url = "https://music.apple.com/us/song/1", artworkUrl = "https://is1/cover.jpg")),
            found("Love Me Do")
        )
        assertTrue(fake.requestedUrls.any { it.startsWith("https://itunes.apple.com/search?") })
        // Apple's own country is asked, so it answers with what that country carries.
        assertTrue(fake.requestedUrls.toString(), fake.requestedUrls.any { it.contains("country=AR") })
    }

    /** Hong Kong's store answers its own query, the country's store another. */
    private fun respondByStore(hongKong: String) {
        fake.handler = { request: Request ->
            val body = if (request.url.queryParameter("country") == "HK") hongKong else """{"results":[],"data":[]}"""
            FakeSpotify.html(request, body)
        }
    }

    private val jayChou = """{"results":[
        {"trackName":"晴天","artistName":"周杰倫","trackViewUrl":"https://music.apple.com/hk/song/1721464906"},
        {"trackName":"七里香","artistName":"周杰倫","trackViewUrl":"https://music.apple.com/hk/song/1721450037"}]}"""

    @Test
    fun aChineseSongTypedInPinyinIsFoundByItsName() {
        respondByStore(jayChou)
        // Hong Kong's store finds both by "jay chou", but only 晴天 is what was typed.
        assertEquals(
            listOf(MusicMetadata("晴天", "周杰倫", url = "https://music.apple.com/hk/song/1721464906")),
            found("qing tian jay chou")
        )
    }

    @Test
    fun aSearchInChineseLettersDoesntAskForPinyin() {
        respondByStore(jayChou)
        assertEquals(emptyList<MusicMetadata>(), found("晴天"))
        assertTrue(fake.requestedUrls.toString(), fake.requestedUrls.none { it.contains("country=HK") })
    }

    @Test
    fun aSongWithoutChineseInItsNameIsntTakenForPinyin() {
        respondByStore("""{"results":[{"trackName":"Qing Tian","artistName":"Someone","trackViewUrl":"https://music.apple.com/hk/song/2"}]}""")
        assertEquals(emptyList<MusicMetadata>(), found("qing tian"))
    }

    @Test
    fun deezersAnswersLeadAndARepeatedSongIsOne() {
        respond(
            """{"data":[{"id":1,"title":"Love Me Do","link":"https://www.deezer.com/track/1","artist":{"name":"The Beatles"}}]}""",
            """{"results":[{"trackName":"Love Me Do","artistName":"The Beatles","trackViewUrl":"https://music.apple.com/us/song/1"},
                       {"trackName":"Yesterday","artistName":"The Beatles","trackViewUrl":"https://music.apple.com/us/song/2"}]}"""
        )
        // The same song from both services is one song, and Deezer's comes first.
        assertEquals(
            listOf(
                MusicMetadata("Love Me Do", "The Beatles", url = "https://www.deezer.com/track/1"),
                MusicMetadata("Yesterday", "The Beatles", url = "https://music.apple.com/us/song/2")
            ),
            found("Love Me Do")
        )
    }

    @Test
    fun aSongComesBeforeACoverNamedJustLikeIt() {
        // As Deezer answers: a violin cover named word for word as typed, then the song.
        respond(
            """{"data":[
                {"id":1,"title":"Quevedo BZRP Music Sessions, Vol. 52","rank":39919,"link":"https://www.deezer.com/track/1","artist":{"name":"Carlos Ro Violin"}},
                {"id":2,"title":"Shakira: Bzrp Music Sessions, Vol. 53","rank":900000,"link":"https://www.deezer.com/track/2","artist":{"name":"Bizarrap"}},
                {"id":3,"title":"Quevedo: Bzrp Music Sessions, Vol. 52/66","rank":764325,"link":"https://www.deezer.com/track/3","artist":{"name":"Bizarrap"}}
            ]}"""
        )
        // Of those with all that was typed, the one more people play leads; one with less of it,
        // however played, follows.
        assertEquals(
            listOf("Quevedo: Bzrp Music Sessions, Vol. 52/66", "Quevedo BZRP Music Sessions, Vol. 52", "Shakira: Bzrp Music Sessions, Vol. 53"),
            found("Quevedo Bzrp Music Sessions Vol 52").map { it.title }
        )
    }

    @Test
    fun nothingToSearchAndNothingFoundBothFindNothing() {
        respond("""{"data":[]}""")
        assertEquals(emptyList<MusicMetadata>(), found("   "))
        assertEquals(emptyList<MusicMetadata>(), found("qqqqzzzznotasong"))

        // Rows that name no song, or no artist to go with the name, are left out.
        respond("""{"data":[{"id":1,"title":"","link":"https://www.deezer.com/track/1","artist":{"name":"Band"}}]}""")
        assertEquals(emptyList<MusicMetadata>(), found("Song"))
        respond("""{"data":[{"id":1,"title":"Song","link":"https://www.deezer.com/track/1","artist":{"name":""}}]}""")
        assertEquals(emptyList<MusicMetadata>(), found("Song"))

        // Its version in brackets, as other services write it.
        respond("""{"data":[{"id":1,"title":"Matador Remasterizado 2008","title_short":"Matador","title_version":"Remasterizado 2008","link":"https://www.deezer.com/track/1","artist":{"name":"Los Fabulosos Cadillacs"}}]}""")
        assertEquals(listOf("Matador (Remasterizado 2008)"), found("Matador").map { it.title })
        respond("""{"data":[null,7,"nope"]}""")
        assertEquals(emptyList<MusicMetadata>(), found("Song"))

        // A row with no link or cover is still a song; the words are what matter.
        respond("""{"data":[{"id":1,"title":"Song","artist":{"name":"Band"}}]}""")
        val bare = found("Song").single()
        assertEquals("Song", bare.title)
        assertNull(bare.url)
        assertNull(bare.artworkUrl)
    }

    @Test
    fun oneServiceBeingOfflineCostsOnlyItsOwnAnswers() {
        fake.handler = { request: Request ->
            if (request.url.host == "api.deezer.com") throw IOException("offline")
            FakeSpotify.html(
                request,
                """{"results":[{"trackName":"Yesterday","artistName":"The Beatles",
                    "trackViewUrl":"https://music.apple.com/us/song/2","artworkUrl100":"https://is1/cover.jpg"}]}"""
            )
        }
        // Deezer refusing must not cost the search Apple's answer to the same words.
        assertEquals(
            listOf(MusicMetadata("Yesterday", "The Beatles", url = "https://music.apple.com/us/song/2", artworkUrl = "https://is1/cover.jpg")),
            found("Yesterday")
        )
        assertTrue(fake.requestedUrls.any { it.startsWith("https://itunes.apple.com/search?") })
    }

    @Test
    fun oddOrRefusedAnswersCostOnlyWhatsOddInThem() {
        // Deezer refusing, Apple answering in bits: what's whole of Apple's is kept, nothing else.
        fake.handler = { request: Request ->
            if (request.url.host == "api.deezer.com") FakeSpotify.html(request, "Service unavailable", code = 503)
            else FakeSpotify.html(
                request,
                """{"results":[1,"two",{"trackName":"","artistName":"Band"},{"trackName":"Song","artistName":""},
                    {"trackName":"Whole","artistName":"Band"}]}"""
            )
        }
        assertEquals(listOf(MusicMetadata("Whole", "Band")), found("Whole"))

        // Deezer's bits too: an artist that isn't one, a song with no artist or album, and one whole.
        respond(
            """{"data":[null,"x",{"title":"No artist"},{"title":"Odd artist","artist":"Band"},
                {"title":"Whole","artist":{"name":"Band"},"album":"none"}]}""",
            itunes = """["not","an","object"]"""
        )
        assertEquals(listOf(MusicMetadata("Whole", "Band")), found("Whole"))

        // Neither answering with what it should: nothing found, nothing thrown.
        respond("""{"data":"none"}""", itunes = "<html>Error</html>")
        assertEquals(emptyList<MusicMetadata>(), found("Whole"))
    }

    @Test
    fun aCoverThatIsBlankIsNoCoverAndWordsMadeOfMarksAreNoMoreThanTheRest() {
        respond("""{"data":[{"id":1,"title":"Song","link":"https://www.deezer.com/track/1","artist":{"name":"Band"},"album":{"cover_big":""}}]}""")
        assertEquals(listOf(MusicMetadata("Song", "Band", url = "https://www.deezer.com/track/1")), found(" Song !! "))
    }

    @Test
    fun appleIsAskedForNoStoreWhenTheCountryIsUnknown() {
        respond("""{"data":[]}""")
        runBlocking { SongSearcher(fake.client(), country = "").search("Song") }
        val apple = fake.requestedUrls.filter { it.startsWith("https://itunes.apple.com/") }
        // The Chinese search asks Hong Kong's store; the other asks no store at all.
        assertTrue(apple.toString(), apple.any { "country=" !in it } && apple.none { "country=" in it && !it.endsWith("country=HK") })
    }

    @Test
    fun noServiceAndNoTimeBothFindNothing() {
        fake.handler = { throw IOException("offline") }
        assertEquals(emptyList<MusicMetadata>(), found("Song"))

        respond("""{"data":[{"id":1,"title":"Song","link":"https://www.deezer.com/track/1","artist":{"name":"Band"}}]}""")
        fake.delayMillis = 300
        // A search that runs past the time the list waits gives up rather than hang there empty.
        assertEquals(
            emptyList<MusicMetadata>(),
            runBlocking { SongSearcher(fake.client()).search("Song", timeoutMs = 20L) }
        )
        fake.delayMillis = 0
    }
}