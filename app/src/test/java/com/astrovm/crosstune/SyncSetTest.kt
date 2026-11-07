package com.astrovm.crosstune

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The songs of scripts/syncreplay/songs.tsv, found as the app finds them: each one's recording on
 * YouTube Music, and its words, for `syncreplay.py set` to prep and replay. Live, so it only runs
 * when that script asks, by leaving set.asked in build/syncreplay. Writes set.json there, or in
 * SYNCREPLAY_DIR when set, and each song's words as ID.lyrics.json: [ms, line] each, or -1 for ms when
 * the words came without timing, and as ID.found.json, as the app keeps them: each line's words too,
 * when it's timed word by word. A row naming a recording, in a fourth column, is held to that one.
 */
@RunWith(RobolectricTestRunner::class)
class SyncSetTest {
    @Test
    fun findTheSet() {
        // Run from the app's own folder, the replay's where the whole project builds.
        val asked = listOf(File("../build/syncreplay"), File("build/syncreplay")).firstOrNull { File(it, "set.asked").exists() }
        assumeTrue("Only when scripts/syncreplay asks", asked != null)
        if (asked == null) return
        val dir = System.getenv("SYNCREPLAY_DIR")?.let(::File) ?: asked
        val songs = listOf(File("../scripts/syncreplay/songs.tsv"), File("scripts/syncreplay/songs.tsv")).first { it.isFile }.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split("\t") }
        val client = httpClient()
        val matcher = ExactMatcher(client, country = "US")
        val lyrics = LyricsFinder(
            client,
            versionName = "sync-set",
            youTubeMusicSong = { song -> runBlocking { matcher.find(MusicService.YOUTUBE_MUSIC, song) }?.substringAfter("watch?v=", "")?.ifEmpty { null } }
        )
        val set = JSONArray()
        for (row in songs) {
            val (title, artist, why) = row
            val song = MusicMetadata(title, artist)
            val id = row.getOrNull(3) ?: runBlocking { matcher.find(MusicService.YOUTUBE_MUSIC, song) }?.substringAfter("watch?v=", "")?.substringBefore("&")?.ifEmpty { null }
            val found = runBlocking { lyrics.lyricsOf(song) } as? LyricsFinder.Lyrics.Found
            val entry = JSONObject().put("title", title).put("artist", artist).put("why", why).put("id", id)
            if (id != null && found != null) {
                val lines = found.lines.map { it.timeMs to it.text }.ifEmpty { found.words.lines().filter { it.isNotBlank() }.map { -1L to it.trim() } }
                // Words already kept for a song, as from the phone's own cache, stay as they are.
                File(dir, "$id.lyrics.json").takeUnless { it.exists() }?.writeText(JSONArray(lines.map { (ms, text) -> JSONArray().put(ms).put(text) }).toString())
                // And as the app found them, word by word when they are, for the replay.
                val keptLines = JSONArray(found.lines.map { line ->
                    JSONArray().put(line.timeMs).put(line.text).put(JSONArray(line.words.map { JSONArray().put(it.timeMs).put(it.text) })).apply { line.endMs?.let { put(it) } }
                })
                File(dir, "$id.found.json").writeText(JSONObject().put("source", found.source).put("length", found.lengthMs).put("lines", keptLines).toString())
                entry.put("source", found.source).put("lengthMs", found.lengthMs).put("timed", found.lines.isNotEmpty()).put("lines", lines.size)
            }
            set.put(entry)
            println("${id ?: "not on YouTube Music"}\t${found?.source ?: "no words"}\t$title\t$artist")
        }
        File(dir, "set.json").writeText(set.toString(1))
    }
}
