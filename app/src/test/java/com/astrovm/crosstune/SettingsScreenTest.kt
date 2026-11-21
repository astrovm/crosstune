package com.astrovm.crosstune

import android.content.Context
import android.text.format.Formatter
import androidx.compose.material3.DividerDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.unit.DpRect
import androidx.test.core.app.ApplicationProvider
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    @Config(qualifiers = "de-w411dp-h891dp-xxhdpi")
    fun theSignedOutChatGptHintStaysWholeWithLargeGermanText() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f)
            ) {
                CrosstuneTheme { SettingsScreen(UiState(), ScreenActions(), onBack = {}) }
            }
        }
        val hint = composeRule.onNodeWithText(string(R.string.chatgpt_use_plan)).performScrollTo()
        val layouts = mutableListOf<TextLayoutResult>()
        hint.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().lineCount > 1)
        assertFalse(layouts.single().hasVisualOverflow)
    }

    @Test
    @Config(qualifiers = "es-w411dp-h891dp-xxhdpi", fontScale = 2f)
    fun aSettingsNameKeepsItsWordsWholeBesideWhatItIsSetToInSpanishAtTheLargestFont() = namesWholeAtTheLargestFont()

    @Test
    @Config(qualifiers = "es-w411dp-h891dp-xxhdpi", fontScale = 3.2f)
    fun aSettingsNameKeepsItsWordsWholeBesideWhatItIsSetToInSpanishAtTheBiggestFontAndroidHas() = namesWholeAtTheLargestFont()

    @Test
    @Config(qualifiers = "de-w320dp-h891dp-xxhdpi", fontScale = 2f)
    fun aSettingsNameKeepsItsWordsWholeBesideWhatItIsSetToInGermanOnANarrowPhone() = namesWholeAtTheLargestFont()

    @Test
    @Config(qualifiers = "de-w411dp-h891dp-xxhdpi", fontScale = 3.2f)
    fun aSettingsNameKeepsItsWordsWholeBesideWhatItIsSetToInGermanAtTheBiggestFontAndroidHas() = namesWholeAtTheLargestFont()

    @Test
    fun theMusicAppRowSaysWhatItIsForWithTheAppItIsSetTo() {
        show(UiState())
        // One button, so a screen reader says both, as the other rows do.
        composeRule.onNodeWithTag(DEFAULT_MENU_TAG).assert(hasText(string(R.string.settings_default_label))).assert(hasText(string(R.string.target_youtube_music)))
    }

    @Test
    fun theMusicAppsMenuTicksTheAppItIsSetToAsTheOtherSettingsDo() {
        val spotify = Destination.Service(MusicService.SPOTIFY)
        val deezer = Destination.Service(MusicService.DEEZER)
        show(UiState(destinations = listOf(spotify, deezer), defaultDestination = deezer))
        composeRule.onNodeWithTag(DEFAULT_MENU_TAG).performClick()
        composeRule.waitForIdle()
        // One tick, by the app it's set to, not its color alone, which says nothing to someone who can't tell it apart.
        composeRule.onAllNodes(hasTestTag(PICKED_TAG), useUnmergedTree = true).assertCountEquals(1)
        composeRule.onNode(hasClickAction() and hasAnyDescendant(hasText(string(R.string.service_spotify))), useUnmergedTree = true).assertIsNotSelected()
        composeRule.onNode(isSelected() and hasAnyDescendant(hasText(string(R.string.target_deezer))) and hasAnyDescendant(hasTestTag(PICKED_TAG)), useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun theMusicAppsMenuListsTheAppsOnThePhoneThenMoreAndClosesOnAPick() {
        val spotify = Destination.Service(MusicService.SPOTIFY)
        val deezer = Destination.Service(MusicService.DEEZER)
        val tidal = Destination.Service(MusicService.TIDAL)
        val picked = mutableListOf<Destination>()
        show(
            UiState(destinations = listOf(spotify, deezer, tidal), defaultDestination = deezer, installed = setOf(MusicService.SPOTIFY)),
            actions = ScreenActions(onTargetChange = { picked += it })
        )
        val more = string(R.string.more_button)
        val tidalName = string(R.string.target_tidal)
        // The app it's set to shows by its icon beside its name.
        composeRule.onNodeWithTag("destination-icon:DEEZER", useUnmergedTree = true).assertExists()
        fun open() {
            composeRule.onNodeWithTag(DEFAULT_MENU_TAG).performClick()
            composeRule.waitForIdle()
        }
        // The apps on the phone and the one it's set to; the rest wait behind More.
        open()
        composeRule.onNodeWithText(string(R.string.service_spotify)).assertExists()
        composeRule.onNodeWithText(tidalName).assertDoesNotExist()
        composeRule.onNodeWithText(more).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(more).assertDoesNotExist()
        composeRule.onNodeWithText(tidalName).performClick()
        composeRule.waitForIdle()
        // Picked, the menu closes; opened again, it's short again.
        assertEquals(listOf<Destination>(tidal), picked)
        composeRule.onNodeWithText(tidalName).assertDoesNotExist()
        open()
        composeRule.onNodeWithText(more).assertExists()
        composeRule.onNodeWithText(tidalName).assertDoesNotExist()
    }

    @Test
    fun aShortValueStaysBesideItsSettingAtTheUsualFont() {
        show(UiState())
        val name = composeRule.onNodeWithText(string(R.string.setting_language), useUnmergedTree = true).performScrollTo().getBoundsInRoot()
        val density = composeRule.density
        val values = composeRule.onAllNodesWithText(string(R.string.language_system_default), useUnmergedTree = true).fetchSemanticsNodes()
            .map { node -> with(density) { node.boundsInRoot.let { DpRect(it.left.toDp(), it.top.toDp(), it.right.toDp(), it.bottom.toDp()) } } }
        // On the same line, at the right.
        assertTrue("$name, $values", values.any { value -> value.top < name.bottom && value.bottom > name.top && value.left >= name.right })
    }

    @Test
    fun theLanguageMenuMarksEachLanguageWithItsFlag() {
        show(UiState())
        composeRule.onNodeWithText(string(R.string.setting_language)).performScrollTo().performClick()
        composeRule.waitForIdle()
        val flags = AppLanguage.tags.mapNotNull(AppLanguage::flag)
        assertTrue(flags.isNotEmpty())
        for (flag in flags) assertTrue(flag, composeRule.onAllNodesWithText(flag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    /** Every setting with what it's set to beside it: its name a line a word at most, never broken inside one. */
    private fun namesWholeAtTheLargestFont() {
        // At the font the test is set to, grown as Android grows it.
        composeRule.setContent { CrosstuneTheme { SettingsScreen(UiState(), ScreenActions(), onBack = {}) } }
        val names = listOf(
            R.string.setting_language, R.string.setting_theme, R.string.setting_color, R.string.settings_links_title, R.string.setting_translate_into,
            R.string.setting_untranslated, R.string.setting_lines_early, R.string.setting_next_line_first, R.string.setting_visuals_list, R.string.setting_visuals_change,
            // The app songs open in, what isn't translated, and the switches, each beside its row's words or over them.
            R.string.settings_default_label, R.string.untranslated_none, R.string.setting_show_in_crosstune_first, R.string.setting_play_right_away,
            R.string.setting_lyrics_follow, R.string.setting_auto_report
        )
        for (id in names) {
            val name = string(id)
            val node = composeRule.onAllNodesWithText(name, useUnmergedTree = true).onFirst().performScrollTo()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            // A line ends only where a word does, or after a hyphen, as in "Song-Probleme"; or inside a word
            // wider than the whole row, as "automáticamente" is at the biggest font, which nothing could fit.
            val input = layout.layoutInput
            val row = with(input.density) { (composeRule.onRoot().getBoundsInRoot().right - 40.dp).toPx() }
            fun widerThanTheRow(word: String) = Paragraph(word, input.style, Constraints(), input.density, input.fontFamilyResolver).maxIntrinsicWidth > row
            val pieces = name.split(' ', '-').size
            val ends = (0 until layout.lineCount - 1).map { line -> name.substring(0, layout.getLineEnd(line, visibleEnd = true)).trimEnd() }
            val inWords = ends.filterNot { end -> end.isEmpty() || end.endsWith('-') || name.getOrNull(end.length) == ' ' }
            val word = { end: String -> end.substringAfterLast(' ') + name.substring(end.length).substringBefore(' ') }
            assertTrue("\"$name\" breaks as ${ends.map { it.takeLast(6) }}", inWords.all { widerThanTheRow(word(it)) } && layout.lineCount <= pieces + inWords.size)
            assertFalse("\"$name\" is cut short", layout.isLineEllipsized(layout.lineCount - 1))
            // And every line is inside the name's room, none cut off at its edge.
            val widest = (0 until layout.lineCount).maxOf { layout.getLineRight(it) }
            assertTrue("\"$name\" is $widest px wide in ${layout.size.width} px", widest <= layout.size.width + 0.5f && !layout.didOverflowHeight)
        }
    }

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
    fun theAdvancedPageSaysHowMuchRoomWhatsKeptOfflineTakesAsItGrows() {
        var state by mutableStateOf(UiState(offlineUsedBytes = 0))
        composeRule.setContent { CrosstuneTheme { SettingsScreen(state, ScreenActions(), onBack = {}, openSource = "advanced") } }
        val used = { bytes: Long -> string(R.string.setting_offline_space_used, Formatter.formatShortFileSize(context, bytes)) }
        composeRule.onNodeWithText(string(R.string.offline)).performScrollTo().assertExists()
        composeRule.onNodeWithText(used(0)).performScrollTo().assertExists()
        // Kept songs fetched while it's open show at once.
        state = state.copy(offlineUsedBytes = 3_500_000)
        composeRule.onNodeWithText(used(3_500_000)).assertExists()
        composeRule.onNodeWithText(used(0)).assertDoesNotExist()
    }

    @Test
    fun everyTwoRowsOfAboutHaveAHairlineBetweenThem() {
        show(UiState(autoReports = true))
        composeRule.onNodeWithText(string(R.string.privacy_policy)).performScrollTo()
        val rows = listOf(
            string(R.string.report_problem),
            string(R.string.setting_auto_report),
            string(R.string.privacy_policy)
        ).map { composeRule.onNodeWithText(it).getBoundsInRoot() }
        // Each row ends where the hairline under it starts, and the next starts where it ends.
        assertEquals(listOf(DividerDefaults.Thickness, DividerDefaults.Thickness), rows.zipWithNext { above, below -> below.top - above.bottom })
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
