package com.astrovm.crosstune.songcheck

import com.astrovm.crosstune.ChordFinder
import com.astrovm.crosstune.ExactMatcher
import com.astrovm.crosstune.LineReading
import com.astrovm.crosstune.LyricsFinder
import com.astrovm.crosstune.MusicMetadata
import com.astrovm.crosstune.MusicService
import com.astrovm.crosstune.ReadingFix
import com.astrovm.crosstune.Readings
import com.astrovm.crosstune.Ruby
import com.astrovm.crosstune.Script
import com.astrovm.crosstune.SungReadings
import com.astrovm.crosstune.TestUniDic
import com.astrovm.crosstune.httpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.MessageDigest

/**
 * The readings the app shows on each song's lines, beside how UtaTen says they're sung, for
 * scripts/readingsaudit/readingsaudit.py to compare. Not part of the unit tests: the script runs it
 * through Gradle's `songCheck` task, with `-PsongcheckTest` naming it.
 *
 * Each song's words, and whether it has timing and chords, are looked up once and kept in
 * `found/`; the readings are worked out again on every run, so a change to them is measured on
 * the same words. UtaTen's pages are kept in `http/`, asked a second apart, so a run asks it nothing twice.
 */
@RunWith(RobolectricTestRunner::class)
class ReadingsAudit {

    private val dir = File(System.getProperty("songcheck.dir") ?: "build/songcheck")
    private val pages = mutableMapOf<String, String>()
    private var lastAsked = 0L
    private val client = httpClient().newBuilder().addInterceptor(::kept).build()
    private val matcher = ExactMatcher(client, country = "JP")
    private val lyrics = LyricsFinder(
        client,
        "songcheck",
        youTubeMusicSong = { song -> matcher.find(MusicService.YOUTUBE_MUSIC, song)?.substringAfter("watch?v=", "")?.ifEmpty { null } }
    )
    private val chords = ChordFinder(client)
    private val sungReadings = SungReadings(client)

    /** UtaTen's answers from `http/` when they're there, else asked at most once a second and kept; the rest goes as it is. */
    private fun kept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        if (request.url.host != "utaten.com" || request.method != "GET") return chain.proceed(request)
        val file = File(dir, "http/" + MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) })
        val body = if (file.exists()) file.readText() else {
            val wait = lastAsked + ASK_EVERY_MS - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastAsked = System.currentTimeMillis()
            val response = chain.proceed(request)
            if (!response.isSuccessful) return response
            // An empty answer, as its search gives when it won't answer, is not kept.
            response.body.string().also { if (it.isNotBlank()) file.apply { parentFile!!.mkdirs() }.writeText(it) }
        }
        if ("/lyric/" in url) pages[url] = body
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody("text/html".toMediaType())).build()
    }

    @Test
    fun songs() = runBlocking {
        val songs = JSONArray(File(dir, "songs.json").readText())
        val out = File(dir, "audit").apply { mkdirs() }
        for (i in 0 until songs.length()) {
            val song = songs.getJSONObject(i)
            val found = found(song)
            File(out, "${song.getString("id")}.json").writeText(read(song, found).toString())
            println("readingsaudit ${i + 1}/${songs.length()} ${song.getString("artist")} - ${song.getString("title")}")
        }
    }

    /** The song's words, and whether they're timed and have chords, from `found/`, or looked up and kept there. */
    private suspend fun found(song: JSONObject): JSONObject {
        val file = File(dir, "found/${song.getString("id")}.json")
        if (file.exists()) return JSONObject(file.readText())
        val metadata = metadata(song)
        val report = JSONObject()
        val answer = runCatching { lyrics.lyricsOf(metadata) }.onFailure { report.put("error", it.toString()) }.getOrNull()
        report.put("lyrics", answer?.let { it::class.simpleName!!.lowercase() } ?: "error")
        if (answer is LyricsFinder.Lyrics.Found) {
            val lines = answer.lines.map { it.text }.ifEmpty { answer.words.lines() }
            report.put("source", answer.source).put("timed", answer.lines.isNotEmpty()).put("lines", JSONArray(lines))
            report.put("chords", runCatching { chords.chordsOf(metadata, lines)::class.simpleName!!.lowercase() }.getOrElse { "error" })
        }
        file.parentFile!!.mkdirs()
        file.writeText(report.toString())
        // The services are free; asked a song at a time, with a breath between.
        delay(PAUSE_MS)
        return report
    }

    private fun metadata(song: JSONObject) = MusicMetadata(song.getString("title"), song.getString("artist"))

    /** Each line as the app reads it, with the built-in dictionary alone, with UniDic, and as shown, after UtaTen's fixes; and UtaTen's line for it. */
    private suspend fun read(song: JSONObject, found: JSONObject): JSONObject {
        val report = JSONObject(song.toString())
        listOf("lyrics", "source", "timed", "chords", "error").forEach { key -> found.opt(key)?.let { report.put(key, it) } }
        val lines = found.optJSONArray("lines")?.let { array -> (0 until array.length()).map(array::getString) } ?: return report
        if (Readings.displayScriptOf(lines) != Script.JAPANESE) return report.put("script", Readings.displayScriptOf(lines)?.name)
        val builtIn = Readings.of(lines, Script.JAPANESE)
        // Read as once UniDic is on the phone, as it is for most who read it, the fixes too.
        Readings.uniDic = TestUniDic.tokenizer
        val uniDic: List<LineReading>
        val shown: List<LineReading>
        var fixes: List<ReadingFix>? = null
        try {
            uniDic = Readings.of(lines, Script.JAPANESE)
            pages.clear()
            // Asked again if UtaTen took longer than the app waits, now that what it answered is kept.
            for (attempt in 1..TRIES) {
                fixes = sungReadings.of(metadata(song), lines, uniDic)
                if (fixes != null) break
            }
            shown = fixes?.takeIf { it.isNotEmpty() }?.let { Readings.corrected(uniDic, it) } ?: uniDic
        } finally {
            Readings.uniDic = null
        }
        val theirs = utaTen(lines, uniDic)
        report.put("script", "JAPANESE").put("sungFixes", fixes?.size ?: -1).put("utaten", theirs?.first)
        report.put("lines", JSONArray(lines.indices.map { i ->
            JSONObject().put("text", lines[i]).put("builtIn", parts(builtIn[i])).put("uniDic", parts(uniDic[i])).put("shown", parts(shown[i]))
                .put("truth", theirs?.second?.get(i)?.let(::rubies))
        }))
        return report
    }

    /**
     * The page of UtaTen's the app read that's most like the song, and its line most like each of
     * ours, as [SungReadings.matched] picks them; null when the app read none.
     */
    private fun utaTen(lines: List<String>, read: List<LineReading>): Pair<String, List<List<Ruby>?>>? = pages.map { (url, page) ->
        val theirs = SungReadings.linesOf(page)
        val written = theirs.map { line -> letters(line.joinToString("") { it.text }) }
        val said = theirs.map { line -> letters(line.joinToString("") { it.reading ?: it.text }) }
        url to lines.indices.map { index ->
            val likeness = theirs.indices.map { maxOf(SungReadings.alike(letters(lines[index]), written[it]), SungReadings.alike(letters(read[index].reading), said[it])) }
            likeness.indices.maxByOrNull { likeness[it] }?.takeIf { likeness[it] >= SungReadings.ALIKE }?.let { theirs[it] }
        }
    }.maxByOrNull { (_, matched) -> matched.count { it != null } }

    private fun letters(text: String) = Readings.hiragana(text.filter(Char::isLetterOrDigit).lowercase())

    private fun parts(line: LineReading) = rubies(line.parts)

    private fun rubies(parts: List<Ruby>) = JSONArray(parts.map { JSONArray().put(it.text).put(it.reading ?: JSONObject.NULL) })

    private companion object {
        const val PAUSE_MS = 1_500L
        const val ASK_EVERY_MS = 1_000L
        const val TRIES = 4
    }
}
