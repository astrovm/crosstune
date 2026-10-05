package com.astrovm.crosstune

import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.coroutines.executeAsync
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

internal enum class AppError(@StringRes val messageRes: Int, val canRetry: Boolean = false) {
    INVALID_URL(R.string.error_invalid_url),
    CLIPBOARD_EMPTY(R.string.error_clipboard_empty),
    NOT_FOUND(R.string.error_not_found),
    RATE_LIMITED(R.string.error_rate_limited, canRetry = true),
    SERVICE_UNAVAILABLE(R.string.error_service_unavailable, canRetry = true),
    NETWORK(R.string.error_network, canRetry = true),
    METADATA_UNAVAILABLE(R.string.error_metadata_unavailable, canRetry = true),
    NO_APP_TO_OPEN(R.string.error_no_app_to_open)
}

internal sealed interface Resolution {
    data class Resolved(val link: MusicLink, val metadata: MusicMetadata) : Resolution
    data class Failed(val error: AppError, val link: MusicLink? = null) : Resolution
}

/**
 * Reads an item's name and artist from its service, using official key-less endpoints where they
 * exist (YouTube and SoundCloud oEmbed, iTunes Lookup, Deezer's API) and public page metadata otherwise.
 */
internal class LinkResolver(
    private val client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun resolve(input: LinkInput): Resolution = when (input) {
        is LinkInput.Link -> resolveLink(input.link)
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
            artworkUrl = song.optJSONObject("images")?.optString("coverart")?.takeIf { it.isNotEmpty() }
        )
        val recognized = MusicLinks.recognizedSong(metadata)
        return Resolution.Resolved(MusicLink(null, ItemType.TRACK, recognized.url, recognized.url), metadata)
    }

    private suspend fun resolveLink(link: MusicLink): Resolution = guarded(link) {
        val (response, body) = fetch(metadataUrl(link))
        httpError(response)?.let { return@guarded Resolution.Failed(it, link) }
        // Deezer reports errors, such as a missing item or its rate limit, inside a successful response.
        if (link.service == MusicService.DEEZER) {
            MetadataParsers.deezerError(JSONObject(body))?.let { return@guarded Resolution.Failed(it, link) }
        }
        when (val metadata = parse(link, body)) {
            null -> Resolution.Failed(if (isApiNotFound(link)) AppError.NOT_FOUND else AppError.METADATA_UNAVAILABLE, link)
            else -> Resolution.Resolved(link, withSpotifyTracks(link, metadata, body))
        }
    }

    /**
     * Spotify's album and playlist pages don't list the songs, but their embed pages do. They're
     * extra, so the album or playlist still shows without them when that page can't be read. A
     * playlist's own [page] has its first songs' covers.
     */
    private suspend fun withSpotifyTracks(link: MusicLink, metadata: MusicMetadata, page: String): MusicMetadata {
        if (link.service != MusicService.SPOTIFY || (link.type != ItemType.PLAYLIST && link.type != ItemType.ALBUM)) return metadata
        val tracks = try {
            val (response, body) = fetch("https://open.spotify.com/embed/${link.type.name.lowercase()}/${link.id}")
            if (response.isSuccessful) MetadataParsers.spotifyEmbedTracks(body) else emptyList()
        } catch (_: IOException) {
            emptyList()
        } catch (_: JSONException) {
            emptyList()
        }
        val covers = if (link.type == ItemType.PLAYLIST) MetadataParsers.spotifyCovers(page) else emptyMap()
        return metadata.copy(
            tracks = tracks.map { track -> covers[track.url]?.let { track.copy(artworkUrl = it) } ?: track },
            trackCount = metadata.trackCount?.takeIf { it > tracks.size }
        )
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
        // Bandcamp; Amazon Music is never a source.
        else -> MetadataParsers.bandcamp(body, link.type)
    }

    private inline fun guarded(link: MusicLink?, block: () -> Resolution): Resolution = try {
        block()
    } catch (_: IOException) {
        Resolution.Failed(AppError.NETWORK, link)
    } catch (_: JSONException) {
        Resolution.Failed(AppError.METADATA_UNAVAILABLE, link)
    }

    /** Cancelling the calling coroutine cancels the HTTP call. */
    private suspend fun fetch(url: String): Pair<Response, String> {
        val request = Request.Builder().url(url).get().build()
        return client.newCall(request).executeAsync().use { response ->
            response to withContext(ioDispatcher) { response.body.stringAtMost() }
        }
    }

    /** Error pages still carry generic metadata, so any non-2xx status must fail before parsing. */
    private fun httpError(response: Response): AppError? = when {
        response.isSuccessful -> null
        response.code == 404 || response.code == 410 -> AppError.NOT_FOUND
        // oEmbed answers private or embedding-disabled videos with 401/403.
        response.code == 401 || response.code == 403 -> AppError.NOT_FOUND
        response.code == 429 -> AppError.RATE_LIMITED
        response.code >= 500 -> AppError.SERVICE_UNAVAILABLE
        else -> AppError.METADATA_UNAVAILABLE
    }
}
