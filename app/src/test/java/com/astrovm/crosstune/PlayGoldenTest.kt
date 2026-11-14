package com.astrovm.crosstune

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.zip.GZIPInputStream
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
    fun tearDown() = ShadowMediaSessionManager.reset()

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
        val wrong = scenes.flatMap { scene ->
            PlayScenes.show(app, scene)
            val what = PlayScenes.playback(app).whatPlays()
            val plays = "plays\t$scene"
            val asked = PlayScenes.followsOf(scene).map { (song, of) -> "follow\t$scene\t${song.title}|${song.artist}\t$of" to PlayScenes.follows(app, song, of) }
            (listOf(plays to plays(what)) + asked).mapNotNull { (key, now) ->
                val was = checkNotNull(main[key]) { key }
                "$key\tmain $was\tnow $now".takeIf { now != meant(key, was, scene) }
            }
        }
        assertEquals("", wrong.joinToString("\n"))
    }

    /**
     * `main`'s answer [was] to [key] in [scene], as the changes this branch means make it. Each is a rule, with why:
     * 1. An app that names no artist, none, "" or "<unknown>", names no song: what plays first there is the same session,
     *    shown as what names no song, to be named by ear (Issue #998), where `main` took its title for a song.
     * 2. What `main` read no song from, a video that doesn't say it's music or a session with no title, still plays: it's
     *    shown as what names no song, after every app that names one, where `main` showed nothing (owner's call, round 6).
     * 3. A session that names no song has a song asked for by its own name only, never by words it shares (round 10),
     *    so "Fake Song" by "<unknown>" isn't "Fake Band - Fake Song". `main` took it so, but not the same file with no artist.
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
        // 3. Followed in an app that names no song, by words its name shares with the song's.
        val (title, _) = asked[2].split("|")
        val app = was.substringBefore(" ")
        val session = scene.sessions.firstOrNull { it.kind.app == app }
        if (session != null && namesNoSong(session.kind) && title != ownName(session.kind)) return "none"
        return was
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
