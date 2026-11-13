package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * [SongNames.withoutEditionTag] cuts guests, marks and tags out of a title pass after pass, and on each pass reads
 * again only near where the passes before it cut. Main's loop read the whole title again on each pass. Here titles
 * made of the pieces where that matters, cut next to each other, are read as main's loop read them.
 */
class CutTitleTest {
    @Test
    fun aTitleIsCutAsMainCutIt() {
        val random = Random(SEED)
        repeat(TITLES) {
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
        )
        ) {
            assertEquals(title, MainLoop.withoutEditionTag(title), SongNames.withoutEditionTag(title))
        }
    }

    /** Main's loop, with main's patterns, which read the whole title again on each pass. */
    private object MainLoop {
        private val featuring = Regex(
            """\s*[(\[]\s*(?:feat|ft|featuring)\b[^()\[\]]*[)\]]|(?<!\d)\s+(?:feat\.?|ft\.?|featuring)\s+[^()\[\]]*?(?=\s*[(\[]|$)""",
            RegexOption.IGNORE_CASE
        )
        private val videoMark = Regex("""\s*[(\[]\s*M/V\s*[)\]]|\s+M/V\b""", RegexOption.IGNORE_CASE)
        private const val EDITION_WORDS = """original|(?:album|single|short|original|radio)\s+(?:version|edit|mix)"""
        private const val TIE_IN = """主[題题][曲歌]|挿入歌|插曲|片[頭头尾]曲|\bOST\b|\btheme\s+song\b"""
        private const val IN_TAG = """(?:(?!\s[-–]\s)[^()\[\]])"""
        private val editionTag = Regex(
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
        const val SEED = 933
        const val TITLES = 200_000
        const val MAX_PIECES = 14

        /** Brackets, spaces, line breaks, dashes, digits, and guests, marks and tags whole and in parts that a cut can join. */
        val PIECES = listOf(
            "(", ")", "[", "]", " ", "  ", "\t", "\n", "\r\n", "\u0085", " ", "-", " - ", " – ", "2", "a", "x", ".",
            "feat", "ft", "featuring", "f", "eat", "t.", "eat.", "feat. ", " ft. ", "M/V", "m/v", "M", "/V", "M/",
            "(feat. a)", " ft. a", "(M/V)", " M/V", "( M/V )", "Remastered", "Remaster", "ed", "Live", "Mono", "Album Version",
            "Original", "OST", "ı", "İ", "K",
        )
    }
}
