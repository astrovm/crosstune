package com.astrovm.crosstune

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryStoreTest {

    private val preferences = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("history_test", Context.MODE_PRIVATE)
    private val store = HistoryStore(preferences)

    private fun entry(n: Int, type: ItemType = ItemType.TRACK, service: MusicService = MusicService.DEEZER) =
        HistoryEntry(
            MusicLink(service, type, "$n", "https://example.com/${service.name}/$type/$n"),
            MusicMetadata("Title $n", "Artist $n", type)
        )

    @Test
    fun keepsNewestFirstWithoutDuplicatesAndCapsAtTwenty() {
        (1..25).forEach { store.add(entry(it)) }
        store.add(entry(10))

        val loaded = HistoryStore(preferences).load()
        assertEquals(20, loaded.size)
        assertEquals(entry(10), loaded.first())
        assertEquals(entry(25), loaded[1])
        assertEquals(1, loaded.count { it == entry(10) })
    }

    @Test
    fun roundTripsEveryItemTypeServiceAndRegion() {
        ItemType.entries.forEachIndexed { index, type -> store.add(entry(index, type)) }
        val apple = HistoryEntry(
            MusicLink(MusicService.APPLE_MUSIC, ItemType.TRACK, "1", "https://music.apple.com/ar/song/x/1", "ar"),
            MusicMetadata("Song", "Artist", artworkUrl = "https://is1-ssl.mzstatic.com/image/thumb/a/600x600bb.jpg")
        )
        store.add(apple)

        val loaded = store.load()
        assertEquals(apple, loaded.first())
        assertEquals(ItemType.entries.reversed(), loaded.drop(1).map { it.link.type })
    }

    @Test
    fun cachedDestinationsRoundTripWithoutReorderingOrRestoringClearedHistory() {
        preferences.edit { remove("history") }
        val first = entry(1)
        val second = entry(2)
        store.add(first)
        store.add(second)
        val exact = PreparedLink("https://music.youtube.com/watch?v=remembered", exact = true, matchingEnabled = true)
        val search = PreparedLink("player://search/Title%201", exact = false, matchingEnabled = false)
        store.remember(first.link.url, "YOUTUBE_MUSIC", exact)
        store.remember(first.link.url, "custom:player", search)
        assertEquals(listOf(second, first.copy(destinationLinks = mapOf("YOUTUBE_MUSIC" to exact, "custom:player" to search))), HistoryStore(preferences).load())
        store.clear()
        assertTrue(store.remember(first.link.url, "YOUTUBE_MUSIC", exact).isEmpty())
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun corruptCachedDestinationsDoNotLoseTheHistoryEntry() {
        preferences.edit {
            putString("history", """[{"type":"TRACK","id":"1","title":"Kept","destinations":{"bad":"text","blank":{"url":""},"good":{"url":"https://example.com/search","exact":false,"matchingEnabled":true}}}]""")
        }
        assertEquals(mapOf("good" to PreparedLink("https://example.com/search", false, true)), store.load().single().destinationLinks)
    }

    @Test
    fun clearRemovesEverything() {
        store.add(entry(1))
        store.clear()
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun readsEntriesSavedBeforeMultiServiceSupportAsSpotify() {
        preferences.edit {
            putString("history", """[{"type":"ALBUM","id":"4yP0hdKOZPNshxUOjY0cZj","title":"After Hours","artist":"The Weeknd"}]""")
        }
        val link = MusicLink(
            MusicService.SPOTIFY, ItemType.ALBUM, "4yP0hdKOZPNshxUOjY0cZj",
            "https://open.spotify.com/album/4yP0hdKOZPNshxUOjY0cZj"
        )
        assertEquals(listOf(HistoryEntry(link, MusicMetadata("After Hours", "The Weeknd", ItemType.ALBUM))), store.load())
    }

    @Test
    fun corruptOrPartialDataIsIgnored() {
        preferences.edit { putString("history", "{not an array") }
        assertTrue(store.load().isEmpty())

        preferences.edit {
            putString(
                "history",
                """[{"type":"PODCAST","id":"x","title":"t"},{"type":"TRACK","id":"","title":"t"},
                   {"type":"TRACK","id":"x","title":""},{"service":"NAPSTER","type":"TRACK","id":"x","title":"t"},"text",
                   {"service":"TIDAL","type":"TRACK","id":"x","url":"https://tidal.com/track/x","title":"Kept","artist":"A"}]"""
            )
        }
        assertEquals(
            listOf(HistoryEntry(MusicLink(MusicService.TIDAL, ItemType.TRACK, "x", "https://tidal.com/track/x"), MusicMetadata("Kept", "A"))),
            store.load()
        )
    }
}
