package com.astrovm.crosstune

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
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

/** Runs under Robolectric for the real org.json. */
@RunWith(RobolectricTestRunner::class)
class MusicBrainzTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val fake = FakeSpotify()
    private val song = MusicMetadata("De Música Ligera", "Soda Stereo")

    private fun brainz(file: File = File(folder.root, "musicbrainz.json"), gapMs: Long = 0) =
        MusicBrainz(fake.client(), "2.3.3", LookupCache(file, Dispatchers.Unconfined), Dispatchers.Unconfined, gapMs)

    private fun group(title: String, date: String, secondary: List<String>? = null, id: String = title) = JSONObject()
        .put("id", id).put("title", title).put("primary-type", "Album").put("first-release-date", date)
        .apply { secondary?.let { put("secondary-types", JSONArray(it)) } }

    private fun release(group: JSONObject) = JSONObject().put("title", group.getString("title")).put("release-group", group)

    private fun recording(
        id: String,
        date: String?,
        title: String = "De música ligera",
        credit: String = "Soda Stereo",
        note: String = "",
        on: List<JSONObject> = listOf(group("Una parte de la euforia", "1991", listOf("Compilation"))),
        aliases: List<String> = emptyList()
    ) = JSONObject().put("id", id).put("title", title).put("disambiguation", note)
        .put("artist-credit", JSONArray().put(JSONObject().put("name", credit).put("joinphrase", "").put("artist", JSONObject().put("name", credit).put("aliases", JSONArray(aliases.map { JSONObject().put("name", it) })))))
        .put("releases", JSONArray(on.map(::release)))
        .apply { date?.let { put("first-release-date", it) } }

    /** MusicBrainz answering a search with [recordings], and each recording's releases with [albums]. */
    private fun answering(recordings: List<JSONObject>, albums: Map<String, List<JSONObject>> = emptyMap()) {
        fake.handler = { request ->
            val body = when (request.url.encodedPath) {
                "/ws/2/recording" -> JSONObject().put("recordings", JSONArray(recordings))
                else -> JSONObject().put("releases", JSONArray(albums[request.url.queryParameter("recording")].orEmpty().map(::release)))
            }
            FakeSpotify.html(request, body.toString())
        }
    }

    private fun facts(song: MusicMetadata = this.song, brainz: MusicBrainz = brainz()) = runBlocking { brainz.release(song) }

    @Test
    fun theYearIsTheEarliestRecordingsAndTheAlbumIsThePlainOneItCameOutOn() {
        val studio = group("Canción animal", "1990-09")
        answering(
            listOf(
                recording("remix", "2017-03-10", note = "SEP7IMO DIA"),
                recording("live", "1997-12-16", note = "live, 1997-09-20: Estadio River Plate"),
                recording("studio", "1990"),
                recording("other-live", "2008", on = listOf(group("Me verás volver", "2008", listOf("Live"))))
            ),
            mapOf("studio" to listOf(group("Una parte de la euforia", "1991", listOf("Compilation")), studio, group("Canción animal", "1990-09", id = "again"), group("Canción animal (30 años)", "2020")))
        )
        assertEquals(ReleaseFacts(1990, "Canción animal"), facts())
        val search = fake.requestedUrls.first().toHttpUrl()
        assertEquals("https://musicbrainz.org/ws/2/recording", search.toString().substringBefore('?'))
        assertEquals("recording:\"De Música Ligera\" AND artist:\"Soda Stereo\" AND status:official AND primarytype:(album OR single OR ep)", search.queryParameter("query"))
        assertEquals("100", search.queryParameter("limit"))
        val albums = fake.requestedUrls.last().toHttpUrl()
        assertEquals("/ws/2/release", albums.encodedPath)
        assertEquals(listOf("studio", "official", "album", "release-groups", "100"), listOf("recording", "status", "type", "inc", "limit").map { albums.queryParameter(it) })
        // It says who is asking, with a way to be told about a problem.
        assertEquals("Crosstune/2.3.3 (https://github.com/astrovm/crosstune)", fake.requestHeaders.first { it.first == "User-Agent" }.second)
    }

    @Test
    fun aSongIsAskedAboutOnceAndRemembersWhenThereWasNoAnswer() {
        answering(listOf(recording("studio", "1990")), mapOf("studio" to listOf(group("Canción animal", "1990"))))
        val file = File(folder.root, "musicbrainz.json")
        assertEquals(ReleaseFacts(1990, "Canción animal"), facts(brainz = brainz(file)))
        val asked = fake.requestedUrls.size
        // Also after the app starts again.
        assertEquals(ReleaseFacts(1990, "Canción animal"), facts(brainz = brainz(file)))
        assertEquals(asked, fake.requestedUrls.size)
        // A song it found nothing for gets none again, without asking.
        val unknown = MusicMetadata("Unknown", "Nobody")
        answering(emptyList())
        assertNull(facts(unknown, brainz(file)))
        val askedForIt = fake.requestedUrls.size
        assertNull(facts(unknown, brainz(file)))
        assertEquals(askedForIt, fake.requestedUrls.size)
    }

    @Test
    fun aYearWithoutAPlainAlbumIsGivenAloneAndTheNextRecordingFromThatYearIsLookedAt() {
        answering(
            listOf(recording("a", "1990"), recording("b", "1990-05"), recording("c", "1990-08"), recording("d", "1990-09"), recording("e", "1991")),
            mapOf(
                // Compilations, live and soundtrack albums are another's; so is one that came out years after.
                "a" to listOf(group("Hits", "1990", listOf("Compilation")), group("En vivo", "1990", listOf("Live")), group("Later", "1995")),
                "b" to listOf(group("Film", "1990", listOf("Soundtrack")), group("No date", "")),
                "c" to listOf(group("Canción animal", "1990-09")),
                "d" to listOf(group("Never asked", "1990"))
            )
        )
        assertEquals(ReleaseFacts(1990, "Canción animal"), facts())
        // The first three of that year only.
        assertEquals(listOf("a", "b", "c"), fake.requestedUrls.drop(1).map { it.toHttpUrl().queryParameter("recording") })
        answering(listOf(recording("a", "1990", title = "Other")), mapOf("a" to listOf(group("Hits", "1990", listOf("Compilation")))))
        assertEquals(ReleaseFacts(1990, null), facts(MusicMetadata("Other", "Soda Stereo"), brainz(File(folder.root, "other.json"))))
    }

    @Test
    fun anAlbumTheYearAfterCountsAndTwoAlbumsOutTheSameDayAreNone() {
        answering(listOf(recording("a", "1990")), mapOf("a" to listOf(group("Second", "1991-02"), group("Third", "1992"))))
        assertEquals(ReleaseFacts(1990, "Second"), facts())
        answering(listOf(recording("a", "1990", title = "Another")), mapOf("a" to listOf(group("First", "1990-04"), group("Other", "1990-04"), group("Second", "1990-05"))))
        assertEquals(ReleaseFacts(1990, null), facts(MusicMetadata("Another", "Soda Stereo"), brainz(File(folder.root, "tie.json"))))
        // The same album twice, as two editions of it, isn't two.
        answering(listOf(recording("a", "1990", title = "Third")), mapOf("a" to listOf(group("First", "1990-04", id = "one"), group("First", "1990-04", id = "two"))))
        assertEquals(ReleaseFacts(1990, "First"), facts(MusicMetadata("Third", "Soda Stereo"), brainz(File(folder.root, "twice.json"))))
    }

    @Test
    fun aRecordingOfAnotherSongArtistOrTakeIsNotTheSong() {
        answering(
            listOf(
                recording("other-song", "1980", title = "De música ligera (remix)"),
                recording("other-title", "1981", title = "Persiana americana"),
                recording("other-artist", "1982", credit = "Soda Stereo Tribute"),
                recording("no-date", null),
                recording("live-only", "1983", on = listOf(group("En vivo", "1983", listOf("Live")), group("En vivo 2", "1984", listOf("Live", "Compilation")))),
                recording("noted", "1992", note = "radio edit"),
                recording("studio", "1991")
            ),
            mapOf("studio" to listOf(group("Canción animal", "1991")))
        )
        assertEquals(ReleaseFacts(1991, "Canción animal"), facts())
        // Nothing left is no answer.
        answering(listOf(recording("noted", "1984", title = "Another", note = "live")))
        assertNull(facts(MusicMetadata("Another", "Soda Stereo"), brainz(File(folder.root, "none.json"))))
    }

    @Test
    fun aNotedRecordingFromBeforeTheFirstOneLeavesItUnsure() {
        // The year of one that came out in 1992 with a note, and the next in 1995 without, isn't known.
        answering(listOf(recording("explicit", "1992", note = "explicit"), recording("plain", "1995")), mapOf("plain" to listOf(group("Canción animal", "1995"))))
        assertNull(facts())
        // The same year, or after, isn't a doubt.
        answering(listOf(recording("explicit", "1995", note = "explicit"), recording("plain", "1995")), mapOf("plain" to listOf(group("Canción animal", "1995"))))
        assertEquals(ReleaseFacts(1995, "Canción animal"), facts(brainz = brainz(File(folder.root, "same.json"))))
    }

    @Test
    fun theSongMayBeCreditedWithGuestsOrBySomeOtherNameOfItsArtist() {
        answering(
            listOf(recording("a", "2019", title = "真夜中のドア 〜stay with me〜", credit = "松原みき", aliases = listOf("Miki Matsubara")), recording("b", "2018", credit = "Daft Punk feat. Pharrell Williams")),
            mapOf("a" to listOf(group("Pocket Park", "2019")))
        )
        assertEquals(ReleaseFacts(2019, "Pocket Park"), facts(MusicMetadata("真夜中のドア〜stay with me", "Miki Matsubara")))
        assertEquals(ReleaseFacts(2018, null), facts(MusicMetadata("De Música Ligera", "Pharrell Williams"), brainz(File(folder.root, "guest.json"))))
    }

    @Test
    fun theQuestionNamesTheFirstArtistAndNotTheEditionOrQuotes() {
        answering(listOf(recording("studio", "1990", title = "Song \"One\"", credit = "A")))
        facts(MusicMetadata("Song \"One\" - Remastered 2012", "A \\ B & C feat. D"))
        assertEquals("recording:\"Song One\" AND artist:\"A  B\" AND status:official AND primarytype:(album OR single OR ep)", fake.requestedUrls.single().toHttpUrl().queryParameter("query"))
    }

    @Test
    fun withoutAnArtistOrATitleNothingIsAsked() {
        assertNull(facts(MusicMetadata("Song", " ")))
        assertNull(facts(MusicMetadata(" ", "Band")))
        assertTrue(fake.requestedUrls.isEmpty())
    }

    @Test
    fun whenMusicBrainzCantAnswerThereIsNoAnswerAndItIsAskedAgainNextTime() {
        val brainz = brainz()
        listOf<(Request) -> Response>(
            { FakeSpotify.html(it, "", code = 503) },
            { FakeSpotify.html(it, "<html>busy</html>") },
            { FakeSpotify.html(it, "[]") },
            { throw IOException("offline") }
        ).forEach { failing ->
            fake.handler = failing
            assertNull(facts(brainz = brainz))
        }
        // A search that works but whose albums don't, is the same.
        fake.handler = { request -> if (request.url.encodedPath.endsWith("recording")) FakeSpotify.html(request, JSONObject().put("recordings", JSONArray().put(recording("studio", "1990"))).toString()) else FakeSpotify.html(request, "", code = 503) }
        assertNull(facts(brainz = brainz))
        answering(listOf(recording("studio", "1990")), mapOf("studio" to listOf(group("Canción animal", "1990"))))
        assertEquals(ReleaseFacts(1990, "Canción animal"), facts(brainz = brainz))
    }

    @Test
    fun requestsKeepToTheOneASecondMusicBrainzAllows() {
        val times = mutableListOf<Long>()
        fake.handler = { request ->
            times += System.nanoTime()
            FakeSpotify.html(request, JSONObject().put("recordings", JSONArray().put(recording("studio", "1990"))).put("releases", JSONArray()).toString())
        }
        facts(brainz = brainz(gapMs = 150))
        assertEquals(2, times.size)
        assertTrue("${(times[1] - times[0]) / 1_000_000} ms between them", times[1] - times[0] >= 140_000_000)
    }
}
