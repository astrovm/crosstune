package com.astrovm.crosstune.songcheck

import com.astrovm.crosstune.Heard
import com.astrovm.crosstune.Shazam
import com.astrovm.crosstune.ShazamSignature
import com.astrovm.crosstune.httpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What Shazam makes of a recording, every 10 s of it: the song it names, the recording it matched,
 * how long that one is, and where in it the sound is. Only run by hand, with DIR/probe.pcm mono
 * 16 kHz 16-bit (`ffmpeg -i song.wav -ac 1 -ar 16000 -f s16le DIR/probe.pcm`):
 * ./gradlew :app:songCheck -PsongcheckTest=com.astrovm.crosstune.songcheck.ShazamProbe -PsongcheckDir=DIR
 * One line per stretch heard in DIR/probe.jsonl, and Shazam's own matches, as it sent them, in
 * DIR/probe.raw.jsonl.
 */
@RunWith(RobolectricTestRunner::class)
class ShazamProbe {
    private val dir = File(System.getProperty("songcheck.dir") ?: "build/songcheck")

    @Test
    fun probe() = runBlocking {
        val file = File(dir, "probe.pcm")
        if (!file.exists()) return@runBlocking
        val bytes = file.readBytes()
        val samples = ShortArray(bytes.size / 2).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
        val raw = File(dir, "probe.raw.jsonl").apply { writeText("") }
        val client = httpClient().newBuilder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (chain.request().url.host == "amp.shazam.com") {
                JSONObject(response.peekBody(MAX_ANSWER_BYTES).string()).optJSONArray("matches")?.let { raw.appendText("$it\n") }
            }
            response
        }.build()
        val shazam = Shazam(client)
        val out = File(dir, "probe.jsonl").apply { writeText("") }
        var at = 0
        while (at + ShazamSignature.SAMPLES <= samples.size) {
            val atMs = at * 1000L / ShazamSignature.SAMPLE_RATE
            val line = when (val answer = shazam.recognize(samples.copyOfRange(at, at + ShazamSignature.SAMPLES), ShazamSignature.SAMPLES)) {
                is Heard.Song -> JSONObject().put("atMs", atMs).put("title", answer.metadata.title).put("artist", answer.metadata.artist)
                    .put("appleMusicId", answer.appleMusicId).put("matchId", answer.matchId).put("songMs", answer.offsetMs)
                    .put("lengthMs", answer.metadata.lengthMs).put("gapMs", answer.offsetMs?.let { it - atMs }).put("speed", answer.speed)
                else -> JSONObject().put("atMs", atMs).put("answer", answer.toString())
            }
            println(line)
            out.appendText("$line\n")
            at += STEP_SECONDS * ShazamSignature.SAMPLE_RATE
            // Shazam is free; asked with a breath between.
            delay(PAUSE_MS)
        }
    }

    private companion object {
        const val STEP_SECONDS = 10
        const val PAUSE_MS = 1_500L
        const val MAX_ANSWER_BYTES = 1_000_000L
    }
}
