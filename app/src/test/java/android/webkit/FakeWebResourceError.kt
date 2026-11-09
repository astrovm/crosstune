package android.webkit

/** A page's load error, which only Android's own package may make. */
class FakeWebResourceError : WebResourceError() {
    override fun getErrorCode() = WebViewClient.ERROR_HOST_LOOKUP

    override fun getDescription(): CharSequence = "offline"
}
