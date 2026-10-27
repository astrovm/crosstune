package com.astrovm.crosstune

import android.text.Html
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
private val titleBreak = Regex(""" [-–—/] |／""")
private val ugData = Regex("""class="js-store" data-content="([^"]*)"""")

/** In LaCuerda's results, an artist, or a song of theirs: its row, the kind of sheet each take is, and the takes best liked first. */
private val laCuerdaResult = Regex(
    """<a href="/([^/"]+)/">([^<]*)</a>|<li id='r(\d+)' lcd='([A-Z]*)-([^']*)'>\s*<a href="javascript:">([^<]*)</a>""",
    RegexOption.IGNORE_CASE
)
private val laCuerdaFiles = Regex("""var fns=\[([^\]]*)];""")
private val laCuerdaLast = Regex("""var NMAX=(\d+);""")
private val laCuerdaSheet = Regex("""<div id=t_body><pre>(.*?)</pre>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val laCuerdaChord = Regex("""<a>([^<]*)</a>""", RegexOption.IGNORE_CASE)
private val markup = Regex("<[^>]*>")

/** Cifra Club's sheet of words and chords, its tabs written apart, its chords in bold, and the fret its capo is on. */
private val cifraClubSheet = Regex("""<pre[^>]*data-chord-content="true"[^>]*>(.*?)</pre>""", RegexOption.DOT_MATCHES_ALL)
private val cifraClubTabs = Regex("""<div class="tabs">.*?</div>""", RegexOption.DOT_MATCHES_ALL)
private val cifraClubChord = Regex("""<b[^>]*>([^<]*)</b>""")
private val cifraClubCapo = Regex("""\\"capo\\":(\d+)""")

/**
 * A song's chords, from the sites guitarists share them on: U-FRET, which has most Japanese songs,
 * Ultimate Guitar, which has most others, Cifra Club, which has the Portuguese ones, and LaCuerda,
 * which has the Spanish ones. A sheet only counts when its words are the song's.
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

        /** No site has chords whose words are these. */
        data object None : Chords

        /** A site wouldn't answer, so there may still be some. */
        data object Unavailable : Chords
    }

    /** [song]'s chords, from a sheet whose words line up with [texts], the lines shown. */
    suspend fun chordsOf(song: MusicMetadata, texts: List<String>, timeoutMs: Long = TIMEOUT_MS): Chords = withContext(ioDispatcher) {
        val key = "$CACHE_VERSION\u0000${SongNames.normalize(song.title)}\u0000${SongNames.normalize(song.artist)}"
        cache?.get(key)?.let { kept -> fromCache(kept)?.let { return@withContext Chords.Found(it) } }
        // Japanese words are likelier on U-FRET; any song may be on either.
        val sites = if (texts.any(::japanese)) {
            listOf(::fromUFret, ::fromUltimateGuitar)
        } else {
            listOf(::fromUltimateGuitar, ::fromCifraClub, ::fromLaCuerda, ::fromUFret)
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
                return@withContext Chords.Found(found)
            }
        }
        if (unavailable) Chords.Unavailable else Chords.None
    }

    /**
     * U-FRET's first sheet for the song that [fits]. It only knows songs by their Japanese names, so
     * a title in two, "プラスティック・ラブ - Plastic Love", is looked up by its Japanese one, and a
     * song named in Latin letters by the names NetEase knows it by: "君は1000%" for "Kimi ha 1000%",
     * "Lemon" by "米津玄師" for Kenshi Yonezu.
     */
    private suspend fun fromUFret(song: MusicMetadata, fits: (ChordSheet) -> Boolean): ChordSheet? {
        var netEase: OtherNames? = null
        val otherNames = suspend { netEase ?: otherNames(song).also { netEase = it } }
        val parts = titleParts(song.title).filter(::japanese)
        searchUFret(song, "${song.title} ${song.artist}", song.title, otherNames, fits)?.let { return it }
        for (name in parts) searchUFret(song, name, name, otherNames, fits)?.let { return it }
        if (japanese(song.title) && japanese(song.artist)) return null
        val names = otherNames()
        val artist = names.artists.firstOrNull(::japanese)
        for (name in (names.titles + song.title + parts).distinct().take(MAX_NAMES + 1)) {
            searchUFret(song, listOfNotNull(name, artist).joinToString(" "), name, otherNames, fits)?.let { return it }
        }
        // Known only by its name in katakana, it's among the artist's songs.
        val japaneseArtist = song.artist.takeIf(::japanese) ?: artist ?: return null
        return if (japanese(song.title)) null else soundAlikeOnUFret(song, japaneseArtist, fits)
    }

    /**
     * The first sheet that [fits] of [artist]'s songs on U-FRET whose name in katakana sounds like
     * [song]'s, "プラスティック・ラブ" for "Plastic Love", as no search finds it by the other.
     * U-FRET lists them a page at a time, so only until the last page.
     */
    private suspend fun soundAlikeOnUFret(song: MusicMetadata, artist: String, fits: (ChordSheet) -> Boolean): ChordSheet? {
        for (page in 1..MAX_ARTIST_PAGES) {
            val search = U_FRET_SEARCH_URL.toHttpUrl().newBuilder().addQueryParameter("key", artist).addQueryParameter("p", "$page").build()
            val listed = uFretResult.findAll(fetch(search.toString())).map { it.destructured }.toList()
            val ids = listed.filter { (_, _, named, by) ->
                SongNames.sameArtist(unescape(by.trim()), artist) && SongNames.soundsAlike(song.title, unescape(named.replace(tags, "").trim()))
            }.sortedBy { (kind) -> kind == "beginner" }.map { (_, id) -> id }.take(MAX_SHEETS)
            for (id in ids) sheetOnUFret(id, fits)?.let { return it }
            if (listed.size < U_FRET_PAGE) return null
        }
        return null
    }

    /** U-FRET's sheet [id], if it [fits]. */
    private suspend fun sheetOnUFret(id: String, fits: (ChordSheet) -> Boolean): ChordSheet? {
        val url = "$U_FRET_SONG_URL$id"
        val lines = uFretSheet.find(sheetPage(url))?.groupValues?.get(1) ?: return null
        return ChordSheet(ChordSheets.fromUFret(JSONArray(lines)), site = ChordSite.U_FRET, url = url).takeIf(fits)
    }

    /**
     * U-FRET's first sheet for [title] that [fits], searched for by [key]: by the artist first, then
     * by others, and the song's own before one made easier to play. U-FRET may name the artist
     * otherwise, in Japanese, so then by the names [otherNames] has for them.
     */
    private suspend fun searchUFret(song: MusicMetadata, key: String, title: String, otherNames: suspend () -> OtherNames, fits: (ChordSheet) -> Boolean): ChordSheet? {
        val search = U_FRET_SEARCH_URL.toHttpUrl().newBuilder().addQueryParameter("key", key).build()
        val found = uFretResult.findAll(fetch(search.toString())).map { it.destructured }
            .filter { (_, _, named) -> SongNames.same(named.replace(tags, "").trim(), title) }.toList()
        val artists = if (found.any { (_, _, _, artist) -> SongNames.sameArtist(artist.trim(), song.artist) } || japanese(song.artist)) {
            listOf(song.artist)
        } else {
            listOf(song.artist) + otherNames().artists
        }
        val ids = found.map { (kind, id, _, artist) -> Triple(artists.none { SongNames.sameArtist(artist.trim(), it) }, kind == "beginner", id) }
            .sortedWith(compareBy({ it.first }, { it.second })).map { it.third }.take(MAX_SHEETS)
        for (id in ids) sheetOnUFret(id, fits)?.let { return it }
        return null
    }

    /**
     * The names a site may have [song] by: its own, then without what it adds in brackets, "Dreams"
     * for "Dreams (Take 2)", as the sites' searches find nothing by that, and a title in two,
     * "プラスティック・ラブ - Plastic Love", by its name in Latin letters.
     */
    private fun searchTitles(song: MusicMetadata): List<String> =
        (listOf(song.title) + (listOf(SongNames.bareTitle(song.title)) + titleParts(song.title)).filterNot(::japanese)).distinct()

    /** Ultimate Guitar's first chord sheet for the song that [fits], by any of its [searchTitles]. */
    private suspend fun fromUltimateGuitar(song: MusicMetadata, fits: (ChordSheet) -> Boolean): ChordSheet? {
        for (title in searchTitles(song)) searchUltimateGuitar(song, title, fits)?.let { return it }
        return null
    }

    /**
     * Ultimate Guitar's first chord sheet for [title] that [fits]: by the artist first, those most
     * voted for first. It may name the artist otherwise, "Misc Soundtrack" for a film's songs, so
     * when that finds none, by the title alone.
     */
    private suspend fun searchUltimateGuitar(song: MusicMetadata, title: String, fits: (ChordSheet) -> Boolean): ChordSheet? =
        firstSheet(listOf("$title ${song.artist}".trim(), title), { key -> ultimateGuitarSheets(song, title, key) }, fits) { url ->
            val view = ugStore(sheetPage(url))?.optJSONObject("data")?.optJSONObject("tab_view") ?: return@firstSheet null
            val content = view.optJSONObject("wiki_tab")?.optString("content").orEmpty()
            ChordSheet(ChordSheets.fromUltimateGuitar(unescape(content)), capo = view.optJSONObject("meta")?.optInt("capo") ?: 0, site = ChordSite.ULTIMATE_GUITAR, url = url)
        }

    /**
     * The first sheet that [fits], of those [sheets] finds for each of [keys] in turn, each read by
     * [read] at most once and [MAX_SHEETS] in all. A request that went unanswered still makes it unavailable, unless another
     * found a matching sheet.
     */
    private suspend fun firstSheet(
        keys: List<String>,
        sheets: suspend (String) -> List<String>,
        fits: (ChordSheet) -> Boolean,
        read: suspend (String) -> ChordSheet?
    ): ChordSheet? {
        var failure: IOException? = null
        val tried = mutableSetOf<String>()
        for (key in keys.distinct()) {
            val urls = try {
                sheets(key)
            } catch (e: IOException) {
                failure = e
                continue
            }
            for (url in urls.filterNot { it in tried }.take(MAX_SHEETS - tried.size)) {
                tried += url
                val sheet = try {
                    read(url)
                } catch (e: IOException) {
                    failure = e
                    continue
                }
                if (sheet != null && fits(sheet)) return sheet
            }
        }
        failure?.let { throw it }
        return null
    }

    /** The pages of Ultimate Guitar's chord sheets for [title] by [song]'s artist, searched for by [key]: by the artist first, those most voted for first. */
    private suspend fun ultimateGuitarSheets(song: MusicMetadata, title: String, key: String): List<String> {
        val search = UG_SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("search_type", "title")
            .addQueryParameter("value", key)
            .build()
        // A search that finds nothing is answered with a 404 and its usual page.
        val results = ugStore(fetch(search.toString()) { code, body -> code == 404 && ugData.containsMatchIn(body) })?.optJSONObject("data")?.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { results.optJSONObject(it) }
            .filter { it.optString("type") == "Chords" && named(it.optString("song_name"), title, byArtist(it.optString("artist_name"), song)) }
            .sortedWith(compareBy({ !SongNames.sameArtist(it.optString("artist_name"), song.artist) }, { -it.optInt("votes") }))
            .map { it.optString("tab_url") }
    }

    /** LaCuerda's first sheet of words and chords for the song that [fits], by any of its [searchTitles]. */
    private suspend fun fromLaCuerda(song: MusicMetadata, fits: (ChordSheet) -> Boolean): ChordSheet? {
        for (title in searchTitles(song)) searchLaCuerda(song, title, fits)?.let { return it }
        return null
    }

    /**
     * LaCuerda's first sheet of words and chords for [title] that [fits], by the artist first. It
     * may name the artist otherwise, "Dos Minutos" for 2 Minutos, and a search with the artist can
     * fail even when the title alone finds the song, so then by the title alone.
     */
    private suspend fun searchLaCuerda(song: MusicMetadata, title: String, fits: (ChordSheet) -> Boolean): ChordSheet? =
        firstSheet(listOf("$title ${song.artist}".trim(), title), { key -> laCuerdaSheets(song, title, key) }, fits) { url ->
            val sheet = laCuerdaSheet.find(sheetPage(url))?.groupValues?.get(1) ?: return@firstSheet null
            // Its chords as Ultimate Guitar marks them, over the words they're played on.
            val content = unescape(sheet.replace(laCuerdaChord) { "[ch]${it.groupValues[1]}[/ch]" }.replace(markup, ""))
            ChordSheet(ChordSheets.fromUltimateGuitar(content), site = ChordSite.LA_CUERDA, url = url)
        }

    /** The pages of LaCuerda's sheets of words and chords for [title] by [song]'s artist, searched for by [key]: by the artist first, those best liked first. */
    private suspend fun laCuerdaSheets(song: MusicMetadata, title: String, key: String): List<String> {
        val search = LA_CUERDA_SEARCH_URL.toHttpUrl().newBuilder().addQueryParameter("exp", key).addQueryParameter("canc", "1").build()
        // A search that finds nothing is answered with a 500 and no page at all.
        val page = fetch(search.toString()) { code, body -> code == 500 && body.isBlank() }
        // Each song's page, by its row counted from the last.
        val files = laCuerdaFiles.find(page)?.groupValues?.get(1)?.split(',')?.map { it.trim().trim('\'') }.orEmpty()
        val last = laCuerdaLast.find(page)?.groupValues?.get(1)?.toInt() ?: return emptyList()
        var artist: Pair<String, String>? = null
        val found = mutableListOf<Pair<Boolean, String>>()
        laCuerdaResult.findAll(page).forEach { match ->
            val (folder, artistName, row, kinds, takes, listed) = match.destructured
            if (folder.isNotEmpty()) {
                artist = folder to artistName
                return@forEach
            }
            val (by, byName) = artist ?: return@forEach
            val file = files.getOrNull(last - row.toInt()) ?: return@forEach
            if (!named(unescape(listed), title, byArtist(byName, song))) return@forEach
            // Words and chords, not a tab or a bass part.
            takes.filter(Char::isDigit).map { it.digitToInt() }.filter { kinds.getOrNull(it - 1) == 'R' }.forEach { take ->
                found += !SongNames.sameArtist(byName, song.artist) to "$LA_CUERDA_URL$by/$file${if (take == 1) "" else "-$take"}.shtml"
            }
        }
        return found.sortedBy { it.first }.map { it.second }
    }

    /** Cifra Club's first sheet of words and chords for the song that [fits], by any of its [searchTitles]. */
    private suspend fun fromCifraClub(song: MusicMetadata, fits: (ChordSheet) -> Boolean): ChordSheet? {
        for (title in searchTitles(song)) searchCifraClub(song, title, fits)?.let { return it }
        return null
    }

    /** Cifra Club's first sheet for [title] that [fits]: by the artist first, then by the title alone, as the site may name the artist otherwise. */
    private suspend fun searchCifraClub(song: MusicMetadata, title: String, fits: (ChordSheet) -> Boolean): ChordSheet? =
        firstSheet(listOf("$title ${song.artist}".trim(), title), { key -> cifraClubSheets(song, title, key) }, fits) { url ->
            val page = sheetPage(url, asPhone = true)
            val sheet = cifraClubSheet.find(page)?.groupValues?.get(1) ?: return@firstSheet null
            // Its chords as Ultimate Guitar marks them, over the words they're played on, without its tabs.
            val content = unescape(sheet.replace(cifraClubTabs, "").replace(cifraClubChord) { "[ch]${it.groupValues[1]}[/ch]" }.replace(markup, "").replace("\u200B", ""))
            ChordSheet(ChordSheets.fromUltimateGuitar(content), capo = cifraClubCapo.find(page)?.groupValues?.get(1)?.toInt() ?: 0, site = ChordSite.CIFRA_CLUB, url = url)
        }

    /** The pages of Cifra Club's sheets for [title] by [song]'s artist, searched for by [key]: by the artist first, as its search puts them. */
    private suspend fun cifraClubSheets(song: MusicMetadata, title: String, key: String): List<String> {
        val search = CIFRA_CLUB_SEARCH_URL.toHttpUrl().newBuilder().addQueryParameter("q", key).build()
        // Answered as a call to a function, which is taken off.
        val docs = JSONObject(fetch(search.toString()).substringAfter('(').substringBeforeLast(')')).optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        return (0 until docs.length()).mapNotNull { docs.optJSONObject(it) }
            .filter { it.optString("t") == CIFRA_CLUB_SONG && named(it.optString("m"), title, byArtist(it.optString("a"), song)) }
            .sortedBy { !SongNames.sameArtist(it.optString("a"), song.artist) }
            .map { "$CIFRA_CLUB_URL${it.optString("d")}/${it.optString("u")}/" }
    }

    /**
     * Whether a site's [title] is the song's, [ours]: by the artist, also written with other small
     * words, "Vahos de ayer" for "Vahos del Ayer", as its words still have to be the song's.
     */
    private fun named(title: String, ours: String, byArtist: Boolean) =
        (if (byArtist) SongNames.sameButSmallWords(title, ours) else SongNames.wordsMatch(title, ours)) || endsWithSong(title, ours) || insideTitle(title, ours)

    /** [title] names the film or album first: "Brahmāstra Part One - Shiva - Kesariya" is "Kesariya". */
    private fun endsWithSong(title: String, ours: String) = title.substringAfterLast(" - ", "").let { it.isNotBlank() && SongNames.same(it, ours) }

    /**
     * A title with no Latin letters is somewhere in [title], whichever Chinese characters write it:
     * "我的少女時代 Our Times - 小幸運 Xiao Xing Yun" holds "小幸运". It must be a whole name, not part of another.
     */
    private fun insideTitle(title: String, ours: String): Boolean {
        if (SongNames.words(ours).isNotEmpty()) return false
        val wanted = titleWords.findAll(Readings.simplified(SongNames.bareTitle(ours))).map { SongNames.normalize(it.value) }.toList()
        val words = titleWords.findAll(Readings.simplified(title)).map { SongNames.normalize(it.value) }.toList()
        return wanted.sumOf { it.length } >= 2 && words.windowed(wanted.size).any { it == wanted }
    }

    /** [artist] is the song's, [song]: one with no artist is nobody's. */
    private fun byArtist(artist: String, song: MusicMetadata) = song.artist.isNotBlank() && SongNames.sameArtist(artist, song.artist)

    /** The other names [title] is in, when it's two put together, "プラスティック・ラブ - Plastic Love". */
    private fun titleParts(title: String): List<String> =
        title.split(titleBreak).map { it.trim() }.filter { it.length >= 2 && it != title }.distinct()

    /** A song's names in Japanese, and its artists' names, as NetEase has them. */
    private class OtherNames(val titles: List<String>, val artists: List<String>)

    /**
     * The names NetEase has [song] by: in Japanese, "君は1000%" for "Kimi ha 1000%", and of the
     * artists of a song of its name, "竹内まりや" for Mariya Takeuchi, as it knows many by both.
     */
    private suspend fun otherNames(song: MusicMetadata): OtherNames {
        val search = NETEASE_SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("s", "${song.title} ${song.artist}")
            .addQueryParameter("type", "1")
            .addQueryParameter("limit", "10")
            .build()
        val list = JSONObject(fetch(search.toString(), NETEASE_REFERER)).optJSONObject("result")?.optJSONArray("songs")
        val songs = (0 until (list?.length() ?: 0)).mapNotNull { list?.optJSONObject(it) }
        val titles = songs.map { it.optString("name") }.filter(::japanese).distinct().take(MAX_NAMES)
        val names = listOf(song.title) + titleParts(song.title)
        val artists = songs.filter { found -> names.any { SongNames.same(found.optString("name"), it) } }.flatMap { found ->
            found.optJSONArray("artists")?.let { all -> (0 until all.length()).mapNotNull { all.optJSONObject(it)?.optString("name") } }.orEmpty()
        }.distinct()
        return OtherNames(titles, artists)
    }

    /** Whether [text] is written in Japanese, kana or kanji. */
    private fun japanese(text: String) = text.any { it in '぀'..'ヿ' || it in '一'..'鿿' }

    /** What Ultimate Guitar's page has for its app to show, written into the page. */
    private fun ugStore(page: String): JSONObject? =
        ugData.find(page)?.groupValues?.get(1)?.let { JSONObject(unescape(it)).optJSONObject("store")?.optJSONObject("page") }

    /** A sheet's page. One that's gone, though the site's search still lists it, has no sheet in it: that sheet isn't there, it's not a site that won't answer. */
    private suspend fun sheetPage(url: String, asPhone: Boolean = false) = fetch(url, asPhone = asPhone) { code, _ -> code == 404 }

    /** [found] tells a failed answer that's still the site's own, e.g. a search that found nothing, from a site that wouldn't answer. */
    private suspend fun fetch(url: String, referer: String? = null, asPhone: Boolean = false, found: (Int, String) -> Boolean = { _, _ -> false }): String {
        // Ultimate Guitar shows its search results only to a computer's browser, and Cifra Club its pages only to a phone's.
        val request = Request.Builder().url(url).apply {
            if (asPhone) asPhoneBrowser() else header("User-Agent", BROWSER)
            referer?.let { header("Referer", it) }
        }.get().build()
        return client.newCall(request).executeAsync().use { response ->
            val body = response.body.stringAtMost()
            if (!response.isSuccessful && !found(response.code, body)) throw IOException("Chord site returned ${response.code}")
            body
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

    internal companion object {
        /** Raised when sheets are read differently, so ones kept before are looked up again. */
        const val CACHE_VERSION = 2

        /** Per site; looking a song up by its other names takes a few more requests. */
        const val TIMEOUT_MS = 20_000L

        /** Sheets tried per site, as the first may be for another take of the song. */
        const val MAX_SHEETS = 3

        /** Japanese names tried for a song named in Latin letters. */
        const val MAX_NAMES = 2
        /** How many songs U-FRET lists a page, and how many pages of an artist's are looked through. */
        const val U_FRET_PAGE = 30
        const val MAX_ARTIST_PAGES = 8

        const val NETEASE_SEARCH_URL = "https://music.163.com/api/search/get"
        const val NETEASE_REFERER = "https://music.163.com/"

        const val U_FRET_SEARCH_URL = "https://www.ufret.jp/search.php"
        const val U_FRET_SONG_URL = "https://www.ufret.jp/song.php?data="
        const val UG_SEARCH_URL = "https://www.ultimate-guitar.com/search.php"
        const val LA_CUERDA_URL = "https://acordes.lacuerda.net/"
        const val LA_CUERDA_SEARCH_URL = "${LA_CUERDA_URL}busca.php"
        const val CIFRA_CLUB_URL = "https://www.cifraclub.com.br/"
        const val CIFRA_CLUB_SEARCH_URL = "https://solr.sscdn.co/cc/h2/"

        /** What Cifra Club's search calls a song, among artists and other things. */
        const val CIFRA_CLUB_SONG = "2"
        const val BROWSER = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

        private val titleWords = Regex("""[\p{L}\p{N}]+""")

        val entity = Regex("""&(#\d+|#x[0-9a-fA-F]+|[a-zA-Z]+);""")
        /** The entities pages use most, and one read otherwise than HTML says: a hidden hyphen is no letter of the word. */
        val named = mapOf("quot" to "\"", "amp" to "&", "lt" to "<", "gt" to ">", "apos" to "'", "nbsp" to " ", "shy" to "")

        /** An HTML attribute's text as written, spaces and all, which [android.text.Html] would run together. */
        fun unescape(text: String): String = entity.replace(text) { match ->
            val name = match.groupValues[1]
            when {
                name.startsWith("#x") -> codePoint(name.substring(2).toIntOrNull(16), match.value)
                name.startsWith("#") -> codePoint(name.substring(1).toIntOrNull(), match.value)
                // Any other by name, as Ultimate Guitar writes "wei&szlig;", "c&oelig;ur" and "&bdquo;".
                else -> named[name] ?: Html.fromHtml(match.value, Html.FROM_HTML_MODE_LEGACY).toString()
            }
        }

        /** The character of a numbered entity, or the entity as it is when the number is no character. */
        private fun codePoint(code: Int?, entity: String): String =
            if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else entity
    }
}
