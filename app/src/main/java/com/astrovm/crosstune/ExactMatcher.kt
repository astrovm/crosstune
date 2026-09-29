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
import java.text.Normalizer
import java.util.Locale

/**
 * Finds a direct link to the same item using services' official, key-less search APIs:
 * Apple's iTunes Search API and Deezer's public API. Other destinations have no such API,
 * so they keep using a search. Any failure or uncertain match returns null so the caller
 * falls back to a search.
 */
internal class ExactMatcher(
    private val client: OkHttpClient,
    private val country: String = Locale.getDefault().country,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun find(target: SearchTarget, metadata: SpotifyMetadata): String? {
        if (metadata.type == SpotifyType.PLAYLIST) return null
        return withTimeoutOrNull(TIMEOUT_MS) {
            try {
                when (target) {
                    SearchTarget.APPLE_MUSIC -> findOnAppleMusic(metadata)
                    SearchTarget.DEEZER -> findOnDeezer(metadata)
                    else -> null
                }
            } catch (_: IOException) {
                null
            } catch (_: JSONException) {
                null
            }
        }
    }

    private suspend fun findOnAppleMusic(metadata: SpotifyMetadata): String? {
        val (entity, nameKey, urlKey) = when (metadata.type) {
            SpotifyType.TRACK -> Triple("song", "trackName", "trackViewUrl")
            SpotifyType.ALBUM -> Triple("album", "collectionName", "collectionViewUrl")
            else -> Triple("musicArtist", "artistName", "artistLinkUrl")
        }
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", searchQuery(metadata))
            .addQueryParameter("entity", entity)
            .addQueryParameter("limit", "10")
            .apply { if (country.length == 2) addQueryParameter("country", country) }
            .build()
        val results = fetchJson(url.toString()).optJSONArray("results") ?: return null
        return results.objects().firstOrNull { result ->
            matches(metadata, result.optString(nameKey), result.optString("artistName"))
        }?.optString(urlKey)?.ifBlank { null }
    }

    private suspend fun findOnDeezer(metadata: SpotifyMetadata): String? {
        val path = when (metadata.type) {
            SpotifyType.TRACK -> "search/track"
            SpotifyType.ALBUM -> "search/album"
            else -> "search/artist"
        }
        val url = "https://api.deezer.com/$path".toHttpUrl().newBuilder()
            .addQueryParameter("q", searchQuery(metadata))
            .addQueryParameter("limit", "10")
            .build()
        val results = fetchJson(url.toString()).optJSONArray("data") ?: return null
        return results.objects().firstOrNull { result ->
            val name = result.optString("title").ifEmpty { result.optString("name") }
            val artist = result.optJSONObject("artist")?.optString("name") ?: name
            matches(metadata, name, artist)
        }?.optString("link")?.ifBlank { null }
    }

    private suspend fun fetchJson(url: String): JSONObject {
        val request = Request.Builder().url(url).get().build()
        return client.newCall(request).executeAsync().use { response ->
            JSONObject(withContext(ioDispatcher) { response.body.string() })
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    /** Requires the same name and artist so remixes, covers and deluxe editions don't win by rank. */
    private fun matches(metadata: SpotifyMetadata, name: String, artist: String): Boolean {
        if (normalize(name) != normalize(metadata.title)) return false
        if (metadata.type == SpotifyType.ARTIST) return true
        val wanted = normalize(metadata.artist)
        return wanted.isEmpty() || normalize(artist).contains(wanted)
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}]+"""), "")

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
