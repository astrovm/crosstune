package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the screens are handed is stable to Compose, so a screen handed a new copy equal to the last
 * skips rather than composing again for nothing. A list, map or set in a class made Compose call it
 * unstable, and so every class holding it, up to UiState, and compare them only by identity
 * (Issue #884). app/compose-stability.conf says they're stable.
 */
class StableStateTest {

    /** What the Compose compiler worked out for [type]: 0 is stable, anything else isn't; null for a type it didn't look at. */
    private fun stability(type: Class<*>) = runCatching { type.getField("\$stable").getInt(null) }.getOrNull()

    @Test
    fun theStateTheMainScreenThePlayerAndTheLyricsAreHandedIsStable() {
        val handed = listOf(
            UiState::class, ScreenActions::class, NowPlaying::class, Following::class, PlayerState::class,
            HistoryEntry::class, MusicMetadata::class, LyricLine::class
        )
        assertEquals(handed.map { it.simpleName to 0 }, handed.map { it.simpleName to stability(it.java) })
    }

    @Test
    fun everythingInTheStateIsStable() {
        val unstable = UiState::class.java.declaredFields.filter { (stability(it.type) ?: 0) != 0 }.map { "${it.name}: ${it.type.simpleName}" }
        assertEquals(emptyList<String>(), unstable)
    }
}
