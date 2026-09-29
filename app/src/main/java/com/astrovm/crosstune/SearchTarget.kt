package com.astrovm.crosstune

import androidx.annotation.StringRes
import okhttp3.HttpUrl.Companion.toHttpUrl

internal enum class SearchTarget(
    @StringRes val labelRes: Int,
    @StringRes val openButtonLabelRes: Int,
    val packageName: String,
    private val searchBaseUrl: String,
    private val queryParameter: String
) {
    YOUTUBE_MUSIC(
        R.string.target_youtube_music,
        R.string.open_in_youtube_music,
        "com.google.android.apps.youtube.music",
        "https://music.youtube.com/search",
        "q"
    ),
    YOUTUBE(
        R.string.target_youtube,
        R.string.open_in_youtube,
        "com.google.android.youtube",
        "https://www.youtube.com/results",
        "search_query"
    );

    fun searchUrl(query: String): String =
        searchBaseUrl.toHttpUrl().newBuilder().addQueryParameter(queryParameter, query).build().toString()

    companion object {
        fun fromName(name: String?): SearchTarget = entries.firstOrNull { it.name == name } ?: YOUTUBE_MUSIC
    }
}

internal fun searchQuery(metadata: TrackMetadata): String =
    listOf(metadata.title, metadata.artist).filter { it.isNotBlank() }.joinToString(" ")
