package com.astrovm.crosstune

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.zip.GZIPInputStream

/** YouTube Music and Deezer answers captured by matchcheck; Apple editions are reconstructed as in its replay. */
@RunWith(RobolectricTestRunner::class)
class MatcherRecordingEvidenceTest {
    private val fake = FakeSpotify()
    private val captured by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/matcher/recording-evidence.json.gz"))
        JSONArray(GZIPInputStream(stream).bufferedReader().use { it.readText() })
    }

    private fun find(index: Int, nativeNames: Boolean, differentRecording: Boolean = false): String? {
        val song = captured.getJSONObject(index)
        val metadata = MusicMetadata(song.getString("title"), song.getString("artist"), lengthMs = song.getLong("lengthMs"))
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
                    val results = if (url.encodedPath == "/search" && term == song.optString("reverseTerm") && store == "JP") {
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
                "api.deezer.com" -> JSONObject().put("data", song.getJSONArray(if (url.encodedPath == "/search/artist") "artists" else "tracks"))
                "music.youtube.com" -> song.getJSONObject(if (FakeSpotify.bodyOf(request).contains(VIDEOS)) "videos" else "songs")
                else -> throw IOException("not captured: $url")
            }
            FakeSpotify.html(request, response.toString())
        }
        return runBlocking { ExactMatcher(fake.client(), "US", timeoutMs = 60_000).find(MusicService.YOUTUBE_MUSIC, metadata) }
    }

    @Test fun peaceSignPrefersTheArtistsVideo() = assertEquals(WATCH + "9aJVr5tTTWk", find(0, true))

    @Test fun bayCityKeepsTheLongRecording() = assertEquals(WATCH + "HNfnjWbO4ds", find(1, true))

    @Test fun mizuiroNoAmeOpensTheJapaneseSongWithoutAppleNativeNames() = assertEquals(WATCH + "M3-K4knCeLE", find(2, false))

    @Test fun jealousOpensTheJapaneseSongWithoutAppleNativeNames() = assertEquals(WATCH + "8eQdsCZWrLg", find(3, false))

    @Test fun aDifferentAppleRecordingDoesNotProveTheJapaneseSong() = assertEquals(WATCH + "tTN1GZbKU3w", find(2, false, differentRecording = true))

    private companion object {
        const val WATCH = "https://music.youtube.com/watch?v="
        const val VIDEOS = "EgWKAQIQAWoKEAoQAxAEEAkQBQ=="
    }
}
