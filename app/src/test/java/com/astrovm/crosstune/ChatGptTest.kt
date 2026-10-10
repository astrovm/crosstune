package com.astrovm.crosstune

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/** Runs under Robolectric for the real org.json; the browser comes back over a real socket. */
@RunWith(RobolectricTestRunner::class)
class ChatGptTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val fake = FakeSpotify()
    private val file by lazy { File(folder.root, "chatgpt.json") }
    private var clock = 1_800_000_000_000L
    private val openAi = FakeOpenAi { clock }

    /** What the page the browser came back to said. */
    private var backPage: String? = null

    private fun chatGpt(port: Int = 0) = ChatGpt(fake.client(), file, "com.example.crosstune", Dispatchers.IO, now = { clock }, callbackPort = port)

    init {
        fake.handler = { request -> openAi.answer(request) ?: FakeSpotify.html(request, "") }
    }

    /**
     * Signs in, the browser coming back with [answer]'s query for where it was sent; or never,
     * when that's null.
     */
    private fun signIn(
        chatGpt: ChatGpt = chatGpt(),
        timeoutMs: Long = 10_000,
        answer: (HttpUrl) -> String? = { "code=the-code&state=${it.queryParameter("state")}&client_id=oaiapp_1" }
    ) = runBlocking {
        chatGpt.signIn(open = { url ->
            openAi.authorize = url.toHttpUrl()
            val query = answer(openAi.authorize) ?: return@signIn
            thread { backPage = openAi.comeBack(query) }
        }, timeoutMs = timeoutMs)
    }

    private fun tokenForms() = fake.requestedUrls.indices
        .filter { fake.requestedUrls[it] == ChatGpt.TOKEN_URL }
        .map { fake.requestBodies[it].split("&").associate { pair -> pair.substringBefore("=") to java.net.URLDecoder.decode(pair.substringAfter("="), "UTF-8") } }

    private fun saved() = JSONObject(file.readText())

    @Test
    fun theFirstSignInRegistersCrosstuneAndKeepsTheTokensOnThePhone() {
        val chatGpt = chatGpt()
        assertEquals(ChatGptSignInResult.SIGNED_IN, signIn(chatGpt))

        // A first sign-in registers, under Crosstune's name, for this phone, asking for the plan's use.
        assertEquals("dynamic_agent_client", openAi.authorize.queryParameter("client_id"))
        assertEquals("Crosstune", openAi.authorize.queryParameter("agent_name_hint"))
        assertTrue(openAi.authorize.queryParameter("ext_agent_host_id")!!.startsWith("urn:uuid:"))
        assertEquals("openid profile email offline_access resource.invoke chatgpt.tokens.use.direct", openAi.authorize.queryParameter("scope"))
        assertEquals("https://api.openai.com/v1", openAi.authorize.queryParameter("resource"))
        assertEquals("code", openAi.authorize.queryParameter("response_type"))
        assertNull(openAi.authorize.queryParameter("prompt"))
        assertNull(openAi.authorize.queryParameter("login_hint"))
        val redirect = openAi.authorize.queryParameter("redirect_uri")!!.toHttpUrl()
        assertEquals("127.0.0.1", redirect.host)
        assertEquals("/auth/callback", redirect.encodedPath)

        // The code's exchanged with the ID given back, and the PKCE secret its challenge was made from.
        val form = tokenForms().single()
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("oaiapp_1", form["client_id"])
        assertEquals("the-code", form["code"])
        assertEquals(redirect.toString(), form["redirect_uri"])
        val challenge = FakeOpenAi.base64Url(MessageDigest.getInstance("SHA-256").digest(form.getValue("code_verifier").toByteArray()))
        assertEquals(openAi.authorize.queryParameter("code_challenge"), challenge)
        assertEquals("S256", openAi.authorize.queryParameter("code_challenge_method"))

        // The page the browser lands on sends it back to Crosstune.
        assertTrue(backPage!!.contains("intent://chatgpt#Intent;scheme=crosstune;package=com.example.crosstune;end"))

        assertEquals("ana@example.com", runBlocking { chatGpt.email() })
        assertEquals("access-1", runBlocking { chatGpt.accessToken() })
        // Kept, for Crosstune opened again.
        assertEquals("ana@example.com", runBlocking { chatGpt().email() })
        assertEquals("oaiapp_1", saved().getString("client_id"))
    }

    @Test
    fun signedOutTheSessionEndsAndSigningInAgainReusesTheRegistration() {
        val chatGpt = chatGpt()
        signIn(chatGpt)
        val host = openAi.authorize.queryParameter("ext_agent_host_id")
        runBlocking { chatGpt.signOut() }
        val revoke = fake.requestedUrls.indexOf(ChatGpt.REVOKE_URL)
        assertEquals("token=refresh-1&token_type_hint=refresh_token&client_id=oaiapp_1", fake.requestBodies[revoke])
        assertNull(runBlocking { chatGpt.email() })
        assertNull(runBlocking { chatGpt.accessToken() })

        // The browser may not say the registration again; it's the one kept.
        assertEquals(ChatGptSignInResult.SIGNED_IN, signIn(chatGpt) { "code=c&state=${it.queryParameter("state")}" })
        assertEquals("oaiapp_1", openAi.authorize.queryParameter("client_id"))
        assertNull(openAi.authorize.queryParameter("agent_name_hint"))
        assertEquals(host, openAi.authorize.queryParameter("ext_agent_host_id"))
        assertEquals("ana@example.com", openAi.authorize.queryParameter("login_hint"))
        // Signed out, the account's free to change, so it isn't hinted at.
        assertNull(openAi.authorize.queryParameter("id_token_hint"))
        assertEquals("oaiapp_1", tokenForms().last()["client_id"])

        // Signed in again without signing out, it is.
        signIn(chatGpt) { "code=c&state=${it.queryParameter("state")}" }
        assertTrue(openAi.authorize.queryParameter("id_token_hint")!!.isNotEmpty())
    }

    @Test
    fun signingOutOfflineStillSignsOutAndSignedOutThereIsNothingToEnd() {
        val chatGpt = chatGpt()
        runBlocking { chatGpt.signOut() }
        assertTrue(fake.requestedUrls.isEmpty())
        signIn(chatGpt)
        openAi.revokeFails = true
        runBlocking { chatGpt.signOut() }
        assertNull(runBlocking { chatGpt.email() })
    }

    @Test
    fun withoutThePlansUseItsDeclinedAndAskedForAgainNextTime() {
        val chatGpt = chatGpt()
        assertEquals(ChatGptSignInResult.DECLINED, signIn(chatGpt) { "error=access_denied&state=${it.queryParameter("state")}" })
        assertNull(runBlocking { chatGpt.email() })

        openAi.tokenAnswer = { form -> 200 to openAi.tokens(openAi.idToken(audience = form.getValue("client_id")), scope = "openid profile email") }
        assertEquals(ChatGptSignInResult.DECLINED, signIn(chatGpt))
        assertNull(runBlocking { chatGpt.email() })
        assertNull(runBlocking { chatGpt.accessToken() })

        openAi.tokenAnswer = { form -> 200 to openAi.tokens(openAi.idToken(audience = form.getValue("client_id"))) }
        assertEquals(ChatGptSignInResult.SIGNED_IN, signIn(chatGpt) { "code=c&state=${it.queryParameter("state")}" })
        assertEquals("consent", openAi.authorize.queryParameter("prompt"))
        // Allowed now, it isn't asked again.
        signIn(chatGpt) { "code=c&state=${it.queryParameter("state")}" }
        assertNull(openAi.authorize.queryParameter("prompt"))
    }

    @Test
    fun aBrowserComingBackWithAnythingButThisSignInsAnswerFails() {
        val answers = listOf<(HttpUrl) -> String>(
            { "code=c&state=someone-elses&client_id=oaiapp_1" },
            { "error=server_error&state=${it.queryParameter("state")}" },
            // A first sign-in that doesn't say what it registered.
            { "code=c&state=${it.queryParameter("state")}" }
        )
        answers.forEach { assertEquals(ChatGptSignInResult.FAILED, signIn(answer = it)) }
        assertTrue(tokenForms().isEmpty())
        assertFalse(file.readText().contains("access_token"))
    }

    @Test
    fun signedInAgainItMustBeTheSameRegistrationAndAccount() {
        val chatGpt = chatGpt()
        signIn(chatGpt)
        assertEquals(ChatGptSignInResult.FAILED, signIn(chatGpt) { "code=c&state=${it.queryParameter("state")}&client_id=oaiapp_2" })
        openAi.tokenAnswer = { form -> 200 to openAi.tokens(openAi.idToken(audience = form.getValue("client_id"), subject = "user-2")) }
        assertEquals(ChatGptSignInResult.FAILED, signIn(chatGpt))
        // Still signed in as before.
        assertEquals("access-1", runBlocking { chatGpt.accessToken() })
    }

    @Test
    fun anIdTokenOpenAiDidntSignForThisSignInFails() {
        val bad = listOf(
            { openAi.idToken(nonce = "another") },
            { openAi.idToken(audience = "oaiapp_9") },
            { openAi.idToken(expires = clock / 1000 - 1) },
            { openAi.idToken(alg = "none") },
            { openAi.idToken(kid = "unknown") },
            { openAi.idToken(signer = FakeOpenAi.newKeys()) },
            { "not-a-token" },
            { "@@@.@@@.@@@" },
            { openAi.idToken().substringBeforeLast(".") + ".@@@" },
            // A signature too short to be one.
            { openAi.idToken().substringBeforeLast(".") + ".AAAA" },
            // Signed with a key too small for any RSA to take.
            { openAi.jwks = { 200 to """{"keys":[{"kid":"key-1","n":"AQ","e":"AQAB"}]}""" }; openAi.idToken() }
        )
        bad.forEach { token ->
            openAi.tokenAnswer = { 200 to openAi.tokens(token()) }
            assertEquals(ChatGptSignInResult.FAILED, signIn())
        }
        // An audience listing Crosstune's registration among others is one.
        openAi.jwks = { 200 to openAi.jwksJson() }
        openAi.tokenAnswer = { 200 to openAi.tokens(openAi.idToken(audience = JSONArray().put("other").put("oaiapp_1"))) }
        assertEquals(ChatGptSignInResult.SIGNED_IN, signIn())
    }

    @Test
    fun theTokenEndpointOrItsKeysFailingFailsTheSignIn() {
        val failures = listOf(
            { openAi.tokenAnswer = { 400 to """{"error":"invalid_grant"}""" } },
            { openAi.tokenAnswer = { 200 to "not json" } },
            { openAi.tokenAnswer = { throw IOException("offline") } },
            { openAi.tokenAnswer = { 200 to openAi.tokens(openAi.idToken()) }; openAi.jwks = { 503 to "" } }
        )
        failures.forEach { failure ->
            failure()
            assertEquals(ChatGptSignInResult.FAILED, signIn())
        }
    }

    @Test
    fun aBrowserThatNeverComesBackRunsOutOfTime() {
        assertEquals(ChatGptSignInResult.FAILED, signIn(timeoutMs = 1_500) { null })
    }

    @Test
    fun otherRequestsAndSilentConnectionsAreIgnoredUntilTheBrowserComesBack() {
        val result = runBlocking {
            chatGpt().signIn(open = { url ->
                openAi.authorize = url.toHttpUrl()
                val callback = openAi.authorize.queryParameter("redirect_uri")!!.toHttpUrl()
                thread {
                    // A connection opened ahead of time that says nothing, then a request for the icon.
                    Socket("127.0.0.1", callback.port).use { Thread.sleep(800) }
                    val icon = URL("http://127.0.0.1:${callback.port}/favicon.ico").openConnection() as java.net.HttpURLConnection
                    assertEquals(404, icon.responseCode)
                    Socket("127.0.0.1", callback.port).use { it.getOutputStream().write("\r\n".toByteArray()) }
                    backPage = openAi.comeBack()
                }
            })
        }
        assertEquals(ChatGptSignInResult.SIGNED_IN, result)
    }

    @Test
    fun theFirstSignInsPortIsUsedWhenFreeAndAnyOtherWhenNot() {
        val free = ServerSocket(0).use { it.localPort }
        signIn(chatGpt(port = free))
        assertEquals(free, openAi.authorize.queryParameter("redirect_uri")!!.toHttpUrl().port)
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { taken ->
            assertEquals(ChatGptSignInResult.SIGNED_IN, signIn(chatGpt(port = taken.localPort)))
            assertTrue(openAi.authorize.queryParameter("redirect_uri")!!.toHttpUrl().port != taken.localPort)
        }
    }

    @Test
    fun theTokenIsRenewedWhenAboutToRunOut() {
        val chatGpt = chatGpt()
        assertNull(runBlocking { chatGpt.accessToken() })
        signIn(chatGpt)
        clock += 50 * 60 * 1000
        assertEquals("access-1", runBlocking { chatGpt.accessToken() })
        assertEquals(1, tokenForms().size)

        // Five minutes from running out, it's renewed, and the renewal replaces both tokens.
        clock += 6 * 60 * 1000
        assertEquals("access-2", runBlocking { chatGpt.accessToken() })
        val renewal = tokenForms().last()
        assertEquals("refresh_token", renewal["grant_type"])
        assertEquals("refresh-1", renewal["refresh_token"])
        assertEquals("oaiapp_1", renewal["client_id"])
        assertEquals("https://api.openai.com/v1", renewal["resource"])
        assertEquals("refresh-2", saved().getString("refresh_token"))
        assertEquals("access-2", runBlocking { chatGpt().accessToken() })
    }

    @Test
    fun aRenewalThatFailsForNowKeepsTheTokensAndOneRefusedForGoodSignsOut() {
        val chatGpt = chatGpt()
        signIn(chatGpt)
        clock += 60 * 60 * 1000
        listOf<() -> Pair<Int, String>>({ 503 to "" }, { 200 to "not json" }, { throw IOException("offline") }, { 400 to "not json" }).forEach { failure ->
            openAi.tokenAnswer = { failure() }
            assertNull(runBlocking { chatGpt.accessToken() })
            assertEquals("ana@example.com", runBlocking { chatGpt.email() })
        }
        openAi.tokenAnswer = { 400 to """{"error":"refresh_token_reused"}""" }
        assertNull(runBlocking { chatGpt.accessToken() })
        assertNull(runBlocking { chatGpt.email() })
        // The registration stays, for signing in again.
        assertEquals("oaiapp_1", saved().getString("client_id"))
    }

    @Test
    fun aDamagedFileStartsOver() {
        file.writeText("{not json")
        val chatGpt = chatGpt()
        assertNull(runBlocking { chatGpt.email() })
        assertTrue(saved().getString("ext_agent_host_id").startsWith("urn:uuid:"))
    }

}
