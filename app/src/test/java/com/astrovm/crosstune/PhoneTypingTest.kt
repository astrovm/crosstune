package com.astrovm.crosstune

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/** The page a phone types on for a TV, served for real on this computer's own address. */
class PhoneTypingTest {
    private val sent = CopyOnWriteArrayList<String>()
    private val typing = WifiTyping({ PhonePageWords("Paste a <link> & \"name\"", "Send") }, { InetAddress.getLoopbackAddress() }, secret = SECRET)

    @After
    fun tearDown() = typing.close()

    private fun open() = typing.open { sent += it }!!

    /** What the TV answers a raw request with, as a phone's browser would send it. */
    private fun ask(link: String, request: (path: String) -> String): String {
        val port = link.substringAfterLast(':').substringBefore('/').toInt()
        val path = "/" + link.substringAfterLast(':').substringAfter('/')
        return Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.getOutputStream().apply { write(request(path).toByteArray()) }.flush()
            socket.shutdownOutput()
            socket.getInputStream().readBytes().decodeToString()
        }
    }

    private fun post(link: String, body: String, path: String? = null) =
        ask(link) { "POST ${path ?: it} HTTP/1.1\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body" }

    @Test
    fun theCodeOpensAPageWithAFieldAndASendButtonInTheTvsLanguage() {
        val link = open()
        assertTrue(link, link.matches(Regex("http://127\\.0\\.0\\.1:\\d+/$SECRET")))
        val page = ask(link) { "GET $it HTTP/1.1\r\nHost: tv\r\n\r\n" }
        assertTrue(page, page.startsWith("HTTP/1.1 200 OK"))
        // Its words are the TV's, which can't break the page.
        assertTrue(page, page.contains("placeholder=\"Paste a &lt;link&gt; &amp; &quot;name&quot;\""))
        assertTrue(page, page.contains("<button>Send</button>"))
        // As long as it says.
        val length = Regex("Content-Length: (\\d+)").find(page)!!.groupValues[1].toInt()
        assertEquals(length, page.substringAfter("\r\n\r\n").toByteArray().size)
        assertEquals(emptyList<String>(), sent)
    }

    @Test
    fun whatsSentFromThePageReachesTheTvAndThePageComesBackEmpty() {
        val link = open()
        val answer = post(link, "text=+https%3A%2F%2Fopen.spotify.com%2Ftrack%2Fabc+")
        assertEquals(listOf("https://open.spotify.com/track/abc"), sent)
        assertTrue(answer.startsWith("HTTP/1.1 200 OK"))
        assertTrue(answer.contains("<input name=\"text\""))
        // A name in any language, with other fields around it.
        post(link, "x=1&text=%E5%A4%9C%E3%81%AB%E9%A7%86%E3%81%91%E3%82%8B&y=2")
        assertEquals("夜に駆ける", sent.last())
    }

    @Test
    fun nothingTypedSendsNothing() {
        val link = open()
        post(link, "text=+++")
        post(link, "other=abc")
        assertEquals(emptyList<String>(), sent)
    }

    @Test
    fun withoutTheSecretThereIsNoPage() {
        val link = open()
        assertTrue(ask(link) { "GET / HTTP/1.1\r\n\r\n" }.startsWith("HTTP/1.1 404"))
        assertTrue(post(link, "text=abc", path = "/0123").startsWith("HTTP/1.1 404"))
        // Nor half a request.
        assertTrue(ask(link) { "" }.startsWith("HTTP/1.1 404"))
        assertEquals(emptyList<String>(), sent)
    }

    @Test
    fun aRequestItCantReadIsTurnedAway() {
        val link = open()
        assertTrue(ask(link) { "DELETE $it HTTP/1.1\r\n\r\n" }.startsWith("HTTP/1.1 400"))
        // No length, one too long, or a body cut short.
        assertTrue(ask(link) { "POST $it HTTP/1.1\r\n\r\ntext=abc" }.startsWith("HTTP/1.1 400"))
        assertTrue(ask(link) { "POST $it HTTP/1.1\r\nContent-Length: ${WifiTyping.MAX_BODY + 1}\r\n\r\ntext=abc" }.startsWith("HTTP/1.1 400"))
        assertTrue(ask(link) { "POST $it HTTP/1.1\r\nContent-Length: 50\r\n\r\ntext=abc" }.startsWith("HTTP/1.1 400"))
        // A line longer than any request's is cut, rather than read forever.
        assertTrue(ask(link) { "GET $it${"a".repeat(5_000)} HTTP/1.1\r\n\r\n" }.startsWith("HTTP/1.1 404"))
        assertEquals(emptyList<String>(), sent)
        // It still answers after.
        post(link, "text=abc")
        assertEquals(listOf("abc"), sent)
    }

    @Test
    fun openedAgainItsTheSamePageUntilClosed() {
        val link = open()
        assertEquals(link, open())
        typing.close()
        val again = open()
        // The same secret, on a port of its own.
        assertNotEquals(link, again)
        assertEquals(link.substringAfterLast('/'), again.substringAfterLast('/'))
        post(again, "text=abc")
        assertEquals(listOf("abc"), sent)
        // Closing twice is fine.
        typing.close()
        typing.close()
    }

    @Test
    fun offTheNetworkThereIsNoPage() {
        assertNull(WifiTyping({ PhonePageWords("", "") }, { null }).open { sent += it })
    }

    @Test
    fun theTvsOwnAddressIsUsedAndNoOtherKind() {
        // Whatever this computer has; each secret is new.
        val one = WifiTyping({ PhonePageWords("", "") })
        val two = WifiTyping({ PhonePageWords("", "") })
        try {
            val first = one.open {}
            val second = two.open {}
            if (first != null && second != null) assertNotEquals(first.substringAfterLast('/'), second.substringAfterLast('/'))
        } finally {
            one.close()
            two.close()
        }
        val home = InetAddress.getByName("192.168.1.20")
        assertEquals(home, WifiTyping.lanAddress(listOf(InetAddress.getByName("fe80::1"), InetAddress.getByName("8.8.8.8"), home)))
        assertEquals(InetAddress.getByName("10.0.0.5"), WifiTyping.lanAddress(listOf(InetAddress.getByName("10.0.0.5"))))
        assertNull(WifiTyping.lanAddress(listOf(InetAddress.getByName("fd00::1"), InetAddress.getLoopbackAddress(), InetAddress.getByName("1.1.1.1"))))
    }

    private companion object {
        const val SECRET = "5ec4e7"
    }
}
