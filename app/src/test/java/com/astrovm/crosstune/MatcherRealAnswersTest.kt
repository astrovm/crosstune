package com.astrovm.crosstune

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream

/**
 * Songs looked for on YouTube Music through the real [ExactMatcher], with each service served what it really answered,
 * kept in the test's resources: Apple's searches in every store the matcher asks and its lookups of each song they found,
 * Deezer's search, and YouTube Music's songs and videos. Anything the matcher asks that wasn't kept fails the test, so a
 * change that asks something new gets its real answer kept too, not a guess.
 */
@RunWith(RobolectricTestRunner::class)
class MatcherRealAnswersTest {

    private val fake = FakeSpotify()

    /** What the matcher asked that no kept answer is for. */
    private val unkept: MutableList<String> = CopyOnWriteArrayList()

    /** What the matcher asked, as each service's host and path: itunes.apple.com/lookup. */
    private val asked: MutableList<String> = CopyOnWriteArrayList()

    /** Which of Apple's answers fail, as offline. */
    @Volatile
    private var appleDown: (HttpUrl) -> Boolean = { false }

    /** Serve just the saved lyrics upload, as if YouTube Music had only that video. */
    @Volatile
    private var onlySpringDayFan = false

    /** The song whose answers are served. */
    @Volatile
    private var kept = LEMON

    private var clock = 0L

    @After
    fun askedOnlyWhatWasKept() = assertEquals(emptyList<String>(), unkept)

    // Issues #1102 and #1103: Lemon, by 米津玄師, as a link or a local file names it. YouTube Music has it by Kenshi Yonezu,
    // which only Apple's US store says is the same artist. At any length, the song opens, never J -KARAOKE's lyrics video.
    @Test
    fun lemonByItsArtistInAnotherScriptOpensTheSongAtAnyLength() {
        val lengths = listOf(null, 256_000L) + (230_000L..300_000L step 5_000L)
        val opened = lengths.associateWith { length -> find(LEMON, length) }
        assertEquals(lengths.associateWith { LEMON_SONG }, opened)
    }

    // Songs YouTube Music names as the US store does, in Latin letters, where they're asked for as their artists' country
    // writes them: by Official Hige Dandism, Hikaru Utada, BTS and G.E.M. At each length Apple has, or none.
    // Issue #1134: a later recording cannot replace the song, even when it is as long as the link.
    @Test
    fun songsNamedOtherwiseOnYouTubeMusicOpenTheArtistsOwn() {
        for ((song, lengths) in OTHERWISE) {
            val opened = lengths.associateWith { length -> find(song, length) }
            assertEquals(song.metadata.title, lengths.associateWith { song.opens }, opened)
        }
    }

    // A store whose search turned the song up isn't asked for it again by its id: for Lemon at 4:40, the US, Japan's,
    // Taiwan's and Hong Kong's searches have it, so only Korea's and China's are looked up, where each of the six once was.
    @Test
    fun aStoreWhoseSearchFoundTheSongIsntAskedForItAgain() {
        find(LEMON, 280_000L)
        assertEquals(mapOf("itunes.apple.com/search" to 6, "itunes.apple.com/lookup" to 2), apple())
    }

    // Issue #1102: with Apple down, Deezer links Kenshi Yonezu to 米津玄師 when the known length fits.
    @Test
    fun lemonOpensWhenAppleIsDownAndDeezerNamesTheSameRecording() {
        appleDown = { true }
        assertEquals(LEMON_SONG, find(LEMON, 256_000L))
        assertEquals(LEMON_SONG, find(LEMON, null))
    }

    // Without a fitting length, the unanswered search is retried when Apple is available again.
    @Test
    fun lemonIsAskedAgainWhenAppleWasDown() {
        val matcher = matcher()
        val song = LEMON.metadata.copy(lengthMs = 280_000L)
        appleDown = { true }
        assertEquals(ExactMatcher.Match.Failed, runBlocking { matcher.lookFor(MusicService.YOUTUBE_MUSIC, song) })
        asked.clear()
        assertEquals(ExactMatcher.Match.Failed, runBlocking { matcher.lookFor(MusicService.YOUTUBE_MUSIC, song) })
        assertEquals(emptyList<String>(), asked)
        appleDown = { false }
        clock += ExactMatcher.UNSURE_FOR_MS
        assertEquals(ExactMatcher.Match.Found(LEMON_SONG), runBlocking { matcher.lookFor(MusicService.YOUTUBE_MUSIC, song) })
    }

    // With Apple down, a queue's song is asked once: two searches of YouTube Music, each store's, and Deezer's track
    // and artist searches. Deezer's answer names the song, so there is no second attempt.
    @Test
    fun aQueuesSongIsAskedOnceWhileAppleIsDown() {
        appleDown = { true }
        val queue = runBlocking { matcher().youtubeQueue(MusicService.YOUTUBE_MUSIC, listOf(LEMON.metadata)) {} }
        assertEquals(LEMON_SONG, queue?.url)
        assertEquals(mapOf("api.deezer.com/search/track" to 1, "api.deezer.com/search/artist" to 1, "itunes.apple.com/search" to 6, "music.youtube.com/youtubei/v1/search" to 2, "www.youtube.com/watch_videos" to 1), asked.groupingBy { it }.eachCount())
    }

    // Issue #1103: Japan's store finds the song, but the US store fails. Deezer names its artist and Japan's length
    // corroborates it, so the song opens, not J -KARAOKE's lyrics video.
    @Test
    fun lemonOpensNoFansVideoWhenTheUsStoreFailed() {
        val matcher = matcher()
        val song = LEMON.metadata.copy(lengthMs = 280_000L)
        appleDown = { url -> url.queryParameter("country") == "US" }
        assertEquals(ExactMatcher.Match.Found(LEMON_SONG), runBlocking { matcher.lookFor(MusicService.YOUTUBE_MUSIC, song) })

        appleDown = { false }
        clock += ExactMatcher.UNSURE_FOR_MS
        assertEquals(ExactMatcher.Match.Found(LEMON_SONG), runBlocking { matcher.lookFor(MusicService.YOUTUBE_MUSIC, song) })
    }

    @Test
    fun springDayOpensItsSongWhileJapansLookupFails() {
        kept = SPRING_DAY
        appleDown = { it.encodedPath == "/lookup" && it.queryParameter("country") == "JP" }
        assertEquals(ExactMatcher.Match.Found(SPRING_DAY.opens.orEmpty(), lasting = false), runBlocking {
            matcher().lookFor(MusicService.YOUTUBE_MUSIC, SPRING_DAY.metadata)
        })
    }

    @Test
    fun springDayOpensAFansUploadWhileJapansLookupFails() {
        kept = SPRING_DAY
        onlySpringDayFan = true
        appleDown = { it.encodedPath == "/lookup" && it.queryParameter("country") == "JP" }
        assertEquals(ExactMatcher.Match.Found("$WATCH=$SPRING_DAY_FAN", lasting = false), runBlocking {
            matcher().lookFor(MusicService.YOUTUBE_MUSIC, SPRING_DAY.metadata)
        })
    }

    private fun matcher(): ExactMatcher {
        fake.handler = ::answer
        return ExactMatcher(fake.client(), "US", missingForMs = 60 * 60_000L, now = { clock }, timeoutMs = WHILE_MS)
    }

    private fun find(song: Kept, lengthMs: Long?): String? {
        kept = song
        fake.handler = ::answer
        return runBlocking { ExactMatcher(fake.client(), "US", timeoutMs = WHILE_MS).find(MusicService.YOUTUBE_MUSIC, song.metadata.copy(lengthMs = lengthMs)) }
    }

    private fun apple() = asked.filter { it.startsWith("itunes.apple.com") }.groupingBy { it }.eachCount()

    private fun answer(request: Request): Response {
        val url = request.url
        asked += url.host + url.encodedPath
        if (url.host == "www.youtube.com" && url.encodedPath == "/watch_videos") {
            return FakeSpotify.html(request, "").newBuilder().code(303).header("Location", "https://www.youtube.com/watch?v=3NNhrqHZqlI").build()
        }
        val answers = kept.answers
        val term = kept.term
        val served = when (url.host) {
            "itunes.apple.com" -> {
                if (appleDown(url)) throw IOException("Apple is down")
                apple(url, answers.getJSONObject("apple"), term)
            }
            "api.deezer.com" -> when {
                url.encodedPath == "/search/track" && url.queryParameter("q") == term -> answers.getJSONObject("deezer")
                url.encodedPath == "/search/artist" && url.queryParameter("q") == kept.metadata.artist -> answers.optJSONObject("deezerArtists")
                else -> null
            }
            "music.youtube.com" -> JSONObject(FakeSpotify.bodyOf(request)).takeIf { it.optString("query") == term }
                ?.let { youtube(answers, it.optString("params") == VIDEOS) }
            else -> null
        }
        return FakeSpotify.html(request, served?.toString() ?: throw IOException("not kept: $url").also { unkept += url.toString() })
    }

    private fun youtube(answers: JSONObject, videos: Boolean): JSONObject {
        if (!onlySpringDayFan) return answers.getJSONObject(if (videos) "videos" else "songs")
        if (!videos) return JSONObject()
        val rows = answers.getJSONObject("videos").getJSONObject("contents").getJSONObject("tabbedSearchResultsRenderer")
            .getJSONArray("tabs").getJSONObject(0).getJSONObject("tabRenderer").getJSONObject("content")
            .getJSONObject("sectionListRenderer").getJSONArray("contents").getJSONObject(0)
            .getJSONObject("musicShelfRenderer").getJSONArray("contents")
        val fan = (0 until rows.length()).map(rows::getJSONObject).single {
            it.getJSONObject("musicResponsiveListItemRenderer").getJSONObject("playlistItemData").getString("videoId") == SPRING_DAY_FAN
        }
        return JSONObject().put("contents", fan)
    }

    private fun apple(url: HttpUrl, apple: JSONObject, term: String): JSONObject? {
        val store = url.queryParameter("country").orEmpty()
        return when (url.encodedPath) {
            "/search" -> apple.getJSONObject("search").optJSONObject(store)?.takeIf { url.queryParameter("term") == term }
            "/lookup" -> apple.getJSONObject("lookup").optJSONObject("${url.queryParameter("id")}-$store")
            else -> null
        }
    }

    /** A song as asked for, what each service answered for it, kept in [file], and the row it [opens]. */
    private class Kept(val metadata: MusicMetadata, file: String, val opens: String?) {
        val term = "${metadata.title} ${metadata.artist}"

        val answers: JSONObject by lazy {
            val stream = requireNotNull(MatcherRealAnswersTest::class.java.getResourceAsStream("/matcher/$file")) { file }
            JSONObject(GZIPInputStream(stream).bufferedReader().use { it.readText() })
        }
    }

    private companion object {
        const val WATCH = "https://music.youtube.com/watch?v"
        const val LEMON_SONG = "$WATCH=3NNhrqHZqlI"
        const val SPRING_DAY_FAN = "9zNcHOD1Hdk"
        const val VIDEOS = "EgWKAQIQAWoKEAoQAxAEEAkQBQ=="

        /** Long, as a busy machine is no answer from a service here: each answer is kept. */
        const val WHILE_MS = 60_000L

        /** What each service answered, in November 2026. */
        val LEMON = Kept(MusicMetadata("Lemon", "米津玄師"), "lemon.json.gz", LEMON_SONG)
        val SPRING_DAY = Kept(MusicMetadata("봄날", "방탄소년단"), "spring-day.json.gz", "$WATCH=ZO1xdXOXarc")

        val OTHERWISE = mapOf(
            Kept(MusicMetadata("Pretender", "Official髭男dism"), "pretender.json.gz", "$WATCH=37W7Y2RRyiM") to
                listOf(null, 223_840L, 326_440L, 326_843L, 344_813L),
            // The original is only among videos; the songs include a 2025 recording From THE FIRST TAKE.
            Kept(MusicMetadata("First Love", "宇多田ヒカル"), "first-love.json.gz", "$WATCH=o1sUaVJUeB0") to
                listOf(null, 237_752L, 257_894L, 258_627L, 259_385L, 262_030L),
            SPRING_DAY to listOf(null, 274_097L, 274_704L),
            // The G.E.M. Version is a re-recording within a second of the original.
            Kept(MusicMetadata("光年之外", "鄧紫棋"), "light-years-away.json.gz", "$WATCH=f8IqVtY_4jo") to
                listOf(null, 217_489L, 234_951L, 235_505L)
        )
    }
}
