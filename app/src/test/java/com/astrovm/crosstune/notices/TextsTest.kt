package com.astrovm.crosstune.notices

import com.astrovm.crosstune.Notice
import com.astrovm.crosstune.Notices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** What a licence asks of each notice under it: words its [texts] hold, and words its [note] says. */
private class Asks(val texts: List<String>, val note: List<String> = emptyList())

private val APACHE = listOf("Apache License Version 2.0, January 2004", "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION")
private const val MIT = "Permission is hereby granted, free of charge"
private const val BSD = "Redistribution and use in source and binary forms"

/**
 * Each licence a notice names, and what it asks. A notice under a licence not here fails, so a new
 * licence gets its words before it ships.
 */
private val ASKS = mapOf(
    "Apache License 2.0" to Asks(APACHE),
    "Apache License 2.0 with LLVM Exceptions" to Asks(APACHE + "LLVM Exceptions to the Apache 2.0 License"),
    "MIT License" to Asks(listOf(MIT)),
    "BSD 3-Clause License" to Asks(listOf(BSD, "Neither the name of")),
    "BSD and Apache License 2.0" to Asks(listOf(BSD) + APACHE),
    // The LGPL asks for the source of the work as built, so the note says where it is.
    "GNU LGPL 2.1 or later" to Asks(listOf("GNU LESSER GENERAL PUBLIC LICENSE Version 2.1, February 1999", "or (at your option) any later version"), listOf("git clone", "https://github.com/astrovm/crosstune")),
    // CC BY-SA asks to say what was changed.
    "CC BY-SA 4.0" to Asks(listOf("Attribution-ShareAlike 4.0 International"), listOf("changed")),
    "CC0 1.0" to Asks(listOf("CC0 1.0 Universal")),
    "SIL Open Font License 1.1" to Asks(listOf("SIL OPEN FONT LICENSE Version 1.1")),
    "zlib License" to Asks(listOf("This software is provided 'as-is'", "The origin of this software must not be misrepresented")),
    // The IJG asks for these words in the documentation of a program that uses its code.
    "IJG License" to Asks(listOf("Independent JPEG Group"), listOf("This software is based in part on the work of the Independent JPEG Group")),
    "Public domain" to Asks(listOf("public domain")),
    "Public domain, and MIT License" to Asks(listOf("public domain", MIT)),
    "NAIST and ICOT licence" to Asks(listOf("Nara Institute of Science and Technology", "ICOT")),
    "None stated" to Asks(emptyList())
)

/** [text] with its spaces and line ends as single spaces, in lower case, as licences wrap their lines each their own way. */
private fun plain(text: String) = text.split(Regex("\\s+")).filter(String::isNotEmpty).joinToString(" ").lowercase()

/**
 * What each notice is missing of its own: who it's by, its licence, where it is, its texts, and what its
 * licence asks of its texts and its note. Checked a notice at a time, never across all of them, as many
 * share a text: a notice that loses the Apache License's text still has others that name it.
 */
internal fun missing(notices: List<Notice>, text: (Notice) -> String = Notices::text): List<String> = notices.flatMap { notice ->
    val asks = ASKS[notice.license]
    val read = runCatching { text(notice) }
    // A text that isn't there is said once, not again for each of its licence's words.
    val body = plain(read.getOrDefault(""))
    val lacks = listOfNotNull(
        "who it's by".takeIf { notice.by.isBlank() },
        "where it is".takeIf { notice.url?.startsWith("https://") != true },
        "a text it names".takeIf { read.isFailure },
        "what ${notice.license} asks: add the licence to TextsTest".takeIf { asks == null }
    ) + asks?.texts.orEmpty().filterNot { read.isFailure || plain(it) in body }.map { "\"$it\" in its texts" } +
        asks?.note.orEmpty().filterNot { plain(it) in plain(notice.note.orEmpty()) }.map { "\"$it\" in its note" }
    lacks.map { "${notice.name}: $it" }
}

/**
 * Each licence text in notices/ is the one copied from its work, byte for byte: its SHA-256 is the one
 * notice-texts.txt records with its source, and a source in this checkout, like a submodule's LICENSE, is
 * compared to the file itself. scripts/notices.py checks the sources on the web.
 */
class TextsTest {

    private val root = File("..").canonicalFile
    private val folder = File(root, "app/src/main/resources/com/astrovm/crosstune/notices")

    /** name to (SHA-256, source). */
    private val manifest = File(root, "app/src/test/resources/com/astrovm/crosstune/notice-texts.txt").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.split(Regex("\\s+"), limit = 3) }
        .associate { (name, sha, source) -> name to (sha to source) }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun everyTextHasItsSourceAndEveryNoticesTextIsOne() {
        val texts = folder.list().orEmpty().filter { it != "notices.txt" }.sorted()
        assertEquals(texts, manifest.keys.sorted())
        assertEquals(texts, Notices.all.flatMap { it.texts }.distinct().sorted())
    }

    @Test
    fun eachNoticeHasItsOwnLicenceTextAndWhatItsLicenceAsks() {
        assertEquals(emptyList<String>(), missing(Notices.all))
    }

    @Test
    fun aNoticeThatLosesAFieldIsMissingItEvenWhenAnotherNoticeKeepsTheSame() {
        fun losing(name: String, change: (Notice) -> Notice): List<String> {
            assertTrue("No notice named $name", Notices.all.any { it.name == name })
            return missing(Notices.all.map { if (it.name == name) change(it) else it })
        }
        // The Apache License's text, which other notices still name.
        assertEquals(
            listOf("OkHttp and Okio: \"Apache License Version 2.0, January 2004\" in its texts", "OkHttp and Okio: \"TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION\" in its texts"),
            losing("OkHttp and Okio") { it.copy(texts = emptyList()) }
        )
        // One of its texts, while it keeps the others.
        assertEquals(listOf("projectM: \"GNU LESSER GENERAL PUBLIC LICENSE Version 2.1, February 1999\" in its texts"), losing("projectM") { it.copy(texts = it.texts - "lgpl-2.1.txt") })
        assertEquals(listOf("Guava: a text it names"), losing("Guava") { it.copy(texts = it.texts + "nothing.txt") })
        assertEquals(listOf("Guava: who it's by"), losing("Guava") { it.copy(by = " ") })
        assertEquals(listOf("Guava: where it is"), losing("Guava") { it.copy(url = null) })
        assertEquals(listOf("Guava: where it is"), losing("Guava") { it.copy(url = "http://github.com/google/guava") })
        assertEquals(listOf("Guava: what Apache License 2.0 or later asks: add the licence to TextsTest"), losing("Guava") { it.copy(license = "Apache License 2.0 or later") })
        // The notes the licences ask for: what was changed, where the source is, and the IJG's words.
        assertEquals(listOf("JMdict: \"changed\" in its note"), losing("JMdict") { it.copy(note = null) })
        assertEquals(listOf("projectM: \"git clone\" in its note", "projectM: \"https://github.com/astrovm/crosstune\" in its note"), losing("projectM") { it.copy(note = "Built from source.") })
        assertEquals(listOf("Independent JPEG Group's JPEG software: \"This software is based in part on the work of the Independent JPEG Group\" in its note"), losing("Independent JPEG Group's JPEG software") { it.copy(note = null) })
    }

    @Test
    fun everyTextIsTheBytesCopiedFromItsSource() {
        val changed = manifest.filter { (name, entry) -> sha256(File(folder, name).readBytes()) != entry.first }.keys
        assertEquals("Texts that differ from what notice-texts.txt records: copy them again with scripts/notices.py", emptySet<String>(), changed)
    }

    @Test
    fun aTextFromThisCheckoutIsTheFileItself() {
        val local = manifest.filterValues { (_, source) -> !source.contains(':') }
        assertTrue(local.size >= 10)
        local.forEach { (name, entry) ->
            val (path, span) = entry.second.split('#').let { it[0] to it.getOrNull(1) }
            val bytes = File(root, path).readBytes()
            val expected = if (span == null) bytes else {
                val (first, last) = Regex("\\d+").findAll(span).map { it.value.toInt() }.toList()
                (String(bytes, Charsets.ISO_8859_1).split("\n").subList(first - 1, last).joinToString("\n") + "\n").toByteArray(Charsets.ISO_8859_1)
            }
            assertEquals(name, sha256(expected), sha256(File(folder, name).readBytes()))
        }
    }
}
