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
            // Each service's own repeated name is one song. A karaoke, a cover, a music box
            // or a piano version goes after the artist's own song. Deezer lists those first
            // when the artist is written in their own script, and Apple already has the song.
            songs.distinctBy { SongNames.normalize(it.title) + "|" + SongNames.normalize(it.artist) }
                .sortedBy { anotherRecording(it) }
        }
    }

    /**
     * A row that is not the singer's own recording. Deezer matches the artist inside these
     * titles, and some music-box rows put that only in the artist credit.
     */
    private fun anotherRecording(song: MusicMetadata): Boolean {
        if (creditsTheOriginal(song.title)) return true
        if (taggedAsAnotherVersion(song.title)) return true
        if (markedRecording(song.title) || markedRecording(song.artist)) return true
        if (KEY_CHANGE.containsMatchIn(song.title)) return true
        return remakeOfAnotherSong(song.title)
    }

    /** "Originally Performed by" names the singer and not the people on the row. */
    private fun creditsTheOriginal(title: String): Boolean =
        SongNames.coverOf(title) != null || ORIGINAL_CREDIT.containsMatchIn(title)

    /** A tag the title adds, "(Piano Version)" or "[カラオケ]". The song's own name does not count. */
    private fun taggedAsAnotherVersion(title: String): Boolean =
        SongNames.saysAnotherVersion(title, SongNames.bareTitle(title), "")

    /**
     * The same marks when they sit in the artist credit, or in fullwidth brackets.
     * Those stay part of the bare title, so the tag check does not see them.
     * A song whose whole name is one mark, "Karaoke", stays.
     */
    private fun markedRecording(text: String): Boolean {
        val named = SongNames.normalize(text)
        if (RECORDING_MARKS.any { named == it }) return false
        return RECORDING_MARKS.any { it in named }
    }

    /** "Cherry REMAKE" is another recording. A song whose name is "Remake" is not. */
    private fun remakeOfAnotherSong(title: String): Boolean {
        if ("remake" !in SongNames.words(title)) return false
        return SongNames.normalize(SongNames.bareTitle(title)) != "remake"
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

        /** The singer named in the title, with no brackets around the phrase. */
        val ORIGINAL_CREDIT = Regex("""originally\s+(?:performed\s+)?by|原曲歌手""", RegexOption.IGNORE_CASE)

        /** A karaoke key change, "+3Key" or "Key-1", which those catalogues add to the title. */
        val KEY_CHANGE = Regex("""[+-]\d+\s*key\b|\bkey\s*[+-]\d+""", RegexOption.IGNORE_CASE)

        /**
         * What a row says when it is not that singer's recording, in the scripts those
         * catalogues use. Matched on the letters left after [SongNames.normalize].
         */
        val RECORDING_MARKS = listOf(
            "オルゴール", "orgel", "musicbox", "karaoke", "караоке", "노래방", "커버"
        ).map(SongNames::normalize)
    }
}
