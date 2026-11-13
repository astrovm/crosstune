package com.astrovm.crosstune

/**
 * Each reader's time on the long titles that took longest, at three lengths, on the engine it runs on: on an emulator,
 * ICU, as on a phone. A time that grows four times as the title doubles is time squared. `baseline.py --long` runs it,
 * on main or on any commit, as LongTitlesTest can't: on the JVM the patterns are written out and run slower.
 */
object LongTitlesProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val readers = listOf<Pair<String, (String) -> Any?>>(
            "musicVideo" to { t -> TitleProbes.musicVideo(t, "Channel") },
            "youtubeVideo" to { t -> TitleProbes.youtubeVideo(t, "Channel") },
            "playerSong" to { t -> TitleProbes.playerSong(t, "") },
            "bracketedSong" to { t -> TitleProbes.bracketedSong(t) },
            "coverOf" to { t -> TitleProbes.coverOf(t) },
            "bareTitle" to { t -> TitleProbes.bareTitle(t) },
            "withoutEditionTag" to { t -> TitleProbes.withoutEditionTag(t) },
            "withoutFeaturing" to { t -> TitleProbes.withoutFeaturing(t) },
            "chordWikiTitles" to { t -> TitleProbes.chordWikiTitles(t) },
        )
        for ((piece, end) in listOf(" - " to " | Spotify", " - Live" to " | Spotify", " HD" to "", " Lyrics" to "", "(" to ")")) {
            for (length in listOf(2_500, 5_000, 10_000)) {
                val title = piece.repeat(length / piece.length) + end
                readers.forEach { (_, read) -> read(title.take(500)) }
                println("'$piece'+'$end' $length " + readers.joinToString(" ") { (name, read) ->
                    val began = System.nanoTime(); read(title); "$name=${(System.nanoTime() - began) / 1_000_000}"
                })
            }
        }
    }
}
