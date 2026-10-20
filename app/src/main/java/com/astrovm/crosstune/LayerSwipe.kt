package com.astrovm.crosstune

import android.os.SystemClock
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs

/** A screen a swipe reaches: [go] gets there, and [back], for one that's opened as the finger starts, undoes it. */
internal class Layer(val to: Screen, val go: () -> Unit, val back: (() -> Unit)? = null)

/**
 * A swipe up or down between layers (the bar, the player, the words) under way. The screens follow
 * the finger by [fraction], from the one shown to [to], then carry on or go back once it lifts.
 */
@Stable
internal class LayerSwipe {
    /** Where a swipe up or down from the screen shown leads, if anywhere. */
    var up: Layer? = null
    var down: Layer? = null
    /** How far a full swipe is, and how quick a flick is to count for one, in pixels. */
    var distance = 1f
    var flick = 1f

    /** The screen being swiped to, null while no swipe is under way. */
    var to by mutableStateOf<Screen?>(null)
        private set
    /** How far along that is, 0 to 1. */
    var fraction by mutableFloatStateOf(0f)
        private set
    /** Once the finger lifts, whether it carries on to [to] or goes back. */
    var carryOn by mutableStateOf<Boolean?>(null)
        private set
    private var layer: Layer? = null
    private var upward = false
    private val tracker = VelocityTracker()
    private var travelled = 0f

    val swiping get() = to != null && carryOn == null

    /** Moves along by [amount] of the finger's, positive down, and returns how much of it that took. */
    fun drag(amount: Float, time: Long = SystemClock.uptimeMillis()): Float {
        if (carryOn != null) return 0f
        if (layer == null) {
            val started = (if (amount < 0f) up else down) ?: return 0f
            // The screen it reaches has to be there to follow the finger, so one that opens is opened now.
            if (started.back != null) started.go()
            layer = started
            upward = amount < 0f
            tracker.resetTracking()
            travelled = 0f
            to = started.to
        }
        val before = fraction
        fraction = (before + (if (upward) -amount else amount) / distance).coerceIn(0f, 1f)
        val moved = (fraction - before) * distance
        travelled += amount
        tracker.addPosition(time, Offset(0f, travelled))
        return if (upward) -moved else moved
    }

    /** The finger lifts, at [velocity] (positive down) if it knows, or the swipe is cut short. */
    fun release(velocity: Float = tracker.calculateVelocity().y) {
        val started = layer ?: return
        val toward = if (upward) -velocity else velocity
        val carry = toward >= flick || (toward > -flick && fraction >= COMMIT)
        // Down is only done once it's let go, up was opened as it started and is closed again if it goes back.
        if (carry && started.back == null) started.go()
        if (!carry) started.back?.invoke()
        carryOn = carry
    }

    /** A fling lifts the finger too: how much of it a swipe under way takes, all of it, or none. */
    fun flung(velocity: Float): Float {
        if (!swiping) return 0f
        release(velocity)
        return velocity
    }

    /** The screens are where they're going, so the swipe is over. */
    fun finish() {
        layer = null
        to = null
        fraction = 0f
        carryOn = null
    }

    private companion object {
        /** A swipe let go this far along carries on. */
        const val COMMIT = 0.3f
    }
}

internal val LocalLayerSwipe = compositionLocalOf { LayerSwipe() }

/** A swipe up or down moves a layer: the player, the words, and back. A tap or a slide sideways is left to what's under it. */
@Composable
internal fun Modifier.swipeLayers(): Modifier {
    val swipe = LocalLayerSwipe.current
    return pointerInput(swipe) {
        detectVerticalDragGestures(
            onDragEnd = { swipe.release() },
            onDragCancel = { swipe.release(0f) }
        ) { change, amount -> swipe.drag(amount, change.uptimeMillis) }
    }
}
