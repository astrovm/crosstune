package com.astrovm.crosstune.songcheck

import com.astrovm.crosstune.LyricsFinder
import com.astrovm.crosstune.MusicMetadata
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Live text measurement, excluded from normal tests. Use songCheck with songcheckTest and songcheckDir. */
@RunWith(RobolectricTestRunner::class)
class LyricsTextSnapshot {
    @Test fun snapshots() = runBlocking {
        val directory = File(System.getProperty("songcheck.dir") ?: "build/songcheck").apply { mkdirs() }
        val client = OkHttpClient()
        val songs = listOf(
            "Message in a Bottle" to "The Police", "Roxanne" to "The Police",
            "Wrapped Around Your Finger" to "The Police", "Every Little Thing She Does Is Magic" to "The Police",
            "Don't Stand So Close to Me" to "The Police", "Killing in the Name" to "Rage Against the Machine",
            "B.Y.O.B." to "System Of A Down", "YMCA" to "Village People"
        ) + File("../scripts/syncreplay/songs.tsv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }.take(20).map {
                val fields = it.split('\t')
                fields[0] to fields[1]
            }
        val results = JSONObject()
        for ((index, song) in songs.distinct().withIndex()) {
            val (title, artist) = song
            val found = LyricsFinder(client, "text-measurement", busyPauseMs = 0).lyricsOf(MusicMetadata(title, artist))
            File(directory, "$index.txt").writeText(found.toString())
            val result = if (found is LyricsFinder.Lyrics.Found) JSONObject()
                .put("source", found.source).put("words", found.words)
                .put("lines", JSONArray(found.lines.map { it.text }))
                .put("timed", JSONArray(found.lines.map { line ->
                    JSONObject().put("time", line.timeMs).put("end", line.endMs).put("text", line.text)
                        .put("words", JSONArray(line.words.map { JSONObject().put("time", it.timeMs).put("text", it.text) }))
                })) else found.toString()
            results.put("$title | $artist", result)
            File(directory, "results.json").writeText(results.toString(2))
        }
    }
}
