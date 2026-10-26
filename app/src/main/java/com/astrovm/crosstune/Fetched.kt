package com.astrovm.crosstune

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Fetches [url] into [file], and keeps it only when it's the very file it was tried with, by its
 * [sha256]: one cut short or changed is no use. Written to a part first, so [file] is whole or not there.
 */
internal fun OkHttpClient.fetchChecked(url: String, sha256: String, file: File): Boolean {
    file.parentFile?.mkdirs()
    val part = File(file.path + ".part")
    try {
        newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return false
            val digest = MessageDigest.getInstance("SHA-256")
            part.outputStream().use { out ->
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                    }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) } == sha256 && part.renameTo(file)
        }
    } catch (_: IOException) {
        return false
    } finally {
        part.delete()
    }
}

private const val BUFFER = 64 * 1024
