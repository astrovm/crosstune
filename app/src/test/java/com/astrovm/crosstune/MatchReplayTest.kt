package com.astrovm.crosstune

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The songs scripts/matchcheck/collect.py kept, looked for on YouTube Music through the real [ExactMatcher], with
 * every service served what it answered then: see scripts/matchcheck/replay.py, which prepares MATCHCHECK_DIR and
 * judges what this writes. Skipped without MATCHCHECK_DIR, as what the services answered stays out of git.
 */
@RunWith(RobolectricTestRunner::class)
class MatchReplayTest {

    private val fake = FakeSpotify()
    private lateinit var dir: File
    private var proofs = JSONObject()
    private val missingProofs = ConcurrentHashMap.newKeySet<String>()

    @Test
    fun candidateSearchesNeedTheirOwnCapturedAnswers() {
        val song = JSONObject().put("query", "Song Artist").put("us", JSONArray())
        val request = Request.Builder().url("https://itunes.apple.com/search?country=JP&term=Candidate%20Artist").build()
        assertThrows(IOException::class.java) { answer(request, song, emptyMap(), false, false) }
        assertEquals(setOf("JP|Candidate Artist"), missingProofs)
        proofs.put("JP|Candidate Artist", JSONArray().put(JSONObject().put("trackId", 17)))
        val result = answer(request, song, emptyMap(), false, false).body.string()
        assertEquals(17, JSONObject(result).getJSONArray("results").getJSONObject(0).getInt("trackId"))
    }

    @Test
    fun replay() {
        val given = System.getenv("MATCHCHECK_DIR")?.let(::File)
        assumeTrue(given != null && File(given, "replay.json").isFile)
        dir = requireNotNull(given)
        proofs = if (System.getenv("MATCHCHECK_PROBE") == "1") JSONObject() else {
            File(dir, "candidate-proofs.json").takeIf(File::isFile)?.let { JSONObject(it.readText()) } ?: JSONObject()
        }
        val songs = JSONArray(File(dir, "replay.json").readText()).let { all -> (0 until all.length()).map(all::getJSONObject) }
        val wrongQueries = songs.filter { song ->
            val query = song.optString("query")
            query.isNotEmpty() && query != searchQuery(MusicMetadata(song.getString("title"), song.getString("artist")))
        }.map { it.optString("query") }
        assertTrue("captured queries differ from the app's searches: $wrongQueries", wrongQueries.isEmpty())
        // Every answer is read now, once, and served from here: one that can't be read, missing or not allowed, would be
        // taken for a lookup that failed, and every song would open nothing, the same on main and the branch, which reads
        // as a pass. Read again later, one that went away meanwhile would do the same.
        val unread = mutableListOf<File>()
        val kept = songs.map { song ->
            KINDS.associateWith { kind -> keptAnswer(song, kind)?.let { file -> runCatching { file.readText() }.getOrElse { unread += file; null } } }
        }
        assertTrue("replay.json names answers that can't be read (${unread.size}), as ${unread.take(2)}: run replay.py prepare again", unread.isEmpty())
        val both = System.getenv("MATCHCHECK_BOTH") == "1"
        val down = System.getenv("MATCHCHECK_APPLE_DOWN") == "1"
        for (appleDown in if (both) listOf(false, true) else listOf(down)) {
            replay(songs, kept, appleDown, both)
        }
        File(dir, "missing-proofs.json").writeText(JSONArray(missingProofs.sorted()).toString())
        if (System.getenv("MATCHCHECK_REQUIRE_PROOFS") == "1") assertTrue("Missing candidate Apple searches: $missingProofs", missingProofs.isEmpty())
    }

    private fun replay(songs: List<JSONObject>, kept: List<Map<String, String?>>, appleDown: Boolean, both: Boolean) {
        val found = JSONObject()
        val shown = JSONObject()
        for (names in listOf(true, false)) {
            for (asked in listOf(false, true)) {
                val urls = JSONArray()
                val asks = JSONArray()
                songs.zip(kept).forEach { (song, answers) ->
                    val seen = JSONArray()
                    urls.put(find(song, answers, names, asked, seen, appleDown) ?: JSONObject.NULL)
                    asks.put(seen)
                }
                val mode = "${if (names) "names" else "latin"}, ${if (asked) "asked" else "alone"}"
                found.put(mode, urls)
                shown.put(mode, asks)
            }
        }
        found.put("shown", shown)
        val label = System.getenv("MATCHCHECK_LABEL") ?: "head"
        val suffix = if (both) if (appleDown) "-down" else "-up" else ""
        File(dir, "replay-$label$suffix.json").writeText(found.toString())
    }

    /**
     * What [song] opens, with YouTube Music's kept [answers] served, and what ChatGPT was shown put in [seen], each time:
     * the rows, then what it picked.
     */
    private fun find(song: JSONObject, answers: Map<String, String?>, names: Boolean, asked: Boolean, seen: JSONArray, appleDown: Boolean): String? {
        val right = song.getJSONArray("right").let { ids -> (0 until ids.length()).map(ids::getString).toSet() }
        val shownNames = song.getJSONObject("names")
        val rightShown = right.mapNotNull { shownNames.optString(it).ifEmpty { null } }.toSet()
        fake.handler = { request -> answer(request, song, answers, names, appleDown) }
        // ChatGPT, when asked, picks the first right result it's shown, and none, -1, when it's shown none.
        val pick: suspend (MusicMetadata, List<String>, Boolean) -> Int? = { _, rows, _ ->
            rows.indexOfFirst { it in rightShown }.also { seen.put(JSONArray().put(JSONArray(rows)).put(it)) }
        }
        // Long whiles, as a busy machine is no answer from a service here: each answer is kept.
        val matcher = ExactMatcher(fake.client(), "US", pick = if (asked) pick else { _, _, _ -> null }, timeoutMs = WHILE_MS, pickTimeoutMs = WHILE_MS)
        return runBlocking { matcher.find(MusicService.YOUTUBE_MUSIC, MusicMetadata(song.getString("title"), song.getString("artist"), lengthMs = song.optLong("lengthMs").takeIf { it > 0 })) }
    }

    private fun answer(request: Request, song: JSONObject, answers: Map<String, String?>, names: Boolean, appleDown: Boolean): okhttp3.Response {
        val url = request.url
        if (url.host == "itunes.apple.com" && appleDown) throw IOException("Apple is down")
        return when {
            url.host == "itunes.apple.com" && url.encodedPath == "/search" -> {
                val term = url.queryParameter("term").orEmpty()
                val store = url.queryParameter("country").orEmpty()
                val results = if (term == song.optString("query")) {
                    if (store == "US") song.getJSONArray("us") else JSONArray()
                } else {
                    val key = "$store|$term"
                    proofs.optJSONArray(key) ?: throw IOException("not captured: $key").also { missingProofs += key }
                }
                FakeSpotify.html(request, JSONObject().put("results", results).toString())
            }
            // A lookup in the US store names the song as its search did; the other stores, with their own names, when served.
            // Each by the song's id: one the store doesn't have is none.
            url.host == "itunes.apple.com" -> url.queryParameter("country").orEmpty().let { store ->
                val id = url.queryParameter("id")?.toLongOrNull()
                if (store == "US") song.getJSONArray("us").objects().firstOrNull { it.optLong("trackId") == id }
                else song.getJSONObject("native").optJSONObject(store)?.takeIf { names && it.optLong("trackId") == id }
            }?.let { FakeSpotify.html(request, JSONObject().put("results", JSONArray().put(it)).toString()) }
                ?: FakeSpotify.html(request, """{"results":[]}""")
            url.host == "api.deezer.com" -> FakeSpotify.html(request, JSONObject().put("data", song.getJSONArray(if (url.encodedPath == "/search/artist") "deezerArtists" else "deezer")).toString())
            url.host == "music.youtube.com" -> {
                val videos = FakeSpotify.bodyOf(request).contains(VIDEOS)
                FakeSpotify.html(request, answers[if (videos) "videos" else "songs"] ?: "{}")
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
