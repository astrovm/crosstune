package com.astrovm.crosstune

import android.content.Context

/*
 * The build without Google Play Services has no casting: nothing here names a Google class, and
 * FossBuildTest checks that nothing in the build does.
 */

internal fun platformCastSender(context: Context): CastSender = NoCast

internal fun platformCastReceiver(context: Context): CastReceiver = NoCast
