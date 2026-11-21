package com.astrovm.crosstune

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
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
    private lateinit var dir: File

    @Test
    fun replay() {
        val given = System.getenv("MATCHCHECK_DIR")?.let(::File)
        assumeTrue(given != null && File(given, "replay.json").isFile)
        dir = requireNotNull(given)
        val songs = JSONArray(File(dir, "replay.json").readText()).let { all -> (0 until all.length()).map(all::getJSONObject) }
        // An answer that can't be read would be taken for a lookup that failed, and every song would open nothing, the same
        // on main and the branch, which reads as a pass.
        val missing = songs.flatMap { song -> KINDS.mapNotNull { keptAnswer(song, it) } }.filterNot(File::isFile)
        assertTrue("replay.json names answers that aren't there (${missing.size}), as ${missing.take(2)}: run replay.py prepare again", missing.isEmpty())
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
            // A lookup in the US store names the song as its search did; the other stores, with their own names, when served.
            // Each by the song's id: one the store doesn't have is none.
            url.host == "itunes.apple.com" -> url.queryParameter("country").orEmpty().let { store ->
                val id = url.queryParameter("id")?.toLongOrNull()
                if (store == "US") song.getJSONArray("us").objects().firstOrNull { it.optLong("trackId") == id }
                else song.getJSONObject("native").optJSONObject(store)?.takeIf { names && it.optLong("trackId") == id }
            }?.let { FakeSpotify.html(request, JSONObject().put("results", JSONArray().put(it)).toString()) }
                ?: FakeSpotify.html(request, """{"results":[]}""")
            url.host == "api.deezer.com" -> FakeSpotify.html(request, JSONObject().put("data", song.getJSONArray("deezer")).toString())
            url.host == "music.youtube.com" -> {
                val videos = FakeSpotify.bodyOf(request).contains(VIDEOS)
                FakeSpotify.html(request, keptAnswer(song, if (videos) "videos" else "songs")?.readText() ?: "{}")
            }
            else -> throw IOException("not kept: $url")
        }
    }

    /**
     * Where YouTube Music's [kind] answer for [song] was kept, in [dir]; none when it never gave one. Older replay.json
     * files name it from the root, as they are.
     */
    private fun keptAnswer(song: JSONObject, kind: String): File? =
        song.takeUnless { it.isNull(kind) }?.getString(kind)?.let { path -> File(path).takeIf(File::isAbsolute) ?: File(dir, path) }

    private companion object {
        val KINDS = listOf("songs", "videos")
        const val VIDEOS = "EgWKAQIQAWoKEAoQAxAEEAkQBQ=="
        const val WHILE_MS = 60_000L
    }
}
