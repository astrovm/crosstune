package com.astrovm.crosstune

import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Handler
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

/** How a music app repeats: not at all, its whole list, or the one song. */
internal enum class Repeat { OFF, ALL, ONE }

/** A music app's shuffle and repeat, which Android's media sessions have no word for. */
internal interface AppModes {
    /** Whether [app] shuffles; null when it doesn't say, or can't be told to. */
    fun shuffles(app: MediaController): Boolean?

    /** Has [app] shuffle, or play in order. */
    fun shuffle(app: MediaController, on: Boolean)

    /** How [app] repeats; null when it doesn't say, or can't be told to. */
    fun repeats(app: MediaController): Repeat?

    /** Has [app] repeat [how]. */
    fun repeat(app: MediaController, how: Repeat)

    /** Calls [changed], on [handler], whenever [app] shuffles or repeats otherwise; what it gives back stops that. */
    fun watch(app: MediaController, handler: Handler, changed: () -> Unit): () -> Unit
}

/**
 * Most music apps' sessions are the support library's, which has words for shuffle and repeat,
 * asked and told its own way through the app's session. That needs a real app's session, on a
 * phone, where it's checked; what's shown of it and asked is tested.
 */
internal class SessionModes(private val context: Context) : AppModes {
    /** One for each app's session, as each first asks the app for its way to tell, which takes a moment. */
    private val compat = mutableMapOf<MediaSession.Token, MediaControllerCompat>()

    private fun of(app: MediaController) =
        compat.getOrPut(app.sessionToken) { MediaControllerCompat(context, MediaSessionCompat.Token.fromToken(app.sessionToken)) }

    /** Whether [app] says it can be told to [action]: one that can't says so. */
    private fun lets(app: MediaController, action: Long) = ((app.playbackState?.actions ?: 0L) and action) != 0L

    override fun shuffles(app: MediaController): Boolean? {
        if (!lets(app, PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE)) return null
        return when (of(app).shuffleMode) {
            PlaybackStateCompat.SHUFFLE_MODE_NONE -> false
            PlaybackStateCompat.SHUFFLE_MODE_ALL, PlaybackStateCompat.SHUFFLE_MODE_GROUP -> true
            else -> null
        }
    }

    override fun shuffle(app: MediaController, on: Boolean) {
        of(app).transportControls.setShuffleMode(if (on) PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE)
    }

    override fun repeats(app: MediaController): Repeat? {
        if (!lets(app, PlaybackStateCompat.ACTION_SET_REPEAT_MODE)) return null
        return when (of(app).repeatMode) {
            PlaybackStateCompat.REPEAT_MODE_NONE -> Repeat.OFF
            PlaybackStateCompat.REPEAT_MODE_ALL, PlaybackStateCompat.REPEAT_MODE_GROUP -> Repeat.ALL
            PlaybackStateCompat.REPEAT_MODE_ONE -> Repeat.ONE
            else -> null
        }
    }

    override fun repeat(app: MediaController, how: Repeat) {
        of(app).transportControls.setRepeatMode(
            when (how) {
                Repeat.OFF -> PlaybackStateCompat.REPEAT_MODE_NONE
                Repeat.ALL -> PlaybackStateCompat.REPEAT_MODE_ALL
                Repeat.ONE -> PlaybackStateCompat.REPEAT_MODE_ONE
            }
        )
    }

    override fun watch(app: MediaController, handler: Handler, changed: () -> Unit): () -> Unit {
        val callback = object : MediaControllerCompat.Callback() {
            override fun onShuffleModeChanged(shuffleMode: Int) = changed()

            override fun onRepeatModeChanged(repeatMode: Int) = changed()

            // Until the app has said how it's told, how it shuffles and repeats is unknown.
            override fun onSessionReady() = changed()
        }
        val controller = of(app)
        controller.registerCallback(callback, handler)
        return { controller.unregisterCallback(callback) }
    }
}
