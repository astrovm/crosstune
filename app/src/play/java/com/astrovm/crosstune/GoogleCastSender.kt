package com.astrovm.crosstune

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.MediaRouteControllerDialog
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
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
        val status = client.mediaStatus ?: return null
        return castRemoteState(info, status, client.approximateStreamPosition, client.streamDuration)
    }

    override fun chooseTv(context: Context) {
        val selector = cast?.mergedSelector ?: return
        MediaRouteChooserDialog(context).apply { routeSelector = selector }.show()
    }

    override fun showTv(context: Context) {
        MediaRouteControllerDialog(context).show()
    }

    override fun load(song: CastSong) {
        remote?.load(castLoadRequest(song))
    }

    override fun send(command: CastCommand) {
        val client = remote ?: return
        sendCastCommand(command, object : CastTransport {
            override fun play() { client.play() }
            override fun pause() { client.pause() }
            override fun stop() { client.stop() }
            override fun seek(positionMs: Long) { client.seek(MediaSeekOptions.Builder().setPosition(positionMs).build()) }
            override fun next() { client.queueNext(null) }
            override fun previous() { client.queuePrev(null) }
            override fun message(value: String) {
                cast?.sessionManager?.currentCastSession?.sendMessage(CAST_NAMESPACE, value)
            }
        })
    }

    override fun disconnect() {
        cast?.sessionManager?.endCurrentSession(false)
    }
}
