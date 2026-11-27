package com.astrovm.crosstune

import java.util.zip.GZIPInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What Crosstune takes for one song, against what `main` took (names-golden.tsv.gz, written by scripts/main-golden.sh names
 * from dea6ebc1): a session with no artist, or "<unknown>" for one, as it's followed for a song by an artist or by none, a
 * title with the artist named, and titles by their words. A recording note must no longer supply a title's missing
 * words. The fixture declares the base titles separately, so this allowance does not depend on the new tag reader.
 */
@RunWith(RobolectricTestRunner::class)
class NamesGoldenTest {
    private val pairs = NamePairs.all()

    /** Each way, the pairs `main` took. */
    private val main: Map<String, Set<Pair<String, String>>> by lazy {
        val lines = checkNotNull(javaClass.classLoader?.getResourceAsStream("names-golden.tsv.gz")).use { GZIPInputStream(it).bufferedReader().readLines() }
        assertEquals("names-golden.tsv.gz is of other pairs: run scripts/main-golden.sh names", "pairs\t${NamePairs.fingerprint(pairs)}", lines.first())
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
    fun everyPairAnswersAsMainExceptTagsThatSuppliedMissingTitleWords() {
        val wrong = mutableListOf<String>()
        val differences = mutableListOf<String>()
        for (way in listOf("follow", "unknown", "follow-blank", "unknown-blank", "artist", "words")) for (pair in pairs) {
            val now = now(way, pair)
            val was = main(way, pair)
            val row = "${if (now) "TAKES" else "MISSES"}\t$way\t${pair.title}\t${pair.song}"
            if (now != was) differences += row
            if (now != (was && !tagSuppliedTitleWords(pair))) wrong += row
        }
        File("build/name-tag-differences.tsv").writeText(differences.joinToString("\n", postfix = "\n"))
        File("build/name-tag-unexpected.tsv").writeText(wrong.joinToString("\n", postfix = "\n"))
        assertEquals("${wrong.size} unexpected changes; full list in build/name-tag-unexpected.tsv", emptyList<String>(), wrong.take(30))
    }

    /** These whole-title rows predate the fixture's separate base and tag fields. Both directions are explicit. */
    private val writtenCorrections = listOf(
        "愛【Cover】" to "恋【Cover】", "Hello【Live】" to "Hello World【Live】", "Hello（Live）" to "Hello World（Live）",
        "Hello (Live)" to "Hello World (Live)", "Hello – Live" to "Hello World – Live", "Hello — Live" to "Hello World — Live"
    ).flatMap { (one, other) -> listOf(one to other, other to one) }.toSet()

    private fun tagSuppliedTitleWords(pair: NamePair): Boolean {
        if (pair.title to pair.song in writtenCorrections) return true
        if (pair.titleTag.isBlank() && pair.songTag.isBlank()) return false
        if (SongNames.normalize(pair.titleBase) == SongNames.normalize(pair.songBase)) return false
        val one = SongNames.words(pair.titleBase)
        val other = SongNames.words(pair.songBase)
        val shorter = if (one.size <= other.size) one else other
        return shorter.isEmpty() || shorter.size == 1 && one != other
    }
}
