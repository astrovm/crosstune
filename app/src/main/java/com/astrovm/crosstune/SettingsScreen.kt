package com.astrovm.crosstune

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import android.os.Build
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.astrovm.crosstune.ui.theme.Palette
import com.astrovm.crosstune.ui.theme.wallpaperColors
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import kotlin.math.roundToInt
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.astrovm.crosstune.ui.theme.CrosstuneTheme

@Composable
internal fun SettingsScreen(
    state: UiState,
    actions: ScreenActions,
    onBack: () -> Unit,
    // Kept by the caller, so coming back from a guide returns to the same source's page.
    openSource: String? = null,
    onOpenSource: (String?) -> Unit = {}
) {
    val sources = sourceSettings(state, actions)
    // Kept out here, so the list is where it was on coming back from a page.
    val listScroll = rememberScrollState()
    // A source's page slides in over the list, and back out the other way.
    // The visuals' list and the advanced settings too.
    AnimatedContent(
        targetState = openSource?.takeIf { key -> key == VISUALS_PAGE || key == ADVANCED_PAGE || sources.any { it.key == key } },
        transitionSpec = { slide(forward = targetState != null) },
        label = "source"
    ) { key ->
        val item = sources.firstOrNull { it.key == key }
        if (key == VISUALS_PAGE) {
            VisualsPage(state.visualsSettings, actions, onBack = { onOpenSource(null) })
        } else if (key == ADVANCED_PAGE) {
            AdvancedPage(state, actions, onBack = { onOpenSource(null) })
        } else if (item != null) {
            SourcePage(item, state.destinations, state.installed, state.claimingAppsBySource.orEmpty(), state.onlyMusicVideos, actions, fixingLinks = state.fixingInOneTap == OneTapFix.LINKS, onBack = { onOpenSource(null) })
        } else {
            SettingsList(state, actions, sources, listScroll, onBack, onOpenSource)
        }
    }
}

@Composable
private fun SettingsList(
    state: UiState,
    actions: ScreenActions,
    sources: List<SourceSettings>,
    scroll: ScrollState,
    onBack: () -> Unit,
    onOpenSource: (String?) -> Unit
) {
    var addingCustom by rememberSaveable { mutableStateOf(false) }
    val openWebPage = rememberWebOpener()
    val githubUrl = stringResource(R.string.github_repo_url)
    val privacyUrl = stringResource(R.string.privacy_policy_url)

    Page(
        title = stringResource(R.string.settings_title),
        navigationIcon = {
            IconButton(onClick = onBack) {
                AppIcon(R.drawable.ic_arrow_back, contentDescription = stringResource(R.string.back_button))
            }
        },
        scrollState = scroll
    ) {
        Column(modifier = Modifier.padding(top = 4.dp)) { LinkNotices(state, actions, inSettings = true) }

        // "Open links in" says what this is, also when it's set to ask or show first.
        Spacer(Modifier.height(16.dp))
        Group {
            DefaultDestinationMenu(
                destinations = state.destinations,
                selected = state.defaultDestination,
                mode = state.linkMode,
                onSelect = actions.onTargetChange,
                onMode = actions.onLinkModeChange,
                installed = state.installed,
                label = stringResource(R.string.settings_default_label),
                modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
            )
        }

        SectionHeader(title = stringResource(R.string.settings_links_title))
        Group {
            sources.forEachIndexed { index, item ->
                if (index > 0) GroupDivider()
                SourceSummaryRow(item, state.installed, onOpen = { onOpenSource(item.key) })
            }
        }


        SectionHeader(
            title = stringResource(R.string.settings_custom_title)
        )
        Group {
            state.destinations.filterIsInstance<Destination.Custom>().forEach { custom ->
                Row(
                    modifier = Modifier.padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Like every other app and site, it's shown with its icon: its first letter.
                    DestinationIcon(custom, state.installed)
                    Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(custom.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            custom.template,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { actions.onRemoveCustom(custom) }) {
                        AppIcon(R.drawable.ic_delete, contentDescription = stringResource(R.string.remove_button))
                    }
                }
                GroupDivider()
            }
            AnimatedContent(targetState = addingCustom, transitionSpec = { swap() }, label = "add") { adding ->
                if (adding) {
                    AddCustomDestinationForm(
                        onAdd = { name, template ->
                            actions.onAddCustom(name, template).also { added -> if (added) addingCustom = false }
                        },
                        onCancel = { addingCustom = false }
                    )
                } else {
                    TextButton(
                        onClick = { addingCustom = true },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        AppIcon(R.drawable.ic_add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.add_custom_destination_button), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        // What happens around a song once it's found: naming one playing nearby, and its words.
        SectionHeader(stringResource(R.string.settings_listening_title))
        Group {
            // Which app names a song playing nearby, when there's more than one.
            if (actions.recognizers.size > 1) {
                RecognizerRow(actions)
                GroupDivider()
            }
            // On, or off, in Android's settings: switching it opens them, with the steps where there are some.
            TranslateIntoRow(state.learning.into, actions.onTranslateIntoChange)
            GroupDivider()
            UntranslatedRow(state.learning.untranslated, actions.onUntranslatedChange)
            GroupDivider()
            // Signed in with ChatGPT, it or Google Translate; without, Google Translate is all there is.
            if (state.chatGpt.email != null) {
                TranslationSourceRow(state.learning.chatGptTranslation, actions.onChatGptTranslationChange)
                GroupDivider()
            }
            SettingSwitch(
                label = stringResource(R.string.setting_lyrics_follow),
                description = stringResource(R.string.setting_lyrics_follow_description),
                checked = state.canFollowApps,
                onCheckedChange = { on -> if (on) actions.onAllowFollowing() else actions.onOpenFollowAccess() }
            )
        }

        // The AI features run on the user's ChatGPT plan, once signed in.
        SectionHeader(stringResource(R.string.settings_chatgpt_title))
        Group {
            ChatGptRow(state.chatGpt, actions)
            if (state.chatGpt.email != null) {
                GroupDivider()
                ManageUsageRow(onClick = { openWebPage(ChatGpt.USAGE_URL) })
            }
            // Signed in, which of the account's models to ask.
            val model = state.chatGpt.model?.takeIf { slug -> state.chatGpt.email != null && state.chatGpt.models.any { it.slug == slug } }
            if (model != null) {
                GroupDivider()
                ChoiceRow(
                    label = stringResource(R.string.chatgpt_model),
                    choices = state.chatGpt.models.map { it.slug to it.name },
                    selected = model,
                    onSelect = actions.onChatGptModelChange
                )
            }
        }
        if (state.chatGpt.welcome) ChatGptWelcome(onDismiss = actions.onDismissChatGptWelcome)

        // Which visuals move behind the words, and how long each shows; how dark they are is set over them.
        SectionHeader(stringResource(R.string.settings_visuals_title))
        Group {
            VisualsListRow(state.visualsSettings, onOpen = { onOpenSource(VISUALS_PAGE) })
            GroupDivider()
            VisualsChangeRow(state.visualsSettings.changeMs, actions.onVisualsChangeEveryChange)
        }

        SectionHeader(stringResource(R.string.settings_appearance_title))
        Group {
            ThemeRow(state.theme, actions.onThemeChange)
            GroupDivider()
            PaletteRow(state.palette, actions.onPaletteChange)
            GroupDivider()
            SettingSwitch(
                label = stringResource(R.string.setting_pure_black),
                description = null,
                checked = state.pureBlack,
                onCheckedChange = actions.onPureBlackChange
            )
            GroupDivider()
            LanguageRow(actions.onLanguageChange)
        }

        // What few change, out of the way.
        Spacer(Modifier.height(16.dp))
        Group { PageRow(stringResource(R.string.settings_advanced_title), onOpen = { onOpenSource(ADVANCED_PAGE) }) }

        SectionHeader(stringResource(R.string.settings_about_title))
        Group {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_version),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = appVersion(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            GroupDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openWebPage(privacyUrl) }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.privacy_policy),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                AppIcon(R.drawable.ic_open_in_new, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            GroupDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openWebPage(githubUrl) }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val (before, after) = stringResource(R.string.made_with_love, HEART).split(HEART).map { it.trim() }
                if (before.isNotEmpty()) Text(before, style = MaterialTheme.typography.bodyLarge)
                Image(
                    painterResource(R.drawable.ic_heart),
                    contentDescription = null,
                    modifier = Modifier.padding(horizontal = 4.dp).size(16.dp)
                )
                Text(after, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                AppIcon(R.drawable.ic_open_in_new, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun LinkOwnerRow(app: LinkApp, opensThem: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(if (opensThem) R.string.link_owner_opens_them else R.string.link_owner_lets_crosstune),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AppIcon(R.drawable.ic_open_in_new, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

/** "Opens videos on yewtu.be": the site a web frontend opens on; tapping it edits the site in place. */
@Composable
private fun FrontendSiteRow(web: Destination.Alternative, onChange: (String) -> Boolean) {
    var editing by rememberSaveable { mutableStateOf(false) }
    if (!editing) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { editing = true }
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.frontend_site_on, web.instance.orEmpty().removePrefix("https://")),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            AppIcon(R.drawable.ic_edit, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        return
    }
    var address by rememberSaveable { mutableStateOf(web.instance.orEmpty()) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)) {
        OutlinedTextField(
            value = address,
            onValueChange = { address = it; invalid = false },
            label = { Text(stringResource(R.string.frontend_address_title, web.frontend.label)) },
            supportingText = { if (invalid) Text(stringResource(R.string.frontend_address_invalid)) },
            isError = invalid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel_button)) }
            TextButton(onClick = { if (onChange(address)) editing = false else invalid = true }) {
                Text(stringResource(R.string.save_button))
            }
        }
    }
}

/** Where lyrics are translated, signed in with ChatGPT: by ChatGPT, or by Google Translate. */
@Composable
private fun TranslationSourceRow(chatGpt: Boolean, onSelect: (Boolean) -> Unit) = ChoiceRow(
    label = stringResource(R.string.setting_translation_server),
    choices = listOf(true to CHATGPT, false to GOOGLE_TRANSLATE),
    selected = chatGpt,
    onSelect = onSelect,
    icon = { if (it) R.drawable.ic_translator_chatgpt else R.drawable.ic_translator_google }
)

/** A language's flag, or a globe for the app's or the phone's own. */
@Composable
private fun LanguageMark(tag: String?, modifier: Modifier = Modifier) {
    val flag = tag?.let(AppLanguage::flag)
    if (flag != null) Text(flag, style = MaterialTheme.typography.bodyLarge, modifier = modifier)
    else AppIcon(R.drawable.ic_language, contentDescription = null, modifier = modifier.size(18.dp))
}

/**
 * The ChatGPT account, by its logo: OpenAI's own "Continue with ChatGPT" to sign in, the browser
 * being waited for, or who's signed in, to sign out.
 */
@Composable
private fun ChatGptRow(chatGpt: ChatGptState, actions: ScreenActions) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
        val email = chatGpt.email
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
            AppIcon(R.drawable.ic_translator_chatgpt, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(
                email ?: stringResource(R.string.chatgpt_use_plan),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 16.dp)
            )
            when {
                chatGpt.signingIn -> {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    TextButton(onClick = actions.onCancelChatGptSignIn) { Text(stringResource(R.string.cancel_button)) }
                }
                email != null -> TextButton(onClick = actions.onSignOutOfChatGpt) { Text(stringResource(R.string.chatgpt_sign_out)) }
            }
        }
        chatGpt.problem?.let { problem ->
            Text(
                stringResource(if (problem == ChatGptSignInResult.DECLINED) R.string.chatgpt_declined else R.string.chatgpt_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 40.dp)
            )
        }
        // Black, as OpenAI asks of its sign-in button.
        if (email == null && !chatGpt.signingIn) {
            Button(
                onClick = actions.onSignInWithChatGpt,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                // Edged, so it shows on a pure black ground too.
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.align(Alignment.End).padding(end = 8.dp, top = 4.dp)
            ) { Text(stringResource(R.string.chatgpt_continue)) }
        }
    }
}

/** Opens where the user sees, and limits, what Crosstune uses of their plan. */
@Composable
private fun ManageUsageRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.chatgpt_manage_usage), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        AppIcon(R.drawable.ic_open_in_new, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

/** Said once, after the first sign-in: the AI features use the user's ChatGPT plan from now on. */
@Composable
private fun ChatGptWelcome(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chatgpt_welcome_title)) },
        text = { Text(stringResource(R.string.chatgpt_welcome_message)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.got_it_button)) } }
    )
}

/** The translation service used when no server is set; a name, so never translated. */
private const val GOOGLE_TRANSLATE = "Google Translate"
private const val CHATGPT = "ChatGPT"

/** Marks where the heart icon goes in [R.string.made_with_love]. */
internal const val HEART = "\uFFFC"

/** Picks the app the Recognize button opens. */
@Composable
private fun RecognizerRow(actions: ScreenActions) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.setting_recognizer),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                actions.recognizer?.let { PackageIcon(it.packageName, size = 20.dp) }
                Text(
                    text = actions.recognizer?.label.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp)
                )
                AppIcon(R.drawable.ic_expand_more, contentDescription = null, modifier = Modifier.padding(start = 2.dp).size(16.dp).flipWhen(expanded))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                actions.recognizers.forEach { recognizer ->
                    DropdownMenuItem(
                        text = { Text(recognizer.label) },
                        leadingIcon = { PackageIcon(recognizer.packageName, size = 24.dp) },
                        trailingIcon = { PickedMark(recognizer == actions.recognizer) },
                        modifier = Modifier.semantics { selected = recognizer == actions.recognizer },
                        onClick = {
                            expanded = false
                            actions.onRecognizerChange(recognizer)
                        }
                    )
                }
            }
        }
    }
}

/** What happens when the app a song goes to doesn't have it: ask, open it where it's from, or search anyway. */
/** The page listing the visuals. */
private const val VISUALS_PAGE = "visuals"

/** How many of the visuals are on, opening the list of them. */
@Composable
private fun VisualsListRow(settings: VisualsSettings, onOpen: () -> Unit) {
    val names = presetNames()
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.setting_visuals_list), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            stringResource(R.string.visuals_list_summary, names.count(settings::shows), names.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** The visuals that come with the app, by name. */
@Composable
private fun presetNames(): List<String> {
    val assets = LocalContext.current.assets
    return remember(assets) { milkdropPresets(assets) }
}

/** How long each visual shows, short, as the phone's language writes it: "15 sec", "1 min", or never. */
@Composable
private fun changeEveryText(ms: Long): String {
    if (ms == VisualsSettings.NEVER) return stringResource(R.string.visuals_change_never)
    val format = android.icu.text.MeasureFormat.getInstance(LocalConfiguration.current.locales[0], android.icu.text.MeasureFormat.FormatWidth.SHORT)
    return if (ms < 60_000) format.format(Measure(ms / 1000, MeasureUnit.SECOND)) else format.format(Measure(ms / 60_000, MeasureUnit.MINUTE))
}

/**
 * How long each visual shows, on a slider from swapping every few seconds to staying on one, with
 * what it's set to beside it. Also in the visuals' own menu, held down.
 */
@Composable
internal fun VisualsChangeRow(changeMs: Long, onChange: (Long) -> Unit, modifier: Modifier = Modifier, inset: Dp = 20.dp) {
    val steps = VisualsSettings.CHANGE_EVERY
    val label = stringResource(R.string.setting_visuals_change)
    val shown = changeEveryText(changeMs)
    Column(modifier = modifier.fillMaxWidth().padding(start = inset, end = inset, top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(shown, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = steps.indexOf(changeMs).coerceAtLeast(0).toFloat(),
            onValueChange = { at -> steps[at.roundToInt().coerceIn(steps.indices)].takeIf { it != changeMs }?.let(onChange) },
            valueRange = 0f..steps.lastIndex.toFloat(),
            steps = steps.size - 2,
            modifier = Modifier.heightIn(min = 48.dp).semantics {
                contentDescription = label
                stateDescription = shown
            }
        )
    }
}

/** A row that opens a page of its own. */
@Composable
private fun PageRow(label: String, onOpen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        AppIcon(R.drawable.ic_chevron_right, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

/** The page of advanced settings. */
private const val ADVANCED_PAGE = "advanced"

/**
 * What few change: whether a link opens the exact match, and what's done when there's none; tracking
 * dropped from links; the music apps in the share sheet; and heavy visuals skipped.
 */
@Composable
private fun AdvancedPage(state: UiState, actions: ScreenActions, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Page(
        title = stringResource(R.string.settings_advanced_title),
        navigationIcon = {
            IconButton(onClick = onBack) {
                AppIcon(R.drawable.ic_arrow_back, contentDescription = stringResource(R.string.back_button))
            }
        },
        scrollState = rememberScrollState()
    ) {
        Spacer(Modifier.height(16.dp))
        Group {
            SettingSwitch(
                label = stringResource(R.string.setting_exact_match),
                description = stringResource(R.string.setting_exact_match_description),
                checked = state.exactMatch,
                onCheckedChange = actions.onExactMatchChange
            )
            // Only a song looked for can be not found; without exact matching, everything is searched.
            AnimatedVisibility(visible = state.exactMatch, enter = Motion.appear, exit = Motion.disappear) {
                Column {
                    GroupDivider()
                    NotFoundRow(state.notFoundAction, actions.onNotFoundActionChange)
                }
            }
            GroupDivider()
            SettingSwitch(
                label = stringResource(R.string.setting_clean_links),
                description = stringResource(R.string.setting_clean_links_description),
                checked = state.cleanLinks,
                onCheckedChange = actions.onCleanLinksChange
            )
            GroupDivider()
            SettingSwitch(
                label = stringResource(R.string.setting_share_sheet_apps),
                description = stringResource(R.string.setting_share_sheet_apps_description),
                checked = state.shareSheetApps,
                onCheckedChange = actions.onShareSheetAppsChange
            )
        }
        SectionHeader(stringResource(R.string.settings_visuals_title))
        Group {
            SettingSwitch(
                label = stringResource(R.string.setting_skip_heavy_visuals),
                description = stringResource(R.string.setting_skip_heavy_visuals_description),
                checked = state.visualsSettings.skipHeavy,
                onCheckedChange = actions.onSkipHeavyVisualsChange
            )
        }
    }
}

/**
 * Every visual by kind, each with a still and switched on or off; those too heavy for this phone say so,
 * and can be switched on anyway. The last one on stays on. Tapped, they play one at a time, to swipe through.
 */
@Composable
private fun VisualsPage(settings: VisualsSettings, actions: ScreenActions, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val assets = LocalContext.current.assets
    val kinds = remember(assets) { milkdropKinds(assets) }
    val names = kinds.flatMap { it.second }
    val on = names.count(settings::shows)
    var seeing by rememberSaveable { mutableStateOf<Int?>(null) }
    // Kept out here, so the list is where it was on coming back from one.
    val scroll = rememberScrollState()
    val canTurnOff = { name: String -> !(settings.shows(name) && on == 1) }
    val playing = seeing
    if (playing != null) {
        VisualsPager(kinds, playing, settings, canTurnOff, actions, onBack = { seeing = null })
        return
    }
    Page(
        title = stringResource(R.string.setting_visuals_list),
        navigationIcon = {
            IconButton(onClick = onBack) {
                AppIcon(R.drawable.ic_arrow_back, contentDescription = stringResource(R.string.back_button))
            }
        },
        scrollState = scroll
    ) {
        kinds.forEach { (kind, inKind) ->
            SectionHeader(
                stringResource(kindName(kind)),
                description = stringResource(R.string.visuals_list_summary, inKind.count(settings::shows), inKind.size)
            )
            Group {
                inKind.forEachIndexed { index, name ->
                    if (index > 0) GroupDivider()
                    VisualRow(name, settings, canTurnOff(name), actions, onSee = { seeing = names.indexOf(name) })
                }
            }
        }
    }
}

/** A visual's still, name and switch; tapped anywhere but the switch, it plays. */
@Composable
private fun VisualRow(name: String, settings: VisualsSettings, canTurnOff: Boolean, actions: ScreenActions, onSee: () -> Unit) {
    val assets = LocalContext.current.assets
    val shows = settings.shows(name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.visual_see), onClick = onSee)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CoverArt(name, load = { withContext(Dispatchers.IO) { milkdropThumb(assets, it) } }, size = 56.dp)
        Column(modifier = Modifier.weight(1f)) {
            // Some names run long: two lines keep the list quick to scan.
            Text(text = name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (name in settings.heavy) HeavyNote()
        }
        Switch(
            checked = shows,
            onCheckedChange = { actions.onVisualOnChange(name, it) },
            enabled = canTurnOff,
            modifier = Modifier.semantics { contentDescription = name }
        )
    }
}

@Composable
private fun HeavyNote() = Text(
    text = stringResource(R.string.visual_heavy),
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 2.dp)
)

/**
 * The visuals one at a time, from [start], to swipe through and pick fast: the one in view plays as it
 * would behind the words, with how many frames a second this phone draws it at, and its switch stays
 * in the same spot under it. Those beside it show their still till swiped to, so swiping stays smooth.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VisualsPager(
    kinds: List<Pair<String, List<String>>>,
    start: Int,
    settings: VisualsSettings,
    canTurnOff: (String) -> Boolean,
    actions: ScreenActions,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val names = remember(kinds) { kinds.flatMap { it.second } }
    val pager = rememberPagerState(initialPage = start) { names.size }
    val name = names[pager.currentPage]
    val kind = kinds.first { name in it.second }.first
    // Drawn even when off or too heavy, and kept on it, so it can be judged before it's picked.
    val alone = VisualsSettings(skipHeavy = false, changeMs = VisualsSettings.NEVER)
    var fps by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pager.settledPage) { fps = null }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(kindName(kind)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        AppIcon(R.drawable.ic_arrow_back, contentDescription = stringResource(R.string.back_button))
                    }
                },
                actions = {
                    Text(
                        stringResource(R.string.visuals_position, pager.currentPage + 1, names.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            Group(modifier = Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)) {
                SettingSwitch(
                    label = stringResource(R.string.visual_show),
                    description = if (name in settings.heavy) stringResource(R.string.visual_heavy) else null,
                    checked = settings.shows(name),
                    onCheckedChange = { actions.onVisualOnChange(name, it) },
                    enabled = canTurnOff(name)
                )
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = 16.dp),
            pageSpacing = 8.dp,
            key = { names[it] },
            modifier = Modifier.padding(padding).fillMaxSize()
        ) { page ->
            Box(Modifier.fillMaxSize().clip(MaterialTheme.shapes.large).background(Color.Black)) {
                VisualStill(names[page], Modifier.fillMaxSize())
                if (page == pager.settledPage) {
                    LocalVisuals.current(VisualsPlay(settings = alone, only = names[page], onFps = { fps = it }), Modifier.fillMaxSize())
                    fps?.let { FpsBadge(it, Modifier.align(Alignment.TopEnd).padding(12.dp)) }
                }
            }
        }
    }
}

/** What a kind of visual is called, by its key in assets/milkdrop-kinds.txt. */
private fun kindName(kind: String) = when (kind) {
    "dancers" -> R.string.visual_kind_dancers
    "drawing" -> R.string.visual_kind_drawing
    "fractals" -> R.string.visual_kind_fractals
    "geometric" -> R.string.visual_kind_geometric
    "hypnotic" -> R.string.visual_kind_hypnotic
    "particles" -> R.string.visual_kind_particles
    "liquid" -> R.string.visual_kind_liquid
    "sparkle" -> R.string.visual_kind_sparkle
    "supernova" -> R.string.visual_kind_supernova
    "waveforms" -> R.string.visual_kind_waveforms
    else -> R.string.visual_kind_classics
}

/** A visual's still, filling [modifier], once loaded. */
@Composable
private fun VisualStill(name: String, modifier: Modifier) {
    val assets = LocalContext.current.assets
    val still by produceState<ImageBitmap?>(null, name) { value = withContext(Dispatchers.IO) { milkdropThumb(assets, name) } }
    still?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.testTag(ARTWORK_TAG)) }
}

/** How many frames a second, red when too few to keep it, as with those skipped as too heavy. */
@Composable
private fun FpsBadge(fps: Int, modifier: Modifier) {
    val slow = fps < MilkdropRenderer.SLOW_FPS
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (slow) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
    ) {
        Text(
            stringResource(R.string.visual_fps, fps),
            style = MaterialTheme.typography.labelLarge,
            color = if (slow) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun NotFoundRow(action: NotFoundAction, onSelect: (NotFoundAction) -> Unit) = ChoiceRow(
    stringResource(R.string.setting_not_found),
    listOf(
        NotFoundAction.ASK to stringResource(R.string.not_found_ask),
        NotFoundAction.ORIGINAL to stringResource(R.string.not_found_original),
        NotFoundAction.SEARCH to stringResource(R.string.not_found_search)
    ),
    action,
    onSelect
)

/** Picks light or dark, or the phone's with "System default". */
@Composable
private fun ThemeRow(theme: ThemeMode, onSelect: (ThemeMode) -> Unit) = ChoiceRow(
    stringResource(R.string.setting_theme),
    listOf(
        ThemeMode.SYSTEM to stringResource(R.string.language_system_default),
        ThemeMode.LIGHT to stringResource(R.string.theme_light),
        ThemeMode.DARK to stringResource(R.string.theme_dark)
    ),
    theme,
    onSelect
)

/** A tick by the choice picked in a menu, so it's clear which it is; nothing by the others. */
@Composable
private fun PickedMark(picked: Boolean) {
    if (picked) AppIcon(R.drawable.ic_check, contentDescription = null, modifier = Modifier.size(20.dp))
}

/** A setting picked from a short list, with the one picked shown beside its name. */
@Composable
private fun <T> ChoiceRow(label: String, choices: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, icon: ((T) -> Int)? = null) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon?.let { AppIcon(it(selected), contentDescription = null, modifier = Modifier.padding(end = 6.dp).size(18.dp)) }
                Text(
                    text = choices.first { it.first == selected }.second,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                AppIcon(R.drawable.ic_expand_more, contentDescription = null, modifier = Modifier.padding(start = 2.dp).size(16.dp).flipWhen(expanded))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { (choice, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        leadingIcon = icon?.let { { AppIcon(it(choice), contentDescription = null, modifier = Modifier.size(20.dp)) } },
                        trailingIcon = { PickedMark(choice == selected) },
                        modifier = Modifier.semantics { this.selected = choice == selected },
                        onClick = {
                            expanded = false
                            onSelect(choice)
                        }
                    )
                }
            }
        }
    }
}

/**
 * The app's color, picked from swatches in the look it has now, dark or light. The wallpaper's is
 * offered only where Android shares it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaletteRow(selected: Palette, onSelect: (Palette) -> Unit) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
        // The picked one by name, as the other rows say theirs, since a swatch alone doesn't say it.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = stringResource(R.string.setting_color), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(text = stringResource(selected.labelRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        // All in sight, spread across the row, onto another only on a screen too narrow for them.
        FlowRow(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .selectableGroup()
        ) {
            Palette.entries.filter { it != Palette.WALLPAPER || wallpaperColors }.forEach { palette ->
                val wallpaper = if (palette == Palette.WALLPAPER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).primary
                } else {
                    null
                }
                val accent = if (dark) palette.dark else palette.light
                Swatch(wallpaper ?: accent.primary, accent.onPrimary, stringResource(palette.labelRes), palette == selected) { onSelect(palette) }
            }
        }
    }
}

/** One color to pick: a round of it, ringed and ticked when it's the one picked. */
@Composable
private fun Swatch(color: Color, onColor: Color, name: String, isSelected: Boolean, onClick: () -> Unit) {
    // The ring fades rather than thins: a border of no width is still drawn, as a hairline.
    val ring by animateFloatAsState(if (isSelected) 1f else 0f, label = "swatch ring")
    Box(
        contentAlignment = Alignment.Center,
        // Its whole round takes a touch, as big as Android asks of one.
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name }
            .border(2.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = ring), CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(color)
    ) {
        AnimatedVisibility(visible = isSelected, enter = Motion.pop, exit = fadeOut(Motion.fadeOut)) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = onColor, modifier = Modifier.size(18.dp))
        }
    }
}

private val Palette.labelRes: Int
    get() = when (this) {
        Palette.VIOLET -> R.string.palette_violet
        Palette.WALLPAPER -> R.string.palette_wallpaper
        Palette.OCEAN -> R.string.palette_ocean
        Palette.FOREST -> R.string.palette_forest
        Palette.SUNSET -> R.string.palette_sunset
        Palette.ROSE -> R.string.palette_rose
    }

/** Picks the language lyrics are translated into, or the app's own. */
@Composable
private fun TranslateIntoRow(into: String?, onSelect: (String?) -> Unit) {
    val appLanguage = stringResource(R.string.translate_into_app_language)
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.setting_translate_into), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LanguageMark(into, modifier = Modifier.padding(end = 6.dp))
                Text(
                    text = into?.let(AppLanguage::displayName) ?: appLanguage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                AppIcon(R.drawable.ic_expand_more, contentDescription = null, modifier = Modifier.padding(start = 2.dp).size(16.dp).flipWhen(expanded))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                (listOf(null) + AppLanguage.translationTags).forEach { tag ->
                    DropdownMenuItem(
                        text = { Text(tag?.let(AppLanguage::displayName) ?: appLanguage) },
                        leadingIcon = { LanguageMark(tag) },
                        onClick = {
                            expanded = false
                            onSelect(tag)
                        }
                    )
                }
            }
        }
    }
}

/** Picks the languages the user reads, whose songs aren't translated: as many as they like, so the list stays open. */
@Composable
private fun UntranslatedRow(untranslated: Set<String>, onChange: (Set<String>) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.setting_untranslated), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = AppLanguage.untranslatedTags.filter { it in untranslated }.joinToString { AppLanguage.displayName(it) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp)
                )
                AppIcon(R.drawable.ic_expand_more, contentDescription = null, modifier = Modifier.padding(start = 2.dp).size(16.dp).flipWhen(expanded))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                AppLanguage.untranslatedTags.forEach { tag ->
                    val picked = tag in untranslated
                    DropdownMenuItem(
                        text = { Text(AppLanguage.displayName(tag)) },
                        leadingIcon = { LanguageMark(tag) },
                        trailingIcon = { PickedMark(picked) },
                        modifier = Modifier.semantics { selected = picked },
                        onClick = { onChange(if (picked) untranslated - tag else untranslated + tag) }
                    )
                }
            }
        }
    }
}

/** Picks the app's language, or the phone's with "System default". */
@Composable
private fun LanguageRow(onSelect: (String?) -> Unit) {
    val context = LocalContext.current
    val current = remember { AppLanguage.current(context) }
    val systemDefault = stringResource(R.string.language_system_default)
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.setting_language),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LanguageMark(current, modifier = Modifier.padding(end = 6.dp))
                Text(
                    text = current?.let(AppLanguage::displayName) ?: systemDefault,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                AppIcon(R.drawable.ic_expand_more, contentDescription = null, modifier = Modifier.padding(start = 2.dp).size(16.dp).flipWhen(expanded))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                (listOf(null) + AppLanguage.tags).forEach { tag ->
                    DropdownMenuItem(
                        text = { Text(tag?.let(AppLanguage::displayName) ?: systemDefault) },
                        leadingIcon = { LanguageMark(tag) },
                        trailingIcon = { PickedMark(tag == current) },
                        modifier = Modifier.semantics { selected = tag == current },
                        onClick = {
                            expanded = false
                            onSelect(tag)
                        }
                    )
                }
            }
        }
    }
}

/** The installed version, e.g. "1.2.1", or "1.2.1-dev.5" for a build made after a release. */
@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
}

/** Everything Settings offers for one source of links: a service, or a web frontend's sites. */
private class SourceSettings(
    val key: String,
    /** Whose icon it shows. */
    val icon: Destination,
    val label: String,
    val on: Boolean,
    /** The app the user listens in, which can't be turned on while its links would just go back to it. */
    val locked: Boolean,
    val listeningNote: String?,
    val notAllowed: Boolean,
    val rule: Destination?,
    /** Where its links go without a rule of their own. */
    val fallback: Destination,
    val onToggle: (Boolean) -> Unit,
    val onRule: (Destination?) -> Unit,
    /** YouTube and its frontends, which are mostly videos that aren't music. */
    val video: Boolean = false,
    /** For web frontends, the site they open videos on when picked as where to open things. */
    val site: Destination.Alternative? = null,
    val onSite: (String) -> Boolean = { false }
)

@Composable
private fun sourceSettings(state: UiState, actions: ScreenActions): List<SourceSettings> {
    val services = MusicService.entries.filter { it.canBeSource }.map { source ->
        val intercepted = source in state.intercepted
        val rule = state.rules[source]
        val listeningHere = source == state.listeningService() && rule == null
        val label = stringResource(source.labelRes)
        SourceSettings(
            key = source.name,
            icon = Destination.Service(source),
            label = label,
            on = intercepted,
            // Already on from before the rule existed: it can still be turned off.
            locked = listeningHere && !intercepted,
            // Off and locked, the note also says how to turn it on.
            listeningNote = if (listeningHere) {
                stringResource(if (intercepted) R.string.setup_sources_listening_note else R.string.settings_listening_locked, label)
            } else {
                null
            },
            notAllowed = intercepted && state.unapprovedHosts?.get(source).orEmpty().isNotEmpty(),
            rule = rule,
            fallback = state.defaultDestination,
            onToggle = { actions.onInterceptChange(source, it) },
            onRule = { actions.onRuleChange(source, it) },
            video = source == MusicService.YOUTUBE
        )
    }
    val frontends = Frontend.SOURCES.map { frontend ->
        val on = frontend in state.frontendSources
        SourceSettings(
            key = Destination.FRONTEND_PREFIX + frontend.name,
            icon = Destination.Alternative(frontend),
            label = frontend.label,
            on = on,
            locked = false,
            listeningNote = null,
            notAllowed = on && state.unapprovedFrontendHosts?.get(frontend).orEmpty().isNotEmpty(),
            rule = state.frontendRules[frontend],
            // Without a rule of its own, its videos go where YouTube's do.
            fallback = state.rules[MusicService.YOUTUBE] ?: state.defaultDestination,
            onToggle = { actions.onFrontendInterceptChange(frontend, it) },
            onRule = { actions.onFrontendRuleChange(frontend, it) },
            video = true,
            site = state.destinations.filterIsInstance<Destination.Alternative>().firstOrNull { it.frontend == frontend },
            onSite = { actions.onFrontendInstanceChange(frontend, it) }
        )
    }
    return services + frontends
}

/**
 * One source in the list: its name, what needs attention or where its links go, and its switch.
 * Tapping the name opens the rest of its options.
 */
@Composable
private fun SourceSummaryRow(item: SourceSettings, installed: Set<MusicService>, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(end = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpen)
                .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DestinationIcon(item.icon, installed)
            Column(modifier = Modifier.weight(1f, fill = false).padding(start = 16.dp)) {
            Text(item.label, style = MaterialTheme.typography.bodyLarge)
            if (item.on && item.rule != null && !item.notAllowed && !item.locked) {
                // Only when it differs from the default, so the list stays quiet.
                Text(
                    stringResource(R.string.rule_opens_in, item.rule.label()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            }
            // Marks, not words, so a list of them stays quiet: the notice above says what to do.
            if (item.notAllowed) {
                AppIcon(
                    R.drawable.ic_error, contentDescription = stringResource(R.string.setup_not_allowed),
                    tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 8.dp).size(18.dp)
                )
            } else if (item.locked) {
                // The switch is off and can't be turned on; the reason is on the source's page.
                AppIcon(
                    R.drawable.ic_lock, contentDescription = stringResource(R.string.settings_where_you_listen),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp).size(18.dp)
                )
            }
        }
        Switch(
            checked = item.on,
            onCheckedChange = item.onToggle,
            enabled = !item.locked,
            modifier = Modifier.semantics { contentDescription = item.label }
        )
    }
}

/**
 * One source's own page: whether Crosstune opens its links, whether Android lets it, where they go,
 * for web frontends their site, and the apps that can open the same links.
 */
@Composable
private fun SourcePage(
    item: SourceSettings,
    destinations: List<Destination>,
    installed: Set<MusicService>,
    claimingApps: Map<String, Map<LinkApp, Boolean>>,
    onlyMusicVideos: Boolean,
    actions: ScreenActions,
    fixingLinks: Boolean,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    Page(
        title = item.label,
        navigationIcon = {
            IconButton(onClick = onBack) {
                AppIcon(R.drawable.ic_arrow_back, contentDescription = stringResource(R.string.back_button))
            }
        }
    ) {
        Group(modifier = Modifier.padding(top = 8.dp)) {
            SettingSwitch(
                label = stringResource(R.string.source_open_links),
                description = item.listeningNote,
                checked = item.on,
                onCheckedChange = item.onToggle,
                enabled = !item.locked
            )
            if (item.notAllowed) NotAllowedRow(fixingLinks, actions)
            if (item.video) {
                GroupDivider()
                // Shared by YouTube, Invidious and Piped.
                SettingSwitch(
                    label = stringResource(R.string.setting_only_music_videos),
                    description = stringResource(R.string.setting_only_music_videos_description),
                    checked = onlyMusicVideos,
                    onCheckedChange = actions.onOnlyMusicVideosChange
                )
            }
            GroupDivider()
            RuleMenu(item.rule, item.fallback, destinations, installed, item.onRule)
            item.site?.let { site ->
                GroupDivider()
                FrontendSiteRow(site, item.onSite)
            }
        }
        // Kept here even once they let Crosstune open the links, so the choice can be undone.
        // Locked, the app the user listens in keeping its links is just what should happen.
        val owners = if (item.locked) emptyMap() else claimingApps[item.key].orEmpty()
        if (owners.isNotEmpty()) {
            SectionHeader(
                title = stringResource(R.string.settings_link_owners_title),
                description = stringResource(R.string.settings_link_owners_description)
            )
            Group {
                owners.entries.forEachIndexed { index, (app, opensThem) ->
                    if (index > 0) GroupDivider()
                    LinkOwnerRow(app, opensThem = opensThem, onClick = { actions.onOpenAppLinkSettings(app) })
                }
            }
        }
    }
}

@Composable
private fun NotAllowedRow(fixing: Boolean, actions: ScreenActions) {
    Row(modifier = Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.setup_not_allowed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f)
        )
        OneTapButton(stringResource(R.string.allow_button), actions.onShowAllowGuide, fixing = fixing)
    }
}

@Composable
private fun RuleMenu(rule: Destination?, fallback: Destination, destinations: List<Destination>, installed: Set<MusicService>, onChange: (Destination?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val defaultLabel = stringResource(R.string.rule_default, fallback.label())
    Box(modifier = Modifier.padding(start = 8.dp)) {
        TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text(
                stringResource(R.string.rule_opens_in, rule?.label() ?: defaultLabel),
                style = MaterialTheme.typography.bodyMedium
            )
            AppIcon(
                R.drawable.ic_expand_more,
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(16.dp)
                    .flipWhen(expanded)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(defaultLabel) },
                onClick = {
                    expanded = false
                    onChange(null)
                }
            )
            destinations.forEach { destination ->
                DropdownMenuItem(
                    text = { Text(destination.label()) },
                    leadingIcon = { DestinationIcon(destination, installed) },
                    onClick = {
                        expanded = false
                        onChange(destination)
                    }
                )
            }
        }
    }
}

@Composable
private fun AddCustomDestinationForm(onAdd: (String, String) -> Boolean, onCancel: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var template by rememberSaveable { mutableStateOf("https://") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; invalid = false },
            label = { Text(stringResource(R.string.custom_name_label)) },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = template,
            onValueChange = { template = it; invalid = false },
            label = { Text(stringResource(R.string.custom_template_label)) },
            supportingText = {
                Text(stringResource(if (invalid) R.string.custom_template_invalid else R.string.custom_template_hint))
            },
            isError = invalid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
        )
        Row(modifier = Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel_button)) }
            TextButton(onClick = { invalid = !onAdd(name, template) }) { Text(stringResource(R.string.add_button)) }
        }
    }
}

@Preview(showBackground = true)
@Composable
internal fun SettingsScreenPreview() {
    CrosstuneTheme {
        SettingsScreen(
            state = UiState(
                intercepted = setOf(MusicService.SPOTIFY, MusicService.YOUTUBE),
                rules = mapOf(MusicService.YOUTUBE to Destination.Service(MusicService.YOUTUBE_MUSIC)),
                destinations = MusicService.entries.map(Destination::Service) +
                    Destination.Custom("1", "Invidious", "https://yewtu.be/search?q={query}")
            ),
            actions = ScreenActions(),
            onBack = {}
        )
    }
}
