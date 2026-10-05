package com.astrovm.crosstune

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/**
 * A song's words, from LRCLIB, which asks for no account and no key. Its answers are
 * crowdsourced, so what it gives back is checked against the song asked about before it's
 * believed: the words of a song with the same name by someone else are no use at all.
 *
 * Synced words carry their timings inline, and are left out; plain ones are what a song's
 * lyrics are.
 */
internal class LyricsFinder(
    private val client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    /** The words of [metadata]'s song, or null when it has none, is offline, or is too slow. */
    suspend fun lyricsOf(metadata: MusicMetadata, timeoutMs: Long = TIMEOUT_MS): String? {
        // Only a song has words, and without an artist there's nothing to check an answer against.
        if (metadata.type != ItemType.TRACK || metadata.artist.isBlank() || metadata.title.isBlank()) return null
        return withTimeoutOrNull(timeoutMs) {
            try {
                val answers = answersFor(metadata)
                // The service's own answers first, then a close one, as [ExactMatcher] does.
                answers.firstOrNull { it.isExact(metadata) }?.plain()
                    ?: answers.firstOrNull { it.belongsTo(metadata) }?.plain()
            } catch (_: IOException) {
                null
            } catch (_: JSONException) {
                null
            }
        }
    }

    private suspend fun answersFor(metadata: MusicMetadata): List<Answer> {
        val url = SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("track_name", metadata.title)
            .addQueryParameter("artist_name", metadata.artist)
            .build()
        val list = fetchJson(url.toString()).optJSONArray("data") ?: return emptyList()
        return (0 until list.length()).mapNotNull { index -> Answer.of(list.optJSONObject(index)) }
    }

    private suspend fun fetchJson(url: String): JSONObject {
        val request = Request.Builder().url(url).get().build()
        return client.newCall(request).executeAsync().use { response ->
            JSONObject(withContext(ioDispatcher) { response.body.stringAtMost() })
        }
    }

    /** One answer: whose song it claims to be, and its words. */
    private class Answer(private val title: String, private val artist: String, private val json: JSONObject) {
        fun plain(): String? = json.optString("plainLyrics").trim().takeIf { it.isNotEmpty() }

        /**
         * The very song asked about, name for name. A title compared without its edition tag would
         * make a remaster the same song as the plain recording, so [SongNames.normalize] is used on
         * its own here and no tag is taken off.
         */
        fun isExact(metadata: MusicMetadata) =
            SongNames.normalize(title) == SongNames.normalize(metadata.title) && SongNames.sameArtist(artist, metadata.artist)

        /**
         * The same song, near enough: LRCLIB credits an artist as others do, so "Randy" is in
         * "Randy Nota Loca", and a title in another script is written out in letters.
         */
        fun belongsTo(metadata: MusicMetadata) =
            SongNames.wordsMatch(title, metadata.title) && SongNames.artistInside(artist, metadata.artist)

        companion object {
            fun of(json: JSONObject?): Answer? {
                if (json == null) return null
                val title = json.optString("trackName").trim()
                val artist = json.optString("artistName").trim()
                if (title.isEmpty()) return null
                return Answer(title, artist, json)
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val SEARCH_URL = "https://lrclib.net/api/search"
    }
}