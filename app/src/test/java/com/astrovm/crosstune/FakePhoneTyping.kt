package com.astrovm.crosstune

/** Stands in for the page on the Wi-Fi: [type] is a phone sending what was typed on it. */
internal class FakePhoneTyping(var link: String? = "http://192.168.1.20:41234/5ec4e7") : PhoneTyping {
    private var onText: ((String) -> Unit)? = null
    var opened = 0
    var closed = false

    override fun open(onText: (String) -> Unit): String? {
        opened++
        this.onText = onText
        return link
    }

    override fun close() {
        closed = true
    }

    /** Sent from another thread, as the page's are. */
    fun type(text: String) = Thread { onText!!(text) }.apply { start() }.join()
}
