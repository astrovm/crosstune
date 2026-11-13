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
        val count = Fuzz.count(TitleFuzz.COUNT, FEW)
        // The heading, the titles read, and one more while some are left out.
        val lines = GZIPInputStream(File(BASELINE).inputStream()).bufferedReader().useLines { it.take(count + 2).toList() }
        // Main read them on a phone's engine, ICU, as the app reads titles: the JVM reads spaces and case otherwise.
        assertEquals(lines.first(), true, lines.first().startsWith("# ") && ", Android " in lines.first())
        assertEquals(if (count < TitleFuzz.COUNT) count + 2 else count + 1, lines.size)
        val worse = mutableListOf<String>()
        val better = sortedMapOf<String, Int>()
        val differences = StringBuilder()
        TitleFuzz.titles().take(count).zip(lines.drop(1).asSequence()).forEach { (title, line) ->
            val parts = line.split(TitleFuzz.VARIANT)
            val variants = TitleFuzz.variants(title)
            assertEquals(title.text, variants.map { it.first }, parts.drop(1).chunked(2).map { it.first() })
            val main = read(title, parts.first())
            val mainVariants = variants.zip(parts.drop(1).chunked(2)) { (name, variant), (_, readings) -> name to read(variant, readings) }.toMap()
            val here = TitleFuzz.readings(title)
            TitleFuzz.FIELDS.forEachIndexed { at, field ->
                if (here[at] == main.whole[at]) return@forEachIndexed
                val reading = Reading(title.text, field, here, main, mainVariants)
                val why = BETTER.firstOrNull { field in it.fields && it.applies(reading) }
                val shown = listOf(field, why?.name ?: "WORSE", title.text, title.channel, title.artist, "${here[at]}", "${main.whole[at]}")
                differences.append(shown.joinToString("\t") { it.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") }).append('\n')
                if (why == null) worse += shown.joinToString(" | ") { it.replace("\n", "\\n").replace("\r", "\\r") } else better.merge(why.name, 1, Int::plus)
            }
        }
        File("build/titles-differences.tsv").writeText(differences.toString())
        println("Better than main: $better")
        assertEquals("${worse.size} titles read worse than on main", emptyList<String>(), worse.take(SHOWN))
    }

    /** Main's readings of [title] and of its cuts, from its part of a baseline line. */
    private fun read(title: TitleFuzz.Title, part: String): Readings {
        val cuts = TitleFuzz.cuts(title)
        val readings = part.split(TitleFuzz.CUT)
        assertEquals(title.text, cuts.size, readings.size - 1)
        return Readings(TitleFuzz.readingsIn(title, readings.first()), cuts.zip(readings.drop(1)) { cut, line -> TitleFuzz.readingsIn(cut, line) })
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
        assertEquals(true, nested("Song (Live [2019])", "(["))
        listOf("Song (Live) [2019]", "Song (Live [2019)]", "Song ((Live)", "Song (Live))").forEach { assertEquals(false, nested(it, "([")) }
    }

    @Test
    fun aTitleIsWrittenWithAMarkForItsTagsInTagsAndWithoutABlankSong() {
        fun variants(text: String) = TitleFuzz.variants(TitleFuzz.Title(text, "", "")).map { (name, title) -> name to title.text }
        assertEquals(
            listOf(
                "flat ([" to "Song (Live ¦2019 ¦x¦¦)", "flat ([【" to "Song (Live ¦2019 ¦x¦¦)", "flat （(" to "Song (Live [2019 ¦x¦])",
                "flat ([（【「『［" to "Song (Live ¦2019 ¦x¦¦)",
            ),
            variants("Song (Live [2019 (x)])"),
        )
        // Only by the brackets that hold one another, and one that doesn't close in order makes none.
        assertEquals(listOf("flat （(" to "Song（歌：A¦B¦）", "flat ([（【「『［" to "Song（歌：A¦B¦）"), variants("Song（歌：A（B））"))
        assertEquals(emptyList<Pair<String, String>>(), variants("Song (Live [2019)]"))
        // The song main read, again while it has only spaces: past an empty one, and on a line only spaces start.
        assertEquals(listOf("blank" to "A『x』"), variants("A「 \u3000」『 』『x』"))
        assertEquals(listOf("blank" to "A「」『x』"), variants("A「」「 」『x』"))
        assertEquals(listOf("blank" to "A\n 『x』"), variants("A\n「 」 『x』"))
        assertEquals(listOf("blank" to "A\u0085"), variants("A\u0085「 」"))
        // Main cut a video's tags first, so it may have reached the first on any line, after them.
        assertEquals(listOf("blank past tags" to "（,）\u0085[Letra]   - 「?」"), variants("（,）\u0085[Letra]  「\u00A0」 - 「?」"))
        assertEquals(listOf("blank past tags" to "A\nB"), variants("A\nB「 」"))
        // Then on as main's pattern reads: none where it's that already, nor a later one main's pattern didn't reach.
        assertEquals(listOf("blank" to "A\n\n"), variants("A「 」\n\n「 」"))
        assertEquals(listOf("blank past tags" to "A\nB\nC『 』"), variants("A\nB「 」\nC『 』"))
        // Not a song with something in it.
        listOf("「『」」", "『 「』」", "「x『 」", "A「」『x』").forEach { assertEquals(emptyList<Pair<String, String>>(), variants(it)) }
    }

    /** Main's readings of a title, [whole], and of the title cut at each of its lines, as [TitleFuzz.cuts] makes them. */
    private class Readings(val whole: List<String?>, val cuts: List<List<String?>>) {
        fun whole(field: String) = whole[TitleFuzz.FIELDS.indexOf(field)]
        fun cut(field: String) = cuts.map { it[TitleFuzz.SONG_FIELDS.indexOf(field)] }
    }

    /** One field's reading of a title here and on main, with the others, and main's of the title's [TitleFuzz.variants]. */
    private class Reading(val title: String, val field: String, val here: List<String?>, val main: Readings, val variants: Map<String, Readings>) {
        fun here(field: String = this.field) = here[TitleFuzz.FIELDS.indexOf(field)]
        fun main(field: String = this.field) = main.whole(field)
        fun variant(name: String) = variants[name]
    }

    /** A way a reading of one of [fields] differs from main's that is better: when it [applies]. */
    private class Better(val name: String, val fields: Set<String>, val applies: (Reading) -> Boolean)

    private companion object {
        const val BASELINE = "src/test/titlefuzz/main.tsv.gz"
        const val SHOWN = 40
        const val FEW = 1_000
        val SONGS = setOf("musicVideo", "youtubeVideo", "playerSong")

        /** Whether [text] has a tag in a tag, by the brackets [opens] starts, and each of those brackets closes in order, with its own kind. */
        fun nested(text: String, opens: String) = TitleFuzz.depth(text, opens) >= 2

        /** The song a reading names, before its artist. */
        fun song(reading: String?) = reading?.substringBefore(TitleFuzz.PART)

        /**
         * Whether [reading] reads [field] as main read the song of the title, or of the title cut at one of its lines, in
         * [main]: "YOASOBI「アイドル」\nOfficial Video" names its song as "YOASOBI「アイドル」" does, where main named none.
         * As music, it's the video's song as main read it so, while main said it's music, on the title or a cut one.
         */
        fun readAsOn(reading: Reading, main: Readings, field: String = reading.field): Boolean {
            val here = reading.here(field)
            if (here == main.whole(field)) return true
            if (field in TitleFuzz.SONG_FIELDS && here in main.cut(field)) return onACut(field, here)
            // As music only where a line follows the song, which main read as music on the title or a cut one.
            if (field != "musicVideo" || main.cuts.isEmpty()) return false
            val song = reading.here("youtubeVideo")
            return here == song && (song == main.whole("youtubeVideo") || song in main.cut("youtubeVideo") && onACut("youtubeVideo", song)) &&
                (main.whole(field) != null || main.cut(field).any { it != null } || main.cut("bracketedSong").any { it != null })
        }

        /**
         * Whether [reading] of [field], as main read the title cut at a line, is read so here: none, or a song that
         * [names] one. Main named none in "「|」\nQ", and its "|" alone on a line says nothing.
         */
        private fun onACut(field: String, reading: String?): Boolean {
            val song = if (field == "bracketedSong") reading?.substringAfter(TitleFuzz.PART) else song(reading)
            return reading == null || names(song.orEmpty())
        }

        /** Whether a song in 「」 or 『』 names one: anything but spaces and bars. "？", "♡" and "𠮷" do, as main named them. */
        fun names(song: String) = song.any { it != '|' && !it.isWhitespace() }

        /** [text] without each 「」 or 『』 that has only spaces in it, as a reading keeps none, trimmed. */
        fun withoutBlankPairs(text: String): String {
            var kept = text
            while (true) {
                val blank = BLANK_PAIR.find(kept) ?: return kept.trim()
                kept = kept.removeRange(blank.range)
            }
        }

        /** A 「」 or 『』 with only spaces in it, as `isBlank` reads them. */
        private val BLANK_PAIR = Regex("""[「『][\s\p{Zs}\x{1C}-\x{1F}\x{2028}\x{2029}]*[」』]""")

        val BETTER = listOf(
            Better("lines after the song's 」", setOf("musicVideo", "youtubeVideo", "playerSong", "bracketedSong")) { readAsOn(it, it.main) },
            // "A「 」『x』" names "x", as main named it with no blank 「 」; main named a song with no name.
            // Main may have reached it past the tags it cut first, as in "（,）\u0085[Letra] 「 」 - 「？」".
            Better("a blank 「 」 names no song", SONGS + "bracketedSong") { reading ->
                listOf(TitleFuzz.BLANK, TitleFuzz.BLANK_PAST_TAGS).any { name -> reading.variant(name)?.let { readAsOn(reading, it) } == true }
            },
            // "「|」 Q" is no song in 「」, and "Band - Song 『[HD] 』" is "Song" by "Band": main named a song of only a bar,
            // "|", or one with no name once it cut "[HD]", where the brackets left blank go wherever the title is cut
            // down. Here no song is read in those brackets: the title is read as one with no song in them. Where main's
            // player read none, it was for that song. A song of marks, "？" or "♡", is a song, as main read it.
            Better("a bar alone names no song", SONGS + "bracketedSong") { reading ->
                val main = reading.main()?.let { if (reading.field == "bracketedSong") it.substringAfter(TitleFuzz.PART) else song(it) }
                val bracketed = reading.main("bracketedSong")?.substringAfter(TitleFuzz.PART)
                val here = reading.here()
                (main ?: bracketed)?.let(::names) == false && if (reading.field == "bracketedSong") here == null else song(here) != main
            },
            // "「 」\rLove\n「 」" is "Love": main kept a blank 「 」 that only a trim or a cut brought to the song's line.
            Better("a blank 「 」 after a cut goes", SONGS) { reading ->
                val here = reading.here()?.split(TitleFuzz.PART)
                val main = reading.main()?.split(TitleFuzz.PART)
                here != null && main != null && here != main && here.map(String::trim) == main.map(::withoutBlankPairs)
            },
            // "Song (Live [2019])" is one tag, read as main read "Song (Live 2019)"; main read "(Live [2019]" as one and ")" as part of the name.
            Better("nested tags read whole", SONGS + "bracketedSong") { it.flatAsMain("([") },
            Better("nested tags read whole", setOf("coverOf")) { it.flatAsMain("([【") },
            Better("nested tags read whole", setOf("chordWikiTitles")) { it.flatAsMain("（(") },
            Better("nested tags read whole", setOf("outsideWords")) { it.flatAsMain("([（【「『［") },
            // "Song - Cover by Q (Live)" is by "Q"; main had "Q (Live". In a tag that holds it, "Song (Live (Cover by Q))",
            // it's main's reading with no tag in another, as "nested tags read whole", to the mark for the inner bracket.
            Better("a cover's artist ends before a bracket", setOf("coverOf")) {
                val flat = if (nested(it.title, "([【")) it.variant("${TitleFuzz.FLAT}([【")?.whole("coverOf") else null
                TitleFuzz.depth(it.title, "([【") >= 0 && listOf(it.main(), flat).any { main -> main != null && it.here() == byToABracket(main) }
            },
        )

        /** [main]'s reading of a cover, its artist to the first bracket or [TitleFuzz.MARK]. */
        private fun byToABracket(main: String): String {
            val by = main.substringAfter(TitleFuzz.PART)
            val artist = by.substring(0, by.indexOfAny(charArrayOf('(', '[', '【', TitleFuzz.MARK)).takeIf { it >= 0 } ?: by.length).trim()
            return "${song(main)}${TitleFuzz.PART}${artist.ifEmpty { null }}"
        }

        /** Whether this reading of a title with tags in tags, by [opens], is main's of it with the inner tags' brackets taken out. */
        private fun Reading.flatAsMain(opens: String) = nested(title, opens) && variant("${TitleFuzz.FLAT}$opens")?.let { readAsOn(this, it) } == true
    }
}
