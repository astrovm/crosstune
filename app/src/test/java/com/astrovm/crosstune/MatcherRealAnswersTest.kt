package com.astrovm.crosstune

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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

    /** Which of Apple's answers fail, as offline. */
    @Volatile
    private var appleDown: (HttpUrl) -> Boolean = { false }

    @After
    fun askedOnlyWhatWasKept() = assertEquals(emptyList<String>(), unkept)

    // Issues #1102 and #1103: Lemon, by 米津玄師, as a link or a local file names it. YouTube Music has it by Kenshi Yonezu,
    // which only Apple's US store says is the same artist. At any length, the song opens, never J -KARAOKE's lyrics video.
    @Test
    fun lemonByItsArtistInAnotherScriptOpensTheSongAtAnyLength() {
        val lengths = listOf(null, 256_000L) + (230_000L..300_000L step 5_000L)
        val opened = lengths.associateWith { length -> find(LEMON.copy(lengthMs = length)) }
        assertEquals(lengths.associateWith { LEMON_SONG }, opened)
    }

    // Issue #1102: with Apple down, nothing says Kenshi Yonezu is 米津玄師, which is no answer, not an answer of "none". So it
    // isn't remembered as missing, and it's asked again, when Apple answers.
    @Test
    fun lemonIsAskedAgainWhenAppleWasDown() {
        val matcher = ExactMatcher(fake.client(), "US", missingForMs = 60 * 60_000L, timeoutMs = WHILE_MS)
        fake.handler = ::answer
        for (length in listOf(null, 256_000L, 280_000L)) {
            val song = LEMON.copy(lengthMs = length)
            appleDown = { true }
            assertEquals(ExactMatcher.Match.Failed, runBlocking { matcher.lookFor(MusicService.YOUTUBE_MUSIC, song) })
            appleDown = { false }
            assertEquals(ExactMatcher.Match.Found(LEMON_SONG), runBlocking { matcher.lookFor(MusicService.YOUTUBE_MUSIC, song) })
        }
    }

    // Issue #1103: Japan's store finds the song, but the US store, the only one that names 米津玄師 Kenshi Yonezu, fails. Then
    // J -KARAOKE's lyrics video, which names 米津玄師, isn't taken for it, and the song is asked again.
    @Test
    fun lemonOpensNoFansVideoWhenTheUsLookupFailed() {
        val matcher = ExactMatcher(fake.client(), "US", missingForMs = 60 * 60_000L, timeoutMs = WHILE_MS)
        fake.handler = ::answer
        val song = LEMON.copy(lengthMs = 280_000L)
        appleDown = { url -> url.encodedPath == "/lookup" && url.queryParameter("country") == "US" }
        assertEquals(ExactMatcher.Match.Failed, runBlocking { matcher.lookFor(MusicService.YOUTUBE_MUSIC, song) })
        appleDown = { false }
        assertEquals(ExactMatcher.Match.Found(LEMON_SONG), runBlocking { matcher.lookFor(MusicService.YOUTUBE_MUSIC, song) })
    }

    private fun find(song: MusicMetadata): String? {
        fake.handler = ::answer
        return runBlocking { ExactMatcher(fake.client(), "US", timeoutMs = WHILE_MS).find(MusicService.YOUTUBE_MUSIC, song) }
    }

    private fun answer(request: Request): Response {
        val url = request.url
        val kept = when (url.host) {
            "itunes.apple.com" -> {
                if (appleDown(url)) throw IOException("Apple is down")
                apple(url)
            }
            "api.deezer.com" -> answers.getJSONObject("deezer").takeIf { url.encodedPath == "/search/track" && url.queryParameter("q") == TERM }
            "music.youtube.com" -> JSONObject(FakeSpotify.bodyOf(request)).takeIf { it.optString("query") == TERM }
                ?.let { answers.getJSONObject(if (it.optString("params") == VIDEOS) "videos" else "songs") }
            else -> null
        }
        return FakeSpotify.html(request, kept?.toString() ?: throw IOException("not kept: $url").also { unkept += url.toString() })
    }

    private fun apple(url: HttpUrl): JSONObject? {
        val store = url.queryParameter("country").orEmpty()
        val kept = answers.getJSONObject("apple")
        return when (url.encodedPath) {
            "/search" -> kept.getJSONObject("search").optJSONObject(store)?.takeIf { url.queryParameter("term") == TERM }
            "/lookup" -> kept.getJSONObject("lookup").optJSONObject("${url.queryParameter("id")}-$store")
            else -> null
        }
    }

    private companion object {
        val LEMON = MusicMetadata("Lemon", "米津玄師")
        const val TERM = "Lemon 米津玄師"
        const val LEMON_SONG = "https://music.youtube.com/watch?v=3NNhrqHZqlI"
        const val VIDEOS = "EgWKAQIQAWoKEAoQAxAEEAkQBQ=="

        /** Long, as a busy machine is no answer from a service here: each answer is kept. */
        const val WHILE_MS = 60_000L

        /** What each service answered for Lemon by 米津玄師, in November 2026. */
        val answers: JSONObject by lazy {
            val stream = requireNotNull(MatcherRealAnswersTest::class.java.getResourceAsStream("/matcher/lemon.json.gz")) { "matcher/lemon.json.gz" }
            JSONObject(GZIPInputStream(stream).bufferedReader().use { it.readText() })
        }
    }
}
