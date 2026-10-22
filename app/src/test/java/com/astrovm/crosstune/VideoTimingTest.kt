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
class VideoTimingTest {
    private val prefs = ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("video-test", Context.MODE_PRIVATE).apply { edit().clear().commit() }
    private val song = MusicMetadata("Demo", "Band", recordingId = "app:video")

    @Test fun recordingsKeepTheirOwnConfirmedIntro() {
        val store = VideoTimingStore(prefs)
        assertNull(store.load(song))
        store.save(song, 10_000)
        assertEquals(10_000L, VideoTimingStore(prefs).load(song))
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
        val store = VideoTimingStore(prefs)
        repeat(201) { store.save(song.copy(recordingId = "app:$it"), it.toLong()) }
        assertNull(store.load(song.copy(recordingId = "app:0")))
        assertEquals(200L, store.load(song.copy(recordingId = "app:200")))
    }

    @Test fun anIntroMustAgreeWithTheRecordingsAndCannotBeAReferenceAheadOfTheVideo() {
        val timing = VideoTiming()
        val video = PlaybackClock(20_000, 0)
        val song = PlaybackClock(10_000, 0)
        assertNull(timing.heard(PlaybackClock(0, 0), song))
        assertNull(timing.heard(video, song, expectedLeadMs = 30_000))
        assertNull(timing.heard(video, song, expectedLeadMs = 18_000))
        assertNull(timing.heard(PlaybackClock(30_000, 10_000), PlaybackClock(20_000, 10_000), expectedLeadMs = 10_000))
        assertEquals(10_000L, timing.heard(PlaybackClock(40_000, 20_000), PlaybackClock(30_000, 20_000), expectedLeadMs = 2_000))
    }

    @Test fun separateMovingHearingsConfirmAnIntroAndSlipsDoNot() {
        val timing = VideoTiming()
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
