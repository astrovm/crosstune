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

/**
 * What plays first, and what a follow finds, in every [PlayScenes] scene, against what `main` did (play-golden.tsv.gz,
 * written by scripts/playcheck/golden.sh from 2c24d2d4). Each answer must be `main`'s, but where a rule below says it
 * changes, and then as it says.
 */
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
        assertEquals("play-golden.tsv.gz is of other scenes: run scripts/playcheck/golden.sh", "scenes\t${PlayScenes.fingerprint(scenes)}", lines.first())
        lines.drop(1).filter { it.isNotBlank() }.associate { it.substringBeforeLast("\t") to it.substringAfterLast("\t") }
    }

    /** What plays first now, as `main` would write it, or as what names no song. */
    private fun plays(what: WhatPlays?): String = when (what) {
        is WhatPlays.Named -> PlayScenes.named(what.song)
        is WhatPlays.Nameless -> what.playing.let { "nameless ${it.app} ${it.name.orEmpty()} ${it.where}${if (it.said) " said" else ""}" }
        null -> "none"
    }

    @Test
    fun everySceneAnswersAsMainOrAsAChangeMeantSays() {
        PlayScenes.allow(app)
        val differences = mutableListOf<String>()
        val wrong = scenes.flatMap { scene ->
            PlayScenes.show(app, scene)
            val what = PlayScenes.playback(app).whatPlays()
            val plays = "plays\t$scene"
            val asked = PlayScenes.followsOf(scene).map { (song, of) -> "follow\t$scene\t${song.title}|${song.artist}\t$of" to PlayScenes.follows(app, song, of) }
            (listOf(plays to plays(what)) + asked).mapNotNull { (key, now) ->
                val was = checkNotNull(main[key]) { key }
                val change = "$key\tmain $was\tnow $now"
                if (now != was) differences += change
                change.takeIf { now != meant(key, was, scene) }
            }
        }
        File("build/player-title-differences.tsv").writeText(differences.joinToString("\n", postfix = "\n"))
        assertEquals("", wrong.joinToString("\n"))
    }

    /**
     * `main`'s answer [was] to [key] in [scene], as the changes this branch means make it. Each is a rule, with why:
     * 1. An app that names no artist, none, "" or "<unknown>", names no song: what plays first there is the same session,
     *    shown as what names no song, to be named by ear (Issue #998), where `main` took its title for a song.
     * 2. What `main` read no song from, a video that doesn't say it's music or a session with no title, still plays: it's
     *    shown as what names no song, after every app that names one, where `main` showed nothing (owner's call, round 6).
     * 3. Without an artist, follows need a whole title. The file and unknown-artist fixture name Fake Song only;
     *    the station names Fake FM only. The dashed fixture also credits Fake Band, so it can name Fake Song.
     *    Other sessions keep their individual main-baseline matches. Their ranking is unchanged.
     */
    private fun meant(key: String, was: String, scene: Scene): String {
        val asked = key.split("\t")
        if (asked[0] == "plays") {
            val playing = scene.sessions.filter { it.playing && (scene.sounding || it.remote) }
            val first = was.removePrefix("named ").substringBefore(" ")
            // 1. Main's song, from an app that names no artist.
            playing.firstOrNull { it.kind.app == first && namesNoSong(it.kind) }?.let { return nameless(it, said = true) }
            // 2. Nothing main read, and what plays says no song a phone's session could be read as.
            if (was == "none") playing.filter { it.kind.name in READ_NO_SONG }.sortedWith(onThePhoneFirst(scene)).firstOrNull()?.let { return nameless(it, said = false) }
            return was
        }
        val title = asked[2].substringBefore('|')
        val of = asked[3].takeUnless { it == "null" }
        val candidates = scene.sessions.filter { played ->
            (of == null || played.kind.app == of) && when (played.kind.name) {
                "file", "unknown" -> title == "Fake Song"
                "station" -> title == "Fake FM"
                "dashed" -> title == "Fake Song" || title == "Fake Band - Fake Song"
                "untitled" -> false
                else -> {
                    val alone = Scene(listOf(played), scene.sounding)
                    checkNotNull(main["follow\t$alone\t${asked[2]}\tnull"]) != "none"
                }
            }
        }
        val chosen = candidates.maxByOrNull { if (it.playing) 1 else 0 } ?: return "none"
        return "${chosen.kind.app} ${if (chosen.playing) "playing" else "paused"}"
    }

    /** Kinds `main` read no song from: a video not said to be music, and a session with no title. */
    private val READ_NO_SONG = setOf("vlog", "untitled")

    private fun namesNoSong(kind: Kind) = kind.title.isNotEmpty() && Songless.namesNoSong(kind.title, kind.artist.orEmpty())

    /** What a session calls what it plays, past a file's extension. */
    private fun ownName(kind: Kind) = kind.title.removeSuffix(".mp3")

    /** The phone's own first, then one cast by a music app, then one on another device; of two in one place, the one that said so last. */
    private fun onThePhoneFirst(scene: Scene) = compareBy<Played>({ place(it).ordinal }, { -scene.sessions.indexOf(it) })

    private fun place(played: Played) = when {
        !played.remote -> Place.PHONE
        MusicService.ofPackage(played.kind.app) != null -> Place.CAST
        else -> Place.ELSEWHERE
    }

    private fun nameless(played: Played, said: Boolean) = "nameless ${played.kind.app} ${ownName(played.kind)} ${place(played)}${if (said) " said" else ""}"
}
