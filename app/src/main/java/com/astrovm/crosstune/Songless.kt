package com.astrovm.crosstune

/**
 * What an app on the phone plays when it doesn't say what song it is, e.g. a radio station or a file
 * with no tags: [name] as the app shows it, the station's or the file's, or null with none; [app] by
 * package, and [label], its name; [artworkUrl] the cover it shows, e.g. the station's logo. Crosstune
 * names the song by listening, and shows this meanwhile, never as a song to open, search or report.
 */
internal data class Unnamed(val app: String, val label: String?, val name: String?, val artworkUrl: String? = null)

/** Whether what a player says it plays names no song, so the song is named by listening instead. */
internal object Songless {
    /**
     * [title] and [artist] as the app [app], by its name, says them. No song is named by no title; by a
     * title with no one named as its artist, e.g. a radio station, as VRadio names one with no artist,
     * or a file, as "Fake Song.mp3" by "<unknown>"; by the app's own name as the artist; nor, even with an
     * artist, by a track's number, a recording's file name or a file's path: "Track 01",
     * "AUD-20240101-WA0003" or "/storage/emulated/0/Music/song.mp3".
     * A title with an artist is a song, as music apps name theirs, and so is "Artist - Song" alone.
     */
    fun namesNoSong(title: String, artist: String, app: String?): Boolean {
        // Read before it's split at a dash, which "AUD-20240101-WA0003" has.
        val bare = title.trim().replace(extension, "")
        if (placeholder.any { it.matches(bare) } || path.containsMatchIn(bare)) return true
        val song = MetadataParsers.playerSong(title, ownName(artist, app)) ?: return true
        return song.artist.isBlank()
    }

    private val extension = Regex("""\.[A-Za-z0-9]{2,4}$""")

    /** Where a file is rather than what it is: "/storage/emulated/0/Music/…", "file:///…" or "content://…". */
    private val path = Regex("""^(?:/|[a-z]+://)""")

    /** [artist], or none when it's only [app]'s own name, as a radio app may put it there. */
    fun ownName(artist: String, app: String?): String = if (app != null && artist.trim().equals(app.trim(), ignoreCase = true)) "" else artist

    /** A title that names a file rather than a song: a track's number, or a recording a phone app named with its date. */
    private val placeholder = listOf(
        Regex("""(?:track|pista|faixa|piste|titel|traccia)\s*\d+""", RegexOption.IGNORE_CASE),
        Regex("""[A-Za-z]{2,10}[-_ ]\d{6,}[A-Za-z0-9_-]*""")
    )
}
