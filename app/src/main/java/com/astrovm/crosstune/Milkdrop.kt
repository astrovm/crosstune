package com.astrovm.crosstune

import android.content.Context
import android.content.res.AssetManager
import android.media.audiofx.Visualizer
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import android.view.TextureView
import kotlin.random.Random

/** Draws MilkDrop presets, moving to the sound it hears. Every call is on the GL thread, with its context current. */
internal interface Milkdrop {
    /** Starts drawing at [width] by [height]; false when it can't. */
    fun open(width: Int, height: Int): Boolean
    fun size(width: Int, height: Int)
    /** Moves on to [preset], the text of a .milk file, blending into it when [smooth]. */
    fun show(preset: String, smooth: Boolean)
    /** The first [count] of [samples], 8-bit mono, centred on 128. */
    fun hear(samples: ByteArray, count: Int)
    fun draw()
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

    override fun close() {
        nativeDestroy(instance)
        instance = 0L
    }

    private external fun nativeCreate(width: Int, height: Int): Long
    private external fun nativeResize(instance: Long, width: Int, height: Int)
    private external fun nativeLoad(instance: Long, preset: String, smooth: Boolean)
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
    override fun read(into: ByteArray): Int {
        if (into.size < visualizer.captureSize) return 0
        return if (visualizer.getWaveForm(into) == Visualizer.SUCCESS) visualizer.captureSize else 0
    }

    override fun close() = visualizer.release()

    companion object {
        /** Null without the permission, or on a phone that won't share its sound. */
        /** [create] makes one on the output mix, audio session 0. */
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
 * all moving to what [tap] hears, at no more than [FRAME_MS] a frame to spare the battery.
 */
internal class MilkdropRenderer(
    private val milkdrop: Milkdrop,
    private val presets: () -> List<String>,
    private val tap: () -> SoundTap?,
    private val random: Random = Random.Default,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val presetMs: Long = PRESET_MS
) {
    private var open = false
    private var sound: SoundTap? = null
    private var shown: List<String> = emptyList()
    private var next = 0
    private var shownAt = 0L
    private var drawnAt = 0L
    private var drawnWidth = 0
    private var drawnHeight = 0
    private val samples = ByteArray(OutputMixTap.SAMPLES)

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
        val wait = drawnAt + FRAME_MS - now()
        if (wait > 0) sleep(wait)
        drawnAt = now()
        if (drawnAt - shownAt >= presetMs) showNext(smooth = true)
        val heard = sound?.read(samples) ?: 0
        if (heard > 0) milkdrop.hear(samples, heard)
        milkdrop.draw()
    }

    private fun showNext(smooth: Boolean) {
        shownAt = now()
        if (shown.isEmpty()) return
        milkdrop.show(shown[next], smooth)
        next = (next + 1) % shown.size
    }

    /** Stops drawing and listening; on the GL thread, while its context is still there. */
    fun stop() {
        sound?.close()
        sound = null
        if (open) milkdrop.close()
        open = false
    }

    companion object {
        const val PRESET_MS = 30_000L
        /** 30 frames a second. */
        const val FRAME_MS = 33L
    }
}

/**
 * Shows what [renderer] draws, in the views like any other, so the words can go over it; a
 * SurfaceView would sit behind the window, out of sight. Drawing stops while it's out of sight.
 */
internal class MilkdropView(context: Context, private val renderer: MilkdropRenderer) : TextureView(context), TextureView.SurfaceTextureListener {
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

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        thread?.finish()
        thread = null
        return true
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

    /** Stops, and waits for it, so nothing draws into the surface once it's gone. */
    fun finish() {
        synchronized(lock) {
            done = true
            lock.notifyAll()
        }
        join()
    }

    override fun run() {
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
        // Without OpenGL ES 3 there are just no visuals.
        if (window != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, window, window, context)) {
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
            renderer.stop()
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        }
        if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }
}

/** MilkDrop visuals, moving to what the phone plays. */
@Composable
internal fun MilkdropVisuals(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            MilkdropView(context, MilkdropRenderer(MainActivity.milkdropFactory(), { milkdropPresets(context.assets) }, MainActivity.soundTapFactory))
        },
        modifier = modifier
    )
}
