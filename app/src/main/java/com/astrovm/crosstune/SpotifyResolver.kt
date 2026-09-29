package com.astrovm.crosstune

import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.coroutines.executeAsync
import java.io.IOException

internal enum class AppError(@StringRes val messageRes: Int) {
    INVALID_URL(R.string.error_invalid_url),
    NOT_FOUND(R.string.error_not_found),
    RATE_LIMITED(R.string.error_rate_limited),
    SPOTIFY_UNAVAILABLE(R.string.error_spotify_unavailable),
    NETWORK(R.string.error_network),
    METADATA_UNAVAILABLE(R.string.error_metadata_unavailable),
    NO_APP_TO_OPEN(R.string.error_no_app_to_open)
}

internal sealed interface Resolution {
    data class Resolved(val trackId: String, val metadata: TrackMetadata) : Resolution
    data class Failed(val error: AppError) : Resolution
}

/** Turns a Spotify link into track metadata using Spotify's public web pages. */
internal class SpotifyResolver(
    private val client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun resolve(input: SpotifyInput): Resolution = try {
        when (input) {
            is SpotifyInput.Track -> resolveTrack(input.id)
            is SpotifyInput.ShortLink -> resolveShortLink(input.url)
        }
    } catch (_: IOException) {
        Resolution.Failed(AppError.NETWORK)
    }

    private suspend fun resolveShortLink(url: String): Resolution {
        val (response, html) = fetch(url)
        val trackId = SpotifyLinks.trackIdFromUrl(response.request.url)
            ?: SpotifyLinks.trackIdFromPage(html)
            ?: return Resolution.Failed(httpError(response) ?: AppError.INVALID_URL)
        return resolveTrack(trackId)
    }

    private suspend fun resolveTrack(trackId: String): Resolution {
        val (response, html) = fetch(SpotifyLinks.trackUrl(trackId))
        httpError(response)?.let { return Resolution.Failed(it) }
        val metadata = SpotifyPage.parseTrack(html) ?: return Resolution.Failed(AppError.METADATA_UNAVAILABLE)
        return Resolution.Resolved(trackId, metadata)
    }

    /** Cancelling the calling coroutine cancels the HTTP call. */
    private suspend fun fetch(url: String): Pair<Response, String> {
        val request = Request.Builder().url(url).get().build()
        return client.newCall(request).executeAsync().use { response ->
            response to withContext(ioDispatcher) { response.body.string() }
        }
    }

    /** Error pages still carry generic og: tags, so any non-2xx status must fail before parsing. */
    private fun httpError(response: Response): AppError? = when {
        response.isSuccessful -> null
        response.code == 404 || response.code == 410 -> AppError.NOT_FOUND
        response.code == 429 -> AppError.RATE_LIMITED
        response.code >= 500 -> AppError.SPOTIFY_UNAVAILABLE
        else -> AppError.METADATA_UNAVAILABLE
    }
}
