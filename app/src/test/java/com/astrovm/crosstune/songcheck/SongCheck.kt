package com.astrovm.crosstune.songcheck

import com.astrovm.crosstune.ChordFinder
import com.astrovm.crosstune.ExactMatcher
import com.astrovm.crosstune.Heard
import com.astrovm.crosstune.LookupCache
import com.astrovm.crosstune.LyricsFinder
import com.astrovm.crosstune.MusicMetadata
import com.astrovm.crosstune.MusicService
import com.astrovm.crosstune.Readings
import com.astrovm.crosstune.Script
import com.astrovm.crosstune.Shazam
import com.astrovm.crosstune.ShazamSignature
import com.astrovm.crosstune.Translator
import com.astrovm.crosstune.SungReadings
import com.astrovm.crosstune.TestUniDic
import com.astrovm.crosstune.httpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The app's own lookups, run on the songs people play most, against the real services, one report
 * per song. Not part of the unit tests: scripts/songcheck/songcheck.py runs it, through Gradle's
 * `songCheck` task, after it has put the songs and their previews in [dir].
 */
@RunWith(RobolectricTestRunner::class)
class SongCheck {

    private val dir = File(System.getProperty("songcheck.dir") ?: "build/songcheck")
    private val client = httpClient()
    private val matcher = ExactMatcher(client, country = "US")
    private val lyrics = LyricsFinder(
        client,
        "songcheck",
        youTubeMusicSong = { song -> matcher.find(MusicService.YOUTUBE_MUSIC, song)?.substringAfter("watch?v=", "")?.ifEmpty { null } }
    )
    private val chords = ChordFinder(client)
    private val translator = Translator(client, LookupCache(File(dir, "translations.json"), maxEntries = 100_000))
    private val sungReadings = SungReadings(client)
    private val shazam = Shazam(client)

    @Test
    fun songs() = runBlocking {
        // Japanese read as once UniDic is on the phone, as it is for most who read it.
        Readings.uniDic = TestUniDic.tokenizer
        val reports = File(dir, "reports").apply { mkdirs() }
        val songs = JSONArray(File(dir, "songs.json").readText())
        for (i in 0 until songs.length()) {
            val song = songs.getJSONObject(i)
            val out = File(reports, "${song.getString("id")}.json")
            // A run stopped halfway goes on where it was.
            if (out.exists()) continue
            out.writeText(check(song).toString(1))
            println("songcheck ${i + 1}/${songs.length()} ${song.getString("artist")} - ${song.getString("title")}")
            // The services are free; asked a song at a time, with a breath between.
            delay(PAUSE_MS)
        }
    }

    private suspend fun check(song: JSONObject): JSONObject {
        val metadata = MusicMetadata(song.getString("title"), song.getString("artist"), lengthMs = song.optLong("lengthMs").takeIf { it > 0 })
        val report = JSONObject(song.toString())
        val started = System.currentTimeMillis()
        val found = try {
            lyrics.lyricsOf(metadata)
        } catch (e: Exception) {
            report.put("error", "lyrics: $e")
            null
        }
        report.put("lyricsMs", System.currentTimeMillis() - started)
        report.put("lyrics", lyricsJson(found))
        report.put("preview", preview(song.getString("id"), metadata))
        val words = found as? LyricsFinder.Lyrics.Found ?: return report
        val lines = words.lines.map { it.text }.ifEmpty { words.words.lines() }
        runCatching { report.put("chords", chordsJson(metadata, lines)) }.onFailure { report.put("chordsError", it.toString()) }
        runCatching { report.put("translation", translationJson(lines)) }.onFailure { report.put("translationError", it.toString()) }
        runCatching { readingsJson(metadata, lines)?.let { report.put("readings", it) } }.onFailure { report.put("readingsError", it.toString()) }
        return report
    }

    private fun lyricsJson(found: LyricsFinder.Lyrics?): JSONObject = when (found) {
        is LyricsFinder.Lyrics.Found -> JSONObject()
            .put("kind", "found")
            .put("source", found.source)
            .put("lengthMs", found.lengthMs)
            .put("words", found.words)
            .put("lines", JSONArray(found.lines.map { JSONObject().put("ms", it.timeMs).put("text", it.text) }))
        null -> JSONObject().put("kind", "error")
        else -> JSONObject().put("kind", found::class.simpleName!!.lowercase())
    }

    /**
     * Where the song's preview is in the song, as Shazam hears it at a few places in it: the timing
     * check lines what's sung there up with the lyrics' times.
     */
    private suspend fun preview(id: String, metadata: MusicMetadata): JSONObject {
        val file = File(dir, "audio/$id.pcm")
        if (!file.exists()) return JSONObject().put("missing", true)
        val bytes = file.readBytes()
        val samples = ShortArray(bytes.size / 2).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
        val heard = JSONArray()
        var at = 0
        while (at + ShazamSignature.SAMPLES <= samples.size) {
            val window = samples.copyOfRange(at, at + ShazamSignature.SAMPLES)
            val atMs = at * 1000L / ShazamSignature.SAMPLE_RATE
            when (val answer = shazam.recognize(window, window.size)) {
                is Heard.Song -> heard.put(
                    JSONObject().put("atMs", atMs).put("title", answer.metadata.title).put("artist", answer.metadata.artist)
                        .put("songMs", answer.offsetMs).put("startsAtMs", answer.offsetMs?.let { it - atMs })
                )
                else -> heard.put(JSONObject().put("atMs", atMs).put("answer", answer.toString()))
            }
            at += WINDOW_STEP_MS * ShazamSignature.SAMPLE_RATE / 1000
            delay(PAUSE_MS)
        }
        return JSONObject().put("lengthMs", samples.size * 1000L / ShazamSignature.SAMPLE_RATE).put("heard", heard).put("of", metadata.title)
    }

    private suspend fun chordsJson(metadata: MusicMetadata, lines: List<String>): JSONObject = when (val answer = chords.chordsOf(metadata, lines)) {
        is ChordFinder.Chords.Found -> JSONObject()
            .put("kind", "found")
            .put("site", answer.sheet.site.name)
            .put("url", answer.sheet.url)
            .put("capo", answer.sheet.capo)
            .put("lines", JSONArray(answer.sheet.lines.take(SHOWN_LINES).map { line -> "${line.chords.joinToString(" ") { it.name }} | ${line.text}" }))
        else -> JSONObject().put("kind", answer::class.simpleName!!.lowercase())
    }

    /** Into English, or Spanish for a song already in English, as nothing comes back for it in English. */
    private suspend fun translationJson(lines: List<String>): JSONObject {
        var target = "en"
        var translated = translator.translate(lines, target)
        if (translated != null && translated.none { it != null }) {
            target = "es"
            translated = translator.translate(lines, target)
        }
        val shown = lines.zip(translated ?: lines.map { null }).filter { it.first.isNotBlank() }.take(SHOWN_LINES)
        return JSONObject()
            .put("target", target)
            .put("failed", translated == null)
            .put("lines", JSONArray(shown.map { (line, into) -> JSONObject().put("line", line).put("into", into) }))
    }

    /** How the lines are read, for words not written in Latin letters, as the app shows them. */
    private suspend fun readingsJson(metadata: MusicMetadata, lines: List<String>): JSONObject? {
        val script = Readings.displayScriptOf(lines) ?: return null
        var read = Readings.of(lines, script)
        var fixed = 0
        if (script == Script.JAPANESE) {
            sungReadings.of(metadata, lines, read)?.takeIf { it.isNotEmpty() }?.let { fixes ->
                read = Readings.corrected(read, fixes)
                fixed = fixes.size
            }
        }
        val shown = lines.zip(read).filter { it.first.isNotBlank() }.take(SHOWN_LINES)
        return JSONObject()
            .put("script", script.name)
            .put("sungFixes", fixed)
            .put("lines", JSONArray(shown.map { (line, reading) -> JSONObject().put("line", line).put("reading", reading.reading).put("romanized", reading.romanized) }))
    }

    private companion object {
        const val PAUSE_MS = 1_500L
        const val WINDOW_STEP_MS = 9_000
        /** What a grader reads of a song's chords, translation and readings: enough to judge, few enough to read. */
        const val SHOWN_LINES = 40
    }
}
