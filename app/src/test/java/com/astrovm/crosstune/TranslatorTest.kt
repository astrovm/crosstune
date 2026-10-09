package com.astrovm.crosstune

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.util.Locale

/** Runs under Robolectric for the real org.json and HTML decoding. */
@RunWith(RobolectricTestRunner::class)
class TranslatorTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val fake = FakeSpotify()
    private lateinit var cacheFile: File

    private fun translator(): Translator {
        if (!::cacheFile.isInitialized) cacheFile = File(folder.root, "translations.json")
        return Translator(fake.client(), LookupCache(cacheFile, Dispatchers.Unconfined), Dispatchers.Unconfined)
    }

    /** MyMemory, translating each row of what it's asked by [rows]. */
    private fun myMemory(status: Int = 200, quotaFinished: Boolean = false, rows: (String) -> String = { "[$it]" }) {
        fake.handler = { request: Request ->
            val text = request.url.queryParameter("q").orEmpty()
            val translated = text.split("\n").joinToString("\n") { rows(it) }
            val body = JSONObject()
                .put("responseData", JSONObject().put("translatedText", translated))
                .put("responseStatus", status)
                .put("quotaFinished", quotaFinished)
            FakeSpotify.html(request, body.toString())
        }
    }

    private fun translate(lines: List<String>, server: TranslationServer? = null) = runBlocking { translator().translate(lines, "es", server) }

    @Test
    fun linesAreTranslatedTogetherAndEachOnceKept() {
        myMemory()
        val lines = listOf("Night sky", "", "♪", "Stars", "Night sky")
        assertEquals(listOf("[Night sky]", null, null, "[Stars]", "[Night sky]"), translate(lines))
        // Asked once, each line once, with the app's language and the line's left for MyMemory to tell.
        val asked = fake.requestedUrls.single()
        assertEquals("Night sky\nStars", Request.Builder().url(asked).build().url.queryParameter("q"))
        assertEquals("Autodetect|es", Request.Builder().url(asked).build().url.queryParameter("langpair"))

        // Kept: asked again, even by a new translator reading the saved file, nothing is sent.
        assertEquals(listOf("[Stars]"), translate(listOf("Stars")))
        assertEquals(1, fake.requestedUrls.size)
    }

    @Test
    fun aLineAlreadyInTheLanguageIsntRepeatedUnderItself() {
        myMemory { if (it == "Hola") "hola" else "[$it]" }
        assertEquals(listOf(null, "[Hello]"), translate(listOf("Hola", "Hello")))
    }

    @Test
    fun wordsAlreadyInTheLanguageMyMemoryRefusesComeBackAsTheyWere() {
        // Asked for English into English, MyMemory says so as a 403 instead of answering.
        fake.handler = { request ->
            FakeSpotify.html(request, """{"responseData":{"translatedText":"PLEASE SELECT TWO DISTINCT LANGUAGES"},"responseDetails":"PLEASE SELECT TWO DISTINCT LANGUAGES","responseStatus":"403"}""")
        }
        assertEquals(listOf(null, null), runBlocking { translator().translate(listOf("Me and Michael", "So you think"), "en", null) })
    }

    @Test
    fun aWordInTheAppsLanguageIsLookedUpInWiktionary() {
        fake.handler = { request ->
            when (request.url.encodedPathSegments.last()) {
                "we're" -> FakeSpotify.html(
                    request,
                    """{"en":[{"partOfSpeech":"Contraction","definitions":[{"definition":""},{"definition":"<span><a href=\"/wiki/Appendix:Glossary\">Contraction</a> of <i>we</i> + <i>are</i></span>"}]}]}"""
                )
                // Some definitions come with the page's styling inside.
                "word" -> FakeSpotify.html(
                    request,
                    """{"en":[{"definitions":[{"definition":"<style data-mw-deduplicate=\"x\">.mw-parser-output .defdate{font-size:smaller}</style> a word, a term"}]}]}"""
                )
                "nothing" -> FakeSpotify.html(request, """{"en":[{"definitions":[]},{}]}""")
                "empty" -> FakeSpotify.html(request, "{}")
                "broken" -> FakeSpotify.html(request, "not json")
                else -> FakeSpotify.html(request, "{}", code = 404)
            }
        }
        assertEquals(Definition("contraction", "Contraction of we + are"), runBlocking { translator().define("We're", "en") })
        // No type said, none shown.
        assertEquals(Definition(null, "a word, a term"), runBlocking { translator().define("word", "en") })
        // Wikimedia is told who's asking.
        assertTrue("User-Agent" to "Crosstune (https://github.com/astrovm/crosstune)" in fake.requestHeaders)
        listOf("nothing", "empty", "broken", "unknown").forEach { assertNull(it, runBlocking { translator().define(it, "en") }) }
        // Only English has a dictionary to ask.
        val asked = fake.requestedUrls.size
        assertNull(runBlocking { translator().define("palabra", "es") })
        assertEquals(asked, fake.requestedUrls.size)
    }

    @Test
    fun manyLinesGoInBatchesMyMemoryTakes() {
        myMemory()
        val lines = (1..40).map { "Line number $it of the song" }
        assertEquals(lines.map { "[$it]" }, translate(lines))
        assertEquals(3, fake.requestedUrls.size)
        fake.requestedUrls.forEach { assert(Request.Builder().url(it).build().url.queryParameter("q")!!.toByteArray().size <= Translator.MYMEMORY_BYTES) }
    }

    @Test
    fun aBatchThatComesBackWithOtherRowsIsAskedLineByLine() {
        // Two lines read back as one.
        fake.handler = { request: Request ->
            val text = request.url.queryParameter("q").orEmpty()
            val translated = if ("\n" in text) "all at once" else "[$text]"
            FakeSpotify.html(request, JSONObject().put("responseData", JSONObject().put("translatedText", translated)).put("responseStatus", 200).toString())
        }
        assertEquals(listOf("[One]", "[Two]"), translate(listOf("One", "Two")))
        assertEquals(3, fake.requestedUrls.size)
    }

    @Test
    fun htmlInWhatComesBackIsReadAsText() {
        myMemory { "It&#39;s &quot;fine&quot; &amp; good" }
        assertEquals(listOf("It's \"fine\" & good"), translate(listOf("Bien")))
    }

    @Test
    fun outOfTheDaysTranslationsOrOfflineItCantTranslate() {
        myMemory(status = 429)
        assertNull(translate(listOf("Stars")))
        myMemory(quotaFinished = true)
        assertNull(translate(listOf("Stars")))
        fake.handler = { throw IOException("offline") }
        assertNull(translate(listOf("Stars")))
        fake.handler = { request -> FakeSpotify.html(request, "busy", code = 503) }
        assertNull(translate(listOf("Stars")))
        fake.handler = { request -> FakeSpotify.html(request, "<html>not json</html>") }
        assertNull(translate(listOf("Stars")))
        // A batch that falls apart, then fails line by line, fails too.
        var calls = 0
        fake.handler = { request ->
            calls++
            val body = if (calls == 1) JSONObject().put("responseData", JSONObject().put("translatedText", "one row")).put("responseStatus", 200) else JSONObject().put("responseStatus", 403)
            FakeSpotify.html(request, body.toString())
        }
        assertNull(translate(listOf("One", "Two")))
        // Nothing worth translating needs nothing asked.
        fake.requestedUrls.clear()
        assertEquals(listOf(null, null), translate(listOf("", "123")))
        assertEquals(0, fake.requestedUrls.size)
    }

    @Test
    fun aLibreTranslateServerGetsEveryLineAtOnceWithItsKey() {
        fake.handler = { request ->
            val asked = JSONObject(fake.requestBodies.last()).getJSONArray("q")
            val out = JSONArray((0 until asked.length()).map { "<${asked.getString(it)}>" })
            FakeSpotify.html(request, JSONObject().put("translatedText", out).toString())
        }
        val server = TranslationServer("https://translate.example.org", "secret")
        assertEquals(listOf("<One>", "<Two>"), runBlocking { translator().translate(listOf("One", "Two"), "pt-BR", server) })
        assertEquals("https://translate.example.org/translate", fake.requestedUrls.single())
        val body = JSONObject(fake.requestBodies.single())
        assertEquals("pt", body.getString("target"))
        assertEquals("auto", body.getString("source"))
        assertEquals("secret", body.getString("api_key"))

        // Without a key, none is sent; kept apart from what MyMemory said for the same line.
        assertEquals(listOf("<Three>"), translate(listOf("Three"), TranslationServer("https://translate.example.org")))
        assertEquals(false, JSONObject(fake.requestBodies.last()).has("api_key"))

        // Fewer lines back than went, or no address at all, is no translation.
        fake.handler = { request -> FakeSpotify.html(request, JSONObject().put("translatedText", JSONArray(listOf("only one"))).toString()) }
        assertNull(translate(listOf("Four", "Five"), server))
        assertNull(translate(listOf("Six"), TranslationServer("not an address")))
    }

    @Test
    fun theLanguageIsTheAppsAsTheServicesNameIt() {
        assertEquals("zh-CN", Translator.languageOf(Locale.SIMPLIFIED_CHINESE))
        assertEquals("pt-BR", Translator.languageOf(Locale.forLanguageTag("pt-BR")))
        assertEquals("id", Translator.languageOf(Locale.forLanguageTag("id")))
        assertEquals("id", Translator.languageOf(Locale("in")))
        assertEquals("ja", Translator.languageOf(Locale.JAPAN))
    }
}
