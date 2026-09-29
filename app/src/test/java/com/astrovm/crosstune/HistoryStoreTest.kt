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

    private fun entry(n: Int, type: SpotifyType = SpotifyType.TRACK) = HistoryEntry(
        SpotifyItem(type, "id".padEnd(20, '0') + n.toString().padStart(2, '0')),
        SpotifyMetadata("Title $n", "Artist $n", type)
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
    fun roundTripsEveryItemType() {
        SpotifyType.entries.forEachIndexed { index, type -> store.add(entry(index, type)) }
        assertEquals(SpotifyType.entries.reversed(), store.load().map { it.item.type })
    }

    @Test
    fun clearRemovesEverything() {
        store.add(entry(1))
        store.clear()
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun corruptOrPartialDataIsIgnored() {
        preferences.edit { putString("history", "{not an array") }
        assertTrue(store.load().isEmpty())

        preferences.edit {
            putString(
                "history",
                """[{"type":"PODCAST","id":"x","title":"t"},{"type":"TRACK","id":"","title":"t"},
                   {"type":"TRACK","id":"x","title":""},"text",
                   {"type":"TRACK","id":"x","title":"Kept","artist":"A"}]"""
            )
        }
        assertEquals(
            listOf(HistoryEntry(SpotifyItem(SpotifyType.TRACK, "x"), SpotifyMetadata("Kept", "A"))),
            store.load()
        )
    }
}
