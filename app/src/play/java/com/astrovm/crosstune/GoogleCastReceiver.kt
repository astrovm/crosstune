package com.astrovm.crosstune

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import com.google.android.gms.cast.tv.CastReceiverContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Cast Connect on the TV: the phone's LOAD and commands reach Crosstune as the plain [CastSong] and [CastCommand] it
 * understands, and what plays is told back through a media session, which is where Cast reads it from.
 */
internal class GoogleCastReceiver(private val context: Context) : CastReceiver {
    private val main = Handler(Looper.getMainLooper())
    private var session: MediaSessionCompat? = null

    override fun start(onLoad: (CastSong) -> Unit, onCommand: (CastCommand) -> Unit) {
        // A TV without Google Play Services has no Cast to answer.
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) return
        CastReceiverContext.initInstance(context)
        val receiver = CastReceiverContext.getInstance()
        val mediaSession = MediaSessionCompat(context, "CrosstuneCast").apply { isActive = true }
        session = mediaSession
        receiver.setMessageReceivedListener(CAST_NAMESPACE) { _, _, message ->
            CastCommand.fromMessage(message)?.let { main.post { onCommand(it) } }
        }
        receiver.mediaManager.apply {
            setSessionCompatToken(mediaSession.sessionToken)
            setMediaLoadCommandCallback(castLoadCallback(onLoad) { main.post(it) })
            setMediaCommandCallback(castCommandCallback(onCommand) { main.post(it) })
        }

        receiver.start()
    }

    override fun publish(state: CastTvState?) {
        val mediaSession = session ?: return
        val publication = castPublication(state)
        mediaSession.setMetadata(publication.metadata)
        mediaSession.setPlaybackState(publication.playback)
        CastReceiverContext.getInstance().mediaManager.broadcastMediaStatus()
    }

    override fun handle(intent: Intent): Boolean = session != null && CastReceiverContext.getInstance().mediaManager.onNewIntent(intent)

    override fun stop() {
        val mediaSession = session ?: return
        CastReceiverContext.getInstance().stop()
        mediaSession.release()
        session = null
    }
}
