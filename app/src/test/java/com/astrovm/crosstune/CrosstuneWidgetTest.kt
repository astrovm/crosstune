package com.astrovm.crosstune

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasAnyDescendant
import androidx.glance.testing.unit.hasContentDescription
import androidx.glance.testing.unit.hasText
import androidx.glance.text.Text
import androidx.glance.unit.ResourceColorProvider
import androidx.test.core.app.ApplicationProvider
import com.astrovm.crosstune.ui.theme.LightColorScheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class CrosstuneWidgetTest {

    private val app: Context = ApplicationProvider.getApplicationContext()
    private val song = WidgetSong("Song", "Artist · Spotify", "https://open.spotify.com/track/1", null)
    private val paste = Intent(MainActivity.ACTION_PASTE_FROM_CLIPBOARD).setClassName(app, MainActivity.PASTE_ALIAS)
    private val withCover = song.copy(title = "Covered", subtitle = "Band · Deezer", url = "https://open.spotify.com/track/2", cover = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))

    @Before
    fun clearHistory() {
        app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun widget(size: DpSize, songs: List<WidgetSong>, block: GlanceAppWidgetUnitTest.() -> Unit) =
        runGlanceAppWidgetUnitTest {
            setContext(app)
            setAppWidgetSize(size)
            provideComposable { WidgetContent(songs) }
            block()
        }

    @Test
    fun recognizedSongWidgetUsesArtistWithoutASourceService() {
        val url = "https://www.google.com/search?q=A%20Song%20by%20Example%20Band"
        val preferences = app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)
        HistoryStore(preferences).add(HistoryEntry(MusicLink(null, ItemType.TRACK, url, url), MusicMetadata("A Song", "Example Band")))
        val loaded = runBlocking { CrosstuneWidget.widgetSongs(app).first() }
        assertEquals("A Song", loaded.title)
        assertEquals("Example Band", loaded.subtitle)
        assertEquals(url, loaded.url)
    }

    @Test
    fun oneRowTallItsJustTheButtonForTheCopiedLink() = widget(CrosstuneWidget.PILL, listOf(song)) {
        onNode(hasText("Song")).assertDoesNotExist()
        onAllNodes(hasAnyDescendant(hasText(app.getString(R.string.shortcut_paste_short)))).assertAny(hasStartActivityClickAction(paste))
    }

    @Test
    fun wideItShowsRecentWithEachSongsActions() = widget(CrosstuneWidget.LIST, listOf(song, withCover)) {
        onNode(hasText(app.getString(R.string.app_name))).assertExists()
        onAllNodes(hasAnyDescendant(hasContentDescription(app.getString(R.string.shortcut_paste_long)))).assertAny(hasStartActivityClickAction(paste))
        onNode(hasText("Artist · Spotify")).assertExists()
        onNode(hasText("Covered")).assertExists()
        // ▶ opens the song in the user's app; tapping the rest of the row shows it in Crosstune.
        onAllNodes(hasAnyDescendant(hasContentDescription(app.getString(R.string.history_open, "Song"))))
            .assertAny(hasStartActivityClickAction(Intent(MainActivity.ACTION_OPEN_RECENT, song.url.toUri()).setClass(app, MainActivity::class.java)))
        onAllNodes(hasAnyDescendant(hasText("Song")))
            .assertAny(hasStartActivityClickAction(Intent(Intent.ACTION_VIEW, song.url.toUri()).setClass(app, MainActivity::class.java)))
    }

    @Test
    fun wideItNamesASongNearbyAndItsNameOpensTheApp() = widget(CrosstuneWidget.LIST, listOf(song)) {
        onAllNodes(hasAnyDescendant(hasContentDescription(app.getString(R.string.recognize_button)))).assertAny(hasStartActivityClickAction(Intent(app, RecognizeSongActivity::class.java)))
        onAllNodes(hasAnyDescendant(hasText(app.getString(R.string.app_name))))
            .assertAny(hasStartActivityClickAction(Intent(app, MainActivity::class.java)))
    }

    @Test
    fun twoColumnsWideTheSongsTakeTheWholeRow() = widget(CrosstuneWidget.NARROW_LIST, listOf(song)) {
        // Only the copied link's button fits beside the logo.
        onNode(hasContentDescription(app.getString(R.string.recognize_button))).assertDoesNotExist()
        onNode(hasText("Song")).assertExists()
        onNode(hasText(app.getString(R.string.app_name))).assertDoesNotExist()
        onNode(hasContentDescription(app.getString(R.string.history_open, "Song"))).assertDoesNotExist()
        onNode(hasContentDescription(app.getString(R.string.shortcut_paste_long))).assertExists()
    }

    @Test
    fun withoutRecentItSaysHowToStart() = widget(CrosstuneWidget.LIST, emptyList()) {
        onNode(hasText(app.getString(R.string.empty_hint))).assertExists()
    }

    @Test
    fun itFollowsTheWallpaperColors() = runGlanceAppWidgetUnitTest {
        setContext(app)
        provideComposable {
            CrosstuneWidget.WidgetTheme {
                Text(if (GlanceTheme.colors.primary is ResourceColorProvider) "dynamic" else "own")
            }
        }
        onNode(hasText("dynamic")).assertExists()
    }

    @Test
    @Config(sdk = [30])
    fun beforeAndroid12ItUsesTheAppsOwnColors() = runGlanceAppWidgetUnitTest {
        setContext(app)
        provideComposable {
            CrosstuneWidget.WidgetTheme {
                Text("${GlanceTheme.colors.primary.getColor(LocalContext.current).toArgb()}")
            }
        }
        onNode(hasText("${LightColorScheme.primary.toArgb()}")).assertExists()
    }

    @Test
    fun recentComesFromTheAppsHistoryWithItsSavedCovers() = runBlocking {
        val cover = "https://example.com/cover.png"
        File(app.cacheDir, "artwork").apply { mkdirs() }.resolve(sha256(cover)).outputStream().use {
            Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val store = HistoryStore(app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE))
        store.add(HistoryEntry(MusicLink(MusicService.DEEZER, ItemType.ALBUM, "2", "https://www.deezer.com/album/2"), MusicMetadata("Album", "", ItemType.ALBUM, artworkUrl = "not a link")))
        store.add(HistoryEntry(MusicLink(MusicService.SPOTIFY, ItemType.TRACK, "1", "https://open.spotify.com/track/1"), MusicMetadata("Track", "Band", artworkUrl = cover)))

        val songs = CrosstuneWidget.widgetSongs(app)

        assertEquals(listOf("Track", "Album"), songs.map { it.title })
        assertEquals("Band · Spotify", songs[0].subtitle)
        assertEquals("${app.getString(R.string.type_album)} · Deezer", songs[1].subtitle)
        assertEquals(CrosstuneWidget.COVER_PIXELS, songs[0].cover!!.width)
        assertNull(songs[1].cover)
    }

    @Test
    fun theWidgetDrawsForTheLauncher() = runBlocking {
        HistoryStore(app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE))
            .add(HistoryEntry(MusicLink(MusicService.SPOTIFY, ItemType.TRACK, "1", "https://open.spotify.com/track/1"), MusicMetadata("Track", "Band")))
        assertNotNull(CrosstuneWidgetReceiver().glanceAppWidget.compose(app, size = CrosstuneWidget.LIST))
        assertTrue(CrosstuneWidgetReceiver().glanceAppWidget is CrosstuneWidget)
    }

    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
