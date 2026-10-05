package com.astrovm.crosstune

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
        assertEquals(Heard.Song(MusicMetadata("Iris", "The Goo Goo Dolls", artworkUrl = "https://example.com/iris.jpg"), "1109658204"), heard())
        // Without Apple Music, or a cover, it's still the song.
        answer("""{"matches":[{"id":"1"}],"track":{"title":"Demo","subtitle":"Band","hub":{"actions":[{"type":"uri","uri":"https://example.com"},"x"]}}}""")
        assertEquals(Heard.Song(MusicMetadata("Demo", "Band"), null), heard())
        answer("""{"matches":[{"id":"1"}],"track":{"title":"Demo","images":{},"hub":{"actions":[{"type":"applemusicplay","id":""}]}}}""")
        assertEquals(Heard.Song(MusicMetadata("Demo", ""), null), heard())
        answer("""{"matches":[{"id":"1"}],"track":{"title":"Demo"}}""")
        assertEquals(Heard.Song(MusicMetadata("Demo", ""), null), heard())
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
    fun aFailureStopsListeningRightAway() {
        microphone.play(3.0)
        answer("", code = 429)
        assertEquals(Heard.Failed(AppError.RECOGNITION_UNAVAILABLE), runBlocking { listener.listen() })
        assertEquals(1, fake.requestedUrls.size)
        assertTrue(microphone.closed)
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

    private companion object {
        const val NO_MATCH = """{"matches":[],"timestamp":5,"tagid":"x"}"""
        const val MATCH = """{"matches":[{"id":"238534"}],"track":{"key":"238534","title":"Iris","subtitle":"The Goo Goo Dolls",""" +
            """"images":{"coverart":"https://example.com/iris.jpg"},""" +
            """"hub":{"actions":[{"name":"apple","type":"applemusicplay","id":"1109658204"},{"name":"apple","type":"uri","uri":"https://example.com/preview.m4a"}]}}}"""
    }
}
