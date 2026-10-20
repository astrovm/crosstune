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
        /** Presets this phone can't load. */
        var broken = emptySet<String>()

        override fun show(preset: String, smooth: Boolean): Boolean {
            calls += "show $preset${if (smooth) " smoothly" else ""}"
            return preset !in broken
        }
        override fun hear(samples: ByteArray, count: Int) {
            heard += samples.take(count)
        }
        override fun draw() {
            calls += "draw"
        }
        override fun finish() = Unit
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
        MilkdropRenderer(milkdrop, { presets }, { tap }, Random(1), now = { clock }, sleep = { sleeps += it; clock += it }, settings = VisualsSettings(changeMs = 10_000))

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

        // Nothing heard, a soft wave instead, so presets that fade in silence still show.
        tap.sound = byteArrayOf()
        renderer.frame()
        assertEquals(4, milkdrop.heard.size)
        assertEquals(OutputMixTap.SAMPLES, milkdrop.heard.last().size)
        assertTrue(milkdrop.heard.last().any { it != 128.toByte() })

        // Flat silence, as Android gives when nothing plays, is the same.
        tap.sound = ByteArray(8) { 128.toByte() }
        clock += 100
        renderer.frame()
        assertEquals(OutputMixTap.SAMPLES, milkdrop.heard.last().size)
        assertTrue(milkdrop.heard.last().any { it != 128.toByte() })
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
        assertTrue(milkdrop.heard.all { it.size == OutputMixTap.SAMPLES })
    }

    @Test
    fun thePresetsThatComeWithTheAppAreAllMilkdropOnes() {
        val assets = ApplicationProvider.getApplicationContext<Application>().assets
        val presets = milkdropPresets(assets)
        assertTrue(presets.size >= 20)
        // By name, without .milk, each read as a .milk file.
        assertTrue(presets.none { it.endsWith(".milk") })
        assertTrue(presets.all { "[preset00]" in milkdropPreset(assets, it) })
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
        // Taken away meanwhile, it hears nothing rather than crashing.
        visualizer.setSource(object : ShadowVisualizer.VisualizerSource {
            override fun getWaveForm(waveform: ByteArray): Int = throw IllegalStateException("Visualizer not enabled")
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

    @Test
    fun theSurfaceIsLetGoByTheThreadDrawingOnItOnceItsDone() {
        val view = MilkdropView(ApplicationProvider.getApplicationContext(), MilkdropRenderer(FakeMilkdrop(), { emptyList() }, { null }))
        // With nothing drawing, Android lets it go.
        assertTrue(view.onSurfaceTextureDestroyed(android.graphics.SurfaceTexture(0)))
        val released = java.util.concurrent.CountDownLatch(1)
        val surface = object : android.graphics.SurfaceTexture(0) {
            override fun release() = released.countDown()
        }
        view.onSurfaceTextureAvailable(surface, 100, 100)
        // Drawing, the thread does, once it's stopped, without the screen waiting for it.
        assertFalse(view.onSurfaceTextureDestroyed(surface))
        assertTrue(released.await(5, java.util.concurrent.TimeUnit.SECONDS))
    }

    private fun visualizerOf(tap: OutputMixTap): Visualizer =
        OutputMixTap::class.java.getDeclaredField("visualizer").apply { isAccessible = true }.get(tap) as Visualizer

    @Test
    fun aPresetThatWontLoadIsPassedOverForTheNext() {
        val milkdrop = FakeMilkdrop()
        val order = listOf("a", "b", "c").shuffled(Random(1))
        milkdrop.broken = setOf(order[0])
        val renderer = renderer(milkdrop)
        renderer.created()
        assertEquals(listOf("show ${order[0]}", "show ${order[1]}"), milkdrop.calls.filter { it.startsWith("show") })
        // None loading at all, each is tried once, and it still draws.
        val none = FakeMilkdrop().apply { broken = setOf("a", "b", "c") }
        renderer(none).apply { created(); frame() }
        assertEquals(3, none.calls.count { it.startsWith("show") })
        assertEquals("draw", none.calls.last())
    }

    @Test
    fun nextMovesOnAtTheNextFrameOncePerAsk() {
        val milkdrop = FakeMilkdrop()
        val renderer = MilkdropRenderer(milkdrop, { listOf("a", "b", "c") }, { null }, Random(1), now = { clock }, sleep = {}, settings = VisualsSettings(changeMs = 10_000), skips = 2)
        renderer.created()
        // Asks from before it started aren't made.
        renderer.frame()
        assertEquals(1, milkdrop.calls.count { it.startsWith("show") })
        renderer.skipTo(3)
        renderer.frame()
        renderer.frame()
        assertEquals(2, milkdrop.calls.count { it.startsWith("show") })
        assertTrue(milkdrop.calls.last { it.startsWith("show") }.endsWith("smoothly"))
    }

    /**
     * projectM asking for each preset's frame in [drawMs] of the clock, and the phone drawing it in
     * [finishMs] more; the same as [FakeMilkdrop] otherwise.
     */
    private inner class TimedMilkdrop(private val drawMs: Map<String, Long>, private val finishMs: Map<String, Long> = emptyMap()) : Milkdrop {
        val shows = mutableListOf<String>()
        private var showing = ""

        override fun open(width: Int, height: Int) = true
        override fun size(width: Int, height: Int) = Unit
        override fun show(preset: String, smooth: Boolean): Boolean {
            shows += preset
            showing = preset
            return true
        }
        override fun hear(samples: ByteArray, count: Int) = Unit
        override fun draw() {
            clock += drawMs.getValue(showing)
        }
        override fun finish() {
            clock += finishMs[showing] ?: 0
        }
        override fun close() = Unit
    }

    private fun timed(milkdrop: Milkdrop, settings: VisualsSettings = VisualsSettings(), onTooSlow: (String) -> Unit = {}, onDrop: (String) -> Unit = {}) =
        MilkdropRenderer(milkdrop, { listOf("a", "b", "c") }, { null }, Random(1), now = { clock }, sleep = { clock += it }, settings = settings, onTooSlow = onTooSlow, onDrop = onDrop)

    @Test
    fun eachSecondItTellsHowManyFramesASecondThePhoneCouldDraw() {
        val told = mutableListOf<Int>()
        val renderer = MilkdropRenderer(
            TimedMilkdrop(mapOf("a" to 8L), finishMs = mapOf("a" to 2L)), { listOf("a") }, { null }, Random(1),
            now = { clock }, sleep = { clock += it }, onFps = { told += it }
        )
        renderer.created()
        // A frame drawn in 10 ms waits out the rest of its 33: 100 a second could be drawn, not the 30 that are.
        repeat(100) { renderer.frame() }
        assertEquals(listOf(100, 100), told.drop(1).take(2))
    }

    @Test
    fun aPresetTooSlowToDrawIsPassedOverAndKeptSo() {
        val order = listOf("a", "b", "c").shuffled(Random(1))
        val milkdrop = TimedMilkdrop(mapOf(order[0] to 60L, order[1] to 10L, order[2] to 10L))
        val kept = mutableListOf<String>()
        val renderer = timed(milkdrop, onTooSlow = { kept += it })
        renderer.created()
        // Drawn at a frame each 60 ms, it's known slow once it's blended in, and cut straight from.
        repeat(100) { renderer.frame() }
        assertEquals(listOf(order[0]), kept)
        assertEquals(listOf(order[0], order[1]), milkdrop.shows)
        // One fast enough stays on.
        assertEquals(order[1], milkdrop.shows.last())

        // Next time, it's passed over from the start, and again when its turn comes.
        val again = TimedMilkdrop(mapOf(order[0] to 60L, order[1] to 10L, order[2] to 10L))
        val next = timed(again, VisualsSettings(heavy = kept.toSet()))
        next.created()
        next.skipTo(1)
        next.frame()
        next.skipTo(2)
        next.frame()
        assertEquals(listOf(order[1], order[2], order[1]), again.shows)
    }

    @Test
    fun aPresetIsTimedTillThePhoneHasDrawnItAndSlowerThan40ASecondIsTooSlow() {
        val order = listOf("a", "b", "c").shuffled(Random(1))
        // Asked for in 5 ms, but drawn in 21 more: 26 ms a frame, too slow, though it fits in 30 a second.
        val slow = TimedMilkdrop(mapOf("a" to 5L, "b" to 5L, "c" to 5L), mapOf(order[0] to 21L))
        val kept = mutableListOf<String>()
        timed(slow, onTooSlow = { kept += it }).apply { created(); repeat(200) { frame() } }
        assertEquals(listOf(order[0]), kept)
        // 25 ms a frame is fast enough.
        val fast = TimedMilkdrop(mapOf("a" to 5L, "b" to 5L, "c" to 5L), mapOf(order[0] to 20L))
        timed(fast, onTooSlow = { kept += it }).apply { created(); repeat(200) { frame() } }
        assertEquals(1, kept.size)
        assertEquals(listOf(order[0]), fast.shows)
    }

    @Test
    fun withEveryPresetTooSlowItStillShowsThemAndAPauseIsntSlowness() {
        val slow = setOf("a", "b", "c")
        val milkdrop = TimedMilkdrop(mapOf("a" to 10L, "b" to 10L, "c" to 10L))
        val kept = mutableListOf<String>()
        val renderer = timed(milkdrop, VisualsSettings(heavy = slow))
        renderer.created()
        assertEquals(1, milkdrop.shows.size)
        // Out of sight a while, the frame after isn't timed as slow.
        val paused = TimedMilkdrop(mapOf("a" to 10L, "b" to 10L, "c" to 10L))
        val fast = timed(paused, onTooSlow = { kept += it })
        fast.created()
        // Once it's blended in, while it's timed.
        repeat(109) { fast.frame() }
        clock += 1_200
        repeat(40) { fast.frame() }
        assertEquals(emptyList<String>(), kept)
        assertEquals(1, paused.shows.size)
    }

    @Test
    fun theDebugModeTimesEachPresetAsFastAsItDrawsThenGoesOnAsUsual() {
        val order = listOf("a", "b", "c").shuffled(Random(1))
        // a draws in 20 ms, b in 50, c in 10, each waited for till it's on the screen.
        val milkdrop = TimedMilkdrop(mapOf("a" to 20L, "b" to 50L, "c" to 10L))
        val reports = mutableListOf<String>()
        val renderer = MilkdropRenderer(
            milkdrop, { listOf("a", "b", "c") }, { null }, Random(1), now = { clock }, sleep = { clock += it },
            settings = VisualsSettings(heavy = setOf("b")), bench = true, report = { reports += it }
        )
        renderer.created()
        repeat(400) { renderer.frame() }
        // No wait between frames: as many as each draws in its second timed, even one kept as too slow.
        val fps = mapOf("a" to 50, "b" to 20, "c" to 100)
        assertEquals(order.map { "$it: ${fps.getValue(it)}" }, reports.take(3))
        assertEquals("slowest first: " + listOf("b", "a", "c").joinToString { "$it: ${fps.getValue(it)}" }, reports[3])
        assertEquals(4, reports.size)
        // Then as usual: the one too slow is passed over.
        assertEquals(order + order.first { it != "b" }, milkdrop.shows.take(4))
    }

    @Test
    fun visualsTurnedOffNeverShowAndSettingsChangeAsItDraws() {
        val order = listOf("a", "b", "c").shuffled(Random(1))
        val milkdrop = TimedMilkdrop(mapOf("a" to 10L, "b" to 10L, "c" to 10L))
        val renderer = timed(milkdrop, VisualsSettings(off = setOf(order[0]), changeMs = 15_000))
        renderer.created()
        assertEquals(listOf(order[1]), milkdrop.shows)
        // Turned off meanwhile, the next is passed over too.
        renderer.settings = VisualsSettings(off = setOf(order[0], order[2]), changeMs = 15_000)
        repeat(1_000) { renderer.frame() }
        assertEquals(setOf(order[1]), milkdrop.shows.toSet())
    }

    @Test
    fun aVisualDroppedIsPassedOverAtOnceAndSaidSo() {
        val order = listOf("a", "b", "c").shuffled(Random(1))
        val milkdrop = TimedMilkdrop(mapOf("a" to 10L, "b" to 10L, "c" to 10L))
        val dropped = mutableListOf<String>()
        val renderer = timed(milkdrop, VisualsSettings(changeMs = 15_000), onDrop = { dropped += it })
        renderer.created()
        renderer.frame()
        renderer.dropTo(1)
        renderer.frame()
        assertEquals(listOf(order[0]), dropped)
        assertEquals(listOf(order[0], order[1]), milkdrop.shows)
        // It doesn't come back, though settings haven't caught up yet; nor is it dropped twice.
        repeat(2_000) { renderer.frame() }
        assertEquals(listOf(order[0]), dropped)
        assertEquals(setOf(order[1], order[2]), milkdrop.shows.drop(1).toSet())
    }

    @Test
    fun aHeavyVisualTurnedOnAnywayOrWithHeavyOnesKeptShowsAndIsntTimed() {
        val order = listOf("a", "b", "c").shuffled(Random(1))
        val kept = mutableListOf<String>()
        // Too slow, and known so, but turned on anyway: shown first, and left on.
        val anyway = TimedMilkdrop(mapOf(order[0] to 60L, order[1] to 10L, order[2] to 10L))
        timed(anyway, VisualsSettings(heavy = setOf(order[0]), keep = setOf(order[0])), onTooSlow = { kept += it }).apply { created(); repeat(100) { frame() } }
        assertEquals(listOf(order[0]), anyway.shows)
        // Heavy ones not skipped, the same.
        val all = TimedMilkdrop(mapOf(order[0] to 60L, order[1] to 10L, order[2] to 10L))
        timed(all, VisualsSettings(heavy = setOf(order[0]), skipHeavy = false), onTooSlow = { kept += it }).apply { created(); repeat(100) { frame() } }
        assertEquals(listOf(order[0]), all.shows)
        assertEquals(emptyList<String>(), kept)
    }

    @Test
    fun setToNeverAVisualStaysTillTheNextIsAskedFor() {
        val milkdrop = TimedMilkdrop(mapOf("a" to 10L, "b" to 10L, "c" to 10L))
        val renderer = timed(milkdrop, VisualsSettings(changeMs = VisualsSettings.NEVER))
        renderer.created()
        repeat(5_000) { renderer.frame() }
        assertEquals(1, milkdrop.shows.size)
        renderer.skipTo(1)
        renderer.frame()
        assertEquals(2, milkdrop.shows.size)
    }

    @Test
    fun visualsSettingsAreKept() {
        val store = VisualsStore(ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("visuals-test", 0))
        assertEquals(VisualsSettings(), store.load())
        store.turn("a", on = false)
        store.heavy("b")
        assertEquals(VisualsSettings(off = setOf("a"), heavy = setOf("b")), store.load())
        assertTrue(!store.load().shows("a") && !store.load().shows("b") && store.load().shows("c"))
        // A heavy one turned on is kept on; turned off again, it isn't.
        store.turn("b", on = true)
        assertEquals(setOf("b"), store.load().keep)
        assertTrue(store.load().shows("b"))
        store.turn("b", on = false)
        assertEquals(emptySet<String>(), store.load().keep)
        assertEquals(setOf("a", "b"), store.load().off)
        store.turn("a", on = true)
        assertEquals(setOf("b"), store.load().off)
        // Heavy ones shown, and how long each shows; a length Settings doesn't offer is the usual.
        store.skipHeavy(false)
        store.changeEvery(60_000)
        assertEquals(false to 60_000L, store.load().let { it.skipHeavy to it.changeMs })
        store.changeEvery(1)
        assertEquals(MilkdropRenderer.PRESET_MS, store.load().changeMs)
    }
}
