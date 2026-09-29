package com.astrovm.crosstune

import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** No lookup should keep the user waiting longer than this, however slowly a server answers. */
private const val CALL_TIMEOUT_SECONDS = 20L

/** Pages and API responses are far smaller; anything bigger isn't what Crosstune asked for. */
internal const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024

internal fun httpClient(): OkHttpClient =
    OkHttpClient.Builder().callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

/** Like [ResponseBody.bytes], but fails instead of reading more than [maxBytes] into memory. */
internal fun ResponseBody.bytesAtMost(maxBytes: Long = MAX_RESPONSE_BYTES): ByteArray {
    val source = source()
    if (source.request(maxBytes + 1)) throw IOException("Response is larger than $maxBytes bytes")
    return source.buffer.readByteArray()
}

/** Like [ResponseBody.string], but fails instead of reading more than [maxBytes] into memory. */
internal fun ResponseBody.stringAtMost(maxBytes: Long = MAX_RESPONSE_BYTES): String =
    String(bytesAtMost(maxBytes), contentType()?.charset() ?: Charsets.UTF_8)
