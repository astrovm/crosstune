package com.astrovm.crosstune

import android.app.Application
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** When the phone's too busy or hot for the visuals' frames to say how heavy they are. */
@RunWith(RobolectricTestRunner::class)
class BusyTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun thePhoneIsBusyWhileAnyWorkRunsEvenOneThatFails() {
        assertFalse(Busy.now)
        val inside = Busy.during { Busy.during { Busy.now } to Busy.now }
        assertEquals(true to true, inside)
        assertFalse(Busy.now)
        runCatching { Busy.during { error("failed") } }
        assertFalse(Busy.now)
    }

    @Test
    fun thePhoneIsHotFromWhenAndroidSaysItsStartingToSlowDownTillItsStopped() {
        val power = shadowOf(context.getSystemService(PowerManager::class.java))
        val heat = Heat()
        power.setCurrentThermalStatus(PowerManager.THERMAL_STATUS_LIGHT)
        val stop = heat.follow(context)
        assertFalse(heat.hot)
        power.setCurrentThermalStatus(PowerManager.THERMAL_STATUS_MODERATE)
        assertTrue(heat.hot)
        power.setCurrentThermalStatus(PowerManager.THERMAL_STATUS_NONE)
        assertFalse(heat.hot)
        stop()
        assertEquals(0, power.thermalStatusListeners.size)
        power.setCurrentThermalStatus(PowerManager.THERMAL_STATUS_SEVERE)
        assertFalse(heat.hot)
    }

    @Test
    @Config(sdk = [29])
    fun fromAndroid10ThePhoneCanBeHot() {
        val power = shadowOf(context.getSystemService(PowerManager::class.java))
        val heat = Heat()
        val stop = heat.follow(context)
        power.setCurrentThermalStatus(PowerManager.THERMAL_STATUS_MODERATE)
        assertTrue(heat.hot)
        stop()
    }

    @Test
    @Config(sdk = [28])
    fun beforeAndroid10ThePhoneIsNeverHot() {
        val heat = Heat()
        heat.follow(context)()
        assertFalse(heat.hot)
    }
}
