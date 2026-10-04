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
    fun recognizedSongsRoundTripWithPreparedDestinations() {
        val url = "https://www.google.com/search?q=A%20Song%20by%20Example%20Band"
        val song = HistoryEntry(MusicLink(null, ItemType.TRACK, url, url), MusicMetadata("A Song", "Example Band"))
        store.add(song)
        val prepared = PreparedLink("https://music.youtube.com/watch?v=abcdefghijk", true, true)
        store.remember(url, "YOUTUBE_MUSIC", prepared)
        assertEquals(song.copy(destinationLinks = mapOf("YOUTUBE_MUSIC" to prepared)), HistoryStore(preferences).load().first())
    }

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
    fun forgettingADestinationDropsOnlyItsLinksFromEveryEntry() {
        preferences.edit { remove("history") }
        val site = PreparedLink("https://yewtu.be/search?q=Title", exact = false, matchingEnabled = false)
        val app = PreparedLink("https://music.youtube.com/watch?v=kept", exact = true, matchingEnabled = true)
        store.add(entry(1))
        store.add(entry(2))
        listOf(entry(1), entry(2)).forEach { store.remember(it.link.url, "frontend:INVIDIOUS", site) }
        store.remember(entry(1).link.url, "YOUTUBE_MUSIC", app)

        val forgotten = store.forget("frontend:INVIDIOUS")
        assertEquals(listOf(entry(2), entry(1).copy(destinationLinks = mapOf("YOUTUBE_MUSIC" to app))), forgotten)
        assertEquals(forgotten, HistoryStore(preferences).load())
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

    @Test
    fun keepsAPlaylistsSongs() {
        val playlist = HistoryEntry(
            MusicLink(MusicService.DEEZER, ItemType.PLAYLIST, "1", "https://www.deezer.com/playlist/1"),
            MusicMetadata("Top", "", ItemType.PLAYLIST, tracks = listOf(MusicMetadata("One", "Band"), MusicMetadata("Two", "")))
        )
        store.add(playlist)
        assertEquals(playlist, HistoryStore(preferences).load().single())

        // Songs saved without a title, or not as a pair, are skipped.
        preferences.edit().putString(
            "history",
            """[{"type":"PLAYLIST","id":"1","title":"Top","service":"DEEZER","url":"https://www.deezer.com/playlist/1","tracks":[["One","Band"],["",""],"x"]}]"""
        ).commit()
        assertEquals(listOf(MusicMetadata("One", "Band")), HistoryStore(preferences).load().single().metadata.tracks)
    }

    @Test
    fun aPlaylistKeepsItsSongsCoversAndLinksAndCanBeUpdatedInPlace() {
        val store = HistoryStore(preferences)
        val link = MusicLink(MusicService.SPOTIFY, ItemType.PLAYLIST, "1", "https://open.spotify.com/playlist/1")
        val song = MusicMetadata("One", "Band", url = "https://open.spotify.com/track/1")
        store.add(HistoryEntry(link, MusicMetadata("Top", "", ItemType.PLAYLIST, tracks = listOf(song), trackCount = 150)))
        store.add(HistoryEntry(MusicLink(MusicService.DEEZER, ItemType.TRACK, "2", "https://www.deezer.com/track/2"), MusicMetadata("Newer", "Band")))
        val covered = MusicMetadata("Top", "", ItemType.PLAYLIST, tracks = listOf(song.copy(artworkUrl = "https://i.scdn.co/image/1")), trackCount = 150)

        // Found covers don't move it up.
        val updated = store.update(link.url, covered)
        assertEquals(listOf("Newer", "Top"), updated.map { it.metadata.title })
        assertEquals(covered, HistoryStore(preferences).load()[1].metadata)
    }
}
