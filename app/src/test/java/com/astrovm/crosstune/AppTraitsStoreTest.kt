package com.astrovm.crosstune

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What practice remembers about each music app: kept apart per app, believed as long as it should be, and lasting. */
@RunWith(RobolectricTestRunner::class)
class AppTraitsStoreTest {
    private val preferences = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("traits-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    private var clock = 5_000L
    private val store = AppTraitsStore(preferences) { clock }

    @Test
    fun anAppNotSeenHasNothingKnownAboutIt() {
        assertEquals(AppTraits(), store.of("com.example"))
        assertEquals(null, store.of("com.example").keeps)
        assertFalse(store.of("com.example").works)
        assertFalse(store.of("com.example").fails)
    }

    @Test
    fun whatAnAppDoesIsKeptApartFromTheOthersAndAfterARestart() {
        store.seeks("a", true)
        store.keeps("a", true)
        store.seeks("b", false)
        assertEquals(AppTraits(seeks = true, keeps = true), store.of("a"))
        assertTrue(store.of("a").works)
        assertEquals(AppTraits(seeks = false), store.of("b"))
        assertTrue(store.of("b").fails)
        assertFalse(store.of("b").works)
        // A new store over the same preferences reads the same.
        assertEquals(AppTraits(seeks = true, keeps = true), AppTraitsStore(preferences) { clock }.of("a"))
    }

    @Test
    fun anAppSeenNotToWorkIsTriedAgainAfterAWeekButOneSeenToWorkIsBelievedUntilItFails() {
        store.seeks("a", true)
        store.keeps("a", false)
        store.seeks("b", true)
        store.keeps("b", true)
        clock += AppTraitsStore.FORGET_AFTER_MS - 1
        assertTrue(store.of("a").fails)
        clock += 1
        assertEquals(AppTraits(seeks = true), store.of("a"))
        assertEquals(AppTraits(seeks = true, keeps = true), store.of("b"))
        // Seen again, it's remembered as of then.
        store.keeps("a", false)
        assertEquals(AppTraits(seeks = true, keeps = false), store.of("a"))
        assertEquals(false, store.of("a").keeps)
    }

    @Test
    fun whatCantBeReadIsNothingKnown() {
        preferences.edit().putString("app_traits", "not json").commit()
        assertEquals(AppTraits(), store.of("a"))
        store.seeks("a", true)
        assertEquals(AppTraits(seeks = true), store.of("a"))
    }
}
