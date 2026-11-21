package com.astrovm.crosstune

import android.app.Instrumentation
import android.os.Bundle
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Whether two hearings at once both hear what a music app plays, as the singing and Shazam do (issue
 * #1100): one for [FIRST_MS], another opened [SECOND_AT_MS] into it, then one more alone after both.
 * It plays nothing and sends nothing: start a song in a music app first, and Shizuku or root must have
 * allowed the dev app's playback capture. The loudest sample of each second of each comes back.
 *
 * `scripts/device-check-build.sh --dirty CaptureCheck capturecheck` builds it, then
 * `adb shell am instrument -w -e microphone playback com.astrovm.crosstune.dev.test/com.astrovm.crosstune.CaptureCheck`
 * hears through [PlaybackCapture] alone; `-e microphone heard` through [MainActivity.playedHearing],
 * the way the singing and Shazam each hear. That way, as in the app, the visuals' tap may note in the
 * dev app's preferences that the phone shares its sound ([PlayedSound.KEY_SHARES]). `am instrument`
 * restarts the dev app's process, so an open Crosstune Dev starts over.
 */
class CaptureCheck : Instrumentation() {
    private var through = "playback"

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        through = arguments?.getString("microphone") ?: through
        start()
    }

    override fun onStart() {
        try {
            val microphone = when (through) {
                "playback" -> PlaybackCapture(targetContext)
                "heard" -> MainActivity.playedHearing(targetContext)
                else -> error("Unknown microphone $through")
            }
            // The long hearing's own thread hands back what it heard, or what went wrong, so either is reported.
            var long: Result<String>? = null
            val first = thread { long = runCatching { hear(microphone, FIRST_MS) } }
            Thread.sleep(SECOND_AT_MS)
            val during = runCatching { hear(microphone, SECOND_MS) }
            first.join()
            val after = hear(microphone, SECOND_MS)
            val heardFirst = checkNotNull(long).getOrThrow()
            finish(0, Bundle().apply {
                putString("first", heardFirst)
                putString("during", during.getOrThrow())
                putString("after", after)
            })
        } catch (error: Throwable) {
            finish(1, Bundle().apply { putString("error", error.toString()) })
        }
    }

    /** The loudest sample of each second heard, for [ms]; "none" when it wouldn't open. */
    private fun hear(microphone: Microphone, ms: Long): String {
        val opened = System.nanoTime()
        val recording = microphone.open() ?: return "none"
        val openMs = (System.nanoTime() - opened) / 1_000_000
        val samples = ShortArray(ShazamSignature.SAMPLE_RATE / 10)
        val loudest = IntArray(((ms + 999) / 1000).toInt())
        var heard = 0
        recording.use {
            while (heard < ms * ShazamSignature.SAMPLE_RATE / 1000) {
                val read = it.read(samples, 0, samples.size)
                if (read <= 0) break
                val second = minOf(heard / ShazamSignature.SAMPLE_RATE, loudest.size - 1)
                for (index in 0 until read) loudest[second] = maxOf(loudest[second], abs(samples[index].toInt()))
                heard += read
            }
        }
        return "opened in $openMs ms, $heard samples, loudest by second ${loudest.joinToString(",")}"
    }

    private companion object {
        const val FIRST_MS = 8_000L
        const val SECOND_AT_MS = 3_000L
        const val SECOND_MS = 3_000L
    }
}
