package com.astrovm.crosstune

import androidx.annotation.StringRes
import okhttp3.HttpUrl.Companion.toHttpUrl

internal enum class SearchTarget(
    @StringRes val labelRes: Int,
    @StringRes val openButtonLabelRes: Int,
    val packageName: String,
    private val searchUrlBuilder: (String) -> String
) {
    YOUTUBE_MUSIC(
        R.string.target_youtube_music,
        R.string.open_in_youtube_music,
        "com.google.android.apps.youtube.music",
        queryUrl("https://music.youtube.com/search", "q")
    ),
    YOUTUBE(
        R.string.target_youtube,
        R.string.open_in_youtube,
        "com.google.android.youtube",
        queryUrl("https://www.youtube.com/results", "search_query")
    ),
    APPLE_MUSIC(
        R.string.target_apple_music,
        R.string.open_in_apple_music,
        "com.apple.android.music",
        queryUrl("https://music.apple.com/search", "term")
    ),
    DEEZER(
        R.string.target_deezer,
        R.string.open_in_deezer,
        "deezer.android.app",
        { query -> "https://www.deezer.com/search".toHttpUrl().newBuilder().addPathSegment(query).build().toString() }
    ),
    TIDAL(
        R.string.target_tidal,
        R.string.open_in_tidal,
        "com.aspiro.tidal",
        queryUrl("https://listen.tidal.com/search", "q")
    ),
    SOUNDCLOUD(
        R.string.target_soundcloud,
        R.string.open_in_soundcloud,
        "com.soundcloud.android",
        queryUrl("https://soundcloud.com/search", "q")
    );

    fun searchUrl(query: String): String = searchUrlBuilder(query)

    companion object {
        fun fromName(name: String?): SearchTarget = entries.firstOrNull { it.name == name } ?: YOUTUBE_MUSIC
    }
}

private fun queryUrl(baseUrl: String, parameter: String): (String) -> String = { query ->
    baseUrl.toHttpUrl().newBuilder().addQueryParameter(parameter, query).build().toString()
}

internal fun searchQuery(metadata: SpotifyMetadata): String =
    listOf(metadata.title, metadata.artist).filter { it.isNotBlank() }.joinToString(" ")
