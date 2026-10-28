package com.astrovm.crosstune

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The app's motion, in one place so everything moves alike: springs for movement, so a change
 * mid-way carries on from where it is instead of jumping, and short fades. Android's animation
 * speed setting, "Remove animations" included, applies to all of it.
 */
internal object Motion {
    /** Movement and size: quick, settling without a wobble. */
    val offset: FiniteAnimationSpec<IntOffset> = spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset(1, 1))
    val size: FiniteAnimationSpec<IntSize> = spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntSize(1, 1))
    /** How a screen rises or sinks once the finger is off it, or it was a tap: it carries on from where it is. */
    val rise: FiniteAnimationSpec<Float> = spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 0.001f)
    /** Something on a screen going once a swipe back's let go, or coming back: quicker than a screen, as it's smaller. */
    val away: FiniteAnimationSpec<Float> = spring(stiffness = Spring.StiffnessMedium, visibilityThreshold = 0.001f)
    /** What comes in fades in a touch slower than what goes, so the two don't blur together. */
    val fadeIn: FiniteAnimationSpec<Float> = tween(durationMillis = 220, delayMillis = 60)
    val fadeOut: FiniteAnimationSpec<Float> = tween(durationMillis = 120)

    /** Something new on the page: it fades in as its space opens. */
    val appear: EnterTransition = fadeIn(fadeIn) + expandVertically(size)
    val disappear: ExitTransition = fadeOut(fadeOut) + shrinkVertically(size)

    /** A result, cover or logo arriving: it grows in slightly as it fades in. */
    val pop: EnterTransition = fadeIn(fadeIn) + scaleIn(spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow), initialScale = 0.92f)
}

/** Going deeper (settings, a source, the next setup step) slides in from the end; back slides the other way. */
internal fun <S> AnimatedContentTransitionScope<S>.slide(forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    return (slideInHorizontally(Motion.offset) { width -> direction * width / 5 } + fadeIn(Motion.fadeIn)) togetherWith
        (slideOutHorizontally(Motion.offset) { width -> -direction * width / 5 } + fadeOut(Motion.fadeOut))
}

/**
 * A screen rising over the one below it, [up], or sinking back down to show it again. It moves the
 * whole way and evenly, so a swipe can hold it where the finger is; the rest is [Motion.rise].
 */
internal fun <S> AnimatedContentTransitionScope<S>.rise(up: Boolean): ContentTransform =
    if (up) {
        slideInVertically(RISE) { height -> height } togetherWith ExitTransition.KeepUntilTransitionsFinished
    } else {
        // What goes is the one on top, so the one coming back is under it.
        (EnterTransition.None togetherWith slideOutVertically(RISE) { height -> height }).apply { targetContentZIndex = -1f }
    }

private val RISE = tween<IntOffset>(durationMillis = 300, easing = LinearEasing)

/**
 * A screen going deeper, [forward], or back, as [slide], moving evenly so a swipe back can hold it
 * where the finger is; the rest is [Motion.rise]. What goes fades as it starts, what comes as it ends.
 */
internal fun <S> AnimatedContentTransitionScope<S>.slideAlong(forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    return (slideInHorizontally(RISE) { width -> direction * width / 5 } + fadeIn(ALONG_IN)) togetherWith
        (slideOutHorizontally(RISE) { width -> -direction * width / 5 } + fadeOut(ALONG_OUT))
}

/**
 * A screen going back as a swipe back has it: off to the side with the finger, over the one it goes
 * back to, which comes a little way out from under it.
 */
internal fun <S> AnimatedContentTransitionScope<S>.slideAway(): ContentTransform =
    (slideInHorizontally(RISE) { width -> -width / 5 } togetherWith slideOutHorizontally(RISE) { width -> width }).apply { targetContentZIndex = -1f }

private val ALONG_IN = tween<Float>(durationMillis = 200, delayMillis = 100, easing = LinearEasing)
private val ALONG_OUT = tween<Float>(durationMillis = 200, easing = LinearEasing)

/** One thing swapping for another in place, e.g. a new result for the last one, or nothing for something. */
internal fun <S> AnimatedContentTransitionScope<S>.swap(): ContentTransform =
    (Motion.pop togetherWith fadeOut(Motion.fadeOut)) using SizeTransform(clip = false) { _, _ -> Motion.size }

/** Something showing or going in place, with the space around it following smoothly. */
internal fun <S> AnimatedContentTransitionScope<S>.fade(): ContentTransform =
    (fadeIn(Motion.fadeIn) togetherWith fadeOut(Motion.fadeOut)) using SizeTransform(clip = true) { _, _ -> Motion.size }

/** A button's press feedback: the interaction source to hand the button, and the scale to apply to it. */
internal class Press(val source: MutableInteractionSource, val modifier: Modifier)

@Composable
internal fun rememberPress(): Press {
    val source = remember { MutableInteractionSource() }
    return Press(source, Modifier.pressScale(source))
}

/** Sinks a little while pressed and springs back, so a tap feels like it landed. */
@Composable
internal fun Modifier.pressScale(interactionSource: InteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press"
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** A dropdown's arrow, which turns over while its menu is open. */
@Composable
internal fun Modifier.flipWhen(open: Boolean): Modifier {
    val rotation by animateFloatAsState(if (open) 180f else 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "flip")
    return graphicsLayer { rotationZ = rotation }
}

/**
 * Rises and fades in when first shown, a beat after the one before it, so a list arrives in a
 * wave instead of all at once. Only the first few wait, so a long list never keeps anyone waiting.
 */
@Composable
internal fun Modifier.enterIn(index: Int): Modifier {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(minOf(index, 8) * 40L)
        shown.animateTo(1f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow))
    }
    return graphicsLayer {
        alpha = shown.value
        translationY = (1f - shown.value) * 24.dp.toPx()
    }
}
