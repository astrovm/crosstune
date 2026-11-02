package com.astrovm.crosstune

import android.content.Context
import com.google.android.gms.cast.LaunchOptions
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.tv.CastReceiverOptions
import com.google.android.gms.cast.tv.ReceiverOptionsProvider

/** How the phone casts: to Crosstune on an Android TV (Cast Connect), and with no player or notification of Google's. */
internal class GoogleCastOptions : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(BuildConfig.CAST_APP_ID)
        .setLaunchOptions(LaunchOptions.Builder().setAndroidReceiverCompatible(true).build())
        .setCastMediaOptions(CastMediaOptions.Builder().setNotificationOptions(null).setMediaSessionEnabled(false).build())
        .setStopReceiverApplicationWhenEndingSession(false)
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/** How the TV answers: under the same app ID, and with the channel the phone's controls beyond Cast's go over. */
internal class GoogleCastReceiverOptions : ReceiverOptionsProvider {
    override fun getOptions(context: Context): CastReceiverOptions = CastReceiverOptions.Builder(context)
        .setCastAppId(BuildConfig.CAST_APP_ID)
        .setCustomNamespaces(listOf(CAST_NAMESPACE))
        .build()
}
