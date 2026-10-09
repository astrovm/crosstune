package com.astrovm.crosstune

import androidx.core.text.HtmlCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Lyrics, line by line, in another language. By default from MyMemory, which asks for no account
 * but only translates so much a day, or from the LibreTranslate server set in Settings. Each line
 * is kept once translated, so a chorus, or a song read again, costs nothing more.
 */
/** A LibreTranslate server to translate with instead, at [url], with the API [key] it may want. */
internal data class TranslationServer(val url: String, val key: String = "")

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
    suspend fun translate(lines: List<String>, target: String, server: TranslationServer?): List<String?>? {
        val words = lines.filter(::worthTranslating).distinct()
        val key = { line: String -> "${server?.url.orEmpty()}|$target|$line" }
        val known = words.associateWith { cache.get(key(it)) }
        val missing = words.filter { known[it] == null }
        val found = try {
            if (missing.isEmpty()) emptyMap() else if (server != null) libre(missing, target, server) else myMemory(missing, target)
        } catch (_: IOException) {
            return null
        } catch (_: JSONException) {
            return null
        } ?: return null
        found.forEach { (line, translation) -> cache.put(key(line), translation) }
        cache.save()
        val all = known.filterValues { it != null }.mapValues { it.value!! } + found
        // A line already in [target] comes back as it was, and is no use under itself.
        return lines.map { line -> all[line]?.takeUnless { it.equals(line.trim(), ignoreCase = true) } }
    }

    private fun worthTranslating(line: String) = line.any(Char::isLetter)

    /**
     * MyMemory takes up to [MYMEMORY_BYTES] at a time and keeps lines apart, so lines go in batches,
     * one per row. A batch that comes back with other rows than it went with is asked line by line.
     */
    private suspend fun myMemory(lines: List<String>, target: String): Map<String, String>? {
        val batches = mutableListOf(mutableListOf<String>())
        lines.forEach { line ->
            val batch = batches.last()
            if (batch.isNotEmpty() && (batch + line).joinToString("\n").toByteArray().size > MYMEMORY_BYTES) batches += mutableListOf(line) else batch += line
        }
        val found = mutableMapOf<String, String>()
        for (batch in batches) {
            val rows = myMemoryRows(batch.joinToString("\n"), target) ?: return null
            if (rows.size == batch.size) {
                batch.zip(rows).forEach { (line, row) -> found[line] = row }
            } else {
                batch.forEach { line -> found[line] = myMemoryRows(line, target)?.joinToString(" ") ?: return null }
            }
        }
        return found
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
        if (language != "en") return null
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

    /** LibreTranslate takes every line at once, with an API key when the server wants one. */
    private suspend fun libre(lines: List<String>, target: String, server: TranslationServer): Map<String, String>? {
        val url = server.url.toHttpUrlOrNull()?.newBuilder()?.addPathSegment("translate")?.build() ?: return null
        val body = JSONObject()
            .put("q", JSONArray(lines))
            .put("source", "auto")
            .put("target", target.substringBefore('-').lowercase(Locale.ROOT))
            .put("format", "text")
            .apply { server.key.takeIf { it.isNotBlank() }?.let { put("api_key", it) } }
        val json = JSONObject(call(Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType())).build()))
        val translated = json.getJSONArray("translatedText")
        if (translated.length() != lines.size) return null
        return lines.indices.associate { lines[it] to translated.getString(it).trim() }
    }

    private suspend fun call(request: Request): String = client.newCall(request).executeAsync().use { response ->
        if (!response.isSuccessful) throw IOException("Translation service returned ${response.code}")
        withContext(ioDispatcher) { response.body.stringAtMost() }
    }

    companion object {
        const val MYMEMORY_URL = "https://api.mymemory.translated.net/get"
        const val WIKTIONARY_URL = "https://en.wiktionary.org/api/rest_v1/page/definition"
        /** What MyMemory says, as a 403, for words already in the language asked for. */
        private const val SAME_LANGUAGE = "DISTINCT LANGUAGES"
        /** Wikimedia asks to be told who's calling. */
        private const val USER_AGENT = "Crosstune (https://github.com/astrovm/crosstune)"
        /** MyMemory's limit on what's sent at once is 500 bytes; a little under, to be safe. */
        const val MYMEMORY_BYTES = 450

        /** The language lyrics are translated into, as the services name it: the app's own. */
        fun languageOf(locale: Locale): String = when (locale.language) {
            "zh" -> "zh-CN"
            "pt" -> "pt-BR"
            "in", "id" -> "id"
            else -> locale.language
        }
    }

/** A page's styling, as Wiktionary leaves in some definitions. */
/** What a word means, and what [type] of word it is, e.g. "noun", when the dictionary says. */
internal data class Definition(val type: String?, val meaning: String)

private val styleSheet = Regex("""<style[^>]*>.*?</style>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
}
