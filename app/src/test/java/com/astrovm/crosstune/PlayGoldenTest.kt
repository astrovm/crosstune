package com.astrovm.crosstune

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.zip.GZIPInputStream
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowMediaSessionManager

/** Ordinary playback is exactly what current main did. Known ads have their own tests. */
@RunWith(RobolectricTestRunner::class)
class PlayGoldenTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scenes = PlayScenes.all()

    @After
    fun tearDown() {
        ShadowMediaSessionManager.reset()
        PlayScenes.disallow(app)
    }

    /** `main`'s answers, by what was asked: "plays" and the scene, or "follow", the scene, the song and the app. */
    private val main: Map<String, String> by lazy {
        val lines = checkNotNull(javaClass.classLoader?.getResourceAsStream("play-golden.tsv.gz")).use { GZIPInputStream(it).bufferedReader().readLines() }
        assertEquals("play-golden.tsv.gz is of other scenes: run scripts/main-golden.sh play", "scenes\t${PlayScenes.fingerprint(scenes)}", lines.first())
        lines.drop(1).filter { it.isNotBlank() }.associate { it.substringBeforeLast("\t") to it.substringAfterLast("\t") }
    }

    @Test
    fun everySceneAnswersExactlyAsMain() {
        PlayScenes.allow(app)
        val found = scenes.flatMap { scene ->
            PlayScenes.show(app, scene)
            val what = PlayScenes.playback(app).whatPlays()
            val asked = PlayScenes.followsOf(scene).map { (song, of) ->
                "follow\t$scene\t${song.title}|${song.artist}\t$of" to PlayScenes.follows(app, song, of)
            }
            listOf("plays\t$scene" to PlayScenes.plays(what)) + asked
        }.toMap()
        val differences = found.mapNotNull { (key, now) ->
            val was = checkNotNull(main[key]) { key }
            "$key\tmain $was\tnow $now".takeIf { now != was }
        }
        File("build/player-title-differences.tsv").writeText(differences.joinToString("\n", postfix = "\n"))
        assertEquals("", differences.joinToString("\n"))
    }

    @Test
    fun everyReplayWithoutAnAdMatchesMainExactly() {
        PlayScenes.allow(app)
        val found = PlayScenes.replays.flatMap { replay ->
            PlayScenes.replay(app, replay) { source ->
                val evidence = source.youTubePlayEvidence
                val request = evidence.start(MusicService.YOUTUBE_MUSIC.packageName, replay.videoId, replay.catalogueDuration)
                val clear: () -> Unit = { evidence.clear(request) }
                clear
            }
        }.toMap()
        assertEquals(main.filterKeys { it.startsWith("replay\t") }, found)
    }
}
