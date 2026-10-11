package com.astrovm.crosstune

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** A song in U-FRET's results: whether it's the easier version, its number, title and artist. */
private val uFretResult = Regex(
    """c-list__item (normal|beginner)-chord">\s*<a href="/song\.php\?data=(\d+)".*?c-list__title">(.*?)</p>\s*<p class="c-list__artist">(.*?)</p>""",
    RegexOption.DOT_MATCHES_ALL
)
private val tags = Regex("<[^>]*>[^<]*</[^>]*>|<[^>]*>")
private val uFretSheet = Regex("""var ufret_chord_datas = (\[.*?]);""")
private val ugData = Regex("""class="js-store" data-content="([^"]*)"""")

/**
 * A song's chords, from the sites guitarists share them on: U-FRET, which has most Japanese songs,
 * and Ultimate Guitar, which has most others. A sheet only counts when its words are the song's.
 */
internal class ChordFinder(
    private val client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Sheets found, so a song played again needs no lookup. */
    private val cache: LookupCache? = null
) {
    /** A song's chords, or why there are none to show. */
    sealed interface Chords {
        data class Found(val sheet: ChordSheet) : Chords

        /** Neither site has chords whose words are these. */
        data object None : Chords

        /** A site wouldn't answer, so there may still be some. */
        data object Unavailable : Chords
    }

    /** [song]'s chords, from a sheet whose words line up with [texts], the lines shown. */
    suspend fun chordsOf(song: MusicMetadata, texts: List<String>, timeoutMs: Long = TIMEOUT_MS): Chords {
        val key = "${SongNames.normalize(song.title)}\u0000${SongNames.normalize(song.artist)}"
        cache?.get(key)?.let { kept -> fromCache(kept)?.let { return Chords.Found(it) } }
        // Japanese words are likelier on U-FRET; any song may be on either.
        val sites = if (texts.any { line -> line.any { it in '぀'..'ヿ' || it in '一'..'鿿' } }) {
            listOf(::fromUFret, ::fromUltimateGuitar)
        } else {
            listOf(::fromUltimateGuitar, ::fromUFret)
        }
        var unavailable = false
        for (site in sites) {
            val sheet = withTimeoutOrNull(timeoutMs) {
                try {
                    Result.success(site(song) { ChordSheets.place(it, texts) != null })
                } catch (e: IOException) {
                    Result.failure(e)
                } catch (e: JSONException) {
                    Result.failure(e)
                }
            }
            if (sheet == null || sheet.isFailure) {
                unavailable = true
                continue
            }
            sheet.getOrNull()?.let { found ->
                cache?.let {
                    it.put(key, toCache(found))
                    it.save()
                }
                return Chords.Found(found)
            }
        }
        return if (unavailable) Chords.Unavailable else Chords.None
    }

    /**
     * U-FRET's first sheet for the song that [fits]: by the artist first, then by others, as its own
     * name for the artist can be in Japanese, and the song's own before one made easier to play.
     */
    private suspend fun fromUFret(song: MusicMetadata, fits: (ChordSheet) -> Boolean): ChordSheet? {
        val search = U_FRET_SEARCH_URL.toHttpUrl().newBuilder().addQueryParameter("key", "${song.title} ${song.artist}").build()
        val ids = uFretResult.findAll(fetch(search.toString())).mapNotNull { result ->
            val (kind, id, title, artist) = result.destructured
            if (!SongNames.same(title.replace(tags, "").trim(), song.title)) return@mapNotNull null
            Triple(!SongNames.sameArtist(artist.trim(), song.artist), kind == "beginner", id)
        }.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }.take(MAX_SHEETS).toList()
        for (id in ids) {
            val url = "$U_FRET_SONG_URL$id"
            val lines = uFretSheet.find(fetch(url))?.groupValues?.get(1) ?: continue
            ChordSheet(ChordSheets.fromUFret(JSONArray(lines)), site = ChordSite.U_FRET, url = url).takeIf(fits)?.let { return it }
        }
        return null
    }

    /** Ultimate Guitar's first chord sheet for the song that [fits]: by the artist first, those most voted for first. */
    private suspend fun fromUltimateGuitar(song: MusicMetadata, fits: (ChordSheet) -> Boolean): ChordSheet? {
        val search = UG_SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("search_type", "title")
            .addQueryParameter("value", "${song.title} ${song.artist}")
            .build()
        val results = ugStore(fetch(search.toString()))?.optJSONObject("data")?.optJSONArray("results") ?: return null
        val urls = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
            .filter { it.optString("type") == "Chords" && SongNames.wordsMatch(it.optString("song_name"), song.title) }
            .sortedWith(compareBy({ !SongNames.sameArtist(it.optString("artist_name"), song.artist) }, { -it.optInt("votes") }))
            .map { it.optString("tab_url") }.take(MAX_SHEETS)
        for (url in urls) {
            val view = ugStore(fetch(url))?.optJSONObject("data")?.optJSONObject("tab_view") ?: continue
            val content = view.optJSONObject("wiki_tab")?.optString("content").orEmpty()
            ChordSheet(ChordSheets.fromUltimateGuitar(content), capo = view.optJSONObject("meta")?.optInt("capo") ?: 0, site = ChordSite.ULTIMATE_GUITAR, url = url)
                .takeIf(fits)?.let { return it }
        }
        return null
    }

    /** What Ultimate Guitar's page has for its app to show, written into the page. */
    private fun ugStore(page: String): JSONObject? =
        ugData.find(page)?.groupValues?.get(1)?.let { JSONObject(unescape(it)).optJSONObject("store")?.optJSONObject("page") }

    private suspend fun fetch(url: String): String {
        // Ultimate Guitar shows its search results only to a computer's browser.
        val request = Request.Builder().url(url).header("User-Agent", BROWSER).get().build()
        return client.newCall(request).executeAsync().use { response ->
            if (!response.isSuccessful) throw IOException("Chord site returned ${response.code}")
            withContext(ioDispatcher) { response.body.stringAtMost() }
        }
    }

    private fun toCache(sheet: ChordSheet): String = JSONObject()
        .put("site", sheet.site.name)
        .put("url", sheet.url)
        .put("capo", sheet.capo)
        .put("lines", JSONArray().apply {
            sheet.lines.forEach { line ->
                put(JSONArray().put(line.text).put(JSONArray().apply { line.chords.forEach { put(JSONArray().put(it.at).put(it.name)) } }))
            }
        })
        .toString()

    private fun fromCache(kept: String): ChordSheet? = try {
        val json = JSONObject(kept)
        val lines = json.getJSONArray("lines").let { list ->
            (0 until list.length()).map { index ->
                val line = list.getJSONArray(index)
                val chords = line.getJSONArray(1).let { all -> (0 until all.length()).map { all.getJSONArray(it).let { chord -> Chord(chord.getInt(0), chord.getString(1)) } } }
                SheetLine(line.getString(0), chords)
            }
        }
        ChordSheet(lines, json.getInt("capo"), ChordSite.valueOf(json.getString("site")), json.getString("url"))
    } catch (_: JSONException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        const val TIMEOUT_MS = 8_000L

        /** Sheets tried per site, as the first may be for another take of the song. */
        const val MAX_SHEETS = 3

        const val U_FRET_SEARCH_URL = "https://www.ufret.jp/search.php"
        const val U_FRET_SONG_URL = "https://www.ufret.jp/song.php?data="
        const val UG_SEARCH_URL = "https://www.ultimate-guitar.com/search.php"
        const val BROWSER = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

        val entity = Regex("""&(#\d+|#x[0-9a-fA-F]+|[a-zA-Z]+);""")
        val named = mapOf("quot" to "\"", "amp" to "&", "lt" to "<", "gt" to ">", "apos" to "'", "nbsp" to " ")

        /** An HTML attribute's text as written, spaces and all, which [android.text.Html] would run together. */
        fun unescape(text: String): String = entity.replace(text) { match ->
            val name = match.groupValues[1]
            when {
                name.startsWith("#x") -> name.substring(2).toInt(16).let { String(Character.toChars(it)) }
                name.startsWith("#") -> name.substring(1).toInt().let { String(Character.toChars(it)) }
                else -> named[name] ?: htmlLetters[name] ?: match.value
            }
        }

        /** The accented letters Ultimate Guitar writes by name, as in "Beyonc&eacute;". */
        val htmlLetters: Map<String, String> = buildMap {
            val accents = mapOf("acute" to '́', "grave" to '̀', "circ" to '̂', "tilde" to '̃', "uml" to '̈', "ring" to '̊', "cedil" to '̧')
            for (letter in "aeiouyncAEIOUYNC") for ((accent, mark) in accents) {
                val composed = java.text.Normalizer.normalize("$letter$mark", java.text.Normalizer.Form.NFC)
                if (composed.length == 1) put("$letter$accent", composed)
            }
        }
    }
}
