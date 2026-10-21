package com.astrovm.crosstune

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Runs under Robolectric for SharedPreferences and the real org.json. */
@RunWith(RobolectricTestRunner::class)
class SavedLinesPracticeTest {

    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("practice-test", Context.MODE_PRIVATE)
    private val line = SavedLine("風が噂をはこんでく", translation = "El viento trae rumores", title = "November Blue", artist = "S.Kiyotaka & Omega Tribe")
    private val day = 24 * 60 * 60 * 1000L

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
    }

    private val minute = 60 * 1000L

    @Test
    fun aNewLineIsLearnedInTwoStepsThenComesBackAfterADay() {
        val store = SavedLinesStore(prefs)
        store.toggle(line)
        // As in Anki: back in 10 minutes, then, known again, after a day.
        val first = store.practised(line, Grade.GOOD, now = 0).single()
        assertEquals(1, first.step)
        assertEquals(0, first.interval)
        assertEquals(10 * minute, first.due)
        val learned = store.practised(first, Grade.GOOD, now = 10 * minute).single()
        assertEquals(0, learned.step)
        assertEquals(1, learned.interval)
        assertEquals(10 * minute + day, learned.due)
        // Kept as practised; another line isn't touched.
        assertEquals(learned, SavedLinesStore(prefs).load().single())
        assertEquals(listOf(learned), store.practised(line.copy(text = "Other"), Grade.GOOD, now = 0))
    }

    @Test
    fun aNewLineMissedOrKnownWithTroubleStaysAMinuteOrTenAway() {
        val missed = SavedLinesStore.answered(line, Grade.AGAIN, now = 0)
        assertEquals(0, missed.step)
        assertEquals(minute, missed.due)
        val hard = SavedLinesStore.answered(line, Grade.HARD, now = 0)
        assertEquals(0, hard.step)
        assertEquals(330_000L, hard.due)
        // On its second step, trouble keeps it there; a miss starts the steps over.
        val second = line.copy(step = 1)
        assertEquals(10 * minute, SavedLinesStore.answered(second, Grade.HARD, now = 0).due)
        assertEquals(minute, SavedLinesStore.answered(second, Grade.AGAIN, now = 0).due)
        assertEquals(0, SavedLinesStore.answered(second, Grade.AGAIN, now = 0).step)
    }

    @Test
    fun aNewLineKnownEasilyComesBackAfterFourDays() {
        val kept = SavedLinesStore.answered(line, Grade.EASY, now = 0)
        assertEquals(4, kept.interval)
        assertEquals(4 * day, kept.due)
        assertEquals(0, kept.step)
        assertEquals(250, kept.ease)
    }

    @Test
    fun aLineKnownGrowsByItsEaseEachTimeAsInAnki() {
        val ten = line.copy(interval = 10)
        assertEquals(12 * day, SavedLinesStore.waitFor(ten, Grade.HARD))
        assertEquals(25 * day, SavedLinesStore.waitFor(ten, Grade.GOOD))
        assertEquals(33 * day, SavedLinesStore.waitFor(ten, Grade.EASY))
        // Trouble makes it grow slower from then on, and easy faster.
        assertEquals(235, SavedLinesStore.answered(ten, Grade.HARD, now = 0).ease)
        assertEquals(250, SavedLinesStore.answered(ten, Grade.GOOD, now = 0).ease)
        assertEquals(265, SavedLinesStore.answered(ten, Grade.EASY, now = 0).ease)
        assertEquals(25, SavedLinesStore.answered(ten, Grade.GOOD, now = 0).interval)
        // Never by less than a day more than the answer before it.
        listOf(1, 2, 3).forEach { days ->
            val waits = Grade.entries.drop(1).map { SavedLinesStore.waitFor(line.copy(interval = days), it) }
            assertEquals("interval $days: $waits", waits.sorted().distinct(), waits)
            assertEquals(true, waits.all { it > days * day })
        }
    }

    @Test
    fun aLineMissedAfterBeingKnownRelearnsInTenMinutesThenComesBackAfterADay() {
        val lapsed = SavedLinesStore.answered(line.copy(interval = 40), Grade.AGAIN, now = 0)
        assertEquals(1, lapsed.interval)
        assertEquals(1, lapsed.step)
        assertEquals(230, lapsed.ease)
        assertEquals(10 * minute, lapsed.due)
        // Relearning, known brings it back after a day, with trouble in another ten minutes, easy in two days.
        assertEquals(day, SavedLinesStore.answered(lapsed, Grade.GOOD, now = 0).due)
        assertEquals(0, SavedLinesStore.answered(lapsed, Grade.GOOD, now = 0).step)
        assertEquals(10 * minute, SavedLinesStore.answered(lapsed, Grade.HARD, now = 0).due)
        assertEquals(10 * minute, SavedLinesStore.answered(lapsed, Grade.AGAIN, now = 0).due)
        assertEquals(2 * day, SavedLinesStore.answered(lapsed, Grade.EASY, now = 0).due)
    }

    @Test
    fun aLineNeverGrowsPastAHundredYearsOrEasesBelowAFloor() {
        val old = line.copy(interval = 36_000, ease = 130)
        assertEquals(36_500 * day, SavedLinesStore.waitFor(old, Grade.EASY))
        assertEquals(130, SavedLinesStore.answered(old, Grade.AGAIN, now = 0).ease)
        assertEquals(130, SavedLinesStore.answered(old, Grade.HARD, now = 0).ease)
    }

    @Test
    fun withAllTheRoomTakenTheLineLearnedBestMakesRoom() {
        val store = SavedLinesStore(prefs)
        // The oldest is still being learned; two in the middle are known best, the older of them first.
        repeat(SavedLinesStore.MAX) { store.toggle(line.copy(text = "Line $it")) }
        listOf("Line 5", "Line 9").forEach { text ->
            repeat(3) { store.practised(store.load().first { it.text == text }, Grade.GOOD, now = 0) }
        }
        val kept = store.toggle(line.copy(text = "New"))
        assertEquals(SavedLinesStore.MAX, kept.size)
        assertEquals("New", kept.first().text)
        assertEquals(false, kept.any { it.text == "Line 5" })
        assertEquals(true, kept.any { it.text == "Line 9" } && kept.any { it.text == "Line 0" })
    }

    @Test
    fun linesSavedBeforePracticeAreDueRightAway() {
        prefs.edit().putString("saved_lines", """[{"text":"風","translation":"Viento","title":"T","artist":"A"}]""").commit()
        val old = SavedLinesStore(prefs).load().single()
        assertEquals(0, old.interval)
        assertEquals(0L, old.due)
    }

    @Test
    fun savedLinesKeptBrokenAreSkippedAndSavingStillWorks() {
        // Not a list at all: nothing kept, and saving starts over.
        prefs.edit().putString("saved_lines", "{not json").commit()
        val store = SavedLinesStore(prefs)
        assertEquals(emptyList<SavedLine>(), store.load())
        assertEquals(listOf(line.copy(due = 0)), store.toggle(line))

        // A list with bits that aren't lines: only the whole ones come back, with what's missing left out.
        prefs.edit().putString("saved_lines", """[null,"x",{"text":""},{"title":"No text"},{"text":"Kept","reading":"","box":"two"}]""").commit()
        assertEquals(listOf(SavedLine("Kept", title = "", artist = "")), store.load())
    }

    @Test
    fun aLineKeepsWhereItsSungTheAppItPlayedInItsCoverAndItsLine() {
        val store = SavedLinesStore(prefs)
        val timed = line.copy(startMs = 10_000, endMs = 20_000, leadMs = 3_000, app = "com.example.music", artworkUrl = "https://example.com/cover.jpg", line = "風が噂をはこんでく夜", typed = "kaze ga uwasa wo")
        store.toggle(timed)
        assertEquals(timed, SavedLinesStore(prefs).load().single())
        assertTrue(timed.timed && timed.practisable)
        // A start at 0 is a start; one missing, or broken, isn't.
        prefs.edit().putString("saved_lines", """[{"text":"A","title":"T","artist":"B","start":0,"end":5},{"text":"B","title":"T","artist":"B","start":"x","end":5}]""").commit()
        val (zero, broken) = store.load()
        assertTrue(zero.timed)
        assertFalse(broken.timed || broken.practisable)
    }

    @Test
    fun aWholeSongsLinesAreKeptInTheirOrderWithoutTheOnesKeptAlready() {
        val store = SavedLinesStore(prefs)
        store.toggle(line)
        val kept = store.addAll(listOf(line.copy(text = "One"), line, line.copy(text = "Two"), line.copy(text = "One")))
        assertEquals(listOf("One", "Two", line.text), kept.map { it.text })
        // With all the room taken, the lines learned best make room for them.
        repeat(SavedLinesStore.MAX) { store.toggle(line.copy(text = "Line $it")) }
        store.practised(store.load().first { it.text == "Line 3" }, Grade.EASY, now = 0)
        val full = store.addAll(listOf(line.copy(text = "New")))
        assertEquals(SavedLinesStore.MAX, full.size)
        assertEquals("New", full.first().text)
        assertFalse(full.any { it.text == "Line 3" })
        // Learned as well as each other, the one kept longest goes.
        val oldest = full.last().text
        val fuller = store.addAll(listOf(line.copy(text = "Newer")))
        assertEquals(full.dropLast(1).map { it.text }, fuller.drop(1).map { it.text })
        assertFalse(fuller.any { it.text == oldest })
    }

    @Test
    fun aLineKeptBeforeItCouldBeTimedIsTimedLaterOnce() {
        val store = SavedLinesStore(prefs)
        store.toggle(line.copy(artworkUrl = "https://example.com/old.jpg"))
        val timed = store.timed(line.copy(startMs = 1_000, endMs = 2_000, app = "com.example.music", artworkUrl = "https://example.com/new.jpg")).single()
        assertEquals(1_000L, timed.startMs)
        assertEquals("com.example.music", timed.app)
        // The cover it had stays.
        assertEquals("https://example.com/old.jpg", timed.artworkUrl)
        // Timed already, it stays as it was; with no app known, the one it had stays too.
        assertEquals(timed, store.timed(line.copy(startMs = 9_000, endMs = 9_500)).single())
        store.remove(timed)
        store.toggle(line.copy(app = "com.example.old"))
        assertEquals("com.example.old", store.timed(line.copy(startMs = 1_000, endMs = 2_000)).single().app)
    }

    @Test
    fun linesLetGoTogetherAreEachTheSameLineOfTheSameSong() {
        val store = SavedLinesStore(prefs)
        val other = line.copy(text = "Other line")
        val elsewhere = line.copy(title = "Other song")
        store.addAll(listOf(line, other, elsewhere))
        // The same line, read or timed otherwise since, is still it; the same words of another song aren't.
        assertEquals(listOf(elsewhere), store.removeAll(listOf(line.copy(reading = "read", startMs = 1), other)))
        assertEquals(listOf(elsewhere), store.load())
        assertTrue(elsewhere.isOf(MusicMetadata("Other song", line.artist)))
        assertFalse(elsewhere.isOf(MusicMetadata(line.title, line.artist)))
        assertFalse(elsewhere.isOf(MusicMetadata("Other song", "Someone else")))
    }

    @Test
    fun howALineReadsIsKeptPieceByPiece() {
        val store = SavedLinesStore(prefs)
        val ruby = listOf(Ruby("風", "かぜ"), Ruby("が"), Ruby("噂", "うわさ"), Ruby("をはこんでく"))
        store.toggle(line.copy(reading = "かぜがうわさをはこんでく", ruby = ruby))
        assertEquals(ruby, SavedLinesStore(prefs).load().single().ruby)
        // None kept, or none at all, it has none.
        store.toggle(line)
        store.toggle(line.copy(ruby = emptyList()))
        assertEquals(null, SavedLinesStore(prefs).load().single().ruby)
    }

    @Test
    fun aLineIsPractisedWithSomethingToRecall() {
        val bare = line.copy(translation = null)
        assertFalse(bare.practisable)
        assertTrue(bare.copy(reading = "かぜ").practisable)
        assertTrue(bare.copy(startMs = 0, endMs = 1_000).practisable)
        assertTrue(line.practisable)
    }

    @Test
    fun howLongALineWaitsDependsOnHowWellItsKnown() {
        assertEquals(minute, SavedLinesStore.waitFor(line, Grade.AGAIN))
        assertEquals(330_000L, SavedLinesStore.waitFor(line, Grade.HARD))
        assertEquals(10 * minute, SavedLinesStore.waitFor(line, Grade.GOOD))
        assertEquals(4 * day, SavedLinesStore.waitFor(line, Grade.EASY))
        assertEquals(10 * minute, SavedLinesStore.waitFor(line.copy(interval = 7), Grade.AGAIN))
        assertEquals(8 * day, SavedLinesStore.waitFor(line.copy(interval = 7), Grade.HARD))
        assertEquals(18 * day, SavedLinesStore.waitFor(line.copy(interval = 7), Grade.GOOD))
    }

    @Test
    fun linesSavedBeforeAnkisWayKeepHowLongTheirBoxMadeThemWait() {
        prefs.edit().putString("saved_lines", """[{"text":"A","title":"T","artist":"A","box":3},{"text":"B","title":"T","artist":"A","box":9},{"text":"C","title":"T","artist":"A","box":0}]""").commit()
        assertEquals(listOf(7, 150, 0), SavedLinesStore(prefs).load().map { it.interval })
        assertEquals(List(3) { 250 }, SavedLinesStore(prefs).load().map { it.ease })
    }

    private val night = SavedLine(
        "都会の夜", reading = "とかいのよる", romanized = "tokai no yoru", title = "Demo", artist = "Band",
        ruby = listOf(Ruby("都会", "とかい"), Ruby("の"), Ruby("夜", "よる"))
    )

    @Test
    fun aLineReadOtherwiseByHandIsReadSoOnItsKanjiInItsKanaAndInRomaji() {
        assertEquals(listOf(Ruby("都会", "とかい"), Ruby("夜", "よる")), night.readable)
        val read = night.readAs(listOf("まち", "よる"))
        assertEquals(listOf(Ruby("都会", "まち"), Ruby("の"), Ruby("夜", "よる")), read.ruby)
        assertEquals("まちのよる", read.reading)
        assertEquals("machi no yoru", read.romanized)
        assertTrue(read.readByHand)
        // As typed, the same: it's only kept where it's spelled otherwise.
        assertEquals(null, read.typed)
        val typed = night.copy(reading = "せんせいのよる", romanized = "sensee no yoru", typed = "sensei no yoru", ruby = listOf(Ruby("先生", "せんせい"), Ruby("の"), Ruby("夜", "よる")))
        val readTyped = typed.readAs(listOf("せんせい", "ばん"))
        assertEquals("sensee no ban", readTyped.romanized)
        assertEquals("sensei no ban", readTyped.typed)
        // In katakana, it's read the same.
        assertEquals(read, night.readAs(listOf(" マチ ", "よる")))
        // Not kana, or the same, nothing changes.
        assertEquals(night, night.readAs(listOf("machi", "よる")))
        assertEquals(night, night.readAs(listOf("とかい", "よる")))
        assertEquals(night, night.readAs(listOf("", "")))
    }

    @Test
    fun aWordReadOtherwiseByHandIsReadSoAsAWhole() {
        val word = SavedLine("都会", reading = "とかい", romanized = "tokai", title = "Demo", artist = "Band", line = "都会の夜")
        assertEquals(listOf(Ruby("都会", "とかい")), word.readable)
        assertEquals(word.copy(reading = "まち", romanized = "machi", readByHand = true), word.readAs(listOf("まち")))
        assertEquals(word, word.readAs(listOf("machi")))
        assertEquals(word, word.readAs(emptyList()))
        // A line with nothing to read otherwise has nothing to put right.
        assertEquals(emptyList<Ruby>(), line.readable)
        assertEquals(line, line.readAs(listOf("かぜ")))
    }

    @Test
    fun linesReadAgainTakeTheNewReadingsButNotOnesPutRightByHand() {
        val store = SavedLinesStore(prefs)
        val morning = SavedLine("都会の朝", reading = "とかいのあさ", title = "Demo", artist = "Band", ruby = listOf(Ruby("都会", "とかい"), Ruby("の"), Ruby("朝", "あさ")))
        store.addAll(listOf(night, morning))
        store.readAs(morning.readAs(listOf("とかい", "あした")))
        val sung = night.readAs(listOf("まち", "よる")).copy(readByHand = false)
        val again = store.reread(listOf(sung, morning.copy(reading = "まちのあさ", ruby = listOf(Ruby("都会", "まち"), Ruby("の"), Ruby("朝", "あさ")))))
        assertEquals("まちのよる", again.first { it.text == night.text }.reading)
        assertEquals(sung.ruby, again.first { it.text == night.text }.ruby)
        assertFalse(again.first { it.text == night.text }.readByHand)
        // Put right by hand, it stays so, after the phone's restarted too.
        val kept = SavedLinesStore(prefs).load().first { it.text == morning.text }
        assertEquals("とかいのあした", kept.reading)
        assertTrue(kept.readByHand)
        assertFalse(SavedLinesStore(prefs).load().first { it.text == night.text }.readByHand)
    }

    @Test
    fun linesOfAJapaneseSongKeptAsAChineseSiteWroteThemLoadAsJapanWritesThem() {
        // Kept before, from words NetEase wrote China's way: 瞬间 for 瞬間, 仆 for 僕, 终 for 終, with China's comma.
        val moment = SavedLine(
            "真実の瞬间はいつも，", reading = "しんじつのまどか间はいつも，", romanized = "shinjitsu no madoka 间 wa itsumo,", translation = "El momento", title = "Song", artist = "Band", interval = 3,
            ruby = listOf(Ruby("真実", "しんじつ"), Ruby("の"), Ruby("瞬", "まどか"), Ruby("间はいつも，"))
        )
        val me = SavedLine("仆", reading = "ふ", translation = "yo", title = "Song", artist = "Band", line = "仆や君のため，")
        val byHand = SavedLine("终わらない，", reading = "おわらない，", title = "Song", artist = "Band", ruby = listOf(Ruby("终", "お"), Ruby("わらない，")), readByHand = true)
        val chinese = SavedLine("我爱你的时间，", reading = "wǒ ài nǐ de shí jiān，", title = "时间", artist = "Band")
        SavedLinesStore(prefs).addAll(listOf(moment, me, byHand, chinese))
        val loaded = SavedLinesStore(prefs).load()
        // Their readings, worked out from China's kanji, are left to be worked out again...
        assertEquals(moment.copy(text = "真実の瞬間はいつも", reading = null, romanized = null, ruby = null), loaded[0])
        assertEquals(me.copy(text = "僕", reading = null, line = "僕や君のため"), loaded[1])
        // ...but for those put right by hand.
        assertEquals(byHand.copy(text = "終わらない", ruby = listOf(Ruby("終", "お"), Ruby("わらない"))), loaded[2])
        // A Chinese song's are as China writes them.
        assertEquals(chinese, loaded[3])
    }
}
