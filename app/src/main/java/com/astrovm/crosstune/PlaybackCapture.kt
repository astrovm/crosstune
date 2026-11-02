package com.astrovm.crosstune

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The 16-bit sound music apps play, through Android's playback capture, at the rate Shazam and
 * whisper hear. Only once Shizuku or root has allowed it in setup (PROJECT_MEDIA), so Android
 * never asks to share the screen, which it would each time; null without that, or before Android 10.
 * Recording starts at the first read, so what's heard starts then, as with the visuals' tap.
 */
internal class PlaybackCapture(private val context: Context) : Microphone {
    override fun open(): Recording? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !allowed(context)) return null
        val projection = Projections.take(context) ?: return null
        val record = try {
            record(projection)
        } catch (_: RuntimeException) {
            null
        }?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        if (record == null) {
            Projections.letGo()
            return null
        }
        return object : Recording {
            private var started = false
            private var heard = 0L
            private var loudest = 0

            override fun read(buffer: ShortArray, offset: Int, count: Int): Int {
                if (!started) {
                    record.startRecording()
                    started = true
                }
                val read = record.read(buffer, offset, count)
                if (read < 0) throw IllegalStateException("Playback capture read failed: $read")
                heard += read
                for (index in offset until offset + read) loudest = maxOf(loudest, abs(buffer[index].toInt()))
                return read
            }

            override fun close() {
                if (Log.isLoggable(TIMING_LOG, Log.DEBUG)) Log.d(TIMING_LOG, "captured $heard samples, loudest $loudest")
                try {
                    if (started) record.stop()
                } finally {
                    record.release()
                    Projections.letGo()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun record(projection: MediaProjection): AudioRecord {
        // Playback capture needs the microphone too; taken away meanwhile, there's nothing to hear.
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw SecurityException("No microphone")
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(ShazamSignature.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        // Two seconds, so nothing's lost while a chunk is copied.
        return AudioRecord.Builder().setAudioFormat(format).setAudioPlaybackCaptureConfig(config)
            .setBufferSizeInBytes(ShazamSignature.SAMPLE_RATE * 2 * 2).build()
    }

    companion object {
        /** Whether Shizuku or root allowed it, so Android won't ask, and the microphone's allowed. */
        @RequiresApi(Build.VERSION_CODES.Q)
        fun allowed(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                context.getSystemService(AppOpsManager::class.java)
                    ?.checkOpNoThrow(OneTapSetup.PROJECT_MEDIA_OP, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }
}

/**
 * The one projection every capture shares, asked for through [CaptureAsk] and held by
 * [CaptureService]. Kept a while after the last capture closes, as the next listen comes soon.
 */
private object Projections {
    private var projection: MediaProjection? = null
    private var users = 0
    private var asking: CountDownLatch? = null
    private var refusedAt: Long? = null
    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val end = Runnable { synchronized(this) { if (users == 0) stop() } }

    /** The projection, asked for when there's none; null when Android won't give one. Blocks while it asks. */
    fun take(context: Context): MediaProjection? {
        val latch = synchronized(this) {
            app = context.applicationContext
            main.removeCallbacks(end)
            projection?.let { users++; return it }
            // Refused moments ago, it's not asked again for each listen.
            refusedAt?.let { if (SystemClock.elapsedRealtime() - it < RETRY_MS) return null }
            asking ?: CountDownLatch(1).also {
                asking = it
                context.startActivity(Intent(context, CaptureAsk::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }
        }
        latch.await(ASK_MS, TimeUnit.MILLISECONDS)
        synchronized(this) {
            val given = projection
            if (given == null) {
                if (asking === latch) refused()
                return null
            }
            users++
            return given
        }
    }

    @Synchronized
    fun letGo() {
        if (--users == 0) main.postDelayed(end, LINGER_MS)
    }

    @Synchronized
    fun given(projection: MediaProjection) {
        this.projection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            // Stopped by the user, or by Android, it's asked for again next time.
            override fun onStop() = synchronized(this@Projections) {
                if (this@Projections.projection === projection) {
                    this@Projections.projection = null
                    app?.let(CaptureService::stop)
                }
            }
        }, main)
        refusedAt = null
        asking?.countDown()
        asking = null
        // Given too late for whoever asked, it's still let go of in time.
        if (users == 0) main.postDelayed(end, LINGER_MS)
    }

    @Synchronized
    fun refused() {
        refusedAt = SystemClock.elapsedRealtime()
        asking?.countDown()
        asking = null
    }

    private fun stop() {
        projection?.stop()
        projection = null
        app?.let(CaptureService::stop)
    }

    /** Android answers in about a second and a half when allowed. */
    private const val ASK_MS = 4_000L
    private const val RETRY_MS = 10 * 60_000L
    private const val LINGER_MS = 60_000L
}

/** Asks Android for the projection, which it gives without asking the user once Shizuku or root allowed it. Shows nothing. */
class CaptureAsk : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            Projections.refused()
            finish()
        } else {
            startActivityForResult(manager.createScreenCaptureIntent(), ASK)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Android wants the service running before the projection's taken.
        if (resultCode == RESULT_OK && data != null) CaptureService.start(this, data) else Projections.refused()
        finish()
    }

    private companion object {
        const val ASK = 1
    }
}

/** Holds the projection, which Android only gives a service of its kind, with its notification. */
class CaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.hearing_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_lyrics)
            .setContentTitle(getString(R.string.hearing_channel))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        val projection = data?.let {
            try {
                getSystemService(MediaProjectionManager::class.java)?.getMediaProjection(Activity.RESULT_OK, it)
            } catch (_: RuntimeException) {
                null
            }
        }
        if (projection == null) {
            Projections.refused()
            stopSelf()
        } else {
            Projections.given(projection)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "hearing"
        private const val NOTIFICATION_ID = 4
        private const val EXTRA_DATA = "data"

        fun start(context: Context, data: Intent) =
            ContextCompat.startForegroundService(context, Intent(context, CaptureService::class.java).putExtra(EXTRA_DATA, data))

        fun stop(context: Context) = context.stopService(Intent(context, CaptureService::class.java))
    }
}
