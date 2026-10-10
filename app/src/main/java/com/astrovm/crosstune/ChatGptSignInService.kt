package com.astrovm.crosstune

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps Crosstune running while the browser signs in with ChatGPT. Out of sight, Android may
 * freeze it, and then the browser, coming back to it, waits until Crosstune's opened again.
 */
class ChatGptSignInService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.chatgpt_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_lyrics)
            .setContentTitle(getString(R.string.chatgpt_signing_in_notification))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
        // A short one: Android gives it three minutes, plenty for signing in.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        return START_NOT_STICKY
    }

    /** Out of time, it goes; signing in still finishes once Crosstune's opened again. */
    override fun onTimeout(startId: Int) = stopSelf()

    companion object {
        private const val CHANNEL = "chatgpt_sign_in"
        private const val NOTIFICATION_ID = 3

        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, ChatGptSignInService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, ChatGptSignInService::class.java))
    }
}
