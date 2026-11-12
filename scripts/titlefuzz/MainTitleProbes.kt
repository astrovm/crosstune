package com.astrovm.crosstune

/**
 * `app/src/test/java/com/astrovm/crosstune/TitleProbes.kt` for main's code at the commit the baseline is made from,
 * where some readers are private: those are reached by their names. `baseline.py` puts this file in its place.
 */
internal object TitleProbes {
    fun musicVideo(title: String, channel: String) = MetadataParsers.musicVideo(title, channel)
    fun youtubeVideo(title: String, channel: String) = MetadataParsers.youtubeVideo(title, channel)
    fun playerSong(title: String, artist: String) = MetadataParsers.playerSong(title, artist)
    fun coverOf(title: String) = SongNames.coverOf(title)
    fun bareTitle(title: String) = SongNames.bareTitle(title)
    fun withoutEditionTag(title: String) = SongNames.withoutEditionTag(title)
    fun withoutFeaturing(title: String) = title.replace(held(SongNames::class.java, SongNames, "featuring"), "")
    fun bracketedSong(title: String) = held(MetadataParsers::class.java, MetadataParsers, "bracketedSongRegex").matchEntire(title)?.groupValues?.drop(1)

    fun chordWikiTitles(title: String) = (
        Class.forName("com.astrovm.crosstune.ChordWikiKt")
            .getDeclaredMethod("chordWikiTitles", String::class.java).apply { isAccessible = true }.invoke(null, title) as List<*>
        ).map { it as String }

    fun outsideWords(title: String) = SongNames.words(title.replace(held(ExactMatcher::class.java, null, "BRACKETED"), " "))

    private fun held(owner: Class<*>, instance: Any?, name: String) = owner.getDeclaredField(name).apply { isAccessible = true }.get(instance) as Regex
}
