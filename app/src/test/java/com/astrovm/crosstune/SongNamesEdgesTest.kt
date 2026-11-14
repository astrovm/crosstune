package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The edges of comparing names: Android versions that can't write kanji as China does, names with no letters, and credits that hold each other. Runs under Robolectric for the Android version. */
/**
 * Titles an app may send with no artist, each against a song asked for that it isn't: another recording or another part.
 * Crosstune never took one for the other, by words or otherwise.
 */
internal val otherRecordingsByTitle: List<Pair<String, String>> = listOf(
    "Hotel California (Live)" to "Hotel California (Acoustic)", "Fake Song (Remix)" to "Fake Song (Live)", "Song (Instrumental)" to "Song (Karaoke)",
    "Yesterday (Remastered 2009)" to "Yesterday (Live)", "05 Song (Live).flac" to "Song (Demo)", "Stay (Instrumental)" to "Stay",
    "Hello (Live)" to "Hello", "Hello (Remix)" to "Hello", "Hello" to "Hello (Live)",
    "Fake Song (Part 2)" to "Fake Song (Part 1)", "Fake Song (Part 2) [Explicit]" to "Fake Song (Part 1)", "Fake Song (Part 2)" to "Fake Song (Part 1) [Explicit]",
    "Fake Song (Spanish Version)" to "Fake Song (English Version)", "Fake Song (Versión Acústica)" to "Fake Song (Versión En Vivo)",
    "Fake Song (Versión Acústica)" to "Fake Song (En Vivo)", "Fake Song (Stripped)" to "Fake Song (Live)", "Fake Song (Reprise)" to "Fake Song [Explicit]",
    "Fake Song (Taylor's Version)" to "Fake Song [Explicit]", "Fake Song (ライブ)" to "Fake Song (Acoustic)"
) + listOf(
    "(Part 2)", "(Pt. 2)", "(Reprise)", "(Interlude)", "(Versión Acústica)", "(Versão Acústica)", "(Versione Acustica)", "(Akustik Version)",
    "(Spanish Version)", "(Orchestral Version)", "(Symphonic Version)", "(Stripped)", "(Re-Recorded)", "(Taylor's Version)", "(Rehearsal)",
    "(Alternate Take)", "(Remixed)", "(Rework)", "(VIP)", "(Dub)", "(Bootleg)", "(Extended)", "(ライブ)"
).flatMap { tag -> listOf("Hello $tag" to "Hello", "Hello" to "Hello $tag") }

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

    @Test
    fun anAppThatSendsNoArtistHasASongByItsTitleOnlyWhereCrosstuneAlwaysTookIt() {
        // What an app plays, by its title alone, against the song asked for; and whether that's the song.
        val rows = listOf(
            "Fake Song [Explicit]" to "Fake Song" to true,
            "Fake Song (Official Audio)" to "Fake Song" to true,
            "Fake Song (From \"Fake Movie\")" to "Fake Song" to true,
            "01 Fake Song" to "Fake Song" to true,
            "01. Fake Song" to "Fake Song" to true,
            "1. Fake Song" to "Fake Song" to true,
            "Fake Song.mp3" to "Fake Song" to true,
            "01 Fake Song (Live) [Explicit].mp3" to "Fake Song" to true,
            "Fake Song (Live)" to "Fake Song" to true,
            "Fake Song (Acoustic)" to "Fake Song" to true,
            "Fake Song (Extended Mix)" to "Fake Song" to true,
            "Yesterday (Remastered 2009)" to "Yesterday" to true,
            "01 Hello" to "Hello" to true,
            "Hello.mp3" to "Hello" to true,
            "Hello [Explicit]" to "Hello" to true,
            "Hello (Official Audio)" to "Hello" to true,
            "Fake Song (Part 2)" to "Fake Song" to true,
            "Fake Song (Reprise)" to "Fake Song" to true,
            "Fake Song (ライブ)" to "Fake Song" to true,
            // A number that's part of the name, and words it only shares.
            "1999" to "99" to false,
            "22" to "2" to false,
            "09" to "9" to false,
            "Song 2" to "Song" to false,
            "7 Rings" to "Rings" to false,
            "100 Song" to "Song" to false,
            "Hotel California Radio" to "Hotel California" to false,
            "Los 40 Classic" to "Los 40" to false,
            "Fake Song Remastered 2013" to "Fake Song" to false
            // Another recording of it, or another part, is another song to follow, as before.
        ) + otherRecordingsByTitle.map { it to false }
        // Taken for the song where Crosstune always took it by its words, and more only where meant to:
        // a track number, a file's extension, or a tag that names no other recording, on a one-word title.
        val meant = setOf("01 Hello", "Hello.mp3", "Hello [Explicit]")
        val gained = mutableSetOf<String>()
        for ((pair, has) in rows) {
            val (title, song) = pair
            val asked = MusicMetadata(song, "Fake Band")
            val now = SongNames.names(title, "", asked, byWords = false)
            assertEquals("$title for $song", has, now)
            if (now && !SongNames.names(title, "", asked)) gained += title
        }
        assertEquals(meant, gained)
    }

    @Test
    fun anAppThatSendsNoArtistHasASongByItsTitleOnlyPastTagsThatNameNoOtherRecording() {
        // Tags that never name another recording, and those Crosstune already took as the song itself.
        val noOther = listOf(
            "[Explicit]", "(Clean)", "(Official Audio)", "(Official Video)", "(Lyric Video)", "(Lyrics)", "(From \"Fake Movie\")",
            "(feat. Fake Guest)", "(2011 Remaster)", "(Radio Edit)", "(Mono)"
        )
        val others = listOf(
            "(Live)", "(Acoustic)", "(Remix)", "(Instrumental)", "(Karaoke)", "(Demo)", "(Part 1)", "(Part 2)", "(Pt. 2)", "(Reprise)",
            "(Interlude)", "(Spanish Version)", "(English Version)", "(Versión Acústica)", "(Versión En Vivo)", "(Versão Acústica)",
            "(Versione Acustica)", "(Akustik Version)", "(Orchestral Version)", "(Symphonic Version)", "(Stripped)", "(Re-Recorded)",
            "(Taylor's Version)", "(Rehearsal)", "(Alternate Take)", "(Remixed)", "(Rework)", "(VIP)", "(Dub)", "(Bootleg)", "(Extended)",
            "(En Vivo)", "(Ao Vivo)", "[Live]", "- Live", "(Live at Wembley)", "(Sped Up)", "(Slowed + Reverb)", "(Nightcore)", "(Cover)",
            "(Karaoke Version)", "(Acústico)", "(En Directo)", "(Japanese Ver.)", "(Club Mix)", "(A Cappella)", "(ライブ)"
        )
        // Each tag on its own, and with "[Explicit]" after it, on either side.
        val tags = listOf("") + (noOther + others).flatMap { listOf(it, "$it [Explicit]") }
        // The tags that may name another recording: those two titles must have the same, past what names no other.
        fun naming(tag: String) = if (tag.removeSuffix(" [Explicit]") in noOther) "" else tag.removeSuffix(" [Explicit]")
        val wrong = mutableListOf<String>()
        for (base in listOf("Hello", "Fake Song")) for (theirs in tags) for (ours in tags) for ((number, extension) in listOf("" to "", "01 " to ".mp3")) {
            val named = "$base $theirs".trim()
            val title = "$number$named$extension"
            val asked = MusicMetadata("$base $ours".trim(), "Fake Band")
            if (naming(theirs) == naming(ours) || !SongNames.names(title, "", asked, byWords = false)) continue
            // Where the tags differ in what may name another recording, only what Crosstune took by its words before,
            // as it names it or without its track number and extension.
            if (!SongNames.names(title, "", asked) && !SongNames.names(named, "", asked)) wrong += "$title for ${asked.title}"
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun aVersionNamedInAnotherScriptIsAnotherRecordingByWordsToo() {
        // "ライブ" says "Live" in Japanese: as "Live" does, it tells the recording apart, with the artist named or not.
        for (artist in listOf("Fake Band", "")) {
            assertFalse(SongNames.names("Fake Song (ライブ)", artist, MusicMetadata("Fake Song (Acoustic)", "Fake Band")))
            assertFalse(SongNames.names("Hello (ライブ)", artist, MusicMetadata("Hello", "Fake Band")))
            assertFalse(SongNames.names("Hello", artist, MusicMetadata("Hello (ライブ)", "Fake Band")))
            assertFalse(SongNames.names("アイドル (ライブ)", artist, MusicMetadata("アイドル", "Fake Band")))
            // A version as long a title, "Fake Song (Live)" for "Fake Song", still has it.
            assertTrue(SongNames.names("Fake Song (ライブ)", artist, MusicMetadata("Fake Song", "Fake Band")))
            assertTrue(SongNames.names("Fake Song (ライブ)", artist, MusicMetadata("Fake Song (Live) (ライブ)", "Fake Band")))
        }
        // The same mark on both, or in the song's own name, is no other recording.
        assertTrue(SongNames.wordsMatch("ライブ・ア・ライブ", "ライブ・ア・ライブ"))
        assertTrue(SongNames.wordsMatch("アイドル (ライブ)", "アイドル (ライブ) [Remastered]"))
    }
}
