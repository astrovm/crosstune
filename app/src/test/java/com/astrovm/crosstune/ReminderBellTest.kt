package com.astrovm.crosstune

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.provider.Settings
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** The bell that asks cards due back, as a user meets it, and a reminder tapped. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2000dp")
class ReminderBellTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeSpotify()
    private val phone = FakePlayback().apply { access = true }
    private var controller: ActivityController<MainActivity>? = null
    private val zone = ZoneId.of("America/Argentina/Buenos_Aires")
    private var clock = LocalDateTime.of(2026, 3, 2, 20, 5).atZone(zone).toInstant().toEpochMilli()
    private val realPhone = MainActivity.phoneFactory
    private val realReminders = MainActivity.remindersFactory
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java))
    private val activity get() = requireNotNull(controller).get()

    private val remindMe get() = app.getString(R.string.practice_remind_me)
    private val blocked get() = app.getString(R.string.practice_reminder_blocked)
    private val eightOFive get() = reminderTime(app, 20 * 60 + 5)
    private val reminderAt get() = app.getString(R.string.practice_reminder_at, eightOFive)

    @Before
    fun setUp() {
        MainActivity.httpClientFactory = { fake.client() }
        MainActivity.systemDispatcher = Dispatchers.Unconfined
        MainActivity.lookupDispatcher = Dispatchers.Unconfined
        MainActivity.playbackFactory = { phone }
        MainActivity.remindersFactory = { PracticeReminders(it, now = { clock }, zone = { zone }) }
        prefs().edit().clear().putBoolean("setup_complete", true).putString("default_target", "YOUTUBE_MUSIC").putBoolean("links_in_crosstune", false).commit()
        fake.handler = { request -> FakeSpotify.html(request, "{}") }
        // Allowed, unless the test says it's not.
        allowed(true)
        saved()
    }

    @After
    fun tearDown() {
        runCatching { controller?.pause()?.stop()?.destroy() }
        MainActivity.httpClientFactory = ::httpClient
        MainActivity.systemDispatcher = Dispatchers.Default
        MainActivity.lookupDispatcher = Dispatchers.IO
        MainActivity.playbackFactory = ::MediaSessionPlayback
        MainActivity.remindersFactory = realReminders
        MainActivity.phoneFactory = realPhone
    }

    private fun prefs() = app.getSharedPreferences(MainViewModel.PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** What Android says, as a user who's allowed notifications or not has it: both the permission and whether they show. */
    private fun allowed(yes: Boolean) {
        val shadow = shadowOf(app)
        if (yes) shadow.grantPermissions(Manifest.permission.POST_NOTIFICATIONS) else shadow.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(yes)
    }

    private fun saved(lines: String = """{"text":"Stars","translation":"Meaning","title":"Demo","artist":"Band"}""") =
        prefs().edit().putString("saved_lines", "[$lines]").commit()

    private fun launch(intent: Intent = Intent(app, MainActivity::class.java)) {
        controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        composeRule.waitForIdle()
    }

    private fun model() = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java]

    private fun waitUntil(condition: () -> Boolean) =
        composeRule.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); condition() }

    /** Forgets the screens started so far, so the next one started is the one a tap opened. */
    private fun drainStartedActivities() {
        do {
            val started = shadowOf(activity).nextStartedActivity
        } while (started != null)
    }

    private fun shown(text: String) = composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun described(label: String) = composeRule.onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty()

    private fun click(text: String) {
        composeRule.onAllNodes(hasText(text) or hasContentDescription(text)).onLast().performClick()
        composeRule.waitForIdle()
    }

    /** Practises the one line saved, to the screen that ends the session. */
    private fun finishASession() {
        val due = "${app.getString(R.string.saved_practice)}, ${app.getString(R.string.practice_due, 1)}"
        if (!shown(app.getString(R.string.practice_all_songs))) click(due)
        click(app.getString(R.string.practice_all_songs))
        finishTheCard()
    }

    private fun finishTheCard() {
        waitUntil { shown(app.getString(R.string.practice_show)) }
        click(app.getString(R.string.practice_show))
        click(app.getString(R.string.practice_easy))
        waitUntil { shown(app.getString(R.string.practice_done, 1)) }
    }

    /** Back from the session's end, to the list, whose bell is the same. */
    private fun backToTheList() {
        click(app.getString(R.string.floating_close))
        waitUntil { shown(app.getString(R.string.practice_all_songs)) }
    }

    /** Settings, from the main screen. */
    private fun openSettings() {
        composeRule.onNode(hasContentDescription(app.getString(R.string.settings_title))).performClick()
        composeRule.waitForIdle()
    }

    private fun switch() = composeRule.onNode(isToggleable() and hasText(app.getString(R.string.practice_reminders)), useUnmergedTree = false)

    @Test
    fun noSessionYetNoBell() {
        launch()
        // The list has no time to offer yet.
        click("${app.getString(R.string.saved_practice)}, ${app.getString(R.string.practice_due, 1)}")
        assertFalse(described(remindMe))
        assertNull(alarms.peekNextScheduledAlarm())
        // Nor do settings.
        click(app.getString(R.string.back_button))
        openSettings()
        assertFalse(shown(app.getString(R.string.practice_reminders)))
    }

    @Test
    fun theEndOfASessionOffersItAndTheTimeIsWhenThatSessionEnded() {
        launch()
        finishASession()
        assertTrue(shown(remindMe))
        // Offered, not done: nothing's set until it's tapped.
        assertNull(alarms.peekNextScheduledAlarm())
        assertEquals(20 * 60 + 5, model().uiState.reminder.minute)
        click(remindMe)
        assertTrue(shown(reminderAt))
        assertFalse(shown(remindMe))
        assertNotNull(alarms.peekNextScheduledAlarm())
        assertTrue(model().uiState.reminder.working)
    }

    @Test
    fun androidAsksOnTheTapNeverBefore() {
        allowed(false)
        launch()
        // Not at launch.
        assertNull(shadowOf(activity).lastRequestedPermission)
        finishASession()
        // Nor when the session ends.
        assertNull(shadowOf(activity).lastRequestedPermission)
        click(remindMe)
        val asked = shadowOf(activity).lastRequestedPermission
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), asked.requestedPermissions.toList())
        // Until it answers, it's off.
        assertTrue(shown(remindMe))
        allowed(true)
        activity.answer(asked, PackageManager.PERMISSION_GRANTED)
        waitUntil { shown(reminderAt) }
        assertNotNull(alarms.peekNextScheduledAlarm())
    }

    @Test
    fun turnedDownItStaysOffAndOnceAndroidWontAskATapGoesToItsSettings() {
        allowed(false)
        launch()
        finishASession()
        // Turned down once, Android would still ask.
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS, true)
        click(remindMe)
        activity.answer(shadowOf(activity).lastRequestedPermission, PackageManager.PERMISSION_DENIED)
        waitUntil { shown(remindMe) }
        assertFalse(model().uiState.reminder.on)
        assertNull(alarms.peekNextScheduledAlarm())
        // Asked again on the next tap; turned down again, it won't.
        click(remindMe)
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS, false)
        activity.answer(shadowOf(activity).lastRequestedPermission, PackageManager.PERMISSION_DENIED)
        waitUntil { shown(remindMe) }
        // So a tap goes to where it can be allowed, asking nothing more.
        drainStartedActivities()
        click(remindMe)
        val page = requireNotNull(shadowOf(activity).nextStartedActivity) { "No settings page was opened by the tap" }
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, page.action)
        assertEquals(app.packageName, page.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        // Back without allowing it, it's still off.
        controller?.pause()?.resume()
        composeRule.waitForIdle()
        assertTrue(shown(remindMe))
        // Off to allow it, and back, it's on.
        click(remindMe)
        assertNotNull(shadowOf(activity).nextStartedActivity)
        allowed(true)
        controller?.pause()?.resume()
        waitUntil { shown(reminderAt) }
        assertNotNull(alarms.peekNextScheduledAlarm())
        // And it isn't turned on by coming back another time.
        model().setReminder(false)
        controller?.pause()?.resume()
        composeRule.waitForIdle()
        assertFalse(model().uiState.reminder.on)
    }

    @Test
    fun turnedOffInAndroidItShowsThatAndATapGoesToFixIt() {
        launch()
        finishASession()
        click(remindMe)
        allowed(false)
        // Back from Android's settings.
        controller?.pause()?.resume()
        waitUntil { shown(blocked) }
        // Android won't ask again here, as it's said no for good on this one.
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS, false)
        click(blocked)
        val asked = shadowOf(activity).lastRequestedPermission
        activity.answer(asked, PackageManager.PERMISSION_DENIED)
        composeRule.waitForIdle()
        drainStartedActivities()
        click(blocked)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, shadowOf(activity).nextStartedActivity.action)
        // The list and settings say the same.
        backToTheList()
        assertTrue(described(blocked))
        click(app.getString(R.string.back_button))
        openSettings()
        composeRule.onNodeWithTextScrolled(app.getString(R.string.practice_reminders))
        assertTrue(shown(blocked))
        switch().assertIsOff()
        allowed(true)
        controller?.pause()?.resume()
        waitUntil { model().uiState.reminder.working }
        switch().assertIsOn()
        assertTrue(shown(eightOFive))
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onNodeWithTextScrolled(text: String) {
        onNode(hasText(text)).performScrollTo()
        waitForIdle()
    }

    @Test
    fun onTheListTheBellIsTheTimeOrOff() {
        launch()
        finishASession()
        backToTheList()
        // Off, a tap turns it on, at the time of that first session.
        assertTrue(described(remindMe))
        click(remindMe)
        assertTrue(described(reminderAt))
        // On, a tap is the time.
        click(reminderAt)
        composeRule.waitForIdle()
        composeRule.onNode(hasContentDescription("9 o'clock") and androidx.compose.ui.test.hasClickAction()).performClick()
        composeRule.waitForIdle()
        click(app.getString(R.string.save_button))
        assertEquals(21 * 60 + 5, model().uiState.reminder.minute)
        assertTrue(described(app.getString(R.string.practice_reminder_at, reminderTime(app, 21 * 60 + 5))))
        assertEquals(
            LocalDateTime.of(2026, 3, 2, 21, 5).atZone(zone).toInstant().toEpochMilli(),
            alarms.peekNextScheduledAlarm()?.triggerAtMs
        )
        // Put away, nothing changes.
        click(app.getString(R.string.practice_reminder_at, reminderTime(app, 21 * 60 + 5)))
        (org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.activity.ComponentDialog).onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        assertFalse(shown(app.getString(R.string.save_button)))
        assertTrue(model().uiState.reminder.working)
        // Or off.
        click(app.getString(R.string.practice_reminder_at, reminderTime(app, 21 * 60 + 5)))
        click(app.getString(R.string.player_sleep_off))
        assertFalse(model().uiState.reminder.on)
        assertTrue(described(remindMe))
        assertNull(alarms.peekNextScheduledAlarm())
    }

    @Test
    fun onceOnTheEndOfASessionShowsItAndTurnedOffItsNoLongerOffered() {
        launch()
        finishASession()
        click(remindMe)
        // A tap there is the time or off too.
        click(reminderAt)
        click(app.getString(R.string.player_sleep_off))
        // It says so where it was, and isn't asked of them again.
        composeRule.waitForIdle()
        assertFalse(shown(remindMe))
        // The next session's end doesn't offer it, though the list does.
        backToTheList()
        assertTrue(described(remindMe))
    }

    @Test
    fun settingsTurnItOnAndOff() {
        launch()
        finishASession()
        click(app.getString(R.string.floating_close))
        waitUntil { shown(app.getString(R.string.practice_all_songs)) }
        click(app.getString(R.string.back_button))
        openSettings()
        composeRule.onNodeWithTextScrolled(app.getString(R.string.practice_reminders))
        switch().assertIsOff()
        assertFalse(shown(eightOFive))
        switch().performClick()
        composeRule.waitForIdle()
        switch().assertIsOn()
        assertTrue(shown(eightOFive))
        assertNotNull(alarms.peekNextScheduledAlarm())
        switch().performClick()
        composeRule.waitForIdle()
        switch().assertIsOff()
        assertNull(alarms.peekNextScheduledAlarm())
    }

    @Test
    fun aTvHasNoBellNoSwitchAndNoAlarm() {
        MainActivity.phoneFactory = { Phone.TV }
        launch()
        model().startPractice()
        finishTheCard()
        assertEquals(20 * 60 + 5, model().uiState.reminder.minute)
        assertFalse(shown(remindMe))
        assertNull(alarms.peekNextScheduledAlarm())
        assertNull(model().uiState.reminderMinute)
    }

    @Test
    fun aReminderTappedOpensTheFirstCardWithTheAppClosedAndStartsTheCountOver() {
        val reminders = MainActivity.remindersFactory(app)
        reminders.sessionDone()
        reminders.turn(true)
        // Three ignored, it's stopped.
        repeat(3) {
            clock += 2 * 24 * 60 * 60_000L
            reminders.remind()
        }
        assertNull(alarms.peekNextScheduledAlarm())
        launch(Intent(MainActivity.ACTION_PRACTISE).setClassName(app, MainActivity.PRACTISE_ALIAS))
        waitUntil { shown("Stars") }
        assertEquals("Stars", model().uiState.practice?.line?.text)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertNotNull(alarms.peekNextScheduledAlarm())
    }

    @Test
    fun openingTheAppSetsTheAlarmAgainAfterAForceStopTookIt() {
        val reminders = MainActivity.remindersFactory(app)
        reminders.sessionDone()
        reminders.turn(true)
        // What a force stop does to it.
        val alarm = android.app.PendingIntent.getBroadcast(
            app, 0, Intent(PracticeReminders.ACTION_REMIND).setClass(app, PracticeReminderReceiver::class.java), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        app.getSystemService(AlarmManager::class.java).cancel(alarm)
        assertNull(alarms.peekNextScheduledAlarm())
        launch()
        assertNotNull(alarms.peekNextScheduledAlarm())
    }

    @Test
    fun aCardPractisedInTheMiddleOfASessionTakesBackTheReminderButDoesntAnswerTheDay() {
        saved(
            """{"text":"Stars","translation":"Meaning","title":"Demo","artist":"Band"},{"text":"Moon","translation":"Meaning","title":"Demo","artist":"Band"}"""
        )
        val reminders = MainActivity.remindersFactory(app)
        reminders.sessionDone()
        reminders.turn(true)
        clock += 24 * 60 * 60_000L
        reminders.remind()
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertEquals(1, prefs().getInt("practice_reminder_ignored", 0))
        val doneAt = prefs().getLong("practice_reminder_done_at", 0)
        launch()
        click("${app.getString(R.string.saved_practice)}, ${app.getString(R.string.practice_due, 2)}")
        click(app.getString(R.string.practice_all_songs))
        waitUntil { shown(app.getString(R.string.practice_show)) }
        click(app.getString(R.string.practice_show))
        click(app.getString(R.string.practice_easy))
        waitUntil { model().uiState.practice?.done == 1 }
        // One card in, with another to go: the reminder is out of date and the count starts over, but the day isn't answered until the session ends.
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertEquals(0, prefs().getInt("practice_reminder_ignored", -1))
        assertEquals(doneAt, prefs().getLong("practice_reminder_done_at", 0))
    }

    @Test
    fun aReminderTappedOverSettingsLeavesThemAndKeepsASessionGoing() {
        launch()
        finishASession()
        click(app.getString(R.string.floating_close))
        waitUntil { shown(app.getString(R.string.practice_all_songs)) }
        click(app.getString(R.string.back_button))
        openSettings()
        saved()
        val tapped = Intent(MainActivity.ACTION_PRACTISE).setClassName(app, MainActivity.PRACTISE_ALIAS)
        controller?.newIntent(tapped)
        waitUntil { model().uiState.practice != null && !shown(app.getString(R.string.settings_title)) }
        assertTrue(model().uiState.practice?.total == 0)
        // At the end of a session, one's over, so a new one starts; in the middle of one, it goes on.
        model().stopPractice()
        composeRule.waitForIdle()
        val ongoing = Practice(listOf(SavedLine("Stars", translation = "Meaning", title = "Demo", artist = "Band")), total = 1, done = 0)
        model().startPractice(MusicMetadata("Demo", "Band"))
        waitUntil { model().uiState.practice?.line != null }
        val before = model().uiState.practice
        assertEquals(ongoing.total, before?.total)
        model().turnPractice()
        controller?.newIntent(tapped)
        composeRule.waitForIdle()
        assertEquals(true, model().uiState.practice?.turned)
    }

    @Test
    fun anotherAppAskingThroughTheMainScreenStartsNoSession() {
        launch(Intent(MainActivity.ACTION_PRACTISE).setClass(app, MainActivity::class.java))
        assertNull(model().uiState.practice)
    }

    @Test
    fun aCardPractisedTakesBackOneShowingAndNoneDueTakesBackOneShowingToo() {
        val reminders = MainActivity.remindersFactory(app)
        reminders.sessionDone()
        reminders.turn(true)
        clock += 24 * 60 * 60_000L
        reminders.remind()
        assertEquals(1, shadowOf(manager).allNotifications.size)
        launch()
        // Still due, and still shown.
        assertEquals(1, shadowOf(manager).allNotifications.size)
        finishASession()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        // Shown again, with nothing due, it's taken back as soon as it's seen.
        saved()
        clock += 24 * 60 * 60_000L
        reminders.remind()
        assertEquals(1, shadowOf(manager).allNotifications.size)
        click(app.getString(R.string.floating_close))
        waitUntil { shown(app.getString(R.string.practice_all_songs)) || model().uiState.practice == null }
        val kept = model().uiState.savedLines
        assertEquals("the list holds the line that was practised", 1, kept.size)
        model().chooseLines(emptyList(), kept, false)
        assertTrue("the line is let go", model().uiState.savedLines.isEmpty())
        composeRule.waitForIdle()
        val takenBack = runCatching { waitUntil { shadowOf(manager).allNotifications.isEmpty() } }.isSuccess
        assertTrue("still showing: ${shadowOf(manager).allNotifications.map { it.extras.getString(android.app.Notification.EXTRA_TITLE) }}", takenBack)
    }
}
