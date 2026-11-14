package com.astrovm.crosstune

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageInstaller
import android.graphics.Bitmap
import android.media.AudioDeviceInfo
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
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Where in a song it is: [positionMs] at [atMs] on the clock that counts since the phone started,
 * moving on at [speed] while [playing].
 */
internal data class PlaybackClock(val positionMs: Long, val atMs: Long, val speed: Float = 1f, val playing: Boolean = true) {
    fun positionAt(nowMs: Long): Long = if (playing) positionMs + ((nowMs - atMs) * speed).toLong() else positionMs

    /**
     * How long from [nowMs] until the song is where something next changes: [changesAt] gives that
     * place, after the one it's handed, or null when nothing changes again. Null too for a song that
     * isn't moving on. So what follows the song sleeps to its next change, where looking every frame
     * kept the phone busy the whole song, with nothing new to draw.
     */
    fun untilMs(nowMs: Long, changesAt: (Long) -> Long?): Long? {
        if (!playing || speed <= 0f) return null
        val position = positionAt(nowMs)
        val next = changesAt(position) ?: return null
        return ((next - position) / speed).toLong().coerceAtLeast(1)
    }
}

/** The clock the timing log last had from an app, so what the app says again to the same effect isn't kept over what's new. */
internal class NotedClock {
    @Volatile
    var clock: PlaybackClock? = null
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
    /** The app's clock that's late, if Crosstune saw it fall behind; see [LateClock]. */
    private val late: LateClock?,
    /** What the timing log last had of the app's clock, shared by every follower of it, as each one hears the same. */
    private val noted: NotedClock = NotedClock(),
    /** Told of a clock the app fell behind on. */
    private val learn: (LateClock) -> Unit
) {
    private var last: PlaybackClock? = null
    /** Where the app said it paused, when that was a moment behind; and where it had really got to. */
    private var said: Long? = null
    private var stopped = 0L

    fun of(clock: PlaybackClock): PlaybackClock = steady(clock).also {
        if (isNews(clock)) {
            noted.clock = clock
            TimingLog.note("said ${clock.positionMs} at ${clock.atMs} playing ${clock.playing} speed ${clock.speed}, kept ${it.positionMs} at ${it.atMs}")
        }
    }

    /** Whether [clock] says something the last one noted didn't: it plays or stops, changes speed, or is somewhere else than that one was going. */
    private fun isNews(clock: PlaybackClock): Boolean {
        val was = noted.clock ?: return true
        return was.playing != clock.playing || was.speed != clock.speed || abs(was.positionAt(clock.atMs) - clock.positionMs) > SAME_CLOCK_NOTE_MS
    }

    private fun steady(clock: PlaybackClock): PlaybackClock {
        // Picked up on a clock the app fell behind on, it's as far behind as it fell.
        val was = last ?: return (late?.ahead(clock) ?: clock).also { last = it }
        val behind = was.takeIf { it.playing }?.let { it.positionAt(clock.atMs) - clock.positionMs }
        val stale = behind != null && behind in 1..STALE_CLOCK_MS
        if (stale && clock.playing && clock.speed == was.speed) return was.also { fellBehind(clock, behind) }
        return (resumed(was, clock) ?: stoppedAt(clock, behind, stale)).also { last = it }
    }

    /** Played again from a moment behind where it stopped, as YouTube Music says it is, it plays on from where it stopped. */
    private fun resumed(was: PlaybackClock, clock: PlaybackClock): PlaybackClock? =
        clock.takeIf { !was.playing && it.playing && was.positionMs - it.positionMs in 1..STALE_CLOCK_MS }?.copy(positionMs = was.positionMs)

    private fun fellBehind(clock: PlaybackClock, behind: Long) {
        if (behind >= SAYING_MS) learn(LateClock(clock, behind))
    }

    /** [clock], or where it really stopped when it says it paused a moment behind that, till it says it's elsewhere. */
    private fun stoppedAt(clock: PlaybackClock, behind: Long?, stale: Boolean): PlaybackClock {
        if (behind != null && !clock.playing) {
            said = clock.positionMs.takeIf { stale }
            stopped = clock.positionMs + behind
        } else if (clock.positionMs != said) {
            said = null
        }
        return if (said != null) clock.copy(positionMs = stopped) else clock
    }
}

/**
 * A clock an app fell [lagMs] behind on. A song YouTube Music starts from a link, it says is at the
 * start, which is right, then says so again most of a second later: 0.7 to 1 s behind, a little more
 * or less each time. All it says after, even just now, goes on from that late clock, till it plays
 * the song from elsewhere, or another song. A screen that starts following then would be that late.
 */
internal data class LateClock(val clock: PlaybackClock, val lagMs: Long) {
    /** [other] moved on by [lagMs] of the song when it goes on from this clock; null when it doesn't. */
    fun ahead(other: PlaybackClock): PlaybackClock? = other.takeIf {
        it.playing && it.speed == clock.speed && abs(clock.positionAt(it.atMs) - it.positionMs) <= SAME_CLOCK_MS
    }?.let { it.copy(positionMs = it.positionMs + lagMs) }
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
    val from: String? = null,
    /** It plays an ad under the song's name, as YouTube Music's free plan does, and the song waits at its start: see [PlaybackClock]. */
    val ad: Boolean = false
)

/**
 * One of a music app's own buttons, as its session shows it: what it does, and what it's called and drawn
 * with, an [icon] of the app's, there. For a like, [liked] says whether the song's liked there already;
 * null when that can't be told, so it's never pressed blind.
 */
internal data class AppAction(val action: String, val name: String, val icon: Int, val liked: Boolean? = null)

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

    /**
     * Where [song] is in the app playing it, as that changes; null while no app plays it. Only [app], by package, when
     * one is named. Asked of [app], what plays with no song named is [song] by just what it calls it, as
     * [Unnamed.played], even nothing; see [playingUnnamed]. So is it for each control asked of [app] below.
     */
    fun follow(song: MusicMetadata, app: String? = null): Flow<Following?>

    /** Moves the app playing [song], [app] when named, to [positionMs]. */
    fun seekTo(song: MusicMetadata, positionMs: Long, app: String? = null)

    /** Has the app playing [song], [app] when named, [play] it, or pause it. */
    fun playPause(song: MusicMetadata, play: Boolean, app: String? = null)

    /** Pauses whatever [app], by package, plays: a song asked to play there may have gone on into another, which isn't left playing. */
    fun pauseApp(app: String)

    /** Has [app], by package, play whatever it has: what names no song can't be asked for by a name, once its app is on to another. */
    fun playApp(app: String)

    /**
     * Has the app [song] is paused in play it on, from where it is, in the background. False when it
     * says it can't or hasn't started within a couple of seconds, as YouTube Music without Premium,
     * then known to [playsOnlyInFront], or already known to; or when no app has it.
     */
    suspend fun resume(song: MusicMetadata, app: String? = null): Boolean

    /** Brings the app [song] is paused in to the front and has it play on there, from where it is; false when it can't be. */
    suspend fun bringToFront(song: MusicMetadata, app: String? = null): Boolean

    /** Has the app playing [song], [app] when named, go on to its [next] song, or back to the one before, and play it. */
    fun skip(song: MusicMetadata, next: Boolean, app: String? = null)

    /** Has the app playing [song], [app] when named, shuffle, or play in order. */
    fun shuffle(song: MusicMetadata, on: Boolean, app: String? = null)

    /** Has the app playing [song], [app] when named, repeat [how], or, one with a repeat button of its own, the next way it goes on to there. */
    fun repeat(song: MusicMetadata, how: Repeat, app: String? = null)

    /** Presses the own like of the app playing [song], [app] when named, which likes it there, or no longer, as the app has it. */
    fun like(song: MusicMetadata, app: String? = null)

    /** Has the app playing [song], [app] when named, play the one numbered [id] in its queue now. */
    fun playQueued(song: MusicMetadata, id: Long, app: String? = null)

    /**
     * Has [app] find [song] by its name and play it, in the background, or YouTube Music play its
     * [link] there when it's known. Not running, [app] is woken in the background to; with no [link],
     * the music app that last played is asked when [app] can't be, unless not [orLastPlayed], as a practice
     * card follows [app] alone. False with no app to ask, and, when [mustStart], when the app says it can't
     * play it or hasn't started within a couple of seconds, as YouTube Music in the background without
     * Premium: the caller then opens the song in the app.
     */
    suspend fun playSong(song: MusicMetadata, app: String?, link: String? = null, mustStart: Boolean = false, orLastPlayed: Boolean = true): Boolean

    /**
     * Opens [app] on a song's [link], for when it isn't running and so has nothing to ask in the
     * background, or won't play there; false when it can't be. It comes to the front, so Crosstune
     * [comesBack] once it plays, unless it won't play in the background.
     */
    fun openSong(app: String, link: String): Boolean

    /** Brings Crosstune back to the front, over the app [openSong] opened, unless it only plays in front. */
    fun comesBack()

    /**
     * Whether Crosstune can [comesBack] by itself over an app [openSong] opened: Android lets it only once it may show
     * over other apps. Without that, the app stays in front, and Crosstune, behind it, can't open another either.
     */
    fun comesBackByItself(): Boolean

    /**
     * Mutes the phone's music, [on], while an app is on its way to a card's line, as it may play a moment of
     * something else before it's paused, or lets it be heard again. Music the user muted is left so.
     */
    fun quiet(on: Boolean)

    /** Whether the phone is in use, its screen on and unlocked, so an app [openSong] opens comes to the front now. */
    fun unlocked(): Boolean

    /** Whether the phone has no PIN, pattern or password, so its screen turned on is all it takes to use it. */
    fun lockless(): Boolean

    /** Turns the screen on, as a song opened with it off waits for it in Spotify; it goes off as it times out. */
    fun wakeScreen()

    /** Whether [app] was seen to play only in front, as YouTube Music without Premium, so [comesBack] leaves it there. */
    fun playsOnlyInFront(app: String): Boolean

    /** The song an app on the phone is playing right now, and where it is; null when none is, or none can be seen. */
    fun nowPlaying(): Heard.Song?

    /** The matching recording, including one paused in its music app. */
    fun recording(song: MusicMetadata): MusicMetadata?

    /** The song playing now, updated when music apps start, pause, stop, or change songs. */
    @MainThread
    fun nowPlayingUpdates(): Flow<Heard.Song?>

    /**
     * What an app on the phone itself plays while it names no song, e.g. a radio station, a file with no
     * tags or a video that doesn't say it's music, so it's named by listening; null when none plays, or
     * an app names a song, which [nowPlaying] has.
     */
    fun playingUnnamed(): Unnamed?

    /** What plays first, named or not, on the phone or not; see [WhatPlays]. Null when nothing plays, or none can be seen. */
    fun whatPlays(): WhatPlays?

    /** [playingUnnamed], updated when apps start, pause, stop, or change what they play. */
    @MainThread
    fun unnamedUpdates(): Flow<Unnamed?>

    /** Whether the phone's music plays out loud, where the microphone hears it: not muted, nor in headphones. */
    fun playsAloud(): Boolean

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
    /** What an app calls one of its drawings, by its number there; Robolectric has no other app's. */
    private val drawingName: (String, Int) -> String? = { app, id -> runCatching { context.packageManager.getResourcesForApplication(app).getResourceEntryName(id) }.getOrNull() }
) : PlaybackSource {
    private val listener = ComponentName(context, NowPlayingListener::class.java)
    private val sessions: MediaSessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val audio: AudioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    /** The clock each app, by package, last fell behind on. */
    private val late = ConcurrentHashMap<String, LateClock>()
    private val notedClocks = ConcurrentHashMap<String, NotedClock>()
    /** Replaced each time Crosstune moves a song, so a move a moment back isn't taken for a stale clock. */
    @Volatile
    private var moves = Any()
    /** The apps that wouldn't play in the background when asked, as YouTube Music without Premium; see [comesBack]. */
    private val onlyInFront: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** The app [openSong] last opened. */
    @Volatile
    private var opened: String? = null
    private val preferences = context.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)

    init {
        quiet(false)
    }

    override fun hasAccess(): Boolean = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    override fun restricted(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { context.packageManager.getInstallSourceInfo(context.packageName).packageSource }.getOrNull() in fromAFile

    override fun follow(song: MusicMetadata, app: String?): Flow<Following?> = callbackFlow {
        var followed: MediaController? = null
        var modesWatched: () -> Unit = {}
        var watched: List<MediaController> = emptyList()
        lateinit var clocks: SteadyClock
        var movesSeen = moves
        // What it plays next only for the one followed, as reading an app's queue takes a while.
        fun steadily(controller: MediaController) = following(controller, song.lengthMs)?.let {
            // Moved by Crosstune, where the app says next is believed, even a moment back.
            if (moves !== movesSeen) clocks = steadyClock(controller.packageName).also { movesSeen = moves }
            it.copy(clock = clocks.of(it.clock), upNext = upNext(controller))
        }
        val callback = object : MediaController.Callback() {
            // Gone on to another song, as TIDAL does at the end of a preview, it plays this one no longer, even before it's said its new name.
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                trySend(followed?.takeIf { it.has(song, app) }?.let(::steadily))
            }

            // What it plays next.
            override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) {
                trySend(followed?.let(::steadily))
            }
        }
        fun pick(controllers: List<MediaController>) {
            val playing = appFor(song, controllers, app)
            if (playing?.sessionToken != followed?.sessionToken) {
                followed?.unregisterCallback(callback)
                modesWatched()
                followed = playing
                clocks = steadyClock(playing?.packageName)
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

    /** [app]'s clock kept steady; see [SteadyClock]. With no app, there's nothing to keep. */
    private fun steadyClock(app: String?) = SteadyClock(app?.let(late::get), notedClocks.getOrPut(app.orEmpty(), ::NotedClock)) { if (app != null) late[app] = it }

    /**
     * The app playing [song], among all that have it: one playing it beats one paused on it. It's the
     * one followed, so it's the one moved, played, paused and skipped too. Only [app]'s, by package, when one is named.
     */
    @VisibleForTesting
    internal fun appFor(song: MusicMetadata, controllers: List<MediaController> = sessions.getActiveSessions(listener), app: String? = null): MediaController? =
        controllers.filter { (app == null || it.packageName == app) && it.has(song, app) }.maxByOrNull { if (it.playbackState?.state == PlaybackState.STATE_PLAYING) 1 else 0 }

    /**
     * Whether it has [song], by its name. What names no song, e.g. a station or a file with no title, had or
     * asked for, is that only asked of [app] by package, by just what it's called, even nothing. So a station's
     * buttons reach it whatever it's called; a song, or the station before, never does, even sharing its words,
     * as "Hotel California" by Eagles and the station "Hotel California Radio", or "Los 40" and "Los 40 Classic".
     */
    private fun MediaController.has(song: MusicMetadata, app: String?): Boolean {
        val (title, artist) = calls()
        if (Songless.namesNoSong(title, artist) || Songless.namesNoSong(song.title, song.artist)) return app != null && title == song.title && artist == song.artist
        return plays(song)
    }

    /** What it calls what it plays, its title, "" with none, and its artist: as what names no song is shown and asked for by. */
    private fun MediaController.calls(): Pair<String, String> =
        metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }.orEmpty() to metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()

    /** What it plays, as what names no song is shown. */
    private fun MediaController.unnamed(): Unnamed {
        val (title, artist) = calls()
        return Unnamed(packageName, labelOf(packageName), title.ifEmpty { null }, metadata?.let(::coverOf), artist)
    }

    override fun seekTo(song: MusicMetadata, positionMs: Long, app: String?) {
        // Taken back meanwhile, the words just stay where they are. An app that doesn't offer a seek,
        // as Spotify's free plan, errors its player if one is sent, so it isn't.
        runCatching {
            val controller = appFor(song, app = app) ?: return
            val actions = controller.playbackState?.actions ?: 0L
            if (actions and PlaybackState.ACTION_SEEK_TO == 0L) return
            moves = Any()
            controller.transportControls.seekTo(positionMs)
        }
    }

    override fun playPause(song: MusicMetadata, play: Boolean, app: String?) {
        // Taken back meanwhile, the song just keeps on as it was.
        runCatching { appFor(song, app = app)?.transportControls?.run { if (play) play() else pause() } }
    }

    override fun pauseApp(app: String) {
        // Taken back meanwhile, it plays on.
        runCatching {
            sessions.getActiveSessions(listener).filter { it.packageName == app && it.playbackState?.state == PlaybackState.STATE_PLAYING }.forEach { it.transportControls.pause() }
        }
    }

    override fun playApp(app: String) {
        runCatching { sessions.getActiveSessions(listener).filter { it.packageName == app }.sortedWith(nearestFirst).firstOrNull()?.transportControls?.play() }
    }

    override suspend fun resume(song: MusicMetadata, app: String?): Boolean {
        val controller = runCatching { appFor(song, app = app) }.getOrNull() ?: return false
        return watchStart(controller, mustStart = true) { controller.transportControls.play() }
    }

    // Opened as from its icon, which brings back the screen it was on, song and all; a link would start it over.
    override suspend fun bringToFront(song: MusicMetadata, app: String?): Boolean {
        val playing = runCatching { appFor(song, app = app) }.getOrNull() ?: return false
        val opens = context.packageManager.getLaunchIntentForPackage(playing.packageName) ?: return false
        val back = CompletableDeferred<Unit>()
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (state?.state == PlaybackState.STATE_PAUSED || state?.state == PlaybackState.STATE_PLAYING) back.complete(Unit)
            }
        }
        playing.registerCallback(callback, handler)
        try {
            if (runCatching { context.startActivity(opens.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure) return false
            opened = playing.packageName
            // Back in front, YouTube Music puts the song where it was, paused; asked to play before that, it starts it over.
            withTimeoutOrNull(startMs) { back.await() }
            runCatching { playing.transportControls.play() }
            return true
        } finally {
            playing.unregisterCallback(callback)
        }
    }

    override fun skip(song: MusicMetadata, next: Boolean, app: String?) = moveOn(song, app) { if (next) skipToNext() else skipToPrevious() }

    override fun playQueued(song: MusicMetadata, id: Long, app: String?) = moveOn(song, app) { skipToQueueItem(id) }

    /** Has the app playing [song], [named] when it is, go on to another, as [move] asks it, and play it. */
    private fun moveOn(song: MusicMetadata, named: String?, move: MediaController.TransportControls.() -> Unit) {
        // Taken back meanwhile, the song just keeps on as it was.
        runCatching {
            val app = appFor(song, app = named) ?: return
            val paused = app.playbackState?.state != PlaybackState.STATE_PLAYING
            app.transportControls.run {
                move()
                // Paused, the song moved on to would wait unheard, with the words still on the last one.
                if (paused) play()
            }
        }
    }

    override fun shuffle(song: MusicMetadata, on: Boolean, app: String?) {
        // Taken back meanwhile, it plays on as it did.
        runCatching { appFor(song, app = app)?.let { modes.shuffle(it, on) } }
    }

    override fun repeat(song: MusicMetadata, how: Repeat, app: String?) {
        // Taken back meanwhile, it plays on as it did.
        runCatching {
            val playing = appFor(song, app = app) ?: return
            // One with a button of its own for it goes on to its next way as when it's tapped there: YouTube Music
            // only repeats a mix's song, never the endless mix, and won't be told to.
            val own = playing.playbackState?.customActions?.firstOrNull { it.action in REPEATS }
            if (own != null) playing.transportControls.sendCustomAction(own, Bundle()) else modes.repeat(playing, how)
        }
    }

    override fun like(song: MusicMetadata, app: String?) {
        // Taken back meanwhile, nothing's liked.
        runCatching {
            val playing = appFor(song, app = app) ?: return
            val like = playing.playbackState?.customActions?.firstOrNull { it.action in LIKES } ?: return
            playing.transportControls.sendCustomAction(like, Bundle())
        }
    }

    override suspend fun playSong(song: MusicMetadata, app: String?, link: String?, mustStart: Boolean, orLastPlayed: Boolean): Boolean {
        // Without the user's say-so Android shows none, and says so by throwing; an app can still be woken.
        val apps = runCatching { sessions.getActiveSessions(listener) }.getOrDefault(emptyList()).filter { it.packageName != context.packageName }
        // Not running, the app's woken, as Android Auto wakes one; another app wouldn't have its link.
        val asked = apps.firstOrNull { sameApp(app, it.packageName, context::hasApp) }
            ?: app?.let { waker.wake(MusicService.appsFor(it)) }
            ?: apps.takeIf { link == null && (app == null || orLastPlayed) }?.maxByOrNull { it.playbackState?.lastPositionUpdateTime ?: 0L }
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
        else asked.transportControls.playFromSearch(searchQuery(song), wanted)
    }

    // The app may be installed as a build patched from it, under a name of its own, or as its TV app.
    override fun openSong(app: String, link: String): Boolean = openSearch(app, link) || MusicService.appLinksIn(app, link).any { (packageName, its) ->
        runCatching {
            // Qobuz may have to be opened again if the song doesn't start, see [QobuzNumbers].
            if (MusicService.ofOwnApp(packageName) == MusicService.QOBUZ) {
                context.openLeavingTimeToOpenAgain(Intent(Intent.ACTION_VIEW, Uri.parse(its)).setPackage(packageName)).also { if (it) opened = packageName }
            } else {
                context.tryStartMusicActivity(Intent(Intent.ACTION_VIEW, Uri.parse(its)).setPackage(packageName)).also { if (it) opened = packageName }
            }
        }.getOrDefault(false)
    }

    // An app that opens no page of its website's is asked for the search in the link, if it holds one.
    private fun openSearch(app: String, link: String): Boolean {
        val search = MusicService.searchIn(app, link, asking = null)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
        return context.tryStartActivityTwice(search).also { if (it) opened = app }
    }

    override fun playsOnlyInFront(app: String) = onlyInFront.any { sameApp(app, it, context::hasApp) }

    override fun unlocked(): Boolean = context.getSystemService(PowerManager::class.java).isInteractive &&
        !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    override fun lockless(): Boolean = !context.getSystemService(KeyguardManager::class.java).isDeviceSecure

    override fun wakeScreen() {
        // Android's replacement, an activity that turns the screen on, is blocked from the background unless
        // Crosstune may show over other apps (seen on Android 16); this wake lock works for every app.
        @Suppress("DEPRECATION")
        val wakes = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
        context.getSystemService(PowerManager::class.java).newWakeLock(wakes, "crosstune:next song").acquire(WAKE_MS)
    }

    // From the background, Android only lets the activity start if Crosstune may show over other apps, and a
    // blocked start doesn't say so. Otherwise a notification is one tap back: Android sends that tap itself.
    // Left alone when the app opened wouldn't play in the background, as it would stop as soon as it's left.
    override fun comesBack() {
        if (opened?.let(onlyInFront::contains) == true) return
        val back = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { context.startActivity(back) }
        if (!comesBackByItself()) context.noticeToReturn(back)
    }

    override fun comesBackByItself(): Boolean = Settings.canDrawOverlays(context)

    override fun quiet(on: Boolean) {
        // Kept, so music muted by Crosstune when it stopped on the way isn't left muted: the next start lets it be heard.
        if (on == preferences.getBoolean(QUIETED_KEY, false) || on && audio.isStreamMute(AudioManager.STREAM_MUSIC)) return
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
        preferences.edit { putBoolean(QUIETED_KEY, on) }
    }

    // Without the user's say-so Android shows none, and says so by throwing.
    override fun nowPlaying(): Heard.Song? = playingApps().firstNotNullOfOrNull(::playing)

    // A song any app names comes first; only the phone's own sound can be heard, not a TV's or a computer's.
    override fun playingUnnamed(): Unnamed? {
        val apps = playingApps()
        if (apps.any { playing(it) != null }) return null
        return apps.firstOrNull { !it.remote() }?.unnamed()
    }

    // Ranked as songs are, a station among them: one on the phone comes before a song on a computer.
    override fun whatPlays(): WhatPlays? = playingApps().firstOrNull()?.let { app -> playing(app)?.let(WhatPlays::Named) ?: WhatPlays.Nameless(app.unnamed()) }

    override fun unnamedUpdates(): Flow<Unnamed?> = changes(::playingUnnamed)

    override fun playsAloud(): Boolean =
        !audio.isStreamMute(AudioManager.STREAM_MUSIC) && audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0 &&
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).all { it.type in ROOM_OUTPUTS }

    /**
     * The apps playing now, nearest first. Without the user's say-so Android shows none, and says so by
     * throwing. An app may still say it plays long after it stopped, e.g. a browser tab left behind, so one
     * playing on the phone is only believed while the phone is playing something. Cast to a TV, or
     * played on a computer, the phone itself stays quiet.
     */
    private fun playingApps(): List<MediaController> {
        if (!hasAccess()) return emptyList()
        val sounding = audio.isMusicActive
        return runCatching { sessions.getActiveSessions(listener) }.getOrNull().orEmpty()
            .filter { it.packageName != context.packageName && it.playbackState?.state == PlaybackState.STATE_PLAYING && !it.leftBehind() && (sounding || it.remote()) }
            .sortedWith(nearestFirst)
    }

    /** What the app [packageName] is called, as Android shows it. */
    private fun labelOf(packageName: String): String? = runCatching {
        context.packageManager.run { getApplicationLabel(getApplicationInfo(packageName, 0)).toString() }
    }.getOrNull()

    override fun recording(song: MusicMetadata): MusicMetadata? {
        if (!hasAccess()) return null
        return runCatching { sessions.getActiveSessions(listener) }.getOrNull().orEmpty()
            .filter { it.packageName != context.packageName && it.plays(song) }
            .sortedWith(nearestFirst)
            // plays() already checked the session's title and artist. YouTube's video-title
            // parser can mistake a bilingual title's first half for an artist.
            .firstNotNullOfOrNull { playing(it, song.lengthMs)?.metadata?.copy(title = song.title, artist = song.artist) }
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

    /**
     * How long the app says what it plays is, as it said before for the song; see [SteadyLengths]. Under
     * an ad, the song's: see [songUnderAd].
     */
    private fun MediaController.lengthOf(metadata: MediaMetadata, asked: Long? = null): Long? =
        metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 }?.let {
            songUnderAd(metadata, asked) ?: lengths.of(metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(), metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(), it)
        }

    /**
     * How long the song is, when it's an ad that plays: free YouTube Music plays its ads under the next
     * song's name, with all of the song's details but its length, the ad's, then starts the song over
     * from 0. So shorter than the longest ad, said by YouTube Music for a song known to be longer, as [asked]
     * or as said before, it's an ad. A short song of no longer length known is the song.
     */
    private fun MediaController.songUnderAd(metadata: MediaMetadata, asked: Long?): Long? {
        if (MusicService.ofPackage(packageName) != MusicService.YOUTUBE_MUSIC) return null
        if (metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) !in 1 until LONGEST_AD_MS) return null
        val known = lengths.known(metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(), metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty())
        // The one said last, of the song's versions, when not asked for one.
        return (listOfNotNull(asked) + known.asReversed()).firstOrNull { it >= LONGEST_AD_MS }
    }

    /** What plays, as the app names it. */
    private fun MediaController.named(): MusicMetadata? =
        metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?.let { MusicMetadata(it, metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()) }

    /** Stopped or paused at its song's end, where it said it is. */
    private fun MediaController.ended(): Boolean {
        val state = playbackState ?: return false
        val duration = metadata?.let { lengthOf(it) } ?: return false
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

    /** What [controller] plays, as a song, unless it names none; [asked] is how long the song asked for is, when known. */
    private fun playing(controller: MediaController, asked: Long? = null): Heard.Song? {
        val metadata = controller.metadata ?: return null
        val said = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val by = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        if (Songless.namesNoSong(said, by)) return null
        val (title, artist) = MetadataParsers.playerSong(said, by) ?: return null
        // With no album outside a music app it's most likely a video, "Artist - Song (Official Video)" by "ArtistVEVO",
        // named as a song the way a YouTube link is; a video that doesn't say it's music is no song.
        // YouTube Music plays videos with no album too, all of them music, "Queen - Bohemian Rhapsody (Lyrics)" by "7clouds".
        // A music app's "Song - Remastered" stays whole.
        // A song's length tells its versions apart; a video's is the video's, which may open with more.
        val named = when {
            !controller.isVideo() -> MusicMetadata(title, artist, lengthMs = controller.lengthOf(metadata, asked))
            MusicService.ofPackage(controller.packageName) == MusicService.YOUTUBE_MUSIC -> MetadataParsers.youtubeVideo(title, artist) ?: return null
            // A computer's video, as KDE Connect shows one, is only a song when it says so plainly: a
            // talk show's "Show - Episode 12" isn't, though a video on the phone named so is taken as one.
            controller.mirrored() -> MetadataParsers.musicVideo(title, artist, byDash = false) ?: return null
            else -> MetadataParsers.musicVideo(title, artist) ?: return null
        }
        val artwork = coverOf(metadata)
        // Where a video is isn't where its song is, as it may open with more, so it isn't given.
        // Nor where a song waiting for an ad to end is, as it isn't playing yet.
        val clock = following(controller, asked)?.takeIf { !it.video }?.clock?.takeIf { it.playing }
        val id = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        val uri = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
        val duration = controller.lengthOf(metadata, asked)
        return Heard.Song(named.copy(
            artworkUrl = artwork,
            url = uri ?: id?.takeIf { MusicLinks.parse(it) is LinkInput.Link },
            recordingId = RecordingKey.playing(controller.packageName, id, duration)
        ), appleMusicId = null, offsetMs = clock?.positionMs, startedAtMs = clock?.atMs, app = controller.packageName, speed = clock?.speed ?: 1f)
    }

    /** The cover the app shows: its link on the web, or the picture itself; see [ownCover]. */
    private fun coverOf(metadata: MediaMetadata): String? =
        listOf(MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            .mapNotNull(metadata::getString).firstOrNull { it.startsWith("https://") } ?: ownCover(metadata)

    /**
     * The cover the app shows, when it gives no link to one, e.g. YouTube Music for a song no other
     * store has: kept as a file with the covers downloaded, once a song, so it shows like those.
     */
    private fun ownCover(metadata: MediaMetadata): String? {
        val cover = COVERS.firstNotNullOfOrNull(metadata::getBitmap) ?: return null
        val bytes = runCatching {
            // Memory only, so there's nothing to close.
            val output = ByteArrayOutputStream()
            if (cover.compress(Bitmap.CompressFormat.JPEG, COVER_QUALITY, output)) output.toByteArray() else null
        }.getOrNull() ?: return null
        val file = File(context.cacheDir, "$ARTWORK_DIR/playing-${UUID.nameUUIDFromBytes(bytes)}.jpg")
        if (!file.isFile) {
            // Written aside and moved in once whole, so one cut short isn't the song's cover from then on.
            val part = File(file.path + ".part")
            val saved = runCatching {
                file.parentFile?.mkdirs()
                part.writeBytes(bytes)
                part.renameTo(file)
            }.getOrDefault(false)
            if (!saved) {
                part.delete()
                return null
            }
        }
        return file.toURI().toString()
    }

    private fun following(controller: MediaController, asked: Long?): Following? {
        // Stopped, or failing, e.g. a video that won't play in the background, it plays nothing to follow.
        val state = controller.playbackState?.takeIf { it.state !in NOT_PLAYING } ?: return null
        // An app that doesn't say where it is gives nothing to keep the words in time with.
        if (state.position < 0) return null
        val app = labelOf(controller.packageName)
        // Its position is from when it last said so; since then it moved on at its own speed.
        val at = state.lastPositionUpdateTime.takeIf { it > 0 } ?: now()
        val speed = state.playbackSpeed.takeIf { it > 0f } ?: 1f
        // Under an ad, the song waits at its start until the ad's over.
        val ad = controller.metadata?.let { controller.songUnderAd(it, asked) } != null
        val clock = if (ad) PlaybackClock(0, at, speed, playing = false) else PlaybackClock(state.position, at, speed, state.state == PlaybackState.STATE_PLAYING)
        return Following(
            clock, app, state.actions and PlaybackState.ACTION_SEEK_TO != 0L, controller.packageName, controller.isVideo(),
            canSkipNext = state.actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
            canSkipPrevious = state.actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            durationMs = controller.metadata?.let { controller.lengthOf(it, asked) },
            shuffle = modes.shuffles(controller),
            repeat = modes.repeats(controller),
            like = state.customActions.firstOrNull { it.action in LIKES }?.let { AppAction(it.action, it.name.toString(), it.icon, liked(controller.packageName, it)) },
            ad = ad
        )
    }

    /**
     * Whether the song [app] plays is liked there, as its [like] shows: Spotify says so by which button it
     * shows, the others by how they draw the one button, whatever the language. Null when it can't be told.
     */
    private fun liked(app: String, like: PlaybackState.CustomAction): Boolean? = when (like.action) {
        SPOTIFY_LIKED -> true
        SPOTIFY_LIKE -> false
        // YouTube Music's is filled once liked and outlined before; TIDAL's, filled or plain.
        else -> drawingName(app, like.icon)?.let { drawing ->
            when {
                "fill" in drawing -> true
                "outline" in drawing || drawing.endsWith("favorite") -> false
                else -> null
            }
        }
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
            MusicService.ofPackage(packageName).let {
                it == MusicService.YOUTUBE || it == MusicService.YOUTUBE_MUSIC ||
                    (it == null && !MetadataParsers.noArtist(metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()))
            }

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

    /** Only shown here from another device, e.g. a computer's player through KDE Connect: what the phone itself plays comes first. */
    private fun MediaController.mirrored(): Boolean = remote() && !cast()

    /**
     * What the phone itself plays, then what it casts, then what another device plays; among each, the
     * one that last said where it is, over one that said so long ago. A computer's player says so every
     * second, but the phone's own music app is what the phone plays.
     */
    private val nearestFirst = compareBy<MediaController> { if (it.mirrored()) 2 else if (it.cast()) 1 else 0 }
        .thenByDescending { it.playbackState?.lastPositionUpdateTime ?: 0L }

    /** Whether what this app has [playing] is known to be another recording than [song], whatever it's named. */
    private fun MediaController.anotherRecording(playing: MediaMetadata, song: MusicMetadata): Boolean {
        val length = lengthOf(playing, song.lengthMs)
        val recording = RecordingKey.playing(packageName, playing.getString(MediaMetadata.METADATA_KEY_MEDIA_ID), length)
        if (song.recordingId?.startsWith("$packageName:") == true && recording != null && song.recordingId != recording) return true
        // Far from as long as the song, it's another version of it, e.g. the album's take of a single; a video may open with more.
        return !isVideo() && length != null && song.lengthMs?.let { abs(it - length) > SAME_RECORDING_LENGTH_MS } == true
    }

    /** Whether it's [song] this app plays: a video's title names more, e.g. "Artist - Song (Official Video)". */
    private fun MediaController.plays(song: MusicMetadata): Boolean {
        val playing = metadata ?: return false
        if (anotherRecording(playing, song)) return false
        return SongNames.names(playing.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(), playing.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(), song)
    }
}

/** Installed from a file rather than by an app store, which Android 13 and later restrict settings for. */
private val fromAFile = setOf(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE, PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)

/** How long an app asked to play in the background has to say it does, before it's taken as not playing. */
internal const val START_MS = 3_000L

/** Whether the phone's music is muted by Crosstune, see [PlaybackSource.quiet]. */
internal const val QUIETED_KEY = "quieted_music"

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

/** An app saying where it is to within this of where it was going is saying the same, and the timing log keeps it once. */
private const val SAME_CLOCK_NOTE_MS = 20L

/** Longer than any free YouTube Music ad: the longest seen was 2:14. */
internal const val LONGEST_AD_MS = 150_000L

/** Two clocks this close go on from the same: what an app says on a clock is exact to the millisecond. */
internal const val SAME_CLOCK_MS = 50L

/** Where an app puts the cover it shows, the song's own before its album's. */
private val COVERS = listOf(MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)

private const val COVER_QUALITY = 90

/**
 * A music app's own button to like a song, or keep it, by what the app's session calls it. Most call it
 * the same whether it's liked or not, and what it's called and drawn with there says which; Spotify
 * shows one of two.
 */
private val LIKES = setOf(
    SPOTIFY_LIKE,
    SPOTIFY_LIKED,
    "thumbs_up_action", // YouTube Music
    "com.aspiro.tidal.player.action.TOGGLE_FAVORITE", // TIDAL
    "waze.thumbUp" // Amazon Music
)

/** Spotify's like, which it shows while the song isn't in the user's collection, and the one it shows once it is. */
private const val SPOTIFY_LIKE = "ADD_TO"
private const val SPOTIFY_LIKED = "CHECK_FILL"

/** A music app's own button to repeat, which goes round its ways as it does there, by what the app's session calls it. */
private val REPEATS = setOf("loop_mode_action") // YouTube Music

/**
 * Where the phone's music is heard in the room, or never goes: its own speaker and earpiece, a call, a TV
 * over HDMI, a car, a Bluetooth LE speaker. Any other output may be heard by its listener alone, so the
 * microphone isn't asked to hear it: headphones, a hearing aid, a USB or classic Bluetooth one, which can't
 * be told from headphones, a line out, a dock, or a kind a later Android adds.
 */
private val ROOM_OUTPUTS = buildSet {
    addAll(
        listOf(
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_TELEPHONY,
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_BUS
        )
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) addAll(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE, AudioDeviceInfo.TYPE_REMOTE_SUBMIX))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) addAll(listOf(AudioDeviceInfo.TYPE_HDMI_EARC, AudioDeviceInfo.TYPE_BLE_SPEAKER))
}

/** States in which an app plays nothing to follow. */
private val NOT_PLAYING = setOf(PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR)

/** States an app rests in, once its song ends with nothing after it. */
private val STILL = setOf(PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED)

/** How short of its song's length an app may stop, and still have played it to its end. YouTube stops a little past it. */
private const val ENDED_WITHIN_MS = 2_000L

/**
 * How long the screen is held on for Spotify to play a song opened with it off: 0.5 to 3 s worked on the Samsung.
 * Android counts the wake as using the phone, so the screen then stays on until it times out.
 */
private const val WAKE_MS = 1_000L

/** The notice that brings Crosstune back after a music app covered it. */
internal const val RETURN_CHANNEL = "return"

/** 1 is floating lyrics, 3 is ChatGPT sign-in, 4 is hearing, and 5 is a practice reminder. */
internal const val RETURN_NOTICE_ID = 6

/** Set once Android has been asked for the notice, so it isn't asked on every card. */
internal const val KEY_ASKED_RETURN = "asked_return_notifications"

/** Removes the notice, once Crosstune is in front again or the tap opened it. */
internal fun Context.dismissReturnToCard() {
    getSystemService(NotificationManager::class.java).cancel(RETURN_NOTICE_ID)
}

/**
 * Asks [launch] for notification permission once, while Crosstune is still in front, when it can't
 * show over other apps. Returns whether it asked, so what would leave can wait for the answer.
 * Turned down, the music app stays in front and Back still returns.
 */
internal fun Context.askToReturnToCard(launch: (String) -> Unit): Boolean {
    if (Settings.canDrawOverlays(this)) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return false
    val prefs = getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)
    if (prefs.getBoolean(KEY_ASKED_RETURN, false)) return false
    prefs.edit().putBoolean(KEY_ASKED_RETURN, true).apply()
    launch(android.Manifest.permission.POST_NOTIFICATIONS)
    return true
}

/** One tap back to the card. Android sends the tap, so it can start the activity the app itself no longer can. */
private fun Context.noticeToReturn(back: Intent) {
    val notices = getSystemService(NotificationManager::class.java)
    notices.createNotificationChannel(NotificationChannel(RETURN_CHANNEL, getString(R.string.return_channel), NotificationManager.IMPORTANCE_HIGH))
    val options = ActivityOptions.makeBasic()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        options.setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)
    }
    val open = PendingIntent.getActivity(this, RETURN_NOTICE_ID, back, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, options.toBundle())
    notices.notify(
        RETURN_NOTICE_ID,
        NotificationCompat.Builder(this, RETURN_CHANNEL)
            .setSmallIcon(R.drawable.ic_lyrics)
            .setContentTitle(getString(R.string.return_title))
            .setContentText(getString(R.string.return_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    )
}

/**
 * Only here so Android shows Crosstune what music apps are playing, which it does for an app the
 * user lets read notifications. The notifications themselves are never looked at.
 */
class NowPlayingListener : NotificationListenerService() {
    // Android starts Crosstune again for this after stopping it, so a playlist it played through carries on.
    override fun onListenerConnected() = MainActivity.playQueue(applicationContext).resume()
}
