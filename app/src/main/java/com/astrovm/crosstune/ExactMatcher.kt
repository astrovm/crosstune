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
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds a direct link to the same item using services' key-less search APIs: Apple's iTunes
 * Search API and Deezer's public API, plus the search behind Bandcamp's, YouTube Music's, TIDAL's,
 * SoundCloud's and Audiomack's own websites, and Qobuz's store pages, which have no documented API.
 * Spotify and Amazon Music need an account, so they keep using a search. Any failure or uncertain
 * match returns null so the caller falls back to a search.
 */
internal class ExactMatcher(
    private val client: OkHttpClient,
    private val country: String = Locale.getDefault().country,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Where what's found is remembered, so the same song isn't looked up again. */
    private val cache: LookupCache? = null,
    private val apis: ServiceApis = ServiceApis(client, country, ioDispatcher)
) {

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
        // A playlist whose songs aren't known may be an album put up as one, e.g. a SoundCloud set,
        // so it's found as that album, by the same artist, or not at all.
        val item = if (metadata.type == ItemType.PLAYLIST && metadata.tracks.isEmpty()) metadata.copy(type = ItemType.ALBUM) else metadata
        // Other apps need an account to search, so there's nothing to look up or remember.
        if (!canMatchExactly(target, item.type)) return null
        return remembered("find", target.name, item) { lookUp(target, item) }
    }

    /**
     * For a queue, where a song that plays beats one that is left out: the song's exact match, or else
     * the first song by the same artist whose title has all the words of the other's, e.g. a title
     * written in another script or a classical work's long name. Kept apart from [match]'s answers,
     * which a single song is opened with.
     */
    private suspend fun matchForQueue(target: MusicService, metadata: MusicMetadata): String? {
        if (metadata.type != ItemType.TRACK || target !in setOf(MusicService.YOUTUBE_MUSIC, MusicService.YOUTUBE)) return match(target, metadata)
        metadata.url?.let(MusicLinks::linkFor)?.youtubeVideoOn(target)?.let { return it }
        cache?.get(cacheKey("find", target.name, metadata))?.let { return it }
        return remembered("queue", target.name, metadata) { lookUp(target, metadata, loosely = true, retrying = true) }
    }

    private class Answer(val url: String?)

    /**
     * What [target] has for [metadata], null if nothing or no answer. A search that failed or timed
     * out is asked once more when [retrying], e.g. for a queue, where one song in fifty must not be
     * lost to a slow moment; an answer of "none" is final.
     */
    private suspend fun lookUp(target: MusicService, metadata: MusicMetadata, loosely: Boolean = false, retrying: Boolean = false): String? {
        repeat(if (retrying) 2 else 1) {
            val answer = withTimeoutOrNull(TIMEOUT_MS) {
                try {
                    Answer(
                        when (target) {
                            MusicService.APPLE_MUSIC -> findOnAppleMusic(metadata)
                            MusicService.DEEZER -> findOnDeezer(metadata)
                            MusicService.BANDCAMP -> findOnBandcamp(metadata)
                            MusicService.TIDAL -> findOnTidal(metadata)
                            MusicService.SOUNDCLOUD -> findOnSoundCloud(metadata)
                            MusicService.AUDIOMACK -> findOnAudiomack(metadata)
                            MusicService.QOBUZ -> findOnQobuz(metadata)
                            MusicService.YOUTUBE_MUSIC -> findOnYouTubeMusic(metadata, YOUTUBE_MUSIC_WATCH_URL, loosely)
                            // YouTube.
                            else -> findOnYouTubeMusic(metadata, YOUTUBE_WATCH_URL, loosely)
                        }
                    )
                } catch (_: IOException) {
                    null
                } catch (_: JSONException) {
                    null
                }
            }
            if (answer != null) return answer.url
        }
        return null
    }

    private fun cacheKey(kind: String, target: String, metadata: MusicMetadata) =
        listOf(kind, target, metadata.type.name, metadata.title, metadata.artist, metadata.url.orEmpty()).joinToString("|")

    /** What was found for [metadata] before, or [lookUp]'s answer, which is kept when it found something. */
    private suspend fun remembered(kind: String, target: String, metadata: MusicMetadata, lookUp: suspend () -> String?): String? {
        val key = cacheKey(kind, target, metadata)
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
                    lookups.withPermit { matchForQueue(target, track) }.also { onProgress(looked.incrementAndGet()) }
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
        val title = SongNames.normalize(SongNames.withoutEditionTag(metadata.title))
        // "Song" by "A, B" is credited to just "A" on Deezer, so any one of the names will do.
        val artists = SongNames.artists(metadata.artist)
        return searchDeezer(metadata.copy(type = ItemType.TRACK)).firstOrNull { result ->
            val name = SongNames.normalize(SongNames.withoutEditionTag(result.optString("title")))
            val credit = SongNames.normalize(result.optJSONObject("artist")?.optString("name").orEmpty())
            title.isNotEmpty() && name.startsWith(title) && artists.any { it in credit }
        }?.optJSONObject("album")?.optString("cover_big")?.ifBlank { null }
    }

    /** TIDAL's catalogue is the labels' own, so a match by name and artist is the item itself. */
    private suspend fun findOnTidal(metadata: MusicMetadata): String? {
        val (kind, path) = when (metadata.type) {
            ItemType.TRACK -> "tracks" to "track"
            ItemType.ALBUM -> "albums" to "album"
            else -> "artists" to "artist"
        }
        return apis.tidalSearch(kind, searchQuery(metadata)).firstOrNull { result ->
            when (metadata.type) {
                ItemType.TRACK -> ServiceApis.tidalTrack(result)?.let { matches(metadata, it.title, it.artist) } == true
                ItemType.ALBUM -> matches(metadata, result.optString("title"), result.optJSONArray("artists").objects().joinToString(", ") { it.optString("name") })
                else -> matches(metadata, result.optString("name"), "")
            }
        }?.let { "https://tidal.com/browse/$path/${it.optLong("id")}" }
    }

    /**
     * Anyone can upload to SoundCloud under any name, so a song or album counts only when it's the
     * artist's own, or a verified account's, and an artist only when verified.
     */
    private suspend fun findOnSoundCloud(metadata: MusicMetadata): String? {
        fun owned(user: JSONObject?) = user != null && (user.optBoolean("verified") || sameArtist(metadata, user.optString("username")))
        val found = when (metadata.type) {
            ItemType.TRACK -> apis.soundCloudSearch("tracks", searchQuery(metadata)).firstOrNull { result ->
                ServiceApis.soundCloudTrack(result)?.let { matches(metadata, it.title, it.artist) } == true && owned(result.optJSONObject("user"))
            }
            ItemType.ALBUM -> apis.soundCloudSearch("albums", searchQuery(metadata)).firstOrNull { result ->
                val user = result.optJSONObject("user")
                matches(metadata, result.optString("title"), user?.optString("username").orEmpty()) && owned(user)
            }
            else -> apis.soundCloudSearch("users", searchQuery(metadata)).firstOrNull { result ->
                result.optBoolean("verified") && matches(metadata, result.optString("username"), "")
            }
        }
        return found?.optString("permalink_url")?.ifBlank { null }
    }

    /** Like SoundCloud, anyone can upload to Audiomack, so only the artist's own or a verified account's upload counts. */
    private suspend fun findOnAudiomack(metadata: MusicMetadata): String? {
        fun owned(uploader: JSONObject?) = uploader != null && (uploader.optString("verified") == "yes" || sameArtist(metadata, uploader.optString("name")))
        val kind = when (metadata.type) {
            ItemType.TRACK -> "songs"
            ItemType.ALBUM -> "albums"
            else -> "artists"
        }
        val found = apis.audiomackSearch(kind, searchQuery(metadata)).firstOrNull { result ->
            if (metadata.type == ItemType.ARTIST) {
                result.optString("verified") == "yes" && matches(metadata, result.optString("name"), "")
            } else {
                // One that can't be played here is no use, e.g. kept from this country by its rights holder.
                matches(metadata, result.optString("title"), result.optString("artist")) && owned(result.optJSONObject("uploader")) &&
                    !result.optBoolean("geo_restricted")
            }
        } ?: return null
        val slug = found.optString("url_slug").ifBlank { return null }
        if (metadata.type == ItemType.ARTIST) return "https://audiomack.com/$slug"
        val owner = found.optJSONObject("uploader")?.optString("url_slug")?.ifBlank { null } ?: return null
        return "https://audiomack.com/$owner/${if (metadata.type == ItemType.TRACK) "song" else "album"}/$slug"
    }

    /**
     * Qobuz's API needs an account, but its store's search page lists each song with its album:
     * enough for songs and albums. Its artists aren't told apart there, so they're left to the search.
     */
    private suspend fun findOnQobuz(metadata: MusicMetadata): String? {
        if (metadata.type != ItemType.TRACK && metadata.type != ItemType.ALBUM) return null
        val url = "https://www.qobuz.com/us-en/search/tracks".toHttpUrl().newBuilder().addPathSegment(searchQuery(metadata)).build()
        val page = client.newCall(Request.Builder().url(url).get().build()).executeAsync().use { response ->
            if (!response.isSuccessful) throw IOException("Qobuz answered ${response.code}")
            withContext(ioDispatcher) { response.body.stringAtMost() }
        }
        val songs = qobuzItemRegex.findAll(page).mapNotNull { item ->
            val html = item.value
            val (title, credit) = qobuzTitleRegex.find(html)?.groupValues?.drop(1)?.map(::htmlText) ?: return@mapNotNull null
            val (artist, album) = credit.split("•").map(String::trim).let { it.first() to it.getOrElse(1) { "" } }
            QobuzSong(title, artist, album, qobuzTrackRegex.find(html)?.groupValues?.get(1), qobuzAlbumRegex.find(html)?.groupValues?.get(1))
        }
        return if (metadata.type == ItemType.TRACK) {
            songs.firstOrNull { it.track != null && matches(metadata, it.title, it.artist) }?.let { "https://play.qobuz.com/track/${it.track}" }
        } else {
            songs.firstOrNull { it.album != null && matches(metadata, it.albumTitle, it.artist) }?.let { "https://play.qobuz.com/album/${it.album}" }
        }
    }

    private class QobuzSong(val title: String, val artist: String, val albumTitle: String, val track: String?, val album: String?)

    private fun htmlText(html: String) = android.text.Html.fromHtml(html.replace(Regex("<[^>]+>"), " "), android.text.Html.FROM_HTML_MODE_LEGACY)
        .toString().replace(Regex("\\s+"), " ").trim()

    /** Whether [name] is one of [metadata]'s artists, e.g. the account an upload is on. */
    private fun sameArtist(metadata: MusicMetadata, name: String): Boolean {
        val wanted = SongNames.artists(metadata.artist)
        return SongNames.artists(name).any(wanted::contains)
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
        val host = SongNames.normalize(pageUrl.toHttpUrlOrNull()?.host?.removeSuffix(".bandcamp.com").orEmpty())
        if (host.isEmpty()) return false
        // Without an artist there is nothing to check the page against, so leave it to the search.
        return SongNames.artistNames(metadata.artist).any { name ->
            // "The" only counts as an article when it is a word of its own: not in "Thelonious".
            listOf(name, SongNames.withoutArticle(name)).map(SongNames::normalize).any { candidate ->
                candidate.isNotEmpty() && pageSuffixes.any { suffix -> host == candidate + suffix }
            }
        }
    }

    /**
     * YouTube Music's search, filtered to songs, albums or artists. Songs are also watchable on
     * YouTube itself, which has no such filter, so [watchUrl] picks the app the video id opens in.
     */
    private suspend fun findOnYouTubeMusic(metadata: MusicMetadata, watchUrl: String, loosely: Boolean): String? {
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
        fun found(item: JSONObject, loose: Boolean): String? {
            val columns = item.optJSONArray("flexColumns")?.objects()
                ?.map { it.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.text().orEmpty() }
                ?: return null
            val name = columns.firstOrNull().orEmpty()
            // The second line is "Artist • Album • 4:46" for songs and "Album • Artist • 2012" for albums.
            val details = columns.getOrNull(1).orEmpty().split(" • ")
            val artist = if (isSong) details.firstOrNull().orEmpty() else details.getOrNull(1).orEmpty()
            val same = if (loose) looselyMatches(metadata, name, artist) else matches(metadata, name, artist.ifEmpty { name })
            if (!same) return null
            return when (metadata.type) {
                ItemType.TRACK -> item.optJSONObject("playlistItemData")?.optString("videoId")?.ifBlank { null }
                    ?.let { watchUrl + it }
                else -> item.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
                    ?.ifBlank { null }
                    ?.let { (if (metadata.type == ItemType.ALBUM) YOUTUBE_MUSIC_ALBUM_URL else YOUTUBE_MUSIC_ARTIST_URL) + it }
            }
        }
        // An exact match anywhere in the results beats a close one at the top.
        return items.firstNotNullOfOrNull { found(it, loose = false) }
            ?: (if (loosely) items.firstNotNullOfOrNull { found(it, loose = true) } else null)
            // A song that was never released, e.g. one only on SoundCloud, may still be uploaded as a video.
            ?: if (isSong) findVideoOnYouTubeMusic(metadata, watchUrl, client) else null
    }

    /**
     * The song as a video on YouTube, which YouTube Music plays too: e.g. one its artist put up but
     * never released. A video's title names more, "Artist - Song (Official Video)", so it counts when
     * it's the song's words, its artist's and nothing else but such tags; a remix or a live take has
     * words of its own and stays out.
     */
    private suspend fun findVideoOnYouTubeMusic(metadata: MusicMetadata, watchUrl: String, client: JSONObject): String? {
        val body = JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("query", searchQuery(metadata))
            .put("params", YOUTUBE_MUSIC_VIDEOS)
        return fetchJson(YOUTUBE_MUSIC_SEARCH_URL, body).listItems().firstNotNullOfOrNull { item ->
            val columns = item.optJSONArray("flexColumns")?.objects()
                ?.map { it.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.text().orEmpty() }
                ?: return@firstNotNullOfOrNull null
            // The second line is "Channel • 1.2M views • 3:45".
            val channel = columns.getOrNull(1).orEmpty().split(" • ").first()
            if (!isTheSongsVideo(metadata, columns.firstOrNull().orEmpty(), channel)) return@firstNotNullOfOrNull null
            item.optJSONObject("playlistItemData")?.optString("videoId")?.ifBlank { null }?.let { watchUrl + it }
        }
    }

    private fun isTheSongsVideo(metadata: MusicMetadata, name: String, channel: String): Boolean {
        val title = SongNames.words(SongNames.withoutEditionTag(metadata.title))
        val words = SongNames.words(SongNames.withoutEditionTag(name))
        if (title.isEmpty() || !words.containsAll(title)) return false
        val artists = SongNames.artistNames(metadata.artist).map(SongNames::words).filter { it.isNotEmpty() }
        val named = artists.filter { words.containsAll(it) }
        // Its artist is who put it up, or is named in its title. A channel is the artist's own by its
        // whole name, maybe with what YouTube adds to one: "Band - Topic", "BandVEVO".
        val uploader = SongNames.normalize(channel)
        val own = SongNames.artists(metadata.artist).any { it == uploader || uploader.removePrefix(it) in CHANNEL_SUFFIXES && uploader.startsWith(it) }
        if (!own && named.isEmpty()) return false
        return (words - title - named.flatten().toSet() - VIDEO_TAGS).isEmpty()
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
        if (!SongNames.same(name, metadata.title)) return false
        if (metadata.type == ItemType.ARTIST) return true
        val wanted = SongNames.artists(metadata.artist)
        return wanted.isEmpty() || SongNames.artists(artist).any(wanted::contains)
    }

    private fun looselyMatches(metadata: MusicMetadata, name: String, artist: String): Boolean =
        SongNames.wordsMatch(name, metadata.title) && SongNames.artistInside(artist, metadata.artist)

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val QUEUE_LOOKUPS_AT_ONCE = 6
        const val SPOTIFY_TRACK_URL = "https://open.spotify.com/track/"
        const val YOUTUBE_WATCH_VIDEOS_URL = "https://www.youtube.com/watch_videos"
        /** One song in Qobuz's search: its title and "artist • album", and the numbers of the song and its album. */
        private val qobuzItemRegex = Regex("""<div class="ListItem">.*?</li>""", RegexOption.DOT_MATCHES_ALL)
        private val qobuzTitleRegex = Regex("""class="ListItem__title"[^>]*>(.*?)</a>\s*<p class="ListItem__artists">(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
        private val qobuzTrackRegex = Regex("""track&#x2F;(\d+)""")
        private val qobuzAlbumRegex = Regex("""href="/[a-z]{2}-[a-z]{2}/album/[^"/]+/([A-Za-z0-9]+)"""")

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
        const val YOUTUBE_MUSIC_VIDEOS = "EgWKAQIQAWoKEAoQAxAEEAkQBQ=="

        /** What YouTube adds to an artist's own channel name. */
        private val CHANNEL_SUFFIXES = setOf("topic", "vevo", "official", "music", "tv")

        /** What a video's title adds that isn't another recording: "Official Video", "Lyrics", "HD". */
        private val VIDEO_TAGS = setOf(
            "official", "video", "audio", "music", "lyric", "lyrics", "visualizer", "visualiser", "hd", "hq", "4k", "mv",
            "clip", "videoclip", "full", "version", "feat", "ft", "the"
        )
    }
}

/** The apps whose search needs no account. */
private val matchable = setOf(
    MusicService.APPLE_MUSIC, MusicService.DEEZER, MusicService.BANDCAMP, MusicService.YOUTUBE_MUSIC, MusicService.YOUTUBE,
    MusicService.TIDAL, MusicService.SOUNDCLOUD, MusicService.AUDIOMACK, MusicService.QOBUZ
)

/**
 * Whether Crosstune can look [type] up in [target] itself, so that finding nothing there means it isn't
 * there, rather than that it couldn't look. A playlist is someone's own, and is never anywhere else;
 * Qobuz's artists aren't told apart, see ExactMatcher.findOnQobuz.
 */
internal fun canMatchExactly(target: MusicService, type: ItemType): Boolean =
    type != ItemType.PLAYLIST && target in matchable && !(target == MusicService.QOBUZ && type == ItemType.ARTIST)

/**
 * Whether [item] not being on [target] is worth saying, rather than searching for it: a song or album
 * that could have been matched, or a playlist whose songs aren't known, which is someone's own and
 * so on no other service.
 */
internal fun canBeMissing(target: MusicService, item: MusicMetadata): Boolean =
    canMatchExactly(target, item.type) || (item.type == ItemType.PLAYLIST && item.tracks.isEmpty())
