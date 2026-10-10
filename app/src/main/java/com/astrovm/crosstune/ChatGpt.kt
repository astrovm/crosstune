package com.astrovm.crosstune

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/** What ChatGPT answered: [text], or why there's none. */
internal sealed interface ChatGptReply {
    data class Answer(val text: String) : ChatGptReply

    /** The user's plan, or what they let Crosstune use of it, is used up for now. */
    data object LimitReached : ChatGptReply

    /** Signed out, offline, or anything else that kept it from answering. */
    data object Failed : ChatGptReply
}

/** A model the account offers: [slug] to ask for it by, [name] to show. */
internal data class ChatGptModel(val slug: String, val name: String)

/** How signing in with ChatGPT ended. */
internal enum class ChatGptSignInResult {
    SIGNED_IN,

    /** Signed in, but Crosstune wasn't allowed to use the ChatGPT plan, which is all it's for. */
    DECLINED,
    FAILED
}

/**
 * Signs in with ChatGPT, so Crosstune's AI features run on the user's ChatGPT plan, the way OpenAI
 * lays out for open-source apps: https://developers.openai.com/siwc/token-sharing-open-source.
 * The browser signs in and comes back to a listener on the phone itself, on [LOOPBACK]. The
 * tokens are kept in [file], which should be left out of backups, and only ever go to OpenAI.
 */
internal class ChatGpt(
    private val client: OkHttpClient,
    private val file: File,
    /** Crosstune's package, which the page the browser comes back to sends it back to. */
    private val appPackage: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    /** The port the browser comes back to, which only a sign-in after the first may change. */
    private val callbackPort: Int = CALLBACK_PORT
) {
    private val random = SecureRandom()
    private val tokens = Mutex()
    private var saved: JSONObject? = null

    /** Answers take as long as they take to write, far longer than any lookup. */
    private val answers = client.newBuilder().callTimeout(ANSWER_TIMEOUT_SECONDS, TimeUnit.SECONDS).readTimeout(ANSWER_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

    /** The models the account offers, once read. */
    private var offered: List<ChatGptModel>? = null

    /** The model picked in Settings, asked while the account offers it. */
    @Volatile
    var picked: String? = null

    /** The models the account offers, to pick from; null when they can't be read, e.g. offline. */
    suspend fun models(): List<ChatGptModel>? {
        offered?.let { return it }
        val token = accessToken() ?: return null
        return try {
            fetchModels(token)?.also { offered = it }
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        }
    }

    /** The model asked: the one picked, while the account offers it, or else [defaultModel]'s. */
    suspend fun model(): String? {
        val models = models() ?: return null
        return models.firstOrNull { it.slug == picked }?.slug ?: defaultModel(models)?.slug
    }

    /**
     * What ChatGPT answers to [input], told how by [instructions], on the user's plan. [onText] gets
     * the answer so far as it's written.
     */
    suspend fun ask(instructions: String, input: String, onText: (String) -> Unit = {}): ChatGptReply {
        val token = accessToken() ?: return ChatGptReply.Failed
        return try {
            val model = model() ?: return ChatGptReply.Failed
            val body = JSONObject()
                .put("model", model)
                .put("instructions", instructions)
                .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", input)))
                // OpenAI asks for both, on every request on the user's plan.
                .put("store", false)
                .put("stream", true)
            val request = Request.Builder().url(RESPONSES_URL).header("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody(JSON)).build()
            // All of it away from the main thread, closing too: an answer read only up to "completed"
            // leaves the rest of the stream, which closing reads off the network.
            withContext(ioDispatcher) {
                answers.newCall(request).executeAsync().use { response ->
                    when {
                        response.code == 429 -> ChatGptReply.LimitReached
                        !response.isSuccessful -> ChatGptReply.Failed
                        else -> read(response.body.source(), onText)
                    }
                }
            }
        } catch (_: IOException) {
            ChatGptReply.Failed
        } catch (_: JSONException) {
            ChatGptReply.Failed
        }
    }

    /**
     * The answer streamed in [source], one event at a time, done only once it says it's complete;
     * it may also stop partway when the plan runs out.
     */
    private fun read(source: okio.BufferedSource, onText: (String) -> Unit): ChatGptReply {
        val text = StringBuilder()
        while (true) {
            val line = source.readUtf8Line() ?: return ChatGptReply.Failed
            if (!line.startsWith("data:")) continue
            val event = runCatching { JSONObject(line.removePrefix("data:").trim()) }.getOrNull() ?: continue
            when (event.optString("type")) {
                "response.output_text.delta" -> {
                    text.append(event.optString("delta"))
                    if (text.length > MAX_ANSWER_CHARS) return ChatGptReply.Failed
                    onText(text.toString())
                }
                "response.completed" -> return ChatGptReply.Answer(text.toString())
                // Wherever in the event the error says what it is.
                "response.failed", "response.incomplete", "error" -> return if (USAGE_LIMITS.any { it in line }) ChatGptReply.LimitReached else ChatGptReply.Failed
            }
        }
    }

    /** The models the account lists to pick from; it also has some it keeps out of sight. */
    private suspend fun fetchModels(token: String): List<ChatGptModel>? {
        val request = Request.Builder().url(MODELS_URL).header("Authorization", "Bearer $token").build()
        val models = withContext(ioDispatcher) {
            client.newCall(request).executeAsync().use { response ->
                if (response.isSuccessful) JSONObject(response.body.stringAtMost()).getJSONArray("models") else null
            }
        } ?: return null
        return (0 until models.length()).map(models::getJSONObject).filter { it.optString("visibility") == "list" }.map { model ->
            val slug = model.getString("slug")
            ChatGptModel(slug, model.optString("display_name").trim().ifEmpty { slug })
        }
    }

    /** The email signed in with, or null when signed out. */
    suspend fun email(): String? = tokens.withLock { load().takeIf { it.has(ACCESS_TOKEN) }?.optString(EMAIL) }

    /**
     * Opens the browser with [open] to sign in, then waits for it to come back with the result, for
     * up to [timeoutMs]. Signing in again reuses the registration the first sign-in made.
     */
    suspend fun signIn(open: suspend (String) -> Unit, timeoutMs: Long = SIGN_IN_TIMEOUT_MS): ChatGptSignInResult {
        val account = tokens.withLock { load() }
        val clientId = account.optString(CLIENT_ID).ifEmpty { null }
        val state = randomString()
        val nonce = randomString()
        val verifier = randomString()
        val callback = withContext(ioDispatcher) { Callback.open(callbackPort, appPackage) }
        val query = try {
            val redirectUri = "http://127.0.0.1:${callback.port}$CALLBACK_PATH"
            val url = AUTHORIZE_URL.toHttpUrl().newBuilder()
                .addQueryParameter("client_id", clientId ?: DYNAMIC_CLIENT)
                .apply { if (clientId == null) addQueryParameter("agent_name_hint", AGENT_NAME) }
                .addQueryParameter("ext_agent_host_id", account.getString(HOST_ID))
                .apply { account.optString(ID_TOKEN).ifEmpty { null }?.let { addQueryParameter("id_token_hint", it) } }
                .apply { account.optString(EMAIL).ifEmpty { null }?.let { addQueryParameter("login_hint", it) } }
                // Asked again after it wasn't allowed, the plan's use is asked for again too.
                .apply { if (account.optBoolean(DECLINED)) addQueryParameter("prompt", "consent") }
                .addQueryParameter("response_type", "code")
                .addQueryParameter("redirect_uri", redirectUri)
                .addQueryParameter("scope", SCOPES)
                .addQueryParameter("resource", RESOURCE)
                .addQueryParameter("state", state)
                .addQueryParameter("nonce", nonce)
                .addQueryParameter("code_challenge_method", "S256")
                .addQueryParameter("code_challenge", base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())))
                .build()
            open(url.toString())
            withTimeoutOrNull(timeoutMs) { withContext(ioDispatcher) { callback.await() } }?.let { it to redirectUri }
        } finally {
            callback.close()
        } ?: return ChatGptSignInResult.FAILED
        val (answer, redirectUri) = query
        if (answer.queryParameter("state") != state) return ChatGptSignInResult.FAILED
        if (answer.queryParameter("error") == "access_denied") return ChatGptSignInResult.DECLINED
        val code = answer.queryParameter("code") ?: return ChatGptSignInResult.FAILED
        // A first sign-in gets the registration's ID back; a later one may say it again, but no other.
        val issued = answer.queryParameter("client_id")
        val registration = clientId ?: issued ?: return ChatGptSignInResult.FAILED
        if (issued != null && issued != registration) return ChatGptSignInResult.FAILED
        return try {
            val form = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", registration)
                .add("code", code)
                .add("code_verifier", verifier)
                .add("redirect_uri", redirectUri)
                .add("resource", RESOURCE)
                .build()
            val response = JSONObject(post(form) ?: return ChatGptSignInResult.FAILED)
            val idToken = response.getString("id_token")
            val claims = verified(idToken, registration, nonce) ?: return ChatGptSignInResult.FAILED
            // The registration is the account's: signed in again, it must be the same account.
            if (account.has(SUBJECT) && account.getString(SUBJECT) != claims.getString("sub")) return ChatGptSignInResult.FAILED
            tokens.withLock {
                account.put(CLIENT_ID, registration).put(SUBJECT, claims.getString("sub")).put(EMAIL, claims.optString("email")).put(ID_TOKEN, idToken)
                val usesPlan = PLAN_SCOPE in response.optString("scope").split(" ")
                if (usesPlan) keep(account, response) else forget(account)
                if (usesPlan) account.remove(DECLINED) else account.put(DECLINED, true)
                write(account)
                if (usesPlan) ChatGptSignInResult.SIGNED_IN else ChatGptSignInResult.DECLINED
            }
        } catch (_: IOException) {
            ChatGptSignInResult.FAILED
        } catch (_: JSONException) {
            ChatGptSignInResult.FAILED
        } catch (_: IllegalArgumentException) {
            // Not base64, so not a token OpenAI made.
            ChatGptSignInResult.FAILED
        }
    }

    /**
     * A token to call OpenAI with, renewed when it's about to run out; null when signed out, or
     * when it couldn't be renewed, maybe offline. Renewing won't ever happen twice at once, as each
     * renewal replaces the token it was renewed with.
     */
    suspend fun accessToken(): String? = tokens.withLock {
        val account = load()
        if (!account.has(ACCESS_TOKEN)) return null
        if (account.getLong(EXPIRES_AT) - now() > RENEW_BEFORE_MS) return account.getString(ACCESS_TOKEN)
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", account.getString(CLIENT_ID))
            .add("refresh_token", account.getString(REFRESH_TOKEN))
            .add("resource", RESOURCE)
            .build()
        try {
            val request = Request.Builder().url(TOKEN_URL).post(form).build()
            client.newCall(request).executeAsync().use { response ->
                val body = withContext(ioDispatcher) { response.body.stringAtMost() }
                if (response.isSuccessful) {
                    keep(account, JSONObject(body))
                    write(account)
                    return account.getString(ACCESS_TOKEN)
                }
                // Not to be used again, so signing in again is the only way back; anything else may pass.
                val error = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
                if (error in UNUSABLE) {
                    forget(account)
                    write(account)
                }
                null
            }
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        }
    }

    /** Signs out, asking OpenAI to end the session too; the registration is kept for signing in again. */
    suspend fun signOut() = tokens.withLock {
        // Another account may offer others.
        offered = null
        val account = load()
        val refresh = account.optString(REFRESH_TOKEN).ifEmpty { null }
        if (refresh != null) {
            val form = FormBody.Builder()
                .add("token", refresh)
                .add("token_type_hint", "refresh_token")
                .add("client_id", account.getString(CLIENT_ID))
                .build()
            // Signed out here either way: the user can still disconnect Crosstune in ChatGPT.
            try {
                client.newCall(Request.Builder().url(REVOKE_URL).post(form).build()).executeAsync().close()
            } catch (_: IOException) {
            }
        }
        forget(account)
        // Signed out, the next sign-in is free to pick another account.
        account.remove(ID_TOKEN)
        write(account)
    }

    private fun keep(account: JSONObject, response: JSONObject) {
        account.put(ACCESS_TOKEN, response.getString("access_token"))
            .put(REFRESH_TOKEN, response.getString("refresh_token"))
            .put(EXPIRES_AT, now() + response.getLong("expires_in") * 1000)
    }

    private fun forget(account: JSONObject) {
        listOf(ACCESS_TOKEN, REFRESH_TOKEN, EXPIRES_AT).forEach(account::remove)
    }

    /** The token endpoint's answer to [form], or null when it refused. */
    private suspend fun post(form: FormBody): String? {
        val request = Request.Builder().url(TOKEN_URL).post(form).build()
        return client.newCall(request).executeAsync().use { response ->
            if (response.isSuccessful) withContext(ioDispatcher) { response.body.stringAtMost() } else null
        }
    }

    /**
     * The claims of [idToken] when OpenAI signed it, for this sign-in: for [clientId], with [nonce],
     * and not expired. Null when it's anything else.
     */
    private suspend fun verified(idToken: String, clientId: String, nonce: String): JSONObject? {
        val parts = idToken.split(".")
        if (parts.size != 3) return null
        val header = JSONObject(String(base64UrlDecode(parts[0])))
        if (header.optString("alg") != "RS256") return null
        val keys = client.newCall(Request.Builder().url(JWKS_URL).build()).executeAsync().use { response ->
            if (!response.isSuccessful) return null
            JSONObject(withContext(ioDispatcher) { response.body.stringAtMost() }).getJSONArray("keys")
        }
        val key = (0 until keys.length()).map(keys::getJSONObject).firstOrNull { it.optString("kid") == header.optString("kid") } ?: return null
        // Whatever keeps it from being checked, a key or signature that isn't one, it isn't OpenAI's.
        val valid = runCatching {
            val spec = RSAPublicKeySpec(BigInteger(1, base64UrlDecode(key.getString("n"))), BigInteger(1, base64UrlDecode(key.getString("e"))))
            Signature.getInstance("SHA256withRSA").run {
                initVerify(KeyFactory.getInstance("RSA").generatePublic(spec))
                update("${parts[0]}.${parts[1]}".toByteArray())
                verify(base64UrlDecode(parts[2]))
            }
        }.getOrDefault(false)
        if (!valid) return null
        val claims = JSONObject(String(base64UrlDecode(parts[1])))
        val audience = claims.opt("aud")
        val forUs = audience == clientId || audience is JSONArray && (0 until audience.length()).any { audience.optString(it) == clientId }
        return claims.takeIf {
            it.optString("iss") == ISSUER && forUs && it.optString("nonce") == nonce && it.optLong("exp") * 1000 > now()
        }
    }

    /** What's kept, read once; a new host ID is made and kept before the first sign-in. */
    private suspend fun load(): JSONObject {
        saved?.let { return it }
        val account = withContext(ioDispatcher) {
            try {
                if (file.exists()) JSONObject(file.readText()) else JSONObject()
            } catch (_: JSONException) {
                JSONObject()
            }
        }
        if (!account.has(HOST_ID)) {
            account.put(HOST_ID, "urn:uuid:${UUID.randomUUID()}")
            write(account)
        }
        saved = account
        return account
    }

    /** Written whole and then swapped in, so a write cut short never loses the tokens. */
    private suspend fun write(account: JSONObject) = withContext(ioDispatcher) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(account.toString())
        temporary.renameTo(file)
    }

    private fun randomString(): String = base64Url(ByteArray(32).also(random::nextBytes))

    /**
     * Listens on the phone itself for the browser coming back from signing in, preferably on [port],
     * the one the first sign-in registers, else on any that's free.
     */
    private class Callback(private val server: ServerSocket, private val appPackage: String) {
        val port get() = server.localPort

        /** The address the browser came back to, once it has. */
        suspend fun await(): HttpUrl {
            // Checked every so often, so leaving off waiting closes the listener soon.
            server.soTimeout = ACCEPT_TIMEOUT_MS
            while (true) {
                currentCoroutineContext().ensureActive()
                val socket = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val url = socket.use {
                    try {
                        socket.soTimeout = ACCEPT_TIMEOUT_MS
                        val target = socket.getInputStream().bufferedReader().readLine()?.split(" ")?.getOrNull(1)
                        val url = target?.let { "http://127.0.0.1$it".toHttpUrlOrNull() }?.takeIf { it.encodedPath == CALLBACK_PATH }
                        val (status, page) = if (url != null) "200 OK" to backPage() else "404 Not Found" to ""
                        val body = page.toByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body)
                            flush()
                        }
                        url
                    } catch (_: IOException) {
                        // Maybe the browser connecting ahead of time, saying nothing.
                        null
                    }
                }
                if (url != null) return url
            }
        }

        /** Sends the browser back to Crosstune, by itself or with a tap where the browser wants one. */
        private fun backPage(): String {
            val back = "intent://chatgpt#Intent;scheme=$RETURN_SCHEME;package=$appPackage;end"
            return "<!doctype html><meta name=viewport content=\"width=device-width\"><title>$AGENT_NAME</title>" +
                "<p style=\"font:20px sans-serif;text-align:center;margin-top:40vh\"><a href=\"$back\">$AGENT_NAME</a></p>" +
                "<script>location.replace(\"$back\")</script>"
        }

        fun close() = server.close()

        companion object {
            fun open(port: Int, appPackage: String): Callback {
                val server = try {
                    ServerSocket(port, BACKLOG, LOOPBACK)
                } catch (_: BindException) {
                    ServerSocket(0, BACKLOG, LOOPBACK)
                }
                return Callback(server, appPackage)
            }
        }
    }

    companion object {
        const val ISSUER = "https://auth.openai.com"
        const val AUTHORIZE_URL = "$ISSUER/api/accounts/authorize"
        const val TOKEN_URL = "$ISSUER/api/accounts/oauth/token"
        const val REVOKE_URL = "$ISSUER/api/accounts/oauth/revoke"
        const val JWKS_URL = "$ISSUER/.well-known/jwks.json"
        const val RESOURCE = "https://api.openai.com/v1"
        const val MODELS_URL = "$RESOURCE/models"
        const val RESPONSES_URL = "$RESOURCE/responses"
        private val JSON = "application/json".toMediaType()
        private const val ANSWER_TIMEOUT_SECONDS = 120L

        /** Far more than any explanation or song's translation; anything longer has gone wrong. */
        private const val MAX_ANSWER_CHARS = 20_000

        /** What OpenAI calls the plan, or the app's share of it, being used up. */
        private val USAGE_LIMITS = listOf("subscription_sharing_usage_limit_exceeded", "subscription_sharing_usage_unavailable")

        /** Where the user sees and limits what Crosstune uses of their plan. */
        const val USAGE_URL = "https://chatgpt.com/settings/usage"

        /** The scheme the page the browser comes back to opens Crosstune with. */
        const val RETURN_SCHEME = "crosstune"

        /** The registration a first sign-in starts from, never one to keep. */
        private const val DYNAMIC_CLIENT = "dynamic_agent_client"
        private const val AGENT_NAME = "Crosstune"
        private const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
        private const val SCOPES = "openid profile email offline_access resource.invoke $PLAN_SCOPE"
        private const val CALLBACK_PATH = "/auth/callback"
        const val CALLBACK_PORT = 1455
        private const val BACKLOG = 8
        private const val ACCEPT_TIMEOUT_MS = 500
        private const val SIGN_IN_TIMEOUT_MS = 10 * 60 * 1000L

        /** A token's renewed this long before it runs out, so none runs out mid-request. */
        private const val RENEW_BEFORE_MS = 5 * 60 * 1000L

        /** What a renewal says when the session is over and only signing in again helps. */
        private val UNUSABLE = setOf(
            "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
            "refresh_token_invalidated", "refresh_token_reused"
        )

        private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

        private const val HOST_ID = "ext_agent_host_id"
        private const val CLIENT_ID = "client_id"
        private const val SUBJECT = "subject"
        private const val EMAIL = "email"
        private const val ID_TOKEN = "id_token"
        private const val ACCESS_TOKEN = "access_token"
        private const val REFRESH_TOKEN = "refresh_token"
        private const val EXPIRES_AT = "expires_at"

        /** Set when the last sign-in didn't allow the plan's use, so the next one asks for it again. */
        private const val DECLINED = "declined"

        private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        private fun base64UrlDecode(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
    }
}

/**
 * The model asked unless another's picked: the newest Luna, by its version, the plain one before
 * its smaller kinds; else a small one, quick and light on the plan; else the first.
 */
internal fun defaultModel(models: List<ChatGptModel>): ChatGptModel? {
    val luna = models.filter { "luna" in it.slug.lowercase() }
    return luna.maxWithOrNull(newestFirst) ?: models.firstOrNull { "mini" in it.slug } ?: models.firstOrNull()
}

/** By the numbers in their names, "gpt-6.1" after "gpt-6"; for the same ones, the shorter name. */
private val newestFirst = Comparator<ChatGptModel> { a, b ->
    val one = version(a.slug)
    val other = version(b.slug)
    (0 until maxOf(one.size, other.size)).firstNotNullOfOrNull { i -> (one.getOrElse(i) { -1 } compareTo other.getOrElse(i) { -1 }).takeIf { it != 0 } }
        ?: (b.slug.length compareTo a.slug.length)
}

private fun version(slug: String) = Regex("""\d+""").findAll(slug).map { it.value.toLong() }.toList()
