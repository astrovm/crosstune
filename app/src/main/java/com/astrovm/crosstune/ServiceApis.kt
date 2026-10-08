package com.astrovm.crosstune

import android.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The APIs behind TIDAL's, SoundCloud's and Audiomack's own websites, which need no account: what
 * each website itself asks with, for songs, albums, artists and playlists' songs. None is a public
 * API, so any of them may stop answering; everything that uses them falls back to what it did before.
 */
internal class ServiceApis(
    private val client: OkHttpClient,
    private val country: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** The clock and the one-off strings Audiomack's signatures are made with; tests fix them. */
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val nonce: () -> String = { UUID.randomUUID().toString().replace("-", "") }
) {
    private suspend fun get(url: HttpUrl, headers: Map<String, String> = emptyMap()): Any {
        val request = Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }.get().build()
        return client.newCall(request).executeAsync().use { response ->
            if (!response.isSuccessful) throw ServiceException(response.code)
            JSONTokener(withContext(ioDispatcher) { response.body.stringAtMost() }).nextValue()
        }
    }

    /** [get]'s answer as the object it should be; anything else, e.g. an error page, is no answer. */
    private suspend fun getObject(url: HttpUrl, headers: Map<String, String> = emptyMap()): JSONObject =
        get(url, headers) as? JSONObject ?: throw JSONException("Not an object")

    /** A service turned a request down, e.g. with a key it no longer takes. */
    class ServiceException(val code: Int) : IOException("Service answered $code")

    // TIDAL

    private val tidalCountry get() = country.takeIf { it.length == 2 } ?: "US"

    private suspend fun tidal(path: String, params: Map<String, String>): JSONObject {
        val url = "$TIDAL_API/$path".toHttpUrl().newBuilder()
            .apply { params.forEach { (name, value) -> addQueryParameter(name, value) } }
            .addQueryParameter("countryCode", tidalCountry)
            .build()
        return getObject(url, mapOf("x-tidal-token" to TIDAL_TOKEN))
    }

    /** What TIDAL finds for [query] among its "tracks", "albums" or "artists". */
    suspend fun tidalSearch(kind: String, query: String): List<JSONObject> =
        tidal("search/$kind", mapOf("query" to query, "limit" to "10")).optJSONArray("items").objects()

    /** An album's or playlist's songs, a page at a time, as far as [MAX_TRACKS]. */
    suspend fun tidalTracks(type: ItemType, id: String): List<MusicMetadata> {
        val path = if (type == ItemType.ALBUM) "albums/$id/tracks" else "playlists/$id/tracks"
        val tracks = mutableListOf<MusicMetadata>()
        while (tracks.size < MAX_TRACKS) {
            val page = tidal(path, mapOf("limit" to PAGE.toString(), "offset" to tracks.size.toString()))
            val items = page.optJSONArray("items").objects()
            tracks += items.mapNotNull(::tidalTrack)
            if (items.size < PAGE || tracks.size >= page.optInt("totalNumberOfItems")) break
        }
        return tracks
    }

    // SoundCloud

    private val soundCloudKey = Mutex()
    private var clientId: String? = null

    /**
     * The key SoundCloud's website asks its API with, read from the website's own code the first time
     * it's needed, and read again if SoundCloud stops taking it.
     */
    private suspend fun soundCloudId(fresh: Boolean): String = soundCloudKey.withLock {
        if (!fresh) clientId?.let { return it }
        val page = client.newCall(Request.Builder().url(SOUNDCLOUD_URL).header("User-Agent", BROWSER).get().build()).executeAsync()
            .use { withContext(ioDispatcher) { it.body.stringAtMost() } }
        // The key sits in one of the last scripts the page loads.
        for (script in soundCloudScriptRegex.findAll(page).map { it.groupValues[1] }.toList().asReversed()) {
            val code = client.newCall(Request.Builder().url(script).get().build()).executeAsync()
                .use { withContext(ioDispatcher) { it.body.stringAtMost(MAX_SCRIPT_BYTES) } }
            soundCloudIdRegex.find(code)?.let { found -> return found.groupValues[1].also { clientId = it } }
        }
        throw IOException("SoundCloud's key wasn't found")
    }

    private suspend fun soundCloud(path: String, params: Map<String, String>): Any {
        suspend fun ask(fresh: Boolean): Any {
            val url = "$SOUNDCLOUD_API/$path".toHttpUrl().newBuilder()
                .apply { params.forEach { (name, value) -> addQueryParameter(name, value) } }
                .addQueryParameter("client_id", soundCloudId(fresh))
                .build()
            return get(url)
        }
        return try {
            ask(fresh = false)
        } catch (refused: ServiceException) {
            // An old key is turned away; the website's code has the new one.
            if (refused.code != 401 && refused.code != 403) throw refused
            ask(fresh = true)
        }
    }

    /** What SoundCloud finds for [query] among its "tracks", "albums" or "users". */
    suspend fun soundCloudSearch(kind: String, query: String): List<JSONObject> =
        (soundCloud("search/$kind", mapOf("q" to query, "limit" to "10")) as? JSONObject)?.optJSONArray("collection").objects()

    /**
     * A set's songs. Its own answer has the first few in full and only the others' numbers, which
     * are asked for [SOUNDCLOUD_BATCH] at a time; they come back in any order, so they're put back in the set's.
     */
    suspend fun soundCloudTracks(url: String): List<MusicMetadata> {
        val set = soundCloud("resolve", mapOf("url" to url)) as? JSONObject ?: return emptyList()
        val listed = set.optJSONArray("tracks").objects().take(MAX_TRACKS)
        val known = listed.filter { it.has("title") }.associateBy { it.optLong("id") }.toMutableMap()
        listed.map { it.optLong("id") }.filterNot(known::containsKey).chunked(SOUNDCLOUD_BATCH).forEach { ids ->
            (soundCloud("tracks", mapOf("ids" to ids.joinToString(","))) as? JSONArray).objects().forEach { known[it.optLong("id")] = it }
        }
        return listed.mapNotNull { known[it.optLong("id")]?.let(::soundCloudTrack) }
    }

    // Audiomack

    /**
     * Audiomack's API wants each request signed, OAuth 1.0 style, with the key its website signs
     * with; no account, so no token.
     */
    private suspend fun audiomack(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val url = "$AUDIOMACK_API/$path"
        return getObject(url.toHttpUrl().newBuilder().apply { signed("GET", url, params).forEach { (name, value) -> addQueryParameter(name, value) } }.build())
    }

    /** [params] with the OAuth fields and their signature, as Audiomack checks them. */
    fun signed(method: String, url: String, params: Map<String, String>): List<Pair<String, String>> {
        val all = params + mapOf(
            "oauth_consumer_key" to AUDIOMACK_KEY,
            "oauth_nonce" to nonce(),
            "oauth_signature_method" to "HMAC-SHA1",
            "oauth_timestamp" to nowSeconds().toString(),
            "oauth_version" to "1.0"
        )
        val normalized = all.entries.map { encode(it.key) to encode(it.value) }.sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { "${it.first}=${it.second}" }
        val base = listOf(method, encode(url), encode(normalized)).joinToString("&")
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec("${encode(AUDIOMACK_SECRET)}&".toByteArray(), "HmacSHA1")) }
        val signature = Base64.encodeToString(mac.doFinal(base.toByteArray()), Base64.NO_WRAP)
        return all.toList() + ("oauth_signature" to signature)
    }

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")

    /** What Audiomack finds for [query] among its "songs", "albums" or "artists". */
    suspend fun audiomackSearch(kind: String, query: String): List<JSONObject> =
        audiomack("search", mapOf("q" to query, "show" to kind, "limit" to "10")).optJSONArray("results").objects()

    /** An album's or playlist's songs; [id] is "artist/name", as its link has it. */
    suspend fun audiomackTracks(type: ItemType, id: String): List<MusicMetadata> {
        val list = audiomack(if (type == ItemType.ALBUM) "music/album/$id" else "playlist/$id").optJSONObject("results") ?: return emptyList()
        // An album's songs share its cover and uploader.
        val cover = list.optString("image").ifBlank { null }
        val uploader = list.optJSONObject("uploader")?.optString("url_slug").orEmpty()
        return list.optJSONArray("tracks").objects().take(MAX_TRACKS).mapNotNull { audiomackTrack(it, uploader, cover) }
    }

    companion object {
        const val TIDAL_API = "https://api.tidal.com/v1"
        /** The token TIDAL's own web player asks its API with. */
        const val TIDAL_TOKEN = "CzET4vdadNUFQ5JU"
        const val SOUNDCLOUD_URL = "https://soundcloud.com/"
        const val SOUNDCLOUD_API = "https://api-v2.soundcloud.com"
        const val AUDIOMACK_API = "https://api.audiomack.com/v1"
        /** The key and secret Audiomack's own website signs its requests with. */
        const val AUDIOMACK_KEY = "audiomack-web"
        const val AUDIOMACK_SECRET = "bd8a07e9f23fbe9d808646b730f89b8e"

        /** As many of a list's songs as are worth listing; a queue plays 50 at a time. */
        const val MAX_TRACKS = 500
        private const val PAGE = 100
        const val SOUNDCLOUD_BATCH = 50
        private const val MAX_SCRIPT_BYTES = 8L * 1024 * 1024
        private const val BROWSER = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

        private val soundCloudScriptRegex = Regex("""<script crossorigin src="(https://a-v2\.sndcdn\.com/assets/[^"]+\.js)"""")
        private val soundCloudIdRegex = Regex("""client_id:"([A-Za-z0-9]{32})"""")

        /** A TIDAL song, with its version, e.g. "Remix", as TIDAL shows it, and its album's cover. */
        fun tidalTrack(json: JSONObject): MusicMetadata? {
            val title = json.optString("title").ifBlank { return null }
            val version = json.optString("version").takeIf { it.isNotBlank() && it != "null" }
            val artists = json.optJSONArray("artists").objects().map { it.optString("name") }.filter { it.isNotBlank() }
            return MusicMetadata(
                version?.let { "$title ($it)" } ?: title,
                artists.joinToString(", ").ifEmpty { json.optJSONObject("artist")?.optString("name").orEmpty() },
                artworkUrl = json.optJSONObject("album")?.let { tidalCover(it.optString("cover")) },
                url = "https://tidal.com/browse/track/${json.optLong("id")}"
            )
        }

        /** TIDAL names a cover by an id, whose dashes are folders on its image server. */
        fun tidalCover(id: String): String? = id.takeIf { it.isNotBlank() && it != "null" }?.let { "https://resources.tidal.com/images/${it.replace('-', '/')}/320x320.jpg" }

        /** A SoundCloud upload, by the artist it credits, or else by whoever uploaded it. */
        fun soundCloudTrack(json: JSONObject): MusicMetadata? {
            val title = json.optString("title").ifBlank { return null }
            val credited = json.optJSONObject("publisher_metadata")?.optString("artist")?.takeIf { it.isNotBlank() }
            return MusicMetadata(
                title,
                credited ?: json.optJSONObject("user")?.optString("username").orEmpty(),
                // SoundCloud's covers come small; the same picture is there bigger.
                artworkUrl = json.optString("artwork_url").takeIf { it.isNotBlank() && it != "null" }?.replace("-large.", "-t500x500."),
                url = json.optString("permalink_url").ifBlank { null }
            )
        }

        fun audiomackTrack(json: JSONObject, uploader: String, cover: String?): MusicMetadata? {
            val title = json.optString("title").ifBlank { return null }
            val owner = json.optJSONObject("uploader")?.optString("url_slug") ?: json.optString("uploader_url_slug").ifBlank { uploader }
            val slug = json.optString("url_slug")
            return MusicMetadata(
                title,
                json.optString("artist"),
                artworkUrl = json.optString("image").takeIf { it.isNotBlank() && it != "null" } ?: cover,
                url = if (owner.isNotEmpty() && slug.isNotEmpty()) "https://audiomack.com/$owner/song/$slug" else null
            )
        }
    }
}

internal fun JSONArray?.objects(): List<JSONObject> = this?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } }.orEmpty()
