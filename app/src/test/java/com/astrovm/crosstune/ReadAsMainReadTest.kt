package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Each reader that took the place of one of main's patterns, on short texts made of the pieces where they differ, read
 * as main's pattern read them. Quick, so mutation testing runs these before the app's slow tests.
 */
class ReadAsMainReadTest {
    @Test
    fun whatAVideosTitleEndsOnIsCutAsMainCutIt() {
        texts(
            " ", "  ", "\n", "\r\n", "\u0085", "|", "-", "–", " - ", " | ", "Lyrics", "lyric", "HD", "4K", "4\u212A", "hq", "full ", "Official ",
            "Video", "Music ", "Lyric ", "Audio", "Visualizer", "video oficial", "clip", "Animated ", "x", "a", "1", "ſ", "|HD|",
        ).forEach { text ->
            val title = text.trim()
            assertEquals(title, Main.withoutTrailingNoise(title), MetadataParsers.withoutTrailingNoise(title))
        }
    }

    @Test
    fun aSubtitlesTagIsCutAsMainCutIt() {
        texts(" ", "//", "/", "|", "||", "sub", "Sub.", "esp", "Español", "\n", "\u0085", "x", " Sub Esp", "a ").forEach { title ->
            assertEquals(title, title.replace(Main.subtitlesTag, ""), MetadataParsers.withoutSubtitlesTag(title))
        }
    }

    @Test
    fun aSpotifyPagesTitleIsReadAsMainReadIt() {
        // Made here, so mutation testing sees each read what it's made of: the app's are made once, by whichever test is first.
        val wordings = MetadataParsers.spotifyPageWordings.map { MetadataParsers.SpotifyWording(it.wording) }
        assertTrue(wordings.any { it.wording == "song and lyrics by %s" })
        for (wording in wordings) {
            val songAlone = wording.wording.split("%s").joinToString("(.+)") { Regex.escape(it) } + """ \| Spotify$"""
            val song = phoneRegex("""^(.+)(?: [-–‑] |: )$songAlone""", RegexOption.IGNORE_CASE)
            val alone = phoneRegex(songAlone, RegexOption.IGNORE_CASE)
            val (before, after) = wording.wording.split("%s")
            texts("a", " ", ":", ": ", " - ", " – ", " ‑ ", "-", "\n", "x", before, after, " | Spotify", count = SHORT).forEach { text ->
                for (title in listOf(text, text + after + " | Spotify", text + before + "b" + after + " | Spotify", "a - " + before + text + after + " | Spotify")) {
                    assertEquals(title, song.matchEntire(title)?.destructured?.let { (s, a) -> s to a }, wording.songAndArtist(title))
                    assertEquals(title, alone.matchEntire(title)?.groupValues?.get(1), wording.artist(title))
                }
            }
        }
    }

    @Test
    fun aSharedSongIsSplitAsMainSplitIt() {
        for (pattern in listOf("%1\$s by %2\$s", "%2\$s - %1\$s", "Listen to %1\$s, by %2\$s!", "%1\$s%2\$s", "♪ %1\$s / %2\$s")) {
            val words = pattern.split("%1\$s", "%2\$s")
            val regex = phoneRegex(words.joinToString("(.+)") { Regex.escape(it) })
            val split = MusicLinks.SongPattern(pattern)
            assertEquals(pattern, pattern != "%2\$s - %1\$s", split.titleFirst)
            texts("a", " ", "by", " by ", "-", " - ", "\n", "!", ",", "/", " / ", "♪ ", "Listen to ", count = SHORT).forEach { text ->
                for (song in listOf(text, words.first() + text + words.last())) {
                    assertEquals(song, regex.matchEntire(song)?.destructured?.let { (one, two) -> one to two }, split.split(song))
                }
            }
        }
    }

    @Test
    fun aSongInBracketsIsReadAsMainReadIt() {
        // As main read a title whose first song in brackets has no letter: a later one, on its line or past only spaces.
        val main = phoneRegex("""(.*?)\s*[「『]([^」』]+)[」』].*""")
        // An empty song, then one: main's song has a letter at least.
        (sequenceOf("A「」B「C」", "『』 「x」") + texts("「", "」", "『", "』", "A", "|", " ", "\u3000", "\n", "\r\n", "\u0085", "x")).forEach { text ->
            assertEquals(text, main.matchEntire(text)?.destructured?.let { (artist, song) -> artist to song }, MetadataParsers.songAsMainRead(text))
        }
    }

    @Test
    fun aChordWikiCreditIsCutAsMainCutIt() {
        val credit = phoneRegex("""\s*[（(]\s*(?:歌|唄|作詞|作曲|編曲|原曲)[^）)]*[）)]\s*$""")
        texts("（", "）", "(", ")", "歌：", "作詞", "x", " ", "\u3000", "\n", "\r\n", "\u0085", "A").forEach { title ->
            // A credit with brackets in it that close in order is read whole, as main didn't (TitlesAgainstMainTest).
            if (bracketsInOrder(title, "（(", "）)") && nested(title)) return@forEach
            assertEquals(title, listOf(title, title.replace(credit, "")), chordWikiTitles(title))
        }
    }

    @Test
    fun cssCommentsAndASheetAreReadAsMainReadThem() {
        val comment = phoneRegex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val sheet = phoneRegex("""<!--HPSTART-->(.*?)<!--HPEND-->""", RegexOption.DOT_MATCHES_ALL)
        texts("/*", "*/", "/", "*", "a", "\n", "/**/", "<!--HPSTART-->", "<!--HPEND-->", "<!--", "-->").forEach { text ->
            assertEquals(text, text.replace(comment, ""), ChordSheets.withoutCssComments(text))
            assertEquals(text, sheet.find(text)?.groupValues?.get(1), ChordSheets.jTotalSheet(text))
        }
    }

    @Test
    fun noiseIsLookedForOnlyInATitleThatEndsAsItsPatternsEnd() {
        // Main's patterns end on a letter or a bar, then spaces, then a line break, if any, that ends the text. Each
        // letter there is read as a pattern reads it in PatternLettersTest; here, the edges and those case folds to ASCII.
        val endsOnAWord = phoneRegex("""[a-z|]\s*$""", RegexOption.IGNORE_CASE)
        for (last in "aAzZ|09-@[`{ \n\u212A\u017F\u0130\u0131\u0085\u3000") {
            for (text in listOf("", " ", "\n", " \r\n", "\u0085", " \u2028", "-").map { "x$last$it" }) {
                assertEquals(text, endsOnAWord.containsMatchIn(text), MetadataParsers.endsOnAWord(text))
            }
        }
        for (text in listOf("", " ", "\n", " \n", "a")) assertEquals(text, text == "a", MetadataParsers.endsOnAWord(text))
    }

    @Test
    fun noiseIsLookedForFromAsManyLettersBeforeTheEndAsItsPatternReads() {
        // A pattern that reads as many letters, not counting spaces, before the end starts no sooner than there.
        val random = Random(SEED)
        repeat(SHORT) {
            val text = buildString { repeat(random.nextInt(0, MAX_PIECES)) { append(" ab\n\u3000\u0085"[random.nextInt(6)]) } }
            val end = random.nextInt(0, text.length + 1)
            val letters = random.nextInt(1, 5)
            val start = phoneRegex("""\S(?:\s*\S){${letters - 1}}\s*\z""").find(text.substring(0, end))?.range?.first ?: 0
            assertEquals("$text to $end, $letters", start, MetadataParsers.beforeLetters(text, end, letters))
        }
    }

    @Test
    fun noiseIsLookedForFromNoPlaceInsideALetterPastUFFFF() {
        assertEquals(1, MetadataParsers.beforeLetters("a\uD835\uDC00 b", 5, 2))
        assertEquals(4, MetadataParsers.beforeLetters("a\uD835\uDC00 b", 5, 1))
        assertEquals(2, MetadataParsers.beforeLetters("\uD835\uDC00\uD835\uDC00", 4, 1))
        assertEquals(0, MetadataParsers.beforeLetters("\uD835\uDC00\uD835\uDC00", 4, 3))
        // A half alone is a letter of its own.
        assertEquals(1, MetadataParsers.beforeLetters("a\uDC00", 2, 1))
        assertEquals(1, MetadataParsers.beforeLetters("\uD835\uD835", 2, 1))
    }

    @Test
    fun aLookNearAPlaceFindsWhatALookAlongTheWholeTitleFinds() {
        // Patterns that look behind a letter at most, past the marks and format letters that `\b` looks past on a phone.
        for (pattern in listOf("""\bx""", """(?<!\s)x""", """(?<=a)x""", """\s*x\s*$""")) {
            val regex = phoneRegex(pattern, RegexOption.IGNORE_CASE)
            texts("a", "x", " ", "\u0301", "\u200D", "\u00AD", "\uD835\uDC00", "\uD834\uDD67", "\n", "-", count = SHORT).forEach { text ->
                for (start in 0..text.length) {
                    // Never inside a letter past U+FFFF, see [MetadataParsers.beforeLetters].
                    if (start in 1 until text.length && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) continue
                    assertEquals("$pattern in $text from $start", regex.find(text, start)?.range, findNear(regex, text, start))
                }
            }
        }
    }

    /** Whether a bracket opens in [title] before the one open before it closes. */
    private fun nested(title: String): Boolean {
        var depth = 0
        for (char in title) {
            if (char in "（(") depth++ else if (char in "）)") depth--
            if (depth > 1) return true
        }
        return false
    }

    /** [COUNT] texts of up to [MAX_PIECES] of [pieces], the same each run. */
    private fun texts(vararg pieces: String, count: Int = COUNT): Sequence<String> {
        val random = Random(SEED)
        return generateSequence { buildString { repeat(random.nextInt(0, MAX_PIECES)) { append(pieces[random.nextInt(pieces.size)]) } } }.take(count)
    }

    /** Main's patterns. */
    private object Main {
        val subtitlesTag = phoneRegex("""\s*(?://|\|\|?)\s*sub\.?\s*(?:esp|español).*$""", RegexOption.IGNORE_CASE)
        private val trailingVideoNoise = phoneRegex(
            """\s*(?:[|\-–]\s*)?\b(?:official\s+(?:music\s+|lyric\s+)?(?:video|audio|visuali[sz]er)|lyric\s+video|music\s+video|animated\s+video|video\s*oficial|video\s*clip(?:\s+oficial)?|audio\s+oficial)\s*$""",
            RegexOption.IGNORE_CASE
        )
        private val trailingQuality = phoneRegex("""(?:(?:\s*[|\-–]\s*|\s+)lyrics?\s*(?:\|?\s*(?:hd|4k)\s*\|?)?|\s+(?:full\s+)?(?:hd|hq|4k))\s*$""", RegexOption.IGNORE_CASE)
        private val danglingDash = phoneRegex("""\s+[-–]\s*$""")

        fun withoutTrailingNoise(title: String): String {
            var raw = title
            do {
                val previous = raw
                raw = raw.replace(trailingQuality, "").replace(trailingVideoNoise, "").replace(danglingDash, "").trim()
            } while (raw != previous)
            return raw
        }
    }

    private companion object {
        const val SEED = 933
        const val COUNT = 3_000
        const val SHORT = 300
        const val MAX_PIECES = 9
    }
}
