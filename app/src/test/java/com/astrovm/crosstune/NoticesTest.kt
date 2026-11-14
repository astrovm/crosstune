package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The notices in **Settings** → **About** → **Licenses**: how notices.txt is read, what each says, and
 * README's table of them. Whether they cover all of the APK is the notices task's check, in the notices
 * package.
 */
class NoticesTest {

    /** The checkout, as unit tests run in app/. */
    private val root = File("..").canonicalFile

    @Test
    fun everyNoticeSaysWhoAndWhichLicenceAndItsTextsAreThere() {
        Notices.all.forEach { notice ->
            assertTrue(notice.name, notice.by.isNotBlank() && notice.license.isNotBlank())
            assertTrue(notice.name, notice.url == null || notice.url.startsWith("https://"))
            // Each named text is in the APK; a work with no licence says so, rather than having none.
            if (notice.texts.isNotEmpty()) assertTrue(notice.name, Notices.text(notice).isNotBlank()) else assertEquals(notice.name, "None stated", notice.license)
        }
        assertEquals("Names are each a work's own", Notices.all.size, Notices.all.map { it.name }.toSet().size)
    }

    @Test
    fun theJmdictNoticeSaysWhoMadeItItsLicenceAndThatItWasChanged() {
        val jmdict = Notices.all.single { it.name == "JMdict" }
        assertTrue(jmdict.by.contains("Electronic Dictionary Research and Development Group"))
        assertEquals("CC BY-SA 4.0", jmdict.license)
        assertTrue(Notices.text(jmdict).contains("Attribution-ShareAlike 4.0 International"))
        val note = checkNotNull(jmdict.note)
        assertTrue(note.contains("changed"))
        assertTrue(note.contains("https://www.edrdg.org/edrdg/licence.html"))
    }

    @Test
    fun theReadmesTableListsWhatTheAppShows() {
        val rows = File(root, "README.md").readLines().dropWhile { it != "| Work | By | License |" }.drop(2).takeWhile { it.startsWith("|") }
        val expected = Notices.all.map { "| ${if (it.url != null) "[${it.name}](${it.url})" else it.name} | ${it.by} | ${it.license} |" }
        assertEquals(expected, rows)
    }

    @Test
    fun aBlockIsANameAndItsLinesAndCommentsAreSkipped() {
        val notices = Notices.parse("# about\n\nOne\nby: Someone\nlicense: MIT License\ntext: a.txt b.txt\ncovers: maven a:b\ncovers: file c\n\n\nTwo\nby: Else\nlicense: None stated\n")
        assertEquals(
            listOf(
                Notice("One", "Someone", "MIT License", listOf("a.txt", "b.txt"), null, null, listOf("maven a:b", "file c")),
                Notice("Two", "Else", "None stated", emptyList(), null, null, emptyList())
            ),
            notices
        )
        // Blank lines before the first block and after the last, as a comment leaves them, make no block.
        assertEquals(listOf("One"), Notices.parse("# about\n\n\nOne\nby: Someone\nlicense: MIT License\n\n\n# end\n\n").map { it.name })
    }

    @Test(expected = IllegalArgumentException::class)
    fun aBlockWithNoLicenceIsAMistake() {
        Notices.parse("One\nby: Someone\n")
    }

    @Test(expected = IllegalArgumentException::class)
    fun aBlockWithNoAuthorIsAMistake() {
        Notices.parse("One\nlicense: MIT License\n")
    }

    @Test(expected = IllegalArgumentException::class)
    fun aMissingTextIsAMistake() {
        Notices.text(Notice("One", "Someone", "MIT License", listOf("nothing.txt"), null, null, emptyList()))
    }

    @Test
    fun aLicencesLinesAreJoinedIntoParagraphsAndItsListsKeptAsLines() {
        assertEquals(
            listOf("Permission is hereby granted, free of charge.", "# Authors", "* Gaute\n* Masaru", "1. Definitions.\n(a) You must give"),
            licenseParagraphs("  Permission is hereby granted,\n  free of charge.\n\n# Authors\n\n* Gaute\n* Masaru\n \n1. Definitions.\n(a) You must\n    give\n\n\n")
        )
    }

    @Test
    fun aLongParagraphIsCutAtASentenceEndElseBetweenWordsElseWhereItMust() {
        val most = LICENSE_ROW * LICENSE_ROWS
        // A sentence ends past a third of the way: cut there.
        val sentence = "a".repeat(200) + ". " + "b ".repeat(100)
        assertEquals(listOf("a".repeat(200) + ".", "b ".repeat(100).trim()), licenseUnits(sentence).map { it.text })
        // One that ends a third of the way exactly is late enough.
        val third = "a".repeat(most / 3 - 1) + ". " + "b ".repeat(150)
        assertEquals("a".repeat(most / 3 - 1) + ".", licenseUnits(third).first().text)
        // None does, or only too early: cut between words, as late as fits.
        val words = "c. " + "word ".repeat(80)
        val cut = licenseUnits(words).map { it.text }
        assertTrue(cut.all { it.length <= most })
        assertEquals(words.trim(), cut.joinToString(" "))
        assertTrue(cut.first().length > most - 5)
        // One word longer than a piece: cut inside it.
        assertEquals(listOf("x".repeat(most), "x".repeat(10)), licenseUnits("x".repeat(most + 10)).map { it.text })
    }

    @Test
    fun backClosesTheLicenceTheRemoteIsInOrTheLastOpenedAndAnOpenOneIsNotSplitAgain() {
        assertEquals("projectM", licenceToClose("text:projectM:12", listOf("JMdict", "projectM")))
        assertEquals("JMdict", licenceToClose("work:JMdict", listOf("JMdict", "projectM")))
        assertEquals("projectM", licenceToClose("url:Kuromoji", listOf("JMdict", "projectM")))
        assertEquals("projectM", licenseFocusWork("text:projectM:3"))
        assertEquals("Kuromoji", licenseFocusWork("url:Kuromoji"))
        val open = listOf("JMdict", "projectM")
        val units = open.associateWith { name -> licenseUnits(Notices.text(Notices.all.first { it.name == name })) }
        val before = Notices.all.takeWhile { it.name != "projectM" }
        val jmdict = units.getValue("JMdict")
        assertEquals(before.size + 1 + jmdict.size, licenseWorkIndex("projectM", open, units))
        LicenseSplits.clear()
        val notice = Notices.all.first { it.name == "JMdict" }
        assertEquals(licenseUnits(Notices.text(notice)), LicenseSplits.make(notice))
        assertEquals(1, LicenseSplits.times["JMdict"])
        LicenseSplits.make(notice)
        assertEquals(1, LicenseSplits.times["JMdict"])
    }

    @Test
    fun aListsItemsShareAPieceWhileItFitsAndEachPieceSaysWhereItsParagraphStartsAndEnds() {
        val list = (1..7).joinToString("\n") { "* item $it" }
        val units = licenseUnits("Before.\n\n$list")
        assertEquals(
            listOf(
                LicenseUnit("Before.", starts = true, ends = true),
                LicenseUnit((1..5).joinToString("\n") { "* item $it" }, starts = true, ends = false),
                LicenseUnit("* item 6\n* item 7", starts = false, ends = true)
            ),
            units
        )
        assertTrue(Notices.all.flatMap { licenseUnits(Notices.text(it)) }.all { licenseRows(it.text) <= LICENSE_ROWS })
    }
}
