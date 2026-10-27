package com.astrovm.crosstune.shots

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.astrovm.crosstune.Following
import com.astrovm.crosstune.Frontend
import com.astrovm.crosstune.HistoryEntry
import com.astrovm.crosstune.ItemType
import com.astrovm.crosstune.Learning
import com.astrovm.crosstune.LocalVisuals
import com.astrovm.crosstune.MusicLink
import com.astrovm.crosstune.MusicMetadata
import com.astrovm.crosstune.MusicService
import com.astrovm.crosstune.PlaybackClock
import com.astrovm.crosstune.R
import com.astrovm.crosstune.Readings
import com.astrovm.crosstune.ScreenActions
import com.astrovm.crosstune.Script
import com.astrovm.crosstune.SongRecognizers
import com.astrovm.crosstune.SyncedLyrics
import com.astrovm.crosstune.UiState
import com.astrovm.crosstune.CrosstuneScreen
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import com.astrovm.crosstune.ui.theme.Palette
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The store's screenshots and the site's clips, drawn here on a clock the test moves, frame by
 * frame, so every run and every language shows the same thing. Not part of the unit tests:
 * scripts/shots/shots.py runs them, through Gradle's `shots` task, with the MilkDrop visuals
 * rendered ahead by scripts/milkdrop-render.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night")
abstract class Shots(private val lang: String, private val darkTheme: Boolean = true) {

    private val theme = if (darkTheme) "dark" else "light"

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val out = File(File(System.getProperty("shots.out") ?: "build/shots", lang), theme).apply { mkdirs() }
    private val visualsDir = File(System.getProperty("shots.visuals") ?: "build/shots/visuals")

    // Each test's density and each class's language are set before the screen opens: changing them
    // after makes Robolectric open the screen again, without the bars' insets.
    @Test
    @Config(qualifiers = "+420dpi")
    fun store() {
        start()
        val store = File(out, "store").apply { mkdirs() }
        show(home(apple, resolved = true))
        still(File(store, "1.png"))
        show(home(deezer, resolved = true))
        scrollUp(300f)
        still(File(store, "2.png"))
        show(home(apple, resolved = true))
        settle()
        composeRule.onNodeWithContentDescription(string(R.string.choose_app_button)).performTouchInput { click() }
        step(16)
        placeMenus()
        settle()
        still(File(store, "3.png"))
        show(lyrics())
        still(File(store, "4.png"))
        visuals = Frames(File(visualsDir, "full.mp4"), width, height)
        show(lyrics(visuals = true))
        still(File(store, "5.png"))
        visuals = null
        listeningBar = true
        show(UiState(listening = true))
        still(File(store, "6.png"))
        listeningBar = false
        show(home(null, resolved = false))
        settle()
        composeRule.onNodeWithContentDescription(string(R.string.settings_button)).performTouchInput { click() }
        settle()
        scrollUp(500f)
        still(File(store, "7.png"))
        show(firstRun())
        still(File(store, "8.png"))
    }

    @Test
    @Config(qualifiers = "+210dpi")
    fun clips() {
        start()
        homeClip()
        lyricsClip()
    }

    /** A song's link turned into one for YouTube Music, then a playlist's into its songs. */
    private fun homeClip() {
        var state by mutableStateOf(home(null, resolved = false))
        show { state }
        Video(File(out, "home-$theme.mp4"), width, height).use { video ->
            record(video, 13_300) { at ->
                when (at) {
                    1_000L -> state = state.copy(linkText = apple.url)
                    1_800L -> state = state.copy(isLoading = true)
                    2_600L -> state = home(apple, resolved = true)
                    5_000L -> save(File(out, "home-$theme.png"))
                    6_000L -> state = home(null, resolved = false, recentFirst = true).copy(linkText = deezer.url)
                    6_800L -> state = state.copy(isLoading = true)
                    7_600L -> state = home(deezer, resolved = true, recentFirst = true)
                    9_200L -> scrollUpStart()
                }
                if (at in 9_200L..10_700L) scrollStep(at - 9_200L, 1_500L, 420f * density)
                if (at == 10_700L) scrollEnd()
            }
        }
    }

    /** The words following the song, in Latin letters and translated, then with MilkDrop visuals behind. */
    private fun lyricsClip() {
        var state by mutableStateOf(lyrics())
        show { state }
        Video(File(out, "lyrics-$theme.mp4"), width, height).use { video ->
            record(video, 11_400) { at ->
                if (at == 500L) save(File(out, "lyrics-$theme.png"))
                // By then the bar has gone out of the words' way, as it does a few seconds into a
                // playing song; a touch at the top, clear of the words, brings it back.
                if (at == 4_000L) root().performTouchInput { click(Offset(centerX, 8f)) }
                if (at == 4_600L) {
                    composeRule.onNodeWithContentDescription(string(R.string.visuals_show)).performTouchInput { click() }
                }
                if (at == 4_700L) {
                    visuals = Frames(File(visualsDir, "half.mp4"), width, height)
                    state = state.copy(visuals = true)
                }
            }
        }
        visuals = null
    }

    // The scenes.

    private val apple = MusicLink(MusicService.APPLE_MUSIC, ItemType.TRACK, "1060484510", "https://music.apple.com/us/song/1060484510")
    private val deezer = MusicLink(MusicService.DEEZER, ItemType.PLAYLIST, "3884384422", "https://www.deezer.com/playlist/3884384422")
    private val dreams = MusicMetadata("Dreams Come True", "S.E.S.", artworkUrl = "dreams.jpg")
    private val cityPop by lazy {
        val songs = resource("city-pop.tsv").bufferedReader().readLines().map { it.split('\t') }
            .map { (title, artist, cover, ms) -> MusicMetadata(title, artist, artworkUrl = cover, lengthMs = ms.toLongOrNull()) }
        MusicMetadata("City Pop", "", ItemType.PLAYLIST, artworkUrl = "dz-playlist.jpg", tracks = songs, trackCount = 55)
    }
    private val recent by lazy {
        listOf(
            HistoryEntry(deezer, cityPop.copy(tracks = emptyList())),
            HistoryEntry(MusicLink(MusicService.SPOTIFY, ItemType.TRACK, "fineday", "https://open.spotify.com/track/fineday"), MusicMetadata("It's A Fine Day", "Opus III", artworkUrl = "fine-day.jpg")),
            HistoryEntry(MusicLink(MusicService.YOUTUBE_MUSIC, ItemType.TRACK, "firstlove", "https://music.youtube.com/watch?v=firstlove"), MusicMetadata("First Love", "After School", artworkUrl = "dz-698223c4f7562143096ddcff890f3157.jpg")),
            HistoryEntry(MusicLink(MusicService.DEEZER, ItemType.TRACK, "dakare", "https://www.deezer.com/track/dakare"), MusicMetadata("Dakare Ni Kita Onna", "Kingo Hamada", artworkUrl = "dz-4f1c4b4e23c3c302adb946573c9fdad8.jpg"))
        )
    }

    private fun home(link: MusicLink?, resolved: Boolean, recentFirst: Boolean = false): UiState {
        val history = if (recentFirst) listOf(HistoryEntry(apple, dreams)) + recent else recent
        val result = when (link) {
            apple -> dreams
            deezer -> cityPop
            else -> null
        }
        return base().copy(
            linkText = link?.url.orEmpty(),
            result = if (resolved) result else null,
            link = if (resolved) link else null,
            history = history
        )
    }

    private val lrc by lazy { JSONObject(resource("dreams-come-true.json").bufferedReader().readText()) }

    private fun lyrics(visuals: Boolean = false): UiState {
        val lines = SyncedLyrics.withPauses(SyncedLyrics.parse(lrc.getString("lrc")))
        val texts = lines.map { it.text }
        val words = lrc.getJSONObject("translations").getJSONObject(lang)
        return base().copy(
            lyricsFor = dreams,
            lyrics = texts.joinToString("\n"),
            lyricLines = lines,
            lyricsSynced = true,
            following = Following(PlaybackClock(LYRICS_AT, SystemClock.elapsedRealtime()), "VLC", canSeek = true, appPackage = VLC),
            canFollowApps = true,
            visuals = visuals,
            learning = Learning(
                romanized = true,
                translation = true,
                script = Script.KOREAN,
                lines = Readings.of(texts, Script.KOREAN),
                translations = texts.map { words.optString(it).ifEmpty { null } }
            )
        )
    }

    /** A fresh install: nothing opened yet, and what Android has to allow asked under the field. */
    private fun firstRun() = base().copy(
        intercepted = MusicService.entries.filter { it.opensTappedLinks }.toSet(),
        unapprovedHosts = MusicService.entries.filter { it.opensTappedLinks }.associateWith { listOf("open.spotify.com") },
        canFollowApps = false
    )

    private fun base() = UiState(
        palette = Palette.VIOLET,
        destinations = MusicService.entries.map { com.astrovm.crosstune.Destination.Service(it) },
        intercepted = MusicService.entries.filter { it.opensTappedLinks }.toSet(),
        unapprovedHosts = emptyMap(),
        blockingApps = emptySet(),
        claimingApps = emptySet(),
        canFollowApps = true,
        canFloatOverApps = true
    )

    // Drawing.

    private var width = 0
    private var height = 0
    private var density = 1f
    private var scene by mutableStateOf<() -> UiState>({ UiState() })
    private var sceneId by mutableIntStateOf(0)
    private var listeningBar by mutableStateOf(false)
    private var visualFrame by mutableStateOf<ImageBitmap?>(null)
    private var visuals: Frames? = null
        set(value) {
            field?.close()
            field = value
            visualFrame = null
        }
    private val covers = mutableMapOf<String, ImageBitmap?>()

    private fun start() {
        val activity = composeRule.activity
        density = activity.resources.displayMetrics.density
        shadowOf(activity.packageManager).setApplicationIcon(VLC, BitmapDrawable(activity.resources, bitmap("vlc.png")))
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val statusBar = bitmap("statusbar.png").asImageBitmap()
        val listening = bitmap("statusbar-listening.png").asImageBitmap()
        val lightListening = lightBar(bitmap("statusbar-listening.png")).asImageBitmap()
        val handle = bitmap("handle.png").asImageBitmap()
        val actions = ScreenActions(
            recognizers = SongRecognizers.available(activity),
            loadArtwork = { name -> covers.getOrPut(name) { runCatching { bitmap("covers/$name").asImageBitmap() }.getOrNull() } }
        )
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CrosstuneTheme(darkTheme = darkTheme, palette = Palette.VIOLET) {
                CompositionLocalProvider(
                    LocalVisuals provides { play, modifier -> VisualFrame(play.shade, modifier) },
                    LocalResources provides released(activity.resources)
                ) {
                    Box(Modifier.fillMaxSize()) {
                        // Always reading the bars' room keeps it: once nothing does, Compose forgets it,
                        // and asks Android for it again, which has none to give here.
                        Box(Modifier.windowInsetsPadding(WindowInsets.systemBars))
                        key(sceneId) { CrosstuneScreen(scene(), actions) }
                        // Cut from a real screenshot at 420 dpi, so 2.625 pixels a dp.
                        val lightBars = !darkTheme && !scene().visuals
                        val barTint = if (lightBars) ColorFilter.tint(Color.Black) else null
                        Image(if (listeningBar) (if (lightBars) lightListening else listening) else statusBar, null,
                            Modifier.fillMaxWidth().align(Alignment.TopCenter), contentScale = ContentScale.FillWidth,
                            colorFilter = if (listeningBar) null else barTint)
                        Image(handle, null, Modifier.align(Alignment.BottomCenter).size((320 / 2.625f).dp, (40 / 2.625f).dp),
                            contentScale = ContentScale.FillBounds, colorFilter = barTint)
                    }
                }
            }
        }
        applyInsets()
        step(16)
        val root = capture()
        width = root.width and 1.inv()
        height = root.height and 1.inv()
    }

    /** Resources that name the app as released: the shots are drawn with the dev build, named Crosstune Dev. */
    @Suppress("DEPRECATION")
    private fun released(resources: Resources): Resources = object : Resources(resources.assets, resources.displayMetrics, resources.configuration) {
        override fun getString(id: Int): String = if (id == R.string.app_name) "Crosstune" else super.getString(id)
    }

    /** The bars' room, as the phone the screenshots used to come from has it, in its pixels. */
    private fun applyInsets() {
        val px = density / 2.625f
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, (132 * px).toInt(), 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, (66 * px).toInt()))
            .build()
        composeRule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(composeRule.activity.window.decorView, insets) }
    }

    @Composable
    private fun VisualFrame(shade: Float, modifier: Modifier) {
        // Darkened as the visuals darken themselves.
        visualFrame?.let { Image(it, null, modifier.drawWithContent { drawContent(); drawRect(Color.Black.copy(alpha = shade)) }, contentScale = ContentScale.Crop) }
    }

    private fun show(state: UiState) = show { state }

    private fun show(state: () -> UiState) {
        scene = state
        sceneId++
        settle()
    }

    /** Lets what's loading land and what moves come to rest, covers' colors worked out in real time included. */
    private fun settle() {
        step(16)
        Thread.sleep(1_000)
        step(2_000)
    }

    private fun still(file: File) {
        visuals?.let { frames ->
            frames.skip(STILL_VISUAL_FRAME)
            visualFrame = frames.next()
            step(16)
        }
        save(file)
    }

    /** [ms] of frames at 60 a second, 17, 17 and 16 ms apart, so every 50 ms lands on one, as [onFrame]'s times are. */
    private fun record(video: Video, ms: Long, onFrame: (at: Long) -> Unit) {
        var frame = 0
        var at = 0L
        while (at < ms) {
            onFrame(at)
            visuals?.let { visualFrame = it.next() }
            val next = if (frame % 3 == 2) 16L else 17L
            step(next)
            video.write(capture())
            at += next
            frame++
        }
    }

    // A swipe up, spread over frames so it shows moving.
    private var swipeY = 0f

    private fun scrollUpStart() {
        swipeY = height * 0.75f
        root().performTouchInput { down(Offset(width / 2f, swipeY)) }
    }

    private fun scrollStep(elapsed: Long, duration: Long, distance: Float) {
        val t = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
        val eased = t * t * (3 - 2 * t)
        val y = height * 0.75f - distance * eased
        val dy = y - swipeY
        swipeY = y
        if (dy != 0f) root().performTouchInput { moveBy(Offset(0f, dy)) }
    }

    private fun scrollEnd() = root().performTouchInput { up() }

    private fun scrollUp(dp: Float) {
        scrollUpStart()
        var elapsed = 0L
        while (elapsed <= 1_000L) {
            scrollStep(elapsed, 1_000L, dp * density)
            step(16)
            elapsed += 16
        }
        scrollStep(1_000L, 1_000L, dp * density)
        scrollEnd()
        step(2_000)
    }

    private fun tap(text: String) {
        composeRule.onNodeWithText(text).performTouchInput { click() }
        settle()
    }

    private fun root(): SemanticsNodeInteraction = composeRule.onAllNodes(isRoot()).onFirst()

    private fun step(ms: Long) {
        // What the test changed shows in the next frame, as it would have if the app had changed it.
        Snapshot.sendApplyNotifications()
        ShadowSystemClock.advanceBy(Duration.ofMillis(ms))
        composeRule.mainClock.advanceTimeBy(ms)
    }

    /** The screen, with any menu open over it where it shows. */
    private fun capture(): Bitmap {
        val screen = composeRule.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true)
        // A menu is a window of its own, which capturing a node of it doesn't draw, nor does drawing
        // its view on a canvas, so it's drawn as Robolectric draws a screen, then laid over.
        val decor = composeRule.activity.window.decorView
        val popups = windows().filter { it !== decor && it.isShown && it.width > 0 }
        if (popups.isNotEmpty()) {
            val canvas = android.graphics.Canvas(screen)
            val at = IntArray(2)
            for (popup in popups) {
                popup.getLocationOnScreen(at)
                val menu = Bitmap.createBitmap(popup.width, popup.height, Bitmap.Config.ARGB_8888)
                renderView.invoke(null, popup, menu)
                canvas.drawBitmap(menu, at[0].toFloat(), at[1].toFloat(), null)
            }
        }
        return screen
    }

    /**
     * A menu stays see-through until it knows where it goes, which it learns when its window is laid
     * out, and Robolectric doesn't tell a menu's window it was. So it's told here.
     */
    private fun placeMenus() {
        val decor = composeRule.activity.window.decorView
        composeRule.runOnUiThread { windows().filter { it !== decor }.forEach { it.viewTreeObserver.dispatchOnGlobalLayout() } }
    }

    /** Robolectric's own drawing of a view through the GPU's path, the one that draws Compose. */
    private val renderView = Class.forName("org.robolectric.shadows.HardwareRenderingScreenshot")
        .getDeclaredMethod("takeScreenshot", View::class.java, Bitmap::class.java).apply { isAccessible = true }

    /** Every window's view, the screen's first. */
    @Suppress("UNCHECKED_CAST")
    private fun windows(): List<View> {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<View>
        return views.toList()
    }

    private fun save(file: File) {
        val image = capture()
        val cropped = Bitmap.createBitmap(image, 0, 0, width, height)
        file.outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun string(id: Int) = composeRule.activity.getString(id)

    private fun resource(name: String): InputStream = javaClass.getResourceAsStream("/shots/$name") ?: error("No shots/$name in the test resources")

    private fun bitmap(name: String): Bitmap = resource(name).use { BitmapFactory.decodeStream(it) }

    /** Dark system icons for light screens, keeping Android's green microphone indicator. */
    private fun lightBar(source: Bitmap): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(result.width * result.height)
        result.getPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
        for (i in pixels.indices) {
            val color = pixels[i]
            val red = AndroidColor.red(color)
            if (red == AndroidColor.green(color) && red == AndroidColor.blue(color)) {
                val shade = 255 - red
                pixels[i] = AndroidColor.argb(AndroidColor.alpha(color), shade, shade, shade)
            }
        }
        result.setPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
        return result
    }

    /** A video's frames one after another, scaled to fill [width] by [height], as the visuals were drawn ahead. */
    private class Frames(file: File, private val width: Int, private val height: Int) : AutoCloseable {
        private val process = ProcessBuilder(
            "ffmpeg", "-v", "error", "-threads", "1", "-filter_threads", "1", "-i", file.path.also { check(File(it).exists()) { "No visuals at $it, run scripts/shots/shots.py" } },
            "-vf", "scale=$width:$height:force_original_aspect_ratio=increase,crop=$width:$height", "-f", "rawvideo", "-pix_fmt", BYTE_ORDER, "-"
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        private val input = process.inputStream.buffered(1 shl 22)
        private val bytes = ByteArray(width * height * 4)
        private var last: ImageBitmap? = null

        private fun read(): Boolean {
            var read = 0
            while (read < bytes.size) {
                val n = input.read(bytes, read, bytes.size - read)
                if (n < 0) return false
                read += n
            }
            return true
        }

        fun skip(frames: Int) = repeat(frames) { read() }

        /** The next frame, or the last one again once the video ends. */
        fun next(): ImageBitmap? {
            if (!read()) return last
            val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            image.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
            return image.asImageBitmap().also { last = it }
        }

        override fun close() {
            process.destroy()
            input.close()
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor()
            }
        }
    }

    /** A clip at 60 frames a second, encoded as the site has them. */
    private class Video(file: File, private val width: Int, private val height: Int) : AutoCloseable {
        private val process = ProcessBuilder(
            "ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", BYTE_ORDER, "-s", "${width}x$height", "-r", "60", "-i", "-",
            "-c:v", "libx264", "-profile:v", "high", "-crf", "30", "-preset", "veryfast", "-threads", "2", "-pix_fmt", "yuv420p", "-movflags", "+faststart", file.path
        ).redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        private val pipe: OutputStream = process.outputStream.buffered(1 shl 22)
        private val row = ByteArray(width * 4)

        fun write(image: Bitmap) {
            val buffer = ByteBuffer.allocate(image.byteCount)
            image.copyPixelsToBuffer(buffer)
            val all = buffer.array()
            for (y in 0 until height) {
                System.arraycopy(all, y * image.rowBytes, row, 0, row.size)
                pipe.write(row)
            }
        }

        override fun close() {
            try {
                pipe.close()
                check(process.waitFor(30, TimeUnit.SECONDS)) { "ffmpeg timed out" }
                check(process.exitValue() == 0) { "ffmpeg failed" }
            } finally {
                if (process.isAlive) {
                    process.destroy()
                    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                }
                process.waitFor()
            }
        }
    }

    private companion object {
        const val VLC = "org.videolan.vlc"
        /** How a bitmap's pixels lie in memory under Robolectric's native graphics: blue first. */
        const val BYTE_ORDER = "bgra"
        /** Where the song is as the words show: just before "네게 모두 말하고 싶어 baby". */
        const val LYRICS_AT = 49_000L
        /** How far into the visuals the store's still is. */
        const val STILL_VISUAL_FRAME = 180
    }
}

class ShotsEn : Shots("en")
@Config(qualifiers = "+de")
class ShotsDe : Shots("de")
@Config(qualifiers = "+es")
class ShotsEs : Shots("es")
@Config(qualifiers = "+fr")
class ShotsFr : Shots("fr")
@Config(qualifiers = "+hi")
class ShotsHi : Shots("hi")
@Config(qualifiers = "+in")
class ShotsId : Shots("id")
@Config(qualifiers = "+it")
class ShotsIt : Shots("it")
@Config(qualifiers = "+ja")
class ShotsJa : Shots("ja")
@Config(qualifiers = "+ko")
class ShotsKo : Shots("ko")
@Config(qualifiers = "+nl")
class ShotsNl : Shots("nl")
@Config(qualifiers = "+pl")
class ShotsPl : Shots("pl")
@Config(qualifiers = "+pt-rBR")
class ShotsPtBr : Shots("pt-br")
@Config(qualifiers = "+ru")
class ShotsRu : Shots("ru")
@Config(qualifiers = "+tr")
class ShotsTr : Shots("tr")
@Config(qualifiers = "+zh-rCN")
class ShotsZhCn : Shots("zh-cn")
@Config(qualifiers = "+zh-rTW")
class ShotsZhTw : Shots("zh-tw")

// Explicit qualifiers select light Android resources before the activity is created.
@Config(qualifiers = "+notnight")
class ShotsEnLight : Shots("en", darkTheme = false)
@Config(qualifiers = "+de-notnight")
class ShotsDeLight : Shots("de", darkTheme = false)
@Config(qualifiers = "+es-notnight")
class ShotsEsLight : Shots("es", darkTheme = false)
@Config(qualifiers = "+fr-notnight")
class ShotsFrLight : Shots("fr", darkTheme = false)
@Config(qualifiers = "+hi-notnight")
class ShotsHiLight : Shots("hi", darkTheme = false)
@Config(qualifiers = "+in-notnight")
class ShotsIdLight : Shots("id", darkTheme = false)
@Config(qualifiers = "+it-notnight")
class ShotsItLight : Shots("it", darkTheme = false)
@Config(qualifiers = "+ja-notnight")
class ShotsJaLight : Shots("ja", darkTheme = false)
@Config(qualifiers = "+ko-notnight")
class ShotsKoLight : Shots("ko", darkTheme = false)
@Config(qualifiers = "+nl-notnight")
class ShotsNlLight : Shots("nl", darkTheme = false)
@Config(qualifiers = "+pl-notnight")
class ShotsPlLight : Shots("pl", darkTheme = false)
@Config(qualifiers = "+pt-rBR-notnight")
class ShotsPtBrLight : Shots("pt-br", darkTheme = false)
@Config(qualifiers = "+ru-notnight")
class ShotsRuLight : Shots("ru", darkTheme = false)
@Config(qualifiers = "+tr-notnight")
class ShotsTrLight : Shots("tr", darkTheme = false)
@Config(qualifiers = "+zh-rCN-notnight")
class ShotsZhCnLight : Shots("zh-cn", darkTheme = false)
@Config(qualifiers = "+zh-rTW-notnight")
class ShotsZhTwLight : Shots("zh-tw", darkTheme = false)
