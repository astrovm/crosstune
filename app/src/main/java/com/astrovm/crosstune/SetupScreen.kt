package com.astrovm.crosstune

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.astrovm.crosstune.ui.theme.CrosstuneTheme
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp

private enum class SetupStep { DESTINATION, SOURCES, APPS, ALLOW }

/**
 * First-run setup: where the user listens, which other services' links to open, stopping the
 * services' own installed apps from taking them, and allowing the links in Android. Stopping the
 * apps comes first because Android won't let Crosstune take links another app has verified.
 * Choices apply as they're made, so leaving halfway keeps them; setup shows again until finished.
 */
@Composable
internal fun SetupScreen(state: UiState, actions: ScreenActions) {
    // 0 is the welcome page; after that, a position in [steps].
    var position by rememberSaveable { mutableIntStateOf(0) }
    val appsToFix = appsToStop(state)
    // Every step is counted, so the total doesn't change as sources are picked, but stopping the
    // apps is skipped when no installed app is in the way.
    val steps = SetupStep.entries
    val skipped = { position: Int -> steps.getOrNull(position - 1) == SetupStep.APPS && appsToFix.isEmpty() }
    val current = position
    val step = if (current == 0) null else steps[current - 1]
    // Which steps are skipped, and what they show, depends on what Android says about other apps
    // and links, so a step restored before that's known waits for it.
    val ready = step == null || state.systemStateKnown
    val back = { if (ready) position = (current - 1).let { if (skipped(it)) it - 1 else it } }
    // System back steps back like the Back button instead of leaving setup.
    BackHandler(enabled = current > 0, onBack = back)

    // A step that was scrolled down mustn't leave the next one scrolled too.
    val scroll = key(position) { rememberScrollState() }
    Page(
        scrollState = scroll,
        title = null,
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
            ) {
                val buttonModifier = Modifier
                    .weight(1f)
                    .widthIn(max = 290.dp)
                    .heightIn(min = 52.dp)
                if (step != null) {
                    OutlinedButton(onClick = back, enabled = ready, modifier = buttonModifier) {
                        Text(stringResource(R.string.back_button), style = MaterialTheme.typography.labelLarge)
                    }
                }
                val isLast = current == steps.size
                val onNext = {
                    if (isLast) actions.onCompleteSetup() else position = (current + 1).let { if (skipped(it)) it + 1 else it }
                }
                // There's no built-in default any more, so one has to be picked.
                val enabled = ready && (step != SetupStep.DESTINATION || state.hasDefault)
                // Leaving with links still to allow is skipping them, so the button says so and
                // the guide's own button stays the main one.
                if (step == SetupStep.ALLOW && state.someLinksNotAllowed) {
                    FilledTonalButton(onClick = onNext, enabled = enabled, modifier = buttonModifier) {
                        Text(stringResource(R.string.setup_skip_for_now), style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    Button(onClick = onNext, enabled = enabled, modifier = buttonModifier) {
                        Text(
                            stringResource(
                                when {
                                    step == null -> R.string.setup_get_started
                                    isLast -> R.string.setup_finish
                                    else -> R.string.next_button
                                }
                            ),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    ) {
        AnimatedContent(targetState = step, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "setup") { shown ->
            Column {
                if (shown == null) return@Column Welcome()
                StepProgress(steps.indexOf(shown) + 1, steps.size)
                if (!state.systemStateKnown) return@Column
                when (shown) {
                    SetupStep.DESTINATION -> DestinationStep(state, actions)
                    SetupStep.SOURCES -> SourcesStep(state, actions)
                    SetupStep.APPS -> AppsStep(appsToFix, state, actions)
                    SetupStep.ALLOW -> AllowStep(state, actions)
                }
            }
        }
    }
}

@Composable
private fun StepProgress(step: Int, lastStep: Int) {
    Column(modifier = Modifier.padding(top = 24.dp)) {
        Text(
            text = stringResource(R.string.setup_step, step, lastStep),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        LinearProgressIndicator(progress = { step / lastStep.toFloat() }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun StepHeader(title: Int, body: Int) = StepHeader(title, stringResource(body))

@Composable
private fun StepHeader(title: Int, body: String?) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(top = 28.dp, bottom = if (body == null) 24.dp else 0.dp)
    )
    body?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )
    }
}

@Composable
private fun Welcome() {
    Column(modifier = Modifier.padding(top = 72.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(88.dp)) {
            Box(contentAlignment = Alignment.Center) {
                AppLogo(size = 48.dp)
            }
        }
        Spacer(Modifier.height(12.dp))
        StepHeader(R.string.setup_welcome_title, R.string.setup_welcome_body)
    }
}

/** A small rounded label, e.g. "Installed" or "Allowed". */
@Composable
private fun Tag(text: String, container: Color, content: Color) {
    Surface(shape = MaterialTheme.shapes.small, color = container, contentColor = content) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun InstalledTag(service: MusicService?, installed: Set<MusicService>) {
    if (service != null && service in installed) {
        Tag(
            stringResource(R.string.setup_installed),
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/** Installed apps first, keeping the usual order otherwise. */
private fun <T> List<T>.installedFirst(installed: Set<MusicService>, service: (T) -> MusicService?) =
    sortedByDescending { service(it) in installed }

@Composable
private fun ChoiceRow(
    modifier: Modifier,
    control: @Composable () -> Unit,
    label: String,
    tag: @Composable () -> Unit = {},
    icon: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        control()
        icon?.let {
            Spacer(Modifier.size(12.dp))
            it()
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        )
        tag()
    }
}

/** The service the user listens in, whose links already open where they should. */
internal fun UiState.listeningService(): MusicService? =
    if (hasDefault) (defaultDestination as? Destination.Service)?.service else null

/**
 * The services the user gets links from. The one they listen in isn't offered: its links already
 * open there, and stopping its app from taking them would only get in the way.
 */
@Composable
private fun SourcesStep(state: UiState, actions: ScreenActions) {
    // Music services start ticked; it's what most people want.
    LaunchedEffect(Unit) { actions.onPreselectSources() }
    StepHeader(R.string.setup_sources_title, null)
    val listening = state.listeningService()
    SetupGroupTitle(R.string.setup_sources_music)
    Group {
        // Links usually come from the services the user doesn't use, so installed apps aren't put first.
        MusicService.entries.filter { it.canBeSource && it != MusicService.YOUTUBE && it != listening }
            .forEachIndexed { index, source ->
                if (index > 0) GroupDivider()
                SourceChoice(stringResource(source.labelRes), Destination.Service(source), state.installed, source in state.intercepted) {
                    actions.onInterceptChange(source, it)
                }
            }
    }
    if (listening != null && listening.canBeSource) {
        Text(
            text = stringResource(R.string.setup_sources_listening_note, stringResource(listening.labelRes)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp)
        )
    }
    // Most videos aren't music, so these start off; YouTube's own frontends are sources of their own.
    SetupGroupTitle(R.string.setup_sources_videos)
    Group {
        if (listening != MusicService.YOUTUBE) {
            SourceChoice(
                stringResource(R.string.target_youtube), Destination.Service(MusicService.YOUTUBE), state.installed,
                MusicService.YOUTUBE in state.intercepted
            ) {
                actions.onInterceptChange(MusicService.YOUTUBE, it)
            }
            GroupDivider()
        }
        Frontend.SOURCES.forEachIndexed { index, frontend ->
            if (index > 0) GroupDivider()
            SourceChoice(frontend.label, Destination.Alternative(frontend), state.installed, frontend in state.frontendSources) {
                actions.onFrontendInterceptChange(frontend, it)
            }
        }
    }
    Text(
        text = stringResource(R.string.setting_only_music_videos_note),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp)
    )
}

@Composable
private fun SetupGroupTitle(title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
    )
}

@Composable
private fun SourceChoice(label: String, icon: Destination, installed: Set<MusicService>, checked: Boolean, onChange: (Boolean) -> Unit) {
    ChoiceRow(
        modifier = Modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        control = { Checkbox(checked = checked, onCheckedChange = null) },
        label = label,
        icon = { DestinationIcon(icon, installed) }
    )
}

@Composable
private fun DestinationStep(state: UiState, actions: ScreenActions) {
    // With just one music app installed, that's most likely where the user listens. YouTube
    // doesn't count: it comes with most phones and is mostly videos.
    LaunchedEffect(state.installed) {
        if (state.hasDefault) return@LaunchedEffect
        val only = (state.installed - MusicService.YOUTUBE).singleOrNull()?.let(Destination::Service)
        if (only != null && only in state.destinations) actions.onTargetChange(only)
    }
    StepHeader(R.string.setup_destination_title, R.string.setup_destination_body)
    Group {
        state.destinations.installedFirst(state.installed) { (it as? Destination.Service)?.service }
            .forEachIndexed { index, destination ->
                if (index > 0) GroupDivider()
                val selected = state.hasDefault && destination == state.defaultDestination
                ChoiceRow(
                    // Picking a service also stops Crosstune opening its links (see MainViewModel.selectDefault).
                    modifier = Modifier.selectable(selected = selected, role = Role.RadioButton) { actions.onTargetChange(destination) },
                    control = { RadioButton(selected = selected, onClick = null) },
                    label = destination.label(),
                    tag = { InstalledTag((destination as? Destination.Service)?.service, state.installed) },
                    icon = { DestinationIcon(destination, state.installed) }
                )
            }
    }
}

/** A numbered instruction, short enough to read at a glance. */
@Composable
private fun NumberedStep(number: Int, text: String) = NumberedStep(number) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
}

/** A step's number beside what to do, a line of text or, when that's just a button, the button itself. */
@Composable
private fun NumberedStep(number: Int, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(28.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        content()
    }
}

@Composable
private fun StatusTag(done: Boolean, doneText: String, todoText: String) {
    Tag(
        if (done) doneText else todoText,
        if (done) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        if (done) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    )
}

/**
 * One source and the links of it still to tick, since Android lists every source's links together:
 * those Android says aren't allowed yet from Android 12, which can tell, or all of them before.
 */
@Composable
private fun AllowRow(label: String, hosts: List<String>) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = hosts.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun AllowStep(state: UiState, actions: ScreenActions) {
    if (!state.interceptsAnything) {
        StepHeader(R.string.setup_allow_title, R.string.setup_allow_none)
        return
    }
    Text(
        text = stringResource(R.string.setup_allow_title),
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(top = 28.dp, bottom = 16.dp)
    )
    AllowLinksGuide(state, actions)
}

/**
 * A real screenshot of the Android screen to use, with what to tap circled, and a line under it:
 * by default that phones vary, or what to do when that's shorter said there.
 */
@Composable
internal fun GuideShot(@DrawableRes image: Int, modifier: Modifier = Modifier, caption: String? = stringResource(R.string.guide_may_differ)) {
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // The steps around it say the same in words, for TalkBack.
        Image(
            painterResource(image),
            contentDescription = null,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
        )
        caption?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/**
 * How to allow the links in Android, with a screenshot for each step and, from Android 12, how
 * many are allowed so far and which, per source. Used in setup and from the notices.
 */
@Composable
internal fun AllowLinksGuide(state: UiState, actions: ScreenActions) {
    // Once Android says every link is allowed, that's all there is to show.
    if (state.unapprovedHosts != null && !state.someLinksNotAllowed) {
        Text(stringResource(R.string.setup_allow_all_done), style = MaterialTheme.typography.titleMedium)
        return
    }
    // The button says what to do, so step 1 is the button itself.
    NumberedStep(1) {
        Button(
            onClick = actions.onOpenLinkSettings,
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp)
                .heightIn(min = 52.dp)
        ) {
            AppIcon(R.drawable.ic_open_in_new, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.open_link_settings_button), style = MaterialTheme.typography.labelLarge)
        }
    }
    Spacer(Modifier.height(10.dp))
    // Set to open in the browser, Android hides the links to tick, so switching back comes first.
    if (state.ownLinksOff) {
        NumberedStep(2, stringResource(R.string.setup_allow_step_in_app))
        return
    }
    NumberedStep(2, stringResource(R.string.setup_allow_step_add))
    GuideShot(R.drawable.guide_add_link, Modifier.padding(vertical = 12.dp))
    NumberedStep(3, stringResource(R.string.setup_allow_step_tick))
    // The first screenshot already says phones vary.
    GuideShot(R.drawable.guide_tick_links, Modifier.padding(vertical = 12.dp), caption = null)
    // Only what's left to allow.
    val toAllow = MusicService.entries.filter { it in state.intercepted }.map { source ->
        stringResource(source.labelRes) to (state.unapprovedHosts?.let { it[source].orEmpty() } ?: LinkInterception.HOSTS[source].orEmpty())
    } + Frontend.SOURCES.filter { it in state.frontendSources }.map { frontend ->
        frontend.label to (state.unapprovedFrontendHosts?.let { it[frontend].orEmpty() } ?: frontend.sites)
    }
    val rows = toAllow.filter { (_, hosts) -> hosts.isNotEmpty() }
    if (rows.isNotEmpty()) {
        Group {
            rows.forEachIndexed { index, (label, hosts) ->
                if (index > 0) GroupDivider()
                AllowRow(label, hosts)
            }
        }
    }
}

@Composable
private fun AppsStep(apps: List<LinkApp>, state: UiState, actions: ScreenActions) {
    StepHeader(R.string.setup_apps_title, null)
    StopAppsGuide(apps, state.blockingApps, actions.onOpenAppLinkSettings)
}

/** Whether a guide is finished, so its button can say Done rather than Skip for now. */
internal fun appsGuideDone(apps: List<LinkApp>, blocking: Set<LinkApp>?): Boolean =
    blocking == null || apps.none { it in blocking }

/** An installed app's own icon, or nothing if it's just been uninstalled. */
@Composable
private fun PackageIcon(packageName: String, size: Dp = 32.dp) {
    val context = LocalContext.current
    val icon = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(size).clip(MaterialTheme.shapes.small)) }
}

/**
 * Installed apps that keep the links, one at a time from Android 12, which tells when each is
 * done, with a screenshot of what to choose; with more than one, every app stays listed below,
 * marked once fixed, so the list doesn't shrink under the user's finger. Before Android 12 all are listed,
 * each with its own button. Used in setup and from the notices.
 */
@Composable
internal fun StopAppsGuide(apps: List<LinkApp>, blocking: Set<LinkApp>?, onOpen: (LinkApp) -> Unit) {
    val current = blocking?.let { apps.firstOrNull { it in blocking } }
    if (blocking == null) {
        GuideShot(R.drawable.guide_stop_app, Modifier.padding(bottom = 16.dp), caption = stringResource(R.string.setup_apps_stop_body))
    } else if (current == null) {
        Text(stringResource(R.string.setup_apps_all_done), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 16.dp))
    } else {
        Group(modifier = Modifier.padding(bottom = 16.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {
                if (apps.size > 1) {
                    Text(
                        text = stringResource(R.string.setup_apps_progress, apps.indexOf(current) + 1, apps.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PackageIcon(current.packageName)
                    Text(
                        text = current.label,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp)
                    )
                    // With one app there's no list below, so its status goes here.
                    if (apps.size == 1) {
                        StatusTag(false, stringResource(R.string.setup_fixed), stringResource(R.string.setup_still_opens))
                    }
                }
                // What to pick goes under the screenshot, which shows it, instead of a paragraph above.
                GuideShot(R.drawable.guide_stop_app, Modifier.padding(vertical = 16.dp), caption = stringResource(R.string.setup_apps_stop_body))
                Button(
                    onClick = { onOpen(current) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                ) {
                    Text(stringResource(R.string.open_app_link_settings_button, current.label), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
    // A list of one would only repeat the card above, or the all-set line once it's fixed.
    if (blocking != null && apps.size <= 1) return
    Group {
        apps.forEachIndexed { index, app ->
            if (index > 0) GroupDivider()
            Column(modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = app.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    blocking?.let { StatusTag(app !in it, stringResource(R.string.setup_fixed), stringResource(R.string.setup_still_opens)) }
                }
                // Before Android 12 there's no way to tell which still keep the links, so each gets a button.
                if (blocking == null) {
                    TextButton(onClick = { onOpen(app) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text(stringResource(R.string.open_app_link_settings_button, app.label))
                    }
                }
            }
        }
    }
}

/**
 * Installed apps setup should ask the user to stop: on Android 12+ every one seen taking the
 * links that still claims some Crosstune opens (kept once seen, so fixing one doesn't remove its
 * row, but dropped once the user stops opening its links); before that, every installed app whose
 * links Crosstune opens, since Android can't say which really do.
 */
@Composable
internal fun appsToStop(state: UiState): List<LinkApp> {
    var seen by rememberSaveable { mutableStateOf("") }
    val blocking = state.blockingApps ?: return state.installedSourceApps
    val claiming = state.claimingApps.orEmpty()
    val known = seen.split(',').filter { it.isNotEmpty() }
    val all = (known + blocking.map { it.packageName }).distinct()
    SideEffect { if (all.size != known.size) seen = all.joinToString(",") }
    return claiming.filter { it.packageName in all }
}

@Preview(showBackground = true)
@Composable
internal fun SetupScreenPreview() {
    CrosstuneTheme(dynamicColor = false) {
        SetupScreen(
            state = UiState(
                setupComplete = false,
                intercepted = setOf(MusicService.SPOTIFY),
                installed = setOf(MusicService.YOUTUBE_MUSIC)
            ),
            actions = ScreenActions()
        )
    }
}
