package com.astrovm.crosstune

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDateTime
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** When due cards are asked back, what's said, and when nothing is. */
@RunWith(RobolectricTestRunner::class)
class PracticeRemindersTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("America/Argentina/Buenos_Aires")
    private var clock = at(day = 2, hour = 20, minute = 5)
    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java))
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val shown get() = shadowOf(manager).allNotifications
    private val reminders by lazy { PracticeReminders(app, now = { clock }, zone = { zone }) }

    private fun at(day: Int, hour: Int, minute: Int = 0, zone: ZoneId = this.zone) = LocalDateTime.of(2026, 3, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** The day's alarm as the app asks Android for it: it's the same one that's found, set or cancelled. */
    private fun alarmIntent(flag: Int): PendingIntent? =
        PendingIntent.getBroadcast(app, 0, Intent(PracticeReminders.ACTION_REMIND).setClass(app, PracticeReminderReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or flag)

    private fun prefs() = app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun saved(vararg lines: JSONObject) = prefs().edit().putString("saved_lines", JSONArray(lines.toList()).toString()).commit()

    private fun line(text: String, title: String = "Demo", due: Long = 0) =
        JSONObject().put("text", text).put("translation", "Meaning").put("title", title).put("artist", "Band").put("due", due)

    /** The bell set after a first session at 20:05, with [lines] saved. */
    private fun setUpWith(vararg lines: JSONObject) {
        saved(*lines)
        reminders.sessionDone()
        reminders.turn(true)
    }

    /** The alarm going, on [day], at 20:05. */
    private fun firesOn(day: Int) {
        clock = at(day, 20, 5)
        reminders.remind()
    }

    private fun Notification.title() = extras.getString(Notification.EXTRA_TITLE)

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        // Android's page for it, where a user has allowed it.
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @Test
    fun aReminderIsTheNextTimeTheWallSaysItsMinute() {
        val eight = 20 * 60
        // Still to come today.
        assertEquals(at(2, 20), nextReminderAt(eight, at(2, 9), zone))
        // Gone by, and only just there, it's tomorrow, so an alarm that just went doesn't go again.
        assertEquals(at(3, 20), nextReminderAt(eight, at(2, 21), zone))
        assertEquals(at(3, 20), nextReminderAt(eight, at(2, 20), zone))
        // Moved to another time zone, it's that zone's eight o'clock.
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertEquals(at(2, 20, zone = tokyo), nextReminderAt(eight, at(2, 9, zone = tokyo), tokyo))
    }

    @Test
    fun aClockGoingForwardOrBackKeepsTheSameHourOnTheWall() {
        val newYork = ZoneId.of("America/New_York")
        // Clocks go forward on March 8th, 2026, so that day is an hour short.
        val before = LocalDateTime.of(2026, 3, 7, 21, 0).atZone(newYork).toInstant().toEpochMilli()
        val next = nextReminderAt(20 * 60, before, newYork)
        assertEquals(LocalDateTime.of(2026, 3, 8, 20, 0), java.time.Instant.ofEpochMilli(next).atZone(newYork).toLocalDateTime())
        // 23 hours away by the wall, 22 by the clock, the hour that day skips.
        assertEquals(22 * 60 * 60_000L, next - before)
    }

    @Test
    fun theMinuteOfTheDayIsWhatTheWallSays() {
        assertEquals(20 * 60 + 5, minuteOfDay(at(2, 20, 5), zone))
        assertEquals(0, minuteOfDay(at(2, 0), zone))
    }

    @Test
    fun aSessionCountsForTheSameDayOrAcrossMidnightButNotForTheNextDayAtTheSameTime() {
        // Earlier today, even long before.
        assertTrue(practisedLately(at(2, 7), at(2, 20), zone))
        // Just before midnight, with a reminder just after.
        assertTrue(practisedLately(at(1, 23, 50), at(2, 0, 30), zone))
        // Just after yesterday's reminder, today's still goes, or it would go every other day.
        assertFalse(practisedLately(at(1, 20, 10), at(2, 20), zone))
        // Never.
        assertFalse(practisedLately(0, at(2, 20), zone))
        // Six hours ago to the minute, over midnight, is no longer lately; a minute short of it still is.
        assertFalse(practisedLately(at(1, 18), at(2, 0), zone))
        assertTrue(practisedLately(at(1, 18, 1), at(2, 0), zone))
    }

    @Test
    fun aSessionDoneTakesBackAReminderAndStartsTheCountOverToo() {
        setUpWith(line("One"))
        prefs().edit().remove("practice_reminder_done_at").commit()
        firesOn(3)
        assertEquals(1, shown.size)
        assertEquals(1, prefs().getInt("practice_reminder_ignored", 0))
        reminders.sessionDone()
        assertTrue(shown.isEmpty())
        assertEquals(0, prefs().getInt("practice_reminder_ignored", -1))
        assertNotNull(alarms.peekNextScheduledAlarm())
    }

    @Test
    fun theTimeIsWrittenTheWayThePhoneWritesIt() {
        assertTrue(reminderTime(app, 20 * 60 + 5).startsWith("8:05"))
        assertTrue(reminderTime(app, 0).startsWith("12:00"))
        assertTrue(reminderTime(app, 9 * 60 + 30).startsWith("9:30"))
    }

    @Test
    fun midnightIsATimeLikeAnyOther() {
        reminders.sessionDone()
        reminders.turn(true)
        reminders.at(0)
        assertEquals(0, reminders.state.minute)
        assertEquals(at(3, 0), alarms.peekNextScheduledAlarm()?.triggerAtMs)
    }

    @Test
    fun theAlarmMakesTheChannelItShowsInWhenAndroidHasNone() {
        setUpWith(line("One"))
        manager.deleteNotificationChannel(PracticeReminders.CHANNEL)
        assertNull(manager.getNotificationChannel(PracticeReminders.CHANNEL))
        firesOn(3)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel(PracticeReminders.CHANNEL)?.importance)
    }

    @Test
    fun itNamesTheSongDueLongestAndHowManyMore() {
        val named = { first: String, others: Int -> "$first +$others" }
        assertNull(reminderSongs(emptyList(), named))
        val demo = SavedLine("One", title = "Demo", artist = "Band")
        // Two lines of one song are one song.
        assertEquals("Demo", reminderSongs(listOf(demo, demo.copy(text = "Two")), named))
        assertEquals("Demo +2", reminderSongs(listOf(demo, demo.copy(title = "Other"), demo.copy(text = "Two"), demo.copy(title = "Third")), named))
        // The same name by someone else is another song.
        assertEquals("Demo +1", reminderSongs(listOf(demo, demo.copy(artist = "Other band")), named))
    }

    @Test
    fun theFirstSessionSetsTheTimeAndLaterOnesLeaveIt() {
        assertNull(reminders.state.minute)
        reminders.sessionDone()
        assertEquals(20 * 60 + 5, reminders.state.minute)
        clock = at(3, 9)
        reminders.sessionDone()
        assertEquals(20 * 60 + 5, reminders.state.minute)
    }

    @Test
    fun turnedOnWithExactAlarmsAllowedItSetsOneThatGoesOnTimeEvenInDoze() {
        reminders.sessionDone()
        // Off, nothing wakes the phone.
        reminders.arm()
        assertNull(alarms.peekNextScheduledAlarm())
        reminders.turn(true)
        val alarm = alarms.peekNextScheduledAlarm()
        assertNotNull(alarm)
        assertEquals(at(3, 20, 5), alarm?.triggerAtMs)
        assertEquals(AlarmManager.RTC_WAKEUP, alarm?.getType())
        assertEquals(true, alarm?.isAllowWhileIdle)
        // Exact: Samsung's freezer holds an inexact one for as long as the app stays frozen.
        assertEquals(0L, requireNotNull(alarm).windowLengthMs)
        assertTrue(reminders.state.exact)
        assertFalse(reminders.state.late)
        assertNotNull(alarmIntent(PendingIntent.FLAG_NO_CREATE))
        reminders.turn(false)
        assertNull(alarms.peekNextScheduledAlarm())
    }

    @Test
    fun withoutThePermissionItSetsTheInexactOneAndSaysItCanBeLate() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        reminders.sessionDone()
        reminders.turn(true)
        val alarm = requireNotNull(alarms.peekNextScheduledAlarm())
        assertEquals(at(3, 20, 5), alarm.triggerAtMs)
        assertEquals(true, alarm.isAllowWhileIdle)
        assertTrue(alarm.windowLengthMs != 0L)
        assertFalse(reminders.state.exact)
        // Only a reminder that's on can be late.
        assertTrue(reminders.state.late)
        reminders.turn(false)
        assertFalse(reminders.state.late)
    }

    @Test
    fun theSameAlarmIsReplacedWhenThePermissionChangesSoOneIsNeverLeftBehind() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        reminders.sessionDone()
        reminders.turn(true)
        assertTrue(requireNotNull(alarms.peekNextScheduledAlarm()).windowLengthMs != 0L)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        reminders.arm()
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(0L, requireNotNull(alarms.peekNextScheduledAlarm()).windowLengthMs)
    }

    /** Before Android 12 there's no permission: exact is always allowed. */
    @Test
    @Config(sdk = [30])
    fun beforeAndroidTwelveExactNeedsNothingAsked() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        reminders.sessionDone()
        reminders.turn(true)
        assertEquals(0L, requireNotNull(alarms.peekNextScheduledAlarm()).windowLengthMs)
        assertTrue(reminders.state.exact)
    }

    /** Android 12 is the first one that asks, so the boundary is its own version. Robolectric has no jar for it, so only the number is set. */
    @Test
    fun onAndroidTwelveItselfExactIsTheUsersToAllow() {
        val was = Build.VERSION.SDK_INT
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", Build.VERSION_CODES.S)
        try {
            ShadowAlarmManager.setCanScheduleExactAlarms(false)
            reminders.sessionDone()
            reminders.turn(true)
            assertTrue(requireNotNull(alarms.peekNextScheduledAlarm()).windowLengthMs != 0L)
            assertFalse(reminders.state.exact)
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", was)
        }
    }

    @Test
    fun theExactAlarmPageIsTheAppsOwnOnAndroidsAlarmsAndRemindersList() {
        val page = reminders.exactPage
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, page.action)
        assertEquals("package:${app.packageName}", page.dataString)
    }

    @Test
    fun theAppAsksForTheExactAlarmPermissionAndHearsWhenItChanges() {
        val declared = app.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue("android.permission.SCHEDULE_EXACT_ALARM" in declared)
        // Never the one for alarm and calendar apps, which Play gives only to them.
        assertFalse("android.permission.USE_EXACT_ALARM" in declared)
        val heard = app.packageManager.queryBroadcastReceivers(Intent(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED).setPackage(app.packageName), 0)
        assertTrue(heard.any { it.activityInfo.name == PracticeReminderReceiver::class.java.name })
    }

    @Test
    fun allowedFromAndroidsSettingsTheReceiverSetsTheAlarmAgainExact() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val phone = PracticeReminders(app)
        phone.sessionDone()
        phone.turn(true)
        assertTrue(requireNotNull(alarms.peekNextScheduledAlarm()).windowLengthMs != 0L)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        PracticeReminderReceiver().onReceive(app, Intent(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED))
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(0L, requireNotNull(alarms.peekNextScheduledAlarm()).windowLengthMs)
    }

    @Test
    fun theTimeCanBeChanged() {
        reminders.sessionDone()
        reminders.turn(true)
        reminders.at(9 * 60)
        assertEquals(9 * 60, reminders.state.minute)
        assertEquals(at(3, 9), alarms.peekNextScheduledAlarm()?.triggerAtMs)
    }

    @Test
    fun itSaysHowManyAndWhichSongsThenOpensThePractice() {
        setUpWith(line("One"), line("Two"), line("Three", "Other"))
        // A new card is the one due longest, so they're all there, and the session's over a day ago.
        firesOn(3)
        val notification = shown.single()
        assertEquals("3 cards to practice", notification.title())
        assertEquals("Demo and 1 more", notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(PracticeReminders.CHANNEL, notification.channelId)
        // Tapped, it goes away, and it opens through the alias only Crosstune can reach.
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        val opens = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity.ACTION_PRACTISE, opens.action)
        assertEquals(MainActivity.PRACTISE_ALIAS, opens.component?.className)
        // On the lock screen, only the count.
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals("3 cards to practice", notification.publicVersion.title())
        assertNull(notification.publicVersion.extras.getString(Notification.EXTRA_TEXT))
        // And tomorrow's is set.
        assertEquals(at(4, 20, 5), alarms.peekNextScheduledAlarm()?.triggerAtMs)
    }

    @Test
    fun oneCardIsOneCardOfOneSong() {
        setUpWith(line("One"))
        firesOn(3)
        assertEquals("1 card to practice", shown.single().title())
        assertEquals("Demo", shown.single().extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun nothingDueSaysNothingAndTomorrowsIsStillSet() {
        setUpWith(line("Later", due = at(9, 0)))
        firesOn(3)
        assertTrue(shown.isEmpty())
        assertEquals(at(4, 20, 5), alarms.peekNextScheduledAlarm()?.triggerAtMs)
        // Nor are days with nothing to say ignored ones.
        firesOn(4)
        firesOn(5)
        firesOn(6)
        assertEquals(at(7, 20, 5), alarms.peekNextScheduledAlarm()?.triggerAtMs)
        // Once due, it says so.
        firesOn(9)
        assertEquals("1 card to practice", shown.single().title())
    }

    @Test
    fun aCardOfAKindTurnedOffIsntDue() {
        saved(line("One"))
        saveCardTypes(prefs(), DEFAULT_CARD_TYPES.map { if (it.id == READ_TYPE) it.copy(on = false) else it.copy(on = it.id == HEAR_TYPE) })
        reminders.sessionDone()
        reminders.turn(true)
        // Hearing it needs a line that's timed.
        firesOn(3)
        assertTrue(shown.isEmpty())
    }

    @Test
    fun aSessionDoneThatDaySaysNothingEvenWithCardsLeft() {
        setUpWith(line("One"))
        clock = at(3, 7)
        reminders.sessionDone()
        firesOn(3)
        assertTrue(shown.isEmpty())
        firesOn(4)
        assertEquals(1, shown.size)
    }

    @Test
    fun threeIgnoredInARowStopThemUntilTheNextTimeCardsArePractised() {
        setUpWith(line("One"))
        firesOn(3)
        firesOn(4)
        firesOn(5)
        assertEquals(1, shown.size)
        // No fourth: nothing wakes the phone for it, even after a reboot.
        assertNull(alarms.peekNextScheduledAlarm())
        clock = at(6, 8)
        reminders.arm()
        assertNull(alarms.peekNextScheduledAlarm())
        // The one showing is out of date as soon as a card's practised, and they start over.
        reminders.practised()
        assertTrue(shown.isEmpty())
        assertEquals(at(6, 20, 5), alarms.peekNextScheduledAlarm()?.triggerAtMs)
        firesOn(7)
        firesOn(8)
        firesOn(9)
        assertNull(alarms.peekNextScheduledAlarm())
        // Turned off and on again, they start over too.
        reminders.turn(false)
        assertTrue(shown.isEmpty())
        reminders.turn(true)
        assertEquals(at(10, 20, 5), alarms.peekNextScheduledAlarm()?.triggerAtMs)
    }

    @Test
    fun notAllowedItSaysNothingAndCountsNothingAndShowsAsBlocked() {
        setUpWith(line("One"))
        assertEquals(Reminder(20 * 60 + 5, on = true, allowed = true, turnedOff = false), reminders.state)
        assertTrue(reminders.state.working)
        shadowOf(manager).setNotificationsEnabled(false)
        assertTrue(reminders.state.blocked)
        assertFalse(reminders.state.working)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, reminders.settingsPage.action)
        assertEquals(app.packageName, reminders.settingsPage.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        // With the channel named too, Android shows that channel's page, whose switch can't undo the app's being off.
        assertNull(reminders.settingsPage.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        repeat(5) { firesOn(3 + it) }
        assertTrue(shown.isEmpty())
        // Still set, for when they're allowed again.
        shadowOf(manager).setNotificationsEnabled(true)
        firesOn(8)
        assertEquals(1, shown.size)
    }

    @Test
    fun onlyTheChannelTurnedOffIsBlockedToo() {
        setUpWith(line("One"))
        manager.getNotificationChannel(PracticeReminders.CHANNEL).importance = NotificationManager.IMPORTANCE_NONE
        manager.createNotificationChannel(manager.getNotificationChannel(PracticeReminders.CHANNEL))
        assertTrue(reminders.state.blocked)
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, reminders.settingsPage.action)
        assertEquals(PracticeReminders.CHANNEL, reminders.settingsPage.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        firesOn(3)
        assertTrue(shown.isEmpty())
    }

    @Test
    fun takenBackWhenAskedTo() {
        setUpWith(line("One"))
        firesOn(3)
        reminders.clear()
        assertTrue(shown.isEmpty())
    }

    @Test
    @Config(sdk = [32])
    fun androidTwelveHasNoPermissionToAskAndStillShowsIt() {
        setUpWith(line("One"))
        firesOn(3)
        assertEquals(1, shown.size)
    }

    @Test
    fun theAlarmWordsItInTheLanguagePickedEvenWhereAndroidLeavesItToTheScreens() {
        // The alarm starts the app with no screen, and a process Android hasn't set the app's language on.
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        AppLanguage.set(activity, "de")
        try {
            setUpWith(line("One"), line("Two"))
            firesOn(3)
            assertEquals("2 Karten zum Üben", shown.single().title())
            assertEquals("Übungserinnerungen", manager.getNotificationChannel("practice_reminders").name.toString())
        } finally {
            AppLanguage.set(activity, null)
        }
    }

    @Test
    fun theReceiverTakesTheAlarmAndSetsItAgainAfterWhatLosesIt() {
        // With the phone's own clock, as Android starts it.
        val phone = PracticeReminders(app)
        saved(line("One"))
        phone.sessionDone()
        phone.turn(true)
        app.getSystemService(AlarmManager::class.java).cancel(requireNotNull(alarmIntent(PendingIntent.FLAG_UPDATE_CURRENT)))
        assertNull(alarms.peekNextScheduledAlarm())
        PracticeReminderReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNotNull(alarms.peekNextScheduledAlarm())
        // A session long ago, as its clock moved on past the same day.
        prefs().edit().remove("practice_reminder_done_at").commit()
        PracticeReminderReceiver().onReceive(app, Intent(PracticeReminders.ACTION_REMIND))
        assertEquals(1, shown.size)
    }

    @Test
    fun androidBringsItThoseAfterARebootAndATimeChange() {
        val receivers = { action: String -> app.packageManager.queryBroadcastReceivers(Intent(action).setPackage(app.packageName), 0).map { it.activityInfo.name } }
        val name = PracticeReminderReceiver::class.java.name
        listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED).forEach { action ->
            assertTrue(action, name in receivers(action))
        }
    }

    @Test
    fun everyLanguageHasWordsForIt() {
        AppLanguage.tags.forEach { tag ->
            val configuration = android.content.res.Configuration(app.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(tag)) }
            val words = app.createConfigurationContext(configuration)
            assertTrue(tag, words.resources.getQuantityString(R.plurals.practice_reminder_title, 12, 12).contains("12"))
            assertTrue(tag, words.resources.getQuantityString(R.plurals.practice_reminder_title, 1, 1).contains("1"))
            val songs = words.getString(R.string.practice_reminder_songs, "Demo", 2)
            assertTrue(tag, songs.contains("Demo") && songs.contains("2"))
            assertTrue(tag, words.getString(R.string.practice_reminder_at, "8:05").contains("8:05"))
            // Said in the language, not left in English by a language missing it.
            if (!tag.startsWith("en")) {
                assertTrue(tag, words.getString(R.string.practice_reminder_exact) != app.getString(R.string.practice_reminder_exact))
                assertTrue(tag, words.getString(R.string.practice_reminder_late) != app.getString(R.string.practice_reminder_late))
            }
        }
    }
}
