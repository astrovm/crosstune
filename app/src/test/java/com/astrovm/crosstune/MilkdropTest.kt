package com.astrovm.crosstune

import android.app.Application
import android.media.audiofx.AudioEffect
import android.media.audiofx.Visualizer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowVisualizer
import kotlin.random.Random

/** MilkDrop visuals: the presets that come with the app, what they hear, and when each is drawn. */
@RunWith(RobolectricTestRunner::class)
class MilkdropTest {

    /** Records what it's told to do, as projectM would do it. */
    private class FakeMilkdrop(private val opens: Boolean = true) : Milkdrop {
        val calls = mutableListOf<String>()
        val heard = mutableListOf<List<Byte>>()

        override fun open(width: Int, height: Int) = opens.also { calls += "open" }
        override fun size(width: Int, height: Int) {
            calls += "size ${width}x$height"
        }
        override fun show(preset: String, smooth: Boolean) {
            calls += "show $preset${if (smooth) " smoothly" else ""}"
        }
        override fun hear(samples: ByteArray, count: Int) {
            heard += samples.take(count)
        }
        override fun draw() {
            calls += "draw"
        }
        override fun close() {
            calls += "close"
        }
    }

    /** Hears [sound] each time, until closed. */
    private class FakeTap(var sound: ByteArray = byteArrayOf()) : SoundTap {
        var closed = false

        override fun read(into: ByteArray): Int {
            sound.copyInto(into)
            return sound.size
        }

        override fun close() {
            closed = true
        }
    }

    private var clock = 1_000L
    private val sleeps = mutableListOf<Long>()

    private fun renderer(milkdrop: Milkdrop, tap: SoundTap? = FakeTap(), presets: List<String> = listOf("a", "b", "c")) =
        MilkdropRenderer(milkdrop, { presets }, { tap }, Random(1), now = { clock }, sleep = { sleeps += it; clock += it }, presetMs = 10_000)

    @Test
    fun itStartsOnAPresetAtOnceAndMovesOnToEachInTurnBlendingIn() {
        val milkdrop = FakeMilkdrop()
        val renderer = renderer(milkdrop)
        renderer.created()
        val first = milkdrop.calls.last()
        assertTrue(first.startsWith("show ") && !first.endsWith("smoothly"))
        val order = listOf("a", "b", "c").shuffled(Random(1))
        assertEquals("show ${order[0]}", first)

        // Not yet time for the next one.
        clock += 9_000
        renderer.frame()
        assertEquals(1, milkdrop.calls.count { it.startsWith("show") })
        clock += 1_000
        renderer.frame()
        assertEquals("show ${order[1]} smoothly", milkdrop.calls.dropLast(1).last())
        // After the last, the first again.
        clock += 10_000
        renderer.frame()
        clock += 10_000
        renderer.frame()
        assertEquals("show ${order[0]} smoothly", milkdrop.calls.dropLast(1).last())
    }

    @Test
    fun eachFrameDrawsWhatItHearsNoFasterThan30ASecond() {
        val milkdrop = FakeMilkdrop()
        val tap = FakeTap(byteArrayOf(1, 2, 3))
        val renderer = renderer(milkdrop, tap)
        renderer.created()
        renderer.frame()
        assertEquals(listOf<Byte>(1, 2, 3), milkdrop.heard.single())
        assertEquals("draw", milkdrop.calls.last())
        // Asked again straight away, it waits out the rest of the frame.
        clock += 10
        renderer.frame()
        assertEquals(listOf(MilkdropRenderer.FRAME_MS - 10), sleeps)
        // Asked late, it draws at once.
        clock += 100
        renderer.frame()
        assertEquals(1, sleeps.size)

        // Nothing heard, nothing given.
        tap.sound = byteArrayOf()
        renderer.frame()
        assertEquals(3, milkdrop.heard.size)
    }

    @Test
    fun itsSizeIsOnlySetWhenItChanges() {
        val milkdrop = FakeMilkdrop()
        val renderer = renderer(milkdrop)
        renderer.created()
        renderer.sized(360, 772)
        renderer.sized(360, 772)
        renderer.sized(360, 800)
        renderer.sized(400, 800)
        assertEquals(listOf("size 360x772", "size 360x800", "size 400x800"), milkdrop.calls.filter { it.startsWith("size") })
    }

    @Test
    fun comingBackItsGivenItsSizeAgainThoughItsTheSame() {
        // Back from another app, a new projectM starts at no size, so the same size is set again.
        val milkdrop = FakeMilkdrop()
        val renderer = renderer(milkdrop)
        renderer.created()
        renderer.sized(360, 772)
        renderer.stop()
        renderer.created()
        renderer.sized(360, 772)
        assertEquals(listOf("size 360x772", "size 360x772"), milkdrop.calls.filter { it.startsWith("size") })
    }

    @Test
    fun stoppedItLetsGoOfTheSoundAndOfProjectM() {
        val milkdrop = FakeMilkdrop()
        val tap = FakeTap()
        val renderer = renderer(milkdrop, tap)
        renderer.created()
        renderer.stop()
        assertTrue(tap.closed)
        assertEquals("close", milkdrop.calls.last())
        // Stopped twice, it closes once; and draws nothing more.
        renderer.stop()
        renderer.frame()
        renderer.sized(10, 10)
        assertEquals("close", milkdrop.calls.last())
    }

    @Test
    fun withoutProjectMNothingIsDrawnOrHeard() {
        val milkdrop = FakeMilkdrop(opens = false)
        var tapped = false
        val renderer = MilkdropRenderer(milkdrop, { listOf("a") }, { tapped = true; FakeTap() })
        renderer.created()
        renderer.sized(10, 10)
        renderer.frame()
        renderer.stop()
        assertEquals(listOf("open"), milkdrop.calls)
        assertFalse(tapped)
    }

    @Test
    fun withNothingToHearOrNoPresetsItStillDraws() {
        val milkdrop = FakeMilkdrop()
        val renderer = renderer(milkdrop, tap = null, presets = emptyList())
        renderer.created()
        clock += 20_000
        renderer.frame()
        renderer.stop()
        assertEquals(listOf("open", "draw", "close"), milkdrop.calls)
        assertTrue(milkdrop.heard.isEmpty())
    }

    @Test
    fun thePresetsThatComeWithTheAppAreAllMilkdropOnes() {
        val presets = milkdropPresets(ApplicationProvider.getApplicationContext<Application>().assets)
        assertTrue(presets.size >= 20)
        assertTrue(presets.all { "[preset00]" in it })
    }

    @Test
    fun theTapHearsWhatThePhonePlays() {
        val tap = OutputMixTap.open()
        assertNotNull(tap)
        val visualizer = Shadow.extract<ShadowVisualizer>(visualizerOf(tap!!))
        visualizer.setSource(object : ShadowVisualizer.VisualizerSource {
            override fun getWaveForm(waveform: ByteArray): Int {
                waveform.fill(7)
                return Visualizer.SUCCESS
            }
        })
        val samples = ByteArray(OutputMixTap.SAMPLES)
        assertEquals(OutputMixTap.SAMPLES, tap.read(samples))
        assertArrayEquals(ByteArray(OutputMixTap.SAMPLES) { 7 }, samples)
        // Too little room, or Android failing to say, is nothing heard.
        assertEquals(0, tap.read(ByteArray(16)))
        visualizer.setSource(object : ShadowVisualizer.VisualizerSource {
            override fun getWaveForm(waveform: ByteArray) = AudioEffect.ERROR
        })
        assertEquals(0, tap.read(samples))
        tap.close()
    }

    @Test
    fun aPhoneThatWontShareItsSoundHasNoTap() {
        // Made, but not ready to use, as without the permission; it's let go of all the same.
        var released = false
        assertNull(
            OutputMixTap.open {
                Visualizer(0).also {
                    val shadow = Shadow.extract<ShadowVisualizer>(it)
                    shadow.setSource(object : ShadowVisualizer.VisualizerSource {
                        override fun release() {
                            released = true
                        }
                    })
                    shadow.setState(Visualizer.STATE_UNINITIALIZED)
                }
            }
        )
        assertTrue(released)
        // One that can't even be made is no tap either.
        assertNull(OutputMixTap.open { throw UnsupportedOperationException("No visualizer here") })
    }

    private fun visualizerOf(tap: OutputMixTap): Visualizer =
        OutputMixTap::class.java.getDeclaredField("visualizer").apply { isAccessible = true }.get(tap) as Visualizer
}
