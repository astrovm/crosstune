package com.astrovm.crosstune

import kotlin.math.abs

/**
 * What an app on the phone plays, heard through [clean], the 16-bit sound it plays, when it can be:
 * the visuals' 8-bit tap, [played], loses quiet words under loud music, mostly in live recordings.
 * When [clean] can't open, or hears only silence, [played] hears instead: Spotify won't let other
 * apps hear it this way, and at volume 0 Android silences it.
 */
internal class CleanFirst(private val clean: Microphone?, private val played: Microphone) : Microphone {
    override fun open(): Recording? {
        if (clean != null && hearsSound(clean)) clean.open()?.let { return it }
        return played.open()
    }

    /** Whether [clean] hears anything within [PROBE_SAMPLES]; once it does, it's opened again from now. */
    private fun hearsSound(clean: Microphone): Boolean = clean.open()?.use { probe ->
        val samples = ShortArray(PROBE_SAMPLES)
        var heard = 0
        while (heard < samples.size) {
            // A little at a time, so sound heard at once doesn't wait out the rest.
            val read = probe.read(samples, heard, minOf(PROBE_STEP, samples.size - heard))
            if (read <= 0) return@use false
            if ((heard until heard + read).any { abs(samples[it].toInt()) > QUIET }) return@use true
            heard += read
        }
        false
    } == true

    companion object {
        /** Half a second: silence that long, while a song plays, is an app that won't be heard. */
        const val PROBE_SAMPLES = ShazamSignature.SAMPLE_RATE / 2

        /** A tenth of a second. */
        private const val PROBE_STEP = ShazamSignature.SAMPLE_RATE / 10

        /** Off by this much from silence is still silence. */
        private const val QUIET = 2
    }
}
