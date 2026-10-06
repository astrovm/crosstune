package com.astrovm.crosstune

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.Locale

/**
 * Finds songs by what's typed rather than by a link. Deezer answers first, since its metadata and
 * covers are the best of the two; Apple's search is asked when Deezer has nothing, which covers
 * the songs Deezer doesn't carry. Neither wants an account.
 *
 * Only songs come back. An album or an artist typed as text is a search the destination apps do
 * better, and Crosstune has no way to say which one was meant.
 */
internal class SongSearcher(
    private val client: OkHttpClient,
    private val country: String = Locale.getDefault().country,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    /** Songs named like [query], best first, or an empty list when neither service has any. */
    suspend fun search(query: String, timeoutMs: Long = TIMEOUT_MS): List<MusicMetadata> {
        val words = query.trim()
        if (words.isEmpty()) return emptyList()
        return withTimeoutOrNull(timeoutMs) {
            try {
                val songs = deezer(words) + itunes(words)
                // Deezer first, so its answers lead; each service's own repeated name is one song.
                songs.distinctBy { SongNames.normalize(it.title) + "|" + SongNames.normalize(it.artist) }
            } catch (_: IOException) {
                emptyList()
            } catch (_: JSONException) {
                emptyList()
            }
        } ?: emptyList()
    }

    /** What Deezer holds for [query]; empty when it won't answer or has nothing. */
    private suspend fun deezer(query: String): List<MusicMetadata> {
        val url = "https://api.deezer.com/search/track".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", "$LIMIT")
            .build()
        val results = fetchJson(url.toString()).optJSONArray("data") ?: return emptyList()
        return (0 until results.length()).mapNotNull { index ->
            val song = results.optJSONObject(index) ?: return@mapNotNull null
            val title = song.optString("title").trim()
            val artist = song.optJSONObject("artist")?.optString("name").orEmpty().trim()
            // Without both a name and an artist there's no song to look up anywhere else.
            if (title.isEmpty() || artist.isEmpty()) return@mapNotNull null
            MusicMetadata(
                title = title,
                artist = artist,
                // Deezer's own link, so a song picked from here opens as itself in its app.
                url = song.optString("link").trim().ifBlank { null },
                artworkUrl = song.optJSONObject("album")?.optString("cover_big")?.trim()?.ifBlank { null }
            )
        }
    }

    /** What Apple's search holds for [query]; empty when it won't answer or has nothing. */
    private suspend fun itunes(query: String): List<MusicMetadata> {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", query)
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "$LIMIT")
            .apply { if (country.length == 2) addQueryParameter("country", country) }
            .build()
        val results = fetchJson(url.toString()).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { index ->
            val song = results.optJSONObject(index) ?: return@mapNotNull null
            val title = song.optString("trackName").trim()
            val artist = song.optString("artistName").trim()
            if (title.isEmpty() || artist.isEmpty()) return@mapNotNull null
            MusicMetadata(
                title = title,
                artist = artist,
                url = song.optString("trackViewUrl").trim().ifBlank { null },
                artworkUrl = song.optString("artworkUrl100").trim().ifBlank { null }
            )
        }
    }

    /** The object at [url], or an empty one when what came back isn't an object, e.g. an error page. */
    private suspend fun fetchJson(url: String): JSONObject {
        val request = Request.Builder().url(url).get().build()
        return client.newCall(request).executeAsync().use { response ->
            val body = withContext(ioDispatcher) { response.body.stringAtMost() }
            JSONTokener(body).nextValue() as? JSONObject ?: JSONObject()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 6_000L

        /** Enough to fill the list, few enough that neither service is asked for a page nobody reads. */
        const val LIMIT = 20
    }
}