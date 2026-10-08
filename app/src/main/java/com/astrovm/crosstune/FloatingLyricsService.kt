package com.astrovm.crosstune

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import kotlinx.coroutines.launch
import kotlin.math.abs

/** The screen the words float out of, which keeps them in time and does what they ask. */
internal interface FloatingHost {
    val state: UiState
    val dark: Boolean
    suspend fun loadArtwork(url: String): ImageBitmap?
    fun toggleListening()
    fun setLocked(locked: Boolean)
    fun moveTo(top: Int)
}

/**
 * Keeps the words floating over other apps while Crosstune is out of sight, with a notification
 * Android requires for it, which can also unlock or close them. With the microphone allowed, it can
 * keep listening along too.
 */
class FloatingLyricsService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    // The words' window is in sight, and so as live as a screen in front, from when it's shown.
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    private val savedState = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private val windows get() = getSystemService(WindowManager::class.java)
    private var view: FrameLayout? = null
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP }

    override fun onCreate() {
        super.onCreate()
        savedState.performAttach()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val host = host
        when (intent?.action) {
            // Started by Crosstune as it's left: Android wants the notification up before anything
            // else, even when there's nothing to float after all.
            null -> {
                startForeground()
                if (host == null) stopSelf() else if (view == null) show(host)
            }
            ACTION_UNLOCK -> host?.setLocked(false)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForeground() {
        val microphone = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        var type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        // Started while Crosstune is still in sight, it may go on listening along once it isn't.
        if (microphone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(locked = false), type)
    }

    private fun notification(locked: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.floating_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, openIntent(), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_lyrics)
            .setContentTitle(getString(R.string.floating_notification))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .apply {
                if (locked) addAction(0, getString(R.string.floating_unlock), command(ACTION_UNLOCK))
            }
            .addAction(0, getString(R.string.floating_close), command(ACTION_CLOSE))
            .build()
    }

    private fun command(action: String) =
        PendingIntent.getService(this, action.hashCode(), Intent(this, FloatingLyricsService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    private fun openIntent() = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

    private fun show(host: FloatingHost) {
        // Taken back in Android's settings since Crosstune was left, so there's nothing to float on.
        if (!Settings.canDrawOverlays(this)) return stopSelf()
        params.y = host.state.floating.top.takeIf { it >= 0 } ?: (resources.displayMetrics.heightPixels / 4)
        val content = ComposeView(this).apply {
            setContent {
                val state = host.state
                CrosstuneTheme(darkTheme = host.dark, palette = state.palette, pureBlack = state.pureBlack) {
                    FloatingOverApps(
                        state,
                        host::loadArtwork,
                        FloatingActions(
                            onOpen = { startActivity(openIntent()) },
                            onToggleListening = host::toggleListening,
                            onLock = { host.setLocked(true) },
                            onClose = ::stopSelf
                        )
                    )
                }
            }
        }
        val view = DragFrame(this, onDrag = ::moveTo, onDragEnd = { host.moveTo(params.y) }).apply {
            addView(content)
            setViewTreeLifecycleOwner(this@FloatingLyricsService)
            setViewTreeSavedStateRegistryOwner(this@FloatingLyricsService)
        }
        this.view = view
        registry.currentState = Lifecycle.State.RESUMED
        windows.addView(view, params)
        // Locked, touches pass through to the app below, which Android only allows from a window it can see through.
        lifecycleScope.launch {
            snapshotFlow { host.state.floating.locked }.collect { locked ->
                params.flags = if (locked) params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                params.alpha = if (locked) LOCKED_ALPHA else 1f
                windows.updateViewLayout(view, params)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(locked))
            }
        }
    }

    /** Where a drag that started at [from] puts the words, kept on screen. */
    private fun moveTo(from: Int, by: Float) {
        val view = view ?: return
        params.y = (from + by.toInt()).coerceIn(0, maxOf(0, resources.displayMetrics.heightPixels - view.height))
        windows.updateViewLayout(view, params)
    }

    /**
     * Holds the words and moves their window with a finger dragged up or down. It goes by where the
     * finger is on screen, since the window, and everything in it, moves under it.
     */
    private inner class DragFrame(context: Context, val onDrag: (from: Int, by: Float) -> Unit, val onDragEnd: () -> Unit) : FrameLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downY = 0f
        private var downTop = 0

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    downTop = params.y
                }
                // Past a tap's wobble it's a drag: the words' own taps are let go.
                MotionEvent.ACTION_MOVE -> return abs(event.rawY - downY) > slop
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> onDrag(downTop, event.rawY - downY)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onDragEnd()
            }
            return true
        }
    }

    override fun onDestroy() {
        view?.let(windows::removeView)
        view = null
        registry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    internal companion object {
        /** Set while there's a screen to float out of; the words close without one. */
        var host: FloatingHost? = null

        const val ACTION_UNLOCK = "com.astrovm.crosstune.floating.UNLOCK"
        const val ACTION_CLOSE = "com.astrovm.crosstune.floating.CLOSE"
        private const val CHANNEL = "floating_lyrics"
        private const val NOTIFICATION_ID = 1

        /** The most Android lets a window be seen while touches pass through it to another app's. */
        private const val LOCKED_ALPHA = 0.8f

        fun start(context: Context, host: FloatingHost) {
            this.host = host
            ContextCompat.startForegroundService(context, Intent(context, FloatingLyricsService::class.java))
        }

        fun stop(context: Context) {
            host = null
            context.stopService(Intent(context, FloatingLyricsService::class.java))
        }
    }
}
