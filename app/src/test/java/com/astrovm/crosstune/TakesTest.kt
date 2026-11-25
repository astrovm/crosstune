package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TakesTest {

    @Test
    fun mixNotesUseCompatibilitySpellingWithoutTreatingSongNamesAsNotes() {
        val notes = listOf(
            "Song (Disco Version)", "Song - Club Remix", "Song （Ｄｉｓｃｏ　Ｍｉｘ）", "Song (DISCOバージョン)",
            "Song (ディスコ版)", "Song (ﾃﾞｨｽｺ･ﾊﾞｰｼﾞｮﾝ)", "Song (テ\u3099ィスコ・ハ\u3099ーシ\u3099ョン)",
            "Song (ディスコ・ヴァージョン)", "Song (プロモーション用ディスコバージョン)"
        )
        for (name in notes) {
            assertTrue(name, Takes.namesMix(name))
            assertTrue(name, Takes.albumNamesMix(name))
        }
        assertTrue(Takes.albumNamesMix("Disco Mix"))
        val names = listOf("Disco", "Disco Nights", "Disco Inferno", "ディスコ・クイーン", "Song (Deluxe Edition)", "Song (Disco)")
        for (name in names) {
            assertFalse(name, Takes.namesMix(name))
            assertFalse(name, Takes.albumNamesMix(name))
        }
        assertFalse(Takes.namesMix("Disco Version"))
    }

    @Test
    fun aTagThatStartsAsAnotherTakeSaysSo() {
        val live = listOf(
            "偽の歌 (ライブ)", "偽の歌 (ライヴ)", "偽の歌 (2019 ライブ)", "偽の歌 (Live from Budokan)", "偽の歌 - Live", "偽の歌 [Live]",
            "偽の歌 (现场版)", "偽の歌 (라이브)", "偽の歌 (En Vivo)", "偽の歌 （ライブ）", "偽の歌 【ライブ】", "偽の歌 (1999 Live)"
        )
        for (name in live) assertEquals(name, Takes.Take.LIVE, Takes.saysAnotherTake(name))
        val other = listOf(
            "偽の歌 (オフ・ヴォーカル)", "偽の歌 (オフボーカル)", "偽の歌 (Off Vocal)", "偽の歌 (TV Size)", "偽の歌 (TV Edit)", "偽の歌 (OP Size)", "偽の歌 (ED Size)",
            "偽の歌 (ショートバージョン)", "偽の歌 (Short)", "偽の歌 (カラオケ)", "偽の歌 (Instrumental)", "偽の歌 (インスト)", "偽の歌 (リミックス)", "偽の歌 (Extended Remix)",
            "偽の歌 (カヴァー)", "偽の歌 (Piano)", "偽の歌 (Acoustic)", "偽の歌 (인스트)", "偽の歌 - Karaoke", "偽の歌 (Demo)"
        )
        for (name in other) assertEquals(name, Takes.Take.OTHER, Takes.saysAnotherTake(name))
        // A live take that is also a remix is the live one, whichever tag comes first.
        assertEquals(Takes.Take.LIVE, Takes.saysAnotherTake("偽の歌 (Remix) (Live)"))
    }

    @Test
    fun aTagThatOnlyHoldsAWordOfATakeSaysNothing() {
        val none = listOf(
            // A show's name in quotes, a credit, a guest, a producer, another song's name and a version no list holds.
            "偽の歌", "偽の歌 (TVアニメ「ライブ」オープニングテーマ)", "偽の歌 - TVアニメ「ライブ」エンディングテーマ", "偽の歌 (with Live Crew)", "偽の歌 (prod. Live Crew)",
            "偽の歌 (Theme from Live Forever)", "偽の歌 (feat. Live Crew)", "偽の歌 (Japanese Version)", "偽の歌 (Single Version)", "偽の歌 (シングル・バージョン)",
            "Alive", "アライヴ", "ライブラリ", "Live Forever", "偽の歌 (2012 Remaster)", "偽の歌 [English Subtitles]", "偽の歌 (", "", "偽の歌 (2019)"
        )
        for (name in none) assertNull(name, Takes.saysAnotherTake(name))
        // The artist's own name is no take either: "(Live Crew)" credited on the row.
        assertNull(Takes.saysAnotherTake("偽の歌 (Live Crew)", listOf("Fake Band & Live Crew")))
        assertEquals(Takes.Take.LIVE, Takes.saysAnotherTake("偽の歌 (Live Crew)"))
        assertEquals(Takes.Take.LIVE, Takes.saysAnotherTake("偽の歌 (Live)", listOf("Fake Band & Live Crew")))
        assertEquals(Takes.Take.LIVE, Takes.saysAnotherTake("偽の歌 (Live Crew Live)", listOf("Fake Band & Live Crew")))
    }

    @Test
    fun aSongsOwnNameMayHoldTheWordLiveButNoOtherVersionWord() {
        assertFalse(Takes.saysAnotherVersionBesidesLive("Live Forever"))
        assertFalse(Takes.saysAnotherVersionBesidesLive("Fake Song"))
        assertTrue(Takes.saysAnotherVersionBesidesLive("Fake Song Acoustic"))
        assertTrue(Takes.saysAnotherVersionBesidesLive("偽の歌 (カラオケ)"))
    }

    @Test
    fun aTagIsReadWhereverItsBracketsAndDashesAre() {
        // A bracket may open the name, close without opening, or hold another.
        assertEquals(Takes.Take.LIVE, Takes.saysAnotherTake("(Live) 偽の歌"))
        assertEquals(Takes.Take.LIVE, Takes.saysAnotherTake("偽の歌) (Live)"))
        assertEquals(Takes.Take.LIVE, Takes.saysAnotherTake("偽の歌 (Live (Remix))"))
        assertNull(Takes.saysAnotherTake("偽の歌 (2019 (Single) Version)"))
        // A dash sets a tag apart only with a space on each side; each tag ends where the next dash does.
        assertEquals(Takes.Take.LIVE, Takes.saysAnotherTake("偽の歌 - Live"))
        assertNull(Takes.saysAnotherTake("偽の歌 -Live"))
        assertNull(Takes.saysAnotherTake("偽の歌- Live"))
        assertNull(Takes.saysAnotherTake("偽の歌 Live - "))
        assertNull(Takes.saysAnotherTake("- Live"))
        assertEquals(Takes.Take.LIVE, Takes.saysAnotherTake("偽の歌 -  - Live"))
        assertEquals(Takes.Take.OTHER, Takes.saysAnotherTake("偽の歌 - Hello Remix - Z"))
        assertEquals(Takes.Take.LIVE, Takes.saysAnotherTake("偽の歌 - Z - Live"))
        assertEquals(Takes.Take.OTHER, Takes.saysAnotherTake("偽の歌 - Short"))
    }
}
