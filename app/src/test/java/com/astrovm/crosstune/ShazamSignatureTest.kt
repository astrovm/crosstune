package com.astrovm.crosstune

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** The fingerprint Shazam reads: its peaks, and the bytes they're sent in. */
class ShazamSignatureTest {

    /** A thousand times less power, on the magnitude's log scale. */
    private val quieter = (1477.3 * kotlin.math.ln(1000.0)).toInt()

    private fun ByteArray.int(offset: Int) = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).getInt(offset)

    private fun tone(hz: Double, seconds: Double, amplitude: Double = 10_000.0) =
        ShortArray((ShazamSignature.SAMPLE_RATE * seconds).toInt()) { (amplitude * sin(2 * PI * hz * it / ShazamSignature.SAMPLE_RATE)).toInt().toShort() }

    /** A tenth of a second of a tone every half second, the way notes come and go, fading in and out without a click. */
    private fun beeps(hz: Double, seconds: Double, amplitude: Double = 10_000.0) = ShortArray((ShazamSignature.SAMPLE_RATE * seconds).toInt()) {
        val inBeep = it % 8000
        val fade = if (inBeep < 1600) sin(PI * inBeep / 1600).let { s -> s * s } else 0.0
        (amplitude * fade * sin(2 * PI * hz * it / ShazamSignature.SAMPLE_RATE)).toInt().toShort()
    }

    private fun hz(peak: SignaturePeak) = peak.bin * 16_000.0 / 2 / 1024 / 64

    @Test
    fun theHeaderSaysWhatFollowsAndItsChecksumCoversIt() {
        val bytes = ShazamSignature.write(mapOf(1 to listOf(SignaturePeak(0, 0x1234, 0x5678))), sampleCount = 16_000)
        // 48 for the header, 8 for the peaks' start, 8 for the band's, and 5 bytes padded to 8.
        assertEquals(72, bytes.size)
        assertEquals(0xCAFE2580.toInt(), bytes.int(0))
        assertEquals(CRC32().apply { update(bytes, 8, bytes.size - 8) }.value.toInt(), bytes.int(4))
        assertEquals(72 - 48, bytes.int(8))
        assertEquals(0x94119C00.toInt(), bytes.int(12))
        (16 until 28 step 4).forEach { assertEquals(0, bytes.int(it)) }
        // 16 kHz.
        assertEquals(0x18000000, bytes.int(28))
        assertEquals(0, bytes.int(32))
        assertEquals(0, bytes.int(36))
        // The samples, and a quarter of a second more.
        assertEquals(16_000 + 3840, bytes.int(40))
        assertEquals(0x007C0000, bytes.int(44))
        assertEquals(0x40000000, bytes.int(48))
        assertEquals(72 - 48, bytes.int(52))
        assertEquals(0x60030041, bytes.int(56))
        assertEquals(5, bytes.int(60))
        assertArrayEquals(byteArrayOf(0, 0x34, 0x12, 0x78, 0x56, 0, 0, 0), bytes.copyOfRange(64, 72))
    }

    @Test
    fun aChangedByteNoLongerMatchesTheChecksum() {
        val bytes = ShazamSignature.write(mapOf(0 to listOf(SignaturePeak(3, 1, 2))), 100)
        val checksum = bytes.int(4)
        bytes[bytes.size - 4] = 9
        assertTrue(CRC32().apply { update(bytes, 8, bytes.size - 8) }.value.toInt() != checksum)
    }

    @Test
    fun bandsGoInOrderEachCountingTimeFromZero() {
        val bytes = ShazamSignature.write(
            mapOf(3 to listOf(SignaturePeak(7, 1, 2)), 0 to listOf(SignaturePeak(2, 3, 4), SignaturePeak(5, 6, 8))),
            sampleCount = 0
        )
        // Band 0: two peaks, 10 bytes padded to 12; band 3: one, 5 padded to 8.
        assertEquals(0x60030040, bytes.int(56))
        assertEquals(10, bytes.int(60))
        assertArrayEquals(byteArrayOf(2, 3, 0, 4, 0, 3, 6, 0, 8, 0, 0, 0), bytes.copyOfRange(64, 76))
        assertEquals(0x60030043, bytes.int(76))
        assertEquals(5, bytes.int(80))
        assertArrayEquals(byteArrayOf(7, 1, 0, 2, 0, 0, 0, 0), bytes.copyOfRange(84, 92))
        assertEquals(92, bytes.size)
    }

    @Test
    fun aLongGapIsMarkedWithTheFullTime() {
        val bytes = ShazamSignature.write(
            mapOf(2 to listOf(SignaturePeak(10, 1, 1), SignaturePeak(265, 2, 2), SignaturePeak(266, 3, 3), SignaturePeak(600, 4, 4))),
            sampleCount = 0
        )
        val data = bytes.copyOfRange(64, 64 + bytes.int(60))
        assertArrayEquals(
            byteArrayOf(
                10, 1, 0, 1, 0,
                // 255 after the last: 0xFF, then the time itself.
                -1, 9, 1, 0, 0, 0, 2, 0, 2, 0,
                1, 3, 0, 3, 0,
                -1, 88, 2, 0, 0, 0, 4, 0, 4, 0
            ),
            data
        )
        // 30 bytes, padded to 32.
        assertEquals(64 + 32, bytes.size)
    }

    /** The loudest peak, and its band. */
    private fun loudest(bands: Map<Int, List<SignaturePeak>>) =
        bands.flatMap { (band, peaks) -> peaks.map { band to it } }.maxBy { it.second.magnitude }

    @Test
    fun aToneMakesPeaksInItsBandAtItsPitch() {
        val bands = ShazamSignature.peaks(beeps(1000.0, 3.0))
        val peaks = bands.getValue(1)
        val (band, peak) = loudest(bands)
        assertEquals(1, band)
        assertTrue("${hz(peak)} Hz", abs(hz(peak) - 1000) < 10)
        // One for each beep, and anything else is far quieter, such as rounding to whole samples.
        val beeps = peaks.filter { it.magnitude > peak.magnitude - quieter }
        assertEquals(6, beeps.size)
        beeps.forEach { assertTrue("${hz(it)} Hz", abs(hz(it) - 1000) < 10) }
        // In time order, from the first block checked, half a second apart.
        bands.values.forEach { assertEquals(it.sortedBy(SignaturePeak::pass), it) }
        assertTrue(peaks.first().pass >= 0)
        assertEquals(listOf(62, 63), beeps.zipWithNext { a, b -> b.pass - a.pass }.distinct().sorted())
        // Louder sound, larger magnitude.
        assertTrue(loudest(ShazamSignature.peaks(beeps(1000.0, 3.0, amplitude = 30_000.0))).second.magnitude > peak.magnitude)
        // A peak has to stand out in time too, so a tone that never changes has none.
        assertEquals(null, ShazamSignature.peaks(tone(1000.0, 3.0))[1])
    }

    @Test
    fun eachToneLandsInItsOwnBand() {
        assertEquals(0, loudest(ShazamSignature.peaks(beeps(440.0, 3.0))).first)
        assertEquals(2, loudest(ShazamSignature.peaks(beeps(2000.0, 3.0))).first)
        assertEquals(3, loudest(ShazamSignature.peaks(beeps(4000.0, 3.0))).first)
        // Too low, or too high, for any band: only the faint rest is left.
        val reference = loudest(ShazamSignature.peaks(beeps(1000.0, 3.0))).second.magnitude
        listOf(100.0, 7000.0).forEach { hz ->
            ShazamSignature.peaks(beeps(hz, 3.0)).values.flatten().forEach { assertTrue("$hz Hz", it.magnitude < reference - quieter) }
        }
    }

    @Test
    fun silenceHasNoPeaks() {
        assertEquals(emptyMap<Int, List<SignaturePeak>>(), ShazamSignature.peaks(ShortArray(48_000)))
        // Nor does anything shorter than the delay before the first check.
        assertEquals(emptyMap<Int, List<SignaturePeak>>(), ShazamSignature.peaks(beeps(1000.0, 0.3)))
        // Just the header and the peaks' start.
        assertEquals(56, ShazamSignature.create(ShortArray(0)).size)
    }

    @Test
    fun shortRecordingsArePaddedWithSilenceToTwelveSeconds() {
        val three = beeps(1000.0, 3.0)
        val signature = ShazamSignature.create(three)
        assertEquals(ShazamSignature.SAMPLES + 3840, signature.int(40))
        assertArrayEquals(signature, ShazamSignature.create(three.copyOf(ShazamSignature.SAMPLES)))
        // Only the first [count] samples count, whatever comes after them.
        val withNoise = three.copyOf(ShazamSignature.SAMPLES).also { beeps(3000.0, 9.0).copyInto(it, three.size) }
        assertArrayEquals(signature, ShazamSignature.create(withNoise, three.size))
        // Longer ones are cut.
        assertArrayEquals(ShazamSignature.create(beeps(1000.0, 12.0)), ShazamSignature.create(beeps(1000.0, 15.0)))
    }

    @Test
    fun theUriCarriesTheBytesInBase64() {
        val samples = beeps(1000.0, 3.0)
        val uri = ShazamSignature.uri(samples)
        assertTrue(uri.startsWith("data:audio/vnd.shazam.sig;base64,"))
        assertArrayEquals(ShazamSignature.create(samples), Base64.getDecoder().decode(uri.substringAfter(",")))
    }
}
