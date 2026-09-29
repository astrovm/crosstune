package com.astrovm.crosstune

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
import java.io.IOException
import java.util.Collections

/**
 * In-memory stand-in for Spotify's web endpoints so tests never touch the network.
 */
class FakeSpotify : Interceptor {
    val requestedUrls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var handler: (Request) -> Response = { request -> html(request, "") }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        requestedUrls += request.url.toString()
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

        fun trackPage(title: String?, description: String?): String = buildString {
            append("<html><head>")
            if (title != null) append("<meta property=\"og:title\" content=\"$title\"/>")
            if (description != null) append("<meta property=\"og:description\" content=\"$description\"/>")
            append("</head><body></body></html>")
        }
    }
}
