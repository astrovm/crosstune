package com.astrovm.crosstune

import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Writes what a checkout's [SongNames] makes of every [NamePairs] pair, for golden.sh to keep as what `main` did:
 * which pairs it took for one song, as a session with no artist was followed, with the artist named, and by words.
 * Not one of the app's tests: golden.sh copies it into a checkout of `main` and runs it there.
 */
@RunWith(RobolectricTestRunner::class)
class NamesGoldenWriter {
    @Test
    fun write() {
        val pairs = NamePairs.all()
        val lines = pairs.flatMap { pair ->
            val asked = MusicMetadata(pair.song, NamePairs.ARTIST)
            listOfNotNull(
                "follow".takeIf { SongNames.names(pair.title, "", asked) },
                "artist".takeIf { SongNames.names(pair.title, NamePairs.ARTIST, asked) },
                "words".takeIf { SongNames.wordsMatch(pair.title, pair.song) }
            ).map { "$it\t${pair.title}\t${pair.song}" }
        }
        File("build/names-golden.tsv").writeText((listOf("pairs\t${NamePairs.fingerprint(pairs)}") + lines).joinToString("\n", postfix = "\n"))
    }
}
