package com.astrovm.crosstune

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException

/**
 * How a Japanese song's kanji are sung, from UtaTen, a lyrics site that writes the kana over them
 * as the singer says them: 都会 as まち, 存在 as こと, which no dictionary gives. Its words are
 * typed by people, so they're not quite ours, かさなり for 重なり or JUST for Just; each of our
 * lines takes the readings of the line there that's most like it, and none when none is.
 */
internal class SungReadings(
    private val client: OkHttpClient,
    /** What was found for each song, so a song read again asks nothing. */
    private val cache: LookupCache? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    /**
     * The readings sung in [song]'s [lines], [read] as the dictionary reads them, that UtaTen has,
     * line by line; empty when it has none, null when it couldn't be asked.
     * [stillWanted] is read again before each request. Once readings are turned off, nothing more is asked,
     * and an answer that arrives after that is not kept, so it is not remembered as the song having none.
     */
    suspend fun of(
        song: MusicMetadata,
        lines: List<String>,
        read: List<LineReading>,
        stillWanted: () -> Boolean = { true },
    ): List<ReadingFix>? {
        val key = cacheKey(song, lines, CACHE_VERSION)
        cache?.get(key)?.let { kept -> fromCache(kept)?.let { return it } }
        // Version 3 kept real readings under its own key. Only its "none" was a page UtaTen doesn't write.
        keptFromBefore(song, lines)?.let { return it }
        if (!stillWanted()) return null
        val fixes = withTimeoutOrNull(TIMEOUT_MS) {
            try {
                found(song, lines, read, stillWanted)
            } catch (_: IOException) {
                null
            }
        } ?: return null
        if (!stillWanted()) return null
        keep(key, fixes)
        return fixes
    }

    /** Readings version 3 kept for [song]'s [lines], when it kept some. None from then is asked again. */
    private suspend fun keptFromBefore(song: MusicMetadata, lines: List<String>): List<ReadingFix>? {
        val kept = cache?.get(cacheKey(song, lines, BEFORE_NONE_WAS_A_PAGE)) ?: return null
        return fromCache(kept)?.takeIf { it.isNotEmpty() }
    }

    private suspend fun keep(key: String, fixes: List<ReadingFix>) {
        val cache = cache ?: return
        cache.put(key, JSONArray(fixes.map { JSONArray().put(it.line).put(it.kanji).put(it.reading) }).toString())
        cache.save()
    }

    private fun cacheKey(song: MusicMetadata, lines: List<String>, version: Int) =
        "utaten|$version|${SongNames.normalize(song.title)}|${SongNames.normalize(song.artist)}|${lines.hashCode()}"

    private suspend fun found(song: MusicMetadata, lines: List<String>, read: List<LineReading>, stillWanted: () -> Boolean): List<ReadingFix>? {
        val ours = lines.count { line -> line.any(Readings::isHan) }
        if (ours == 0) return emptyList()
        // The song by its singer first, then by its name alone, for a singer named otherwise there.
        val asked = mutableSetOf<String>()
        var best = emptyList<ReadingFix>()
        for (title in titlesOf(song.title)) {
            for (artist in listOf(song.artist, "")) {
                val pages = songsFound(title, artist, song.artist, stillWanted) ?: return null
                val readPages = readPages(pages, asked, lines, read, ours, best, stillWanted) ?: return null
                best = readPages.best
                if (readPages.done) return best.takeIf { linesFixed(it) * 3 >= ours }.orEmpty()
            }
        }
        return best.takeIf { linesFixed(it) * 3 >= ours }.orEmpty()
    }

    /** [pages] read into [best], or null once readings are no longer wanted. [done] once this song is decided. */
    private suspend fun readPages(
        pages: List<String>,
        asked: MutableSet<String>,
        lines: List<String>,
        read: List<LineReading>,
        ours: Int,
        best: List<ReadingFix>,
        stillWanted: () -> Boolean,
    ): PagesRead? {
        var current = best
        for (page in pages) {
            if (!asked.add(page)) continue
            val html = get(page, stillWanted) ?: return null
            // A page without the words isn't one of its songs': it couldn't be asked, rather than it has none.
            if (words.firstIn(html) == null) throw IOException("UtaTen's page for a song has no words")
            val fixes = matched(lines, read, linesOf(html))
            if (linesFixed(fixes) > linesFixed(current)) current = fixes
            // Enough lines alike: it's this song.
            if (linesFixed(current) * 3 >= ours || asked.size >= MAX_PAGES) return PagesRead(current, done = true)
        }
        return PagesRead(current, done = false)
    }

    private class PagesRead(val best: List<ReadingFix>, val done: Boolean)

    /**
     * The names to look [title] up by: whole, and each name in it, as 愛が止まらない and Turn It Into Love in "愛が止まらない ～Turn It Into Love～";
     * and without its ! and ?, which UtaTen may write otherwise, ！ for !, and then finds nothing by.
     */
    private fun titlesOf(title: String): List<String> =
        (SongNames.titleNames(title) + SongNames.bareTitle(title) + title.split(titleMark)).map(String::trim).filter(String::isNotEmpty)
            .flatMap { listOf(it, it.replace(exclaimed, "").trim()) }.filter(String::isNotEmpty).distinct()

    private fun linesFixed(fixes: List<ReadingFix>) = fixes.map { it.line }.distinct().size

    /** UtaTen's songs called [title] by [artist], any singer when that's empty: their pages, [singer]'s own first. Null once no longer wanted. */
    private suspend fun songsFound(title: String, artist: String, singer: String, stillWanted: () -> Boolean): List<String>? {
        val search = SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("sort", "popular_sort_asc")
            .addQueryParameter("artist_name", artist)
            .addQueryParameter("title", title)
            .build()
        val found = get(search.toString(), stillWanted) ?: return null
        val rows = searchRows(found).map { (path, artist) -> path to ChordFinder.unescape(artist).trim() }.toList()
        // Neither songs nor its "no words found": another page, one asking whether it's a person, say, which says nothing of the song.
        if (rows.isEmpty() && NONE_FOUND !in found) throw IOException("UtaTen answered its search with a page it doesn't write")
        return rows.sortedByDescending { (_, by) -> SongNames.sameArtist(by, singer) || SongNames.artistInside(singer, by) }.map { (page, _) -> SITE_URL + page }
    }

    /** [url], or null when readings are no longer wanted, before the request leaves. */
    private suspend fun get(url: String, stillWanted: () -> Boolean): String? {
        if (!stillWanted()) return null
        // Its search answers nothing to a request that doesn't say it came from its own pages.
        val request = Request.Builder().url(url).header("User-Agent", BROWSER).header("Referer", "$SITE_URL/").get().build()
        return client.newCall(request).answer(ioDispatcher) { response ->
            if (!response.isSuccessful) throw IOException("UtaTen returned ${response.code}")
            response.body.stringAtMost()
        }
    }

    private fun fromCache(kept: String): List<ReadingFix>? = try {
        val array = JSONArray(kept)
        (0 until array.length()).map { array.getJSONArray(it).let { fix -> ReadingFix(fix.getInt(0), fix.getString(1), fix.getString(2), Reader.SUNG) } }
    } catch (_: JSONException) {
        null
    }

    internal companion object {
        const val SITE_URL = "https://utaten.com"
        const val SEARCH_URL = "$SITE_URL/search"
        const val TIMEOUT_MS = 10_000L

        /** How many of UtaTen's pages are read at most for a song. */
        const val MAX_PAGES = 3

        /**
         * Bumped when what was found before should be looked up again: 2 for songs a phone's pages found nothing for,
         * 3 for those its search answered with nothing, as it does without a Referer, 4 for those it answered with
         * another page, which was taken as none. A version 3 entry that holds readings is still read. Only its none was that page.
         */
        const val CACHE_VERSION = 4

        /** The cache version that could keep "none" for a page UtaTen doesn't write. Readings it kept are still used. */
        private const val BEFORE_NONE_WAS_A_PAGE = 3

        /** What its search says when it finds no words. */
        private const val NONE_FOUND = "歌詞が見つかりませんでした"

        /** It answers a browser; a computer's, as a phone's gets pages laid out otherwise, which say nothing here. */
        const val BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        /** How alike two lines have to be, from 0 to 1, for one's readings to be the other's. */
        const val ALIKE = 0.4

        private val searchRow = phoneRegex("""<p class="searchResult__title">\s*+<a href="(/lyric/[A-Za-z0-9]++/)">""")
        private val searchArtist = phoneRegex("""<td class="searchResult__artist">\s*+<p>\s*+<a """)
        internal val words = PageParts(phoneRegex("""<div class="hiragana"\s*+>"""), phoneRegex("</div>"), tagEnd = null)

        /**
         * Each song in a search's results, its page and its artist, as main's pattern, [searchRow], then
         * `.*?<td class="searchResult__artist">\s*<p>\s*<a [^>]*>(.*?)</a>`, read them: the first artist after the
         * song's link, the next song's too, up to the first `</a>` after its tag.
         */
        fun searchRows(page: String): Sequence<Pair<String, String>> = sequence {
            val rows = searchRow.toPattern().matcher(page)
            val artists = searchArtist.toPattern().matcher(page)
            var artistStart = -1
            var artistEnd = -1
            var from = 0
            while (rows.find()) {
                if (rows.start() < from) continue
                while (artistStart < rows.end()) {
                    if (!artists.find()) return@sequence
                    artistStart = artists.start()
                    artistEnd = artists.end()
                }
                val tagClose = page.indexOf('>', artistEnd).orNone() ?: return@sequence
                // From the `>`, which no `</a>` starts at.
                val end = page.indexOf(LINK_END, tagClose).orNone() ?: return@sequence
                yield(page.substring(rows.start(1), rows.end(1)) to page.substring(tagClose + 1, end))
                // Past the `</a>`: a row has none in it, so none starts in it either.
                from = end
            }
        }

        private const val LINK_END = "</a>"

        /**
         * Each kanji in [line] with how it's sung, its range and both, as main's
         * `<span class="ruby"><span class="rb">(.*?)</span><span class="rt">(.*?)</span></span>` read them.
         */
        internal fun rubies(line: String): Sequence<Triple<IntRange, String, String>> = sequence {
            var at = line.indexOf(RUBY)
            while (at >= 0) {
                val written = at + RUBY.length
                val middle = line.indexOf(RUBY_MIDDLE, written).orNone() ?: return@sequence
                val sung = middle + RUBY_MIDDLE.length
                val end = line.indexOf(RUBY_END, sung).orNone() ?: return@sequence
                yield(Triple(at until end + RUBY_END.length, line.substring(written, middle), line.substring(sung, end)))
                // From its end: a ruby has no `</span>` in it, so none starts in it either.
                at = line.indexOf(RUBY, end)
            }
        }

        private const val RUBY = """<span class="ruby"><span class="rb">"""
        private const val RUBY_MIDDLE = """</span><span class="rt">"""
        private const val RUBY_END = "</span></span>"
        private val exclaimed = phoneRegex("[!?！？]")
        private val titleMark = phoneRegex("[()（）\\[\\]～~〜「」『』]| - ")
        private val lineBreak = phoneRegex("""<br\s*/?>""")

        /** The words on a song's page, line by line, each in pieces, kanji with how they're sung. */
        fun linesOf(page: String): List<List<Ruby>> {
            // One return: no words is no lines, as any empty list.
            return words.firstBetween(page)?.split(lineBreak).orEmpty().map(String::trim).map { line ->
                val parts = mutableListOf<Ruby>()
                var at = 0
                for ((range, written, sung) in rubies(line)) {
                    plain(line.substring(at, range.first))?.let { parts += Ruby(it) }
                    parts += Ruby(plain(written).orEmpty(), plain(sung))
                    at = range.last + 1
                }
                plain(line.substring(at))?.let { parts += Ruby(it) }
                parts
            }.filter { line -> line.any { it.text.isNotBlank() } }
        }

        private fun plain(html: String): String? = ChordFinder.unescape(withoutTags(html)).replace('\n', ' ').takeIf { it.isNotEmpty() }

        /**
         * The readings [theirs] gives [lines], [read] as the dictionary reads them: each line takes
         * those of the line there most like it, as written or as read, when they're alike enough.
         */
        fun matched(lines: List<String>, read: List<LineReading>, theirs: List<List<Ruby>>): List<ReadingFix> {
            val written = theirs.map { line -> letters(line.joinToString("") { it.text }) }
            val said = theirs.map { line -> letters(line.joinToString("") { it.reading ?: it.text }) }
            return lines.indices.flatMap { index ->
                val text = letters(lines[index])
                val sound = letters(read.getOrNull(index)?.reading ?: lines[index])
                val likeness = theirs.indices.map { maxOf(alike(text, written[it]), alike(sound, said[it])) }
                val best = likeness.indices.maxByOrNull { likeness[it] }?.takeIf { likeness[it] >= ALIKE } ?: return@flatMap emptyList()
                theirs[best].filter { part -> part.text.any(Readings::isHan) && part.text in lines[index] }
                    .mapNotNull { part -> part.reading?.let { ReadingFix(index, part.text, it, Reader.SUNG) } }
            }
        }

        /** Letters only, kana all hiragana and Latin lowercase, so lines are compared as they'd be read. */
        private fun letters(text: String) = Readings.hiragana(text.filter(Char::isLetterOrDigit).lowercase())

        /** How alike two lines are, from 0 to 1, by the pairs of letters they share. */
        fun alike(one: String, other: String): Double {
            val ones = one.zipWithNext().groupingBy { it }.eachCount()
            val others = other.zipWithNext().groupingBy { it }.eachCount()
            val total = ones.values.sum() + others.values.sum()
            if (total == 0) return if (one.isNotEmpty() && one == other) 1.0 else 0.0
            return 2.0 * ones.entries.sumOf { (pair, count) -> minOf(count, others[pair] ?: 0) } / total
        }
    }
}
