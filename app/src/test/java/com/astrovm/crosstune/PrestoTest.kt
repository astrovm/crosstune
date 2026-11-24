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
        // A bracket left open or closed once too often loses nothing after it; blank names are no one's.
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
        // Another's initial isn't them: Bill Evans didn't write it.
        assertEquals(MusicMetadata("Evans, M: Song", "Bill Evans"), Presto.song("Evans, M: Song", listOf("Bill Evans")))
    }

    @Test
    fun aClassicalWorkStaysWholeByItsFirstMusician() {
        val orchestra = listOf("Wiener Philharmoniker", "Carlos Kleiber")
        assertEquals(
            MusicMetadata("Beethoven: Symphony No. 5 in C minor, Op. 67: 1. Allegro con brio", "Wiener Philharmoniker"),
            Presto.song("Beethoven: Symphony No. 5 in C minor, Op. 67: 1. Allegro con brio", orchestra)
        )
        // Nothing before a colon, or something no one is called, is no one's name.
        assertEquals(MusicMetadata("Symphony No. 5: 1. Allegro", "Carlos Kleiber"), Presto.song("Symphony No. 5: 1. Allegro", listOf("Carlos Kleiber")))
        assertEquals(MusicMetadata(": Intro", "A"), Presto.song(": Intro", listOf("A")))
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
              {"artistData":[{"artistName":"Wiener Philharmoniker"}],
               "tracks":[{"trackTitle":"1. Allegro con brio","singleFieldTrackTitle":"Beethoven: Symphony No. 5: 1. Allegro con brio","duration":"7:22"},
                         {"trackTitle":"2. Andante con moto","duration":"1:00:00"},
                         {"singleFieldTrackTitle":"","duration":"x"}]}
            ]}}"""
        )
        assertEquals(
            listOf(
                MusicMetadata("So What", "Miles Davis", artworkUrl = "https://img/m.jpg", lengthMs = 547_000),
                // Who wrote it plays it. With no writer, it's the album's leader, though listed second.
                MusicMetadata("Blue in Green", "Bill Evans", artworkUrl = "https://img/m.jpg", lengthMs = 337_000),
                MusicMetadata("All Blues", "Miles Davis", artworkUrl = "https://img/m.jpg", lengthMs = 693_000),
                MusicMetadata("Beethoven: Symphony No. 5: 1. Allegro con brio", "Wiener Philharmoniker", artworkUrl = "https://img/m.jpg", lengthMs = 442_000),
                MusicMetadata("2. Andante con moto", "Wiener Philharmoniker", artworkUrl = "https://img/m.jpg", lengthMs = 3_600_000)
            ),
            Presto.tracks(tracks, MusicMetadata("Kind Of Blue", "Miles Davis", ItemType.ALBUM, "https://img/m.jpg"))
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
