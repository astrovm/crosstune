package com.astrovm.crosstune

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.json.JSONArray
import org.json.JSONObject
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
    fun aLineTimedWordByWordMovesWithItsWords() {
        val line = LyricLine(20_000, "Two words", listOf(TimedWord(20_000, "Two "), TimedWord(20_500, "words")), endMs = 21_000)
        val moved = LyricsSync.adjust(listOf(line), listOf(SyncPoint(20_000, 23_000))).single()
        assertEquals(listOf(23_000L, 23_500L), moved.words.map { it.timeMs })
        // Where it ends moves too; one with no end of its own stays so.
        assertEquals(24_000L, moved.endMs)
        assertEquals(null, LyricsSync.adjust(listOf(line.copy(endMs = null)), listOf(SyncPoint(20_000, 23_000))).single().endMs)
        assertEquals(line.words.map { it.text }, moved.words.map { it.text })
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
    fun pastTheLinesPutInTimeTheOthersGoOnAtTheirPace() {
        // Timed for a take sung 5% slower: each line heard a little off, but along one pace.
        val points = listOf(SyncPoint(10_000, 13_100), SyncPoint(20_000, 22_400), SyncPoint(40_000, 41_600))
        assertEquals(22_400L, LyricsSync.timeOf(20_000, points))
        assertEquals(60_585L, LyricsSync.timeOf(60_000, points))
        assertEquals(3_500L, LyricsSync.timeOf(0, points))
        // Just past the last, where the pace would put it before that one, it stays after it.
        assertEquals(40_000L, LyricsSync.timeOf(40_100, listOf(SyncPoint(10_000, 10_000), SyncPoint(20_000, 19_000), SyncPoint(40_000, 40_000))))
        // So far off a pace it's lines timed out of place: past the last, they keep to it.
        assertEquals(41_700L, LyricsSync.timeOf(40_100, listOf(SyncPoint(10_000, 10_000), SyncPoint(20_000, 20_000), SyncPoint(40_000, 41_600))))
        // Too close together to tell a pace, they keep to the nearest.
        assertEquals(51_600L, LyricsSync.timeOf(50_000, listOf(SyncPoint(30_000, 30_000), SyncPoint(40_000, 41_600))))
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
    fun correctionsBelongToOneRecordingOnly() {
        val store = LyricsSyncStore(prefs)
        val one = song.copy(recordingId = "player:one", lengthMs = 200_000)
        val points = listOf(SyncPoint(10_000, 12_000))
        store.save(one, lines, points)
        assertEquals(points, store.load(one, lines))
        assertEquals(emptyList<SyncPoint>(), store.load(one.copy(recordingId = "player:two"), lines))
        assertEquals(emptyList<SyncPoint>(), store.load(one.copy(lengthMs = 250_000), lines))
        assertEquals(emptyList<SyncPoint>(), store.load(song, lines))
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

    /** A line the words have at [lineMs], heard [shiftMs] later. */
    private fun heardLater(lineMs: Long, shiftMs: Long) = SyncPoint(lineMs, lineMs + shiftMs)

    @Test
    fun aLineHeardFarFromWhereTheOnesAroundItAgreeIsASlipOfTheFinger() {
        val steady = listOf(heardLater(10_000, 10_400), heardLater(20_000, 10_500), heardLater(30_000, 10_300), heardLater(40_000, 10_450))
        assertEquals(steady, LyricsSync.withoutSlips(steady))
        // Two can't outvote one, and no lines is no slip.
        assertEquals(steady.take(2), LyricsSync.withoutSlips(steady.take(2)))
        assertEquals(emptyList<SyncPoint>(), LyricsSync.withoutSlips(emptyList()))
        // In the middle, first and last: the others agree it's far off.
        assertEquals(listOf(steady[0], steady[2], steady[3]), LyricsSync.withoutSlips(listOf(steady[0], heardLater(20_000, 4_900), steady[2], steady[3])))
        assertEquals(steady.drop(1), LyricsSync.withoutSlips(listOf(heardLater(10_000, 3_000)) + steady.drop(1)))
        assertEquals(steady.dropLast(1), LyricsSync.withoutSlips(steady.dropLast(1) + heardLater(40_000, 20_000)))
        // A little off is only a little off, and the lines around one that disagree have no say.
        val little = listOf(steady[0], heardLater(20_000, 12_000), steady[2], steady[3])
        assertEquals(little, LyricsSync.withoutSlips(little))
        val unsure = listOf(heardLater(10_000, 10_400), heardLater(20_000, 4_900), heardLater(30_000, 15_000), heardLater(40_000, 10_450))
        assertEquals(unsure, LyricsSync.withoutSlips(unsure))
    }

    @Test
    fun aSlipIsLeftOutWhenALineIsPutInTimeAndWhenTheLinesAreKept() {
        val points = listOf(heardLater(10_000, 10_400), heardLater(30_000, 10_300), heardLater(40_000, 10_500))
        assertEquals(points, LyricsSync.with(points, heardLater(20_000, 4_900)))
        // A slip doesn't take a line it contradicts with it, here the last, which it puts after itself.
        val stretched = listOf(heardLater(10_000, 0), heardLater(20_000, 0), heardLater(30_000, 2_000))
        assertEquals(stretched, LyricsSync.with(stretched, heardLater(25_000, 25_000)))
        assertEquals(listOf(points[0], heardLater(20_000, 10_350), points[1], points[2]), LyricsSync.with(points, heardLater(20_000, 10_350)))
        val store = LyricsSyncStore(prefs)
        store.save(song, lines, listOf(heardLater(10_000, 10_400), heardLater(20_000, 4_900), heardLater(30_000, 10_300), heardLater(40_000, 10_500)))
        assertEquals(points, store.load(song, lines))
    }

    @Test
    fun keptLinesMissingTheirPointsOrWithBrokenOnesKeepWhatIsLeft() {
        val store = LyricsSyncStore(prefs)
        store.save(song, lines, listOf(SyncPoint(10_000, 12_000)))
        val all = JSONObject(prefs.getString("lyrics_sync", null)!!)
        val key = all.keys().next()
        val kept = all.getJSONObject(key)
        kept.put("points", JSONArray("""[3, [30000, 33000]]"""))
        prefs.edit().putString("lyrics_sync", all.toString()).commit()
        assertEquals(listOf(SyncPoint(30_000, 33_000)), store.load(song, lines))
        kept.remove("points")
        prefs.edit().putString("lyrics_sync", all.toString()).commit()
        assertEquals(emptyList<SyncPoint>(), store.load(song, lines))
    }
}
