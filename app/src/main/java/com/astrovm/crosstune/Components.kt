package com.astrovm.crosstune

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.draw.alpha
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal const val ARTWORK_TAG = "artwork"
internal const val LOGO_TAG = "logo"

/** Widest the content gets on tablets and in landscape, so lines stay easy to read. */
internal val ContentMaxWidth = 600.dp

@Composable
internal fun AppIcon(@DrawableRes id: Int, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(id), contentDescription = contentDescription, modifier = modifier, tint = tint)
}

/**
 * Casting the song to a TV: plain while it can be, lit with the TV's name for a description while it is. Either way a tap
 * is the same one, as there is one TV to choose or one to look after.
 */
@Composable
internal fun CastButton(state: UiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val to = state.castingTo
    IconButton(onClick = onClick, modifier = modifier) {
        AppIcon(
            if (to != null) R.drawable.ic_cast_connected else R.drawable.ic_cast,
            contentDescription = castDescription(to),
            tint = if (to != null) MaterialTheme.colorScheme.primary else LocalContentColor.current
        )
    }
}

@Composable
internal fun castDescription(to: String?) = if (to != null) stringResource(R.string.casting_to_tv, to) else stringResource(R.string.cast_to_tv)

/** That the phone can't reach the internet, in the error color: a state, not a button. */
@Composable
internal fun Offline(modifier: Modifier = Modifier, iconSize: Dp = 24.dp) {
    Box(modifier, contentAlignment = Alignment.Center) {
        AppIcon(R.drawable.ic_cloud_off, contentDescription = stringResource(R.string.offline), modifier = Modifier.size(iconSize), tint = MaterialTheme.colorScheme.error)
    }
}

/**
 * The launcher icon's note and arrow without its badge, tinted from the theme so they read on both
 * light and dark backgrounds. (Compose can't paint the adaptive icon itself.)
 */
@Composable
internal fun AppLogo(modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Box(modifier = modifier.testTag(LOGO_TAG).size(size)) {
        Image(
            painterResource(R.drawable.ic_logo_note),
            contentDescription = null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxSize()
        )
        Image(
            painterResource(R.drawable.ic_logo_arrow),
            contentDescription = null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** Small heading above a group, optionally with an explanation and a trailing action. */
@Composable
internal fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    action: @Composable (() -> Unit)? = null
) {
    Column(modifier = modifier.padding(top = 28.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            action?.invoke()
        }
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * Rounded container that groups related rows, separated by hairline dividers. It grows and shrinks
 * smoothly as rows come and go, so nothing jumps.
 */
@Composable
internal fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().animateContentSize(Motion.size),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(content = content)
    }
}

@Composable
internal fun GroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    )
}

/** Label with an optional supporting line and a switch; the whole row toggles. */
@Composable
internal fun SettingSwitch(
    label: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Covers, and their colors, already worked out, so one shown again is there at once rather than fading in. */
internal interface Covers {
    fun cover(url: String): ImageBitmap?
    fun color(url: String): Color?
    fun keepColor(url: String, color: Color)
}

internal val LocalCovers = staticCompositionLocalOf<Covers?> { null }

/**
 * Square cover that fades in once loaded, or is there at once when it was. Until then, or when
 * there's none, a tile with a music note keeps the layout steady.
 */
@Composable
internal fun CoverArt(url: String?, load: suspend (String) -> ImageBitmap?, size: Dp, modifier: Modifier = Modifier) {
    val covers = LocalCovers.current
    val image by produceState(initialValue = url?.let { covers?.cover(it) }, url) { value = url?.let { load(it) } }
    val alpha by animateFloatAsState(targetValue = if (image != null) 1f else 0f, label = "cover")
    Surface(
        modifier = modifier.size(size),
        shape = if (size >= 72.dp) MaterialTheme.shapes.medium else MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painterResource(R.drawable.ic_music_note),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(size * 0.4f)
                    .alpha(1f - alpha)
            )
            image?.let {
                // Decorative: the title and artist sit right beside it.
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(alpha)
                        .testTag(ARTWORK_TAG)
                )
            }
        }
    }
}

/**
 * The color a cover leans to, for the glow behind it and the lyrics' backdrop: its average,
 * brightened so a dark cover still tints, or null until it's loaded or when there's none.
 */
@Composable
internal fun coverColor(url: String?, load: suspend (String) -> ImageBitmap?): Color? {
    val covers = LocalCovers.current
    val color by produceState(initialValue = url?.let { covers?.color(it) }, url) {
        value = url?.let { cover ->
            load(cover)?.let { image ->
                // Off the main thread: scaling a whole cover down takes a frame or two.
                val pixel = withContext(Dispatchers.Default) { Bitmap.createScaledBitmap(image.asAndroidBitmap(), 1, 1, true).getPixel(0, 0) }
                val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(pixel, it) }
                Color.hsv(hsv[0], hsv[1].coerceIn(0.35f, 0.9f), hsv[2].coerceIn(0.55f, 0.95f))
            }?.also { covers?.keepColor(cover, it) }
        }
    }
    return color
}

/** Opens a web page in the browser; with none to open it, says so rather than crashing. */
@Composable
internal fun rememberWebOpener(): (String) -> Unit {
    val handler = LocalUriHandler.current
    val context = LocalContext.current
    val noApp = stringResource(R.string.error_no_app_to_open)
    return remember(handler, context, noApp) {
        { url ->
            try {
                handler.openUri(url)
            } catch (_: IllegalArgumentException) {
                // What Compose throws when no app can open it, in place of ActivityNotFoundException.
                Toast.makeText(context, noApp, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/** A button for something Shizuku may fix in one tap, with a spinner beside its [label] while it's [fixing]. */
@Composable
internal fun OneTapButton(label: String, onClick: () -> Unit, fixing: Boolean) {
    TextButton(onClick = onClick, enabled = !fixing) {
        if (fixing) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.padding(end = ButtonDefaults.IconSpacing).size(ButtonDefaults.IconSize)
            )
        }
        Text(label)
    }
}

/**
 * The app's slider: a thin track with a round knob, which grows while it's held, as music apps have
 * it; without the dot Material marks the track's end with, or one for each step.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val held = remember { MutableInteractionSource() }
    val pressed by held.collectIsPressedAsState()
    val dragged by held.collectIsDraggedAsState()
    val knob by animateDpAsState(if (pressed || dragged) 20.dp else 14.dp, label = "knob")
    val colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f))
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        interactionSource = held,
        steps = steps,
        valueRange = valueRange,
        thumb = { Box(Modifier.size(knob).background(if (enabled) colors.thumbColor else colors.disabledThumbColor, CircleShape)) },
        track = { slider ->
            SliderDefaults.Track(
                sliderState = slider,
                modifier = Modifier.height(4.dp),
                enabled = enabled,
                colors = colors,
                drawStopIndicator = null,
                drawTick = { _, _ -> },
                thumbTrackGapSize = 0.dp,
                trackInsideCornerSize = 2.dp
            )
        }
    )
}

/**
 * A slider a TV remote passes through: left and right slide it, up and down move on as from
 * anything else, where the slider would take them too, and a ring says the remote is on it.
 */
@Composable
internal fun Modifier.slidesSideways(): Modifier {
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.hasFocus }
        .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small) else Modifier)
        .onPreviewKeyEvent { event ->
            val direction = when (event.key) {
                Key.DirectionUp -> FocusDirection.Up
                Key.DirectionDown -> FocusDirection.Down
                else -> return@onPreviewKeyEvent false
            }
            if (event.type == KeyEventType.KeyDown) focus.moveFocus(direction)
            true
        }
}
