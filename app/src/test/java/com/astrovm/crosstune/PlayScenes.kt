package com.astrovm.crosstune

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.PlaybackInfoBuilder
import org.robolectric.shadows.ShadowMediaSessionManager

/** A kind of session an app shows: what it says it plays, by which app. */
internal data class Kind(val name: String, val app: String, val label: String, val title: String, val artist: String?, val album: String? = null)

/** A session of a [kind], on the phone or played [remote]ly, as a speaker, a TV or a computer has it, [playing] or paused. */
internal data class Played(val kind: Kind, val remote: Boolean, val playing: Boolean) {
    override fun toString() = "${kind.name}@${if (remote) "remote" else "phone"}:${if (playing) "playing" else "paused"}"
}

/** Sessions playing at once, the last one said where it is last, with the phone's own sound [sounding] or not. */
internal data class Scene(val sessions: List<Played>, val sounding: Boolean) {
    override fun toString() = sessions.joinToString("+") + if (sounding) " sound" else " quiet"
}

/**
 * Every kind of session there is, alone and two at a time, in every place, state and order, through the real
 * [MediaSessionPlayback] in Robolectric: what plays first, and what following a song finds. Plain enough to be copied
 * into a checkout of `main` to write what `main` made of each (scripts/main-golden.sh play).
 */
internal object PlayScenes {
    /** A song, as a music app names it, and the same title in every other way an app may send it. */
    val kinds = listOf(
        Kind("song", "com.spotify.music", "Spotify", "Fake Song", "Fake Band", "Fake Album"),
        Kind("ytmusic", "com.google.android.apps.youtube.music", "YouTube Music", "Other Song", "Other Band", "Other Album"),
        Kind("station", "com.ilv.vradio", "VRadio", "Fake FM", ""),
        Kind("file", "com.example.player", "Fake Player", "Fake Song.mp3", null),
        Kind("untitled", "com.example.untitled", "Untitled Player", "", null),
        Kind("vlog", "com.google.android.youtube", "YouTube", "Fake Vlog Day 3", "Fake Channel"),
        Kind("unknown", "com.example.files", "Fake Files", "Fake Song", "<unknown>"),
        Kind("dashed", "com.example.radio", "Fake Radio", "Fake Band - Fake Song", ""),
        Kind("computer", "org.kde.kdeconnect_tp", "KDE Connect", "Fake Song", "Fake Band", "Fake Album")
    )

    /** Songs a follow asks for: the song, as an app with no artist names it, a station, a video, and another one. */
    val asked = listOf(
        MusicMetadata("Fake Song", "Fake Band"), MusicMetadata("Fake Song", ""), MusicMetadata("Fake FM", ""),
        MusicMetadata("Fake Vlog Day 3", "Fake Channel"), MusicMetadata("Other Song", "Other Band"), MusicMetadata("Fake Band - Fake Song", "")
    )

    private fun each(kind: Kind) = listOf(false, true).flatMap { remote -> listOf(true, false).map { playing -> Played(kind, remote, playing) } }

    /** Each session alone, and each two of other apps in both orders, with the phone's sound on and off. */
    fun all(): List<Scene> {
        val alone = kinds.flatMap(::each).map(::listOf)
        val two = kinds.flatMap { one -> kinds.filter { it != one }.flatMap { other -> each(one).flatMap { first -> each(other).map { last -> listOf(first, last) } } } }
        return (alone + two).flatMap { sessions -> listOf(true, false).map { Scene(sessions, it) } }
    }

    /** What every scene is, to tell whether a file of answers was made from these. */
    fun fingerprint(scenes: List<Scene>): String = "${scenes.size}\t${scenes.joinToString("\n").hashCode()}"

    const val NOW = 500_000L
    private const val PLAYED_AT = 400_000L

    /** Allows Crosstune to see sessions, as the user does in Android's settings. */
    fun allow(app: Application) {
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", ComponentName(app, NowPlayingListener::class.java).flattenToString())
    }

    /**
     * Takes that back. AndroidX keeps the allowed listeners until the setting changes, which a cleared one doesn't count
     * as, so the access would carry over into the tests after, e.g. a TV's that asks for it.
     */
    fun disallow(app: Application) {
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", "")
        NotificationManagerCompat.getEnabledListenerPackages(app)
    }

    /** Sets [scene] up as Android shows it: its sessions, each a moment after the one before, and the phone's sound. */
    fun show(app: Application, scene: Scene) {
        val sessions = app.getSystemService(MediaSessionManager::class.java)
        ShadowMediaSessionManager.reset()
        shadowOf(app.getSystemService(AudioManager::class.java)).setIsMusicActive(scene.sounding)
        scene.sessions.forEachIndexed { i, played -> shadowOf(sessions).addController(controller(app, played, PLAYED_AT + i * 5L)) }
    }

    private fun controller(app: Application, played: Played, at: Long): MediaController {
        val kind = played.kind
        shadowOf(app.packageManager).installPackage(
            PackageInfo().apply {
                packageName = kind.app
                applicationInfo = ApplicationInfo().apply { packageName = kind.app; nonLocalizedLabel = kind.label }
            }
        )
        val controller = MediaController(app, MediaSession(app, kind.app).sessionToken)
        shadowOf(controller).setPackageName(kind.app)
        shadowOf(controller).setMetadata(
            MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, kind.title)
                .apply { if (kind.artist != null) putString(MediaMetadata.METADATA_KEY_ARTIST, kind.artist) }
                .apply { if (kind.album != null) putString(MediaMetadata.METADATA_KEY_ALBUM, kind.album) }
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 200_000).build()
        )
        val state = if (played.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        shadowOf(controller).setPlaybackState(PlaybackState.Builder().setState(state, 42_000, 1f, at).setActions(PlaybackState.ACTION_SEEK_TO).build())
        if (played.remote) shadowOf(controller).setPlaybackInfo(PlaybackInfoBuilder.newBuilder().setVolumeType(MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE).build())
        return controller
    }

    /** A song as a line: its app, title and artist. */
    fun named(song: Heard.Song?): String = song?.let { "named ${it.app} ${it.metadata.title}|${it.metadata.artist}" } ?: "none"

    /** Both a named song and a session with no name are playback. */
    fun plays(what: WhatPlays?): String = when (what) {
        is WhatPlays.Named -> named(what.song)
        is WhatPlays.Nameless -> what.playing.let { "nameless ${it.app} ${it.name.orEmpty()} ${it.where}${if (it.said) " said" else ""}" }
        null -> "none"
    }

    /**
     * A source that knows nothing yet, as each answer must stand alone: one kept from scene to scene holds what it
     * noted of an app's clock and lengths, and answered other than the same scene alone does. Shuffle and repeat aren't
     * asked of the apps, as Media3 connects to each session for that, and a few thousand of them made a follow fail.
     */
    fun playback(app: Application): MediaSessionPlayback {
        app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        return MediaSessionPlayback(app, now = { NOW }, waker = { null }, modes = NoModes)
    }

    /** Apps that don't say how they shuffle or repeat. */
    private object NoModes : AppModes {
        override fun shuffles(app: MediaController): Boolean? = null
        override fun shuffle(app: MediaController, on: Boolean) = Unit
        override fun repeats(app: MediaController): Repeat? = null
        override fun repeat(app: MediaController, how: Repeat) = Unit
        override fun watch(app: MediaController, handler: Handler, changed: () -> Unit): () -> Unit = {}
    }

    /** What following [song], in [app] or any, finds first, with a source new to it: the app that has it, and whether it plays. */
    fun follows(application: Application, song: MusicMetadata, app: String?): String {
        val playback = playback(application)
        var seen: Following? = null
        var said = false
        val job = CoroutineScope(Dispatchers.Unconfined).launch { seen = playback.follow(song, app).first(); said = true }
        shadowOf(Looper.getMainLooper()).idle()
        job.cancel()
        return if (!said) "silent" else seen?.let { "${it.appPackage} ${if (it.clock.playing) "playing" else "paused"}" } ?: "none"
    }

    /** The follows each scene is asked: each song, of any app and of each one's own, alone; the song of any app, of two. */
    fun followsOf(scene: Scene): List<Pair<MusicMetadata, String?>> =
        if (scene.sessions.size == 1) asked.flatMap { song -> listOf(song to null, song to scene.sessions[0].kind.app, song to "com.example.other") }
        else listOf(asked[0] to null, asked[1] to null)

    /** Only sequences with no ads belong in the main golden. */
    data class Report(
        val title: String = "Requested song", val id: String? = null, val duration: Long? = 253_000,
        val position: Long = 0, val album: String = "Album", val metadata: Boolean = true, val app: String = "ytmusic"
    )

    data class Replay(
        val name: String, val reports: List<Report>, val twoReaders: Boolean = false,
        val storedDurations: Pair<Long, Long>? = null, val requestedDuration: Long? = null,
        val videoId: String = "abcdefghijk", val catalogueDuration: Long = 253_000
    )

    val replays = listOf(
        Replay("skip and skip back", listOf(Report(id = "abcdefghijk"), Report("Next song", "lmnopqrstuv", 81_000),
            Report(id = "abcdefghijk", position = 90_000))),
        Replay("same title, new video", listOf(Report(id = "abcdefghijk"), Report(id = "lmnopqrstuv", duration = 90_000))),
        Replay("short edition without a position or ID", listOf(Report(duration = 90_000, position = -1), Report(duration = 90_000, position = 20))),
        Replay("81 second opaque edition from a single anchor", listOf(Report(), Report("Next song", "opaque-one", 81_000, 60_000))),
        Replay("same title and duration, different opaque IDs", listOf(Report(id = "opaque-one", duration = 75_000),
            Report(id = "opaque-two", duration = 75_000, position = 1))),
        Replay("Android fills in an ID, then moves on", listOf(Report(), Report(id = "opaque-one"), Report(id = "opaque-two"))),
        Replay("changed album without an ID", listOf(Report(duration = 90_000), Report(duration = 90_000, album = "Other album"))),
        Replay("interleaved old and new metadata", listOf(Report(duration = 262_617, position = 1_714),
            Report(position = 18_694, metadata = false), Report("Next song", duration = 252_261, position = 18_694))),
        Replay("ordinary seek and repeat", listOf(Report(id = "abcdefghijk"), Report(id = "abcdefghijk", position = 50_000),
            Report(id = "abcdefghijk", position = 1))),
        Replay("refresh with no reported duration on the same ID", listOf(Report(id = "abcdefghijk"),
            Report(id = "abcdefghijk", duration = null), Report(id = "abcdefghijk", duration = null, position = 500))),
        Replay("trimmed video", listOf(Report(id = "abcdefghijk"), Report(id = "abcdefghijk", duration = 180_000))),
        Replay("two readers with separate rounding histories", listOf(Report(duration = 239_000), Report(duration = 239_000, position = 3_000)),
            twoReaders = true, storedDurations = 240_000L to 238_000L),
        Replay("two readers see a skip", listOf(Report(id = "opaque-one"), Report("Next song", "opaque-two", 90_000)),
            twoReaders = true),
        Replay("a Deezer ID looks like a YouTube ID",
            listOf(Report(id = "12345678901", duration = 90_000, position = 12_000, app = "deezer")),
            requestedDuration = 253_000, videoId = "12345678901"),
        Replay("short no-ID recording changes app and rounds its duration",
            listOf(Report(duration = 89_000, position = 3_000), Report(duration = 90_000, position = 12_000, app = "deezer"))),
        Replay("Spotify track reports no ID then no duration",
            listOf(Report(duration = 90_000, app = "song"), Report(duration = null, position = 12_000, app = "song"))),
        Replay("unknown app reports a track without ID or duration",
            listOf(Report(duration = null, position = -1, app = "file"), Report(duration = null, position = 12_000, app = "file"))),
        Replay("the catalogue confirms a short trim", listOf(Report(id = "abcdefghijk", duration = 45_000, position = 12_000)),
            catalogueDuration = 45_000),
        Replay("the metadata is repeated when sound resumes",
            listOf(Report(id = "spotify:track:identifier", position = -1, app = "song"),
                Report(id = "spotify:track:identifier", position = 12_000, app = "song")))
    )

    fun replay(app: Application, replay: Replay, before: (MediaSessionPlayback) -> (() -> Unit) = { {} }): List<Pair<String, String>> {
        val replayKinds = (kinds + Kind("deezer", "deezer.android.app", "Deezer", "", null)).associateBy(Kind::name)
        fun kindOf(report: Report) = replayKinds.getValue(report.app).copy(title = "Requested song", artist = "Band", album = "Album")
        val kind = kindOf(replay.reports.first())
        val followApp = kind.app.takeIf { replay.reports.all { it.app == kind.name } }
        val manager = app.getSystemService(MediaSessionManager::class.java)
        ShadowMediaSessionManager.reset()
        shadowOf(app.getSystemService(AudioManager::class.java)).setIsMusicActive(true)
        var current = controller(app, Played(kind, remote = false, playing = true), PLAYED_AT)
        shadowOf(manager).addController(current)
        val sources = List(if (replay.twoReaders) 2 else 1) { i ->
            val prefs = app.getSharedPreferences("play_golden_reader_$i", Context.MODE_PRIVATE)
            prefs.edit().clear().commit()
            val lengths = SteadyLengths(prefs)
            replay.storedDurations?.let { lengths.of(kind.title, kind.artist.orEmpty(), if (i == 0) it.first else it.second) }
            MediaSessionPlayback(app, now = { NOW }, waker = { null }, modes = NoModes, lengths = lengths)
        }
        val watchers = mutableListOf<kotlinx.coroutines.Job>()
        val following = MutableList<Following?>(sources.size) { null }
        val main = shadowOf(Looper.getMainLooper())
        val after = before(sources.first())
        try {
            return replay.reports.flatMapIndexed { index, report ->
                val kindHere = kindOf(report)
                val changedApp = current.packageName != kindHere.app
                if (changedApp) current = controller(app, Played(kindHere, remote = false, playing = true), PLAYED_AT + index * 10_000L)
                val metadata = if (report.metadata) setReport(current, report) else null
                val state = PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, report.position, 1f, PLAYED_AT + index * 10_000L)
                    .setActions(PlaybackState.ACTION_SEEK_TO).build()
                shadowOf(current).setPlaybackState(state)
                if (changedApp) shadowOf(manager).apply {
                    clearControllers()
                    addController(current)
                }
                if (index == 0) sources.forEachIndexed { i, source ->
                    watchers += CoroutineScope(Dispatchers.Unconfined).launch {
                        source.follow(MusicMetadata("Requested song", "Band", url = "https://music.youtube.com/watch?v=${replay.videoId}",
                            lengthMs = replay.requestedDuration), followApp)
                            .collect { following[i] = it }
                    }
                }
                if (metadata != null) shadowOf(current).executeOnMetadataChanged(metadata)
                shadowOf(current).executeOnPlaybackStateChanged(state)
                main.idle()
                sources.mapIndexed { reader, source ->
                    val home = source.nowPlaying()
                    val clock = following[reader]
                    val key = "replay\t${replay.name}\t$index\t$reader"
                    key to "${named(home)}:${home?.metadata?.lengthMs}:${home?.metadata?.recordingId}:${home?.offsetMs}:${home?.ad} " +
                        "${clock?.clock?.positionMs}:${clock?.clock?.playing}:${clock?.ad}:${clock?.durationMs}"
                }
            }
        } finally {
            watchers.forEach { it.cancel() }
            after()
            main.idle()
        }
    }

    private fun setReport(controller: MediaController, report: Report): MediaMetadata =
        MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, report.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Band").putString(MediaMetadata.METADATA_KEY_ALBUM, report.album)
            .apply {
                report.id?.let { putString(MediaMetadata.METADATA_KEY_MEDIA_ID, it) }
                report.duration?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) }
            }.build().also { shadowOf(controller).setMetadata(it) }

}
