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
    private val apis: ServiceApis = ServiceApis(client, country, ioDispatcher),
    /**
     * The one of [rows], a service's search results as "title | details", that is the song, by its
     * index; null for none. The last Boolean says the rows are videos anyone put up. Asked last, when
     * nothing matches by name: ChatGPT, when signed in.
     */
    private val pick: suspend (MusicMetadata, List<String>, Boolean) -> Int? = { _, _, _ -> null },
    /**
     * How long a song looked for and not found stays not found, so it isn't looked for again, and
     * ChatGPT asked again, each time it's opened; none by default.
     */
    private val missingForMs: Long = 0,
    private val now: () -> Long = System::currentTimeMillis,
    /** How long a search has, and then ChatGPT to pick from what it found. */
    private val timeoutMs: Long = TIMEOUT_MS,
    private val pickTimeoutMs: Long = PICK_TIMEOUT_MS
) {

    /** What looking for something came to: found, not there, or no answer, e.g. offline, which says neither. */
    sealed interface Match {
        data class Found(val url: String) : Match
        data object Missing : Match
        data object Failed : Match
    }

    /** When each thing looked for and not found was, by its [cacheKey]. */
    private val missing = java.util.concurrent.ConcurrentHashMap<String, Long>()

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
            musicVideoTypeIn(fetchJson(YOUTUBE_MUSIC_NEXT_URL, body))?.let { it in MUSIC_VIDEO_TYPES }
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        }
    }

    suspend fun find(target: MusicService, metadata: MusicMetadata): String? = (lookFor(target, metadata) as? Match.Found)?.url

    /** What [target] has for [metadata], telling a song that isn't there from one that couldn't be looked for. */
    suspend fun lookFor(target: MusicService, metadata: MusicMetadata): Match = match(target, metadata).also { cache?.save() }

    private suspend fun match(target: MusicService, metadata: MusicMetadata): Match {
        // A playlist whose songs aren't known may be an album put up as one, e.g. a SoundCloud set,
        // so it's found as that album, by the same artist, or not at all.
        val item = if (metadata.type == ItemType.PLAYLIST && metadata.tracks.isEmpty()) metadata.copy(type = ItemType.ALBUM) else metadata
        // Other apps need an account to search, so there's nothing to look up or remember.
        if (!canMatchExactly(target, item.type)) return Match.Missing
        return remembered("find", target.name, item) { lookUp(target, item) }
    }

    /**
     * For a queue, where a song that plays beats one that is left out: the song's exact match, or else
     * the first song by the same artist whose title has all the words of the other's, e.g. a title
     * written in another script or a classical work's long name. Kept apart from [match]'s answers,
     * which a single song is opened with.
     */
    private suspend fun matchForQueue(target: MusicService, metadata: MusicMetadata): String? {
        if (metadata.type != ItemType.TRACK || target !in setOf(MusicService.YOUTUBE_MUSIC, MusicService.YOUTUBE)) {
            return (match(target, metadata) as? Match.Found)?.url
        }
        metadata.url?.let(MusicLinks::linkFor)?.youtubeVideoOn(target)?.let { return it }
        cache?.get(cacheKey("find", target.name, metadata))?.let { return it }
        return (remembered("queue", target.name, metadata) { lookUp(target, metadata, loosely = true, retrying = true) } as? Match.Found)?.url
    }

    /**
     * A search's answer: what it found, or else, on YouTube, the songs and videos it turned up for
     * ChatGPT to pick from, given its own while, as it takes a few seconds.
     */
    private class Answer(val url: String?, val toPick: ToPick? = null)

    private class ToPick(val metadata: MusicMetadata, val songs: List<Row>, val videos: List<Row>, val watchUrl: String, val lengthMs: Long?)

    /**
     * What [target] has for [metadata]. A search that failed or timed out is asked once more when
     * [retrying], e.g. for a queue, where one song in fifty must not be lost to a slow moment; an
     * answer of "none" is final.
     */
    private suspend fun lookUp(target: MusicService, metadata: MusicMetadata, loosely: Boolean = false, retrying: Boolean = false): Match {
        repeat(if (retrying) 2 else 1) {
            val answer = withTimeoutOrNull(timeoutMs) {
                try {
                    when (target) {
                        MusicService.APPLE_MUSIC -> Answer(findOnAppleMusic(metadata))
                        MusicService.DEEZER -> Answer(findOnDeezer(metadata))
                        MusicService.BANDCAMP -> Answer(findOnBandcamp(metadata))
                        MusicService.TIDAL -> Answer(findOnTidal(metadata))
                        MusicService.SOUNDCLOUD -> Answer(findOnSoundCloud(metadata))
                        MusicService.AUDIOMACK -> Answer(findOnAudiomack(metadata))
                        MusicService.QOBUZ -> Answer(findOnQobuz(metadata))
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
            if (answer != null) return answer.url?.let(Match::Found) ?: answer.toPick?.let { picked(it) } ?: Match.Missing
        }
        return Match.Failed
    }

    /** The song ChatGPT picks among the songs, then the videos; no answer in time is no answer. */
    private suspend fun picked(toPick: ToPick): Match {
        val url = withTimeoutOrNull(pickTimeoutMs) {
            Answer(
                pickedFrom(toPick.metadata, toPick.songs, toPick.watchUrl, videos = false, toPick.lengthMs)
                    ?: pickedFrom(toPick.metadata, toPick.videos, toPick.watchUrl, videos = true, toPick.lengthMs)
            )
        } ?: return Match.Failed
        return url.url?.let(Match::Found) ?: Match.Missing
    }

    private fun cacheKey(kind: String, target: String, metadata: MusicMetadata) =
        listOf(kind, target, metadata.type.name, metadata.title, metadata.artist, metadata.url.orEmpty()).joinToString("|")

    /**
     * What was found for [metadata] before, or [lookUp]'s answer, which is kept when it found
     * something; when it found nothing, that's kept for [missingForMs].
     */
    private suspend fun remembered(kind: String, target: String, metadata: MusicMetadata, lookUp: suspend () -> Match): Match {
        val key = cacheKey(kind, target, metadata)
        cache?.get(key)?.let { return Match.Found(it) }
        missing[key]?.takeIf { now() - it < missingForMs }?.let { return Match.Missing }
        return lookUp().also { match ->
            when (match) {
                is Match.Found -> cache?.put(key, match.url)
                Match.Missing -> if (missingForMs > 0) missing[key] = now()
                Match.Failed -> Unit
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
        }?.optString(urlKey)?.linkTo(MusicService.APPLE_MUSIC)
    }

    private suspend fun findOnDeezer(metadata: MusicMetadata): String? =
        searchDeezer(metadata).firstOrNull { result ->
            val name = result.optString("title").ifEmpty { result.optString("name") }
            val artist = result.optJSONObject("artist")?.optString("name") ?: name
            matches(metadata, name, artist)
        }?.optString("link")?.linkTo(MusicService.DEEZER)

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
        val answer = fetchJson(url.toString())
        // Deezer says it's busy, e.g. over its quota, with an error, not with no results.
        if (answer.has("error")) throw IOException("Deezer answered ${answer.optJSONObject("error")?.optInt("code")}")
        return answer.optJSONArray("data")?.objects().orEmpty()
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

    private suspend fun coverOf(metadata: MusicMetadata, timeoutMs: Long): String? = (remembered("cover", "", metadata) {
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
                // Apple has some Deezer doesn't, e.g. The Blue Hearts'.
                own ?: deezerCover(metadata) ?: itunesCover(metadata)
            } catch (_: IOException) {
                null
            } catch (_: JSONException) {
                null
            }
        }?.let(Match::Found) ?: Match.Failed
    } as? Match.Found)?.url

    private suspend fun spotifyCover(url: String): String? {
        val oEmbed = "https://open.spotify.com/oembed".toHttpUrl().newBuilder().addQueryParameter("url", url).build()
        return fetchJson(oEmbed.toString()).optString("thumbnail_url").ifBlank { null }
    }

    /** Deezer's cover for the song, under any of its names. */
    private suspend fun deezerCover(metadata: MusicMetadata): String? =
        SongNames.titleNames(metadata.title).firstNotNullOfOrNull { title -> deezerCoverNamed(metadata.copy(title = title)) }

    /** Apple's cover for the song, under any of its names, as large as it makes them. */
    private suspend fun itunesCover(metadata: MusicMetadata): String? =
        SongNames.titleNames(metadata.title).firstNotNullOfOrNull { title -> itunesCoverNamed(metadata.copy(title = title)) }

    private suspend fun itunesCoverNamed(metadata: MusicMetadata): String? {
        val title = SongNames.normalize(SongNames.withoutEditionTag(metadata.title))
        val artists = SongNames.artists(metadata.artist)
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", searchQuery(metadata.copy(title = SongNames.withoutEditionTag(metadata.title), type = ItemType.TRACK)))
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "10")
            .apply { if (country.length == 2) addQueryParameter("country", country) }
            .build()
        return fetchJson(url.toString()).optJSONArray("results")?.objects()?.firstOrNull { result ->
            val name = SongNames.normalize(SongNames.withoutEditionTag(result.optString("trackName")))
            val credit = SongNames.normalize(result.optString("artistName"))
            title.isNotEmpty() && name.startsWith(title) && artists.any { it in credit }
        }?.optString("artworkUrl100")?.ifBlank { null }?.replace("100x100bb", "600x600bb")
    }

    private suspend fun deezerCoverNamed(metadata: MusicMetadata): String? {
        val title = SongNames.normalize(SongNames.withoutEditionTag(metadata.title))
        // "Song" by "A, B" is credited to just "A" on Deezer, so any one of the names will do.
        val artists = SongNames.artists(metadata.artist)
        // Searched for without its edition tag too: Deezer finds nothing for "Song (2018 Remix) (2024
        // Remaster)", though it has "Song (2018 Remix)", whose cover is the same.
        val searched = metadata.copy(title = SongNames.withoutEditionTag(metadata.title), type = ItemType.TRACK)
        return searchDeezer(searched).firstOrNull { result ->
            val name = SongNames.normalize(SongNames.withoutEditionTag(MetadataParsers.deezerTitle(result)))
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
        return found?.optString("permalink_url")?.linkTo(MusicService.SOUNDCLOUD)
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

    /** This, when it's a link to [service]: an answer that has something else where its link goes, e.g. cut short, has none. */
    private fun String.linkTo(service: MusicService): String? = takeIf { MusicLinks.serviceFor(it) == service }

    private class QobuzSong(val title: String, val artist: String, val albumTitle: String, val track: String?, val album: String?)

    private fun htmlText(html: String) = android.text.Html.fromHtml(html.replace(tagRegex, " "), android.text.Html.FROM_HTML_MODE_LEGACY)
        .toString().replace(spacesRegex, " ").trim()

    private val tagRegex = Regex("<[^>]+>")
    private val spacesRegex = Regex("\\s+")

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
        }?.optString("item_url_path")?.linkTo(MusicService.BANDCAMP)
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
    private suspend fun findOnYouTubeMusic(metadata: MusicMetadata, watchUrl: String, loosely: Boolean): Answer {
        val (params, isSong) = when (metadata.type) {
            ItemType.TRACK -> YOUTUBE_MUSIC_SONGS to true
            ItemType.ALBUM -> YOUTUBE_MUSIC_ALBUMS to false
            else -> YOUTUBE_MUSIC_ARTISTS to false
        }
        // Only songs can be opened in the YouTube app, and it would just be a search for the rest.
        if (!isSong && watchUrl == YOUTUBE_WATCH_URL) return Answer(null)
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
            // A song's album may say what version it is, "Song" on "… Remix".
            val album = if (isSong) details.getOrNull(1).orEmpty() else ""
            val same = if (loose) looselyMatches(metadata, name, artist) else matches(metadata, name, artist.ifEmpty { name }, album)
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
        items.firstNotNullOfOrNull { found(it, loose = false) }?.let { return Answer(it) }
        if (loosely) items.firstNotNullOfOrNull { found(it, loose = true) }?.let { return Answer(it) }
        if (!isSong) return Answer(null)
        // Not there by its name, it may be by another, e.g. in Japanese, or only as a video, e.g. one
        // never released, or only put up by others, as The Blue Hearts' are.
        val (videos, names) = coroutineScope {
            val videos = async { searchVideosOnYouTubeMusic(metadata, client) }
            val names = async { namesOf(metadata) }
            videos.await() to names.await()
        }
        items.map(::row).firstOrNull { isTheSong(it, names) }?.let { return Answer(watchUrl + it.id) }
        videos.map(::row).firstOrNull { isTheSongsVideo(it, names) }?.let { return Answer(watchUrl + it.id) }
        // Last, the songs, then the videos, are picked from, each asked about apart.
        return Answer(null, ToPick(metadata, playable(items).take(PICK_FROM), playable(videos).take(PICK_FROM), watchUrl, names.lengthMs))
    }

    /** The songs or videos among [items] that play. */
    private fun playable(items: List<JSONObject>): List<Row> = items.map(::row).filter { it.id != null }

    /**
     * The one among [rows] that [pick] says the song is, each shown as "title | details". Their titles
     * are anyone's, and may say anything, so one far from the song's [lengthMs] isn't taken: a few
     * seconds for a song, more for a video, which may open with more.
     */
    private suspend fun pickedFrom(metadata: MusicMetadata, rows: List<Row>, watchUrl: String, videos: Boolean, lengthMs: Long?): String? {
        if (rows.isEmpty()) return null
        val index = pick(metadata, rows.map { "${it.name} | ${it.details.joinToString(" • ")}" }, videos) ?: return null
        val row = rows.getOrNull(index) ?: return null
        val off = lengthMs?.let { wanted -> row.lengthMs?.let { kotlin.math.abs(it - wanted) } } ?: 0L
        return (watchUrl + row.id).takeIf { off <= if (videos) PICKED_VIDEO_LENGTH_MS else SAME_LENGTH_MS }
    }

    /**
     * The song as a video on YouTube, which YouTube Music plays too: e.g. one its artist put up but
     * never released. A video's title names more, "Artist - Song (Official Video)", so it counts when
     * it's the song's words, its artist's and nothing else but such tags; a remix or a live take has
     * words of its own and stays out.
     */
    private suspend fun searchVideosOnYouTubeMusic(metadata: MusicMetadata, client: JSONObject): List<JSONObject> {
        val body = JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("query", searchQuery(metadata))
            .put("params", YOUTUBE_MUSIC_VIDEOS)
        return fetchJson(YOUTUBE_MUSIC_SEARCH_URL, body).listItems()
    }

    /** A YouTube Music search result: its video, its name, its second line split up, and its length when it says. */
    private class Row(val id: String?, val name: String, val details: List<String>, val lengthMs: Long?)

    private fun row(item: JSONObject): Row {
        val columns = item.optJSONArray("flexColumns")?.objects()
            ?.map { it.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.text().orEmpty() }.orEmpty()
        val details = columns.getOrNull(1).orEmpty().split(" • ")
        return Row(
            item.optJSONObject("playlistItemData")?.optString("videoId")?.ifBlank { null },
            columns.firstOrNull().orEmpty(), details, lengthMs(details.last())
        )
    }

    /** "4:46" or "1:02:03" as milliseconds, or null for anything else. */
    private fun lengthMs(text: String): Long? {
        val parts = text.trim().split(":").takeIf { it.size in 2..3 } ?: return null
        val numbers = parts.map { it.toLongOrNull() ?: return null }
        return numbers.fold(0L) { total, part -> total * 60 + part } * 1000
    }

    /**
     * What a song goes by and how long it is. Apple Music names it in each store as it's written
     * there, so its Japanese, Korean and Chinese stores give 終わらない歌 for Owaranai Uta, 봄날 for
     * Spring Day and 松原みき for Miki Matsubara. Deezer gives its length when Apple doesn't have it.
     */
    private class Names(val titles: Set<String>, val artists: Set<String>, val lengthMs: Long?)

    private suspend fun namesOf(metadata: MusicMetadata): Names {
        val titles = mutableSetOf(metadata.title)
        val artists = mutableSetOf(metadata.artist)
        val song = appleSong(metadata)
        var lengthMs = song?.optLong("trackTimeMillis")?.takeIf { it > 0 }
        song?.optLong("trackId")?.takeIf { it > 0 }?.let { id ->
            val elsewhere = coroutineScope {
                NAMING_STORES.map { store -> async { answering { appleLookup(id, store) } } }.awaitAll()
            }
            (listOf(song) + elsewhere.filterNotNull()).forEach {
                titles += it.optString("trackName").ifBlank { null } ?: return@forEach
                artists += it.optString("artistName").ifBlank { null } ?: return@forEach
            }
        }
        if (lengthMs == null) {
            lengthMs = answering {
                searchDeezer(metadata).firstOrNull { matches(metadata, MetadataParsers.deezerTitle(it), it.optJSONObject("artist")?.optString("name").orEmpty()) }
                    ?.optLong("duration")?.takeIf { it > 0 }?.times(1000)
            }
        }
        return Names(titles, artists, lengthMs)
    }

    /**
     * The song on Apple Music, by its name, or one of the same name and artist with a tag, as "(with
     * Delon)". A store may only have it in another script, as Argentina's has 夜に駆ける, so the US
     * store, which names it in Latin letters too, is asked when this one doesn't have it; and last,
     * at once, for a song named in Chinese, Japanese or Korean, the stores that name it as its own
     * country does, as only Taiwan's credits 光年之外 to 鄧紫棋, where the rest say G.E.M.
     */
    private suspend fun appleSong(metadata: MusicMetadata): JSONObject? {
        val stores = listOf(country.takeIf { it.length == 2 }, "US").distinct()
        stores.firstNotNullOfOrNull { appleSong(metadata, it) }?.let { return it }
        if ((metadata.title + metadata.artist).none { Character.UnicodeScript.of(it.code) in EAST_ASIAN }) return null
        return coroutineScope { (NAMING_STORES - stores.toSet()).map { store -> async { appleSong(metadata, store) } }.awaitAll() }.firstNotNullOfOrNull { it }
    }

    private suspend fun appleSong(metadata: MusicMetadata, store: String?): JSONObject? = answering {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", searchQuery(metadata))
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "10")
            .apply { store?.let { addQueryParameter("country", it) } }
            .build()
        val results = fetchJson(url.toString()).optJSONArray("results")?.objects().orEmpty()
        results.firstOrNull { matches(metadata, it.optString("trackName"), it.optString("artistName")) }
            ?: results.firstOrNull {
                looselyMatches(metadata, it.optString("trackName"), it.optString("artistName")) &&
                    !SongNames.saysAnotherVersion(it.optString("trackName"), metadata.title, metadata.artist)
            }
    }

    private suspend fun appleLookup(id: Long, store: String): JSONObject? {
        val url = "https://itunes.apple.com/lookup".toHttpUrl().newBuilder()
            .addQueryParameter("id", id.toString())
            .addQueryParameter("country", store)
            .build()
        return fetchJson(url.toString()).optJSONArray("results")?.optJSONObject(0)
    }

    /** [lookUp]'s answer, or null when it failed, so one of many asked at once doesn't sink the rest. */
    private suspend fun <T> answering(lookUp: suspend () -> T?): T? = try {
        lookUp()
    } catch (_: IOException) {
        null
    } catch (_: JSONException) {
        null
    }

    /** As long as the song, give or take what another release of it adds; true when either isn't known. */
    private fun sameLength(row: Row, names: Names): Boolean {
        val wanted = names.lengthMs ?: return true
        val length = row.lengthMs ?: return true
        return kotlin.math.abs(length - wanted) <= SAME_LENGTH_MS
    }

    /** A song result, "Name | Artist • Album • 4:46", under any of the song's names, by any of its artist's. */
    private fun isTheSong(row: Row, names: Names): Boolean {
        if (row.id == null) return false
        val album = row.details.getOrNull(1).orEmpty()
        val named = SongNames.titleNames(row.name).any { name -> names.titles.any { SongNames.sameVersion(it, name, album) } }
        val wanted = names.artists.flatMap(SongNames::artists).toSet()
        val by = SongNames.artists(row.details.first()).any(wanted::contains)
        return named && by && sameLength(row, names)
    }

    /**
     * A video result, "Title | Channel • 1.2M views • 3:45", that plays the song: one of its names in
     * the title, nothing in it saying it's another version, e.g. a cover or karaoke, and its artist
     * the one who put it up, or named in the title of one as long as the song, e.g. with its lyrics.
     * Its title says nothing else but such tags outside brackets, so "Perfect Day" isn't "Perfect";
     * what's in them, "(終わらない歌 An Endless Song)", is what it's called elsewhere or said of it.
     */
    private fun isTheSongsVideo(row: Row, names: Names): Boolean {
        if (row.id == null) return false
        if (names.titles.none { titleIn(it, row.name) }) return false
        if (names.titles.all { title -> SongNames.saysAnotherVersion(row.name, title, names.artists.joinToString(" ")) }) return false
        val said = names.titles.flatMap { SongNames.words(SongNames.withoutEditionTag(it)) } + names.artists.flatMap(SongNames::words)
        val outside = row.name.replace(BRACKETED, " ")
        val rest = SongNames.words(outside) - said.toSet() - VIDEO_TAGS
        if (rest.any { !YEAR.matches(it) }) return false
        // Nor anything in another script, which has no words to tell apart: "桜の雨" isn't "桜". Named
        // both ways, "プラスティック・ラブ - Plastic Love", the other way is the song's name too.
        val known = names.titles.map { SongNames.normalize(SongNames.withoutEditionTag(it)) }
        val bothWays = SongNames.titleNames(outside.trim()).drop(1).takeIf { halves -> halves.any { SongNames.normalize(it) in known } }.orEmpty()
        val written = (known + bothWays.map(SongNames::normalize) +
            names.artists.flatMap(SongNames::artistNames).map { SongNames.normalize(SongNames.withoutArticle(it)) } + VIDEO_TAG_MARKS)
            .filter { it.isNotEmpty() }.sortedByDescending { it.length }
        if (written.fold(SongNames.normalize(outside)) { left, name -> left.replace(name, "") }.any { it.code > 0x7f }) return false
        // A channel is the artist's own by its whole name, maybe with what YouTube adds to one:
        // "Band - Topic", "BandVEVO".
        val uploader = SongNames.normalize(SongNames.withoutArticle(row.details.first()))
        val own = names.artists.flatMap(SongNames::artists).any { it == uploader || uploader.startsWith(it) && uploader.removePrefix(it) in CHANNEL_SUFFIXES }
        return own || names.artists.any { artistIn(it, row.name) } && sameLength(row, names)
    }

    /**
     * [title] is in [name]: all its words, or, written in another script, the whole of it. A title
     * that's only a tag, or one Latin letter, says too little to go on; "桜" says enough, as what
     * else a video's title says in that script is looked at too.
     */
    private fun titleIn(title: String, name: String): Boolean {
        val bare = SongNames.withoutEditionTag(title)
        val words = SongNames.words(bare)
        val letters = SongNames.normalize(bare)
        return when {
            letters.isEmpty() -> false
            letters.any { it.code > 0x7f } -> letters in SongNames.normalize(name)
            else -> words.isNotEmpty() && SongNames.words(name).containsAll(words)
        }
    }

    /** One of the artist's names in [name], as [titleIn] tells. */
    private fun artistIn(artist: String, name: String): Boolean =
        SongNames.artistNames(artist).any { titleIn(SongNames.withoutArticle(it), name) }

    private fun JSONObject.text(): String = optJSONObject("text").runsText()

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
            // Busy, or down, it's no answer, and says nothing about whether it has the song.
            if (!response.isSuccessful) throw IOException("${request.url.host} answered ${response.code}")
            JSONObject(withContext(ioDispatcher) { response.body.stringAtMost() })
        }
    }

    /**
     * Requires the same name and at least one artist in common, so remixes, covers and deluxe
     * editions don't win by rank. Whole names are compared: "Sia" must not match "Asia".
     */
    private fun matches(metadata: MusicMetadata, name: String, artist: String, album: String = ""): Boolean {
        if (!SongNames.sameVersion(metadata.title, name, album)) return false
        if (metadata.type == ItemType.ARTIST) return true
        // With no artist to check, e.g. a Google link that only names the song, any song of that name
        // would do, so none is taken for it: it's searched for instead.
        val wanted = SongNames.artists(metadata.artist)
        return SongNames.artists(artist).any(wanted::contains)
    }

    private fun looselyMatches(metadata: MusicMetadata, name: String, artist: String): Boolean =
        SongNames.wordsMatch(name, metadata.title) && SongNames.artistInside(artist, metadata.artist)

    private companion object {
        const val TIMEOUT_MS = 5_000L

        /** How long ChatGPT has to pick a song, after the searches it picks from; it takes a few seconds. */
        const val PICK_TIMEOUT_MS = 8_000L
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
        /** How many search results the song is picked from. */
        const val PICK_FROM = 6
        const val YOUTUBE_MUSIC_CLIENT_VERSION = "1.20240101.01.00"

        // Search filters as YouTube Music itself encodes them: the song, album and artist tabs.
        const val YOUTUBE_MUSIC_SONGS = "EgWKAQIIAWoKEAoQAxAEEAkQBQ=="
        const val YOUTUBE_MUSIC_ALBUMS = "EgWKAQIYAWoKEAoQAxAEEAkQBQ=="
        const val YOUTUBE_MUSIC_ARTISTS = "EgWKAQIgAWoKEAoQAxAEEAkQBQ=="
        const val YOUTUBE_MUSIC_VIDEOS = "EgWKAQIQAWoKEAoQAxAEEAkQBQ=="

        /** What YouTube adds to an artist's own channel name. */
        private val CHANNEL_SUFFIXES = setOf("topic", "vevo", "official", "music", "tv")

        /** What a video's title says of it besides the song's name, not another recording: "Official Video", "Lyrics", "Sub Español". */
        private val VIDEO_TAGS = setOf(
            "official", "video", "audio", "music", "lyric", "lyrics", "visualizer", "visualiser", "hd", "hq", "4k", "mv", "pv",
            "clip", "videoclip", "full", "version", "feat", "ft", "the", "with", "on", "screen", "high", "quality", "remastered",
            "remaster", "sub", "subs", "subtitles", "subtitulado", "subtitulada", "legendado", "letra", "traduccion",
            "traducida", "translation", "espanol", "english", "eng", "romaji", "kanji"
        )

        /** The same, as other scripts write them: "歌詞付き", "公式". */
        private val VIDEO_TAG_MARKS = listOf("歌詞付き", "歌詞付", "歌詞", "公式", "字幕", "高音質", "日本語", "中日羅", "가사").map(SongNames::normalize)

        /** A year, as a video's title may give the song's: "1984". */
        private val YEAR = Regex("""(?:19|20)\d\d""")

        /** What's in brackets, of any kind a title uses. */
        private val BRACKETED = Regex("""[(\[（【「『［][^)\]）】」』］]*[)\]）】」』］]""")

        /** Apple Music stores that write names as they are in Japanese, Korean and Chinese. */
        private val NAMING_STORES = listOf("JP", "KR", "TW", "HK", "CN")

        /** The scripts the stores in [NAMING_STORES] write names in. */
        private val EAST_ASIAN = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL)

        /** How far another release of a song, e.g. a remaster or a video's, may be from its length. */
        const val SAME_LENGTH_MS = 8_000L

        /** How far a video ChatGPT picks may be from the song's length, as a video may open or end with more. */
        const val PICKED_VIDEO_LENGTH_MS = 45_000L

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
 * so on no other service. Where only the artist's own page or account counts, a release on a
 * label's page isn't found, though it's there, so it's searched for.
 */
internal fun canBeMissing(target: MusicService, item: MusicMetadata): Boolean =
    (canMatchExactly(target, item.type) && target !in ownPagesOnly) || (item.type == ItemType.PLAYLIST && item.tracks.isEmpty())

/** The apps a song is only matched on from its artist's own page or a verified account. */
private val ownPagesOnly = setOf(MusicService.BANDCAMP, MusicService.SOUNDCLOUD, MusicService.AUDIOMACK)
