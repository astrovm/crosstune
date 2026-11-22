package com.astrovm.crosstune

import android.media.MediaDescription
import android.media.session.MediaSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RadioGardenTest {
    private fun item(station: String, text: String?) =
        MediaSession.QueueItem(MediaDescription.Builder().setTitle(station).setSubtitle(text).build(), 0)

    @Test
    fun readsASongFromTheCapturedQueueAndKeepsASeparatedTitleWhole() {
        // Samsung SystemUI recorded item 0: title 1LIVE, this subtitle, and a null description.
        assertEquals(MusicMetadata("A PLACE TO GO", "The Kid LAROI"),
            RadioGarden.queueSong("1LIVE", listOf(item("1LIVE", "The Kid LAROI - A PLACE TO GO"))))
        assertEquals(MusicMetadata("Song - Live", "Artist"),
            RadioGarden.queueSong("1LIVE", listOf(item("1LIVE", "Artist – Song - Live"))))
    }

    @Test
    fun readsIHeartSongTextAndSkipsTheOtherFields() {
        val subtitle = """Olivia Rodrigo / Chappell Roan - text="Expectations" song_spot="M" length="00:03:39" """
        assertEquals(MusicMetadata("Expectations", "Olivia Rodrigo / Chappell Roan"),
            RadioGarden.queueSong("KIIS FM 102.7", listOf(item("KIIS FM 102.7", subtitle))))
    }

    @Test
    fun dropsLocationsStationsShowsAndAdMarkers() {
        val station = "WHTZ Z100"
        val subtitles = listOf(
            null,
            "New York NY, United States",
            station,
            "$station - Today's Hits",
            "Stolen Moments with John Bailey",
            "Artist- Song",
            "Artist -Song",
            "WDR Aktuell - Die Nachrichten",
            "Station Desk - The   News",
            "Station Desk - News",
            """text="Spot Block - End" amgTrackId="9876543" length="00:00:00" """,
            "Artist - 'Spot Block - End'",
            """Artist - text="Spot Block - End" song_spot="M" """,
            """artist="Band" - title="Song" """,
            """Artist - Song program_id=123""",
            "<artist>Band</artist> - Song",
            """Artist - text="<song>Title</song>" song_spot="M" """,
            """Artist - text="Song" extra broadcast words""",
            """Artist - text="Song" length="99""",
            """Artist - text="Song" song_spot="M" SONG_SPOT="A" """,
            """Artist - text="" song_spot="M" """,
            """text="Spot Block End" amgTrackId="9876543" length="00:00:00" """,
            """Artist - text="Spot Block End" amgTrackId="9876543" """,
            """Artist - text="Song" song_spot="A" """,
            """Artist - text=" """
        )
        assertEquals(emptyList<String?>(), subtitles.filter { text ->
            RadioGarden.queueSong(station, listOf(item(station, text))) != null
        })
    }

    @Test
    fun comparesKanaWithoutLosingMarksOrCollapsingSmallKana() {
        assertTrue(RadioGarden.sameTitle("ガラス", "ｶﾞﾗｽ"))
        assertTrue(RadioGarden.sameTitle("パーティー", "ハ\u309aーティー"))
        assertTrue(RadioGarden.sameTitle("Étoile", "Etoile"))
        assertFalse(RadioGarden.sameTitle("ガラス", "カラス"))
        assertFalse(RadioGarden.sameTitle("パーティー", "バーティー"))
        assertFalse(RadioGarden.sameTitle("きゃく", "きやく"))
        assertFalse(RadioGarden.sameTitle("スキー", "スキ"))
    }

    @Test
    fun readsEitherWaveDashAfterKanaAsALongVowel() {
        assertTrue(RadioGarden.sameTitle("スーパースター", "スーパ～スタ〜"))
        assertTrue(RadioGarden.sameTitle("スーパ〜スタ～", "スーパースター"))
        assertTrue(RadioGarden.sameTitle("ガ～", "ガー"))
        assertFalse(RadioGarden.sameTitle("Rock～", "Rockー"))
        assertFalse(RadioGarden.sameTitle("A・～", "A・ー"))
        assertFalse(RadioGarden.sameTitle("ガラス〜", "カラスー"))
    }

    @Test
    fun dropsGenericBroadcastLabelsInTheSiteLanguages() {
        val labels = listOf(
            "Live stream", "On-air", "On air now", "NOW  PLAYING!", "auf Sendung", "Noticias", "Al aire",
            "En direct", "अभी चल रहा है", "Siaran langsung", "In onda", "放送中",
            "재생 중", "In de ether", "Na żywo", "No ar", "В эфире", "Canlı yayın",
            "正在播放", "新聞"
        )
        assertEquals(emptyList<String>(), labels.filter { label ->
            RadioGarden.queueSong("Station", listOf(item("Station", "Radio Desk - $label"))) != null
        })
        assertNull(RadioGarden.queueSong("Station", listOf(item("Station", "Now Playing - Song"))))
        assertNull(RadioGarden.queueSong("Station", listOf(item("Station", "Artist - Station"))))
    }

    @Test
    fun keepsSingleWordNamesAndTitlesWithNumbers() {
        val songs = listOf(
            MusicMetadata("Lightning Crashes", "Live"),
            MusicMetadata("Live Forever", "Live"),
            MusicMetadata("Live", "Band"),
            MusicMetadata("867-5309/Jenny", "Tommy Tutone"),
            MusicMetadata("1999", "Prince"),
            MusicMetadata("7 rings", "Ariana Grande"),
            MusicMetadata("99 Luftballons", "Nena"),
        )
        val dropped = songs.filter { (title, artist) ->
            RadioGarden.queueSong("Station", listOf(item("Station", "$artist - $title"))) != MusicMetadata(title, artist)
        }
        assertEquals(emptyList<MusicMetadata>(), dropped)
    }

    @Test
    fun dropsWebAddressesEmailAndPhoneNumbersButKeepsOrdinaryNames() {
        val subtitles = listOf(
            "Visit example.com - Tune in",
            "Artist - Visit https://example.com/live",
            "Artist - Visit http://radio/live",
            "www.example.com - Song",
            "Artist - Send to desk+live@example.org",
            "Radio Desk - Call 212 555 1234",
            "Radio Desk - Call 212‑555‑1234",
            "Radio Desk - Call +1 (212) 555-1234",
            "Radio Desk - Call +44 20",
            """Artist - text="Call 212.555-1234" song_spot="M" """
        )
        assertEquals(emptyList<String>(), subtitles.filter { text ->
            RadioGarden.queueSong("Station", listOf(item("Station", text))) != null
        })
        assertEquals(MusicMetadata("1989", "R.E.M."),
            RadioGarden.queueSong("Station", listOf(item("Station", "R.E.M. - 1989"))))
        assertEquals(MusicMetadata("Song - Live", "Artist"),
            RadioGarden.queueSong("Station", listOf(item("Station", "Artist - Song - Live"))))
    }

    @Test
    fun requiresOneItemThatBelongsToTheCurrentStation() {
        val item = item("1LIVE", "The Kid LAROI - A PLACE TO GO")
        assertNull(RadioGarden.queueSong("1LIVE", null))
        assertNull(RadioGarden.queueSong("1LIVE", emptyList()))
        assertNull(RadioGarden.queueSong("1LIVE", listOf(item, item)))
        assertNull(RadioGarden.queueSong("BBC World Service", listOf(item)))
    }
}
