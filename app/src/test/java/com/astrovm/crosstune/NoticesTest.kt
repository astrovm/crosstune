package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every work by others in the APK has a notice in **Settings** → **About** → **Licenses**: each library
 * in the release APK, as Gradle's `releaseLibraries` lists it, each submodule built into it, and each
 * bundled file that isn't Crosstune's own. A new one fails here until its notice is added, and a
 * notice's `covers:` line that covers nothing fails too, so the list never claims what isn't there.
 */
class NoticesTest {

    /** The checkout, as unit tests run in app/. */
    private val root = File("..").canonicalFile

    /** What the release APK is made of, by kind: maven, submodule and file. */
    private val parts: Map<String, List<String>> = mapOf(
        "maven" to File(checkNotNull(System.getProperty("notices.libraries")) { "Run through Gradle, which lists the libraries" }).readLines().filter(String::isNotBlank),
        "submodule" to File(root, ".gitmodules").readLines().map(String::trim).filter { it.startsWith("path = ") }.map { it.removePrefix("path = ") },
        "file" to listOf("app/src/main/assets", "app/src/main/resources").flatMap { folder ->
            File(root, folder).walkTopDown().filter(File::isFile).map { it.relativeTo(root).invariantSeparatorsPath }.toList()
        }
    )

    /** Crosstune's own bundled files, which need no notice: lists it made, and these notices themselves. */
    private val own = listOf(
        "file app/src/main/assets/milkdrop-kinds.txt",
        // Widths measured in a browser, numbers rather than the fonts themselves.
        "file app/src/main/resources/com/astrovm/crosstune/jtotal-widths*.txt",
        "file app/src/main/resources/com/astrovm/crosstune/notices/*"
    )

    private val covers = Notices.all.flatMap { notice -> notice.covers.map { line -> line to notice.name } }

    /** Whether [line], "kind pattern", covers [part] of that kind; * is any part of a name, ** any path. */
    private fun covers(line: String, kind: String, part: String): Boolean {
        val (lineKind, pattern) = line.split(' ', limit = 2)
        val regex = pattern.split("**").joinToString(".*") { piece -> piece.split('*').joinToString("[^/]*") { Regex.escape(it) } }
        return lineKind == kind && Regex(regex).matches(part)
    }

    private fun uncovered(kind: String) = parts.getValue(kind).filter { part ->
        (covers.map { it.first } + own).none { covers(it, kind, part) }
    }

    @Test
    fun everyLibraryInTheReleaseApkHasANotice() {
        assertTrue("Gradle listed no libraries", parts.getValue("maven").isNotEmpty())
        assertEquals("Libraries with no notice in notices/notices.txt", emptyList<String>(), uncovered("maven"))
    }

    @Test
    fun everySubmoduleHasANotice() {
        assertEquals(listOf("third_party/projectm", "third_party/whisper.cpp"), parts.getValue("submodule"))
        assertEquals("Submodules with no notice in notices/notices.txt", emptyList<String>(), uncovered("submodule"))
    }

    @Test
    fun everyBundledFileIsCrosstunesOwnOrHasANotice() {
        assertTrue("app/src/main/resources/com/astrovm/crosstune/jmdict-readings.txt" in parts.getValue("file"))
        assertEquals("Bundled files with no notice in notices/notices.txt", emptyList<String>(), uncovered("file"))
    }

    @Test
    fun everyCoversLineCoversSomethingInTheApk() {
        val stale = (covers.map { (line, name) -> "$name: $line" to line } + own.map { "own: $it" to it }).filter { (_, line) ->
            parts.none { (kind, list) -> list.any { covers(line, kind, it) } }
        }.map { it.first }
        assertEquals("Lines that cover nothing in the APK", emptyList<String>(), stale)
    }

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
}
