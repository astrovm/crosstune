package com.astrovm.crosstune

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * Where in a song it is: [positionMs] at [atMs] on the clock that counts since the phone started,
 * moving on at [speed] while [playing].
 */
internal data class PlaybackClock(val positionMs: Long, val atMs: Long, val speed: Float = 1f, val playing: Boolean = true) {
    fun positionAt(nowMs: Long): Long = if (playing) positionMs + ((nowMs - atMs) * speed).toLong() else positionMs
}

/**
 * An app's clock can be a moment behind the song, as YouTube Music's often is, by over a second
 * after a pause. The song plays on from where it really is, so the words would come in late.
 *
 * Where it says it paused can be behind where the song really stopped, and so can where it says it
 * plays again from: where it was playing on to is believed instead, until the app says it's somewhere
 * else, e.g. moved to another place.
 *
 * While it keeps playing, saying it's a moment back is stale too: as each song starts, YouTube
 * Music says it's at the start, then says so again most of a second later. Where it was playing on to
 * is kept. Further back is a move, e.g. to an earlier part, and so is any move Crosstune asks for.
 */
internal class SteadyClock(
    /** How far behind its first the app's last word was as songs started there; see [ClockLags]. */
    private val lagMs: Long,
    private val now: () -> Long,
    /** Told how far behind a stale clock was. */
    private val learn: (Long) -> Unit
) {
    private var last: PlaybackClock? = null
    /** Where the app said it paused, when that was a moment behind; and where it had really got to. */
    private var said: Long? = null
    private var stopped = 0L

    fun of(clock: PlaybackClock): PlaybackClock {
        val was = last ?: return pickedUp(clock).also { last = it }
        val behind = was.takeIf { it.playing }?.let { it.positionAt(clock.atMs) - clock.positionMs }
        val stale = behind != null && behind in 1..STALE_CLOCK_MS
        if (stale && clock.playing && clock.speed == was.speed) return was.also { if (behind >= SAYING_MS) learn(behind) }
        // Played again from a moment behind where it stopped, as YouTube Music says it is, it plays on from where it stopped.
        if (!was.playing && clock.playing && was.positionMs - clock.positionMs in 1..STALE_CLOCK_MS) return clock.copy(positionMs = was.positionMs).also { last = it }
        if (behind != null && !clock.playing) {
            said = clock.positionMs.takeIf { stale }
            stopped = clock.positionMs + behind
        } else if (clock.positionMs != said) {
            said = null
        }
        return (if (said != null) clock.copy(positionMs = stopped) else clock).also { last = it }
    }

    /**
     * Picked up as it plays, the app's word is the last it said as the song started, behind by as much as
     * it was then. Said less than that long ago, it may be the first, which is right.
     */
    private fun pickedUp(clock: PlaybackClock): PlaybackClock =
        if (clock.playing && now() - clock.atMs >= lagMs) clock.copy(positionMs = clock.positionMs + (lagMs * clock.speed).toLong()) else clock
}

/**
 * How far behind what it plays each music app says it is, when Crosstune picks it up as it plays. As
 * a song starts, YouTube Music says it's at the start, which is right, then says so again most of a
 * second later, and only that one is left to see later on: 0.7 to 1 s behind, a little more or less
 * each time. Learnt from the songs Crosstune sees start, a few averaged, and kept from day to day.
 */
internal class ClockLags(private val preferences: SharedPreferences) {
    private val seen: JSONObject by lazy { runCatching { JSONObject(preferences.getString(KEY, null) ?: "{}") }.getOrElse { JSONObject() } }

    @Synchronized
    fun of(app: String): Long = seen.optLong(app)

    @Synchronized
    fun learn(app: String, lagMs: Long) {
        seen.put(app, if (seen.has(app)) (seen.getLong(app) * (AVERAGED - 1) + lagMs) / AVERAGED else lagMs)
        preferences.edit { putString(KEY, seen.toString()) }
    }

    private companion object {
        /** How many songs' lags make up the one used, about. */
        const val AVERAGED = 4
        const val KEY = "clock_lags"
    }
}

/**
 * A song followed as it plays: where it is, the app playing it if one is, by name and package, and
 * whether a line can be jumped to. In a [video] the app says where the video is, which may have
 * more before the song starts. The app may also go on to its next song, or back to the one before.
 */
internal data class Following(
    val clock: PlaybackClock,
    val app: String?,
    val canSeek: Boolean,
    val appPackage: String? = null,
    val video: Boolean = false,
    val canSkipNext: Boolean = false,
    val canSkipPrevious: Boolean = false,
    /** How long what it plays is, when it says; for a video, the video. */
    val durationMs: Long? = null,
    /** The player clock is in the lyrics' recording, rather than an uncalibrated video. */
    val timingKnown: Boolean = true,
    /** Whether it shuffles; null when it doesn't say, or can't be told to. */
    val shuffle: Boolean? = null,
    /** How it repeats; null when it doesn't say, or can't be told to. */
    val repeat: Repeat? = null,
    /** The app's own button to like the song, or keep it in the user's collection there, as the app shows it now. */
    val like: AppAction? = null,
    /** What the app plays after it, as far as it says. */
    val upNext: List<Queued> = emptyList(),
    /** The list it plays from, when Crosstune is playing one through: see [PlayQueue]. */
    val from: String? = null
)

/** One of a music app's own buttons, as its session shows it: what it does, and what it's called and drawn with, an [icon] of the app's, there. */
internal data class AppAction(val action: String, val name: String, val icon: Int)

/**
 * A song waiting to play: [id] is the app's number for it in its queue, to play it now; none for one
 * Crosstune asks for later. [art] is a link to its cover, when known.
 */
internal data class Queued(val title: String, val artist: String, val id: Long? = null, val art: String? = null)

/**
 * A song an app plays, and its place in the app's queue, by the number the app gave it there; null
 * for an app that shows no queue, as YouTube doesn't. YouTube Music numbers what it's asked to play
 * one after another, then what it plays on after those; TIDAL numbers each song by what it is.
 * [ended] once the app stopped at the song's end, as YouTube does after a list's last song.
 * [previousId] is the number of the song before it in the queue.
 */
internal data class AppPlays(val song: MusicMetadata, val queueId: Long?, val ended: Boolean = false, val previousId: Long? = null)

/** What's playing on the phone, as far as Android lets Crosstune see. */
internal interface PlaybackSource {
    /** Whether Android lets Crosstune see what plays, which the user allows in its settings. */
    fun hasAccess(): Boolean

    /** Where [song] is in the app playing it, as that changes; null while no app plays it. */
    fun follow(song: MusicMetadata): Flow<Following?>

    /** Moves the app playing [song] to [positionMs]. */
    fun seekTo(song: MusicMetadata, positionMs: Long)

    /** Has the app playing [song] [play] it, or pause it. */
    fun playPause(song: MusicMetadata, play: Boolean)

    /**
     * Has the app [song] is paused in play it on, from where it is, in the background. False when it
     * says it can't or hasn't started within a couple of seconds, as YouTube Music without Premium,
     * then known to [playsOnlyInFront], or already known to; or when no app has it.
     */
    suspend fun resume(song: MusicMetadata): Boolean

    /** Brings the app [song] is paused in to the front and has it play on there, from where it is; false when it can't be. */
    suspend fun bringToFront(song: MusicMetadata): Boolean

    /** Has the app playing [song] go on to its [next] song, or back to the one before, and play it. */
    fun skip(song: MusicMetadata, next: Boolean)

    /** Has the app playing [song] shuffle, or play in order. */
    fun shuffle(song: MusicMetadata, on: Boolean)

    /** Has the app playing [song] repeat [how], or, one with a repeat button of its own, the next way it goes on to there. */
    fun repeat(song: MusicMetadata, how: Repeat)

    /** Presses the app's own like for [song], which likes it there, or no longer, as the app has it. */
    fun like(song: MusicMetadata)

    /** Has the app playing [song] play the one numbered [id] in its queue now. */
    fun playQueued(song: MusicMetadata, id: Long)

    /**
     * Has [app] find [song] by its name and play it, in the background, or YouTube Music play its
     * [link] there when it's known. Not running, [app] is woken in the background to; with no [link],
     * the music app that last played is asked when [app] can't be. False with no app to ask, and, when
     * [mustStart], when the app says it can't play it or hasn't started within a couple of seconds, as
     * YouTube Music in the background without Premium: the caller then opens the song in the app.
     */
    suspend fun playSong(song: MusicMetadata, app: String?, link: String? = null, mustStart: Boolean = false): Boolean

    /**
     * Opens [app] on a song's [link], for when it isn't running and so has nothing to ask in the
     * background, or won't play there; false when it can't be. It comes to the front, so Crosstune
     * [comesBack] once it plays, unless it won't play in the background.
     */
    fun openSong(app: String, link: String): Boolean

    /** Brings Crosstune back to the front, over the app [openSong] opened, unless it only plays in front. */
    fun comesBack()

    /** Whether [app] was seen to play only in front, as YouTube Music without Premium, so [comesBack] leaves it there. */
    fun playsOnlyInFront(app: String): Boolean

    /** The song an app on the phone is playing right now, and where it is; null when none is, or none can be seen. */
    fun nowPlaying(): Heard.Song?

    /** The matching recording, including one paused in its music app. */
    fun recording(song: MusicMetadata): MusicMetadata?

    /** The song playing now, updated when music apps start, pause, stop, or change songs. */
    @MainThread
    fun nowPlayingUpdates(): Flow<Heard.Song?>

    /** What [app] plays, playing or paused, and where in its queue, as that changes; null while it plays nothing. */
    @MainThread
    fun plays(app: String): Flow<AppPlays?>

    /** Has [app] play the list of songs at [link], from its first, in the background where it can; false with no app to ask, or, if [mustStart], see [playSong]. */
    suspend fun playList(app: String, link: String, mustStart: Boolean = false): Boolean

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
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val waker: AppWaker = MediaBrowserWaker(context),
    /** How long an app asked to play has to start. */
    private val startMs: Long = START_MS,
    private val modes: AppModes = SessionModes(context),
    /** An app's queue; Robolectric shows a test none. */
    private val queueOf: (MediaController) -> List<MediaSession.QueueItem>? = MediaController::getQueue,
    private val lengths: SteadyLengths = SteadyLengths(context.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)),
    private val lags: ClockLags = ClockLags(context.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE))
) : PlaybackSource {
    private val listener = ComponentName(context, NowPlayingListener::class.java)
    private val sessions: MediaSessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val audio: AudioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    /** How many times Crosstune has moved a song, so a move a moment back isn't taken for a stale clock. */
    private var moves = 0
    /** The apps that wouldn't play in the background when asked, as YouTube Music without Premium; see [comesBack]. */
    private val onlyInFront: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** The app [openSong] last opened. */
    @Volatile
    private var opened: String? = null

    override fun hasAccess(): Boolean = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    override fun restricted(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { context.packageManager.getInstallSourceInfo(context.packageName).packageSource }.getOrNull() in fromAFile

    override fun follow(song: MusicMetadata): Flow<Following?> = callbackFlow {
        var followed: MediaController? = null
        var modesWatched: () -> Unit = {}
        var watched: List<MediaController> = emptyList()
        fun clock(app: String?, lagMs: Long) = SteadyClock(lagMs, now) { behind -> app?.let { lags.learn(it, behind) } }
        var clocks = clock(null, 0)
        var movesSeen = moves
        // What it plays next only for the one followed, as reading an app's queue takes a while.
        fun steadily(controller: MediaController) = following(controller)?.let {
            // Moved by Crosstune, where the app says next is believed, even a moment back.
            if (moves != movesSeen) clocks = clock(controller.packageName, 0).also { movesSeen = moves }
            it.copy(clock = clocks.of(it.clock), upNext = upNext(controller))
        }
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                trySend(followed?.let(::steadily))
            }

            // What it plays next.
            override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) {
                trySend(followed?.let(::steadily))
            }
        }
        fun pick(controllers: List<MediaController>) {
            val playing = appFor(song, controllers)
            if (playing?.sessionToken != followed?.sessionToken) {
                followed?.unregisterCallback(callback)
                modesWatched()
                followed = playing
                clocks = playing?.packageName.let { clock(it, it?.let(lags::of) ?: 0) }
                playing?.registerCallback(callback, handler)
                // An app's shuffle and repeat aren't in what Android says of how it plays, so they're watched apart.
                modesWatched = playing?.let { app -> modes.watch(app, handler) { trySend(steadily(app)) } } ?: {}
            }
            trySend(playing?.let(::steadily))
        }
        // An app moving on to another song, e.g. YouTube from an ad to the video, may now play it, or no longer.
        val renamed = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) = pick(watched)
        }
        fun watch(controllers: List<MediaController>) {
            watched.forEach { it.unregisterCallback(renamed) }
            watched = controllers
            controllers.forEach { it.registerCallback(renamed, handler) }
            pick(controllers)
        }
        val changed = MediaSessionManager.OnActiveSessionsChangedListener { watch(it.orEmpty()) }
        // Without the user's say-so, e.g. taken back meanwhile, Android shows none, and says so by throwing.
        val watching = runCatching {
            sessions.addOnActiveSessionsChangedListener(changed, listener, handler)
            watch(sessions.getActiveSessions(listener))
        }.isSuccess
        if (!watching) trySend(null)
        awaitClose {
            sessions.removeOnActiveSessionsChangedListener(changed)
            watched.forEach { it.unregisterCallback(renamed) }
            followed?.unregisterCallback(callback)
            modesWatched()
        }
    }.distinctUntilChanged()

    /**
     * The app playing [song], among all that have it: one playing it beats one paused on it. It's the
     * one followed, so it's the one moved, played, paused and skipped too.
     */
    @VisibleForTesting
    internal fun appFor(song: MusicMetadata, controllers: List<MediaController> = sessions.getActiveSessions(listener)): MediaController? =
        controllers.filter { it.plays(song) }.maxByOrNull { if (it.playbackState?.state == PlaybackState.STATE_PLAYING) 1 else 0 }

    override fun seekTo(song: MusicMetadata, positionMs: Long) {
        moves++
        // Taken back meanwhile, the words just stay where they are.
        runCatching { appFor(song)?.transportControls?.seekTo(positionMs) }
    }

    override fun playPause(song: MusicMetadata, play: Boolean) {
        // Taken back meanwhile, the song just keeps on as it was.
        runCatching { appFor(song)?.transportControls?.run { if (play) play() else pause() } }
    }

    override suspend fun resume(song: MusicMetadata): Boolean {
        val app = runCatching { appFor(song) }.getOrNull() ?: return false
        return watchStart(app, mustStart = true) { app.transportControls.play() }
    }

    // Opened as from its icon, which brings back the screen it was on, song and all; a link would start it over.
    override suspend fun bringToFront(song: MusicMetadata): Boolean {
        val app = runCatching { appFor(song) }.getOrNull() ?: return false
        val opens = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return false
        val back = CompletableDeferred<Unit>()
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (state?.state == PlaybackState.STATE_PAUSED || state?.state == PlaybackState.STATE_PLAYING) back.complete(Unit)
            }
        }
        app.registerCallback(callback, handler)
        try {
            if (runCatching { context.startActivity(opens.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure) return false
            opened = app.packageName
            // Back in front, YouTube Music puts the song where it was, paused; asked to play before that, it starts it over.
            withTimeoutOrNull(startMs) { back.await() }
            runCatching { app.transportControls.play() }
            return true
        } finally {
            app.unregisterCallback(callback)
        }
    }

    override fun skip(song: MusicMetadata, next: Boolean) = moveOn(song) { if (next) skipToNext() else skipToPrevious() }

    override fun playQueued(song: MusicMetadata, id: Long) = moveOn(song) { skipToQueueItem(id) }

    /** Has the app playing [song] go on to another, as [move] asks it, and play it. */
    private fun moveOn(song: MusicMetadata, move: MediaController.TransportControls.() -> Unit) {
        // Taken back meanwhile, the song just keeps on as it was.
        runCatching {
            val app = appFor(song) ?: return
            val paused = app.playbackState?.state != PlaybackState.STATE_PLAYING
            app.transportControls.run {
                move()
                // Paused, the song moved on to would wait unheard, with the words still on the last one.
                if (paused) play()
            }
        }
    }

    override fun shuffle(song: MusicMetadata, on: Boolean) {
        // Taken back meanwhile, it plays on as it did.
        runCatching { appFor(song)?.let { modes.shuffle(it, on) } }
    }

    override fun repeat(song: MusicMetadata, how: Repeat) {
        // Taken back meanwhile, it plays on as it did.
        runCatching {
            val app = appFor(song) ?: return
            // One with a button of its own for it goes on to its next way as when it's tapped there: YouTube Music
            // only repeats a mix's song, never the endless mix, and won't be told to.
            val own = app.playbackState?.customActions?.firstOrNull { it.action in REPEATS }
            if (own != null) app.transportControls.sendCustomAction(own, Bundle()) else modes.repeat(app, how)
        }
    }

    override fun like(song: MusicMetadata) {
        // Taken back meanwhile, nothing's liked.
        runCatching {
            val app = appFor(song) ?: return
            val like = app.playbackState?.customActions?.firstOrNull { it.action in LIKES } ?: return
            app.transportControls.sendCustomAction(like, Bundle())
        }
    }

    override suspend fun playSong(song: MusicMetadata, app: String?, link: String?, mustStart: Boolean): Boolean {
        // Without the user's say-so Android shows none, and says so by throwing; an app can still be woken.
        val apps = runCatching { sessions.getActiveSessions(listener) }.getOrDefault(emptyList()).filter { it.packageName != context.packageName }
        // Not running, the app's woken, as Android Auto wakes one; another app wouldn't have its link.
        val asked = apps.firstOrNull { sameApp(app, it.packageName, context::hasApp) }
            ?: app?.let { waker.wake(MusicService.appsFor(it)) }
            ?: apps.takeIf { link == null }?.maxByOrNull { it.playbackState?.lastPositionUpdateTime ?: 0L }
            ?: return false
        return watchStart(asked, mustStart) { ask(asked, song, link) }
    }

    /**
     * Runs [ask]; false if it throws. If [mustStart], also waits for [asked] to say it plays: an app that
     * can't play in the background says so with an error, or says nothing, and plays nothing either way.
     * One already playing, e.g. another song, is taken to go on with this one. One known to play only in front isn't asked unless it plays, in front.
     */
    private suspend fun watchStart(asked: MediaController, mustStart: Boolean, ask: () -> Unit): Boolean {
        // Known to play only in front, it isn't asked from behind again: YouTube Music, stopped, opens itself on the song from the start.
        if (mustStart && asked.packageName in onlyInFront && asked.playbackState?.state != PlaybackState.STATE_PLAYING) return false
        val verdict = CompletableDeferred<Boolean>()
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                when (state?.state) {
                    PlaybackState.STATE_PLAYING -> verdict.complete(true)
                    PlaybackState.STATE_ERROR -> verdict.complete(false)
                }
            }
        }
        // Listening before it's asked, so an answer that comes at once isn't missed.
        if (mustStart) asked.registerCallback(callback, handler)
        try {
            if (runCatching(ask).isFailure) return false
            if (!mustStart || asked.playbackState?.state == PlaybackState.STATE_PLAYING) return true
            return (withTimeoutOrNull(startMs) { verdict.await() } == true).also { if (!it) onlyInFront += asked.packageName }
        } finally {
            if (mustStart) asked.unregisterCallback(callback)
        }
    }

    // Asked the way a voice assistant asks, which music apps answer without coming to the front.
    private fun ask(asked: MediaController, song: MusicMetadata, link: String?) {
        val wanted = Bundle().apply {
            putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
            putString(MediaStore.EXTRA_MEDIA_TITLE, song.title)
            putString(MediaStore.EXTRA_MEDIA_ARTIST, song.artist)
        }
        // YouTube Music says it plays what's asked for by name, but doesn't; a link of its own it plays.
        if (link != null && MusicService.ofPackage(asked.packageName) == MusicService.YOUTUBE_MUSIC) asked.transportControls.playFromUri(Uri.parse(link), Bundle())
        else asked.transportControls.playFromSearch("${song.title} ${song.artist}", wanted)
    }

    // The app may be installed as a build patched from it, under a name of its own, or as its TV app.
    override fun openSong(app: String, link: String): Boolean = MusicService.appLinksIn(app, link).any { (packageName, its) ->
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(its)).setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            opened = packageName
            true
        }.getOrDefault(false)
    }

    override fun playsOnlyInFront(app: String) = onlyInFront.any { sameApp(app, it, context::hasApp) }

    // From the background, Android only lets it if Crosstune may show over other apps; otherwise it stays behind.
    // Behind it too when the app opened wouldn't play in the background, as it would stop as soon as it's left.
    override fun comesBack() {
        if (opened in onlyInFront) return
        runCatching { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
    }

    // Without the user's say-so Android shows none, and says so by throwing.
    override fun nowPlaying(): Heard.Song? {
        if (!hasAccess()) return null
        // An app may still say it plays long after it stopped, e.g. a browser tab left behind, so one
        // playing on the phone is only believed while the phone is playing something. Cast to a TV,
        // the phone itself stays quiet.
        val sounding = audio.isMusicActive
        return runCatching { sessions.getActiveSessions(listener) }.getOrNull().orEmpty()
            .filter { it.packageName != context.packageName && it.playbackState?.state == PlaybackState.STATE_PLAYING && !it.leftBehind() && !it.mirrored() && (sounding || it.cast()) }
            // What the phone itself plays over what it casts, then the one that last said where it is, over one that said so long ago.
            .sortedWith(compareBy<MediaController> { it.cast() }.thenByDescending { it.playbackState?.lastPositionUpdateTime ?: 0L })
            .firstNotNullOfOrNull(::playing)
    }

    override fun recording(song: MusicMetadata): MusicMetadata? {
        if (!hasAccess()) return null
        return runCatching { sessions.getActiveSessions(listener) }.getOrNull().orEmpty()
            .filter { it.packageName != context.packageName && it.plays(song) && !it.mirrored() }
            .sortedByDescending { it.playbackState?.lastPositionUpdateTime ?: 0L }
            // plays() already checked the session's title and artist. YouTube's video-title
            // parser can mistake a bilingual title's first half for an artist.
            .firstNotNullOfOrNull { playing(it)?.metadata?.copy(title = song.title, artist = song.artist) }
    }

    override fun nowPlayingUpdates(): Flow<Heard.Song?> = changes(::nowPlaying)

    override fun plays(app: String): Flow<AppPlays?> = changes {
        runCatching { sessions.getActiveSessions(listener) }.getOrNull().orEmpty()
            .filter { sameApp(app, it.packageName, context::hasApp) }
            .firstNotNullOfOrNull { controller ->
                val ended = controller.ended()
                controller.takeIf { ended || (it.playbackState?.state ?: PlaybackState.STATE_NONE) !in NOT_PLAYING }
                    // A video that doesn't say it's music is still what the app was asked to play: YouTube
                    // names "Thriller" by "Michael Jackson" with no album, as it does any video.
                    ?.let { playing(it)?.metadata ?: it.named() }
                    ?.let { song ->
                        val id = controller.playbackState?.activeQueueItemId?.takeIf { it != MediaSession.QueueItem.UNKNOWN_ID.toLong() }
                        val queue = queueOf(controller).orEmpty()
                        val previous = queue.getOrNull(queue.indexOfFirst { it.queueId == id } - 1)?.queueId
                        AppPlays(song, id, ended, previous)
                    }
            }
    }

    /** How long the app says what it plays is, as it said before for the song; see [SteadyLengths]. */
    private fun lengthOf(metadata: MediaMetadata): Long? = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 }?.let {
        lengths.of(metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(), metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(), it)
    }

    /** What plays, as the app names it. */
    private fun MediaController.named(): MusicMetadata? =
        metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?.let { MusicMetadata(it, metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()) }

    /** Stopped or paused at its song's end, where it said it is. */
    private fun MediaController.ended(): Boolean {
        val state = playbackState ?: return false
        val duration = metadata?.let(::lengthOf) ?: return false
        return state.state in STILL && state.position >= duration - ENDED_WITHIN_MS
    }

    override suspend fun playList(app: String, link: String, mustStart: Boolean): Boolean {
        val asked = runCatching { sessions.getActiveSessions(listener) }.getOrDefault(emptyList()).firstOrNull { sameApp(app, it.packageName, context::hasApp) }
            ?: waker.wake(MusicService.appsFor(app))
            ?: return false
        return watchStart(asked, mustStart) { asked.transportControls.playFromUri(Uri.parse(link), Bundle()) }
    }

    /** What [read] says, again whenever an app starts, pauses, stops, changes songs or its queue. */
    private fun <T> changes(read: () -> T): Flow<T> = callbackFlow {
        var watched: List<MediaController> = emptyList()
        fun update() { trySend(read()) }
        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) = update()
            override fun onPlaybackStateChanged(state: PlaybackState?) = update()
            override fun onSessionDestroyed() = update()
            override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) = update()
        }
        fun watch(controllers: List<MediaController>) {
            watched.forEach { it.unregisterCallback(callback) }
            watched = controllers
            watched.forEach { it.registerCallback(callback, handler) }
            update()
        }
        val changed = MediaSessionManager.OnActiveSessionsChangedListener { watch(it.orEmpty()) }
        // Audio can fall silent before a player updates its session, or start after it says PLAYING.
        val sounding = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = update()
        }
        val watching = runCatching {
            sessions.addOnActiveSessionsChangedListener(changed, listener, handler)
            audio.registerAudioPlaybackCallback(sounding, handler)
            watch(sessions.getActiveSessions(listener))
        }.isSuccess
        if (!watching) update()
        awaitClose {
            sessions.removeOnActiveSessionsChangedListener(changed)
            audio.unregisterAudioPlaybackCallback(sounding)
            watched.forEach { it.unregisterCallback(callback) }
        }
    }.distinctUntilChanged()

    private fun playing(controller: MediaController): Heard.Song? {
        val metadata = controller.metadata ?: return null
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        // Without both there's no song to look up by name. A browser playing a bare file names it
        // by its address, under its site, e.g. "example.com/song.mp3" by "example.com": no song either.
        if (title.isBlank() || artist.isBlank() || ('.' in artist && title.startsWith("$artist/"))) return null
        // With no album outside a music app it's most likely a video, "Artist - Song (Official Video)" by "ArtistVEVO",
        // named as a song the way a YouTube link is; a video that doesn't say it's music is no song.
        // YouTube Music plays videos with no album too, all of them music, "Queen - Bohemian Rhapsody (Lyrics)" by "7clouds".
        // A music app's "Song - Remastered" stays whole.
        // A song's length tells its versions apart; a video's is the video's, which may open with more.
        val named = when {
            !controller.isVideo() -> MusicMetadata(title, artist, lengthMs = lengthOf(metadata))
            MusicService.ofPackage(controller.packageName) == MusicService.YOUTUBE_MUSIC -> MetadataParsers.youtubeVideo(title, artist) ?: return null
            else -> MetadataParsers.musicVideo(title, artist) ?: return null
        }
        val artwork = listOf(MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            .mapNotNull(metadata::getString).firstOrNull { it.startsWith("https://") } ?: ownCover(metadata, named)
        // Where a video is isn't where its song is, as it may open with more, so it isn't given.
        val clock = following(controller)?.takeIf { !it.video }?.clock
        val id = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        val uri = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
        val duration = lengthOf(metadata)
        return Heard.Song(named.copy(
            artworkUrl = artwork,
            url = uri ?: id?.takeIf { MusicLinks.parse(it) is LinkInput.Link },
            recordingId = RecordingKey.playing(controller.packageName, id, duration)
        ), appleMusicId = null, offsetMs = clock?.positionMs, startedAtMs = clock?.atMs, app = controller.packageName)
    }

    /**
     * The cover the app shows, when it gives no link to one, e.g. YouTube Music for a song no other
     * store has: kept as a file with the covers downloaded, once a song, so it shows like those.
     */
    private fun ownCover(metadata: MediaMetadata, song: MusicMetadata): String? {
        val file = File(context.cacheDir, "$ARTWORK_DIR/playing-${UUID.nameUUIDFromBytes("${song.title}\n${song.artist}".toByteArray())}.jpg")
        if (!file.isFile) {
            val cover = COVERS.firstNotNullOfOrNull(metadata::getBitmap) ?: return null
            // Written aside and moved in once whole, so one cut short isn't the song's cover from then on.
            val part = File(file.path + ".part")
            val saved = runCatching {
                file.parentFile?.mkdirs()
                part.outputStream().use { cover.compress(Bitmap.CompressFormat.JPEG, COVER_QUALITY, it) } && part.renameTo(file)
            }.getOrDefault(false)
            if (!saved) {
                part.delete()
                return null
            }
        }
        return file.toURI().toString()
    }

    private fun following(controller: MediaController): Following? {
        // Stopped, or failing, e.g. a video that won't play in the background, it plays nothing to follow.
        val state = controller.playbackState?.takeIf { it.state !in NOT_PLAYING } ?: return null
        // An app that doesn't say where it is gives nothing to keep the words in time with.
        if (state.position < 0) return null
        val app = runCatching {
            context.packageManager.run { getApplicationLabel(getApplicationInfo(controller.packageName, 0)).toString() }
        }.getOrNull()
        // Its position is from when it last said so; since then it moved on at its own speed.
        val at = state.lastPositionUpdateTime.takeIf { it > 0 } ?: now()
        val clock = PlaybackClock(state.position, at, state.playbackSpeed.takeIf { it > 0f } ?: 1f, state.state == PlaybackState.STATE_PLAYING)
        return Following(
            clock, app, state.actions and PlaybackState.ACTION_SEEK_TO != 0L, controller.packageName, controller.isVideo(),
            canSkipNext = state.actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
            canSkipPrevious = state.actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            durationMs = controller.metadata?.let(::lengthOf),
            shuffle = modes.shuffles(controller),
            repeat = modes.repeats(controller),
            like = state.customActions.firstOrNull { it.action in LIKES }?.let { AppAction(it.action, it.name.toString(), it.icon) }
        )
    }

    /** The songs after the one [controller] plays, in its queue; none when it doesn't say where in it it is. */
    private fun upNext(controller: MediaController): List<Queued> {
        val queue = queueOf(controller).orEmpty()
        val at = queue.indexOfFirst { it.queueId == controller.playbackState?.activeQueueItemId }
        if (at < 0) return emptyList()
        return queue.drop(at + 1).map {
            val art = it.description.iconUri?.toString()?.replace(SIZED_COVER, "=w$QUEUED_COVER_PX-h$QUEUED_COVER_PX")
            Queued(it.description.title?.toString().orEmpty(), it.description.subtitle?.toString().orEmpty(), it.queueId, art)
        }
    }

    /** Music apps also play singles and local files with no album tag; YouTube Music leaves it out only for videos. */
    private fun MediaController.isVideo(): Boolean =
        metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).isNullOrBlank() &&
            MusicService.ofPackage(packageName).let { it == null || it == MusicService.YOUTUBE || it == MusicService.YOUTUBE_MUSIC }

    /**
     * A session left behind, e.g. a browser tab that once played: it says it plays, but not where,
     * and hasn't said anything for long.
     */
    private fun MediaController.leftBehind(): Boolean {
        val state = playbackState ?: return false
        return state.position < 0 && now() - state.lastPositionUpdateTime > LEFT_BEHIND_MS
    }

    /** Playing on another device, e.g. a TV a music app casts to, or a computer KDE Connect shows here. */
    private fun MediaController.remote(): Boolean {
        // Up to Android 14, a session gone meanwhile answers null, though the newest SDK says it never does.
        val info: MediaController.PlaybackInfo? = playbackInfo
        return info?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE
    }

    /** A music app's own song, cast to a TV: still the phone's, played and paused from it. */
    private fun MediaController.cast(): Boolean = remote() && MusicService.ofPackage(packageName) != null

    /** Only shown here from another device, e.g. a computer's video through KDE Connect: what the phone plays is another one. */
    private fun MediaController.mirrored(): Boolean = remote() && !cast()

    /** Whether what this app has [playing] is known to be another recording than [song], whatever it's named. */
    private fun MediaController.anotherRecording(playing: MediaMetadata, song: MusicMetadata): Boolean {
        val length = lengthOf(playing)
        val recording = RecordingKey.playing(packageName, playing.getString(MediaMetadata.METADATA_KEY_MEDIA_ID), length)
        if (song.recordingId?.startsWith("$packageName:") == true && recording != null && song.recordingId != recording) return true
        // Far from as long as the song, it's another version of it, e.g. the album's take of a single; a video may open with more.
        return !isVideo() && length != null && song.lengthMs?.let { abs(it - length) > SAME_RECORDING_LENGTH_MS } == true
    }

    /** Whether it's [song] this app plays: a video's title names more, e.g. "Artist - Song (Official Video)". */
    private fun MediaController.plays(song: MusicMetadata): Boolean {
        val playing = metadata ?: return false
        if (anotherRecording(playing, song)) return false
        val title = playing.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = playing.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        if (title.isBlank()) return false
        // A video's title, read as the song it names the way the song playing is named, e.g. "YOASOBI「アイドル」 Official Music Video".
        val named = MetadataParsers.youtubeVideo(title, artist)?.title
        if (!(SongNames.same(title, song.title) || SongNames.wordsMatch(title, song.title) || named?.let { SongNames.same(it, song.title) } == true)) return false
        // A video's channel may be named "Artist - Topic", or not be the artist at all, when the title names them.
        return artist.isBlank() || SongNames.artistInside(artist, song.artist) || SongNames.sameArtist(song.artist, artist) ||
            SongNames.artistNames(song.artist).map(SongNames::words).any { it.isNotEmpty() && SongNames.words(title).containsAll(it) }
    }
}

/** Installed from a file rather than by an app store, which Android 13 and later restrict settings for. */
private val fromAFile = setOf(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE, PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)

/** How long an app asked to play in the background has to say it does, before it's taken as not playing. */
internal const val START_MS = 3_000L

/** How long a session that doesn't say where it is can stay quiet before it's taken as left behind. */
internal const val LEFT_BEHIND_MS = 10 * 60_000L

/**
 * The size in a cover's link, as YouTube Music's say how big to draw it: the 60 pixels it gives other
 * apps blur at a row's size, so a bigger one of the same cover is asked for.
 */
private val SIZED_COVER = Regex("=w\\d+-h\\d+")
private const val QUEUED_COVER_PX = 180

/** How far behind where it was playing on to an app may say it is, and still be believed to be there instead. */
internal const val STALE_CLOCK_MS = 2_000L

/** A clock said this little behind is just how long saying it took, as YouTube Music says a song starts twice within a tenth or so. */
internal const val SAYING_MS = 300L

/** Where an app puts the cover it shows, the song's own before its album's. */
private val COVERS = listOf(MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)

private const val COVER_QUALITY = 90

/**
 * A music app's own button to like a song, or keep it, by what the app's session calls it, the same
 * whether it's liked or not; what it's called and drawn with there says which.
 */
private val LIKES = setOf(
    "thumbs_up_action", // YouTube Music
    "com.aspiro.tidal.player.action.TOGGLE_FAVORITE", // TIDAL
    "waze.thumbUp" // Amazon Music
)

/** A music app's own button to repeat, which goes round its ways as it does there, by what the app's session calls it. */
private val REPEATS = setOf("loop_mode_action") // YouTube Music

/** States in which an app plays nothing to follow. */
private val NOT_PLAYING = setOf(PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR)

/** States an app rests in, once its song ends with nothing after it. */
private val STILL = setOf(PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED)

/** How short of its song's length an app may stop, and still have played it to its end. YouTube stops a little past it. */
private const val ENDED_WITHIN_MS = 2_000L

/**
 * Only here so Android shows Crosstune what music apps are playing, which it does for an app the
 * user lets read notifications. The notifications themselves are never looked at.
 */
class NowPlayingListener : NotificationListenerService() {
    // Android starts Crosstune again for this after stopping it, so a playlist it played through carries on.
    override fun onListenerConnected() = MainActivity.playQueue(applicationContext).resume()
}
