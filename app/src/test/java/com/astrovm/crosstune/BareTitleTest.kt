package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * [SongNames.bareTitle] and a cover after a dash are read in code, as main's patterns read them. Here short titles made
 * of the pieces where they differ are read with main's patterns too: quick, so mutation testing runs it before the
 * slow [TitlesAgainstMainTest].
 */
class BareTitleTest {
    @Test
    fun aTitleIsBareAsMainMadeIt() {
        val random = Random(SEED)
        repeat(Fuzz.count(TITLES, FEW)) {
            val title = buildString { repeat(random.nextInt(1, MAX_PIECES)) { append(PIECES[random.nextInt(PIECES.size)]) } }
            assertEquals(title, Main.bareTitle(title), SongNames.bareTitle(title))
            // A cover in brackets reads tags in tags as main didn't: only one after a dash, in a title with none.
            if (title.none { it in "()[]" }) assertEquals(title, Main.coverOf(title), SongNames.coverOf(title))
        }
    }

    @Test
    fun aTagEndsBeforeALineBreakThatIsNoSpace() {
        // U+0085 ends a line for "$" but isn't a space that trimming takes: a part after a dash takes it, brackets don't.
        // On a phone "\s" reads it as a space, so it's the space after a dash too.
        for (title in listOf("Song - Live\u0085", "Song (Live)\u0085", "Song - Live (x)\u0085", "Song\u3000 (Live)", "Song -\u0085", "a - b - c\u0085",
            "a\n-\u0085", "Song (x) -\u0085", "Song - (x)\u0085", "-\u0085", "Song -\u2028",
            // A dash right before a tag in brackets has the tag's space after it, not one of its own.
            "Song - (a-b)\u0085", "Song - [a-b] (x)\u0085", "-a - b",
            // U+001F is no "\s" but trimming takes it: it goes with the spaces before a tag.
            "Song\u001F (Live)", "Song \u001F- Live", "Song\u001F\u001F [x] (y)", "\u001F (x)",
        )) {
            assertEquals(title, Main.bareTitle(title), SongNames.bareTitle(title))
        }
    }

    @Test
    fun aCoverIsReadAfterTheLastDashBeforeItsWords() {
        for (title in listOf("a\n- Cover", "- Cover", "x - Cover", "x -Cover", "x - a-b Cover", "x - a - b Cover", "x – Cover\n", "x - Cover\u0085", "x\n - y\nCover of z", "- a - Cover")) {
            assertEquals(title, Main.coverOf(title), SongNames.coverOf(title))
        }
    }

    /** Main's patterns and the loops around them. */
    private object Main {
        private val trailingTag = phoneRegex("""(?:\s*[(\[][^()\[\]]*[)\]]|\s+[-–]\s+[^-–]*)$""")
        private const val COVER_WORDS = """\bcover(?:ed)?\b|\bin the style of\b|\boriginally (?:performed )?by\b|\bmade famous by\b|カバー|歌ってみた|翻唱|커버"""
        private val coverAfterDash = phoneRegex("""\s[-–]\s[^-–]*(?:$COVER_WORDS).*$""", RegexOption.IGNORE_CASE)
        private val coverBy = phoneRegex("""(?:in the style of|originally (?:performed )?by|made famous by|cover of)\s+([^)\]】]+)""", RegexOption.IGNORE_CASE)

        fun bareTitle(title: String): String {
            var bare = title.trim()
            while (true) {
                val shorter = bare.replace(trailingTag, "").trim()
                if (shorter == bare || shorter.isEmpty()) return bare
                bare = shorter
            }
        }

        fun coverOf(title: String): SongNames.Covered? {
            val tag = coverAfterDash.find(title) ?: return null
            val song = bareTitle(title.substring(0, tag.range.first)).ifEmpty { return null }
            return SongNames.Covered(song, coverBy.find(tag.value)?.groupValues?.get(1)?.trim()?.ifEmpty { null })
        }
    }

    private companion object {
        const val SEED = 933
        const val TITLES = 100_000
        const val FEW = 2_000
        const val MAX_PIECES = 10

        /** Dashes, spaces of each kind, line breaks, brackets, and a cover's words. */
        val PIECES = listOf(
            " - ", " – ", "-", "–", " ", "  ", "\t", "\n", "\r\n", "\r", "\u0085", "\u2028", "\u3000", "(", ")", "[", "]", "(Live)",
            " (x)", "\u001F", "Cover", "cover of a", "Acoustic", "in the style of b", "a", "x", ".",
        )
    }
}
