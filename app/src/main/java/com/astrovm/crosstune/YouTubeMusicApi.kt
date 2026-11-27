package com.astrovm.crosstune

import org.json.JSONObject

/** YouTube Music's own API, which its website and app ask. */
internal object YouTubeMusicApi {
    const val SEARCH_URL = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
    const val NEXT_URL = "https://music.youtube.com/youtubei/v1/next?prettyPrint=false"
    const val BROWSE_URL = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"

    /** The website, which answers anyone. */
    const val WEB_CLIENT = "WEB_REMIX"
    const val WEB_CLIENT_VERSION = "1.20240101.01.00"

    /** The Android app: only it is given words timed. */
    const val APP_CLIENT = "ANDROID_MUSIC"
    const val APP_CLIENT_VERSION = "7.21.50"

    /** A request as [client] at [version] sends it, answered in [language] when there's one; what's asked is put in after. */
    fun body(client: String = WEB_CLIENT, version: String = WEB_CLIENT_VERSION, language: String? = "en"): JSONObject =
        JSONObject().put("context", JSONObject().put("client", JSONObject().put("clientName", client).put("clientVersion", version).putOpt("hl", language)))
}
