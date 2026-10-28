package com.astrovm.crosstune

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File
import java.util.UUID

/**
 * Where in a song it is: [positionMs] at [atMs] on the clock that counts since the phone started,
 * moving on at [speed] while [playing].
 */
internal data class PlaybackClock(val positionMs: Long, val atMs: Long, val speed: Float = 1f, val playing: Boolean = true) {
    fun positionAt(nowMs: Long): Long = if (playing) positionMs + ((nowMs - atMs) * speed).toLong() else positionMs
}

/**
 * Where an app says it paused can be a moment behind where the song really stopped, as YouTube
 * Music's often is, by up to half a second. The song plays on from where it really stopped, so the
 * words would come in late after every pause: where it was playing on to is believed instead, until
 * the app says it's somewhere else, e.g. moved to another place.
 */
internal class SteadyPause {
    private var last: PlaybackClock? = null
    /** Where the app said it paused, when that was a moment behind; and where it had really got to. */
    private var said: Long? = null
    private var stopped = 0L

    fun of(clock: PlaybackClock): PlaybackClock {
        val behind = last?.takeIf { it.playing && !clock.playing }?.let { it.positionAt(clock.atMs) - clock.positionMs }
        if (behind != null) {
            said = clock.positionMs.takeIf { behind in 1..STALE_PAUSE_MS }
            stopped = clock.positionMs + behind
        } else if (clock.positionMs != said) {
            said = null
        }
        return (if (said != null) clock.copy(positionMs = stopped) else clock).also { last = it }
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
    val shuffle: Boolean? = null
)

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

    /** Has the app playing [song] go on to its [next] song, or back to the one before, and play it. */
    fun skip(song: MusicMetadata, next: Boolean)

    /** Has the app playing [song] shuffle, or play in order. */
    fun shuffle(song: MusicMetadata, on: Boolean)

    /**
     * Has [app] find [song] by its name and play it, in the background, or YouTube Music play its
     * [link] there when it's known. Not running, [app] is woken in the background to; with no [link],
     * the music app that last played is asked when [app] can't be. False with no app to ask.
     */
    suspend fun playSong(song: MusicMetadata, app: String?, link: String? = null): Boolean

    /**
     * Opens [app] on a song's [link], for when it isn't running and so has nothing to ask in the
     * background; false when it can't be. It comes to the front, so Crosstune [comesBack] once it plays.
     */
    fun openSong(app: String, link: String): Boolean

    /** Brings Crosstune back to the front, over the app [openSong] opened. */
    fun comesBack()

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

    /** Has [app] play the list of songs at [link], from its first, in the background where it can; false with no app to ask. */
    suspend fun playList(app: String, link: String): Boolean

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
    private val shuffles: AppShuffle = SessionShuffle(context),
    /** An app's queue; Robolectric shows a test none. */
    private val queueOf: (MediaController) -> List<MediaSession.QueueItem>? = MediaController::getQueue
) : PlaybackSource {
    private val listener = ComponentName(context, NowPlayingListener::class.java)
    private val sessions: MediaSessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val audio: AudioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    override fun hasAccess(): Boolean = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    override fun restricted(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { context.packageManager.getInstallSourceInfo(context.packageName).packageSource }.getOrNull() in fromAFile

    override fun follow(song: MusicMetadata): Flow<Following?> = callbackFlow {
        var followed: MediaController? = null
        var shuffleWatched: () -> Unit = {}
        var watched: List<MediaController> = emptyList()
        var pauses = SteadyPause()
        fun steadily(controller: MediaController) = following(controller)?.let { it.copy(clock = pauses.of(it.clock)) }
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                trySend(followed?.let(::steadily))
            }
        }
        fun pick(controllers: List<MediaController>) {
            val playing = appFor(song, controllers)
            if (playing?.sessionToken != followed?.sessionToken) {
                followed?.unregisterCallback(callback)
                shuffleWatched()
                followed = playing
                pauses = SteadyPause()
                playing?.registerCallback(callback, handler)
                // An app's shuffle isn't in what Android says of how it plays, so it's watched apart.
                shuffleWatched = playing?.let { app -> shuffles.watch(app, handler) { trySend(steadily(app)) } } ?: {}
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
            shuffleWatched()
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
        // Taken back meanwhile, the words just stay where they are.
        runCatching { appFor(song)?.transportControls?.seekTo(positionMs) }
    }

    override fun playPause(song: MusicMetadata, play: Boolean) {
        // Taken back meanwhile, the song just keeps on as it was.
        runCatching { appFor(song)?.transportControls?.run { if (play) play() else pause() } }
    }

    override fun skip(song: MusicMetadata, next: Boolean) {
        // Taken back meanwhile, the song just keeps on as it was.
        runCatching {
            val app = appFor(song) ?: return
            val paused = app.playbackState?.state != PlaybackState.STATE_PLAYING
            app.transportControls.run {
                if (next) skipToNext() else skipToPrevious()
                // Paused, the song skipped to would wait unheard, with the words still on the last one.
                if (paused) play()
            }
        }
    }

    override fun shuffle(song: MusicMetadata, on: Boolean) {
        // Taken back meanwhile, it plays on as it did.
        runCatching { appFor(song)?.let { shuffles.shuffle(it, on) } }
    }

    override suspend fun playSong(song: MusicMetadata, app: String?, link: String?): Boolean {
        // Without the user's say-so Android shows none, and says so by throwing; an app can still be woken.
        val apps = runCatching { sessions.getActiveSessions(listener) }.getOrDefault(emptyList()).filter { it.packageName != context.packageName }
        // Not running, the app's woken, as Android Auto wakes one; another app wouldn't have its link.
        val asked = apps.firstOrNull { sameApp(app, it.packageName) }
            ?: app?.let { waker.wake(MusicService.ofPackage(it)?.packageNames ?: listOf(it)) }
            ?: apps.takeIf { link == null }?.maxByOrNull { it.playbackState?.lastPositionUpdateTime ?: 0L }
            ?: return false
        return runCatching { ask(asked, song, link) }.isSuccess
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
    override fun openSong(app: String, link: String): Boolean = (MusicService.ofPackage(app)?.appLinks(link) ?: listOf(app to link)).any { (packageName, its) ->
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(its)).setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    // From the background, Android only lets it if Crosstune may show over other apps; otherwise it stays behind.
    override fun comesBack() {
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
            .filter { sameApp(app, it.packageName) }
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

    /** What plays, as the app names it. */
    private fun MediaController.named(): MusicMetadata? =
        metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?.let { MusicMetadata(it, metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()) }

    /** Stopped or paused at its song's end, where it said it is. */
    private fun MediaController.ended(): Boolean {
        val state = playbackState ?: return false
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 } ?: return false
        return state.state in STILL && state.position >= duration - ENDED_WITHIN_MS
    }

    override suspend fun playList(app: String, link: String): Boolean {
        val asked = runCatching { sessions.getActiveSessions(listener) }.getOrDefault(emptyList()).firstOrNull { sameApp(app, it.packageName) }
            ?: waker.wake(MusicService.ofPackage(app)?.packageNames ?: listOf(app))
            ?: return false
        return runCatching { asked.transportControls.playFromUri(Uri.parse(link), Bundle()) }.isSuccess
    }

    /** What [read] says, again whenever an app starts, pauses, stops or changes songs. */
    private fun <T> changes(read: () -> T): Flow<T> = callbackFlow {
        var watched: List<MediaController> = emptyList()
        fun update() { trySend(read()) }
        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) = update()
            override fun onPlaybackStateChanged(state: PlaybackState?) = update()
            override fun onSessionDestroyed() = update()
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
            !controller.isVideo() -> MusicMetadata(title, artist, lengthMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 })
            MusicService.ofPackage(controller.packageName) == MusicService.YOUTUBE_MUSIC -> MetadataParsers.youtubeVideo(title, artist) ?: return null
            else -> MetadataParsers.musicVideo(title, artist) ?: return null
        }
        val artwork = listOf(MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            .mapNotNull(metadata::getString).firstOrNull { it.startsWith("https://") } ?: ownCover(metadata, named)
        // Where a video is isn't where its song is, as it may open with more, so it isn't given.
        val clock = following(controller)?.takeIf { !it.video }?.clock
        val id = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        val uri = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 }
        return Heard.Song(named.copy(
            artworkUrl = artwork,
            url = uri ?: id?.takeIf { MusicLinks.parse(it) is LinkInput.Link },
            recordingId = RecordingKey.playing(controller.packageName, id, duration)
        ), appleMusicId = null, offsetMs = clock?.positionMs, startedAtMs = clock?.atMs)
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
            durationMs = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 },
            shuffle = shuffles.shuffles(controller)
        )
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
    private fun MediaController.remote(): Boolean = playbackInfo?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE

    /** A music app's own song, cast to a TV: still the phone's, played and paused from it. */
    private fun MediaController.cast(): Boolean = remote() && MusicService.ofPackage(packageName) != null

    /** Only shown here from another device, e.g. a computer's video through KDE Connect: what the phone plays is another one. */
    private fun MediaController.mirrored(): Boolean = remote() && !cast()

    /** Whether it's [song] this app plays: a video's title names more, e.g. "Artist - Song (Official Video)". */
    private fun MediaController.plays(song: MusicMetadata): Boolean {
        val playing = metadata ?: return false
        val recording = RecordingKey.playing(packageName, playing.getString(MediaMetadata.METADATA_KEY_MEDIA_ID), playing.getLong(MediaMetadata.METADATA_KEY_DURATION))
        if (song.recordingId?.startsWith("$packageName:") == true && recording != null && song.recordingId != recording) return false
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

/** How long a session that doesn't say where it is can stay quiet before it's taken as left behind. */
internal const val LEFT_BEHIND_MS = 10 * 60_000L

/** How far behind where it was playing an app may say it paused, and still be believed to have stopped there instead. */
internal const val STALE_PAUSE_MS = 1_000L

/** Where an app puts the cover it shows, the song's own before its album's. */
private val COVERS = listOf(MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)

private const val COVER_QUALITY = 90

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
