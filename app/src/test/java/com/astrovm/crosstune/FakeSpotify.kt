package com.astrovm.crosstune

import android.graphics.Bitmap
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Collections

/**
 * In-memory stand-in for Spotify's web endpoints so tests never touch the network.
 */
class FakeSpotify : Interceptor {
    val requestedUrls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** What each request sent, empty for a GET. */
    val requestBodies: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var handler: (Request) -> Response = { request -> html(request, "") }

    /** How long each answer takes to arrive, for a test of a lookup that runs out of time. */
    @Volatile
    var delayMillis: Long = 0

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        requestedUrls += request.url.toString()
        requestBodies += request.body?.let { body -> Buffer().also(body::writeTo).readUtf8() }.orEmpty()
        if (delayMillis > 0) Thread.sleep(delayMillis)
        return handler(request)
    }

    fun client(): OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

    companion object {
        private val HTML = "text/html; charset=utf-8".toMediaType()

        fun html(request: Request, body: String, finalUrl: String? = null, code: Int = 200): Response {
            val servedRequest = finalUrl?.let { request.newBuilder().url(it).build() } ?: request
            return Response.Builder()
                .request(servedRequest)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("Status $code")
                .body(body.toResponseBody(HTML))
                .build()
        }

        fun brokenBody(request: Request): Response {
            val failing = object : ForwardingSource(Buffer()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    throw IOException("stream reset")
                }
            }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(failing.buffer().asResponseBody(HTML, -1))
                .build()
        }

        fun trackPage(title: String?, description: String?, image: String? = null): String = buildString {
            append("<html><head>")
            if (title != null) append("<meta property=\"og:title\" content=\"$title\"/>")
            if (description != null) append("<meta property=\"og:description\" content=\"$description\"/>")
            if (image != null) append("<meta property=\"og:image\" content=\"$image\"/>")
            append("</head><body></body></html>")
        }

        /** A small real PNG, so decoding behaves as it would on a device. */
        fun png(size: Int = 4): ByteArray = ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, out)
        }.toByteArray()

        fun image(request: Request, bytes: ByteArray, code: Int = 200): Response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Status $code")
            .body(bytes.toResponseBody("image/png".toMediaType()))
            .build()
    }
}
