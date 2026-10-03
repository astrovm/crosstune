package com.astrovm.crosstune

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds a direct link to the same item using services' key-less search APIs: Apple's iTunes
 * Search API and Deezer's public API, plus the search behind Bandcamp's and YouTube Music's own
 * websites, which have no documented API. Other destinations need credentials, so they keep using a
 * search. Any failure or uncertain match returns null so the caller falls back to a search.
 */
internal class ExactMatcher(
    private val client: OkHttpClient,
    private val country: String = Locale.getDefault().country,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val leadingArticle = Regex("""^the\s+""", RegexOption.IGNORE_CASE)
    private val artistSeparator = Regex(
        """\s*(?:,|&|\+|/|\bx\b|\bfeat\.?|\bft\.?|\bfeaturing\b)\s*""",
        RegexOption.IGNORE_CASE
    )

    /** What an artist's own Bandcamp page name may add to their name. */
    private val pageSuffixes = listOf("", "music", "band", "official", "officialmusic")

    /**
     * Whether YouTube Music counts a YouTube video as music: a song or an official music video,
     * not someone's own upload such as a tutorial. Null when it can't be told, e.g. offline.
     */
    suspend fun isMusicVideo(videoId: String): Boolean? = withTimeoutOrNull(TIMEOUT_MS) {
        try {
            val client = JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", YOUTUBE_MUSIC_CLIENT_VERSION)
            val body = JSONObject().put("context", JSONObject().put("client", client)).put("videoId", videoId)
            val type = musicVideoTypeRegex.find(fetchJson(YOUTUBE_MUSIC_NEXT_URL, body).toString())?.groupValues?.get(1)
            type?.let { it in MUSIC_VIDEO_TYPES }
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        }
    }

    private val musicVideoTypeRegex = Regex(""""musicVideoType":"MUSIC_VIDEO_TYPE_([A-Z_]+)"""")

    suspend fun find(target: MusicService, metadata: MusicMetadata): String? {
        if (metadata.type == ItemType.PLAYLIST) return null
        return withTimeoutOrNull(TIMEOUT_MS) {
            try {
                when (target) {
                    MusicService.APPLE_MUSIC -> findOnAppleMusic(metadata)
                    MusicService.DEEZER -> findOnDeezer(metadata)
                    MusicService.BANDCAMP -> findOnBandcamp(metadata)
                    MusicService.YOUTUBE_MUSIC -> findOnYouTubeMusic(metadata, YOUTUBE_MUSIC_WATCH_URL)
                    MusicService.YOUTUBE -> findOnYouTubeMusic(metadata, YOUTUBE_WATCH_URL)
                    else -> null
                }
            } catch (_: IOException) {
                null
            } catch (_: JSONException) {
                null
            }
        }
    }

    /**
     * One queue for [tracks] in YouTube Music or YouTube ([target]): each song is matched to its
     * video, then YouTube makes a temporary playlist of those videos, opened at the first. No
     * account needed. [onProgress] hears how many have been looked up. Null when none match.
     */
    suspend fun youtubeQueue(target: MusicService, tracks: List<MusicMetadata>, onProgress: (Int) -> Unit): String? {
        val looked = AtomicInteger()
        val lookups = Semaphore(QUEUE_LOOKUPS_AT_ONCE)
        val ids = coroutineScope {
            tracks.map { track ->
                async {
                    lookups.withPermit { find(target, track) }.also { onProgress(looked.incrementAndGet()) }
                }
            }.awaitAll()
        }.mapNotNull { it?.toHttpUrl()?.queryParameter("v") }
        if (ids.isEmpty()) return null
        val watch = if (target == MusicService.YOUTUBE_MUSIC) YOUTUBE_MUSIC_WATCH_URL else YOUTUBE_WATCH_URL
        val list = try {
            temporaryPlaylist(ids)
        } catch (_: IOException) {
            null
        }
        // Without the playlist, the first song still plays.
        return watch + ids.first() + (list?.let { "&list=$it" } ?: "")
    }

    /** YouTube answers a list of videos with a redirect to a temporary playlist of them. */
    private suspend fun temporaryPlaylist(ids: List<String>): String? {
        val url = YOUTUBE_WATCH_VIDEOS_URL.toHttpUrl().newBuilder().addQueryParameter("video_ids", ids.joinToString(",")).build()
        val noRedirects = client.newBuilder().followRedirects(false).build()
        return noRedirects.newCall(Request.Builder().url(url).get().build()).executeAsync().use { response ->
            response.header("Location")?.toHttpUrlOrNull()?.queryParameter("list")
        }
    }

    private suspend fun findOnAppleMusic(metadata: MusicMetadata): String? {
        val (entity, nameKey, urlKey) = when (metadata.type) {
            ItemType.TRACK -> Triple("song", "trackName", "trackViewUrl")
            ItemType.ALBUM -> Triple("album", "collectionName", "collectionViewUrl")
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
            // Apple marks some albums in their name, e.g. "Name - Single", which the source doesn't.
            val name = result.optString(nameKey).removeSuffix(" - Single").removeSuffix(" - EP")
            matches(metadata, name, result.optString("artistName"))
        }?.optString(urlKey)?.ifBlank { null }
    }

    private suspend fun findOnDeezer(metadata: MusicMetadata): String? {
        val path = when (metadata.type) {
            ItemType.TRACK -> "search/track"
            ItemType.ALBUM -> "search/album"
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

    /**
     * Songs and albums only. An artist match would rest on the name alone, and anyone can register a page
     * named after a famous artist (or share their name), so artists are left to the search.
     */
    private suspend fun findOnBandcamp(metadata: MusicMetadata): String? {
        val filter = when (metadata.type) {
            ItemType.TRACK -> "t"
            ItemType.ALBUM -> "a"
            else -> return null
        }
        val body = JSONObject()
            .put("search_text", searchQuery(metadata))
            .put("search_filter", filter)
            .put("fan_id", JSONObject.NULL)
            .put("full_page", false)
        val results = fetchJson(BANDCAMP_SEARCH_URL, body).optJSONObject("auto")?.optJSONArray("results") ?: return null
        return results.objects().firstOrNull { result ->
            matches(metadata, result.optString("name"), result.optString("band_name").ifEmpty { result.optString("name") }) &&
                isOnArtistsOwnPage(metadata, result.optString("item_url_root"))
        }?.optString("item_url_path")?.ifBlank { null }
    }

    /**
     * Anyone can upload to Bandcamp under any artist name, so a fan's cover credited to "Rick Astley"
     * would match. Real artists sit on a page named after them, like catpower.bandcamp.com, or with a
     * usual suffix, like catpowermusic. A whole name is required: "sia" must not pass on asia.bandcamp.com.
     * Anything else, e.g. a label's page, is left to the search fallback.
     */
    private fun isOnArtistsOwnPage(metadata: MusicMetadata, pageUrl: String): Boolean {
        val host = normalize(pageUrl.toHttpUrlOrNull()?.host?.removeSuffix(".bandcamp.com").orEmpty())
        if (host.isEmpty()) return false
        // Without an artist there is nothing to check the page against, so leave it to the search.
        return artistNames(metadata.artist).any { name ->
            // "The" only counts as an article when it is a word of its own: not in "Thelonious".
            listOf(name, name.replace(leadingArticle, "")).map(::normalize).any { candidate ->
                candidate.isNotEmpty() && pageSuffixes.any { suffix -> host == candidate + suffix }
            }
        }
    }

    /**
     * YouTube Music's search, filtered to songs, albums or artists. Songs are also watchable on
     * YouTube itself, which has no such filter, so [watchUrl] picks the app the video id opens in.
     */
    private suspend fun findOnYouTubeMusic(metadata: MusicMetadata, watchUrl: String): String? {
        val (params, isSong) = when (metadata.type) {
            ItemType.TRACK -> YOUTUBE_MUSIC_SONGS to true
            ItemType.ALBUM -> YOUTUBE_MUSIC_ALBUMS to false
            else -> YOUTUBE_MUSIC_ARTISTS to false
        }
        // Only songs can be opened in the YouTube app, and it would just be a search for the rest.
        if (!isSong && watchUrl == YOUTUBE_WATCH_URL) return null
        val client = JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", YOUTUBE_MUSIC_CLIENT_VERSION)
            .put("hl", "en")
        val body = JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("query", searchQuery(metadata))
            .put("params", params)
        val items = fetchJson(YOUTUBE_MUSIC_SEARCH_URL, body).listItems()
        return items.firstNotNullOfOrNull { item ->
            val columns = item.optJSONArray("flexColumns")?.objects()
                ?.map { it.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.text().orEmpty() }
                ?: return@firstNotNullOfOrNull null
            val name = columns.firstOrNull().orEmpty()
            // The second line is "Artist • Album • 4:46" for songs and "Album • Artist • 2012" for albums.
            val details = columns.getOrNull(1).orEmpty().split(" • ")
            val artist = if (isSong) details.firstOrNull().orEmpty() else details.getOrNull(1).orEmpty()
            if (!matches(metadata, name, artist.ifEmpty { name })) return@firstNotNullOfOrNull null
            when (metadata.type) {
                ItemType.TRACK -> item.optJSONObject("playlistItemData")?.optString("videoId")?.ifBlank { null }
                    ?.let { watchUrl + it }
                else -> item.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
                    ?.ifBlank { null }
                    ?.let { (if (metadata.type == ItemType.ALBUM) YOUTUBE_MUSIC_ALBUM_URL else YOUTUBE_MUSIC_ARTIST_URL) + it }
            }
        }
    }

    private fun JSONObject.text(): String =
        optJSONObject("text")?.optJSONArray("runs")?.objects()?.joinToString("") { it.optString("text") }.orEmpty()

    /** The result rows, which sit at varying depths in the response depending on how it is sectioned. */
    private fun Any?.listItems(): List<JSONObject> = when (this) {
        is JSONObject -> listOfNotNull(optJSONObject("musicResponsiveListItemRenderer")) +
            keys().asSequence().flatMap { optJSONObject(it).listItems() + optJSONArray(it).listItems() }.toList()
        is JSONArray -> (0 until length()).flatMap { optJSONObject(it).listItems() + optJSONArray(it).listItems() }
        else -> emptyList()
    }

    private suspend fun fetchJson(url: String, body: JSONObject? = null): JSONObject {
        val request = Request.Builder().url(url)
            .apply { if (body == null) get() else post(body.toString().toRequestBody("application/json".toMediaType())) }
            .build()
        return client.newCall(request).executeAsync().use { response ->
            JSONObject(withContext(ioDispatcher) { response.body.stringAtMost() })
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    /**
     * Requires the same name and at least one artist in common, so remixes, covers and deluxe
     * editions don't win by rank. Whole names are compared: "Sia" must not match "Asia".
     */
    private fun matches(metadata: MusicMetadata, name: String, artist: String): Boolean {
        if (normalize(name) != normalize(metadata.title)) return false
        if (metadata.type == ItemType.ARTIST) return true
        val wanted = artists(metadata.artist)
        return wanted.isEmpty() || artists(artist).any(wanted::contains)
    }

    /** "A & B feat. C" is credited as just "A" on some services, so each name counts on its own. */
    private fun artists(credit: String): Set<String> = artistNames(credit).map(::normalize).filter { it.isNotEmpty() }.toSet()

    private fun artistNames(credit: String): List<String> = credit.split(artistSeparator).map { it.trim() }.filter { it.isNotEmpty() }

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}]+"""), "")

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val QUEUE_LOOKUPS_AT_ONCE = 6
        const val YOUTUBE_WATCH_VIDEOS_URL = "https://www.youtube.com/watch_videos"
        const val BANDCAMP_SEARCH_URL = "https://bandcamp.com/api/bcsearch_public_api/1/autocomplete_elastic"
        const val YOUTUBE_MUSIC_SEARCH_URL = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
        const val YOUTUBE_MUSIC_NEXT_URL = "https://music.youtube.com/youtubei/v1/next?prettyPrint=false"

        /** Songs (ATV), official music videos (OMV) and label uploads; UGC and podcasts aren't music. */
        val MUSIC_VIDEO_TYPES = setOf("ATV", "OMV", "OFFICIAL_SOURCE_MUSIC")
        const val YOUTUBE_MUSIC_WATCH_URL = "https://music.youtube.com/watch?v="
        const val YOUTUBE_MUSIC_ALBUM_URL = "https://music.youtube.com/browse/"
        const val YOUTUBE_MUSIC_ARTIST_URL = "https://music.youtube.com/channel/"
        const val YOUTUBE_WATCH_URL = "https://www.youtube.com/watch?v="
        const val YOUTUBE_MUSIC_CLIENT_VERSION = "1.20240101.01.00"

        // Search filters as YouTube Music itself encodes them: the song, album and artist tabs.
        const val YOUTUBE_MUSIC_SONGS = "EgWKAQIIAWoKEAoQAxAEEAkQBQ=="
        const val YOUTUBE_MUSIC_ALBUMS = "EgWKAQIYAWoKEAoQAxAEEAkQBQ=="
        const val YOUTUBE_MUSIC_ARTISTS = "EgWKAQIgAWoKEAoQAxAEEAkQBQ=="
    }
}
