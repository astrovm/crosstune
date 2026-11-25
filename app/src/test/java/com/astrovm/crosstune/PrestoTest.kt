package com.astrovm.crosstune

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Android's org.json, which the app reads Presto's answers with, runs only under Robolectric.
@RunWith(RobolectricTestRunner::class)
class PrestoTest {
    @Test
    fun creditsAreReadAsTheirMusiciansWithoutWhatTheyPlay() {
        assertEquals(
            listOf("Miles Davis", "Julian Cannonball Adderley", "Wiener Philharmoniker", "Carlos Kleiber"),
            Presto.musicians("Miles Davis (trumpet), Julian Cannonball Adderley (alto saxophone, flute), Wiener Philharmoniker, Carlos Kleiber (conductor)")
        )
        // A long credit goes on in another line, and a guest is "with" them.
        assertEquals(
            listOf("Martha Argerich", "Alexander Gurning", "Orchestra Della Svizzera Italiana", "Charles Dutoit"),
            Presto.musicians("Martha Argerich (piano), with Alexander Gurning (piano)\nOrchestra Della Svizzera Italiana, Charles Dutoit")
        )
        // A bracket closed once too often loses nothing after it, one left open makes the rest what they play, and blank
        // names are no one's.
        assertEquals(listOf("A", "B"), Presto.musicians("A (piano)), , B"))
        assertEquals(listOf("A"), Presto.musicians("A (piano, (prepared"))
        assertEquals(emptyList<String>(), Presto.musicians(""))
    }

    @Test
    fun aSongStartingWithWhoWroteItIsTheirsWhenTheyPlayIt() {
        val band = listOf("Cannonball Adderley", "Bill Evans", "Miles Davis")
        assertEquals(MusicMetadata("So What", "Miles Davis"), Presto.song("Davis, M: So What", band))
        assertEquals(MusicMetadata("Blue in Green", "Miles Davis"), Presto.song("Davis, M.: Blue in Green", band))
        assertEquals(MusicMetadata("Mood Indigo", "Duke Ellington"), Presto.song("Ellington: Mood Indigo", listOf("Johnny Hodges", "Duke Ellington")))
        // Two writers, and the one who sings it; a name spelled apart or with an initial is still theirs.
        assertEquals(MusicMetadata("Anti-Hero", "Taylor Swift"), Presto.song("Taylor Swift, Jack Antonoff: Anti-Hero", listOf("Taylor Swift")))
        assertEquals(MusicMetadata("Majestic Dance", "Al Di Meola"), Presto.song("A. DiMeola: Majestic Dance", listOf("Lenny White", "Al Di Meola")))
        assertEquals(MusicMetadata("Duet", "Lorenz Hart"), Presto.song("Rodgers & Hart: Duet", listOf("A", "Lorenz Hart")))
        // Another's initial isn't them: Bill Evans didn't write it.
        assertEquals(MusicMetadata("Evans, M: Song", "Bill Evans"), Presto.song("Evans, M: Song", listOf("Bill Evans")))
        assertEquals(MusicMetadata("B. Evans: Song", "Gil Evans"), Presto.song("B. Evans: Song", listOf("Gil Evans")))
        // A first name is theirs too, whole, a surname is theirs whole, and each writer of two keeps their own initial.
        assertEquals(MusicMetadata("Anti-Hero", "Taylor Swift"), Presto.song("Taylor Swift: Anti-Hero", listOf("Jonathan Swift", "Taylor Swift")))
        assertEquals(MusicMetadata("Anti-Hero", "Taylor Swift"), Presto.song("Taylor Swift: Anti-Hero", listOf("Timothy Swift", "Taylor Swift")))
        // "van" is the surname's, initials however they're joined are names, and a name may be joined by a space Android can't break.
        assertEquals(MusicMetadata("Eruption", "Eddie van Halen"), Presto.song("van Halen: Eruption", listOf("Eddie van Halen")))
        assertEquals(MusicMetadata("Prelude", "J.S. Bach"), Presto.song("Bach, J. S.: Prelude", listOf("J.S. Bach")))
        assertEquals(MusicMetadata("Prelude", "J.S. Bach"), Presto.song("Johann Sebastian Bach: Prelude", listOf("J.S. Bach")))
        // More names than the musician has isn't them.
        assertEquals(MusicMetadata("J. S. Bach: Prelude", "Bach"), Presto.song("J. S. Bach: Prelude", listOf("Bach")))
        assertEquals(MusicMetadata("Sonata", "Jean-Pierre Rampal"), Presto.song("Rampal, J. P.: Sonata", listOf("Jean-Pierre Rampal")))
        assertEquals(MusicMetadata("Sonata", "Jean-Pierre Rampal"), Presto.song("J.-P. Rampal: Sonata", listOf("Jean-Pierre Rampal")))
        assertEquals(MusicMetadata("So What", "Miles\u00a0Davis"), Presto.song("Davis, M: So What", listOf("Miles\u00a0Davis")))
        assertEquals(MusicMetadata("Bach: Prelude", "Jacques Offenbach"), Presto.song("Bach: Prelude", listOf("Jacques Offenbach")))
        assertEquals(MusicMetadata("Song", "Miles Davis"), Presto.song("Davis, M & Evans, B: Song", listOf("Bill Davis", "Miles Davis")))
        // A credit left with a comma and nothing after it, or two spaces, is still its writer, and a blank part between a
        // surname and its initial keeps the initial theirs.
        assertEquals(MusicMetadata("So What", "Miles Davis"), Presto.song("Davis,: So What", listOf("Miles Davis")))
        assertEquals(MusicMetadata("Davis, , M: Song", "Bill Davis"), Presto.song("Davis, , M: Song", listOf("Bill Davis")))
        assertEquals(MusicMetadata("Song", "Miles Davis"), Presto.song("Davis, , M: Song", listOf("Miles Davis")))
        assertEquals(MusicMetadata("Anti-Hero", "Taylor Swift"), Presto.song("Taylor  Swift: Anti-Hero", listOf("Taylor Swift")))
        assertEquals(MusicMetadata("Anti-Hero", "Taylor Swift"), Presto.song("Taylor\u00a0Swift: Anti-Hero", listOf("Taylor Swift")))
        // A hyphen in a surname keeps it whole, and a name after a comma is another writer, not initials.
        assertEquals(MusicMetadata("Song", "Alice Smith-Jones"), Presto.song("Smith-Jones: Song", listOf("Alice Smith-Jones")))
        assertEquals(MusicMetadata("Song", "Alice Smith-Jones"), Presto.song("Alice Smith-Jones: Song", listOf("Alice Smith-Jones")))
        assertEquals(MusicMetadata("Song", "Alice Smith-Jones"), Presto.song("Smith-Jones, A.: Song", listOf("Alice Smith-Jones")))
        assertEquals(MusicMetadata("Song", "Brian May"), Presto.song("Lennon, May: Song", listOf("Brian May")))
        assertEquals(MusicMetadata("Song", "John Lennon"), Presto.song("Lennon, May: Song", listOf("John Lennon")))
        // Initials written together are still each a name's, and a capitalized particle is the surname's too.
        assertEquals(MusicMetadata("Prelude", "Johann Sebastian Bach"), Presto.song("Bach, JS: Prelude", listOf("Johann Sebastian Bach")))
        assertEquals(MusicMetadata("Bach, JS: Prelude", "Bill Bach"), Presto.song("Bach, JS: Prelude", listOf("Bill Bach")))
        assertEquals(MusicMetadata("Prelude", "Johann Sebastian Bach"), Presto.song("JS Bach: Prelude", listOf("Johann Sebastian Bach")))
        assertEquals(MusicMetadata("Eruption", "Eddie Van Halen"), Presto.song("Van Halen: Eruption", listOf("Eddie Van Halen")))
        assertEquals(MusicMetadata("Gloria", "Van Morrison"), Presto.song("Van Morrison: Gloria", listOf("Van Morrison")))
        // A particle may be a first name too.
        assertEquals(MusicMetadata("Gloria", "V. Morrison"), Presto.song("Van Morrison: Gloria", listOf("V. Morrison")))
        assertEquals(MusicMetadata("Gloria", "Van Morrison"), Presto.song("Morrison, Van: Gloria", listOf("Van Morrison")))
        assertEquals(MusicMetadata("Morrison, Van: Gloria", "Bill Morrison"), Presto.song("Morrison, Van: Gloria", listOf("Bill Morrison")))
        // Initials together are the same as apart, on either side, but never a different initial's.
        assertEquals(MusicMetadata("Song", "DJ Shadow"), Presto.song("DJ Shadow: Song", listOf("DJ Shadow")))
        assertEquals(MusicMetadata("Prelude", "J.S. Bach"), Presto.song("JS Bach: Prelude", listOf("J.S. Bach")))
        assertEquals(MusicMetadata("Prelude", "JS Bach"), Presto.song("J. S. Bach: Prelude", listOf("JS Bach")))
        assertEquals(MusicMetadata("JS Bach: Prelude", "J. C. Bach"), Presto.song("JS Bach: Prelude", listOf("J. C. Bach")))
        assertEquals(MusicMetadata("Bach, J. C.: Prelude", "JS Bach"), Presto.song("Bach, J. C.: Prelude", listOf("JS Bach")))
        // A short name in capitals is a name, as well as initials, on either side.
        assertEquals(MusicMetadata("Song", "LEE MORGAN"), Presto.song("Lee Morgan: Song", listOf("LEE MORGAN")))
        assertEquals(MusicMetadata("Song", "Lee Morgan"), Presto.song("LEE MORGAN: Song", listOf("Lee Morgan")))
        // A name in capitals is whole while the initial beside it is still an initial, and three capitals are three initials.
        assertEquals(MusicMetadata("Song", "John Lee Morgan"), Presto.song("J. LEE MORGAN: Song", listOf("John Lee Morgan")))
        assertEquals(MusicMetadata("Song", "John Ronald Reuel Tolkien"), Presto.song("JRR Tolkien: Song", listOf("John Ronald Reuel Tolkien")))
        // A mark on its own in a credit is no name to match.
        assertEquals(MusicMetadata("Song", "Jack Antonoff"), Presto.song("Jack ’ Antonoff: Song", listOf("Jack Antonoff")))
    }

    @Test
    fun aWorkLosesItsComposerAndIsByItsFirstMusician() {
        val orchestra = listOf("Wiener Philharmoniker", "Carlos Kleiber")
        assertEquals(
            MusicMetadata("Symphony No. 5 in C minor, Op. 67: 1. Allegro con brio", "Wiener Philharmoniker"),
            Presto.song("Beethoven: Symphony No. 5 in C minor, Op. 67: 1. Allegro con brio", orchestra, "Ludwig van Beethoven")
        )
        assertEquals(MusicMetadata("Wonderful Copenhagen", "A"), Presto.song("Loesser: Wonderful Copenhagen", listOf("A"), "Frank Henry Loesser"))
        // Each of a composer's initials is one of their names.
        assertEquals(MusicMetadata("Prelude", "Glenn Gould"), Presto.song("Bach, J. S.: Prelude", listOf("Glenn Gould"), "Johann Sebastian Bach"))
        assertEquals(MusicMetadata("Bagatelle", "A"), Presto.song("L. v. Beethoven: Bagatelle", listOf("A"), "Ludwig van Beethoven"))
        // A particle after the initials is the surname's, and the initial is still theirs.
        assertEquals(MusicMetadata("Bagatelle", "A"), Presto.song("Beethoven, L. van: Bagatelle", listOf("A"), "Ludwig van Beethoven"))
        assertEquals(MusicMetadata("Bagatelle", "A"), Presto.song("Beethoven, L. VAN: Bagatelle", listOf("A"), "Ludwig van Beethoven"))
        assertEquals(MusicMetadata("Beethoven, L. van: Bagatelle", "A"), Presto.song("Beethoven, L. van: Bagatelle", listOf("A"), "Johann van Beethoven"))
        assertEquals(MusicMetadata("Beethoven, L. van: Bagatelle", "Johann Beethoven"), Presto.song("Beethoven, L. van: Bagatelle", listOf("Johann Beethoven")))
        // Without a composer to tell, a name no one plays stays.
        assertEquals(MusicMetadata("Beethoven: Symphony No. 5", "Wiener Philharmoniker"), Presto.song("Beethoven: Symphony No. 5", orchestra))
        assertEquals(MusicMetadata("Heyward, Gershwin: Summertime", "A"), Presto.song("Heyward, Gershwin: Summertime", listOf("A"), "Someone Else"))
        // Nothing before a colon, numbers, or a mark alone are no one's name.
        assertEquals(MusicMetadata("Symphony No. 5: 1. Allegro", "Carlos Kleiber"), Presto.song("Symphony No. 5: 1. Allegro", listOf("Carlos Kleiber")))
        assertEquals(MusicMetadata(": Intro", "A"), Presto.song(": Intro", listOf("A")))
        assertEquals(MusicMetadata(".: Intro", "A"), Presto.song(".: Intro", listOf("A"), "B"))
        assertEquals(MusicMetadata("Intro", ""), Presto.song("Intro", emptyList()))
    }

    @Test
    fun anAlbumIsNamedByItsLeaderWithItsCover() {
        val album = JSONObject(
            """{"payload":{"title":"Kind Of Blue","artistSummary":"Miles Davis (trumpet), John Coltrane (tenor saxophone)",
            "images":[{"image":"https://img/large.jpg"}],"images_sizes":[{"small":"https://img/s.jpg","medium":"https://img/m.jpg"}]}}"""
        )
        // A search for every musician finds nothing; other services credit the one Presto credits first.
        assertEquals(MusicMetadata("Kind Of Blue", "Miles Davis", ItemType.ALBUM, "https://img/m.jpg"), Presto.album(album))
        // Without sizes, the one cover it has; without either, none.
        val large = JSONObject("""{"payload":{"title":"Record","artistSummary":"A","images":[{"image":"https://img/large.jpg"}],"images_sizes":[{"medium":""}]}}""")
        assertEquals("https://img/large.jpg", Presto.album(large)?.artworkUrl)
        assertNull(Presto.album(JSONObject("""{"payload":{"title":"Record","artistSummary":"A"}}"""))?.artworkUrl)
        assertEquals(MusicMetadata("Record", "", ItemType.ALBUM), Presto.album(JSONObject("""{"payload":{"title":"Record"}}""")))
        // Android reads a null as "null", which is no one.
        assertEquals(MusicMetadata("Record", "", ItemType.ALBUM), Presto.album(JSONObject("""{"payload":{"title":"Record","artistSummary":null}}""")))
        assertNull(Presto.album(JSONObject("""{"payload":{"title":null}}""")))
        // No album, or no name for it.
        assertNull(Presto.album(JSONObject("""{"status":"error","code":54}""")))
        assertNull(Presto.album(JSONObject("""{"payload":{"title":" "}}""")))
    }

    @Test
    fun anAlbumsSongsAreEachWorksMovementsInTurnByItsLeaderWhereTheyPlay() {
        val tracks = JSONObject(
            """{"payload":{"works":[
              {"artistData":[{"artistName":"Bill Evans"},{"artistName":"Miles Davis"},{"artistName":" "}],
               "tracks":[{"trackTitle":"Davis, M: So What","singleFieldTrackTitle":"Davis, M: So What","duration":"9:07"},
                         {"trackTitle":"Evans, B: Blue in Green","duration":"5:37"},
                         {"trackTitle":"All Blues","duration":"11:33"}]},
              {"artistData":[{"artistName":"Wiener Philharmoniker"},{"artistName":null}],"composerName":"Ludwig van Beethoven",
               "tracks":[{"trackTitle":"1. Allegro con brio","singleFieldTrackTitle":"Beethoven: Symphony No. 5: 1. Allegro con brio","duration":"7:22"},
                         {"trackTitle":"2. Andante con moto","duration":"1:00:00"},
                         {"singleFieldTrackTitle":"","duration":"x"},
                         {"trackTitle":"3. Allegro","duration":null}]}
            ]}}"""
        )
        assertEquals(
            listOf(
                MusicMetadata("So What", "Miles Davis", artworkUrl = "https://img/m.jpg", lengthMs = 547_000),
                // Who wrote it plays it. With no writer, it's the album's leader, though listed second.
                MusicMetadata("Blue in Green", "Bill Evans", artworkUrl = "https://img/m.jpg", lengthMs = 337_000),
                MusicMetadata("All Blues", "Miles Davis", artworkUrl = "https://img/m.jpg", lengthMs = 693_000),
                // Who wrote the piece comes off it.
                MusicMetadata("Symphony No. 5: 1. Allegro con brio", "Wiener Philharmoniker", artworkUrl = "https://img/m.jpg", lengthMs = 442_000),
                MusicMetadata("2. Andante con moto", "Wiener Philharmoniker", artworkUrl = "https://img/m.jpg", lengthMs = 3_600_000),
                // Android reads a null as "null"; it's no length.
                MusicMetadata("3. Allegro", "Wiener Philharmoniker", artworkUrl = "https://img/m.jpg")
            ),
            // The album may write its leader another way.
            Presto.tracks(tracks, MusicMetadata("Kind Of Blue", "MILES DAVIS.", ItemType.ALBUM, "https://img/m.jpg"))
        )
        assertEquals(emptyList<MusicMetadata>(), Presto.tracks(JSONObject("""{"status":"error"}"""), MusicMetadata("Record", "", ItemType.ALBUM)))
    }

    @Test
    fun aClockReadsAsHowLongItIs() {
        assertEquals(547_000L, MetadataParsers.clockMs("9:07"))
        assertEquals(3_723_000L, MetadataParsers.clockMs("1:02:03"))
        assertNull(MetadataParsers.clockMs("9"))
        assertNull(MetadataParsers.clockMs("1:2:3:4"))
        assertNull(MetadataParsers.clockMs("9:x"))
        assertNull(MetadataParsers.clockMs(""))
    }
}
