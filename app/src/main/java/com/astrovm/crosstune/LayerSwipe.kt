package com.astrovm.crosstune

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.core.ExperimentalTransitionApi
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.CancellationException

/**
 * A screen a swipe reaches: [go] gets there, and [back], for one that's opened as the finger starts,
 * undoes it; [settled] is what's done only once it's let go there.
 */
internal class Layer(val to: Screen, val go: () -> Unit, val back: (() -> Unit)? = null, val settled: (() -> Unit)? = null)

/**
 * A swipe between screens under way: up or down between layers (the bar, the player, the words), or
 * back from the side of the screen. The screens follow the finger by [fraction], from the one shown
 * to [to], then carry on or go back once it lifts.
 */
@Stable
internal class LayerSwipe {
    /** Where a swipe up or down from the screen shown leads, if anywhere. */
    var up: Layer? = null
    var down: Layer? = null
    /** Where Back from the screen shown goes, when that's known, for a swipe back to follow the finger there. */
    var backTo: Screen? = null
    /** How far a full swipe is, and how quick a flick is to count for one, in pixels. */
    var distance = 1f
    var flick = 1f

    /** The screen being swiped to, null while no swipe is under way. */
    var to by mutableStateOf<Screen?>(null)
        private set
    /** How far along that is, 0 to 1. */
    var fraction by mutableFloatStateOf(0f)
        private set
    /** Whether the swipe under way is one back, from the side of the screen. */
    var backing by mutableStateOf(false)
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
    fun drag(amount: Float, time: Long): Float {
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
        if (carry) started.settled?.invoke()
        if (!carry) started.back?.invoke()
        carryOn = carry
    }

    /** A fling lifts the finger too: how much of it a swipe under way takes, all of it, or none. */
    fun flung(velocity: Float): Float {
        if (!swiping) return 0f
        release(velocity)
        return velocity
    }

    /**
     * A swipe back, from the side of the screen, starts; whether the screens can follow it, as where
     * Back goes is known and nothing else is moving them.
     */
    fun backStart(): Boolean {
        val going = backTo
        if (going == null || to != null) return false
        // Back is only done once it's let go, so there's nothing to do on the way.
        layer = Layer(going, go = {})
        upward = false
        backing = true
        to = going
        return true
    }

    /** The swipe back, [progress] of the way, 0 to 1. */
    fun backBy(progress: Float) {
        if (carryOn == null) fraction = progress
    }

    /** The swipe back let go, done, with [back] going back, or taken back. */
    fun backDone(done: Boolean, back: () -> Unit) {
        if (done) back()
        carryOn = done
    }

    /** The screens are where they're going, so the swipe is over. */
    fun finish() {
        layer = null
        to = null
        fraction = 0f
        carryOn = null
        backing = false
    }

    private companion object {
        /** A swipe let go this far along carries on. */
        const val COMMIT = 0.3f
    }
}

internal val LocalLayerSwipe = compositionLocalOf { LayerSwipe() }

/**
 * Back, which a swipe from the side of the screen follows as it goes: the screen it goes back to
 * comes as far as the swipe has, then [onBack] once it's let go, or it's taken back. A press of Back,
 * or Back from a screen whose way back isn't known, only goes back.
 */
@Composable
internal fun SwipedBack(enabled: Boolean = true, onBack: () -> Unit) {
    val swipe = LocalLayerSwipe.current
    val back by rememberUpdatedState(onBack)
    PredictiveBackHandler(enabled) { progress ->
        val following = swipe.backStart()
        try {
            progress.collect { if (following) swipe.backBy(it.progress) }
            if (following) swipe.backDone(done = true, back) else back()
        } catch (e: CancellationException) {
            if (following) swipe.backDone(done = false, back)
            throw e
        }
    }
}

/**
 * Pages, [shown] the one in view: a new one slides in over the one it opens from, [forward] when it's
 * deeper, and back out the other way. A swipe back drags it off towards [back] as far as it's gone,
 * then [onBack] once it's let go; a press of Back only goes back.
 */
@OptIn(ExperimentalTransitionApi::class)
@Composable
internal fun <S> SwipedBackPages(
    shown: S,
    back: S,
    label: String,
    forward: (from: S, to: S) -> Boolean,
    onBack: () -> Unit,
    content: @Composable AnimatedContentScope.(S) -> Unit
) {
    val moving = remember { SeekableTransitionState(shown) }
    var swiped by remember { mutableStateOf<Float?>(null) }
    PredictiveBackHandler(enabled = shown != back) { swipe ->
        try {
            swipe.collect { swiped = it.progress }
            swiped = null
            onBack()
        } catch (e: CancellationException) {
            swiped = null
            throw e
        }
    }
    LaunchedEffect(shown, swiped) {
        val far = swiped
        if (far != null) moving.seekTo(far, back) else moving.animateTo(shown, Motion.rise)
    }
    rememberTransition(moving, label = label).AnimatedContent(
        transitionSpec = { if (swiped != null) slideAway() else slideAlong(forward(initialState, targetState)) },
        content = content
    )
}

/** How far below the status bar a swipe down has to start to move a layer, about as far as a screen's header goes. */
private val TOP_ZONE = 96.dp

/**
 * A swipe up or down moves a layer: the player, the words, and back. A tap or a slide sideways is left to what's under it.
 * With [downFromTop], a swipe down only counts when it starts at the top of the screen, so scrolling or dragging anything below isn't taken for one.
 */
@Composable
internal fun Modifier.swipeLayers(downFromTop: Boolean = false): Modifier {
    val swipe = LocalLayerSwipe.current
    val density = LocalDensity.current
    val zone = WindowInsets.statusBars.getTop(density) + with(density) { TOP_ZONE.toPx() }
    // Where the finger came down is only known as it does, before the drag that follows.
    val atTop = remember { BooleanArray(1) }
    val watching = if (downFromTop) {
        pointerInput(zone) {
            awaitEachGesture { atTop[0] = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position.y <= zone }
        }
    } else {
        this
    }
    return watching.pointerInput(swipe) {
        detectVerticalDragGestures(
            onDragEnd = { swipe.release() },
            onDragCancel = { swipe.release(0f) }
        ) { change, amount ->
            if (downFromTop && amount > 0f && !swipe.swiping && !atTop[0]) return@detectVerticalDragGestures
            swipe.drag(amount, change.uptimeMillis)
        }
    }
}
