package com.astrovm.crosstune

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
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
internal fun AppIcon(@DrawableRes id: Int, contentDescription: String?, modifier: Modifier = Modifier) {
    Icon(painterResource(id), contentDescription = contentDescription, modifier = modifier)
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
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
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
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(content = content)
    }
}

@Composable
internal fun GroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
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

/**
 * Square cover that fades in once loaded. Until then, or when there's none, a tile with a
 * music note keeps the layout steady.
 */
@Composable
internal fun CoverArt(url: String?, load: suspend (String) -> ImageBitmap?, size: Dp, modifier: Modifier = Modifier) {
    val image by produceState<ImageBitmap?>(initialValue = null, url) { value = url?.let { load(it) } }
    val alpha by animateFloatAsState(targetValue = if (image != null) 1f else 0f, label = "cover")
    Surface(
        modifier = modifier.size(size),
        shape = if (size >= 72.dp) MaterialTheme.shapes.medium else MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
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
