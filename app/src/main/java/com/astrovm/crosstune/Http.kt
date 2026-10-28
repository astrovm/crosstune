package com.astrovm.crosstune

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.coroutines.executeAsync
import java.io.IOException
import java.util.concurrent.TimeUnit

/** No lookup should keep the user waiting longer than this, however slowly a server answers. */
private const val CALL_TIMEOUT_SECONDS = 20L

/** Pages and API responses are far smaller; anything bigger isn't what Crosstune asked for. */
internal const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024

internal fun httpClient(): OkHttpClient =
    OkHttpClient.Builder().callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

/**
 * Asks [this], hands the answer to [read], and closes it, all on [io]. Android stops the app for any
 * use of the network on the main thread, and closing an answer that wasn't read to its end, as an
 * error, a redirect or one too big isn't, still sends the server word to stop.
 */
internal suspend fun <T> Call.answer(io: CoroutineDispatcher, read: suspend (Response) -> T): T =
    withContext(io) { executeAsync().use { read(it) } }

/** Like [ResponseBody.bytes], but fails instead of reading more than [maxBytes] into memory. */
internal fun ResponseBody.bytesAtMost(maxBytes: Long = MAX_RESPONSE_BYTES): ByteArray {
    val source = source()
    if (source.request(maxBytes + 1)) throw IOException("Response is larger than $maxBytes bytes")
    return source.buffer.readByteArray()
}

/** Like [ResponseBody.string], but fails instead of reading more than [maxBytes] into memory. */
internal fun ResponseBody.stringAtMost(maxBytes: Long = MAX_RESPONSE_BYTES): String =
    String(bytesAtMost(maxBytes), contentType()?.charset() ?: Charsets.UTF_8)
