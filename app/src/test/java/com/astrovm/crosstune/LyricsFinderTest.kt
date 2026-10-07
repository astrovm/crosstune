package com.astrovm.crosstune

import com.astrovm.crosstune.LyricsFinder.Lyrics
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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

    private fun found(song: MusicMetadata) = runBlocking { LyricsFinder(fake.client(), "2.3.3").lyricsOf(song) }

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

        // Synced words carry their timings inline, so they're not what a song's lyrics are.
        respond("""[{"trackName":"Song","artistName":"Band","syncedLyrics":"[00:01.00] Timed"}]""")
        assertEquals(Lyrics.None, found(MusicMetadata("Song", "Band")))
    }

    @Test
    fun aBusyOrMissingServiceSaysSoRatherThanClaimingThereAreNoWords() {
        // LRCLIB is often busy, and a lookup that failed is not a song without words.
        fake.handler = { throw IOException("offline") }
        assertEquals(Lyrics.Unavailable, found(MusicMetadata("Song", "Band")))
        // Asked once more, since one song's words are worth the second try.
        assertEquals(2, fake.requestedUrls.size)

        respond("""[${answer("Song", "Band", "Words")}]""")
        fake.delayMillis = 300
        assertEquals(Lyrics.Unavailable, runBlocking { LyricsFinder(fake.client(), "2.3.3").lyricsOf(MusicMetadata("Song", "Band"), timeoutMs = 20L) })
        fake.delayMillis = 0

        // Its own error page isn't an answer either.
        respond("""{"message":"The server is busy","statusCode":503}""")
        assertEquals(Lyrics.Unavailable, found(MusicMetadata("Song", "Band")))

        respond("not json at all")
        assertEquals(Lyrics.Unavailable, found(MusicMetadata("Song", "Band")))
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
}