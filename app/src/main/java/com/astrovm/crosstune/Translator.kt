package com.astrovm.crosstune

import androidx.core.text.HtmlCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/**
 * Lyrics, line by line, in another language, from Google Translate, or from MyMemory where it
 * can't. Neither asks for an account; MyMemory only translates so much a day. Each line is kept
 * once translated, so a chorus, or a song read again, costs nothing more.
 */

internal class Translator(
    private val client: OkHttpClient,
    private val cache: LookupCache,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    /**
     * [lines] in [target], each one's translation where it reads differently, or null where there's
     * nothing to translate; null for all of them when it couldn't be done, maybe offline or out of
     * the day's translations.
     */
    suspend fun translate(lines: List<String>, target: String): List<String?>? {
        val words = lines.filter(::worthTranslating).distinct()
        val found = mutableMapOf<String, String>()
        // Google's, kept or asked now; for the lines it can't do, e.g. offline or turned away, MyMemory's.
        val done = translated(words, found, { "$GOOGLE|$target|$it" }) { missing, onFound -> google(missing, target, onFound) } ||
            translated(words, found, { "|$target|$it" }) { missing, onFound -> myMemory(missing, target, onFound) }
        cache.save()
        if (!done) return null
        // A line already in [target] comes back as it was, and is no use under itself.
        return lines.map { line -> found[line]?.takeUnless { it.equals(line.trim(), ignoreCase = true) } }
    }

    /**
     * Into [found], each of [words] not there yet, as kept under [key] or else from [service].
     * Each batch translated is kept as it comes, so one failing later doesn't lose those, and
     * asking again only asks for the rest. Whether they all were.
     */
    private suspend fun translated(
        words: List<String>,
        found: MutableMap<String, String>,
        key: (String) -> String,
        service: suspend (List<String>, suspend (String, String) -> Unit) -> Boolean
    ): Boolean {
        words.filter { it !in found }.forEach { line -> cache.get(key(line))?.let { found[line] = it } }
        val missing = words.filter { it !in found }
        return try {
            missing.isEmpty() || service(missing) { line, translation ->
                found[line] = translation
                cache.put(key(line), translation)
            }
        } catch (_: IOException) {
            false
        } catch (_: JSONException) {
            false
        }
    }

    private fun worthTranslating(line: String) = line.any(Char::isLetter)

    /**
     * Google Translate, as Chrome's dictionary asks it, with no account: up to [GOOGLE_LINES] lines
     * at once, each its own row back. Rows other than the lines asked, e.g. turned away with a
     * page instead, leave the rest to MyMemory. [onFound] hears each line translated.
     */
    private suspend fun google(lines: List<String>, target: String, onFound: suspend (String, String) -> Unit): Boolean {
        val url = GOOGLE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("client", "dict-chrome-ex")
            .addQueryParameter("sl", "auto")
            .addQueryParameter("tl", target)
            .build()
        for (batch in lines.chunked(GOOGLE_LINES)) {
            val form = FormBody.Builder().apply { batch.forEach { add("q", it) } }.build()
            // Each row is the line in [target], then the language it was in.
            val rows = JSONArray(call(Request.Builder().url(url).post(form).build()))
            if (rows.length() != batch.size) return false
            batch.forEachIndexed { i, line -> onFound(line, rows.getJSONArray(i).getString(0).trim()) }
        }
        return true
    }

    /**
     * MyMemory takes up to [MYMEMORY_BYTES] at a time and keeps lines apart, so lines go in batches,
     * one per row. A batch that comes back with other rows than it went with is asked line by line.
     * [onFound] hears each line translated; whether they all were.
     */
    private suspend fun myMemory(lines: List<String>, target: String, onFound: suspend (String, String) -> Unit): Boolean {
        val batches = mutableListOf(mutableListOf<String>())
        lines.forEach { line ->
            val batch = batches.last()
            if (batch.isNotEmpty() && (batch + line).joinToString("\n").toByteArray().size > MYMEMORY_BYTES) batches += mutableListOf(line) else batch += line
        }
        for (batch in batches) {
            val rows = myMemoryRows(batch.joinToString("\n"), target) ?: return false
            if (rows.size == batch.size) {
                batch.zip(rows).forEach { (line, row) -> onFound(line, row) }
            } else {
                batch.forEach { line -> onFound(line, myMemoryRows(line, target)?.joinToString(" ") ?: return false) }
            }
        }
        return true
    }

    private suspend fun myMemoryRows(text: String, target: String): List<String>? {
        val url = MYMEMORY_URL.toHttpUrl().newBuilder()
            .addQueryParameter("q", text)
            .addQueryParameter("langpair", "Autodetect|$target")
            .build()
        val json = JSONObject(call(Request.Builder().url(url).get().build()))
        // Words already in [target] are refused rather than given back, so they're given back here.
        if (json.optString("responseDetails").contains(SAME_LANGUAGE, ignoreCase = true)) return text.split("\n")
        // Out of the day's translations, it says so with a status other than 200, and "translates" into that notice.
        if (json.optInt("responseStatus") != 200 || json.optBoolean("quotaFinished")) return null
        val translated = json.getJSONObject("responseData").getString("translatedText")
        return translated.split("\n").map { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim() }
    }

    /**
     * What [word] means, from Wiktionary's dictionary, for when translating it gives it back as it
     * was: a word already in [language]. Only English has one to ask; null without an entry.
     */
    suspend fun define(word: String, language: String): Definition? {
        if (Locale.forLanguageTag(language).language != "en") return null
        val url = WIKTIONARY_URL.toHttpUrl().newBuilder().addPathSegment(word.lowercase(Locale.ROOT)).build()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        return try {
            val entries = JSONObject(call(request))
            // The word's own language comes first; its first sense is the one most meant.
            val senses = entries.optJSONArray(entries.keys().asSequence().firstOrNull() ?: return null) ?: return null
            (0 until senses.length()).asSequence()
                .map(senses::getJSONObject)
                .flatMap { sense ->
                    val type = sense.optString("partOfSpeech").lowercase(Locale.ROOT).ifBlank { null }
                    sense.optJSONArray("definitions")?.let { list -> (0 until list.length()).map { Definition(type, list.getJSONObject(it).optString("definition")) } }.orEmpty()
                }
                // Wiktionary sends its page styling along with some definitions, which isn't text to show.
                .map { it.copy(meaning = HtmlCompat.fromHtml(it.meaning.replace(styleSheet, ""), HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()) }
                .firstOrNull { it.meaning.isNotEmpty() }
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        }
    }

    private suspend fun call(request: Request): String = client.newCall(request).executeAsync().use { response ->
        if (!response.isSuccessful) throw IOException("Translation service returned ${response.code}")
        withContext(ioDispatcher) { response.body.stringAtMost() }
    }

    companion object {
        const val GOOGLE_URL = "https://clients5.google.com/translate_a/t"
        const val MYMEMORY_URL = "https://api.mymemory.translated.net/get"
        const val WIKTIONARY_URL = "https://en.wiktionary.org/api/rest_v1/page/definition"
        /** What MyMemory says, as a 403, for words already in the language asked for. */
        private const val SAME_LANGUAGE = "DISTINCT LANGUAGES"
        /** Wikimedia asks to be told who's calling. */
        private const val USER_AGENT = "Crosstune (https://github.com/astrovm/crosstune)"
        /** Lines asked of Google at once: a whole song, most of the time. */
        const val GOOGLE_LINES = 100
        /** Where Google's translations are kept, apart from MyMemory's. */
        private const val GOOGLE = "google"
        /** MyMemory's limit on what's sent at once is 500 bytes; a little under, to be safe. */
        const val MYMEMORY_BYTES = 450

        /**
         * The language lyrics are translated into for [locale], the app's own: where it's spoken
         * there when that's one of [AppLanguage.translationTags], e.g. "es-ES", else the language's first.
         */
        fun languageOf(locale: Locale): String {
            val language = if (locale.language == "in") "id" else locale.language
            // Traditional Chinese is written in Taiwan, Hong Kong and Macao.
            val region = if (language == "zh" && (locale.script == "Hant" || locale.country in setOf("HK", "MO"))) "TW" else locale.country
            val regional = "$language-$region"
            return if (regional in AppLanguage.translationTags) regional else AppLanguage.translationTag(language) ?: language
        }
    }

}

/** What a word means, and what [type] of word it is, e.g. "noun", when the dictionary says. */
internal data class Definition(val type: String?, val meaning: String)

/** A page's styling, as Wiktionary leaves in some definitions. */
private val styleSheet = Regex("""<style[^>]*>.*?</style>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
