package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A page comes from a site, and can be anything, very long too. Main's patterns for its parts, `open[^>]*>(.*?)close`,
 * looked to the end of the page from each open that never closed: time squared. Here, each reader that took their place,
 * on long pages of the marks it looks for, each alone and after another, so opens that never close come up.
 */
class LongPagesTest {
    @Test
    fun noPageTakesLongToRead() {
        val slow = READERS.flatMap { reader ->
            Growth.outOfStep({ pages(reader.marks, it) }, LENGTH, { page -> "${reader.name}: ${page.take(30).replace("\n", "\\n")}…" }, reader.read)
        }
        assertEquals(emptyList<String>(), slow)
    }

    /** Pages about [length] long, each of [marks] again and again, and each two of them in turn. */
    private fun pages(marks: List<String>, length: Int): List<String> =
        (marks + marks.flatMap { first -> marks.map { first + it } }).map { it.repeat(length / it.length + 1) }

    /** What reads a page, by [name], and the [marks] it looks for. */
    private class Reader(val name: String, val marks: List<String>, val read: (String) -> Any?)

    private companion object {
        /** How long a page is read, and then twice it: pages are long, and each reading quick. */
        const val LENGTH = 20_000

        val SCRIPT = listOf("</script>", "<", ">", "}", "};", "a")
        val READERS = listOf(
            Reader("Translator's style sheets", listOf("<style", "<style>", ">", "</style", "a")) { styleSheets.cutFrom(it) },
            Reader("J-Total's font", listOf("<!--", "<style", "<style>", ">", "</style>", "-->", "tt", " tt{", "}", "/*", "*/")) { ChordSheets.jTotalMonospace(it) },
            Reader("J-Total's sheet", listOf("<a ", "cd/", ">", "x", "</a>", "<br>", " ", "<", "\n", "<a cd/>E", "on", "<b>")) { ChordSheets.fromJTotal(it) },
            Reader("ChordWiki's editors", listOf("<textarea", "<textarea>", ">", "</textarea")) { ChordSheets.textarea.allIn(it).count() },
            Reader("ChordWiki's links", listOf("<a", " href=\"", "\"", ">", "</a>", "<", "x")) { chordWikiLinks(it).count() },
            Reader("ChordWiki's search", listOf("<a href=\"/wiki/x\">", "<b>", "</a>", "<", " - ")) { chordWikiHits(it) },
            Reader("a page's title", listOf("<title", "<title>", ">", "<", "</title")) { MetadataParsers.titleTag.firstIn(it) },
            Reader("YouTube's data", listOf("var ytInitialData = {") + SCRIPT) { MetadataParsers.youtubeData.firstIn(it) },
            Reader("Apple Music's data", listOf("""<script type="application/json" id="serialized-server-data">""") + SCRIPT) { MetadataParsers.appleData.firstIn(it) },
            Reader("Spotify's data", listOf("""<script id="__NEXT_DATA__" type="application/json">""") + SCRIPT) { MetadataParsers.spotifyData.firstIn(it) },
            Reader("Bandcamp's data", listOf("""<script type="application/ld+json"""") + SCRIPT) { MetadataParsers.bandcampData.firstIn(it) },
            Reader("U-FRET's sheet", listOf("var ufret_chord_datas = [", "]", "];", "\n", "a")) { uFretSheet.firstIn(it) },
            Reader("U-FRET's search", listOf("""c-list__item normal-chord"><a href="/song.php?data=1"""", """c-list__title">""", "</p>", """<p class="c-list__artist">""")) {
                uFretResults(it)
            },
            Reader("J-Total's search", listOf("""<a href="//music.j-total.net/data/a.html" target=""><font size="3"><b>""", "</b></font></a>", "歌：", "/")) { jTotalResults(it) },
            Reader("LaCuerda's takes", listOf("var fns=[", "]", ";", "];")) { laCuerdaFiles.firstIn(it) },
            Reader("LaCuerda's sheet", listOf("<div id=t_body><pre>", "</pre", "<")) { laCuerdaSheet.firstIn(it) },
            Reader("Cifra Club's sheet", listOf("<pre", " data-chord-content=\"true\"", ">", "</pre", "<")) { cifraClubSheet.firstIn(it) },
            Reader("Cifra Club's tabs", listOf("""<div class="tabs">""", "</div", "<")) { cifraClubTabs.cutFrom(it) },
            Reader("Cifra Club's chords", listOf("<b", "<b>", ">", "<", "</b")) { cifraClubChord.replaceParts(it) { chord -> "[ch]$chord[/ch]" } },
            Reader("Letras's words", listOf("""<div class="lyric-content""", "\"", "\">", "</div")) { LyricsFinder.letrasWords.firstIn(it) },
            Reader("Letras's original", listOf("""<div class="lyric-translation-left">""", " ", "<strong>", "</strong>", "</div")) { LyricsFinder.letrasOriginal(it) },
            Reader(
                "Genius's boxes",
                listOf(
                    "<div", " data-lyrics-container=\"true\"", " data-exclude-from-selection=\"true\"", ">", "</div>", "<di", "v",
                    "<div data-exclude-from-selection=\"true\">x</div>", "<div data-lyrics-container=\"true\">x</div>",
                ),
            ) { LyricsFinder.geniusBoxes(LyricsFinder.withoutGeniusNotWords(it)).count() },
            Reader("Letras's ads", listOf("<div id=\"pub_", "\">", "</div>", "<div>", "<di", "v", "<div id=\"pub_\">x</div>", "a")) { LyricsFinder.withoutLetrasAds(it) },
            Reader("UtaTen's words", listOf("""<div class="hiragana"""", " ", ">", "</div")) { SungReadings.words.firstIn(it) },
            Reader("UtaTen's readings", listOf("""<span class="ruby"><span class="rb">""", """</span><span class="rt">""", "</span>")) { SungReadings.rubies(it).count() },
            Reader("UtaTen's search", listOf("""<p class="searchResult__title"><a href="/lyric/a1/">""", """<td class="searchResult__artist"><p><a """, ">", "</a")) {
                SungReadings.searchRows(it).count()
            },
            Reader("Qobuz's search", listOf("""<div class="ListItem">""", "</li", """class="ListItem__title"""", ">", "</a>", """<p class="ListItem__artists">""")) {
                ExactMatcher.qobuzItems.allIn(it).count() + (ExactMatcher.qobuzTitle(it)?.first?.length ?: 0)
            },
            Reader("tags", listOf("<", ">", "</", "<a", " ")) { withoutTags(it) + withoutTags(it, least = 0) + withoutTagged(it) },
            Reader("chords in brackets", listOf("[", "]", "a")) { ChordSheets.brackets(it).count() },
            Reader("ChatGPT's sources and links", listOf("([", "[", "]", "](", "(", "](https://", ")", " ")) { plainText(it) },
        )
    }
}
