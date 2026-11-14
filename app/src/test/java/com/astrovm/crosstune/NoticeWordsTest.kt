package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The notices' own words in each of the app's languages: what a work is, who it's by, its licence by
 * name and the notes, from notices-TAG.txt. Licence texts stay as published, and so do the names works
 * give themselves.
 */
class NoticeWordsTest {

    private val folder = File("src/main/resources/com/astrovm/crosstune/notices")

    /** Each translated language, by the tag its strings give: every app language but English. */
    private val languages = AppLanguage.tags - "en"

    private fun translations(tag: String): Map<String, Map<String, String>> {
        val file = File(folder, "notices-$tag.txt")
        assertTrue("No notices-$tag.txt", file.isFile)
        return Notices.translations(file.readText())
    }

    /**
     * Crosstune's own words in a name, a by or a licence: joining words and words that say what a work
     * is. A name with none, like "Guava" or "The LLVM Project", is the work's own and stays.
     */
    private val ownWords = Regex("\\b(and|or|with|from|named|stated|later|others|their|contributors|presets|textures|headers|annotations|code|software|decompressor|parser|domain|licence)\\b|'s\\b", RegexOption.IGNORE_CASE)

    /** Names with such words that are a work's or a group's own, kept as they write them. */
    private val keptAsWritten = setOf("Electronic Dictionary Research and Development Group", "Apache License 2.0 with LLVM Exceptions")

    /** What of each notice a person reads in Crosstune's own words, by field. */
    private fun ownFields(notice: Notice): Map<String, String> = listOfNotNull(
        ("name" to notice.name).takeIf { ownWords.containsMatchIn(notice.name) && notice.name !in keptAsWritten },
        ("by" to notice.by).takeIf { ownWords.containsMatchIn(notice.by) && notice.by !in keptAsWritten },
        ("license" to notice.license).takeIf { ownWords.containsMatchIn(notice.license) && notice.license !in keptAsWritten },
        notice.note?.let { "note" to it }
    ).toMap()

    @Test
    fun everyNoteAndEveryNameByAndLicenceInCrosstunesOwnWordsIsInEveryLanguage() {
        assertEquals(15, languages.size)
        languages.forEach { tag ->
            val translated = translations(tag)
            val missing = Notices.all.flatMap { notice -> (ownFields(notice).keys - translated[notice.name].orEmpty().keys).map { "${notice.name}: $it" } }
            assertEquals("Untranslated in notices-$tag.txt", emptyList<String>(), missing)
        }
    }

    @Test
    fun aTranslationIsOfAWorkAndAFieldThereAreAndNotTheEnglishAgain() {
        languages.forEach { tag ->
            translations(tag).forEach { (name, fields) ->
                val notice = Notices.all.firstOrNull { it.name == name }
                assertTrue("notices-$tag.txt names no work: $name", notice != null)
                val own = ownFields(checkNotNull(notice))
                assertEquals("notices-$tag.txt translates what $name has none of, or names as its own", emptySet<String>(), fields.keys - own.keys)
                fields.forEach { (field, words) -> assertNotEquals("notices-$tag.txt, $name: $field", own.getValue(field), words) }
            }
        }
    }

    /** What a note says as it is in every language: addresses, paths, files, commands and versions. */
    private fun kept(note: String): List<String> =
        note.split(' ').map { it.trimEnd('.', ',', ';', ':', ')').trimStart('(') }
            .filter { it.contains("://") || it.contains('/') || it.startsWith("--") || it == "vX.Y.Z" || Regex("[0-9]\\.[0-9]|\\.(txt|md|bin|cpp|jar)$").containsMatchIn(it) }

    @Test
    fun aTranslatedNoteKeepsItsAddressesPathsCommandsAndVersions() {
        assertEquals(
            listOf("v4.1.8", "app/src/main/cpp/", "--recursive", "--branch", "vX.Y.Z", "https://github.com/astrovm/crosstune", "CONTRIBUTING.md"),
            kept(checkNotNull(Notices.all.single { it.name == "projectM" }.note))
        )
        languages.forEach { tag ->
            val translated = translations(tag)
            Notices.all.forEach { notice ->
                val note = translated[notice.name]?.get("note") ?: return@forEach
                val lost = kept(checkNotNull(notice.note)).filterNot { it in note }
                assertEquals("notices-$tag.txt, ${notice.name}", emptyList<String>(), lost)
            }
        }
    }

    /**
     * Words a licence asks a note to say as they are, which stay in English in every language: the IJG's
     * sentence, and the LGPL's command for the source. TextsTest checks them in the English notes.
     */
    private val asWritten = mapOf(
        "Independent JPEG Group's JPEG software" to "This software is based in part on the work of the Independent JPEG Group",
        "projectM" to "git clone --recursive --branch vX.Y.Z https://github.com/astrovm/crosstune"
    )

    @Test
    fun aTranslatedNoteKeepsTheWordsItsLicenceAsksForAsWritten() {
        asWritten.forEach { (name, words) -> assertTrue(name, checkNotNull(Notices.all.single { it.name == name }.note).contains(words)) }
        languages.forEach { tag ->
            val translated = translations(tag)
            val lost = asWritten.filterNot { (name, words) -> translated[name]?.get("note")?.contains(words) == true }.keys
            assertEquals("notices-$tag.txt", emptySet<String>(), lost)
        }
    }

    @Test
    fun eachLanguagesStringsNameTheFileOfItsNotices() {
        val res = File("src/main/res")
        val tags = res.list().orEmpty().filter { it.startsWith("values") && File(res, "$it/strings.xml").isFile }.associateWith { folder ->
            val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$folder/strings.xml")).documentElement.getElementsByTagName("string")
            (0 until strings.length).map { strings.item(it) as org.w3c.dom.Element }.single { it.getAttribute("name") == "language_tag" }.textContent
        }
        assertEquals("en", tags["values"])
        assertEquals("pt-BR", tags["values-pt"])
        assertEquals("id", tags["values-in"])
        assertEquals("zh-TW", tags["values-zh-rTW"])
        assertEquals(AppLanguage.tags.sorted(), tags.values.sorted())
    }

    @Test
    fun aLanguageShowsItsWordsAndEnglishWhereAWorkHasNone() {
        val spanish = Notices.words("es")
        assertEquals("Presets de MilkDrop", spanish.getValue("MilkDrop presets").name)
        assertEquals("No indica ninguna", spanish.getValue("MilkDrop presets").license)
        assertTrue(checkNotNull(spanish.getValue("projectM").note).contains("compilá"))
        // Guava's words are its own, and it has no note.
        assertEquals(NoticeWords("Guava", "The Guava Authors", "Apache License 2.0", null), spanish.getValue("Guava"))
        assertEquals(Notices.all.map { it.name }, spanish.keys.toList())
        // English, and a language with no file, show notices.txt as it is.
        listOf("en", "eo").forEach { tag ->
            assertEquals(Notices.all.map { NoticeWords(it.name, it.by, it.license, it.note) }, Notices.words(tag).values.toList())
        }
    }

    @Test
    fun aTranslationFilesBlocksAreAWorksNameAndItsWords() {
        assertEquals(
            mapOf("One" to mapOf("name" to "Uno", "note" to "with: colon"), "Two" to emptyMap()),
            Notices.translations("# about\n\n\n  One\nname: Uno\nnote: with: colon\n# between\n \n\nTwo\n")
        )
        assertEquals(listOf(listOf("a", "b"), listOf("c")), Notices.blocks("a\n  b\n\t\nc"))
    }
}
