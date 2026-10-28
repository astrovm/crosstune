package com.astrovm.crosstune

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
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
        return coroutineScope {
            // Deezer first, so its answers lead; a service that won't answer costs only its own
            // part, and the other is still asked.
            val deezer = async { withTimeoutOrNull(timeoutMs) { answering { deezer(words) } } ?: emptyList() }
            val itunes = async { withTimeoutOrNull(timeoutMs) { answering { itunes(words) } } ?: emptyList() }
            // A Chinese song typed as it's said, "qing tian jay chou", which only Hong Kong's store
            // looks up by; its songs whose name says that come first.
            val pinyin = async {
                if (words.any { it.code > 0x7f }) return@async emptyList()
                val typed = SongNames.normalize(words)
                val found = withTimeoutOrNull(timeoutMs) { answering { itunes(words, PINYIN_STORE) } }.orEmpty()
                found.filter { song -> Readings.pinyinLetters(SongNames.bareTitle(song.title))?.let { it in typed } == true }
            }
            val songs = pinyin.await() + deezer.await() + itunes.await()
            // Each service's own repeated name is one song.
            songs.distinctBy { SongNames.normalize(it.title) + "|" + SongNames.normalize(it.artist) }
        }
    }

    /** What [find] found, or nothing when the service is unreachable or sends back rubbish. */
    private suspend fun answering(find: suspend () -> List<MusicMetadata>): List<MusicMetadata> =
        try {
            find()
        } catch (_: IOException) {
            emptyList()
        } catch (_: JSONException) {
            emptyList()
        }

    /** What Deezer holds for [query]; empty when it won't answer or has nothing. */
    private suspend fun deezer(query: String): List<MusicMetadata> {
        val url = "https://api.deezer.com/search/track".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", "$LIMIT")
            .build()
        val results = fetchJson(url.toString()).optJSONArray("data") ?: return emptyList()
        val typed = query.split(Regex("\\s+")).map(SongNames::normalize).filter { it.isNotEmpty() }
        return (0 until results.length()).mapNotNull { index ->
            val song = results.optJSONObject(index) ?: return@mapNotNull null
            val title = MetadataParsers.deezerTitle(song).trim()
            val artist = song.optJSONObject("artist")?.text("name").orEmpty()
            // Without both a name and an artist there's no song to look up anywhere else.
            if (title.isEmpty() || artist.isEmpty()) return@mapNotNull null
            val named = SongNames.normalize("$title $artist")
            val found = MusicMetadata(
                title = title,
                artist = artist,
                // Deezer's own link, so a song picked from here opens as itself in its app.
                url = song.text("link").ifBlank { null },
                artworkUrl = song.optJSONObject("album")?.text("cover_big")?.ifBlank { null }
            )
            Triple(found, typed.count { it in named }, song.optLong("rank"))
        }
            // Deezer puts the name said word for word first, so a cover named just like the song,
            // "Quevedo BZRP Music Sessions, Vol. 52", came before it. Of those with as much of
            // what was typed, the one more people play is first.
            .sortedWith(compareByDescending<Triple<MusicMetadata, Int, Long>> { it.second }.thenByDescending { it.third })
            .map { it.first }
    }

    /** What Apple's search holds for [query]; empty when it won't answer or has nothing. */
    private suspend fun itunes(query: String, store: String = country): List<MusicMetadata> {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", query)
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "$LIMIT")
            .apply { if (store.length == 2) addQueryParameter("country", store) }
            .build()
        val results = fetchJson(url.toString()).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { index ->
            val song = results.optJSONObject(index) ?: return@mapNotNull null
            val title = song.text("trackName")
            val artist = song.text("artistName")
            if (title.isEmpty() || artist.isEmpty()) return@mapNotNull null
            MusicMetadata(
                title = title,
                artist = artist,
                url = song.text("trackViewUrl").ifBlank { null },
                artworkUrl = song.text("artworkUrl100").ifBlank { null }
            )
        }
    }

    /** The object at [url], or an empty one when what came back isn't an object, e.g. an error page. */
    private suspend fun fetchJson(url: String): JSONObject {
        val request = Request.Builder().url(url).get().build()
        return client.newCall(request).answer(ioDispatcher) { response ->
            if (!response.isSuccessful) throw IOException("Song service returned ${response.code}")
            JSONTokener(response.body.stringAtMost()).nextValue() as? JSONObject ?: JSONObject()
        }
    }

    private fun JSONObject.text(key: String): String = (opt(key) as? String)?.trim().orEmpty()

    private companion object {
        const val TIMEOUT_MS = 6_000L

        /** Apple's store that finds Chinese songs by their pinyin. */
        const val PINYIN_STORE = "HK"

        /** Enough to fill the list, few enough that neither service is asked for a page nobody reads. */
        const val LIMIT = 20
    }
}
