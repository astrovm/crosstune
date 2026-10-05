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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Where what's found is remembered, so the same song isn't looked up again. */
    private val cache: LookupCache? = null
) {
    private val leadingArticle = Regex("""^the\s+""", RegexOption.IGNORE_CASE)
    private val artistSeparator = Regex(
        """\s*(?:,|&|\+|/|\bx\b|\bfeat\.?|\bft\.?|\bfeaturing\b)\s*""",
        RegexOption.IGNORE_CASE
    )

    /** The apps whose search needs no account. */
    private val matchable = setOf(MusicService.APPLE_MUSIC, MusicService.DEEZER, MusicService.BANDCAMP, MusicService.YOUTUBE_MUSIC, MusicService.YOUTUBE)

    /**
     * A trailing tag that names no other recording: "Remastered 2012" and "Mono" anywhere in it, or
     * a whole "Album Version" or "Original". "Live", "Acoustic" or "Remix" are other recordings.
     */
    private val editionTag = Regex(
        """(?:\s+[-–]\s+|\s*[(\[])\s*(?:$EDITION_WORDS|[^()\[\]]*\b(?:remaster(?:ed)?|mono|stereo)\b[^()\[\]]*)\s*[)\]]?$""",
        RegexOption.IGNORE_CASE
    )

    /** A guest credited in the title, as "(feat. Emel)", which services that credit it as an artist leave out. */
    private val featuring = Regex("""\s*[(\[]\s*(?:feat|ft|featuring)\b[^()\[\]]*[)\]]""", RegexOption.IGNORE_CASE)

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

    suspend fun find(target: MusicService, metadata: MusicMetadata): String? = match(target, metadata).also { cache?.save() }

    private suspend fun match(target: MusicService, metadata: MusicMetadata): String? {
        // Other apps need an account to search, so there's nothing to look up or remember.
        if (metadata.type == ItemType.PLAYLIST || target !in matchable) return null
        return remembered("find", target.name, metadata) { lookUp(target, metadata) }
    }

    /**
     * For a queue, where a song that plays beats one that is left out: the first song by the same
     * artist whose title shares at least half its words, e.g. a title written in another script
     * or a classical work's long name. Songs only, and only after [match] found none.
     */
    private suspend fun matchLoosely(target: MusicService, metadata: MusicMetadata): String? {
        if (metadata.type != ItemType.TRACK || target !in setOf(MusicService.YOUTUBE_MUSIC, MusicService.YOUTUBE)) return null
        return remembered("loose", target.name, metadata) { lookUp(target, metadata, loosely = true) }
    }

    private suspend fun lookUp(target: MusicService, metadata: MusicMetadata, loosely: Boolean = false): String? =
        withTimeoutOrNull(TIMEOUT_MS) {
            try {
                when (target) {
                    MusicService.APPLE_MUSIC -> findOnAppleMusic(metadata)
                    MusicService.DEEZER -> findOnDeezer(metadata)
                    MusicService.BANDCAMP -> findOnBandcamp(metadata)
                    MusicService.YOUTUBE_MUSIC -> findOnYouTubeMusic(metadata, YOUTUBE_MUSIC_WATCH_URL, loosely)
                    // YouTube.
                    else -> findOnYouTubeMusic(metadata, YOUTUBE_WATCH_URL, loosely)
                }
            } catch (_: IOException) {
                null
            } catch (_: JSONException) {
                null
            }
        }

    /** What was found for [metadata] before, or [lookUp]'s answer, which is kept when it found something. */
    private suspend fun remembered(kind: String, target: String, metadata: MusicMetadata, lookUp: suspend () -> String?): String? {
        val key = listOf(kind, target, metadata.type.name, metadata.title, metadata.artist, metadata.url.orEmpty()).joinToString("|")
        cache?.get(key)?.let { return it }
        return lookUp()?.also { cache?.put(key, it) }
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
                    lookups.withPermit { match(target, track) ?: matchLoosely(target, track) }.also { onProgress(looked.incrementAndGet()) }
                }
            }.awaitAll()
        }.mapNotNull { it?.toHttpUrl()?.queryParameter("v") }.distinct()
        cache?.save()
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

    private suspend fun findOnDeezer(metadata: MusicMetadata): String? =
        searchDeezer(metadata).firstOrNull { result ->
            val name = result.optString("title").ifEmpty { result.optString("name") }
            val artist = result.optJSONObject("artist")?.optString("name") ?: name
            matches(metadata, name, artist)
        }?.optString("link")?.ifBlank { null }

    private suspend fun searchDeezer(metadata: MusicMetadata): List<JSONObject> {
        val path = when (metadata.type) {
            ItemType.TRACK -> "search/track"
            ItemType.ALBUM -> "search/album"
            else -> "search/artist"
        }
        val url = "https://api.deezer.com/$path".toHttpUrl().newBuilder()
            .addQueryParameter("q", searchQuery(metadata))
            .addQueryParameter("limit", "10")
            .build()
        return fetchJson(url.toString()).optJSONArray("data")?.objects().orEmpty()
    }

    /**
     * A song's album cover found by its name, for songs that come without one (Now Playing) or with
     * a video frame instead (YouTube). A cover doesn't need the exact recording, so "Song (2003
     * Remaster)" by "The Band" is close enough to "Song" by "Band". Null when no song is close,
     * offline, or slower than [timeoutMs].
     */
    suspend fun cover(metadata: MusicMetadata, timeoutMs: Long = TIMEOUT_MS): String? = coverOf(metadata, timeoutMs).also { cache?.save() }

    /**
     * Covers for a playlist's [tracks] that came without one, a few at a time; [onFound] hears each
     * one's index and cover as it's found.
     */
    suspend fun covers(tracks: List<MusicMetadata>, onFound: (Int, String) -> Unit) {
        val lookups = Semaphore(QUEUE_LOOKUPS_AT_ONCE)
        try {
            coroutineScope {
                tracks.forEachIndexed { index, track ->
                    async { lookups.withPermit { coverOf(track, TIMEOUT_MS) }?.let { onFound(index, it) } }
                }
            }
        } finally {
            // Stopped partway, e.g. for another playlist, what was found is still kept.
            cache?.save()
        }
    }

    private suspend fun coverOf(metadata: MusicMetadata, timeoutMs: Long): String? = remembered("cover", "", metadata) {
        withTimeoutOrNull(timeoutMs) {
            try {
                // A Spotify song's own cover is known by its link, with no guessing by name. When Spotify
                // doesn't answer, e.g. it's busy with the rest of the playlist, the name still finds it.
                val own = if (metadata.url?.startsWith(SPOTIFY_TRACK_URL) == true) {
                    try {
                        spotifyCover(metadata.url)
                    } catch (_: IOException) {
                        null
                    } catch (_: JSONException) {
                        null
                    }
                } else {
                    null
                }
                own ?: deezerCover(metadata)
            } catch (_: IOException) {
                null
            } catch (_: JSONException) {
                null
            }
        }
    }

    private suspend fun spotifyCover(url: String): String? {
        val oEmbed = "https://open.spotify.com/oembed".toHttpUrl().newBuilder().addQueryParameter("url", url).build()
        return fetchJson(oEmbed.toString()).optString("thumbnail_url").ifBlank { null }
    }

    private suspend fun deezerCover(metadata: MusicMetadata): String? {
        val title = normalize(withoutEditionTag(metadata.title))
        // "Song" by "A, B" is credited to just "A" on Deezer, so any one of the names will do.
        val artists = artists(metadata.artist)
        return searchDeezer(metadata.copy(type = ItemType.TRACK)).firstOrNull { result ->
            val name = normalize(withoutEditionTag(result.optString("title")))
            val credit = normalize(result.optJSONObject("artist")?.optString("name").orEmpty())
            title.isNotEmpty() && name.startsWith(title) && artists.any { it in credit }
        }?.optJSONObject("album")?.optString("cover_big")?.ifBlank { null }
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
    private suspend fun findOnYouTubeMusic(metadata: MusicMetadata, watchUrl: String, loosely: Boolean = false): String? {
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
            val same = if (loosely) looselyMatches(metadata, name, artist) else matches(metadata, name, artist.ifEmpty { name })
            if (!same) return@firstNotNullOfOrNull null
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
        if (normalize(withoutEditionTag(name)) != normalize(withoutEditionTag(metadata.title))) return false
        if (metadata.type == ItemType.ARTIST) return true
        val wanted = artists(metadata.artist)
        return wanted.isEmpty() || artists(artist).any(wanted::contains)
    }

    private fun looselyMatches(metadata: MusicMetadata, name: String, artist: String): Boolean {
        val wanted = artists(metadata.artist)
        if (wanted.isNotEmpty() && artists(artist).none(wanted::contains)) return false
        val ours = words(withoutEditionTag(metadata.title))
        val theirs = words(withoutEditionTag(name))
        if (ours.isEmpty() || theirs.isEmpty()) return false
        return ours.intersect(theirs).size * 2 >= minOf(ours.size, theirs.size)
    }

    private fun words(title: String): Set<String> =
        Normalizer.normalize(title, Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "").lowercase(Locale.ROOT)
            .split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }.toSet()

    /** "Song - Remastered 2012", "Song (2003 Remaster)" and "Song (feat. A)" are the song itself, which other services list without the tag. */
    private fun withoutEditionTag(title: String): String {
        var stripped = title
        // "Song - 2012 Remaster (Mono)" has two, and a title that is only a tag is left as it is.
        while (true) {
            val shorter = stripped.replace(featuring, "").replace(editionTag, "")
            if (shorter == stripped || shorter.isBlank()) return stripped
            stripped = shorter
        }
    }

    /** "A & B feat. C" is credited as just "A" on some services, so each name counts on its own, and "The" doesn't count. */
    private fun artists(credit: String): Set<String> =
        artistNames(credit).map { name -> normalize(name.replace(leadingArticle, "")) }.filter { it.isNotEmpty() }.toSet()

    private fun artistNames(credit: String): List<String> = credit.split(artistSeparator).map { it.trim() }.filter { it.isNotEmpty() }

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}]+"""), "")

    private companion object {
        const val EDITION_WORDS = """original|(?:album|single|short|original|radio)\s+(?:version|edit|mix)"""
        const val TIMEOUT_MS = 5_000L
        const val QUEUE_LOOKUPS_AT_ONCE = 6
        const val SPOTIFY_TRACK_URL = "https://open.spotify.com/track/"
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
