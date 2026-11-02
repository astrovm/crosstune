package com.astrovm.crosstune

import android.content.Context
import android.content.pm.PackageManager

// A TV is the one casts go to, never the one casting; and where Google Play Services is missing, GoogleCastSender
// finds no one to cast to, so the phone shows nothing.
internal fun platformCastSender(context: Context): CastSender =
    if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) NoCast else GoogleCastSender(context)

internal fun platformCastReceiver(context: Context): CastReceiver = GoogleCastReceiver(context)
