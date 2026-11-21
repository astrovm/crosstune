package com.astrovm.crosstune

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Writes what a checkout's [MediaSessionPlayback] makes of every [PlayScenes] scene, for scripts/main-golden.sh to keep as what `main`
 * did: what it offers as playing, and what each follow finds.
 * Not one of the app's tests: main-golden.sh copies it into a checkout of `main` and runs it there.
 */
@RunWith(RobolectricTestRunner::class)
class PlayGoldenWriter {
    @Test
    fun write() {
        val app: Application = ApplicationProvider.getApplicationContext()
        PlayScenes.allow(app)
        val scenes = PlayScenes.all()
        val lines = scenes.flatMap { scene ->
            PlayScenes.show(app, scene)
            listOf("plays\t$scene\t${PlayScenes.named(PlayScenes.playback(app).nowPlaying())}") +
                PlayScenes.followsOf(scene).map { (song, of) -> "follow\t$scene\t${song.title}|${song.artist}\t$of\t${PlayScenes.follows(app, song, of)}" }
        }
        File("build/play-golden.tsv").writeText((listOf("scenes\t${PlayScenes.fingerprint(scenes)}") + lines).joinToString("\n", postfix = "\n"))
    }
}
