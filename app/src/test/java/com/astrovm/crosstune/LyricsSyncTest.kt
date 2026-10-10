package com.astrovm.crosstune

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Runs under Robolectric for SharedPreferences and the real org.json implementation. */
@RunWith(RobolectricTestRunner::class)
class LyricsSyncTest {

    private val lines = listOf(LyricLine(10_000, "One"), LyricLine(20_000, "Two"), LyricLine(30_000, "Three"), LyricLine(40_000, "Four"))
    private val song = MusicMetadata("Asphalt Lady (2018 Remix)", "S.Kiyotaka & Omega Tribe")
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("sync-test", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
    }

    @Test
    fun noLinePutInTimeLeavesTheWordsAsFound() {
        assertEquals(lines, LyricsSync.adjust(lines, emptyList()))
    }

    @Test
    fun oneLinePutInTimeMovesThemAll() {
        // "Two" was heard 3 seconds later than the words had it.
        val later = LyricsSync.adjust(lines, listOf(SyncPoint(20_000, 23_000)))
        assertEquals(listOf(13_000L, 23_000L, 33_000L, 43_000L), later.map { it.timeMs })
        // And sooner.
        assertEquals(listOf(8_000L, 18_000L, 28_000L, 38_000L), LyricsSync.adjust(lines, listOf(SyncPoint(20_000, 18_000))).map { it.timeMs })
        assertEquals(lines.map { it.text }, later.map { it.text })
    }

    @Test
    fun betweenTwoLinesPutInTimeTheOthersStretchToFit() {
        // "One" is right, "Three" came 10 seconds later: "Two" falls halfway, past "Three" all move by 10.
        val points = listOf(SyncPoint(10_000, 10_000), SyncPoint(30_000, 40_000))
        assertEquals(listOf(10_000L, 25_000L, 40_000L, 50_000L), LyricsSync.adjust(lines, points).map { it.timeMs })
        // Before the first line put in time, the lines move with it.
        assertEquals(2_000L, LyricsSync.timeOf(5_000, listOf(SyncPoint(10_000, 7_000), SyncPoint(30_000, 40_000))))
    }

    @Test
    fun aLinePutInTimeAgainOrOutOfOrderReplacesWhatItContradicts() {
        var points = LyricsSync.with(emptyList(), SyncPoint(20_000, 23_000))
        points = LyricsSync.with(points, SyncPoint(40_000, 45_000))
        assertEquals(listOf(SyncPoint(20_000, 23_000), SyncPoint(40_000, 45_000)), points)
        // The same line again: the newer one counts.
        points = LyricsSync.with(points, SyncPoint(20_000, 21_000))
        assertEquals(listOf(SyncPoint(20_000, 21_000), SyncPoint(40_000, 45_000)), points)
        // "Three" heard after where "Four" was put: "Four" goes, so the words keep their order.
        points = LyricsSync.with(points, SyncPoint(30_000, 46_000))
        assertEquals(listOf(SyncPoint(20_000, 21_000), SyncPoint(30_000, 46_000)), points)
        // "One" heard after "Two" was: "Two" goes.
        points = LyricsSync.with(points, SyncPoint(10_000, 22_000))
        assertEquals(listOf(SyncPoint(10_000, 22_000), SyncPoint(30_000, 46_000)), points)
        // Heard at the same moment as another line is no order at all.
        assertEquals(listOf(SyncPoint(20_000, 22_000), SyncPoint(30_000, 46_000)), LyricsSync.with(points, SyncPoint(20_000, 22_000)))
    }

    @Test
    fun linesPutInTimeAreKeptForTheSameWordsOnly() {
        val store = LyricsSyncStore(prefs)
        assertEquals(emptyList<SyncPoint>(), store.load(song, lines))
        val points = listOf(SyncPoint(10_000, 12_000), SyncPoint(30_000, 33_000))
        store.save(song, lines, points)
        assertEquals(points, LyricsSyncStore(prefs).load(song, lines))
        // The same song however its name is written.
        assertEquals(points, store.load(song.copy(title = "ASPHALT LADY (2018 REMIX)"), lines))
        // Other words for it, or another song, start over.
        assertEquals(emptyList<SyncPoint>(), store.load(song, lines.dropLast(1)))
        assertEquals(emptyList<SyncPoint>(), store.load(song, lines.map { it.copy(timeMs = it.timeMs + 1) }))
        assertEquals(emptyList<SyncPoint>(), store.load(MusicMetadata("Joanna", "S.Kiyotaka & Omega Tribe"), lines))
        // None left: nothing is kept.
        store.save(song, lines, emptyList())
        assertEquals(emptyList<SyncPoint>(), store.load(song, lines))
        assertEquals("{}", prefs.getString("lyrics_sync", null))
    }

    @Test
    fun onlyTheLatestSongsAreKept() {
        val store = LyricsSyncStore(prefs)
        repeat(201) { store.save(MusicMetadata("Song $it", "Band"), lines, listOf(SyncPoint(10_000, 11_000 + it.toLong()))) }
        assertEquals(emptyList<SyncPoint>(), store.load(MusicMetadata("Song 0", "Band"), lines))
        assertEquals(listOf(SyncPoint(10_000, 11_001)), store.load(MusicMetadata("Song 1", "Band"), lines))
        assertEquals(listOf(SyncPoint(10_000, 11_200)), store.load(MusicMetadata("Song 200", "Band"), lines))
    }

    @Test
    fun keptLinesThatCantBeReadStartOver() {
        prefs.edit().putString("lyrics_sync", "not json").commit()
        val store = LyricsSyncStore(prefs)
        assertEquals(emptyList<SyncPoint>(), store.load(song, lines))
        store.save(song, lines, listOf(SyncPoint(10_000, 9_000)))
        assertEquals(listOf(SyncPoint(10_000, 9_000)), store.load(song, lines))
    }
}
