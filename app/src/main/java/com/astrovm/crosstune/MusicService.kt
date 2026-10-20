package com.astrovm.crosstune

import androidx.annotation.DrawableRes
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
 * Amazon Music and Qobuz links render with JavaScript and expose no metadata, so they are destination-only.
 */
internal enum class MusicService(
    @StringRes val labelRes: Int,
    @StringRes val openLabelRes: Int,
    @DrawableRes val iconRes: Int,
    val packageName: String,
    val canBeSource: Boolean,
    private val searchUrlBuilder: (String) -> String
) {
    SPOTIFY(
        R.string.service_spotify, R.string.open_in_spotify, R.drawable.ic_service_spotify, "com.spotify.music", true,
        pathSearchUrl("https://open.spotify.com/search")
    ),
    YOUTUBE_MUSIC(
        R.string.target_youtube_music, R.string.open_in_youtube_music, R.drawable.ic_service_youtubemusic, "com.google.android.apps.youtube.music", true,
        querySearchUrl("https://music.youtube.com/search", "q")
    ),
    YOUTUBE(
        R.string.target_youtube, R.string.open_in_youtube, R.drawable.ic_service_youtube, "com.google.android.youtube", true,
        querySearchUrl("https://www.youtube.com/results", "search_query")
    ),
    APPLE_MUSIC(
        R.string.target_apple_music, R.string.open_in_apple_music, R.drawable.ic_service_applemusic, "com.apple.android.music", true,
        querySearchUrl("https://music.apple.com/search", "term")
    ),
    DEEZER(
        R.string.target_deezer, R.string.open_in_deezer, R.drawable.ic_service_deezer, "deezer.android.app", true,
        pathSearchUrl("https://www.deezer.com/search")
    ),
    TIDAL(
        R.string.target_tidal, R.string.open_in_tidal, R.drawable.ic_service_tidal, "com.aspiro.tidal", true,
        querySearchUrl("https://listen.tidal.com/search", "q")
    ),
    SOUNDCLOUD(
        R.string.target_soundcloud, R.string.open_in_soundcloud, R.drawable.ic_service_soundcloud, "com.soundcloud.android", true,
        querySearchUrl("https://soundcloud.com/search", "q")
    ),
    BANDCAMP(
        R.string.service_bandcamp, R.string.open_in_bandcamp, R.drawable.ic_service_bandcamp, "com.bandcamp.android", true,
        querySearchUrl("https://bandcamp.com/search", "q")
    ),
    AUDIOMACK(
        R.string.service_audiomack, R.string.open_in_audiomack, R.drawable.ic_service_audiomack, "com.audiomack", true,
        querySearchUrl("https://audiomack.com/search", "q")
    ),
    AMAZON_MUSIC(
        R.string.service_amazon_music, R.string.open_in_amazon_music, R.drawable.ic_service_amazonmusic, "com.amazon.mp3", false,
        pathSearchUrl("https://music.amazon.com/search")
    ),
    QOBUZ(
        R.string.service_qobuz, R.string.open_in_qobuz, R.drawable.ic_service_qobuz, "com.qobuz.music", false,
        querySearchUrl("https://play.qobuz.com/search", "q")
    );

    fun searchUrl(query: String): String = searchUrlBuilder(query)

    /**
     * The app's own package, then those of builds patched from it, e.g. ReVanced's, which open its
     * links and play as it does: YouTube and YouTube Music by ReVanced, ReVanced Extended, Morphe and
     * Vanced, under names of their own. Patched Spotify keeps Spotify's. Keep in sync with the
     * manifest's queries.
     */
    val packageNames: List<String> get() = listOf(packageName) + when (this) {
        YOUTUBE -> listOf("app.revanced.android.youtube", "app.rvx.android.youtube", "app.morphe.android.youtube", "com.vanced.android.youtube")
        YOUTUBE_MUSIC -> listOf(
            "app.revanced.android.apps.youtube.music", "app.rvx.android.apps.youtube.music", "app.morphe.android.apps.youtube.music",
            "com.vanced.android.apps.youtube.music"
        )
        else -> emptyList()
    }

    companion object {
        fun fromName(name: String?): MusicService? = entries.firstOrNull { it.name == name }

        /** The service whose app, or a build patched from it, has [packageName]. */
        fun ofPackage(packageName: String?): MusicService? = entries.firstOrNull { packageName in it.packageNames }
    }
}

/** Whether [one] and [other] are the same app, or one is a build patched from the other. */
internal fun sameApp(one: String?, other: String?): Boolean = one == other || MusicService.ofPackage(one)?.packageNames?.contains(other) == true

private fun querySearchUrl(baseUrl: String, parameter: String): (String) -> String = { query ->
    baseUrl.toHttpUrl().newBuilder().addQueryParameter(parameter, query).build().toString()
}

private fun pathSearchUrl(baseUrl: String): (String) -> String = { query ->
    baseUrl.toHttpUrl().newBuilder().addPathSegment(query).build().toString()
}

internal fun searchQuery(metadata: MusicMetadata): String =
    listOf(metadata.title, metadata.artist).filter { it.isNotBlank() }.joinToString(" ")
