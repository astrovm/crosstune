package com.astrovm.crosstune

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DestinationStoreTest {

    private val preferences = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("destinations_test", Context.MODE_PRIVATE)
    private val store = DestinationStore(preferences)
    private val deezer = Destination.Service(MusicService.DEEZER)

    @Test
    fun defaultsToYouTubeMusicAndKeepsTheOldPreferenceFormat() {
        assertEquals(Destination.Service(MusicService.YOUTUBE_MUSIC), store.defaultDestination())
        // Older versions stored the service name under the same key.
        preferences.edit { putString("default_target", "TIDAL") }
        assertEquals(Destination.Service(MusicService.TIDAL), store.defaultDestination())
        preferences.edit { putString("default_target", "custom:missing") }
        assertEquals(Destination.Service(MusicService.YOUTUBE_MUSIC), store.defaultDestination())
    }

    @Test
    fun customDestinationsCanBeDefaultsAndRulesUntilRemoved() {
        val invidious = store.addCustom("  Invidious ", " https://yewtu.be/search?q={query} ")
        assertEquals("Invidious", invidious.name)
        assertEquals(MusicService.entries.map(Destination::Service) + invidious, store.allDestinations())

        store.setDefault(invidious)
        store.setRule(MusicService.YOUTUBE, invidious)
        store.setRule(MusicService.SPOTIFY, deezer)
        assertEquals(invidious, store.defaultDestination())
        assertEquals(invidious, store.rule(MusicService.YOUTUBE))

        store.removeCustom(invidious)
        assertTrue(store.customDestinations().isEmpty())
        assertEquals(Destination.Service(MusicService.YOUTUBE_MUSIC), store.defaultDestination())
        assertNull(store.rule(MusicService.YOUTUBE))
        assertEquals(deezer, store.rule(MusicService.SPOTIFY))

        store.setRule(MusicService.SPOTIFY, null)
        assertNull(store.rule(MusicService.SPOTIFY))
    }

    @Test
    fun customSearchUrlsEncodeTheQuery() {
        val custom = Destination.Custom("1", "Web", "https://duckduckgo.com/?q={query}+lyrics")
        assertEquals("https://duckduckgo.com/?q=Rock%20%26%20Roll%20AC%2FDC+lyrics", custom.searchUrl("Rock & Roll AC/DC"))
        assertEquals("custom:1", custom.key)
        assertEquals("DEEZER", deezer.key)
        assertEquals("https://www.deezer.com/search/Song", deezer.searchUrl("Song"))
    }

    @Test
    fun templatesNeedAPlaceholderAndAScheme() {
        assertTrue(Destination.isValidTemplate("https://example.com/?q={query}"))
        assertTrue(Destination.isValidTemplate("vlc://search/{query}"))
        assertFalse(Destination.isValidTemplate("https://example.com/"))
        assertFalse(Destination.isValidTemplate("example.com/?q={query}"))
    }

    @Test
    fun corruptOrInvalidCustomEntriesAreIgnored() {
        preferences.edit { putString("custom_destinations", "{broken") }
        assertTrue(store.customDestinations().isEmpty())

        preferences.edit {
            putString(
                "custom_destinations",
                """[{"id":"","name":"n","template":"https://x/{query}"},{"id":"a","name":"","template":"https://x/{query}"},
                   {"id":"b","name":"n","template":"https://x/"},"text",{"id":"c","name":"Kept","template":"https://x/{query}"}]"""
            )
        }
        assertEquals(listOf(Destination.Custom("c", "Kept", "https://x/{query}")), store.customDestinations())
    }
}
