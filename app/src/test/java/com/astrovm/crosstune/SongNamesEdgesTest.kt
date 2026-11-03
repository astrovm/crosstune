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
    fun aNameWrittenBothWaysApartByAWideSpaceGoesByEachWay() {
        // As YouTube Music names Anri's song: both ways, then its romaji and English.
        val anri = "悲しみがとまらない　I CAN'T STOP THE LONELINESS - Kanashimi ga Tomaranai I CAN'T STOP THE LONELINESS"
        assertTrue("I CAN'T STOP THE LONELINESS" in SongNames.titleNames(anri))
        assertTrue("悲しみがとまらない" in SongNames.titleNames(anri))
        assertEquals(listOf("夜に駆ける　Racing into the Night", "夜に駆ける", "Racing into the Night"), SongNames.titleNames("夜に駆ける　Racing into the Night"))
        // Only Japanese on both sides, or the other side with no letters, or Latin letters first, it's one name.
        assertEquals(listOf("君の　名は"), SongNames.titleNames("君の　名は"))
        assertEquals(listOf("夜に駆ける　2020"), SongNames.titleNames("夜に駆ける　2020"))
        assertEquals(listOf("Racing into the Night　夜に駆ける"), SongNames.titleNames("Racing into the Night　夜に駆ける"))
        assertEquals(listOf("夜に　駆ける　Racing"), SongNames.titleNames("夜に　駆ける　Racing"))
        // Nothing between two marks is no way of its own.
        assertEquals(listOf("夜に駆ける　　Racing", "夜に駆ける", "Racing"), SongNames.titleNames("夜に駆ける　　Racing"))
        assertEquals(listOf("Racing - "), SongNames.titleNames("Racing - "))
    }

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
        // Said in a note this doesn't know the words of, it's still a note.
        assertTrue(SongNames.wordsMatch("小幸运 (我的少女时代)", "小幸运"))
        assertFalse(SongNames.wordsMatch("小幸运", "別の歌"))
        // Nothing but marks has no letters to be the same by.
        assertFalse(SongNames.wordsMatch("♪", "♪"))
        assertFalse(SongNames.wordsMatch("♪ (Live)", "♪♪"))
    }
}
