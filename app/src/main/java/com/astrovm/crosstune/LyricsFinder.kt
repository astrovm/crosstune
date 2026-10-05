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
import org.json.JSONTokener
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
    /** The app's own version, which LRCLIB asks a caller to give so a problem can be traced back. */
    private val versionName: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    /** A song's words, or why there are none to show. */
    internal sealed interface Lyrics {
        /** The words themselves. */
        data class Found(val words: String) : Lyrics

        /** The service answered, and holds none for this song. */
        data object None : Lyrics

        /** The service wouldn't answer, or was too slow: worth saying rather than claiming none. */
        data object Unavailable : Lyrics
    }

    /**
     * What there is to show for [metadata]'s song. A lookup that failed or timed out is asked once
     * more, since LRCLIB is often busy and a song's words are worth the second try; an answer of
     * "none" is final.
     */
    suspend fun lyricsOf(metadata: MusicMetadata, timeoutMs: Long = TIMEOUT_MS): Lyrics {
        // Only a song has words, and without an artist there's nothing to check an answer against.
        if (metadata.type != ItemType.TRACK || metadata.artist.isBlank() || metadata.title.isBlank()) return Lyrics.None
        repeat(2) {
            val answer = withTimeoutOrNull(timeoutMs) {
                try {
                    // No list at all means it wouldn't answer, which is not a song without words.
                    val answers = answersFor(metadata) ?: return@withTimeoutOrNull Lyrics.Unavailable
                    wordsOf(answers, metadata).let { if (it == null) Lyrics.None else Lyrics.Found(it) }
                } catch (_: IOException) {
                    Lyrics.Unavailable
                } catch (_: JSONException) {
                    Lyrics.Unavailable
                }
            } ?: return Lyrics.Unavailable
            if (answer !is Lyrics.Unavailable) return answer
        }
        return Lyrics.Unavailable
    }

    private fun wordsOf(answers: List<Answer>, metadata: MusicMetadata): String? {
        // The service's own answers first, then a close one, as [ExactMatcher] does.
        val song = answers.firstOrNull { it.isExact(metadata) } ?: answers.firstOrNull { it.belongsTo(metadata) }
        return song?.plain()
    }

    /** What the service said, or null when it said something that isn't a list of answers. */
    private suspend fun answersFor(metadata: MusicMetadata): List<Answer>? {
        val url = SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("track_name", metadata.title)
            .addQueryParameter("artist_name", metadata.artist)
            .build()
        // LRCLIB answers with a plain list of what it found. Its errors come back as an object
        // naming itself, which is no list of answers, so the difference between "none" and
        // "wouldn't answer" rests on it.
        val list = parse(url.toString()) as? JSONArray ?: return null
        return (0 until list.length()).mapNotNull { index -> Answer.of(list.optJSONObject(index)) }
    }

    private suspend fun parse(url: String): Any {
        val request = Request.Builder().url(url)
            // LRCLIB turns away a request that doesn't say who it is, answering with an error page
            // rather than lyrics, so it names the app and leaves a way to be told about a problem.
            .header("User-Agent", "Crosstune/$versionName (https://github.com/astrovm/crosstune)")
            .get()
            .build()
        return client.newCall(request).executeAsync().use { response ->
            val body = withContext(ioDispatcher) { response.body.stringAtMost() }
            JSONTokener(body).nextValue()
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