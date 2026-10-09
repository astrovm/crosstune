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
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.compose.runtime.snapshotFlow
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
import kotlin.math.hypot

/** The screen the words float out of, which keeps them in time and does what they ask. */
internal interface FloatingHost {
    val state: UiState
    val dark: Boolean
    val artwork: ArtworkLoader
    fun toggleListening()
    /** How the words look and where they are, kept when [save]d, e.g. once a drag is done. */
    fun update(options: FloatingOptions, save: Boolean = true)
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
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /**
     * Locked, the words let every touch through, so a small button of their own, beside them, still
     * takes one: the way out that's always there, notification or not.
     */
    private var unlock: ImageView? = null
    private val unlockParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /** Where in the window the sliders are, whose drags are theirs, not the window's. */
    private var sliders: Rect? = null

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
            ACTION_UNLOCK -> host?.let { it.update(it.state.floating.copy(locked = false)) }
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
        val options = host.state.floating
        val screen = resources.displayMetrics
        val margin = (MARGIN_DP * screen.density).toInt()
        params.width = options.width.takeIf { it > 0 }?.coerceAtMost(screen.widthPixels) ?: (screen.widthPixels - 2 * margin)
        params.x = options.left.takeIf { it >= 0 }?.coerceAtMost(screen.widthPixels - params.width) ?: ((screen.widthPixels - params.width) / 2)
        params.y = options.top.takeIf { it >= 0 } ?: (screen.heightPixels / 4)
        val content = ComposeView(this).apply {
            setContent {
                val state = host.state
                CrosstuneTheme(darkTheme = host.dark, palette = state.palette, pureBlack = state.pureBlack) {
                    FloatingOverApps(
                        state,
                        host.artwork::load,
                        FloatingActions(
                            onOpen = { startActivity(openIntent()) },
                            onToggleListening = host::toggleListening,
                            onLock = { host.update(host.state.floating.copy(locked = true)) },
                            onClose = ::stopSelf,
                            onChange = host::update,
                            onSave = { host.update(host.state.floating) },
                            onSlidersAt = { sliders = it }
                        )
                    )
                }
            }
        }
        val view = GestureFrame(this, host).apply {
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
                if (locked) showUnlock(host) else hideUnlock()
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(locked))
            }
        }
    }

    private fun showUnlock(host: FloatingHost) {
        if (unlock != null) return
        val size = (UNLOCK_DP * resources.displayMetrics.density).toInt()
        val button = ImageView(this).apply {
            setImageResource(R.drawable.ic_lock)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(UNLOCK_BACKGROUND)
            }
            val inset = size / 4
            setPadding(inset, inset, inset, inset)
            contentDescription = getString(R.string.floating_unlock)
            setOnClickListener { host.update(host.state.floating.copy(locked = false)) }
        }
        // At the words' top right corner, over them, where it's found without covering a line.
        unlockParams.width = size
        unlockParams.height = size
        unlockParams.x = (params.x + params.width - size).coerceAtLeast(0)
        unlockParams.y = params.y
        unlock = button
        windows.addView(button, unlockParams)
    }

    private fun hideUnlock() {
        unlock?.let(windows::removeView)
        unlock = null
    }

    /**
     * Holds the words and moves their window with them: a finger dragged moves it, one dragged from
     * either side makes it wider or narrower, and two pinched make the words bigger or smaller. It
     * goes by where fingers are on screen, since the window, and everything in it, moves under them.
     * Taps, and drags on the sliders, are left to the words.
     */
    private inner class GestureFrame(context: Context, private val host: FloatingHost) : FrameLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val edge = EDGE_DP * resources.displayMetrics.density
        private val narrowest = (NARROWEST_DP * resources.displayMetrics.density).toInt()
        private var gesture = Gesture.NONE
        private var downX = 0f
        private var downY = 0f
        private var from = Rect()
        private var pinchFrom = 0f
        private var scaleFrom = 1f

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    from = Rect(params.x, params.y, params.x + params.width, params.y)
                    gesture = when {
                        sliders?.contains(event.x.toInt(), event.y.toInt()) == true -> Gesture.SLIDER
                        event.x < edge -> Gesture.LEFT
                        event.x > width - edge -> Gesture.RIGHT
                        else -> Gesture.NONE
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (gesture != Gesture.SLIDER) {
                    pinchFrom = spread(event)
                    scaleFrom = host.state.floating.scale
                    gesture = Gesture.PINCH
                    return true
                }
                // Past a tap's wobble it's a drag: the words' own taps are let go, and they move from here.
                MotionEvent.ACTION_MOVE -> if (gesture != Gesture.SLIDER && hypot(event.rawX - downX, event.rawY - downY) > slop) {
                    if (gesture == Gesture.NONE) gesture = Gesture.MOVE
                    drag(event)
                    return true
                }
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    pinchFrom = spread(event)
                    scaleFrom = host.state.floating.scale
                    gesture = Gesture.PINCH
                }
                MotionEvent.ACTION_MOVE -> drag(event)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    host.update(host.state.floating.copy(left = params.x, top = params.y, width = params.width))
                    gesture = Gesture.NONE
                }
            }
            return true
        }

        /**
         * Grown, e.g. with its buttons and their options out, it's moved up as far as it takes to stay
         * on screen; shrunk back, it goes back to where it was left. Android only gives it the room
         * below where it is, so it's measured with the whole screen's to know how tall it would be.
         */
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val screen = resources.displayMetrics.heightPixels
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(screen, MeasureSpec.AT_MOST))
            if (gesture != Gesture.NONE) return
            val left = host.state.floating.top.takeIf { it >= 0 } ?: params.y
            val top = left.coerceAtMost(maxOf(0, screen - measuredHeight))
            if (top == params.y) return
            params.y = top
            unlockParams.y = top
            // Not in the middle of measuring: once it's done.
            post {
                if (isAttachedToWindow) windows.updateViewLayout(this, params)
                unlock?.let { windows.updateViewLayout(it, unlockParams) }
            }
        }

        private fun spread(event: MotionEvent) =
            if (event.pointerCount < 2) 0f else hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))

        private fun drag(event: MotionEvent) {
            val screen = resources.displayMetrics
            val dx = (event.rawX - downX).toInt()
            val dy = (event.rawY - downY).toInt()
            when (gesture) {
                Gesture.PINCH -> {
                    val spread = spread(event)
                    if (pinchFrom <= 0f || spread <= 0f) return
                    val scale = (scaleFrom * spread / pinchFrom).coerceIn(FloatingOptions.MIN_SCALE, FloatingOptions.MAX_SCALE)
                    host.update(host.state.floating.copy(scale = scale), save = false)
                    return
                }
                Gesture.LEFT -> {
                    params.x = (from.left + dx).coerceIn(0, from.right - narrowest)
                    params.width = from.right - params.x
                }
                Gesture.RIGHT -> params.width = (from.width() + dx).coerceIn(narrowest, screen.widthPixels - from.left)
                else -> {
                    params.x = (from.left + dx).coerceIn(0, maxOf(0, screen.widthPixels - params.width))
                    params.y = (from.top + dy).coerceIn(0, maxOf(0, screen.heightPixels - height))
                }
            }
            windows.updateViewLayout(this, params)
        }
    }

    private enum class Gesture { NONE, MOVE, LEFT, RIGHT, PINCH, SLIDER }

    override fun onDestroy() {
        hideUnlock()
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

        private const val UNLOCK_DP = 40
        private const val UNLOCK_BACKGROUND = 0xCC000000.toInt()

        /** How far in from either side a drag makes the words wider or narrower rather than moving them. */
        private const val EDGE_DP = 24
        private const val NARROWEST_DP = 160
        /**
         * Room either side of the words, until they're first resized: enough to keep their sides,
         * dragged to resize, clear of the edges, where a swipe is Android's back gesture.
         */
        private const val MARGIN_DP = 32

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
