package com.astrovm.crosstune

/**
 * What an app plays when it doesn't say what song it is, e.g. a radio station or a file with no tags:
 * [name] as the app shows it, the station's or the file's, or null with none; [app] by package, and
 * [label], its name; [artworkUrl] the cover it shows, e.g. the station's logo; [artist] what the app has
 * where the artist goes, e.g. "<unknown>", or nothing. Crosstune names the song by listening, and shows
 * this meanwhile, never as a song to open, search or report. [away] when it plays on another device, e.g.
 * a speaker it's cast to: its bar still has its buttons, but there's nothing on the phone to listen to.
 * [said] when it says what it plays as Crosstune once took for a song, e.g. a station's name: what says
 * nothing a song could be read from, as a video that doesn't say it's music, never hides a paused song.
 */
internal data class Unnamed(
    val app: String,
    val label: String?,
    val name: String?,
    val artworkUrl: String? = null,
    private val artist: String = "",
    val away: Boolean = false,
    val said: Boolean = name != null
) {
    /** What it plays as the app names it, which its buttons and the player follow it there by, never as a song. */
    val played: MusicMetadata get() = MusicMetadata(name.orEmpty(), artist, artworkUrl = artworkUrl)
}

/**
 * What plays first, as [playsFirst] ranks the apps that play. A song by its name, [Named], or what names
 * none, [Nameless], e.g. a radio station, even one cast to a speaker, which isn't heard on the phone. What
 * the bar shows, a sleep timer pauses, and practice goes back to.
 */
internal sealed interface WhatPlays {
    data class Named(val song: Heard.Song) : WhatPlays
    data class Nameless(val playing: Unnamed) : WhatPlays
}

/** Whether what a player says it plays names no song, so the song is named by listening instead. */
internal object Songless {
    /**
     * [title] and [artist] as a player says them. No song is named by no title; by a title with no one
     * named as its artist, e.g. a radio station, as VRadio names one with no artist, or a file, as
     * "Fake Song.mp3" by "<unknown>"; nor, by no one, by a recording's file name or a file's path:
     * "AUD-20240101-WA0003" or "/storage/emulated/0/Music/song.mp3". A title with an artist is always a
     * song, as music apps name theirs, even "Track 10", "Demo 20230415" or one by an artist named as
     * the app is, and so is "Artist - Song" alone.
     */
    fun namesNoSong(title: String, artist: String): Boolean {
        // Read before it's split at a dash, which "AUD-20240101-WA0003" has.
        val bare = title.trim().replace(extension, "")
        if (MetadataParsers.noArtist(artist) && (recording.matches(bare) || path.containsMatchIn(bare))) return true
        val song = MetadataParsers.playerSong(title, artist) ?: return true
        return song.artist.isBlank()
    }

    private val extension = Regex("""\.[A-Za-z0-9]{2,4}$""")

    /** Where a file is rather than what it is: "/storage/emulated/0/Music/…", "file:///…" or "content://…". */
    private val path = Regex("""^(?:/|[a-z]+://)""")

    /** A title that names a file rather than a song: a recording a phone app named with its date, "AUD-20240101-WA0003". */
    private val recording = Regex("""[A-Za-z]{2,10}[-_ ]\d{6,}[A-Za-z0-9_-]*""")
}

/** Where an app plays: on the phone, cast to a TV by a music app, or shown from another device, e.g. a computer through KDE Connect. */
internal enum class Place { PHONE, CAST, ELSEWHERE }

/**
 * How an app that plays ranks: whether it [said] what it plays the way Crosstune once took for a song,
 * even a station's name; [where]; and [at], when it last said where it is, on its clock.
 */
internal data class Rank(val said: Boolean, val where: Place, val at: Long)

/**
 * What plays first: an app that says what it plays before one that says nothing of it, e.g. a session
 * with no title, or a video that doesn't say it's music. Then the phone's own, as a computer's player
 * says where it is every second; then the one that said so last. A station keeps the place it had when
 * it was taken for a song, though it's shown as what it is now.
 */
internal val playsFirst: Comparator<Rank> = compareBy<Rank>({ !it.said }, { it.where }).thenByDescending { it.at }
