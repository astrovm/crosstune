package com.astrovm.crosstune

import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What Crosstune takes for one song, against what `main` took (names-golden.tsv.gz, written by scripts/namescheck/golden.sh
 * from 2c24d2d4): a session with no artist as it's followed, a title with the artist named, and titles by their words.
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

    private fun now(way: String, pair: NamePair): Boolean {
        val asked = MusicMetadata(pair.song, NamePairs.ARTIST)
        return when (way) {
            // As Playback follows a session that sends no artist.
            "follow" -> SongNames.names(pair.title, "", asked, byWords = !Songless.namesNoSong(pair.title, ""))
            "artist" -> SongNames.names(pair.title, NamePairs.ARTIST, asked)
            else -> SongNames.wordsMatch(pair.title, pair.song)
        }
    }

    /** What each version a script's mark says is, in Latin letters, as these pairs write it. */
    private val inLatin = mapOf(
        "(ライブ)" to "(Live)", "(라이브)" to "(Live)", "(カバー)" to "(Cover)", "(커버)" to "(Cover)", "(翻唱)" to "(Cover)",
        "(伴奏)" to "(Instrumental)", "(カラオケ)" to "(Karaoke)"
    )

    /** [tag] as Latin letters write it: "(ライブ) [Explicit]" is "(Live) [Explicit]". */
    private fun latin(tag: String) = inLatin.entries.fold(tag) { written, (mark, word) -> written.replace(mark, word) }

    /** The pair with each version its tags say in a script's mark written in Latin letters. */
    private fun NamePair.twin() = copy(titleTag = latin(titleTag), songTag = latin(songTag))

    /**
     * What [tag] says of the recording, past what names none, in its letters only, as "(Live)", "[Live]" and "- Live" say one thing.
     * A work it's from counts only where [other] names another.
     */
    private fun recording(tag: String, other: String): String {
        val said = NamePairs.SAME_RECORDING.fold(tag) { left, same -> left.replace(same, "") }
        val from = "(From \"Other Movie\")"
        return (if ("From" !in other) said.replace(from, "") else said).lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")
    }

    @Test
    fun everyChangeFromMainIsMeant() {
        val changed = mutableListOf<String>()
        val all = pairs.toSet()
        for (way in listOf("follow", "artist", "words")) for (pair in pairs) {
            val before = (pair.title to pair.song) in main[way].orEmpty()
            val after = now(way, pair)
            if (before == after) continue
            val twin = pair.twin()
            val meant = when {
                // Followed with no artist, never by words another name shares, "Hotel California Radio" for "Hotel California".
                way == "follow" && !after && pair.titleBase != pair.songBase -> true
                // A version a script's mark says is taken as it's taken in Latin letters, "(ライブ)" as "(Live)", which is checked as its own pair.
                twin != pair -> twin in all && after == now(way, twin)
                way != "follow" || !after -> false
                // And more only past a track number and extension, or tags that name no other recording.
                else -> pair.titleBase == pair.songBase && (recording(pair.titleTag, pair.songTag) == recording(pair.songTag, pair.titleTag) ||
                    pair.numbered && (pair.copy(numbered = false).let { it.title to it.song }) in main[way].orEmpty())
            }
            if (!meant) changed += "${if (after) "GAIN" else "LOSS"}\t$way\t${pair.title}\t${pair.song}"
        }
        assertEquals(emptyList<String>(), changed)
    }
}
