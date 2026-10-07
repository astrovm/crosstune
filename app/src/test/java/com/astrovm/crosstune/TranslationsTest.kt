package com.astrovm.crosstune

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every language has to carry the whole app, or Android silently shows English for what is missing
 * (or, with a bad placeholder, crashes while formatting). Reads the resource files directly, since
 * a test can only load one language at a time.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationsTest {

    /**
     * Resource folder to store-listing folder. Spanish and Portuguese folders also serve the other
     * countries' speakers, and Indonesian has to be `in`: Android matches it under that old code
     * (`values-id` compiles but never loads).
     */
    private val languages = mapOf(
        "values-es" to "es",
        "values-pt" to "pt-BR",
        "values-de" to "de-DE",
        "values-fr" to "fr-FR",
        "values-ru" to "ru-RU",
        "values-in" to "id",
        "values-tr" to "tr-TR",
        "values-it" to "it-IT",
        "values-ja" to "ja-JP",
        "values-ko" to "ko-KR",
        "values-zh-rCN" to "zh-CN",
        "values-hi" to "hi-IN",
        "values-pl" to "pl-PL",
        "values-nl" to "nl-NL"
    )

    private val res = File("src/main/res")
    private val store = File("../fastlane/metadata/android")
    private val placeholder = Regex("""%\d\$[sd]|%[sd]|\{query\}""")

    /** name to text, for the strings that are meant to be translated. */
    private fun strings(folder: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$folder/strings.xml"))
        val nodes = document.documentElement.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
            .filter { it.getAttribute("translatable") != "false" }
            .associate { it.getAttribute("name") to it.textContent }
    }

    private val english = strings("values")

    @Test
    fun everyLanguageHasEveryTranslatableString() {
        for (folder in languages.keys) {
            val translated = strings(folder)
            assertEquals("$folder is missing strings", emptySet<String>(), english.keys - translated.keys)
            assertEquals("$folder has strings that don't exist", emptySet<String>(), translated.keys - english.keys)
        }
    }

    @Test
    fun placeholdersSurviveTranslation() {
        for (folder in languages.keys) {
            for ((name, text) in strings(folder)) {
                assertEquals(
                    "$folder/$name",
                    placeholder.findAll(english.getValue(name)).map { it.value }.sorted().toList(),
                    placeholder.findAll(text).map { it.value }.sorted().toList()
                )
                assertTrue("$folder/$name is blank", text.isNotBlank())
            }
        }
    }

    @Test
    fun nothingIsLeftUntranslated() {
        // A few short words are the same in some languages (Playlist, Album), but a whole sentence never is.
        for (folder in languages.keys) {
            val same = strings(folder).filter { (name, text) -> text == english.getValue(name) && text.split(' ').size > 2 }
            assertEquals("$folder has English sentences", emptyMap<String, String>(), same)
        }
    }

    @Test
    fun theLyricsButtonIsNamedForTheSheetItOpens() {
        // The button opens the sheet titled lyrics_title, so it carries the same word. Every
        // language once gave it the word for songs instead, and a Spanish speaker tapped
        // "Canciones" to read a sheet titled "Letra".
        for (folder in languages.keys) {
            val translated = strings(folder)
            assertEquals(
                "$folder lyrics_button",
                translated.getValue("lyrics_title"),
                translated.getValue("lyrics_button")
            )
        }
    }

    @Test
    fun everyLanguageHasAStoreListingWithinTheStoreLimits() {
        for (folder in languages.values + "en-US") {
            val title = File(store, "$folder/title.txt").readText().trim()
            val short = File(store, "$folder/short_description.txt").readText().trim()
            val full = File(store, "$folder/full_description.txt").readText().trim()
            assertTrue("$folder title '$title'", title.isNotEmpty() && title.length <= 30)
            assertTrue("$folder short description is ${short.length} characters", short.isNotEmpty() && short.length <= 80)
            assertTrue("$folder full description is ${full.length} characters", full.isNotEmpty() && full.length <= 4000)
            // The same structure as the English listing, so no section goes missing.
            assertEquals("$folder <b> sections", "<b>".toRegex().findAll(File(store, "en-US/full_description.txt").readText()).count(), "<b>".toRegex().findAll(full).count())
            assertEquals("$folder list items", "<li>".toRegex().findAll(File(store, "en-US/full_description.txt").readText()).count(), "<li>".toRegex().findAll(full).count())
        }
    }

    @Test
    fun languagesAreOfferedInAndroidsPerAppLanguageSetting() {
        // The locale config is generated from the res folders, so the folders and the manifest have to line up.
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:localeConfig") || File("build.gradle.kts").readText().contains("generateLocaleConfig = true"))
        val folders = res.list().orEmpty().filter { it.startsWith("values-") && File(res, "$it/strings.xml").exists() }.toSet()
        assertEquals(languages.keys, folders)
    }

    @Test
    @Config(qualifiers = "es")
    fun spanishShowsSpanish() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(strings("values-es").getValue("settings_title"), app.getString(R.string.settings_title))
        assertNotEquals(english.getValue("settings_title"), app.getString(R.string.settings_title))
    }

    @Test
    @Config(qualifiers = "es-rMX")
    fun otherSpanishSpeakersGetSpanishToo() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertNotEquals(english.getValue("settings_title"), app.getString(R.string.settings_title))
    }

    @Test
    @Config(qualifiers = "pt-rPT")
    fun portugalGetsPortugueseToo() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertNotEquals(english.getValue("settings_title"), app.getString(R.string.settings_title))
    }

    @Test
    @Config(qualifiers = "in")
    fun indonesianIsFoundUnderItsOldCodeToo() {
        // Some Android versions still report Indonesian as "in", others as "id".
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(strings("values-in").getValue("settings_title"), app.getString(R.string.settings_title))
        assertNotEquals(english.getValue("settings_title"), app.getString(R.string.settings_title))
    }

    @Test
    @Config(qualifiers = "id")
    fun indonesianIsFoundUnderItsCurrentCode() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(strings("values-in").getValue("settings_title"), app.getString(R.string.settings_title))
    }

    @Test
    @Config(qualifiers = "ja")
    fun formatArgumentsWorkInTranslatedText() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertTrue(app.getString(R.string.setup_step, 2, 4).let { "2" in it && "4" in it })
        assertTrue(app.getString(R.string.open_app_link_settings_button, "Spotify").contains("Spotify"))
    }
}
