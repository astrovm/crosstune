package com.astrovm.crosstune

import android.service.quicksettings.TileService

/**
 * Quick Settings tile that names the song playing nearby and shows its words, in time with it, from
 * wherever the phone is; leaving Crosstune then floats them over the other app.
 */
class LyricsTileService : TileService() {

    override fun onClick() {
        super.onClick()
        openAndCollapse(MainActivity.lyricsIntent(this))
    }
}
