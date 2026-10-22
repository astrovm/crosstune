package com.astrovm.crosstune

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/** Shizuku, when the user runs it: adb's powers without a computer. Checked on a phone, which runs it. */
internal class ShizukuShell : Shell {
    override val root = false

    override fun ready(): Boolean = Shizuku.pingBinder() && !Shizuku.isPreV11()

    override fun watch(changed: () -> Unit): () -> Unit {
        val received = Shizuku.OnBinderReceivedListener { changed() }
        val dead = Shizuku.OnBinderDeadListener { changed() }
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        return {
            Shizuku.removeBinderReceivedListener(received)
            Shizuku.removeBinderDeadListener(dead)
        }
    }

    override suspend fun allowed(): Boolean {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true
        // Denied for good, Shizuku won't ask again.
        if (Shizuku.shouldShowRequestPermissionRationale()) return false
        return suspendCancellableCoroutine { asked ->
            val answered = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != REQUEST) return
                    Shizuku.removeRequestPermissionResultListener(this)
                    asked.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            Shizuku.addRequestPermissionResultListener(answered)
            asked.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(answered) }
            Shizuku.requestPermission(REQUEST)
        }
    }

    override suspend fun run(command: List<String>): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = IShizukuService.Stub.asInterface(Shizuku.getBinder()).newProcess(command.toTypedArray(), null, null)
            // Read to the end, so the command never waits on a full pipe.
            ParcelFileDescriptor.AutoCloseInputStream(process.inputStream).use { it.readBytes() }
            ParcelFileDescriptor.AutoCloseInputStream(process.errorStream).use { it.readBytes() }
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        const val REQUEST = 1
    }
}
