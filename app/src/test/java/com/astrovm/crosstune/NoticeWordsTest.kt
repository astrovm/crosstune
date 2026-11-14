package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

/** The res folder of each of the app's languages, by its tag. */
internal fun valuesFolder(tag: String): String = when (tag) {
    "en" -> "values"
    // Portuguese from anywhere reads Brazil's words.
    "pt-BR" -> "values-pt"
    // Android loads Indonesian only under its old code.
    "id" -> "values-in"
    else -> "values-" + tag.replace("-", "-r")
}

/** The language_tag that [strings], a strings.xml, names. */
internal fun languageTag(strings: File): String {
    val all = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(strings).documentElement.getElementsByTagName("string")
    val tag = (0 until all.length).map { all.item(it) as org.w3c.dom.Element }.firstOrNull { it.getAttribute("name") == "language_tag" }
    return checkNotNull(tag) { "$strings has no language_tag" }.textContent
}

/** [notice]'s [field] in English, as notices.txt has it. */
private fun english(notice: Notice, field: String): String? = when (field) {
    "name" -> notice.name
    "by" -> notice.by
    "license" -> notice.license
    else -> notice.note
}

/**
 * What [notice]'s translations are made from: the first 12 hex digits of the SHA-256 of the English
 * of each field it translates, a "field: words" line each. A translation block names it in `from:`.
 */
internal fun englishOf(notice: Notice): String {
    val english = notice.translate.orEmpty().joinToString("\n") { "$it: ${english(notice, it)}" }
    return MessageDigest.getInstance("SHA-256").digest(english.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
}

/** Each work in [translated], one language's blocks, made from English that [notices] no longer have. */
internal fun stale(notices: List<Notice>, translated: Map<String, Map<String, String>>): List<String> =
    notices.filter { it.name in translated }.map { it.name to englishOf(it) }
        .filter { (name, from) -> translated.getValue(name)["from"] != from }
        .map { (name, from) -> "$name: translated from other English; translate it again, then set from: $from" }

/**
 * A word for "changed" in each translated language. CC BY-SA 4.0 asks a work's copy to say it was
 * changed, so each note of a work under it says so in every language it's shown in.
 */
internal val CHANGED = mapOf(
    "es" to "cambiad", "pt-BR" to "alterad", "de" to "verändert", "fr" to "modifié", "it" to "modificat", "nl" to "veranderd",
    "pl" to "zmienion", "ru" to "изменен", "tr" to "değiştiril", "id" to "diubah", "hi" to "बदली गई", "ja" to "変更",
    "ko" to "바뀌었", "zh-CN" to "修改", "zh-TW" to "修改"
)

/** Each "language: work" in [translated], by language, whose note doesn't say the work was changed where its licence asks. */
internal fun unsaid(notices: List<Notice>, translated: Map<String, Map<String, Map<String, String>>>): List<String> =
    translated.flatMap { (tag, blocks) ->
        notices.filter { it.license == "CC BY-SA 4.0" }
            .filterNot { blocks[it.name]?.get("note")?.contains(CHANGED.getValue(tag)) == true }
            .map { "$tag: ${it.name}" }
    }

/**
 * The notices' own words in each of the app's languages: what a work is, who it's by, its licence by
 * name and the notes, from notices-TAG.txt. Licence texts stay as published, and so do the names works
 * give themselves.
 */
class NoticeWordsTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val folder = File("src/main/resources/com/astrovm/crosstune/notices")

    /** Each translated language, by the tag its strings give: every app language but English. */
    private val languages = AppLanguage.tags - "en"

    private fun translations(tag: String): Map<String, Map<String, String>> {
        val file = File(folder, "notices-$tag.txt")
        assertTrue("No notices-$tag.txt", file.isFile)
        return Notices.translations(file.readText())
    }

    private val all by lazy { languages.associateWith(::translations) }

    /** What of each notice a person reads in Crosstune's own words, by field, as its translate: line says. */
    private fun ownFields(notice: Notice): Map<String, String?> = notice.translate.orEmpty().associateWith { english(notice, it) }

    @Test
    fun everyNoticeSaysWhichOfItsWordsAreCrosstunes() {
        val unsure = Notices.all.filter { it.translate == null }.map { it.name }
        assertEquals("Notices with no translate: line: name the fields Crosstune wrote, or none", emptyList<String>(), unsure)
        Notices.all.forEach { notice ->
            assertEquals(notice.name, emptyList<String>(), checkNotNull(notice.translate) - setOf("name", "by", "license", "note"))
            // A note is always Crosstune's words.
            assertEquals(notice.name, notice.note != null, "note" in checkNotNull(notice.translate))
        }
        val read = Notices.parse("One\nby: A\nlicense: B\ntranslate: name note\n\nTwo\nby: C\nlicense: D\ntranslate: none\n\nThree\nby: E\nlicense: F")
        assertEquals(listOf(listOf("name", "note"), emptyList(), null), read.map { it.translate })
    }

    @Test
    fun everyNoteAndEveryNameByAndLicenceInCrosstunesOwnWordsIsInEveryLanguage() {
        assertEquals(15, languages.size)
        languages.forEach { tag ->
            val translated = all.getValue(tag)
            val missing = Notices.all.flatMap { notice -> (ownFields(notice).keys - translated[notice.name].orEmpty().keys).map { "${notice.name}: $it" } }
            assertEquals("Untranslated in notices-$tag.txt", emptyList<String>(), missing)
        }
    }

    @Test
    fun aTranslationIsOfAWorkAndAFieldThereAreAndNotTheEnglishAgain() {
        languages.forEach { tag ->
            all.getValue(tag).forEach { (name, fields) ->
                val notice = Notices.all.firstOrNull { it.name == name }
                assertTrue("notices-$tag.txt names no work: $name", notice != null)
                val own = ownFields(checkNotNull(notice))
                val words = fields - "from"
                assertEquals("notices-$tag.txt translates what $name has none of, or names as its own", emptySet<String>(), words.keys - own.keys)
                words.forEach { (field, translated) -> assertNotEquals("notices-$tag.txt, $name: $field", own.getValue(field), translated) }
            }
        }
    }

    @Test
    fun everyTranslationIsOfTheEnglishNoticesTxtHasNow() {
        languages.forEach { tag -> assertEquals("notices-$tag.txt", emptyList<String>(), stale(Notices.all, all.getValue(tag))) }
    }

    @Test
    fun aTranslationOfEnglishThatChangedSinceIsStale() {
        val changed = Notices.all.map { if (it.name == "JMdict") it.copy(note = it.note + " One more sentence.") else it }
        assertEquals(
            listOf("JMdict: translated from other English; translate it again, then set from: ${englishOf(changed.first { it.name == "JMdict" })}"),
            stale(changed, all.getValue("fr"))
        )
        // A field it doesn't translate changes nothing.
        val url = Notices.all.map { if (it.name == "JMdict") it.copy(url = "https://example.com") else it }
        assertEquals(emptyList<String>(), stale(url, all.getValue("fr")))
    }

    @Test
    fun aCcBySaNoteSaysItsWorkWasChangedInEveryLanguage() {
        assertEquals(languages.toSet(), CHANGED.keys)
        assertEquals(emptyList<String>(), unsaid(Notices.all, all))
        // The French note without its first sentence, which says the word lists are changed.
        val french = all.getValue("fr").mapValues { (name, fields) ->
            if (name == "JMdict") fields + ("note" to fields.getValue("note").substringAfter("sont modifiées. ")) else fields
        }
        assertTrue(french.getValue("JMdict").getValue("note") != all.getValue("fr").getValue("JMdict").getValue("note"))
        assertEquals(listOf("fr: JMdict"), unsaid(Notices.all, all + ("fr" to french)))
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
            val translated = all.getValue(tag)
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
            val translated = all.getValue(tag)
            val lost = asWritten.filterNot { (name, words) -> translated[name]?.get("note")?.contains(words) == true }.keys
            assertEquals("notices-$tag.txt", emptySet<String>(), lost)
        }
    }

    @Test
    fun eachAppLanguagesStringsNameItsTagAndNoOtherFolderHasStrings() {
        val res = File("src/main/res")
        assertEquals(listOf("values", "values-pt", "values-in", "values-zh-rTW"), listOf("en", "pt-BR", "id", "zh-TW").map(::valuesFolder))
        AppLanguage.tags.forEach { tag -> assertEquals(tag, languageTag(File(res, "${valuesFolder(tag)}/strings.xml"))) }
        val others = res.list().orEmpty().filter { File(res, "$it/strings.xml").isFile } - AppLanguage.tags.map(::valuesFolder).toSet()
        assertEquals("Strings of no app language", emptyList<String>(), others)
    }

    @Test
    fun aStringsFileWithNoLanguageTagIsNamed() {
        val strings = File(temporary.newFolder("values-v34"), "strings.xml").apply { writeText("<resources><string name=\"app_name\">Crosstune</string></resources>") }
        val failure = assertThrows(IllegalStateException::class.java) { languageTag(strings) }
        assertEquals("$strings has no language_tag", failure.message)
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

    @Test
    fun noticesTxtIsReadTheWayItsTranslationsAre() {
        // Indented lines, and a blank line of spaces between blocks, read as they do in a translation file.
        val read = Notices.parse("  One\n  by: A\n  license: B\n \t\nTwo\nby: C\nlicense: D\n")
        assertEquals(listOf("One" to "A", "Two" to "C"), read.map { it.name to it.by })
    }
}
