package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The build without Google Play Services has none of it, and casts to nothing. */
@RunWith(RobolectricTestRunner::class)
class FossBuildTest {
    @Test
    fun noGoogleCodeIsInTheBuild() {
        assertThrows(ClassNotFoundException::class.java) { Class.forName("com.google.android.gms.cast.framework.CastContext") }
        assertThrows(ClassNotFoundException::class.java) { Class.forName("com.astrovm.crosstune.GoogleCastSender") }
    }

    @Test
    fun castingIsNoCast() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        assertSame(NoCast, platformCastSender(app))
        assertSame(NoCast, platformCastReceiver(app))
        assertEquals(false, NoCast.around.value)
    }
}
