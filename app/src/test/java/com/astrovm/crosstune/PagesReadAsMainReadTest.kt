package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random
import kotlin.text.RegexOption.DOT_MATCHES_ALL
import kotlin.text.RegexOption.IGNORE_CASE

/**
 * Each reader of a page that took the place of one of main's patterns, on short pages made of the pieces where they
 * could differ, read as main's pattern read them: nested opens, a `<` or `>` in a tag, a part that never closes, line
 * breaks, and case. Main's patterns took time squared on a page of opens that never close; these, in step with it
 * (LinearPatternsTest). Quick, so mutation testing runs these before the app's slow tests.
 */
class PagesReadAsMainReadTest {
    @Test
    fun stylesAndEditorsAreReadAsMainReadThem() {
        val style = arrayOf("<style", "<STYLE", "<stylex", "</style>", "</Style>", "</style", ">", "<", "a", "\n", " ", "<style>")
        assertParts(styleSheets, main("""<style[^>]*>.*?</style>""", IGNORE_CASE, DOT_MATCHES_ALL), null, *style)
        assertParts(ChordSheets.styleBlock, main("""<style\b[^>]*>(.*?)</style>""", IGNORE_CASE, DOT_MATCHES_ALL), 1, *style)
        assertParts(
            ChordSheets.commentedStyle,
            main("""<!--\s*<style\b[^>]*>.*?</style>\s*-->""", IGNORE_CASE, DOT_MATCHES_ALL),
            null,
            *style, "<!--", "-->", "\u3000", "<!--<style>", "<!-- <STYLE a>", "</style>-->", "</Style> -->",
        )
        assertParts(
            ChordSheets.textarea,
            main("""<textarea\b[^>]*>(.*?)</textarea>""", IGNORE_CASE, DOT_MATCHES_ALL),
            1,
            "<textarea", "<TEXTAREA", "<textareas", "</textarea>", "</TextArea>", ">", "<", "a", "\n",
        )
        val sheet = main("""<style[^>]*>.*?</style>""", IGNORE_CASE, DOT_MATCHES_ALL)
        texts(*style).forEach { page -> assertEquals(page, page.replace(sheet, ""), styleSheets.cutFrom(page)) }
    }

    @Test
    fun aPagesTitleAndDataAreReadAsMainReadThem() {
        assertParts(
            MetadataParsers.titleTag,
            main("""<title[^>]*>([^<]*)</title>""", IGNORE_CASE),
            1,
            "<title", "<TITLE", "</title>", "</Title>", ">", "<", "a", "\n", "\u0085", "<title>",
        )
        val script = arrayOf("</script>", "<", ">", "a", "\n", "{", "}", "};", "};</script>")
        assertParts(
            MetadataParsers.youtubeData,
            main("""var ytInitialData = (\{.*?\});</script>""", DOT_MATCHES_ALL),
            1,
            "var ytInitialData = {", "var ytInitialData = ", *script,
            widen = 1,
            most = LONG,
        )
        assertParts(
            MetadataParsers.appleData,
            main("""<script type="application/json" id="serialized-server-data">(.*?)</script>""", DOT_MATCHES_ALL),
            1,
            """<script type="application/json" id="serialized-server-data">""", *script,
        )
        assertParts(
            MetadataParsers.spotifyData,
            main("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", DOT_MATCHES_ALL),
            1,
            """<script id="__NEXT_DATA__" type="application/json">""", *script,
        )
        assertParts(
            MetadataParsers.bandcampData,
            main("""<script type="application/ld\+json"[^>]*>(.*?)</script>""", DOT_MATCHES_ALL),
            1,
            """<script type="application/ld+json"""", """<script type="application/ld+json">""", *script,
        )
        val title = main("""<title[^>]*>([^<]*)</title>""", IGNORE_CASE)
        texts("<title", "</title>", ">", "<", "a").forEach { page ->
            assertEquals(page, title.find(page)?.groupValues?.get(1), MetadataParsers.titleTag.firstBetween(page))
        }
    }

    @Test
    fun chordSheetsAreReadAsMainReadThem() {
        assertParts(
            uFretSheet,
            main("""var ufret_chord_datas = (\[.*?]);"""),
            1,
            "var ufret_chord_datas = [", "var ufret_chord_datas = ", "]", ";", "];", "[", "a", "\n", "\r", "\u0085", "\u2028", "\u000B",
            widen = 1,
            most = LONG,
        )
        assertParts(laCuerdaFiles, main("""var fns=\[([^\]]*)];"""), 1, "var fns=[", "]", ";", "];", "[", "'a'", ",", "\n")
        assertParts(
            laCuerdaSheet,
            main("""<div id=t_body><pre>(.*?)</pre>""", IGNORE_CASE, DOT_MATCHES_ALL),
            1,
            "<div id=t_body><pre>", "<DIV ID=T_BODY><PRE>", "</pre>", "</PRE>", "<", "a", "\n",
        )
        assertParts(
            cifraClubSheet,
            main("""<pre[^>]*data-chord-content="true"[^>]*>(.*?)</pre>""", DOT_MATCHES_ALL),
            1,
            "<pre", " data-chord-content=\"true\"", "data-chord-content=", ">", "</pre>", "<", "a", "\n", "<pre data-chord-content=\"true\">",
            "<pre x data-chord-content=\"true\" y>",
        )
        assertParts(cifraClubTabs, main("""<div class="tabs">.*?</div>""", DOT_MATCHES_ALL), null, """<div class="tabs">""", "</div>", "<", ">", "a", "\n")
        val bold = main("""<b[^>]*>([^<]*)</b>""")
        val chord = arrayOf("<b", "<b>", "</b>", ">", "<", "a", "\n")
        assertParts(cifraClubChord, bold, 1, *chord)
        texts(*chord).forEach { page ->
            assertEquals(page, page.replace(bold) { "[ch]${it.groupValues[1]}[/ch]" }, cifraClubChord.replaceParts(page) { "[ch]$it[/ch]" })
        }
    }

    @Test
    fun songsWordsAreReadAsMainReadThem() {
        assertParts(
            LyricsFinder.letrasWords,
            main("""<div class="lyric-content[^"]*">(.*?)</div>""", DOT_MATCHES_ALL),
            1,
            """<div class="lyric-content""", "\"", ">", "\">", "</div>", "<", "a", "\n", """<div class="lyric-content x">""",
        )
        val box = arrayOf("<div", "<divs", " ", ">", "<", "a", "<div ")
        assertParts(
            LyricsFinder.geniusNotWords,
            main("""<div\b[^>]*data-exclude-from-selection="true"[^>]*>"""),
            null,
            *box, "data-exclude-from-selection=\"true\"", "data-exclude", "<div data-exclude-from-selection=\"true\">",
        )
        assertParts(LyricsFinder.geniusBox, main("""<div\b[^>]*data-lyrics-container="true"[^>]*>"""), null, *box, "data-lyrics-container=\"true\"", "<div x data-lyrics-container=\"true\"")
        assertParts(
            SungReadings.words,
            main("""<div class="hiragana"\s*>(.*?)</div>""", DOT_MATCHES_ALL),
            1,
            """<div class="hiragana"""", " ", "\u3000", ">", "</div>", "<", "a", "\n", """<div class="hiragana">""", """<div class="hiragana" >""",
        )
        assertParts(ExactMatcher.qobuzItems, main("""<div class="ListItem">.*?</li>""", DOT_MATCHES_ALL), null, """<div class="ListItem">""", "</li>", "<", ">", "a")
        val original = main("""<div class="lyric-translation-left">\s*(?:<strong>.*?</strong>)?(.*?)</div>""", DOT_MATCHES_ALL)
        texts(
            """<div class="lyric-translation-left">""", " ", "\n", "<strong>", "</strong>", "</div>", "<", "a", """<div class="lyric-translation-left"> <strong>""",
            "</strong>w</div>",
        ).forEach { page ->
            assertEquals(page, original.find(page)?.groupValues?.get(1), LyricsFinder.letrasOriginal(page))
        }
        val qobuz = main("""class="ListItem__title"[^>]*>(.*?)</a>\s*<p class="ListItem__artists">(.*?)</p>""", DOT_MATCHES_ALL)
        texts(
            """class="ListItem__title"""", """class="ListItem__title">""", ">", "</a>", " ", """<p class="ListItem__artists">""", "</p>", "<", "a",
            """</a> <p class="ListItem__artists">""", """</a> <p class="ListItem__artists">c</p>""",
            most = SHORT_PIECES,
        ).forEach { page ->
            assertEquals(page, qobuz.find(page)?.destructured?.let { (title, credit) -> title to credit }, ExactMatcher.qobuzTitle(page))
        }
    }

    @Test
    fun searchResultsAreReadAsMainReadThem() {
        val uFret = main(
            """c-list__item (normal|beginner)-chord">\s*<a href="/song\.php\?data=(\d+)".*?c-list__title">(.*?)</p>\s*<p class="c-list__artist">(.*?)</p>""",
            DOT_MATCHES_ALL,
        )
        texts(
            """c-list__item normal-chord"><a href="/song.php?data=1"""", """c-list__item beginner-chord"> <a href="/song.php?data=22"""",
            """c-list__title">""", "</p>", """ <p class="c-list__artist">""", "a",
            """c-list__item normal-chord"><a href="/song.php?data=1"c-list__title">t</p> <p class="c-list__artist">s</p>""",
        ).forEach { page -> assertEquals(page, uFret.findAll(page).map { it.groupValues.drop(1) }.toList(), uFretResults(page)) }
        val jTotal = main(
            """<a href="(?:https:)?//music\.j-total\.net/data/([^"]+\.html)" target=""><font size="3"><b>([^<]*)</b></font></a>.*?歌：([^/<]*)""",
            DOT_MATCHES_ALL,
        )
        texts(
            """<a href="//music.j-total.net/data/a.html" target=""><font size="3"><b>""", """<a href="https://music.j-total.net/data/b.html" target=""><font size="3"><b>""",
            "x", "</b></font></a>", "歌：", "/", "<", """<a href="//music.j-total.net/data/a.html" target=""><font size="3"><b>n</b></font></a>""", "歌：s/",
        ).forEach { page -> assertEquals(page, jTotal.findAll(page).map { it.groupValues.drop(1) }.toList(), jTotalResults(page)) }
        val utaTen = main(
            """<p class="searchResult__title">\s*<a href="(/lyric/[A-Za-z0-9]+/)">.*?<td class="searchResult__artist">\s*<p>\s*<a [^>]*>(.*?)</a>""",
            DOT_MATCHES_ALL,
        )
        texts(
            """<p class="searchResult__title"><a href="/lyric/a1/">""", """<p class="searchResult__title"> <a href="/lyric/b/">""",
            """<td class="searchResult__artist"><p> <a """, ">", "</a>", "<", "a",
            """<p class="searchResult__title"><a href="/lyric/a1/"><td class="searchResult__artist"><p> <a h>s</a>""",
        ).forEach { page -> assertEquals(page, utaTen.findAll(page).map { it.groupValues[1] to it.groupValues[2] }.toList(), SungReadings.searchRows(page).toList()) }
        val anchor = main("""<a\b[^>]*\bhref="([^"]*)"[^>]*>(.*?)</a>""", IGNORE_CASE, DOT_MATCHES_ALL)
        texts("<a", "<A", "<ab", " ", "href=\"", "xhref=\"", " HREF=\"", "\"", ">", "</a>", "</A>", "<", "x", "\n", "<a href=\"u\">s</a>").forEach { page ->
            assertEquals(page, anchor.findAll(page).map { it.groupValues[1] to it.groupValues[2] }.toList(), chordWikiLinks(page).toList())
        }
    }

    @Test
    fun aSheetsChordsAreReadAsMainReadThem() {
        val ruby = main("""<span class="ruby"><span class="rb">(.*?)</span><span class="rt">(.*?)</span></span>""", DOT_MATCHES_ALL)
        texts(
            """<span class="ruby"><span class="rb">""", """</span><span class="rt">""", "</span></span>", "</span>", "a", "\n",
            """<span class="ruby"><span class="rb">字</span><span class="rt">じ</span></span>""",
        ).forEach { line ->
            assertEquals(line, ruby.findAll(line).map { Triple(it.range, it.groupValues[1], it.groupValues[2]) }.toList(), SungReadings.rubies(line).toList())
        }
        val link = main("""<a [^>]*cd/[^>]*>([^<]*[^<\s][^<]*)</a>(?:on([A-G][#b]?))?""")
        texts(
            "<a ", "<a", "cd/", ">", "</a>", "<", "x", " ", "\u3000", "\n", "on", "A", "H", "#", "b", "<a cd/>", "<a href=\"cd/x\">", "<a cd/>E", "</a>onG",
            "</a>onA#", "</a>onBb",
            most = SHORT_PIECES,
        ).forEach { line ->
            assertEquals(
                line,
                link.findAll(line).map { listOf(it.range.first, it.range.last, it.groupValues[1], it.groupValues[2]) }.toList(),
                ChordSheets.chordLinks(line).map { listOf(it.range.first, it.range.last, it.name, it.bass) }.toList(),
            )
        }
        val nameThenBreak = main("""(<a [^>]*cd/[^>]*>[^<]*[^<\s][^<]*)(<br\s*/?>)(\s*</a>)""", IGNORE_CASE)
        texts(
            "<a ", "cd/", ">", "x", " ", "\n", "</a>", "</A>", "<", "<a cd/>E", "<A x CD/>E", "<br/>", "<BR >", "<br>", " </a>", "<a cd/>E<br></a>", "<br",
            most = SHORT_PIECES,
        ).forEach { sheet ->
            assertEquals(sheet, sheet.replace(nameThenBreak, "$1$3$2"), ChordSheets.withNamesBeforeBreaks(sheet))
        }
        val inline = main("""\[([^\]]*)]""")
        texts("[", "]", "a", "[]").forEach { line -> assertEquals(line, inline.findAll(line).map { it.range }.toList(), ChordSheets.brackets(line).toList()) }
        val tt = main("""(?:^|[}\s>])tt\s*\{([^}]*)\}""", IGNORE_CASE)
        texts("tt", "TT", "t", "{", "}", " ", "\n", "\u3000", ">", "a", ";", " tt{", "}tt {", ">TT{a}").forEach { css ->
            assertEquals(css, tt.findAll(css).map { it.groupValues[1] }.toList(), ChordSheets.ttRules(css).toList())
        }
    }

    @Test
    fun tagsAndSpacesAreCutAsMainCutThem() {
        val tag = main("""<[^>]+>""")
        val tagOrNone = main("""<[^>]*>""")
        val notALink = main("""<(?!/?a(?:\s|>))/?[^>]+>""", IGNORE_CASE)
        val tagged = main("""<[^>]*>[^<]*</[^>]*>|<[^>]*>""")
        texts("<", ">", "/", "a", "A", "</", "<a", "<a>", "</a>", " ", "\u3000", "\n", "x").forEach { text ->
            assertEquals(text, text.replace(tag, ""), withoutTags(text))
            assertEquals(text, text.replace(tag, " "), withoutTags(text, with = " "))
            assertEquals(text, text.replace(tagOrNone, ""), withoutTags(text, least = 0))
            assertEquals(text, text.replace(notALink, ""), withoutTags(text) { ChordSheets.isLinkTag(text, it) })
            assertEquals(text, text.replace(tagged, ""), withoutTagged(text))
        }
        val spacesAndTags = main("""(?: |<[^>]+>)+""")
        val oneSpaceOrTag = main(""" |<[^>]+>""")
        texts(" ", "<", ">", "<b>", "a", "  ").forEach { line ->
            val collapsed = line.replace(spacesAndTags) { run ->
                var spaced = run.range.first == 0
                run.value.replace(oneSpaceOrTag) { if (it.value != " ") it.value else if (spaced) "" else " ".also { spaced = true } }
            }.trim(' ')
            assertEquals(line, collapsed, ChordSheets.collapseSpaces(line))
        }
    }

    @Test
    fun chatGptsSourcesAndLinksAreCutAsMainCutThem() {
        val source = main("""\s*\(\[[^\]]*]\([^)]*\)\)""")
        val link = main("""\[([^\]]+)]\(https?://[^)]*\)""")
        texts(
            "([", "[", "]", "(", ")", "))", "](", "(https://", "(http://", "](https://", "](http://", "(htt", " ", "\n", "a", "([s](u))", " ([s](u)", "[w](https://u)", "[]",
        ).forEach { text ->
            assertEquals(text, text.replace(source, ""), withoutSources(text))
            assertEquals(text, text.replace(link, "$1"), withoutLinks(text))
        }
    }

    /** Each part [parts] finds in pages of [pieces], as [main] finds them: whole, and its [group] [widen]ed, if any. */
    private fun assertParts(parts: PageParts, main: Regex, group: Int?, vararg pieces: String, widen: Int = 0, most: Int = MAX_PIECES) {
        texts(*pieces, most = most).forEach { page ->
            val expected = main.findAll(page).map { match ->
                listOfNotNull(match.range.first, match.range.last, group?.let { checkNotNull(match.groups[it]).range.first }, group?.let { checkNotNull(match.groups[it]).range.last })
            }.toList()
            val found = parts.allIn(page).map { part ->
                listOfNotNull(part.whole.first, part.whole.last, group?.let { part.between.first - widen }, group?.let { part.between.last + widen })
            }.toList()
            assertEquals(page, expected, found)
        }
    }

    /** Main's [pattern], read as a phone reads it. */
    private fun main(pattern: String, vararg options: RegexOption) = phoneRegex(pattern, *options)

    /** [COUNT] texts of up to [most] of [pieces], the same each run. */
    private fun texts(vararg pieces: String, most: Int = MAX_PIECES): Sequence<String> {
        val random = Random(SEED)
        return generateSequence { buildString { repeat(random.nextInt(0, most)) { append(pieces[random.nextInt(pieces.size)]) } } }.take(COUNT)
    }

    private companion object {
        const val SEED = 933
        const val COUNT = 3_000
        const val MAX_PIECES = 10
        const val SHORT_PIECES = 8
        const val LONG = 12
    }
}
