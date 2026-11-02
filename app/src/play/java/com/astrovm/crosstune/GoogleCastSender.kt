package com.astrovm.crosstune

import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.MediaRouteControllerDialog
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Google Cast on a phone: the glue between [CastSender] and Google's framework, which has nothing of Crosstune's own. */
internal class GoogleCastSender(private val context: Context) : CastSender {
    private val aroundNow = MutableStateFlow(false)
    private val targetNow = MutableStateFlow<CastTarget?>(null)
    override val around: StateFlow<Boolean> = aroundNow
    override val target: StateFlow<CastTarget?> = targetNow

    private var cast: CastContext? = null
    private var remote: RemoteMediaClient? = null

    private val tvChanged = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = update()
        override fun onMetadataUpdated() = update()
        override fun onQueueStatusUpdated() = update()
    }

    private val sessions = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = begin(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = begin(session)
        override fun onSessionEnded(session: CastSession, error: Int) = end()
        override fun onSessionSuspended(session: CastSession, reason: Int) = end()
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionStartFailed(session: CastSession, error: Int) = end()
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumeFailed(session: CastSession, error: Int) = end()
    }

    init {
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS) {
            CastContext.getSharedInstance(context, ContextCompat.getMainExecutor(context)).addOnSuccessListener(::ready)
        }
    }

    private fun ready(castContext: CastContext) {
        cast = castContext
        castContext.addCastStateListener { aroundNow.value = it != CastState.NO_DEVICES_AVAILABLE }
        aroundNow.value = castContext.castState != CastState.NO_DEVICES_AVAILABLE
        castContext.sessionManager.addSessionManagerListener(sessions, CastSession::class.java)
        castContext.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let(::begin)
    }

    private fun begin(session: CastSession) {
        remote?.unregisterCallback(tvChanged)
        remote = session.remoteMediaClient?.also { it.registerCallback(tvChanged) }
        targetNow.value = CastTarget(session.castDevice?.friendlyName.orEmpty(), state())
    }

    private fun end() {
        remote?.unregisterCallback(tvChanged)
        remote = null
        targetNow.value = null
    }

    private fun update() {
        targetNow.value = targetNow.value?.copy(state = state())
    }

    private fun state(): CastTvState? {
        val client = remote ?: return null
        val info = client.mediaInfo ?: return null
        val meta = info.metadata
        val status = client.mediaStatus ?: return null
        val playerState = status.playerState
        return CastTvState(
            title = meta?.getString(MediaMetadata.KEY_TITLE).orEmpty(),
            artist = meta?.getString(MediaMetadata.KEY_ARTIST).orEmpty(),
            playing = playerState == MediaStatus.PLAYER_STATE_PLAYING || playerState == MediaStatus.PLAYER_STATE_BUFFERING,
            positionMs = client.approximateStreamPosition,
            durationMs = client.streamDuration.takeIf { it > 0 },
            canSeek = status.isMediaCommandSupported(MediaStatus.COMMAND_SEEK),
            canSkipNext = status.isMediaCommandSupported(MediaStatus.COMMAND_QUEUE_NEXT),
            canSkipPrevious = status.isMediaCommandSupported(MediaStatus.COMMAND_QUEUE_PREVIOUS)
        )
    }

    override fun chooseTv(context: Context) {
        val selector = cast?.mergedSelector ?: return
        MediaRouteChooserDialog(context).apply { routeSelector = selector }.show()
    }

    override fun showTv(context: Context) {
        MediaRouteControllerDialog(context).show()
    }

    override fun load(song: CastSong) {
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
        remote?.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).setCustomData(song.toJson()).build())
    }

    override fun send(command: CastCommand) {
        val client = remote ?: return
        when (command) {
            CastCommand.Play -> client.play()
            CastCommand.Pause -> client.pause()
            CastCommand.Stop -> client.stop()
            is CastCommand.Seek -> client.seek(MediaSeekOptions.Builder().setPosition(command.positionMs).build())
            CastCommand.Next -> client.queueNext(null)
            CastCommand.Previous -> client.queuePrev(null)
            is CastCommand.Translation, is CastCommand.Readings ->
                command.message()?.let { cast?.sessionManager?.currentCastSession?.sendMessage(CAST_NAMESPACE, it) }
        }
    }

    override fun disconnect() {
        cast?.sessionManager?.endCurrentSession(false)
    }
}
