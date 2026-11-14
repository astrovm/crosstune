package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Whether what's before a dash of a title is by its artist, which says the dash starts the song's name and not a tag, read
 * once along the title: here, each dash of titles made of names, separators, conjunctions, articles, marks and the letters
 * that read otherwise at the end of a word, against [SongNames.sameArtist] on the title up to it.
 */
class ArtistBeforeTest {
    @Test
    fun whatsBeforeEachDashIsReadAsSameArtistReadsIt() {
        val random = Random(SEED)
        val seen = mutableMapOf<Boolean, Int>()
        val count = Fuzz.sampled(COUNT, COUNT / Fuzz.SAMPLED)
        repeat(count) {
            val name = buildString { repeat(random.nextInt(1, MAX_PIECES)) { append(PIECES[random.nextInt(PIECES.size)]) } }
            val by = ARTISTS[random.nextInt(ARTISTS.size)]
            val before = SongNames.ArtistBefore(name, by)
            // The app asks only of the dashes that start a tag of an instrumental, so some are passed by.
            DASH.matchesIn(name).filter { random.nextInt(ASKED_OF) != 0 }.forEach { dash ->
                val theirs = SongNames.sameArtist(name.substring(0, dash.range.first), by)
                assertEquals("${name.escaped()} by $by at ${dash.range.first}", theirs, before.isTheirs(dash.range.first))
                seen.merge(theirs, 1, Int::plus)
            }
        }
        assertTrue(seen.toString(), seen.values.size == 2 && seen.values.all { it > MIN_SEEN * count / COUNT })
    }

    @Test
    fun aConjunctionEndingInASpaceOnlyPatternsSeeIsReadAsSameArtistReadsIt() {
        // A next line (U+0085) is a space to a pattern and not to trimming: "A and<NEL>" is "A" and an empty name, a dash later.
        val names = listOf(
            "A and\u0085 - x" to "A", "B, A and\u0085 - x" to "A", "A and - x" to "A", "A\u0085 - x and y" to "A",
            "B, C x - y" to "C", "B & A & - x" to "A", "D ~,B" to "Q", "B, C - x, Abcde - y" to "Abcde",
            // One of theirs read on across a dash: "qqqqqqban" and then "dx".
            "Qqqqqq Ban - dx - y" to "Bandx",
        )
        val seen = names.flatMap { (name, by) ->
            val before = SongNames.ArtistBefore(name, by)
            DASH.matchesIn(name).map { dash ->
                val theirs = SongNames.sameArtist(name.substring(0, dash.range.first), by)
                assertEquals("${name.escaped()} by $by at ${dash.range.first}", theirs, before.isTheirs(dash.range.first))
                theirs
            }
        }
        assertEquals(setOf(true, false), seen.toSet())
    }

    @Test
    fun aNameAConjunctionJoinsIsTheirsAtThatDashAndEachAfterIt() {
        assertEquals(listOf(true, true), dashes("A and B - x - y", "A"))
        assertEquals(listOf(true, true), dashes("C, A and B - x - y", "A"))
        assertEquals(listOf(false, false), dashes("C and B - x - y", "A"))
    }

    @Test
    fun aNameBeforeAConjunctionEndingInASpaceOnlyPatternsSeeIsTheirsOnlyWhenItIs() {
        assertEquals(listOf(true), dashes("A and\u0085 - x", "A"))
        assertEquals(listOf(false), dashes("B and\u0085 - x", "A"))
        assertEquals(listOf(false), dashes("C, B and\u0085 - x", "A"))
    }

    @Test
    fun aLeadingTheIsNoPartOfTheNameBeforeADash() {
        // Their name with "the" in it is found only in the letters after "The ", at each length and on to a later dash.
        assertEquals(listOf(false), dashes("The Band - x", "Theband"))
        assertEquals(listOf(false, false), dashes("The Bandxxxx - x - y", "Theband"))
        assertEquals(listOf(true), dashes("The Theband - x", "Theband"))
        assertEquals(listOf(false, true), dashes("The Banda - x Theband - y", "Theband"))
    }

    /** What's before each dash of [name] by [by], as [SongNames.ArtistBefore] reads it, checked against [SongNames.sameArtist]. */
    private fun dashes(name: String, by: String): List<Boolean> {
        val before = SongNames.ArtistBefore(name, by)
        return DASH.matchesIn(name).map { dash ->
            val theirs = SongNames.sameArtist(name.substring(0, dash.range.first), by)
            assertEquals("${name.escaped()} by $by at ${dash.range.first}", theirs, before.isTheirs(dash.range.first))
            theirs
        }.toList()
    }

    @Test
    fun aDashAfterTheArtistStartsTheSongsName() {
        assertFalse(SongNames.saysInstrumental("Band - Instrumental", "Band"))
        assertFalse(SongNames.saysInstrumental("The Band and A - Instrumental", "A"))
        assertFalse(SongNames.saysInstrumental("Band & B - Instrumental", "BandVEVO"))
        assertTrue(SongNames.saysInstrumental("Band - Instrumental - Instrumental", "Band"))
        assertTrue(SongNames.saysInstrumental("Song - Instrumental", "Band"))
        // A singer named where a tag starts, though a name before it ended there: "feat [" is one, "[x Instrumental" another.
        assertFalse(SongNames.saysInstrumental("Song feat [x Instrumental]", ""))
        assertTrue(SongNames.saysInstrumental("Song feat. A [Instrumental]", ""))
    }

    private fun String.escaped() = map { if (it.code in ' '.code..'~'.code) "$it" else "\\u%04X".format(it.code) }.joinToString("")

    private companion object {
        const val SEED = 933
        const val COUNT = 30_000
        const val MAX_PIECES = 12
        const val ASKED_OF = 3
        /** How many dashes of each kind, theirs and not, are read at least, of [COUNT] names: and that share of fewer. */
        const val MIN_SEEN = 1_000

        /** Main's dash or tilde starting a tag, as the app finds them. */
        val DASH = phoneRegex("""(?:(?<=\s)[-–]\s?|[~〜])([^-–~〜]*)""")

        val PIECES = listOf(
            "the ", "The ", "THE", "the", " and ", " y ", "and", " and", " & ", ", ", " x ", " feat. ", " ft.", "/", " - ", " – ",
            "~", "〜", " -", "- ", "Band", "Bandx", "A", "B", "Σ", "ΑΣ", "é", "́", "İ", "\u0085", " ", "\u001C", " ", "  ",
            "Inst", "x", "Bandx Band", "Ελλάς",
        )
        val ARTISTS = listOf("Band", "Bandx", "The Band", "A & B", "A and B", "a", "Bandx Band", "ΑΣ", "Σας Band", "", "-", "Insta", "x", "Theband")
    }
}
