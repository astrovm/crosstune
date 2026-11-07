package com.astrovm.crosstune

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Calendar

/** [minute], in minutes from midnight, as the phone writes the time of day. */
internal fun reminderTime(context: Context, minute: Int): String {
    val at = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, minute / 60)
        set(Calendar.MINUTE, minute % 60)
    }
    return DateFormat.getTimeFormat(context).format(at.time)
}

/**
 * The bell that asks for due cards to come back, once there's been a session to take its time from;
 * with its [labelled] words where there's room. Off, a tap turns it on, Android asking first if need
 * be. On, a tap is the time, or off. On but not allowed by Android, it shows that, and a tap goes to fix it.
 */
@Composable
internal fun ReminderBell(state: UiState, actions: ScreenActions, labelled: Boolean = false) {
    val minute = state.reminderMinute ?: return
    val reminder = state.reminder
    var picking by rememberSaveable { mutableStateOf(false) }
    val words = when {
        reminder.blocked -> stringResource(R.string.practice_reminder_blocked)
        reminder.on -> stringResource(R.string.practice_reminder_at, reminderTime(LocalContext.current, minute))
        else -> stringResource(R.string.practice_remind_me)
    }
    val icon = when {
        reminder.blocked -> R.drawable.ic_notifications_off
        reminder.on -> R.drawable.ic_notifications_on
        else -> R.drawable.ic_notifications
    }
    val tint = when {
        reminder.blocked -> MaterialTheme.colorScheme.error
        reminder.on -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val onClick = { if (reminder.working) picking = true else actions.onReminderChange(true) }
    if (labelled) {
        TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = tint)) {
            AppIcon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(words)
        }
    } else {
        IconButton(onClick = onClick) { AppIcon(icon, contentDescription = words, tint = tint) }
    }
    if (picking) ReminderTimeDialog(minute, onPick = actions.onReminderTimeChange, onOff = { actions.onReminderChange(false) }, onDismiss = { picking = false })
}

/** The time a reminder goes, or off. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ReminderTimeDialog(minute: Int, onPick: (Int) -> Unit, onOff: () -> Unit, onDismiss: () -> Unit) {
    val time = rememberTimePickerState(minute / 60, minute % 60, is24Hour = DateFormat.is24HourFormat(LocalContext.current))
    ScreenAlert(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onPick(time.hour * 60 + time.minute)
                onDismiss()
            }) { Text(stringResource(R.string.save_button)) }
        },
        dismissButton = {
            TextButton(onClick = {
                onOff()
                onDismiss()
            }) { Text(stringResource(R.string.player_sleep_off)) }
        },
        title = { Text(stringResource(R.string.practice_remind_me)) },
        // Scrolls where a big font or a turned phone leaves it no room.
        text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) { TimePicker(time) } }
    )
}
