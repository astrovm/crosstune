package com.astrovm.crosstune

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/**
 * The songs scripts/matchcheck/collect.py kept, looked for on YouTube Music through the real [ExactMatcher], with
 * every service served what it answered then: see scripts/matchcheck/replay.py, which prepares MATCHCHECK_DIR and
 * judges what this writes. Skipped without MATCHCHECK_DIR, as what the services answered stays out of git.
 */
@RunWith(RobolectricTestRunner::class)
class MatchReplayTest {

    private val fake = FakeSpotify()

    @Test
    fun replay() {
        val dir = System.getenv("MATCHCHECK_DIR")?.let(::File)
        assumeTrue(dir != null && File(dir, "replay.json").isFile)
        val out = File(requireNotNull(dir), "replay.json")
        val songs = JSONArray(out.readText()).let { all -> (0 until all.length()).map(all::getJSONObject) }
        val found = JSONObject()
        val shown = JSONObject()
        for (names in listOf(true, false)) {
            for (asked in listOf(false, true)) {
                val urls = JSONArray()
                val asks = JSONArray()
                songs.forEach { song ->
                    val seen = JSONArray()
                    urls.put(find(song, names, asked, seen) ?: JSONObject.NULL)
                    asks.put(seen)
                }
                val mode = "${if (names) "names" else "latin"}, ${if (asked) "asked" else "alone"}"
                found.put(mode, urls)
                shown.put(mode, asks)
            }
        }
        found.put("shown", shown)
        File(dir, "replay-${System.getenv("MATCHCHECK_LABEL") ?: "head"}.json").writeText(found.toString())
    }

    /** What [song] opens, with what ChatGPT was shown put in [seen], each time: the rows, then what it picked. */
    private fun find(song: JSONObject, names: Boolean, asked: Boolean, seen: JSONArray): String? {
        val right = song.getJSONArray("right").let { ids -> (0 until ids.length()).map(ids::getString).toSet() }
        val shownNames = song.getJSONObject("names")
        val rightShown = right.mapNotNull { shownNames.optString(it).ifEmpty { null } }.toSet()
        fake.handler = { request -> answer(request, song, names) }
        // ChatGPT, when asked, picks the first right result it's shown, and none, -1, when it's shown none.
        val pick: suspend (MusicMetadata, List<String>, Boolean) -> Int? = { _, rows, _ ->
            rows.indexOfFirst { it in rightShown }.also { seen.put(JSONArray().put(JSONArray(rows)).put(it)) }
        }
        // Long whiles, as a busy machine is no answer from a service here: each answer is kept.
        val matcher = ExactMatcher(fake.client(), "US", pick = if (asked) pick else { _, _, _ -> null }, timeoutMs = WHILE_MS, pickTimeoutMs = WHILE_MS)
        return runBlocking { matcher.find(MusicService.YOUTUBE_MUSIC, MusicMetadata(song.getString("title"), song.getString("artist"))) }
    }

    private fun answer(request: Request, song: JSONObject, names: Boolean): okhttp3.Response {
        val url = request.url
        return when {
            url.host == "itunes.apple.com" && url.encodedPath == "/search" ->
                FakeSpotify.html(request, JSONObject().put("results", if (url.queryParameter("country") == "US") song.getJSONArray("us") else JSONArray()).toString())
            url.host == "itunes.apple.com" -> song.getJSONObject("native").optJSONObject(url.queryParameter("country").orEmpty())?.takeIf { names }
                ?.let { FakeSpotify.html(request, JSONObject().put("results", JSONArray().put(it)).toString()) }
                ?: FakeSpotify.html(request, """{"results":[]}""")
            url.host == "api.deezer.com" -> FakeSpotify.html(request, JSONObject().put("data", song.getJSONArray("deezer")).toString())
            url.host == "music.youtube.com" -> {
                val videos = FakeSpotify.bodyOf(request).contains(VIDEOS)
                val path = song.optString(if (videos) "videos" else "songs").ifEmpty { null }
                FakeSpotify.html(request, path?.let { File(it).readText() } ?: "{}")
            }
            else -> throw IOException("not kept: $url")
        }
    }

    private companion object {
        const val VIDEOS = "EgWKAQIQAWoKEAoQAxAEEAkQBQ=="
        const val WHILE_MS = 60_000L
    }
}
