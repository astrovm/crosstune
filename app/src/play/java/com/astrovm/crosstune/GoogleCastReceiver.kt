package com.astrovm.crosstune

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.RequestData
import com.google.android.gms.cast.tv.CastReceiverContext
import com.google.android.gms.cast.tv.media.MediaCommandCallback
import com.google.android.gms.cast.tv.media.MediaLoadCommandCallback
import com.google.android.gms.cast.tv.media.QueueUpdateRequestData
import com.google.android.gms.cast.tv.media.SeekRequestData
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks

/**
 * Cast Connect on the TV: the phone's LOAD and commands reach Crosstune as the plain [CastSong] and [CastCommand] it
 * understands, and what plays is told back through a media session, which is where Cast reads it from.
 */
internal class GoogleCastReceiver(private val context: Context) : CastReceiver {
    private val main = Handler(Looper.getMainLooper())
    private var session: MediaSessionCompat? = null

    override fun start(onLoad: (CastSong) -> Unit, onCommand: (CastCommand) -> Unit) {
        CastReceiverContext.initInstance(context)
        val receiver = CastReceiverContext.getInstance()
        val mediaSession = MediaSessionCompat(context, "CrosstuneCast").apply { isActive = true }
        session = mediaSession
        receiver.setMessageReceivedListener(CAST_NAMESPACE) { _, _, message ->
            CastCommand.fromMessage(message)?.let { main.post { onCommand(it) } }
        }
        receiver.mediaManager.apply {
            setSessionCompatToken(mediaSession.sessionToken)
            setMediaLoadCommandCallback(object : MediaLoadCommandCallback() {
                override fun onLoad(senderId: String?, request: MediaLoadRequestData): Task<MediaLoadRequestData> {
                    val song = CastSong.fromJson(request.mediaInfo?.customData) ?: CastSong.fromJson(request.customData)
                    if (song != null) main.post { onLoad(song) }
                    return Tasks.forResult(request)
                }
            })
            setMediaCommandCallback(object : MediaCommandCallback() {
                override fun onPlay(senderId: String?, request: RequestData) = done(onCommand, CastCommand.Play)
                override fun onPause(senderId: String?, request: RequestData) = done(onCommand, CastCommand.Pause)
                override fun onStop(senderId: String?, request: RequestData) = done(onCommand, CastCommand.Stop)
                override fun onSeek(senderId: String?, request: SeekRequestData) =
                    done(onCommand, CastCommand.Seek(request.currentTime ?: 0L))
                override fun onQueueUpdate(senderId: String?, request: QueueUpdateRequestData): Task<Void> {
                    val jump = request.jump ?: 0
                    return done(onCommand, if (jump > 0) CastCommand.Next else if (jump < 0) CastCommand.Previous else null)
                }
            })
        }
        receiver.start()
    }

    private fun done(onCommand: (CastCommand) -> Unit, command: CastCommand?): Task<Void> {
        command?.let { main.post { onCommand(it) } }
        return Tasks.forResult(null)
    }

    override fun publish(state: CastTvState?) {
        val mediaSession = session ?: return
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state?.title.orEmpty())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, state?.artist.orEmpty())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state?.durationMs ?: -1L)
                .build()
        )
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions(state))
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
        CastReceiverContext.getInstance().mediaManager.broadcastMediaStatus()
    }

    private fun actions(state: CastTvState?): Long {
        var actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP
        if (state?.canSeek == true) actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
        if (state?.canSkipNext == true) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        if (state?.canSkipPrevious == true) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        return actions
    }

    override fun handle(intent: Intent): Boolean = session != null && CastReceiverContext.getInstance().mediaManager.onNewIntent(intent)

    override fun stop() {
        CastReceiverContext.getInstance().stop()
        session?.release()
        session = null
    }
}
