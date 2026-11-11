package com.astrovm.crosstune.notices

import com.astrovm.crosstune.Notices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

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
