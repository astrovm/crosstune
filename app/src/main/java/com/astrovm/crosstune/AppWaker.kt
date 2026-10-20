package com.astrovm.crosstune

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.media.MediaBrowserService
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Wakes a music app that isn't running, in the background, to be asked for a song. */
internal fun interface AppWaker {
    /** The player of the first of [packages] installed, woken; null when none lets Crosstune, or none is. */
    suspend fun wake(packages: List<String>): MediaController?
}

/**
 * Wakes a music app through its media browser, as Android Auto and voice assistants start one, so
 * it plays without coming to the front. It's kept bound a while, so it isn't stopped before it does.
 * Binding another app needs a phone, where it's checked; what's asked of the app woken is tested.
 */
internal class MediaBrowserWaker(private val context: Context) : AppWaker {
    private val handler = Handler(Looper.getMainLooper())
    private var woken: MediaBrowser? = null

    override suspend fun wake(packages: List<String>): MediaController? = withContext(Dispatchers.Main) {
        val service = packages.firstNotNullOfOrNull { packageName ->
            context.packageManager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(packageName), 0).firstOrNull()?.serviceInfo
        } ?: return@withContext null
        letGo()
        var browser: MediaBrowser? = null
        var keep = false
        try {
            withTimeoutOrNull(WAKE_MS) {
                suspendCancellableCoroutine { asked ->
                    val connected = object : MediaBrowser.ConnectionCallback() {
                        override fun onConnected() {
                            if (asked.isActive) asked.resume(browser?.let { MediaController(context, it.sessionToken) })
                        }

                        // Only callers it knows, e.g. Android Auto, some apps let in.
                        override fun onConnectionFailed() {
                            if (asked.isActive) asked.resume(null)
                        }
                    }
                    // Asked as Android asks to resume what played last, which more apps let in.
                    val recent = Bundle().apply { putBoolean(MediaBrowserService.BrowserRoot.EXTRA_RECENT, true) }
                    browser = MediaBrowser(context, ComponentName(service.packageName, service.name), connected, recent).also {
                        woken = it
                        it.connect()
                    }
                }
            }.also { controller ->
                keep = controller != null
                if (keep) handler.postDelayed(::letGo, KEEP_MS)
            }
        } finally {
            // A cancelled request may never get an answer, so it must release the binding too.
            if (!keep && woken === browser) letGo()
        }
    }

    private fun letGo() {
        handler.removeCallbacksAndMessages(null)
        woken?.disconnect()
        woken = null
    }

    private companion object {
        const val WAKE_MS = 5_000L
        // Long enough for the app to start playing, after which it keeps itself running.
        const val KEEP_MS = 30_000L
    }
}
