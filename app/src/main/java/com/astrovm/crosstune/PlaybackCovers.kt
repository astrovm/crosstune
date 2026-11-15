package com.astrovm.crosstune

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Bitmap covers saved away from the screen, shared by the app's playback readers. */
internal class PlaybackCovers(private val directory: File) {
    private class Entry(val generation: Int, var file: File? = null)
    private val entries = WeakHashMap<Bitmap, Entry>()
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writing = Mutex()
    private val changed = MutableStateFlow(0L)
    val updates = changed.asStateFlow()

    fun of(bitmap: Bitmap): String? = synchronized(entries) {
        if (bitmap.isRecycled) return null
        val generation = bitmap.generationId
        entries[bitmap]?.takeIf { it.generation == generation }?.let { entry ->
            val file = entry.file
            if (file == null || file.isFile) return file?.toURI()?.toString()
        }
        val entry = Entry(generation)
        entries[bitmap] = entry
        work.launch {
            val file = writing.withLock { save(bitmap) }
            synchronized(entries) {
                if (entries[bitmap] === entry) {
                    if (file == null) entries.remove(bitmap) else entry.file = file
                }
            }
            // A failed save has no new cover to show. A later playback update can try it again.
            if (file != null) changed.update { it + 1 }
        }
        null
    }

    private fun save(bitmap: Bitmap): File? {
        val bytes = runCatching {
            val output = ByteArrayOutputStream()
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) output.toByteArray() else null
        }.getOrNull() ?: return null
        val file = File(directory, "playing-${UUID.nameUUIDFromBytes(bytes)}.jpg")
        if (file.isFile) return file
        // All playback readers use this writer, so a partial file cannot be shared between writes.
        val part = File(file.path + ".part")
        val saved = runCatching {
            directory.mkdirs()
            part.writeBytes(bytes)
            part.renameTo(file)
        }.getOrDefault(false)
        if (saved) return file
        part.delete()
        return null
    }

    companion object {
        private val shared = WeakHashMap<Context, PlaybackCovers>()

        fun shared(context: Context): PlaybackCovers = synchronized(shared) {
            val app = context.applicationContext
            shared.getOrPut(app) { PlaybackCovers(File(app.cacheDir, ARTWORK_DIR)) }
        }
    }
}
