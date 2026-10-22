package com.astrovm.crosstune

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal const val ANDROID_PICTURE_TAG = "android_picture"

/**
 * Small drawings of Android's settings pages, as each kind of phone shows them, with what to tap
 * ringed in red. Drawn rather than screenshots, they read in the app's language and theme. The
 * step's words say the same for TalkBack, so the drawing itself is left out.
 */
@Composable
internal fun AndroidPage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .widthIn(max = 320.dp)
            .fillMaxWidth()
            .clearAndSetSemantics { testTag = ANDROID_PICTURE_TAG }
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp), content = content)
    }
}

/** What a row of a settings page has to tap: a radio button, a box to tick, a switch, or a way on. */
internal enum class Control { NONE, RADIO, RADIO_ON, BOX, CIRCLE, TICK, SWITCH, SWITCH_ON, CHEVRON, PLUS, MENU }

/** The red ring around what to tap, the same red on every phone. */
private val RING = Color(0xFFF95054)

/** Rings what's inside, or not, so a ringed row lines up with the rest. */
@Composable
internal fun Ringed(ringed: Boolean, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .then(if (ringed) Modifier.border(2.5.dp, RING, RoundedCornerShape(14.dp)) else Modifier)
    ) { content() }
}

/**
 * One row of a settings page: its [label] and its [control], before the label as Pixel and Xiaomi
 * put radio buttons and ticks, after it as switches go.
 */
@Composable
internal fun AndroidRow(
    label: String,
    control: Control = Control.NONE,
    ringed: Boolean = false,
    leading: (@Composable () -> Unit)? = null
) = Ringed(ringed) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val first = control in LEADING
        if (first) ControlFor(control)
        leading?.invoke()
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!first) ControlFor(control)
    }
}

/** The controls Android puts before a row's label. */
private val LEADING = setOf(Control.RADIO, Control.RADIO_ON, Control.BOX, Control.CIRCLE, Control.TICK, Control.PLUS)

@Composable
private fun ControlFor(control: Control) {
    if (control == Control.RADIO || control == Control.RADIO_ON) {
        RadioButton(selected = control == Control.RADIO_ON, onClick = null)
    } else if (control == Control.BOX) {
        Checkbox(checked = false, onCheckedChange = null)
    } else if (control == Control.CIRCLE) {
        // Xiaomi ticks a link with a filled circle; one not ticked is just its outline.
        Box(Modifier.size(20.dp).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape))
    } else if (control == Control.TICK) {
        AppIcon(R.drawable.ic_check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    } else if (control == Control.SWITCH || control == Control.SWITCH_ON) {
        Switch(checked = control == Control.SWITCH_ON, onCheckedChange = null)
    } else if (control == Control.CHEVRON) {
        AppIcon(R.drawable.ic_chevron_right, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (control == Control.PLUS) {
        Text("+", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    } else if (control == Control.MENU) {
        AppIcon(R.drawable.ic_more_vert, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
    }
}

/** A small heading over a page's rows, like "Links to open in this app". */
@Composable
internal fun AndroidHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 2.dp)
    )
}

/** A dialog's buttons side by side, as Android lays them out, the one to tap ringed. */
@Composable
internal fun AndroidButtons(vararg labels: String, ringed: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End
    ) {
        labels.forEach { label ->
            Ringed(label == ringed) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
    }
}

/** Crosstune's icon as Android lists it beside its name. */
@Composable
internal fun AndroidAppIcon() {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(32.dp)) {
        Box(contentAlignment = Alignment.Center) { AppLogo(size = 20.dp) }
    }
}

/** The pictures for each step, by what's needed and the phone it's on. */
internal object AndroidPictures {
    /** The choice between the app and the browser, the one to pick ringed, the other still set. */
    @Composable
    fun InTheAppOrBrowser(phone: Phone, inTheApp: Boolean) = AndroidPage {
        val app = stringResource(R.string.android_in_the_app)
        val browser = stringResource(R.string.android_in_your_browser)
        // Xiaomi ticks the one set; Pixel and the rest use radio buttons.
        val set = if (phone == Phone.XIAOMI) Control.TICK else Control.RADIO_ON
        val unset = if (phone == Phone.XIAOMI) Control.NONE else Control.RADIO
        AndroidRow(app, if (inTheApp) unset else set, ringed = inTheApp)
        AndroidRow(browser, if (inTheApp) set else unset, ringed = !inTheApp)
    }

    /** Pixel's Add link, under the links to open in the app, none of them yet. */
    @Composable
    fun AddLink() = AndroidPage {
        AndroidHeading(stringResource(R.string.android_links_to_open))
        AndroidRow(stringResource(R.string.android_add_link), Control.PLUS, ringed = true)
    }

    /** The links to tick, a few of [hosts], ringed together, and on Pixel the dialog's Add. */
    @Composable
    fun TickLinks(phone: Phone, hosts: List<String>) = AndroidPage {
        if (phone == Phone.XIAOMI) AndroidHeading(stringResource(R.string.android_links_to_open))
        Ringed(true) {
            Column {
                // Samsung switches each link on, Xiaomi ticks a circle, and Pixel a box in a dialog.
                val control = if (phone == Phone.SAMSUNG) Control.SWITCH else if (phone == Phone.XIAOMI) Control.CIRCLE else Control.BOX
                hosts.take(3).forEach { host -> AndroidRow(host, control) }
            }
        }
        if (phone == Phone.PIXEL) {
            AndroidButtons(stringResource(R.string.cancel_button), stringResource(R.string.android_add), ringed = stringResource(R.string.android_add))
        }
    }

    /** Samsung's page for an app's links: the switch, and the links under it. */
    @Composable
    fun SamsungLinks(switchOn: Boolean, ringSwitch: Boolean) = AndroidPage {
        AndroidRow(stringResource(R.string.android_open_supported_links), if (switchOn) Control.SWITCH_ON else Control.SWITCH, ringed = ringSwitch)
        AndroidRow(stringResource(R.string.android_supported_web_addresses), ringed = !ringSwitch)
    }

    /** The switch for an app's notification access, on its own page or, on Samsung, in the list. */
    @Composable
    fun AccessSwitch(phone: Phone) = AndroidPage {
        if (phone == Phone.SAMSUNG) {
            AndroidRow(stringResource(R.string.app_name), Control.SWITCH, ringed = true, leading = { AndroidAppIcon() })
        } else {
            AndroidRow(stringResource(R.string.android_allow_notification_access), Control.SWITCH, ringed = true)
        }
    }

    /** The question Android asks once the access is turned on, its Allow ringed. */
    @Composable
    fun AllowDialog() = AndroidPage {
        AndroidButtons(stringResource(R.string.android_deny), stringResource(R.string.allow_button), ringed = stringResource(R.string.allow_button))
    }

    /** Android turning restricted access down for an app from a file, its Close ringed. */
    @Composable
    fun DeniedDialog() = AndroidPage {
        AndroidRow(stringResource(R.string.android_restricted_setting))
        AndroidButtons(stringResource(R.string.android_close), ringed = stringResource(R.string.android_close))
    }

    /** App info's ⋮, and Allow restricted settings in its menu, both ringed. */
    @Composable
    fun RestrictedMenu() = AndroidPage {
        AndroidRow(stringResource(R.string.follow_help_app_info), Control.MENU)
        AndroidRow(stringResource(R.string.android_allow_restricted), ringed = true)
    }

    /** App info's row for permissions, which Xiaomi calls App permissions. */
    @Composable
    fun PermissionsRow(phone: Phone) = AndroidPage {
        AndroidRow(stringResource(if (phone == Phone.XIAOMI) R.string.android_app_permissions else R.string.android_permissions), Control.CHEVRON, ringed = true)
    }

    /** The microphone among the permissions. */
    @Composable
    fun MicrophoneRow() = AndroidPage {
        AndroidRow(stringResource(R.string.android_microphone), Control.CHEVRON, ringed = true)
    }

    /** The microphone's choices, Allow only while using the app ringed, Don't allow still set. */
    @Composable
    fun MicrophoneChoices() = AndroidPage {
        AndroidRow(stringResource(R.string.android_allow_while_using), Control.RADIO, ringed = true)
        AndroidRow(stringResource(R.string.android_ask_every_time), Control.RADIO)
        AndroidRow(stringResource(R.string.android_dont_allow), Control.RADIO_ON)
    }

    /** Android's own question about the microphone, While using the app ringed. */
    @Composable
    fun MicrophoneQuestion() = AndroidPage {
        Ringed(true) {
            Text(
                stringResource(R.string.android_while_using),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            )
        }
        AndroidRow(stringResource(R.string.android_only_this_time))
        AndroidRow(stringResource(R.string.android_dont_allow))
    }

    /** Crosstune in the list of apps that can show over others, which Samsung switches right there. */
    @Composable
    fun OverlayRow(phone: Phone) = AndroidPage {
        AndroidRow(
            stringResource(R.string.app_name),
            if (phone == Phone.SAMSUNG) Control.SWITCH else Control.CHEVRON,
            ringed = true,
            leading = { AndroidAppIcon() }
        )
    }

    /** The switch on Crosstune's own page for showing over other apps. */
    @Composable
    fun OverlaySwitch() = AndroidPage {
        AndroidRow(stringResource(R.string.android_display_over_apps), Control.SWITCH, ringed = true)
    }
}

/** Room between two pictures of one step. */
@Composable
internal fun PictureGap() = Spacer(Modifier.size(10.dp))
