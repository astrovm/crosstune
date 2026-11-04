package com.astrovm.crosstune

import android.net.Uri
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.google.android.gms.cast.RequestData
import com.google.android.gms.cast.tv.media.MediaCommandCallback
import com.google.android.gms.cast.tv.media.MediaLoadCommandCallback
import com.google.android.gms.cast.tv.media.QueueUpdateRequestData
import com.google.android.gms.cast.tv.media.SeekRequestData
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.common.images.WebImage

/** The Cast wire format, independent of Google Play Services and its connection lifecycle. */
internal fun castLoadSong(request: MediaLoadRequestData): CastSong? =
    CastSong.fromJson(request.mediaInfo?.customData) ?: CastSong.fromJson(request.customData)

internal fun castSeek(positionMs: Long?): CastCommand = CastCommand.Seek(positionMs ?: 0L)

internal fun castJump(jump: Int?): CastCommand? = when {
    jump == null || jump == 0 -> null
    jump > 0 -> CastCommand.Next
    else -> CastCommand.Previous
}

internal data class CastPublication(val metadata: MediaMetadataCompat, val playback: PlaybackStateCompat)

internal fun castPublication(state: CastTvState?): CastPublication = CastPublication(
    MediaMetadataCompat.Builder()
        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state?.title.orEmpty())
        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, state?.artist.orEmpty())
        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state?.durationMs ?: -1L)
        .build(),
    PlaybackStateCompat.Builder()
        .setActions(castActions(state))
        .setState(
            when {
                state == null -> PlaybackStateCompat.STATE_NONE
                state.playing -> PlaybackStateCompat.STATE_PLAYING
                else -> PlaybackStateCompat.STATE_PAUSED
            },
            state?.positionMs ?: 0L,
            if (state?.playing == true) 1f else 0f
        )
        .build()
)

private fun castActions(state: CastTvState?): Long {
    var actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP
    if (state?.canSeek == true) actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
    if (state?.canSkipNext == true) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
    if (state?.canSkipPrevious == true) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
    return actions
}

internal fun castRemoteState(info: MediaInfo, status: MediaStatus, positionMs: Long, durationMs: Long): CastTvState {
    val meta = info.metadata
    val playerState = status.playerState
    return CastTvState(
        title = meta?.getString(MediaMetadata.KEY_TITLE).orEmpty(),
        artist = meta?.getString(MediaMetadata.KEY_ARTIST).orEmpty(),
        playing = playerState == MediaStatus.PLAYER_STATE_PLAYING || playerState == MediaStatus.PLAYER_STATE_BUFFERING,
        positionMs = positionMs,
        durationMs = durationMs.takeIf { it > 0 },
        canSeek = status.isMediaCommandSupported(MediaStatus.COMMAND_SEEK),
        canSkipNext = status.isMediaCommandSupported(MediaStatus.COMMAND_QUEUE_NEXT),
        canSkipPrevious = status.isMediaCommandSupported(MediaStatus.COMMAND_QUEUE_PREVIOUS)
    )
}

internal fun castLoadRequest(song: CastSong): MediaLoadRequestData {
    val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
        putString(MediaMetadata.KEY_TITLE, song.title)
        putString(MediaMetadata.KEY_ARTIST, song.artist)
        song.artworkUrl?.let { addImage(WebImage(Uri.parse(it))) }
    }
    val info = MediaInfo.Builder(song.link ?: song.title)
        .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
        .setContentType("audio/*")
        .setMetadata(meta)
        .setCustomData(song.toJson())
        .apply { song.durationMs?.let(::setStreamDuration) }
        .build()
    return MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).setCustomData(song.toJson()).build()
}

/** Dispatch on the app's thread, then acknowledge the original Cast request. */
internal fun castLoadCallback(onLoad: (CastSong) -> Unit, post: (() -> Unit) -> Unit) = object : MediaLoadCommandCallback() {
    override fun onLoad(senderId: String?, request: MediaLoadRequestData): Task<MediaLoadRequestData> {
        castLoadSong(request)?.let { song -> post { onLoad(song) } }
        return Tasks.forResult(request)
    }
}

internal fun castCommandCallback(onCommand: (CastCommand) -> Unit, post: (() -> Unit) -> Unit) = object : MediaCommandCallback() {
    private fun done(command: CastCommand?): Task<Void> {
        command?.let { post { onCommand(it) } }
        return Tasks.forResult(null)
    }

    override fun onPlay(senderId: String?, request: RequestData) = done(CastCommand.Play)
    override fun onPause(senderId: String?, request: RequestData) = done(CastCommand.Pause)
    override fun onStop(senderId: String?, request: RequestData) = done(CastCommand.Stop)
    override fun onSeek(senderId: String?, request: SeekRequestData) = done(castSeek(request.currentTime))
    override fun onQueueUpdate(senderId: String?, request: QueueUpdateRequestData) = done(castJump(request.jump))
}
