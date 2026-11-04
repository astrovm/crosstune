package com.astrovm.crosstune

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CastOptionsTest {
    @Test
    fun theAppOwnsThePlayerAndDisconnectingLeavesTheTvPlaying() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = GoogleCastOptions()
        assertNull(provider.getAdditionalSessionProviders(context))
        val options = provider.getCastOptions(context)
        assertEquals(BuildConfig.CAST_APP_ID, options.receiverApplicationId)
        assertFalse(options.stopReceiverApplicationWhenEndingSession)
        val media = requireNotNull(options.castMediaOptions)
        assertFalse(media.mediaSessionEnabled)
        assertNull(media.notificationOptions)
        assertEquals(listOf(CAST_NAMESPACE), GoogleCastReceiverOptions().getOptions(context).customNamespaces)
    }
}
