package com.astrovm.crosstune

import android.text.Html
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.Charset
import java.net.URLEncoder

/** Where a song starts in U-FRET's results: whether it's the easier version, and its number. */
private val uFretItem = phoneRegex("""c-list__item (normal|beginner)-chord">\s*+<a href="/song\.php\?data=(\d++)"""")
private val uFretArtist = phoneRegex("""</p>\s*+<p class="c-list__artist">""")
private const val U_FRET_TITLE = """c-list__title">"""

/** U-FRET's sheet: its lines, a list in its page's data, which ends on the line it starts on. */
internal val uFretSheet = PageParts(phoneRegex("""var ufret_chord_datas = \["""), phoneRegex("""];"""), tagEnd = null, oneLine = true)
private val titleBreak = phoneRegex(""" [-–—/] |／""")
private val creditBracket = phoneRegex("""$SPACES_START\s*[(（].*$""")
private val ugData = phoneRegex("""class="js-store" data-content="([^"]*)"""")

/** In LaCuerda's results, an artist, or a song of theirs: its row, the kind of sheet each take is, and the takes best liked first. */
private val laCuerdaResult = phoneRegex(
    """<a href="/([^/"]+)/">([^<]*)</a>|<li id='r(\d+)' lcd='([A-Z]*)-([^']*)'>\s*<a href="javascript:">([^<]*)</a>""",
    RegexOption.IGNORE_CASE
)
internal val laCuerdaFiles = PageParts(phoneRegex("""var fns=\["""), phoneRegex("""];"""), tagEnd = null, without = ']')
private val laCuerdaLast = phoneRegex("""var NMAX=(\d+);""")
internal val laCuerdaSheet = PageParts(phoneRegex("<div id=t_body><pre>", RegexOption.IGNORE_CASE), phoneRegex("</pre>", RegexOption.IGNORE_CASE), tagEnd = null)
private val laCuerdaChord = phoneRegex("""<a>([^<]*)</a>""", RegexOption.IGNORE_CASE)

/** Cifra Club's sheet of words and chords, its tabs written apart, its chords in bold, and the fret its capo is on. */
internal val cifraClubSheet = PageParts(phoneRegex("<pre"), phoneRegex("</pre>"), holds = "data-chord-content=\"true\"")
internal val cifraClubTabs = PageParts(phoneRegex("""<div class="tabs">"""), phoneRegex("</div>"), tagEnd = null)
internal val cifraClubChord = PageParts(phoneRegex("<b"), phoneRegex("</b>"), without = '<')
private val cifraClubCapo = phoneRegex("""\\"capo\\":(\d+)""")

/** A song's link in J-Total's results: its page and its name. Who sings it is after, by "歌：". */
private val jTotalLink = phoneRegex("""<a href="(?:https:)?//music\.j-total\.net/data/([^"]+\.html)" target=""><font size="3"><b>([^<]*+)</b></font></a>""")

/**
 * The songs in U-FRET's results, each whether it's the easier version, its number, its title and its artist, as main's
 * `c-list__item (normal|beginner)-chord">\s*<a href="/song\.php\?data=(\d+)".*?c-list__title">(.*?)</p>\s*<p class="c-list__artist">(.*?)</p>`
 * read them: a song with no title of its own takes the next one's, and that one is passed over.
 */
internal fun uFretResults(page: String): List<List<String>> = buildList {
    val items = uFretItem.toPattern().matcher(page)
    val artists = uFretArtist.toPattern().matcher(page)
    var artistStart = -1
    var artistEnd = -1
    var from = 0
    while (items.find()) {
        if (items.start() < from) continue
        val title = page.indexOf(U_FRET_TITLE, items.end()).orNone()?.plus(U_FRET_TITLE.length) ?: break
        while (artistStart < title) {
            if (!artists.find()) return@buildList
            artistStart = artists.start()
            artistEnd = artists.end()
        }
        val end = page.indexOf("</p>", artistEnd).orNone() ?: break
        add(listOf(items.group(1), items.group(2), page.substring(title, artistStart), page.substring(artistEnd, end)))
        // Past its `</p>`, which no song starts in.
        from = end
    }
}

/**
 * The songs in J-Total's results, each its page, its name, and who sings it, as main's pattern, [jTotalLink] then
 * `.*?歌：([^/<]*)`, read them: the first "歌：" after the link, the next song's too.
 */
internal fun jTotalResults(page: String): List<List<String>> = buildList {
    val links = jTotalLink.toPattern().matcher(page)
    var from = 0
    while (links.find()) {
        if (links.start() < from) continue
        val singer = page.indexOf(SINGER, links.end()).orNone()?.plus(SINGER.length) ?: break
        val end = (singer until page.length).firstOrNull { page[it] == '/' || page[it] == '<' } ?: page.length
        add(listOf(links.group(1), links.group(2), page.substring(singer, end)))
        from = end
    }
}

private const val SINGER = "歌："

/**
 * [text] without its tags, as main's `<[^>]*>[^<]*</[^>]*>|<[^>]*>` cut them: a tag, and when the next tag closes one,
 * the words up to it and it too.
 */
internal fun withoutTagged(text: String): String {
    val ends = TagEnds(text, least = 0)
    val kept = StringBuilder()
    var from = 0
    var at = text.indexOf('<')
    while (at >= 0) {
        val end = ends.at(at)
        if (end == -1) break
        val next = text.indexOf('<', end + 1)
        val closed = if (next != -1 && text.startsWith("</", next)) ends.at(next) else -1
        kept.append(text, from, at)
        from = (if (closed != -1) closed else end) + 1
        at = text.indexOf('<', from)
    }
    return kept.append(text, from, text.length).toString()
}

/** What J-Total adds to the name of its easier versions: "〜初心者向け簡単コードVer.〜". */
private val jTotalVersion = phoneRegex("""〜[^〜]*Ver\.〜""")
private val J_TOTAL_CHARSET = Charset.forName("Shift_JIS")

/**
 * A sheet's page as a browser reads it. Browsers read Shift_JIS as Windows-31J, which has the circled
 * numbers and Roman numerals J-Total's sheets use, and the wave dash it draws; Java's Shift_JIS has
 * no place for them and gives back "\uFFFD".
 */
private val J_TOTAL_PAGE_CHARSET = runCatching { Charset.forName("windows-31j") }.getOrDefault(J_TOTAL_CHARSET)

/**
 * A song's chords, from the sites guitarists share them on: U-FRET, which has most Japanese songs,
 * J-Total, which has the older ones, Ultimate Guitar, which has most others, Cifra Club, which has
 * the Portuguese ones, and LaCuerda, which has the Spanish ones. A sheet only counts when its words
 * are the song's.
 */
internal class ChordFinder(
    private val client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Sheets found, so a song played again needs no lookup. */
    private val cache: LookupCache? = null,
    /** ChordWiki, read in the phone's browser. Absent in tests that aren't about it, and for a build with no screen. */
    private val chordWiki: ChordWikiPages? = null,
    /** A pause between ChordWiki's pages, so a lookup goes no faster than a person opening them. */
    private val chordWikiPauseMs: Long = CHORD_WIKI_PAUSE_MS
) {
    /** A song's chords, or why there are none to show. */
    sealed interface Chords {
        data class Found(val sheet: ChordSheet) : Chords

        /** No site has chords whose words are these. */
        data object None : Chords

        /** A site wouldn't answer, so there may still be some. */
        data object Unavailable : Chords

        /** ChordWiki's check ran out of time. There may still be a sheet. */
        data object WikiTimedOut : Chords

        /** ChordWiki asked for a check a person has to do. The app does not do it. */
        data object WikiCheck : Chords
    }

    /** [song]'s chords, from a sheet whose words line up with [texts], the lines shown. */
    suspend fun chordsOf(song: MusicMetadata, texts: List<String>, timeoutMs: Long = TIMEOUT_MS): Chords = withContext(ioDispatcher) {
        val key = "$CACHE_VERSION\u0000${SongNames.normalize(song.title)}\u0000${SongNames.normalize(song.artist)}"
        note("lookup title=${song.title} artist=${song.artist} lines=${texts.size}")
        // One kept before a page of the words alone stopped counting as chords is looked up again.
        cache?.get(key)?.let { kept ->
            fromCache(kept)?.takeIf { ChordSheets.place(it, texts) != null }?.let {
                note("cache ${it.site.name}")
                return@withContext Chords.Found(it)
            }
        }
        // U-FRET and J-Total both want the song's other names, so this lookup asks for them once, and only if a step does.
        val otherNames = askedOnce { otherNames(song) }
        // Japanese words are likelier on U-FRET; any song may be on either.
        val sites = if (texts.any(::japanese)) {
            listOf(Site("ufret") { fromUFret(song, otherNames, it) }, Site("ug") { fromUltimateGuitar(song, it) }, Site("jtotal") { fromJTotal(song, otherNames, it) })
        } else {
            listOf(Site("ug") { fromUltimateGuitar(song, it) }, Site("cifra") { fromCifraClub(song, it) }, Site("lacuerda") { fromLaCuerda(song, it) }, Site("ufret") { fromUFret(song, otherNames, it) })
        }
        val fits = { sheet: ChordSheet ->
            val aligned = ChordSheets.align(sheet, texts)
            if (sheet.site == ChordSite.CHORDWIKI) note(wikiFit(aligned))
            aligned.placed != null
        }
        // ChordWiki only for a song in Japanese, and alongside the others: their sheet wins, and it doesn't go first.
        if (!wantsChordWiki(song, texts)) return@withContext answered(key, readSites(sites, timeoutMs, fits))
        coroutineScope {
            val wiki = async { wikiTried(song, otherNames, fits, timeoutMs) }
            yield()
            val earlier = readSites(sites, timeoutMs, fits)
            if (earlier.sheet != null) {
                wiki.cancel()
                answered(key, earlier)
            } else {
                answered(key, mergeWiki(earlier, wiki.await()))
            }
        }
    }

    /** Why ChordWiki itself stopped, when the other sites had no sheet. */
    private enum class WikiMiss { TIMED_OUT, CHECK }

    /** One site's try: the sheet it found, whether a site wouldn't answer, and ChordWiki's own miss. */
    private class Tried(val sheet: ChordSheet?, val unavailable: Boolean, val wiki: WikiMiss? = null)

    /** A site asked in order, by the short name the lookup log uses. */
    private class Site(val name: String, val read: suspend ((ChordSheet) -> Boolean) -> ChordSheet?)

    private suspend fun readSites(sites: List<Site>, timeoutMs: Long, fits: (ChordSheet) -> Boolean): Tried {
        var unavailable = false
        for (site in sites) {
            val sheet = withTimeoutOrNull(timeoutMs) {
                try {
                    Result.success(site.read(fits))
                } catch (e: IOException) {
                    Result.failure(e)
                } catch (e: JSONException) {
                    Result.failure(e)
                }
            }
            if (sheet == null || sheet.isFailure) {
                unavailable = true
                note("${site.name} ${if (sheet == null) "timeout" else "error"}")
                continue
            }
            val found = sheet.getOrNull()
            if (found != null) {
                note("${site.name} sheet ${found.lines.sumOf { it.chords.size }}")
                return Tried(found, false)
            }
            note("${site.name} none")
        }
        return Tried(null, unavailable)
    }

    private suspend fun answered(key: String, tried: Tried): Chords {
        val found = tried.sheet
        if (found != null) {
            cache?.put(key, toCache(found))
            cache?.save()
            return Chords.Found(found)
        }
        // ChordWiki's check is said on its own. Any other miss stays "a site wouldn't answer".
        return when (tried.wiki) {
            WikiMiss.TIMED_OUT -> Chords.WikiTimedOut
            WikiMiss.CHECK -> Chords.WikiCheck
            null -> if (tried.unavailable) Chords.Unavailable else Chords.None
        }
    }

    /** Japanese words, or a Japanese title or singer. Anything else never asks ChordWiki. */
    private fun wantsChordWiki(song: MusicMetadata, texts: List<String>): Boolean =
        chordWiki != null && (texts.any(::japanese) || japanese(song.title) || japanese(song.artist))

    private suspend fun wikiTried(song: MusicMetadata, otherNames: suspend () -> OtherNames, fits: (ChordSheet) -> Boolean, timeoutMs: Long): Tried {
        val looked = withTimeoutOrNull(timeoutMs) {
            try {
                Result.success(readChordWiki(song, otherNames, fits))
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: JSONException) {
                Result.failure(e)
            }
        }
        if (looked == null || looked.isFailure) {
            val error = looked?.exceptionOrNull()
            note("wiki ${if (looked == null) "timeout" else "error: ${error?.message}"}")
            // Only a check the browser saw is said as one. A lookup that just ran out of time may not have met one.
            val wiki = when ((error as? ChordWikiStopped)?.check) {
                ChordWikiCheck.INTERACTIVE -> WikiMiss.CHECK
                ChordWikiCheck.AUTOMATIC -> WikiMiss.TIMED_OUT
                else -> null
            }
            return Tried(null, true, wiki)
        }
        val found = looked.getOrNull()
        if (found == null) note("wiki none")
        return Tried(found, false)
    }

    /** One line for logcat: the letter match, and the chord names [align] did not draw. No lyric text. */
    private fun wikiFit(aligned: ChordSheets.ChordAlign): String {
        val dropped = aligned.lines.flatMap { line ->
            val left = line.drawn.toMutableList()
            line.chords.mapNotNull { name ->
                val at = left.indexOf(name)
                if (at < 0) name else {
                    left.removeAt(at)
                    null
                }
            }
        }
        val drawn = aligned.lines.sumOf { it.drawn.size }
        val page = aligned.lines.sumOf { it.chords.size }
        val names = dropped.take(DROPPED_LOG).joinToString(",")
        // Where they were: the sheet line, how many of its letters lined up, and how many chords it lost.
        val where = aligned.lines.withIndex().filter { (_, line) -> line.drawn.size < line.chords.size }.take(DROPPED_LOG)
            .joinToString(" ") { (number, line) -> "#$number:${line.matched}/${line.letters}:${line.drawn.size}/${line.chords.size}" }
        return "wiki lines=$where letters=${aligned.matchedLetters}/${aligned.letters} runs=${aligned.runs}/${aligned.letters} placed=${aligned.placed != null} drawn=$drawn/$page dropped=$names"
    }

    private fun note(message: String) {
        if (Log.isLoggable(CHORD_LOG, Log.INFO)) Log.i(CHORD_LOG, message)
    }

    /** ChordWiki's sheet wins when the others had none; a failure only matters when nobody had a sheet. */
    private fun mergeWiki(earlier: Tried, wiki: Tried): Tried {
        val sheet = wiki.sheet ?: earlier.sheet
        // A sheet from either side is the answer. The check is only why there's nothing to show.
        return if (sheet != null) Tried(sheet, false) else Tried(null, earlier.unavailable || wiki.unavailable, wiki.wiki)
    }

    /**
     * ChordWiki's sheet for [song] that [fits]. The edit page of the name it's known by, then the
     * name it was given when that page is another song, and the search only when neither is: then
     * one more sheet, no faster than a person would open them. An empty page is tried again under
     * the name with a trailing tag taken off, and with its punctuation written the other width.
     */
    private suspend fun readChordWiki(song: MusicMetadata, otherNames: suspend () -> OtherNames, fits: (ChordSheet) -> Boolean): ChordSheet? {
        val pages = chordWiki ?: return null
        val known = chordWikiTitle(song, otherNames)
        openChordWiki(pages, known, fits)?.let { return it }
        // The Japanese name a search lists can be a different song. This one's own page is still its sheet.
        if (song.title.isNotBlank() && known != song.title) {
            chordWikiPause()
            openChordWiki(pages, song.title, fits)?.let { return it }
        }
        return searchChordWiki(pages, song, known, fits)
    }

    /**
     * The edit page of [title] when its words are the song's. An empty editor is not that page, so
     * the same name written another way is opened too. A page of a different song is not.
     */
    private suspend fun openChordWiki(pages: ChordWikiPages, title: String, fits: (ChordSheet) -> Boolean): ChordSheet? {
        val opened = chordWikiSheet(pages, title)
        if (opened != null) return opened.takeIf(fits)
        for (name in chordWikiSpellings(title)) {
            chordWikiPause()
            chordWikiSheet(pages, name)?.takeIf(fits)?.let { return it }
        }
        return null
    }

    /** The search for [known], then for the name the song was given, then for either written another way. */
    private suspend fun searchChordWiki(pages: ChordWikiPages, song: MusicMetadata, known: String, fits: (ChordSheet) -> Boolean): ChordSheet? {
        val more = (chordWikiSpellings(known) + chordWikiSpellings(song.title)).distinct()
        val names = listOf(known, song.title).filter { it.isNotBlank() } + more
        for (query in names.map { chordWikiQuery(it, song.artist) }.distinct()) {
            chordWikiPause()
            val hits = chordWikiHits(pages.page(chordWikiSearchUrl(query)))
            val best = chordWikiBest(hits, known, song.artist, song.title, more)
            note("wiki search q=$query hits=${hits.joinToString(" | ") { "${it.title} / ${it.artist}" }} best=${best?.page ?: "none"}")
            if (best == null) continue
            chordWikiPause()
            return chordWikiSheet(pages, best.page)?.takeIf(fits)
        }
        return null
    }

    /**
     * The song and its lead singer. A game recording credits every character and the actor behind
     * each, "島村卯月 (CV: 大橋彩香), 小日向美穂 (CV: ...)", and ChordWiki's search lists nothing for
     * all of that. The first name, up to its bracket, is the one it indexes.
     */
    private fun chordWikiQuery(name: String, artist: String): String {
        val lead = SongNames.artistNames(artist).firstOrNull().orEmpty().replace(creditBracket, "").trim()
        return listOf(name, lead).filter { it.isNotBlank() }.joinToString(" ")
    }

    /**
     * [title] with a trailing tag taken off, and with ASCII punctuation written at full width or
     * back. Letters stay as they are. The title itself isn't here.
     */
    private fun chordWikiSpellings(title: String): List<String> {
        val bare = SongNames.bareTitle(title)
        return listOf(bare, chordWikiWide(title), chordWikiNarrow(title), chordWikiWide(bare), chordWikiNarrow(bare))
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != title }
            .distinct()
    }

    private fun chordWikiWide(title: String): String = buildString(title.length) {
        title.forEach { letter ->
            append(if (letter.code in 0x21..0x7E && !letter.isLetterOrDigit()) (letter.code + 0xFEE0).toChar() else letter)
        }
    }

    private fun chordWikiNarrow(title: String): String = buildString(title.length) {
        title.forEach { letter ->
            append(if (chordWikiWidePunct(letter)) (letter.code - 0xFEE0).toChar() else letter)
        }
    }

    private fun chordWikiWidePunct(letter: Char): Boolean {
        val code = letter.code
        return code in 0xFF01..0xFF0F || code in 0xFF1A..0xFF20 || code in 0xFF3B..0xFF40 || code in 0xFF5B..0xFF5E
    }

    // delay() of nothing returns at once, so a pause of none needs no check of its own.
    private suspend fun chordWikiPause() = delay(chordWikiPauseMs)

    /** [song]'s title, or the Japanese one it's known by when the one given isn't. */
    private suspend fun chordWikiTitle(song: MusicMetadata, otherNames: suspend () -> OtherNames): String {
        if (japanese(song.title)) return song.title
        return try {
            otherNames().titles.firstOrNull(::japanese) ?: song.title
        } catch (e: IOException) {
            song.title
        }
    }

    /** The edit page of [title], or null when its editor is empty. The address shown is the sheet's page, not the editor. */
    private suspend fun chordWikiSheet(pages: ChordWikiPages, title: String): ChordSheet? {
        val read = ChordSheets.fromChordWiki(pages.page(chordWikiEditUrl(title)))
        val chords = read.lines.sumOf { it.chords.size }
        note("wiki open title=$title editorLines=${read.lines.size} editorChords=$chords pageTitle=${read.title}")
        if (read.lines.isEmpty()) return null
        return ChordSheet(read.lines, read.capo, ChordSite.CHORDWIKI, chordWikiViewUrl(read.title.ifBlank { title }))
    }

    /**
     * U-FRET's first sheet for the song that [fits]. It only knows songs by their Japanese names, so
     * a title in two, "プラスティック・ラブ - Plastic Love", is looked up by its Japanese one, and a
     * song named in Latin letters by the names NetEase knows it by: "君は1000%" for "Kimi ha 1000%",
     * "Lemon" by "米津玄師" for Kenshi Yonezu.
     */
    private suspend fun fromUFret(song: MusicMetadata, otherNames: suspend () -> OtherNames, fits: (ChordSheet) -> Boolean): ChordSheet? {
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
            val listed = uFretResults(fetch(search.toString()))
            val ids = listed.filter { (_, _, named, by) ->
                SongNames.sameArtist(unescape(by.trim()), artist) && SongNames.soundsAlike(song.title, unescape(withoutTagged(named).trim()))
            }.sortedBy { (kind) -> kind == "beginner" }.map { (_, id) -> id }.take(MAX_SHEETS)
            for (id in ids) sheetOnUFret(id, fits)?.let { return it }
            if (listed.size < U_FRET_PAGE) return null
        }
        return null
    }

    /** U-FRET's sheet [id], if it [fits]. */
    private suspend fun sheetOnUFret(id: String, fits: (ChordSheet) -> Boolean): ChordSheet? {
        val url = "$U_FRET_SONG_URL$id"
        val lines = sheetPage(url).let { page -> uFretSheet.firstIn(page)?.let { page.substring(it.between.first - 1, it.between.last + 2) } } ?: return null
        return ChordSheet(ChordSheets.fromUFret(JSONArray(lines)), site = ChordSite.U_FRET, url = url).takeIf(fits)
    }

    /**
     * U-FRET's first sheet for [title] that [fits], searched for by [key]: by the artist first, then
     * by others, and the song's own before one made easier to play. U-FRET may name the artist
     * otherwise, in Japanese, so then by the names [otherNames] has for them.
     */
    private suspend fun searchUFret(song: MusicMetadata, key: String, title: String, otherNames: suspend () -> OtherNames, fits: (ChordSheet) -> Boolean): ChordSheet? {
        val search = U_FRET_SEARCH_URL.toHttpUrl().newBuilder().addQueryParameter("key", key).build()
        val found = uFretResults(fetch(search.toString())).asSequence()
            .filter { (_, _, named) -> SongNames.same(withoutTagged(named).trim(), title) }.toList()
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
            val sheet = laCuerdaSheet.firstBetween(sheetPage(url)) ?: return@firstSheet null
            // Its chords as Ultimate Guitar marks them, over the words they're played on.
            val content = unescape(laCuerdaChord.replaceEach(sheet) { "[ch]${it.groupValues[1]}[/ch]" }.let { withoutTags(it, least = 0) })
            ChordSheet(ChordSheets.fromUltimateGuitar(content), site = ChordSite.LA_CUERDA, url = url)
        }

    /** The pages of LaCuerda's sheets of words and chords for [title] by [song]'s artist, searched for by [key]: by the artist first, those best liked first. */
    private suspend fun laCuerdaSheets(song: MusicMetadata, title: String, key: String): List<String> {
        val search = LA_CUERDA_SEARCH_URL.toHttpUrl().newBuilder().addQueryParameter("exp", key).addQueryParameter("canc", "1").build()
        // A search that finds nothing is answered with a 500 and no page at all.
        val page = fetch(search.toString()) { code, body -> code == 500 && body.isBlank() }
        // Each song's page, by its row counted from the last.
        val files = laCuerdaFiles.firstBetween(page)?.split(',')?.map { it.trim().trim('\'') }.orEmpty()
        val last = laCuerdaLast.firstGroup(page)?.toInt() ?: return emptyList()
        var artist: Pair<String, String>? = null
        val found = mutableListOf<Pair<Boolean, String>>()
        laCuerdaResult.matchesIn(page).forEach { match ->
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

    /** J-Total's first sheet for the song that [fits], by the names it's given, then, unless those are in Japanese already, by those it has there. */
    private suspend fun fromJTotal(song: MusicMetadata, otherNames: suspend () -> OtherNames, fits: (ChordSheet) -> Boolean): ChordSheet? =
        searchJTotal(song, listOf(song.title) + titleParts(song.title), fits)
            ?: if (japanese(song.title) && japanese(song.artist)) null else searchJTotal(song, otherNames().titles, fits)

    private suspend fun searchJTotal(song: MusicMetadata, names: List<String>, fits: (ChordSheet) -> Boolean): ChordSheet? =
        firstSheet(names, { name -> jTotalSheets(song, name) }, fits) { url ->
            val page = fetch(url, charset = J_TOTAL_PAGE_CHARSET) { code, _ -> code == 404 }
            val sheet = ChordSheets.jTotalSheet(page) ?: return@firstSheet null
            ChordSheet(ChordSheets.fromJTotal(unescape(sheet), ChordSheets.jTotalMonospace(page)), site = ChordSite.J_TOTAL, url = url)
        }

    /** The pages of J-Total's sheets of [name]: by the artist first, and its easier versions after its own. */
    private suspend fun jTotalSheets(song: MusicMetadata, name: String): List<String> {
        // It reads a search in Shift_JIS, as all it has is. A name with a letter that has no place there would go as a "?", and find nothing.
        if (!J_TOTAL_CHARSET.newEncoder().canEncode(name)) return emptyList()
        val search = J_TOTAL_SEARCH_URL.toHttpUrl().newBuilder()
            .addEncodedQueryParameter("word", URLEncoder.encode(name, J_TOTAL_CHARSET.name()))
            .addQueryParameter("method", "and")
            .build()
        return jTotalResults(fetch(search.toString(), charset = J_TOTAL_CHARSET)).asSequence()
            .filter { (_, title, artist) -> named(title.replace(jTotalVersion, ""), name, byArtist(artist, song)) }
            .sortedWith(compareBy({ (_, _, artist) -> !byArtist(artist, song) }, { (_, title) -> jTotalVersion.containsMatchIn(title) }))
            .map { (path) -> "$J_TOTAL_URL$path" }
            .toList()
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
            val sheet = cifraClubSheet.firstBetween(page) ?: return@firstSheet null
            // Its chords as Ultimate Guitar marks them, over the words they're played on, without its tabs.
            val content = unescape(cifraClubChord.replaceParts(cifraClubTabs.cutFrom(sheet)) { "[ch]$it[/ch]" }.let { withoutTags(it, least = 0) }.replace("\u200B", ""))
            ChordSheet(ChordSheets.fromUltimateGuitar(content), capo = cifraClubCapo.firstGroup(page)?.toInt() ?: 0, site = ChordSite.CIFRA_CLUB, url = url)
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
        val wanted = titleWords.matchesIn(Readings.simplified(SongNames.bareTitle(ours))).map { SongNames.normalize(it.value) }.toList()
        val words = titleWords.matchesIn(Readings.simplified(title)).map { SongNames.normalize(it.value) }.toList()
        return wanted.sumOf { it.length } >= 2 && words.windowed(wanted.size).any { it == wanted }
    }

    /** [artist] is the song's, [song]: one with no artist is nobody's. */
    private fun byArtist(artist: String, song: MusicMetadata) = song.artist.isNotBlank() && SongNames.sameArtist(artist, song.artist)

    /** The other names [title] is in, when it's two put together, "プラスティック・ラブ - Plastic Love". */
    private fun titleParts(title: String): List<String> =
        title.split(titleBreak).map { it.trim() }.flatMap { listOf(it, SongNames.bareTitle(it)) }.filter { it.length >= 2 && it != title }.distinct()

    /** A song's names in Japanese, and its artists' names, as NetEase or Japan's Apple Music store has them. */
    private class OtherNames(val titles: List<String>, val artists: List<String>)

    /**
     * [ask]'s answer, asked for the first time it's wanted and kept for the rest. One that failed isn't
     * kept, so the next step asks again. ChordWiki and U-FRET can ask together; only one request goes out.
     */
    private fun <T> askedOnce(ask: suspend () -> T): suspend () -> T {
        val gate = Mutex()
        var kept: T? = null
        return { gate.withLock { kept ?: ask().also { kept = it } } }
    }

    /**
     * The names NetEase has [song] by: in Japanese, "君は1000%" for "Kimi ha 1000%", and of the
     * artists of a song of its name, "竹内まりや" for Mariya Takeuchi, as it knows many by both.
     * It knows a song with a name of its own in English, "The Cruel Angel's Thesis", only by that,
     * so then by the names Japan's Apple Music store has it by: "残酷な天使のテーゼ", by "高橋洋子".
     */
    private suspend fun otherNames(song: MusicMetadata): OtherNames {
        val netEase = netEaseNames(song)
        if (netEase.titles.isNotEmpty()) return netEase
        val inJapan = namesInJapan(song)
        return OtherNames(inJapan.map { it.first }.distinct().take(MAX_NAMES), (netEase.artists + inJapan.map { it.second }).distinct())
    }

    /**
     * The titles and artists Japan's Apple Music store has for songs found by [song]'s names, those
     * named in Japanese, without what a version adds: "残酷な天使のテーゼ" for "残酷な天使のテーゼ (2009VERSION)".
     */
    private suspend fun namesInJapan(song: MusicMetadata): List<Pair<String, String>> {
        val search = LyricsFinder.ITUNES_SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("term", "${song.title} ${song.artist}")
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "10")
            .addQueryParameter("country", "jp")
            .build()
        val list = JSONObject(fetch(search.toString())).optJSONArray("results")
        val found = list?.let { all -> (0 until all.length()).mapNotNull { all.optJSONObject(it) } }.orEmpty()
        return found.map { SongNames.bareTitle(it.optString("trackName")) to it.optString("artistName").trim() }.filter { (title) -> japanese(title) }
    }

    /** The names NetEase has [song] by, in Japanese, and the artists of a song of its name. */
    private suspend fun netEaseNames(song: MusicMetadata): OtherNames {
        val search = NETEASE_SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("s", "${song.title} ${song.artist}")
            .addQueryParameter("type", "1")
            .addQueryParameter("limit", "10")
            .build()
        val list = JSONObject(fetch(search.toString(), NETEASE_REFERER)).optJSONObject("result")?.optJSONArray("songs")
        val songs = list?.let { all -> (0 until all.length()).mapNotNull { all.optJSONObject(it) } }.orEmpty()
        val titles = songs.map { SongNames.bareTitle(it.optString("name")) }.filter(::japanese).distinct().take(MAX_NAMES)
        val names = listOf(song.title) + titleParts(song.title)
        val artists = songs.filter { found -> names.any { SongNames.same(found.optString("name"), it) } }.flatMap { found ->
            found.optJSONArray("artists")?.let { all -> (0 until all.length()).mapNotNull { all.optJSONObject(it) }.map { it.optString("name") } }.orEmpty()
        }.distinct()
        return OtherNames(titles, artists)
    }

    /** What the first group of this matches in [text], if it does. */
    private fun Regex.firstGroup(text: String): String? = find(text)?.let { it.groupValues[1] }

    /** Whether [text] is written in Japanese, kana or kanji. */
    private fun japanese(text: String) = text.any { it in '぀'..'ヿ' || it in '一'..'鿿' }

    /** What Ultimate Guitar's page has for its app to show, written into the page. */
    private fun ugStore(page: String): JSONObject? =
        ugData.firstGroup(page)?.let { JSONObject(unescape(it)).optJSONObject("store")?.optJSONObject("page") }

    /** A sheet's page. One that's gone, though the site's search still lists it, has no sheet in it: that sheet isn't there, it's not a site that won't answer. */
    private suspend fun sheetPage(url: String, asPhone: Boolean = false) = fetch(url, asPhone = asPhone) { code, _ -> code == 404 }

    /** [found] tells a failed answer that's still the site's own, e.g. a search that found nothing, from a site that wouldn't answer. */
    private suspend fun fetch(url: String, referer: String? = null, asPhone: Boolean = false, charset: Charset? = null, found: (Int, String) -> Boolean = { _, _ -> false }): String {
        // Ultimate Guitar shows its search results only to a computer's browser, and Cifra Club its pages only to a phone's.
        val request = Request.Builder().url(url).apply {
            if (asPhone) asPhoneBrowser() else header("User-Agent", BROWSER)
            referer?.let { header("Referer", it) }
        }.get().build()
        return client.newCall(request).answer(ioDispatcher) { response ->
            val body = charset?.let { String(response.body.bytesAtMost(), it) } ?: response.body.stringAtMost()
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
        const val J_TOTAL_URL = "https://music.j-total.net/data/"
        const val J_TOTAL_SEARCH_URL = "https://music.j-total.net/db/search.cgi?mode=search"
        const val LA_CUERDA_SEARCH_URL = "${LA_CUERDA_URL}busca.php"
        const val CIFRA_CLUB_URL = "https://www.cifraclub.com.br/"
        const val CIFRA_CLUB_SEARCH_URL = "https://solr.sscdn.co/cc/h2/"

        /** What Cifra Club's search calls a song, among artists and other things. */
        const val CIFRA_CLUB_SONG = "2"
        const val BROWSER = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

        /** Between ChordWiki's pages. A person looks a song up about this fast, and no faster. */
        const val CHORD_WIKI_PAUSE_MS = 400L

        /** Logcat tag for one lookup: the title passed in, what each site answered, and why a sheet was dropped. */
        const val CHORD_LOG = "ChordFinder"

        /** Chord names named in one dropped-sheet line. The rest are counted, not listed. */
        private const val DROPPED_LOG = 24

        private val titleWords = phoneRegex("""[\p{L}\p{N}]+""")

        val entity = phoneRegex("""&(#\d+|#x[0-9a-fA-F]+|[a-zA-Z]+);""")
        /** The entities pages use most, and one read otherwise than HTML says: a hidden hyphen is no letter of the word. */
        val named = mapOf("quot" to "\"", "amp" to "&", "lt" to "<", "gt" to ">", "apos" to "'", "nbsp" to " ", "shy" to "")

        /** An HTML attribute's text as written, spaces and all, which [android.text.Html] would run together. */
        fun unescape(text: String): String = entity.replaceEach(text) { match ->
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
