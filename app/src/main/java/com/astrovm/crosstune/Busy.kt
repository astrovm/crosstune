package com.astrovm.crosstune

import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether the app's working the phone hard right now: hearing the words sung, or making a song's
 * fingerprint for Shazam. The visuals' frames drawn meanwhile say more about that than about how
 * heavy a visual is, so they aren't timed.
 */
internal object Busy {
    private val working = AtomicInteger()

    val now: Boolean get() = working.get() > 0

    /** [work], with the phone busy while it runs. */
    fun <T> during(work: () -> T): T {
        working.incrementAndGet()
        try {
            return work()
        } finally {
            working.decrementAndGet()
        }
    }
}

/**
 * Whether Android says the phone's getting hot, from Android 10, when it starts slowing itself down:
 * frames drawn then say more about that than about how heavy a visual is.
 */
internal class Heat {
    @Volatile
    var hot = false
        private set

    /** Follows how hot the phone is till the returned stop is called. */
    fun follow(context: Context): () -> Unit {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return {}
        val power = context.getSystemService(PowerManager::class.java) ?: return {}
        val listener = PowerManager.OnThermalStatusChangedListener { status -> hot = status >= PowerManager.THERMAL_STATUS_MODERATE }
        // Told on the main thread, at once and at each change.
        power.addThermalStatusListener(listener)
        return { power.removeThermalStatusListener(listener) }
    }
}
