package com.astrovm.crosstune

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** YouTube Music and Deezer answers captured by matchcheck; Apple editions are reconstructed as in its replay. */
@RunWith(RobolectricTestRunner::class)
class MatcherRecordingEvidenceTest {
    private val fake = FakeSpotify()
    private var clock = 0L
    private val matcher by lazy { ExactMatcher(fake.client(), "US", now = { clock }, timeoutMs = 60_000) }
    private var lastMatch: ExactMatcher.Match? = null
    private val captured by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/matcher/recording-evidence.json.gz"))
        JSONArray(GZIPInputStream(stream).bufferedReader().use { it.readText() })
    }

    private fun find(index: Int, nativeNames: Boolean, differentRecording: Boolean = false, reverseDown: Boolean = false, deezerHeld: Boolean = false, inputLength: Long? = null, held: Pair<CountDownLatch, CountDownLatch>? = null, withoutLength: Boolean = false): String? {
        val song = captured.getJSONObject(index)
        val metadata = MusicMetadata(song.getString("title"), song.getString("artist"), lengthMs = if (withoutLength) null else inputLength ?: song.getLong("lengthMs"))
        val us = JSONArray().also { results ->
            val editions = song.getJSONArray("editionsMs")
            for (i in 0 until editions.length()) results.put(JSONObject().put("trackId", song.getLong("id"))
                .put("trackName", song.getString("title")).put("artistName", song.getString("storeArtist"))
                .put("collectionName", song.getString("album")).put("trackTimeMillis", editions.getLong(i)))
        }
        fake.handler = { request ->
            val url = request.url
            val response = when (url.host) {
                "itunes.apple.com" -> {
                    val store = url.queryParameter("country").orEmpty()
                    val term = url.queryParameter("term").orEmpty()
                    if (held != null && term.startsWith("夜空のイヤリング")) {
                        held.first.countDown()
                        held.second.await(10, TimeUnit.SECONDS)
                    }
                    val results = if (url.encodedPath == "/search" && term == song.optString("reverseTerm") && store == "JP") {
                        if (held != null) assertTrue(held.first.await(3, TimeUnit.SECONDS))
                        if (reverseDown) throw IOException("candidate search is down")
                        val recorded = song.getJSONObject("reverse").getJSONArray("results")
                        if (!differentRecording) recorded else JSONArray(recorded.toString()).also { answers ->
                            for (i in 0 until answers.length()) if (answers.getJSONObject(i).optLong("trackId") == song.getLong("id")) {
                                answers.getJSONObject(i).put("trackId", 123456789L)
                            }
                        }
                    } else if (url.encodedPath == "/search" && term == "${metadata.title} ${metadata.artist}" && store == "US") us else if (url.encodedPath == "/lookup" && nativeNames) {
                        val native = song.getJSONArray("native")
                        val found = (0 until native.length()).map(native::getJSONObject).firstOrNull { it.optString("store") == store }
                        JSONArray().also { if (found != null) it.put(JSONObject().put("trackId", song.getLong("id"))
                            .put("trackName", found.getString("title")).put("artistName", found.getString("artist"))
                            .put("collectionName", found.getString("album")).put("trackTimeMillis", song.getLong("lengthMs"))) }
                    } else JSONArray()
                    JSONObject().put("results", results)
                }
                "api.deezer.com" -> {
                    if (deezerHeld && !CountDownLatch(1).await(2, TimeUnit.SECONDS)) throw IOException("Deezer is still waiting")
                    JSONObject().put("data", song.getJSONArray(if (url.encodedPath == "/search/artist") "artists" else "tracks"))
                }
                "music.youtube.com" -> song.getJSONObject(if (FakeSpotify.bodyOf(request).contains(VIDEOS)) "videos" else "songs")
                else -> throw IOException("not captured: $url")
            }
            FakeSpotify.html(request, response.toString())
        }
        lastMatch = runBlocking { matcher.lookFor(MusicService.YOUTUBE_MUSIC, metadata) }
        return (lastMatch as? ExactMatcher.Match.Found)?.url
    }

    // Issue #1199: the artist's 4:25 video and a 3:58 fan video are both in the captured search.
    @Test fun peaceSignPrefersTheArtistsVideo() = assertEquals(WATCH + "9aJVr5tTTWk", find(0, true))

    @Test fun bayCityKeepsTheLongRecording() = assertEquals(WATCH + "HNfnjWbO4ds", find(1, true))

    // Issue #1127: neither source lookup gives the native names in these two checks.
    @Test fun mizuiroNoAmeOpensTheJapaneseSongWithoutAppleNativeNames() = assertEquals(WATCH + "M3-K4knCeLE", find(2, false))

    // Issues #992, #983 and #945: before ID proof, the 4:00 row for another song by this artist opened.
    @Test fun jealousOpensTheJapaneseSongWithoutAppleNativeNames() = assertEquals(WATCH + "8eQdsCZWrLg", find(3, false))

    @Test fun zeroInputLengthDoesNotHideAnAppleProvedSong() = assertEquals(WATCH + "M3-K4knCeLE", find(2, false, inputLength = 0))

    @Test fun noInputLengthUsesTheAppleRecordingLength() = assertEquals(WATCH + "M3-K4knCeLE", find(2, false, withoutLength = true))

    @Test fun aDifferentAppleRecordingDoesNotProveTheJapaneseSong() = assertEquals(WATCH + "tTN1GZbKU3w", find(2, false, differentRecording = true))

    @Test fun anAppleProvedCandidateDoesNotWaitForDeezer() {
        assertEquals(WATCH + "8eQdsCZWrLg", find(3, false, deezerHeld = true))
        assertTrue(fake.requestedUrls.none { it.contains("api.deezer.com") })
    }

    @Test fun anAppleProvedCandidateDoesNotWaitForAnotherCandidate() = runBlocking {
        val held = CountDownLatch(1) to CountDownLatch(1)
        try {
            val result = async(Dispatchers.Default) { find(3, false, held = held) }
            assertTrue(held.first.await(3, TimeUnit.SECONDS))
            assertEquals(WATCH + "8eQdsCZWrLg", withTimeout(2_000) { result.await() })
        } finally {
            held.second.countDown()
        }
    }

    @Test fun aFailedCandidateSearchHoldsTheFanUploadForRetry() {
        assertEquals(null, find(2, false, reverseDown = true))
        assertEquals(ExactMatcher.Match.WaitingForNames, lastMatch)
        clock += ExactMatcher.UNSURE_FOR_MS
        assertEquals(WATCH + "M3-K4knCeLE", find(2, false))
    }

    private companion object {
        const val WATCH = "https://music.youtube.com/watch?v="
        const val VIDEOS = "EgWKAQIQAWoKEAoQAxAEEAkQBQ=="
    }
}
