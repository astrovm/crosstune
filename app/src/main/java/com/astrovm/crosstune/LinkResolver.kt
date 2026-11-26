package com.astrovm.crosstune

import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal enum class AppError(@StringRes val messageRes: Int, val canRetry: Boolean = false) {
    INVALID_URL(R.string.error_invalid_url),
    CLIPBOARD_EMPTY(R.string.error_clipboard_empty),
    NOT_FOUND(R.string.error_not_found),
    RATE_LIMITED(R.string.error_rate_limited, canRetry = true),
    SERVICE_UNAVAILABLE(R.string.error_service_unavailable, canRetry = true),
    NETWORK(R.string.error_network, canRetry = true),
    METADATA_UNAVAILABLE(R.string.error_metadata_unavailable, canRetry = true),
    NO_APP_TO_OPEN(R.string.error_no_app_to_open),
    /** Listening didn't name the song. */
    NO_MATCH(R.string.error_no_match, canRetry = true),
    /** The microphone can't record, most likely without the permission. */
    MICROPHONE(R.string.error_microphone),
    /** Shazam is busy or failing; another song recognition app may still work. */
    RECOGNITION_UNAVAILABLE(R.string.error_recognition_unavailable, canRetry = true),
    /** ChatGPT named no songs like the one asked about that could be found. */
    NO_SONGS_LIKE(R.string.error_no_songs_like)
}

/** The duration of one exact YouTube video, used only to recognize its ad. */
internal data class VideoAdLength(val id: String, val lengthMs: Long)

internal sealed interface Resolution {
    data class Resolved(val link: MusicLink, val metadata: MusicMetadata, val videoAdLength: VideoAdLength? = null) : Resolution
    data class Failed(val error: AppError, val link: MusicLink? = null) : Resolution
}

/**
 * Reads an item's name and artist from its service, using official key-less endpoints where they
 * exist (YouTube and SoundCloud oEmbed, iTunes Lookup, Deezer's API) and public page metadata otherwise.
 */
internal class LinkResolver(
    private val client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val apis: ServiceApis = ServiceApis(client, java.util.Locale.getDefault().country, ioDispatcher)
) {
    suspend fun resolve(input: LinkInput, videoAnswer: Deferred<JSONObject?>? = null): Resolution = when (input) {
        is LinkInput.Link -> resolveLink(input.link, videoAnswer)
        is LinkInput.ShortLink -> guarded(null) { resolveShortLink(input.url) }
        is LinkInput.ShazamTrack -> guarded(null) { resolveShazamTrack(input.key) }
        is LinkInput.RecognizedSong -> Resolution.Resolved(
            MusicLink(null, ItemType.TRACK, input.url, input.url), input.metadata
        )
    }

    private suspend fun resolveShortLink(url: String): Resolution {
        val (response, body) = fetch(url)
        // A shared Google result leads to a search for it, which names the song.
        MusicLinks.googleSong(response.request.url)?.let { song ->
            return Resolution.Resolved(MusicLink(null, ItemType.TRACK, song.url, song.url), song.metadata)
        }
        val link = MusicLinks.fromUrl(response.request.url)
            ?: MusicLinks.fromPage(body)
            ?: return Resolution.Failed(httpError(response) ?: AppError.INVALID_URL)
        return resolveLink(link)
    }

    /**
     * An older Shazam link's song, from Shazam's public API. It opens as the song on Apple Music,
     * like newer Shazam links, or else by its name, like a song Now Playing heard.
     */
    private suspend fun resolveShazamTrack(key: String): Resolution {
        val (response, body) = fetch("https://cdn.shazam.com/discovery/v5/en-US/US/web/-/track/$key")
        httpError(response)?.let { return Resolution.Failed(it) }
        // It answers an unknown key with no content.
        if (body.isBlank()) return Resolution.Failed(AppError.NOT_FOUND)
        val song = JSONObject(body)
        song.optString("trackadamid").takeIf { it.isNotEmpty() }?.let { id ->
            return resolveLink(MusicLink(MusicService.APPLE_MUSIC, ItemType.TRACK, id, "https://music.apple.com/us/song/$id", "us"))
        }
        val metadata = MusicMetadata(
            title = song.optString("title").takeIf { it.isNotBlank() } ?: return Resolution.Failed(AppError.METADATA_UNAVAILABLE),
            artist = song.optString("subtitle"),
            artworkUrl = song.optJSONObject("images")?.let { it.optString("coverart").takeIf { cover -> cover.isNotEmpty() } }
        )
        val recognized = MusicLinks.recognizedSong(metadata)
        return Resolution.Resolved(MusicLink(null, ItemType.TRACK, recognized.url, recognized.url), metadata)
    }

    private suspend fun resolveLink(link: MusicLink, shared: Deferred<JSONObject?>? = null): Resolution {
        // Never wait for this optional answer. It runs alongside the normal link lookup and is
        // cancelled when that lookup finishes, so a slow catalogue does not hold up the link.
        val scope = if (link.isYouTubeVideo() && shared == null) CoroutineScope(SupervisorJob() + ioDispatcher) else null
        val video = shared ?: scope?.async(start = CoroutineStart.UNDISPATCHED) {
            youTubeMusic(YouTubeMusicApi.NEXT_URL, "videoId", link.id)
        }
        return try {
            resolveLinkAnswer(link, video)
        } finally {
            scope?.cancel()
        }
    }

    private suspend fun resolveLinkAnswer(link: MusicLink, video: Deferred<JSONObject?>?): Resolution = guarded(link) {
        if (link.service == MusicService.YOUTUBE_MUSIC && (link.type == ItemType.ALBUM || link.type == ItemType.ARTIST)) {
            return@guarded youTubeMusicPage(link)?.let { Resolution.Resolved(link, it) } ?: Resolution.Failed(AppError.METADATA_UNAVAILABLE, link)
        }
        val (response, body) = fetch(metadataUrl(link))
        resolveResponse(link, video, response, body)
    }

    private suspend fun resolveResponse(link: MusicLink, video: Deferred<JSONObject?>?, response: Response, body: String): Resolution {
        // A video its owner keeps from being embedded elsewhere, as many labels' are, has no oEmbed,
        // but YouTube Music still says what it is. SoundCloud's API can also name a refused upload.
        if (response.code == 401 || response.code == 403) {
            refusedTrack(link, video)?.let { return it }
        }
        httpError(response, privateWhenRefused = link.isOEmbed())?.let { return Resolution.Failed(it, link) }
        // Deezer reports errors, such as a missing item or its rate limit, inside a successful response.
        if (link.service == MusicService.DEEZER) {
            MetadataParsers.deezerError(JSONObject(body))?.let { return Resolution.Failed(it, link) }
        }
        return when (val metadata = parse(link, body)) {
            null -> Resolution.Failed(if (isApiNotFound(link)) AppError.NOT_FOUND else AppError.METADATA_UNAVAILABLE, link)
            else -> {
                val length = video?.takeIf { it.isCompleted }?.await()?.let { MetadataParsers.youtubeMusicVideoLength(it, link.id) }
                Resolution.Resolved(link, withTracks(link, metadata, body), length?.let { VideoAdLength(link.id, it) })
            }
        }
    }

    private suspend fun refusedTrack(link: MusicLink, video: Deferred<JSONObject?>?): Resolution.Resolved? {
        if (link.isYouTubeVideo()) {
            val early = video?.takeIf { it.isCompleted }?.await()
            val answer = early?.takeIf { MetadataParsers.youtubeMusicVideo(it, link.id) != null }
                ?: youTubeMusic(YouTubeMusicApi.NEXT_URL, "videoId", link.id) ?: return null
            val metadata = MetadataParsers.youtubeMusicVideo(answer, link.id) ?: return null
            val length = MetadataParsers.youtubeMusicVideoLength(answer, link.id)
            return Resolution.Resolved(link, metadata, length?.let { VideoAdLength(link.id, it) })
        }
        if (link.service == MusicService.SOUNDCLOUD && link.type == ItemType.TRACK) {
            return soundCloudTrack(link.url)?.let { Resolution.Resolved(link, it) }
        }
        return null
    }

    /**
     * The songs of an album or playlist whose page doesn't list them, from where its service's own
     * website gets them: Spotify's embed pages, the APIs behind TIDAL's, SoundCloud's and
     * Audiomack's websites, and the one behind Presto Music's app. They're extra, so the album or
     * playlist still shows without them when they can't be read. A Spotify playlist's own [page] has
     * its first songs' covers.
     */
    private suspend fun withTracks(link: MusicLink, metadata: MusicMetadata, page: String): MusicMetadata {
        if (link.type != ItemType.PLAYLIST && link.type != ItemType.ALBUM) return metadata
        val tracks = answeredOr(emptyList()) {
            when (link.service) {
                MusicService.SPOTIFY -> {
                    val (response, body) = fetch("https://open.spotify.com/embed/${link.type.name.lowercase()}/${link.id}")
                    val covers = if (link.type == ItemType.PLAYLIST) MetadataParsers.spotifyCovers(page) else emptyMap()
                    if (response.isSuccessful) MetadataParsers.spotifyEmbedTracks(body).map { track -> covers[track.url]?.let { track.copy(artworkUrl = it) } ?: track } else emptyList()
                }
                MusicService.TIDAL -> apis.tidalTracks(link.type, link.id)
                MusicService.SOUNDCLOUD -> apis.soundCloudTracks(link.url)
                MusicService.AUDIOMACK -> apis.audiomackTracks(link.type, link.id)
                MusicService.PRESTO_MUSIC -> apis.prestoTracks(link.id, metadata)
                else -> return metadata
            }
        }
        return metadata.copy(tracks = tracks, trackCount = metadata.trackCount?.takeIf { it > tracks.size })
    }

    /** The iTunes Lookup API reports missing items as an empty result inside a successful response. */
    private fun isApiNotFound(link: MusicLink) =
        link.service == MusicService.APPLE_MUSIC && link.type != ItemType.PLAYLIST

    private fun metadataUrl(link: MusicLink): String = when (link.service) {
        // A playlist's page lists its videos; YouTube Music's own pages don't, but share its lists.
        MusicService.YOUTUBE, MusicService.YOUTUBE_MUSIC -> if (link.type == ItemType.PLAYLIST) {
            "https://www.youtube.com/playlist?list=${link.id}"
        } else {
            oEmbed("https://www.youtube.com/oembed", link)
        }
        MusicService.SOUNDCLOUD -> oEmbed("https://soundcloud.com/oembed", link)
        MusicService.AUDIOMACK -> oEmbed("https://audiomack.com/oembed", link)
        MusicService.APPLE_MUSIC -> if (link.type == ItemType.PLAYLIST) {
            link.url
        } else {
            "https://itunes.apple.com/lookup".toHttpUrl().newBuilder()
                .addQueryParameter("id", link.id)
                .addQueryParameter("country", link.region ?: "us")
                // An album's songs come with it.
                .apply { if (link.type == ItemType.ALBUM) addQueryParameter("entity", "song") }
                .build().toString()
        }
        MusicService.DEEZER -> "https://api.deezer.com/${link.url.toHttpUrl().pathSegments.first()}/${link.id}"
        // Its website asks a browser to prove it's one; the API behind its app doesn't.
        MusicService.PRESTO_MUSIC -> "${Presto.API}/album/${link.id}"
        else -> link.url
    }

    private fun oEmbed(endpoint: String, link: MusicLink): String =
        endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("format", "json")
            .addQueryParameter("url", link.url)
            .build().toString()

    private fun parse(link: MusicLink, body: String): MusicMetadata? = when (link.service) {
        MusicService.SPOTIFY -> MetadataParsers.spotify(body, link.type)
        MusicService.YOUTUBE, MusicService.YOUTUBE_MUSIC -> if (link.type == ItemType.PLAYLIST) {
            MetadataParsers.youtubePlaylist(body)
        } else {
            MetadataParsers.youtube(JSONObject(body))
        }
        MusicService.APPLE_MUSIC -> if (link.type == ItemType.PLAYLIST) {
            MetadataParsers.applePlaylist(body)
        } else {
            MetadataParsers.appleMusic(JSONObject(body), link.type)
        }
        MusicService.DEEZER -> MetadataParsers.deezer(JSONObject(body), link.type)
        MusicService.TIDAL -> MetadataParsers.tidal(body, link.type)
        MusicService.SOUNDCLOUD -> MetadataParsers.soundCloud(JSONObject(body), link.type)
        MusicService.AUDIOMACK -> MetadataParsers.audiomack(JSONObject(body), link.type)
        MusicService.PRESTO_MUSIC -> Presto.album(JSONObject(body))
        // Bandcamp; Amazon Music and Qobuz are never a source.
        else -> MetadataParsers.bandcamp(body, link.type)
    }

    private inline fun guarded(link: MusicLink?, block: () -> Resolution): Resolution = try {
        block()
    } catch (_: IOException) {
        Resolution.Failed(AppError.NETWORK, link)
    } catch (_: JSONException) {
        Resolution.Failed(AppError.METADATA_UNAVAILABLE, link)
    }

    /** Whether this link names an exact YouTube video. */
    private fun MusicLink.isYouTubeVideo() =
        (service == MusicService.YOUTUBE || service == MusicService.YOUTUBE_MUSIC) && type == ItemType.TRACK

    /** The SoundCloud upload at [url] as SoundCloud's own API has it, or null when it can't say. */
    private suspend fun soundCloudTrack(url: String): MusicMetadata? = try {
        apis.soundCloudItem(url)?.takeIf { it.optString("kind") == "track" }?.let(ServiceApis::soundCloudTrack)
    } catch (_: IOException) {
        null
    }

    /** An album's or artist's page on YouTube Music, as its header names it, with an album's songs. */
    private suspend fun youTubeMusicPage(link: MusicLink): MusicMetadata? =
        youTubeMusic(YouTubeMusicApi.BROWSE_URL, "browseId", link.id)?.let { MetadataParsers.youtubeMusicPage(it, link.type) }

    /** What YouTube Music's [url] answers about [key] [id]; null when it doesn't. */
    private suspend fun youTubeMusic(url: String, key: String, id: String): JSONObject? = answeredOr(null) {
        val body = YouTubeMusicApi.body().put(key, id)
        val request = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val call = client.newCall(request)
        coroutineScope {
            val finished = AtomicBoolean(false)
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { if (!finished.get()) call.cancel() }
            }
            try {
                call.answer(ioDispatcher) { response ->
                    if (!response.isSuccessful) return@answer null
                    JSONObject(response.body.stringAtMost())
                }
            } finally {
                finished.set(true)
                cancellation.cancel()
            }
        }
    }

    private suspend fun fetch(url: String): Pair<Response, String> {
        val request = Request.Builder().url(url).get().build()
        return client.newCall(request).answer(ioDispatcher) { response -> response to response.body.stringAtMost() }
    }

    /** Whether [metadataUrl] asks an oEmbed endpoint, which turns away what's private. */
    private fun MusicLink.isOEmbed() = when (service) {
        MusicService.YOUTUBE, MusicService.YOUTUBE_MUSIC -> type != ItemType.PLAYLIST
        MusicService.SOUNDCLOUD, MusicService.AUDIOMACK -> true
        else -> false
    }

    /**
     * Error pages still carry generic metadata, so any non-2xx status must fail before parsing.
     * Turned away, an oEmbed endpoint means it's private, as [privateWhenRefused] says; a page means
     * the service won't answer, e.g. it takes Crosstune for a bot, which says nothing about the song.
     */
    private fun httpError(response: Response, privateWhenRefused: Boolean = true): AppError? = when {
        response.isSuccessful -> null
        response.code == 404 || response.code == 410 -> AppError.NOT_FOUND
        response.code == 401 || response.code == 403 -> if (privateWhenRefused) AppError.NOT_FOUND else AppError.SERVICE_UNAVAILABLE
        response.code == 429 -> AppError.RATE_LIMITED
        response.code >= 500 -> AppError.SERVICE_UNAVAILABLE
        else -> AppError.METADATA_UNAVAILABLE
    }

    private companion object {
    }
}
