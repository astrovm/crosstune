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
    fun aRecordingNoteStartsAtItsOwnWordNotAtALaterLatinWord() {
        val names = listOf("Любовь" to "Любовь моя", "Αγάπη" to "Αγάπη μου", "愛" to "愛 の歌", "사랑" to "사랑 노래")
        for ((one, other) in names) for (tag in listOf("Live", "- Live", "– Live", "— Live", "- Part 2")) {
            assertFalse("$one / $other / $tag", SongNames.wordsMatch("$one $tag", "$other $tag"))
            assertFalse("$other / $one / $tag", SongNames.wordsMatch("$other $tag", "$one $tag"))
        }
    }

    @Test
    fun recordingMarksNeverMakeDifferentSingleWordTitlesSpecificEnough() {
        val brackets = listOf("(" to ")", "[" to "]", "【" to "】", "「" to "」", "〔" to "〕", "（" to "）", "［" to "］")
        val notes = listOf("Live", "Cover", "Part 2", "カバー", "커버")
        val suffixes = brackets.flatMap { (open, close) -> notes.map { "$open$it$close" } } +
            listOf("-", "–", "—").flatMap { dash -> notes.map { "$dash $it" } } + notes
        for (tag in suffixes) {
            assertFalse(tag, SongNames.wordsMatch("Hello $tag", "Hello World $tag"))
            assertFalse(tag, SongNames.wordsMatch("Hello World $tag", "Hello $tag"))
            assertFalse(tag, SongNames.wordsMatch("愛 $tag", "恋 $tag"))
            assertTrue(tag, SongNames.wordsMatch("Hello $tag", "Hello $tag"))
            // Two words in the title itself still permit the established partial-title rule.
            assertTrue(tag, SongNames.wordsMatch("Fake Song $tag", "Fake Song Two $tag"))
        }
    }

    @Test
    fun recordingTagsCannotSupplyTheNamesMissingWords() {
        val rows = listOf(
            Triple("愛 カバー", "恋 カバー", false), Triple("愛【カバー】", "恋【カバー】", false),
            Triple("愛（カバー）", "恋（カバー）", false), Triple("愛【歌ってみた】", "恋【歌ってみた】", false),
            Triple("愛 (歌ってみた)", "恋 (歌ってみた)", false), Triple("사랑 커버", "이별 커버", false),
            Triple("사랑 (커버)", "이별 (커버)", false), Triple("愛 ライブ", "恋 ライブ", false),
            Triple("愛【Cover】", "恋【Cover】", false), Triple("Hello【Live】", "Hello World【Live】", false),
            Triple("Hello（Live）", "Hello World（Live）", false), Triple("Hello (Live)", "Hello World (Live)", false),
            Triple("Hello World (Part 2)", "Hello (Part 2)", false), Triple("愛 (カバー)", "恋 (カバー)", false),
            Triple("ライブ", "Live", false), Triple("アイドル", "アイドル (ライブ)", true),
            Triple("Fake Song", "Fake Song (Live)", true), Triple("Fake Song", "Fake Song【Live】", true),
            Triple("愛「カバー」", "恋「カバー」", false), Triple("愛〔カバー〕", "恋〔カバー〕", false),
            Triple("愛［カバー］", "恋［カバー］", false), Triple("愛 - カバー", "恋 - カバー", false),
            Triple("Hello – Live", "Hello World – Live", false), Triple("Hello — Live", "Hello World — Live", false),
            Triple("Hello (Live)", "Hello", false), Triple("Fake Song (Live)", "Fake Song Two (Live)", true)
        )
        val wrong = mutableListOf<String>()
        for ((one, other, wanted) in rows) for ((title, asked) in listOf(one to other, other to one)) {
            val answers = listOf(
                "words" to SongNames.wordsMatch(title, asked),
                "named" to SongNames.names(title, "Fake Utaite", MusicMetadata(asked, "Fake Utaite")),
                "unnamed" to SongNames.names(title, "", MusicMetadata(asked, "Fake Utaite")),
                "both unnamed" to SongNames.names(title, "", MusicMetadata(asked, ""))
            )
            answers.filter { (_, actual) -> actual != wanted }.forEach { (way, actual) -> wrong += "$way: $title / $asked: $actual, wanted $wanted" }
        }
        assertEquals("${wrong.size} wrong title comparisons", emptyList<String>(), wrong)
    }

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

    @Test
    fun manySongsInOneAreToldByTheirNames() {
        fun many(title: String, artist: String = "Fake Band") = SongNames.manySongs(MusicMetadata(title, artist))
        assertTrue(many("Summer Medley 2024"))
        assertTrue(many("Song One", "[Fan Collab] Fake Mashup 2023"))
        assertTrue(many("Enganchados de Verano"))
        assertTrue(many("夏のメドレー"))
        assertTrue(many("Live in Town 2010 (Recital Completo)"))
        assertTrue(many("Fake Band Full Concert"))
        assertTrue(many("Álbum Completo"))
        assertTrue(many("First Song / Second Song / Third Song"))
        assertTrue(many("Summer_Vibes_Playlist_🎧", "<unknown>"))
        assertTrue(many("Top 20 Hits of the Year"))
        // Ten is many. Nine is a song's own name, as "99 Red Balloons" is.
        assertTrue(many("Top 10 Hits"))
        assertFalse(many("Top 9 Hits"))
        assertTrue(many("✿_30__Fake_Party_Audios_✿"))
        assertTrue(many("Grandes Éxitos: 15 Canciones de Fake Band"))
        // A number that counts something else, or few songs, is one song's name.
        assertFalse(many("99 Red Balloons"))
        assertFalse(many("3 Songs About You"))
        assertFalse(many("Hits Different", "Fake 21"))
        // One song, or two, as a single's two sides, is no medley.
        assertFalse(many("Full Moon"))
        assertFalse(many("A Song About a Concert"))
        assertFalse(many("Side A / Side B"))
        assertFalse(many("AC/DC Song"))
    }
}
