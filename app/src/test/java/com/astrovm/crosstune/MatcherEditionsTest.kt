package com.astrovm.crosstune

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS

/**
 * The cases the review of PR #1197's dfa7c517b found worse than main: an explicit and a clean edition of one song, a
 * collaboration's row after them, a radio edit's link where YouTube Music has only the album's take, and the artist's
 * other names asked of MusicBrainz right after another lookup of the app. What main opened is in each assertion.
 */
@RunWith(RobolectricTestRunner::class)
class MatcherEditionsTest {
    @get:Rule
    val folder = TemporaryFolder()
    private val fake = FakeSpotify()

    private fun row(name: String, details: String, videoId: String, explicit: Boolean = false): String {
        fun column(text: String) = JSONObject().put("musicResponsiveListItemFlexColumnRenderer",
            JSONObject().put("text", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", text)))))
        val item = JSONObject().put("flexColumns", JSONArray().put(column(name)).put(column(details)))
            .put("playlistItemData", JSONObject().put("videoId", videoId))
        if (explicit) item.put("badges", JSONArray().put(JSONObject().put("musicInlineBadgeRenderer",
            JSONObject().put("icon", JSONObject().put("iconType", "MUSIC_EXPLICIT_BADGE")))))
        return JSONObject().put("musicResponsiveListItemRenderer", item).toString()
    }

    private fun page(rows: List<String>) =
        """{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
            {"musicShelfRenderer":{"contents":[${rows.joinToString(",")}]}}]}}}}]}}}"""

    private fun apple(title: String, artist: String, length: Long, id: Long, album: String, explicitness: String? = null) =
        JSONObject().put("trackId", id).put("trackName", title).put("artistName", artist).put("trackTimeMillis", length)
            .put("collectionName", album).apply { explicitness?.let { put("trackExplicitness", it) } }.toString()

    /** YouTube Music finds [songs] and [videos]; Apple's search [appleResults], or fails with null; Deezer [deezer]. */
    private fun serve(songs: List<String>, videos: List<String>, appleResults: String?, deezer: String = "[]", artist: String = "Artist") {
        fake.handler = { request: Request ->
            val host = request.url.host
            when {
                host == "itunes.apple.com" && appleResults == null -> FakeSpotify.html(request, "{}", code = 503)
                host == "itunes.apple.com" && request.url.encodedPath == "/search" -> FakeSpotify.html(request, """{"results":[$appleResults]}""")
                host == "itunes.apple.com" -> FakeSpotify.html(request, """{"results":[]}""")
                host == "api.deezer.com" && request.url.encodedPath == "/search/artist" ->
                    FakeSpotify.html(request, """{"data":[{"id":1,"name":"$artist"}]}""")
                host == "api.deezer.com" -> FakeSpotify.html(request, """{"data":$deezer}""")
                else -> FakeSpotify.html(request, page(if (FakeSpotify.bodyOf(request).contains(VIDEO_PARAMS)) videos else songs))
            }
        }
    }

    private fun opens(metadata: MusicMetadata, songs: List<String>, videos: List<String>, appleResults: String?, deezer: String = "[]"): String? {
        serve(songs, videos, appleResults, deezer)
        return runBlocking { ExactMatcher(fake.client(), "US").find(MusicService.YOUTUBE_MUSIC, metadata) }?.removePrefix(WATCH)
    }

    private val song = MusicMetadata("Song", "Artist", album = "Album", lengthMs = 240_000)
    private val explicitFirst = listOf(row("Song", "Artist • Album • 4:00", "explicit000", explicit = true), row("Song", "Artist • Album • 4:00", "clean000000"))
    private val bothAtApple = apple("Song", "Artist", 240_000, 11, "Album", "explicit") + "," + apple("Song", "Artist", 240_100, 12, "Album", "cleaned")
    private val explicitAtApple = apple("Song", "Artist", 240_000, 11, "Album", "explicit")
    private val cleanAtApple = apple("Song", "Artist", 240_000, 12, "Album", "cleaned")
    private val deezerSong = """[{"title":"Song","artist":{"id":1,"name":"Artist"},"album":{"title":"Album"},"duration":240}]"""

    @Test
    fun anExplicitAndACleanEditionOpenTheFirstAsMainDidWhereAppleCantTellThemApart() {
        val video = listOf(row("Artist - Song (Official Video)", "Artist • 10M views • 4:05", "video000000"))
        assertEquals("explicit000", opens(song, explicitFirst, emptyList(), bothAtApple, deezerSong))
        assertEquals("explicit000", opens(song, explicitFirst, video, bothAtApple, deezerSong))
        assertEquals("explicit000", opens(song, explicitFirst, emptyList(), null, deezerSong))
        assertEquals("explicit000", opens(song.copy(album = null), explicitFirst, emptyList(), bothAtApple, deezerSong))
        assertEquals("explicit000", opens(song.copy(lengthMs = null), explicitFirst, emptyList(), bothAtApple, deezerSong))
        assertEquals("clean000000", opens(song, explicitFirst.reversed(), emptyList(), bothAtApple, deezerSong))
        // Apple's edition without a word on it says nothing either.
        assertEquals("clean000000", opens(song, explicitFirst.reversed(), emptyList(), apple("Song", "Artist", 240_000, 11, "Album"), deezerSong))
    }

    @Test
    fun applesOneEditionPicksTheSameBadge() {
        assertEquals("explicit000", opens(song, explicitFirst, emptyList(), explicitAtApple, deezerSong))
        assertEquals("explicit000", opens(song, explicitFirst.reversed(), emptyList(), explicitAtApple, deezerSong))
        assertEquals("clean000000", opens(song, explicitFirst, emptyList(), cleanAtApple, deezerSong))
        assertEquals("clean000000", opens(song, explicitFirst, emptyList(), apple("Song", "Artist", 240_000, 12, "Album", "notExplicit"), deezerSong))
        // Only Apple's editions of the link's song and length count: a clean edit a minute longer is another.
        val longer = explicitAtApple + "," + apple("Song", "Artist", 300_000, 13, "Album", "cleaned")
        assertEquals("explicit000", opens(song, explicitFirst.reversed(), emptyList(), longer, deezerSong))
        val otherSong = explicitAtApple + "," + apple("Another Song", "Artist", 240_000, 13, "Album", "cleaned")
        assertEquals("explicit000", opens(song, explicitFirst.reversed(), emptyList(), otherSong, deezerSong))
    }

    @Test
    fun applesBadgeIsAskedOnlyWhereTheBadgeDecides() {
        serve(listOf(row("Song", "Artist • Album • 4:00", "explicit000", explicit = true)), emptyList(), explicitAtApple)
        assertEquals("$WATCH" + "explicit000", runBlocking { ExactMatcher(fake.client(), "US").find(MusicService.YOUTUBE_MUSIC, song) })
        assertFalse(fake.requestedUrls.any { it.contains("itunes.apple.com") })
        // The link's album names one of them, so no badge is needed.
        val albums = listOf(row("Song", "Artist • Album • 4:00", "explicit000", explicit = true), row("Song", "Artist • Album (Clean) • 4:00", "clean000000"))
        serve(albums, emptyList(), cleanAtApple)
        assertEquals("$WATCH" + "explicit000", runBlocking { ExactMatcher(fake.client(), "US").find(MusicService.YOUTUBE_MUSIC, song) })
        assertFalse(fake.requestedUrls.any { it.contains("itunes.apple.com") })
    }

    @Test
    fun aSlowAppleLeavesTheFirstEditionAsMainOpenedIt() = runBlocking {
        serve(explicitFirst.reversed(), emptyList(), explicitAtApple, deezerSong)
        val answer = fake.handler
        val release = CountDownLatch(1)
        fake.handler = { request ->
            if (request.url.host == "itunes.apple.com") assertTrue(release.await(5, SECONDS))
            answer(request)
        }
        try {
            val matcher = ExactMatcher(fake.client(), "US", explicitWaitMs = 50)
            val found = async(Dispatchers.Default) { matcher.find(MusicService.YOUTUBE_MUSIC, song) }
            assertEquals(WATCH + "clean000000", withTimeoutOrNull(5_000) { found.await() })
        } finally {
            release.countDown()
        }
    }

    @Test
    fun aCollaborationsRowAfterThemIsNotTheSong() {
        val withCollab = explicitFirst + row("Song", "Artist, Rapper • Song (Rapper Version) • 4:02", "collab00000")
        assertEquals("explicit000", opens(song, withCollab, emptyList(), bothAtApple, deezerSong))
        assertEquals("explicit000", opens(song, withCollab, emptyList(), null, deezerSong))
        // Nor after a live and a studio take, which the first credit can't tell apart.
        val takes = listOf(row("Song", "Artist • Album • 4:00", "studio00000"), row("Song", "Artist • Live at Budokan • 4:01", "live0000000"),
            row("Song", "Artist, Rapper • Song (Rapper Version) • 4:02", "collab00000"))
        assertTrue(opens(song.copy(album = null), takes, emptyList(), null) != "collab00000")
    }

    @Test
    fun aRadioEditsLinkOpensTheAlbumsTakeWhereThatsAllThereIs() {
        val radioEdit = MusicMetadata("Song", "Artist", lengthMs = 210_000)
        val album = listOf(row("Song", "Artist • Album • 4:30", "album000000"))
        val both = apple("Song", "Artist", 210_000, 11, "Song - Single", "notExplicit") + "," + apple("Song", "Artist", 270_000, 12, "Album", "notExplicit")
        assertEquals("album000000", opens(radioEdit, album, emptyList(), both))
        assertEquals("album000000", opens(radioEdit, album, emptyList(), apple("Song", "Artist", 270_000, 12, "Album", "notExplicit")))
        assertEquals("album000000", opens(radioEdit, album, emptyList(), null))
        // Apple's one edition as long as the link is the single's, no live take.
        assertEquals("album000000", opens(radioEdit, album, emptyList(), apple("Song", "Artist", 210_000, 11, "Song - Single")))
    }

    @Test
    fun aLiveLinkDoesNotOpenTheStudioTakeOfTheSameName() {
        val live = MusicMetadata("Song", "Artist", lengthMs = 330_000)
        val studio = listOf(row("Song", "Artist • Album • 4:30", "album000000"))
        val editions = apple("Song", "Artist", 330_000, 11, "Live at Budokan") + "," + apple("Song", "Artist", 270_000, 12, "Album")
        assertEquals(null, opens(live, studio, emptyList(), editions))
        assertEquals(null, opens(live, studio, emptyList(), apple("Song", "Artist", 330_000, 11, "Live at Budokan")))
        // The live album's row is the link's, though it's longer than the link says.
        val liveRow = listOf(row("Song", "Artist • Live at Budokan • 5:45", "live0000000"))
        assertEquals("live0000000", opens(live, studio + liveRow, emptyList(), editions))
    }

    @Test
    fun theArtistsLiveTakeAsLongAsTheLinkKeepsTheStudioTakeOut() {
        val link = MusicMetadata("Song", "Artist", lengthMs = 240_000)
        val studio = row("Song", "Artist • Album • 3:30", "album000000")
        val single = apple("Song", "Artist", 210_000, 12, "Album")
        assertEquals(null, opens(link, listOf(studio, row("Song - Live", "Artist • Album • 4:00", "live0000000")), emptyList(), single))
        // Another artist's live take, or the artist's of another length, says nothing of the link's.
        assertEquals("album000000", opens(link, listOf(studio, row("Song - Live", "Someone Else • Album • 4:00", "live0000000")), emptyList(), single))
        assertEquals("album000000", opens(link, listOf(studio, row("Song - Live", "Artist • Album • 5:00", "live0000000")), emptyList(), single))
        assertEquals("album000000", opens(link, listOf(studio, row("Other Song - Live", "Artist • Album • 4:00", "live0000000")), emptyList(), single))
    }

    /** No answer from MusicBrainz leaves the native name unproved: retried, never a lasting wrong take. */
    @Test
    fun aNativeArtistWithNoAliasAnswerIsNotTheConcertTake() {
        val songs = listOf(row("突然の贈りもの - Totsuzen No Okurimono", "Taeko Onuki • Symphonic Concert 2016 • 5:02", "concert0000"),
            row("突然の贈りもの - Totsuzen No Okurimono", "Taeko Onuki • Mignonne • 5:05", "studio00000"))
        val apple = """{"trackId":7,"trackName":"Totsuzen No Okurimono","artistName":"Taeko Onuki","trackTimeMillis":304293,"collectionName":"Mignonne"}"""
        val deezer = """[{"title":"Totsuzen No Okurimono","artist":{"id":1,"name":"Taeko Onuki"},"album":{"title":"Mignonne"},"duration":304}]"""
        for (appleUp in listOf(true, false)) {
            serve(songs, emptyList(), apple.takeIf { appleUp }, deezer, artist = "Taeko Onuki")
            val match = runBlocking { ExactMatcher(fake.client(), "US").lookFor(MusicService.YOUTUBE_MUSIC,
                MusicMetadata("Totsuzen No Okurimono", "大貫妙子", lengthMs = 304_293)) }
            assertEquals("Apple up: $appleUp", ExactMatcher.Match.Failed, match)
        }
    }

    /** The real MusicBrainz client, with its 1.1 s gap: asked just before, its answer still comes in time. */
    @Test
    fun theArtistsOtherNameWaitsOutMusicBrainzsGap() = runBlocking {
        val concert = row("突然の贈りもの - Totsuzen No Okurimono", "Taeko Onuki • Symphonic Concert 2016 • 5:02", "concert0000")
        val studio = row("突然の贈りもの - Totsuzen No Okurimono", "Taeko Onuki • Mignonne • 5:05", "studio00000")
        val apple = """{"trackId":7,"trackName":"Totsuzen No Okurimono","artistName":"Taeko Onuki","trackTimeMillis":304293,"collectionName":"Mignonne"}"""
        val track = """[{"title":"Totsuzen No Okurimono","artist":{"id":1,"name":"Taeko Onuki"},"album":{"title":"Mignonne"},"duration":304}]"""
        for (appleUp in listOf(true, false)) {
            for (askedJustBefore in listOf(false, true)) {
                serve(listOf(concert, studio), emptyList(), apple.takeIf { appleUp }, track, artist = "Taeko Onuki")
                val answer = fake.handler
                fake.handler = { request ->
                    if (request.url.host == "musicbrainz.org") {
                        FakeSpotify.html(request, """{"artists":[{"id":"a1","name":"大貫妙子","aliases":[{"name":"Taeko Onuki","type":"Artist name"}]}]}""")
                    } else answer(request)
                }
                val brainz = MusicBrainz(fake.client(), "1", LookupCache(File(folder.newFolder(), "mb.json"), Dispatchers.Unconfined),
                    MusicBrainz.Turn(), Dispatchers.Unconfined, MusicBrainz.ASK_GAP_MS)
                // Another lookup of the app, as a Spotify artist's or a song's year, just before.
                if (askedJustBefore) brainz.artistAliases("Someone Else")
                val matcher = ExactMatcher(fake.client(), "US", artistAliases = brainz.matcherArtistAliases)
                val match = matcher.lookFor(MusicService.YOUTUBE_MUSIC, MusicMetadata("Totsuzen No Okurimono", "大貫妙子", lengthMs = 304_293))
                assertEquals("Apple up: $appleUp, asked just before: $askedJustBefore", WATCH + "studio00000", (match as ExactMatcher.Match.Found).url)
            }
        }
    }

    private companion object {
        const val WATCH = "https://music.youtube.com/watch?v="
        const val VIDEO_PARAMS = "EgWKAQIQAWoKEAoQAxAEEAkQBQ=="
    }
}
