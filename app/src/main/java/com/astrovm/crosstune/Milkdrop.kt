package com.astrovm.crosstune

import android.content.Context
import android.content.res.AssetManager
import android.media.audiofx.Visualizer
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES20
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import android.view.TextureView
import kotlin.math.sin
import kotlin.random.Random

/** Draws MilkDrop presets, moving to the sound it hears. Every call is on the GL thread, with its context current. */
internal interface Milkdrop {
    /** Starts drawing at [width] by [height]; false when it can't. */
    fun open(width: Int, height: Int): Boolean
    fun size(width: Int, height: Int)
    /**
     * Moves on to [preset], the text of a .milk file, blending into it when [smooth]; whether it
     * loaded, as a phone may not manage some presets' shaders.
     */
    fun show(preset: String, smooth: Boolean): Boolean
    /** The first [count] of [samples], 8-bit mono, centred on 128. */
    fun hear(samples: ByteArray, count: Int)
    fun draw()
    /** Waits till what's drawn is on the screen, to time it. */
    fun finish() = Unit
    fun close()
}

/** projectM, the MilkDrop that runs on phones, built from source into the app. */
internal class NativeMilkdrop : Milkdrop {
    private var instance = 0L

    override fun open(width: Int, height: Int): Boolean {
        if (!loaded) return false
        instance = nativeCreate(width, height)
        return instance != 0L
    }

    override fun size(width: Int, height: Int) = nativeResize(instance, width, height)
    override fun show(preset: String, smooth: Boolean) = nativeLoad(instance, preset, smooth)
    override fun hear(samples: ByteArray, count: Int) = nativeHear(instance, samples, count)
    override fun draw() = nativeDraw(instance)
    override fun finish() = GLES20.glFinish()

    override fun close() {
        nativeDestroy(instance)
        instance = 0L
    }

    private external fun nativeCreate(width: Int, height: Int): Long
    private external fun nativeResize(instance: Long, width: Int, height: Int)
    private external fun nativeLoad(instance: Long, preset: String, smooth: Boolean): Boolean
    private external fun nativeHear(instance: Long, samples: ByteArray, count: Int)
    private external fun nativeDraw(instance: Long)
    private external fun nativeDestroy(instance: Long)

    companion object {
        /** Missing, e.g. on a phone whose processor it wasn't built for, there are just no visuals. */
        private val loaded = runCatching { System.loadLibrary("milkdrop") }.isSuccess
    }
}

/** The sound the phone plays, as a waveform. */
internal interface SoundTap {
    /** Fills [into] with what's playing now, 8-bit mono; how much, or 0 when there's nothing to hear. */
    fun read(into: ByteArray): Int
    fun close()
}

/**
 * What every app on the phone plays, mixed, through Android's [Visualizer]: no recording, and it
 * hears the music through headphones too. Android only allows it with the microphone permission.
 */
internal class OutputMixTap private constructor(private val visualizer: Visualizer) : SoundTap {
    override fun read(into: ByteArray): Int = try {
        if (into.size < visualizer.captureSize) 0
        else if (visualizer.getWaveForm(into) == Visualizer.SUCCESS) visualizer.captureSize else 0
    } catch (_: IllegalStateException) {
        // Taken away meanwhile, e.g. by another app's, it hears nothing.
        0
    }

    override fun close() = visualizer.release()

    companion object {
        /** [create] makes one on the output mix, audio session 0; null without the permission, or on a phone that won't share its sound. */
        fun open(create: () -> Visualizer = { Visualizer(0) }): OutputMixTap? {
            var visualizer: Visualizer? = null
            return try {
                create().also { visualizer = it }.apply {
                    // Android's largest; a phone that allows less keeps its own, which reads take.
                    captureSize = SAMPLES
                    enabled = true
                }.let(::OutputMixTap)
            } catch (_: RuntimeException) {
                visualizer?.release()
                null
            }
        }

        /** As much as projectM takes in at once. */
        const val SAMPLES = 1024
    }
}

/** The presets that come with the app, in assets/milkdrop. */
internal fun milkdropPresets(assets: AssetManager): List<String> =
    assets.list(PRESETS).orEmpty().filter { it.endsWith(".milk") }.sorted().map { name ->
        assets.open("$PRESETS/$name").bufferedReader().use { it.readText() }
    }

private const val PRESETS = "milkdrop"

/**
 * Draws [milkdrop] in a GL view: a preset at random, then another every [presetMs], blending in,
 * all moving to what [tap] hears, at no more than [FRAME_MS] a frame to spare the battery. A
 * preset this phone draws too slowly, which makes the words over it stutter too, is passed over
 * from then on.
 */
internal class MilkdropRenderer(
    private val milkdrop: Milkdrop,
    private val presets: () -> List<String>,
    private val tap: () -> SoundTap?,
    private val random: Random = Random.Default,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val presetMs: Long = PRESET_MS,
    /** Skips asked for before it started, which it doesn't make. */
    skips: Int = 0,
    /** The presets, by [presetKey], this phone drew too slowly before. */
    tooSlow: Set<String> = emptySet(),
    /** Told of a preset, by [presetKey], this phone draws too slowly, to pass it over next time too. */
    private val onTooSlow: (String) -> Unit = {},
    /**
     * A debug mode: each preset for [BENCH_MS], as fast as it draws, [report]ing its frames a second
     * as "key fps", then all of them, slowest first, before it goes on as usual.
     */
    bench: Boolean = false,
    private val report: (String) -> Unit = {}
) {
    private var benching = bench
    private var benchFrames = 0
    private val benched = mutableListOf<Pair<String, Int>>()

    private var open = false
    private var sound: SoundTap? = null
    private var shown: List<String> = emptyList()
    private var next = 0
    private var shownAt = 0L
    private var drawnAt = 0L
    private var drawnWidth = 0
    private var drawnHeight = 0
    private val samples = ByteArray(OutputMixTap.SAMPLES)
    private val slow = tooSlow.toMutableSet()
    private var current: String? = null
    private var timedMs = 0L
    private var timedFrames = 0
    private var timed = false

    /** Its GL context is ready. */
    fun created() {
        // A new one, as when the screen comes back, starts at no size, so the size is given again.
        drawnWidth = 0
        drawnHeight = 0
        open = milkdrop.open(1, 1)
        if (!open) return
        shown = presets().shuffled(random)
        next = 0
        sound = tap()
        showNext(smooth = false)
    }

    /** Drawing at [width] by [height], before each frame, so a change is caught. */
    fun sized(width: Int, height: Int) {
        if (!open || (width == drawnWidth && height == drawnHeight)) return
        drawnWidth = width
        drawnHeight = height
        milkdrop.size(width, height)
    }

    fun frame() {
        if (!open) return
        if (benching) return benchFrame()
        timeLastFrame()
        val wait = drawnAt + FRAME_MS - now()
        if (wait > 0) sleep(wait)
        drawnAt = now()
        if (skipsDone != skipsAsked || drawnAt - shownAt >= presetMs) {
            skipsDone = skipsAsked
            showNext(smooth = true)
        }
        listen()
        milkdrop.draw()
        // Waited for, so a frame's time is how long the phone took to draw it, not just to be asked to.
        milkdrop.finish()
    }

    private fun listen() {
        val heard = sound?.read(samples) ?: 0
        if (heard > 0 && !silent(samples, heard)) {
            milkdrop.hear(samples, heard)
        } else {
            // Many presets fade to black in silence, so with nothing playing a soft wave keeps them alive.
            idle(samples, ++idleFrame)
            milkdrop.hear(samples, samples.size)
        }
    }

    private var idleFrame = 0

    /** A frame timed: drawn as soon as the last is on the screen, counted once the preset's shaders are made. */
    private fun benchFrame() {
        val at = now()
        if (at - shownAt >= BENCH_MS) {
            val fps = (benchFrames * 1000 / (BENCH_MS - BENCH_FROM_MS)).toInt()
            current?.let { benched += presetKey(it) to fps }
            report("${current?.let(::presetKey)} $fps")
            if (benched.size >= shown.size) {
                report("slowest first: " + benched.sortedBy { it.second }.joinToString { "${it.first} ${it.second}" })
                benching = false
            }
            showNext(smooth = false)
            benchFrames = 0
        }
        if (now() - shownAt >= BENCH_FROM_MS) benchFrames++
        listen()
        milkdrop.draw()
        milkdrop.finish()
    }

    /**
     * How long the last frame took to draw, from the end of its wait to now, timed once the preset
     * has blended in, as two draw at once till then. Too slow, the preset's passed over at once.
     */
    private fun timeLastFrame() {
        val at = now()
        val since = at - shownAt
        val took = at - drawnAt
        // A frame that took this long was paused, out of sight, rather than slow.
        if (since in TIME_FROM_MS..TIME_TO_MS && took < PAUSED_MS) {
            timedMs += took
            timedFrames++
        }
        if (timed || since < TIME_TO_MS) return
        timed = true
        val preset = current ?: return
        if (timedFrames < TIMED_FRAMES || timedMs / timedFrames <= SLOW_FRAME_MS) return
        presetKey(preset).let { key ->
            slow += key
            onTooSlow(key)
        }
        // Cut straight to the next, rather than blending in, which would draw this one a while longer.
        showNext(smooth = false)
    }

    /** The next preset that loads, trying each at most once; one too slow only when they all are. */
    private fun showNext(smooth: Boolean) {
        shownAt = now()
        timedMs = 0
        timedFrames = 0
        timed = false
        val anyFast = shown.any { presetKey(it) !in slow }
        repeat(shown.size) {
            val preset = shown[next]
            next = (next + 1) % shown.size
            if (anyFast && !benching && presetKey(preset) in slow) return@repeat
            if (milkdrop.show(preset, smooth)) {
                current = preset
                return
            }
        }
    }

    @Volatile
    private var skipsAsked = skips
    private var skipsDone = skips

    /** Moves on to the next preset at the next frame, once for each time [skips] has gone up. */
    fun skipTo(skips: Int) {
        skipsAsked = skips
    }

    /** Stops drawing and listening; on the GL thread, while its context is still there. */
    fun stop() {
        sound?.close()
        sound = null
        if (open) milkdrop.close()
        open = false
    }

    companion object {
        /** Whether the first [count] of [samples] are all within a hair of the centre, 128. */
        fun silent(samples: ByteArray, count: Int): Boolean =
            (0 until count).all { (samples[it].toInt() and 0xFF) in 126..130 }

        /** Fills [into] with a slow, gentle wave for frame [frame]: quiet, but enough for presets to move to. */
        fun idle(into: ByteArray, frame: Int) {
            val phase = frame * 0.07
            val level = 0.35 + 0.25 * sin(frame * 0.045) + 0.15 * sin(frame * 0.13)
            for (i in into.indices) {
                val wave = 0.6 * sin(i * 0.02 + phase) + 0.3 * sin(i * 0.11 + phase * 2.3) + 0.1 * sin(i * 0.5 + frame)
                into[i] = (128 + 110 * level * wave).toInt().toByte()
            }
        }

        /** A preset, the text of its .milk file, by a key short enough to keep. */
        fun presetKey(preset: String): String = Integer.toHexString(preset.hashCode())

        const val PRESET_MS = 30_000L
        /** 30 frames a second. */
        const val FRAME_MS = 33L

        /** When a preset's frames are timed, from when it's shown: once it's blended in, for a couple of seconds. */
        private const val TIME_FROM_MS = 3_500L
        private const val TIME_TO_MS = 5_500L
        /** Fewer frames than this timed, and it's not known how fast it draws. */
        private const val TIMED_FRAMES = 20

        /** How long each preset's timed for in the debug mode, from when it's had time to make its shaders. */
        const val BENCH_MS = 1_500L
        private const val BENCH_FROM_MS = 500L

        /**
         * Slower than this a frame, on average, under 40 a second, and it keeps the phone so busy
         * that it and the words over it stutter, even drawn at 30 a second.
         */
        const val SLOW_FRAME_MS = 25L
        private const val PAUSED_MS = 1_000L
    }
}

/**
 * Shows what [renderer] draws, in the views like any other, so the words can go over it; a
 * SurfaceView would sit behind the window, out of sight. Drawing stops while it's out of sight.
 */
internal class MilkdropView(context: Context, val renderer: MilkdropRenderer) : TextureView(context), TextureView.SurfaceTextureListener {
    private var thread: GlThread? = null

    init {
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        val drawing = GlThread(surface, renderer)
        thread = drawing
        resize(surface, width, height)
        drawing.pause(!isShown)
        drawing.start()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = resize(surface, width, height)

    /** Drawn at a fraction of the screen's pixels and stretched: behind the words, the detail isn't missed, and it's far lighter. */
    private fun resize(surface: SurfaceTexture, width: Int, height: Int) {
        val drawnWidth = (width / SCALE).coerceAtLeast(1)
        val drawnHeight = (height / SCALE).coerceAtLeast(1)
        surface.setDefaultBufferSize(drawnWidth, drawnHeight)
        thread?.resize(drawnWidth, drawnHeight)
    }

    private companion object {
        const val SCALE = 2
    }

    /** Drawing on, the thread lets the surface go once it's done, so the screen doesn't wait for it. */
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        val drawing = thread ?: return true
        drawing.finish()
        thread = null
        return false
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        // Back in sight, the smaller size is asked for again, as Android may have put it back to the view's.
        if (isVisible) surfaceTexture?.let { resize(it, width, height) }
        thread?.pause(!isVisible)
    }
}

/** The thread [renderer] draws on, into [surface], with an OpenGL ES 3 context of its own. */
private class GlThread(private val surface: SurfaceTexture, private val renderer: MilkdropRenderer) : Thread("Milkdrop") {
    private val lock = Object()
    private var width = 0
    private var height = 0
    private var sized = false
    private var paused = false
    private var done = false

    fun resize(width: Int, height: Int) = synchronized(lock) {
        this.width = width
        this.height = height
        sized = true
        lock.notifyAll()
    }

    fun pause(paused: Boolean) = synchronized(lock) {
        this.paused = paused
        lock.notifyAll()
    }

    /** Stops after the frame it's on, then lets the surface go, so nothing draws into it once it's gone. */
    fun finish() = synchronized(lock) {
        done = true
        lock.notifyAll()
    }

    override fun run() = try {
        draw()
    } finally {
        // Its surface is this thread's to let go, once nothing draws into it.
        surface.release()
    }

    private fun draw() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(display, null, 0, null, 0)
        val configs = arrayOfNulls<EGLConfig>(1)
        val found = IntArray(1)
        EGL14.eglChooseConfig(
            display,
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_NONE
            ),
            0, configs, 0, 1, found, 0
        )
        val config = configs[0]
        val context = if (found[0] > 0) {
            EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        } else {
            EGL14.EGL_NO_CONTEXT
        }
        val window = if (context != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        } else {
            EGL14.EGL_NO_SURFACE
        }
        // Without OpenGL ES 3, or with visuals that fail to start, there are just no visuals.
        if (window != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, window, window, context)) try {
            renderer.created()
            while (true) {
                val size = synchronized(lock) {
                    while (!done && (paused || !sized)) lock.wait()
                    if (done) null else Pair(width, height)
                } ?: break
                // The surface's own size, which Android may change back behind the view's back, as when
                // the app comes back from another one; what was asked for is only the fallback.
                val drawn = IntArray(2)
                val known = EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, drawn, 0) &&
                    EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, drawn, 1) && drawn[0] > 0 && drawn[1] > 0
                if (known) renderer.sized(drawn[0], drawn[1]) else renderer.sized(size.first, size.second)
                renderer.frame()
                EGL14.eglSwapBuffers(display, window)
            }
        } catch (_: RuntimeException) {
            // Failed partway, the visuals just stop.
        } finally {
            runCatching { renderer.stop() }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        }
        if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }
}

/** What draws the visuals behind the words: MilkDrop, or frames drawn ahead, as the screenshots have them. */
internal val LocalVisuals = staticCompositionLocalOf<@Composable (skips: Int, modifier: Modifier) -> Unit> {
    { skips, modifier -> MilkdropVisuals(skips, modifier) }
}

/** MilkDrop visuals, moving to what the phone plays; each time [skips] goes up, on to the next. */
@Composable
internal fun MilkdropVisuals(skips: Int, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            // The presets too slow on this phone are kept, to pass over from the start next time.
            val kept = context.getSharedPreferences(VISUALS_PREFS, Context.MODE_PRIVATE)
            val renderer = MilkdropRenderer(
                MainActivity.milkdropFactory(), { milkdropPresets(context.assets) }, MainActivity.soundTapFactory, skips = skips,
                tooSlow = kept.getStringSet(KEY_TOO_SLOW, null).orEmpty(),
                onTooSlow = { key -> synchronized(kept) { kept.edit { putStringSet(KEY_TOO_SLOW, kept.getStringSet(KEY_TOO_SLOW, null).orEmpty() + key) } } },
                // adb shell setprop debug.crosstune.visuals_bench 1, then open the visuals: logcat -s CrosstuneVisuals.
                bench = systemProperty("debug.crosstune.visuals_bench") == "1",
                report = { Log.i("CrosstuneVisuals", it) }
            )
            MilkdropView(context, renderer)
        },
        update = { it.renderer.skipTo(skips) },
        modifier = modifier
    )
}

private const val VISUALS_PREFS = "milkdrop"
private const val KEY_TOO_SLOW = "too_slow"

/** A system property, set with adb shell setprop; null when it can't be read. */
private fun systemProperty(name: String): String? = runCatching {
    Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
}.getOrNull()
