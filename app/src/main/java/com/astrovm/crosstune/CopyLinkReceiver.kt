package com.astrovm.crosstune

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent

/**
 * Copies a link for the share sheet's "Copy … link" action, which Android runs while the share
 * sheet is still open. Android 13+ confirms the copy itself, and the action only exists on 14+.
 */
class CopyLinkReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val link = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Crosstune link", link))
    }
}
