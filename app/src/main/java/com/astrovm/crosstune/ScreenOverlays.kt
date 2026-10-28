package com.astrovm.crosstune

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle

/*
 * A dialog or a sheet is a window of its own, which Crosstune can only open from an activity. On a
 * TV the cover over another app is a window of a service's, with none, so there they're drawn
 * inside the screen instead, over what's under them: where the remote can reach them, and where
 * Back puts them away.
 */

/** What's drawn over the screen, the last on top; null where dialogs and sheets are windows. */
private val LocalOverlays = staticCompositionLocalOf<Overlays?> { null }

private class Overlays(val screen: FocusRequester) {
    val shown = mutableStateListOf<Overlay>()
}

/** One dialog or sheet shown, and what to give the remote back to when it's put away. */
private class Overlay(val content: State<@Composable () -> Unit>, val opener: FocusRequester) {
    val focus = FocusRequester()
}

/** Draws [content], the screen, with room over it for [ScreenAlert]s and [ScreenSheet]s, where [inside]. */
@Composable
internal fun WithOverlays(inside: Boolean, content: @Composable () -> Unit) {
    if (!inside) return content()
    val overlays = remember { Overlays(FocusRequester()) }
    CompositionLocalProvider(LocalOverlays provides overlays) {
        Box {
            Box(Modifier.focusRequester(overlays.screen).focusGroup()) { content() }
            overlays.shown.forEach { key(it) { OverlayFrame(it) } }
        }
    }
}

@Composable
private fun OverlayFrame(overlay: Overlay) {
    var focused by remember { mutableStateOf(false) }
    // With a remote, it starts in the first thing in it, unless something in it has already taken that.
    LaunchedEffect(Unit) {
        withFrameNanos {}
        if (!focused) overlay.focus.requestFocus()
    }
    // Once it's gone, the remote goes back to what opened it.
    DisposableEffect(overlay) {
        // Gone with the screen it was over, when it's that that put it away.
        onDispose { runCatching { overlay.opener.requestFocus() } }
    }
    Box(
        Modifier
            .fillMaxSize()
            // Said to be a dialog, as the window it stands in for is.
            .semantics { dialog() }
            .onFocusChanged { focused = it.hasFocus }
            .focusRequester(overlay.focus)
            .keepsTheRemote()
    ) { overlay.content.value() }
}

/**
 * The remote stays in what this is on, as what's under it is out of reach, until Back. Only moves it
 * makes are stopped: a focus asked for, as of what's opened from it, or given back, goes through.
 */
internal fun Modifier.keepsTheRemote(): Modifier = focusProperties {
    onExit = { if (requestedFocusDirection in MOVES) cancelFocusChange() }
}.focusGroup()

private val MOVES = setOf(FocusDirection.Up, FocusDirection.Down, FocusDirection.Left, FocusDirection.Right, FocusDirection.Next, FocusDirection.Previous)

/** Shows [content] in [overlays] for as long as this is in the composition. */
@Composable
private fun InOverlay(overlays: Overlays, opener: FocusRequester?, content: @Composable () -> Unit) {
    val latest = rememberUpdatedState(content)
    DisposableEffect(overlays) {
        val overlay = Overlay(latest, opener ?: overlays.shown.lastOrNull()?.focus ?: overlays.screen)
        overlays.shown.add(overlay)
        onDispose { overlays.shown.remove(overlay) }
    }
}

/** An [AlertDialog], or on a TV its place in the screen, which gives the remote back to [opener], what opened it, once it's put away. */
@Composable
internal fun ScreenAlert(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    opener: FocusRequester? = null
) {
    val overlays = LocalOverlays.current
    if (overlays == null) {
        AlertDialog(onDismissRequest = onDismissRequest, confirmButton = confirmButton, dismissButton = dismissButton, icon = icon, title = title, text = text)
    } else {
        InOverlay(overlays, opener) { InlineAlert(onDismissRequest, confirmButton, dismissButton, icon, title, text) }
    }
}

/** A [ModalBottomSheet], or on a TV its place in the screen, which gives the remote back to [opener] once it's put away. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScreenSheet(onDismiss: () -> Unit, opener: FocusRequester? = null, content: @Composable ColumnScope.() -> Unit) {
    val overlays = LocalOverlays.current
    if (overlays == null) {
        // Open all the way, so one back closes it rather than lowering it halfway first. Kept clear of the
        // status bar by hand: Material's own pads its top by however much of it the sheet is under, so one
        // about as tall as the screen grew as it was dragged up, which moved it again, and it shook.
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.statusBarsPadding(), content = content)
    } else {
        InOverlay(overlays, opener) { InlineSheet(onDismiss, content = content) }
    }
}

@Composable
private fun InlineAlert(
    onDismiss: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)?,
    icon: (@Composable () -> Unit)?,
    title: (@Composable () -> Unit)?,
    text: (@Composable () -> Unit)?
) {
    BackHandler(onBack = onDismiss)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        Scrim(onDismiss)
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            modifier = Modifier.padding(24.dp).widthIn(min = 280.dp, max = 560.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                icon?.let {
                    Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp)) {
                        CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.iconContentColor) { it() }
                    }
                }
                title?.let {
                    Box(Modifier.align(if (icon != null) Alignment.CenterHorizontally else Alignment.Start).padding(bottom = 16.dp)) {
                        CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.titleContentColor) {
                            ProvideTextStyle(MaterialTheme.typography.headlineSmall) { it() }
                        }
                    }
                }
                // Last to give room, so the buttons stay in sight over a long list.
                text?.let {
                    Box(Modifier.weight(1f, fill = false).padding(bottom = 24.dp)) {
                        CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.textContentColor) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium) { it() }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

/**
 * A sheet rising from the bottom over what's under it, which a tap beside it, or Back, puts away. The
 * player's up next is one too, as it's over the player rather than in a window of its own.
 */
@Composable
internal fun InlineSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onDismiss)
    Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.fillMaxSize()) {
        Scrim(onDismiss)
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = 48.dp)
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .keepsTheRemote()
        ) {
            Column(modifier = Modifier.navigationBarsPadding().padding(top = 24.dp), content = content)
        }
    }
}

/** A tap beside what's over the screen puts it away; the remote has Back for that, so it takes no focus. */
@Composable
private fun Scrim(onDismiss: () -> Unit) {
    Spacer(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
            .pointerInput(onDismiss) { detectTapGestures { onDismiss() } }
    )
}
