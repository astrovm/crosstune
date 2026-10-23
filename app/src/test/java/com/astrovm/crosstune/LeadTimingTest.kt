package com.astrovm.crosstune

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LeadTimingTest {
    private val prefs = ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("video-test", Context.MODE_PRIVATE).apply { edit().clear().commit() }
    private val song = MusicMetadata("Demo", "Band", recordingId = "app:video")

    @Test fun recordingsKeepTheirOwnConfirmedIntro() {
        val store = LeadStore(prefs)
        assertNull(store.load(song))
        store.save(song, 10_000)
        assertEquals(10_000L, LeadStore(prefs).load(song))
        assertNull(store.load(song.copy(recordingId = "app:other")))
        store.save(song, -60_000)
        assertEquals(-60_000L, store.load(song))
        store.save(song, 60_000)
        assertEquals(60_000L, store.load(song))
        store.save(song, Long.MIN_VALUE)
        store.save(song, 60_001)
        store.save(song.copy(recordingId = null), 10_000)
        assertEquals(60_000L, store.load(song))
        assertNull(store.load(song.copy(recordingId = null)))
        prefs.edit().putString("video_timing", "invalid").commit()
        assertNull(store.load(song))
        store.save(song, 100)
        assertEquals(100L, store.load(song))
        prefs.edit().putString("video_timing", "{\"${RecordingKey.of(song).replace("\u0000", "\\u0000")}\":60001}").commit()
        assertNull(store.load(song))
        prefs.edit().putString("video_timing", "{\"${RecordingKey.of(song).replace("\u0000", "\\u0000")}\":\"wrong\"}").commit()
        assertNull(store.load(song))
    }

    @Test fun theOldestOfManyRecordingsIsForgotten() {
        val store = LeadStore(prefs)
        repeat(201) { store.save(song.copy(recordingId = "app:$it"), it.toLong()) }
        assertNull(store.load(song.copy(recordingId = "app:0")))
        assertEquals(200L, store.load(song.copy(recordingId = "app:200")))
    }

    @Test fun shazamsGapIsKeptForEachRecordingItMatchesAndTheWordsItWasLearntFor() {
        val store = ShazamGapStore(prefs)
        val song = MusicMetadata("Plastic Love", "Mariya Takeuchi")
        assertNull(store.load(294_000, "403191606"))
        assertFalse(store.knows(song, 294_000))
        store.save(song, 294_000, "403191606", -36_800)
        // Whatever another release names the song, the recording Shazam matched is the same.
        assertEquals(-36_800L, ShazamGapStore(prefs).load(294_400, "403191606"))
        assertTrue(store.knows(song, 294_000))
        assertTrue(store.knows(MusicMetadata("PLASTIC LOVE", "Mariya Takeuchi"), 294_400))
        // Another recording Shazam matches, or words timed for another release, have gaps of their own.
        assertNull(store.load(294_000, "1"))
        assertNull(store.load(309_000, "403191606"))
        assertFalse(store.knows(song, 309_000))
        assertFalse(store.knows(MusicMetadata("Plastic Love", "Cindy Zhang"), 294_000))
        store.save(song, 294_000, "403191606", -36_000)
        assertEquals(-36_000L, store.load(294_000, "403191606"))
        prefs.edit().putString("shazam_gaps", "invalid").commit()
        assertNull(store.load(294_000, "403191606"))
        assertFalse(store.knows(song, 294_000))
        store.save(song, 294_000, "403191606", 100)
        assertEquals(100L, store.load(294_000, "403191606"))
        prefs.edit().putString("shazam_gaps", "{\"plastic love\\u0000mariya takeuchi\\u0000294\\u0000403191606\":\"wrong\"}").commit()
        assertNull(store.load(294_000, "403191606"))
        repeat(201) { store.save(song, 294_000, "$it", it.toLong()) }
        // Only the oldest goes, keeping two hundred.
        assertNull(store.load(294_000, "0"))
        assertEquals(1L, store.load(294_000, "1"))
        assertEquals(200L, store.load(294_000, "200"))
    }

    @Test fun aGapCanBeAheadOrBehindButOnlyAsFarAsItMayBe() {
        fun confirmed(gap: Long, possible: LongRange): Long? {
            val timing = LeadTiming()
            return (0..2).map { timing.heard(PlaybackClock(40_000 + it * 10_000L + gap, 0), PlaybackClock(40_000 + it * 10_000L, 0), possible) }.last()
        }
        assertEquals(-36_800L, confirmed(-36_800, -60_000L..60_000L))
        assertEquals(20_000L, confirmed(20_000, -60_000L..60_000L))
        assertNull(confirmed(-36_800, 0L..60_000L))
        // Never more than a minute, whatever may be.
        assertNull(confirmed(-61_000, -120_000L..120_000L))
    }

    @Test fun anIntroMustAgreeWithTheRecordingsAndCannotBeAReferenceAheadOfTheVideo() {
        val rejected = LeadTiming()
        repeat(3) {
            val at = it * 10_000L
            assertNull(rejected.heard(PlaybackClock(at + 30_000, at), PlaybackClock(at, at), extraMs = 0))
        }
        val timing = LeadTiming()
        val video = PlaybackClock(20_000, 0)
        val song = PlaybackClock(10_000, 0)
        assertNull(timing.heard(PlaybackClock(0, 0), song))
        assertNull(timing.heard(video, song, extraMs = 30_000))
        assertNull(timing.heard(video, song, extraMs = 18_000))
        assertNull(timing.heard(PlaybackClock(30_000, 10_000), PlaybackClock(20_000, 10_000), extraMs = 10_000))
        assertEquals(10_000L, timing.heard(PlaybackClock(40_000, 20_000), PlaybackClock(30_000, 20_000), extraMs = 2_000))
    }

    @Test fun aLeadFitsInHowMuchLongerOrShorterTheReleaseIs() {
        fun confirmed(lead: Long, extraMs: Long?): Long? {
            val timing = LeadTiming()
            return (0..2).map { timing.heard(PlaybackClock(it * 10_000L + lead, 0), PlaybackClock(it * 10_000L, 0), extraMs) }.last()
        }
        // Twelve seconds longer: an intro, an outro, or some of each.
        assertEquals(12_000L, confirmed(12_000, 12_000))
        assertEquals(0L, confirmed(0, 12_000))
        assertEquals(5_000L, confirmed(5_000, 12_000))
        assertNull(confirmed(25_000, 12_000))
        // Twenty seconds shorter, its intro cut: it's behind the words.
        assertEquals(-15_000L, confirmed(-15_000, -20_000))
        assertNull(confirmed(-30_000, -20_000))
        // Lengths not known, only a video's intro is believed, and never more than a minute.
        assertNull(confirmed(-5_000, null))
        assertNull(confirmed(70_000, 100_000))
    }

    @Test fun separateMovingHearingsConfirmAnIntroAndSlipsDoNot() {
        val timing = LeadTiming()
        fun heard(position: Long, lead: Long = 10_000, videoPlaying: Boolean = true, songPlaying: Boolean = true) =
            timing.heard(PlaybackClock(position + lead, position, playing = videoPlaying), PlaybackClock(position, position, playing = songPlaying))
        assertNull(heard(0, videoPlaying = false))
        assertNull(heard(0, songPlaying = false))
        assertNull(heard(0, 60_001))
        assertNull(heard(0))
        assertNull(heard(2_999))
        assertNull(heard(10_000))
        assertEquals(10_000L, heard(20_000))
        assertEquals(10_000L, heard(30_000, 40_000))
        repeat(7) { heard(40_000 + it * 10_000L) }
        assertEquals(10_000L, heard(120_000))
    }
}
