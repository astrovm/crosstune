package com.astrovm.crosstune

import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What Crosstune takes for one song, against what `main` took (names-golden.tsv.gz, written by scripts/namescheck/golden.sh
 * from 2c24d2d4): a session with no artist, or "<unknown>" for one, as it's followed for a song by an artist or by none, a
 * title with the artist named, and titles by their words. Each pair must answer as `main` did, but where a rule below says it changes, and then as it says.
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

    private fun now(way: String, pair: NamePair): Boolean {
        val asked = MusicMetadata(pair.song, if (way.endsWith("-blank")) "" else NamePairs.ARTIST)
        return when (way) {
            // As Playback follows a session that sends no artist, or "<unknown>" for one, for a song by an artist or by none.
            "follow", "unknown", "follow-blank", "unknown-blank" ->
                artistOf(way).let { artist -> SongNames.names(pair.title, artist, asked, byWords = !Songless.namesNoSong(pair.title, artist)) }
            "artist" -> SongNames.names(pair.title, NamePairs.ARTIST, asked)
            else -> SongNames.wordsMatch(pair.title, pair.song)
        }
    }

    private fun artistOf(way: String) = if (way.startsWith("unknown")) "<unknown>" else ""

    /** What each version a script's mark says is, in Latin letters, as these pairs write it. */
    private val inLatin = mapOf(
        "(ライブ)" to "(Live)", "(라이브)" to "(Live)", "(カバー)" to "(Cover)", "(커버)" to "(Cover)", "(翻唱)" to "(Cover)",
        "(伴奏)" to "(Instrumental)", "(カラオケ)" to "(Karaoke)", "(ピアノ弾いてみた)" to "(Piano Cover)"
    )

    /** [tag] as Latin letters write it: "(ライブ) [Explicit]" is "(Live) [Explicit]". */
    private fun latin(tag: String) = inLatin.entries.fold(tag) { written, (mark, word) -> written.replace(mark, word) }

    /** The pair with each version its tags say in a script's mark written in Latin letters. */
    private fun NamePair.twin() = copy(titleTag = latin(titleTag), songTag = latin(songTag))

    /** The two names alone, with no tag, number or extension. */
    private fun NamePair.names() = NamePair(titleBase, "", false, songBase, "")

    /**
     * What [tag] says of the recording, past what names none, in its letters only, as "(Live)", "[Live]" and "- Live" say one thing.
     * A work it's from counts only where [other] names another.
     */
    private fun recording(tag: String, other: String): String {
        val said = (NamePairs.SAME_RECORDING + remasters).fold(tag) { left, same -> left.replace(same, "") }
        val from = "(From \"Other Movie\")"
        return (if ("From" !in other) said.replace(from, "") else said).lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")
    }

    /** Remasters, which name no other recording either, as `same` has always taken them off. */
    private val remasters = listOf("(Remastered 2011)", "- 2011 Remaster")

    /** The two names are one, but for their accents and case: "Canción" is "Cancion". */
    private fun NamePair.oneName() = SongNames.normalize(titleBase) == SongNames.normalize(songBase)

    /** What [tag] adds after each dash in it: "- Live - Explicit" adds "Live" and "Explicit". */
    private fun dashed(tag: String) = tag.split(Regex("""\s*[-–—]\s+""")).drop(1).map(String::trim)

    /** What Crosstune must answer: `main`'s answer, past the changes this list makes, each with why. */
    private fun meant(way: String, pair: NamePair, all: Set<NamePair>): Boolean {
        val twin = pair.twin()
        // 1. A version a script's mark says is taken as it's taken in Latin letters, "(ライブ)" as "(Live)". `main` had no word for it.
        if (twin != pair) return twin in all && meant(way, twin, all)
        // 2. A session with "<unknown>" for its artist is followed for a song by an artist as `main` followed it, as no artist
        //    is "<unknown>".
        if (way == "unknown") return main(way, pair)
        // 3. Followed when it names no song, by its title as a file or a store names it, never by words another name shares:
        //    "Hotel California Radio" isn't "Hotel California". More than `main` only past a track number and an extension, or
        //    tags that name no other recording. For a song by no artist too, and in a session with "<unknown>" for one.
        if (way in listOf("follow", "follow-blank", "unknown-blank") && Songless.namesNoSong(pair.title, artistOf(way))) {
            if (!pair.oneName()) return false
            return main(way, pair) || recording(pair.titleTag, pair.songTag) == recording(pair.songTag, pair.titleTag) ||
                pair.numbered && main(way, pair.copy(numbered = false))
        }
        // 4. Issue #1009: a tag's words never make two names one. Two names `main` didn't take alone, it no longer takes with
        //    their tags: "Hello (Live)" isn't "Hello World (Live)", nor "7 Rings [Explicit]" "Rings [Explicit]".
        // 5. But what one adds after a dash may be the other's name, as a video names a song after its band: "Live Forever - Live"
        //    is "Live" by Live Forever. Taken as `main` took it.
        if (dashed(pair.titleTag).any { SongNames.same(it, pair.songBase) } || dashed(pair.songTag).any { SongNames.same(it, pair.titleBase) }) {
            return main(way, pair)
        }
        if (!pair.oneName()) return main(way, pair) && main(way, pair.names())
        return main(way, pair)
    }

    @Test
    fun everyPairAnswersAsMainOrAsAChangeMeantSays() {
        val all = pairs.toSet()
        val wrong = mutableListOf<String>()
        for (way in listOf("follow", "unknown", "follow-blank", "unknown-blank", "artist", "words")) for (pair in pairs) {
            val after = now(way, pair)
            if (after != meant(way, pair, all)) wrong += "${if (after) "TAKES" else "MISSES"}\t$way\t${pair.title}\t${pair.song}\tmain ${main(way, pair)}"
        }
        assertEquals(emptyList<String>(), wrong)
    }
}
