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

    private fun signedIn(): ChatGpt = chatGpt().also { signIn(it) }

    @Test
    fun askedSignedInItStreamsTheAnswerFromASmallModel() {
        val chatGpt = signedIn()
        val written = mutableListOf<String>()
        assertEquals(ChatGptReply.Answer("Hello"), runBlocking { chatGpt.ask("Be brief.", "Say hello") { written += it } })
        assertEquals(listOf("Hel", "Hello"), written)
        val body = openAi.asked.single()
        assertEquals("gpt-6.1-sol-mini", body.getString("model"))
        assertEquals("Be brief.", body.getString("instructions"))
        assertEquals("Say hello", body.getJSONArray("input").getJSONObject(0).getString("content"))
        // OpenAI asks for both on every request on the user's plan.
        assertFalse(body.getBoolean("store"))
        assertTrue(body.getBoolean("stream"))
        // The models are asked for once.
        runBlocking { chatGpt.ask("Be brief.", "Again") }
        assertEquals(1, fake.requestedUrls.count { it == ChatGpt.MODELS_URL })
    }

    @Test
    fun withoutASmallModelTheFirstListedIsAsked() {
        openAi.models = { 200 to FakeOpenAi.modelsJson("gpt-6-hidden-mini" to "hide", "gpt-6.1-sol" to "list", "gpt-6.1-pro" to "list") }
        val chatGpt = signedIn()
        runBlocking { chatGpt.ask("", "Hi") }
        assertEquals("gpt-6.1-sol", openAi.asked.single().getString("model"))
    }

    @Test
    fun noModelSignedOutOrAnErrorIsNoAnswer() {
        assertEquals(ChatGptReply.Failed, runBlocking { chatGpt().ask("", "Hi") })
        val chatGpt = signedIn()
        listOf<() -> Pair<Int, String>>({ 200 to FakeOpenAi.modelsJson("gpt-6.1-sol" to "hide") }, { 500 to "" }, { 200 to "not json" }, { throw IOException("offline") }).forEach { models ->
            openAi.models = models
            assertEquals(ChatGptReply.Failed, runBlocking { chatGpt.ask("", "Hi") })
        }
        assertTrue(openAi.asked.isEmpty())
    }

    @Test
    fun aPlanUsedUpSaysSoAndAnyOtherFailureIsNoAnswer() {
        val chatGpt = signedIn()
        val limit = """{"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}}"""
        val unavailable = """{"type":"error","code":"subscription_sharing_usage_unavailable"}"""
        mapOf<() -> Pair<Int, String>, ChatGptReply>(
            { 429 to "" } to ChatGptReply.LimitReached,
            // It can run out partway through an answer.
            { 200 to FakeOpenAi.stream("Half", end = limit) } to ChatGptReply.LimitReached,
            { 200 to FakeOpenAi.stream(end = unavailable) } to ChatGptReply.LimitReached,
            { 200 to FakeOpenAi.stream("Half", end = """{"type":"response.failed","response":{"error":{"code":"server_error"}}}""") } to ChatGptReply.Failed,
            { 200 to FakeOpenAi.stream("Half", end = """{"type":"response.incomplete"}""") } to ChatGptReply.Failed,
            // Only a completed answer is one.
            { 200 to FakeOpenAi.stream("Cut off", end = null) } to ChatGptReply.Failed,
            { 200 to FakeOpenAi.stream("x".repeat(20_001)) } to ChatGptReply.Failed,
            { 500 to "" } to ChatGptReply.Failed,
            { throw IOException("offline") } to ChatGptReply.Failed
        ).forEach { (reply, expected) ->
            openAi.reply = { reply() }
            assertEquals(expected, runBlocking { chatGpt.ask("", "Hi") })
        }
        // Lines that aren't events, or events that aren't JSON, are skipped.
        openAi.reply = { 200 to ": keep-alive\n\ndata: [not json]\n\n" + FakeOpenAi.stream("Fine") }
        assertEquals(ChatGptReply.Answer("Fine"), runBlocking { chatGpt.ask("", "Hi") })
    }

    private fun tutor(chatGpt: ChatGpt = signedIn()) = ChatGptTutor(chatGpt, LookupCache(File(folder.root, "answers.json"), Dispatchers.Unconfined))

    private fun answering(text: String) {
        openAi.reply = { 200 to FakeOpenAi.stream(text) }
    }

    @Test
    fun aSongIsTranslatedWholeAndEachLineKept() {
        val tutor = tutor()
        answering("Here you go:\n```json\n[\"Hello, friend\", \"Ciao\"]\n```")
        val lines = listOf("Hola amigo", "", "♪", "Hola amigo", "Ciao")
        // A line already in the language, or with no words, needs no translation.
        assertEquals(listOf("Hello, friend", null, null, "Hello, friend", null), runBlocking { tutor.translate(lines, "en") })
        val asked = openAi.asked.single()
        assertEquals(JSONArray(listOf("Hola amigo", "Ciao")).toString(), asked.getJSONArray("input").getJSONObject(0).getString("content"))
        assertTrue(asked.getString("instructions").contains("into English"))
        // Read again, nothing is asked; a new line is, alone.
        assertEquals(listOf("Hello, friend"), runBlocking { tutor.translate(listOf("Hola amigo"), "en") })
        answering("[\"Goodbye\"]")
        assertEquals(listOf("Hello, friend", "Goodbye"), runBlocking { tutor.translate(listOf("Hola amigo", "Adiós"), "en") })
        assertEquals(JSONArray(listOf("Adiós")).toString(), openAi.asked.last().getJSONArray("input").getJSONObject(0).getString("content"))
        assertTrue(openAi.asked.last().getString("instructions").contains("into Portuguese (Brazil)").not())
        assertEquals(2, openAi.asked.size)
    }

    @Test
    fun aTranslationThatDoesntFitTheLinesIsNone() {
        val tutor = tutor()
        listOf("[\"Just one\"]", "No JSON at all", "] backwards [", "[{\"a\": }]").forEach { text ->
            answering(text)
            assertNull(runBlocking { tutor.translate(listOf("Uno", "Dos"), "pt-BR") })
        }
        assertTrue(openAi.asked.last().getString("instructions").contains("into Portuguese (Brazil)"))
        openAi.reply = { 429 to "" }
        assertNull(runBlocking { tutor.translate(listOf("Uno"), "en") })
        // Nothing to translate asks nothing.
        val before = openAi.asked.size
        assertEquals(listOf<String?>(null), runBlocking { tutor.translate(listOf("♪"), "en") })
        assertEquals(before, openAi.asked.size)
    }

    @Test
    fun aLineIsExplainedAsItsWrittenAndKept() {
        val tutor = tutor()
        val song = MusicMetadata("Asphalt Lady", "S.Kiyotaka & Omega Tribe")
        answering("It means you're a city woman.")
        val written = mutableListOf<String>()
        val reply = runBlocking { tutor.explain(song, listOf("Shock!", "君はアスファルト・レディ"), "君はアスファルト・レディ", "es") { written += it } }
        assertEquals(ChatGptReply.Answer("It means you're a city woman."), reply)
        assertEquals("It means you're a city woman.", written.last())
        val asked = openAi.asked.single()
        assertTrue(asked.getString("instructions").contains("In Spanish"))
        val input = asked.getJSONArray("input").getJSONObject(0).getString("content")
        assertTrue(input, input.contains("Asphalt Lady by S.Kiyotaka & Omega Tribe") && input.contains("Line: 君はアスファルト・レディ"))
        // Asked again, it's there already.
        written.clear()
        assertEquals(reply, runBlocking { tutor.explain(song, emptyList(), "君はアスファルト・レディ", "es") { written += it } })
        assertEquals(1, openAi.asked.size)
        assertTrue(written.isEmpty())
        // What couldn't be explained isn't kept, so it's asked again.
        openAi.reply = { 429 to "" }
        assertEquals(ChatGptReply.LimitReached, runBlocking { tutor.explain(song, emptyList(), "Shock!", "es") {} })
        answering("A cry.")
        assertEquals(ChatGptReply.Answer("A cry."), runBlocking { tutor.explain(song, emptyList(), "Shock!", "es") {} })
    }

    @Test
    fun aWordMeansWhatItDoesInItsLine() {
        val tutor = tutor()
        answering("""{"meaning": "you (to a lover)", "type": "pronoun"}""")
        assertEquals(Definition("pronoun", "you (to a lover)"), runBlocking { tutor.meaning("君", "君はアスファルト・レディ", "en") })
        val asked = JSONObject(openAi.asked.single().getJSONArray("input").getJSONObject(0).getString("content"))
        assertEquals("君", asked.getString("word"))
        assertEquals("君はアスファルト・レディ", asked.getString("line"))
        // Kept, and only for that line.
        assertEquals(Definition("pronoun", "you (to a lover)"), runBlocking { tutor.meaning("君", "君はアスファルト・レディ", "en") })
        assertEquals(1, openAi.asked.size)
        answering("""{"meaning": "you", "type": " "}""")
        assertEquals(Definition(null, "you"), runBlocking { tutor.meaning("君", "君の名は", "en") })
        // An answer without a meaning, or not an answer at all, is none.
        listOf("""{"type": "noun"}""", "no idea", "{broken").forEach { text ->
            answering(text)
            assertNull(runBlocking { tutor.meaning("愛", "愛してる", "en") })
        }
        openAi.reply = { 500 to "" }
        assertNull(runBlocking { tutor.meaning("愛", "愛してる", "en") })
    }
}
