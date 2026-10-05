package com.astrovm.crosstune

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/** One spectral peak: when, in blocks after the first checked one, how loud, and where, in 1/64 FFT bins. */
internal data class SignaturePeak(val pass: Int, val magnitude: Int, val bin: Int)

/**
 * Shazam's audio fingerprint: the loudest points of the sound's spectrum, by frequency band. Only
 * this leaves the phone, never the audio. Pure Kotlin, no Android APIs.
 */
internal object ShazamSignature {
    const val SAMPLE_RATE = 16_000
    /** What's sent: shorter recordings are padded with silence. */
    const val SAMPLES = SAMPLE_RATE * 12
    private const val BLOCK = 128
    private const val FFT_SIZE = 2048
    private const val BINS = FFT_SIZE / 2 + 1
    private const val RING = 256
    /** Peaks are picked this many blocks behind, once the spread spectra around them are known. */
    private const val DELAY = 46
    private val SPREAD_OFFSETS = intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)
    private val NEIGHBOURS = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
    private val BANDS = listOf(250..519, 520..1449, 1450..3499, 3500..5500)
    private const val MAGIC = 0xCAFE2580.toInt()
    private const val PREFIX = "data:audio/vnd.shazam.sig;base64,"

    private val window = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2 * PI * (it + 1) / (FFT_SIZE + 1)) }
    private val fft = Fft(FFT_SIZE)

    /** The first [count] of [samples], mono 16 kHz, as a data URI, padded or cut to [SAMPLES]. */
    fun uri(samples: ShortArray, count: Int = samples.size): String = PREFIX + Base64.getEncoder().encodeToString(create(samples, count))

    fun create(samples: ShortArray, count: Int = samples.size): ByteArray {
        val audio = samples.copyOf(SAMPLES).also { if (count < SAMPLES) it.fill(0, count, SAMPLES) }
        return write(peaks(audio), SAMPLES)
    }

    /** Each band's peaks, in time order; bands without any are left out. */
    fun peaks(samples: ShortArray): Map<Int, List<SignaturePeak>> {
        val bands = sortedMapOf<Int, MutableList<SignaturePeak>>()
        val recent = DoubleArray(FFT_SIZE)
        var recentStart = 0
        val spectra = Array(RING) { DoubleArray(BINS) }
        val spread = Array(RING) { DoubleArray(BINS) }
        var position = 0
        val frame = DoubleArray(FFT_SIZE)
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        for (block in 0 until samples.size / BLOCK) {
            for (i in 0 until BLOCK) recent[(recentStart + i) % FFT_SIZE] = samples[block * BLOCK + i].toDouble()
            recentStart = (recentStart + BLOCK) % FFT_SIZE
            for (n in 0 until FFT_SIZE) frame[n] = recent[(recentStart + n) % FFT_SIZE] * window[n]
            fft.transform(frame, re, im)
            val spectrum = spectra[position]
            for (i in 0 until BINS) spectrum[i] = max((re[i] * re[i] + im[i] * im[i]) / 131_072.0, 1e-10)

            val newest = spread[position]
            spectrum.copyInto(newest)
            for (i in 0 until BINS - 2) newest[i] = max(newest[i], max(newest[i + 1], newest[i + 2]))
            for (back in intArrayOf(1, 3, 6)) {
                val older = spread[(position - back + RING) % RING]
                for (i in 0 until BINS) if (older[i] < newest[i]) older[i] = newest[i]
            }
            position = (position + 1) % RING

            val processed = block + 1
            if (processed >= DELAY) pickPeaks(spectra, spread, position, processed - DELAY, bands)
        }
        return bands
    }

    private fun pickPeaks(
        spectra: Array<DoubleArray>,
        spread: Array<DoubleArray>,
        position: Int,
        pass: Int,
        bands: MutableMap<Int, MutableList<SignaturePeak>>
    ) {
        val a = spectra[(position - DELAY + RING) % RING]
        val b = spread[(position - DELAY - 3 + RING) % RING]
        for (k in 10..1014) {
            val value = a[k]
            if (value < 1.0 / 64 || value < b[k - 1]) continue
            var around = 0.0
            for (d in NEIGHBOURS) around = max(around, b[k + d])
            if (value <= around) continue
            for (offset in SPREAD_OFFSETS) around = max(around, spread[(position + offset + RING) % RING][k - 1])
            if (value <= around) continue

            val m = magnitude(value)
            val below = magnitude(a[k - 1])
            val above = magnitude(a[k + 1])
            val curve = 2 * m - below - above
            val shift = if (curve > 0) (above - below) * 32 / curve else 0.0
            val bin = (k * 64 + shift.toInt()) and 0xFFFF
            val hz = (bin * SAMPLE_RATE.toDouble() / 2 / 1024 / 64).toInt()
            val band = BANDS.indexOfFirst { hz in it }.takeIf { it >= 0 } ?: continue
            bands.getOrPut(band) { mutableListOf() }.add(SignaturePeak(pass, m.toInt() and 0xFFFF, bin))
        }
    }

    private fun magnitude(power: Double) = max(ln(power), 1.0 / 64) * 1477.3 + 6144

    /** The binary signature, see the format in the docs: a header, then each band's peaks. */
    fun write(bands: Map<Int, List<SignaturePeak>>, sampleCount: Int): ByteArray {
        val blocks = bands.toSortedMap().map { (band, peaks) -> band to encode(peaks) }
        val size = 56 + blocks.sumOf { (_, data) -> 8 + (data.size + 3) / 4 * 4 }
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MAGIC).putInt(0).putInt(size - 48).putInt(0x94119C00.toInt())
        buffer.putInt(0).putInt(0).putInt(0)
        buffer.putInt(3 shl 27)
        buffer.putInt(0).putInt(0)
        buffer.putInt(sampleCount + (SAMPLE_RATE * 0.24).toInt())
        buffer.putInt(0x007C0000)
        buffer.putInt(0x40000000).putInt(size - 48)
        for ((band, data) in blocks) {
            buffer.putInt(0x60030040 + band).putInt(data.size).put(data)
            buffer.position((buffer.position() + 3) / 4 * 4)
        }
        val bytes = buffer.array()
        val crc = CRC32().apply { update(bytes, 8, bytes.size - 8) }.value.toInt()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, crc)
        return bytes
    }

    private fun encode(peaks: List<SignaturePeak>): ByteArray {
        val buffer = ByteBuffer.allocate(peaks.size * 10).order(ByteOrder.LITTLE_ENDIAN)
        var last = 0
        for (peak in peaks) {
            if (peak.pass - last >= 255) {
                buffer.put(0xFF.toByte()).putInt(peak.pass)
                last = peak.pass
            }
            buffer.put((peak.pass - last).toByte()).putShort(peak.magnitude.toShort()).putShort(peak.bin.toShort())
            last = peak.pass
        }
        return buffer.array().copyOf(buffer.position())
    }
}

/** An in-place radix-2 FFT of real input, for one fixed power-of-two size. */
private class Fft(private val size: Int) {
    private val reversed = IntArray(size).also { table ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) table[i] = Integer.reverse(i) ushr (32 - bits)
    }
    private val cosines = DoubleArray(size / 2) { cos(2 * PI * it / size) }
    private val sines = DoubleArray(size / 2) { -sin(2 * PI * it / size) }

    fun transform(input: DoubleArray, re: DoubleArray, im: DoubleArray) {
        for (i in 0 until size) {
            re[reversed[i]] = input[i]
            im[i] = 0.0
        }
        var length = 2
        while (length <= size) {
            val half = length / 2
            val step = size / length
            for (start in 0 until size step length) {
                for (j in 0 until half) {
                    val wr = cosines[j * step]
                    val wi = sines[j * step]
                    val a = start + j
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
            }
            length *= 2
        }
    }
}
