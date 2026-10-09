package com.astrovm.crosstune

import kotlin.math.abs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

/**
 * A song's words, from LRCLIB, which asks for no account and no key. Its answers are
 * crowdsourced, so what it gives back is checked against the song asked about before it's
 * believed: the words of a song with the same name by someone else are no use at all.
 *
 * Timed words come with when each line is sung, so they can follow the song as it plays; the plain
 * words are what's shown when nothing says where in the song it is.
 */
/** What LRCLIB's Retry-After asks for when it's busy. */
internal const val LYRICS_BUSY_PAUSE_MS = 1_000L

/** How far apart two recordings' lengths can be and still be the same one. */
internal const val SAME_RECORDING_LENGTH_MS = 3_000L

internal class LyricsFinder(
    private val client: OkHttpClient,
    /** The app's own version, which LRCLIB asks a caller to give so a problem can be traced back. */
    private val versionName: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** How long to wait before asking again after a failed lookup; tests pass 0, so they don't wait. */
    private val busyPauseMs: Long = LYRICS_BUSY_PAUSE_MS,
    /**
     * What songs' words were, so a song shown again needs no lookup. Only words found are kept: a
     * song without any is asked about again, since the places with words keep getting more.
     */
    private val cache: LookupCache? = null
) {
    /** A song's words, or why there are none to show. */
    internal sealed interface Lyrics {
        /** The words themselves, and their [lines] with when each is sung, when the service has them timed. */
        data class Found(val words: String, val lines: List<LyricLine> = emptyList()) : Lyrics

        /** The service answered, and holds none for this song. */
        data object None : Lyrics

        /** The service wouldn't answer, or was too slow: worth saying rather than claiming none. */
        data object Unavailable : Lyrics
    }

    /**
     * What there is to show for [metadata]'s song: LRCLIB's words first. A song from Japan, say, is
     * often there under its own names, not the ones in Latin letters a music app shows, so those are
     * asked for too, and NetEase, which has many more such songs, last. Words found anywhere win;
     * otherwise "none" from any of them is believed over one that wouldn't answer.
     */
    suspend fun lyricsOf(metadata: MusicMetadata, timeoutMs: Long = TIMEOUT_MS): Lyrics {
        // Only a song has words, and without an artist there's nothing to check an answer against.
        if (metadata.type != ItemType.TRACK || metadata.artist.isBlank() || metadata.title.isBlank()) return Lyrics.None
        val key = cacheKey(metadata)
        cache?.get(key)?.let { kept -> fromCache(kept)?.let { return it } }
        val answer = withTimeoutOrNull(TOTAL_TIMEOUT_MS) { anywhere(metadata, timeoutMs) } ?: Lyrics.Unavailable
        cache?.let { cache ->
            toCache(answer)?.let { cache.put(key, it) }
            cache.save()
        }
        return answer
    }

    /** The song, whatever the case or spacing of its name. */
    private fun cacheKey(metadata: MusicMetadata) = "${SongNames.normalize(metadata.title)}\u0000${SongNames.normalize(metadata.artist)}"

    /** Words as kept: the plain words and each timed line. */
    private fun toCache(answer: Lyrics): String? = (answer as? Lyrics.Found)?.let { found ->
        JSONObject()
            .put("words", found.words)
            .put("lines", JSONArray().apply { found.lines.forEach { put(JSONArray().put(it.timeMs).put(it.text)) } })
            .toString()
    }

    private fun fromCache(kept: String): Lyrics? {
        // "None", as earlier versions kept, isn't words, so it's asked again.
        return try {
            val json = JSONObject(kept)
            val lines = json.getJSONArray("lines").let { list ->
                (0 until list.length()).map { index -> list.getJSONArray(index).let { LyricLine(it.getLong(0), it.getString(1)) } }
            }
            Lyrics.Found(json.getString("words"), lines)
        } catch (_: JSONException) {
            null
        }
    }

    /**
     * Under the title as given, then, for one that's two names joined by a dash, as YouTube Music
     * gives "ミステリー・ガール - Mystery Girl", under each of them.
     */
    private suspend fun anywhere(metadata: MusicMetadata, timeoutMs: Long): Lyrics {
        val answers = SongNames.titleNames(metadata.title).map { title ->
            underName(metadata.copy(title = title), timeoutMs).also { if (it is Lyrics.Found) return it }
        }
        return if (Lyrics.None in answers) Lyrics.None else Lyrics.Unavailable
    }

    private suspend fun underName(metadata: MusicMetadata, timeoutMs: Long): Lyrics {
        val answers = mutableListOf<Lyrics>()
        suspend fun tried(answer: Lyrics) = answer.also { answers += it } is Lyrics.Found
        if (tried(fromLrclib(metadata, timeoutMs))) return answers.last()
        val recording = withTimeoutOrNull(timeoutMs) { inStore(metadata) }
        val own = recording?.let { withTimeoutOrNull(timeoutMs) { ownNames(metadata, it.id) } }
        if (own != null && tried(fromLrclib(own, timeoutMs))) return answers.last()
        val lengthMs = recording?.lengthMs
        if (tried(fromNetEase(own ?: metadata, lengthMs, timeoutMs))) return answers.last()
        if (own != null && tried(fromNetEase(metadata, lengthMs, timeoutMs))) return answers.last()
        return if (Lyrics.None in answers) Lyrics.None else Lyrics.Unavailable
    }

    /**
     * A song LRCLIB is busy for is asked about again, a moment later, as it asks, since it often turns
     * away many lookups in a row; an answer of "none" is final. All the tries together get
     * [LRCLIB_TIMEOUT_MS], so the other places still get their turn.
     */
    private suspend fun fromLrclib(metadata: MusicMetadata, timeoutMs: Long): Lyrics =
        withTimeoutOrNull(LRCLIB_TIMEOUT_MS) { lrclib(metadata, timeoutMs) } ?: Lyrics.Unavailable

    private suspend fun lrclib(metadata: MusicMetadata, timeoutMs: Long): Lyrics {
        repeat(TRIES) { tried ->
            if (tried > 0) delay(busyPauseMs)
            val answer = withTimeoutOrNull(timeoutMs) {
                try {
                    // No list at all means it wouldn't answer, which is not a song without words.
                    val answers = answersFor(metadata) ?: return@withTimeoutOrNull Lyrics.Unavailable
                    wordsOf(answers, metadata) ?: Lyrics.None
                } catch (_: IOException) {
                    Lyrics.Unavailable
                } catch (_: JSONException) {
                    Lyrics.Unavailable
                }
            } ?: Lyrics.Unavailable
            if (answer !is Lyrics.Unavailable) return answer
        }
        return Lyrics.Unavailable
    }

    private fun wordsOf(answers: List<Answer>, metadata: MusicMetadata): Lyrics.Found? {
        // The service's own answers first, then a close one, as [ExactMatcher] does. Among those, one
        // with timed words wins, so they can follow the song.
        val songs = answers.filter { it.isExact(metadata) }.ifEmpty { answers.filter { it.belongsTo(metadata) } }
        val song = songs.firstOrNull { it.synced().isNotEmpty() } ?: songs.firstOrNull() ?: return null
        val lines = song.synced()
        // Timed words are the words too, once their timings are taken off.
        val words = song.plain() ?: lines.joinToString("\n") { it.text }.trim().ifEmpty { return null }
        return Lyrics.Found(words, lines)
    }

    /** A recording in the iTunes Store: its [id], and how long it lasts, when that's given. */
    private data class Recording(val id: Long, val lengthMs: Long?)

    /**
     * The song in the US iTunes Store, which names it as music apps do, in Latin letters: by the
     * artist given, or by one it only writes in another script. Null when it's not there.
     */
    private suspend fun inStore(metadata: MusicMetadata): Recording? = try {
        val search = ITUNES_SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("term", "${metadata.title} ${metadata.artist}")
            .addQueryParameter("country", "us")
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "10")
            .build()
        val found = (parse(search.toString()) as? JSONObject)?.optJSONArray("results")
        found?.let { list ->
            (0 until list.length()).mapNotNull { list.optJSONObject(it) }.firstOrNull { song ->
                val artist = song.optString("artistName")
                SongNames.same(song.optString("trackName"), metadata.title) &&
                    (SongNames.sameArtist(artist, metadata.artist) || !artist.hasLatin())
            }
        }?.let { song ->
            Recording(song.optLong("trackId"), song.optLong("trackTimeMillis").takeIf { it > 0 }).takeIf { it.id > 0 }
        }
    } catch (_: IOException) {
        null
    } catch (_: JSONException) {
        null
    }

    /**
     * The song's names as written where it comes from, say in Japanese, when they're not the ones
     * given: the Japanese iTunes Store names recording [id] in its own. Null when it's not there, or
     * has no other names.
     */
    private suspend fun ownNames(metadata: MusicMetadata, id: Long): MusicMetadata? = try {
        val lookup = ITUNES_LOOKUP_URL.toHttpUrl().newBuilder()
            .addQueryParameter("id", id.toString())
            .addQueryParameter("country", "jp")
            .build()
        (parse(lookup.toString()) as? JSONObject)?.optJSONArray("results")?.optJSONObject(0)?.let { song ->
            val title = song.optString("trackName").trim()
            val artist = song.optString("artistName").trim()
            metadata.copy(title = title, artist = artist).takeIf {
                title.isNotEmpty() && artist.isNotEmpty() && !(SongNames.same(title, metadata.title) && SongNames.same(artist, metadata.artist))
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: JSONException) {
        null
    }

    /**
     * NetEase Cloud Music's words for the song, which has many from Japan, Korea and China that
     * LRCLIB hasn't. Only a song of the same name by the same artist is taken, and the credits it
     * opens its words with are left off.
     */
    private suspend fun fromNetEase(metadata: MusicMetadata, lengthMs: Long?, timeoutMs: Long): Lyrics = withTimeoutOrNull(timeoutMs) {
        try {
            val search = NETEASE_SEARCH_URL.toHttpUrl().newBuilder()
                .addQueryParameter("s", "${metadata.title} ${metadata.artist}")
                .addQueryParameter("type", "1")
                .addQueryParameter("limit", "10")
                .build()
            val result = (parse(search.toString(), NETEASE_REFERER) as? JSONObject)?.optJSONObject("result")
                ?: return@withTimeoutOrNull Lyrics.Unavailable
            val songs = result.optJSONArray("songs") ?: return@withTimeoutOrNull Lyrics.None
            val id = (0 until songs.length()).mapNotNull { songs.optJSONObject(it) }.firstOrNull { song ->
                val artists = song.optJSONArray("artists")?.let { list -> (0 until list.length()).map { list.optJSONObject(it)?.optString("name").orEmpty() } }.orEmpty()
                // An artist only written in another script there, say パイパー for Piper, counts when
                // the recording lasts as long as the store's.
                val sameLength = lengthMs != null && abs(song.optLong("duration") - lengthMs) <= SAME_RECORDING_LENGTH_MS
                SongNames.same(song.optString("name"), metadata.title) &&
                    artists.any { SongNames.sameArtist(it, metadata.artist) || SongNames.artistInside(it, metadata.artist) || (sameLength && !it.hasLatin()) }
            }?.optLong("id") ?: return@withTimeoutOrNull Lyrics.None
            val lyric = NETEASE_LYRIC_URL.toHttpUrl().newBuilder()
                .addQueryParameter("id", id.toString())
                .addQueryParameter("lv", "1")
                .build()
            val lrc = (parse(lyric.toString(), NETEASE_REFERER) as? JSONObject)?.optJSONObject("lrc")?.optString("lyric").orEmpty()
            netEaseWords(lrc) ?: Lyrics.None
        } catch (_: IOException) {
            Lyrics.Unavailable
        } catch (_: JSONException) {
            Lyrics.Unavailable
        }
    } ?: Lyrics.Unavailable

    /** NetEase's words, without the credits, such as "作词 : …", it puts first; null for none, or an instrumental. */
    private fun netEaseWords(lrc: String): Lyrics.Found? {
        val stamped = SyncedLyrics.parse(lrc)
        val timed = stamped.filterNot { netEaseCredit.containsMatchIn(it.text) }
        val lines = SyncedLyrics.withPauses(timed)
        // Words with timings are only those; plain words, only when there are no timings at all.
        val words = if (stamped.isNotEmpty()) timed.joinToString("\n") { it.text }.trim()
        else lrc.lines().filterNot { netEaseCredit.containsMatchIn(it) }.joinToString("\n").trim()
        if (words.isEmpty() || words.contains(NETEASE_INSTRUMENTAL)) return null
        return Lyrics.Found(words, lines)
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

    private suspend fun parse(url: String, referer: String? = null): Any {
        val request = Request.Builder().url(url)
            // NetEase only answers a request that seems to come from its own site.
            .apply { referer?.let { header("Referer", it) } }
            // LRCLIB turns away a request that doesn't say who it is, answering with an error page
            // rather than lyrics, so it names the app and leaves a way to be told about a problem.
            .header("User-Agent", "Crosstune/$versionName (https://github.com/astrovm/crosstune)")
            .get()
            .build()
        return client.newCall(request).executeAsync().use { response ->
            if (!response.isSuccessful) throw IOException("Lyrics service returned ${response.code}")
            val body = withContext(ioDispatcher) { response.body.stringAtMost() }
            JSONTokener(body).nextValue()
        }
    }

    /** One answer: whose song it claims to be, and its words. */
    private class Answer(private val title: String, private val artist: String, private val json: JSONObject) {
        fun plain(): String? = (json.opt("plainLyrics") as? String)?.trim()?.takeIf { it.isNotEmpty() }

        /** The words with when each line is sung, empty when it has none timed. */
        fun synced(): List<LyricLine> = (json.opt("syncedLyrics") as? String)?.let { SyncedLyrics.withPauses(SyncedLyrics.parse(it)) }.orEmpty()

        /**
         * The very song asked about, name for name. A title compared without its edition tag would
         * make a remaster the same song as the plain recording, so [SongNames.normalize] is used on
         * its own here and no tag is taken off: when both this and a plain-titled answer are there,
         * the plain one wins. [belongsTo] takes the tag off, so no song's words are lost over it.
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
                val title = (json.opt("trackName") as? String)?.trim().orEmpty()
                val artist = (json.opt("artistName") as? String)?.trim().orEmpty()
                if (title.isEmpty() || artist.isEmpty()) return null
                return Answer(title, artist, json)
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val TOTAL_TIMEOUT_MS = 30_000L
        const val LRCLIB_TIMEOUT_MS = 12_000L
        const val TRIES = 5

        const val SEARCH_URL = "https://lrclib.net/api/search"
        const val ITUNES_SEARCH_URL = "https://itunes.apple.com/search"
        const val ITUNES_LOOKUP_URL = "https://itunes.apple.com/lookup"
        const val NETEASE_SEARCH_URL = "https://music.163.com/api/search/get"
        const val NETEASE_LYRIC_URL = "https://music.163.com/api/song/lyric"
        const val NETEASE_REFERER = "https://music.163.com/"


        /** What NetEase writes for a song without words: "pure music, please enjoy". */
        const val NETEASE_INSTRUMENTAL = "纯音乐，请欣赏"

        /** A credit line: who wrote, composed, arranged or produced it, in Chinese, Japanese or English. */
        val netEaseCredit = Regex("""^\s*(?:作词|作詞|作曲|编曲|編曲|制作人|製作人|词|詞|曲|Lyricist|Lyrics|Composer|Arranger|Producer)\s*[:：]""", RegexOption.IGNORE_CASE)
    }

/** Whether any of it is written in Latin letters. */
private fun String.hasLatin() = any { it in 'a'..'z' || it in 'A'..'Z' }
}
