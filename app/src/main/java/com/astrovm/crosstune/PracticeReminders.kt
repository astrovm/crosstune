package com.astrovm.crosstune

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Whether due cards are asked back once a day, as the bell shows it. */
internal data class Reminder(
    /** The time of day it goes, in minutes from midnight; null until a first session ends, which sets it. */
    val minute: Int? = null,
    val on: Boolean = false,
    /** Whether Android lets it show, for the app and for its channel. */
    val allowed: Boolean = true,
    /** Turned off by hand, so the end of a session stops offering it. */
    val turnedOff: Boolean = false,
    /** Whether Android lets the alarm go at the exact minute: from Android 12 the user allows that in its settings. */
    val exact: Boolean = true
) {
    /** On, but Android won't show it, which needs saying. */
    val blocked get() = on && !allowed

    /** Showing as it should: tapped, it's the time or off. */
    val working get() = on && allowed

    /** On, but only on the inexact alarm, which Android may hold up to an hour. */
    val late get() = on && !exact
}

/** The time of day reminders are set for, when they can be: after a first session, and not on a TV. */
internal val UiState.reminderMinute: Int? get() = reminder.minute?.takeIf { phone != Phone.TV }

/** The moment after [now] the clock on [zone]'s wall next says [minute], so it stays that hour across time zones and clock changes. */
internal fun nextReminderAt(minute: Int, now: Long, zone: ZoneId): Long {
    val today = Instant.ofEpochMilli(now).atZone(zone)
    val at = today.toLocalDate().atTime(LocalTime.of(minute / 60, minute % 60)).atZone(zone)
    return (if (at.isAfter(today)) at else at.plusDays(1)).toInstant().toEpochMilli()
}

/** What the clock on [zone]'s wall says at [at], in minutes from midnight. */
internal fun minuteOfDay(at: Long, zone: ZoneId): Int = Instant.ofEpochMilli(at).atZone(zone).run { hour * 60 + minute }

/**
 * Whether a session done at [doneAt] counts for [now]: the same day, or, at either side of midnight,
 * only hours ago. Done a day ago, just after yesterday's reminder, it doesn't, so one a day goes on.
 */
internal fun practisedLately(doneAt: Long, now: Long, zone: ZoneId): Boolean =
    now - doneAt < RESTED_AFTER_MS || Instant.ofEpochMilli(doneAt).atZone(zone).toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

/** What a reminder says: [title] says how many are due, and this which songs they're from, the song due longest first. */
internal fun reminderSongs(due: List<SavedLine>, named: (first: String, others: Int) -> String): String? {
    val first = due.firstOrNull()?.title ?: return null
    val others = due.distinctBy { it.title to it.artist }.size - 1
    return if (others == 0) first else named(first, others)
}

private const val RESTED_AFTER_MS = 6 * 60 * 60 * 1000L

/**
 * Asks due cards back once a day, at about the time the first session ended, the phone counting them
 * itself: nothing leaves it. Nothing due, or a session done that day, it says nothing, and after
 * [IGNORED_ENOUGH] in a row with no answer it stops until the next time cards are practised.
 *
 * An exact alarm that fires in Doze, when the user has allowed alarms and reminders for the app:
 * Samsung's freezer holds an inexact one for as long as it holds the app, which no window shortens.
 * Not allowed, an inexact one still fires in Doze, up to an hour late, which [Reminder.late] says.
 * Android forgets alarms on a reboot or a force stop, so they're set again then, and when the clock
 * or time zone changes, so it stays the same hour on the wall, and when the permission changes.
 */
internal class PracticeReminders(
    base: Context,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault
) {
    private val context = base.applicationContext
    private val preferences = context.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val alarms = context.getSystemService(AlarmManager::class.java)

    private val alarm
        get() = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_REMIND).setClass(context, PracticeReminderReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private val on get() = preferences.getBoolean(KEY_ON, false)
    private val minute get() = preferences.getInt(KEY_MINUTE, -1).takeIf { it >= 0 }
    private val ignored get() = preferences.getInt(KEY_IGNORED, 0)

    private val allowed get() = notifications.areNotificationsEnabled() && notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    /** Before Android 12 exact alarms need nothing asked; from it, the user allows them. */
    private val exact get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    val state get() = Reminder(minute, on, allowed, preferences.getBoolean(KEY_TURNED_OFF, false), exact)

    /** Android's page where alarms and reminders are allowed for the app, which only the user can do. */
    val exactPage: Intent get() = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))

    /** Android's page where they're allowed: the channel's, when it's only that that's off. */
    val settingsPage: Intent
        get() = if (notifications.areNotificationsEnabled()) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL)
        } else {
            // Naming the channel here too opens its page, where the switch can't undo the app's being off.
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }

    fun turn(on: Boolean) {
        preferences.edit {
            putBoolean(KEY_ON, on)
            putBoolean(KEY_TURNED_OFF, !on)
            // Turned on again, it starts afresh, whatever was ignored before.
            putInt(KEY_IGNORED, 0)
        }
        if (on) makeChannel(AppLanguage.words(context)) else clear()
        arm()
    }

    /** From now on, at [minute] on the wall. */
    fun at(minute: Int) {
        preferences.edit { putInt(KEY_MINUTE, minute) }
        arm()
    }

    /** A card was practised, or a reminder tapped: it's what they're for, so they start over, and one showing is out of date. */
    fun practised() {
        preferences.edit { putInt(KEY_IGNORED, 0) }
        clear()
        arm()
    }

    /** A session ended, the first of which sets the time of day from now on. */
    fun sessionDone() {
        preferences.edit {
            putLong(KEY_DONE_AT, now())
            if (minute == null) putInt(KEY_MINUTE, minuteOfDay(now(), zone()))
        }
        practised()
    }

    /** Takes back a reminder showing, whose cards are no longer due. */
    fun clear() = notifications.cancel(NOTIFICATION)

    /** Sets the next reminder, or, off or ignored, none, so the phone isn't woken for nothing. */
    fun arm() {
        val minute = minute
        if (on && minute != null && ignored < IGNORED_ENOUGH) {
            val at = nextReminderAt(minute, now(), zone())
            if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm) else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm)
        } else {
            alarms.cancel(alarm)
        }
    }

    /** The alarm went: says what's due, unless there's no reason to, and sets tomorrow's. */
    fun remind() {
        val at = now()
        val types = loadCardTypes(preferences)
        val due = sessionOf(SavedLinesStore(preferences).load(), at, max = Int.MAX_VALUE, types = types)
        if (due.isNotEmpty() && mayAsk(at)) {
            post(due)
            preferences.edit { putInt(KEY_IGNORED, ignored + 1) }
        }
        arm()
    }

    /** Wanted, shown by Android, and not already answered for today by a session. */
    private fun mayAsk(at: Long) = on && allowed && !practisedLately(preferences.getLong(KEY_DONE_AT, 0), at, zone())

    private fun post(due: List<SavedLine>) {
        // The alarm has no screen, so Android hasn't put the app's language on it.
        val words = AppLanguage.words(context)
        makeChannel(words)
        val title = words.resources.getQuantityString(R.plurals.practice_reminder_title, due.size, due.size)
        val songs = reminderSongs(due) { first, others -> words.getString(R.string.practice_reminder_songs, first, others) }
        val open = PendingIntent.getActivity(
            context, 0, Intent(MainActivity.ACTION_PRACTISE).setClassName(context, MainActivity.PRACTISE_ALIAS), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Over a locked phone only the count shows, not what's being listened to.
        val locked = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_school).setContentTitle(title).setContentIntent(open).setAutoCancel(true).build()
        notifications.notify(
            NOTIFICATION,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_school)
                .setContentTitle(title)
                .setContentText(songs)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(locked)
                .build()
        )
    }

    /** Named in the app's language, which updates it when it's already there. */
    private fun makeChannel(words: Context) = notifications.createNotificationChannel(
        NotificationChannel(CHANNEL, words.getString(R.string.practice_reminders), NotificationManager.IMPORTANCE_DEFAULT)
    )

    companion object {
        const val ACTION_REMIND = "com.astrovm.crosstune.action.REMIND_PRACTICE"

        /** How many go unanswered in a row before they stop. */
        const val IGNORED_ENOUGH = 3

        const val CHANNEL = "practice_reminders"
        // Its own number: 1, 3 and 4 are the lyrics, sign-in and capture services' foreground notifications.
        const val NOTIFICATION = 5

        private const val KEY_ON = "practice_reminder_on"
        private const val KEY_MINUTE = "practice_reminder_minute"
        private const val KEY_IGNORED = "practice_reminder_ignored"
        private const val KEY_TURNED_OFF = "practice_reminder_turned_off"
        private const val KEY_DONE_AT = "practice_reminder_done_at"
    }
}

/** The day's alarm, and whatever makes Android forget it, or its hour: a reboot, a new time zone or clock. */
class PracticeReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminders = PracticeReminders(context)
        if (intent.action == PracticeReminders.ACTION_REMIND) reminders.remind() else reminders.arm()
    }
}
