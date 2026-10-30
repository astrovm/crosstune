package com.astrovm.crosstune

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

/** Hearing the words sung, and how far they are from the timed words. Robolectric for SharedPreferences. */
@RunWith(RobolectricTestRunner::class)
class SungWordsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("sung-test", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
    }

    @Test
    fun wordsHeardAreReadFromWhatWhisperGives() {
        val bytes = "0\tFirst\n320\t Oh,\n820\tshow\n-1\tlost\nnot a time\tskipped\n900\t \n1200\tcorazón\n".toByteArray()
        assertEquals(listOf(HeardWord("First", 0), HeardWord("Oh,", 320), HeardWord("show", 820), HeardWord("corazón", 1200)), HeardWords.parse(bytes))
        assertEquals(emptyList<HeardWord>(), HeardWords.parse(ByteArray(0)))
    }

    /** [lines] timed a line at a time, from [startMs], [gapMs] apart. */
    private fun timed(vararg lines: String, startMs: Long = 0, gapMs: Long = 3_000) =
        lines.mapIndexed { index, text -> LyricLine(startMs + index * gapMs, text) }

    /** Each line's words heard sung, from the song's start, [shiftMs] later than [lines] have them, a little late as whisper.cpp hears them. */
    private fun heard(lines: List<LyricLine>, shiftMs: Long, wordMs: Long = 250) = Sung(0, lines.flatMap { line ->
        line.text.split(' ').mapIndexed { index, word -> HeardWord(word, line.timeMs + shiftMs + SungAlignment.HEARD_LATE_MS + index * wordMs) }
    })

    private val song = timed("Sing to me tonight", "Under the yellow moon", "Every river finds the sea", "Hold the morning light", "Dancing on the water", "Never let it go")

    /** Where the song is a moment into what was heard: here, the same, heard from its start. */
    private val fromTheStart: (Long) -> Long = { it }

    @Test
    fun wordsSungEarlierOrLaterThanTimedSayByHowMuch() {
        assertEquals(-1_300L, SungAlignment.shiftMs(heard(song, -1_300), fromTheStart, song))
        assertEquals(800L, SungAlignment.shiftMs(heard(song, 800), fromTheStart, song))
        assertEquals(0L, SungAlignment.shiftMs(heard(song, 0), fromTheStart, song))
        // Heard while the song was elsewhere, where it was is what counts.
        val later = Sung(5_000, heard(song, 800).words)
        assertEquals(800L, SungAlignment.shiftMs(later, { it - 5_000 }, song))
    }

    @Test
    fun wordsAreMatchedHoweverTheyreWrittenOrHeard() {
        val written = timed("¡Corazón, sí!", "Él no sabe", "Dímelo ya", "Qué será", "Mañana es hoy", "Ven aquí")
        val heardAs = listOf("corazon", "Si.", "el", "No", "SABE", "dimelo", "ya", "que", "sera", "manana", "es", "hoy", "ven", "aqui")
        val sung = Sung(0, heard(written, 1_000).words.zip(heardAs) { word, text -> word.copy(text = text) })
        assertEquals(1_000L, SungAlignment.shiftMs(sung, fromTheStart, written))
    }

    @Test
    fun aLineOpeningWithSomethingNotSungIsTimedByItsFirstWordSung() {
        val written = song.map { it.copy(text = "♪ - " + it.text) }
        assertEquals(-400L, SungAlignment.shiftMs(heard(song, -400), fromTheStart, written))
    }

    @Test
    fun wordsSungBeyondTheTimedOnesAreLeftOut() {
        // Ad-libs after the last line, and before the first.
        val sung = heard(song, 700).words
        val extra = Sung(0, listOf(HeardWord("yeah", 0)) + sung + listOf(HeardWord("yeah", 19_000), HeardWord("oh", 19_500)))
        assertEquals(700L, SungAlignment.shiftMs(extra, fromTheStart, song))
    }

    @Test
    fun linesTimedWordByWordTellByEachWord() {
        // Two lines only, but every word timed: enough words to tell.
        val words = listOf(
            LyricLine(1_000, "Take me now", listOf(TimedWord(1_000, "Take "), TimedWord(1_400, "me "), TimedWord(1_800, "now"))),
            LyricLine(3_000, "Never let me go", listOf(TimedWord(3_000, "Never "), TimedWord(3_300, "let me "), TimedWord(3_900, "go")))
        )
        val sung = Sung(0, listOf("Take" to 1_000L, "me" to 1_400L, "now" to 1_800L, "Never" to 3_000L, "let" to 3_300L, "me" to 3_600L, "go" to 3_900L).map { (word, at) ->
            HeardWord(word, at - 700 + SungAlignment.HEARD_LATE_MS)
        })
        // "me" in "let me" has no time of its own, so six words tell; five still do, four don't.
        assertEquals(-700L, SungAlignment.shiftMs(sung, fromTheStart, words))
        assertEquals(-700L, SungAlignment.shiftMs(Sung(0, sung.words.take(5)), fromTheStart, words))
        assertNull(SungAlignment.shiftMs(Sung(0, sung.words.take(4)), fromTheStart, words))
    }

    @Test
    fun linesTimedAsFarOffAsCanBeStillTell() {
        // Sung 9.6 s after it's timed, heard 0.4 s late: the first line is 10 s before the first word heard.
        assertEquals(9_600L, SungAlignment.shiftMs(heard(song.take(3), 9_600), fromTheStart, song))
    }

    @Test
    fun eachLineHeardIsPlacedWhereItsSung() {
        // Two seconds later as a whole, and some lines further off by themselves: the third 600 ms later, the fifth 300 ms earlier.
        // The second, 100 ms later, is as near as a word is heard, so it's taken as the rest.
        fun sung(off: List<Long>) = Sung(0, song.flatMapIndexed { index, line -> heard(listOf(line), 2_000 + off[index]).words })
        assertEquals(mapOf(0 to 2_000L, 1 to 5_000L, 2 to 8_600L, 3 to 11_000L, 4 to 13_700L, 5 to 17_000L), SungAlignment.linesMs(sung(listOf(0, 100, 600, 0, -300, 0)), fromTheStart, song, 2_000))
        // One heard much further off than the rest is another line, sung where this one isn't.
        assertFalse(2 in SungAlignment.linesMs(sung(listOf(0, 0, 1_500, 0, 0, 0)), fromTheStart, song, 2_000))
        // Nor is one with a word heard alone, which could be the same word in another line.
        val alone = Sung(0, song.flatMapIndexed { index, line -> heard(listOf(line), 2_000).words.take(if (index == 2) 1 else 9) })
        assertEquals(setOf(0, 1, 3, 4, 5), SungAlignment.linesMs(alone, fromTheStart, song, 2_000).keys)
        // A first word misheard, the word heard just before the rest of the line is where it starts.
        val misheard = Sung(0, sung(listOf(0, 0, 0, 400, 0, 0)).words.map { if (it.text == "Hold") it.copy(text = "Old") else it })
        assertEquals(11_400L, SungAlignment.linesMs(misheard, fromTheStart, song, 2_000)[3])
        // Nor when more of its first words are misheard than a few, as the model may hear fewer words than are sung.
        val tooMany = timed("Sing to me tonight", "Under the yellow moon", "Every river finds the sea", "You say the words that I can't say", "Dancing on the water", "Never let it go")
        val heardAs = Sung(0, tooMany.flatMapIndexed { index, line -> heard(listOf(line), 2_000L + if (index == 3) 400 else 0).words.mapIndexed { word, it -> if (index == 3 && word < 4) it.copy(text = "who") else it } })
        assertEquals(11_000L, SungAlignment.linesMs(heardAs, fromTheStart, tooMany, 2_000)[3])
        // Not heard at all, the word before is the line before's, so it keeps to the rest.
        val missed = Sung(0, sung(listOf(0, 0, 0, 400, 0, 0)).words.filterNot { it.text == "Hold" })
        assertEquals(11_000L, SungAlignment.linesMs(missed, fromTheStart, song, 2_000)[3])
        // So does one whose words are heard too far apart to be sung one after the other.
        val apart = Sung(0, song.flatMapIndexed { index, line ->
            heard(listOf(line), 2_000).words.mapIndexed { word, it -> if (index == 3 && word > 0) it.copy(atMs = it.atMs + 1_000) else it }
        })
        assertEquals(11_000L, SungAlignment.linesMs(apart, fromTheStart, song, 2_000)[3])
        // Heard elsewhere in the song, none are.
        assertEquals(emptyMap<Int, Long>(), SungAlignment.linesMs(Sung(0, heard(song, 2_000).words.map { it.copy(atMs = it.atMs + 60_000) }), fromTheStart, song, 2_000))
        assertEquals(emptyMap<Int, Long>(), SungAlignment.linesMs(Sung(0, listOf(HeardWord("♪", 0))), fromTheStart, song, 0))
    }

    @Test
    fun aLineTimedWordByWordIsPlacedByMostOfItsWords() {
        val words = listOf(
            LyricLine(1_000, "Take me now", listOf(TimedWord(1_000, "Take "), TimedWord(1_400, "me "), TimedWord(1_800, "now"))),
            LyricLine(3_000, "Never let me go", listOf(TimedWord(3_000, "Never "), TimedWord(3_300, "let me "), TimedWord(3_900, "go")))
        )
        fun sung(vararg words: Pair<String, Long>) = Sung(0, words.map { (word, at) -> HeardWord(word, at + SungAlignment.HEARD_LATE_MS) })
        // The second line sung 400 ms later than the first, by its words "let" and "go", whatever "Never" says.
        val heard = sung("Take" to 300L, "me" to 700L, "now" to 1_100L, "Never" to 2_300L, "let" to 3_000L, "me" to 3_300L, "go" to 3_600L)
        assertEquals(mapOf(0 to 300L, 1 to 2_700L), SungAlignment.linesMs(heard, fromTheStart, words, -700))
        // Heard by one of its timed words only, it keeps to the rest.
        assertEquals(mapOf(0 to 300L, 1 to 2_300L), SungAlignment.linesMs(sung("Take" to 300L, "me" to 700L, "now" to 1_100L, "Never" to 2_700L, "me" to 3_300L), fromTheStart, words, -700))
    }

    /** A verse of eight lines, then a chorus of three sung twice, three seconds a line. */
    private val verseAndChorus = timed(
        "Every time I think of you", "I feel shot right through with a bolt of blue", "It is no problem of mine",
        "But it is a problem I find", "Living a life that I cannot leave behind", "There is no sense in telling me",
        "The wisdom of a fool will not set you free", "But that is the way that it goes",
        "Every time I see you falling", "I get down on my knees and pray", "I am waiting for that final moment",
        "Every time I see you falling", "I get down on my knees and pray", "I am waiting for that final moment"
    )

    @Test
    fun linesTimedMuchFurtherOffAreFoundWhereByFarTheMostWordsAgree() {
        // The verse sung 23.6 s later than timed, as YouTube Music's own words for a release with a longer intro.
        assertEquals(23_600L, SungAlignment.shiftMs(heard(verseAndChorus.take(5), 23_600), fromTheStart, verseAndChorus))
        // Or earlier.
        assertEquals(-15_000L, SungAlignment.shiftMs(heard(verseAndChorus.subList(5, 8), -15_000), fromTheStart, verseAndChorus))
        // A chorus sung as much in two places tells nothing that far off.
        assertNull(SungAlignment.shiftMs(heard(verseAndChorus.subList(8, 11), 20_000), fromTheStart, verseAndChorus))
        // Nor do too few words.
        assertNull(SungAlignment.shiftMs(heard(verseAndChorus.take(1), 23_600), fromTheStart, verseAndChorus))
    }

    @Test
    fun tooFewWordsHeardToTell() {
        // Three lines' first words tell, as lines timed a line at a time have few words with a time; two don't.
        assertEquals(500L, SungAlignment.shiftMs(heard(song.take(3), 500), fromTheStart, song))
        assertNull(SungAlignment.shiftMs(heard(song.take(2), 500), fromTheStart, song))
        // Nor do four words of one line, timed word by word.
        val oneLine = listOf(LyricLine(1_000, "Take me home now", listOf(TimedWord(1_000, "Take "), TimedWord(1_400, "me "), TimedWord(1_800, "home "), TimedWord(2_200, "now"))))
        assertNull(SungAlignment.shiftMs(Sung(0, oneLine.single().words.map { HeardWord(it.text.trim(), it.timeMs + 300 + SungAlignment.HEARD_LATE_MS) }), fromTheStart, oneLine))
        // Heard words that aren't the song's, or nothing but sounds, tell nothing.
        assertNull(SungAlignment.shiftMs(Sung(0, listOf(HeardWord("♪", 600), HeardWord("...", 900))), fromTheStart, song))
        assertNull(SungAlignment.shiftMs(Sung(0, emptyList()), fromTheStart, song))
        assertNull(SungAlignment.shiftMs(Sung(0, listOf("la", "la", "la", "la", "la", "la").mapIndexed { index, word -> HeardWord(word, index * 500L) }), fromTheStart, song))
    }

    /** The words of [lines] after their first, sung evenly over the first 2.4 s of the 3 s each line has. */
    private fun heardMidLine(lines: List<LyricLine>, shiftMs: Long) = Sung(0, lines.flatMap { line ->
        val words = line.text.split(' ')
        val letters = words.sumOf { it.length }
        words.indices.drop(1).map { at -> HeardWord(words[at], line.timeMs + shiftMs + SungAlignment.HEARD_LATE_MS + 2_400L * words.take(at).sumOf { it.length } / letters) }
    })

    @Test
    fun wordsHeardOnlyMidLineTellByWhereTheyAreInIt() {
        // Lines timed a line at a time say nothing of their other words, but when they're sung says about where.
        assertEquals(1_500L, SungAlignment.shiftMs(heardMidLine(song.take(3), 1_500), fromTheStart, song))
        // The last line has no next one to say how long it's sung: about 3 s.
        assertEquals(-800L, SungAlignment.shiftMs(heardMidLine(song.takeLast(3), -800), fromTheStart, song))
        // A guess takes eight words, from three lines.
        assertNull(SungAlignment.shiftMs(heardMidLine(song.take(3), 1_500).let { it.copy(words = it.words.drop(2).dropLast(1)) }, fromTheStart, song))
        val long = timed("We will sing and dance all night long", "We will run and hide under the moon", "Far away")
        assertNull(SungAlignment.shiftMs(heardMidLine(long.take(2), 1_500), fromTheStart, long))
    }

    @Test
    fun wordsThatDontAgreeTellNothing() {
        val lines = timed("One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Eleven")
        // Five agree on half a second, six are each off their own way: no most.
        val offs = listOf(500L, 500, 500, 500, 500, 2_000, -1_500, 3_500, -3_000, 5_000, -4_500)
        val sung = Sung(0, lines.zip(offs) { line, off -> HeardWord(line.text, line.timeMs + off + SungAlignment.HEARD_LATE_MS) })
        assertNull(SungAlignment.shiftMs(sung, fromTheStart, lines))
        // Half of them agreeing is enough.
        val half = Sung(0, lines.dropLast(1).zip(offs.take(5) + offs.drop(6)) { line, off -> HeardWord(line.text, line.timeMs + off + SungAlignment.HEARD_LATE_MS) })
        assertEquals(500L, SungAlignment.shiftMs(half, fromTheStart, lines))
        // With one more agreeing, most do.
        val most = Sung(0, lines.zip(offs.take(6).map { 500L } + offs.drop(6)) { line, off -> HeardWord(line.text, line.timeMs + off + SungAlignment.HEARD_LATE_MS) })
        assertEquals(500L, SungAlignment.shiftMs(most, fromTheStart, lines))
    }

    @Test
    fun whereMostAgreeTheMiddleOneCounts() {
        val lines = timed("One", "Two", "Three", "Four", "Five", "Six")
        // Within 400 ms of each other they agree; the one far off has no say.
        val offs = listOf(1_000L, 1_100, 1_200, 1_300, 1_400, 3_000)
        val sung = Sung(0, lines.zip(offs) { line, off -> HeardWord(line.text, line.timeMs + off + SungAlignment.HEARD_LATE_MS) })
        assertEquals(1_200L, SungAlignment.shiftMs(sung, fromTheStart, lines))
        // 400 ms apart still agree: three lines, each 400 ms from the middle one.
        val edge = Sung(0, lines.take(3).zip(listOf(0L, 400, 800)) { line, off -> HeardWord(line.text, line.timeMs + off + SungAlignment.HEARD_LATE_MS) })
        assertEquals(400L, SungAlignment.shiftMs(edge, fromTheStart, lines))
        // 401 ms apart, none agree with another.
        val apart = Sung(0, lines.zip(listOf(0L, 401, 802, 1_203, 1_604, 2_005)) { line, off -> HeardWord(line.text, line.timeMs + off + SungAlignment.HEARD_LATE_MS) })
        assertNull(SungAlignment.shiftMs(apart, fromTheStart, lines))
    }

    @Test
    fun aChorusSungAgainElsewhereIsntMatchedToWhatWasHeard() {
        val chorus = listOf("Hold me close", "Never let me go", "Stay tonight", "Under the stars", "Dance with me")
        // The chorus at the start and again a minute later, where it's heard.
        val lines = timed(*chorus.toTypedArray()) + timed(*chorus.toTypedArray(), startMs = 60_000)
        assertEquals(-900L, SungAlignment.shiftMs(heard(lines.drop(5), -900), fromTheStart, lines))
        assertEquals(-900L, SungAlignment.shiftMs(heard(lines.take(5), -900), fromTheStart, lines))
        // Nor is it when something not a word was heard near the first.
        val withNoise = Sung(0, listOf(HeardWord("♪", 5_000)) + heard(lines.drop(5), -900).words)
        assertEquals(-900L, SungAlignment.shiftMs(withNoise, fromTheStart, lines))
    }

    @Test
    fun wordsTimedOverTenSecondsOffTakeManyWordsToPlace() {
        val lines = timed("Sing to me tonight", "Under the yellow moon", "Every river finds the sea", "Hold the morning light", "Dancing on the water", startMs = 20_000)
        assertEquals(9_400L, SungAlignment.shiftMs(heard(lines, 9_400), fromTheStart, lines))
        assertEquals(-9_000L, SungAlignment.shiftMs(heard(lines, -9_000), fromTheStart, lines))
        assertEquals(-10_000L, SungAlignment.shiftMs(heard(lines, -10_000), fromTheStart, lines))
        // Further off, every line heard places them.
        assertEquals(-10_600L, SungAlignment.shiftMs(heard(lines, -10_600), fromTheStart, lines))
        assertEquals(10_800L, SungAlignment.shiftMs(heard(lines, 10_800), fromTheStart, lines))
        // Three lines still do; two, as near, don't.
        assertEquals(10_800L, SungAlignment.shiftMs(heard(lines.take(3), 10_800), fromTheStart, lines))
        assertNull(SungAlignment.shiftMs(heard(lines.take(2), 10_800), fromTheStart, lines))
    }

    @Test
    fun aSongsWordsAreHeardOnceForTheSameWordsOnly() {
        val store = SungStore(prefs)
        val demo = MusicMetadata("Demo", "Band", recordingId = "player:one", lengthMs = 200_000)
        assertFalse(store.heard(demo, song))
        store.save(demo, song)
        assertTrue(SungStore(prefs).heard(demo, song))
        assertTrue(store.heard(demo.copy(title = "DEMO"), song))
        // Other words, timed or written another way, another recording or another song are heard again.
        assertFalse(store.heard(demo, song.dropLast(1)))
        assertFalse(store.heard(demo, song.map { it.copy(timeMs = it.timeMs + 1) }))
        assertFalse(store.heard(demo, song.map { it.copy(text = it.text + "!") }))
        assertFalse(store.heard(demo.copy(recordingId = "player:two"), song))
        assertFalse(store.heard(MusicMetadata("Other", "Band"), song))
        // Saved again, it's still one.
        store.save(demo, song)
        assertEquals(1, prefs.getString("sung_heard", null)!!.split('\n').size)
    }

    @Test
    fun onlyTheLatestSongsHeardAreKept() {
        val store = SungStore(prefs)
        repeat(301) { store.save(MusicMetadata("Song $it", "Band"), song) }
        assertFalse(store.heard(MusicMetadata("Song 0", "Band"), song))
        assertTrue(store.heard(MusicMetadata("Song 1", "Band"), song))
        assertTrue(store.heard(MusicMetadata("Song 300", "Band"), song))
        // Saved again, the oldest is the newest.
        store.save(MusicMetadata("Song 1", "Band"), song)
        store.save(MusicMetadata("Song 301", "Band"), song)
        assertTrue(store.heard(MusicMetadata("Song 1", "Band"), song))
        assertFalse(store.heard(MusicMetadata("Song 2", "Band"), song))
    }

    /** A model that hears [words], and what it was given. */
    private class FakeWords(private val words: List<HeardWord>) : WordHearing, Closeable {
        var samples: FloatArray? = null
        var language: String? = null
        var closed = false

        override fun words(samples: FloatArray, language: String?): List<HeardWord> {
            this.samples = samples
            this.language = language
            return words
        }

        override fun close() {
            closed = true
        }
    }

    private fun listener(microphone: Microphone, model: File? = File("model"), hearing: WordHearing? = FakeWords(listOf(HeardWord("Oh", 320))), modelAsked: () -> Unit = {}) =
        SingingListener(microphone, { modelAsked(); model }, { hearing }, Dispatchers.Unconfined, Dispatchers.Unconfined, clock = { 1_234L })

    @Test
    fun twentySecondsOfWhatsPlayedAreHeardWithTheModel() = runBlocking {
        val microphone = FakeMicrophone().apply { play(SingingListener.SECONDS.toDouble()) }
        val words = FakeWords(listOf(HeardWord("Oh", 320), HeardWord("show", 820)))
        val models = mutableListOf<File>()
        val listener = SingingListener(microphone, { File("model") }, { models += it; words }, Dispatchers.Unconfined, Dispatchers.Unconfined, clock = { 1_234L })
        assertEquals(Sung(1_234, listOf(HeardWord("Oh", 320), HeardWord("show", 820))), listener.hear("es"))
        assertEquals(listOf(File("model")), models)
        assertEquals("es", words.language)
        // 16 kHz, as whisper.cpp hears, from -1 to 1.
        val samples = words.samples!!
        assertEquals(16_000 * SingingListener.SECONDS, samples.size)
        assertEquals(99 / 32768f, samples[99])
        assertEquals(0f, samples[100])
        assertTrue(microphone.closed)
        assertTrue(words.closed)
    }

    @Test
    fun aPhoneThatWontShareItsSoundFetchesNoModel() = runBlocking {
        var asked = 0
        assertNull(listener(FakeMicrophone(works = false), modelAsked = { asked++ }).hear(null))
        assertEquals(0, asked)
    }

    @Test
    fun withoutTheModelNothingsHeard() = runBlocking {
        val microphone = FakeMicrophone().apply { play(SingingListener.SECONDS.toDouble()) }
        val words = FakeWords(listOf(HeardWord("Oh", 320)))
        val listener = listener(microphone, model = null, hearing = words)
        assertNull(listener.hear(null))
        assertTrue(microphone.closed)
        assertNull(words.samples)
        // It's the model that was missed, so it can be offered.
        assertTrue(listener.missesModel)
    }

    @Test
    fun theModelIsMissedOnlyWhenItWasWantedAndWasntThere() = runBlocking {
        // A phone that won't share its sound never got as far as wanting it.
        val silent = listener(FakeMicrophone(works = false), model = null)
        silent.hear(null)
        assertFalse(silent.missesModel)
        // Missed once, then there: it's no longer missed.
        var model: File? = null
        val listener = SingingListener(FakeMicrophone().apply { play(SingingListener.SECONDS.toDouble()); play(SingingListener.SECONDS.toDouble()) }, { model }, { FakeWords(listOf(HeardWord("Oh", 320))) }, Dispatchers.Unconfined, Dispatchers.Unconfined, clock = { 1_234L })
        listener.hear(null)
        assertTrue(listener.missesModel)
        model = File("model")
        listener.hear(null)
        assertFalse(listener.missesModel)
    }

    @Test
    fun theModelIsFetchedByHandThroughTheListener() = runBlocking {
        val fetched = SingingListener(FakeMicrophone(), { null }, { null }, Dispatchers.Unconfined, Dispatchers.Unconfined, fetch = { File("model") })
        assertTrue(fetched.fetchModel())
        val failed = SingingListener(FakeMicrophone(), { null }, { null }, Dispatchers.Unconfined, Dispatchers.Unconfined, fetch = { null })
        assertFalse(failed.fetchModel())
        // Without a way to fetch it, nothing is.
        assertFalse(SingingListener(FakeMicrophone(), { null }, { null }).fetchModel())
    }

    @Test
    fun soundThatEndsTooSoonIsntHeard() = runBlocking {
        val microphone = FakeMicrophone().apply { play(SingingListener.SECONDS - 1.0); end() }
        val words = FakeWords(listOf(HeardWord("Oh", 320)))
        assertNull(listener(microphone, hearing = words).hear(null))
        assertTrue(microphone.closed)
        assertNull(words.samples)
    }

    @Test
    fun soundGivenInPiecesIsHeardWhole() = runBlocking {
        // Pieces that don't fill the reads evenly: the last read takes only what's left.
        val microphone = FakeMicrophone().apply { play(7.0001); play(13.0) }
        val words = FakeWords(listOf(HeardWord("Oh", 320)))
        assertEquals(1, listener(microphone, hearing = words).hear(null)!!.words.size)
        assertEquals(16_000 * SingingListener.SECONDS, words.samples!!.size)
    }

    @Test
    fun soundThatStopsComingIsntHeard() = runBlocking {
        val silent = Microphone {
            object : Recording {
                override fun read(buffer: ShortArray, offset: Int, count: Int) = 0
                override fun close() = Unit
            }
        }
        assertNull(listener(silent).hear(null))
    }

    @Test
    fun aModelThatWontLoadOrHearsNoWordsHearsNothing() = runBlocking {
        assertNull(listener(FakeMicrophone().apply { play(SingingListener.SECONDS.toDouble()) }, hearing = null).hear(null))
        val words = FakeWords(emptyList())
        assertNull(listener(FakeMicrophone().apply { play(SingingListener.SECONDS.toDouble()) }, hearing = words).hear(null))
        assertTrue(words.closed)
        // One that isn't closed needn't be.
        val open = WordHearing { _, _ -> listOf(HeardWord("Oh", 320)) }
        assertEquals(1, listener(FakeMicrophone().apply { play(SingingListener.SECONDS.toDouble()) }, hearing = open).hear(null)!!.words.size)
    }

    @Test
    fun aModelThatFailsWhileHearingIsStillClosedIfItNeedsToBe() = runBlocking {
        val closing = object : WordHearing, Closeable {
            var closed = false
            override fun words(samples: FloatArray, language: String?): List<HeardWord> = error("no ears")
            override fun close() {
                closed = true
            }
        }
        val failed = runCatching { listener(FakeMicrophone().apply { play(SingingListener.SECONDS.toDouble()) }, hearing = closing).hear(null) }
        assertEquals("no ears", failed.exceptionOrNull()?.message)
        assertTrue(closing.closed)
        // One that isn't closed needn't be, and fails the same.
        val open = WordHearing { _, _ -> error("no ears either") }
        val failedOpen = runCatching { listener(FakeMicrophone().apply { play(SingingListener.SECONDS.toDouble()) }, hearing = open).hear(null) }
        assertEquals("no ears either", failedOpen.exceptionOrNull()?.message)
    }

    @Test
    fun aSingleWordHeardOfTheSongIsntEnoughToFindLinesFarOff() {
        assertNull(SungAlignment.shiftMs(Sung(0, listOf(HeardWord("Every", 500))), fromTheStart, verseAndChorus))
    }

    private val modelBytes = "a model, as far as a test knows".toByteArray()
    private val modelSha = MessageDigest.getInstance("SHA-256").digest(modelBytes).joinToString("") { "%02x".format(it) }

    private fun whisper(fake: FakeSpotify, mayFetch: Boolean = true, sha256: String = modelSha) =
        WhisperModel(fake.client(), File(folder.root, "whisper"), { mayFetch }, Dispatchers.Unconfined, "https://models.test/tiny.bin", sha256)

    private fun serving(code: Int = 200) = FakeSpotify().apply {
        handler = { request -> FakeSpotify.html(request, String(modelBytes), code = code) }
    }

    @Test
    fun theModelIsFetchedOnceAndKept() = runBlocking {
        val fake = serving()
        val file = whisper(fake).file()!!
        assertEquals(String(modelBytes), file.readText())
        assertEquals(listOf("https://models.test/tiny.bin"), fake.requestedUrls)
        assertEquals(listOf("ggml-tiny-q5_1.bin"), file.parentFile!!.list()!!.toList())
        // Kept, it isn't fetched again, even where it couldn't be.
        assertEquals(file, whisper(fake, mayFetch = false).file())
        assertEquals(1, fake.requestedUrls.size)
    }

    @Test
    fun theModelIsOnlyFetchedWhereItMayBe() = runBlocking {
        val fake = serving()
        assertNull(whisper(fake, mayFetch = false).file())
        assertEquals(emptyList<String>(), fake.requestedUrls)
    }

    @Test
    fun askedForByHandTheModelIsFetchedOnAnyNetworkAndKept() = runBlocking {
        val fake = serving()
        val file = whisper(fake, mayFetch = false).fetchAnyway()!!
        assertEquals(String(modelBytes), file.readText())
        // There now, it's used without fetching it again.
        assertEquals(file, whisper(fake, mayFetch = false).fetchAnyway())
        assertEquals(file, whisper(fake, mayFetch = false).file())
        assertEquals(1, fake.requestedUrls.size)
    }

    @Test
    fun oneNotAsTriedIsntKeptWhenFetchedByHandEither() = runBlocking {
        assertNull(whisper(serving(), mayFetch = false, sha256 = "0".repeat(64)).fetchAnyway())
        assertEquals(emptyList<String>(), File(folder.root, "whisper").list()!!.toList())
    }

    @Test
    fun aModelNotAsTriedOrNotThereIsntKept() = runBlocking {
        assertNull(whisper(serving(), sha256 = "0".repeat(64)).file())
        assertNull(whisper(serving(code = 404)).file())
        assertNull(whisper(FakeSpotify().apply { handler = { FakeSpotify.brokenBody(it) } }).file())
        assertEquals(emptyList<String>(), File(folder.root, "whisper").list()!!.toList())
    }

    @Test
    fun theModelIsWhisperCppsTinyOne() {
        assertEquals("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin", WhisperModel.URL)
        assertEquals("818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7", WhisperModel.SHA256)
    }
}
