package com.astrovm.crosstune

import android.content.SharedPreferences
import android.os.SystemClock
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
internal data class Sung(val startedAtMs: Long, val words: List<HeardWord>)

/** Hears the words of the song the phone plays now; null when it can't, or heard none. */
internal interface SingingHearing {
    suspend fun hear(language: String?): Sung?

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

    override suspend fun hear(language: String?): Sung? {
        // A phone that won't share its sound has nothing to hear, so the model isn't fetched for it.
        val recording = withContext(recordDispatcher) { sound.open() } ?: return null
        var model: File? = null
        val heard = recording.use {
            // The model first, so what's heard is fresh when it's listened to.
            model = this.model()
            missesModel = model == null
            if (model == null) return null
            withContext(recordDispatcher) { record(recording) }
        } ?: return null
        val words = withContext(computeDispatcher) {
            val hears = hearing(model ?: return@withContext null) ?: return@withContext null
            try {
                hears.words(FloatArray(heard.second.size) { heard.second[it] / 32768f }, language)
            } finally {
                (hears as? Closeable)?.close()
            }
        } ?: return null
        return Sung(heard.first, words).takeIf { words.isNotEmpty() }
    }

    /** When the sound started, and all of it; null when there's no sound to hear, or not all of it. */
    private suspend fun record(recording: Recording): Pair<Long, ShortArray>? {
        val audio = ShortArray(SAMPLES)
        val startedAt = clock()
        var recorded = 0
        while (recorded < audio.size) {
            val read = runInterruptible { recording.read(audio, recorded, minOf(CHUNK, audio.size - recorded)) }
            if (read <= 0) return null
            recorded += read
        }
        return startedAt to audio
    }

    companion object {
        /** Enough for a few lines, and short enough for a phone to hear in a few seconds. */
        const val SECONDS = 20
        private const val SAMPLES = ShazamSignature.SAMPLE_RATE * SECONDS
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
        return shift.takeIf { abs(it) <= MAX_SHIFT_MS }
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

    /** The model hears a word about this long after it starts, tried on songs heard both ways. */
    const val HEARD_LATE_MS = 600L

    /** Words timed further off than this are for another song, or another part of it. */
    const val MAX_SHIFT_MS = 10_000L
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
