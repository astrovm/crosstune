package com.astrovm.crosstune.songcheck

import com.astrovm.crosstune.ExactMatcher
import com.astrovm.crosstune.LyricsFinder
import com.astrovm.crosstune.MetadataParsers
import com.astrovm.crosstune.MusicMetadata
import com.astrovm.crosstune.MusicService
import com.astrovm.crosstune.SongNames
import com.astrovm.crosstune.httpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * What the app finds for each song in DIR/songs.json, as SongCheck does, and what the services said
 * on the way that could tell an instrumental recording from a sung one: each LRCLIB copy, whether the
 * app takes it for the song, its length and its instrumental mark, and YouTube Music's video and words
 * page. Only run by hand, to measure a rule before it's made (Issue #861):
 * ./gradlew :app:songCheck -PsongcheckTest=com.astrovm.crosstune.songcheck.InstrumentalProbe -PsongcheckDir=DIR
 * One file per song in DIR/probe/. They hold song words, so they stay out of the repo.
 */
@RunWith(RobolectricTestRunner::class)
class InstrumentalProbe {
    private val dir = File(System.getProperty("songcheck.dir") ?: "build/songcheck")
    private val heard = mutableListOf<Pair<HttpUrl, String>>()
    private val client = httpClient().newBuilder().addInterceptor { chain ->
        val response = chain.proceed(chain.request())
        val url = chain.request().url
        if (url.host == "lrclib.net" || url.host == "music.youtube.com") {
            val body = response.peekBody(MAX_ANSWER_BYTES).string()
            val asked = chain.request().body?.let { sent -> okio.Buffer().also(sent::writeTo).readUtf8() }.orEmpty()
            synchronized(heard) { heard += url to JSONObject().put("asked", asked).put("body", body).toString() }
        }
        response
    }.build()
    private val matcher = ExactMatcher(client, country = "US")
    private val lyrics = LyricsFinder(
        client,
        "songcheck",
        youTubeMusicSong = { song -> matcher.find(MusicService.YOUTUBE_MUSIC, song)?.substringAfter("watch?v=", "")?.ifEmpty { null } }
    )

    @Test
    fun probe() = runBlocking {
        val out = File(dir, "probe").apply { mkdirs() }
        val songs = JSONArray(File(dir, "songs.json").readText())
        for (i in 0 until songs.length()) {
            val song = songs.getJSONObject(i)
            val file = File(out, "${song.getString("id")}.json")
            // A run stopped halfway goes on where it was.
            if (file.exists()) continue
            synchronized(heard) { heard.clear() }
            file.writeText(check(song).toString(1))
            println("probe ${i + 1}/${songs.length()} ${song.getString("artist")} - ${song.getString("title")}")
            delay(PAUSE_MS)
        }
    }

    private suspend fun check(song: JSONObject): JSONObject {
        val metadata = MusicMetadata(song.getString("title"), song.getString("artist"), lengthMs = song.optLong("lengthMs").takeIf { it > 0 })
        val report = JSONObject(song.toString())
        val found = try {
            lyrics.lyricsOf(metadata)
        } catch (e: Exception) {
            report.put("error", "lyrics: $e")
            null
        }
        report.put("lyrics", when (found) {
            is LyricsFinder.Lyrics.Found -> JSONObject().put("kind", "found").put("source", found.source).put("lengthMs", found.lengthMs)
                .put("wordLines", found.words.lines().count { it.isNotBlank() }).put("timedLines", found.lines.count { it.text.isNotBlank() })
            null -> JSONObject().put("kind", "error")
            else -> JSONObject().put("kind", found::class.simpleName!!.lowercase())
        })
        val answers = synchronized(heard) { heard.toList() }
        report.put("lrclib", JSONArray(answers.filter { it.first.host == "lrclib.net" }.map { (url, said) -> lrclibSearch(url, JSONObject(said).getString("body")) }))
        report.put("youtubeMusic", JSONArray(answers.filter { it.first.host == "music.youtube.com" }.map { (url, said) -> youTubeMusic(url, JSONObject(said)) }))
        return report
    }

    /** One LRCLIB search: what was asked, and each copy it gave, with whether the app takes it for the song asked about. */
    private fun lrclibSearch(url: HttpUrl, body: String): JSONObject {
        val title = url.queryParameter("track_name")
        val artist = url.queryParameter("artist_name")
        val search = JSONObject().put("track", title).put("artist", artist).put("q", url.queryParameter("q"))
        val list = runCatching { JSONArray(body) }.getOrNull() ?: return search.put("error", body.take(200))
        val asked = if (title != null && artist != null) MusicMetadata(title, artist) else null
        return search.put("copies", JSONArray((0 until list.length()).mapNotNull { list.optJSONObject(it) }.map { copy ->
            val name = copy.optString("trackName").trim()
            val by = copy.optString("artistName").trim()
            // The app's own tests, as LyricsFinder.Answer has them.
            val exact = asked != null && SongNames.normalize(name) == SongNames.normalize(asked.title) && SongNames.sameArtist(by, asked.artist)
            val belongs = asked != null && (SongNames.wordsMatch(name, asked.title) || SongNames.sameVersion(asked.title, name, copy.optString("albumName"))) &&
                SongNames.artistInside(by, SongNames.withGuests(asked.artist, asked.title))
            JSONObject().put("id", copy.optLong("id")).put("title", name).put("artist", by).put("album", copy.optString("albumName"))
                .put("seconds", (copy.opt("duration") as? Number)?.toDouble()).put("instrumental", copy.optBoolean("instrumental"))
                .put("plain", (copy.opt("plainLyrics") as? String)?.isNotBlank() == true).put("synced", (copy.opt("syncedLyrics") as? String)?.isNotBlank() == true)
                .put("plainLines", (copy.opt("plainLyrics") as? String)?.lines()?.count { it.isNotBlank() } ?: 0)
                .put("exact", exact).put("belongs", belongs)
        }))
    }

    /** One YouTube Music answer: the video it plays and its words page, or the words page itself, kept whole to read. */
    private fun youTubeMusic(url: HttpUrl, said: JSONObject): JSONObject {
        val asked = runCatching { JSONObject(said.getString("asked")) }.getOrNull()
        val body = said.getString("body")
        val answer = JSONObject().put("path", url.encodedPath)
        asked?.optString("videoId")?.ifEmpty { null }?.let { id ->
            answer.put("videoId", id)
            runCatching { MetadataParsers.youtubeMusicVideo(JSONObject(body), id) }.getOrNull()?.let {
                answer.put("title", it.title).put("artist", it.artist).put("lengthMs", it.lengthMs)
            }
            answer.put("wordsPage", Regex(""""browseId":"(MPLY[^"]+)"""").containsMatchIn(body))
        }
        asked?.optString("browseId")?.ifEmpty { null }?.let { answer.put("browseId", it).put("body", body) }
        return answer
    }

    private companion object {
        const val PAUSE_MS = 1_500L
        const val MAX_ANSWER_BYTES = 8L * 1024 * 1024
    }
}
