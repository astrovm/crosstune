package com.astrovm.crosstune

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings, drawn on its own with the state it's in: what it offers signed in, what's wrong with a source, and each page. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun show(state: UiState, openSource: String? = null, actions: ScreenActions = ScreenActions()) {
        composeRule.setContent { CrosstuneTheme { SettingsScreen(state, actions, onBack = {}, openSource = openSource) } }
        composeRule.waitForIdle()
    }

    private val models = listOf(ChatGptModel("gpt-a", "Model A"), ChatGptModel("gpt-b", "Model B"))

    @Test
    fun signedInWithChatGptTheAccountsModelsAndWhereToTranslateAreOffered() {
        val picked = mutableListOf<String>()
        val translated = mutableListOf<Boolean>()
        show(
            UiState(chatGpt = ChatGptState(email = "me@example.com", models = models, model = "gpt-b"), learning = Learning(into = "fr", untranslated = setOf("es"))),
            actions = ScreenActions(onChatGptModelChange = { picked += it }, onChatGptTranslationChange = { translated += it })
        )
        composeRule.onNodeWithText(string(R.string.chatgpt_model)).performScrollTo()
        composeRule.onNodeWithText("Model B").assertExists()
        composeRule.onNodeWithText("Model B").performClick()
        composeRule.onNodeWithText("Model A").performClick()
        assertEquals(listOf("gpt-a"), picked)
        composeRule.onNodeWithText(string(R.string.setting_translation_server)).assertExists()
    }

    @Test
    fun aModelTheAccountDoesNotOfferOrOneWithNoAccountIsNotShown() {
        show(UiState(chatGpt = ChatGptState(email = "me@example.com", models = models, model = "gone")))
        composeRule.onNodeWithText(string(R.string.chatgpt_model)).assertDoesNotExist()
    }

    @Test
    fun withoutAnAccountThereIsNoModelAndNoChoiceOfTranslator() {
        show(UiState(chatGpt = ChatGptState(models = models, model = "gpt-a")))
        composeRule.onNodeWithText(string(R.string.chatgpt_model)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.setting_translation_server)).assertDoesNotExist()
    }

    @Test
    fun theFirstSignInSaysTheAiFeaturesNowUseThePlan() {
        var dismissed = 0
        show(UiState(chatGpt = ChatGptState(email = "me@example.com", welcome = true)), actions = ScreenActions(onDismissChatGptWelcome = { dismissed++ }))
        composeRule.onNodeWithText(string(R.string.chatgpt_welcome_title)).assertExists()
    }

    @Test
    fun anUnknownPageIsTheList() {
        show(UiState(), openSource = "nowhere")
        composeRule.onNodeWithText(string(R.string.settings_title)).assertExists()
    }

    @Test
    fun theAdvancedPageOpensFromTheList() {
        show(UiState(), openSource = "advanced")
        composeRule.onNodeWithText(string(R.string.settings_advanced_title)).assertExists()
    }

    @Test
    fun theVisualsPageOpensFromTheList() {
        show(UiState(), openSource = "visuals")
        composeRule.onNodeWithText(string(R.string.setting_visuals_list)).assertExists()
    }

    @Test
    fun theSourcesPageSaysWhatKeepsALinkFromReachingCrosstune() {
        val spotify = LinkApp("com.spotify.music", "Spotify app")
        val other = LinkApp("com.other", "Other app")
        show(
            UiState(
                intercepted = setOf(MusicService.SPOTIFY, MusicService.DEEZER, MusicService.TIDAL),
                unapprovedHosts = mapOf(MusicService.TIDAL to listOf("tidal.com")),
                claimingAppsBySource = mapOf(
                    MusicService.SPOTIFY.name to mapOf(spotify to true, other to false),
                    MusicService.DEEZER.name to mapOf(spotify to true, other to true)
                )
            ),
            openSource = "sources"
        )
        composeRule.onNodeWithText(string(R.string.settings_links_title)).assertExists()
        composeRule.onNodeWithContentDescription(string(R.string.notice_app_still_opens, "Spotify app")).assertExists()
        composeRule.onNodeWithContentDescription(string(R.string.notice_apps_still_open, "Other app, Spotify app")).assertExists()
        composeRule.onNodeWithContentDescription(string(R.string.setup_not_allowed)).assertExists()
    }

    @Test
    fun aSourcesOwnPageOpensToItsRuleAndAWebFrontendsToItsSite() {
        show(UiState(intercepted = setOf(MusicService.SPOTIFY)), openSource = MusicService.SPOTIFY.name)
        composeRule.onNodeWithText(string(MusicService.SPOTIFY.labelRes)).assertExists()
    }

    @Test
    fun theAppYouListenInCantBeTurnedOnAndASourceWithARuleSaysWhereItOpens() {
        show(
            UiState(
                hasDefault = true, linksInCrosstune = false, defaultDestination = Destination.Service(MusicService.SPOTIFY),
                intercepted = setOf(MusicService.TIDAL), rules = mapOf(MusicService.TIDAL to Destination.Service(MusicService.DEEZER))
            ),
            openSource = "sources"
        )
        composeRule.onNodeWithContentDescription(string(R.string.settings_where_you_listen)).assertExists()
        composeRule.onNodeWithText(string(R.string.rule_opens_in, string(MusicService.DEEZER.labelRes))).assertExists()
    }

    @Test
    fun listeningInASourceAlreadyOnSaysItsLinksAlreadyOpenThere() {
        show(
            UiState(hasDefault = true, linksInCrosstune = false, defaultDestination = Destination.Service(MusicService.SPOTIFY), intercepted = setOf(MusicService.SPOTIFY)),
            openSource = MusicService.SPOTIFY.name
        )
        composeRule.onNodeWithText(string(R.string.setup_sources_listening_note, string(MusicService.SPOTIFY.labelRes))).assertExists()
    }

    @Test
    fun aWebFrontendHasItsSiteToEditAndSaveOnlyWhenItIsAnAddress() {
        val changed = mutableListOf<Pair<Frontend, String>>()
        val frontend = Frontend.SOURCES.first()
        show(
            UiState(destinations = MusicService.entries.map(Destination::Service) + Destination.Alternative(frontend, frontend.defaultInstance)),
            openSource = Destination.FRONTEND_PREFIX + frontend.name,
            actions = ScreenActions(onFrontendInstanceChange = { f, address -> changed += f to address; address.startsWith("https://") })
        )
        composeRule.onNodeWithText(string(R.string.frontend_site_on, frontend.defaultInstance.orEmpty().removePrefix("https://"))).performClick()
        composeRule.onNodeWithText(string(R.string.save_button)).performClick()
        assertEquals(listOf(frontend to frontend.defaultInstance.orEmpty()), changed)
    }
}
