package com.astrovm.crosstune

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Where Crosstune sends the user in Android's settings, on phones that lack some of the pages. */
@RunWith(RobolectricTestRunner::class)
class AndroidPagesTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun opens(action: String) {
        val page = ComponentName("com.android.settings", action)
        shadowOf(app.packageManager).addActivityIfNotPresent(page)
        shadowOf(app.packageManager).addIntentFilterForActivity(page, IntentFilter(action).apply { addCategory(Intent.CATEGORY_DEFAULT) })
    }

    @Test
    fun aTvWithoutAListOfAppsLetsTheUserAllowItInAndroidsNotificationAccess() {
        shadowOf(app).checkActivities(true)
        opens(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        activity.openNotificationAccess(tv = true)
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, shadowOf(activity).nextStartedActivity.action)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun aPageNoAppOpensIsNotOpened() {
        shadowOf(app).checkActivities(true)
        activity.openNotificationAccess(tv = true)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
