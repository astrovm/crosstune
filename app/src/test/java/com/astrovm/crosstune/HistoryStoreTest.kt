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
    fun whatsReadIsKeptUntilAnotherWritesInstead() {
        store.add(entry(1))
        val first = store.load()
        // Read again unchanged, it's the same list, not read anew.
        assertTrue(first === store.load())
        // Written elsewhere, e.g. undone from another screen, it's read anew.
        HistoryStore(preferences).add(entry(2))
        assertEquals(listOf(entry(2), entry(1)), store.load())
        preferences.edit().clear().commit()
        assertEquals(emptyList<HistoryEntry>(), store.load())
    }

    @Test
    fun theSameSongLinkedAnotherWayIsOneEntry() {
        val fromAlbum = HistoryEntry(
            MusicLink(MusicService.APPLE_MUSIC, ItemType.TRACK, "1060484510", "https://music.apple.com/us/album/dreams-come-true/1060484503?i=1060484510"),
            MusicMetadata("Dreams Come True", "S.E.S.")
        )
        val onItsOwn = fromAlbum.copy(link = fromAlbum.link.copy(url = "https://music.apple.com/us/song/1060484510"))
        store.add(fromAlbum)
        store.add(entry(1))
        store.add(onItsOwn)
        assertEquals(listOf(onItsOwn, entry(1)), HistoryStore(preferences).load())

        // The same id on another service, or of another kind, is something else.
        store.add(entry(1, service = MusicService.SPOTIFY).let { it.copy(metadata = it.metadata.copy(title = "Another Song")) })
        store.add(entry(1, type = ItemType.ALBUM))
        assertEquals(4, HistoryStore(preferences).load().size)
    }

    @Test
    fun oneSongFromTwoAppsOrSearchedForIsOneEntry() {
        val searched = "https://www.google.com/search?q=Plastic%20Love"
        val deezer = HistoryEntry(MusicLink(MusicService.DEEZER, ItemType.TRACK, "1", "https://www.deezer.com/track/1"), MusicMetadata("Plastic Love", "Mariya Takeuchi"))
        val apple = HistoryEntry(MusicLink(MusicService.APPLE_MUSIC, ItemType.TRACK, "2", "https://music.apple.com/song/2"), MusicMetadata("Plastic love", "Mariya Takeuchi"))
        val found = HistoryEntry(MusicLink(null, ItemType.TRACK, searched, searched), MusicMetadata("Plastic Love", "Mariya Takeuchi"))
        store.add(deezer)
        store.add(entry(1))
        store.add(apple)
        store.add(found)
        assertEquals(listOf(found, entry(1)), HistoryStore(preferences).load())
        // Another artist's song of that name, an album of it, or a name with no letters to tell, are others.
        val cover = found.copy(link = found.link.copy(url = "$searched%20cover", id = "cover"), metadata = found.metadata.copy(artist = "Someone"))
        val album = HistoryEntry(MusicLink(MusicService.DEEZER, ItemType.ALBUM, "3", "https://www.deezer.com/album/3"), MusicMetadata("Plastic Love", "Mariya Takeuchi", ItemType.ALBUM))
        val noLetters = HistoryEntry(MusicLink(MusicService.DEEZER, ItemType.TRACK, "4", "https://www.deezer.com/track/4"), MusicMetadata("!!!", "Mariya Takeuchi"))
        val noLettersEither = HistoryEntry(MusicLink(MusicService.DEEZER, ItemType.TRACK, "5", "https://www.deezer.com/track/5"), MusicMetadata("???", "Mariya Takeuchi"))
        listOf(cover, album, noLetters, noLettersEither).forEach(store::add)
        assertEquals(6, HistoryStore(preferences).load().size)

        // Kept twice by an older version, it's listed once, the latest.
        val older = org.json.JSONArray(preferences.getString("history", null))
        preferences.edit { putString("history", org.json.JSONArray().put(older.get(0)).put(older.get(0)).put(older.get(4)).toString()) }
        assertEquals(listOf(noLettersEither, found), HistoryStore(preferences).load())
    }

    @Test
    fun twoVersionsOfASongOnOneServiceAreTwoEntries() {
        // Another upload or release of it, timed and sounding its own way, as YouTube Music has.
        val one = HistoryEntry(MusicLink(MusicService.YOUTUBE_MUSIC, ItemType.TRACK, "aaaaaaaaaaa", "https://music.youtube.com/watch?v=aaaaaaaaaaa"), MusicMetadata("Plastic Love", "Mariya Takeuchi"))
        val other = HistoryEntry(MusicLink(MusicService.YOUTUBE_MUSIC, ItemType.TRACK, "bbbbbbbbbbb", "https://music.youtube.com/watch?v=bbbbbbbbbbb"), MusicMetadata("Plastic Love", "Mariya Takeuchi"))
        store.add(one)
        store.add(other)
        assertEquals(listOf(other, one), HistoryStore(preferences).load())
    }

    @Test
    fun twoVersionsOfASongKnownByNameAreTwoEntries() {
        // A 2:54 single and a 4:22 album take of one name, each heard as an app played it.
        fun named(lengthMs: Long?) = MusicMetadata("Plastic Love", "Mariya Takeuchi", lengthMs = lengthMs).let { song ->
            MusicLinks.recognizedSong(song).let { HistoryEntry(MusicLink(null, ItemType.TRACK, it.url, it.url), song) }
        }
        val single = named(174_000)
        val album = named(262_000)
        store.add(named(null))
        store.add(single)
        store.add(album)
        assertEquals(listOf(album, single), HistoryStore(preferences).load())
        // Each keeps the link it opened in, as each is another recording there.
        val opened = PreparedLink("https://music.youtube.com/watch?v=aaaaaaaaaaa", true, true)
        store.remember(single.link.url, "YOUTUBE_MUSIC", opened)
        assertEquals(listOf(album, single.copy(destinationLinks = mapOf("YOUTUBE_MUSIC" to opened))), HistoryStore(preferences).load())
        // Said a second longer by another app, it's still the single.
        val again = named(175_000)
        store.add(again)
        assertEquals(listOf(again, album), HistoryStore(preferences).load())
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
