package com.astrovm.crosstune

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ChatGptSignInServiceTest {

    private fun signingIn() {
        val controller = Robolectric.buildService(ChatGptSignInService::class.java).create().startCommand(0, 1)
        val service = controller.get()
        // Kept running in the foreground, saying why, quietly.
        val notification = shadowOf(service).lastForegroundNotification
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        assertEquals(context.getString(R.string.chatgpt_signing_in_notification), shadowOf(notification).contentTitle)
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(notification.channelId)
        assertEquals(context.getString(R.string.chatgpt_channel), channel.name)
        assertEquals(null, service.onBind(null))

        // Out of time, it goes.
        service.onTimeout(1)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun itKeepsCrosstuneRunningWhileSigningInForAsLongAsAndroidAllows() = signingIn()

    @Test
    @Config(sdk = [33])
    fun beforeAndroid14ItsAnyForegroundService() = signingIn()
}
