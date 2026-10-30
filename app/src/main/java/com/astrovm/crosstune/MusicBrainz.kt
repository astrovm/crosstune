package com.astrovm.crosstune

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** The year a song first came out and the album it first came out on, when MusicBrainz is sure of them. */
internal data class ReleaseFacts(val year: Int, val album: String?)

/**
 * When a song first came out and on what album, from MusicBrainz, which people keep in order. Asked
 * rather than left to ChatGPT's web search, which gave another year or album each time it was asked.
 * Anything it isn't sure of is left out: a wrong year or album is worse than none. What it found is
 * kept in [cache], so a song is asked about once.
 */
internal class MusicBrainz(
    private val client: OkHttpClient,
    /** The app's own version, which MusicBrainz asks a caller to give so a problem can be traced back. */
    private val versionName: String,
    private val cache: LookupCache,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** How long to leave between two requests, which MusicBrainz limits to one a second; tests pass 0. */
    private val gapMs: Long = ASK_GAP_MS
) {
    /** [song]'s facts; null when MusicBrainz isn't sure which song it is, or couldn't be asked. */
    suspend fun release(song: MusicMetadata): ReleaseFacts? {
        val artist = SongNames.artistNames(song.artist).firstOrNull() ?: return null
        val title = SongNames.withoutEditionTag(song.title).trim().ifEmpty { return null }
        val key = "release|${song.title}|${song.artist}"
        cache.get(key)?.let { return read(it) }
        val facts = try {
            find(song, title, artist)
        } catch (_: IOException) {
            // Not kept, it's just asked again.
            return null
        } catch (_: JSONException) {
            return null
        }
        // An answer of none is kept too, so the song gets the same facts, or none, every time.
        cache.put(key, facts?.let { JSONObject().put("year", it.year).put("album", it.album).toString() }.orEmpty())
        cache.save()
        return facts
    }

    /**
     * The Spotify id of the artist called [name], from the links MusicBrainz keeps of them; null when it
     * has none, knows no artist of that name, or couldn't be asked. Kept in [cache] like the facts.
     */
    suspend fun spotifyArtist(name: String): String? {
        val key = "spotify-artist|$name"
        cache.get(key)?.let { return it.ifEmpty { null } }
        val id = try {
            findSpotifyArtist(name)
        } catch (_: IOException) {
            return null
        } catch (_: JSONException) {
            return null
        }
        cache.put(key, id.orEmpty())
        cache.save()
        return id
    }

    private suspend fun findSpotifyArtist(name: String): String? {
        val search = "$API/artist".toHttpUrl().newBuilder().addQueryParameter("query", "artist:${quoted(name)}").addQueryParameter("limit", "5").build()
        // The first called just that, not one with more to their name, as a tribute act has. Should it be another artist
        // who goes by it, the song isn't among their songs, so none is found.
        val artist = get(search).optJSONArray("artists").objects().firstOrNull { SongNames.normalize(it.optString("name")) == SongNames.normalize(name) } ?: return null
        val links = get("$API/artist/${artist.getString("id")}".toHttpUrl().newBuilder().addQueryParameter("inc", "url-rels").build())
        return links.optJSONArray("relations").objects()
            .firstNotNullOfOrNull { SPOTIFY_ARTIST.matchEntire(it.optJSONObject("url")?.optString("resource").orEmpty())?.groupValues?.get(1) }
    }

    private fun read(kept: String): ReleaseFacts? =
        kept.takeIf { it.isNotEmpty() }?.let { JSONObject(it) }?.let { ReleaseFacts(it.getInt("year"), it.optString("album").ifEmpty { null }) }

    private suspend fun find(song: MusicMetadata, title: String, artist: String): ReleaseFacts? {
        val wanted = SongNames.artists(song.artist)
        // The same recordings of the song by the same artist, the earliest out first. The first of them is
        // where the song first came out: its live takes, remixes and covers come after it.
        val found = search(title, artist).filter { isTheSong(it, song.title, wanted) }.sortedBy { it.getString("first-release-date") }
        val recordings = found.filter { it.optString("disambiguation").isEmpty() }
        val year = recordings.firstOrNull()?.let { yearOf(it.getString("first-release-date")) } ?: return null
        // A recording with a note, "live, 1997" or "radio edit", isn't the song's first. But one from before that
        // year might be, as the original of "Creep" is noted "explicit": it isn't sure when the song came out.
        if (found.any { (yearOf(it.getString("first-release-date")) ?: year) < year }) return null
        // The album is looked for on the recordings from that year, as the one asked about may be on none that
        // MusicBrainz lists with it: a plain album is its song's own, a compilation's or a live one is another's.
        val album = recordings.filter { yearOf(it.getString("first-release-date")) == year }.take(RECORDINGS_TRIED)
            .firstNotNullOfOrNull { albumOf(it.getString("id"), year) }
        return ReleaseFacts(year, album)
    }

    /** The recordings MusicBrainz has of [title] by [artist] on official albums, singles and EPs, 100 at most. */
    private suspend fun search(title: String, artist: String): List<JSONObject> {
        val url = "$API/recording".toHttpUrl().newBuilder()
            .addQueryParameter("query", "recording:${quoted(title)} AND artist:${quoted(artist)} AND status:official AND primarytype:(album OR single OR ep)")
            .addQueryParameter("limit", "100")
            .build()
        return get(url).optJSONArray("recordings").objects()
    }

    /**
     * Whether [recording] is the song [title] by [wanted] and not another take of it: one only on live albums is
     * one. Nor does one without a date say when.
     */
    private fun isTheSong(recording: JSONObject, title: String, wanted: Set<String>): Boolean {
        val releases = recording.optJSONArray("releases").objects()
        val live = releases.isNotEmpty() && releases.all { release -> "Live" in strings(release.optJSONObject("release-group")?.optJSONArray("secondary-types")) }
        return !live && yearOf(recording.optString("first-release-date")) != null &&
            SongNames.same(recording.optString("title"), title) && credited(recording).any(wanted::contains)
    }

    /** Who [recording] is credited to, as the names [SongNames.artists] gives, and the other names its artists go by. */
    private fun credited(recording: JSONObject): Set<String> {
        val credit = recording.optJSONArray("artist-credit").objects()
        val names = credit.flatMap { part ->
            val artist = part.optJSONObject("artist")
            listOf(artist?.optString("name").orEmpty()) + artist?.optJSONArray("aliases").objects().map { it.optString("name") }
        }
        return SongNames.artists(credit.joinToString("") { it.optString("name") + it.optString("joinphrase") }) + names.map(SongNames::normalize)
    }

    /**
     * The plain album, not a compilation, a live one or a soundtrack, that the recording [id] first came out on in
     * [year] or the year after; null when there's none, or two of them came out the same day.
     */
    private suspend fun albumOf(id: String, year: Int): String? {
        val url = "$API/release".toHttpUrl().newBuilder()
            .addQueryParameter("recording", id)
            .addQueryParameter("status", "official")
            .addQueryParameter("type", "album")
            .addQueryParameter("inc", "release-groups")
            .addQueryParameter("limit", "100")
            .build()
        val albums = get(url).optJSONArray("releases").objects().mapNotNull { it.optJSONObject("release-group") }
            .filter { it.optString("primary-type") == "Album" && strings(it.optJSONArray("secondary-types")).isEmpty() }
            .distinctBy { it.optString("id") }
            .filter { group -> yearOf(group.optString("first-release-date"))?.let { it in year..year + 1 } == true }
        val first = albums.minOfOrNull { it.getString("first-release-date") } ?: return null
        return albums.filter { it.getString("first-release-date") == first }.map { it.optString("title") }.distinct().singleOrNull()
    }

    /** [text] as a phrase in a search; quotes and backslashes are all that mean anything inside one. */
    private fun quoted(text: String) = "\"${text.replace("\"", "").replace("\\", "")}\""

    private fun strings(array: JSONArray?): List<String> = array?.let { list -> (0 until list.length()).map(list::optString) }.orEmpty()

    private fun yearOf(date: String): Int? = date.take(YEAR_DIGITS).takeIf { it.length == YEAR_DIGITS }?.toIntOrNull()

    private suspend fun get(url: HttpUrl): JSONObject = turn.withLock {
        delay(maxOf(0, askedAt + gapMs - System.currentTimeMillis()))
        // MusicBrainz turns away a request that doesn't say who it is.
        val request = Request.Builder().url(url).header("User-Agent", "Crosstune/$versionName (https://github.com/astrovm/crosstune)").header("Accept", "application/json").get().build()
        try {
            client.newCall(request).answer(ioDispatcher) { response ->
                if (!response.isSuccessful) throw IOException("MusicBrainz returned ${response.code}")
                JSONObject(response.body.stringAtMost())
            }
        } finally {
            askedAt = System.currentTimeMillis()
        }
    }

    private companion object {
        /** Whose turn it is to ask, and when it last was: the app's, and its queue's, which looks songs up on its own. */
        val turn = Mutex()
        private var askedAt = 0L

        const val API = "https://musicbrainz.org/ws/2"
        val SPOTIFY_ARTIST = Regex("""https://open\.spotify\.com/artist/([A-Za-z0-9]{22})""")
        const val ASK_GAP_MS = 1100L
        const val YEAR_DIGITS = 4

        /** A song's recordings from its first year whose albums are looked at, to stay inside MusicBrainz's one request a second. */
        const val RECORDINGS_TRIED = 3
    }
}
