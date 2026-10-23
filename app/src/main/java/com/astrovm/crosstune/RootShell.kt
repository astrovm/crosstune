package com.astrovm.crosstune

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Root, on a phone that has it, through su: Magisk, KernelSU and the like ask the user the first
 * time. Not checked on a phone: none at hand is rooted.
 */
internal class RootShell : Shell {
    override val root = true

    // Magisk shows su to every app; KernelSU only to apps already let in, in its own app.
    override fun ready(): Boolean = SU.any { File(it).exists() }

    override fun watch(changed: () -> Unit): () -> Unit = {}

    override suspend fun allowed(): Boolean = run(listOf("id"))

    override suspend fun run(command: List<String>): Boolean = withContext(Dispatchers.IO) {
        try {
            // Quoted, so su's shell runs it as given.
            val line = command.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
            val process = ProcessBuilder("su", "-c", line).redirectErrorStream(true).start()
            // Read to the end, so the command never waits on a full pipe.
            process.inputStream.use { it.readBytes() }
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        val SU = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "/su/bin/su")
    }
}
