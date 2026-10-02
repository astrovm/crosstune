package com.astrovm.crosstune

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Alternative frontends for a service ([via]): apps, by [packageName], that open its links, and
 * web frontends that open the same items on a site of the user's choosing. Crosstune finds the
 * item on [via] as usual, then hands the link to the app or moves it to the site.
 */
internal enum class Frontend(
    val label: String,
    val via: MusicService,
    val packageName: String? = null,
    val defaultInstance: String? = null
) {
    NEWPIPE("NewPipe", MusicService.YOUTUBE, packageName = "org.schabi.newpipe"),
    PIPEPIPE("PipePipe", MusicService.YOUTUBE, packageName = "InfinityLoop1309.NewPipeEnhanced"),
    TUBULAR("Tubular", MusicService.YOUTUBE, packageName = "org.polymorphicshade.tubular"),
    LIBRETUBE("LibreTube", MusicService.YOUTUBE, packageName = "com.github.libretube"),
    GRAYJAY("Grayjay", MusicService.YOUTUBE, packageName = "com.futo.platformplayer"),
    METROLIST("Metrolist", MusicService.YOUTUBE_MUSIC, packageName = "com.metrolist.music"),
    OUTERTUNE("OuterTune", MusicService.YOUTUBE_MUSIC, packageName = "com.dd3boh.outertune"),
    INNERTUNE("InnerTune", MusicService.YOUTUBE_MUSIC, packageName = "com.zionhuang.music"),
    RIMUSIC("RiMusic", MusicService.YOUTUBE_MUSIC, packageName = "it.fast4x.rimusic"),
    SPOTUBE("Spotube", MusicService.SPOTIFY, packageName = "oss.krtirtho.spotube"),
    INVIDIOUS("Invidious", MusicService.YOUTUBE, defaultInstance = "https://yewtu.be"),
    PIPED("Piped", MusicService.YOUTUBE, defaultInstance = "https://piped.video");

    /**
     * The same YouTube page on [instance]. Both frontends copy YouTube's paths, except that
     * Invidious searches at /search?q=. Links that aren't YouTube's are left alone.
     */
    fun onSite(url: String, instance: String): String {
        val youtube = url.toHttpUrlOrNull()?.takeIf { it.host.endsWith("youtube.com") } ?: return url
        val site = instance.toHttpUrlOrNull() ?: return url
        val search = youtube.queryParameter("search_query")
        if (this == INVIDIOUS && youtube.encodedPath == "/results" && search != null) {
            return site.newBuilder().encodedPath("/search").addQueryParameter("q", search).build().toString()
        }
        return site.newBuilder().encodedPath(youtube.encodedPath).encodedQuery(youtube.encodedQuery).build().toString()
    }

    companion object {
        /** "yewtu.be" or "https://yewtu.be/feed" both mean "https://yewtu.be"; null if it isn't a web address. */
        fun instanceOf(address: String): String? {
            val trimmed = address.trim()
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val url = withScheme.toHttpUrlOrNull()?.takeIf { "." in it.host } ?: return null
            return url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().removeSuffix("/")
        }
    }
}
