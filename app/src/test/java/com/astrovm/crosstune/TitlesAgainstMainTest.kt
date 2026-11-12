package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * No title reads worse than on main: each of [TitleFuzz]'s titles is read here and compared with main's readings,
 * made by `scripts/titlefuzz/baseline.py`. A reading that differs is one of [BETTER], with why it's better, or the
 * test fails. Every difference is written to `build/titles-differences.tsv` to look at.
 */
class TitlesAgainstMainTest {
    @Test
    fun noTitleReadsWorseThanOnMain() {
        val lines = GZIPInputStream(File(BASELINE).inputStream()).bufferedReader().readLines()
        assertEquals(true, lines.first().startsWith("# "))
        assertEquals(TitleFuzz.COUNT, lines.size - 1)
        val worse = mutableListOf<String>()
        val better = sortedMapOf<String, Int>()
        val differences = StringBuilder()
        TitleFuzz.titles().zip(lines.drop(1).asSequence()).forEach { (title, line) ->
            val parts = line.split(TitleFuzz.CUT)
            val cuts = TitleFuzz.cuts(title)
            assertEquals(title.text, cuts.size, parts.size - 1)
            val main = TitleFuzz.readingsIn(title, parts.first())
            val mainCuts = cuts.zip(parts.drop(1)) { cut, readings -> TitleFuzz.readingsIn(cut, readings) }
            val here = TitleFuzz.readings(title)
            TitleFuzz.FIELDS.forEachIndexed { at, field ->
                if (here[at] == main[at]) return@forEachIndexed
                val reading = Reading(title.text, field, here, main, mainCuts)
                val why = BETTER.firstOrNull { field in it.fields && it.applies(reading) }
                val shown = listOf(field, why?.name ?: "WORSE", title.text, title.channel, title.artist, "${here[at]}", "${main[at]}")
                differences.append(shown.joinToString("\t") { it.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") }).append('\n')
                if (why == null) worse += shown.joinToString(" | ") { it.replace("\n", "\\n").replace("\r", "\\r") } else better.merge(why.name, 1, Int::plus)
            }
        }
        File("build/titles-differences.tsv").writeText(differences.toString())
        println("Better than main: $better")
        assertEquals("${worse.size} titles read worse than on main", emptyList<String>(), worse.take(SHOWN))
    }

    @Test
    fun aTitleIsCutAtEachLineAfterItsSong() {
        fun cuts(text: String) = TitleFuzz.cuts(TitleFuzz.Title(text, "", "")).map { it.text }
        assertEquals(listOf("YOASOBI「アイドル」 MV", "YOASOBI「アイドル」 MV\nOfficial"), cuts("YOASOBI「アイドル」 MV\nOfficial\nVideo"))
        // After the first one with something in it, on any line, and at the first of the line breaks between two lines.
        assertEquals(listOf("A 「」 \n 『x』"), cuts("A 「」 \n 『x』\r\n\nB"))
        // None for one that doesn't close, nor for one with no line after it.
        listOf("A「x\nB", "A「x」 B").forEach { assertEquals(emptyList<String>(), cuts(it)) }
    }

    @Test
    fun nestedTagsAreTagsInTagsEachClosedByItsOwnKind() {
        assertEquals(true, nested("Song (Live [2019])", "([", ")]"))
        listOf("Song (Live) [2019]", "Song (Live [2019)]", "Song ((Live)", "Song (Live))").forEach { assertEquals(false, nested(it, "([", ")]")) }
    }

    /** One field's reading of a title here and on main, with the others, and main's of the title's cuts, as [TitleFuzz.cuts] makes them. */
    private class Reading(val title: String, val field: String, val here: List<String?>, val main: List<String?>, val mainCuts: List<List<String?>>) {
        fun here(field: String = this.field) = here[TitleFuzz.FIELDS.indexOf(field)]
        fun main(field: String = this.field) = main[TitleFuzz.FIELDS.indexOf(field)]

        /** Main's readings of [field] on the title cut at each of its lines. */
        fun mainCut(field: String = this.field) = mainCuts.map { it[TitleFuzz.SONG_FIELDS.indexOf(field)] }
    }

    /** A way a reading of one of [fields] differs from main's that is better: when it [applies]. */
    private class Better(val name: String, val fields: Set<String>, val applies: (Reading) -> Boolean)

    private companion object {
        const val BASELINE = "src/test/titlefuzz/main.tsv.gz"
        const val SHOWN = 40
        val SONGS = setOf("musicVideo", "youtubeVideo", "playerSong")

        /** Whether [text] has a tag in a tag, by [opens] and [closes], and each of those brackets closes in order, with its own kind. */
        fun nested(text: String, opens: String, closes: String) = depth(text, opens, closes) >= 2

        /** How deep [text]'s tags go, by [opens] and [closes]: -1 when a bracket doesn't close in order, with its own kind. */
        fun depth(text: String, opens: String, closes: String): Int {
            val open = StringBuilder()
            var deepest = 0
            for (char in text) {
                val kind = opens.indexOf(char)
                if (kind >= 0) {
                    open.append(closes[kind])
                    deepest = maxOf(deepest, open.length)
                } else if (char in closes) {
                    if (open.isEmpty() || open.last() != char) return -1
                    open.setLength(open.length - 1)
                }
            }
            return if (open.isEmpty()) deepest else -1
        }

        /** The song a reading names, before its artist. */
        fun song(reading: String?) = reading?.substringBefore(TitleFuzz.PART)

        val BETTER = listOf(
            // "YOASOBI「アイドル」\nOfficial Video" names its song as "YOASOBI「アイドル」" does; main named none.
            Better("lines after the song's 」", setOf("youtubeVideo", "playerSong", "bracketedSong")) { it.here() in it.mainCut() },
            // The same, as music: the video's song as main read it on a cut title, and main said it's music on the title or a cut one.
            Better("lines after the song's 」", setOf("musicVideo")) {
                val song = it.here("youtubeVideo")
                it.here() in it.mainCut() || it.here() == song && (song == it.main("youtubeVideo") || song in it.mainCut("youtubeVideo")) &&
                    (it.main() != null || it.mainCut().any { cut -> cut != null } || it.mainCut("bracketedSong").any { cut -> cut != null })
            },
            // "A「 」" names no song; main named a blank one, so a song with no name.
            Better("a blank 「 」 names no song", setOf("bracketedSong")) {
                (it.mainCut() + it.main()).any { reading -> reading != null && reading.substringAfter(TitleFuzz.PART).isBlank() }
            },
            // "A「 」" alone, which main named a song with no name and so none, is read as it is.
            Better("a blank 「 」 names no song", SONGS) { (it.mainCut("youtubeVideo") + it.main("youtubeVideo")).any { reading -> reading != null && song(reading).isNullOrBlank() } },
            // "Song (Live [2019])" is one tag; main read "(Live [2019]" as one and ")" as part of the name.
            Better("nested tags read whole", SONGS) { nested(it.title, "([", ")]") },
            Better("nested tags read whole", setOf("coverOf")) { nested(it.title, "([【", ")]】") },
            Better("nested tags read whole", setOf("chordWikiTitles")) { nested(it.title, "（(", "）)") },
            Better("nested tags read whole", setOf("outsideWords")) { nested(it.title, "([（【「『［", ")]）】」』］") },
            // "Song - Cover by Q (Live)" is by "Q"; main had "Q (Live".
            Better("a cover's artist ends before a bracket", setOf("coverOf")) {
                val main = it.main()
                val inOrder = depth(it.title, "([【", ")]】") >= 0
                val artist = main?.substringAfter(TitleFuzz.PART)?.let { by -> by.substring(0, by.indexOfAny(charArrayOf('(', '[', '【')).takeIf { at -> at >= 0 } ?: by.length).trim().ifEmpty { null } }
                inOrder && main != null && it.here() == "${song(main)}${TitleFuzz.PART}$artist"
            },
        )
    }
}
