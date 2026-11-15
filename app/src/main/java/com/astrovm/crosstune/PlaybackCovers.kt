package com.astrovm.crosstune

import android.content.Context
import android.graphics.Bitmap
import android.media.session.MediaSession
import java.lang.ref.WeakReference
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
    private class SessionEntry(val name: List<String?>, bitmap: Bitmap, val entry: Entry) {
        val bitmap = WeakReference(bitmap)
    }
    private val entries = WeakHashMap<Bitmap, Entry>()
    private val sessions = WeakHashMap<MediaSession.Token, SessionEntry>()

    fun forget(session: MediaSession.Token) = synchronized(entries) { sessions.remove(session); Unit }
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writing = Mutex()
    private val changed = MutableStateFlow(0L)
    val updates = changed.asStateFlow()

    fun of(bitmap: Bitmap, session: MediaSession.Token? = null, name: List<String?> = emptyList()): String? = synchronized(entries) {
        if (bitmap.isRecycled) return null
        val generation = bitmap.generationId
        // Binder gives each metadata read a new bitmap object. Keep the session's result until
        // its metadata callback changes it. The same local bitmap can still change its pixels.
        val known = session?.let { sessions[it] }?.takeIf {
            it.name == name && (it.bitmap.get() !== bitmap || it.entry.generation == generation)
        }?.entry ?: entries[bitmap]?.takeIf { it.generation == generation }
        known?.let { entry ->
            val file = entry.file
            if (file == null || file.isFile) return file?.toURI()?.toString()
        }
        val entry = Entry(generation)
        entries[bitmap] = entry
        if (session != null) sessions[session] = SessionEntry(name, bitmap, entry)
        work.launch {
            val file = writing.withLock { save(bitmap) }
            synchronized(entries) {
                if (entries[bitmap] === entry) {
                    if (file == null) {
                        entries.remove(bitmap)
                        sessions.entries.removeAll { it.value.entry === entry }
                    } else entry.file = file
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
