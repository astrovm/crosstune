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
internal data class Kind(val name: String, val app: String, val label: String, val title: String, val artist: String?, val album: String? = null, val duration: Long = 200_000, val id: String? = null)

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
        Kind("ytmusic-short", "com.google.android.apps.youtube.music", "YouTube Music", "Short Song", "Short Band", duration = 30_000, id = "4NRXx6U8ABQ"),
        Kind("spotify-short", "com.spotify.music", "Spotify", "Short Spotify", "Short Band", duration = 30_000, id = "4NRXx6U8ABQ"),
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
        MusicMetadata("Fake Vlog Day 3", "Fake Channel"), MusicMetadata("Other Song", "Other Band"), MusicMetadata("Fake Band - Fake Song", ""),
        MusicMetadata("Short Song", "Short Band"), MusicMetadata("Short Spotify", "Short Band")
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
                .apply { if (kind.id != null) putString(MediaMetadata.METADATA_KEY_MEDIA_ID, kind.id) }
                .putLong(MediaMetadata.METADATA_KEY_DURATION, kind.duration).build()
        )
        val state = if (played.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        shadowOf(controller).setPlaybackState(PlaybackState.Builder().setState(state, 42_000, 1f, at).setActions(PlaybackState.ACTION_SEEK_TO).build())
        if (played.remote) shadowOf(controller).setPlaybackInfo(PlaybackInfoBuilder.newBuilder().setVolumeType(MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE).build())
        return controller
    }

    /** A song as a line: its app, title and artist. */
    fun named(song: Heard.Song?): String = song?.let { "named ${it.app} ${it.metadata.title}|${it.metadata.artist}" } ?: "none"

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
}
