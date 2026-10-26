package com.astrovm.crosstune

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Typing on the phone for a TV, whose remote is slow at it: the TV shows a code the phone's camera
 * opens as a page the TV serves on the Wi-Fi, and what's sent from it lands in the link field.
 */
internal interface PhoneTyping {
    /** Starts taking what the page sends, if it hasn't; the page's address, or null when the TV is on no network. */
    fun open(onText: (String) -> Unit): String?

    fun close()
}

/** The page's words, in the TV's language. */
internal data class PhonePageWords(val placeholder: String, val send: String)

/**
 * Serves the page on the TV's own address. The address has a secret in it, which only the code on
 * the TV's screen shows, so someone else on the Wi-Fi can't type into it.
 */
internal class WifiTyping(
    private val words: () -> PhonePageWords,
    private val address: () -> InetAddress? = { lanAddress(NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }.flatMap { it.inetAddresses.toList() }) },
    private val secret: String = newSecret()
) : PhoneTyping {
    @Volatile private var server: ServerSocket? = null
    private var link: String? = null

    @Synchronized
    override fun open(onText: (String) -> Unit): String? {
        link?.let { return it }
        val host = address() ?: return null
        val socket = ServerSocket(0, BACKLOG, host)
        server = socket
        thread(isDaemon = true, name = "PhoneTyping") {
            while (true) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use { answer(it, onText) }
            }
        }
        return "http://${host.hostAddress}:${socket.localPort}/$secret".also { link = it }
    }

    @Synchronized
    override fun close() {
        server?.close()
        server = null
        link = null
    }

    private fun answer(client: Socket, onText: (String) -> Unit) {
        client.soTimeout = READ_TIMEOUT_MS
        val response = runCatching { respond(BufferedInputStream(client.getInputStream()), onText) }.getOrDefault(BAD_REQUEST)
        runCatching { client.getOutputStream().apply { write(response.toByteArray()) }.flush() }
    }

    private fun respond(input: InputStream, onText: (String) -> Unit): String {
        val request = readLine(input).split(" ")
        val headers = generateSequence { readLine(input).takeIf { it.isNotEmpty() } }.toList()
        if (request.size < 2 || request[1].substringBefore('?') != "/$secret") return NOT_FOUND
        when (request[0]) {
            "GET" -> Unit
            "POST" -> {
                val length = headers.firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.toIntOrNull()?.takeIf { it in 0..MAX_BODY } ?: return BAD_REQUEST
                val body = ByteArray(length)
                var read = 0
                while (read < length) read += input.read(body, read, length - read).takeIf { it >= 0 } ?: return BAD_REQUEST
                val text = String(body).split('&').firstOrNull { it.startsWith("text=") }
                    ?.let { URLDecoder.decode(it.substringAfter('='), "UTF-8").trim() }
                if (!text.isNullOrEmpty()) onText(text)
            }
            else -> return BAD_REQUEST
        }
        val page = page(words())
        return "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${page.toByteArray().size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n$page"
    }

    /** A line of the request, without its line break, kept short, as nothing in this page's needs a long one. */
    private fun readLine(input: InputStream): String {
        val line = ByteArrayOutputStream()
        while (line.size() < MAX_LINE) {
            when (val byte = input.read()) {
                -1, '\n'.code -> break
                '\r'.code -> Unit
                else -> line.write(byte)
            }
        }
        return String(line.toByteArray(), Charsets.UTF_8)
    }

    internal companion object {
        private const val BACKLOG = 4
        private const val READ_TIMEOUT_MS = 5_000
        private const val MAX_LINE = 2_048
        /** Longer than any link or song's name. */
        internal const val MAX_BODY = 4_096
        private const val NOT_FOUND = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        private const val BAD_REQUEST = "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"

        private fun newSecret() = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

        /** The TV's address on the home network: a private IPv4 one, which a phone's camera opens as typed. */
        internal fun lanAddress(addresses: List<InetAddress>): InetAddress? =
            addresses.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }

        /** A field and a button, sending the form again after, so the field's empty for the next one. */
        internal fun page(words: PhonePageWords) = """
            <!doctype html>
            <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Crosstune</title>
            <style>
            body { margin: 0; padding: 24px; font: 18px system-ui, sans-serif; background: #1c1b20; color: #e6e1e9 }
            form { display: flex; flex-direction: column; gap: 16px; max-width: 600px; margin: auto }
            input { font: inherit; padding: 16px; border: 0; border-radius: 16px; background: #2b2930; color: inherit }
            button { font: inherit; font-weight: 600; padding: 16px; border: 0; border-radius: 28px; background: #cfbcff; color: #381e72 }
            </style></head>
            <body><form method="post">
            <input name="text" type="text" autofocus autocomplete="off" autocapitalize="off" maxlength="2000" placeholder="${escape(words.placeholder)}">
            <button>${escape(words.send)}</button>
            </form></body></html>
        """.trimIndent()

        private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}
