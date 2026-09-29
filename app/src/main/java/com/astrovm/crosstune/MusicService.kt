package com.astrovm.crosstune

import androidx.annotation.StringRes
import okhttp3.HttpUrl.Companion.toHttpUrl

internal enum class ItemType(@StringRes val labelRes: Int) {
    TRACK(R.string.type_track),
    ALBUM(R.string.type_album),
    ARTIST(R.string.type_artist),
    PLAYLIST(R.string.type_playlist)
}

/**
 * A music service Crosstune can open things in and, when [canBeSource], read links from.
 * Amazon Music links render with JavaScript and expose no metadata, so it is destination-only.
 */
internal enum class MusicService(
    @StringRes val labelRes: Int,
    @StringRes val openLabelRes: Int,
    val packageName: String,
    val canBeSource: Boolean,
    private val searchUrlBuilder: (String) -> String
) {
    SPOTIFY(
        R.string.service_spotify, R.string.open_in_spotify, "com.spotify.music", true,
        pathSearchUrl("https://open.spotify.com/search")
    ),
    YOUTUBE_MUSIC(
        R.string.target_youtube_music, R.string.open_in_youtube_music, "com.google.android.apps.youtube.music", true,
        querySearchUrl("https://music.youtube.com/search", "q")
    ),
    YOUTUBE(
        R.string.target_youtube, R.string.open_in_youtube, "com.google.android.youtube", true,
        querySearchUrl("https://www.youtube.com/results", "search_query")
    ),
    APPLE_MUSIC(
        R.string.target_apple_music, R.string.open_in_apple_music, "com.apple.android.music", true,
        querySearchUrl("https://music.apple.com/search", "term")
    ),
    DEEZER(
        R.string.target_deezer, R.string.open_in_deezer, "deezer.android.app", true,
        pathSearchUrl("https://www.deezer.com/search")
    ),
    TIDAL(
        R.string.target_tidal, R.string.open_in_tidal, "com.aspiro.tidal", true,
        querySearchUrl("https://listen.tidal.com/search", "q")
    ),
    SOUNDCLOUD(
        R.string.target_soundcloud, R.string.open_in_soundcloud, "com.soundcloud.android", true,
        querySearchUrl("https://soundcloud.com/search", "q")
    ),
    BANDCAMP(
        R.string.service_bandcamp, R.string.open_in_bandcamp, "com.bandcamp.android", true,
        querySearchUrl("https://bandcamp.com/search", "q")
    ),
    AMAZON_MUSIC(
        R.string.service_amazon_music, R.string.open_in_amazon_music, "com.amazon.mp3", false,
        pathSearchUrl("https://music.amazon.com/search")
    );

    fun searchUrl(query: String): String = searchUrlBuilder(query)

    companion object {
        fun fromName(name: String?): MusicService? = entries.firstOrNull { it.name == name }
    }
}

private fun querySearchUrl(baseUrl: String, parameter: String): (String) -> String = { query ->
    baseUrl.toHttpUrl().newBuilder().addQueryParameter(parameter, query).build().toString()
}

private fun pathSearchUrl(baseUrl: String): (String) -> String = { query ->
    baseUrl.toHttpUrl().newBuilder().addPathSegment(query).build().toString()
}

internal fun searchQuery(metadata: MusicMetadata): String =
    listOf(metadata.title, metadata.artist).filter { it.isNotBlank() }.joinToString(" ")
