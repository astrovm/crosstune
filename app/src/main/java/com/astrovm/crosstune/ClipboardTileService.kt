package com.astrovm.crosstune

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/** Quick Settings tile that opens whatever music link is on the clipboard. */
class ClipboardTileService : TileService() {

    override fun onClick() {
        super.onClick()
        openAndCollapse(Intent().setClassName(this, MainActivity.PASTE_ALIAS).setAction(MainActivity.ACTION_PASTE_FROM_CLIPBOARD))
    }
}

/** Opens [intent] from a tile, closing Quick Settings, as each Android version lets it. */
@SuppressLint("StartActivityAndCollapseDeprecated")
internal fun TileService.openAndCollapse(intent: Intent) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
    } else {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
    }
}
