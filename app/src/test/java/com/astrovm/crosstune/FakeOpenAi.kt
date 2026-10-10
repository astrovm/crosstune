package com.astrovm.crosstune

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.net.URL
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * OpenAI's sign-in as Crosstune sees it: the token endpoint, the keys it signs with, and ID
 * tokens signed with them, all at the time [now] says.
 */
internal class FakeOpenAi(private val now: () -> Long) {
    val keys: KeyPair = newKeys()

    /** Where the browser was last sent to sign in. */
    lateinit var authorize: HttpUrl

    /** What the token endpoint says to the form it was sent; signs in, then renews, by default. */
    var tokenAnswer: (Map<String, String>) -> Pair<Int, String> = { form ->
        if (form["grant_type"] == "authorization_code") 200 to tokens(idToken(audience = form.getValue("client_id")))
        else 200 to tokens(access = "access-2", refresh = "refresh-2")
    }
    var jwks: () -> Pair<Int, String> = { 200 to jwksJson() }
    var revokeFails = false

    /** The models the account offers. */
    var models: () -> Pair<Int, String> = { 200 to modelsJson("gpt-6.1-sol" to "list", "gpt-6.1-sol-mini" to "list") }

    /** What ChatGPT streams back to a request with this body; "Hello" by default. */
    var reply: (JSONObject) -> Pair<Int, String> = { 200 to stream("Hel", "lo") }

    /** The requests ChatGPT was asked, as sent. */
    val asked = mutableListOf<JSONObject>()

    /** OpenAI's answer to [request], or null when it isn't for OpenAI. */
    fun answer(request: Request): Response? {
        val url = request.url.toString()
        val form = (request.body as? FormBody)?.let { body -> (0 until body.size).associate { body.name(it) to body.value(it) } }.orEmpty()
        val (code, body) = when (url) {
            ChatGpt.TOKEN_URL -> tokenAnswer(form)
            ChatGpt.JWKS_URL -> jwks()
            ChatGpt.REVOKE_URL -> if (revokeFails) throw IOException("offline") else 200 to ""
            ChatGpt.MODELS_URL -> models()
            ChatGpt.RESPONSES_URL -> {
                val body = JSONObject(okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                asked += body
                reply(body)
            }
            else -> return null
        }
        val response = FakeSpotify.html(request, body, code = code)
        if (url != ChatGpt.MODELS_URL && url != ChatGpt.RESPONSES_URL) return response
        // Closing an answer not read to its end reads the rest off the network, which Android
        // only allows away from the main thread.
        val source = object : okio.ForwardingSource(response.body.source()) {
            override fun close() {
                if (android.os.Looper.getMainLooper().isCurrentThread) throw android.os.NetworkOnMainThreadException()
                super.close()
            }
        }.buffer()
        return response.newBuilder().body(source.asResponseBody(response.body.contentType())).build()
    }

    fun tokens(idToken: String = idToken(), access: String = "access-1", refresh: String = "refresh-1", scope: String = PLAN_SCOPES): String =
        JSONObject().put("access_token", access).put("refresh_token", refresh).put("id_token", idToken)
            .put("token_type", "Bearer").put("expires_in", 3600).put("scope", scope).toString()

    fun jwksJson(kid: String = "key-1"): String {
        val public = keys.public as RSAPublicKey
        fun unsigned(n: BigInteger) = n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        val key = JSONObject().put("kty", "RSA").put("kid", kid).put("n", base64Url(unsigned(public.modulus))).put("e", base64Url(unsigned(public.publicExponent)))
        return JSONObject().put("keys", JSONArray().put(JSONObject().put("kid", "other")).put(key)).toString()
    }

    /** An ID token as OpenAI signs them, for the last sign-in unless told otherwise. */
    fun idToken(
        audience: Any = "oaiapp_1",
        nonce: String? = null,
        subject: String = "user-1",
        expires: Long = now() / 1000 + 3600,
        alg: String = "RS256",
        kid: String = "key-1",
        signer: KeyPair = keys
    ): String {
        val header = base64Url(JSONObject().put("alg", alg).put("kid", kid).toString().toByteArray())
        val claims = JSONObject().put("iss", "https://auth.openai.com").put("aud", audience).put("sub", subject)
            .put("email", EMAIL).put("nonce", nonce ?: authorize.queryParameter("nonce")).put("exp", expires)
        val payload = base64Url(claims.toString().toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(signer.private)
            update("$header.$payload".toByteArray())
            sign()
        }
        return "$header.$payload.${base64Url(signature)}"
    }

    /** The browser coming back to Crosstune with [query]; what the page it lands on says. */
    fun comeBack(query: String = "code=the-code&state=${authorize.queryParameter("state")}&client_id=oaiapp_1"): String =
        URL("${authorize.queryParameter("redirect_uri")}?$query").readText()

    companion object {
        const val EMAIL = "ana@example.com"

        fun modelsJson(vararg models: Pair<String, String>): String =
            JSONObject().put("models", JSONArray().apply { models.forEach { (slug, visibility) -> put(JSONObject().put("slug", slug).put("visibility", visibility)) } }).toString()

        /** An answer streamed as OpenAI does, [deltas] at a time, ending with [end]. */
        fun stream(vararg deltas: String, end: String? = """{"type":"response.completed"}"""): String =
            (deltas.map { JSONObject().put("type", "response.output_text.delta").put("delta", it).toString() } + listOfNotNull(end))
                .joinToString("") { "event: x\ndata: $it\n\n" }
        const val PLAN_SCOPES = "chatgpt.tokens.use.direct email offline_access openid profile resource.invoke"

        fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun newKeys(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }
}
