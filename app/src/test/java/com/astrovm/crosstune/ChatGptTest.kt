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
    fun signedOutTheSessionEndsAndAnotherAccountCanSignIn() {
        val chatGpt = chatGpt()
        signIn(chatGpt)
        val host = openAi.authorize.queryParameter("ext_agent_host_id")
        runBlocking { chatGpt.signOut() }
        val revoke = fake.requestedUrls.indexOf(ChatGpt.REVOKE_URL)
        assertEquals("token=refresh-1&token_type_hint=refresh_token&client_id=oaiapp_1", fake.requestBodies[revoke])
        assertNull(runBlocking { chatGpt.email() })
        assertNull(runBlocking { chatGpt.accessToken() })
        assertFalse(saved().has("client_id"))

        // Signing in again starts afresh, hinting at no account, so another one can sign in, with a
        // registration of its own; this phone is still the same one.
        openAi.tokenAnswer = { form -> 200 to openAi.tokens(openAi.idToken(audience = form.getValue("client_id"), subject = "user-2"), access = "access-2") }
        assertEquals(ChatGptSignInResult.SIGNED_IN, signIn(chatGpt) { "code=c&state=${it.queryParameter("state")}&client_id=oaiapp_2" })
        assertEquals("dynamic_agent_client", openAi.authorize.queryParameter("client_id"))
        assertTrue(openAi.authorize.queryParameter("agent_name_hint")!!.isNotEmpty())
        assertEquals(host, openAi.authorize.queryParameter("ext_agent_host_id"))
        assertNull(openAi.authorize.queryParameter("login_hint"))
        assertNull(openAi.authorize.queryParameter("id_token_hint"))
        assertEquals("oaiapp_2", saved().getString("client_id"))
        assertEquals("access-2", runBlocking { chatGpt.accessToken() })

        // Signed in again without signing out, the account is hinted at, and kept to.
        signIn(chatGpt) { "code=c&state=${it.queryParameter("state")}" }
        assertTrue(openAi.authorize.queryParameter("id_token_hint")!!.isNotEmpty())
        assertEquals("oaiapp_2", openAi.authorize.queryParameter("client_id"))
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
            // Signed with OpenAI's key, but for another sign-in service.
            { openAi.idToken(issuer = "https://auth.example.com") },
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
    fun aRenewalThatLeavesTheRefreshTokenKeepsIt() {
        val chatGpt = chatGpt()
        signIn(chatGpt)
        clock += 60 * 60 * 1000
        openAi.tokenAnswer = { 200 to """{"access_token":"access-3","expires_in":3600}""" }
        assertEquals("access-3", runBlocking { chatGpt.accessToken() })
        assertEquals("refresh-1", saved().getString("refresh_token"))
    }

    @Test
    fun aTokenTurnedDownIsRenewedAndAskedWithOnceMore() {
        val chatGpt = signedIn()
        var turnedDown = 1
        openAi.reply = { if (turnedDown-- > 0) 401 to "" else 200 to FakeOpenAi.stream("Hello") }
        assertEquals(ChatGptReply.Answer("Hello"), runBlocking { chatGpt.ask("Be brief.", "Say hello") })
        assertEquals("refresh_token", tokenForms().last()["grant_type"])
        assertEquals("access-2", runBlocking { chatGpt.accessToken() })
        // Turned down again with the new one, it's no answer, not asked on and on.
        val renewals = tokenForms().size
        openAi.reply = { 401 to "" }
        assertEquals(ChatGptReply.Failed, runBlocking { chatGpt.ask("Be brief.", "Say hello") })
        assertEquals(renewals + 1, tokenForms().size)
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
    fun askedSignedInItStreamsTheAnswer() {
        val chatGpt = signedIn()
        val written = mutableListOf<String>()
        assertEquals(ChatGptReply.Answer("Hello"), runBlocking { chatGpt.ask("Be brief.", "Say hello") { written += it } })
        assertEquals(listOf("Hel", "Hello"), written)
        val body = openAi.asked.single()
        assertEquals("gpt-6.1-sol", body.getString("model"))
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
    fun withoutALunaTheFirstListedIsAsked() {
        openAi.models = { 200 to FakeOpenAi.modelsJson("gpt-6-hidden-luna" to "hide", "gpt-6.1-sol" to "list", "gpt-6.1-pro" to "list") }
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
        // Lines in the same language, from anywhere, are left alone.
        assertTrue(asked.getString("instructions").contains("already in English, from anywhere"))
        // Read again, nothing is asked; a new line is, alone.
        assertEquals(listOf("Hello, friend"), runBlocking { tutor.translate(listOf("Hola amigo"), "en") })
        answering("[\"Goodbye\"]")
        assertEquals(listOf("Hello, friend", "Goodbye"), runBlocking { tutor.translate(listOf("Hola amigo", "Adiós"), "en") })
        assertEquals(JSONArray(listOf("Adiós")).toString(), openAi.asked.last().getJSONArray("input").getJSONObject(0).getString("content"))
        assertTrue(openAi.asked.last().getString("instructions").contains("into Portuguese (Brazil)").not())
        assertEquals(2, openAi.asked.size)
    }

    @Test
    fun aLineAlreadyInTheLanguageComesBackEmptyAndIsNoneButChineseGoesByHowItsWritten() {
        val tutor = tutor()
        answering("[\"Hello\", \"\"]")
        assertEquals(listOf("Hello", null), runBlocking { tutor.translate(listOf("Hola", "Good night, mate"), "en-US") })
        answering("[\"故事里的小黄花\"]")
        assertEquals(listOf("故事里的小黄花"), runBlocking { tutor.translate(listOf("故事的小黃花"), "zh-CN") })
        assertTrue(openAi.asked.last().getString("instructions").contains("already in Simplified Chinese"))
        answering("[\"故事的小黃花\"]")
        runBlocking { tutor.translate(listOf("故事的小黄花"), "zh-TW") }
        assertTrue(openAi.asked.last().getString("instructions").contains("already in Traditional Chinese"))
    }

    @Test
    fun aTranslationThatDoesntFitTheLinesIsNone() {
        val tutor = tutor()
        listOf("[\"Just one\"]", "No JSON at all", "] backwards [", "[{\"a\": }]").forEach { text ->
            answering(text)
            assertNull(runBlocking { tutor.translate(listOf("Uno", "Dos"), "pt-BR") })
        }
        assertTrue(openAi.asked.last().getString("instructions").contains("into Portuguese (Brazil)"))
        assertTrue(openAi.asked.last().getString("instructions").contains("already in Portuguese, from anywhere"))
        openAi.reply = { 429 to "" }
        assertNull(runBlocking { tutor.translate(listOf("Uno"), "en") })
        // Nothing to translate asks nothing.
        val before = openAi.asked.size
        assertEquals(listOf<String?>(null), runBlocking { tutor.translate(listOf("♪"), "en") })
        assertEquals(before, openAi.asked.size)
    }

    @Test
    fun aLineIsExplainedAsPartOfTheWholeSongAndKept() {
        val tutor = tutor()
        val song = MusicMetadata("Asphalt Lady", "S.Kiyotaka & Omega Tribe")
        val lines = listOf("Shock!", "君はアスファルト・レディ", "Shock!")
        answering("He calls her a city woman.")
        val written = mutableListOf<String>()
        val reply = runBlocking { tutor.explain(song, lines, 1, "es") { written += it } }
        assertEquals(ChatGptReply.Answer("He calls her a city woman."), reply)
        assertEquals("He calls her a city woman.", written.last())
        val asked = openAi.asked.single()
        val instructions = asked.getString("instructions")
        assertTrue(instructions.contains("in Spanish") && instructions.contains("read the whole song"))
        // Short, and thinking little, as measured on real songs.
        assertTrue(instructions.contains("At most 45 words"))
        assertEquals("low", asked.getJSONObject("reasoning").getString("effort"))
        // Numbered, so which of the lines is meant is clear.
        val input = asked.getJSONArray("input").getJSONObject(0).getString("content")
        assertTrue(input, input.contains("Asphalt Lady by S.Kiyotaka & Omega Tribe") && input.contains("1. Shock!\n2. 君はアスファルト・レディ\n3. Shock!") && input.endsWith("Line 2: 君はアスファルト・レディ"))
        // Asked again, it's there already.
        written.clear()
        assertEquals(reply, runBlocking { tutor.explain(song, lines, 1, "es") { written += it } })
        assertEquals(1, openAi.asked.size)
        assertTrue(written.isEmpty())
        // The same words elsewhere in the song are another line, asked about on their own.
        answering("A cry at the start.")
        runBlocking { tutor.explain(song, lines, 0, "es") {} }
        answering("A cry again at the end.")
        assertEquals(ChatGptReply.Answer("A cry again at the end."), runBlocking { tutor.explain(song, lines, 2, "es") {} })
        // What couldn't be explained isn't kept, so it's asked again; a line that isn't there isn't asked.
        openAi.reply = { 429 to "" }
        assertEquals(ChatGptReply.LimitReached, runBlocking { tutor.explain(song.copy(title = "Other"), lines, 0, "es") {} })
        assertEquals(ChatGptReply.Failed, runBlocking { tutor.explain(song, lines, 9, "es") {} })
    }

    @Test
    fun theWholeSongIsExplainedKeptAndToldToItsLines() {
        val tutor = tutor()
        val song = MusicMetadata("Asphalt Lady", "S.Kiyotaka & Omega Tribe")
        val lines = listOf("Shock!", "君はアスファルト・レディ")
        answering("A man dazzled by a woman of the city.")
        val written = mutableListOf<String>()
        assertEquals(ChatGptReply.Answer("A man dazzled by a woman of the city."), runBlocking { tutor.explainSong(song, lines, "en") { written += it } })
        assertEquals("A man dazzled by a woman of the city.", written.last())
        val asked = openAi.asked.single()
        assertTrue(asked.getString("instructions").contains("explain the song in four parts"))
        assertTrue(asked.getJSONArray("input").getJSONObject(0).getString("content").endsWith("Lyrics:\nShock!\n君はアスファルト・レディ"))
        // Kept, and its lines are explained knowing it.
        assertEquals(ChatGptReply.Answer("A man dazzled by a woman of the city."), runBlocking { tutor.explainSong(song, lines, "en") {} })
        assertEquals(1, openAi.asked.size)
        answering("He calls her a city woman.")
        runBlocking { tutor.explain(song, lines, 1, "en") {} }
        assertTrue(openAi.asked.last().getJSONArray("input").getJSONObject(0).getString("content").contains("What the song is about:\nA man dazzled by a woman of the city."))
        // Opened again with the models not listed, e.g. offline, what the model asked last answered is
        // still there, with nothing asked.
        openAi.models = { 500 to "" }
        val askedBefore = openAi.asked.size
        assertEquals(ChatGptReply.Answer("A man dazzled by a woman of the city."), runBlocking { tutor(chatGpt()).explainSong(song, lines, "en") {} })
        assertEquals(askedBefore, openAi.asked.size)
        // Signed in afresh, no model's been asked, so there's none to ask.
        runBlocking { chatGpt().signOut() }
        signIn(chatGpt())
        assertEquals(ChatGptReply.Failed, runBlocking { tutor(chatGpt()).explainSong(song.copy(title = "Other"), lines, "en") {} })
        assertEquals(ChatGptReply.Failed, runBlocking { tutor(chatGpt()).explain(song.copy(title = "Other"), lines, 0, "en") {} })
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

    @Test
    fun theNewestLunaIsAskedByDefault() {
        fun named(vararg slugs: String) = slugs.map { ChatGptModel(it, it) }
        assertEquals("gpt-6.1-luna", defaultModel(named("gpt-6.1-sol", "gpt-6-luna", "gpt-6.1-luna-fast", "gpt-6.1-luna", "gpt-5.6-luna"))?.slug)
        // By version, not by how it's written: 6.10 is newer than 6.9.
        assertEquals("gpt-6.10-luna", defaultModel(named("gpt-6.9-luna", "gpt-6.10-luna", "gpt-6-luna"))?.slug)
        assertEquals("gpt-6.1-Luna", defaultModel(named("gpt-6-luna", "gpt-6.1-Luna"))?.slug)
        // Without a Luna, the first listed.
        assertEquals("gpt-6.1-sol", defaultModel(named("gpt-6.1-sol", "gpt-6-astra", "daybreak-blue"))?.slug)
        assertNull(defaultModel(emptyList()))
    }

    @Test
    fun theModelsOfferedArePickedFromAndThePickAskedWhileOffered() {
        val models = JSONObject().put(
            "models",
            JSONArray()
                .put(JSONObject().put("slug", "gpt-6.1-luna").put("display_name", "GPT-6.1 Luna").put("visibility", "list"))
                .put(JSONObject().put("slug", "gpt-6.1-sol").put("display_name", " ").put("visibility", "list"))
                .put(JSONObject().put("slug", "gpt-6-hidden").put("visibility", "hide"))
        ).toString()
        openAi.models = { 200 to models }
        val chatGpt = signedIn()
        assertEquals(listOf(ChatGptModel("gpt-6.1-luna", "GPT-6.1 Luna"), ChatGptModel("gpt-6.1-sol", "gpt-6.1-sol")), runBlocking { chatGpt.models() })
        runBlocking { chatGpt.ask("", "Hi") }
        assertEquals("gpt-6.1-luna", openAi.asked.last().getString("model"))
        chatGpt.picked = "gpt-6.1-sol"
        runBlocking { chatGpt.ask("", "Hi") }
        assertEquals("gpt-6.1-sol", openAi.asked.last().getString("model"))
        // One no longer offered gives way to the default.
        chatGpt.picked = "gpt-5"
        assertEquals("gpt-6.1-luna", runBlocking { chatGpt.model() })
        // Read once while signed in, and again for whoever signs in next.
        assertEquals(1, fake.requestedUrls.count { it == ChatGpt.MODELS_URL })
        runBlocking { chatGpt.signOut() }
        assertNull(runBlocking { chatGpt.models() })
        signIn(chatGpt)
        runBlocking { chatGpt.models() }
        assertEquals(2, fake.requestedUrls.count { it == ChatGpt.MODELS_URL })
    }

    @Test
    fun anotherModelAnswersAnew() {
        val chatGpt = signedIn()
        val tutor = tutor(chatGpt)
        answering("[\"Hello\"]")
        assertEquals(listOf("Hello"), runBlocking { tutor.translate(listOf("Hola"), "en") })
        chatGpt.picked = "gpt-6-terra"
        answering("[\"Hi\"]")
        assertEquals(listOf("Hi"), runBlocking { tutor.translate(listOf("Hola"), "en") })
        assertEquals(listOf("gpt-6.1-sol", "gpt-6-terra"), openAi.asked.map { it.getString("model") })
        // Without the models listed, the one asked last is asked again.
        openAi.models = { 500 to "" }
        assertEquals(listOf("Hi"), runBlocking { tutor(chatGpt()).translate(listOf("Adiós"), "en") })
        assertEquals("gpt-6-terra", openAi.asked.last().getString("model"))
    }

    @Test
    fun theSongIsLookedUpOnTheWebAndWithoutWhenTheAccountMayNot() {
        val tutor = tutor()
        val song = MusicMetadata("November Blue (2023 Remix)", "S.Kiyotaka & Omega Tribe")
        answering("FIRST FINALE, 1985.\n---\nStory.")
        runBlocking { tutor.explainSong(song, listOf("ガラス越しの City Talk"), "es") {} }
        val asked = openAi.asked.single()
        assertEquals("web_search", asked.getJSONArray("tools").getJSONObject(0).getString("type"))
        assertTrue(asked.getString("instructions").contains("First search the web for this exact song") && asked.getString("instructions").contains("for this song by name"))
        // Refused, as OpenAI refuses a tool it doesn't allow, it's asked again without.
        openAi.reply = { body -> if (body.has("tools")) 400 to """{"detail":"Unsupported tool type: web_search"}""" else 200 to FakeOpenAi.stream("From the lyrics.") }
        assertEquals(ChatGptReply.Answer("From the lyrics."), runBlocking { tutor.explainSong(song.copy(title = "Other"), listOf("Line"), "es") {} })
        assertEquals(listOf(true, false), openAi.asked.takeLast(2).map { it.has("tools") })
        // Other questions don't search, and a 400 for them is no answer.
        openAi.reply = { 400 to "" }
        assertEquals(ChatGptReply.Failed, runBlocking { tutor.explain(song, listOf("Line"), 0, "es") {} })
        assertFalse(openAi.asked.last().has("tools"))
    }

    @Test
    fun eachLineKeepsItsOwnPartAndLinesTranslatedBeforeThatAreAskedAgain() {
        answering("[\"que nunca viste\"]")
        assertEquals(listOf("que nunca viste"), runBlocking { tutor().translate(listOf("見た事もないような"), "es") })
        // Told that a sentence over two lines keeps each line's part under it, rather than its words in the order the language would put them.
        assertTrue(openAi.asked.single().getString("instructions").contains("Each translation says only what its own line says"))
        // Kept as it was translated before that, it's asked again.
        val file = File(folder.root, "answers.json")
        file.writeText(file.readText().replace("translation$TRANSLATION_VERSION|", "translation|"))
        answering("[\"que nunca se vio\"]")
        assertEquals(listOf("que nunca se vio"), runBlocking { tutor().translate(listOf("見た事もないような"), "es") })
        assertEquals(2, openAi.asked.size)
    }

    @Test
    fun aLineIsTranslatedForTheSongItsIn() {
        val tutor = tutor()
        val one = MusicMetadata("November Blue", "S.Kiyotaka & Omega Tribe")
        answering("[\"Too vivid\"]")
        assertEquals(listOf("Too vivid"), runBlocking { tutor.translate(listOf("鮮やかすぎる"), "en", one) })
        // The same line in another song is translated in that song.
        answering("[\"So bright\"]")
        assertEquals(listOf("So bright"), runBlocking { tutor.translate(listOf("鮮やかすぎる"), "en", one.copy(title = "Asphalt Lady")) })
        // And kept for each.
        assertEquals(listOf("Too vivid"), runBlocking { tutor.translate(listOf("鮮やかすぎる"), "en", one) })
        assertEquals(2, openAi.asked.size)
    }

    @Test
    fun aTranslationShowsLineByLineAsItComesThinkingLittle() {
        val tutor = tutor()
        // Streamed in pieces that cut lines in two.
        openAi.reply = { 200 to FakeOpenAi.stream("[\"Charla urbana", " tras el cristal\", \"El vie", "nto trae rumores\"]") }
        val shown = mutableListOf<List<String?>>()
        val lines = listOf("ガラス越しの City Talk", "♪", "風が噂をはこんでく")
        assertEquals(listOf("Charla urbana tras el cristal", null, "El viento trae rumores"), runBlocking { tutor.translate(lines, "es") { shown += it } })
        assertEquals(
            listOf(listOf(null, null, null), listOf("Charla urbana tras el cristal", null, null), listOf("Charla urbana tras el cristal", null, "El viento trae rumores")),
            shown.distinct()
        )
        assertEquals("low", openAi.asked.single().getJSONObject("reasoning").getString("effort"))
        // Lines kept already show from the start; only new ones are asked.
        openAi.reply = { 200 to FakeOpenAi.stream("[\"Que llega", "ras tarde\"]") }
        shown.clear()
        runBlocking { tutor.translate(lines + "君が遅れたのは", "es") { shown += it } }
        assertEquals(listOf("Charla urbana tras el cristal", null, "El viento trae rumores", null), shown.first())
        assertEquals(JSONArray(listOf("君が遅れたのは")).toString(), openAi.asked.last().getJSONArray("input").getJSONObject(0).getString("content"))
    }

    @Test
    fun aQuestionAboutALineIsAnsweredWithWhatWasSaidBefore() {
        val tutor = tutor()
        val song = MusicMetadata("November Blue (2023 Remix)", "S.Kiyotaka & Omega Tribe")
        val lines = listOf("ガラス越しの City Talk", "Don't Say 誰かの腕時計は")
        answering("Es una elección estilística.")
        val written = mutableListOf<String>()
        val reply = runBlocking {
            tutor.askAboutLine(song, lines, 1, "Le pide que no lo diga.", listOf("¿Qué es は?" to "La partícula de tema."), "¿Por qué en inglés?", "es") { written += it }
        }
        assertEquals(ChatGptReply.Answer("Es una elección estilística."), reply)
        assertEquals("Es una elección estilística.", written.last())
        val asked = openAi.asked.single()
        val input = asked.getJSONArray("input").getJSONObject(0).getString("content")
        assertTrue(input, input.contains("2. Don't Say 誰かの腕時計は") && input.contains("What you said about it:\nLe pide que no lo diga."))
        assertTrue(input, input.endsWith("Question: ¿Qué es は?\nAnswer: La partícula de tema.\n\nQuestion: ¿Por qué en inglés?"))
        assertTrue(asked.getString("instructions").contains("you can only help with the song"))
        assertEquals("low", asked.getJSONObject("reasoning").getString("effort"))
        // Without an explanation, or about a line that isn't there.
        runBlocking { tutor.askAboutLine(song, lines, 0, null, emptyList(), "¿Y esto?", "es") {} }
        assertTrue(!openAi.asked.last().getJSONArray("input").getJSONObject(0).getString("content").contains("What you said"))
        assertEquals(ChatGptReply.Failed, runBlocking { tutor.askAboutLine(song, lines, 5, null, emptyList(), "?", "es") {} })
    }

    @Test
    fun readingsAreCheckedOncePerSong() {
        val tutor = tutor()
        val song = MusicMetadata("Asphalt Lady", "S.Kiyotaka & Omega Tribe")
        val lines = listOf("Shock!　君はアスファルト・レディ")
        val read = listOf(LineReading(listOf(Ruby("Shock!　"), Ruby("君", "くん"), Ruby("はアスファルト・レディ")), "Shock!　くんはアスファルト・レディ", "Shock! kun wa asufaruto redi"))
        answering("Here:\n[{\"line\": 1, \"kanji\": \"君\", \"reading\": \"きみ\"}]")
        val checked = runBlocking { tutor.checkReadings(song, lines, read) }!!
        assertEquals("きみ", checked[0].parts[1].reading)
        assertEquals("Shock! kimi wa asufaruto redi", checked[0].romanized)
        val asked = openAi.asked.single()
        assertEquals("low", asked.getJSONObject("reasoning").getString("effort"))
        val sent = JSONArray(asked.getJSONArray("input").getJSONObject(0).getString("content")).getJSONObject(0)
        assertEquals(1, sent.getInt("line"))
        assertEquals("君=くん", sent.getJSONArray("readings").getString(0))
        // Kept for the song's words; another copy of its words is checked anew.
        assertEquals(checked, runBlocking { tutor.checkReadings(song, lines, read) })
        assertEquals(1, openAi.asked.size)
        runBlocking { tutor.checkReadings(song, lines + "もう一度", read) }
        assertEquals(2, openAi.asked.size)
        // Nothing wrong, or no answer.
        answering("[]")
        assertEquals(read, runBlocking { tutor.checkReadings(song.copy(title = "Other"), lines, read) })
        openAi.reply = { 500 to "" }
        assertNull(runBlocking { tutor.checkReadings(song.copy(title = "Third"), lines, read) })
        answering("No idea")
        assertNull(runBlocking { tutor.checkReadings(song.copy(title = "Fourth"), lines, read) })
        openAi.models = { 500 to "" }
        assertNull(runBlocking { tutor(chatGpt()).checkReadings(song.copy(title = "Fifth"), lines, read) })
    }

    @Test
    fun eachLineTranslatedIsKeptApartFromTheOtherAnswers() {
        val answers = LookupCache(File(folder.root, "answers.json"), Dispatchers.Unconfined)
        val lines = LookupCache(File(folder.root, "lines.json"), Dispatchers.Unconfined)
        val tutor = ChatGptTutor(signedIn(), answers, lines)
        answering("[\"Hello\"]")
        assertEquals(listOf("Hello"), runBlocking { tutor.translate(listOf("Hola"), "en") })
        assertTrue(File(folder.root, "lines.json").readText().contains("Hello"))
        assertFalse(File(folder.root, "answers.json").exists())
    }

    @Test
    fun aQuestionIsAskedWithOnlyTheLastFewBeforeIt() {
        val tutor = tutor()
        val song = MusicMetadata("Song", "Band")
        val earlier = (1..10).map { "Question $it?" to "Answer $it." }
        runBlocking { tutor.askAboutSong(song, listOf("A line"), null, earlier, "Last?", "en") {} }
        runBlocking { tutor.askAboutLine(song, listOf("A line"), 0, null, earlier, "Last?", "en") {} }
        openAi.asked.map { it.getJSONArray("input").getJSONObject(0).getString("content") }.forEach { sent ->
            assertFalse(sent.contains("Question 4?"))
            assertTrue(sent.contains("Question: Question 5?\nAnswer: Answer 5."))
            assertTrue(sent.endsWith("Question: Question 10?\nAnswer: Answer 10.\n\nQuestion: Last?"))
        }
    }

    @Test
    fun songsAreNamedFromADescription() {
        val tutor = tutor()
        answering("[{\"title\": \"Plastic Love\", \"artist\": \"Mariya Takeuchi\"}, {\"title\": \" \", \"artist\": \"Nobody\"}]")
        assertEquals(listOf("Plastic Love" to "Mariya Takeuchi"), runBlocking { tutor.songsFor("  japanese 80s song, plastic something ") })
        assertEquals("japanese 80s song, plastic something", openAi.asked.single().getJSONArray("input").getJSONObject(0).getString("content"))
        // Kept, whatever the case.
        runBlocking { tutor.songsFor("Japanese 80s song, plastic something") }
        assertEquals(1, openAi.asked.size)
        answering("[]")
        assertEquals(emptyList<Pair<String, String>>(), runBlocking { tutor.songsFor("nothing like it") })
        // None found isn't kept: asked again, it may find some.
        runBlocking { tutor.songsFor("nothing like it") }
        assertEquals(3, openAi.asked.size)
        answering("No idea")
        assertNull(runBlocking { tutor.songsFor("something else") })
        openAi.models = { 500 to "" }
        assertNull(runBlocking { tutor(chatGpt()).songsFor("another") })
    }

    @Test
    fun aSongIsPickedFromAnotherServicesResultsByNumber() {
        val tutor = tutor()
        val song = MusicMetadata("Stay With Me", "Miki Matsubara")
        val rows = listOf("真夜中のドア〜stay with me | 松原みき • Pocket Park • 5:26", "Stay With Me | Sam Smith • In the Lonely Hour • 2:52")
        answering("1")
        assertEquals(0, runBlocking { tutor.pickSong(song, rows) })
        val asked = openAi.asked.single()
        assertEquals(
            "Song: Stay With Me by Miki Matsubara\n\nResults:\n1. ${rows[0]}\n2. ${rows[1]}",
            asked.getJSONArray("input").getJSONObject(0).getString("content")
        )
        assertTrue(asked.getString("instructions").contains("Prefer the original recording"))
        assertEquals("low", asked.getJSONObject("reasoning").getString("effort"))
        answering(" 2.\n")
        assertEquals(1, runBlocking { tutor.pickSong(song, rows) })
        // None, a number past the list, no number or no answer is no pick.
        listOf("0", "3", "None", "").forEach { text ->
            answering(text)
            assertNull(text, runBlocking { tutor.pickSong(song, rows) })
        }
        openAi.reply = { 500 to "" }
        assertNull(runBlocking { tutor.pickSong(song, rows) })
        // Nothing to pick from asks nothing.
        val count = openAi.asked.size
        assertNull(runBlocking { tutor.pickSong(song, emptyList()) })
        assertNull(runBlocking { tutor.pickSong(song, emptyList(), videos = true) })
        assertEquals(count, openAi.asked.size)
    }

    @Test
    fun aSongIsPickedFromVideosByTheirTitleNotTheirChannel() {
        val tutor = tutor()
        val song = MusicMetadata("Owaranai Uta", "THE BLUE HEARTS")
        val rows = listOf("【カラオケ】終わらない歌/THE BLUE HEARTS | Karaoke Ch • 39K views • 3:09", "The Blue Hearts - Owaranai Uta with lyrics | Fan • 195K views • 3:04")
        answering("2")
        assertEquals(1, runBlocking { tutor.pickSong(song, rows, videos = true) })
        val asked = openAi.asked.single()
        assertEquals(
            "Song: Owaranai Uta by THE BLUE HEARTS\n\nVideos:\n1. ${rows[0]}\n2. ${rows[1]}",
            asked.getJSONArray("input").getJSONObject(0).getString("content")
        )
        assertTrue(asked.getString("instructions").contains("its title, not its channel"))
        assertEquals("low", asked.getJSONObject("reasoning").getString("effort"))
        listOf("0", "3", "None").forEach { text ->
            answering(text)
            assertNull(text, runBlocking { tutor.pickSong(song, rows, videos = true) })
        }
    }

    @Test
    fun theWordsToKnowComeInOrderWithTheirReadingAndAreKept() {
        val tutor = tutor()
        val song = MusicMetadata("Plastic Love", "Mariya Takeuchi")
        val lines = listOf("突然のキスや 熱いまなざしで", "孤独な友だち")
        answering("[{\"word\": \"突然\", \"reading\": \"とつぜん\", \"meaning\": \"de repente\"}, {\"word\": \"孤独\", \"reading\": \"孤独\", \"meaning\": \"soledad\"}, {\"word\": \"突然\", \"meaning\": \"otra vez\"}, {\"word\": \"\", \"meaning\": \"nada\"}]")
        // One a word reads as written has no reading to show; one named twice, or without a word, is left out.
        assertEquals(listOf(KeyWord("突然", "とつぜん", "de repente"), KeyWord("孤独", null, "soledad")), runBlocking { tutor.wordsToKnow(song, lines, "es") })
        val asked = openAi.asked.single()
        assertTrue(asked.getString("instructions").contains("6 to 10 words") && asked.getString("instructions").contains("in a few words of Spanish"))
        assertTrue(asked.getJSONArray("input").getJSONObject(0).getString("content").endsWith("Lyrics:\n突然のキスや 熱いまなざしで\n孤独な友だち"))
        assertEquals("low", asked.getJSONObject("reasoning").getString("effort"))
        // Kept for the song.
        runBlocking { tutor.wordsToKnow(song, lines, "es") }
        assertEquals(1, openAi.asked.size)
        // None, or no answer, is none.
        listOf("[]", "No JSON").forEachIndexed { index, text ->
            answering(text)
            assertNull(runBlocking { tutor.wordsToKnow(song.copy(title = "Song $index"), lines, "es") })
        }
        openAi.models = { 500 to "" }
        assertNull(runBlocking { tutor(chatGpt()).wordsToKnow(song.copy(title = "Other"), lines, "es") })
    }


    @Test
    fun whatASongCoversOrSamplesIsFoundOnTheWebByItsName() {
        val tutor = tutor()
        val song = MusicMetadata("Hurt", "Johnny Cash")
        answering("{\"original\": {\"title\": \"Hurt\", \"artist\": \"Nine Inch Nails\"}, \"samples\": [{\"title\": \"I Got a Woman\", \"artist\": \"Ray Charles\"}, {\"title\": \"\"}]}")
        assertEquals(SongFacts("Hurt" to "Nine Inch Nails", listOf("I Got a Woman" to "Ray Charles")), runBlocking { tutor.songFacts(song) })
        val asked = openAi.asked.single()
        assertEquals("web_search", asked.getJSONArray("tools").getJSONObject(0).getString("type"))
        assertEquals("Song: Hurt by Johnny Cash", asked.getJSONArray("input").getJSONObject(0).getString("content"))
        runBlocking { tutor.songFacts(song) }
        assertEquals(1, openAi.asked.size)
        answering("{\"original\": null, \"samples\": [{\"title\": \"Beat\", \"artist\": \"Drummer\"}]}")
        assertEquals(SongFacts(null, listOf("Beat" to "Drummer")), runBlocking { tutor.songFacts(song.copy(title = "Other")) })
        // Neither, or no answer, is none.
        listOf("{\"original\": null, \"samples\": []}", "No JSON").forEachIndexed { index, text ->
            answering(text)
            assertNull(runBlocking { tutor.songFacts(song.copy(title = "Song $index")) })
        }
        // Nothing to say is kept, so the web isn't searched again for it; no answer isn't.
        val askedBefore = openAi.asked.size
        assertNull(runBlocking { tutor.songFacts(song.copy(title = "Song 0")) })
        assertEquals(askedBefore, openAi.asked.size)
        assertNull(runBlocking { tutor.songFacts(song.copy(title = "Song 1")) })
        assertEquals(askedBefore + 1, openAi.asked.size)
        openAi.models = { 500 to "" }
        assertNull(runBlocking { tutor(chatGpt()).songFacts(song.copy(title = "Fourth")) })
    }

    @Test
    fun theArtistIsLookedUpOnTheWebAndKept() {
        val tutor = tutor()
        val song = MusicMetadata("Plastic Love", "Mariya Takeuchi")
        answering("A singer from Shimane.\n---\nCity pop.\n---\nVariety (1984).")
        val written = mutableListOf<String>()
        val reply = runBlocking { tutor.aboutArtist(song, "en") { written += it } }
        assertEquals(ChatGptReply.Answer("A singer from Shimane.\n---\nCity pop.\n---\nVariety (1984)."), reply)
        assertEquals("A singer from Shimane.\n---\nCity pop.\n---\nVariety (1984).", written.last())
        val asked = openAi.asked.single()
        assertEquals("Artist: Mariya Takeuchi\nA song of theirs: Plastic Love", asked.getJSONArray("input").getJSONObject(0).getString("content"))
        assertEquals("web_search", asked.getJSONArray("tools").getJSONObject(0).getString("type"))
        // Kept for the artist, whichever of their songs.
        assertEquals(reply, runBlocking { tutor.aboutArtist(song.copy(title = "Septentrion"), "en") {} })
        assertEquals(1, openAi.asked.size)
        // Without the models listed, the one asked last is asked again.
        openAi.models = { 500 to "" }
        runBlocking { tutor(chatGpt()).aboutArtist(song.copy(artist = "Other"), "en") {} }
        assertEquals(2, openAi.asked.size)
        assertEquals(asked.getString("model"), openAi.asked.last().getString("model"))
    }

    @Test
    fun aWordIsUsedInExamplesTheWayItsLineDoesKeptForIt() {
        val tutor = tutor()
        val song = MusicMetadata("Plastic Love", "Mariya Takeuchi")
        answering("[{\"sentence\": \"予定を狂わせないでね。\", \"meaning\": \"No me cambies los planes.\"}, {\"sentence\": \"ペース\", \"meaning\": \"\"}]")
        assertEquals(listOf(Example("予定を狂わせないでね。", "No me cambies los planes.")), runBlocking { tutor.examples("狂わせ", "恋のプログラムを 狂わせないでね", song, "es") })
        val sent = JSONObject(openAi.asked.single().getJSONArray("input").getJSONObject(0).getString("content"))
        assertEquals("狂わせ", sent.getString("word"))
        assertEquals("恋のプログラムを 狂わせないでね", sent.getString("line"))
        assertEquals("Plastic Love by Mariya Takeuchi", sent.getString("song"))
        runBlocking { tutor.examples("狂わせ", "恋のプログラムを 狂わせないでね", song, "es") }
        assertEquals(1, openAi.asked.size)
        // Another word in the same line is asked anew.
        answering("[{\"sentence\": \"恋をした。\", \"meaning\": \"Me enamoré.\"}]")
        assertEquals(listOf(Example("恋をした。", "Me enamoré.")), runBlocking { tutor.examples("恋", "恋のプログラムを 狂わせないでね", song, "es") })
        assertEquals(2, openAi.asked.size)
        listOf("[]", "No JSON").forEachIndexed { index, text ->
            answering(text)
            assertNull(runBlocking { tutor.examples("Word", "Line $index", song, "es") })
        }
        openAi.models = { 500 to "" }
        assertNull(runBlocking { tutor(chatGpt()).examples("Word", "Line 9", song, "es") })
    }

    @Test
    fun songsLikeOneAreNamedAndKept() {
        val tutor = tutor()
        val song = MusicMetadata("Plastic Love", "Mariya Takeuchi")
        answering("[{\"title\": \"Stay With Me\", \"artist\": \"Miki Matsubara\"}, {\"title\": \"Nobody\"}]")
        assertEquals(listOf("Stay With Me" to "Miki Matsubara"), runBlocking { tutor.songsLike(song) })
        assertEquals("Plastic Love by Mariya Takeuchi", openAi.asked.single().getJSONArray("input").getJSONObject(0).getString("content"))
        assertTrue(openAi.asked.single().getString("instructions").contains("no song twice"))
        runBlocking { tutor.songsLike(song) }
        assertEquals(1, openAi.asked.size)
        answering("No idea")
        assertNull(runBlocking { tutor.songsLike(song.copy(title = "Other")) })
        openAi.models = { 500 to "" }
        assertNull(runBlocking { tutor(chatGpt()).songsLike(song.copy(title = "Third")) })
    }

    @Test
    fun aQuestionAboutTheWholeSongIsAnsweredWithWhatWasSaidBefore() {
        val tutor = tutor()
        val song = MusicMetadata("Plastic Love", "Mariya Takeuchi")
        answering("Porque habla de un amor falso.")
        val written = mutableListOf<String>()
        val reply = runBlocking {
            tutor.askAboutSong(song, listOf("突然のキスや"), "Una mujer que no se enamora.", listOf("¿Quién canta?" to "Ella."), "¿Por qué plástico?", "es") { written += it }
        }
        assertEquals(ChatGptReply.Answer("Porque habla de un amor falso."), reply)
        assertEquals("Porque habla de un amor falso.", written.last())
        val asked = openAi.asked.single()
        val input = asked.getJSONArray("input").getJSONObject(0).getString("content")
        assertTrue(input, input.contains("Lyrics:\n突然のキスや") && input.contains("What you said about it:\nUna mujer que no se enamora."))
        assertTrue(input, input.endsWith("Question: ¿Quién canta?\nAnswer: Ella.\n\nQuestion: ¿Por qué plástico?"))
        assertTrue(asked.getString("instructions").contains("you can only help with the song"))
        runBlocking { tutor.askAboutSong(song, emptyList(), null, emptyList(), "¿Y?", "es") {} }
        assertTrue(!openAi.asked.last().getJSONArray("input").getJSONObject(0).getString("content").contains("What you said"))
    }


    @Test
    fun japaneseInRomajiIsWrittenInJapaneseLineByLine() {
        val tutor = tutor()
        val song = MusicMetadata("Owaranai Uta", "Paranmaum")
        val lines = listOf("owaranai uta o utaou", "", "kusottare no sekai no tame")
        answering("[\" 終わらない歌を歌おう \", \"♪\", \"クソッタレの世界のため\"]")
        // A line with no words stays as it was.
        assertEquals(listOf("終わらない歌を歌おう", "", "クソッタレの世界のため"), runBlocking { tutor.fromRomaji(song, lines) })
        val asked = openAi.asked.single()
        val sent = JSONObject(asked.getJSONArray("input").getJSONObject(0).getString("content"))
        assertEquals("Owaranai Uta by Paranmaum", sent.getString("song"))
        assertEquals(JSONArray(lines).toString(), sent.getJSONArray("lines").toString())
        assertTrue(asked.getString("instructions").contains("keep words sung in English"))
        assertEquals("low", asked.getJSONObject("reasoning").getString("effort"))
        // Kept for the song.
        runBlocking { tutor.fromRomaji(song, lines) }
        assertEquals(1, openAi.asked.size)
        // Too few lines, or no answer, is none.
        listOf("[\"終わらない歌を歌おう\"]", "No JSON").forEachIndexed { index, text ->
            answering(text)
            assertNull(runBlocking { tutor.fromRomaji(song.copy(title = "Song $index"), lines) })
        }
        openAi.models = { 500 to "" }
        assertNull(runBlocking { tutor(chatGpt()).fromRomaji(song.copy(title = "Other"), lines) })
    }

    @Test
    fun koreanInLatinLettersIsWrittenInHangulLineByLine() {
        val tutor = tutor()
        val song = MusicMetadata("Song", "Band")
        val lines = listOf("neon nae mameul molla", "", "geu ttae uri dasi")
        answering("[\"넌 내 맘을 몰라\", \"\", \"그 때 우리 다시\"]")
        assertEquals(listOf("넌 내 맘을 몰라", "", "그 때 우리 다시"), runBlocking { tutor.fromRomaji(song, lines, Script.KOREAN) })
        val instructions = openAi.asked.single().getString("instructions")
        assertTrue(instructions, instructions.contains("to Korean") && instructions.contains("in Hangul") && !instructions.contains("Japanese"))
        // Kept apart from the same lines read as romaji.
        runBlocking { tutor.fromRomaji(song, lines, Script.KOREAN) }
        assertEquals(1, openAi.asked.size)
        runBlocking { tutor.fromRomaji(song, lines) }
        assertEquals(2, openAi.asked.size)
        assertTrue(openAi.asked.last().getString("instructions").contains("romaji to Japanese"))
        // Japanese stays kept where it was before Korean, so what earlier versions kept still counts.
        val keys = JSONArray(File(folder.root, "answers.json").readText()).let { kept -> (0 until kept.length()).map { kept.getJSONArray(it).getString(0) } }
        assertEquals(listOf("hangul", "romaji"), keys.map { it.substringBefore('|') })
    }

    private val aSong = MusicMetadata("Song", "Band")

    /** Every question the tutor answers with a list or an object, asked of [tutor]; each gives nothing when it couldn't be answered. */
    private fun everyLookup(tutor: ChatGptTutor): List<Any?> = runBlocking {
        listOf(
            tutor.translate(listOf("Hola"), "en"),
            tutor.checkReadings(aSong, listOf("君"), emptyList()),
            tutor.songsFor("a rainy song"),
            tutor.pickSong(aSong, listOf("Song | Band")),
            tutor.pickSong(aSong, listOf("Song | Band"), videos = true),
            tutor.fromRomaji(aSong, listOf("kimi")),
            tutor.wordsToKnow(aSong, listOf("Hola"), "en"),
            tutor.songFacts(aSong),
            tutor.examples("hola", "Hola amigo", aSong, "en"),
            tutor.songsLike(aSong),
            tutor.meaning("hola", "Hola amigo", "en")
        )
    }

    private fun everyExplanation(tutor: ChatGptTutor): List<ChatGptReply> = runBlocking {
        listOf(
            tutor.explainSong(aSong, listOf("Hola"), "en") {},
            tutor.explain(aSong, listOf("Hola"), 0, "en") {},
            tutor.aboutArtist(aSong, "en") {},
            tutor.askAboutSong(aSong, listOf("Hola"), "It's about a greeting.", listOf("Why?" to "Because."), "Who?", "en") {}
        )
    }

    @Test
    fun signedOutTheTutorAsksNothingAndAnswersNothing() {
        val tutor = tutor(chatGpt())
        assertTrue(everyLookup(tutor).all { it == null })
        assertEquals(listOf(ChatGptReply.Failed), everyExplanation(tutor).distinct())
        assertTrue(openAi.asked.isEmpty())
    }

    @Test
    fun anAnswerThatIsNoAnswerOrNotWhatWasAskedForIsNothing() {
        val tutor = tutor()
        // Plain failure, then an answer with no JSON in it.
        listOf<() -> Pair<Int, String>>({ 500 to "" }, { 200 to FakeOpenAi.stream("Sorry, I can't.") }).forEach { reply ->
            openAi.reply = { reply() }
            assertTrue(everyLookup(tutor).all { it == null })
        }
        openAi.reply = { 500 to "" }
        assertEquals(listOf(ChatGptReply.Failed), everyExplanation(tutor).distinct())
        // Nothing was kept from any of it, and none found isn't kept either.
        answering("[]")
        assertEquals(emptyList<Pair<String, String>>(), runBlocking { tutor.songsFor("a rainy song") })
        answering("""[{"title":"Plastic Love","artist":"Mariya Takeuchi"}]""")
        assertEquals(listOf("Plastic Love" to "Mariya Takeuchi"), runBlocking { tutor.songsFor("a rainy song") })
    }

    @Test
    fun anAnswerWithNothingUsableInItIsNothingAndIsAskedAgain() {
        val tutor = tutor()
        // Songs with no title or no artist are no songs; none left is an empty list, which isn't kept.
        answering("""[{"title":"Plastic Love"},{"artist":"Mariya Takeuchi"},{"title":" ","artist":"x"},"text"]""")
        assertEquals(emptyList<Pair<String, String>>(), runBlocking { tutor.songsFor("plastic algo") })
        assertEquals(emptyList<Pair<String, String>>(), runBlocking { tutor.songsLike(aSong) })
        // Words, examples and a meaning without both parts are none.
        answering("""[{"word":"","meaning":"x"},{"word":"hola","meaning":" "}]""")
        assertNull(runBlocking { tutor.wordsToKnow(aSong, listOf("Hola"), "en") })
        answering("""[{"sentence":"","meaning":"x"},{"sentence":"Hola","meaning":""}]""")
        assertNull(runBlocking { tutor.examples("hola", "Hola amigo", aSong, "en") })
        answering("""{"meaning":" ","type":"noun"}""")
        assertNull(runBlocking { tutor.meaning("hola", "Hola amigo", "en") })
        // A reading that is the word itself isn't one.
        answering("""[{"word":"hola","reading":"hola","meaning":"hello"},{"word":"君","reading":"きみ","meaning":"you"},{"word":"hola","reading":"","meaning":"again"}]""")
        assertEquals(
            listOf(KeyWord("hola", null, "hello"), KeyWord("君", "きみ", "you")),
            runBlocking { tutor.wordsToKnow(aSong, listOf("Hola"), "en") }
        )
        // A meaning with no kind of word still has its meaning.
        answering("""{"meaning":"hello","type":""}""")
        assertEquals(Definition(null, "hello"), runBlocking { tutor.meaning("hola", "Hola amigo", "en") })
    }

    @Test
    fun aSongThatCoversOrSamplesIsToldAndOneThatDoesntIsNone() {
        val tutor = tutor()
        answering("""{"original":null,"samples":[]}""")
        assertNull(runBlocking { tutor.songFacts(aSong) })
        answering("""{"original":{"title":"Orig","artist":"Them"},"samples":[{"title":"Bit","artist":"Else"},{"title":"No artist"},"text"]}""")
        assertEquals(SongFacts(Pair("Orig", "Them"), listOf(Pair("Bit", "Else"))), runBlocking { tutor.songFacts(MusicMetadata("Cover", "Us")) })
        answering("""{"original":{"title":"Orig"},"samples":[{"title":"Bit","artist":"Else"}]}""")
        assertEquals(SongFacts(null, listOf(Pair("Bit", "Else"))), runBlocking { tutor.songFacts(MusicMetadata("Cover 2", "Us")) })
        answering("""{"samples":null}""")
        assertNull(runBlocking { tutor.songFacts(MusicMetadata("Cover 3", "Us")) })
    }

    @Test
    fun theSearchResultPickedIsTheOneNumberedOrNone() {
        val tutor = tutor()
        val rows = listOf("A | X", "B | Y")
        listOf("2" to 1, "1." to 0, "3" to null, "0" to null, "none" to null, "" to null).forEach { (text, picked) ->
            answering(text)
            assertEquals(text, picked, runBlocking { tutor.pickSong(aSong, rows) })
            assertEquals(text, picked, runBlocking { tutor.pickSong(aSong, rows, videos = true) })
        }
        assertTrue(openAi.asked.last().getString("instructions").contains("YouTube videos"))
        // No rows, nothing to ask.
        val before = openAi.asked.size
        assertNull(runBlocking { tutor.pickSong(aSong, emptyList()) })
        assertEquals(before, openAi.asked.size)
    }

    @Test
    fun linesTheModelReturnsInTheWrongNumberAreNotRomajiFixes() {
        val tutor = tutor()
        answering("""["a"]""")
        assertNull(runBlocking { tutor.fromRomaji(aSong, listOf("a", "b")) })
        // Blank lines stay as they were, and the rest is trimmed.
        answering("""["  きみ ", "x"]""")
        assertEquals(listOf("きみ", " "), runBlocking { tutor.fromRomaji(aSong, listOf("kimi", " ")) })
    }

    @Test
    fun aLineOfWordsIsSortedIntoItsSheetPartsByHowItReads() {
        // Five parts are no mood, nor are parts with a number or a full stop in them.
        listOf("one · two · three · four · five", "one · 2", "one · two.", "one two three four · five").forEach { line ->
            assertEquals(line, emptyList<String>(), sheet(line).mood)
        }
        assertEquals(listOf("tender", "wistful"), sheet("tender · wistful").mood)
        // Once there is one, the next such line is part of the story.
        assertEquals(listOf("" to "a · b"), sheet("tender · wistful\na · b").facts)
        // Once the story has begun, a line with a colon is part of it.
        val sheet = sheet("It's a story, told long.\nYear: 1984")
        assertEquals(emptyList<Pair<String, String>>(), sheet.facts)
        assertEquals("It's a story, told long. Year: 1984", sheet.text)
    }
}
