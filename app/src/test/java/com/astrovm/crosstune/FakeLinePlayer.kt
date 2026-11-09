package com.astrovm.crosstune

import android.content.Context
import android.view.View
import java.util.concurrent.CopyOnWriteArrayList

/** What a [FakeLinePlayer] was asked to show: [videoId] from [startMs] to [endMs], playing when [play]. */
internal data class Shown(val videoId: String, val startMs: Long, val endMs: Long, val expectedMs: Long?, val play: Boolean)

/** The small YouTube player of a test: a plain view that remembers what it was asked, and says how it went when the test says so. */
internal class FakeLinePlayer(context: Context) : LinePlayer {
    private val view = View(context)
    val shown: MutableList<Shown> = CopyOnWriteArrayList()

    /** How many times it was paused. */
    @Volatile var pauses = 0

    @Volatile private var done: (LineEnd) -> Unit = {}

    override fun view(): View = view

    override fun show(videoId: String, startMs: Long, endMs: Long, expectedMs: Long?, play: Boolean, done: (LineEnd) -> Unit) {
        shown += Shown(videoId, startMs, endMs, expectedMs, play)
        this.done = done
    }

    override fun pause() {
        pauses++
    }

    /** The line it was last shown ends there, or can't be played. */
    fun end(how: LineEnd) = done(how)
}
