package com.astrovm.crosstune

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ShortcutManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The launcher's shortcuts: the latest songs, and where a shortcut that was used leads. */
@RunWith(RobolectricTestRunner::class)
class AppShortcutsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val covers = mutableListOf<String>()

    private fun song(n: Int, cover: String?) = HistoryEntry(
        MusicLink(MusicService.DEEZER, ItemType.TRACK, "$n", "https://www.deezer.com/track/$n"),
        MusicMetadata("Title $n", "Artist $n", artworkUrl = cover)
    )

    private fun shortcuts(context: Context = app) = AppShortcuts(context) { url -> ImageBitmap(2, 2).also { covers += url } }

    private fun listed() = app.getSystemService(ShortcutManager::class.java).dynamicShortcuts.sortedBy { it.rank }.map { it.shortLabel.toString() }

    @Test
    fun aSongWithACoverAsksForItAndOneWithoutDoesNot() = runBlocking {
        shortcuts().update(listOf(song(1, "https://img.example/1.jpg"), song(2, null)), shareSheet = false, app = null)
        assertEquals(listOf("Title 1", "Title 2"), listed())
        assertEquals(listOf("https://img.example/1.jpg"), covers)
    }

    @Test
    fun withoutAShortcutServiceNothingIsChanged() = runBlocking {
        shortcuts().update(listOf(song(1, null)), shareSheet = false, app = null)
        val without = object : ContextWrapper(app) {
            override fun <T : Any?> getSystemService(serviceClass: Class<T>): T? = null
        }
        shortcuts(without).update(emptyList(), shareSheet = false, app = null)
        assertEquals(listOf("Title 1"), listed())
    }

    @Test
    fun theShareActionAShortcutDoesIsReadFromItsId() {
        fun chosen(id: String?) = AppShortcuts.chosenAction(Intent().apply { if (id != null) putExtra(Intent.EXTRA_SHORTCUT_ID, id) })
        assertEquals(AppShortcuts.ShareAction.SHARE, chosen("action:SHARE"))
        assertEquals(AppShortcuts.ShareAction.SHOW, chosen("action:SHOW"))
        assertNull(chosen("action:LOSE"))
        assertNull(chosen("open_in:DEEZER"))
        assertNull(chosen(null))
    }
}
