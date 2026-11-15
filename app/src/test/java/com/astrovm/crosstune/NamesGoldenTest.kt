package com.astrovm.crosstune

import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What Crosstune takes for one song, against what `main` took (names-golden.tsv.gz, written by scripts/namescheck/golden.sh
 * from 2c24d2d4): a session with no artist, or "<unknown>" for one, as it's followed for a song by an artist or by none, a
 * title with the artist named, and titles by their words. Each pair must answer as `main` did. No change is meant: this
 * branch keeps the names as they were.
 */
@RunWith(RobolectricTestRunner::class)
class NamesGoldenTest {
    private val pairs = NamePairs.all()

    /** Each way, the pairs `main` took. */
    private val main: Map<String, Set<Pair<String, String>>> by lazy {
        val lines = checkNotNull(javaClass.classLoader?.getResourceAsStream("names-golden.tsv.gz")).use { GZIPInputStream(it).bufferedReader().readLines() }
        assertEquals("names-golden.tsv.gz is of other pairs: run scripts/namescheck/golden.sh", "pairs\t${NamePairs.fingerprint(pairs)}", lines.first())
        lines.drop(1).filter { it.isNotBlank() }.map { it.split("\t") }.groupBy({ it[0] }, { it[1] to it[2] }).mapValues { it.value.toSet() }
    }

    /** As `main` answered. */
    private fun main(way: String, pair: NamePair) = (pair.title to pair.song) in main[way].orEmpty()

    /** As Crosstune answers now, each way as NamesGoldenWriter asks it. */
    private fun now(way: String, pair: NamePair): Boolean {
        val asked = MusicMetadata(pair.song, if (way.endsWith("-blank")) "" else NamePairs.ARTIST)
        return when (way) {
            // As Playback follows a session that sends no artist, or "<unknown>" for one, for a song by an artist or by none.
            "follow", "follow-blank" -> SongNames.names(pair.title, "", asked)
            "unknown", "unknown-blank" -> SongNames.names(pair.title, "<unknown>", asked)
            "artist" -> SongNames.names(pair.title, NamePairs.ARTIST, asked)
            else -> SongNames.wordsMatch(pair.title, pair.song)
        }
    }

    @Test
    fun everyPairAnswersAsMain() {
        val wrong = mutableListOf<String>()
        for (way in listOf("follow", "unknown", "follow-blank", "unknown-blank", "artist", "words")) for (pair in pairs) {
            val now = now(way, pair)
            if (now != main(way, pair)) wrong += "${if (now) "TAKES" else "MISSES"}\t$way\t${pair.title}\t${pair.song}"
        }
        assertEquals(emptyList<String>(), wrong)
    }
}
