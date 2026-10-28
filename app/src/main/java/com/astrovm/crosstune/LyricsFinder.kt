package com.astrovm.crosstune

import kotlin.math.abs
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

/** What LRCLIB's Retry-After asks for when it's busy. */
internal const val LYRICS_BUSY_PAUSE_MS = 1_000L

/** How far apart two recordings' lengths can be and still be the same one. */
internal const val SAME_RECORDING_LENGTH_MS = 3_000L

/** A credit line: who wrote, composed, arranged, produced or published it, in Chinese, Japanese or English: "SP：…". */
private val netEaseCredit = Regex("""^\s*(?:作词|作詞|作曲|编曲|編曲|制作人|製作人|词|詞|曲|Lyricist|Lyrics|Composer|Arranger|Producer|OP|SP)\s*[:：]""", RegexOption.IGNORE_CASE)

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
    fun afterCredits(from: Int): Int {
        var start = from
        while (start < size) {
            val line = lines[start]
            val label = netEaseOpeningCredit.find(line)?.groupValues?.get(1)?.trim()
            if (line.isNotEmpty() && (label == null || label.none(Char::isLetter) || sungPart.matches(label) || label.split(' ').size > 3)) break
            start++
        }
        return start
    }
    var start = afterCredits(0)
    // The name then the artist, as a heading, or the other way round: neither alone, as a song may open with its name.
    // The name may be without what the store adds to it: "Let Down" for "Let Down (Remastered)".
    val titles = setOf(song.title, SongNames.bareTitle(song.title)).map(SongNames::normalize)
    val artist = SongNames.normalize(song.artist)
    val names = lines.drop(start).filter { it.isNotEmpty() }.take(2).map(SongNames::normalize)
    if (titles.any { names.toSet() == setOf(it, artist) }) {
        repeat(2) { while (lines[start].isEmpty()) start++; start++ }
    } else if (lines.getOrNull(start)?.let { isHeading(it, song) } == true) {
        start++
        // A guest, under it: "（feat. Dwele）".
        while (lines.getOrNull(start)?.let(guestCredit::matches) == true) start++
    }
    return drop(afterCredits(start))
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
private val headingWords = listOf("lyrics", "explicit", "featuring", "feat", "ft")

/** A line that's only a guest's credit: "（feat. Dwele）", "ft. Someone". */
private val guestCredit = Regex("""(?i)\s*[(（\[]?\s*(?:feat\.?|ft\.?|featuring)\s+[^)）\]]+[)）\]]?\s*""")

/** A web address, as a lyrics site's ad for itself: "{more ILLIT lyrics at lyricsify.com/u/KPopLRCFiles}". */
private val webAddress = Regex("""(?i)https?://|www\.|\b[a-z0-9-]+\.[a-z]{2,6}/""")

/** The lines without all NetEase's contributors add to a song's words: credits, a heading, notes and parts' names. */
private fun <T> List<T>.withoutNetEaseExtras(song: MusicMetadata, text: (T) -> String): List<T> =
    filterNot { netEaseCredit.containsMatchIn(text(it)) }.withoutOpeningCredits(song, text).withoutClosingCredits(text).withoutNotes(text)

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
        val (best, own) = withTimeoutOrNull(TOTAL_TIMEOUT_MS) { best(metadata, timeoutMs) } ?: Best(Lyrics.Unavailable)
        val found = (best as? Lyrics.Found)?.let { cleaned(it, metadata) } ?: best
        // Kept at once, so the song shown again soon needs no lookup; still to be timed better, unless
        // they're the recording's own, which no copy found elsewhere times better.
        cache?.let { cache ->
            toCache(found, provisional = !own)?.let { cache.put(cacheKey(metadata), it) }
            cache.save()
        }
        return FirstLook(asJapanWrites(found), final = own)
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
    private suspend fun best(metadata: MusicMetadata, timeoutMs: Long): Best = withContext(Answers()) {
        val official = async { fromYouTubeMusic(metadata, timeoutMs) }
        // Words timed word by word beat any timed a line at a time, and only NetEase has them.
        val byWord = async { (fromNetEase(metadata, null, timeoutMs) as? Lyrics.Found)?.takeIf { timing(it) == WORD_TIMED } }
        val elsewhere = async { anywhere(metadata, timeoutMs) }
        val own = official.await()
        // A word-timed search result isn't better evidence than this recording's own timed lyrics, so
        // nothing else is worth waiting for: NetEase alone takes a few seconds.
        if (own != null && RecordingKey.youTubeId(metadata) != null && timing(own) >= WELL_TIMED) {
            byWord.cancel()
            elsewhere.cancel()
            return@withContext Best(own, own = true)
        }
        val found = own?.let { better(it, elsewhere.await()) } ?: elsewhere.await()
        val word = byWord.await() ?: return@withContext Best(found)
        // Another take's words, timed word by word, don't beat this one's, timed a line at a time.
        if (found is Lyrics.Found && fits(found, metadata) && !fits(word, metadata)) return@withContext Best(found)
        Best(better(word, found))
    }

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
        var romanized: Lyrics.Found? = null
        val answers = (SongNames.titleNames(metadata.title) + SongNames.bareTitle(metadata.title)).distinct().map { title ->
            underName(metadata.copy(title = title), timeoutMs).also { answer ->
                val kept = romanized
                if (answer is Lyrics.Found) {
                    if (kept == null && romanizedScript(answer.words) != null) romanized = answer
                    else if (kept == null || (romanizedScript(answer.words) == null && timing(answer) >= timing(kept))) return answer
                }
            }
        }
        return romanized ?: answers.reduce(::better)
    }

    /**
     * LRCLIB's words first. Words only there in Latin letters, Japanese in romaji or Korean, give
     * way to the song's own, in its script, from wherever else has them; words with no timings give
     * way to timed ones, which can follow the song.
     */
    private suspend fun underName(metadata: MusicMetadata, timeoutMs: Long): Lyrics {
        val first = fromLrclib(metadata, timeoutMs)
        val romanized = (first as? Lyrics.Found)?.let { romanizedScript(it.words) }
        // Words timed badly are no better than untimed ones: both give way to words timed well.
        val untimed = first is Lyrics.Found && timing(first) < WELL_TIMED
        // Words timed for a recording that isn't as long as the one playing are another take's, so
        // they give way to words from somewhere else that are timed for one that is.
        val otherTake = first is Lyrics.Found && !fits(first, metadata)
        if (first is Lyrics.Found && romanized == null && !untimed && !otherTake) return first
        val answers = mutableListOf(first)
        // Words in Latin letters are only bettered by words in the song's script, untimed words by timed
        // ones, and another take's by this one's.
        suspend fun tried(answer: Lyrics) = answer.also { answers += it }.let {
            it is Lyrics.Found && (romanized == null || it.words.isIn(romanized)) && (!untimed || timing(it) >= WELL_TIMED) &&
                (!otherTake || (it.lengthMs != null && fits(it, metadata)))
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
        return answers.reduce(::better)
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
                youTubeMusicWords(JSONObject(post(YOUTUBE_MUSIC_BROWSE_URL, innertube(APP_CLIENT, APP_CLIENT_VERSION).put("browseId", page))))
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
            (netEaseWordByWord(byWord, metadata) ?: netEaseWords(lrc, metadata))?.copy(lengthMs = recordingMs) ?: Lyrics.None
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
            val docs = JSONObject(letrasPage(search.toString()).substringAfter('(').substringBeforeLast(')')).optJSONObject("response")?.optJSONArray("docs")
                ?: return@withTimeoutOrNull Lyrics.Unavailable
            val song = (0 until docs.length()).mapNotNull { docs.optJSONObject(it) }.firstOrNull { doc ->
                doc.optString("t") == LETRAS_SONG && SongNames.sameVersion(metadata.title, doc.optString("txt")) &&
                    doc.optString("art").let { SongNames.sameArtist(it, metadata.artist) || SongNames.artistInside(it, SongNames.withGuests(metadata.artist, metadata.title)) }
            } ?: return@withTimeoutOrNull Lyrics.None
            val page = LETRAS_URL.toHttpUrl().newBuilder().addPathSegment(song.optString("dns")).addPathSegment(song.optString("url")).build()
            val shown = withoutAds(letrasPage(page.toString()))
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

    private suspend fun letrasPage(url: String): String {
        val request = Request.Builder().url(url).asPhoneBrowser().get().build()
        return client.newCall(request).answer(ioDispatcher) { response ->
            if (!response.isSuccessful) throw IOException("Lyrics service returned ${response.code}")
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
        val starts = all.map { it.optJSONObject("cueRange")?.optString("startTimeMilliseconds")?.toLongOrNull() }
        if (starts.any { it == null }) return Lyrics.Found(words, source = YOUTUBE_MUSIC)
        return Lyrics.Found(words, SyncedLyrics.withPauses(texts.mapIndexed { index, text -> LyricLine(starts[index]!!, text) }.sortedBy { it.timeMs }), source = YOUTUBE_MUSIC)
    }

    /** NetEase's words timed word by word, without credits; null when it hasn't them so. */
    private fun netEaseWordByWord(yrc: String, song: MusicMetadata): Lyrics.Found? {
        val lines = SyncedLyrics.parseWordByWord(yrc).withoutNetEaseExtras(song) { it.text }
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

    /** What each address answered in one lookup. */
    private class Answers : AbstractCoroutineContextElement(Answers) {
        companion object Key : CoroutineContext.Key<Answers>

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
        fun plain(): String? = (json.opt("plainLyrics") as? String)?.trim()?.takeIf { it.isNotEmpty() }

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
        const val LETRAS_SEARCH_URL = "https://solr.sscdn.co/letras/m1/"
        const val LETRAS_INSTRUMENTAL = "instrumental-lyric"
        const val LETRAS_SONG = "2"

        // Where words came from, as reports name them.
        const val LRCLIB = "lrclib"
        const val YOUTUBE_MUSIC = "youtube_music"
        const val NETEASE = "netease"
        const val LETRAS = "letras"
        const val LETRAS_BROWSER = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        val letrasWords = Regex("""<div class="lyric-content[^"]*">(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)

        /** On a page with Letras's own translation beside the song, the song's words, after its name. */
        val letrasOriginal = Regex("""<div class="lyric-translation-left">\s*(?:<strong>.*?</strong>)?(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
        private val letrasBox = Regex("""<div\b|</div>""")
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
         * Bumped when words kept from before should be looked up again: 8 for Japanese songs NetEase writes
         * with China's kanji, and words in romaji found under a song's name in Latin letters; 11 for
         * Korean songs found in Latin letters.
         */
        const val CACHE_VERSION = 11

        /**
         * [found] without what's since learned to be left out: a site's ad, from any site, and what
         * NetEase's contributors add. Words kept from before get each fix too, with no need to look
         * them up again.
         */
        fun cleaned(found: Lyrics.Found, song: MusicMetadata): Lyrics.Found {
            val netEase = found.source?.startsWith(NETEASE) == true
            val ads = found.words.lines().any(webAddress::containsMatchIn) || found.lines.any { webAddress.containsMatchIn(it.text) }
            if (!netEase && !ads) return found
            fun <T> List<T>.clean(text: (T) -> String) = filterNot { webAddress.containsMatchIn(text(it)) }.let { if (netEase) it.withoutNetEaseExtras(song, text) else it }
            val words = found.words.lines().clean { it }.joinToString("\n").trim()
            return found.copy(words = words, lines = SyncedLyrics.withPauses(found.lines.clean { it.text }))
        }

        /** How long looking for a well-timed copy of badly timed words can take. */
        const val BORROW_TIMEOUT_MS = 15_000L

        /** How much longer or shorter another copy's recording can be and still be the same take, remastered or not. */
        const val SAME_TAKE_LENGTH_MS = 15_000L

        /** How far apart two copies of the same take of a song may be in length. */
        const val SAME_LENGTH_MS = 3_000L

        /** How close two copies' times are, on the middle line, when they're timed alike. */
        const val CLOSE_MS = 2_500L


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
    val lines = (answer as? LyricsFinder.Lyrics.Found)?.lines.orEmpty()
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
