package com.astrovm.crosstune

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

internal const val FIX_BAR_TAG = "fix_bar"

/** How long a thing just fixed shows checked off before the next one. */
internal const val FIX_DONE_PAUSE_MS = 700L

/** How long the floating guide shows each step before the next. */
internal const val GUIDE_STEP_MS = 4_000L

/** What a test link carries, so Crosstune knows it's the one it sent rather than a song to look up. */
internal const val TEST_LINK = "crosstune-test"

/** The kind of phone, whose settings pages the steps' pictures show; for others the steps are in words. */
internal enum class Phone {
    PIXEL, SAMSUNG, XIAOMI, OTHER,
    /** A TV, run with a remote, whose settings have no page for opening links. */
    TV;

    companion object {
        /** From who Android says made the phone, e.g. "samsung", and whose brand it is, e.g. "POCO", or that it's a [tv]. */
        fun of(manufacturer: String, brand: String, tv: Boolean = false): Phone {
            val names = setOf(manufacturer.lowercase(), brand.lowercase())
            return when {
                tv -> TV
                "samsung" in names -> SAMSUNG
                names.any { it in XIAOMI_BRANDS } -> XIAOMI
                "google" in names -> PIXEL
                else -> OTHER
            }
        }

        private val XIAOMI_BRANDS = setOf("xiaomi", "redmi", "poco")
    }
}

/** Whether Crosstune may use the microphone, or Android still asks, or it's turned down for good and allowed in settings. */
internal enum class MicAccess { ALLOWED, ASKABLE, DENIED }

/** Something Android has to allow for part of Crosstune to work, which the user does in Android's settings. */
internal sealed interface Need {
    val key: String

    /** Another app that still opens the links Crosstune should. */
    data class StopApp(val app: LinkApp) : Need {
        override val key get() = "app:${app.packageName}"
    }

    /** Crosstune's own links, which Android has to let it open. */
    data object Links : Need {
        override val key get() = "links"
    }

    /** The microphone, which hears the song the lyrics are for. */
    data object Microphone : Need {
        override val key get() = "mic"
    }

    /** Seeing what music apps play, which lyrics follow. */
    data object Follow : Need {
        override val key get() = "follow"
    }

    /** Showing over other apps, which floating lyrics need; asked when they first float, so it isn't listed. */
    data object Float : Need {
        override val key get() = "float"
    }
}

/** A [need] and whether it's [done]: null when Android can't tell. */
internal data class Check(val need: Need, val done: Boolean?)

/** What's being fixed: the [list] of it all, and the [steps] taken one at a time, [at] the one shown, once one tap [failed]. */
internal data class Fixes(val list: Boolean = false, val steps: List<Need> = emptyList(), val at: Int = 0, val failed: Boolean = false) {
    val need: Need? get() = steps.getOrNull(at)
}

/** The apps still opening the links from Android 12, which can tell; before, every source's own app, which may. */
internal val UiState.appsInTheWay: List<LinkApp> get() = blockingApps?.sortedBy { it.label.lowercase() } ?: installedSourceApps

/**
 * Everything Android has to allow, in the order it's done: the apps in the way first, since
 * Android won't let Crosstune take links another app verified, then the links and following music
 * apps. [apps] can include ones fixed already, so a list doesn't shrink under a finger. The
 * microphone isn't one: it's asked where it's used, naming a song or opening lyrics.
 */
internal fun UiState.checks(apps: List<LinkApp> = appsInTheWay): List<Check> = buildList {
    if (interceptsAnything) {
        apps.forEach { add(Check(Need.StopApp(it), done(Need.StopApp(it)))) }
        add(Check(Need.Links, linksAllowed))
    }
    add(Check(Need.Follow, canFollowApps))
}

/** How many things are known to need fixing; none until Android has been asked. */
internal val UiState.toFix: Int get() = if (systemStateKnown) checks().count { it.done == false } else 0

/**
 * Whether Crosstune's links are allowed. From Android 12, as Android says; before, which can't
 * tell, once a test link reached Crosstune, or not yet right after a source was turned on.
 */
internal val UiState.linksAllowed: Boolean?
    get() = when {
        unapprovedHosts != null -> !someLinksNotAllowed
        Need.Links.key in linksTested -> true
        showLinkSettingsHelper -> false
        else -> null
    }

/** Whether [need] is done; null when Android can't tell, as for another app's links before Android 12, until a test link says. */
internal fun UiState.done(need: Need): Boolean? = when (need) {
    is Need.StopApp -> blockingApps?.let { need.app !in it } ?: true.takeIf { need.key in linksTested }
    Need.Links -> linksAllowed
    Need.Microphone -> microphone == MicAccess.ALLOWED
    Need.Follow -> canFollowApps
    Need.Float -> canFloatOverApps
}

/** A link of [service]'s, which opens in whatever app takes its links, Crosstune or another, [TEST_LINK] telling it apart. */
internal fun testLink(service: MusicService): String = "https://${testHost(service)}/#$TEST_LINK"

/** One of [service]'s sites that Crosstune opens. */
internal fun testHost(service: MusicService): String = LinkInterception.HOSTS[service].orEmpty().first()

/** The service a test link for [need] is of: the app's own, or the first whose links Crosstune opens. */
internal fun UiState.testService(need: Need): MusicService? =
    (need as? Need.StopApp)?.let { MusicService.ofPackage(it.app.packageName) }?.takeIf { it in intercepted }
        ?: MusicService.entries.firstOrNull { it in intercepted }

/** Before Android 12, which can't tell, a test link says whether links reach Crosstune. */
private fun UiState.tests(need: Need): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S && (need is Need.StopApp || need == Need.Links) && testService(need) != null

/** At the bottom of the main screen while something is known to be broken: how many, and a tap to fix them. It can't be closed. */
@Composable
internal fun FixBar(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .testTag(FIX_BAR_TAG)
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 56.dp)
                    .padding(start = 16.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Badge(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) {
                    Text("$count", style = MaterialTheme.typography.labelMedium)
                }
                Text(stringResource(R.string.fix_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                AppIcon(R.drawable.ic_chevron_right, contentDescription = null)
            }
        }
    }
}

/** Everything Android has to allow, each marked, with one way to do it all: in one tap with Shizuku or root, or one by one. */
@Composable
internal fun FixesPage(state: UiState, actions: ScreenActions) {
    SwipedBack(onBack = actions.onCloseFixes)
    Page(
        title = stringResource(R.string.fix_title),
        navigationIcon = {
            IconButton(onClick = actions.onCloseFixes) {
                AppIcon(R.drawable.ic_arrow_back, contentDescription = stringResource(R.string.back_button))
            }
        }
    ) {
        // What's listed is what Android says, so a page restored before that's known waits for it.
        if (!state.systemStateKnown) return@Page
        val checks = state.checks(appsToStop(state))
        if (state.shizukuStopped && !state.canSetUpInOneTap) ShizukuStopped(actions.onOpenShizuku)
        FixList(checks) { actions.onFixSteps(listOf(it)) }
        val left = checks.filter { it.done == false }.map { it.need }
        if (left.isNotEmpty()) FixAll(state, actions, left)
    }
}

/** Shizuku stops when the phone restarts, unless it's rooted; started again, everything is fixed in one tap. */
@Composable
private fun ShizukuStopped(onOpen: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.fix_shizuku_stopped),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
            )
            FilledTonalButton(onClick = onOpen, modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.open_button)) }
        }
    }
}

/** Fix all, through Shizuku or root, which does it in one tap; without either, or once it couldn't, the steps one by one. */
@Composable
private fun FixAll(state: UiState, actions: ScreenActions, left: List<Need>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val oneTap = state.canSetUpInOneTap && !state.oneTapFailed
        val press = rememberPress()
        Button(
            onClick = if (oneTap) actions.onSetUpInOneTap else { { actions.onFixSteps(left) } },
            enabled = !state.settingUpInOneTap,
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            interactionSource = press.source,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .then(press.modifier)
        ) {
            if (state.settingUpInOneTap) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(ButtonDefaults.IconSize))
            } else {
                AppIcon(if (oneTap) R.drawable.ic_check else R.drawable.ic_open_in_new, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            }
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(stringResource(if (oneTap) R.string.fix_all else R.string.fix_one_by_one), style = MaterialTheme.typography.labelLarge)
        }
        val note = if (state.oneTapFailed) R.string.fix_failed else if (!oneTap) null else if (state.oneTapRoot) R.string.fix_with_root else R.string.fix_with_shizuku
        note?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.oneTapFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/**
 * Before anything's been opened, under the link field: what Android has to allow, each saying what
 * it gets you, and a way to do it all. Once something has been, what's left folds into [FixBar].
 */
@Composable
internal fun FirstAsks(state: UiState, actions: ScreenActions) {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        val checks = state.checks(appsToStop(state))
        FixList(checks, asks = true) { actions.onFixSteps(listOf(it)) }
        val left = checks.filter { it.done == false }.map { it.need }
        if (left.isNotEmpty()) FixAll(state, actions, left)
    }
}

/**
 * Each thing to fix, a red dot until it's done and a check once it is; a tap shows how. What
 * Android can't tell about is never marked. As first [asks], each says what it gets you, and
 * what's left to allow says Allow rather than that it's broken.
 */
@Composable
internal fun FixList(checks: List<Check>, asks: Boolean = false, onOpen: (Need) -> Unit) {
    Group {
        checks.forEachIndexed { index, check ->
            if (index > 0) GroupDivider()
            FixRow(check, asks, onOpen)
        }
    }
}

@Composable
private fun FixRow(check: Check, asks: Boolean, onOpen: (Need) -> Unit) {
    val done = check.done == true
    val doneLabel = stringResource(R.string.setup_done)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !done, onClickLabel = stringResource(R.string.fix_button)) { onOpen(check.need) }
            .semantics { if (done) stateDescription = doneLabel }
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NeedIcon(check.need)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(needTitle(check.need), style = MaterialTheme.typography.bodyLarge)
            check.need.gets().takeIf { asks }?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AnimatedContent(targetState = check.done, transitionSpec = { fade() }, label = "check") { shown ->
            Box(modifier = Modifier.heightIn(min = 24.dp).widthIn(min = 24.dp), contentAlignment = Alignment.Center) {
                if (shown == true) {
                    AppIcon(R.drawable.ic_check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                } else if (shown == false && asks && check.need.gets() != null) {
                    Text(stringResource(R.string.allow_button), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                } else if (shown == false) {
                    Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.error, CircleShape))
                }
            }
        }
    }
}

/** What [need] is about: the app in the way, Crosstune, or what lyrics do with it. */
@Composable
private fun NeedIcon(need: Need, size: Dp = 32.dp) {
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        if (need is Need.StopApp) {
            PackageIcon(need.app.packageName, size)
        } else if (need == Need.Links) {
            AppLogo(size = size * 0.85f)
        } else {
            AppIcon(need.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** What allowing it gets you, said where it's first asked; an app in the way only needs stopping. */
@StringRes
private fun Need.gets(): Int? = when (this) {
    Need.Links -> R.string.ask_links_gets
    Need.Follow -> R.string.ask_follow_gets
    else -> null
}

private fun Need.icon(): Int = when (this) {
    Need.Microphone -> R.drawable.ic_recognize
    Need.Float -> R.drawable.ic_float
    else -> R.drawable.ic_lyrics
}

@StringRes
private fun Need.title(): Int = when (this) {
    is Need.StopApp -> R.string.setup_check_app
    Need.Links -> R.string.setup_check_allow
    Need.Microphone -> R.string.fix_mic_title
    Need.Follow -> R.string.follow_help_title
    Need.Float -> R.string.floating_permission_title
}

@Composable
internal fun needTitle(need: Need): String = stringResource(need.title(), (need as? Need.StopApp)?.app?.label.orEmpty())

/**
 * One thing to fix at a time, its steps with pictures of Android's pages on this kind of phone and a
 * button to the page; once Android says it's done, it's checked off and the next one shows by itself.
 */
@Composable
internal fun FixStepPage(state: UiState, actions: ScreenActions) {
    val fixes = state.fixes ?: return
    val need = fixes.need ?: return
    SwipedBack(onBack = actions.onFixBack)
    val done = state.done(need) == true
    LaunchedEffect(need, done) {
        if (!done) return@LaunchedEffect
        // Long enough to see it checked off.
        delay(FIX_DONE_PAUSE_MS)
        actions.onNextFix()
    }
    Page(
        title = "",
        navigationIcon = {
            IconButton(onClick = actions.onFixBack) {
                AppIcon(R.drawable.ic_close, contentDescription = stringResource(R.string.back_button))
            }
        },
        bottomBar = { StepButtons(need, state, actions) }
    ) {
        AnimatedContent(targetState = need, transitionSpec = { slide(forward = true) }, label = "fix") { shown ->
            Column {
                // A single thing needs no counting.
                if (fixes.steps.size > 1) StepProgress(fixes.steps.indexOf(shown) + 1, fixes.steps.size)
                Row(modifier = Modifier.padding(top = 24.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AnimatedContent(targetState = state.done(shown) == true, transitionSpec = { fade() }, label = "done") { checked ->
                        if (checked) {
                            AppIcon(
                                R.drawable.ic_check,
                                contentDescription = stringResource(R.string.setup_done),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                        } else {
                            NeedIcon(shown)
                        }
                    }
                    Text(needTitle(shown), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 12.dp))
                }
                if (fixes.failed) {
                    Text(
                        stringResource(R.string.fix_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
                val steps = fixSteps(shown, state, actions)
                steps.forEachIndexed { index, step ->
                    NumberedStep(index + 1, step.text)
                    step.button?.let { (label, onClick) ->
                        FilledTonalButton(onClick = onClick, modifier = Modifier.padding(start = 44.dp, bottom = 4.dp)) { Text(label) }
                    }
                    step.picture?.let { picture ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 44.dp, top = 4.dp, bottom = 16.dp)
                        ) { picture() }
                    }
                }
            }
        }
    }
}

/** The way to Android's page; before Android 12, a test link; and skipping it. */
@Composable
private fun StepButtons(need: Need, state: UiState, actions: ScreenActions) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val buttonModifier = Modifier
            .widthIn(max = 400.dp)
            .fillMaxWidth()
            .heightIn(min = 52.dp)
        val press = rememberPress()
        Button(
            onClick = { actions.onOpenFix(need) },
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            interactionSource = press.source,
            modifier = buttonModifier.then(press.modifier)
        ) {
            AppIcon(R.drawable.ic_open_in_new, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(
                if (need is Need.StopApp) {
                    stringResource(R.string.open_app_link_settings_button, need.app.label)
                } else {
                    stringResource(R.string.fix_open_settings)
                },
                style = MaterialTheme.typography.labelLarge
            )
        }
        if (state.tests(need)) {
            FilledTonalButton(onClick = { actions.onTryLink(need) }, modifier = buttonModifier) {
                Text(stringResource(R.string.fix_try_link), style = MaterialTheme.typography.labelLarge)
            }
        }
        TextButton(onClick = actions.onNextFix) { Text(stringResource(R.string.setup_skip_for_now)) }
    }
}

/** One step: what to do, Android's own words in bold, a picture of it on this kind of phone, and a button to another page when it's done there. */
internal class FixStep(val text: AnnotatedString, val picture: (@Composable () -> Unit)? = null, val button: Pair<String, () -> Unit>? = null)

/** [template] with [words], Android's own, filled in and in bold, so they stand out as what to look for. */
@Composable
private fun bold(@StringRes template: Int, vararg words: String): AnnotatedString {
    val text = stringResource(template, *words)
    return buildAnnotatedString {
        append(text)
        words.forEach { word ->
            val at = text.indexOf(word)
            if (at >= 0) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), at, at + word.length)
        }
    }
}

/**
 * The steps for [need], in Android's words, with a picture of each where this kind of phone has
 * them; on other phones, and Android's older pages, words only.
 */
@Composable
internal fun fixSteps(need: Need, state: UiState, actions: ScreenActions): List<FixStep> {
    val phone = state.phone
    val tap = R.string.fix_step_tap
    val pick = R.string.fix_step_pick
    val turnOn = R.string.fix_step_turn_on
    // Only turned down for good does the microphone have steps: while Android still asks, it's asked.
    if (need == Need.Microphone) {
        val newer = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val permissions = stringResource(if (phone == Phone.XIAOMI) R.string.android_app_permissions else R.string.android_permissions)
        val microphone = stringResource(R.string.android_microphone)
        if (!newer) return listOf(inWords(tap, permissions), inWords(tap, microphone), inWords(pick, stringResource(R.string.allow_button)))
        return listOf(
            phone.step(tap, permissions) { AndroidPictures.PermissionsRow(phone) },
            phone.step(tap, microphone) { AndroidPictures.MicrophoneRow() },
            phone.step(pick, stringResource(R.string.android_allow_while_using)) { AndroidPictures.MicrophoneChoices() }
        )
    }
    if (need == Need.Follow) {
        // Samsung and TVs list every app, each with its switch, rather than open Crosstune's own page.
        val access = stringResource(if (phone == Phone.SAMSUNG || phone == Phone.TV) R.string.app_name else R.string.android_allow_notification_access)
        val allow = stringResource(R.string.allow_button)
        if (!state.followRestricted) {
            // Google TV's page, opened straight away, can leave the remote stuck outside its list, as on
            // the Chromecast, so a TV is taken to its list of apps and goes from there. Its switch asks nothing more.
            if (phone == Phone.TV) {
                return listOf(
                    inWords(R.string.fix_step_pick, stringResource(R.string.android_special_app_access)),
                    inWords(R.string.fix_step_pick, stringResource(R.string.android_notification_access)),
                    inWords(turnOn, access)
                )
            }
            return listOf(phone.step(turnOn, access) { AndroidPictures.AccessSwitch(phone) }, phone.step(tap, allow) { AndroidPictures.AllowDialog() })
        }
        // Android turns the first try down for an app installed from a file, and only then offers
        // to allow restricted settings, in App info, after which the access turns on.
        return listOf(
            phone.step(R.string.fix_step_try_then, access, stringResource(R.string.android_close)) {
                Column {
                    AndroidPictures.AccessSwitch(phone)
                    PictureGap()
                    AndroidPictures.DeniedDialog()
                }
            },
            phone.step(R.string.fix_step_menu, stringResource(R.string.android_allow_restricted), button = stringResource(R.string.follow_help_app_info) to actions.onOpenAppInfo) {
                AndroidPictures.RestrictedMenu()
            },
            phone.step(R.string.fix_step_turn_on_then, access, allow) {
                Column {
                    AndroidPictures.AccessSwitch(phone)
                    PictureGap()
                    AndroidPictures.AllowDialog()
                }
            }
        )
    }
    if (need == Need.Float) {
        val overApps = stringResource(R.string.android_display_over_apps)
        // Before 11 Android opens Crosstune's own page; from 11, the list of apps, which Samsung and TVs switch right there.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return listOf(phone.step(turnOn, overApps) { AndroidPictures.OverlaySwitch() })
        val crosstune = stringResource(R.string.app_name)
        if (phone == Phone.SAMSUNG || phone == Phone.TV) return listOf(phone.step(turnOn, crosstune) { AndroidPictures.OverlayRow(phone) })
        return listOf(phone.step(tap, crosstune) { AndroidPictures.OverlayRow(phone) }, phone.step(turnOn, overApps) { AndroidPictures.OverlaySwitch() })
    }
    // Links, Crosstune's or another app's. Before Android 12 they're one choice in App info, which a test link checks.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        val choice = stringResource(if (need is Need.StopApp) R.string.android_dont_open_in_this_app else R.string.android_open_in_this_app)
        return listOf(
            inWords(tap, stringResource(R.string.android_open_by_default)),
            inWords(R.string.fix_step_set, stringResource(R.string.android_open_supported_links), choice),
            inWords(tap, stringResource(R.string.fix_try_link))
        )
    }
    val supported = stringResource(R.string.android_open_supported_links)
    if (need is Need.StopApp) {
        if (phone == Phone.SAMSUNG) return listOf(phone.step(R.string.fix_step_turn_off, supported) { AndroidPictures.SamsungLinks(switchOn = true, ringSwitch = true) })
        return listOf(phone.step(pick, stringResource(R.string.android_in_your_browser)) { AndroidPictures.InTheAppOrBrowser(phone, inTheApp = false) })
    }
    val hosts = state.hostsToTick()
    return buildList {
        // Set to open in the browser, Android hides the links to tick, so switching back comes first.
        if (state.ownLinksOff && phone == Phone.SAMSUNG) {
            add(phone.step(turnOn, supported) { AndroidPictures.SamsungLinks(switchOn = false, ringSwitch = true) })
        } else if (state.ownLinksOff) {
            add(phone.step(pick, stringResource(R.string.android_in_the_app)) { AndroidPictures.InTheAppOrBrowser(phone, inTheApp = true) })
        }
        if (phone == Phone.SAMSUNG) {
            add(phone.step(tap, stringResource(R.string.android_supported_web_addresses)) { AndroidPictures.SamsungLinks(switchOn = true, ringSwitch = false) })
            add(phone.step(R.string.fix_step_switches) { AndroidPictures.TickLinks(phone, hosts) })
        } else if (phone == Phone.XIAOMI) {
            add(phone.step(R.string.fix_step_tick_under, stringResource(R.string.android_links_to_open)) { AndroidPictures.TickLinks(phone, hosts) })
        } else {
            add(phone.step(tap, stringResource(R.string.android_add_link)) { AndroidPictures.AddLink() })
            add(phone.step(R.string.fix_step_tick_then, stringResource(R.string.android_add)) { AndroidPictures.TickLinks(phone, hosts) })
        }
    }
}

/** A step with its [picture], which shows only on the phones there are pictures for. */
@Composable
private fun Phone.step(@StringRes template: Int, vararg words: String, button: Pair<String, () -> Unit>? = null, picture: @Composable () -> Unit): FixStep =
    FixStep(bold(template, *words), picture.takeIf { this != Phone.OTHER && this != Phone.TV }, button)

/** A step in words alone, for a page of Android's that looks different on every phone. */
@Composable
private fun inWords(@StringRes template: Int, vararg words: String): FixStep = FixStep(bold(template, *words))

/** The links Android doesn't let Crosstune open yet, or, set to open in the browser with all allowed, every one. */
private fun UiState.hostsToTick(): List<String> =
    intercepted.flatMap { unapprovedHosts?.get(it).orEmpty() }
        .ifEmpty { intercepted.flatMap { LinkInterception.HOSTS[it].orEmpty() } }

/** A step's number beside what to do. */
@Composable
internal fun NumberedStep(number: Int, text: AnnotatedString) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(28.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(number.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
    }
}

/**
 * How [need] is done, small, floating over Android's page while the user is there: each of its
 * steps in turn, with its picture.
 */
@Composable
internal fun FloatingGuide(need: Need, state: UiState) {
    val steps = fixSteps(need, state, ScreenActions())
    var shown by remember(need) { mutableIntStateOf(0) }
    LaunchedEffect(need, steps.size) {
        while (steps.size > 1) {
            delay(GUIDE_STEP_MS)
            shown = (shown + 1) % steps.size
        }
    }
    val step = steps[shown % steps.size]
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        if (state.done(need) == true) {
            // Newer Android won't let Crosstune come back from a floating window by itself, but
            // making it big brings it back.
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically), modifier = Modifier.padding(12.dp)) {
                AppIcon(R.drawable.ic_check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
                Text(stringResource(R.string.practice_back_to, stringResource(R.string.app_name)), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
            return@Surface
        }
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppLogo(size = 18.dp)
                Spacer(Modifier.weight(1f))
                if (steps.size > 1) {
                    Text("${shown + 1}/${steps.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(step.text, style = MaterialTheme.typography.titleMedium)
            step.picture?.invoke()
        }
    }
}
