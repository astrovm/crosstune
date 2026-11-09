package com.astrovm.crosstune

import com.astrovm.crosstune.LyricsFinder.Lyrics
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A song no service has words for is an instrumental when the store files it where only those are. */
@RunWith(RobolectricTestRunner::class)
class WordlessSongTest {
    private val fake = FakeSpotify()
    private val song = MusicMetadata("Rain Piano", "Fake Trio")

    private fun store(genre: String, title: String = song.title) =
        """{"results":[{"trackId":7,"trackName":"$title","artistName":"Fake Trio","trackTimeMillis":200000,"primaryGenreName":"$genre"}]}"""

    private fun artists(genre: String, name: String = "Fake Trio") = """{"results":[{"artistName":"$name","primaryGenreName":"$genre"}]}"""

    private val noArtists = """{"results":[]}"""

    /** The store answers a search for artists with [artistAnswer] and every other with [storeAnswer]. */
    private fun respond(storeAnswer: String, genius: String = "{}", artistAnswer: String = noArtists) {
        fake.handler = { request ->
            when (request.url.host) {
                "itunes.apple.com" -> FakeSpotify.html(request, if (request.url.queryParameter("entity") == "musicArtist") artistAnswer else storeAnswer)
                "lrclib.net" -> FakeSpotify.html(request, "[]")
                "genius.com" -> FakeSpotify.html(request, genius)
                else -> FakeSpotify.html(request, "{}")
            }
        }
    }

    private fun lookup() = runBlocking { LyricsFinder(fake.client(), "test", busyPauseMs = 0).lyricsOf(song) }

    private fun lyrics(title: String) = runBlocking {
        LyricsFinder(fake.client(), "test", busyPauseMs = 0).lyricsOf(MusicMetadata(title, "Fake Trio"))
    }

    /** Store searches for this song. A search for who first sang a title doesn't name the artist. */
    private fun storeSearches() = fake.requestedUrls.count { "itunes.apple.com/search" in it && "entity=song" in it && "Fake%20Trio" in it }

    private fun artistSearches() = fake.requestedUrls.count { "itunes.apple.com/search" in it && "entity=musicArtist" in it }

    private fun storeLookups() = fake.requestedUrls.count { "itunes.apple.com/lookup" in it }

    /** The store only answers [stored] when the search spells the movement [storedMark], so the first search misses. */
    private fun findsTheMovement(played: String, stored: String, storedMark: String) {
        check(storedMark !in "$played Fake Trio") { "$storedMark is already in $played" }
        val before = storeSearches()
        fake.handler = { request ->
            val term = request.url.queryParameter("term").orEmpty()
            when {
                request.url.host == "itunes.apple.com" && storedMark in term -> FakeSpotify.html(request, store("Classical", stored))
                request.url.host == "itunes.apple.com" -> FakeSpotify.html(request, """{"results":[]}""")
                request.url.host == "lrclib.net" -> FakeSpotify.html(request, "[]")
                else -> FakeSpotify.html(request, "{}")
            }
        }
        assertEquals(played, Lyrics.Instrumental, lyrics(played))
        assertEquals(played, before + 2, storeSearches())
    }

    /** [stored] is filed as an instrumental, and only a search that already says [otherMark] can see it. */
    private fun leavesItUnfound(played: String, otherMark: String, stored: String) {
        check(otherMark !in "$played Fake Trio") { "$otherMark is already in $played" }
        val searches = storeSearches()
        val lookups = storeLookups()
        fake.handler = { request ->
            val term = request.url.queryParameter("term").orEmpty()
            when {
                request.url.host == "itunes.apple.com" && otherMark in term -> FakeSpotify.html(request, store("Classical", stored))
                request.url.host == "lrclib.net" -> FakeSpotify.html(request, "[]")
                else -> FakeSpotify.html(request, "{}")
            }
        }
        assertEquals(played, Lyrics.None, lyrics(played))
        assertEquals(played, searches + 1, storeSearches())
        assertEquals(played, lookups, storeLookups())
    }

    @Test
    fun aSongOfAnInstrumentalGenreWithNoWordsAnywhereIsAnInstrumental() {
        for (genre in listOf("Classical", "New Age", "Jazz", "Latin Jazz", "Bop")) {
            respond(store(genre))
            assertEquals(genre, Lyrics.Instrumental, lookup())
        }
    }

    @Test
    fun aSongOfAnyOtherGenreWithNoWordsIsOneNotFound() {
        for (genre in listOf("Pop", "Hip-Hop/Rap", "Latin", "Folk", "")) {
            respond(store(genre))
            assertEquals(genre, Lyrics.None, lookup())
        }
        respond("""{"results":[]}""")
        assertEquals(Lyrics.None, lookup())
        // The genre is the song's own, not another's of the same artist.
        respond(store("Jazz", title = "Another Tune"))
        assertEquals(Lyrics.None, lookup())
    }

    @Test
    fun aMovementTheStoreNumbersInRomanIsStillThatInstrumental() {
        // The store files "I." and a player says "1.". A search for the player's spelling finds nothing.
        val played = "Fake Piece, Op. 3: 1. Allegro"
        val stored = "Fake Piece, Op. 3: I. Allegro"
        fake.handler = { request ->
            val term = request.url.queryParameter("term").orEmpty()
            when {
                request.url.host == "itunes.apple.com" && ": 1." in term -> FakeSpotify.html(request, """{"results":[]}""")
                request.url.host == "itunes.apple.com" && ": I." in term -> FakeSpotify.html(request, store("Classical", stored))
                request.url.host == "lrclib.net" -> FakeSpotify.html(request, "[]")
                else -> FakeSpotify.html(request, "{}")
            }
        }
        val before = storeSearches()
        assertEquals(Lyrics.Instrumental, lyrics(played))
        assertEquals(before + 2, storeSearches())
    }

    @Test
    fun aMovementTheFirstAnswerAlreadySpellsTheOtherWayIsThatInstrumental() {
        // The store's own search already returned "I." for a player that said "1.", so nothing is asked again.
        val played = "Fake Piece: 1. Allegro"
        val before = storeSearches()
        fake.handler = { request ->
            when (request.url.host) {
                "itunes.apple.com" -> FakeSpotify.html(request, store("Classical", "Fake Piece: I. Allegro"))
                "lrclib.net" -> FakeSpotify.html(request, "[]")
                else -> FakeSpotify.html(request, "{}")
            }
        }
        assertEquals(Lyrics.Instrumental, lyrics(played))
        assertEquals(before + 1, storeSearches())
    }

    @Test
    fun aMovementIndexIsTheSameWrittenInArabicOrRoman() {
        // 5 is here so "V" is a part that gets written, not only skipped on the way to "X".
        val pairs = listOf("1" to "I", "2" to "II", "4" to "IV", "5" to "V", "9" to "IX", "14" to "XIV", "39" to "XXXIX")
        for ((arabic, roman) in pairs) {
            findsTheMovement("Fake Piece: $arabic. Allegro", "Fake Piece: $roman. Allegro", ": $roman.")
            findsTheMovement("Fake Piece: $roman. Allegro", "Fake Piece: $arabic. Allegro", ": $arabic.")
        }
        findsTheMovement("1. Allegro", "I. Allegro", "I.")
        findsTheMovement("I. Allegro", "1. Allegro", "1.")
        findsTheMovement("Fake Piece:1. Allegro", "Fake Piece:I. Allegro", ":I.")
        findsTheMovement("Fake Piece: iv. Allegro", "Fake Piece: 4. Allegro", ": 4.")
    }

    @Test
    fun aMovementSpelledTheOtherWayInASungGenreIsStillNotFound() {
        val played = "Fake Piece: 1. Chorus"
        fake.handler = { request ->
            val term = request.url.queryParameter("term").orEmpty()
            when {
                request.url.host == "itunes.apple.com" && ": I." in term -> FakeSpotify.html(request, store("Pop", "Fake Piece: I. Chorus"))
                request.url.host == "itunes.apple.com" -> FakeSpotify.html(request, """{"results":[]}""")
                request.url.host == "lrclib.net" -> FakeSpotify.html(request, "[]")
                else -> FakeSpotify.html(request, "{}")
            }
        }
        val lookups = storeLookups()
        assertEquals(Lyrics.None, lyrics(played))
        assertEquals(lookups + 1, storeLookups())
    }

    @Test
    fun aDifferentMovementIsNotThatPiece() {
        val played = "Fake Piece: II. Allegro"
        val before = storeSearches()
        val lookups = storeLookups()
        fake.handler = { request ->
            when (request.url.host) {
                "itunes.apple.com" -> FakeSpotify.html(request, if (request.url.queryParameter("entity") == "musicArtist") noArtists else store("Classical", "Fake Piece: I. Andante"))
                "lrclib.net" -> FakeSpotify.html(request, "[]")
                else -> FakeSpotify.html(request, "{}")
            }
        }
        assertEquals(Lyrics.None, lyrics(played))
        assertEquals(before + 2, storeSearches())
        assertEquals(lookups, storeLookups())
    }

    @Test
    fun aNumberThatIsNotAMovementIndexStaysUnfound() {
        leavesItUnfound("I Want You", "1 Want You", "1 Want You")
        leavesItUnfound("Fake Work, Op. 3. Allegro", "III", "Fake Work, Op. III. Allegro")
        leavesItUnfound("Fake Piece: IIII. Allegro", ": 4.", "Fake Piece: 4. Allegro")
        leavesItUnfound("Fake Piece: 99. Allegro", "XXXXXXXXXIX", "Fake Piece: XXXXXXXXXIX. Allegro")
        leavesItUnfound("Fake Piece: 01. Allegro", ": I.", "Fake Piece: I. Allegro")
        leavesItUnfound("Fake Piece: 40. Allegro", "XXXX", "Fake Piece: XXXX. Allegro")
        leavesItUnfound("Fake Piece: L. Allegro", ": 50.", "Fake Piece: 50. Allegro")
        leavesItUnfound("Fake Piece: XL. Allegro", ": 40.", "Fake Piece: 40. Allegro")
        // "XXXX" is how the usual parts write 40, so only the range keeps it out.
        leavesItUnfound("Fake Piece: XXXX. Allegro", ": 40.", "Fake Piece: 40. Allegro")
        leavesItUnfound("Fake Piece: C. Allegro", ": 100.", "Fake Piece: 100. Allegro")
        leavesItUnfound("Fake Piece: D. Allegro", ": 500.", "Fake Piece: 500. Allegro")
        leavesItUnfound("Fake Piece: M. Allegro", ": 1000.", "Fake Piece: 1000. Allegro")
    }

    @Test
    fun wordsFoundAnywhereAreNeverHiddenByTheGenre() {
        val hit = """{"response":{"sections":[{"hits":[{"type":"song","result":{"title":"Rain Piano","artist_names":"Fake Trio","primary_artist_names":"Fake Trio","path":"/Fake-trio-rain-piano-lyrics"}}]}]}}"""
        fake.handler = { request ->
            when {
                request.url.host == "itunes.apple.com" -> FakeSpotify.html(request, store("Jazz"))
                request.url.host == "genius.com" && request.url.encodedPath == "/api/search/multi" -> FakeSpotify.html(request, hit)
                request.url.host == "genius.com" -> FakeSpotify.html(request, """<div data-lyrics-container="true">Rain falls down</div>""")
                request.url.host == "lrclib.net" -> FakeSpotify.html(request, "[]")
                else -> FakeSpotify.html(request, "{}")
            }
        }
        assertEquals(Lyrics.Found("Rain falls down", source = LyricsFinder.GENIUS), lookup())
    }

    @Test
    fun anUnlistedSongOfAnArtistTheStoreFilesWhereOnlyInstrumentalsAreIsAnInstrumental() {
        for (genre in listOf("Classical", "New Age", "Jazz", "Latin Jazz", "Bop", "Mainstream Jazz")) {
            respond(noArtists, artistAnswer = artists(genre))
            assertEquals(genre, Lyrics.Instrumental, lookup())
        }
        // The artist may be written a little differently, and is the one of several the store lists.
        fake.requestedUrls.clear()
        respond(noArtists, artistAnswer = """{"results":[{"artistName":"Someone Else","primaryGenreName":"Pop"},{"artistName":"The Fake Trio","primaryGenreName":"Classical"}]}""")
        assertEquals(Lyrics.Instrumental, lookup())
        // The store is asked about the artist by name, in the US store, once.
        val asked = fake.requestedUrls.filter { "entity=musicArtist" in it }
        assertEquals(1, asked.size)
        assertEquals(true, "term=Fake%20Trio" in asked.single() && "country=us" in asked.single())
    }

    @Test
    fun anUnlistedSongOfAnyOtherArtistIsOneNotFound() {
        for (genre in listOf("Pop", "Hip-Hop/Rap", "Latin", "Folk", "")) {
            respond(noArtists, artistAnswer = artists(genre))
            assertEquals(genre, Lyrics.None, lookup())
        }
        respond(noArtists)
        assertEquals(Lyrics.None, lookup())
        // Another artist's genre is not this one's.
        respond(noArtists, artistAnswer = artists("Classical", name = "Someone Else"))
        assertEquals(Lyrics.None, lookup())
        respond(noArtists, artistAnswer = "{}")
        assertEquals(Lyrics.None, lookup())
    }

    @Test
    fun anArtistTheStoreWontAnswerForLeavesTheSongNotFound() {
        fake.handler = { request ->
            when {
                request.url.host == "itunes.apple.com" && request.url.queryParameter("entity") == "musicArtist" -> throw java.io.IOException("offline")
                request.url.host == "itunes.apple.com" -> FakeSpotify.html(request, noArtists)
                request.url.host == "lrclib.net" -> FakeSpotify.html(request, "[]")
                else -> FakeSpotify.html(request, "{}")
            }
        }
        assertEquals(Lyrics.None, lookup())
        respond(noArtists, artistAnswer = "not json")
        assertEquals(Lyrics.None, lookup())
    }

    @Test
    fun theArtistIsOnlyAskedAboutWhenTheStoreDoesNotListTheSong() {
        respond(store("Pop"), artistAnswer = artists("Classical"))
        assertEquals(Lyrics.None, lookup())
        // Listed under a genre of its own, the song's genre wins over the artist's.
        assertEquals(0, artistSearches())
    }

    @Test
    fun wordsFoundForAnUnlistedSongAreNeverHiddenByTheArtistsGenre() {
        val hit = """{"response":{"sections":[{"hits":[{"type":"song","result":{"title":"Rain Piano","artist_names":"Fake Trio","primary_artist_names":"Fake Trio","path":"/Fake-trio-rain-piano-lyrics"}}]}]}}"""
        fake.handler = { request ->
            when {
                request.url.host == "itunes.apple.com" -> FakeSpotify.html(request, if (request.url.queryParameter("entity") == "musicArtist") artists("Classical") else noArtists)
                request.url.host == "genius.com" && request.url.encodedPath == "/api/search/multi" -> FakeSpotify.html(request, hit)
                request.url.host == "genius.com" -> FakeSpotify.html(request, """<div data-lyrics-container="true">Rain falls down</div>""")
                request.url.host == "lrclib.net" -> FakeSpotify.html(request, "[]")
                else -> FakeSpotify.html(request, "{}")
            }
        }
        assertEquals(Lyrics.Found("Rain falls down", source = LyricsFinder.GENIUS), lookup())
    }
}
