package com.astrovm.crosstune

import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.session.SessionToken
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import androidx.media3.session.MediaController as Media3Controller

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
 * asked and told its own way through the app's session, which Media3 speaks. That needs a real app's
 * session, on a phone, where it's checked; what's shown of it and asked is tested.
 */
internal class SessionModes(private val context: Context) : AppModes {
    private val main = Handler(Looper.getMainLooper())
    private val onMain = Executor { main.post(it) }

    /** One for each app's session, as each first connects to the app, which takes a moment. */
    private val apps = ConcurrentHashMap<MediaSession.Token, Session>()

    private fun of(app: MediaController) = apps.computeIfAbsent(app.sessionToken) { Session(it) }

    /** Whether [app] says it can be told to [action]: one that can't says so. */
    private fun lets(app: MediaController, action: Long) = ((app.playbackState?.actions ?: 0L) and action) != 0L

    override fun shuffles(app: MediaController): Boolean? = if (lets(app, ACTION_SET_SHUFFLE_MODE)) of(app).shuffles else null

    override fun shuffle(app: MediaController, on: Boolean) = of(app).tell { it.shuffleModeEnabled = on }

    override fun repeats(app: MediaController): Repeat? = if (lets(app, ACTION_SET_REPEAT_MODE)) of(app).repeats else null

    override fun repeat(app: MediaController, how: Repeat) = of(app).tell {
        it.repeatMode = when (how) {
            Repeat.OFF -> Player.REPEAT_MODE_OFF
            Repeat.ALL -> Player.REPEAT_MODE_ALL
            Repeat.ONE -> Player.REPEAT_MODE_ONE
        }
    }

    override fun watch(app: MediaController, handler: Handler, changed: () -> Unit): () -> Unit {
        val watcher = { handler.post(changed); Unit }
        val watched = of(app)
        watched.watchers += watcher
        return { watched.watchers -= watcher }
    }

    /**
     * An app's session, through Media3, which only answers on the thread it was made on, so what the
     * app last said is kept here for any thread to read.
     */
    private inner class Session(token: MediaSession.Token) : Player.Listener {
        private var controller: Media3Controller? = null

        /** Null until connected, or when it doesn't say. */
        @Volatile
        var shuffles: Boolean? = null

        @Volatile
        var repeats: Repeat? = null

        val watchers = CopyOnWriteArrayList<() -> Unit>()

        init {
            val made = SessionToken.createSessionToken(context, token)
            made.addListener({
                // An app that won't let Crosstune in, or is gone meanwhile, says nothing.
                val connecting = runCatching { Media3Controller.Builder(context, made.get()).setApplicationLooper(Looper.getMainLooper()).buildAsync() }.getOrNull()
                connecting?.addListener({
                    controller = runCatching { connecting.get() }.getOrNull()?.also {
                        it.addListener(this)
                        read(it)
                    }
                }, onMain)
            }, onMain)
        }

        /** Has the app do [what], once connected. */
        fun tell(what: (Player) -> Unit) {
            main.post { controller?.let(what) }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED)) read(player)
        }

        private fun read(player: Player) {
            shuffles = player.shuffleModeEnabled
            repeats = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Repeat.OFF
                Player.REPEAT_MODE_ALL -> Repeat.ALL
                Player.REPEAT_MODE_ONE -> Repeat.ONE
                else -> null
            }
            watchers.forEach { it() }
        }
    }
}

// The support library's words, in what an app says it can be told, which Android's own have none for.
private const val ACTION_SET_REPEAT_MODE = 1L shl 18
private const val ACTION_SET_SHUFFLE_MODE = 1L shl 21
