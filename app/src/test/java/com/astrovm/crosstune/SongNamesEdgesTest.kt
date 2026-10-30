package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The edges of comparing names: Android versions that can't write kanji as China does, names with no letters, and credits that hold each other. Runs under Robolectric for the Android version. */
@RunWith(RobolectricTestRunner::class)
class SongNamesEdgesTest {

    @Test
    fun kanjiAreWrittenAsChinaDoesOnlyInNamesThatHaveSome() {
        assertEquals("终わり", SongNames.asChinaWrites("終わり"))
        assertEquals("Ending", SongNames.asChinaWrites("Ending"))
    }

    @Test
    @Config(sdk = [28])
    fun beforeAndroid10KanjiStayAsTheyAre() {
        assertEquals("終わり", SongNames.asChinaWrites("終わり"))
    }

    @Test
    fun aVersionNamedOnlyInAnotherScriptSaysNothingToMatchOn() {
        // The tag has no letters or digits to find in the other name, so it can't be said to be the same.
        assertFalse(SongNames.sameVersion("Song (リミックス)", "Song (Live)"))
    }

    @Test
    fun aCreditHoldingAnotherIsTheSameArtistFromEitherSide() {
        assertTrue(SongNames.sameArtist("Randy Nota Loca", "Randy"))
        assertTrue(SongNames.sameArtist("Randy", "Randy Nota Loca"))
        // Too short a name inside another says nothing.
        assertFalse(SongNames.sameArtist("Ana Gabriel", "Ana"))
        assertFalse(SongNames.sameArtist("Ana", "Ana Gabriel"))
        assertFalse(SongNames.sameArtist("Bad Bunny", "Anuel"))
        // Nothing to compare with.
        assertTrue(SongNames.sameArtist("Bad Bunny", ""))
    }

    @Test
    fun anArtistIsInsideACreditWhenOneHoldsTheOther() {
        assertTrue(SongNames.artistInside("Randy", "Randy Nota Loca"))
        assertTrue(SongNames.artistInside("Randy Nota Loca", "Randy"))
        assertFalse(SongNames.artistInside("Ana", "Ana Gabriel"))
        assertFalse(SongNames.artistInside("Anuel", "Bad Bunny"))
        // No name to look for is in nothing; nothing to look in has everything.
        assertFalse(SongNames.artistInside("", "Bad Bunny"))
        assertTrue(SongNames.artistInside("Bad Bunny", ""))
    }

    @Test
    fun titlesWithoutLettersAreTheSameByTheirNotesAlone() {
        assertTrue(SongNames.wordsMatch("小幸运 (电影《我的少女时代》主题曲)", "小幸运"))
        assertFalse(SongNames.wordsMatch("小幸运", "別の歌"))
        // Nothing but marks has no letters to be the same by.
        assertFalse(SongNames.wordsMatch("♪", "♪"))
        assertFalse(SongNames.wordsMatch("♪ (Live)", "♪♪"))
    }
}
