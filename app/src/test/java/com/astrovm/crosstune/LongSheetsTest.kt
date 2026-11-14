package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test

/** J-Total's sheets that open what they never close, timed as they grow (Growth.kt), apart from ChordSheetsTest's other tests. */
class LongSheetsTest {
    @Test
    fun aChordLinkThatNeverClosesTakesNoLongerThanTheLineItIsOn() {
        // Each pattern that reads a chord's name tried every way to split a long name before giving up, and
        // each tag pattern looked from every < to the line's end: 30,000 letters took seconds. Each sheet
        // below fails one of them.
        val link = """<a href="JavaScript:jump_1('../../../cd/C.html');">"""
        val sheets = { length: Int ->
            val long = "x".repeat(length)
            listOf("$link$long", "$link$long<br>abcd", "$long<br>abcd", "$link $long", "<a cd/".repeat(length / 6), "<".repeat(length), "<b".repeat(length / 2))
        }
        assertEquals(emptyList<String>(), Growth.outOfStep(sheets, 7_500, { it.take(60) }, ChordSheets::fromJTotal))
    }

    @Test
    fun aJTotalPageThatNeverClosesWhatItOpensTakesNoLongerThanItsLength() {
        // Each of these opens a style, a comment or a rule and never closes it. Looked for from each opening
        // to the page's end, they took up to two seconds.
        val pages = { length: Int ->
            listOf(
                "<style>".repeat(length / 7),
                "<!--<style>".repeat(length / 11),
                "<style>${" tt{".repeat(length / 4)}</style>",
                "<style>${"/*x".repeat(length / 3)}</style>",
                "<!--HPSTART-->".repeat(length / 14),
            )
        }
        val read = { page: String -> ChordSheets.jTotalMonospace(page) to ChordSheets.jTotalSheet(page) }
        assertEquals(emptyList<String>(), Growth.outOfStep(pages, 7_500, { it.take(40) }, read))
    }
}
