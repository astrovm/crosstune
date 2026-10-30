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
     */
    suspend fun of(song: MusicMetadata, lines: List<String>, read: List<LineReading>): List<ReadingFix>? {
        val key = "utaten|$CACHE_VERSION|${SongNames.normalize(song.title)}|${SongNames.normalize(song.artist)}|${lines.hashCode()}"
        cache?.get(key)?.let { kept -> fromCache(kept)?.let { return it } }
        val fixes = withTimeoutOrNull(TIMEOUT_MS) {
            try {
                found(song, lines, read)
            } catch (_: IOException) {
                null
            }
        } ?: return null
        cache?.let { cache ->
            cache.put(key, JSONArray(fixes.map { JSONArray().put(it.line).put(it.kanji).put(it.reading) }).toString())
            cache.save()
        }
        return fixes
    }

    private suspend fun found(song: MusicMetadata, lines: List<String>, read: List<LineReading>): List<ReadingFix> {
        val ours = lines.count { line -> line.any(Readings::isHan) }
        if (ours == 0) return emptyList()
        // The song by its singer first, then by its name alone, for a singer named otherwise there.
        val asked = mutableSetOf<String>()
        var best = emptyList<ReadingFix>()
        for (title in titlesOf(song.title)) {
            for (artist in listOf(song.artist, "")) {
                for (page in songsFound(title, artist, song.artist)) {
                    if (!asked.add(page)) continue
                    val fixes = matched(lines, read, linesOf(get(page)))
                    if (linesFixed(fixes) > linesFixed(best)) best = fixes
                    // Enough lines alike: it's this song.
                    if (linesFixed(best) * 3 >= ours || asked.size >= MAX_PAGES) return best.takeIf { linesFixed(it) * 3 >= ours }.orEmpty()
                }
            }
        }
        return best.takeIf { linesFixed(it) * 3 >= ours }.orEmpty()
    }

    /** The names to look [title] up by: whole, and each name in it, as 愛が止まらない and Turn It Into Love in "愛が止まらない ～Turn It Into Love～". */
    private fun titlesOf(title: String): List<String> =
        (SongNames.titleNames(title) + SongNames.bareTitle(title) + title.split(titleMark)).map(String::trim).filter(String::isNotEmpty).distinct()

    private fun linesFixed(fixes: List<ReadingFix>) = fixes.map { it.line }.distinct().size

    /** UtaTen's songs called [title] by [artist], any singer when that's empty: their pages, [singer]'s own first. */
    private suspend fun songsFound(title: String, artist: String, singer: String): List<String> {
        val search = SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("sort", "popular_sort_asc")
            .addQueryParameter("artist_name", artist)
            .addQueryParameter("title", title)
            .build()
        val rows = searchRow.findAll(get(search.toString())).map { it.groupValues[1] to ChordFinder.unescape(it.groupValues[2]).trim() }.toList()
        return rows.sortedByDescending { (_, by) -> SongNames.sameArtist(by, singer) || SongNames.artistInside(singer, by) }.map { (page, _) -> SITE_URL + page }
    }

    private suspend fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", BROWSER).get().build()
        return client.newCall(request).answer(ioDispatcher) { response ->
            if (!response.isSuccessful) throw IOException("UtaTen returned ${response.code}")
            response.body.stringAtMost()
        }
    }

    private fun fromCache(kept: String): List<ReadingFix>? = try {
        val array = JSONArray(kept)
        (0 until array.length()).map { array.getJSONArray(it).let { fix -> ReadingFix(fix.getInt(0), fix.getString(1), fix.getString(2)) } }
    } catch (_: JSONException) {
        null
    }

    internal companion object {
        const val SITE_URL = "https://utaten.com"
        const val SEARCH_URL = "$SITE_URL/search"
        const val TIMEOUT_MS = 10_000L

        /** How many of UtaTen's pages are read at most for a song. */
        const val MAX_PAGES = 3

        /** Bumped when what was found before should be looked up again: 2 for songs a phone's pages found nothing for. */
        const val CACHE_VERSION = 2

        /** It answers a browser; a computer's, as a phone's gets pages laid out otherwise, which say nothing here. */
        const val BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        /** How alike two lines have to be, from 0 to 1, for one's readings to be the other's. */
        const val ALIKE = 0.4

        private val searchRow = Regex(
            """<p class="searchResult__title">\s*<a href="(/lyric/[A-Za-z0-9]+/)">.*?<td class="searchResult__artist">\s*<p>\s*<a [^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )
        private val words = Regex("""<div class="hiragana"\s*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
        private val ruby = Regex("""<span class="ruby"><span class="rb">(.*?)</span><span class="rt">(.*?)</span></span>""", RegexOption.DOT_MATCHES_ALL)
        private val titleMark = Regex("[()（）\\[\\]～~〜「」『』]| - ")
        private val lineBreak = Regex("""<br\s*/?>""")
        private val tag = Regex("""<[^>]+>""")

        /** The words on a song's page, line by line, each in pieces, kanji with how they're sung. */
        fun linesOf(page: String): List<List<Ruby>> {
            val body = words.find(page)?.let { it.groupValues[1] } ?: return emptyList()
            return body.split(lineBreak).map(String::trim).map { line ->
                val parts = mutableListOf<Ruby>()
                var at = 0
                ruby.findAll(line).forEach { match ->
                    plain(line.substring(at, match.range.first))?.let { parts += Ruby(it) }
                    parts += Ruby(plain(match.groupValues[1]).orEmpty(), plain(match.groupValues[2]))
                    at = match.range.last + 1
                }
                plain(line.substring(at))?.let { parts += Ruby(it) }
                parts
            }.filter { line -> line.any { it.text.isNotBlank() } }
        }

        private fun plain(html: String): String? = ChordFinder.unescape(html.replace(tag, "")).replace('\n', ' ').takeIf { it.isNotEmpty() }

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
                theirs[best].filter { part -> part.reading != null && part.text.any(Readings::isHan) && part.text in lines[index] }
                    .map { ReadingFix(index, it.text, it.reading!!) }
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
