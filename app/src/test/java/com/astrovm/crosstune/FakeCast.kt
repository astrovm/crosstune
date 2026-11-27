package com.astrovm.crosstune

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList

/** Casting with no Google Play Services: a TV that comes and goes as a test says, and what it was asked, on both sides. */
internal class FakeCast : CastSender, CastReceiver {
    override val around: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val target: MutableStateFlow<CastTarget?> = MutableStateFlow(null)

    /** How many times the phone asked which TV, and to see the one cast to. */
    var chosen = 0
    var shown = 0
    var disconnected = 0
    val loaded: MutableList<CastSong> = CopyOnWriteArrayList()
    val sent: MutableList<CastCommand> = CopyOnWriteArrayList()

    override fun chooseTv(context: Context) {
        chosen++
    }

    override fun showTv(context: Context) {
        shown++
    }

    override fun load(song: CastSong) {
        loaded += song
    }

    override fun send(command: CastCommand) {
        sent += command
    }

    override fun disconnect() {
        disconnected++
    }

    /** The TV's side: what a phone's cast says to it, and what it told the phones. */
    var onLoad: ((CastSong) -> Unit)? = null
    var onCommand: ((CastCommand) -> Unit)? = null
    var started = 0
    var stopped = 0
    val published: MutableList<CastTvState?> = CopyOnWriteArrayList()
    val handled: MutableList<Intent> = CopyOnWriteArrayList()

    /** Whether an intent is a phone's cast. */
    var handles = false

    override fun start(onLoad: (CastSong) -> Unit, onCommand: (CastCommand) -> Unit) {
        started++
        this.onLoad = onLoad
        this.onCommand = onCommand
    }

    override fun publish(state: CastTvState?) {
        published += state
    }

    override fun handle(intent: Intent): Boolean {
        handled += intent
        return handles
    }

    override fun stop() {
        stopped++
    }
}
