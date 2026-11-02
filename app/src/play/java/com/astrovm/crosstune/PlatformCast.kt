package com.astrovm.crosstune

import android.content.Context

internal fun platformCastSender(context: Context): CastSender = NoCast

internal fun platformCastReceiver(context: Context): CastReceiver = NoCast
