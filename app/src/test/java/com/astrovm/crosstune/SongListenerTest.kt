package com.astrovm.crosstune

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowAudioRecord
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.random.Random

/** Listening for a song: the microphone, Shazam's answers, and when to ask it. Robolectric for org.json and AudioRecord. */
@RunWith(RobolectricTestRunner::class)
class SongListenerTest {

    private val fake = FakeSpotify()
    private val ids = ArrayDeque(listOf(UUID.fromString("0a1b2c3d-0000-4000-8000-00000000000a"), UUID.fromString("0a1b2c3d-0000-4000-8000-00000000000b")))
    private val shazam = Shazam(fake.client(), computeDispatcher = Dispatchers.Unconfined, newId = { ids.removeFirst().also(ids::addLast) }, now = { 0x1_0000_0005L })
    private val microphone = FakeMicrophone()
    private val listener = SongListener(microphone, shazam)

    private fun answer(body: String, code: Int = 200) {
        fake.handler = { FakeSpotify.html(it, body, code = code) }
    }

    /** Answers each request in turn, after doing what it says, e.g. playing more sound meanwhile. */
    private fun answers(vararg steps: () -> String) {
        var next = 0
        fake.handler = { request -> FakeSpotify.html(request, steps[next++]()) }
    }

    private fun heard(samples: ShortArray = ShortArray(16_000) { (it % 50).toShort() }) = runBlocking { shazam.recognize(samples, samples.size) }

    @After
    fun tearDown() = ShadowAudioRecord.clearSource()

    @Test
    fun itSendsOnlyTheFingerprintToShazam() {
        answer(NO_MATCH)
        val samples = ShortArray(32_000) { (it % 70).toShort() }
        runBlocking { shazam.recognize(samples, 16_000) }
        assertEquals(
            "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/0A1B2C3D-0000-4000-8000-00000000000A/0a1b2c3d-0000-4000-8000-00000000000b" +
                "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3",
            fake.requestedUrls.single()
        )
        val body = JSONObject(fake.requestBodies.single())
        assertEquals("""{"altitude":300,"latitude":45,"longitude":2}""", body.getJSONObject("geolocation").toString())
        val signature = body.getJSONObject("signature")
        // Twelve seconds, what's sent once it's padded.
        assertEquals(12_000, signature.getInt("samplems"))
        // The time in milliseconds, as an unsigned 32-bit number.
        assertEquals(5L, signature.getLong("timestamp"))
        assertEquals(5L, body.getLong("timestamp"))
        assertEquals(ShazamSignature.uri(samples, 16_000), signature.getString("uri"))
        assertEquals("Europe/Paris", body.getString("timezone"))
        assertEquals(setOf("geolocation", "signature", "timestamp", "timezone"), body.keys().asSequence().toSet())
    }

    @Test
    fun itSaysWhoItIsLikeShazamsAndroidApp() {
        var request: Request? = null
        fake.handler = { request = it; FakeSpotify.html(it, NO_MATCH) }
        heard()
        assertEquals("application/json; charset=utf-8", request!!.body!!.contentType().toString())
        assertEquals("en_US", request.header("Content-Language"))
        assertTrue(request.header("User-Agent")!!.startsWith("Dalvik/2.1.0 (Linux; U; Android"))
        assertEquals("POST", request.method)
    }

    @Test
    fun aMatchNamesTheSongAndItsAppleMusicId() {
        answer(MATCH)
        assertEquals(Heard.Song(MusicMetadata("Iris", "The Goo Goo Dolls", artworkUrl = "https://example.com/iris.jpg"), "1109658204", matchId = "238534"), heard())
        // Without Apple Music, or a cover, it's still the song.
        answer("""{"matches":[{"id":"1"}],"track":{"title":"Demo","subtitle":"Band","hub":{"actions":[{"type":"uri","uri":"https://example.com"},"x"]}}}""")
        assertEquals(Heard.Song(MusicMetadata("Demo", "Band"), null, matchId = "1"), heard())
        answer("""{"matches":[{"id":"1"}],"track":{"title":"Demo","images":{},"hub":{"actions":[{"type":"applemusicplay","id":""}]}}}""")
        assertEquals(Heard.Song(MusicMetadata("Demo", ""), null, matchId = "1"), heard())
        answer("""{"matches":[{"id":"1"}],"track":{"title":"Demo"}}""")
        assertEquals(Heard.Song(MusicMetadata("Demo", ""), null, matchId = "1"), heard())
    }

    @Test
    fun theRecordingShazamMatchedIsKept() {
        fun matched(matches: String): String? {
            answer("""{"matches":$matches,"track":{"title":"Demo"}}""")
            return (heard() as Heard.Song).matchId
        }
        assertEquals("403191606", matched("""[{"id":"403191606","offset":66.8},{"id":"1","offset":30.0}]"""))
        assertEquals(null, matched("""[{"id":"","offset":12.5}]"""))
        assertEquals(null, matched("""[{"offset":12.5}]"""))
        assertEquals(null, matched("""[]"""))
    }

    @Test
    fun whereInTheSongItWasHeardIsKeptOnlyWhenItMakesSense() {
        fun heardAt(matches: String): Long? {
            answer("""{"matches":$matches,"track":{"title":"Demo"}}""")
            return (heard() as Heard.Song).offsetMs
        }
        assertEquals(12_500L, heardAt("""[{"id":"1","offset":12.5}]"""))
        assertEquals(0L, heardAt("""[{"id":"1","offset":0}]"""))
        // Before the start, not a number, or not there at all: where it is isn't known.
        assertEquals(null, heardAt("""[{"id":"1","offset":-3.2}]"""))
        assertEquals(null, heardAt("""[{"id":"1","offset":"soon"}]"""))
        assertEquals(null, heardAt("""[{"id":"1"}]"""))
        assertEquals(null, heardAt("""["1"]"""))
        assertEquals(null, heardAt("""{}"""))
    }

    @Test
    fun noMatchOrNoTitleIsNothing() {
        answer(NO_MATCH)
        assertEquals(Heard.Nothing, heard())
        answer("""{"matches":[{"id":"1"}],"track":{"title":" ","subtitle":"Band"}}""")
        assertEquals(Heard.Nothing, heard())
    }

    @Test
    fun whenShazamCantAnswerAnotherAppMayStill() {
        listOf(429, 500, 503, 400).forEach { code ->
            answer("", code)
            assertEquals(Heard.Failed(AppError.RECOGNITION_UNAVAILABLE), heard())
        }
        answer("<html>Oops</html>")
        assertEquals(Heard.Failed(AppError.RECOGNITION_UNAVAILABLE), heard())
    }

    @Test
    fun offlineItsTheNetwork() {
        fake.handler = { throw IOException("offline") }
        assertEquals(Heard.Failed(AppError.NETWORK), heard())
        fake.handler = FakeSpotify::brokenBody
        assertEquals(Heard.Failed(AppError.NETWORK), heard())
    }

    @Test
    fun itAsksEveryThreeSecondsAndStopsAtTheFirstMatch() {
        microphone.play(3.0)
        // Shazam's first answer comes after three more seconds of sound.
        answers({ microphone.play(3.0); NO_MATCH }, { microphone.play(3.0); MATCH })
        val heard = runBlocking { listener.listen() }
        assertEquals("Iris", (heard as Heard.Song).metadata.title)
        assertEquals(2, fake.requestedUrls.size)
        // It stopped listening, even though there's more to hear.
        assertTrue(microphone.closed)
    }

    @Test
    fun afterTwelveSecondsWithoutAMatchItsNothing() {
        microphone.play(3.0)
        fake.handler = { microphone.play(3.0); FakeSpotify.html(it, NO_MATCH) }
        assertEquals(Heard.Nothing, runBlocking { listener.listen() })
        // At 3, 6, 9 and 12 seconds.
        assertEquals(4, fake.requestedUrls.size)
        assertTrue(microphone.closed)
    }

    @Test
    fun whenTheMicrophoneStopsWhatsLeftIsTriedOnce() {
        microphone.play(3.0)
        answers({ microphone.play(1.0); microphone.end(); NO_MATCH }, { NO_MATCH })
        assertEquals(Heard.Nothing, runBlocking { listener.listen() })
        assertEquals(2, fake.requestedUrls.size)

        // Or matches with it.
        val short = FakeMicrophone().apply { play(1.0); end() }
        answer(MATCH)
        assertTrue(runBlocking { SongListener(short, shazam).listen() } is Heard.Song)
    }

    @Test
    fun itCanListenAgainAfterASong() {
        answer(MATCH)
        microphone.play(3.0)
        assertTrue(runBlocking { listener.listen() } is Heard.Song)
        microphone.play(3.0)
        assertTrue(runBlocking { listener.listen() } is Heard.Song)
        assertEquals(2, fake.requestedUrls.size)
    }

    @Test
    fun aFailureStopsListeningRightAway() {
        microphone.play(3.0)
        answer("", code = 429)
        assertEquals(Heard.Failed(AppError.RECOGNITION_UNAVAILABLE), runBlocking { listener.listen() })
        assertEquals(1, fake.requestedUrls.size)
        assertTrue(microphone.closed)
    }

    @Test
    fun theNetworkFailingForAMomentIsAskedAgainWithMore() {
        microphone.play(3.0)
        answers({ microphone.play(3.0); throw java.io.IOException("timed out") }, { MATCH })
        assertTrue(runBlocking { listener.listen() } is Heard.Song)
        assertEquals(2, fake.requestedUrls.size)
        // Failing twice in a row, it says so, without listening on.
        microphone.play(3.0)
        fake.handler = { microphone.play(3.0); throw java.io.IOException("offline") }
        fake.requestedUrls.clear()
        assertEquals(Heard.Failed(AppError.NETWORK), runBlocking { listener.listen() })
        assertEquals(2, fake.requestedUrls.size)
        // Or once, with no more sound to ask again with.
        microphone.play(3.0)
        fake.handler = { microphone.end(); throw java.io.IOException("offline") }
        assertEquals(Heard.Failed(AppError.NETWORK), runBlocking { listener.listen() })
    }

    @Test
    fun aMicrophoneThatDoesntWorkSendsNothing() {
        assertEquals(Heard.Failed(AppError.MICROPHONE), runBlocking { SongListener(FakeMicrophone(works = false), shazam).listen() })
        // One that gives no sound at all.
        microphone.end()
        assertEquals(Heard.Failed(AppError.MICROPHONE), runBlocking { listener.listen() })
        assertTrue(microphone.closed)
        assertTrue(fake.requestedUrls.isEmpty())
    }

    @Test
    fun stoppingStopsTheMicrophone() = runBlocking {
        val listening = launch(Dispatchers.Default) { listener.listen() }
        while (microphone.opened == 0) yield()
        listening.cancelAndJoin()
        assertTrue(microphone.closed)
        assertTrue(fake.requestedUrls.isEmpty())
    }

    @Test
    fun stoppingWhileTheMicrophoneOpensStillClosesIt() = runBlocking {
        val opening = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val slow = Microphone {
            opening.countDown()
            stopped.await()
            microphone.open()
        }
        val listening = launch(Dispatchers.Default) { SongListener(slow, shazam).listen() }
        opening.await()
        listening.cancel()
        stopped.countDown()
        listening.join()
        assertTrue(microphone.closed)
        assertTrue(fake.requestedUrls.isEmpty())
    }

    @Test
    fun thePhonesMicrophoneRecordsSixteenKilohertzMono() {
        var made: AudioRecord? = null
        ShadowAudioRecord.setSourceProvider { record ->
            made = record
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInShortArray(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, isBlocking: Boolean): Int {
                    audioData.fill(7, offsetInShorts, offsetInShorts + sizeInShorts)
                    return sizeInShorts
                }
            }
        }
        val recording = AudioRecordMicrophone().open()
        assertNotNull(recording)
        val buffer = ShortArray(10)
        assertEquals(4, recording!!.read(buffer, 2, 4))
        assertArrayEquals(shortArrayOf(0, 0, 7, 7, 7, 7, 0, 0, 0, 0), buffer)
        assertEquals(16_000, made!!.sampleRate)
        assertEquals(AudioFormat.CHANNEL_IN_MONO, made.channelConfiguration)
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, made.audioFormat)
        assertEquals(MediaRecorder.AudioSource.MIC, made.audioSource)
        assertEquals(AudioRecord.RECORDSTATE_RECORDING, made.recordingState)
        recording.close()
        assertEquals(AudioRecord.STATE_UNINITIALIZED, made.state)
    }

    @Test
    fun withoutAMicrophoneThereIsNoRecording() {
        assertNull(AudioRecordMicrophone { throw IllegalArgumentException("no microphone") }.open())
        assertNull(AudioRecordMicrophone { throw UnsupportedOperationException("no permission") }.open())
    }

    @Test
    fun aMicrophoneThatFailsWhileRecordingIsAMicrophoneError() {
        // The phone's microphone answers a read with an error code once it has stopped working.
        ShadowAudioRecord.setSourceProvider {
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInShortArray(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, isBlocking: Boolean) =
                    AudioRecord.ERROR_INVALID_OPERATION
            }
        }
        assertEquals(Heard.Failed(AppError.MICROPHONE), runBlocking { SongListener(AudioRecordMicrophone(), shazam).listen() })
        assertTrue(fake.requestedUrls.isEmpty())
    }

    @Test
    fun aMicrophoneThatWontStartIsReleasedAndNoRecording() {
        var made: AudioRecord? = null
        val busy = AudioRecordMicrophone {
            object : AudioRecord(MediaRecorder.AudioSource.MIC, 16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 32_000) {
                // Another app holding the microphone.
                override fun startRecording() = throw IllegalStateException("busy")
            }.also { made = it }
        }
        assertNull(busy.open())
        assertEquals(AudioRecord.STATE_UNINITIALIZED, made!!.state)
    }

    @Test
    fun aSongPlayingOnThePhoneIsNamedWithoutListening() {
        val playing = Heard.Song(MusicMetadata("Iris", "The Goo Goo Dolls"), null, 42_000, 400_000)
        val phone = FakePlayback().apply { onThePhone = playing }
        val ears = SongHearing { Heard.Failed(AppError.MICROPHONE) }
        assertEquals(playing, runBlocking { PlayingFirst(phone, ears).listen() })
        // Nothing playing on the phone, it listens.
        phone.onThePhone = null
        assertEquals(Heard.Failed(AppError.MICROPHONE), runBlocking { PlayingFirst(phone, ears).listen() })
        // Hearing that only has one way to listen hears what's playing that way too.
        assertEquals(Heard.Failed(AppError.MICROPHONE), runBlocking { ears.listenToPlaying() })
    }

    @Test
    fun aVideoPlayingOnThePhoneIsTimedByEarAndNamedByItsAppWhenNothingsHeard() {
        // Its app names it, but can't say where in the song it is.
        val video = Heard.Song(MusicMetadata("Iris", "The Goo Goo Dolls"), null)
        val phone = FakePlayback().apply { onThePhone = video }
        val heard = Heard.Song(MusicMetadata("Iris", "The Goo Goo Dolls"), null, 12_000, 400_000)
        assertEquals(heard, runBlocking { PlayingFirst(phone, SongHearing { heard }).listen() })
        // Nothing heard, as through headphones, the app's name still does.
        assertEquals(video, runBlocking { PlayingFirst(phone, SongHearing { Heard.Nothing }).listen() })
        assertEquals(video, runBlocking { PlayingFirst(phone, SongHearing { Heard.Failed(AppError.NETWORK) }).listen() })
    }

    /**
     * Plays [sound], 8-bit at [rateHz], from when the clock is at 0: each read is the last
     * [OutputMixTap.SAMPLES] played, as Android's tap gives them, or [size] of them.
     */
    private class PlayingTap(private val sound: ByteArray, override val rateHz: Int = 48_000, private val clock: () -> Long) : SoundTap {
        var size = OutputMixTap.SAMPLES
        var closed = false

        override fun read(into: ByteArray): Int {
            val end = (clock() * rateHz / 1000).toInt()
            for (index in 0 until size) into[index] = sound.getOrElse(end - size + index) { 128.toByte() }
            return size
        }

        override fun close() {
            closed = true
        }
    }

    private var clock = 0L
    private fun played(tap: SoundTap?, every: Long = PlayedSound.POLL_MS) = PlayedSound({ tap }, now = { clock }, sleep = { clock += every })

    /** All [recording] gives, [count] samples asked for at a time, until it gives no more or [max]. */
    private fun all(recording: Recording, count: Int = 1_600, max: Int = 32_000): ShortArray {
        val out = ShortArray(max)
        var got = 0
        while (got < max) {
            val read = recording.read(out, got, minOf(count, max - got))
            if (read <= 0) break
            got += read
        }
        return out.copyOf(got)
    }

    private fun music(seconds: Int, rateHz: Int = 48_000) = Random(7).let { random -> ByteArray(seconds * rateHz) { (random.nextInt(40, 216)).toByte() } }

    @Test
    fun thePhonesOwnSoundIsHeardWhole() {
        val sound = music(3)
        clock = 100
        val tap = PlayingTap(sound) { clock }
        val heard = played(tap).open()!!.use { all(it, max = 16_000) }
        // From where listening started on, every sample, at 16 kHz: each the three it's made of.
        val start = 100 * 48
        assertEquals(16_000, heard.size)
        heard.forEachIndexed { index, sample ->
            val three = (0 until 3).map { (sound[start + index * 3 + it].toInt() and 0xFF) - 128 }
            assertEquals((three.average() * 256).toInt().toShort(), sample)
        }
        assertTrue(tap.closed)
    }

    @Test
    fun soundMissedBetweenReadsFarApartIsSilenceSoWhatsHeardStaysInTime() {
        val sound = music(3)
        clock = 100
        // Reads 40 ms apart don't overlap; what played between them is lost.
        val heard = played(PlayingTap(sound) { clock }, every = 40).open()!!.use { all(it, max = 16_000) }
        assertEquals(16_000, heard.size)
        // Each 40 ms is 640 samples: the last 21 ms heard, the rest silence before it.
        assertTrue(heard.copyOfRange(0, 297).all { it == 0.toShort() })
        assertTrue(heard.copyOfRange(300, 640).all { it != 0.toShort() } || heard.copyOfRange(300, 640).count { it == 0.toShort() } < 10)
    }

    @Test
    fun aPhonesSoundAt44KhzIsHeardAt16() {
        clock = 0
        val heard = played(PlayingTap(music(2, 44_100), rateHz = 44_100) { clock }).open()!!.use { all(it, max = 16_000) }
        assertEquals(16_000, heard.size)
        assertTrue(heard.count { it == 0.toShort() } < 300)
    }

    @Test
    fun aQuietStartIsHeardWhileTheMusicComesInButOnlySilenceIsAPhoneThatWontShareItsSound() {
        // A second of silence, then music: the silence is heard as such, in time.
        val sound = ByteArray(48_000) { 128.toByte() } + music(2)
        clock = 0
        val tap = PlayingTap(sound) { clock }
        val heard = played(tap).open()!!.use { all(it, max = 24_000) }
        assertEquals(24_000, heard.size)
        assertTrue(heard.copyOfRange(0, 15_900).all { it == 0.toShort() })
        assertTrue(heard.copyOfRange(16_100, 24_000).any { it != 0.toShort() })
        // Only silence for a while, there's nothing to hear it with, and the microphone listens instead.
        clock = 0
        val silent = PlayingTap(ByteArray(48_000 * 5) { 128.toByte() }) { clock }
        assertEquals(Heard.Failed(AppError.MICROPHONE), runBlocking { SongListener(played(silent), shazam).listen() })
        assertTrue(silent.closed)
        // Off by one from silence, as some phones' silence is, is silence too.
        clock = 0
        val hiss = PlayingTap(ByteArray(48_000 * 5) { if (it % 2 == 0) 127.toByte() else 129.toByte() }) { clock }
        assertEquals(Heard.Failed(AppError.MICROPHONE), runBlocking { SongListener(played(hiss), shazam).listen() })
        // Without the tap there's nothing to open.
        assertNull(played(null).open())
    }

    @Test
    fun aPhoneThatHasSharedItsSoundIsntCountedOutByAQuietStart() {
        // A phone that shared its sound once does again, so a video's silent start isn't heard by the microphone.
        clock = 0
        val taps = ArrayDeque(listOf(PlayingTap(music(1)) { clock }, PlayingTap(ByteArray(48_000 * 5) { 128.toByte() }) { clock }))
        val preferences = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("played", Context.MODE_PRIVATE)
        fun sound() = PlayedSound({ taps.removeFirst() }, preferences, now = { clock }, sleep = { clock += PlayedSound.POLL_MS })
        assertTrue(sound().open()!!.use { all(it, max = 8_000) }.any { it != 0.toShort() })
        // Even after Crosstune starts again.
        clock = 0
        val quiet = sound().open()!!.use { all(it, max = 48_000) }
        assertTrue(quiet.isNotEmpty() && quiet.all { it == 0.toShort() })
    }

    @Test
    fun readsThatShrinkAreTakenByTheClock() {
        val sound = music(2)
        clock = 100
        val tap = PlayingTap(sound) { clock }
        val heard = played(tap).open()!!.use { recording ->
            all(recording, max = 1_600).also { tap.size = 512 } + all(recording, max = 1_600)
        }
        assertEquals(3_200, heard.size)
    }

    @Test
    fun aVideoIsListenedToThroughThePhonesOwnSoundAndTheMicrophoneOnlyWhenThePhoneWontShareIt() {
        val video = Heard.Song(MusicMetadata("Iris", "The Goo Goo Dolls"), null)
        val phone = FakePlayback().apply { onThePhone = video }
        val byMicrophone = Heard.Song(MusicMetadata("Iris", "The Goo Goo Dolls"), null, 12_000, 400_000)
        val bySound = byMicrophone.copy(offsetMs = 13_000)
        assertEquals(bySound, runBlocking { PlayingFirst(phone, SongHearing { byMicrophone }, SongHearing { bySound }).listen() })
        assertEquals(byMicrophone, runBlocking { PlayingFirst(phone, SongHearing { byMicrophone }, SongHearing { Heard.Failed(AppError.MICROPHONE) }).listen() })
        // Nothing playing on the phone, the microphone listens for what's nearby.
        phone.onThePhone = null
        assertEquals(byMicrophone, runBlocking { PlayingFirst(phone, SongHearing { byMicrophone }, SongHearing { bySound }).listen() })
        // Asked not to, it never takes the microphone, even when the phone won't share its sound.
        val unshared = PlayingFirst(phone, SongHearing { byMicrophone }, SongHearing { Heard.Failed(AppError.MICROPHONE) })
        assertEquals(Heard.Failed(AppError.MICROPHONE), runBlocking { unshared.listenToPlaying(byMicrophone = false) })
        assertEquals(bySound, runBlocking { PlayingFirst(phone, SongHearing { byMicrophone }, SongHearing { bySound }).listenToPlaying(byMicrophone = false) })
    }

    private companion object {
        const val NO_MATCH = """{"matches":[],"timestamp":5,"tagid":"x"}"""
        const val MATCH = """{"matches":[{"id":"238534"}],"track":{"key":"238534","title":"Iris","subtitle":"The Goo Goo Dolls",""" +
            """"images":{"coverart":"https://example.com/iris.jpg"},""" +
            """"hub":{"actions":[{"name":"apple","type":"applemusicplay","id":"1109658204"},{"name":"apple","type":"uri","uri":"https://example.com/preview.m4a"}]}}}"""
    }
}
