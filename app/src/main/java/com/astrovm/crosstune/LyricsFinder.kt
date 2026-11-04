package com.astrovm.crosstune

import kotlin.math.abs
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentLinkedQueue

/** What LRCLIB's Retry-After asks for when it's busy. */
internal const val LYRICS_BUSY_PAUSE_MS = 1_000L

/** How far apart two recordings' lengths can be and still be the same one. */
internal const val SAME_RECORDING_LENGTH_MS = 3_000L

/**
 * A credit line: who wrote, composed, arranged, produced or published it, in Chinese, Japanese or English: "SP：…".
 * A Chinese label of two characters or more needs no colon: "编曲 Someone".
 */
private val netEaseCredit = Regex(
    """^\s*(?:(?:作词|作詞|作曲|编曲|編曲|制作人|製作人)\s+\S|(?:作词|作詞|作曲|编曲|編曲|制作人|製作人|词|詞|曲|Lyricist|Lyrics|Composer|Arranger|Producer|OP|SP)\s*[:：])""",
    RegexOption.IGNORE_CASE
)

/** What a contributor ends a song with, not sung: "END", "THE END", "music...". */
private val closingFiller = Regex("""(?:the\s+)?end\.?|music\s*(?:\.{2,}|…)""", RegexOption.IGNORE_CASE)

/** A note under a heading, not sung: wholly in brackets, "（Some Writer / Another）", or who it's by, "BY SOMEONE". */
private val headingNote = Regex("""[（(\[].*[）)\]]|by\s+\S.*""", RegexOption.IGNORE_CASE)

/** A year, as after the artist under a heading: "The Band（1972）". */
private val year = Regex("""(?:19|20)\d\d""")

/** A credit NetEase opens some songs with, a short label and who: "Producers: …", "Writers：…", "母帯工程師：…". */
private val netEaseOpeningCredit = Regex("""^([^:：]{1,40}?)\s*[:：]\s*\S""")

/** A credit NetEase closes some songs with, a part and who played it: "Bass Guitar : John Deacon". */
private val netEaseClosingCredit = Regex("""^([^:：]{1,60})\s[:：]\s+\S""")

/** Where it was made, or who published it, which can come among those credits: "Recorded at Rockfield Studio, …". */
private val netEaseStudio = Regex("""^(?:(?:Recorded|Mixed|Mastered)\b[^:：]*\bat|Published by)\b""", RegexOption.IGNORE_CASE)

/** Who sings a line, in a duet's words, rather than who played on it: "Male : …", "Chorus : …". */
private val sungPart = Regex("""^(?:male|female|both|all|verse|chorus|hook|bridge|rap|intro|outro|pre-?chorus|男|女|合)(?:\s*\d+)?$""", RegexOption.IGNORE_CASE)

/** A line NetEase's contributors fill an intro or an outro with, not sung: the song's name, "♪Artist - Song", or a dash. */
private val netEaseFiller = Regex("""♪.*\s[-–]\s.*|[-–—]+""")

/** A part's name, which some of NetEase's contributors head each part with: "[Verse 1]", "[Chorus: Drake]". */
private val netEasePart = Regex("""\[(?:intro|verse|pre-?chorus|chorus|post-?chorus|hook|bridge|interlude|refrain|outro)\b[^\]]*\]""", RegexOption.IGNORE_CASE)

/**
 * The lines without what NetEase's contributors fill a song's quiet parts with: a note in 【】, which
 * may run over a few lines, as the artist's own words on the song in a solo, [netEaseFiller]s, and
 * the parts' names. Who sings a line, "【A】" before it, is no note, and one never closed is left be.
 */
private fun <T> List<T>.withoutNotes(text: (T) -> String): List<T> {
    val notes = mutableSetOf<Int>()
    var opened: Int? = null
    for ((index, item) in withIndex()) {
        val line = text(item).trim()
        val start = opened ?: index.takeIf { line.startsWith("【") && line.indexOf('】').let { it < 0 || it == line.lastIndex } }
        if (start != null && line.endsWith("】")) {
            notes += start..index
            opened = null
        } else {
            opened = start
        }
    }
    return filterIndexed { index, item -> index !in notes && text(item).trim().let { !netEaseFiller.matches(it) && !netEasePart.matches(it) } }
}

/**
 * The lines after what NetEase's contributors open some songs with, before anything sung: credits
 * in any language, and the song's name and its artist's, one on each line, with more credits after
 * them. A sung line that looks like a credit, "Me : still here", only comes after something sung.
 */
private fun <T> List<T>.withoutOpeningCredits(song: MusicMetadata, text: (T) -> String): List<T> {
    val lines = map { text(it).trim() }
    return drop(afterCredits(lines, afterHeading(lines, afterCredits(lines, 0), song)))
}

/** Where the credits from [from] on end: blank lines and "Label: name", with a label that isn't a sung part. */
private fun afterCredits(lines: List<String>, from: Int): Int {
    var start = from
    while (start < lines.size && isOpeningCredit(lines[start])) start++
    return start
}

private fun isOpeningCredit(line: String): Boolean {
    if (line.isEmpty()) return true
    val label = netEaseOpeningCredit.find(line)?.groupValues?.get(1)?.trim() ?: return false
    return label.any(Char::isLetter) && !sungPart.matches(label) && label.split(' ').size <= 3
}

/**
 * Where the lines from [start] on go past a heading: the name then the artist, or the other way round;
 * a whole heading line; or the artist alone. The name alone only with credits or a note under it, as
 * a song may open with its name. The name may be without what the store adds to it: "Let Down" for
 * "Let Down (Remastered)".
 */
private fun afterHeading(lines: List<String>, start: Int, song: MusicMetadata): Int {
    val titles = setOf(song.title, SongNames.bareTitle(song.title)).map(SongNames::normalize)
    val artist = SongNames.normalize(song.artist)
    val names = lines.drop(start).filter { it.isNotEmpty() }.take(2).map(SongNames::normalize).toSet()
    val first = lines.getOrNull(start) ?: return start
    var at = start
    when {
        titles.any { names == setOf(it, artist) } -> repeat(2) { while (lines[at].isEmpty()) at++; at++ }
        isHeading(first, song) -> {
            at++
            // A guest, under it: "（feat. Dwele）".
            while (lines.getOrNull(at)?.let(guestCredit::matches) == true) at++
        }
        // The artist alone: a song may open with its own name, but not with its singer's.
        SongNames.normalize(first) == artist -> at++
        SongNames.normalize(first) in titles -> at = afterTitleAlone(lines, start, artist)
    }
    return at
}

/** Where the lines go past the song's name alone at [start], when credits or a note under it make it a heading. */
private fun afterTitleAlone(lines: List<String>, start: Int, artist: String): Int {
    val next = (start + 1 until lines.size).firstOrNull { lines[it].isNotEmpty() } ?: return start
    if (afterCredits(lines, next) == next && !isHeadingNote(lines[next], artist)) return start
    var at = start + 1
    while (lines.getOrNull(at)?.let { it.isEmpty() || isHeadingNote(it, artist) } == true) at++
    return at
}

/** Whether [line] is a note under a heading: wholly in brackets, who it's by, or the [artist] and a year. */
private fun isHeadingNote(line: String, artist: String): Boolean =
    headingNote.matches(line) || (year.containsMatchIn(line) && artist.isNotEmpty() && artist in SongNames.normalize(line))

/** The lines before a heading or a filler a contributor ends a song with: "END", or the names again. */
private fun <T> List<T>.withoutClosingHeading(song: MusicMetadata, text: (T) -> String): List<T> {
    var end = size
    while (true) {
        val last = (end - 1 downTo 0).firstOrNull { text(this[it]).isNotBlank() } ?: break
        val line = text(this[last]).trim()
        if (closingFiller.matches(line) || isHeading(line, song)) end = last else break
    }
    return take(end)
}

/**
 * Whether [line] is only the song's name and its artist's, as a heading, with what NetEase's
 * contributors write around them: their names in Chinese, guests, "lyrics" and "Explicit", spaced
 * or not, brackets closed or not. "Leif Erikson （夫·埃里克森）-Interpol （国际刑警乐队）", "Kanye West -
 * Flashing Lights lyrics", "MeAcostumbre（feat.BadBunny（我习惯了（Explicit-Arcangel/BadBunny（坏痞兔".
 */
private fun isHeading(line: String, song: MusicMetadata): Boolean {
    val title = SongNames.normalize(SongNames.bareTitle(song.title))
    val artists = SongNames.artists(song.artist)
    var rest = SongNames.normalize(line)
    if (title.isEmpty() || title !in rest || artists.none { it in rest }) return false
    // Longest first, so a name doesn't break up a longer one holding it.
    val said = (listOf(title) + artists + song.title.split(' ').map(SongNames::normalize) + headingWords)
    said.sortedByDescending { it.length }.forEach { rest = rest.replace(it, "") }
    return rest.all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
}

/** What a heading may say besides the names. */
private val headingWords = listOf("lyrics", "explicit", "featuring", "feat", "ft", "and", "the")

/** A line that's only a guest's credit: "（feat. Dwele）", "ft. Someone". */
private val guestCredit = Regex("""(?i)\s*[(（\[]?\s*(?:feat\.?|ft\.?|featuring)\s+[^)）\]]+[)）\]]?\s*""")

/** A web address, as a lyrics site's ad for itself: "{more ILLIT lyrics at lyricsify.com/u/KPopLRCFiles}". */
private val webAddress = Regex("""(?i)https?://|www\.|\b[a-z0-9-]+\.[a-z]{2,6}/""")

/** A line of the header some LRCLIB answers open with: "Artist: …", "Album: …", "Title: …". */
private val namesHeader = Regex("""(?:artist|album|title)\s*:.*""", RegexOption.IGNORE_CASE)

/** The lines without all NetEase's contributors add to a song's words: credits, a heading, notes and parts' names. */
private fun <T> List<T>.withoutNetEaseExtras(song: MusicMetadata, text: (T) -> String): List<T> =
    filterNot { netEaseCredit.containsMatchIn(text(it)) }.withoutOpeningCredits(song, text).withoutClosingCredits(text).withoutClosingHeading(song, text).withoutNotes(text)

/** Words without the "Artist: … / Album: … / Title: …" some LRCLIB answers open with, all an instrumental's may have. */
private fun withoutNamesHeader(words: String): String {
    val lines = words.lines()
    val header = lines.takeWhile { namesHeader.matches(it.trim()) }.size
    return if (header >= 2) lines.drop(header).joinToString("\n") else words
}

/** An apostrophe ending a word, before a space or the line's end, not one escaped: "friend' and". */
private val apostropheForComma = Regex("""(?<=\p{L})'(?=\s|$)""")

/**
 * The lines without those NetEase opens a song with that pass in a moment, quicker than anything is
 * sung: credits, headings and a contributor's notes, like "Lucky guy" in 105 ms a word. Once the song
 * has begun, a quick line is sung.
 */
private fun List<LyricLine>.withoutOpeningMoments(): List<LyricLine> =
    dropWhile { line -> (line.endMs ?: line.timeMs) - line.timeMs < MOMENT_MS_A_WORD * line.words.size.coerceAtLeast(1) }

/** How quick a word NetEase times is, at most, to be no singing; all of a song's opening credits and headings are, in its word-by-word words. */
private const val MOMENT_MS_A_WORD = 150L

/** A character NetEase lost, as it writes it: "Don't锟斤拷锟斤拷look". */
private val lostCharacters = Regex("""\s*(?:锟斤拷)+\s*""")

/** UTF-8 read once as Windows-1252 or Latin-1: "Ã¢" for "â", "â€™" for "’". */
private val misread = Regex("""[ÃÂâ][\u0080-\u00BF\u0152\u0153\u0160\u0161\u0178\u017D\u017E\u0192\u02C6\u02DC\u2013\u2014\u2018-\u201E\u2020-\u2022\u2026\u2030\u2039\u203A\u20AC\u2122]""")

/**
 * [line] as it was meant to read, where a site broke it in a way that can be undone: an apostrophe
 * stored escaped, a space that never breaks, a lost character, or UTF-8 read once as Windows-1252 or Latin-1.
 */
internal fun mended(line: String): String {
    // A space that never breaks would keep a long line from wrapping between its words.
    var mended = line.replace("\\'", "'").replace('\u00a0', ' ')
    if (lostCharacters.containsMatchIn(mended)) mended = mended.replace(lostCharacters, " ").trim()
    if (misread.containsMatchIn(mended)) {
        val bytes = strictly(mended, charset("windows-1252")) ?: strictly(mended, Charsets.ISO_8859_1)
        mended = bytes?.let { readStrictly(it) } ?: mended
    }
    return mended
}

/** Whether [words] are in capitals, nearly every letter that has them, as some sites keep a whole song. */
private fun inCapitals(words: String): Boolean {
    val cased = words.filter { it.isLetter() && it.lowercaseChar() != it.uppercaseChar() }
    return cased.length >= MIN_CASED_LETTERS && cased.count(Char::isUpperCase) >= cased.length * CAPITALS_SHARE
}

/**
 * [line], written in capitals, as a sentence is written: a capital to start it and each sentence in
 * it, and I. Letter for letter, so words timed one by one can be cut from it again.
 */
internal fun inSentenceCase(line: String): String {
    val letters = line.map(Char::lowercaseChar).toCharArray()
    var capital = true
    for (i in letters.indices) {
        val letter = letters[i]
        val alone = letters.getOrNull(i - 1)?.isLetter() != true && letters.getOrNull(i + 1)?.isLetter() != true
        when {
            letter in ".!?" -> capital = true
            !letter.isLetter() -> Unit
            capital -> letters[i] = letter.uppercaseChar().also { capital = false }
            letter == 'i' && alone -> letters[i] = 'I'
        }
    }
    return String(letters)
}

/** Words timed one by one, [texts], in sentence case as the line they make up. */
private fun inSentenceCase(texts: List<String>): List<String> {
    val line = inSentenceCase(texts.joinToString(""))
    var at = 0
    return texts.map { text -> line.substring(at, at + text.length).also { at += text.length } }
}

/** Letters with a case a song needs, and the share of them in capitals, to count as written in capitals. */
private const val MIN_CASED_LETTERS = 20
private const val CAPITALS_SHARE = 0.9

private fun strictly(text: String, charset: Charset): ByteArray? = try {
    charset.newEncoder().onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text)).let { buffer -> ByteArray(buffer.remaining()).also(buffer::get) }
} catch (_: CharacterCodingException) {
    null
}

private fun readStrictly(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
} catch (_: CharacterCodingException) {
    null
}

/**
 * Whether [words] are UTF-8 read as GBK and kept so, as NetEase has some Korean songs: "누군가" as
 * "雸勱蛋臧". Read back, over half of them is Korean; real Chinese or Japanese, a tenth at most, and
 * a few characters alone can be either.
 */
internal fun readAsGbk(words: String): Boolean {
    val han = words.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
    if (han < GARBLED_MIN_HAN) return false
    val korean = String(words.toByteArray(charset("GBK")), Charsets.UTF_8).count { it in '\uAC00'..'\uD7A3' }
    return korean * 5 >= han * 2
}

/** How many Chinese characters it takes to tell garbled Korean from real Chinese or Japanese. */
private const val GARBLED_MIN_HAN = 10

/** Whether any of it is written in Latin letters. */
internal fun String.hasLatin() = any { it in 'a'..'z' || it in 'A'..'Z' }

/** A name in brackets after a name, what it's called in Chinese: " （夫·埃里克森）". */
private val netEaseGloss = Regex("""\s*[（(][^）)]*[）)]""")

/**
 * The lines up to the credits NetEase closes some songs with: at least two, with nothing sung after
 * them up to the end, where the studios and publishers can be among them.
 */
private fun <T> List<T>.withoutClosingCredits(text: (T) -> String): List<T> {
    var start = size
    var credits = 0
    for (index in indices.reversed()) {
        val line = text(this[index]).trim()
        val part = netEaseClosingCredit.find(line)?.groupValues?.get(1)?.trim()
        when {
            line.isEmpty() -> continue
            part != null && !sungPart.matches(part) -> credits++
            netEaseStudio.containsMatchIn(line) -> Unit
            else -> break
        }
        start = index
    }
    return if (credits >= 2) take(start) else this
}

/**
 * A song's words, from LRCLIB, which asks for no account and no key. Its answers are
 * crowdsourced, so what it gives back is checked against the song asked about before it's
 * believed: the words of a song with the same name by someone else are no use at all.
 *
 * Timed words come with when each line is sung, so they can follow the song as it plays; the plain
 * words are what's shown when nothing says where in the song it is.
 */
internal class LyricsFinder(
    private val client: OkHttpClient,
    /** The app's own version, which LRCLIB asks a caller to give so a problem can be traced back. */
    private val versionName: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** How long to wait before asking again after a failed lookup; tests pass 0, so they don't wait. */
    private val busyPauseMs: Long = LYRICS_BUSY_PAUSE_MS,
    /**
     * What songs' words were, so a song shown again needs no lookup. Only words found are kept: a
     * song without any is asked about again, since the places with words keep getting more.
     */
    private val cache: LookupCache? = null,
    /** The song's video id on YouTube Music, whose own words come timed for it; null when it isn't there. */
    private val youTubeMusicSong: (suspend (MusicMetadata) -> String?)? = null
) {
    /** A song's words, or why there are none to show. */
    internal sealed interface Lyrics {
        /**
         * The words themselves, and their [lines] with when each is sung, when the service has them
         * timed. [lengthMs] is how long the recording they're timed for lasts, when the service says.
         */
        /** [source] is where the words came from, and their timing when it was borrowed, so a report can say. */
        data class Found(val words: String, val lines: List<LyricLine> = emptyList(), val lengthMs: Long? = null, val source: String? = null) : Lyrics {
            /** Whether they're timed for a recording as long as [playingMs], or there's no telling they aren't. */
            fun timedFor(playingMs: Long?): Boolean {
                val timedFor = lengthMs ?: return true
                return playingMs == null || abs(timedFor - playingMs) <= SAME_LENGTH_MS
            }

            /** Whether they're the words of the song a cover covers, it having none of its own. */
            val ofTheOriginal get() = source?.endsWith(OF_THE_ORIGINAL) == true
        }

        /** The service answered, and holds none for this song. */
        data object None : Lyrics

        /** The service says the song has no words: it's an instrumental. */
        data object Instrumental : Lyrics

        /** The service wouldn't answer, or was too slow: worth saying rather than claiming none. */
        data object Unavailable : Lyrics
    }

    /**
     * What there is to show for [metadata]'s song: YouTube Music's own words, which come from the
     * labels' lyrics services and are timed for the song as released, beside LRCLIB's. A song from
     * Japan, say, is often on LRCLIB under its own names, not the ones in Latin letters a music app
     * shows, so those are asked for too, and NetEase, which has many more such songs, last. Words
     * found anywhere win; otherwise "none" from any of them is believed over one that wouldn't answer.
     */
    suspend fun lyricsOf(metadata: MusicMetadata, timeoutMs: Long = TIMEOUT_MS): Lyrics =
        firstLook(metadata, timeoutMs).let { first -> if (first.final) first.lyrics else retimed(metadata, first.lyrics, timeoutMs) }

    /** What [firstLook] found: words kept from before, which are [final], or else ones still to be [retimed]. */
    data class FirstLook(val lyrics: Lyrics, val final: Boolean)

    /**
     * [lyricsOf] in two steps, so the words can show as soon as they're found: first what's kept, or
     * found, then [retimed], which may take a while longer.
     */
    suspend fun firstLook(metadata: MusicMetadata, timeoutMs: Long = TIMEOUT_MS): FirstLook {
        // Only a song has words, and without an artist there's nothing to check an answer against.
        if (metadata.type != ItemType.TRACK || metadata.artist.isBlank() || metadata.title.isBlank()) return FirstLook(Lyrics.None, final = true)
        // Kept for this very recording, since its length is in the key, so even words timed for another take are the best found for it.
        cache?.get(cacheKey(metadata))?.let { kept -> fromCache(kept)?.let { (words, provisional) -> return FirstLook(asJapanWrites(cleaned(words, metadata)), final = !provisional) } }
        val lookup = Answers()
        val (best, own) = withTimeoutOrNull(TOTAL_TIMEOUT_MS) { withContext(lookup) { best(metadata, timeoutMs).orOriginal(metadata, timeoutMs) } } ?: Best(Lyrics.Unavailable)
        val found = (best as? Lyrics.Found)?.let { cleaned(it, metadata, lookup.copies.toList()) } ?: best
        if (found == Lyrics.Unavailable) keptUnderName(metadata)?.let { return FirstLook(asJapanWrites(cleaned(it, metadata)), final = true) }
        // Kept at once, so the song shown again soon needs no lookup; still to be timed better, unless
        // they're the recording's own, which no copy found elsewhere times better.
        cache?.let { cache ->
            toCache(found, provisional = !own)?.let { cache.put(cacheKey(metadata), it) }
            cache.save()
        }
        return FirstLook(asJapanWrites(found), final = own)
    }

    /**
     * With nothing reachable, e.g. offline, the newest words kept for another recording of the song: a
     * song kept offline, played from an app that names its recording otherwise, still has its words.
     */
    private suspend fun keptUnderName(metadata: MusicMetadata): Lyrics.Found? {
        val name = "$CACHE_VERSION\u0000${SongNames.normalize(metadata.title)}\u0000${SongNames.normalize(metadata.artist)}"
        return cache?.entries()?.lastOrNull { (key) -> key == name || key.startsWith("$name\u0000") }?.let { (_, kept) -> fromCache(kept)?.first }
    }

    /**
     * [found], timed as a well-timed copy of the song times them when they're timed badly or not at
     * all and there's one, and kept so.
     */
    suspend fun retimed(metadata: MusicMetadata, found: Lyrics, timeoutMs: Long = TIMEOUT_MS): Lyrics {
        val answer = (found as? Lyrics.Found)?.let { words -> withTimeoutOrNull(BORROW_TIMEOUT_MS) { timedWell(metadata, words, timeoutMs) } } ?: found
        cache?.let { cache ->
            toCache(answer)?.let { cache.put(cacheKey(metadata), it) }
            cache.save()
        }
        return asJapanWrites(answer)
    }

    /**
     * Words in Japanese as Japan writes them, as NetEase, a Chinese site, often has them written
     * China's way: see [Readings.asJapanWrites].
     */
    private fun asJapanWrites(found: Lyrics): Lyrics {
        if (found !is Lyrics.Found || Readings.scriptOf(found.words.lines()) != Script.JAPANESE) return found
        return Lyrics.Found(
            found.words.lines().joinToString("\n", transform = Readings::asJapanWrites),
            found.lines.map { line ->
                // The comma left off a line is left off its last word.
                val words = line.words.map { it.copy(text = Readings.inJapansKanji(it.text)) }.toMutableList()
                val last = words.indexOfLast { it.text.isNotBlank() }
                if (last >= 0) words[last] = words[last].copy(text = Readings.asJapanWrites(words[last].text))
                line.copy(text = Readings.asJapanWrites(line.text), words = words)
            },
            found.lengthMs,
            found.source
        )
    }

    /**
     * The song, whatever the case or spacing of its name. Words kept by versions that didn't ask
     * YouTube Music or check timings may be worse than what's there now, so they're asked for again.
     */
    private fun cacheKey(metadata: MusicMetadata) = "$CACHE_VERSION\u0000${RecordingKey.of(metadata)}"

    /**
     * [found] timed as a well-timed copy of the song on LRCLIB times the same words, which may be
     * written otherwise there, e.g. in romaji; as it is when there's none, or it needs none.
     *
     * A copy under the song's own name is the song, and times words timed badly or not at all. A
     * song named with more, "Song (2018 Remix) (2024 Remaster)", is often only there under its bare
     * name, where a copy may be another take of it, so such copies count only when about as long as
     * the song. They time badly timed words they agree with roughly, and they even retime words
     * that seem well timed when two of them agree with each other and not with those: such words,
     * from a place that names recordings loosely, are timed for another take.
     */
    private suspend fun timedWell(metadata: MusicMetadata, found: Lyrics.Found, timeoutMs: Long): Lyrics.Found {
        val wellTimed = timing(found) >= WELL_TIMED
        val bare = SongNames.bareTitle(metadata.title).takeIf { it != metadata.title }
        if (wellTimed && bare == null) return found
        val rough = found.lines.filter { it.text.isNotBlank() }
        val lines = rough.map { it.text }.ifEmpty { found.words.lines().map(String::trim).filter(String::isNotEmpty) }
        val sounds = soundsOf(lines)
        suspend fun timedAs(copy: Answer): List<LyricLine>? {
            val other = copy.synced().filter { it.text.isNotBlank() }
            if (other.isEmpty() || SyncedLyrics.rushed(other)) return null
            return BorrowedTimings.retime(lines, sounds, other, soundsOf(other.map { it.text }))
        }
        if (!wellTimed) {
            copiesNamed(metadata, metadata.title, timeoutMs).firstNotNullOfOrNull { timedAs(it) }?.let { return borrowing(found, it) }
        }
        if (bare == null || rough.isEmpty()) return found
        val lengthMs = withTimeoutOrNull(timeoutMs) { inStore(metadata) }?.lengthMs
        val timings = TimingEvidence.independent(copiesNamed(metadata, bare, timeoutMs)
            .filter { copy -> lengthMs == null || copy.lengthMs()?.let { abs(it - lengthMs) <= SAME_TAKE_LENGTH_MS } != false }
            .distinctBy { copy -> copy.synced().filter { it.text.isNotBlank() }.map { it.timeMs } }
            .mapNotNull { timedAs(it) })
        if (!wellTimed) return timings.firstOrNull { BorrowedTimings.agrees(rough, it) }?.let { borrowing(found, it) } ?: found
        // Well timed, the words are kept unless two copies agree with each other, and neither with them.
        val agreed = timings.firstOrNull { one -> timings.count { BorrowedTimings.agrees(one, it, CLOSE_MS) } >= 2 } ?: return found
        return if (BorrowedTimings.agrees(rough, agreed, CLOSE_MS * 2)) found else borrowing(found, agreed)
    }

    /** [found]'s words with [lines], LRCLIB's timing for them. */
    private fun borrowing(found: Lyrics.Found, lines: List<LyricLine>) = Lyrics.Found(found.words, lines, source = "${found.source}, timed by $LRCLIB")

    /** LRCLIB's copies of [metadata]'s song under [title]; none when it won't answer. */
    private suspend fun copiesNamed(metadata: MusicMetadata, title: String, timeoutMs: Long): List<Answer> {
        val named = metadata.copy(title = title)
        val answers = withTimeoutOrNull(timeoutMs) {
            try {
                answersFor(named)
            } catch (_: IOException) {
                null
            } catch (_: JSONException) {
                null
            }
        }
        return answers.orEmpty().filter { it.isExact(named) || it.belongsTo(named) }
    }

    /** Each line as the letters it's said with: words in Japanese, Chinese or Korean written out in Latin ones. */
    private suspend fun soundsOf(lines: List<String>): List<String> = withContext(Dispatchers.Default) {
        val script = Readings.scriptOf(lines)
        (if (script == null) lines else Readings.of(lines, script).map { it.romanized }).map(BorrowedTimings::sound)
    }


    /**
     * YouTube Music's words and those from everywhere else, asked for at once; the better of them.
     * YouTube Music only adds words: it not having them doesn't mean the song has none.
     */
    private suspend fun best(metadata: MusicMetadata, timeoutMs: Long): Best = coroutineScope {
        val official = async { fromYouTubeMusic(metadata, timeoutMs)?.seen() }
        // Words timed word by word beat any timed a line at a time, and only NetEase has them.
        val byWord = async { (fromNetEase(metadata, null, timeoutMs).seen() as? Lyrics.Found)?.takeIf { timing(it) == WORD_TIMED } }
        val elsewhere = async { anywhere(metadata, timeoutMs) }
        val own = official.await()
        // A word-timed search result isn't better evidence than this recording's own timed lyrics, so
        // nothing else is worth waiting for: NetEase alone takes a few seconds.
        if (own != null && RecordingKey.youTubeId(metadata) != null && timing(own) >= WELL_TIMED) {
            byWord.cancel()
            elsewhere.cancel()
            return@coroutineScope Best(own, own = true)
        }
        val rest = elsewhere.await()
        // YouTube Music's words for another edit of the song, found by its name, give way to words
        // timed as well for this one.
        val otherTake = own != null && !fits(own, metadata) && rest is Lyrics.Found && rest.lengthMs != null && fits(rest, metadata) && timing(rest) >= timing(own)
        val found = if (own == null || otherTake) rest else better(own, rest)
        val word = byWord.await() ?: return@coroutineScope Best(found)
        // Another take's words, timed word by word, don't beat this one's, timed a line at a time.
        if (found is Lyrics.Found && fits(found, metadata) && !fits(word, metadata)) return@coroutineScope Best(found)
        Best(if (found is Lyrics.Found) rushedTimedAs(word, found) else word)
    }

    /**
     * [word]'s lines with those that go by faster than anyone sings them put back where [other], the
     * same words timed another way, has them, as far from its other lines. Whoever timed them word by
     * word fell behind and hurried to catch up, as NetEase's Expresso Love opens, 5 s late; a quick line
     * that's no later there than the rest was only sung quickly. Its words spread out up to the next line.
     */
    private fun rushedTimedAs(word: Lyrics.Found, other: Lyrics.Found): Lyrics.Found {
        val same = sameLines(word.lines, other.lines)
        if (same.size < MIN_SAME_LINES || timedApart(word, other)) return word
        val gap = same.map { (ours, theirs) -> theirs.timeMs - ours.timeMs }.sorted()[same.size / 2]
        val back = same.filter { (ours, theirs) -> rushed(ours) && ours.timeMs - theirs.timeMs + gap > JUMP_MS }.associate { (ours, theirs) -> ours to theirs.timeMs - gap }
        val lines = word.lines.mapIndexed { index, line ->
            val at = back[line]
            val end = line.endMs
            if (at == null || end == null) return@mapIndexed line
            val until = minOf(end, word.lines.getOrNull(index + 1)?.let { back[it] ?: it.timeMs } ?: end)
            line.copy(timeMs = at, endMs = until, words = line.words.map { it.copy(timeMs = at + (it.timeMs - line.timeMs) * (until - at) / (end - line.timeMs).coerceAtLeast(1)) })
        }
        // Put back past the line before, the sheets aren't telling the same story.
        return if (TimingEvidence.usable(lines)) word.copy(lines = lines) else word
    }

    /** Whether [line] goes by faster than anyone sings it, by the words it's timed with. */
    private fun rushed(line: LyricLine): Boolean =
        line.words.isNotEmpty() && line.endMs != null && line.endMs - line.timeMs < RUSHED_MS_A_WORD * line.words.size

    /** These words, kept for a song in capitals to take its names from, as they've been fetched anyway. */
    private suspend fun <T : Lyrics> T.seen(): T = also { if (it is Lyrics.Found) currentCoroutineContext()[Answers]?.copies?.add(it) }

    /** With no words of its own for a cover, the words of the song it covers. */
    private suspend fun Best.orOriginal(metadata: MusicMetadata, timeoutMs: Long): Best =
        if (lyrics is Lyrics.None || lyrics is Lyrics.Instrumental) original(metadata, timeoutMs)?.let(::Best) ?: this else this

    /**
     * The words of the song [cover] covers, by its first singer: timed only when that recording is as
     * long as this one, as a cover is sung in its own time.
     */
    private suspend fun original(cover: MusicMetadata, timeoutMs: Long): Lyrics.Found? {
        val artist = coveredArtist(cover) ?: return null
        val title = SongNames.coverOf(cover.title)?.title ?: cover.title
        val found = anywhere(cover.copy(title = title, artist = artist, url = null, recordingId = null), timeoutMs) as? Lyrics.Found ?: return null
        val asLong = found.lengthMs != null && cover.lengthMs != null && fits(found, cover)
        return (if (asLong) found else found.copy(lines = emptyList())).let { it.copy(source = "${it.source}$OF_THE_ORIGINAL") }
    }

    /**
     * Who first sang the song [cover] covers: named in its title, or the first singer of a song of
     * its name. A title that doesn't say it's a cover, as Marc Strain's "Smalltown Boy", may be the
     * singer's own song of a famous name, as Haley Reinhart's "Creep" is next to Stone Temple
     * Pilots': it's a cover only when Apple credits the first singer's song to a writer of this one.
     */
    private suspend fun coveredArtist(cover: MusicMetadata): String? {
        val covered = SongNames.coverOf(cover.title)
        covered?.artist?.let { return it }
        val first = firstSinger(covered?.title ?: cover.title, cover.artist) ?: return null
        return first.artist.takeIf { covered != null || writtenBySame(cover, first.id) }
    }

    /** Whether Apple credits [cover], as the iTunes Store has it, and the store's song [id] to a writer in common. */
    private suspend fun writtenBySame(cover: MusicMetadata, id: Long): Boolean {
        val own = inStore(cover)?.id ?: return false
        val theirs = writersOf(id).takeIf { it.isNotEmpty() } ?: return false
        return writersOf(own).any { it in theirs }
    }

    /** Who Apple Music's page for the store's song [id] says wrote it; none when it says none, or won't answer. */
    private suspend fun writersOf(id: Long): Set<String> = try {
        credit.findAll(browserPage("$APPLE_MUSIC_SONG_URL$id")).filter { writerRole.containsMatchIn(it.groupValues[2]) }.map { it.groupValues[1].lowercase() }.toSet()
    } catch (_: IOException) {
        emptySet()
    }

    /**
     * Who first sang [song], as most of the iTunes Store's songs of that name are theirs, many times
     * more than anyone else's: a famous song's covers are many, but by as many singers. A name many
     * songs share, as "Hello", has no one far ahead, so it's not known whose a cover of it is.
     */
    private suspend fun firstSinger(song: String, cover: String): FirstSinger? = try {
        val search = ITUNES_SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("term", song)
            .addQueryParameter("country", "us")
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "$SONGS_OF_A_NAME")
            .build()
        val songs = (parse(search.toString()) as? JSONObject)?.optJSONArray("results")?.let { list -> (0 until list.length()).mapNotNull { list.optJSONObject(it) } }.orEmpty()
            .filter { SongNames.same(SongNames.bareTitle(it.optString("trackName")), song) && it.optString("artistName").let { artist -> artist.isNotBlank() && !SongNames.sameArtist(artist, cover) } }
        val singers = songs.groupBy { it.optString("artistName") }.entries.sortedByDescending { it.value.size }
        val next = singers.getOrNull(1)?.value?.size ?: 0
        singers.firstOrNull()?.takeIf { it.value.size >= MIN_FIRST_SINGER_SONGS && it.value.size >= FIRST_SINGER_LEAD * next }?.let { FirstSinger(it.key, it.value.first().optLong("trackId")) }
    } catch (_: IOException) {
        null
    } catch (_: JSONException) {
        null
    }

    /** Who first sang a song, and one of their recordings of it in the iTunes Store, by its [id]. */
    private data class FirstSinger(val artist: String, val id: Long)

    /** What [best] found, and whether it's the recording's own words, timed for it. */
    private data class Best(val lyrics: Lyrics, val own: Boolean = false)

    /**
     * Words found over none, the better timed of two, and [first] among equals; with no words, an
     * instrumental over "none", and "none" over one that wouldn't answer.
     */
    private fun better(first: Lyrics, second: Lyrics): Lyrics = when {
        first is Lyrics.Found && second is Lyrics.Found -> if (timing(second) > timing(first)) second else first
        first is Lyrics.Found -> first
        second is Lyrics.Found -> second
        first is Lyrics.Instrumental || second is Lyrics.Instrumental -> Lyrics.Instrumental
        first is Lyrics.None || second is Lyrics.None -> Lyrics.None
        else -> Lyrics.Unavailable
    }

    /** Words as kept: the plain words and each timed line, and whether they're still to be timed better. */
    private fun toCache(answer: Lyrics, provisional: Boolean = false): String? = (answer as? Lyrics.Found)?.let { found ->
        JSONObject()
            .put("words", found.words)
            .apply { if (provisional) put("provisional", true) }
            .apply { found.lengthMs?.let { put("length", it) } }
            .apply { found.source?.let { put("source", it) } }
            .put("lines", JSONArray().apply { found.lines.forEach { put(lineToCache(it)) } })
            .toString()
    }

    /** A line as kept: when, what, when each of its words is, when they're timed, and when it ends, when that's known. */
    private fun lineToCache(line: LyricLine): JSONArray = JSONArray().put(line.timeMs).put(line.text).apply {
        if (line.words.isNotEmpty() || line.endMs != null) put(JSONArray().apply { line.words.forEach { put(JSONArray().put(it.timeMs).put(it.text)) } })
        line.endMs?.let { put(it) }
    }

    private fun lineFromCache(kept: JSONArray): LyricLine {
        val words = kept.optJSONArray(2)?.let { list -> (0 until list.length()).map { list.getJSONArray(it).let { word -> TimedWord(word.getLong(0), word.getString(1)) } } }
        return LyricLine(kept.getLong(0), kept.getString(1), words.orEmpty(), kept.optLong(3, -1L).takeIf { it >= 0 })
    }

    /** Words kept, and whether they're still to be timed better. */
    private fun fromCache(kept: String): Pair<Lyrics.Found, Boolean>? {
        // "None", as earlier versions kept, isn't words, so it's asked again.
        return try {
            val json = JSONObject(kept)
            val lines = json.getJSONArray("lines").let { list ->
                (0 until list.length()).map { index -> lineFromCache(list.getJSONArray(index)) }
            }
            Lyrics.Found(json.getString("words"), lines, json.optLong("length", -1L).takeIf { it > 0 }, json.optString("source").ifEmpty { null }) to json.optBoolean("provisional")
        } catch (_: JSONException) {
            null
        }
    }

    /**
     * Under the title as given, then, for one that's two names joined by a dash, as YouTube Music
     * gives "ミステリー・ガール - Mystery Girl", under each of them, and last under its bare title: a
     * video named "Song (Loop y Extendido)" has the song's words, though no one keeps them under that.
     * Words in Latin letters, Japanese in romaji or Korean, found under one give way to the song's
     * own, in its script, under another, when they're timed as well.
     */
    private suspend fun anywhere(metadata: MusicMetadata, timeoutMs: Long): Lyrics {
        var fallback: Lyrics.Found? = null
        val names = SongNames.titleNames(metadata.title).distinct()
        val answers = (names + SongNames.bareTitle(metadata.title)).distinct().map { title ->
            val kept = fallback
            // A bare title may name another edit; timedWell checks whether its times can be lent.
            if (title !in names && kept != null && romanizedScript(kept.words) == null) return kept
            underName(metadata.copy(title = title), timeoutMs).also { answer ->
                if (answer is Lyrics.Found) {
                    val native = romanizedScript(answer.words) == null
                    fallback = if (kept == null) answer else betterNamed(kept, answer)
                    // Untimed words stay as a fallback while the other names may have timings.
                    if (native && timing(answer) >= WELL_TIMED && fallback === answer) return answer
                }
            }
        }
        return fallback ?: answers.reduce(::better)
    }

    /** Across a title's names, better timing wins; among equals, the song's own script does. */
    private fun betterNamed(first: Lyrics.Found, second: Lyrics.Found): Lyrics.Found {
        if (timing(second) > timing(first)) return second
        val ownScript = romanizedScript(first.words) != null && romanizedScript(second.words) == null
        return if (ownScript && timing(second) == timing(first)) second else first
    }

    /**
     * LRCLIB's words first. Words only there in Latin letters, Japanese in romaji or Korean, give
     * way to the song's own, in its script, from wherever else has them; words with no timings give
     * way to timed ones, which can follow the song.
     */
    private suspend fun underName(metadata: MusicMetadata, timeoutMs: Long): Lyrics {
        val first = fromLrclib(metadata, timeoutMs).seen()
        val romanized = (first as? Lyrics.Found)?.let { romanizedScript(it.words) }
        // Words timed badly are no better than untimed ones: both give way to words timed well.
        val untimed = first is Lyrics.Found && timing(first) < WELL_TIMED
        // Words timed for a recording that isn't as long as the one playing are another take's, so
        // they give way to words from somewhere else that are timed for one that is.
        val otherTake = (first as? Lyrics.Found)?.takeUnless { fits(it, metadata) }
        val bettered = romanized != null || untimed || otherTake != null
        if (first is Lyrics.Found && !bettered) return first
        val answers = mutableListOf(first)
        // Words in Latin letters are only bettered by words in the song's script, untimed words by timed
        // ones, and another take's by this one's.
        suspend fun tried(answer: Lyrics) = answer.seen().also { answers += it }.let {
            it is Lyrics.Found && (romanized == null || it.words.isIn(romanized)) && (!untimed || timing(it) >= WELL_TIMED) &&
                (otherTake == null || (it.lengthMs != null && fits(it, metadata) && !timedApart(otherTake, it)))
        }
        val recording = withTimeoutOrNull(timeoutMs) { inStore(metadata) }
        // A song given in its own script is often kept under the store's names in Latin letters, ILLIT's
        // Magnetic for 아일릿's; one given in Latin letters, under the names its own country gives it.
        val own = recording?.let { inLatin(metadata, it) ?: withTimeoutOrNull(timeoutMs) { ownNames(metadata, it.id) } }
        if (own != null && tried(fromLrclib(own, timeoutMs))) return answers.last()
        // The recording playing is the one to time them for; the store's may be another take.
        val lengthMs = metadata.lengthMs ?: recording?.lengthMs
        // Under its own names the artist may still be credited as the music app names them, as
        // "I Re'in For Re'in" is, rather than "アイリーン・フォーリーン".
        if (tried(fromNetEase(own ?: metadata, lengthMs, timeoutMs, alsoBy = metadata.artist))) return answers.last()
        if (own != null && tried(fromNetEase(metadata, lengthMs, timeoutMs))) return answers.last()
        // Letras's words are untimed, so they can't better untimed ones.
        if (!untimed && tried(fromLetras(metadata, timeoutMs))) return answers.last()
        // None better: the best timed of what was found, the first among equals, e.g. words timed
        // badly over untimed ones, as they still mostly follow the song and can be put in time by hand.
        val best = answers.reduce(::better)
        // LRCLIB, which has most songs' words, busy all along while the others had none: asked once
        // more, as they took a while. Still busy, it isn't known that the song has none.
        if (first !is Lyrics.Unavailable || best !is Lyrics.None) return best
        return fromLrclib(metadata, timeoutMs)
    }

    /**
     * Whether [other]'s lines are timed so unlike [one]'s, the same words lined up line by line, that no
     * take of the song is sung both ways: the gap between them jumps more often than not. Another take
     * moves them all alike, or in a step or two where it's cut. NetEase has "Every Little Thing She Does
     * Is Magic" timed for a recording as long as The Police's video, a few seconds off here, hardly
     * there, while LRCLIB's, for the album, are on time.
     */
    private fun timedApart(one: Lyrics.Found, other: Lyrics.Found): Boolean {
        val gaps = sameLines(one.lines, other.lines).map { (ours, theirs) -> theirs.timeMs - ours.timeMs }
        if (gaps.size < MIN_SAME_LINES) return false
        return gaps.zipWithNext().count { (a, b) -> abs(b - a) > JUMP_MS } * 2 > gaps.size - 1
    }

    /** The lines of [a] and [b] with the same words, in order, paired: as many as can be. */
    private fun sameLines(a: List<LyricLine>, b: List<LyricLine>): List<Pair<LyricLine, LyricLine>> {
        val ours = a.map { BorrowedTimings.sound(it.text) }
        val theirs = b.map { BorrowedTimings.sound(it.text) }
        // Words in other letters sound like nothing here, and nothing is the same as nothing.
        fun same(i: Int, j: Int) = ours[i].isNotEmpty() && ours[i] == theirs[j]
        val most = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            most[i][j] = if (same(i, j)) most[i + 1][j + 1] + 1 else maxOf(most[i + 1][j], most[i][j + 1])
        }
        val pairs = mutableListOf<Pair<LyricLine, LyricLine>>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                same(i, j) -> pairs += a[i++] to b[j++]
                most[i + 1][j] >= most[i][j + 1] -> i++
                else -> j++
            }
        }
        return pairs
    }

    /**
     * YouTube Music's words for the song, from the page its app shows them on. They come timed when
     * its lyrics service has them so, and are otherwise the words alone. Null when it has none, or
     * doesn't answer in time.
     */
    private suspend fun fromYouTubeMusic(metadata: MusicMetadata, timeoutMs: Long): Lyrics.Found? {
        val find = youTubeMusicSong
        if (find == null && RecordingKey.youTubeId(metadata) == null) return null
        return withTimeoutOrNull(timeoutMs) {
            try {
                val videoId = RecordingKey.youTubeId(metadata) ?: find?.invoke(metadata) ?: return@withTimeoutOrNull null
                val watching = post(YOUTUBE_MUSIC_NEXT_URL, innertube(WEB_CLIENT, WEB_CLIENT_VERSION).put("videoId", videoId))
                // A song without words has no page for them.
                val page = lyricsPage.find(watching)?.groupValues?.get(1) ?: return@withTimeoutOrNull null
                // Only YouTube Music's app is given them timed.
                val words = youTubeMusicWords(JSONObject(post(YOUTUBE_MUSIC_BROWSE_URL, innertube(APP_CLIENT, APP_CLIENT_VERSION).put("browseId", page))))
                // Timed for that very release, which, found by its name, may be another edit of the song.
                words?.copy(lengthMs = MetadataParsers.youtubeMusicVideo(JSONObject(watching), videoId)?.lengthMs)
            } catch (_: IOException) {
                null
            } catch (_: JSONException) {
                null
            }
        }
    }

    private fun innertube(name: String, version: String) =
        JSONObject().put("context", JSONObject().put("client", JSONObject().put("clientName", name).put("clientVersion", version).put("hl", "en")))

    private suspend fun post(url: String, body: JSONObject): String {
        val request = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return client.newCall(request).answer(ioDispatcher) { response ->
            if (!response.isSuccessful) throw IOException("YouTube Music returned ${response.code}")
            response.body.stringAtMost()
        }
    }

    /**
     * A song LRCLIB is busy for is asked about again, a moment later, as it asks, since it often turns
     * away many lookups in a row; an answer of "none" is final. All the tries together get
     * [LRCLIB_TIMEOUT_MS], so the other places still get their turn.
     */
    private suspend fun fromLrclib(metadata: MusicMetadata, timeoutMs: Long): Lyrics =
        withTimeoutOrNull(LRCLIB_TIMEOUT_MS) { lrclib(metadata, timeoutMs) } ?: Lyrics.Unavailable

    private suspend fun lrclib(metadata: MusicMetadata, timeoutMs: Long): Lyrics {
        repeat(TRIES) { tried ->
            if (tried > 0) delay(busyPauseMs)
            val answer = withTimeoutOrNull(timeoutMs) {
                try {
                    // No list at all means it wouldn't answer, which is not a song without words.
                    val answers = answersFor(metadata) ?: return@withTimeoutOrNull Lyrics.Unavailable
                    wordsOf(answers, metadata, timeoutMs) ?: Lyrics.None
                } catch (_: IOException) {
                    Lyrics.Unavailable
                } catch (_: JSONException) {
                    Lyrics.Unavailable
                }
            } ?: Lyrics.Unavailable
            if (answer !is Lyrics.Unavailable) return answer
        }
        return Lyrics.Unavailable
    }

    /** The song's words among [answers], or that it's an instrumental; null for neither. */
    private suspend fun wordsOf(answers: List<Answer>, metadata: MusicMetadata, timeoutMs: Long): Lyrics? {
        // The service's own answers first, then a close one, as [ExactMatcher] does. Among those, one
        // with timed words wins, so they can follow the song.
        val named = answers.filter { it.isExact(metadata) }.ifEmpty { answers.filter { it.belongsTo(metadata) } }
        // Timed copies of different lengths are different takes, e.g. a live one named the same, so
        // the one as long as the song comes first: as the app playing it or its service says, or
        // else the iTunes Store, asked only then.
        val lengths = named.filter { it.synced().isNotEmpty() }.mapNotNull { it.lengthMs() }
        val takes = lengths.isNotEmpty() && lengths.max() - lengths.min() > SAME_LENGTH_MS
        val lengthMs = metadata.lengthMs ?: if (takes) withTimeoutOrNull(timeoutMs) { inStore(metadata) }?.lengthMs else null
        val songs = lengthMs?.let { length -> named.sortedBy { copy -> copy.lengthMs()?.let { abs(it - length).takeIf { gap -> gap <= SAME_LENGTH_MS } } ?: Long.MAX_VALUE } } ?: named
        val song = songs.firstOrNull { it.synced().isNotEmpty() } ?: songs.firstOrNull { it.plain() != null } ?: songs.firstOrNull() ?: return null
        val lines = song.synced()
        // Timed words are the words too, once their timings are taken off.
        val words = song.plain() ?: lines.joinToString("\n") { it.text }.trim().ifEmpty { return Lyrics.Instrumental.takeIf { named.any(Answer::instrumental) } }
        return Lyrics.Found(words, lines, song.lengthMs(), LRCLIB)
    }

    /**
     * Whether [found] is timed for a recording as long as the one playing, or either length isn't
     * known, so there's no telling it isn't.
     */
    private fun fits(found: Lyrics.Found, metadata: MusicMetadata) = found.timedFor(metadata.lengthMs)

    /** A recording in the iTunes Store: its [id], how long it lasts, when that's given, and the [names] it's under there. */
    private data class Recording(val id: Long, val lengthMs: Long?, val names: MusicMetadata)

    /**
     * The song in the US iTunes Store, which names it as music apps do, in Latin letters: by the
     * artist given, or by one it only writes in another script, or, for an artist given in another
     * script, as Korea's store gives 아일릿 for ILLIT, by one as long. Under another title, as it
     * translates 체포하겠어 as You're Under Arrest, it's the song when the store of the country that
     * writes the names given has it under them. Null when it's not there.
     */
    private suspend fun inStore(metadata: MusicMetadata): Recording? = try {
        val search = ITUNES_SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("term", "${metadata.title} ${metadata.artist}")
            .addQueryParameter("country", "us")
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "10")
            .build()
        val songs = (parse(search.toString()) as? JSONObject)?.optJSONArray("results")?.let { list -> (0 until list.length()).mapNotNull { list.optJSONObject(it) } }.orEmpty()
        // Null when the song's length isn't known.
        fun asLong(song: JSONObject) = metadata.lengthMs?.let { abs(song.optLong("trackTimeMillis") - it) <= SAME_RECORDING_LENGTH_MS }
        val named = songs.firstOrNull { song ->
            val artist = song.optString("artistName")
            SongNames.same(song.optString("trackName"), metadata.title) &&
                (SongNames.sameArtist(artist, metadata.artist) || !artist.hasLatin() || (asLong(song) == true && !metadata.artist.hasLatin()))
        } ?: songs.firstOrNull { asLong(it) != false }?.takeIf { namedAtHome(metadata, it.optLong("trackId")) }
        named?.let { song ->
            val names = metadata.copy(title = song.optString("trackName"), artist = song.optString("artistName"))
            Recording(song.optLong("trackId"), song.optLong("trackTimeMillis").takeIf { it > 0 }, names).takeIf { it.id > 0 }
        }
    } catch (_: IOException) {
        null
    } catch (_: JSONException) {
        null
    }

    /** Whether the store of the country that writes [metadata]'s names so has recording [id] under them. */
    private suspend fun namedAtHome(metadata: MusicMetadata, id: Long): Boolean {
        val store = SongNames.homeStore(metadata.title + metadata.artist) ?: return false
        val lookup = ITUNES_LOOKUP_URL.toHttpUrl().newBuilder()
            .addQueryParameter("id", id.toString())
            .addQueryParameter("country", store)
            .build()
        val song = (parse(lookup.toString()) as? JSONObject)?.optJSONArray("results")?.optJSONObject(0) ?: return false
        return SongNames.same(song.optString("trackName"), metadata.title) && SongNames.sameArtist(song.optString("artistName"), metadata.artist)
    }

    /** [recording]'s names in Latin letters, when [metadata]'s artist isn't written in them. */
    private fun inLatin(metadata: MusicMetadata, recording: Recording) = recording.names.takeIf { !metadata.artist.hasLatin() && it.artist.hasLatin() }

    /**
     * The song's names as written where it comes from, say in Japanese, when they're not the ones
     * given: the Japanese iTunes Store names recording [id] in its own. Null when it's not there, or
     * has no other names.
     */
    private suspend fun ownNames(metadata: MusicMetadata, id: Long): MusicMetadata? = try {
        val lookup = ITUNES_LOOKUP_URL.toHttpUrl().newBuilder()
            .addQueryParameter("id", id.toString())
            .addQueryParameter("country", "jp")
            .build()
        (parse(lookup.toString()) as? JSONObject)?.optJSONArray("results")?.optJSONObject(0)?.let { song ->
            val title = song.optString("trackName").trim()
            val artist = song.optString("artistName").trim()
            metadata.copy(title = title, artist = artist).takeIf {
                title.isNotEmpty() && artist.isNotEmpty() && !(SongNames.same(title, metadata.title) && SongNames.same(artist, metadata.artist))
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: JSONException) {
        null
    }

    /**
     * NetEase Cloud Music's words for the song, which has many from Japan, Korea and China that
     * LRCLIB hasn't. Only a song of the same name by the same artist is taken, and the credits it
     * opens its words with are left off.
     */
    private suspend fun fromNetEase(metadata: MusicMetadata, lengthMs: Long?, timeoutMs: Long, alsoBy: String = metadata.artist): Lyrics = withTimeoutOrNull(timeoutMs) {
        try {
            val search = NETEASE_SEARCH_URL.toHttpUrl().newBuilder()
                .addQueryParameter("s", "${metadata.title} ${metadata.artist}")
                .addQueryParameter("type", "1")
                .addQueryParameter("limit", "10")
                .build()
            val result = (fromNetEaseOnce(search.toString()) as? JSONObject)?.optJSONObject("result")
                ?: return@withTimeoutOrNull Lyrics.Unavailable
            val songs = result.optJSONArray("songs") ?: return@withTimeoutOrNull Lyrics.None
            val matching = (0 until songs.length()).mapNotNull { songs.optJSONObject(it) }.filter { song ->
                val artists = song.optJSONArray("artists")?.let { list -> (0 until list.length()).map { list.optJSONObject(it)?.optString("name").orEmpty() } }.orEmpty()
                // An artist only written in another script there, say パイパー for Piper, or in Latin
                // letters for one given in another, YANGHONGWON for 양홍원, counts when the recording
                // lasts as long as the store's, or the music app's.
                val length = lengthMs ?: metadata.lengthMs
                val sameLength = length != null && abs(song.optLong("duration") - length) <= SAME_RECORDING_LENGTH_MS
                val name = song.optString("name")
                val album = song.optJSONObject("album")?.optString("name").orEmpty()
                // Its album may say what version it is, "Song" on "… Remix". A Japanese title NetEase writes
                // with China's kanji, as it does many, is the same written so; one with kanji Japan simplified
                // its own way, 楽 for 乐, counts when the recording lasts as long.
                (SongNames.sameVersion(metadata.title, name, album) ||
                    SongNames.sameVersion(SongNames.asChinaWrites(metadata.title), SongNames.asChinaWrites(name), SongNames.asChinaWrites(album)) ||
                    (sameLength && SongNames.sameButKanji(metadata.title, name))) &&
                    artists.any { name ->
                        listOf(SongNames.withGuests(metadata.artist, metadata.title), alsoBy).any { SongNames.sameArtist(name, it) || SongNames.artistInside(name, it) } || (sameLength && (!name.hasLatin() || !metadata.artist.hasLatin()))
                    }
            }
            // Of the same song, the recording as long as the one playing is the one timed for it.
            val wanted = lengthMs ?: metadata.lengthMs
            val chosen = matching.firstOrNull { song -> wanted != null && abs(song.optLong("duration") - wanted) <= SAME_LENGTH_MS } ?: matching.firstOrNull()
                ?: return@withTimeoutOrNull Lyrics.None
            val id = chosen.optLong("id")
            val recordingMs = chosen.optLong("duration").takeIf { it > 0 }
            val lyric = NETEASE_LYRIC_URL.toHttpUrl().newBuilder()
                .addQueryParameter("id", id.toString())
                .addQueryParameter("lv", "1")
                // Timed word by word as well, for the songs it has so.
                .addQueryParameter("yv", "1")
                .build()
            val answer = fromNetEaseOnce(lyric.toString()) as? JSONObject
            val byWord = answer?.optJSONObject("yrc")?.optString("lyric").orEmpty()
            val lrc = answer?.optJSONObject("lrc")?.optString("lyric").orEmpty()
            // Its note for an instrumental, "Pure music, please enjoy", takes the words' place.
            if (lrc.contains(NETEASE_INSTRUMENTAL)) return@withTimeoutOrNull Lyrics.Instrumental
            // Words it keeps garbled are none, so another site's are looked for.
            (netEaseWordByWord(byWord, metadata) ?: netEaseWords(lrc, metadata))?.takeUnless { readAsGbk(it.words) }?.copy(lengthMs = recordingMs) ?: Lyrics.None
        } catch (_: IOException) {
            Lyrics.Unavailable
        } catch (_: JSONException) {
            Lyrics.Unavailable
        }
    } ?: Lyrics.Unavailable

    /**
     * Letras.com's words for the song, which has many in Spanish and Portuguese that no one else
     * does, from the page its own search points to. They come untimed. It only answers a request
     * that looks like a browser's, and sends one for another song when the address is guessed, so
     * the address is the search's own.
     */
    private suspend fun fromLetras(metadata: MusicMetadata, timeoutMs: Long): Lyrics = withTimeoutOrNull(timeoutMs) {
        try {
            val search = LETRAS_SEARCH_URL.toHttpUrl().newBuilder()
                .addQueryParameter("q", "${metadata.title} ${metadata.artist}")
                .addQueryParameter("wt", "json")
                // Answered as a call to this function, which is taken off.
                .addQueryParameter("callback", "letras")
                .build()
            val docs = JSONObject(browserPage(search.toString()).substringAfter('(').substringBeforeLast(')')).optJSONObject("response")?.optJSONArray("docs")
                ?: return@withTimeoutOrNull Lyrics.Unavailable
            val song = (0 until docs.length()).mapNotNull { docs.optJSONObject(it) }.firstOrNull { doc ->
                doc.optString("t") == LETRAS_SONG && SongNames.sameVersion(metadata.title, doc.optString("txt")) &&
                    doc.optString("art").let { SongNames.sameArtist(it, metadata.artist) || SongNames.artistInside(it, SongNames.withGuests(metadata.artist, metadata.title)) }
            } ?: return@withTimeoutOrNull Lyrics.None
            val page = LETRAS_URL.toHttpUrl().newBuilder().addPathSegment(song.optString("dns")).addPathSegment(song.optString("url")).build()
            val shown = withoutAds(browserPage(page.toString()))
            // A song in another language has Letras's own translation beside it: its words are the ones on the left.
            val content = (letrasOriginal.find(shown) ?: letrasWords.find(shown))?.groupValues?.get(1)
            // An instrumental's page says so where the words would be, in the page's language.
            if (content?.contains(LETRAS_INSTRUMENTAL) == true) return@withTimeoutOrNull Lyrics.Instrumental
            val words = content
                ?.replace(letrasStanza, "\n\n")?.replace(letrasBreak, "\n")?.replace(letrasTag, "")
                ?.let { ChordFinder.unescape(it).trim() }?.takeIf { it.isNotEmpty() }
            words?.let { Lyrics.Found(it, source = LETRAS) } ?: Lyrics.None
        } catch (_: IOException) {
            Lyrics.Unavailable
        } catch (_: JSONException) {
            Lyrics.Unavailable
        }
    } ?: Lyrics.Unavailable

    /** [page] without the ads Letras puts between a song's verses, each a box of boxes, taken out whole. */
    private fun withoutAds(page: String): String {
        var text = page
        while (true) {
            val start = text.indexOf("<div id=\"pub_").takeIf { it >= 0 } ?: return text
            var depth = 0
            val end = letrasBox.findAll(text, start).firstOrNull { tag ->
                depth += if (tag.value == "</div>") -1 else 1
                depth == 0
            } ?: return text
            text = text.removeRange(start, end.range.last + 1)
        }
    }

    /** A page that only answers a request that looks like a browser's. */
    private suspend fun browserPage(url: String): String {
        val request = Request.Builder().url(url).asPhoneBrowser().get().build()
        return client.newCall(request).answer(ioDispatcher) { response ->
            if (!response.isSuccessful) throw IOException("Page returned ${response.code}")
            response.body.stringAtMost()
        }
    }

    /**
     * The words on YouTube Music's lyrics page, each line with when it starts if they're timed; a
     * note, "♪", where nothing is sung. Null when the page has none, e.g. "Lyrics not available".
     */
    private fun youTubeMusicWords(page: JSONObject): Lyrics.Found? {
        val data = page.optJSONObject("contents")?.optJSONObject("elementRenderer")?.optJSONObject("newElement")?.optJSONObject("type")
            ?.optJSONObject("componentType")?.optJSONObject("model")?.optJSONObject("timedLyricsModel")?.optJSONObject("lyricsData")
            ?.optJSONArray("timedLyricsData") ?: return null
        val all = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
        val texts = all.map { line -> line.optString("lyricLine").trim().takeUnless { it == "♪" }.orEmpty() }
        val words = texts.filter { it.isNotEmpty() }.joinToString("\n").ifEmpty { return null }
        val starts = all.mapNotNull { it.optJSONObject("cueRange")?.optString("startTimeMilliseconds").orEmpty().toLongOrNull() }
        if (starts.size < all.size) return Lyrics.Found(words, source = YOUTUBE_MUSIC)
        return Lyrics.Found(words, SyncedLyrics.withPauses(texts.zip(starts) { text, start -> LyricLine(start, text) }.sortedBy { it.timeMs }), source = YOUTUBE_MUSIC)
    }

    /** NetEase's words timed word by word, without credits; null when it hasn't them so. */
    private fun netEaseWordByWord(yrc: String, song: MusicMetadata): Lyrics.Found? {
        val lines = SyncedLyrics.parseWordByWord(yrc).withoutNetEaseExtras(song) { it.text }.withoutOpeningMoments()
        if (lines.isEmpty()) return null
        return Lyrics.Found(lines.joinToString("\n") { it.text }, lines, source = NETEASE)
    }

    /** NetEase's words, without the credits, such as "作词 : …", it puts first or last; null for none. */
    private fun netEaseWords(lrc: String, song: MusicMetadata): Lyrics.Found? {
        val stamped = SyncedLyrics.parse(lrc)
        val timed = stamped.withoutNetEaseExtras(song) { it.text }
        val lines = SyncedLyrics.withPauses(timed)
        // Words with timings are only those; plain words, only when there are no timings at all.
        val words = if (stamped.isNotEmpty()) timed.joinToString("\n") { it.text }.trim()
        else lrc.lines().withoutNetEaseExtras(song) { it }.joinToString("\n").trim()
        if (words.isEmpty()) return null
        return Lyrics.Found(words, lines, source = NETEASE)
    }

    /** What the service said, or null when it said something that isn't a list of answers. */
    private suspend fun answersFor(metadata: MusicMetadata): List<Answer>? {
        val url = SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("track_name", metadata.title)
            .addQueryParameter("artist_name", metadata.artist)
            .build()
        // LRCLIB answers with a plain list of what it found. Its errors come back as an object
        // naming itself, which is no list of answers, so the difference between "none" and
        // "wouldn't answer" rests on it.
        val list = parse(url.toString()) as? JSONArray ?: return null
        return (0 until list.length()).mapNotNull { index -> Answer.of(list.optJSONObject(index)) }
    }

    /**
     * NetEase's answer, asked once in a lookup: it's asked for the words it times word by word and
     * again under the song's names. LRCLIB isn't kept so, as its answer can be that it's busy.
     */
    private suspend fun fromNetEaseOnce(url: String): Any =
        currentCoroutineContext()[Answers]?.of(url) { parse(url, NETEASE_REFERER) } ?: parse(url, NETEASE_REFERER)

    /** What each address answered in one lookup, and the [copies] of the words found in it. */
    private class Answers : AbstractCoroutineContextElement(Answers) {
        companion object Key : CoroutineContext.Key<Answers>

        val copies = ConcurrentLinkedQueue<Lyrics.Found>()

        private val answers = mutableMapOf<String, CompletableDeferred<Any>>()

        suspend fun of(url: String, ask: suspend () -> Any): Any {
            val mine = CompletableDeferred<Any>()
            val asked = synchronized(answers) { answers.getOrPut(url) { mine } }
            if (asked !== mine) return asked.await()
            return try {
                ask().also { mine.complete(it) }
            } catch (e: Throwable) {
                // Not answered: asked again by whoever needs it next.
                synchronized(answers) { answers.remove(url) }
                mine.completeExceptionally(IOException("Not answered", e))
                throw e
            }
        }
    }

    private suspend fun parse(url: String, referer: String? = null): Any {
        val request = Request.Builder().url(url)
            // NetEase only answers a request that seems to come from its own site.
            .apply { referer?.let { header("Referer", it) } }
            // LRCLIB turns away a request that doesn't say who it is, answering with an error page
            // rather than lyrics, so it names the app and leaves a way to be told about a problem.
            .header("User-Agent", "Crosstune/$versionName (https://github.com/astrovm/crosstune)")
            .get()
            .build()
        return client.newCall(request).answer(ioDispatcher) { response ->
            if (!response.isSuccessful) throw IOException("Lyrics service returned ${response.code}")
            JSONTokener(response.body.stringAtMost()).nextValue()
        }
    }

    /** One answer: whose song it claims to be, and its words. */
    private class Answer(private val title: String, private val artist: String, private val json: JSONObject) {
        fun plain(): String? = (json.opt("plainLyrics") as? String)?.let(::withoutNamesHeader)?.trim()?.takeIf { it.isNotEmpty() }

        /** Whether it says the song has no words. */
        fun instrumental(): Boolean = json.optBoolean("instrumental")

        /** How long the recording it's for lasts, when it says. */
        fun lengthMs(): Long? = (json.opt("duration") as? Number)?.toDouble()?.takeIf { it > 0 }?.let { (it * 1000).toLong() }

        /** The words with when each line is sung, empty when it has none timed. */
        fun synced(): List<LyricLine> = (json.opt("syncedLyrics") as? String)?.let { SyncedLyrics.withPauses(SyncedLyrics.parse(it)) }
            ?.takeIf { TimingEvidence.usable(it, lengthMs()) }.orEmpty()

        /**
         * The very song asked about, name for name. A title compared without its edition tag would
         * make a remaster the same song as the plain recording, so [SongNames.normalize] is used on
         * its own here and no tag is taken off: when both this and a plain-titled answer are there,
         * the plain one wins. [belongsTo] takes the tag off, so no song's words are lost over it.
         */
        fun isExact(metadata: MusicMetadata) =
            SongNames.normalize(title) == SongNames.normalize(metadata.title) && SongNames.sameArtist(artist, metadata.artist)

        /**
         * The same song, near enough: LRCLIB credits an artist as others do, so "Randy" is in
         * "Randy Nota Loca", and a title in another script is written out in letters.
         */
        fun belongsTo(metadata: MusicMetadata) =
            (SongNames.wordsMatch(title, metadata.title) || SongNames.sameVersion(metadata.title, title, json.optString("albumName"))) &&
                SongNames.artistInside(artist, SongNames.withGuests(metadata.artist, metadata.title))

        companion object {
            fun of(json: JSONObject?): Answer? {
                if (json == null) return null
                val title = (json.opt("trackName") as? String)?.trim().orEmpty()
                val artist = (json.opt("artistName") as? String)?.trim().orEmpty()
                if (title.isEmpty() || artist.isEmpty()) return null
                return Answer(title, artist, json)
            }
        }
    }

    internal companion object {
        const val TIMEOUT_MS = 5_000L
        const val TOTAL_TIMEOUT_MS = 30_000L
        const val LRCLIB_TIMEOUT_MS = 12_000L
        const val TRIES = 5

        const val SEARCH_URL = "https://lrclib.net/api/search"
        const val ITUNES_SEARCH_URL = "https://itunes.apple.com/search"
        const val ITUNES_LOOKUP_URL = "https://itunes.apple.com/lookup"
        const val LETRAS_URL = "https://www.letras.com/"
        const val APPLE_MUSIC_SONG_URL = "https://music.apple.com/us/song/"
        const val LETRAS_SEARCH_URL = "https://solr.sscdn.co/letras/m1/"
        const val LETRAS_INSTRUMENTAL = "instrumental-lyric"
        const val LETRAS_SONG = "2"

        // Where words came from, as reports name them.
        const val LRCLIB = "lrclib"

        /** Said after the source of a cover's words that are the original's. */
        const val OF_THE_ORIGINAL = ", the original's"

        /** How many of the iTunes Store's songs of a name are asked for, to tell whose it first was. */
        private const val SONGS_OF_A_NAME = 50

        /**
         * How many of them its first singer has, and how many times as many as anyone else: measured,
         * Evanescence has 10 "Bring Me to Life"s to Halocene's 3, Bronski Beat 20 "Smalltown Boy"s to 3,
         * and no one is that far ahead for "Hello" (4 to 3), "Home" (3 to 3) or "Creep" (6 to 5).
         */
        private const val MIN_FIRST_SINGER_SONGS = 5
        private const val FIRST_SINGER_LEAD = 3
        const val YOUTUBE_MUSIC = "youtube_music"
        const val NETEASE = "netease"
        const val LETRAS = "letras"
        const val LETRAS_BROWSER = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        val letrasWords = Regex("""<div class="lyric-content[^"]*">(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)

        /** On a page with Letras's own translation beside the song, the song's words, after its name. */
        val letrasOriginal = Regex("""<div class="lyric-translation-left">\s*(?:<strong>.*?</strong>)?(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
        private val letrasBox = Regex("""<div\b|</div>""")

        /** Someone credited on an Apple Music song page, and their roles. */
        private val credit = Regex(""""name":"([^"]+)","roleNames":\[([^\]]*)]""")
        private val writerRole = Regex("Songwriter|Composer|Lyricist")
        val letrasStanza = Regex("""(?:<br\s*/?>\s*)?</p>\s*<p>""")
        val letrasBreak = Regex("""<br\s*/?>""")
        val letrasTag = Regex("""<[^>]+>""")
        const val NETEASE_SEARCH_URL = "https://music.163.com/api/search/get"
        const val NETEASE_LYRIC_URL = "https://music.163.com/api/song/lyric"
        const val NETEASE_REFERER = "https://music.163.com/"
        const val YOUTUBE_MUSIC_NEXT_URL = "https://music.youtube.com/youtubei/v1/next?prettyPrint=false"
        const val YOUTUBE_MUSIC_BROWSE_URL = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"
        const val WEB_CLIENT = "WEB_REMIX"
        const val WEB_CLIENT_VERSION = "1.20240101.01.00"
        const val APP_CLIENT = "ANDROID_MUSIC"
        const val APP_CLIENT_VERSION = "7.21.50"

        /**
         * Bumped when words kept from before should be looked up again, the words kept as shown too: 8 for Japanese songs NetEase writes
         * with China's kanji, and words in romaji found under a song's name in Latin letters; 11 for
         * Korean songs found in Latin letters; 12 for Korean words NetEase keeps garbled; 13 for commas it keeps as apostrophes, and the notes it opens with for a moment;
         * 14 for untimed words kept before the other title names were checked for timings; 15 for lines timed late and rushed.
         */
        const val CACHE_VERSION = 15

        /**
         * [found] without what's since learned to be left out: a site's ad, from any site, and what
         * NetEase's contributors add; a song a site keeps in capitals, in sentence case. Words kept from before get each fix too, with no need to look
         * them up again.
         */
        fun cleaned(found: Lyrics.Found, song: MusicMetadata, copies: List<Lyrics.Found> = emptyList()): Lyrics.Found {
            val netEase = found.source?.startsWith(NETEASE) == true
            // A whole song in capitals, as some sites keep it, reads as it's written elsewhere: in sentences.
            val shouting = inCapitals(found.words.ifBlank { found.lines.joinToString("\n") { it.text } })
            if (!netEase && !shouting && !hasAdOrBreak(found)) return found
            // Where NetEase's contributors wrote apostrophes escaped, their commas are apostrophes: "friend' and he\'s back".
            val commas = netEase && "\\'" in found.words
            fun mend(text: String) = mended(if (commas) text.replace(apostropheForComma, ",") else text)
            fun <T> List<T>.clean(text: (T) -> String) = filterNot { webAddress.containsMatchIn(text(it)) }.let { if (netEase) it.withoutNetEaseExtras(song, text) else it }
            val words = found.words.lines().map(::mend).clean { it }.joinToString("\n").trim()
            val lines = found.lines.map { line -> line.copy(text = mend(line.text), words = line.words.map { it.copy(text = mend(it.text)) }) }
                .clean { it.text }
            val kept = found.copy(words = words, lines = SyncedLyrics.withPauses(lines))
            return if (shouting) restoredCase(inSentenceCase(kept), copies.map { it.words }) else kept
        }

        /** Names' capitals from [copies] of the words, in the words and each timed line alike. */
        private fun restoredCase(found: Lyrics.Found, copies: List<String>): Lyrics.Found {
            val casing = LyricCase(copies)
            val texts = casing.apply(found.lines.map { it.text })
            val timed = casing.apply(found.lines.map { line -> line.words.joinToString("") { it.text } })
            return found.copy(words = casing.apply(found.words), lines = found.lines.mapIndexed { index, line ->
                var at = 0
                line.copy(text = texts[index], words = line.words.map { word ->
                    word.copy(text = timed[index].substring(at, at + word.text.length)).also { at += word.text.length }
                })
            })
        }

        private fun hasAdOrBreak(found: Lyrics.Found) = (found.words.lines() + found.lines.map { it.text }).any { webAddress.containsMatchIn(it) || mended(it) != it }

        private fun inSentenceCase(found: Lyrics.Found) = found.copy(
            words = found.words.lines().joinToString("\n", transform = ::inSentenceCase),
            lines = found.lines.map { line ->
                line.copy(text = inSentenceCase(line.text), words = line.words.zip(inSentenceCase(line.words.map { it.text })) { word, text -> word.copy(text = text) })
            }
        )

        /** How long looking for a well-timed copy of badly timed words can take. */
        const val BORROW_TIMEOUT_MS = 15_000L

        /** How much longer or shorter another copy's recording can be and still be the same take, remastered or not. */
        const val SAME_TAKE_LENGTH_MS = 15_000L

        /** How far apart two copies of the same take of a song may be in length. */
        const val SAME_LENGTH_MS = 3_000L

        /** How close two copies' times are, on the middle line, when they're timed alike. */
        const val CLOSE_MS = 2_500L

        /** Lines two copies must have alike to tell how differently they're timed, and how far a gap between them jumps to count. */
        private const val MIN_SAME_LINES = 6
        private const val JUMP_MS = 1_500L

        /** How quick a line's words are, at most, to be said in a hurry; the fastest rap on the songs tried is 165 ms a word. */
        private const val RUSHED_MS_A_WORD = 200L


        /** What NetEase writes for a song without words: "pure music, please enjoy". */
        const val NETEASE_INSTRUMENTAL = "纯音乐，请欣赏"

    }


}


/** The page YouTube Music shows a song's words on, by its id. */
private val lyricsPage = Regex(""""browseId":"(MPLY[^"]+)"""")

/** How well words are timed: well, so they follow the song; badly; or not at all. */
private const val WELL_TIMED = 2

/** Timed well, and word by word. */
private const val WORD_TIMED = 3

private fun timing(answer: LyricsFinder.Lyrics): Int {
    val lines = if (answer is LyricsFinder.Lyrics.Found) answer.lines else emptyList()
    return when {
        lines.isEmpty() -> 0
        SyncedLyrics.rushed(lines) -> 1
        lines.all { it.text.isBlank() || it.words.isNotEmpty() } -> WORD_TIMED
        else -> WELL_TIMED
    }
}

/** Whether any of it is written in Japanese: kana, or the kanji Japanese shares with Chinese. */
private fun String.hasJapanese() = any { it in '\u3040'..'\u30FF' || it in '\u4E00'..'\u9FFF' }

/** Whether any of it is written in Hangul. */
private fun String.hasHangul() = any { it in '\uAC00'..'\uD7A3' }

/** Whether any of it is written in [script], Japanese or Korean. */
private fun String.isIn(script: Script) = if (script == Script.KOREAN) hasHangul() else hasJapanese()

/** A word that could be Japanese in Latin letters: syllables like "ka", "shi", "tsu", "n", and doubled consonants. */
private val romajiWord = Regex("""^(?:(?:ch|sh|ts|[kgsztdnhbpmyrwfj])?y?[aiueo]|n(?![aiueoy])|([kstpgdbc])(?=\1))+$""")

/** Little words Japanese can't do without, which English rarely uses. */
private val romajiParticles = setOf("ni", "wa", "ga", "wo", "de", "mo", "kara", "made", "yo", "ne", "dake", "kimi", "boku", "watashi", "anata")

/**
 * Whether [words] are Japanese written in Latin letters: most words read as Japanese syllables, and
 * its little words show up again and again. English has a few such words, never that many.
 */
internal fun isRomaji(words: String): Boolean {
    if (words.hasJapanese()) return false
    val all = words.lowercase().split(Regex("[^a-z']+")).filter { it.isNotEmpty() }
    if (all.isEmpty()) return false
    val japanese = all.count { romajiWord.matches(it) }
    return japanese * 20 >= all.size * 9 && all.count { it in romajiParticles } >= 3
}

/** A word that could be Korean in Latin letters: syllables like "geu", "saek", "ttae", "jeong". */
private val romanizedKoreanWord =
    Regex("""^(?:(?:kk|tt|pp|ss|jj|ch|[gkndtrlmbpsjh])?(?:yae|yeo|wae|ae|ya|eo|ye|wa|oe|yo|wo|we|wi|yu|eu|ui|[aeoui])(?:ng|[knltmpgbds])?)+$""")

/** Little words Korean songs can't do without, which other languages rarely use. */
private val koreanLittleWords = setOf(
    "neon", "nan", "nal", "neol", "nae", "naega", "naege", "nega", "neoui", "neoreul", "nareul", "naneun", "neoneun",
    "geu", "geudae", "uri", "wae", "mwo", "dasi", "jigeum", "ige", "jeongmal", "neomu", "hamkke",
)

/**
 * Whether [words] are Korean written in Latin letters, as ILLIT's "Geu ttae aiseutineun nae mat":
 * most words read as Korean syllables, and its little words show up again and again. Hindi and
 * Indonesian read as such syllables too, but never use those words.
 */
internal fun isRomanizedKorean(words: String): Boolean {
    if (words.hasHangul() || words.hasJapanese()) return false
    val all = words.lowercase().split(Regex("[^a-z']+")).filter { it.isNotEmpty() }
    if (all.isEmpty()) return false
    val korean = all.count { romanizedKoreanWord.matches(it) }
    return korean * 20 >= all.size * 9 && all.count { it in koreanLittleWords } >= 3
}

/** What [words] are written in, when it's Japanese or Korean in Latin letters. */
internal fun romanizedScript(words: String): Script? = when {
    isRomaji(words) -> Script.JAPANESE
    isRomanizedKorean(words) -> Script.KOREAN
    else -> null
}

/**
 * As a phone's browser asks for a page, which Letras and Cifra Club, sites of the same company,
 * answer only that way: they turn away a request with any less.
 */
internal fun Request.Builder.asPhoneBrowser(): Request.Builder = header("User-Agent", LyricsFinder.LETRAS_BROWSER)
    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
    .header("Accept-Language", "es-AR,es;q=0.9,en;q=0.8")
    .header("Upgrade-Insecure-Requests", "1")
    .header("Sec-Fetch-Mode", "navigate")
    .header("Sec-Fetch-Dest", "document")
    .header("Sec-Fetch-Site", "none")
