package com.astrovm.crosstune

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import kotlin.math.min

/** What listening found. */
internal sealed interface Heard {
    /** [appleMusicId] is the song on Apple Music, when Shazam knows it. */
    /**
     * [offsetMs] is how far into the song the sound heard starts, and [startedAtMs] when that sound
     * started, on the clock that counts since the phone started, so the words can follow on from there.
     */
    data class Song(val metadata: MusicMetadata, val appleMusicId: String?, val offsetMs: Long? = null, val startedAtMs: Long? = null) : Heard {
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
            client.newCall(request).executeAsync().use { response ->
                // Too many requests, or the server failing: another app may still name it.
                if (!response.isSuccessful) return Heard.Failed(AppError.RECOGNITION_UNAVAILABLE)
                parse(withContext(ioDispatcher) { response.body.stringAtMost() })
            }
        } catch (_: IOException) {
            Heard.Failed(AppError.NETWORK)
        } catch (_: JSONException) {
            Heard.Failed(AppError.RECOGNITION_UNAVAILABLE)
        }
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
            .firstOrNull { it.optString("type") == "applemusicplay" }?.optString("id")?.takeIf { it.isNotBlank() }
        val metadata = MusicMetadata(
            title = title,
            artist = track.optString("subtitle"),
            artworkUrl = track.optJSONObject("images")?.optString("coverart")?.takeIf { it.isNotBlank() }
        )
        // How far into the song the sound sent starts, in seconds.
        val offset = JSONObject(body).optJSONArray("matches")?.optJSONObject(0)?.optDouble("offset")?.takeIf { it.isFinite() && it >= 0 }
        return Heard.Song(metadata, appleMusicId, offset?.let { (it * 1000).toLong() })
    }

    companion object {
        private val JSON = "application/json".toMediaType()
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

/** Something that hears what's playing nearby and names it. */
internal fun interface SongHearing {
    suspend fun listen(): Heard
}

/**
 * Names the song an app on the phone is playing straight from that app, which is instant and exact,
 * and only listens with [hearing] when none is: the microphone hears the phone's own speaker
 * poorly, and nothing at all through headphones.
 */
internal class PlayingFirst(private val playback: PlaybackSource, private val hearing: SongHearing) : SongHearing {
    override suspend fun listen(): Heard = playback.nowPlaying() ?: hearing.listen()
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
        while (heard == null) {
            // Asked again once there's another step's worth, or whatever's left when recording ends.
            val now = progress.first { it.done || it.recorded >= tried + STEP }
            heard = if (now.failed) {
                Heard.Failed(AppError.MICROPHONE)
            } else if (now.recorded == tried) {
                // A microphone that can't open, or gives no sound at all, isn't working.
                if (tried == 0) Heard.Failed(AppError.MICROPHONE) else Heard.Nothing
            } else {
                tried = now.recorded
                shazam.recognize(audio.copyOf(tried), tried).takeIf { it !is Heard.Nothing || tried == audio.size }
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
