package com.astrovm.crosstune

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.Response
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
import java.net.URLDecoder
import java.util.Locale

/**
 * Runs under Robolectric for the real org.json and HTML decoding. Google Translate turns these away
 * unless a test says otherwise, so MyMemory translates them.
 */
@RunWith(RobolectricTestRunner::class)
class TranslatorTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val fake = FakeSpotify()
    private lateinit var cacheFile: File

    /** Google Translate; turned away, as when asked too much, unless a test says otherwise, so MyMemory translates. */
    private var google: (Request) -> Response = { request -> FakeSpotify.html(request, "<html>Sorry...</html>", code = 429) }

    /** MyMemory, and Wiktionary. */
    private var others: (Request) -> Response = { request -> FakeSpotify.html(request, "") }

    private fun translator(): Translator {
        if (!::cacheFile.isInitialized) cacheFile = File(folder.root, "translations.json")
        fake.handler = { request -> if (request.url.host == GOOGLE_HOST) google(request) else others(request) }
        return Translator(fake.client(), LookupCache(cacheFile, Dispatchers.Unconfined), Dispatchers.Unconfined)
    }

    /** Google, translating each line it's asked into [row]. */
    private fun googleTranslates(row: (String) -> String = { "<$it>" }) {
        google = { request -> FakeSpotify.html(request, JSONArray(asked(request).map { JSONArray().put(row(it)).put("en") }).toString()) }
    }

    /** The lines [request] asked Google, each its own "q". */
    private fun asked(request: Request): List<String> =
        FakeSpotify.bodyOf(request).split("&").map { URLDecoder.decode(it.removePrefix("q="), "UTF-8") }

    /** What was asked of Google, and of MyMemory. */
    private fun googleAsked() = fake.requestedUrls.filter { GOOGLE_HOST in it }
    private fun myMemoryAsked() = fake.requestedUrls.filter { "api.mymemory.translated.net" in it }

    /** MyMemory, translating each row of what it's asked by [rows]. */
    private fun myMemory(status: Int = 200, quotaFinished: Boolean = false, rows: (String) -> String = { "[$it]" }) {
        others = { request: Request ->
            val text = request.url.queryParameter("q").orEmpty()
            val translated = text.split("\n").joinToString("\n") { rows(it) }
            val body = JSONObject()
                .put("responseData", JSONObject().put("translatedText", translated))
                .put("responseStatus", status)
                .put("quotaFinished", quotaFinished)
            FakeSpotify.html(request, body.toString())
        }
    }

    private fun translate(lines: List<String>) = runBlocking { translator().translate(lines, "es") }

    @Test
    fun googleTranslatesASongsLinesAtOnceAndEachIsKept() {
        googleTranslates { if (it == "Hola") "Hola" else "<$it>" }
        val lines = listOf("Night sky", "", "♪", "Hola", "Stars", "Night sky")
        // A line already in the language isn't repeated under itself.
        assertEquals(listOf("<Night sky>", null, null, null, "<Stars>", "<Night sky>"), translate(lines))
        // Asked once, each line once and apart, into the app's language, with the line's left for Google to tell.
        val url = Request.Builder().url(googleAsked().single()).build().url
        assertEquals(listOf("dict-chrome-ex", "auto", "es"), listOf("client", "sl", "tl").map(url::queryParameter))
        assertEquals("q=Night+sky&q=Hola&q=Stars", fake.requestBodies.single())
        assertEquals(emptyList<String>(), myMemoryAsked())
        // Kept: asked again, even by a new translator reading the saved file, nothing is sent.
        assertEquals(listOf("<Stars>"), translate(listOf("Stars")))
        assertEquals(1, fake.requestedUrls.size)
    }

    @Test
    fun spokenKoreanTheServicesMisreadIsAskedAsItsWritten() {
        googleTranslates()
        val lines = listOf("암만 봐도 넌 내 거야", "암만해도 난 네가 좋아", "글구 넌 갔어", "멀 원해", "멀리 가지 마", "암만이")
        // Each kept, and shown, under the line as it's sung; only a word of its own is written out.
        assertEquals(lines.map { "<$it>" }.toMutableList().apply {
            this[0] = "<아무리 봐도 넌 내 거야>"; this[1] = "<아무래도 난 네가 좋아>"; this[2] = "<그리고 넌 갔어>"; this[3] = "<뭘 원해>"
        }, translate(lines))
        // MyMemory too, together and, when its rows don't come back apart, line by line.
        google = { request -> FakeSpotify.html(request, "<html>Sorry...</html>", code = 429) }
        myMemory()
        assertEquals(listOf("[아무리 울어도]", "[그리고 울어]"), translate(listOf("암만 울어도", "글구 울어")))
        others = { request ->
            val text = request.url.queryParameter("q").orEmpty()
            val rows = if ("\n" in text) "together" else "[$text]"
            FakeSpotify.html(request, JSONObject().put("responseData", JSONObject().put("translatedText", rows)).put("responseStatus", 200).toString())
        }
        assertEquals(listOf("[아무리 웃어도]", "[그리고 울어]"), translate(listOf("암만 웃어도", "글구 울어")))
    }

    /** Google, reading each line asked as in [readAs], or as English, and translating it as asked from what it read: "<line>" from auto, "{line}" from Yoruba. */
    private fun googleReads(readAs: (String) -> String = { "en" }) {
        google = { request ->
            val lines = asked(request)
            val rows = if (request.url.queryParameter("sl") == "yo") JSONArray(lines.map { "{$it}" }) else JSONArray(lines.map { JSONArray().put("<$it>").put(readAs(it)) })
            FakeSpotify.html(request, rows.toString())
        }
    }

    private fun yorubaAsked() = googleAsked().filter { "sl=yo" in it }

    @Test
    fun aLineWithYorubaLettersGoogleTookForEnglishIsAskedAgainAsYoruba() {
        googleReads { if (it.startsWith("Ọmọ ìyá")) "yo" else "en" }
        val lines = listOf("I'm listening to Westlife, ṣ'o mo?", "Keep it moving, rọra baddie", "Ọmọ ìyá mi", "Buy you Dom Pérignon")
        // Written with the dot apart, as some lyrics are, it's still Yoruba's letter.
        assertEquals(listOf("{I'm listening to Westlife, ṣ'o mo?}", "{Keep it moving, rọra baddie}", "<Ọmọ ìyá mi>", "<Buy you Dom Pérignon>", "{E\u0323 jo\u0323\u0301o\u0323\u0301}"),
            translate(lines + "E\u0323 jo\u0323\u0301o\u0323\u0301"))
        // Only those, all at once; the line Google read as Yoruba, and the one with only an accent English writes too, aren't.
        assertEquals(1, yorubaAsked().size)
        assertEquals(2, googleAsked().size)
    }

    @Test
    fun aLineGoogleAnsweredWithNothingIsAskedAgain() {
        var blanks = 1
        googleTranslates { line -> if (line == "ਚੰਗੇਆ ਨਾ" && blanks-- > 0) "" else "<$line>" }
        assertEquals(listOf("<ਚੰਗੇਆ ਨਾ>", "<ਸ਼ਾਮ>"), translate(listOf("ਚੰਗੇਆ ਨਾ", "ਸ਼ਾਮ")))
        assertEquals(2, googleAsked().size)
        // Nothing twice, it isn't kept as nothing, and is asked again the next time.
        blanks = 2
        cacheFile.delete()
        assertEquals(listOf(null, "<ਸ਼ਾਮ>"), runBlocking { translator().translate(listOf("ਚੰਗੇਆ ਨਾ", "ਸ਼ਾਮ"), "es") })
        assertEquals(listOf("<ਚੰਗੇਆ ਨਾ>", "<ਸ਼ਾਮ>"), translate(listOf("ਚੰਗੇਆ ਨਾ", "ਸ਼ਾਮ")))
    }

    @Test
    fun aLineKeptBlankIsAskedAgain() {
        // As Google's nothing once was kept.
        translator()
        cacheFile.writeText(JSONArray().put(JSONArray().put("google|es|ਚੰਗੇਆ ਨਾ").put("")).toString())
        googleTranslates()
        assertEquals(listOf("<ਚੰਗੇਆ ਨਾ>"), translate(listOf("ਚੰਗੇਆ ਨਾ")))
        assertEquals(1, googleAsked().size)
    }

    @Test
    fun vietnameseIsntAskedAgainAsYoruba() {
        // Vietnamese writes ẹ and ọ too, and Google reads it as Vietnamese.
        googleReads { "vi" }
        assertEquals(listOf("<Em đẹp lắm>", "<Anh vẫn đợi em trong mộng>"), translate(listOf("Em đẹp lắm", "Anh vẫn đợi em trong mộng")))
        assertTrue(yorubaAsked().isEmpty())
    }

    @Test
    fun aLineMixingEnglishWithTheSongsLanguageIsAskedAgainFromIt() {
        val song = listOf("Em đẹp lắm", "Anh vẫn đợi em", "Em vẫn chờ anh", "Anh yêu em", "Em nhớ anh", "Nơi ấy em chờ (I'm in love)", "Lọ mọ (in love)", "You′re so far away", "Buy you Dom Pérignon")
        googleReads { if (it.startsWith("Em") || it.startsWith("Anh")) "vi" else "en" }
        val auto = google
        google = { request -> if (request.url.queryParameter("sl") == "vi") FakeSpotify.html(request, JSONArray(asked(request).map { "(vi) $it" }).toString()) else auto(request) }
        // Only the lines with letters English doesn't write, all at once; ọ is Vietnamese's here, not Yoruba's.
        assertEquals(song.take(5).map { "<$it>" } + listOf("(vi) Nơi ấy em chờ (I'm in love)", "(vi) Lọ mọ (in love)", "<You′re so far away>", "<Buy you Dom Pérignon>"), translate(song))
        assertEquals(1, googleAsked().count { "sl=vi" in it })
        assertTrue(yorubaAsked().isEmpty())
        // Into Vietnamese, there's nothing to ask again.
        runBlocking { translator().translate(song.map { "$it." }, "vi") }
        assertEquals(1, googleAsked().count { "sl=vi" in it })
    }

    @Test
    fun yorubaIsntAskedAgainForAReaderOfYoruba() {
        googleReads()
        assertEquals(listOf("<Keep it moving, rọra baddie>"), runBlocking { translator().translate(listOf("Keep it moving, rọra baddie"), "yo") })
        assertTrue(yorubaAsked().isEmpty())
    }

    @Test
    fun googleNotAnsweringAsYorubaLeavesItToMyMemory() {
        googleReads()
        val auto = google
        google = { request -> if (request.url.queryParameter("sl") == "yo") FakeSpotify.html(request, "[]") else auto(request) }
        myMemory()
        assertEquals(listOf("[Keep it moving, rọra baddie]"), translate(listOf("Keep it moving, rọra baddie")))
    }

    @Test
    fun whatGoogleCantTranslateMyMemoryDoes() {
        // A song longer than Google is asked at once, turned away after the first lines.
        val lines = (1..Translator.GOOGLE_LINES + 2).map { "Line $it" }
        googleTranslates()
        val translated = google
        google = { request -> if (googleAsked().size == 1) translated(request) else FakeSpotify.html(request, "<html>Sorry...</html>", code = 429) }
        myMemory()
        assertEquals(lines.take(Translator.GOOGLE_LINES).map { "<$it>" } + lines.drop(Translator.GOOGLE_LINES).map { "[$it]" }, translate(lines))
        assertEquals(listOf("Line 101\nLine 102"), myMemoryAsked().map { Request.Builder().url(it).build().url.queryParameter("q") })
        // Rows other than the lines asked, or a page instead of them, are no translation either.
        google = { request -> FakeSpotify.html(request, """[["all at once","en"]]""") }
        assertEquals(listOf("[One]", "[Two]"), translate(listOf("One", "Two")))
        google = { request -> FakeSpotify.html(request, "<html>not json</html>") }
        assertEquals(listOf("[Three]"), translate(listOf("Three")))
        // Google's are kept apart from MyMemory's: back again, it's asked for those MyMemory did.
        googleTranslates()
        assertEquals(listOf("<Line 1>", "<Line 101>", "<One>"), translate(listOf("Line 1", "Line 101", "One")))
        assertEquals("q=Line+101&q=One", fake.requestBodies.last())
    }

    @Test
    fun linesAreTranslatedTogetherAndEachOnceKept() {
        myMemory()
        val lines = listOf("Night sky", "", "♪", "Stars", "Night sky")
        assertEquals(listOf("[Night sky]", null, null, "[Stars]", "[Night sky]"), translate(lines))
        // Asked once, each line once, with the app's language and the line's left for MyMemory to tell.
        val asked = myMemoryAsked().single()
        assertEquals("Night sky\nStars", Request.Builder().url(asked).build().url.queryParameter("q"))
        assertEquals("Autodetect|es", Request.Builder().url(asked).build().url.queryParameter("langpair"))

        // Kept: asked again, even by a new translator reading the saved file, nothing is sent.
        assertEquals(listOf("[Stars]"), translate(listOf("Stars")))
        assertEquals(1, myMemoryAsked().size)
    }

    @Test
    fun aLineAlreadyInTheLanguageIsntRepeatedUnderItself() {
        myMemory { if (it == "Hola") "hola" else "[$it]" }
        assertEquals(listOf(null, "[Hello]"), translate(listOf("Hola", "Hello")))
    }

    @Test
    fun wordsAlreadyInTheLanguageMyMemoryRefusesComeBackAsTheyWere() {
        // Asked for English into English, MyMemory says so as a 403 instead of answering.
        others = { request ->
            FakeSpotify.html(request, """{"responseData":{"translatedText":"PLEASE SELECT TWO DISTINCT LANGUAGES"},"responseDetails":"PLEASE SELECT TWO DISTINCT LANGUAGES","responseStatus":"403"}""")
        }
        assertEquals(listOf(null, null), runBlocking { translator().translate(listOf("Me and Michael", "So you think"), "en") })
    }

    @Test
    fun aWordInTheAppsLanguageIsLookedUpInWiktionary() {
        others = { request ->
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
        // English from anywhere has one.
        assertEquals(Definition(null, "a word, a term"), runBlocking { translator().define("word", "en-GB") })
        // Wikimedia is told who's asking.
        assertTrue("User-Agent" to "Crosstune (https://github.com/astrovm/crosstune)" in fake.requestHeaders)
        listOf("nothing", "empty", "broken", "unknown").forEach { assertNull(it, runBlocking { translator().define(it, "en") }) }
        // Only English has a dictionary to ask.
        val asked = fake.requestedUrls.size
        assertNull(runBlocking { translator().define("palabra", "es") })
        assertNull(runBlocking { translator().define("palabra", "es-AR") })
        assertEquals(asked, fake.requestedUrls.size)
    }

    @Test
    fun manyLinesGoInBatchesMyMemoryTakes() {
        myMemory()
        val lines = (1..40).map { "Line number $it of the song" }
        assertEquals(lines.map { "[$it]" }, translate(lines))
        assertEquals(3, myMemoryAsked().size)
        myMemoryAsked().forEach { assert(Request.Builder().url(it).build().url.queryParameter("q")!!.toByteArray().size <= Translator.MYMEMORY_BYTES) }
    }

    @Test
    fun batchesTranslatedBeforeOneFailsAreKeptAndOnlyTheRestAskedAgain() {
        val lines = (1..40).map { "Line number $it of the song" }
        // The day's translations run out after the first batch.
        var asked = 0
        others = { request: Request ->
            asked++
            val text = request.url.queryParameter("q").orEmpty()
            val body = if (asked == 1) {
                JSONObject().put("responseData", JSONObject().put("translatedText", text.split("\n").joinToString("\n") { "[$it]" })).put("responseStatus", 200)
            } else {
                JSONObject().put("responseData", JSONObject().put("translatedText", "QUOTA")).put("responseStatus", 429)
            }
            FakeSpotify.html(request, body.toString())
        }
        assertNull(translate(lines))
        assertEquals(2, asked)
        // Asked again, the first batch is already there.
        myMemory()
        fake.requestedUrls.clear()
        assertEquals(lines.map { "[$it]" }, translate(lines))
        assertEquals(2, myMemoryAsked().size)
        assertTrue(myMemoryAsked().none { "Line%20number%201%20" in it })
    }

    @Test
    fun aBatchThatComesBackWithOtherRowsIsAskedLineByLine() {
        // Two lines read back as one.
        others = { request: Request ->
            val text = request.url.queryParameter("q").orEmpty()
            val translated = if ("\n" in text) "all at once" else "[$text]"
            FakeSpotify.html(request, JSONObject().put("responseData", JSONObject().put("translatedText", translated)).put("responseStatus", 200).toString())
        }
        assertEquals(listOf("[One]", "[Two]"), translate(listOf("One", "Two")))
        assertEquals(3, myMemoryAsked().size)
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
        others = { throw IOException("offline") }
        assertNull(translate(listOf("Stars")))
        others = { request -> FakeSpotify.html(request, "busy", code = 503) }
        assertNull(translate(listOf("Stars")))
        others = { request -> FakeSpotify.html(request, "<html>not json</html>") }
        assertNull(translate(listOf("Stars")))
        // A batch that falls apart, then fails line by line, fails too.
        var calls = 0
        others = { request ->
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
    fun theLanguageIsTheAppsAsTheServicesNameIt() {
        assertEquals("zh-CN", Translator.languageOf(Locale.SIMPLIFIED_CHINESE))
        assertEquals("pt-BR", Translator.languageOf(Locale.forLanguageTag("pt-BR")))
        assertEquals("id", Translator.languageOf(Locale.forLanguageTag("id")))
        assertEquals("id", Translator.languageOf(Locale.of("in")))
        assertEquals("ja", Translator.languageOf(Locale.JAPAN))
        // Where it's spoken, when that changes the words.
        assertEquals("es-AR", Translator.languageOf(Locale.forLanguageTag("es-AR")))
        assertEquals("es-ES", Translator.languageOf(Locale.forLanguageTag("es-ES")))
        assertEquals("pt-PT", Translator.languageOf(Locale.forLanguageTag("pt-PT")))
        assertEquals("en-GB", Translator.languageOf(Locale.UK))
        // Else the language's first, or the language alone.
        assertEquals("es-AR", Translator.languageOf(Locale.forLanguageTag("es")))
        assertEquals("es-AR", Translator.languageOf(Locale.forLanguageTag("es-CL")))
        assertEquals("en-US", Translator.languageOf(Locale.ENGLISH))
        assertEquals("de", Translator.languageOf(Locale.GERMANY))
        assertEquals("sv", Translator.languageOf(Locale.forLanguageTag("sv-SE")))
        // Traditional Chinese, by its script or where it's written.
        assertEquals("zh-TW", Translator.languageOf(Locale.TRADITIONAL_CHINESE))
        assertEquals("zh-TW", Translator.languageOf(Locale.forLanguageTag("zh-HK")))
        assertEquals("zh-TW", Translator.languageOf(Locale.forLanguageTag("zh-MO")))
        assertEquals("zh-TW", Translator.languageOf(Locale.forLanguageTag("zh-Hant-SG")))
        assertEquals("zh-CN", Translator.languageOf(Locale.forLanguageTag("zh-SG")))
    }
}

private const val GOOGLE_HOST = "clients5.google.com"
