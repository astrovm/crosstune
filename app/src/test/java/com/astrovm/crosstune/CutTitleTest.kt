package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * [SongNames.withoutEditionTag] cuts guests, marks and tags out of a title pass after pass, and on each pass reads
 * again only near where the passes before it cut. Main's loop read the whole title again on each pass. Here titles
 * made of the pieces where that matters, cut next to each other, are read as main's loop read them.
 */
class CutTitleTest {
    @Test
    fun aWindowOfLettersHoldsALetterPastUFFFFWhole() {
        // Its last letter the first half of one, a window takes the other half too: a pattern reads a half alone as a letter.
        assertEquals("ab\uD835\uDC00", CutTitle("ab\uD835\uDC00c").letters(0, 3))
        assertEquals("ab", CutTitle("ab\uD835\uDC00c").letters(0, 2))
        assertEquals("\uD835\uDC00", CutTitle("\uD835\uDC00").letters(0, 1))
        // A half with no other half after it stays alone.
        assertEquals("a\uD835", CutTitle("a\uD835b").letters(0, 2))
        assertEquals("a\uD835", CutTitle("a\uD835").letters(0, 2))
    }

    @Test
    fun aTitleIsCutAsMainCutIt() {
        val random = Random(SEED)
        repeat(Fuzz.sampled(TITLES, FEW)) {
            val title = buildString { repeat(random.nextInt(1, MAX_PIECES)) { append(PIECES[random.nextInt(PIECES.size)]) } }
            assertEquals(title, MainLoop.withoutFeaturing(title), SongNames.withoutFeaturing(title))
            assertEquals(title, MainLoop.withoutEditionTag(title), SongNames.withoutEditionTag(title))
        }
    }

    @Test
    fun aGuestInAGuestIsCutOnePassAtATime() {
        for (title in listOf("(feat. a(feat. b))", "Song (feat. a (M/V)) - Remastered", "f(feat. a)eat. x", " f(feat. a)t. x", "Song ft. a(feat. b) - Remastered)", "x featuring(feat. a) y",
            // A tag found once a guest that ends where the last look for one started is cut.
            "Song - Remastered(feat. a(feat. b)) 2",
            // A digit at the start, a line break a guest ends before, a tag of two dashes, one that ends on another
            // recording, a dash at the start and one at the end.
            "2  ft. a", "x ft. \u0085", "x - - Remastered", "Song - Remastered Live", "- Remastered", "Song -",
            // The first digit and the last, and a tag with words of one letter, spaced like a dash.
            "0  ft. a", "9  ft. a", "Song - Remastered x y z", "Song – Remastered x y z",
            // A guest that ends before the line break at the end, of either kind, and one that's no line break.
            "x ft. a\r\n", "x ft. a\n", "x ft. a\r", "x ft. a\u0085", "x ft. a\u2028", "x ft. a\n\r", "x ft. a\r\r\n",
            // A guest whose bracket opens more than ten letters before the one in it, a guest after a digit at the
            // start that a tag follows, and dashes with a space before them only, which a tag holds.
            "(feat. a b c d e f g h(feat. b))", "[feat. a b c d e f g h(feat. b)]", "0  ft. a (Live)", "9  ft. a (Live)", "Song - Remastered -a -b",
            "Song - Remastered a- b c- d", "(feat. a b c d e f g h(feat. b)) x", "Song (feat. a b c d e f g h(feat. b))", "Song [feat. a b c d e f g h(feat. b)]",
            // A digit out of the basic plane, two letters, before a guest, a letter that isn't one, and each half alone.
            "Song \uD835\uDFD7 ft. a", "Song \uD835\uDFD7  ft. a", "Song \uD83D\uDE00 ft. a", "Song \uDFD7 ft. a", "Song \uD835 ft. a", "\uDFD7\uD835 ft. a",
        )
        ) {
            assertEquals(title, MainLoop.withoutFeaturing(title), SongNames.withoutFeaturing(title))
            assertEquals(title, MainLoop.withoutEditionTag(title), SongNames.withoutEditionTag(title))
        }
    }

    @Test
    fun aTitleTwiceAsLongTakesTwiceTheSteps() {
        // Not timed, but counted: the same each run, and quick, so mutation testing runs it before the slow tests.
        for ((name, shape) in SHAPES) {
            val short = steps(shape(LENGTH))
            val long = steps(shape(2 * LENGTH))
            // Each letter is looked at once at least, and twice the letters take about twice the steps, not four times.
            assertTrue("$name: $short steps", short >= LENGTH)
            assertTrue("$name: $short steps, then $long", long <= 3 * short)
        }
    }

    @Test
    fun afterACutItReadsAgainOnlyFromNearItToTheSpacesBeforeIt() {
        val title = "one two three four five six seven eight nine ten"
        val cut = CutTitle(title)
        cut.cut(title.indexOf("six"), title.indexOf("seven"))
        val read = mutableListOf<Int>()
        cut.cutEach(0) { at, _ -> read += at; null }
        // From ten letters before the cut, spaces aside, to the space before it, which reads on to "seven" as the
        // first look did from the space before "six". A word after a space is read from the space, not its first letter.
        assertEquals("e our ive ", read.joinToString("") { "${title[it]}" })
        assertEquals(listOf(12, 13, 15, 16, 17, 18, 20, 21, 22, 23), read)
    }

    @Test
    fun afterCutsFarApartItReadsAgainNearEach() {
        val title = "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen"
        val cut = CutTitle(title)
        cut.cut(title.indexOf("six"), title.indexOf("seven"))
        cut.cut(title.indexOf("fourteen"), title.indexOf("fifteen"))
        val read = mutableListOf<Int>()
        cut.cutEach(0) { at, _ -> read += at; null }
        // Near each, as near one: the second is read again whole, not only from where the first was left.
        assertEquals("e our ive e hirteen ", read.joinToString("") { "${title[it]}" })
        assertEquals((12..23) - setOf(14, 19) + (61..71) - setOf(63), read)
    }

    @Test
    fun aCutOfNoLettersIsRefused() {
        // It would leave cutEach cutting none from the same place without end.
        assertThrows(IllegalStateException::class.java) { CutTitle("ab").cut(1, 1) }
    }

    private fun steps(title: String) = CutTitle(title).also { SongNames.withoutEditionTag(it) }.steps

    /** Main's loop, with main's patterns, which read the whole title again on each pass. */
    private object MainLoop {
        private val featuring = phoneRegex(
            """\s*[(\[]\s*(?:feat|ft|featuring)\b[^()\[\]]*[)\]]|(?<!\d)\s+(?:feat\.?|ft\.?|featuring)\s+[^()\[\]]*?(?=\s*[(\[]|$)""",
            RegexOption.IGNORE_CASE
        )
        private val videoMark = phoneRegex("""\s*[(\[]\s*M/V\s*[)\]]|\s+M/V\b""", RegexOption.IGNORE_CASE)
        private const val EDITION_WORDS = """original|(?:album|single|short|original|radio)\s+(?:version|edit|mix)"""
        private const val TIE_IN = """主[題题][曲歌]|挿入歌|插曲|片[頭头尾]曲|\bOST\b|\btheme\s+song\b"""
        private const val IN_TAG = """(?:(?!\s[-–]\s)[^()\[\]])"""
        private val editionTag = phoneRegex(
            """(?:\s+[-–]\s+|\s*[(\[])\s*(?:$EDITION_WORDS|$IN_TAG*\b(?:remaster(?:ed|iz(?:ed|ado|ada))?|mono|stereo)\b$IN_TAG*|$IN_TAG*(?:$TIE_IN)$IN_TAG*)\s*[)\]]?$""",
            RegexOption.IGNORE_CASE
        )
        private val otherRecording = setOf("live", "remix", "acoustic", "demo", "instrumental", "unplugged", "karaoke", "cover", "session", "sessions")

        fun withoutFeaturing(title: String) = title.replace(featuring, "")

        fun withoutEditionTag(title: String): String {
            var stripped = title
            while (true) {
                val unfeatured = stripped.replace(featuring, "").replace(videoMark, "")
                val tag = editionTag.find(unfeatured)?.value.orEmpty()
                if (SongNames.words(tag).any { it in otherRecording }) return unfeatured
                val shorter = unfeatured.replace(editionTag, "")
                if (shorter == stripped || shorter.isBlank()) return stripped
                stripped = shorter
            }
        }
    }

    private companion object {
        const val LENGTH = 2_000

        /** The titles [LongTitlesTest] times for the loop that cuts guests, marks and tags, about as long as asked. */
        val SHAPES = listOf<Pair<String, (Int) -> String>>(
            "guests in guests" to { n -> "(feat. a".repeat(n / 9) + ")".repeat(n / 9) },
            "marks in marks" to { n -> "(M/V".repeat(n / 5) + ")".repeat(n / 5) },
            "guests and marks" to { n -> "(feat. a (M/V".repeat(n / 15) + "))".repeat(n / 15) },
            "guests, then words" to { n -> "(feat. a".repeat(n / 18) + ")".repeat(n / 18) + " x".repeat(n / 4) },
            "guests, words, tags" to { n -> "(feat. a".repeat(n / 18) + ")".repeat(n / 18) + " x".repeat(n / 4) + " - Remastered".repeat(n / 52) },
            "a guest, then tags" to { n -> "Song ft. a" + " - Remaster)".repeat(n / 12) },
            "tags" to { n -> " - Remastered 2012".repeat(n / 18) },
            "guests written out" to { n -> " ft. a  ".repeat(n / 8) + ")" },
            "guests after digits" to { n -> "2  ft. a".repeat(n / 8) },
        )

        const val SEED = 933
        const val TITLES = 200_000
        const val FEW = 2_000
        const val MAX_PIECES = 14

        /** Brackets, spaces, line breaks, dashes, digits, and guests, marks and tags whole and in parts that a cut can join. */
        val PIECES = listOf(
            "(", ")", "[", "]", " ", "  ", "\t", "\n", "\r\n", "\u0085", "\u2028", "-", " - ", " – ", "2", "a", "x", ".",
            "feat", "ft", "featuring", "f", "eat", "t.", "eat.", "feat. ", " ft. ", "M/V", "m/v", "M", "/V", "M/",
            "(feat. a)", " ft. a", "(M/V)", " M/V", "( M/V )", "Remastered", "Remaster", "ed", "Live", "Mono", "Album Version",
            "Original", "OST", "ı", "İ", "K",
        )
    }
}
