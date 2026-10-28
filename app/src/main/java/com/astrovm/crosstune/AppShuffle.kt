package com.astrovm.crosstune

import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Handler
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

/** A music app's shuffle, which Android's media sessions have no word for. */
internal interface AppShuffle {
    /** Whether [app] shuffles; null when it doesn't say, or can't be told to. */
    fun shuffles(app: MediaController): Boolean?

    /** Has [app] shuffle, or play in order. */
    fun shuffle(app: MediaController, on: Boolean)

    /** Calls [changed], on [handler], whenever [app] starts or stops shuffling; what it gives back stops that. */
    fun watch(app: MediaController, handler: Handler, changed: () -> Unit): () -> Unit
}

/**
 * Most music apps' sessions are the support library's, which has a word for shuffle, asked and told
 * its own way through the app's session. That needs a real app's session, on a phone, where it's
 * checked; what's shown of it and asked is tested.
 */
internal class SessionShuffle(private val context: Context) : AppShuffle {
    /** One for each app's session, as each first asks the app for its way to tell, which takes a moment. */
    private val compat = mutableMapOf<MediaSession.Token, MediaControllerCompat>()

    private fun of(app: MediaController) =
        compat.getOrPut(app.sessionToken) { MediaControllerCompat(context, MediaSessionCompat.Token.fromToken(app.sessionToken)) }

    override fun shuffles(app: MediaController): Boolean? {
        // One that can't be told to says so.
        if (((app.playbackState?.actions ?: 0L) and PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE) == 0L) return null
        return when (of(app).shuffleMode) {
            PlaybackStateCompat.SHUFFLE_MODE_NONE -> false
            PlaybackStateCompat.SHUFFLE_MODE_ALL, PlaybackStateCompat.SHUFFLE_MODE_GROUP -> true
            else -> null
        }
    }

    override fun shuffle(app: MediaController, on: Boolean) {
        of(app).transportControls.setShuffleMode(if (on) PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE)
    }

    override fun watch(app: MediaController, handler: Handler, changed: () -> Unit): () -> Unit {
        val callback = object : MediaControllerCompat.Callback() {
            override fun onShuffleModeChanged(shuffleMode: Int) = changed()

            // Until the app has said how it's told, whether it shuffles is unknown.
            override fun onSessionReady() = changed()
        }
        val controller = of(app)
        controller.registerCallback(callback, handler)
        return { controller.unregisterCallback(callback) }
    }
}
