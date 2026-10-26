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
import okhttp3.Request
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.MessageDigest
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
internal fun interface SingingHearing {
    suspend fun hear(language: String?): Sung?

    /** Whether the last try heard nothing for want of the speech model, which wasn't fetched then, e.g. on mobile data. */
    val missesModel: Boolean get() = false

    /** Fetches the speech model now, on any network; whether it's here after. */
    suspend fun fetchModel(): Boolean = false
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

    private fun fetch(): File? {
        dir.mkdirs()
        val part = File(dir, "$NAME.part")
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val digest = MessageDigest.getInstance("SHA-256")
                part.outputStream().use { out ->
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                            out.write(buffer, 0, read)
                        }
                    }
                }
                // Only the very model it was tried with is kept: one cut short or changed is no use.
                if (digest.digest().joinToString("") { "%02x".format(it) } != sha256 || !part.renameTo(file)) return null
            }
            return file
        } catch (_: IOException) {
            return null
        } finally {
            part.delete()
        }
    }

    companion object {
        private const val NAME = "ggml-tiny-q5_1.bin"
        const val URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin"
        const val SHA256 = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"
        private const val BUFFER = 64 * 1024
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
        val timed = lines.filter { it.timeMs in from..to }.flatMap(::wordsOf)
        val shifts = matched(heard.map { it.first }, timed.map { it.first }).mapNotNull { (h, t) -> timed[t].second?.let { heard[h].second - it } }
        if (shifts.size < MIN_WORDS) return null
        val agreed = shifts.map { shift -> shifts.filter { abs(it - shift) <= AGREE_MS } }.maxBy { it.size }
        if (agreed.size < MIN_WORDS || agreed.size * 2 < shifts.size) return null
        val shift = agreed.sorted()[agreed.size / 2] - HEARD_LATE_MS
        return shift.takeIf { abs(it) <= MAX_SHIFT_MS }
    }

    /**
     * A line's words, each with when it's sung: every one timed word by word, or else its first, as
     * the line starts with it. Its first that's sung, so a line opening with "♪" or "-" keeps its time.
     */
    private fun wordsOf(line: LyricLine): List<Pair<String, Long?>> =
        line.words.ifEmpty { listOf(TimedWord(line.timeMs, line.text)) }.flatMap { word ->
            word.text.split(' ').map(::plain).filter { it.isNotEmpty() }.mapIndexed { index, text -> text to word.timeMs.takeIf { index == 0 } }
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

    /** Words heard that must match, and agree, to tell. */
    private const val MIN_WORDS = 5

    /** How far apart words may say the words are and still agree: about how far off one is heard. */
    private const val AGREE_MS = 400L

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
