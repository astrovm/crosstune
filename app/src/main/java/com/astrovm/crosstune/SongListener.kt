package com.astrovm.crosstune

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.min

/** What listening found. */
internal sealed interface Heard {
    /**
     * [appleMusicId] is the song on Apple Music, when Shazam knows it. [offsetMs] is how far into the
     * song the sound heard starts, and [startedAtMs] when that sound started, on the clock that counts
     * since the phone started, so the words can follow on from there. [matchId] is the recording Shazam
     * matched it to, whose place [offsetMs] is: often another release than the one playing. [app] is
     * the music app playing it on the phone, by package.
     */
    data class Song(val metadata: MusicMetadata, val appleMusicId: String?, val offsetMs: Long? = null, val startedAtMs: Long? = null, val matchId: String? = null, val app: String? = null) : Heard {
        /** Where the song is, as heard, when both halves are known. */
        val clock: PlaybackClock? get() = if (offsetMs != null && startedAtMs != null) PlaybackClock(offsetMs, startedAtMs) else null
    }
    data object Nothing : Heard
    data class Failed(val error: AppError) : Heard
}

/** Names a song from its fingerprint with Shazam. Only the fingerprint is sent, never the audio. */
internal class Shazam(
    private val client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Making a fingerprint takes a moment of CPU. */
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val newId: () -> UUID = UUID::randomUUID,
    private val now: () -> Long = System::currentTimeMillis
) {
    /** The first [count] of [samples], mono 16 kHz. */
    suspend fun recognize(samples: ShortArray, count: Int): Heard {
        val uri = withContext(computeDispatcher) { ShazamSignature.uri(samples, count) }
        val request = Request.Builder()
            .url(
                "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/${newId().toString().uppercase()}/${newId()}" +
                    "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3"
            )
            .header("Content-Language", "en_US")
            .header("User-Agent", USER_AGENT)
            .post(body(uri).toString().toRequestBody(JSON))
            .build()
        return try {
            client.newCall(request).answer(ioDispatcher) { response ->
                // Too many requests, or the server failing: another app may still name it.
                if (!response.isSuccessful) Heard.Failed(AppError.RECOGNITION_UNAVAILABLE) else parse(response.body.stringAtMost())
            }.let { heard -> if (heard is Heard.Song) withLength(heard) else heard }
        } catch (_: IOException) {
            Heard.Failed(AppError.NETWORK)
        } catch (_: JSONException) {
            Heard.Failed(AppError.RECOGNITION_UNAVAILABLE)
        }
    }

    /** How long the recordings Shazam named are, by their Apple Music ids, so each is asked about once. */
    private val lengths = ConcurrentHashMap<String, Long>()

    /**
     * [heard] with how long the recording Shazam matched lasts, as the iTunes Store has it, so its
     * words are timed for that one, where Shazam's place in it is, and not another edit of the song.
     */
    private suspend fun withLength(heard: Heard.Song): Heard.Song {
        val id = heard.appleMusicId ?: return heard
        val length = lengths[id] ?: lengthOf(id)?.also { lengths[id] = it } ?: return heard
        return heard.copy(metadata = heard.metadata.copy(lengthMs = length))
    }

    /** Not known, e.g. offline, the song is still named, as only its words need it. */
    private suspend fun lengthOf(id: String): Long? = try {
        val url = ITUNES_LOOKUP_URL.toHttpUrl().newBuilder().addQueryParameter("id", id).build()
        client.newCall(Request.Builder().url(url).build()).answer(ioDispatcher) { response ->
            if (!response.isSuccessful) return@answer null
            JSONObject(response.body.stringAtMost()).optJSONArray("results")?.optJSONObject(0)?.optLong("trackTimeMillis")?.takeIf { it > 0 }
        }
    } catch (_: IOException) {
        null
    } catch (_: JSONException) {
        null
    }

    private fun body(uri: String): JSONObject {
        val timestamp = now() and 0xFFFFFFFFL
        return JSONObject()
            .put("geolocation", JSONObject().put("altitude", 300).put("latitude", 45).put("longitude", 2))
            .put(
                "signature",
                JSONObject()
                    .put("samplems", ShazamSignature.SAMPLES * 1000L / ShazamSignature.SAMPLE_RATE)
                    .put("timestamp", timestamp)
                    .put("uri", uri)
            )
            .put("timestamp", timestamp)
            .put("timezone", "Europe/Paris")
    }

    private fun parse(body: String): Heard {
        // No match comes without a track.
        val track = JSONObject(body).optJSONObject("track") ?: return Heard.Nothing
        val title = track.optString("title").takeIf { it.isNotBlank() } ?: return Heard.Nothing
        val actions = track.optJSONObject("hub")?.optJSONArray("actions") ?: JSONArray()
        val appleMusicId = (0 until actions.length()).mapNotNull(actions::optJSONObject)
            .firstOrNull { it.optString("type") == "applemusicplay" }.text("id")
        val metadata = MusicMetadata(
            title = title,
            artist = track.optString("subtitle"),
            artworkUrl = track.optJSONObject("images").text("coverart")
        )
        val match = JSONObject(body).optJSONArray("matches")?.optJSONObject(0)
        // How far into the song the sound sent starts, in seconds.
        val offset = match?.optDouble("offset")?.takeIf { it.isFinite() && it >= 0 }
        return Heard.Song(metadata, appleMusicId, offset?.let { (it * 1000).toLong() }, matchId = match.text("id"))
    }

    /** What Shazam says under [key], when it says anything. */
    private fun JSONObject?.text(key: String): String? = if (this == null) null else optString(key).takeIf { it.isNotBlank() }

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val ITUNES_LOOKUP_URL = "https://itunes.apple.com/lookup"
        private const val USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8 Build/AP2A.240805.005)"
    }
}

/** Sound from the microphone, mono 16-bit at [ShazamSignature.SAMPLE_RATE]. */
internal fun interface Microphone {
    /** Starts recording, or null when it can't, e.g. without the permission. */
    fun open(): Recording?
}

internal interface Recording : Closeable {
    /** Blocks until there's some sound; 0 or less once there's no more. */
    fun read(buffer: ShortArray, offset: Int, count: Int): Int
}

/** The phone's own microphone. */
internal class AudioRecordMicrophone(private val create: () -> AudioRecord = ::micRecord) : Microphone {
    override fun open(): Recording? {
        val record = try {
            create()
        } catch (_: RuntimeException) {
            null
        }
        // Without the permission it's made, but can't record.
        if (record?.state != AudioRecord.STATE_INITIALIZED) return null.also { record?.release() }
        try {
            record.startRecording()
        } catch (_: RuntimeException) {
            record.release()
            return null
        }
        return object : Recording {
            override fun read(buffer: ShortArray, offset: Int, count: Int): Int {
                val read = record.read(buffer, offset, count)
                if (read < 0) throw IllegalStateException("Microphone read failed: $read")
                return read
            }
            override fun close() {
                try {
                    record.stop()
                } finally {
                    record.release()
                }
            }
        }
    }
}

// The caller asks for the permission first; without it the recorder isn't usable, see above.
@SuppressLint("MissingPermission")
private fun micRecord(): AudioRecord {
    val rate = ShazamSignature.SAMPLE_RATE
    val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    // At least a second, so nothing's lost while a chunk is copied.
    return AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, rate * 2))
}

/**
 * The sound the phone itself plays, through the visuals' [tap], for a song an app on the phone
 * plays: heard through headphones too, and nothing else in the room, unlike the microphone. Each
 * read is the last moment of sound, so reads close together overlap, and only what's new is kept.
 */
internal class PlayedSound(
    private val tap: () -> SoundTap?,
    /** Where it's kept that the phone shares its sound, so even the first listen after Crosstune starts knows. */
    private val preferences: SharedPreferences? = null,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val sleep: (Long) -> Unit = Thread::sleep
) : Microphone {
    override fun open(): Recording? {
        val sound = tap() ?: return null
        val step = sound.rateHz.toDouble() / ShazamSignature.SAMPLE_RATE
        val window = ByteArray(OutputMixTap.SAMPLES)
        var last: IntArray? = null
        var lastAt = now()
        // Once the phone has shared its sound, silence is just a quiet moment, like a video's start.
        var heardAny = preferences?.getBoolean(KEY_SHARES, false) == true
        var waited = 0L
        // What's new and not yet made 16 kHz, and how far into it the next sample made starts.
        val fresh = ArrayList<Int>()
        var from = 0.0
        val ready = ArrayDeque<Short>()

        fun take(samples: IntArray, start: Int) {
            for (index in start until samples.size) fresh += samples[index]
            while (from + step <= fresh.size) {
                ready += (fresh.subList(from.toInt(), (from + step).toInt()).average() * 256).toInt().toShort()
                from += step
            }
            val used = from.toInt()
            fresh.subList(0, used).clear()
            from -= used
        }

        return object : Recording {
            override fun read(buffer: ShortArray, offset: Int, count: Int): Int {
                // Silence is held back until there's sound: a phone that won't share its sound gives
                // only silence, and the microphone hears instead.
                while (ready.isEmpty() || !heardAny) {
                    if (!heardAny && waited >= SILENT_MS) return 0
                    val read = sound.read(window)
                    val readAt = now()
                    if (read > 0) {
                        val samples = IntArray(read) { (window[it].toInt() and 0xFF) - 128 }
                        if (!heardAny && samples.any { abs(it) > QUIET }) {
                            heardAny = true
                            preferences?.edit { putBoolean(KEY_SHARES, true) }
                        }
                        // The first read is what played before listening started.
                        last?.let { before ->
                            val expected = ((readAt - lastAt) * sound.rateHz / 1000).toInt()
                            val new = newIn(before, samples, expected)
                            // Reads too far apart to overlap missed some sound, kept as silence so what's heard stays in time.
                            if (new == read && expected > read) take(IntArray(expected - read), 0)
                            take(samples, read - new)
                        }
                        last = samples
                        lastAt = readAt
                    }
                    if (ready.isEmpty() || !heardAny) {
                        sleep(POLL_MS)
                        waited += POLL_MS
                    }
                }
                val count = min(count, ready.size)
                repeat(count) { buffer[offset + it] = ready.removeFirst() }
                return count
            }

            override fun close() = sound.close()
        }
    }

    /**
     * How many of [now]'s samples weren't in [before]: where its start matches the end of [before],
     * the match nearest [expected] by the clock, which a stretch of silence can only be taken at.
     */
    private fun newIn(before: IntArray, now: IntArray, expected: Int): Int {
        val size = now.size
        val guess = expected.coerceIn(0, size)
        if (before.size != size || now.all { abs(it) <= QUIET } || before.all { abs(it) <= QUIET }) return guess
        // All of it new always matches, so one is always found.
        return (0..size).asSequence().flatMap { sequenceOf(guess - it, guess + it) }
            .first { shift -> shift in 0..size && (0 until size - shift).all { before[shift + it] == now[it] } }
    }

    companion object {
        /** How long only silence means the phone won't share its sound. */
        const val SILENT_MS = 2_000L
        const val KEY_SHARES = "sound_shared"
        /** A little more often than a read lasts, so they overlap. */
        const val POLL_MS = 5L
        /** Off by this much from silence is still silence. */
        private const val QUIET = 1
    }
}

/** Something that hears what's playing nearby and names it. */
// Only Kotlin calls it, so no Java copy of listenToPlaying, which nothing would run.
@JvmDefaultWithoutCompatibility
internal fun interface SongHearing {
    suspend fun listen(): Heard

    /**
     * What an app on the phone plays, heard rather than as the app says, to tell how far the app is from
     * the words; by the microphone too, when the phone won't share its sound, unless not [byMicrophone].
     */
    suspend fun listenToPlaying(byMicrophone: Boolean = true): Heard = listen()
}

/**
 * Names the song an app on the phone is playing straight from that app, which is instant and exact,
 * and only listens with [hearing] when none is. An app that can't say where in the song it is, as
 * for a video, is still listened to for that, through [played], the phone's own sound, which the
 * microphone hears poorly, and not at all through headphones; with the microphone only when the
 * phone won't share its sound. Nothing heard, it's named as the app does.
 */
internal class PlayingFirst(private val playback: PlaybackSource, private val hearing: SongHearing, private val played: SongHearing = hearing) : SongHearing {
    override suspend fun listen(): Heard {
        val playing = playback.nowPlaying() ?: return hearing.listen()
        if (playing.clock != null) return playing
        return listenToPlaying() as? Heard.Song ?: playing
    }

    override suspend fun listenToPlaying(byMicrophone: Boolean): Heard =
        played.listen().let { if (byMicrophone && it == Heard.Failed(AppError.MICROPHONE)) hearing.listen() else it }
}

/**
 * Listens for up to [ShazamSignature.SAMPLES], asking Shazam every [STEP] samples with all there
 * is so far, and stops at the first answer that names the song.
 */
internal class SongListener(
    private val microphone: Microphone,
    private val shazam: Shazam,
    /** Reading the microphone blocks. */
    private val recordDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** When the recording starts, so a song's words can follow on from what was heard. */
    private val clock: () -> Long = SystemClock::elapsedRealtime
) : SongHearing {
    private data class Progress(val recorded: Int, val done: Boolean, val failed: Boolean = false)

    override suspend fun listen(): Heard = coroutineScope {
        val audio = ShortArray(ShazamSignature.SAMPLES)
        val progress = MutableStateFlow(Progress(0, done = false))
        val startedAt = clock()
        // It opens the microphone itself, so whatever it opens it closes, even when stopped meanwhile.
        val recorder = launch(recordDispatcher) {
            var recorded = 0
            try {
                microphone.open()?.use { recording ->
                    while (isActive && recorded < audio.size) {
                        // Stopping interrupts a read that's waiting for sound.
                        val read = runInterruptible { recording.read(audio, recorded, min(CHUNK, audio.size - recorded)) }
                        if (read <= 0) break
                        recorded += read
                        progress.value = Progress(recorded, done = false)
                    }
                }
            } catch (e: IllegalStateException) {
                // Being stopped is one too, and isn't the microphone failing.
                if (e is CancellationException) throw e
                progress.value = Progress(recorded, done = true, failed = true)
                return@launch
            }
            progress.value = Progress(recorded, done = true)
        }
        var tried = 0
        var heard: Heard? = null
        // The network failing for a moment is asked again with more sound, but not twice in a row.
        var offline: Heard.Failed? = null
        while (heard == null) {
            // Asked again once there's another step's worth, or whatever's left when recording ends.
            val now = progress.first { it.done || it.recorded >= tried + STEP }
            heard = if (now.failed) {
                Heard.Failed(AppError.MICROPHONE)
            } else if (now.recorded == tried) {
                // A microphone that can't open, or gives no sound at all, isn't working.
                if (tried == 0) Heard.Failed(AppError.MICROPHONE) else offline ?: Heard.Nothing
            } else {
                tried = now.recorded
                // Not named yet, it's asked again with more, and what the last ask says is the
                // answer; Shazam saying it's busy is believed at once.
                val answer = shazam.recognize(audio.copyOf(tried), tried)
                val failedAgain = answer is Heard.Failed && answer.error == AppError.NETWORK && offline != null
                offline = (answer as? Heard.Failed)?.takeIf { it.error == AppError.NETWORK }
                answer.takeIf { it is Heard.Song || (it is Heard.Failed && (it.error != AppError.NETWORK || failedAgain)) || tried == audio.size }
            }
        }
        recorder.cancelAndJoin()
        // What's sent starts where the recording did.
        if (heard is Heard.Song) heard.copy(startedAtMs = startedAt) else heard
    }

    companion object {
        /** About every 3 seconds. */
        const val STEP = ShazamSignature.SAMPLE_RATE * 3
        /** A tenth of a second at a time, so stopping doesn't wait. */
        private const val CHUNK = ShazamSignature.SAMPLE_RATE / 10
    }
}
