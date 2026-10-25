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
        // "me" in "let me" has no time of its own, so six words tell.
        assertEquals(-700L, SungAlignment.shiftMs(sung, fromTheStart, words))
        assertNull(SungAlignment.shiftMs(Sung(0, sung.words.take(4)), fromTheStart, words))
    }

    @Test
    fun tooFewWordsHeardToTell() {
        // Five lines' first words tell; four don't.
        assertEquals(500L, SungAlignment.shiftMs(heard(song.take(5), 500), fromTheStart, song))
        assertNull(SungAlignment.shiftMs(heard(song.take(4), 500), fromTheStart, song))
        // Heard words that aren't the song's, or nothing but sounds, tell nothing.
        assertNull(SungAlignment.shiftMs(Sung(0, listOf(HeardWord("♪", 600), HeardWord("...", 900))), fromTheStart, song))
        assertNull(SungAlignment.shiftMs(Sung(0, emptyList()), fromTheStart, song))
        assertNull(SungAlignment.shiftMs(Sung(0, listOf("la", "la", "la", "la", "la", "la").mapIndexed { index, word -> HeardWord(word, index * 500L) }), fromTheStart, song))
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
        // 400 ms apart still agree.
        val edge = Sung(0, lines.zip(listOf(0L, 400, 400, 400, 400, 5_000)) { line, off -> HeardWord(line.text, line.timeMs + off + SungAlignment.HEARD_LATE_MS) })
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
    fun wordsTimedTooFarOffAreForAnotherPartOfTheSong() {
        // 9.4 seconds later still is; 10.6 seconds earlier isn't.
        val lines = timed("Sing to me tonight", "Under the yellow moon", "Every river finds the sea", "Hold the morning light", "Dancing on the water", startMs = 20_000)
        assertEquals(9_400L, SungAlignment.shiftMs(heard(lines, 9_400), fromTheStart, lines))
        assertNull(SungAlignment.shiftMs(heard(lines, -10_600), fromTheStart, lines))
        assertEquals(-9_000L, SungAlignment.shiftMs(heard(lines, -9_000), fromTheStart, lines))
        assertEquals(-10_000L, SungAlignment.shiftMs(heard(lines, -10_000), fromTheStart, lines))
        // Lines more than 10 seconds before or after what was heard aren't looked at at all.
        assertNull(SungAlignment.shiftMs(heard(lines, 10_800), fromTheStart, lines))
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
        assertNull(listener(microphone, model = null, hearing = words).hear(null))
        assertTrue(microphone.closed)
        assertNull(words.samples)
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
