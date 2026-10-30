package com.astrovm.crosstune

import android.content.SharedPreferences
import android.os.SystemClock
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.Closeable
import java.io.File
import java.text.Normalizer
import kotlin.math.abs

/** A word heard sung, and when it starts, [atMs] into what was heard. */
internal data class HeardWord(val text: String, val atMs: Long)

/** Hears the words sung in [samples], 16 kHz mono, in [language] when it's known. */
internal fun interface WordHearing {
    fun words(samples: FloatArray, language: String?): List<HeardWord>
}

/** What was heard sung, from [startedAtMs] on the clock that counts since the phone started. */
internal data class Sung(val startedAtMs: Long, val words: List<HeardWord>) {
    /** When, into what was heard, the first word's sung, not just the sound the model tells of, as "[MUSIC]", "(upbeat music)" or "♪"; null when none is. */
    val voiceAtMs: Long?
        get() {
            var told = false
            return words.firstOrNull { word ->
                val sound = told || word.text.startsWith('[') || word.text.startsWith('(')
                told = sound && !word.text.endsWith(']') && !word.text.endsWith(')')
                !sound && word.text.any { it.isLetterOrDigit() }
            }?.atMs
        }
}

/** Hears the words of the song the phone plays now; null when it can't, or heard none. */
internal interface SingingHearing {
    /**
     * What's sung in the next while the phone plays, in [language] when it's known. Given [first],
     * what's sung in its first [SingingListener.FIRST_SECONDS] is told it as soon as that's heard,
     * while the rest still is.
     */
    suspend fun hear(language: String?, first: (suspend (Sung) -> Unit)?): Sung?

    /** Whether the last try heard nothing for want of the speech model, which wasn't fetched then, e.g. on mobile data. */
    val missesModel: Boolean

    /** Fetches the speech model now, on any network; whether it's here after. */
    suspend fun fetchModel(): Boolean
}

/**
 * Hears [SECONDS] of what the phone plays, through [sound], and the words sung in it with the
 * speech model [model] gives, through [hearing]. The sound stays on the phone.
 */
internal class SingingListener(
    private val sound: Microphone,
    private val model: suspend () -> File?,
    private val hearing: (File) -> WordHearing?,
    /** Reading the sound blocks. */
    private val recordDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Hearing the words takes a few seconds of CPU. */
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    /** The speech model fetched now, whatever the network; null when it couldn't be. */
    private val fetch: suspend () -> File? = { null }
) : SingingHearing {
    @Volatile
    override var missesModel = false
        private set

    override suspend fun fetchModel(): Boolean = fetch() != null

    override suspend fun hear(language: String?, first: (suspend (Sung) -> Unit)?): Sung? {
        // A phone that won't share its sound has nothing to hear, so the model isn't fetched for it.
        val recording = withContext(recordDispatcher) { sound.open() } ?: return null
        val (file, heard) = recording.use {
            // The model first, so what's heard is fresh when it's listened to.
            val file = model()
            missesModel = file == null
            if (file == null) return null
            file to (
                coroutineScope {
                    withContext(recordDispatcher) {
                        record(recording) { startedAt, part ->
                            // Told back where it was asked, while the rest is still heard.
                            if (first != null) this@coroutineScope.launch { words(file, part, language)?.let { first(Sung(startedAt, it)) } }
                        }
                    }
                } ?: return null
                )
        }
        val words = words(file, heard.second, language) ?: return null
        return Sung(heard.first, words)
    }

    /** The words sung in [audio], heard with [model], in [language] when it's known; null when it can't be loaded, or hears none. */
    private suspend fun words(model: File, audio: ShortArray, language: String?): List<HeardWord>? = withContext(computeDispatcher) {
        val hears = hearing(model) ?: return@withContext null
        try {
            hears.words(FloatArray(audio.size) { audio[it] / 32768f }, language).ifEmpty { null }
        } finally {
            (hears as? Closeable)?.close()
        }
    }

    /**
     * When the sound started, and all of it; null when there's no sound to hear, or not all of it.
     * Its first [FIRST_SECONDS] are given to [heardFirst] as soon as they're in.
     */
    private suspend fun record(recording: Recording, heardFirst: (Long, ShortArray) -> Unit): Pair<Long, ShortArray>? {
        val audio = ShortArray(SAMPLES)
        val startedAt = clock()
        var recorded = 0
        while (recorded < audio.size) {
            val read = runInterruptible { recording.read(audio, recorded, minOf(CHUNK, audio.size - recorded)) }
            if (read <= 0) return null
            if (recorded < FIRST_SAMPLES && recorded + read >= FIRST_SAMPLES) heardFirst(startedAt, audio.copyOf(FIRST_SAMPLES))
            recorded += read
        }
        return startedAt to audio
    }

    companion object {
        /** Enough for a few lines, and short enough for a phone to hear in a few seconds. */
        const val SECONDS = 20
        /** Enough to tell whether the voice has started, so a song's words timed into its intro are known to be soon. */
        const val FIRST_SECONDS = 8
        private const val SAMPLES = ShazamSignature.SAMPLE_RATE * SECONDS
        private const val FIRST_SAMPLES = ShazamSignature.SAMPLE_RATE * FIRST_SECONDS
        private const val CHUNK = ShazamSignature.SAMPLE_RATE / 10
    }
}

/** The words whisper.cpp heard, as [NativeWhisper] gives them: a line each, its start in milliseconds, a tab, the word. */
internal object HeardWords {
    fun parse(bytes: ByteArray): List<HeardWord> =
        String(bytes, Charsets.UTF_8).lineSequence().mapNotNull { line ->
            val at = line.substringBefore('\t').toLongOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
            HeardWord(line.substringAfter('\t').trim(), at).takeIf { it.text.isNotEmpty() }
        }.toList()
}

/**
 * whisper.cpp's tiny model, which hears words on the phone, fetched the first time it's needed and
 * kept in [dir]. Only while [mayFetch], e.g. on Wi-Fi, as it's about 32 MB.
 */
internal class WhisperModel(
    private val client: OkHttpClient,
    private val dir: File,
    private val mayFetch: () -> Boolean,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val url: String = URL,
    private val sha256: String = SHA256
) {
    private val file get() = File(dir, NAME)

    /** One fetch at a time, as both write the same part. */
    private val fetching = Mutex()

    /** The model, fetched now if it may be; null while it isn't here and can't be. */
    suspend fun file(): File? = get(anyway = false)

    /** The model, fetched now whatever the network, when asked for by hand; null when it couldn't be. */
    suspend fun fetchAnyway(): File? = get(anyway = true)

    private suspend fun get(anyway: Boolean): File? = withContext(ioDispatcher) {
        fetching.withLock { file.takeIf { it.isFile } ?: if (anyway || mayFetch()) fetch() else null }
    }

    private fun fetch(): File? = file.takeIf { client.fetchChecked(url, sha256, it) }

    companion object {
        private const val NAME = "ggml-tiny-q5_1.bin"
        const val URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin"
        const val SHA256 = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"
    }
}

/**
 * How far the words heard sung are from the timed words: matched in order, word by word, each
 * pair says how far off the words are, and where most agree is how far they are.
 */
internal object SungAlignment {
    /**
     * How much later the timed [lines] are sung than they're timed, earlier when it's less than
     * none; null when too few words heard match them to tell. [songAt] is where the song is at a
     * moment of [sung], by the clock the words follow.
     */
    fun shiftMs(sung: Sung, songAt: (Long) -> Long, lines: List<LyricLine>): Long? {
        val heard = sung.words.mapNotNull { word -> plain(word.text).takeIf { it.isNotEmpty() }?.let { it to songAt(sung.startedAtMs + word.atMs) } }
        if (heard.isEmpty()) return null
        return near(heard, lines)?.first ?: far(heard, lines)
    }

    /**
     * Where each of [lines] heard sung starts, by index, once they're [shiftMs] off as a whole. A line
     * timed word by word is placed by most of its words heard. One timed a line at a time is placed by
     * its first two words heard one after the other, or by the words heard just before a pair, as many
     * as come before it in the line, a few at most, and matching none: the model often mishears a
     * line's first word. One heard only a little off, as near as a word is heard, or heard but not
     * told where it starts, stays [shiftMs] off.
     */
    fun linesMs(sung: Sung, songAt: (Long) -> Long, lines: List<LyricLine>, shiftMs: Long): Map<Int, Long> {
        val heard = sung.words.mapNotNull { word -> plain(word.text).takeIf { it.isNotEmpty() }?.let { it to songAt(sung.startedAtMs + word.atMs) - HEARD_LATE_MS } }
        if (heard.isEmpty()) return emptyMap()
        val from = heard.first().second - LINE_OFF_MS
        val to = heard.last().second + LINE_OFF_MS
        val near = lines.indices.filter { lines[it].timeMs + shiftMs in from..to }
        val timed = near.flatMapIndexed { at, line -> wordsOf(lines[line], near.getOrNull(at + 1)?.let { lines[it].timeMs }, line) }
        val pairs = matched(heard.map { it.first }, timed.map { it.text })
        val matchedHeard = pairs.map { it.first }.toSet()
        return pairs.groupBy { (_, t) -> timed[t].line }.mapNotNull { (line, its) ->
            val startMs = if (lines[line].words.isNotEmpty()) {
                val offs = its.mapNotNull { (h, t) -> timed[t].ms?.let { heard[h].second - it } }
                offs.takeIf { it.size >= 2 }?.sorted()?.let { it[it.size / 2] + lines[line].timeMs }
            } else {
                val (h, t) = its.first()
                val before = t - timed.indexOfFirst { it.line == line }
                // A pair: the next word of the line heard right after.
                val pair = its.getOrNull(1) == (h + 1 to t + 1) && heard[h + 1].second - heard[h].second < PAIR_MS
                heard.getOrNull(h - before)?.second?.takeIf { pair && before <= MAX_MISHEARD && (h - before until h).none { it in matchedHeard } }
            } ?: return@mapNotNull (line to lines[line].timeMs + shiftMs).takeIf { its.size >= 2 }
            val off = startMs - lines[line].timeMs - shiftMs
            if (abs(off) > LINE_OFF_MS) null else line to lines[line].timeMs + shiftMs + if (abs(off) >= OWN_LINE_MS) off else 0L
        }.toMap()
    }

    /** How far off the lines timed near what was [heard] are, and how many words heard match them; null when too few. */
    private fun near(heard: List<Pair<String, Long>>, lines: List<LyricLine>): Pair<Long, Int>? {
        // Only the lines near what was heard, so a chorus sung again elsewhere isn't matched to it.
        val from = heard.first().second - MAX_SHIFT_MS
        val to = heard.last().second + MAX_SHIFT_MS
        val near = lines.filter { it.timeMs in from..to }
        val timed = near.indices.flatMap { line -> wordsOf(near[line], near.getOrNull(line + 1)?.timeMs, line) }
        val pairs = matched(heard.map { it.first }, timed.map { it.text })
        // Lines timed a line at a time have a time for their first words only, so a few lines are enough.
        val told = agreed(pairs.mapNotNull { (h, t) -> timed[t].ms?.let { (heard[h].second - it) to timed[t].line } }, AGREE_MS) {
            it.size >= MIN_WORDS || it.distinctBy { (_, line) -> line }.size >= MIN_LINES
        }
            // Or else when the rest of a line's words are sung by where they are in it: a guess, so it takes more of them.
            ?: agreed(pairs.map { (h, t) -> (heard[h].second - timed[t].guessMs) to timed[t].line }, GUESSED_AGREE_MS) {
                it.size >= MIN_GUESSED && it.distinctBy { (_, line) -> line }.size >= MIN_LINES
            }
            ?: return null
        val shift = told - HEARD_LATE_MS
        return (shift to pairs.size).takeIf { abs(shift) <= MAX_SHIFT_MS }
    }

    /**
     * Lines timed much further off than [near] looks, e.g. for a release with a longer intro, as
     * YouTube Music's own words for one of its releases can be: each place in the song the words
     * heard could be is tried, and the one where by far the most of them agree, over several lines,
     * is where they are. A chorus heard that's sung as much elsewhere tells nothing.
     */
    private fun far(heard: List<Pair<String, Long>>, lines: List<LyricLine>): Long? {
        val timed = lines.indices.flatMap { line -> wordsOf(lines[line], lines.getOrNull(line + 1)?.timeMs, line) }
        val tries = heard.flatMap { (text, at) -> timed.filter { it.text == text }.map { at - it.guessMs } }.map { it / FAR_STEP_MS * FAR_STEP_MS }.distinct()
        val found = tries.mapNotNull { offset ->
            near(heard, lines.map { it.copy(timeMs = it.timeMs + offset, words = it.words.map { word -> word.copy(timeMs = word.timeMs + offset) }, endMs = it.endMs?.plus(offset)) })
                ?.let { (shift, matching) -> offset + shift to matching }
        }.sortedByDescending { it.second }
        val best = found.firstOrNull()?.takeIf { it.second >= MIN_FAR_WORDS } ?: return null
        val other = found.firstOrNull { abs(it.first - best.first) > AGREE_MS }
        return best.first.takeIf { other == null || other.second * FAR_CLEARLY < best.second }
    }

    /** Where most of [shifts], each with its line, agree within [within] ms; null when they're too few to tell, or split. */
    private fun agreed(shifts: List<Pair<Long, Int>>, within: Long, enough: (List<Pair<Long, Int>>) -> Boolean): Long? {
        if (shifts.isEmpty()) return null
        val agreed = shifts.map { (shift) -> shifts.filter { abs(it.first - shift) <= within } }.maxBy { it.size }
        if (!enough(agreed) || agreed.size * 2 < shifts.size) return null
        return agreed.map { it.first }.sorted()[agreed.size / 2]
    }

    /** A word of the [line]th line, when it's sung if the lyrics say, and about when by where it is in the line. */
    private class Word(val text: String, val ms: Long?, val guessMs: Long, val line: Int)

    /**
     * A line's words, each with when it's sung: every one timed word by word, or else its first, as
     * the line starts with it. Its first that's sung, so a line opening with "♪" or "-" keeps its time.
     * The rest are guessed by their letters, the line sung over most of the time until [nextMs].
     */
    private fun wordsOf(line: LyricLine, nextMs: Long?, index: Int): List<Word> {
        val words = line.words.ifEmpty { listOf(TimedWord(line.timeMs, line.text)) }.flatMap { word ->
            word.text.split(' ').map(::plain).filter { it.isNotEmpty() }.mapIndexed { at, text -> text to word.timeMs.takeIf { at == 0 } }
        }
        val letters = words.sumOf { it.first.length }
        val sungFor = (nextMs ?: (line.timeMs + LAST_LINE_MS)) - line.timeMs
        var before = 0
        return words.map { (text, ms) ->
            Word(text, ms, ms ?: (line.timeMs + sungFor * before * 4 / 5 / letters), index).also { before += text.length }
        }
    }

    /** A word as it sounds, near enough: lower case, without accents or anything but letters and digits. */
    private fun plain(word: String): String =
        Normalizer.normalize(word.lowercase(), Normalizer.Form.NFKD).filter { it.isLetterOrDigit() }

    /** Which of [heard] match which of [timed], in order, as many as can: index pairs. */
    private fun matched(heard: List<String>, timed: List<String>): List<Pair<Int, Int>> {
        val most = Array(heard.size + 1) { IntArray(timed.size + 1) }
        for (h in heard.indices.reversed()) for (t in timed.indices.reversed()) {
            most[h][t] = if (heard[h] == timed[t]) most[h + 1][t + 1] + 1 else maxOf(most[h + 1][t], most[h][t + 1])
        }
        val pairs = mutableListOf<Pair<Int, Int>>()
        var h = 0
        var t = 0
        while (h < heard.size && t < timed.size) {
            when {
                heard[h] == timed[t] -> pairs += (h++ to t++)
                most[h + 1][t] >= most[h][t + 1] -> h++
                else -> t++
            }
        }
        return pairs
    }

    /** Words heard that must match, and agree, to tell; or as few as the lines they're in, when in this many. */
    private const val MIN_WORDS = 5
    private const val MIN_LINES = 3

    /** How far apart words may say the words are and still agree: about how far off one is heard. */
    private const val AGREE_MS = 400L

    /** Guessed words that must agree to tell, and how far apart they may be: more, as a guess is further off. */
    private const val MIN_GUESSED = 8
    private const val GUESSED_AGREE_MS = 500L

    /** About how long a last line is sung, with no next one to say. */
    private const val LAST_LINE_MS = 3_000L

    /**
     * The model hears a word about this long after it starts: measured against where each word of a
     * song is really sung, both on a phone and from the recording itself.
     */
    const val HEARD_LATE_MS = 400L

    /** Words timed further off than this are only found where by far the most words heard agree. */
    const val MAX_SHIFT_MS = 10_000L

    /** Words heard that must match to place lines timed further off, and by how many times more than anywhere else. */
    private const val MIN_FAR_WORDS = 8
    private const val FAR_CLEARLY = 2

    /** A line heard further than this from where the rest are is another line; one heard less than [OWN_LINE_MS] off isn't off by itself. */
    private const val LINE_OFF_MS = 1_000L
    private const val OWN_LINE_MS = 150L

    /** How many of a line's first words may be misheard, and it still be told where it starts by the words heard in their place. */
    private const val MAX_MISHEARD = 2

    /** Two words heard further apart than this aren't sung one after the other. */
    private const val PAIR_MS = 1_000L

    /** How close together the places tried for far-off lines are: as close as words heard can tell apart. */
    private const val FAR_STEP_MS = 1_000L
}

/** The songs whose words were heard sung and lined up, per recording and words, so each is heard once. */
internal class SungStore(private val preferences: SharedPreferences) {
    fun heard(song: MusicMetadata, lines: List<LyricLine>): Boolean = keyOf(song, lines) in all()

    fun save(song: MusicMetadata, lines: List<LyricLine>) {
        // The oldest go first once there are many.
        val all = (all() - keyOf(song, lines)).toList().takeLast(MAX_SONGS - 1) + keyOf(song, lines)
        preferences.edit { putString(KEY, all.joinToString("\n")) }
    }

    private fun all(): Set<String> = preferences.getString(KEY, null)?.split('\n')?.toCollection(LinkedHashSet()) ?: emptySet()

    /** Other words for the song are timed their own way, so they're heard again. */
    private fun keyOf(song: MusicMetadata, lines: List<LyricLine>) =
        (RecordingKey.of(song) + "\u0000" + lines.joinToString("\n") { "${it.timeMs} ${it.text}" }.hashCode().toString(16)).replace('\n', ' ')

    private companion object {
        const val KEY = "sung_heard"
        const val MAX_SONGS = 300
    }
}
