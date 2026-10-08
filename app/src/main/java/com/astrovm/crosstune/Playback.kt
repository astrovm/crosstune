package com.astrovm.crosstune

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInstaller
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Where in a song it is: [positionMs] at [atMs] on the clock that counts since the phone started,
 * moving on at [speed] while [playing].
 */
internal data class PlaybackClock(val positionMs: Long, val atMs: Long, val speed: Float = 1f, val playing: Boolean = true) {
    fun positionAt(nowMs: Long): Long = if (playing) positionMs + ((nowMs - atMs) * speed).toLong() else positionMs
}

/**
 * A song followed as it plays: where it is, the app playing it if one is, by name and package, and
 * whether a line can be jumped to.
 */
internal data class Following(val clock: PlaybackClock, val app: String?, val canSeek: Boolean, val appPackage: String? = null)

/** What's playing on the phone, as far as Android lets Crosstune see. */
internal interface PlaybackSource {
    /** Whether Android lets Crosstune see what plays, which the user allows in its settings. */
    fun hasAccess(): Boolean

    /** Where [song] is in the app playing it, as that changes; null while no app plays it. */
    fun follow(song: MusicMetadata): Flow<Following?>

    /** Moves the app playing [song] to [positionMs]. */
    fun seekTo(song: MusicMetadata, positionMs: Long)

    /**
     * Whether Android holds this access back until "Allow restricted settings" is turned on for
     * Crosstune, as it does from Android 13 for an app installed from a downloaded file.
     */
    fun restricted(): Boolean
}

/**
 * What music apps tell Android they're playing, through their media sessions. Android only shows
 * those to an app that may read notifications, so [NowPlayingListener] is that app's part; nothing
 * of the notifications themselves is read.
 */
internal class MediaSessionPlayback(
    private val context: Context,
    private val now: () -> Long = SystemClock::elapsedRealtime
) : PlaybackSource {
    private val listener = ComponentName(context, NowPlayingListener::class.java)
    private val sessions: MediaSessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    override fun hasAccess(): Boolean = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    override fun restricted(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { context.packageManager.getInstallSourceInfo(context.packageName).packageSource }.getOrNull() in fromAFile

    override fun follow(song: MusicMetadata): Flow<Following?> = callbackFlow {
        var followed: MediaController? = null
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                trySend(followed?.let(::following))
            }
        }
        // The app playing it, among all that are: one that is playing it beats one paused on it.
        fun pick(controllers: List<MediaController>) {
            val playing = controllers.filter { it.plays(song) }.maxByOrNull { if (it.playbackState?.state == PlaybackState.STATE_PLAYING) 1 else 0 }
            if (playing?.sessionToken != followed?.sessionToken) {
                followed?.unregisterCallback(callback)
                followed = playing
                playing?.registerCallback(callback, handler)
            }
            trySend(playing?.let(::following))
        }
        val changed = MediaSessionManager.OnActiveSessionsChangedListener { pick(it.orEmpty()) }
        // Without the user's say-so, e.g. taken back meanwhile, Android shows none, and says so by throwing.
        val watching = runCatching {
            sessions.addOnActiveSessionsChangedListener(changed, listener, handler)
            pick(sessions.getActiveSessions(listener))
        }.isSuccess
        if (!watching) trySend(null)
        awaitClose {
            sessions.removeOnActiveSessionsChangedListener(changed)
            followed?.unregisterCallback(callback)
        }
    }.distinctUntilChanged()

    override fun seekTo(song: MusicMetadata, positionMs: Long) {
        // Taken back meanwhile, the words just stay where they are.
        runCatching { sessions.getActiveSessions(listener).firstOrNull { it.plays(song) }?.transportControls?.seekTo(positionMs) }
    }

    private fun following(controller: MediaController): Following? {
        // Stopped, or failing, e.g. a video that won't play in the background, it plays nothing to follow.
        val state = controller.playbackState?.takeIf { it.state !in NOT_PLAYING } ?: return null
        val app = runCatching {
            context.packageManager.run { getApplicationLabel(getApplicationInfo(controller.packageName, 0)).toString() }
        }.getOrNull()
        // Its position is from when it last said so; since then it moved on at its own speed.
        val at = state.lastPositionUpdateTime.takeIf { it > 0 } ?: now()
        val clock = PlaybackClock(state.position, at, state.playbackSpeed.takeIf { it > 0f } ?: 1f, state.state == PlaybackState.STATE_PLAYING)
        return Following(clock, app, state.actions and PlaybackState.ACTION_SEEK_TO != 0L, controller.packageName)
    }

    /** Whether it's [song] this app plays: a video's title names more, e.g. "Artist - Song (Official Video)". */
    private fun MediaController.plays(song: MusicMetadata): Boolean {
        val playing = metadata ?: return false
        val title = playing.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = playing.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        if (title.isBlank() || !(SongNames.same(title, song.title) || SongNames.wordsMatch(title, song.title))) return false
        // A video's channel may be named "Artist - Topic", or not be the artist at all, when the title names them.
        return artist.isBlank() || SongNames.artistInside(artist, song.artist) || SongNames.sameArtist(song.artist, artist) ||
            SongNames.artistNames(song.artist).map(SongNames::words).any { it.isNotEmpty() && SongNames.words(title).containsAll(it) }
    }
}

/** Installed from a file rather than by an app store, which Android 13 and later restrict settings for. */
private val fromAFile = setOf(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE, PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)

/** States in which an app plays nothing to follow. */
private val NOT_PLAYING = setOf(PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR)

/**
 * Only here so Android shows Crosstune what music apps are playing, which it does for an app the
 * user lets read notifications. The notifications themselves are never looked at.
 */
class NowPlayingListener : NotificationListenerService()
