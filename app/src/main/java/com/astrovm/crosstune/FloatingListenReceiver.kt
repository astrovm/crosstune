package com.astrovm.crosstune

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The floating window's microphone button. Declared unexported, so only Android, for Crosstune's own
 * window, can send it; each tap goes on to the screen showing the words.
 */
class FloatingListenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        tapped.tryEmit(Unit)
    }

    internal companion object {
        private val tapped = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val taps: SharedFlow<Unit> = tapped
    }
}
