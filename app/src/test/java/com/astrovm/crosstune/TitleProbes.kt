package com.astrovm.crosstune

/**
 * The functions that read titles, as [TitleFuzz] calls them. `scripts/titlefuzz/MainTitleProbes.kt` is this file for
 * main's code, where some are reached another way: change one here, and change it there to match.
 */
internal object TitleProbes {
    fun musicVideo(title: String, channel: String) = MetadataParsers.musicVideo(title, channel)
    fun youtubeVideo(title: String, channel: String) = MetadataParsers.youtubeVideo(title, channel)
    fun playerSong(title: String, artist: String) = MetadataParsers.playerSong(title, artist)
    fun coverOf(title: String) = SongNames.coverOf(title)
    fun bareTitle(title: String) = SongNames.bareTitle(title)
    fun withoutEditionTag(title: String) = SongNames.withoutEditionTag(title)
    fun withoutFeaturing(title: String) = SongNames.withoutFeaturing(title)
    fun bracketedSong(title: String) = MetadataParsers.bracketedSong(title)?.toList()
    fun chordWikiTitles(title: String) = com.astrovm.crosstune.chordWikiTitles(title)
    fun outsideWords(title: String) = SongNames.words(outsideBrackets(title))
}
